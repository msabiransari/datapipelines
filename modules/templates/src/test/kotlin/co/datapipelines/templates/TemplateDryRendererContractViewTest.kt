package co.datapipelines.templates

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TransformContractView
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * [TemplateDryRendererImpl.transformContract] — the one mapping from 7b's contract model to
 * pipeline-contract's §12.13 [TransformContractView] (7c). Every arm of the mapping is walked
 * once: the three modes, both input shapes, the three output shapes, and `rejects`; a SQL template
 * and a missing version answer null rather than a view.
 */
class TemplateDryRendererContractViewTest {
    private val rowContract =
        TransformContract(
            mode = TransformMode.ROW,
            inputs =
                mapOf(
                    "rows" to TransformInput.Table(listOf(ContractColumn("a", LogicalType.STRING, nullable = false))),
                    "k" to TransformInput.Value(LogicalType.INTEGER),
                ),
            output = TransformOutput.Table(listOf(ContractColumn("b", LogicalType.DECIMAL, nullable = true))),
        )
    private val valueContract =
        TransformContract(
            mode = TransformMode.VALUE,
            inputs = emptyMap(),
            output = TransformOutput.Value(LogicalType.STRING),
        )
    private val objContract =
        TransformContract(
            mode = TransformMode.TABLE,
            inputs = emptyMap(),
            output = TransformOutput.Obj(),
            rejects = true,
        )
    private val registry =
        InMemoryTemplateRegistry(
            listOf(
                TemplateFixtures.version("test/fetch.sql", version = 1, dialect = Dialect.MYSQL, body = "SELECT 1"),
                transform("test/row.jsonata", rowContract),
                transform("test/value.jsonata", valueContract),
                transform("test/obj.jsonata", objContract),
            ),
        )
    private val engine = TemplateEngine(registry, 10, 5_000, 1_000_000)
    private val workspaceId = java.util.UUID.randomUUID()
    private val engines =
        mockk<WorkspaceTemplateEngines> {
            every { registryFor(any()) } returns registry
            every { engineFor(any()) } returns engine
        }
    private val dryRenderer = TemplateDryRendererImpl(engines)

    @AfterEach
    fun tearDown() = engine.close()

    @Test
    fun `a SQL template has no transform contract`() {
        dryRenderer.transformContract(workspaceId, TemplateRef("test/fetch.sql", 1)).shouldBeNull()
    }

    @Test
    fun `a missing version has no transform contract`() {
        dryRenderer.transformContract(workspaceId, TemplateRef("test/row.jsonata", 2)).shouldBeNull()
        dryRenderer.transformContract(workspaceId, TemplateRef("test/absent.jsonata", 1)).shouldBeNull()
    }

    @Test
    fun `row mode maps its table and value inputs and its table output column by column`() {
        dryRenderer.transformContract(workspaceId, TemplateRef("test/row.jsonata", 1)) shouldBe
            TransformContractView(
                mode = TransformContractView.Mode.ROW,
                inputs =
                    mapOf(
                        "rows" to
                            TransformContractView.Input.Table(
                                listOf(TransformContractView.Column("a", LogicalType.STRING, nullable = false)),
                            ),
                        "k" to TransformContractView.Input.Value(LogicalType.INTEGER),
                    ),
                output =
                    TransformContractView.Output.Table(
                        listOf(TransformContractView.Column("b", LogicalType.DECIMAL, nullable = true)),
                    ),
                rejects = false,
            )
    }

    @Test
    fun `value mode maps a value output, and table mode an object output with rejects`() {
        dryRenderer.transformContract(workspaceId, TemplateRef("test/value.jsonata", 1)) shouldBe
            TransformContractView(
                mode = TransformContractView.Mode.VALUE,
                inputs = emptyMap(),
                output = TransformContractView.Output.Value(LogicalType.STRING),
                rejects = false,
            )
        dryRenderer.transformContract(workspaceId, TemplateRef("test/obj.jsonata", 1)) shouldBe
            TransformContractView(
                mode = TransformContractView.Mode.TABLE,
                inputs = emptyMap(),
                output = TransformContractView.Output.Obj,
                rejects = true,
            )
    }

    private fun transform(
        id: String,
        contract: TransformContract,
    ): TemplateVersion =
        TemplateFixtures
            .version(id, version = 1, dialect = null, body = "$.rows", type = TemplateType.JSONATA)
            .copy(contractJson = TransformBlocks.writeContract(contract))
}
