package co.datapipelines.auth

/**
 * Declares which catalog [Permission] (auth.md §7.6, #215) a controller handler — or every
 * handler in a controller — requires. [ScopeInterceptor] hands it to [ScopeMatrix.allowed],
 * which judges the ROLE axis against [RolePermissions] and, for an API key, the scope floor
 * the permission carries through the A4 shim — so a handler names WHAT it does and never
 * asserts a role or a scope of its own.
 *
 * Exactly one permission per handler (record §7, gate 2); a route serving two actions is two
 * handlers. The annotation keeps its name — `RequiredScopeKonsistTest` and `RoleWalkE2eTest`
 * find it by name — while its argument is a permission since slice (a); the name retires with
 * the scopes in slice (b).
 *
 * **Annotating is mandatory** for every handler the §8.3 allowlist does not make public: an
 * unannotated governed handler is denied by default (AUTH-SEC-9) rather than silently served.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiredScope(
    val value: Permission,
)
