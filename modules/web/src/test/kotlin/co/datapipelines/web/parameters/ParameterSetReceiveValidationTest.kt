package co.datapipelines.web.parameters

import co.datapipelines.parameters.ParameterErrorCodes
import co.datapipelines.parameters.ParameterSetJson
import co.datapipelines.parameters.ParameterSetReader
import co.datapipelines.parameters.ParameterSetValidationException
import co.datapipelines.parameters.ParametersConfig
import co.datapipelines.parameters.SelectorProbe
import co.datapipelines.parameters.SelectorProbeOutcome
import co.datapipelines.pipeline.DatasourceFacts
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.templates.LibraryResolver
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidationException
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.templates.WorkspaceTemplateEngines
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.util.UUID

/**
 * The receive validation's batch template view (#302, C36) over REAL §4 steps: a template-backed
 * set whose pin rides the batch must validate from the PAYLOAD — the stored registry holds nothing
 * here, so a valid answer proves the payload-backed engine rendered step 5's selector — and a pin
 * neither the batch brings nor the receiver holds refuses the §8.3 code. The probe is scripted:
 * its execution (step 6) is pin-independent and must run against the TARGET workspace as an
 * argument, OUTSIDE any transaction.
 */
class ParameterSetReceiveValidationTest {
    private val repository = mockk<TemplateRepository>()
    private val renderer = mockk<TemplateDryRenderer>()
    private val statuses = mockk<TemplateVersionStatuses>()
    private val probe = mockk<SelectorProbe>()

    private val workspaceId = UUID.randomUUID()

    private val engines = WorkspaceTemplateEngines(repository, 16, 5_000, 64L * 1024 * 1024)
    private val validation =
        ParameterSetReceiveValidation(
            engines,
            renderer,
            statuses,
            { name, _ -> if (name == DATASOURCE) DatasourceFacts(Dialect.H2) else null },
            probe,
            ParametersConfig(),
            TemplateValidator(LibraryResolver { engines.registryFor(it) }),
        )

    init {
        // The receiver's registry is EMPTY: every valid answer below rides the batch payloads.
        every { repository.lookupVersion(any(), any(), any()) } returns null
        every { repository.existsId(any(), any()) } returns false
    }

    @Test
    fun `a pin the batch brings renders from the payload and probes the receiver's datasource - nothing stored`() {
        val rendered = slot<String>()
        val probedWorkspace = slot<UUID>()
        every { probe.probe(capture(probedWorkspace), DATASOURCE, capture(rendered), any(), any()) } returns
            SELECT_PROBED

        val validated =
            validation.validate(
                workspaceId,
                listOf(templatePayload(SELECT_BODY)),
                listOf(entry(DOCUMENT)),
            )

        val single = validated.single()
        assertAll(
            { validated.size shouldBe 1 },
            { single.bound.name shouldBe SET_NAME },
            { single.canonical.parameters.size shouldBe 2 },
            {
                val state = single.canonical.parameters[1].source
                state?.template?.id shouldBe TEMPLATE_ID
            },
            // Step 5 rendered the PAYLOAD through the engine; step 6 ran it on the TARGET
            // workspace's own datasource — the workspace as an argument, never the principal.
            { probedWorkspace.captured shouldBe workspaceId },
            { rendered.captured shouldBe SELECT_BODY },
        )
        verify { renderer wasNot Called }
        verify { statuses wasNot Called }
    }

    @Test
    fun `a pin neither the batch brings nor the receiver holds refuses import missing_template`() {
        // The overlay delegates the not-found verdict to the receiver's own renderer — its
        // registry is empty, so the pin is TemplateNotFound and the §8.3 lens maps the code.
        every { renderer.lookup(workspaceId, TemplateRef(TEMPLATE_ID, 1)) } returns TemplateLookup.TemplateNotFound

        val refusal =
            shouldThrow<ParameterSetValidationException> {
                validation.validate(workspaceId, emptyList(), listOf(entry(DOCUMENT)))
            }

        val failure = refusal.result.failures.single()
        assertAll(
            { refusal.code shouldBe ParameterErrorCodes.IMPORT_MISSING_TEMPLATE },
            { failure.code shouldBe ParameterErrorCodes.IMPORT_MISSING_TEMPLATE },
        )
        verify { probe wasNot Called }
    }

    @Test
    fun `a payload that interpolates a parent refuses - the payload is scanned, not trusted`() {
        val refusal =
            shouldThrow<ParameterSetValidationException> {
                validation.validate(
                    workspaceId,
                    listOf(templatePayload(INTERPOLATING_BODY)),
                    listOf(entry(DOCUMENT)),
                )
            }

        val interpolated = refusal.result.failures.single()
        interpolated.code shouldBe "template.validation.parameter_interpolated"
    }

