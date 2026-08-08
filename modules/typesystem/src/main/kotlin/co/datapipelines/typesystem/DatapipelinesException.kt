package co.datapipelines.typesystem

/**
 * The base exception every module's exceptions extend (module-structure.md §4.3).
 *
 * It lives here because `typesystem` is layer 0 — the only module every other module is
 * allowed to depend on. §4.3 is explicit that there is deliberately **no `common`
 * module**: "a catch-all module is where layering rules go to die."
 *
 * [code] is a code from the single error-code catalog in
 * [pipeline-contract §13](../../../../../../../docs/pipeline-contract.md). This class
 * deliberately does **not** enumerate or validate codes — that authority belongs to
 * pipeline-contract, which sits above this module, and duplicating the catalog here
 * would create a second list to drift.
 *
 * [details] carries the structured `details` object of the unified error response
 * (`{"error": {"code", "message", "details"}}`), and must never contain secrets: the
 * redaction rules in observability.md §9 apply to whatever is put here.
 */
open class DatapipelinesException(
    val code: String,
    message: String,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
