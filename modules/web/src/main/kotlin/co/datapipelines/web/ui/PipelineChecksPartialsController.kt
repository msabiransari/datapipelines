package co.datapipelines.web.ui

import co.datapipelines.application.checks.PipelineCheckRun
import co.datapipelines.application.checks.PipelineCheckRunRepository
import co.datapipelines.application.checks.PipelineCheckRunner
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.CheckExpectation
import co.datapipelines.pipeline.CheckRunOutcome
import co.datapipelines.pipeline.CheckRunVerdict
import co.datapipelines.pipeline.CheckRunVia
import co.datapipelines.pipeline.PipelineCheck
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineService
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.CorrelationId
import co.datapipelines.web.api.currentPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The release checks' UI fragments (140, ui-screens §4.3b/§4.3d): ONE partial,
 * `partials/pipeline-checks`, listing a version's check definitions each with its latest run,
 * and the run that refreshes it.
 *
 * - `GET …/checks` is the read-only half — the definitions beside the latest
 *   `pipeline_check_runs` row per check (metadata-db §4.20), `latest: null` rendered as
 *   "not run". The explorer detail's Checks section and the editor's Details pane lazy-load it.
 * - `POST …/checks/run` commissions a fresh run on the one shared [PipelineCheckRunner]
 *   (`via = ui`, the session principal, the request's correlation id) and re-renders the SAME
 *   partial from the fresh outcomes — the fragment a run returns and the fragment a selection
 *   reads can never disagree about the shape of a row.
 *
 * Both are session-only, the lifecycle dialogs' posture: an API key already has the REST twins
 * (rest-api §5.16), so the browser surface refuses it `auth.session.required` rather than
 * opening a second, CSRF-exempt way to commission runs. A run the dialog commissions
 * (`?footer=release`, §4.3d) additionally splices the release dialog's submit footer in
 * out-of-band: all pass enables Release, anything short of PASS renders the override
 * disclosure instead — the fragment decides, the dialog only holds the placeholder.
 */
@Controller
class PipelineChecksPartialsController(
    private val pipelines: PipelineService,
    private val checkRunner: PipelineCheckRunner,
    private val checkRuns: PipelineCheckRunRepository,
) {
    @GetMapping("/partials/pipelines/{id}/versions/{version}/checks")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun checks(
        model: Model,
        @PathVariable id: UUID,
        @PathVariable version: Int,
    ): String {
        val workspaceId = requireChecksSession().requireWorkspace().id
        val definitions = definitions(workspaceId, id, version)
        val latest = checkRuns.latestPerCheck(id, version).associateBy { it.checkId }
        fill(model, id, version, definitions.map { CheckEntryView.of(it, latest[it.id]) }, releaseFooter = false)
        return VIEW
    }

    @PostMapping("/partials/pipelines/{id}/versions/{version}/checks/run")
    @RequiredScope(ScopeMatrix.RestOperation.EXECUTE_PIPELINE)
    fun run(
        model: Model,
        @PathVariable id: UUID,
        @PathVariable version: Int,
        @RequestParam(required = false) footer: String?,
    ): String {
        val principal = requireChecksSession()
        val workspaceId = principal.requireWorkspace().id
        val outcomes =
            checkRunner.run(
                workspaceId,
                id,
                version,
                emptyMap(),
                CheckRunVia.UI,
                principal.userId,
                CorrelationId.current(),
            ) ?: throw notFound(id)
        // The outcome carries no datasource name (a run never echoes the definition back), so
        // the rows are zipped against the same body the runner just read — one read, one truth.
        val byId = definitions(workspaceId, id, version).associateBy { it.id }
        fill(
            model,
            id,
            version,
            outcomes.map { CheckEntryView.of(it, byId[it.checkId]?.datasource ?: "") },
            releaseFooter = footer == FOOTER_RELEASE,
        )
        return VIEW
    }

    /** The version's declared checks, or the house 404 ([notFound] below). */
    private fun definitions(
        workspaceId: UUID,
        id: UUID,
        version: Int,
    ): List<PipelineCheck> {
        val record = pipelines.findRecord(workspaceId, id) ?: throw notFound(id)
        val executable = pipelines.findExecutable(workspaceId, record, version) ?: throw notFound(id)
        return executable.pipeline.checks
    }

    private fun fill(
        model: Model,
        id: UUID,
        version: Int,
        entries: List<CheckEntryView>,
        releaseFooter: Boolean,
    ) {
        // The footer's submits are RELEASE_VERSION verbs rendered by a fragment fetched on
        // the checks routes, so the ROLE travels in the model (114 §B: the role decides what
        // is rendered — the release dialog's own launcher is the route guard).
        RoleModel.stamp(model)
        val failing = entries.filter { it.verdict != null && it.verdict != CheckRunVerdict.PASS }
        model.addAttribute("pipelineId", id)
        model.addAttribute("version", version)
        model.addAttribute("checks", entries)
        model.addAttribute("hasChecks", entries.isNotEmpty())
        model.addAttribute("allPass", entries.isNotEmpty() && failing.isEmpty() && entries.all { it.verdict != null })
        model.addAttribute("anyFailing", failing.isNotEmpty())
        model.addAttribute("failingIds", failing.map { it.checkId })
        model.addAttribute("releaseFooter", releaseFooter)
    }

    /**
     * The session gate, [co.datapipelines.web.pipelines.LifecycleVerbs.requireSession]'s shape
     * with this surface's own message: the checks UI is a human surface; keys use rest-api §5.16.
     */
    private fun requireChecksSession(): AuthenticatedPrincipal =
        currentPrincipal().also {
            if (it.authMethod != AuthMethod.OIDC) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Auth.SESSION_REQUIRED,
                    message =
                        "Running release checks from the browser requires an interactive session — " +
                            "API keys use the REST checks endpoints.",
                    details = mapOf("auth_method" to it.authMethod.name),
                )
            }
        }

    /**
     * The house 404 — the same code the REST twins answer through `ApiErrors.pipelineNotFound`
     * (`ApiErrorCatalog` maps it to 404); `pipeline.validation.pipeline_not_found` would be a
     * 400, which a missing pipeline is not.
     */
    private fun notFound(id: UUID) =
        DatapipelinesException(
            code = PipelineErrorCodes.Execution.NOT_FOUND,
            message = "Pipeline '$id' not found.",
            details = mapOf("pipeline_id" to id.toString()),
        )

    private companion object {
        const val VIEW = "partials/pipeline-checks"
        const val FOOTER_RELEASE = "release"
    }
}

