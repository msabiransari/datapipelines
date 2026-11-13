package co.datapipelines.web.incoming

import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.LegacyEndpointColumns
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.application.endpoints.PublishedEndpointVersionRepository
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.PipelineNodeRef
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.PipelineVersionStatus.DISCARDED
import co.datapipelines.pipeline.PipelineVersionStatus.DRAFT
import co.datapipelines.pipeline.PipelineVersionStatus.RELEASED
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.WriteMode
import co.datapipelines.scheduler.MissedRunPolicy
import co.datapipelines.scheduler.NewSchedule
import co.datapipelines.scheduler.ScheduleRepository
import co.datapipelines.scheduler.SchedulerAutoConfiguration
import co.datapipelines.visualization.ArtifactRef
import co.datapipelines.visualization.BodyCodec
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardLayout
import co.datapipelines.visualization.DashboardObjectType
import co.datapipelines.visualization.DashboardSource
import co.datapipelines.visualization.RendererKind
import co.datapipelines.visualization.RendererSpec
import co.datapipelines.visualization.TransformBinding
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationOccurrence
import co.datapipelines.web.incoming.IncomingParityCorpus.CorpusClass.ALL_DISCARDED
import co.datapipelines.web.incoming.IncomingParityCorpus.CorpusClass.DISCARDED_WITH_LIVE
import co.datapipelines.web.incoming.IncomingParityCorpus.CorpusClass.FOLLOWS_CURRENT
import co.datapipelines.web.incoming.IncomingParityCorpus.CorpusClass.LIVE_SCHEDULE
import co.datapipelines.web.incoming.IncomingParityCorpus.CorpusClass.REPEATED
import co.datapipelines.web.incoming.IncomingParityCorpus.CorpusClass.SOFT_DELETED
import co.datapipelines.web.incoming.IncomingParityCorpus.CorpusClass.VANISHED_VERSION
import co.datapipelines.web.incoming.IncomingParityCorpus.ParityArm.Companion.ALL
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * #877 §E.1 (A27) — the parity corpus: FIXED content, written by plain SQL (and the API/schedule repositories' own
 * inserts) into an emptied metadata database, so it depends on nothing another test class left behind. For each of the
 * twelve inverse arms it plants the six classes of the brief — a DRAFT dependent version, a RELEASED one, a DISCARDED
 * version of an entity that still has a live version, a dependent whose every version is DISCARDED, a pin naming a
 * target version that does not exist (`@9`), two occurrences of the same target in one dependent version — and the
 * schedule arm's own three (a live exact pin, a soft-deleted schedule, a schema-1 payload following CURRENT). The
 * classes an arm cannot have are [IMPOSSIBLE], each with its reason; [inventory] records which dependent version plants
 * which class, and the test asserts every possible class is planted before it compares anything.
 *
 * One dependent entity per class serves every arm its family feeds: a pipeline version holds a DQL node (template pin +
 * datasource source) and a PIPELINE node (child pin + datasource output), so the `acme/p/<class>` pipelines plant the
 * class for T←P, P←P and D←P at once; a set's selector pins a template AND names a datasource (T←S, D←S); a dashboard
 * pins a set, a source pipeline and a visualization (S←D, P←D, V←D). Noise pins (`OTHER_*`, `LANDING`) sit beside the
 * repeated ones so a key filter is exercised too.
 *
 * [IGNORED] are the reverse-only plants both directions must skip — a CALCULATOR node carrying a `template` key (planted
 * by SQL: no writer can produce it), a SCRIPT node's `source` and a DML node's `tempdb` source (never a datasource).
 */
