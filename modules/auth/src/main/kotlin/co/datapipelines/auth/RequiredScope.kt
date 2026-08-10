package co.datapipelines.auth

/**
 * Declares which documented §7.6 operation a controller handler (or every handler in
 * a controller) implements. [ScopeInterceptor] reads the minimum scope from
 * [ScopeMatrix.RestOperation], so the matrix stays the single source of truth and a
 * handler can never assert a scope that disagrees with the doc.
 *
 * The check is hierarchical: `@RequiredScope(RestOperation.READ_RESOURCES)` is
 * satisfied by a principal holding `execute`, `author` or `admin`.
 *
 * **Annotating is mandatory** for handlers under the `/api` prefix and on `/mcp`: an unannotated
 * handler there is denied by default (AUTH-SEC-9) rather than silently served.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiredScope(
    val value: ScopeMatrix.RestOperation,
)