    @Test
    fun `a payload with a forbidden construct refuses before the overlay renders it - the import's scan runs first (302 pass F1)`() {
        // The renderer and the probe are STRICT mocks: a render before the refusal throws, which is
        // exactly what today's order did (the pass measured the <#ftl> header's parse-time burn).
        val refusal =
            shouldThrow<TemplateValidationException> {
                validation.validate(
                    workspaceId,
                    listOf(templatePayload("SELECT \${\"1\"?eval} AS n")),
                    listOf(entry(DOCUMENT)),
                )
            }
        refusal.result.failures
            .single()
            .code shouldBe "template.validation.dangerous_construct"
        verify { renderer wasNot Called }
        verify { probe wasNot Called }
    }

    @Test
    fun `a payload over the body cap refuses without being parsed - the cap keeps it away from the parser (302 pass F1)`() {
        val refusal =
            shouldThrow<TemplateValidationException> {
                validation.validate(
                    workspaceId,
                    listOf(templatePayload("SELECT 1 AS n -- " + "x".repeat(262_144))),
                    listOf(entry(DOCUMENT)),
                )
            }
        refusal.result.failures
            .single()
            .details["max_body_chars"] shouldBe 262_144
        verify { renderer wasNot Called }
    }

    @Test
    fun `a pin the receiver already holds validates through the production ports - the delegation arm`() {
        every { renderer.lookup(workspaceId, TemplateRef(TEMPLATE_ID, 1)) } returns TemplateLookup.Found(Dialect.H2)
        every { renderer.interpolatedParameters(workspaceId, TemplateRef(TEMPLATE_ID, 1), any(), any()) } returns emptyList()
        every { renderer.boundParameters(workspaceId, TemplateRef(TEMPLATE_ID, 1)) } returns emptyList()
        every { statuses.statusOf(workspaceId, TEMPLATE_ID, 1) } returns
            co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
        // Step 5's render for a STORED pin goes through the production probe (SelectorRunner's
        // render via the workspace engines) — the delegation arm under test.
        every { probe.render(workspaceId, TemplateRef(TEMPLATE_ID, 1), any()) } returns
            co.datapipelines.parameters
                .SelectorRender
                .Rendered(SELECT_BODY)
        val rendered = slot<String>()
        every { probe.probe(any(), DATASOURCE, capture(rendered), any(), any()) } returns SELECT_PROBED

        val validated =
            validation.validate(
                workspaceId,
                // The batch brings NO templates — the pin resolves on the receiver's registry.
                emptyList(),
                listOf(entry(DOCUMENT)),
            )

        assertAll(
            { validated.size shouldBe 1 },
            { rendered.captured shouldBe SELECT_BODY },
        )
    }

    private companion object {
        const val SET_NAME = "acme/sales/region_filters"
        const val TEMPLATE_ID = "acme/sales/states_of_country.sql"
        const val DATASOURCE = "transfer-e2e"

        /** The rendered selector — no Freemarker left, an ORDER BY, `:country` bound as a bind. */
        const val SELECT_BODY =
            "SELECT state_code AS \"value\", state_name AS \"display_value\", FALSE AS \"is_default\"" +
                " FROM dim_state WHERE country_code IN (:country) ORDER BY state_name"
        const val INTERPOLATING_BODY =
            "SELECT state_code AS \"value\", state_name AS \"display_value\", FALSE AS \"is_default\"" +
                " FROM dim_state WHERE country = '\${country}' ORDER BY state_name"

        val SELECT_PROBED =
            SelectorProbeOutcome.Probed(
                listOf(
                    ColumnSchema("value", LogicalType.STRING),
                    ColumnSchema("display_value", LogicalType.STRING),
                    ColumnSchema("is_default", LogicalType.BOOLEAN),
                ),
                listOf(listOf("NY", "New York", false), listOf("CA", "California", false)),
            )

        /** The batch's §21.4 template entry, exactly the fields the overlay reads. */
        fun templatePayload(body: String) =
            JsonNodeFactory.instance
                .objectNode()
                .put("id", TEMPLATE_ID)
                .put("version", 1)
                .put("type", "sql")
                .put("dialect", "H2")
                .put("display_name", "states")
                .put("description", "the pinned selector")
                .put("body", body)

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
                  "source": {"template": {"id": "$TEMPLATE_ID", "version": 1}, "datasource": "$DATASOURCE"},
                  "depends_on": ["country"]
                }
              ]
            }
            """.trimIndent()

        fun entry(document: String) =
            ParameterSetPromotion
                .Bound(
                    UUID.randomUUID(),
                    SET_NAME,
                    1,
                    "b3a6e0dad1f5b9f0e2a1c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a",
                    null,
                    ParameterSetReader().readOrThrow(ParameterSetJson.mapper.readTree(document)).body,
                )
    }
}
