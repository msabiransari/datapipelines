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
 * 7b §A.3(3) — the transfer round trip of a transform template: exported from one workspace,
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
        jdbc.update("DELETE FROM workspaces WHERE id = :id", mapOf("id" to TARGET_WORKSPACE))
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to ACTOR_ID))
    }

    @Test
    fun `the blocks survive export-import and the hash guard passes`() {
        val source = repository
        val contract =
            TransformContract(
                mode = TransformMode.ROW,
                inputs =
                    mapOf(
                        "orders" to TransformInput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
                        "tz" to TransformInput.Value(LogicalType.STRING),
                    ),
                output = TransformOutput.Table(listOf(ContractColumn("order_id", LogicalType.INTEGER))),
            )
        val cases =
            listOf(
                TransformTestCase(
                    name = "empty",
                    input = TransformTestInput(rows = emptyList(), inputs = mapOf("tz" to "UTC")),
                    expect = TransformTestExpect(output = TransformBlocks.mapper.readTree("""{"rows": []}""")),
                ),
            )
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
        val exported = source.create(SOURCE_WORKSPACE, draft, ACTOR_ID, CreateLifecycle.RELEASED, WriteSurface.SESSION)
        exported.bodyHash.isNotBlank() shouldBe true

        // The wire crossing: the DTO out through Jackson, back in through the deserializer.
        val wire = TemplateJson.objectMapper().writeValueAsString(exported)
        val outcome = TemplateDeserializer().read(wire)
        val imported = (outcome as TemplateDeserializationOutcome.Parsed).draft

        assertSoftly {
            imported.type shouldBe TemplateType.JSONATA
            imported.engine shouldBe Template.NONE_ENGINE
            imported.contract shouldBe contract
            imported.invariants shouldBe emptyList()
            imported.tests?.single()?.name shouldBe "empty"
        }

        // The receiver's guard (versioning §9.2): the recompute over the imported fields —
        // blocks included — reproduces the declared hash.
        val recomputed =
            repository.computeBodyHash(
                engine = imported.engine,
                dialect = imported.dialect?.wire,
                isLibrary = imported.isLibrary,
                importsJson = TemplateJson.writeImports(imported.imports),
                body = imported.body,
                contractJson = TransformBlocks.writeContract(imported.contract),
                invariantsJson = TransformBlocks.writeInvariants(imported.invariants),
                testsJson = TransformBlocks.writeTests(imported.tests),
            )
        recomputed shouldBe exported.bodyHash

        // And the import itself lands the blocks in the scratch workspace (the version-less
        // path, the one the seeders and promotion share).
        val stored =
            repository.create(TARGET_WORKSPACE, imported.copy(id = "test/xform_roundtrip.jsonata"), ACTOR_ID, CreateLifecycle.RELEASED, WriteSurface.SESSION)
        stored.contract shouldBe contract
        stored.bodyHash shouldBe exported.bodyHash
        stored.bodyHash shouldNotBe null
    }

    private companion object {
        val ACTOR_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
        val SOURCE_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val TARGET_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000002")
    }
}
