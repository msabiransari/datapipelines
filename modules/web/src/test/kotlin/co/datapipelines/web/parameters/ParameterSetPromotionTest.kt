package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetExport
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetReader
import co.datapipelines.parameters.ParameterSetRecord
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.ParameterSetService
import co.datapipelines.parameters.ParameterSetVersionDetail
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The promotion receive's bind over a REAL batch entry (#299) and its two-act receive (#302, C36):
 * the entry is built exactly as the sender's `payloadOf` emits it — `ParameterSetResponses.full`
 * plus `version`, `body_hash`, `released_at` — `bind` hands the validator a bound entry, `validate`
 * runs it through the receive validation OUTSIDE the transaction, and `land` hands the CANONICAL
 * body to `importValidated` inside it. Before #299 the whole node was bound as `ParameterSetBody`
 * under `FAIL_ON_UNKNOWN_PROPERTIES`, so every real batch carrying a set rolled back whole.
 */
class ParameterSetPromotionTest {
    private val sets = mockk<ParameterSetService>()
    private val repository = mockk<ParameterSetRepository>()
    private val templates = mockk<co.datapipelines.templates.TemplateRepository>()
    private val receiveValidation = mockk<ParameterSetReceiveValidation>()

    private val promotion = ParameterSetPromotion(repository, sets, templates, receiveValidation)

    private val workspaceId = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val setId = UUID.randomUUID()
    private val now = Instant.parse("2026-09-28T00:00:00Z")

    private val document = ParameterSetReader().readOrThrow(ParameterSetJson.mapper.readTree(DOCUMENT))
    private val record =
        ParameterSetRecord(
            id = setId,
            workspaceId = workspaceId,
            name = SET_NAME,
            displayName = "Region filters",
            description = "promotion receive",
            currentVersion = 1,
            createdAt = now,
            updatedAt = now,
            createdBy = actor,
        )
    private val detail =
        ParameterSetVersionDetail(
            parameterSetId = setId,
            version = 1,
            status = PipelineVersionStatus.RELEASED,
            bodyHash = BODY_HASH,
            createdAt = now,
            createdBy = actor,
            releasedAt = now,
        )

    private fun entry(): ObjectNode {
        val node = ParameterSetResponses.full(record, document.body, detail) as ObjectNode
        node.put("version", 1)
        node.put("body_hash", BODY_HASH)
        node.put("released_at", now.toString())
        return node
    }

    @Test
    fun `a real batch entry binds - the lifecycle fields and the body ride into validation`() {
        val bound = promotion.bind(entry())

        assertAll(
            { bound.id shouldBe setId },
            { bound.name shouldBe SET_NAME },
            { bound.version shouldBe 1 },
            { bound.bodyHash shouldBe BODY_HASH },
            { bound.releasedAt shouldBe now },
            { bound.body.displayName shouldBe "Region filters" },
            { bound.body.parameters.size shouldBe 2 },
            { bound.body.parameters[1].source?.template?.id shouldBe TEMPLATE_ID },
        )
        verify { receiveValidation wasNot Called }
    }

    @Test
    fun `validate hands the bound entries to the receive validation - land hands the canonical body to importValidated`() {
        val bound = promotion.bind(entry())
        val canonical = bound.body
        val validated = ParameterSetPromotion.Validated(bound, canonical)
        var askedTemplates: List<JsonNode>? = null
        every { receiveValidation.validate(workspaceId, any(), listOf(bound)) } answers {
            askedTemplates = secondArg()
            listOf(validated)
        }
        var received: ParameterSetExport? = null
        every { sets.importValidated(workspaceId, any(), actor) } answers {
            received = secondArg()
            mockk()
        }

        promotion.validate(listOf(entry()), TEMPLATES, workspaceId)
        promotion.land(validated, workspaceId, actor)

        assertAll(
            { askedTemplates shouldBe TEMPLATES },
            { received!!.id shouldBe setId },
            { received!!.name shouldBe SET_NAME },
            { received!!.version shouldBe 1 },
            { received!!.bodyHash shouldBe BODY_HASH },
            { received!!.releasedAt shouldBe now },
            { received!!.body shouldBe canonical },
        )
    }