/**
 * One row of the checks partial: a definition beside its latest run (or the fresh outcome of
 * the run just commissioned). [observed] is the SERVER's reading — it arrives only from a run,
 * never from the body (pipeline-contract §3.3).
 */
data class CheckEntryView(
    val checkId: String,
    val name: String,
    val datasource: String,
    val expectedText: String,
    val observed: String?,
    val verdict: CheckRunVerdict?,
    val message: String?,
    val ranAt: Instant?,
    val ranAgo: String?,
    val via: String?,
) {
    val verdictLabel: String
        get() = verdict?.wire?.replaceFirstChar { it.uppercase() } ?: "not run"

    companion object {
        /** The GET half: a definition and its latest persisted run, null when never run. */
        fun of(
            check: PipelineCheck,
            run: PipelineCheckRun?,
        ): CheckEntryView =
            CheckEntryView(
                checkId = check.id,
                name = check.name,
                datasource = check.datasource,
                expectedText = expectedText(check.expected),
                observed = run?.observedJson?.let { observedValue(it) },
                verdict = run?.verdict,
                message = run?.message,
                ranAt = run?.ranAt,
                ranAgo = run?.let { RelativeTime.since(it.ranAt, Instant.now()) },
                via = run?.via?.wire,
            )

        /** The POST half: a fresh outcome; [datasource] is zipped back from the definition. */
        fun of(
            outcome: CheckRunOutcome,
            datasource: String,
        ): CheckEntryView =
            CheckEntryView(
                checkId = outcome.checkId,
                name = outcome.name,
                datasource = datasource,
                expectedText = expectedText(outcome.expected),
                observed = outcome.observed,
                verdict = outcome.verdict,
                message = outcome.message,
                ranAt = outcome.ranAt,
                ranAgo = outcome.ranAt?.let { RelativeTime.since(it, Instant.now()) },
                via = CheckRunVia.UI.wire,
            )

        /**
         * The expectation in one compact clause: `= 74.62 ± 0.01`, `74 ≤ x ≤ 75`, `6 rows`.
         * Numbers render without the trailing `.0` a whole Double would carry.
         */
        fun expectedText(expected: CheckExpectation): String =
            when (expected.kind) {
                CheckExpectation.KIND_VALUE -> {
                    "= ${number(expected.value)}" + (expected.tolerance?.let { " ± ${number(it)}" } ?: "")
                }

                CheckExpectation.KIND_RANGE -> {
                    "${number(expected.min)} ≤ x ≤ ${number(expected.max)}"
                }

                CheckExpectation.KIND_ROWS -> {
                    "${expected.rows ?: "—"} rows"
                }

                else -> {
                    expected.kind.ifBlank { "—" }
                }
            }

        private fun number(value: Double?): String = value?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "—"

        /** `observed_json` back to its display value — `{"value": "74.62"}` or `{"rows": 6}` (metadata-db §4.20). */
        private fun observedValue(observedJson: String): String =
            runCatching {
                val node =
                    co.datapipelines.pipeline.PipelineJson
                        .objectMapper()
                        .readTree(observedJson)
                node.get("value")?.asText() ?: node.get("rows")?.asText()
            }.getOrNull() ?: observedJson
    }
}
