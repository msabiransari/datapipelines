# Design: Zero-Stereotype Explicit Bean Wiring + Gate Hardening

**Status:** draft, pending owner review (drafted 2026-08-16)
**Scope:** migrate every `@Service` / `@Component` / `@Repository` in
production sources to explicit `@Bean` wiring in `@Configuration` classes,
codify the transaction policy (`TransactionTemplate`, no `@Transactional`),
and tighten `ArchitectureGuardTest` so the end state is enforced by the
build, not by convention.
**Authority note:** normative text lands as amendments to
`docs/module-structure.md` §7.8 and §8.4 **in the same commit** as the
guard change. Verified 2026-08-16: no spec-drift test parses
module-structure.md prose (the mechanical drift tests target the staging
and pipeline-contract error catalogs only), so this coupling is a coherence
discipline, not a build-breaking one — but the same-commit rule applies
regardless (standing MISTAKES.md rule). §7.8's current prose is already
stale (it still describes the pre-009/F8 name-based `@Transactional` rule);
this work rewrites it.

---

## 1. Summary

Component scanning stays on — it is how `@Configuration` classes and
`@RestController`s are discovered — but after this change **no production
class carries a DI stereotype**. The only Spring-annotated production
classes are `@Configuration` / `@AutoConfiguration`,
`@ConfigurationProperties`, and the web edge (`@RestController`,
`@ControllerAdvice`). Every bean that today enters the context by being
scanned instead becomes an explicit `@Bean` method in the module's existing
configuration home (or a new `AuthConfiguration` for auth). Transaction
policy is codified: `@Transactional` is banned (zero uses exist today —
this is codification, not migration); the sanctioned mechanism for any
future multi-statement unit of work is an injected `TransactionTemplate`.
Two new Konsist guards make both rules permanent.

The domain/engine core (`dag`, `domain`, engine wiring in `web/config`)
already works this way; this change extends the same discipline to the
28 remaining scanned classes and deletes the workarounds their scanning
required (notably the `AuthFilterRegistrationConfig` servlet
double-registration dance).

## 2. Decisions record

| # | Fork | Decision | Rationale |
|---|---|---|---|
| D1 | End state for annotations | Zero `@Service`/`@Component`/`@Repository`; web edge (`@RestController`, `@ControllerAdvice`) and `@Configuration`/`@ConfigurationProperties` stay annotation-driven; component scanning stays on | Owner decision 2026-08-16. Controllers are the MVC programming model, not DI wiring; fighting Boot's scanner buys nothing the guard doesn't already enforce |
| D2 | Transaction mechanism | Ban `@Transactional` outright; injected `TransactionTemplate` is the sanctioned mechanism for future multi-statement units of work | Owner decision 2026-08-16. Zero `@Transactional` uses exist in production today (verified by grep — the house pattern is single-statement atomicity via data-modifying CTEs, per `PipelineRepository` KDoc). `TransactionTemplate` binds the connection to the thread exactly like the proxy does, with no CGLIB open-class trap and no self-invocation trap |
| D3 | Where beans live | Each bean is declared where its module's Spring config already lives: auth gets a new `AuthConfiguration`; `dag`/`datasources`/`pipeline-contract` keep shipping no Spring config — their repository beans go in `web/config` (`EngineConfiguration`, `DomainConfiguration`) where their consumers are already wired; `templates` uses its existing `TemplatesConfiguration` | Follows the established module pattern (module-structure.md §5.x); minimizes new files and keeps the "dag ships no Spring configuration" invariant intact |
| D4 | Security filters | The three auth filters become plain objects constructed inside the `authFilters` `@Bean`; `AuthFilterRegistrationConfig` is **deleted** | A `Filter` that is never a top-level bean is never auto-registered with the servlet container, so the three `FilterRegistrationBean(isEnabled=false)` workarounds (AU-API-10) become dead code. The `AuthFilters` grouping data class survives as the single collaborator `SecurityConfig` takes |

## 3. Inventory (verified by grep, 2026-08-16 — 28 production files)

Regenerate before implementing; the tree moves:

```
grep -rln --include='*.kt' -E '^\s*@(Service|Component|Repository)\b' modules | grep '/src/main/'
```