    @Test
    fun `a shape refusal flows through validate - the receive's own entry point refuses before any port is touched`() {
        val malformed = entry()
        malformed.set<JsonNode>("parameterz", ParameterSetJson.mapper.createArrayNode())

        val refusal = shouldThrow<ApiException> { promotion.validate(listOf(malformed), TEMPLATES, workspaceId) }

        assertAll(
            { refusal.code shouldBe ParameterErrorCodes.BODY_INVALID },
            { verify { receiveValidation wasNot Called } },
        )
    }

    @Test
    fun `a typo in the batch entry refuses body_invalid - never a 500`() {
        val malformed = entry()
        malformed.set<JsonNode>("parameterz", ParameterSetJson.mapper.createArrayNode())

        val refusal = shouldThrow<ApiException> { promotion.bind(malformed) }

        refusal.code shouldBe ParameterErrorCodes.BODY_INVALID
    }

    @Test
    fun `a batch entry with no id refuses body_invalid - never the uncatalogued 500`() {
        val malformed = entry()
        malformed.remove("id")

        val refusal = shouldThrow<ApiException> { promotion.bind(malformed) }

        assertAll(
            { refusal.code shouldBe ParameterErrorCodes.BODY_INVALID },
            { refusal.details["path"] shouldBe "id" },
            { refusal.details["reason"] shouldBe "missing" },
        )
    }

    @Test
    fun `a batch entry whose id is not a UUID refuses body_invalid wrong_type`() {
        val malformed = entry()
        malformed.put("id", "not-a-uuid")

        val refusal = shouldThrow<ApiException> { promotion.bind(malformed) }

        assertAll(
            { refusal.code shouldBe ParameterErrorCodes.BODY_INVALID },
            { refusal.details["path"] shouldBe "id" },
            { refusal.details["reason"] shouldBe "wrong_type" },
        )
    }

    @Test
    fun `a batch entry with a blank name refuses body_invalid - the name is never an empty string`() {
        val malformed = entry()
        malformed.put("name", " ")

        val refusal = shouldThrow<ApiException> { promotion.bind(malformed) }

        assertAll(
            { refusal.code shouldBe ParameterErrorCodes.BODY_INVALID },
            { refusal.details["path"] shouldBe "name" },
            { refusal.details["reason"] shouldBe "missing" },
        )
    }

    private companion object {
        const val SET_NAME = "acme/sales/region_filters"
        const val TEMPLATE_ID = "acme/sales/states_of_country.sql"
        const val BODY_HASH = "b3a6e0dad1f5b9f0e2a1c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a"

        /** The batch's template closure — passed to the receive validation as sent. */
        val TEMPLATES: List<JsonNode> =
            listOf(
                ParameterSetJson.mapper
                    .createObjectNode()
                    .put("id", TEMPLATE_ID)
                    .put("version", 1),
            )

        /** A real document the reader accepts: constants country, template-backed state. */
        val DOCUMENT =
            """
            {
              "name": "$SET_NAME",
              "display_name": "Region filters",
              "description": "promotion receive",
              "parameters": [
                {
                  "name": "country", "label": "Country", "type": "STRING", "kind": "SELECT",
                  "cardinality": "MULTI", "required": true,
                  "source": {"constants": [{"value": "US", "display_value": "United States"}]}
                },
                {
                  "name": "state", "label": "State", "type": "STRING", "kind": "SELECT", "required": true,
                  "source": {"template": {"id": "$TEMPLATE_ID", "version": 1}, "datasource": "transfer-e2e"},
                  "depends_on": ["country"]
                }
              ]
            }
            """.trimIndent()
    }
}
