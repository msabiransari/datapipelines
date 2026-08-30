package co.datapipelines.web.api

import co.datapipelines.pipeline.PipelineErrorCodes
import org.springframework.http.HttpStatus

/**
 * Code → HTTP status, and code → `user_message`, for the whole
 * [pipeline-contract §13](../../../../../../../docs/pipeline-contract.md) catalog.
 *
 * §13's tables carry an `HTTP` column, but no module turns it into code: `DatapipelinesException`
 * has a `code` and no status, and only `auth`'s subclasses carry one. The REST surface is the
 * layer that must answer "what status is this", so the mapping lives here — once, not once per
 * controller. `ApiErrorCatalogSpecDriftTest` parses §13's own tables (plus rest-api §7.6's
 * `result.*` table) and fails the build if this object and the documents ever disagree, in either
 * direction, so the table stays the authority and this stays its projection.
 *
 * ## Status resolution
 * Most families are uniform, so the rule is family-default plus explicit exceptions rather than
 * ninety hand-written rows — a table that repeats `400` thirty times invites a typo nobody sees.
 * `pipeline.validation.*` is uniformly 400 (§13's own "All validation errors use HTTP 400"),
 * `pipeline.staging.*` uniformly 500, `template.validation.*` uniformly 400. Everything whose
 * status is *not* its family default is listed in [EXCEPTIONS] explicitly.
 *
 * ## `user_message` (rest-api §4.2)
 * The envelope requires a non-technical message on every error. Writing ninety bespoke sentences
 * would produce ninety strings that drift from the codes they explain; instead each *family* has
 * one honest, actionable sentence, with per-code overrides where the family sentence would be
 * unhelpfully vague. `AuthException` carries its own `userMessage` and never reaches this table.
 */
object ApiErrorCatalog {
    /**
     * The status for [code]. Unknown codes are 500 — an uncatalogued code is a defect, and
     * reporting it as a client error would blame the caller for our bug.
     */
    fun statusFor(code: String): HttpStatus = EXCEPTIONS[code] ?: familyDefault(code)

    /** The non-technical `user_message` for [code] (rest-api §4.2). */
    fun userMessageFor(code: String): String =
        USER_MESSAGE_OVERRIDES[code]
            ?: FAMILY_USER_MESSAGE.entries.firstOrNull { code.startsWith(it.key) }?.value
            ?: GENERIC_USER_MESSAGE

    /**
     * The public docs page for [code] (rest-api §4.2 `doc_url`).
     *
     * Delegates to `auth`'s derivation rather than re-deriving it: two spellings of the same URL
     * is one spelling too many, and `auth`'s writer already emits errors on this API.
     */
    fun docUrl(code: String): String =
        co.datapipelines.auth.AuthErrorCodes
            .docUrl(code)

    private fun familyDefault(code: String): HttpStatus =
        FAMILY_DEFAULTS.entries.firstOrNull { code.startsWith(it.key) }?.value ?: HttpStatus.INTERNAL_SERVER_ERROR

    /** Family prefix → the status the overwhelming majority of that family's codes carry. */
    private val FAMILY_DEFAULTS: Map<String, HttpStatus> =
        linkedMapOf(
            "pipeline.validation." to HttpStatus.BAD_REQUEST,
            "pipeline.import." to HttpStatus.BAD_REQUEST,
            "pipeline.execution." to HttpStatus.INTERNAL_SERVER_ERROR,
            "pipeline.node." to HttpStatus.INTERNAL_SERVER_ERROR,
            "pipeline.staging." to HttpStatus.INTERNAL_SERVER_ERROR,
            "auth.api_key." to HttpStatus.UNAUTHORIZED,
            "auth.session." to HttpStatus.UNAUTHORIZED,
            "auth.scope." to HttpStatus.FORBIDDEN,
            "auth.csrf." to HttpStatus.FORBIDDEN,
            "auth.login." to HttpStatus.FORBIDDEN,
            "auth.password." to HttpStatus.FORBIDDEN,
            "datasource.validation." to HttpStatus.BAD_REQUEST,
            "template.validation." to HttpStatus.BAD_REQUEST,
            "result." to HttpStatus.INTERNAL_SERVER_ERROR,
            "workspace." to HttpStatus.FORBIDDEN,
        )

