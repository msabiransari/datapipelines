package co.datapipelines.visualization

import co.datapipelines.pipeline.ScriptContractView
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/**
 * The mechanical visualization test (the spec's §11.3, D56 (b)) — server-run, no browser, sub-second:
 *
 * 1. **Schema** — `config` against the renderer's rules; a `chart` document against its v1 JSON Schema
 *    ([ChartDocumentRules], the save's layer 1 re-run over the stored body);
 * 2. **Binding** — every path resolves in `config` (a chart's bindings are field to field, never paths), every bound
 *    column is in the output contract, and the column's type is one the renderer accepts at that path
 *    ([BindingTypes], the renderer-specific table);
 * 3. **Fixture run** — through the pinned script ([TestFixtureEvaluator], the real bounded evaluator
 *    over `TemplateEvaluateService`), or, with no transform, the single input's fixture values through the
 *    same wire-form rules; every bound column must be present in every row's projection, and a present
 *    null passes only when the output column is nullable;
 * 4. **Assertion feasibility** — for a `chart` over its [ChartModel] (design record 2026-11-06 §5.1): `mark_count`
 *    against the model's drawn cells and `no_data` against an empty model, both DISPROVABLE; `text_visible` is
 *    PROVEN by a configuration string or the model's texts and otherwise UNJUDGED — listed on the report, never a
 *    failure (only the browser judges a formatted label). For table and KPI: `no_data` against zero produced rows,
 *    `text_visible`/`value_visible` strings present in the configuration or the bound values;
 * 5. **Rendered state** — [RenderedStateCheck]; the default records `not_available` and never claims a
 *    browser saw anything (a headless render check is a later lane).
 *
 * It never runs a live source pipeline, never queries a datasource and never executes agent code. A DRAFT
 * transform pin is valid HERE (this is the authoring path); published dashboard runtime requires RELEASED pins;
 * draft preview admits live draft pins. The report is the `mechanical_json` stored on the run and re-run at
 * release — failures carry the
 * catalogued code and the path, and `ok` is true only when every step found nothing.
 */
