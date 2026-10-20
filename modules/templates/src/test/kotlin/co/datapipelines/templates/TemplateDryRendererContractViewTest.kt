package co.datapipelines.templates

import co.datapipelines.pipeline.ScriptContractView
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * [TemplateDryRendererImpl.scriptContract] — the one mapping from 7b's contract model to
 * pipeline-contract's §12.13 [ScriptContractView] (7c). Every arm of the mapping is walked
 * once: the three modes, both input shapes, the three output shapes, and `rejects`; a SQL template
 * and a missing version answer null rather than a view.
 */
class TemplateDryRendererContractViewTest {
    private val rowContract =
        ScriptContract(
            mode = ScriptMode.ROW,
            inputs =
                mapOf(
                    "rows" to ScriptInput.Table(listOf(ContractColumn("a", LogicalType.STRING, nullable = false))),
                    "k" to ScriptInput.Value(LogicalType.INTEGER),
                ),
            output = ScriptOutput.Table(listOf(ContractColumn("b", LogicalType.DECIMAL, nullable = true))),
        )
    private val valueContract =
        ScriptContract(
            mode = ScriptMode.VALUE,
            inputs = emptyMap(),
            output = ScriptOutput.Value(LogicalType.STRING),
        )
    private val objContract =
        ScriptContract(
            mode = ScriptMode.TABLE,
            inputs = emptyMap(),
            output = ScriptOutput.Obj(),
            rejects = true,
        )
    private val registry =
        InMemoryTemplateRegistry(
            listOf(
                TemplateFixtures.version("test/fetch.sql", version = 1, dialect = Dialect.MYSQL, body = "SELECT 1"),
                script("test/row.jsonata", rowContract),
                script("test/value.jsonata", valueContract),
                script("test/obj.jsonata", objContract),
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
    fun `a SQL template has no script contract`() {
        dryRenderer.scriptContract(workspaceId, TemplateRef("test/fetch.sql", 1)).shouldBeNull()
    }

    @Test
    fun `a missing version has no script contract`() {
        dryRenderer.scriptContract(workspaceId, TemplateRef("test/row.jsonata", 2)).shouldBeNull()
        dryRenderer.scriptContract(workspaceId, TemplateRef("test/absent.jsonata", 1)).shouldBeNull()
    }

    @Test
    fun `row mode maps its table and value inputs and its table output column by column`() {
        dryRenderer.scriptContract(workspaceId, TemplateRef("test/row.jsonata", 1)) shouldBe
            ScriptContractView(
                mode = ScriptContractView.Mode.ROW,
                inputs =
                    mapOf(
                        "rows" to
                            ScriptContractView.Input.Table(
                                listOf(ScriptContractView.Column("a", LogicalType.STRING, nullable = false)),
                            ),
                        "k" to ScriptContractView.Input.Value(LogicalType.INTEGER),
                    ),
                output =
                    ScriptContractView.Output.Table(
                        listOf(ScriptContractView.Column("b", LogicalType.DECIMAL, nullable = true)),
                    ),
                rejects = false,
            )
    }

    @Test
    fun `value mode maps a value output, and table mode an object output with rejects`() {
        dryRenderer.scriptContract(workspaceId, TemplateRef("test/value.jsonata", 1)) shouldBe
            ScriptContractView(
                mode = ScriptContractView.Mode.VALUE,
                inputs = emptyMap(),
                output = ScriptContractView.Output.Value(LogicalType.STRING),
                rejects = false,
            )
        dryRenderer.scriptContract(workspaceId, TemplateRef("test/obj.jsonata", 1)) shouldBe
            ScriptContractView(
                mode = ScriptContractView.Mode.TABLE,
                inputs = emptyMap(),
                output = ScriptContractView.Output.Obj,
                rejects = true,
            )
    }

    private fun script(
        id: String,
        contract: ScriptContract,
    ): TemplateVersion =
        TemplateFixtures
            .version(id, version = 1, dialect = null, body = "$.rows", type = TemplateType.JSONATA)
            .copy(contractJson = ScriptBlocks.writeContract(contract))
}