    /** Every code whose status differs from its family default (§13, rest-api §7.6). */
    private val EXCEPTIONS: Map<String, HttpStatus> =
        mapOf(
            // §12's duplicate_name row documents HTTP 409 ("mapped from the UNIQUE constraint").
            PipelineErrorCodes.Validation.DUPLICATE_NAME to HttpStatus.CONFLICT,
            PipelineErrorCodes.Import.VERSION_CONFLICT to HttpStatus.CONFLICT,
            PipelineErrorCodes.Execution.NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Execution.PARAMETER_REQUIRED to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Execution.TIMEOUT to HttpStatus.GATEWAY_TIMEOUT,
            PipelineErrorCodes.Execution.CONCURRENCY_LIMIT to HttpStatus.TOO_MANY_REQUESTS,
            PipelineErrorCodes.Execution.NOT_RUNNING to HttpStatus.CONFLICT,
            PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE to HttpStatus.BAD_GATEWAY,
            PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED to HttpStatus.BAD_GATEWAY,
            PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED to HttpStatus.BAD_GATEWAY,
            PipelineErrorCodes.Datasource.DUPLICATE_NAME to HttpStatus.CONFLICT,
            PipelineErrorCodes.Datasource.IN_USE to HttpStatus.CONFLICT,
            PipelineErrorCodes.Datasource.NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Datasource.DRIVER_NOT_LOADED to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Template.NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Result.EXECUTION_NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Result.EXECUTION_INCOMPLETE to HttpStatus.CONFLICT,
            PipelineErrorCodes.Result.EXECUTION_FAILED to HttpStatus.GONE,
            PipelineErrorCodes.Result.EXPIRED to HttpStatus.GONE,
            PipelineErrorCodes.Result.FORMAT_UNSUPPORTED to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Limits.RATE_LIMIT_EXCEEDED to HttpStatus.TOO_MANY_REQUESTS,
            PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED to HttpStatus.CONFLICT,
            PipelineErrorCodes.Workspace.HEADER_FORBIDDEN to HttpStatus.BAD_REQUEST,
            // §13.7 — bad credentials is the one `auth.login.*` code that is a 401,
            // not the family's 403: it answers "not authenticated", not "forbidden".
            PipelineErrorCodes.Auth.LOGIN_BAD_CREDENTIALS to HttpStatus.UNAUTHORIZED,
            // §13.7 — 403 like the auth family default, wired explicitly so the code owns a
            // row rather than being absorbed by the default (the 025 A2 convention).
            PipelineErrorCodes.Auth.SESSION_REQUIRED to HttpStatus.FORBIDDEN,
            // §13.12's CRUD codes break the workspace.* family default (403, the resolution
            // codes) — the surfaces slice's rows each carry their own status.
            // SESSION_REQUIRED is 403 like the family default, but wired explicitly so the
            // code has a row here, not an absorption (025 A2).
            PipelineErrorCodes.Workspace.SESSION_REQUIRED to HttpStatus.FORBIDDEN,
            PipelineErrorCodes.Workspace.NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Workspace.NAME_INVALID to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Workspace.DUPLICATE_NAME to HttpStatus.CONFLICT,
            PipelineErrorCodes.Workspace.IN_USE to HttpStatus.CONFLICT,
            // §13.9 — the T23 mapping: the workspace UNIQUE(name) violation is a 409.
            PipelineErrorCodes.Template.DUPLICATE_NAME to HttpStatus.CONFLICT,
        )

    /**
     * `pipeline.execution.instance_lost` is recorded by the crash sweep and **never returned
     * live** (§13.4 marks its HTTP column `—`). It is listed here so the drift test can assert
     * the exclusion is deliberate rather than an omission.
     */
    val NEVER_RETURNED_LIVE: Set<String> =
        setOf(
            PipelineErrorCodes.Execution.INSTANCE_LOST,
            PipelineErrorCodes.TypeMapping.UNKNOWN_SOURCE_TYPE,
            PipelineErrorCodes.TypeMapping.SQL_VARIANT,
        )

    /**
     * Every code the catalog maps to HTTP 502 (BAD_GATEWAY) — derived from [EXCEPTIONS], so a
     * new 502 mapping lands here automatically. Each member is a deliberate per-code decision:
     * 502 means "a party behind us failed", which splits into the caller's own downstream
     * ([CALLER_DOWNSTREAM_DOWN]) and possibly-our-bug (`query_execution_failed` — the rendered
     * SQL can be the defect). [ApiErrorCatalogGatewayCodesTest] pins the partition so a future
     * gateway code cannot join either side silently.
     */
    val GATEWAY_CODES: Set<String> =
        EXCEPTIONS
            .entries
            .filter { it.value == HttpStatus.BAD_GATEWAY }
            .map { it.key }
            .toSet()