class VisualizationMechanicalCheck(
    private val renderers: RendererConfigValidator,
    private val fixtures: TestFixtureEvaluator,
    private val templates: TemplateContractFacts,
    private val rendered: RenderedStateCheck = RenderedStateCheck.NOT_AVAILABLE,
    private val charts: ChartDocumentRules = ChartDocumentRules.DEFAULT,
) {
    /** One mechanical failure: the step that found it, the catalogued code, the path, and the case when per-case. */
    data class Failure(
        val step: String,
        val code: String,
        val path: String,
        val message: String,
        val case: String? = null,
    ) {
        fun toJson(): JsonNode =
            ArtifactJson.mapper.createObjectNode().apply {
                put("step", step)
                put("code", code)
                put("path", path)
                put("message", message)
                case?.let { put("case", it) }
            }
    }

    /**
     * The failures a run records, BOUNDED (the 352 merge's F2): one failure per row per binding over the fixture
     * caps (200 cases × 100,000 rows × 64 bindings) would otherwise build millions of entries, store them on the
     * run, return them from submit, re-read them with every run list and rebuild them inside the release
     * transaction — an author-reachable heap and storage bloat. The first [limit] are kept; the rest are COUNTED
     * in [dropped], and a sink with dropped failures is never empty, so the report is never `ok`.
     */
    class FailureSink(
        private val limit: Int,
    ) {
        private val kept = ArrayList<Failure>()

        /** Failures beyond [limit] — counted, not kept. */
        var dropped: Int = 0
            private set

        val size: Int get() = kept.size

        fun isEmpty(): Boolean = kept.isEmpty() && dropped == 0

        fun toList(): List<Failure> = kept.toList()

        operator fun plusAssign(failure: Failure) {
            if (kept.size < limit) kept += failure else dropped++
        }

        operator fun plusAssign(other: FailureSink) {
            other.kept.forEach { this += it }
            dropped += other.dropped
        }
    }

    /**
     * A chart assertion the server cannot judge (design record §5.1): `text_visible` whose text is in no configuration
     * string and none of the model's texts — a formatted label. Not a failure: the browser judges it in the session.
     */
    data class Unjudged(
        val case: String,
        val path: String,
        val kind: String,
    ) {
        fun toJson(): JsonNode =
            ArtifactJson.mapper.createObjectNode().apply {
                put("case", case)
                put("path", path)
                put("kind", kind)
            }
    }

    /** The §11.3 outcome — the run's `mechanical_json`, verbatim. */
    data class Report(
        val ranAt: Instant,
        val ok: Boolean,
        val failures: List<Failure>,
        /** Per case: the produced row count, the rendered-state claim and whether the case found nothing. */
        val cases: Map<String, CaseReport>,
        /** Failures beyond [MAX_FAILURES], counted (`failures_dropped`); non-zero means the list is a prefix. */
        val dropped: Int = 0,
        /** The chart assertions only the browser can judge; they never make [ok] false. */
        val unjudged: List<Unjudged> = emptyList(),
    ) {
        data class CaseReport(
            val rows: Int,
            val ok: Boolean,
            val rendered: String,
        )

        fun toJson(): JsonNode =
            ArtifactJson.mapper.createObjectNode().apply {
                put("ran_at", ranAt.toString())
                put("ok", ok)
                put("failures_dropped", dropped)
                set<JsonNode>(
                    "failures",
                    ArtifactJson.mapper.createArrayNode().addAll(failures.map { it.toJson() }),
                )
                set<JsonNode>(
                    "unjudged",
                    ArtifactJson.mapper.createArrayNode().addAll(unjudged.map { it.toJson() }),
                )
                set<JsonNode>(
                    "cases",
                    ArtifactJson.mapper.createObjectNode().apply {
                        cases.forEach { (name, report) ->
                            putObject(name).apply {
                                put("rows", report.rows)
                                put("ok", report.ok)
                                put("rendered", report.rendered)
                            }
                        }
                    },
                )
            }
    }

    /**
     * Runs the five steps over [body]. Every step runs; the failures accumulate, so one refusal never
     * hides another (the validator's all-failures rule, at mechanical time).
     */
    fun run(
        workspaceId: UUID,
        body: VisualizationBody,
        now: Instant = Instant.now(),
    ): Report {
        val failures = FailureSink(MAX_FAILURES)
        schema(body, failures)
        val output = outputColumns(workspaceId, body)
        bindings(body, output, failures)
        val cases = fixtureRun(workspaceId, body, output, failures)
        val unjudged = mutableListOf<Unjudged>()
        assertions(body, cases, failures, unjudged)
        val caseReports =
            cases.entries.associate { (name, outcome) ->
                name to Report.CaseReport(outcome.rows, outcome.failures.isEmpty(), rendered.state(workspaceId, body, name))
            }
        return Report(now, failures.isEmpty(), failures.toList(), caseReports, failures.dropped, unjudged)
    }

    // ---- step 1: schema -------------------------------------------------------------------------------

    private fun schema(
        body: VisualizationBody,
        failures: FailureSink,
    ) {
        if (!body.renderer.kind.renderable) return // reserved kinds are refused at save, never reach a session
        if (body.renderer.kind == RendererKind.CHART) {
            val tree: JsonNode = ArtifactJson.mapper.valueToTree(body)
            charts.schema(tree).forEach {
                failures +=
                    Failure(
                        "schema",
                        VisualizationErrorCodes.CONFIG_SCHEMA_INVALID,
                        ChartPaths.dotted(tree, it.pointer),
                        "The chart document fails the v1 schema's '${it.keyword}' keyword here.",
                    )
            }
            return
        }
        renderers.validate(body.renderer.kind, body.config).forEach {
            failures += Failure("schema", VisualizationErrorCodes.CONFIG_SCHEMA_INVALID, JsonScan.join("config", it.path), it.message)
        }
    }

    // ---- step 2: bindings -----------------------------------------------------------------------------

    /** The columns a binding may name, WITH type and nullability — the pin's output, or the single input's. */
    private fun outputColumns(
        workspaceId: UUID,
        body: VisualizationBody,
    ): Map<String, ScriptContractView.Column> {
        val transform =
            body.transform ?: return body.inputs.values
                .singleOrNull()
                ?.columns
                ?.associate { it.name to ScriptContractView.Column(it.name, it.type, it.nullable) } ?: emptyMap()
        return when (val pin = templates.pinOf(workspaceId, transform.template)) {
            is TemplatePin.Transform -> {
                (pin.contract.output as? ScriptContractView.Output.Table)?.columns?.associateBy { it.name } ?: emptyMap()
            }

            // The pin died after save; the fixture run's evaluator refusal names it per case.
            else -> {
                emptyMap()
            }
        }
    }

    private fun bindings(
        body: VisualizationBody,
        output: Map<String, ScriptContractView.Column>,
        failures: FailureSink,
    ) {
        // A chart's bindings are derived field-to-field maps (design record §4.4), not config paths: only the contract check.
        val paths = body.renderer.kind != RendererKind.CHART
        body.bindings.forEach { (path, column) ->
            val at = "bindings.$path"
            val steps = BindingPath.parse(path)
            val pathProblem =
                when {
                    !paths -> null
                    steps == null -> "Not a binding path."
                    !BindingPath.resolves(body.config, steps) -> "Does not resolve inside config."
                    else -> null
                }
            pathProblem?.let { failures += Failure("bindings", VisualizationErrorCodes.BINDING_UNBOUND, at, it) }
            val type = output[column]?.type
            when {
                type == null -> {
                    failures += Failure("bindings", VisualizationErrorCodes.BINDING_UNBOUND, at, "Column not in the output contract.")
                }

                !BindingTypes.accepts(body.renderer.kind, path, type) -> {
                    failures +=
                        Failure(
                            "bindings",
                            VisualizationErrorCodes.BINDING_UNBOUND,
                            at,
                            "Column type ${type.wire} is not accepted at this path.",
                        )
                }
            }
        }
    }

    // ---- step 3: the fixture run ----------------------------------------------------------------------

    private class CaseOutcome(
        val name: String,
        var rows: Int = 0,
        var boundValues: List<String> = emptyList(),
        val failures: FailureSink = FailureSink(MAX_FAILURES),
        /** A chart case's model over its post-transform rows; null for other kinds and for a case without rows. */
        var chart: ChartModel? = null,
    )

    private fun fixtureRun(
        workspaceId: UUID,
        body: VisualizationBody,
        output: Map<String, ScriptContractView.Column>,
        global: FailureSink,
    ): Map<String, CaseOutcome> {
        val cases = body.tests?.cases ?: return emptyMap()
        val outcomes = cases.mapIndexed { index, case -> runCase(workspaceId, body, output, case, index) }
        outcomes.forEach { global += it.failures }
        return outcomes.associateBy { it.name }
    }

    private fun runCase(
        workspaceId: UUID,
        body: VisualizationBody,
        output: Map<String, ScriptContractView.Column>,
        case: TestCase,
        index: Int,
    ): CaseOutcome {
        val outcome = CaseOutcome(case.name)
        val transform = body.transform
        if (transform == null) {
            val rows =
                case.fixtures.values
                    .singleOrNull()
                    .orEmpty()
            FixtureValues.validate(body, case, index, rows, outcome.failures)
            outcome.rows = rows.size
            val rowMaps = rows.map(::toRowMap)
            outcome.boundValues = boundValues(body.bindings.values, rowMaps)
            chartModel(body, output, rowMaps, index, outcome)
        } else {
            when (val evaluation = fixtures.evaluate(workspaceId, transform.template, case.fixtures)) {
                is FixtureEvaluation.Refused -> {
                    outcome.failures += Failure("fixtures", evaluation.code, "tests.cases[$index]", evaluation.message, case.name)
                }

                is FixtureEvaluation.Rows -> {
                    outcome.rows = evaluation.rows.size
                    outcome.boundValues = boundValues(body.bindings.values, evaluation.rows)
                    projectedRows(body, output, evaluation.rows, index, outcome)
                    chartModel(body, output, evaluation.rows, index, outcome)
                }
            }
        }
        return outcome
    }

    /**
     * A chart case's [ChartModel] over the case's post-transform rows, typed by the contract the save rules read. A
     * case whose rows already failed the fixture step gets no model: a count over rows outside their contract would
     * be a second, misleading refusal of the same defect (its `mark_count`/`no_data` are judged on the next run).
     */
    private fun chartModel(
        body: VisualizationBody,
        output: Map<String, ScriptContractView.Column>,
        rows: List<Map<String, Any?>>,
        caseIndex: Int,
        outcome: CaseOutcome,
    ) {
        if (body.renderer.kind != RendererKind.CHART || !outcome.failures.isEmpty()) return
        try {
            outcome.chart = ChartModel.of(body.config, output.mapValues { it.value.type }, rows)
        } catch (refused: ChartModel.NotAMeasure) {
            outcome.failures +=
                Failure(
                    "fixtures",
                    VisualizationErrorCodes.TEST_CASE_INVALID,
                    "tests.cases[$caseIndex]",
                    refused.message ?: "A measure value is outside its wire form.",
                    outcome.name,
                )
        }
    }

    private fun projectedRows(
        body: VisualizationBody,
        output: Map<String, ScriptContractView.Column>,
        rows: List<Map<String, Any?>>,
        caseIndex: Int,
        outcome: CaseOutcome,
    ) {
        body.bindings.forEach { (path, column) ->
            rows.forEachIndexed { rowIndex, row ->
                val at = "tests.cases[$caseIndex].rows[$rowIndex].$column"
                when {
                    !row.containsKey(column) -> {
                        outcome.failures +=
                            Failure(
                                "fixtures",
                                VisualizationErrorCodes.BINDING_UNBOUND,
                                at,
                                "The bound column is missing from the row that $path consumes.",
                                outcome.name,
                            )
                    }

                    row[column] == null && output[column]?.nullable == false -> {
                        outcome.failures +=
                            Failure("fixtures", VisualizationErrorCodes.TEST_CASE_INVALID, at, "The column is not nullable.", outcome.name)
                    }
                }
            }
        }
    }

    /**
     * Bound strings and numbers' existing scalar text from [rows], without formatting or normalization.
     * Only the bindings' columns participate; nulls and unsupported values supply no assertion text.
     */
    private fun boundValues(
        boundColumns: Collection<String>,
        rows: List<Map<String, Any?>>,
    ): List<String> =
        rows.flatMap { row ->
            boundColumns.mapNotNull { column ->
                when (val value = row[column]) {
                    is String -> value
                    is Number -> value.toString()
                    else -> null
                }
            }
        }

    /** One stored fixture row, as the evaluator and the text check read it. */
    private fun toRowMap(row: ObjectNode): Map<String, Any?> =
        ArtifactJson.mapper.convertValue(
            row,
            object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {},
        )

    // ---- step 4: assertion feasibility ----------------------------------------------------------------

    private fun assertions(
        body: VisualizationBody,
        cases: Map<String, CaseOutcome>,
        failures: FailureSink,
        unjudged: MutableList<Unjudged>,
    ) {
        val bodyCases = body.tests?.cases ?: return
        val configStrings = stringsOf(body.config)
        val chart = body.renderer.kind == RendererKind.CHART
        bodyCases.forEachIndexed { index, case ->
            val outcome = cases[case.name] ?: return@forEachIndexed
            case.assertions.forEachIndexed { assertionIndex, assertion ->
                val at = "tests.cases[$index].assertions[$assertionIndex]"
                when (assertion.kind) {
                    AssertionKind.MARK_COUNT -> {
                        markCount(chart, outcome, assertion, at, case.name, failures)
                    }

                    AssertionKind.NO_DATA -> {
                        noData(chart, outcome, at, case.name, failures)
                    }

                    AssertionKind.TEXT_VISIBLE, AssertionKind.VALUE_VISIBLE -> {
                        if (chart) {
                            val text = assertion.text
                            val proven = text != null && (configStrings.any { text in it } || outcome.chart?.proves(text) == true)
                            if (!proven) unjudged += Unjudged(case.name, at, assertion.kind.wire)
                        } else {
                            textPresent(configStrings, outcome, assertion, at, case.name, failures)
                        }
                    }

                    // Step 5's assertions are a later lane's; the state is RECORDED, never judged here.
                    AssertionKind.RENDERED, AssertionKind.NO_CONSOLE_ERRORS -> {
                        recordedNotJudged()
                    }
                }
            }
        }
    }

    /**
     * `mark_count` (design record §5.1): the chart model's drawn cells — distinct band × series pairs with a non-null
     * aggregate. A case whose rows never reached a model (an evaluator refusal, a measure outside its wire form) already
     * failed; it is not judged twice.
     */
    private fun markCount(
        chart: Boolean,
        outcome: CaseOutcome,
        assertion: Assertion,
        at: String,
        case: String,
        failures: FailureSink,
    ) {
        if (!chart) {
            failures += Failure("assertions", VisualizationErrorCodes.TEST_CASE_INVALID, at, "mark_count needs a chart document.", case)
            return
        }
        val marks = outcome.chart?.markCount ?: return
        if (assertion.equals != null && assertion.equals != marks) {
            failures +=
                Failure(
                    "assertions",
                    VisualizationErrorCodes.TEST_CASE_INVALID,
                    at,
                    "The case's rows draw $marks marks; the case asserts ${assertion.equals}.",
                    case,
                )
        }
    }

    /** Step 5's assertions are a later lane's; the rendered state is RECORDED per case, never judged here. */
    private fun recordedNotJudged() {
        // deliberately empty: the no-op rendered-state check's answer is recorded, not judged
    }

    /**
     * `no_data`: zero produced rows — for a chart, an empty model (no rows, or every aggregate null; record §5.1); a
     * chart case without a model already failed and is not judged twice.
     */
    private fun noData(
        chart: Boolean,
        outcome: CaseOutcome,
        at: String,
        case: String,
        failures: FailureSink,
    ) {
        val model = outcome.chart
        if (chart && model == null) return
        if (model != null && !model.noData) {
            failures +=
                Failure(
                    "assertions",
                    VisualizationErrorCodes.TEST_CASE_INVALID,
                    at,
                    "The case expects the empty state; its rows draw ${model.markCount} marks.",
                    case,
                )
        } else if (model == null && outcome.rows != 0) {
            failures +=
                Failure(
                    "assertions",
                    VisualizationErrorCodes.TEST_CASE_INVALID,
                    at,
                    "The case expects the empty state; the fixture run produced ${outcome.rows} rows.",
                    case,
                )
        }
    }

    private fun textPresent(
        configStrings: List<String>,
        outcome: CaseOutcome,
        assertion: Assertion,
        at: String,
        case: String,
        failures: FailureSink,
    ) {
        val text = assertion.text
        if (text != null && configStrings.none { it.contains(text) } && outcome.boundValues.none { it.contains(text) }) {
            failures +=
                Failure(
                    "assertions",
                    VisualizationErrorCodes.TEST_CASE_INVALID,
                    at,
                    "The text appears neither in the configuration nor in the bound values.",
                    case,
                )
        }
    }

    /** The config's string values, bounded — a config is ≤ 256 KiB but the walk still counts nodes. */
    private fun stringsOf(
        node: JsonNode,
        collect: MutableList<String> = mutableListOf(),
        budget: Int = MAX_TEXT_SCAN,
    ): List<String> {
        if (collect.size >= budget) return collect
        when {
            node.isTextual -> collect += node.asText()
            node.isObject -> node.forEach { stringsOf(it, collect, budget) }
            node.isArray -> node.forEach { stringsOf(it, collect, budget) }
        }
        return collect
    }

    companion object {
        /** The static text scan's node bound. */
        private const val MAX_TEXT_SCAN = 10_000

        /** The failures a report KEEPS; beyond it they are counted (`failures_dropped`), never built. */
        const val MAX_FAILURES = 100
    }
}

