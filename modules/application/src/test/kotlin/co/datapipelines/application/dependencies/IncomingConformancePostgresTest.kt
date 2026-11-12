package co.datapipelines.application.dependencies

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.dependencies.datasource.DatasourceDependencyFamily
import co.datapipelines.application.dependencies.parameterset.ParameterSetReleaseProvider
import co.datapipelines.application.dependencies.template.TemplateReleaseProvider
import co.datapipelines.application.dependencies.visualization.VisualizationReleaseProvider
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.LegacyEndpointColumns
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.IncomingScope.ANY
import co.datapipelines.dependencies.IncomingScope.LIVE
import co.datapipelines.dependencies.IncomingScope.WORKING
import co.datapipelines.dependencies.IncomingTarget
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.Relationship
import co.datapipelines.dependencies.conformance.IncomingConformance.Case
import co.datapipelines.dependencies.conformance.IncomingConformance.Fixture
import co.datapipelines.dependencies.conformance.IncomingConformance.Occurrence
import co.datapipelines.dependencies.conformance.ProviderConformance
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.visualization.DashboardRepository
import co.datapipelines.visualization.VisualizationRepository
import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * #877 §A/§B on a real PostgreSQL: every target family with inbound arms passes the conformance harness's INVERSE arm
 * ([ProviderConformance.checkIncoming]) over seeded rows, through descriptors built as the production beans build them
 * (arms from [IncomingArmCatalog], repositories over the ceiling's template). Each fixture states the exact stored
 * occurrences per query — exact-version isolation, a vanished pinned version, repeated references from one dependent
 * version, the three dependent scopes — and the harness reads each at page size 1 and 256.
 */
class IncomingConformancePostgresTest {
    /** ONE DataSource: the ceiling checks that the transaction it bounds is bound to its own source. */
    private val dataSource = SharedPostgres.dataSource()
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val seed = ReleaseArtifactSeed(jdbc)
    private val others = NonArtifactSeed(jdbc)
    private val ceiling = PostgresStatementCeiling(dataSource)
    private val pipelines = PipelineRepository(ceiling.jdbc)
    private val dashboards = DashboardRepository(ceiling.jdbc)

    @BeforeEach
    fun reset() {
        seed.reset()
        others.adopt(seed.userId, seed.workspaceId)
    }

    @Test
    @Suppress("LongMethod") // one declarative seed + case table per target; splitting it hides which rows the cases cover
    fun `the template target answers its four arms - node pins, imports, selectors, visualization block pins`() {
        seed.template("lib", 1, library = true)
        seed.template("lib", 2, library = true)
        seed.pipeline("job", 1, RELEASED, templates = listOf("lib" to 1, "lib" to 2, "lib" to 1))
        seed.pipeline("job", 2, DRAFT, templates = listOf("lib" to 2))
        seed.pipeline("job", 3, DISCARDED, templates = listOf("lib" to 1))
        seed.pipeline("old", 1, RELEASED, templates = listOf("gone" to 9))
        seed.template("report", 1, RELEASED, imports = listOf("lib" to 1))
        seed.template("report", 2, DRAFT, imports = listOf("lib" to 2, "lib" to 1))
        seed.parameterSet("filters", 1, RELEASED, selectors = listOf("lib" to 1))
        seed.parameterSet("filters", 2, DRAFT, selectors = listOf("lib" to 2, "lib" to 2))
        seed.visualization("chart", 1, RELEASED, transform = "lib" to 1)
        seed.visualization("chart", 2, DRAFT, transform = "lib" to 2)
        val job = id("pipelines", "job")
        val report = id("templates", "report")
        val filters = id("parameter_sets", "filters")
        val chart = id("visualizations", "chart")
        val p = FamilyKey.PIPELINE
        val t = FamilyKey.TEMPLATE
        val s = FamilyKey.PARAMETER_SET
        val v = FamilyKey.VISUALIZATION
        val provider =
            TemplateReleaseProvider(
                TemplateRepository(ceiling.jdbc),
                ceiling,
                IncomingArmCatalog.template(
                    pipelines,
                    TemplateRepository(ceiling.jdbc),
                    ParameterSetRepository(ceiling.jdbc),
                    VisualizationRepository(ceiling.jdbc),
                    ceiling,
                ),
            )
        conforms(
            Fixture(
                provider.descriptor(),
                others.context(),
                listOf(
                    Case(
                        exact(t, "lib", 1),
                        p,
                        LIVE,
                        listOf(occ(p, t, job, 1, "nodes[0].template", 1), occ(p, t, job, 1, "nodes[2].template", 1)),
                    ),
                    Case(
                        exact(t, "lib", 2),
                        p,
                        LIVE,
                        listOf(occ(p, t, job, 1, "nodes[1].template", 2), occ(p, t, job, 2, "nodes[0].template", 2)),
                    ),
                    Case(exact(t, "lib", 2), p, WORKING, listOf(occ(p, t, job, 2, "nodes[0].template", 2))),
                    // ANY: the DISCARDED v3 of a still-live pipeline counts (versioning §3.5 graph rule 3).
                    Case(
                        exact(t, "lib", 1),
                        p,
                        ANY,
                        listOf(
                            occ(p, t, job, 1, "nodes[0].template", 1),
                            occ(p, t, job, 1, "nodes[2].template", 1),
                            occ(p, t, job, 3, "nodes[0].template", 1),
                        ),
                    ),
                    Case(
                        entity(t, "gone"),
                        p,
                        LIVE,
                        listOf(occ(p, t, id("pipelines", "old"), 1, "nodes[0].template", 9)),
                        vanishedVersion = 9,
                    ),
                    Case(exact(t, "lib", 1), t, LIVE, listOf(occ(t, t, report, 1, "imports[0]", 1), occ(t, t, report, 2, "imports[1]", 1))),
                    Case(exact(t, "lib", 2), t, LIVE, listOf(occ(t, t, report, 2, "imports[0]", 2))),
                    Case(exact(t, "lib", 1), s, LIVE, listOf(occ(s, t, filters, 1, "parameters[0].source.template", 1))),
                    Case(
                        exact(t, "lib", 2),
                        s,
                        LIVE,
                        listOf(
                            occ(s, t, filters, 2, "parameters[0].source.template", 2),
                            occ(s, t, filters, 2, "parameters[1].source.template", 2),
                        ),
                    ),
                    Case(exact(t, "lib", 1), v, LIVE, listOf(occ(v, t, chart, 1, "transform.template", 1))),
                    Case(exact(t, "lib", 2), v, LIVE, listOf(occ(v, t, chart, 2, "transform.template", 2))),
                ),
                unreferenced = entity(t, "nobody"),
            ),
        )
    }

    @Test
    @Suppress("LongMethod") // one declarative seed + case table per target; splitting it hides which rows the cases cover
    fun `the pipeline target answers its four arms - child pins, dashboard sources, API versions, schedules`() {
        seed.pipeline(DAILY, 1, RELEASED)
        seed.pipeline(DAILY, 2, RELEASED)
        seed.pipeline("parent", 1, RELEASED, children = listOf(DAILY to 1))
        seed.pipeline("parent", 2, DRAFT, children = listOf(DAILY to 2, DAILY to 2))
        seed.pipeline("orphan", 1, RELEASED, children = listOf("ghost" to 7))
        seed.dashboard("board", 1, RELEASED, pipelines = listOf(DAILY to 1, DAILY to 1))
        seed.dashboard("board", 2, DRAFT, pipelines = listOf(DAILY to 2))
        val api = endpoint("/inverse/v1/daily")
        val versions = PublishedEndpointVersionRepository(jdbc)
        versions.insert(api, 1, RELEASED, EndpointVersionBody(DAILY, 1, 30, ""), seed.userId)
        versions.insert(api, 2, DRAFT, EndpointVersionBody(DAILY, 2, 30, ""), seed.userId)
        versions.setCurrent(api, 1)
        val exactly = others.schedule("hourly", others.pipelinePayload(DAILY, 1))
        // Schema-2 exact pins only (ledger gate-red-final-1: a legacy row is converted away before serving).
        val second = others.schedule("nightly", others.pipelinePayload(DAILY, 2))
        val p = FamilyKey.PIPELINE
        val parent = id("pipelines", "parent")
        val board = id("dashboards", "board")
        val provider =
            PipelineReleaseFamily.descriptor(
                pipelines,
                ceiling,
                incoming =
                    IncomingArmCatalog.pipeline(
                        pipelines,
                        dashboards,
                        PublishedEndpointVersionRepository(ceiling.jdbc),
                        ScheduleTargetQueries(ceiling.jdbc),
                        SCHEDULE_SHAPE,
                        ceiling,
                    ),
            )
        val d = FamilyKey.DASHBOARD
        val a = FamilyKey.API_ENDPOINT
        val sc = FamilyKey.SCHEDULE
        conforms(
            Fixture(
                provider,
                others.context(),
                listOf(
                    Case(exact(p, DAILY, 1), p, LIVE, listOf(occ(p, p, parent, 1, "nodes[0].pipeline", 1))),
                    Case(
                        exact(p, DAILY, 2),
                        p,
                        LIVE,
                        listOf(occ(p, p, parent, 2, "nodes[0].pipeline", 2), occ(p, p, parent, 2, "nodes[1].pipeline", 2)),
                    ),
                    Case(
                        entity(p, "ghost"),
                        p,
                        ANY,
                        listOf(occ(p, p, id("pipelines", "orphan"), 1, "nodes[0].pipeline", 7)),
                        vanishedVersion = 7,
                    ),
                    Case(
                        exact(p, DAILY, 1),
                        d,
                        LIVE,
                        listOf(occ(d, p, board, 1, "sources[0].pipeline", 1), occ(d, p, board, 1, "sources[1].pipeline", 1)),
                    ),
                    Case(exact(p, DAILY, 2), d, WORKING, listOf(occ(d, p, board, 2, "sources[0].pipeline", 2))),
                    Case(exact(p, DAILY, 1), a, LIVE, listOf(occ(a, p, api.toString(), 1, "pipeline_version", 1))),
                    Case(exact(p, DAILY, 2), a, ANY, listOf(occ(a, p, api.toString(), 2, "pipeline_version", 2))),
                    Case(exact(p, DAILY, 1), sc, LIVE, listOf(occ(sc, p, exactly.id.toString(), 0, "/pipeline", 1))),
                    Case(
                        entity(p, DAILY),
                        sc,
                        ANY,
                        listOf(occ(sc, p, exactly.id.toString(), 0, "/pipeline", 1), occ(sc, p, second.id.toString(), 0, "/pipeline", 2)),
                    ),
                    Case(exact(p, DAILY, 2), sc, WORKING, listOf(occ(sc, p, second.id.toString(), 0, "/pipeline", 2))),
                ),
                unreferenced = entity(p, "nobody"),
            ),
        )
    }

    @Test
    @Suppress("LongMethod") // one declarative seed + case table per target; splitting it hides which rows the cases cover
    fun `the parameter-set and visualization targets answer their dashboard arm`() {
        seed.dashboard("board", 1, RELEASED, set = "filters" to 1, visualizations = listOf("chart" to 1, "chart" to 1))
        seed.dashboard("board", 2, DRAFT, set = "filters" to 2, visualizations = listOf("chart" to 2))
        seed.dashboard("stale", 1, RELEASED, set = "old_set" to 4, visualizations = listOf("old_chart" to 5))
        val board = id("dashboards", "board")
        val stale = id("dashboards", "stale")
        val d = FamilyKey.DASHBOARD
        val s = FamilyKey.PARAMETER_SET
        val v = FamilyKey.VISUALIZATION
        val sets =
            ParameterSetReleaseProvider(
                ParameterSetRepository(ceiling.jdbc),
                TemplateRepository(ceiling.jdbc),
                ceiling,
                IncomingArmCatalog.parameterSet(dashboards, ceiling),
            )
        conforms(
            Fixture(
                sets.descriptor(),
                others.context(),
                listOf(
                    Case(exact(s, "filters", 1), d, LIVE, listOf(occ(d, s, board, 1, "parameter_set", 1))),
                    Case(exact(s, "filters", 2), d, LIVE, listOf(occ(d, s, board, 2, "parameter_set", 2))),
                    Case(entity(s, "old_set"), d, ANY, listOf(occ(d, s, stale, 1, "parameter_set", 4)), vanishedVersion = 4),
                ),
                unreferenced = entity(s, "nobody"),
            ),
        )
        val charts =
            VisualizationReleaseProvider(
                VisualizationRepository(ceiling.jdbc),
                ceiling,
                incoming = IncomingArmCatalog.visualization(dashboards, ceiling),
            )
        conforms(
            Fixture(
                charts.descriptor(),
                others.context(),
                listOf(
                    Case(
                        exact(v, "chart", 1),
                        d,
                        LIVE,
                        listOf(
                            occ(d, v, board, 1, "visualizations[0].visualization", 1),
                            occ(d, v, board, 1, "visualizations[1].visualization", 1),
                        ),
                    ),
                    Case(exact(v, "chart", 2), d, WORKING, listOf(occ(d, v, board, 2, "visualizations[0].visualization", 2))),
                    Case(
                        entity(v, "old_chart"),
                        d,
                        LIVE,
                        listOf(occ(d, v, stale, 1, "visualizations[0].visualization", 5)),
                        vanishedVersion = 5,
                    ),
                ),
                unreferenced = entity(v, "nobody"),
            ),
        )
    }

    @Test
    fun `the datasource target answers by name only - pipeline source and output, parameter-set selector`() {
        seed.pipelineNodes(
            "loader",
            1,
            RELEASED,
            listOf(
                node("read", NodeType.DQL, source = WAREHOUSE),
                node("write", NodeType.DML, source = "tempdb", output = NodeOutput.Datasource(WAREHOUSE, "t", WriteMode.REPLACE)),
                node("script", NodeType.SCRIPT, source = WAREHOUSE),
            ),
        )
        seed.parameterSet("filters", 1, RELEASED, selectors = listOf("states" to 1))
        val loader = id("pipelines", "loader")
        val filters = id("parameter_sets", "filters")
        val p = FamilyKey.PIPELINE
        val s = FamilyKey.PARAMETER_SET
        val ds = FamilyKey.DATASOURCE
        val provider =
            DatasourceDependencyFamily.descriptor(
                DatasourceRepository(ceiling.jdbc),
                ceiling,
                incoming = IncomingArmCatalog.datasource(pipelines, ParameterSetRepository(ceiling.jdbc), ceiling),
            )
        conforms(
            Fixture(
                provider,
                others.context(),
                listOf(
                    Case(
                        datasource(WAREHOUSE),
                        p,
                        LIVE,
                        listOf(occ(p, ds, loader, 1, "nodes[0].source", null), occ(p, ds, loader, 1, "nodes[1].output.datasource", null)),
                    ),
                    Case(datasource(WAREHOUSE), s, ANY, listOf(occ(s, ds, filters, 1, "parameters[0].source.datasource", null))),
                ),
                unreferenced = datasource("nobody"),
            ),
        )
    }

    /** The ceiling's `SET LOCAL` bound needs a transaction on its DataSource — the guard's own, in production. */
    private fun <B : Any> conforms(fixture: Fixture<B>) {
        val violations =
            TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                ProviderConformance.checkIncoming(fixture)
            }
        requireNotNull(violations).shouldBeEmpty()
    }

    private fun occ(
        dependents: FamilyKey,
        target: FamilyKey,
        id: String,
        version: Int,
        path: String,
        stored: Int?,
    ) = Occurrence(id, version, requireNotNull(Relationship.between(dependents, target)), path, stored)

    private fun exact(
        family: FamilyKey,
        key: String,
        version: Int,
    ) = IncomingTarget.ExactVersion(family, OwnerScope.Workspace(seed.workspaceId), key, version)

    private fun entity(
        family: FamilyKey,
        key: String,
    ) = IncomingTarget.Entity(family, OwnerScope.Workspace(seed.workspaceId), key)

    private fun datasource(name: String) = IncomingTarget.Entity(FamilyKey.DATASOURCE, OwnerScope.Deployment, name)

    private fun id(
        table: String,
        name: String,
    ): String =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT id::text FROM $table WHERE name = :name AND workspace_id = :ws",
                mapOf("name" to name, "ws" to seed.workspaceId),
                String::class.java,
            ),
        )

    private fun node(
        id: String,
        type: NodeType,
        source: String,
        output: NodeOutput = NodeOutput.Caller,
    ) = Node(
        id = id,
        description = "",
        type = type,
        source = source,
        template = TemplateRef("t", 1),
        output = output,
        dependsOn = emptyList(),
    )

    private fun endpoint(path: String): UUID {
        val pipelineId = UUID.fromString(id("pipelines", DAILY))
        val endpoints = PublishedEndpointRepository(jdbc)
        return requireNotNull(
            TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                endpoints
                    .insert(
                        PublishedEndpoint.of(
                            id = UUID.randomUUID(),
                            workspaceId = seed.workspaceId,
                            pathPattern = path,
                            isEnabled = true,
                            createdBy = seed.userId,
                            createdAt = Instant.EPOCH,
                            updatedAt = Instant.EPOCH,
                        ),
                        LegacyEndpointColumns(pipelineId, 30, ""),
                    ).id
            },
        )
    }

    companion object {
        /** NonArtifactSeed's schedules target this name. */
        const val DAILY = "acme/rides/daily"
        const val WAREHOUSE = "warehouse"
        val SCHEDULE_SHAPE = ScheduleTargetShape("pipeline:", "version", "/pipeline", 2)
    }
}