    /**
     * The only codes whose 5xx status is demoted to WARN without a stack: both mean the
     * downstream the CALLER pointed us at (their own database) is down — not an operator
     * incident. Membership is a deliberate per-code decision; a status alone proves nothing
     * (`query_execution_failed` is also 502 and stays at ERROR with the stack). Kept beside
     * [GATEWAY_CODES] so the coupling test can hold the two sets together.
     */
    val CALLER_DOWNSTREAM_DOWN: Set<String> =
        setOf(
            PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
            PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED,
        )

    private const val GENERIC_USER_MESSAGE = "Something went wrong on our side. Quote the correlation id when reporting this."

    private val FAMILY_USER_MESSAGE: Map<String, String> =
        linkedMapOf(
            "pipeline.validation." to "This pipeline isn't valid yet. Check the highlighted problem and try again.",
            "pipeline.import." to "This pipeline couldn't be imported into this environment.",
            "pipeline.execution." to "The pipeline run couldn't be completed.",
            "pipeline.node." to "A step in the pipeline failed while it was running.",
            "pipeline.staging." to "The pipeline ran out of room, or produced a value the temporary database couldn't hold.",
            "datasource.validation." to "These connection details aren't valid. Check them and try again.",
            "template.validation." to "This SQL template isn't valid. Check the reported problem and try again.",
            "result." to "The results for this run aren't available.",
            "workspace." to "That workspace isn't available to you. Check the name, or ask a workspace owner for access.",
        )

    private val USER_MESSAGE_OVERRIDES: Map<String, String> =
        mapOf(
            PipelineErrorCodes.Validation.CYCLE_DETECTED to
                "Your pipeline has a circular dependency. Remove one of the arrows.",
            PipelineErrorCodes.Execution.NOT_FOUND to
                "We couldn't find that pipeline. It may have been deleted.",
            PipelineErrorCodes.Execution.TIMEOUT to
                "The run took too long and was stopped. Try narrowing the date range or the amount of data.",
            PipelineErrorCodes.Execution.CONCURRENCY_LIMIT to
                "You already have the maximum number of runs in progress. Wait for one to finish and try again.",
            PipelineErrorCodes.Execution.NOT_RUNNING to
                "That run has already finished, so there is nothing to cancel.",
            PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED to
                "We couldn't reach the database this step uses. Check that it is online and reachable from this server.",
            PipelineErrorCodes.Datasource.IN_USE to
                "This connection is still used by one or more pipelines, so it can't be deleted yet.",
            PipelineErrorCodes.Datasource.DUPLICATE_NAME to
                "A connection with that name already exists. Pick a different name.",
            PipelineErrorCodes.Datasource.NOT_FOUND to
                "We couldn't find that connection. It may have been deleted.",
            PipelineErrorCodes.Template.NOT_FOUND to
                "We couldn't find that template. It may have been deleted.",
            PipelineErrorCodes.Template.DUPLICATE_NAME to
                "A template with that name already exists in this workspace. Pick a different name.",
            PipelineErrorCodes.Workspace.DUPLICATE_NAME to
                "A workspace with that name already exists. Pick a different name.",
            PipelineErrorCodes.Workspace.IN_USE to
                "This workspace still has content in it, so it can't be deleted yet.",
            PipelineErrorCodes.Result.EXPIRED to
                "These results have expired. Run the pipeline again to get fresh ones.",
            PipelineErrorCodes.Result.EXECUTION_INCOMPLETE to
                "This run hasn't finished yet. Results appear once it completes.",
            PipelineErrorCodes.Result.EXECUTION_FAILED to
                "This run failed, so there are no results to show.",
            PipelineErrorCodes.Result.FORMAT_UNSUPPORTED to
                "That download format isn't supported. Choose JSON, Arrow or CSV.",
            PipelineErrorCodes.Result.TOO_LARGE to
                "This result is too large to return. Write it back to a database instead and return a summary.",
            PipelineErrorCodes.Limits.RATE_LIMIT_EXCEEDED to
                "You're sending requests faster than we allow. Wait a moment and try again.",
            PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED to
                "That idempotency key was already used with a different request. Use a new key.",
        )
}