/**
 * The no-transform case's fixture-value rules — the SAME wire-form rules the type gate applies to a
 * transform's input rows, applied to the single input's fixtures (the spec's §11.3 step 3, the brief's §B).
 * A non-nullable column must carry a value and every value must be in its column's wire form.
 */
private object FixtureValues {
    fun validate(
        body: VisualizationBody,
        case: TestCase,
        caseIndex: Int,
        rows: List<ObjectNode>,
        failures: VisualizationMechanicalCheck.FailureSink,
    ) {
        val input = body.inputs.values.singleOrNull() ?: return
        rows.forEachIndexed { rowIndex, row ->
            input.columns.forEach { column ->
                val value = row.get(column.name)
                val at = "tests.cases[$caseIndex].fixtures[${case.fixtures.keys.first()}][$rowIndex].${column.name}"
                when {
                    !column.nullable && (value == null || value.isNull) -> {
                        failures += refusal(at, "The column is not nullable.", case.name)
                    }

                    value != null && !value.isNull && !inWireForm(column.type, value) -> {
                        failures += refusal(at, "The value is not in ${column.type.wire}'s wire form.", case.name)
                    }
                }
            }
        }
    }

    private fun refusal(
        at: String,
        message: String,
        case: String,
    ) = VisualizationMechanicalCheck.Failure(
        "fixtures",
        VisualizationErrorCodes.TEST_CASE_INVALID,
        at,
        message,
        case,
    )