| Group | Files | Target home |
|---|---|---|
| auth services | `UserService`, `JwtService`, `ApiKeyService` | `AuthConfiguration` (new) |
| auth repositories | `UserRepository`, `ApiKeyRepository` | `AuthConfiguration` |
| auth collaborators | `AuthCache`, `AuditLogger`, `AuditLogoutHandler`, `AuthAccessDeniedHandler`, `AuthEntryPoint`, `AuthErrorWriter`, `OidcSuccessHandler`, `ScopeInterceptor`, `CookieOAuth2AuthorizationRequestRepository` | `AuthConfiguration` |
| auth filters | `ApiKeyFilter`, `JwtAuthenticationFilter`, `LoginRateLimitFilter`, `AuthFilters` (grouping class) | constructed inside the `authFilters` `@Bean` (D4); `AuthFilterRegistrationConfig` deleted |
| dag repositories | `ExecutionRepository`, `ExecutionEventRepository` | `EngineConfiguration` |
| domain repositories | `DatasourceRepository`, `PipelineRepository` | `DomainConfiguration` |
| templates | `TemplateRepository` | `TemplatesConfiguration` (existing) |
| web | `CorrelationIdFilter` (explicit `FilterRegistrationBean` — it *should* run container-wide, unlike the auth filters), `StagingHealthIndicator` (actuator resolves `HealthIndicator` beans by type), `OidcRegistrations`, `ThemeResolver` | `WebSurfaceConfiguration` / `UiConfig` per current package |
| app | `ConfigValidator` | `@Bean` in the app module's configuration |

Out of scope: `buildSrc/CommonConventionsPlugin.kt` (Gradle build logic,
not a Spring context — its grep hit is incidental) and all test sources
(Spring's TestContext framework is exempt by the guard's production-only
scope, unchanged).

## 4. Wiring design

**auth.** New `AuthConfiguration` in `modules/auth` alongside
`SecurityConfig`, declaring `@Bean`s for everything in the auth rows above.
Existing constructor signatures are unchanged — only the annotation is
removed and a `@Bean` method added; dependencies arrive as method
parameters (already-context beans like `NamedParameterJdbcTemplate`,
`AuthProperties`) or direct calls to sibling `@Bean` methods. The
`authFilters` bean constructs the three filters inline:

```kotlin
@Bean
fun authFilters(/* deps */): AuthFilters =
    AuthFilters(
        apiKey = ApiKeyFilter(...),
        jwt = JwtAuthenticationFilter(...),
        loginRateLimit = LoginRateLimitFilter(...),
    )
```

`SecurityConfig` keeps taking `AuthFilters` and its three
`addFilterBefore` calls are untouched. `AuthFilterRegistrationConfig` and
its KDoc are deleted; the double-registration hazard it guarded against no
longer exists structurally (the filters are not beans), which
`AuthHttpBoundaryTest` must re-prove behaviorally (§7).

