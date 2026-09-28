package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetExport
import co.datapipelines.parameters.ParameterSetImported
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
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The promotion receive's bind over a REAL batch entry (#299): the entry is built exactly as
 * the sender's `payloadOf` emits it — `ParameterSetResponses.full` plus `version`,
 * `body_hash`, `released_at` — and `apply` must hand the service a bound body. Before #299
 * the whole node was bound as `ParameterSetBody` under `FAIL_ON_UNKNOWN_PROPERTIES`, so every
 * real batch carrying a set rolled back whole.
 */
class ParameterSetPromotionTest {
    private val sets = mockk<ParameterSetService>()
    private val repository = mockk<ParameterSetRepository>()
    private val templates = mockk<co.datapipelines.templates.TemplateRepository>()

    private val promotion = ParameterSetPromotion(repository, sets, templates)

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

    @Test
    fun `a real batch entry binds - the receive of a promotion works`() {
        val entry = ParameterSetResponses.full(record, document.body, detail) as ObjectNode
        entry.put("version", 1)
        entry.put("body_hash", BODY_HASH)
        entry.put("released_at", now.toString())
        var received: ParameterSetExport? = null
        every { sets.import(workspaceId, any(), actor) } answers {
            received = secondArg()
            ParameterSetImported(mockk(), created = true, unchanged = false)
        }

        promotion.apply(entry, workspaceId, actor)

        val export = checkNotNull(received)
        export.id shouldBe setId
        export.name shouldBe SET_NAME
        export.version shouldBe 1
        export.bodyHash shouldBe BODY_HASH
        export.releasedAt shouldBe now
        export.body.displayName shouldBe "Region filters"
        val parameters = export.body.parameters
        parameters.size shouldBe 2
        val state = parameters[1].source
        state?.template?.id shouldBe TEMPLATE_ID
    }

    @Test
    fun `a typo in the batch entry refuses body_invalid - never a 500`() {
        val entry = ParameterSetResponses.full(record, document.body, detail) as ObjectNode
        entry.put("version", 1)
        entry.put("body_hash", BODY_HASH)
        entry.put("released_at", now.toString())
        entry.set<JsonNode>("parameterz", ParameterSetJson.mapper.createArrayNode())
        every { sets.import(any(), any(), any()) } answers { throw IllegalStateException("must not be reached") }

        val refusal = shouldThrow<ApiException> { promotion.apply(entry, workspaceId, actor) }

        refusal.code shouldBe ParameterErrorCodes.BODY_INVALID
    }

    @Test
    fun `a batch entry with no id refuses body_invalid - never the uncatalogued 500`() {
        val entry = ParameterSetResponses.full(record, document.body, detail) as ObjectNode
        entry.remove("id")
        every { sets.import(any(), any(), any()) } answers { throw IllegalStateException("must not be reached") }

        val refusal = shouldThrow<ApiException> { promotion.apply(entry, workspaceId, actor) }

        assertAll(
            { refusal.code shouldBe ParameterErrorCodes.BODY_INVALID },
            { refusal.details["path"] shouldBe "id" },
            { refusal.details["reason"] shouldBe "missing" },
        )
    }

    @Test
    fun `a batch entry whose id is not a UUID refuses body_invalid wrong_type`() {
        val entry = ParameterSetResponses.full(record, document.body, detail) as ObjectNode
        entry.put("id", "not-a-uuid")
        every { sets.import(any(), any(), any()) } answers { throw IllegalStateException("must not be reached") }

        val refusal = shouldThrow<ApiException> { promotion.apply(entry, workspaceId, actor) }

        assertAll(
            { refusal.code shouldBe ParameterErrorCodes.BODY_INVALID },
            { refusal.details["path"] shouldBe "id" },
            { refusal.details["reason"] shouldBe "wrong_type" },
        )
    }

    @Test
    fun `a batch entry with a blank name refuses body_invalid - the name is never an empty string`() {
        val entry = ParameterSetResponses.full(record, document.body, detail) as ObjectNode
        entry.put("name", " ")
        every { sets.import(any(), any(), any()) } answers { throw IllegalStateException("must not be reached") }

        val refusal = shouldThrow<ApiException> { promotion.apply(entry, workspaceId, actor) }

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