    /** The type system's wire form (type-system §3): what JSON shape a LogicalType's values travel as. */
    private fun inWireForm(
        type: LogicalType,
        value: JsonNode,
    ): Boolean =
        when (type.wireForm) {
            co.datapipelines.typesystem.JsonWireForm.NUMBER -> value.isNumber
            co.datapipelines.typesystem.JsonWireForm.STRING -> value.isTextual
            co.datapipelines.typesystem.JsonWireForm.BOOLEAN -> value.isBoolean
            co.datapipelines.typesystem.JsonWireForm.NULL -> true
        }
}

/**
 * The renderer-specific type table (the spec's §11.3 step 2). Table and KPI accept canonical scalars, preserving KPI's
 * BIG wire strings; a chart's field types are its save rules' (`chart.measure.not_numeric`, `chart.field.binary`, … —
 * [ChartSaveRules] over the same contract), so every type passes here. Pinned by test — changing a row of this table
 * is a spec amendment, not a refactor.
 */
object BindingTypes {
    /** True when [type] may bind a column at [path] for [renderer]. Reserved renderers refuse bindings. */
    @Suppress("UNUSED_PARAMETER", "UnusedParameter") // path and type are the table's axes; no current row reads them
    fun accepts(
        renderer: RendererKind,
        path: String,
        type: LogicalType,
    ): Boolean =
        when (renderer) {
            RendererKind.CHART, RendererKind.TABLE, RendererKind.KPI -> true
            RendererKind.HTML, RendererKind.SVG -> false
        }
}