**Repositories.** Each repository class loses `@Repository` (no behavior
change: all five are `NamedParameterJdbcTemplate`-based — verified — so
exception translation to `DataAccessException` comes from JdbcTemplate
itself, not the annotation). `@Bean` methods land in the configuration
class that already wires their consumers. The stale comments in
`DomainConfiguration` / `EngineConfiguration` ("already in the context
because `@Repository`") are corrected in the same change.

**web/app stragglers.** Per the inventory table. `CorrelationIdFilter` is
the one filter that genuinely wants container-wide registration, so it gets
an explicit `FilterRegistrationBean` with its order stated, replacing the
implicit registration that being a scanned `Filter` bean provided.

**File-size rule:** if `AuthConfiguration` approaches the 300-line house
limit, split by concern (e.g. `AuthConfiguration` + wiring for
handlers/filters staying in `SecurityConfig`'s file) rather than exceeding
it.

## 5. Transaction policy (codification)

- `@Transactional` is banned in production sources (guard, §6). Nothing
  uses it today; first-choice atomicity remains single-statement
  data-modifying CTEs (the documented `PipelineRepository` stance).
- When a future unit of work genuinely cannot be one statement, the
  sanctioned pattern is an injected `TransactionTemplate` (Boot
  auto-configures it from the single Spring-managed metadata `DataSource`):

```kotlin
class SomeService(
    private val repository: SomeRepository,
    private val tx: TransactionTemplate,
) {
    fun unitOfWork(): Result {
        val result = tx.execute { _ ->
            repository.writeA()
            repository.writeB()   // same thread-bound connection, any call depth
            buildResult()
        }
        return checkNotNull(result) { "transaction callback returned no result" }
    }
}
```

- Semantics: propagation `REQUIRED` by default (nested `execute` joins the
  outer transaction); any exception escaping the lambda rolls back; void
  work uses `executeWithoutResult`. `checkNotNull` is the house-compliant
  unwrap (no `!!`).
- Multi-manager future: a `TransactionTemplate` is built from one
  `PlatformTransactionManager`, so which database a unit of work transacts
  against is explicit in what is injected — no `@Transactional("name")`
  string matching. Customer datasources are engine-managed connections that
  were never under Spring transaction management; nothing changes for them.

## 6. Gates

`ArchitectureGuardTest` (tests/integration-tests) after this change:

1. `no field injection in production code` — **unchanged**.
2. `no stereotype annotations in production code` — **new**: no class or
   interface in the production scope carries `@Service`, `@Component`, or
   `@Repository`. Zero allowlist. This guard is **red against the
   unmigrated tree by construction** (28 violations), which is its §16
   birth-proof: written first, watched failing, turned green by the
   migration.
3. `no declarative transactions in production code` — **replaces** the
   009/F8 `@Transactional`-requires-`@Service` guard (its `@Service` anchor
   no longer exists): no `@Transactional` on any class, interface, or
   function in the production scope. This guard **cannot** land red
   (nothing violates it), so its ability to fail is proven by a one-off
   local mutation: add a scratch `@Transactional` to any production class,
   run the guard, watch it fail, revert — recorded in the implementation
   notes, not committed.
4. `the production scope actually covers the modules` — **unchanged**
   (a guard scanning an empty scope proves nothing).

`RequiredScopeKonsistTest` (web) is untouched — `@RestController` stays,
per D1.

## 7. Documentation amendments (same commit as the guard change)

- **§7.8**: replace the stale `@Transactional`-on-`*Service` description
  with the three-guard list above.
- **§8.4**: replace "`@Service` / `@Repository` / `@Configuration` used per
  Spring conventions" with the zero-stereotype rule: all beans are declared
  in `@Configuration` classes; the only annotated production classes are
  configurations, `@ConfigurationProperties`, and the web edge; transaction
  demarcation is `TransactionTemplate`, `@Transactional` is banned. The
  "open classes" bullet loses its CGLIB-proxying rationale for services
  (kotlin-spring plugin stays for configurations).
- Changelog table at the foot of module-structure.md gains a dated row.

## 8. Verification

1. New stereotype guard written first and observed **red** (28 files).
2. Migration lands; full build green including `./gradlew integrationTest`
   — context boot exercises every new `@Bean` path.
3. `AuthHttpBoundaryTest` green — the behavioral proof that each auth
   filter fires exactly once per request and `permitAll` paths stay
   unauthenticated (the risk `AuthFilterRegistrationConfig` used to carry).
4. `@Transactional` guard mutation-check performed locally (§6, guard 3).
5. Coverage floors (009/F9): `@Bean` methods are exercised by context
   boot; confirm module coverage does not dip below floors — a check, not
   an assumption.
6. Compile-integrity: verify in a cold environment or with `--rerun-tasks`
   on compile tasks (standing MISTAKES.md rule — warm caches can
   compile-skip broken test sources).

## 9. Risks

- **Security chain regression** is the real risk surface: filter order,
  single execution, `permitAll` behavior. It is covered by existing
  boundary tests, not by review — if `AuthHttpBoundaryTest`'s coverage of
  double-execution turns out thinner than assumed, extend it before the
  migration, not after.
- **Bean-name changes:** scanned beans are named after the class
  (`userService`); `@Bean` methods are named after the method. Keeping
  method names matching the old bean names sidesteps any by-name lookup;
  none is known to exist, but the implementer should not rename gratuitously.

## Explicitly NOT in scope

- Migrating `@RestController` / `@ControllerAdvice` to `@Bean` wiring (D1).
- Disabling component scanning or `@Import`-listing configurations.
- Any change to test sources' use of Spring annotations.
- `buildSrc` build logic.
- Introducing any actual `TransactionTemplate` call site (none is needed
  today — §5 is policy for future work).
