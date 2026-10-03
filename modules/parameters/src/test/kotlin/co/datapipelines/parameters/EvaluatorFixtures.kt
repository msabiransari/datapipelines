package co.datapipelines.parameters

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A SCRIPTED selector runtime for the evaluator's unit suites: each template id answers a function of
 * the request (so a state selector can answer by its country bind), and every request, start and end
 * is RECORDED — the ordering and the binds are assertions, never assumed (MISTAKES: a recording double
 * makes the missing call visible). Unscripted ids answer no rows.
 */
internal class ScriptedSelectors : SelectorTasks {
    private val scripts = ConcurrentHashMap<String, (SelectorRequest) -> SelectorRun>()

    val requests = CopyOnWriteArrayList<SelectorRequest>()

    /** `start:<template id>` / `end:<template id>`, in the order they happened across threads. */
    val events = CopyOnWriteArrayList<String>()

    val resolvers = AtomicInteger()

    operator fun set(
        templateId: String,
        script: (SelectorRequest) -> SelectorRun,
    ) {
        scripts[templateId] = script
    }

    fun requestsFor(templateId: String): List<SelectorRequest> = requests.filter { it.template.id == templateId }

    override fun resolver(workspaceId: UUID): DatasourceResolver {
        resolvers.incrementAndGet()
        return DatasourceResolver { null }
    }

    override fun task(
        request: SelectorRequest,
        resolver: DatasourceResolver,
    ): SelectorTask =
        object : SelectorTask {
            override fun run(): SelectorRun {
                requests += request
                events += "start:${request.template.id}"
                try {
                    return (scripts[request.template.id] ?: { options() }).invoke(request)
                } finally {
                    events += "end:${request.template.id}"
                }
            }

            override fun abandon() = Unit
        }

    companion object {
        val SELECT_COLUMNS =
            listOf(
                ColumnSchema("value", LogicalType.STRING),
                ColumnSchema("display_value", LogicalType.STRING),
                ColumnSchema("is_default", LogicalType.BOOLEAN),
            )

        /** `SELECT` rows of STRING values: each `value to label`, the one named [default] marked. */
        fun options(
            vararg values: String,
            default: String? = null,
        ): SelectorRun.Rows = SelectorRun.Rows(SELECT_COLUMNS, values.map { listOf(it, it.lowercase(), it == default) })

        /** An `INPUT` source's rows — one column `value` of [type]. */
        fun inputRows(
            type: LogicalType,
            vararg values: Any?,
        ): SelectorRun.Rows = SelectorRun.Rows(listOf(ColumnSchema("value", type)), values.map { listOf(it) })
    }
}

/** Parameter sets as stored versions — the JSON the author writes, read by the real reader. */
internal object EvaluatorFixtures {
    val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-00000000e194")
    val SET_ID: UUID = UUID.fromString("05e70000-0000-0000-0000-00000000e194")

    fun version(
        vararg parameters: String,
        name: String = "acme/sales/region_filters",
        workspace: UUID = WORKSPACE,
    ): ParameterSetVersion {
        val document = ParameterSetReader().readOrThrow(ParameterSetFixtures.tree(ParameterSetFixtures.setJson(*parameters, name = name)))
        val now = Instant.parse("2026-09-27T00:00:00Z")
        return ParameterSetVersion(
            record = ParameterSetRecord(SET_ID, workspace, document.name, document.body.displayName, "", 4, now, now, workspace),
            detail = ParameterSetVersionDetail(SET_ID, 4, PipelineVersionStatus.RELEASED, "hash", now, WORKSPACE),
            body = document.body,
        )
    }

    /** The fixtures' evaluator user — a person, so the attempt carries no key. */
    val USER: UUID = UUID.fromString("a0740000-0000-0000-0000-000000000194")

    /** An attempt for [caller] (#376: every evaluate names one); a fresh evaluation id per call. */
    fun attempt(caller: EvaluationCaller = EvaluationCaller.REST): EvaluationAttempt = EvaluationAttempt.of(caller, USER, keyId = null)

    fun selections(vararg pairs: Pair<String, Any?>): Map<String, JsonNode?> =
        pairs.associate { (name, value) -> name to ParameterSetJson.mapper.valueToTree<JsonNode>(value) }

    /** A template-backed SELECT named [name] over template [template] (datasource `warehouse`). */
    fun templateSelect(
        name: String,
        template: String = "acme/sales/$name.sql",
        dependsOn: List<String> = emptyList(),
        required: Boolean = false,
        cardinality: String = "SINGLE",
        defaultValue: String? = null,
        extra: String = "",
    ): String =
        """
        { "name": "$name", "label": "${name.replaceFirstChar { it.uppercase() }}", "type": "STRING", "kind": "SELECT",
          "cardinality": "$cardinality", "required": $required,
          ${defaultValue?.let { "\"default_value\": $it," } ?: ""}
          "source": { "template": { "id": "$template", "version": 1 }, "datasource": "warehouse" },
          "depends_on": [ ${dependsOn.joinToString(",") { "\"$it\"" }} ] $extra }
        """.trimIndent()