/** The fixture-run port: the real bounded evaluator behind it lives in `web` (over `TemplateEvaluateService`). */
fun interface TestFixtureEvaluator {
    /**
     * Evaluates one case's fixtures through the pinned script. A refusal carries the evaluator's own
     * catalogued code — the mechanical report stores it verbatim. DRAFT pins are valid here (authoring).
     */
    fun evaluate(
        workspaceId: UUID,
        pin: ArtifactRef,
        fixtures: Map<String, List<ObjectNode>>,
    ): FixtureEvaluation
}

/** The fixture evaluator's two answers. */
sealed interface FixtureEvaluation {
    /** The transform's output rows — every bound column is checked against each. */
    data class Rows(
        val rows: List<Map<String, Any?>>,
    ) : FixtureEvaluation

    /** A refusal with the evaluator's own code (the input check, the type gate, the engine). */
    data class Refused(
        val code: String,
        val message: String,
    ) : FixtureEvaluation
}

/** The rendered-state port (the spec's §11.3 step 5): what a browser check would report, per case. */
fun interface RenderedStateCheck {
    /**
     * The state string recorded on the case's mechanical report. The default records `not_available` —
     * never success, never a claim that a renderer ran (the spec's §15: the no-op is pinned by a test).
     */
    fun state(
        workspaceId: UUID,
        body: VisualizationBody,
        case: String,
    ): String

    companion object {
        /** The production default until a headless render check lands (L6). */
        val NOT_AVAILABLE: RenderedStateCheck = RenderedStateCheck { _, _, _ -> "not_available" }
    }
}