class IncomingParityCorpus(
    private val dataSource: DataSource,
) {
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    val userId: UUID = CompleteAnswerSeed.idOf("user", "parity")
    val workspaceId: UUID = CompleteAnswerSeed.idOf("workspace", "parity")

    /** The arm a stored reference feeds: its TARGET family and the DEPENDENT family that stores it (record §14). */
    enum class ParityArm(
        val target: FamilyKey,
        val dependents: FamilyKey,
    ) {
        TEMPLATE_FROM_PIPELINE(FamilyKey.TEMPLATE, FamilyKey.PIPELINE),
        TEMPLATE_FROM_TEMPLATE(FamilyKey.TEMPLATE, FamilyKey.TEMPLATE),
        TEMPLATE_FROM_PARAMETER_SET(FamilyKey.TEMPLATE, FamilyKey.PARAMETER_SET),
        TEMPLATE_FROM_VISUALIZATION(FamilyKey.TEMPLATE, FamilyKey.VISUALIZATION),
        PIPELINE_FROM_PIPELINE(FamilyKey.PIPELINE, FamilyKey.PIPELINE),
        PIPELINE_FROM_DASHBOARD(FamilyKey.PIPELINE, FamilyKey.DASHBOARD),
        PIPELINE_FROM_API_ENDPOINT(FamilyKey.PIPELINE, FamilyKey.API_ENDPOINT),
        PIPELINE_FROM_SCHEDULE(FamilyKey.PIPELINE, FamilyKey.SCHEDULE),
        PARAMETER_SET_FROM_DASHBOARD(FamilyKey.PARAMETER_SET, FamilyKey.DASHBOARD),
        VISUALIZATION_FROM_DASHBOARD(FamilyKey.VISUALIZATION, FamilyKey.DASHBOARD),
        DATASOURCE_FROM_PIPELINE(FamilyKey.DATASOURCE, FamilyKey.PIPELINE),
        DATASOURCE_FROM_PARAMETER_SET(FamilyKey.DATASOURCE, FamilyKey.PARAMETER_SET),
        ;

        companion object {
            val ALL: List<ParityArm> = entries
        }
    }

    /** What a planted dependent version exercises. The first six are the brief's; the last three are the schedule arm's. */
    enum class CorpusClass {
        DRAFT,
        RELEASED,
        DISCARDED_WITH_LIVE,
        ALL_DISCARDED,
        VANISHED_VERSION,
        REPEATED,
        LIVE_SCHEDULE,
        SOFT_DELETED,
        FOLLOWS_CURRENT,
    }

    /** One planted class: [arm]'s dependent [name] at [version] (0 for a schedule) exercises [corpusClass]. */
    data class Planted(
        val arm: ParityArm,
        val corpusClass: CorpusClass,
        val name: String,
        val version: Int,
    )

    /** A reverse-only plant: [arm]'s dependent [name]@[version] stores something at [path] that is NOT a reference. */
    data class Ignored(
        val arm: ParityArm,
        val name: String,
        val version: Int,
        val path: String,
        val why: String,
    )

    private val planted = mutableListOf<Planted>()

    /** Every planted class, in seeding order — the corpus inventory the evidence prints. */
    val inventory: List<Planted> get() = planted.toList()

    /** Empties the metadata and writes the whole corpus. */
    fun build() {
        planted.clear()
        jdbc.jdbcTemplate.execute("TRUNCATE users CASCADE")
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject)" +
                " VALUES (:id, 'parity@example.com', 'T', 'google', 'parity')",
            mapOf("id" to userId),
        )
        jdbc.update(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES (:id, 'w_parity', 'W', :owner)",
            mapOf("id" to workspaceId, "owner" to userId),
        )
        targets()
        VERSIONED.forEach { (corpusClass, versions) ->
            val name = corpusClass.name.lowercase()
            versions.forEach { (version, status, pin) ->
                val repeat = corpusClass == REPEATED
                pipeline("acme/p/$name", version, status, pipelineNodes(pin, repeat))
                template("acme/i/$name.sql", version, status, imports(pin, repeat))
                parameterSet("acme/s/$name", version, status, selectors(pin, repeat))
                if (!repeat) visualization("acme/v/$name", version, status, pin)
                dashboard("acme/d/$name", version, status, pin, repeat)
                // DISCARDED_WITH_LIVE is its DISCARDED version; its released sibling is what keeps the entity live.
                if (corpusClass != DISCARDED_WITH_LIVE || status == DISCARDED) record(corpusClass, name, version)
            }
        }
        apis()
        rules()
        schedules()
    }

    /** The target rows themselves: bodies that reference nothing, so no target is also a dependent. */
    private fun targets() {
        listOf(1 to RELEASED, 2 to DRAFT).forEach { (version, status) ->
            template(LIB, version, status, emptyList())
            pipeline(DAILY, version, status, emptyList())
            parameterSet(FILTERS, version, status, emptyList())
            visualization(CHART, version, status, null)
        }
    }

    private fun record(
        corpusClass: CorpusClass,
        name: String,
        version: Int,
    ) {
        val names =
            mapOf(FamilyKey.PIPELINE to "acme/p/$name", FamilyKey.TEMPLATE to "acme/i/$name.sql") +
                mapOf(
                    FamilyKey.PARAMETER_SET to "acme/s/$name",
                    FamilyKey.VISUALIZATION to "acme/v/$name",
                    FamilyKey.DASHBOARD to "acme/d/$name",
                )
        ALL.filter { IMPOSSIBLE[it]?.containsKey(corpusClass) != true && it.dependents in names }.forEach { arm ->
            planted += Planted(arm, corpusClass, names.getValue(arm.dependents), version)
        }
    }

    /** The API arm's dependents: the six classes but REPEATED, plus a released v1 under a draft v2 (WORKING = the draft). */
    private fun apis() {
        val endpoints = PublishedEndpointRepository(jdbc)
        val versions = PublishedEndpointVersionRepository(jdbc)
        val daily = CompleteAnswerSeed.idOf("pipeline", DAILY)
        val working = null as CorpusClass? to listOf(Triple(1, RELEASED, 1), Triple(2, DRAFT, 2))
        val classes = VERSIONED.filterKeys { it != REPEATED } + working
        classes.forEach { (corpusClass, rows) ->
            val path = "/parity/v1/${corpusClass?.name?.lowercase() ?: "working"}"
            val api =
                inTransaction {
                    endpoints
                        .insert(
                            PublishedEndpoint.of(
                                id = CompleteAnswerSeed.idOf("api", path),
                                workspaceId = workspaceId,
                                pathPattern = path,
                                isEnabled = true,
                                createdBy = userId,
                                createdAt = Instant.EPOCH,
                                updatedAt = Instant.EPOCH,
                            ),
                            LegacyEndpointColumns(daily, TIMEOUT, ""),
                        ).id
                }
            // A version is never born DISCARDED: release it, point at the highest release, then discard (the shipped verbs).
            rows.forEach { (version, status, pin) ->
                versions.insert(
                    api,
                    version,
                    if (status ==
                        DRAFT
                    ) {
                        DRAFT
                    } else {
                        RELEASED
                    },
                    EndpointVersionBody(DAILY, pin, TIMEOUT, ""),
                    userId,
                )
            }
            rows.filter { it.second != DRAFT }.maxOfOrNull { it.first }?.let { versions.setCurrent(api, it) }
            rows.filter { it.second == DISCARDED }.forEach { versions.discard(api, it.first, userId, draftEligible = false) }
            corpusClass?.let { cls ->
                rows.filter { cls != DISCARDED_WITH_LIVE || it.second == DISCARDED }.forEach {
                    planted += Planted(ParityArm.PIPELINE_FROM_API_ENDPOINT, cls, path, it.first)
                }
            }
        }
    }

    /**
     * The node-type and selector rules: a SCRIPT node pins its template but its `source` is no datasource; a DML node over
     * `tempdb` pins its template and writes a datasource output; a CALCULATOR node carries a planted `template` key. A
     * released v1 under a draft v2, so WORKING reads v2. A set with a template-only and a datasource-only selector.
     */
    private fun rules() {
        listOf(1 to RELEASED, 2 to DRAFT).forEach { (version, status) ->
            val nodes =
                listOf(
                    node("script", NodeType.SCRIPT, WAREHOUSE, TemplateRef(LIB, 1)),
                    node("load", NodeType.DML, "tempdb", TemplateRef(LIB, 2), NodeOutput.Datasource(WAREHOUSE, "t", WriteMode.REPLACE)),
                    node("calc", NodeType.CALCULATOR, "", TemplateRef()),
                )
            pipeline(RULES_PIPELINE, version, status, nodes)
            jdbc.update(
                "UPDATE pipeline_versions SET body_json = jsonb_set(body_json, '{nodes,2,template}', CAST(:pin AS jsonb))" +
                    " WHERE pipeline_id = :id AND version = :version",
                mapOf(
                    "pin" to """{"id":"$LIB","version":1}""",
                    "id" to CompleteAnswerSeed.idOf("pipeline", RULES_PIPELINE),
                    "version" to version,
                ),
            )
        }
        parameterSet(RULES_SET, 1, RELEASED, listOf(LIB to 1 to null, null to WAREHOUSE))
    }

    /** The schedule arm: live exact pins, a vanished pin, a soft-deleted schedule, a schema-1 payload and a noise target. */
    private fun schedules() {
        val schedules = ScheduleRepository(jdbc, SchedulerAutoConfiguration.JSON)

        fun schedule(
            name: String,
            target: String,
            schema: Int,
            version: Any,
        ): UUID {
            val payload = SchedulerAutoConfiguration.JSON.createObjectNode().put("pipeline", target)
            if (version is Int) payload.put("version", version) else payload.put("version", version.toString())
            return schedules
                .insert(
                    NewSchedule(
                        id = CompleteAnswerSeed.idOf("schedule", name),
                        workspaceId = workspaceId,
                        name = name,
                        executorId = "pipeline",
                        payloadSchemaVersion = schema,
                        payload = payload,
                        parameters = SchedulerAutoConfiguration.JSON.createObjectNode(),
                        targetRef = "pipeline:$target",
                        cron = "0 * * * *",
                        timezone = "UTC",
                        missedRunPolicy = MissedRunPolicy.SKIP,
                        nextDueAt = Instant.parse("2026-11-12T00:00:00Z"),
                        createdBy = userId,
                        idempotencyKey = null,
                        idempotencyHash = null,
                        notificationRecipients = emptyList(),
                    ),
                ).id
        }
        val arm = ParityArm.PIPELINE_FROM_SCHEDULE
        schedule("parity-live-1", DAILY, 2, 1).also { planted += Planted(arm, LIVE_SCHEDULE, "parity-live-1", 0) }
        schedule("parity-live-2", DAILY, 2, 2).also { planted += Planted(arm, LIVE_SCHEDULE, "parity-live-2", 0) }
        schedule("parity-vanished", DAILY, 2, VANISHED).also { planted += Planted(arm, VANISHED_VERSION, "parity-vanished", 0) }
        schedule("parity-legacy", DAILY, 1, "current").also { planted += Planted(arm, FOLLOWS_CURRENT, "parity-legacy", 0) }
        val deleted = schedule("parity-deleted", DAILY, 2, 1)
        jdbc.update("UPDATE schedules SET deleted_at = NOW() WHERE id = :id", mapOf("id" to deleted))
        planted += Planted(arm, SOFT_DELETED, "parity-deleted", 0)
        schedule("parity-noise", OTHER_PIPELINE, 2, 1)
    }

    private fun pipelineNodes(
        pin: Int,
        repeat: Boolean,
    ): List<Node> {
        val dql = {
            id: String,
            template: String,
            version: Int,
            source: String,
            ->
            node(id, NodeType.DQL, source, TemplateRef(template, version))
        }
        val child = { id: String, target: String, version: Int, out: String ->
            node(
                id,
                NodeType.PIPELINE,
                "",
                TemplateRef(),
                NodeOutput.Datasource(out, "t", WriteMode.REPLACE),
                PipelineNodeRef(target, version),
            )
        }
        return if (repeat) {
            listOf(
                dql("a", LIB, pin, WAREHOUSE),
                dql("b", LIB, pin, WAREHOUSE),
                dql("c", OTHER_TEMPLATE, 1, LANDING),
                child("d", DAILY, pin, WAREHOUSE),
                child("e", DAILY, pin, WAREHOUSE),
                child("f", OTHER_PIPELINE, 1, LANDING),
            )
        } else {
            listOf(dql("a", LIB, pin, WAREHOUSE), child("b", DAILY, pin, WAREHOUSE))
        }
    }

    private fun imports(
        pin: Int,
        repeat: Boolean,
    ): List<Triple<String, Int, String>> =
        if (repeat) listOf(Triple(LIB, pin, "a"), Triple(LIB, pin, "b"), Triple(OTHER_TEMPLATE, 1, "c")) else listOf(Triple(LIB, pin, "a"))

    private fun selectors(
        pin: Int,
        repeat: Boolean,
    ): List<Pair<Pair<String, Int>?, String?>> =
        if (repeat) {
            listOf(LIB to pin to WAREHOUSE, LIB to pin to WAREHOUSE, OTHER_TEMPLATE to 1 to LANDING)
        } else {
            listOf(LIB to pin to WAREHOUSE)
        }

    @Suppress("LongParameterList") // a node's reference-bearing fields, each named at the call site
    private fun node(
        id: String,
        type: NodeType,
        source: String,
        template: TemplateRef,
        output: NodeOutput = NodeOutput.Caller,
        pipeline: PipelineNodeRef? = null,
    ) = Node(
        id = id,
        description = "",
        type = type,
        source = source,
        template = template,
        output = output,
        dependsOn = emptyList(),
        pipeline = pipeline,
    )

    private fun pipeline(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        nodes: List<Node>,
    ) = seed().pipelineNodes(name, version, status, nodes)

    private fun template(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        imports: List<Triple<String, Int, String>>,
    ) {
        seed().template(name, version, status)
        val json = imports.joinToString(",", "[", "]") { (id, pin, alias) -> """{"id":"$id","version":$pin,"alias":"$alias"}""" }
        jdbc.update(
            "UPDATE template_versions SET imports_json = CAST(:imports AS jsonb) WHERE template_id = :id AND version = :version",
            mapOf("imports" to json, "id" to CompleteAnswerSeed.idOf("template", name), "version" to version),
        )
    }

    private fun parameterSet(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        selectors: List<Pair<Pair<String, Int>?, String?>>,
    ) {
        val parameters =
            selectors.withIndex().joinToString(",", "[", "]") { (index, selector) ->
                val (template, datasource) = selector
                val source =
                    listOfNotNull(
                        template?.let { """"template":{"id":"${it.first}","version":${it.second}}""" },
                        datasource?.let { """"datasource":"$it"""" },
                    ).joinToString(",", "{", "}")
                """{"name":"p$index","label":"P$index","type":"INTEGER","kind":"SELECT","source":$source}"""
            }
        seed().parameterSetBody(name, version, status) { """{"display_name":"$name","parameters":$parameters}""" }
    }

    private fun visualization(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        pin: Int?,
    ) {
        val body =
            VisualizationBody(
                displayName = name,
                renderer = RendererSpec(RendererKind.TABLE, "1"),
                inputs = emptyMap(),
                transform = pin?.let { TransformBinding(ArtifactRef(LIB, it), emptyMap()) },
                config = JsonNodeFactory.instance.objectNode(),
            )
        seed().artifactBody("visualizations", "visualization_versions", "visualization_id", name, version, status) {
            BodyCodec.Visualization.write(body)
        }
    }

    private fun dashboard(
        name: String,
        version: Int,
        status: PipelineVersionStatus,
        pin: Int,
        repeat: Boolean,
    ) {
        val pipelines = if (repeat) listOf(DAILY to pin, DAILY to pin, OTHER_PIPELINE to 1) else listOf(DAILY to pin)
        val charts = if (repeat) listOf(pin, pin) else listOf(pin)
        val body =
            DashboardBody(
                displayName = name,
                parameterSet = ArtifactRef(FILTERS, pin),
                sources =
                    pipelines.withIndex().map { (index, ref) ->
                        DashboardSource("s$index", ArtifactRef(ref.first, ref.second), emptyMap())
                    },
                visualizations =
                    charts.withIndex().map { (index, ref) ->
                        VisualizationOccurrence("v$index", DashboardObjectType.VISUALIZATION, ArtifactRef(CHART, ref))
                    },
                layout = DashboardLayout(),
            )
        seed().artifactBody("dashboards", "dashboard_versions", "dashboard_id", name, version, status) { BodyCodec.Dashboard.write(body) }
    }

    /** The fixed-id SQL writer shared with the complete-answer tests (same workspace and user ids as this corpus). */
    private fun seed() = CompleteAnswerSeed(jdbc, userId, workspaceId)

    private fun <T> inTransaction(work: () -> T): T =
        checkNotNull(
            TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                work()
            },
        )

    companion object {
        const val LIB = "acme/t/lib.sql"
        const val OTHER_TEMPLATE = "acme/t/other.sql"
        const val DAILY = "acme/t/daily"
        const val OTHER_PIPELINE = "acme/t/other"
        const val FILTERS = "acme/t/filters"
        const val CHART = "acme/t/chart"
        const val WAREHOUSE = "warehouse"
        const val LANDING = "landing"
        const val RULES_PIPELINE = "acme/p/rules"
        const val RULES_SET = "acme/s/rules"

        /** A target version no row stores. */
        const val VANISHED = 9
        private const val TIMEOUT = 30

        /** The versioned classes as (version, status, the target version every pin of that version names). */
        val VERSIONED: Map<CorpusClass, List<Triple<Int, PipelineVersionStatus, Int>>> =
            mapOf(
                CorpusClass.DRAFT to listOf(Triple(1, DRAFT, 2)),
                CorpusClass.RELEASED to listOf(Triple(1, RELEASED, 1)),
                DISCARDED_WITH_LIVE to listOf(Triple(1, RELEASED, 1), Triple(2, DISCARDED, 2)),
                ALL_DISCARDED to listOf(Triple(1, DISCARDED, 1), Triple(2, DISCARDED, 2)),
                VANISHED_VERSION to listOf(Triple(1, RELEASED, VANISHED)),
                REPEATED to listOf(Triple(1, DRAFT, 1)),
            )

        /** The target keys and versions side (a) asks about: each key as an entity and at every version the corpus names. */
        val TARGET_KEYS: Map<FamilyKey, List<String>> =
            mapOf(
                FamilyKey.TEMPLATE to listOf(LIB, OTHER_TEMPLATE),
                FamilyKey.PIPELINE to listOf(DAILY, OTHER_PIPELINE),
                FamilyKey.PARAMETER_SET to listOf(FILTERS),
                FamilyKey.VISUALIZATION to listOf(CHART),
                FamilyKey.DATASOURCE to listOf(WAREHOUSE, LANDING),
            )
        val TARGET_VERSIONS: List<Int> = listOf(1, 2, VANISHED)

        private const val UNVERSIONED = "a schedule is unversioned (version 0, no lifecycle status): it is live until soft-deleted"
        private const val SCHEDULE_ONLY = "a schedule-only class: a versioned dependent's liveness is its version status"
        private const val BY_NAME = "a datasource is referenced by name, never at a version (erratum (7)): no version to vanish"

        /** The classes an arm cannot have, each with the reason (never silently skipped). */
        val IMPOSSIBLE: Map<ParityArm, Map<CorpusClass, String>> =
            ALL.associateWith { arm ->
                val scheduleOnly = listOf(LIVE_SCHEDULE, SOFT_DELETED, FOLLOWS_CURRENT).associateWith { SCHEDULE_ONLY }
                when (arm) {
                    ParityArm.PIPELINE_FROM_SCHEDULE -> {
                        listOf(CorpusClass.DRAFT, CorpusClass.RELEASED, DISCARDED_WITH_LIVE, ALL_DISCARDED).associateWith { UNVERSIONED } +
                            (REPEATED to "a schedule payload names ONE target (`/pipeline`)")
                    }

                    ParityArm.TEMPLATE_FROM_VISUALIZATION -> {
                        scheduleOnly + (REPEATED to "a visualization body holds ONE template pin (`transform.template`)")
                    }

                    ParityArm.PARAMETER_SET_FROM_DASHBOARD -> {
                        scheduleOnly + (REPEATED to "a dashboard body holds ONE parameter-set pin (`parameter_set`)")
                    }

                    ParityArm.PIPELINE_FROM_API_ENDPOINT -> {
                        scheduleOnly +
                            (REPEATED to "an API version stores ONE pipeline pin (the `pipeline_name`/`pipeline_version` columns)")
                    }

                    ParityArm.DATASOURCE_FROM_PIPELINE, ParityArm.DATASOURCE_FROM_PARAMETER_SET -> {
                        scheduleOnly + (VANISHED_VERSION to BY_NAME)
                    }

                    else -> {
                        scheduleOnly
                    }
                }
            }

        /** The reverse-only plants: both directions skip each (count 0 on both sides). */
        val IGNORED: List<Ignored> =
            listOf(1, 2).flatMap { version ->
                listOf(
                    Ignored(
                        ParityArm.TEMPLATE_FROM_PIPELINE,
                        RULES_PIPELINE,
                        version,
                        "nodes[2].template",
                        "a CALCULATOR node pins nothing",
                    ),
                    Ignored(
                        ParityArm.DATASOURCE_FROM_PIPELINE,
                        RULES_PIPELINE,
                        version,
                        "nodes[0].source",
                        "a SCRIPT node has no datasource source",
                    ),
                    Ignored(
                        ParityArm.DATASOURCE_FROM_PIPELINE,
                        RULES_PIPELINE,
                        version,
                        "nodes[1].source",
                        "`tempdb` is never a datasource",
                    ),
                )
            }
    }
}
