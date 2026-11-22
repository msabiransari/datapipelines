package co.datapipelines.templates

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * 7b §A.3(3) — the transfer round trip of a script template: exported from one workspace,
 * imported into a scratch workspace, the three blocks survive the wire and the import hash
 * guard passes.
 *
 * The template-level shape is what exists to test: a pipeline PINNING a jsonata template
 * cannot be authored until 7c's node rules land (`template_type_mismatch` still refuses it),
 * so this test drives the template half of the bundle — the [Template] DTO serialized exactly
 * as `PipelineTransferController` serializes it (the keys are the DTO's pinned annotations),
 * through [TemplateDeserializer] into the import draft, with the receiver's
 * `computeBodyHash` recompute as the guard. The full bundle walk is 7c's §10.9.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateTransferRoundTripIntegrationTest {
    private val jdbc =
        NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    private val repository = TemplateRepository(jdbc)

    @org.junit.jupiter.api.BeforeAll
    fun seedActorAndTargetWorkspace() {
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'roundtrip@example.com', 'RT', 'google', 'sub-rt') ON CONFLICT (id) DO NOTHING",
            mapOf("id" to ACTOR_ID),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name) VALUES (:id, 'rt-target', 'RT Target') ON CONFLICT (id) DO NOTHING",
            mapOf("id" to TARGET_WORKSPACE),
        )
    }

    @org.junit.jupiter.api.AfterAll
    fun removeProbeRows() {
        jdbc.update(
            "DELETE FROM template_versions v USING templates t WHERE t.id = v.template_id AND t.name = :name",
            mapOf("name" to "test/xform_roundtrip.jsonata"),
        )
        jdbc.update("DELETE FROM templates WHERE name = :name", mapOf("name" to "test/xform_roundtrip.jsonata"))
        jdbc.update(
            "DELETE FROM template_versions v USING templates t WHERE t.id = v.template_id AND t.name = :name",
            mapOf("name" to BODY_TEMPLATE),
        )
        jdbc.update("DELETE FROM templates WHERE name = :name", mapOf("name" to BODY_TEMPLATE))
        jdbc.update("DELETE FROM workspaces WHERE id = :id", mapOf("id" to TARGET_WORKSPACE))
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to ACTOR_ID))
    }

    private val contract =
        ScriptContract(
            mode = ScriptMode.ROW,
            inputs =
                mapOf(
                    "orders" to ScriptInput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
                    "tz" to ScriptInput.Value(LogicalType.STRING),
                ),
            output = ScriptOutput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
        )

    private val cases =
        listOf(
            ScriptTestCase(
                name = "empty",
                input = ScriptTestInput(rows = emptyList(), inputs = mapOf("tz" to "UTC")),
                expect = ScriptTestExpect(output = ScriptBlocks.mapper.readTree("""{"rows": []}""")),
            ),
        )

    /** The exported released template and its import-bound draft, built ONCE for the class:
     *  the create inserts a fixed name into the source workspace, so a second build collides. */
    private val roundTrip: Pair<Template, TemplateDraft> by lazy { exportAndRebind() }

    private fun exportAndRebind(): Pair<Template, TemplateDraft> {
        val draft =
            TemplateFixtures.draft(
                id = "test/xform_roundtrip.jsonata",
                type = TemplateType.JSONATA,
                engine = Template.NONE_ENGINE,
                dialect = null,
                body = "{ \"rows\": rows }",
                contract = contract,
                invariants = emptyList(),
                tests = cases,
            )
        val exported = repository.create(SOURCE_WORKSPACE, draft, ACTOR_ID, CreateLifecycle.RELEASED, WriteSurface.SESSION)
        exported.bodyHash.isNotBlank() shouldBe true
        val wire = TemplateJson.objectMapper().writeValueAsString(exported)
        val imported = (TemplateDeserializer().read(wire) as TemplateDeserializationOutcome.Parsed).draft
        return exported to imported
    }

    @Test
    fun `the blocks survive the wire crossing`() {
        val (exported, imported) = roundTrip

        assertSoftly {
            imported.type shouldBe TemplateType.JSONATA
            imported.engine shouldBe Template.NONE_ENGINE
            imported.contract shouldBe contract
            imported.invariants shouldBe emptyList()
            imported.tests?.single()?.name shouldBe "empty"
        }
    }

    @Test
    fun `the import hash guard recomputes over the blocks`() {
        val (exported, imported) = roundTrip
        // The receiver's guard (versioning §9.2): the recompute over the imported fields —
        // blocks included — reproduces the declared hash.
        val recomputed =
            repository.computeBodyHash(
                engine = imported.engine,
                dialect = imported.dialect?.wire,
                isLibrary = imported.isLibrary,
                importsJson = TemplateJson.writeImports(imported.imports),
                body = imported.body,
                contractJson = ScriptBlocks.writeContract(imported.contract),
                invariantsJson = ScriptBlocks.writeInvariants(imported.invariants),
                testsJson = ScriptBlocks.writeTests(imported.tests, imported.contract),
            )
        recomputed shouldBe exported.bodyHash

        // And the import itself lands the blocks in the scratch workspace (the version-less
        // path, the one the seeders and promotion share).
        val stored =
            repository.create(
                TARGET_WORKSPACE,
                imported.copy(id = "test/xform_roundtrip.jsonata"),
                ACTOR_ID,
                CreateLifecycle.RELEASED,
                WriteSurface.SESSION,
            )
        stored.contract shouldBe contract
        stored.bodyHash shouldBe exported.bodyHash
        stored.bodyHash shouldNotBe null
    }

    @Test
    fun `a body fixture's repeated members, member order and decimals cross the wire and pass the import hash guard`() {
        val draft =
            (TemplateDeserializer().read(bodyFixturePayload) as TemplateDeserializationOutcome.Parsed).draft
        val exported = repository.create(SOURCE_WORKSPACE, draft, ACTOR_ID, CreateLifecycle.RELEASED, WriteSurface.SESSION)
        val wire = TemplateJson.objectMapper().writeValueAsString(exported)
        val imported = (TemplateDeserializer().read(wire) as TemplateDeserializationOutcome.Parsed).draft

        // The explicit metadata of what crossed, not map equality: every occurrence, in the author's order.
        fun inputsOf(name: String) =
            imported.tests!!
                .single { it.name == name }
                .input.inputs as FixtureInputs
        inputsOf("order").exactText("order") shouldBe BODY_ORDER_TEXT
        inputsOf("nested").exactText("order") shouldBe """{"a":1,"a":2}"""
        inputsOf("twice").occurrences("order") shouldBe listOf("""{"a":1}""", """{"a":2}""")

        val recomputed =
            repository.computeBodyHash(
                engine = imported.engine,
                dialect = imported.dialect?.wire,
                isLibrary = imported.isLibrary,
                importsJson = TemplateJson.writeImports(imported.imports),
                body = imported.body,
                contractJson = ScriptBlocks.writeContract(imported.contract),
                invariantsJson = ScriptBlocks.writeInvariants(imported.invariants),
                testsJson = ScriptBlocks.writeTests(imported.tests, imported.contract),
            )
        recomputed shouldBe exported.bodyHash

        // The stored column read back is the same fixture: the stored and re-read tests carry the same metadata.
        val reread = repository.findVersion(SOURCE_WORKSPACE, BODY_TEMPLATE, exported.version)!!.tests!!
        (reread.single { it.name == "twice" }.input.inputs as FixtureInputs).occurrences("order") shouldBe
            listOf("""{"a":1}""", """{"a":2}""")
        (reread.single { it.name == "order" }.input.inputs as FixtureInputs).exactText("order") shouldBe BODY_ORDER_TEXT
    }

    private companion object {
        const val BODY_TEMPLATE = "test/xform_body_roundtrip.jsonata"
        const val BODY_ORDER_TEXT = """{"zeta":0.123456789012345678901234567890,"alpha":[3,2,1],"mid":{"z":1,"b":2.50}}"""

        val bodyFixturePayload =
            """
            {"id":"$BODY_TEMPLATE","type":"jsonata","display_name":"X","description":"Y",
             "body":"${'$'}join(${'$'}keys(inputs.order), ',')",
             "contract":{"mode":"value","inputs":{"order":{"kind":"body"}},"output":{"kind":"value","type":"STRING"}},
             "invariants":[],
             "tests":[
               {"name":"order","input":{"inputs":{"order":$BODY_ORDER_TEXT}},"expect":{"output":"zeta,alpha,mid"}},
               {"name":"nested","input":{"inputs":{"order":{"a":1,"a":2}}},
                "expect":{"refusal":"pipeline.script.input_contract_violation"}},
               {"name":"twice","input":{"inputs":{"order":{"a":1},"order":{"a":2}}},
                "expect":{"refusal":"pipeline.script.input_contract_violation"}}
             ]}
            """.trimIndent()

        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val SOURCE_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val TARGET_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000002")
    }
}