    /** The country constants select (USA marked default), required. */
    fun country(): String = ParameterSetFixtures.countryJson()

    /** A database-fed DATE input depending on [dependsOn]. */
    fun startDate(
        required: Boolean = false,
        defaultValue: String? = null,
        dependsOn: List<String> = listOf("country"),
    ): String =
        """
        { "name": "start_date", "label": "Start date", "type": "DATE", "kind": "INPUT", "required": $required,
          ${defaultValue?.let { "\"default_value\": \"$it\"," } ?: ""}
          "source": { "template": { "id": "acme/sales/start_date.sql", "version": 1 }, "datasource": "warehouse" },
          "depends_on": [ ${dependsOn.joinToString(",") { "\"$it\"" }} ] }
        """.trimIndent()

    /**
     * The owner's twenty-parameter scenario set (record §5.2): country (constants, required) → state
     * (template, required — a parent that is also a child) → city (template); a MULTI region picker
     * and a store selector binding it; a database-fed start date; the §3.6 amount; and a chain of
     * constants selects and text inputs to make twenty.
     */
    fun twenty(): Array<String> =
        arrayOf(
            country(),
            templateSelect("state", dependsOn = listOf("country"), required = true),
            templateSelect("city", dependsOn = listOf("state")),
            templateSelect("regions", dependsOn = listOf("country"), cardinality = "MULTI"),
            templateSelect("store", dependsOn = listOf("regions")),
            startDate(),
            ParameterSetFixtures.amountJson(),
            ParameterSetFixtures.constantsSelect("segment", listOf("retail", "wholesale", "online"), firstDefault = true),
            ParameterSetFixtures.constantsSelect("channel", listOf("web", "store"), dependsOn = listOf("segment")),
            ParameterSetFixtures.constantsSelect("tier", listOf("gold", "silver"), dependsOn = listOf("channel")),
            ParameterSetFixtures.constantsSelect("currency", listOf("usd", "cad"), firstDefault = true),
            ParameterSetFixtures.constantsSelect("metric", listOf("revenue", "units", "margin")),
            ParameterSetFixtures.constantsSelect("period", listOf("day", "week", "month"), firstDefault = true),
            ParameterSetFixtures.constantsSelect("compare", listOf("none", "prior"), dependsOn = listOf("period")),
            ParameterSetFixtures.textInput("note"),
            ParameterSetFixtures.textInput("tag", dependsOn = listOf("segment")),
            ParameterSetFixtures.constantsSelect("flags", listOf("a", "b", "c"), cardinality = "MULTI"),
            ParameterSetFixtures.textInput("owner"),
            ParameterSetFixtures.constantsSelect("sort", listOf("asc", "desc"), firstDefault = true),
            ParameterSetFixtures.textInput("label_text"),
        )

    /** The scripted warehouse the twenty-parameter set reads: states by country, cities by state, regions, stores, the start date. */
    fun warehouse(selectors: ScriptedSelectors = ScriptedSelectors()): ScriptedSelectors =
        selectors.apply {
            this["acme/sales/state.sql"] = { request ->
                when (request.binds["country"]) {
                    "USA" -> ScriptedSelectors.options("NY", "NJ", "CA", default = "NY")
                    "CAN" -> ScriptedSelectors.options("ON", "QC")
                    else -> ScriptedSelectors.options()
                }
            }
            this["acme/sales/city.sql"] = { request ->
                when (request.binds["state"]) {
                    "NY" -> ScriptedSelectors.options("NYC", "BUF")
                    "NJ" -> ScriptedSelectors.options("NWK")
                    "ON" -> ScriptedSelectors.options("TOR")
                    "QC" -> ScriptedSelectors.options("MTL")
                    else -> ScriptedSelectors.options()
                }
            }
            this["acme/sales/regions.sql"] = { request ->
                if (request.binds["country"] ==
                    "USA"
                ) {
                    ScriptedSelectors.options("EAST", "WEST", "SOUTH", default = "EAST")
                } else {
                    ScriptedSelectors.options("NORTH")
                }
            }
            this["acme/sales/store.sql"] = { request ->
                @Suppress("UNCHECKED_CAST")
                val regions = request.binds["regions"] as List<String>
                ScriptedSelectors.options(*regions.map { "S-$it" }.toTypedArray())
            }
            this["acme/sales/start_date.sql"] = { request ->
                ScriptedSelectors.inputRows(
                    LogicalType.DATE,
                    if (request.binds["country"] ==
                        "USA"
                    ) {
                        java.time.LocalDate.parse("2026-01-01")
                    } else {
                        java.time.LocalDate.parse("2026-04-01")
                    },
                )
            }
        }
}
