package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * pipeline-contract §12.5 (datasource existence) and §12.6 (template registry + dry render).
 *
 * The registries are test doubles: the real ones land in P3b (`datasources`) and P3a
 * (`templates`). That the rules can be specified and proved here, against interfaces this
 * module declares, is the payoff of inverting those dependencies.
 */
class ReferenceRulesTest {
    private val workspaceId = UUID.randomUUID()

    @Test
    fun `an unregistered source datasource is rejected`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(source = "pg-staging")))

        val failure = validate(pipeline).withCode(Validation.UNKNOWN_DATASOURCE).single()

        failure.details["datasource"] shouldBe "pg-staging"
        failure.path shouldBe "nodes[0].source"
    }

    @Test
    fun `an unregistered write-back datasource is rejected`() {
        val pipeline =
            Fixtures.pipeline(
                nodes = listOf(Fixtures.node(output = NodeOutput.Datasource("pg-unknown", "cache", WriteMode.APPEND))),
            )

        validate(pipeline).withCode(Validation.UNKNOWN_DATASOURCE).single().path shouldBe "nodes[0].output.datasource"
    }

    @Test
    fun `the tempdb literal is never looked up in the registry`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(source = "tempdb")))

        validate(pipeline, templates = h2Templates()).codes shouldNotContain Validation.UNKNOWN_DATASOURCE
    }

    @Test
    fun `a missing template id is rejected`() {
        val templates = StubTemplates(lookups = mapOf("fetch_orders.sql" to TemplateLookup.TemplateNotFound))

        validate(Fixtures.pipeline(), templates = templates).codes shouldContainExactly
            listOf(Validation.TEMPLATE_NOT_FOUND)
    }

    @Test
    fun `a missing template version is rejected`() {
        val templates = StubTemplates(lookups = mapOf("fetch_orders.sql" to TemplateLookup.VersionNotFound))

        validate(Fixtures.pipeline(), templates = templates).codes shouldContainExactly
            listOf(Validation.TEMPLATE_VERSION_NOT_FOUND)
    }

    @Test
    fun `a template targeting a different dialect than its node's source is rejected`() {
        // pg-prod speaks POSTGRES; the template declares MYSQL.
        val templates = StubTemplates(defaultLookup = TemplateLookup.Found(Dialect.MYSQL))

        val failure =
            validate(Fixtures.pipeline(), templates = templates)
                .withCode(
                    Validation.TEMPLATE_DIALECT_MISMATCH,
                ).single()

        failure.details["template_dialect"] shouldBe "MYSQL"
        failure.details["source_dialect"] shouldBe "POSTGRES"
    }

    @Test
    fun `a tempdb node's template must match the declared staging engine's dialect`() {
        // SPEC-REVIEW 2.1.8: the dialect is derived from settings.tempdb.engine, not hard-coded.
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(source = "tempdb")))

        validate(pipeline, templates = h2Templates()).codes shouldNotContain Validation.TEMPLATE_DIALECT_MISMATCH
        validate(pipeline).codes shouldContain Validation.TEMPLATE_DIALECT_MISMATCH
    }

    @Test
    fun `an unknown datasource does not also manufacture a dialect mismatch`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(source = "pg-staging")))

        validate(pipeline).codes shouldContainExactly listOf(Validation.UNKNOWN_DATASOURCE)
    }

    // ------------------------------------------- readonly (workspaces design §6, D6)

    /** The one stub every readonly test below uses: `pg-prod` exists and is flagged readonly. */
    private fun readonlyRegistry() = StubDatasources(readonly = setOf("pg-prod"))

    @Test
    fun `a DML node sourcing a readonly datasource is rejected with the details triple`() {
        val pipeline =
            Fixtures.pipeline(nodes = listOf(Fixtures.node(type = NodeType.DML, source = "pg-prod")))

        val failure =
            validate(
                pipeline,
                datasources = readonlyRegistry(),
            ).withCode(Validation.DATASOURCE_READONLY).single()

        failure.path shouldBe "nodes[0].source"
        failure.details["node"] shouldBe "fetch_orders"
        failure.details["datasource"] shouldBe "pg-prod"
        failure.details["shape"] shouldBe "dml_source"
    }

    @Test
    fun `a DDL node sourcing a readonly datasource is rejected with the ddl_source shape`() {
        val pipeline =
            Fixtures.pipeline(nodes = listOf(Fixtures.node(type = NodeType.DDL, source = "pg-prod")))

        val failure =
            validate(
                pipeline,
                datasources = readonlyRegistry(),
            ).withCode(Validation.DATASOURCE_READONLY).single()

        failure.details["shape"] shouldBe "ddl_source"
    }

    @Test
    fun `an output target naming a readonly datasource is rejected regardless of node type`() {
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(
                            output = NodeOutput.Datasource("pg-prod", "cache", WriteMode.APPEND),
                        ),
                    ),
            )

        val failure =
            validate(
                pipeline,
                datasources = readonlyRegistry(),
            ).withCode(Validation.DATASOURCE_READONLY).single()

        failure.path shouldBe "nodes[0].output.datasource"
        failure.details["shape"] shouldBe "output_target"
    }

    @Test
    fun `a PIPELINE node's datasource output target is registry-checked the same way`() {
        // §12.9 owns a PIPELINE node's source/template; its `output` block is a standard §4.7
        // block, so shape 3 fires on it too (the resolver answers null here — the composition
        // failures are not what this test asserts on).
        val pipelineNode =
            Node(
                id = "run_child",
                description = "Runs the child pipeline.",
                type = NodeType.PIPELINE,
                source = "",
                template = TemplateRef(),
                output = NodeOutput.Datasource("pg-prod", "cache", WriteMode.APPEND),
                dependsOn = emptyList(),
                pipeline = PipelineNodeRef("child", 1),
                parameters = null,
            )

        validate(Fixtures.pipeline(nodes = listOf(pipelineNode)), datasources = readonlyRegistry()).codes shouldContain
            Validation.DATASOURCE_READONLY
    }

    @Test
    fun `a DQL-only pipeline against the same readonly datasource saves clean`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(source = "pg-prod")))

        validate(pipeline, datasources = readonlyRegistry()).failures shouldBe emptyList()
    }

    @Test
    fun `a tempdb write against a readonly datasource elsewhere is untouched (D7)`() {
        // DML into tempdb while a DIFFERENT node reads the readonly datasource: neither is a
        // write-shaped use of it.
        val pipeline =
            Fixtures.pipeline(
                nodes =
                    listOf(
                        Fixtures.node(id = "read", source = "pg-prod", output = NodeOutput.Tempdb("src")),
                        Fixtures.node(
                            id = "write_temp",
                            type = NodeType.DML,
                            source = "tempdb",
                            template = TemplateRef("h2_write.sql", 1),
                            dependsOn = listOf("read"),
                        ),
                    ),
            )

        validate(
            pipeline,
            datasources = readonlyRegistry(),
            templates =
                StubTemplates(
                    lookups =
                        mapOf(
                            "fetch_orders.sql" to TemplateLookup.Found(Dialect.POSTGRES),
                            "h2_write.sql" to TemplateLookup.Found(Dialect.H2),
                        ),
                ),
        ).failures shouldBe emptyList()
    }

    @Test
    fun `a dry render that hits an undeclared variable is rejected at save time (D3)`() {
        val templates =
            StubTemplates(
                renders =
                    mapOf(
                        "fetch_orders.sql" to
                            DryRenderOutcome.UndeclaredVariable("region", "The following has evaluated to null: region"),
                    ),
            )

        val failure =
            validate(Fixtures.pipeline(), templates = templates)
                .withCode(
                    Validation.TEMPLATE_PARAMETER_UNDECLARED,
                ).single()

        failure.details["variable"] shouldBe "region"
    }

    @Test
    fun `a dry render that fails for any other reason gets its own code`() {
        // §12.6, added 2026-08-08. A type-mismatched built-in or an unresolvable imported macro
        // is fixed by editing the TEMPLATE; an undeclared variable is fixed by declaring a
        // PARAMETER. Reporting the first as `template_parameter_undeclared` sends the author to
        // the wrong file, so the two have separate codes.
        val templates =
            StubTemplates(
                renders =
                    mapOf(
                        "fetch_orders.sql" to
                            DryRenderOutcome.RenderFailed("?upper_case is not available for a date value"),
                    ),
            )

        val result = validate(Fixtures.pipeline(), templates = templates)

        result.codes shouldContainExactly listOf(Validation.TEMPLATE_RENDER_FAILED)
        result.failures.single().message shouldContain "?upper_case"
    }

    @Test
    fun `the save-time render code is NOT the run-time node code`() {
        // Same English, different domain segment, different HTTP status, different section:
        // `pipeline.validation.template_render_failed` (§12.6, 400, author fixes it) vs
        // `pipeline.node.template_render_failed` (§13.4, 500, operator pages on it).
        Validation.TEMPLATE_RENDER_FAILED shouldNotBe PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED
        Validation.TEMPLATE_RENDER_FAILED shouldBe "pipeline.validation.template_render_failed"
        PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED shouldBe "pipeline.node.template_render_failed"
    }

    @Test
    fun `a successful dry render adds nothing`() {
        validate(Fixtures.pipeline(), templates = StubTemplates()).codes shouldNotContain
            Validation.TEMPLATE_RENDER_FAILED
    }

    @Test
    fun `the dry render receives defaults where present and sample values otherwise (§7-4)`() {
        val templates = StubTemplates()
        val pipeline =
            Fixtures.pipeline(
                parameters =
                    mapOf(
                        "start_date" to Parameter(type = LogicalType.DATE, required = true),
                        "min_total" to
                            Parameter(
                                type = LogicalType.BIGDECIMAL,
                                precision = 12,
                                scale = 2,
                                default = Fixtures.json("\"12.50\""),
                            ),
                    ),
            )

        validate(pipeline, templates = templates)

        val context = templates.renderedContexts.getValue("fetch_orders.sql")
        context.keys shouldContain "start_date"
        // The declared default wins over the sample value, and arrives coerced.
        context["min_total"].toString() shouldBe "12.50"
    }

    @Test
    fun `a template interpolating a declared parameter is refused with the bind form in the message`() {
        val pipeline =
            Fixtures.pipeline(
                nodes = listOf(Fixtures.node()),
                parameters = mapOf("customer_id" to Parameter(type = LogicalType.STRING)),
            )
        val templates = StubTemplates(interpolated = mapOf("fetch_orders.sql" to setOf("customer_id")))

        val failure =
            validate(pipeline, templates = templates)
                .withCode(PipelineErrorCodes.Template.PARAMETER_INTERPOLATED)
                .single()

        failure.details["parameter"] shouldBe "customer_id"
        failure.details["bind_form"] shouldBe ":customer_id"
        failure.message shouldContain "\${customer_id}"
        failure.message shouldContain ":customer_id"
    }

    @Test
    fun `a template that references a declared parameter only as a bind name is not refused`() {
        // The stub reports nothing interpolated; the pipeline declares the parameter the template
        // would reference as :customer_id.
        val pipeline =
            Fixtures.pipeline(
                nodes = listOf(Fixtures.node()),
                parameters = mapOf("customer_id" to Parameter(type = LogicalType.STRING)),
            )

        validate(pipeline).codes shouldNotContain PipelineErrorCodes.Template.PARAMETER_INTERPOLATED
    }

    @Test
    fun `an interpolated name that is not declared is not reported by the bind rule`() {
        // The dry-render's own undeclared-variable rule owns that case; the bind rule only
        // refuses DECLARED parameters, so a stub reporting an undeclared name is ignored.
        val templates = StubTemplates(interpolated = mapOf("fetch_orders.sql" to setOf("undeclared")))

        validate(Fixtures.pipeline(), templates = templates).codes shouldNotContain
            PipelineErrorCodes.Template.PARAMETER_INTERPOLATED
    }

    private fun validate(
        pipeline: Pipeline,
        datasources: DatasourceRegistry = StubDatasources(),
        templates: TemplateDryRenderer = StubTemplates(),
    ) = PipelineValidator(datasources, templates, PipelineResolver { _, _, _ -> null }, 5).validate(pipeline, workspaceId)

    private fun h2Templates() = StubTemplates(defaultLookup = TemplateLookup.Found(Dialect.H2))
}
