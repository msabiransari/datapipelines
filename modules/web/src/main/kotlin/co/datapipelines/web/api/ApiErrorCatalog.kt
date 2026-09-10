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
            // The versioning lifecycle (versioning.md): every one of these is a state or
            // precondition conflict — a stale content hash, a release with no draft, a
            // promotion of something not released or not newer. §13 documents them all 409.
            "pipeline.version." to HttpStatus.CONFLICT,
            "pipeline.release." to HttpStatus.CONFLICT,
            "pipeline.promotion." to HttpStatus.CONFLICT,
            // versioning §5.5: the authoring capability refusal — a promotion receiver's
            // write path refuses, naming the reason. §13.13 documents both mirrors 403.
            "pipeline.authoring." to HttpStatus.FORBIDDEN,
            "template.authoring." to HttpStatus.FORBIDDEN,
            "auth.api_key." to HttpStatus.UNAUTHORIZED,
            // versioning §10.6 — the promotion peer credential. Not a principal and not an
            // API key, so it gets its own family rather than borrowing `auth.api_key.`.
            "auth.promotion." to HttpStatus.UNAUTHORIZED,
            "auth.session." to HttpStatus.UNAUTHORIZED,
            "auth.scope." to HttpStatus.FORBIDDEN,
            "auth.csrf." to HttpStatus.FORBIDDEN,
            "auth.login." to HttpStatus.FORBIDDEN,
            "auth.password." to HttpStatus.FORBIDDEN,
            "datasource.validation." to HttpStatus.BAD_REQUEST,
            "template.validation." to HttpStatus.BAD_REQUEST,
            "template.version." to HttpStatus.CONFLICT,
            "result." to HttpStatus.INTERNAL_SERVER_ERROR,
            "workspace." to HttpStatus.FORBIDDEN,
            // 074 §13.14 — the request validator is the biggest half of the family and every
            // one of its codes is a 400, so 400 is the honest default; the publish-time and
            // resolution-time refusals each name their own status in EXCEPTIONS below.
            "endpoint." to HttpStatus.BAD_REQUEST,
        )

    /** Every code whose status differs from its family default (§13, rest-api §7.6). */
    private val EXCEPTIONS: Map<String, HttpStatus> =
        mapOf(
            // 091 §13.7: the one `auth.api_key.` code that is not 401. The credential is fine;
            // the ISSUANCE BODY named an expiry that is not usable, which is a 400.
            co.datapipelines.auth.AuthErrorCodes.API_KEY_EXPIRY_INVALID to HttpStatus.BAD_REQUEST,
            // §12's duplicate_name row documents HTTP 409 ("mapped from the UNIQUE constraint").
            PipelineErrorCodes.Validation.DUPLICATE_NAME to HttpStatus.CONFLICT,
            PipelineErrorCodes.Import.VERSION_CONFLICT to HttpStatus.CONFLICT,
            // 072 §13.2: a body binding an `org_*` key this deployment does not define. 409, not
            // 400: the payload is well-formed and was valid where it was authored — it is THIS
            // deployment's configuration that conflicts with it, and the fix is an operator's.
            PipelineErrorCodes.Import.CONTEXT_KEY_MISSING to HttpStatus.CONFLICT,
            PipelineErrorCodes.Execution.NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Execution.PARAMETER_REQUIRED to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Execution.TIMEOUT to HttpStatus.GATEWAY_TIMEOUT,
            PipelineErrorCodes.Execution.CONCURRENCY_LIMIT to HttpStatus.TOO_MANY_REQUESTS,
            PipelineErrorCodes.Execution.NOT_RUNNING to HttpStatus.CONFLICT,
            PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE to HttpStatus.BAD_GATEWAY,
            PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED to HttpStatus.BAD_GATEWAY,
            PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED to HttpStatus.BAD_GATEWAY,
            // §13.4 — the node's statement outlived its JDBC query timeout: 504 like its sibling
            // `pipeline.execution.timeout`, not the 502 a wrong query earns.
            PipelineErrorCodes.Node.QUERY_TIMEOUT to HttpStatus.GATEWAY_TIMEOUT,
            PipelineErrorCodes.Node.TIMEOUT to HttpStatus.GATEWAY_TIMEOUT,
            // §13.4 — 500 like the pipeline.node family default, wired explicitly so the code
            // owns a row rather than being absorbed by the default (the 025 A2 convention).
            PipelineErrorCodes.Node.SQL_PARAMETER_MISSING to HttpStatus.INTERNAL_SERVER_ERROR,
            // §13.4 / 037 — the node-run debug query's refusals, against the family's 500
            // default: an unknown node is a 404, and a node with no standalone SQL to run
            // (tempdb source / PIPELINE node) is a 400.
            PipelineErrorCodes.Node.NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Node.STANDALONE_EXECUTION_REFUSED to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Datasource.DUPLICATE_NAME to HttpStatus.CONFLICT,
            PipelineErrorCodes.Datasource.IN_USE to HttpStatus.CONFLICT,
            PipelineErrorCodes.Datasource.NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Datasource.DRIVER_NOT_LOADED to HttpStatus.BAD_REQUEST,
            // 056 §E.2: a customer lease attempted inside a metadata transaction. Nothing a
            // caller sends can produce it — it means a service annotated @Transactional grew a
            // datasource lease, so it is a server fault, loudly, rather than a silent lock hold.
            PipelineErrorCodes.Datasource.LEASE_IN_TRANSACTION to HttpStatus.INTERNAL_SERVER_ERROR,
            // 089 §A — the dp-lake registry's two non-validation codes: a re-registered triple
            // is a conflict (the named UNIQUE's mapping, metadata-db §4.15) and unregistering an
            // absent table is a not-found. The validation family covers the five 400s.
            PipelineErrorCodes.Datasource.LAKE_TABLE_DUPLICATE to HttpStatus.CONFLICT,
            PipelineErrorCodes.Datasource.LAKE_TABLE_NOT_FOUND to HttpStatus.NOT_FOUND,
            // 109 §A — a node referenced a lake table whose connect-time view creation is
            // recorded as broken: the content a party behind us serves failed, like
            // `datasource_connection_failed`, so 502 and the WARN demotion below.
            PipelineErrorCodes.Datasource.LAKE_TABLE_UNAVAILABLE to HttpStatus.BAD_GATEWAY,
            // 109 §A — the registration pre-flight refusal: 400 like the family default, wired
            // explicitly so the code owns a row rather than being absorbed (the 025 A2 convention).
            PipelineErrorCodes.Datasource.LAKE_TABLE_UNREADABLE to HttpStatus.BAD_REQUEST,
            // 109 §B — a declared dialect property with an empty/blank/null value: 400 like the
            // family default, wired explicitly to own a row (the 025 A2 convention).
            PipelineErrorCodes.Datasource.PROPERTY_EMPTY to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Template.NOT_FOUND to HttpStatus.NOT_FOUND,
            // §13.9 (040 D4) — the in-use delete refusal, against any template-family default:
            // it is a conflict with live references, not a validation failure.
            PipelineErrorCodes.Template.IN_USE to HttpStatus.CONFLICT,
            // §13.9 — 400 like the template.validation family default, wired explicitly for
            // the same 025 A2 reason.
            PipelineErrorCodes.Template.PARAMETER_INTERPOLATED to HttpStatus.BAD_REQUEST,
            // §13.9 (046, typed templates) — 400 like the family default, wired explicitly so
            // each code owns a row rather than being absorbed by the default (025 A2).
            PipelineErrorCodes.Template.TYPE_INVALID to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Template.DIALECT_NOT_ALLOWED to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Template.TYPE_IMMUTABLE to HttpStatus.BAD_REQUEST,
            // §12.6 (046) — the html-reference refusal at pipeline save; 400 like the
            // pipeline.validation family default, wired explicitly for the same A2 reason.
            PipelineErrorCodes.Validation.TEMPLATE_TYPE_MISMATCH to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Result.EXECUTION_NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Result.EXECUTION_INCOMPLETE to HttpStatus.CONFLICT,
            PipelineErrorCodes.Result.EXECUTION_FAILED to HttpStatus.GONE,
            PipelineErrorCodes.Result.EXPIRED to HttpStatus.GONE,
            PipelineErrorCodes.Result.FORMAT_UNSUPPORTED to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Limits.RATE_LIMIT_EXCEEDED to HttpStatus.TOO_MANY_REQUESTS,
            // 083 — the fail-closed refusal shares the 429 and differs only in the code, so a
            // client back-off written against `rate_limit.*` needs no change to honour it.
            PipelineErrorCodes.Limits.RATE_LIMIT_UNAVAILABLE to HttpStatus.TOO_MANY_REQUESTS,
            PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED to HttpStatus.CONFLICT,
            PipelineErrorCodes.Workspace.HEADER_FORBIDDEN to HttpStatus.BAD_REQUEST,
            // §13.13 (055) — 409 like the pipeline.promotion family default, wired explicitly
            // so each code owns a row rather than being absorbed by the default (025 A2).
            PipelineErrorCodes.Versioning.PROMOTION_MISSING_DATASOURCES to HttpStatus.CONFLICT,
            PipelineErrorCodes.Versioning.PROMOTION_TARGET_IS_AUTHORING to HttpStatus.CONFLICT,
            // §13.13 (055) — a transport failure reaching the target: 502, against the
            // promotion family's 409. It is the one promotion code that is not a refusal.
            PipelineErrorCodes.Versioning.PROMOTION_TARGET_UNREACHABLE to HttpStatus.BAD_GATEWAY,
            // §13.13/§13.9 (102) — the typed-confirm dialog guard is a 400 against both
            // version families' 409 default: the caller's own input was wrong, nothing about
            // the version refused. Wired explicitly for that reason (025 A2).
            PipelineErrorCodes.Versioning.CONFIRM_MISMATCH to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Template.VERSION_CONFIRM_MISMATCH to HttpStatus.BAD_REQUEST,
            // §13.7 (055) — 401 like the auth.promotion family default, same A2 reason.
            PipelineErrorCodes.Auth.PROMOTION_KEY_INVALID to HttpStatus.UNAUTHORIZED,
            // §13.7 — bad credentials is the one `auth.login.*` code that is a 401,
            // not the family's 403: it answers "not authenticated", not "forbidden".
            PipelineErrorCodes.Auth.LOGIN_BAD_CREDENTIALS to HttpStatus.UNAUTHORIZED,
            // §13.7 — 403 like the auth family default, wired explicitly so the code owns a
            // row rather than being absorbed by the default (the 025 A2 convention).
            PipelineErrorCodes.Auth.SESSION_REQUIRED to HttpStatus.FORBIDDEN,
            // RBAC round 1 §13.7. The two 403s are wired explicitly rather than absorbed by an
            // `auth.` family default, because there is no such default: `auth.role_required`
            // and `auth.key_issuer_role_lost` are bare `auth.` codes with no dotted family, so
            // a missing row here would resolve to the catalog's 500 for an unknown code.
            co.datapipelines.auth.AuthErrorCodes.ROLE_REQUIRED to HttpStatus.FORBIDDEN,
            co.datapipelines.auth.AuthErrorCodes.KEY_ISSUER_ROLE_LOST to HttpStatus.FORBIDDEN,
            co.datapipelines.auth.AuthErrorCodes.KEY_WORKSPACE_INACTIVE to HttpStatus.FORBIDDEN,
            // The credential making the request is fine; the requested scope is not one keys
            // have (O-2) — a 400, like `auth.api_key.expiry_invalid` and for the same reason.
            co.datapipelines.auth.AuthErrorCodes.KEY_SCOPE_UNAVAILABLE to HttpStatus.BAD_REQUEST,
            // D-R7: an ungranted datasource is INVISIBLE, so its refusal is the not-found
            // status, not the datasource family's default.
            co.datapipelines.datasources.DatasourceErrorCodes.GRANT_REQUIRED to HttpStatus.NOT_FOUND,
            // §13.12's CRUD codes break the workspace.* family default (403, the resolution
            // codes) — the surfaces slice's rows each carry their own status.
            // SESSION_REQUIRED is 403 like the family default, but wired explicitly so the
            // code has a row here, not an absorption (025 A2).
            PipelineErrorCodes.Workspace.SESSION_REQUIRED to HttpStatus.FORBIDDEN,
            PipelineErrorCodes.Workspace.NOT_FOUND to HttpStatus.NOT_FOUND,
            // D-R5: a deactivated workspace is a 404 for the same reason an unreachable one is
            // — deactivation must not be a signal a caller can read off a status code.
            PipelineErrorCodes.Workspace.INACTIVE to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Workspace.LAST_ADMIN to HttpStatus.CONFLICT,
            PipelineErrorCodes.Workspace.NAME_INVALID to HttpStatus.BAD_REQUEST,
            PipelineErrorCodes.Workspace.DUPLICATE_NAME to HttpStatus.CONFLICT,
            PipelineErrorCodes.Workspace.IN_USE to HttpStatus.CONFLICT,
            // §13.9 — the T23 mapping: the workspace UNIQUE(name) violation is a 409.
            PipelineErrorCodes.Template.DUPLICATE_NAME to HttpStatus.CONFLICT,
            // 074 §13.14 / rest-api §19.6 — the published-endpoint status table. Only the
            // request-validator codes take the family's 400 default.
            PipelineErrorCodes.Endpoint.PATH_CONFLICT to HttpStatus.CONFLICT,
            // Publish-time refusal. The SERVE path returns this same code as 503 (§19.4) and
            // sets that status on its own response rather than through this catalog: the code
            // says what is wrong, the surface says when it was found.
            PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY to HttpStatus.CONFLICT,
            PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED to HttpStatus.SERVICE_UNAVAILABLE,
            PipelineErrorCodes.Endpoint.NOT_FOUND to HttpStatus.NOT_FOUND,
            PipelineErrorCodes.Endpoint.METHOD_NOT_ALLOWED to HttpStatus.METHOD_NOT_ALLOWED,
            PipelineErrorCodes.Endpoint.NOT_ACCEPTABLE to HttpStatus.NOT_ACCEPTABLE,
            PipelineErrorCodes.Endpoint.KEY_NOT_BOUND to HttpStatus.FORBIDDEN,
            PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED to HttpStatus.FORBIDDEN,
            PipelineErrorCodes.Endpoint.PROMOTION_KEY_MISSING to HttpStatus.CONFLICT,
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
     * The codes whose 5xx status is demoted to WARN without a stack: a machine somebody ELSE
     * operates is down — the caller's own database, or (055) the operator's own promotion
     * peer — which is not an incident in THIS process. Membership is a deliberate per-code
     * decision; a status alone proves nothing (`query_execution_failed` is also 502 and stays
     * at ERROR with the stack, because the rendered SQL can be the defect). Kept beside
     * [GATEWAY_CODES] so the coupling test can hold the two sets together.
     */
    val CALLER_DOWNSTREAM_DOWN: Set<String> =
        setOf(
            PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE,
            PipelineErrorCodes.Node.DATASOURCE_CONNECTION_FAILED,
            PipelineErrorCodes.Versioning.PROMOTION_TARGET_UNREACHABLE,
            // 109 §A — the caller's own object store served content the registered table's view
            // cannot read; the diagnosis is recorded on the registry row, so this is not an
            // incident in THIS process either.
            PipelineErrorCodes.Datasource.LAKE_TABLE_UNAVAILABLE,
        )

    private const val GENERIC_USER_MESSAGE = "Something went wrong on our side. Quote the correlation id when reporting this."

    /** versioning §5.5: both authoring-disabled mirrors share one actionable sentence. */
    private const val RECEIVER_USER_MESSAGE =
        "This server doesn't allow creating or editing — it only receives promoted content. " +
            "Make your changes in the authoring environment."

    private val FAMILY_USER_MESSAGE: Map<String, String> =
        linkedMapOf(
            "pipeline.validation." to "This pipeline isn't valid yet. Check the highlighted problem and try again.",
            "pipeline.import." to "This pipeline couldn't be imported into this environment.",
            "pipeline.promotion." to "This item couldn't be promoted to the target environment.",
            "pipeline.execution." to "The pipeline run couldn't be completed.",
            "pipeline.node." to "A step in the pipeline failed while it was running.",
            "pipeline.staging." to "The pipeline ran out of room, or produced a value the temporary database couldn't hold.",
            "datasource.validation." to "These connection details aren't valid. Check them and try again.",
            "template.validation." to "This SQL template isn't valid. Check the reported problem and try again.",
            "result." to "The results for this run aren't available.",
            "workspace." to "That workspace isn't available to you. Check the name, or ask a workspace admin for access.",
            // 074 — the request family is the one a caller of a published endpoint sees, so the
            // family message is written for THEM (a machine client's developer), not for an
            // author. The publish-time refusals carry their own overrides below.
            "endpoint.request." to "The request to this endpoint isn't valid. Every problem with it is listed in the error details.",
            "endpoint." to "This published endpoint couldn't serve the request.",
        )

    private val USER_MESSAGE_OVERRIDES: Map<String, String> =
        mapOf(
            PipelineErrorCodes.Endpoint.PATH_CONFLICT to
                "Another endpoint could already answer this URL. Pick a path that can't collide with it.",
            PipelineErrorCodes.Endpoint.PIPELINE_NOT_READONLY to
                "Only read-only pipelines can be published as a GET endpoint. This one writes somewhere.",
            PipelineErrorCodes.Endpoint.PIPELINE_NOT_RELEASED to
                "This endpoint's pipeline has no released version yet. Release it and try again.",
            PipelineErrorCodes.Endpoint.KEY_NOT_BOUND to
                "This API key isn't bound to this part of the endpoint tree.",
            PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED to
                "This kind of API key can't be used here.",
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
            PipelineErrorCodes.Template.IN_USE to
                "This template is still used by one or more pipelines, so it can't be deleted yet.",
            PipelineErrorCodes.Datasource.DUPLICATE_NAME to
                "A connection with that name already exists. Pick a different name.",
            PipelineErrorCodes.Datasource.NOT_FOUND to
                "We couldn't find that connection. It may have been deleted.",
            PipelineErrorCodes.Datasource.LAKE_TABLE_UNAVAILABLE to
                "A registered table on this data lake couldn't be read. The recorded reason is in the error details.",
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
            PipelineErrorCodes.Limits.RATE_LIMIT_UNAVAILABLE to
                "We couldn't check your request against our limits just now, so it wasn't run. Try again shortly.",
            PipelineErrorCodes.Limits.IDEMPOTENCY_KEY_REUSED to
                "That idempotency key was already used with a different request. Use a new key.",
            PipelineErrorCodes.Versioning.AUTHORING_DISABLED to RECEIVER_USER_MESSAGE,
            PipelineErrorCodes.Template.AUTHORING_DISABLED to RECEIVER_USER_MESSAGE,
        )
}
