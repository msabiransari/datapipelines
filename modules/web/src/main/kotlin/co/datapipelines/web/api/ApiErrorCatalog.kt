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
            "datasource.validation." to HttpStatus.BAD_REQUEST,
            "template.validation." to HttpStatus.BAD_REQUEST,
            "result." to HttpStatus.INTERNAL_SERVER_ERROR,
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
