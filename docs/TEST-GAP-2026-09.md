# TEST GAP AUDIT — Behavior-Driven Coverage & Browser Suite (2026-09)

**Status:** findings record — backfill tiers and the browser-suite design below are proposed, not yet ratified
**Audit date:** 2026-09-03 (mechanical cross-reference + targeted verification reads)
**Audit baseline:** working tree at session start; re-derivable any time via `scripts/test-gap-audit.py` (see Method)
**Not packaged into the product** (`modules/web/build.gradle.kts` `processResources` exclusion, same list as ARCH-AUDIT): contributor material pending review.

**Related:** [ARCH-AUDIT-2026-08.md](ARCH-AUDIT-2026-08.md) (service layer — S1/S2 shape several findings below), [module-structure.md](module-structure.md) §7.4/§7.7 (test conventions, coverage floors).

---

## Method (re-runnable)

`scripts/test-gap-audit.py` walks every module's `src/main` sources, extracts each
file's top-level type names, and marks a type **referenced** if any test source in
that module **or** `tests/integration-tests` contains the name. Output: per-module
summary (zero-reference files, partial files, layer split) and a LOC-sorted gap list.

**Proxy limits, stated up front:**
1. Name-reference is a floor, not a ceiling: it proves "a test knows this type," not
   "its behavior is asserted." Files with references can still harbor untested
   branches (the partials list).
2. Indirect coverage is invisible to it. `NodeOutputModule` scores zero-reference
   but its serializer/deserializer run inside every `PipelineSerializerTest` /
   `PipelineDeserializerTest` round-trip (verified: `PipelineJson.kt:34` registers
   it). Where this audit verified indirect coverage, the finding says so.
3. It says nothing about test KIND. The controller-layer analysis below re-checks
   each controller for a same-name `*ControllerTest` (the house unit-test shape),
   independent of the script.

**Numbers at baseline:** 344 main Kotlin files vs 341 test files; 28 zero-reference
main files (below), 60 partial. Kover floors hold in every module (§7.7), so this
audit is about **where behavior is unasserted**, not about a red build.

---

## Layer 1 — Controller unit tests (the biggest real gap)

House style (verified from `PipelinesControllerTest`): direct instantiation +
MockK, no Spring context, no `@WebMvcTest` anywhere in the repo (0 files — the
convention is plain JUnit 5). 20 of 35 controllers have a direct unit test;
**13 do not** — and they cluster in the Thymeleaf/htmx UI:

| Untested controller | Kind | Any other coverage? |
|---|---|---|
| `PromotionController` | REST | `PromotionTwoDeploymentE2eTest` (E2E only) |
| `PipelinePartialController` | htmx partial | none found |
| `TemplatePartialController` | htmx partial | `TemplateCreatePartialTest` (create path only) |
| `DatasourcePartialController` | htmx partial | none found |
| `ExecutionHistoryPartialController` | htmx partial | none found |
| `ExecutionDetailPartialController` | htmx partial | none found |
| `DashboardPartialController` | htmx partial | render tests only (template, not logic) |
| `ExecutionHistoryController` | page | partial E2E touches history pages |
| `ExecutionDetailController` | page | none found directly |
| `PromotionUiController` | page | promotion E2E touches UI |
| `DocsController` | page | `SiteDocsE2eTest` |
| `SiteController` | page | `SiteDocsE2eTest` |
| `LocalLoginController` | page | `LocalLoginE2eTest` |

**Correction (2026-09-03, found while backfilling):** this table originally listed
15 controllers — two were false gaps. `ApiKeysPartialController` and
`AdminUsersPartialController` have unit tests as CLASSES INSIDE SIBLING FILES
(`ApiKeysControllerTest.kt:158`, `AdminUsersControllerTest.kt:104`), plus the
8-test `CredentialMintingSessionOnlyTest` standing guard on the session gate.
The audit SCRIPT (name-reference) was right about both; the controller table was
built from a filename-based check that could not see same-name classes in other
files. Lesson folded into the method section: match by declared class name, not
file name.

**Second correction (2026-09-03, Tier 1 completion):** the execution pages were
also partially covered under a different class name — `ExecutionControllerTest`
(not `ExecutionHistoryControllerTest`/`ExecutionDetailControllerTest`) already
pinned several history/detail contracts. The same-name check under-counted
coverage in both directions: it missed sibling-file classes (over-reporting
gaps) and, being filename-based, could not see differently-named suites either.
The backfill landed the same-name unit classes anyway — they pin deeper
contracts (the four-way resultState derivation, the cancel gate, the lineage
family read) — but the audit's controller table should be read as
"same-name test absent," never "no test touches this controller."

The pattern is historical: REST controllers got the unit-test treatment as they
were built; the UI layer grew later (024 website round onward) and leaned on E2Es
plus the browser-driven dev loop. The htmx partials are the untested tail at EVERY
layer — no unit test, and mostly no E2E either.

Also untested at unit level beside the controllers: `McpRecordingExecutionRunner`
(142 LOC, zero refs — records MCP-driven executions for UI visibility) and
`PromotionInventoryService` (100 LOC; promotion E2E exercises it end-to-end only).

## Layer 2 — Domain unit tests (strong; four verified gaps + second-order partials)

The domain modules are in good shape (typesystem: zero gaps; auth: 2, both config
wiring). Zero-reference files with real behavior:

1. **`JdbcUrlGuard` (datasources, 166 LOC) — the audit's highest-value backfill.**
   The §5.6 URL-carrier refusal guard (H2 `INIT`, PG `socketFactory`, MySQL
   `autoDeserialize`, DuckDB `session_init_sql_file`, credential-in-URL, case
   insensitivity, `?`/`&`/`;` tokenization). `DialectRefusalSetsTest` pins the
   SET CONTENTS and the two-carrier parity — but the guard's own parsing logic
   has no test. This is a security surface: a parsing regression is an RCE/SSRF
   hole, silent by construction. Verified untested directly.
2. **`SqlBindTranslator` (dag, 105 LOC)** — bound-SQL translation, zero refs.
3. **`McpRecordingExecutionRunner` (web, 142 LOC)** — see above.
4. **`FailureCollector` (pipeline-contract, 50 LOC)** — used by test *fixtures* as
   a helper, so it runs constantly, but its collection/dedup semantics are never
   the thing asserted.

Second-order partials worth naming (nested/helper types with behavior, inside
otherwise-tested files — full list from the script):

- `PromotionService` — `PinnedPipeline`/`Plan` (web)
- `ExecutionLauncher` — `Attach`/`Reservation` (web)
- `NodeOutputModule` edge cases: blank-vs-absent `output` (the binding rule
  `NodeTypeRules.kt:71` documents) is covered implicitly by round-trips; an
  explicit edge-case test is cheap insurance on exactly the reflective layer
  class of the `xmin`/`arg0` failure family
- `TemplateJson` — `IsoInstantSerializer`/`IsoInstantDeserializer`
- `ClientAddressResolver` — `Cidr` parsing (auth)
- `McpNotFound` (mcp-server, 99 LOC, zero refs)
- `RedisLastUsedWorkspaceStore` (web, 55 LOC — Redis-backed, zero refs)

## Layer 3 — Persistence integration tests (COMPLETE — no backfill needed)

Every repository in the codebase has a Testcontainers-backed integration test.
Verified, not assumed:

| Repository | Test |
|---|---|
| `PipelineRepository` | `PipelineRepositoryIntegrationTest` (module-local) |
| `TemplateRepository` | `TemplateRepositoryIntegrationTest` (module-local) |
| `DatasourceRepository` | `DatasourceRepositoryIntegrationTest` (module-local) |
| `ExecutionRepository` + `ExecutionEventRepository` | `ExecutionRepositoriesIntegrationTest` (dag, module-local) |
| `UserRepository`, `ApiKeyRepository`, `WorkspaceRepository`, `AuditLogger` | `AuthRepositoriesIntegrationTest` (auth, module-local) |

This matches the target structure exactly (unit at controller/domain, integration
at persistence) — the layer needing no work is this one.

## Explicit NOT-list (zero-reference files that do NOT need direct tests)

- **DI/configuration wiring** — exercised by context-load smoke tests and 90+ web
  tests that boot the relevant slices: `EngineConfiguration`, `WebSurfaceConfiguration`,
  `BootstrapConfiguration`, `AppConfiguration`, `TemplatesConfiguration`,
  `LocalAuthConfiguration`, `PipelineLifecycleConfiguration`, `TemplateLifecycleConfiguration`,
  `SweepSchedulingConfiguration`, `RetentionSchedulingConfiguration` (the sweepers
  themselves ARE tested: `StaleExecutionSweeperTest`, `ExecutionEventRetentionTest`).
- **E2E-covered seeds/filters**: `SystemActorSeeder`, `StagingHealthIndicator`
  (the `/health` payload is E2E-asserted).
- **Value holders / projections with no logic**: `UiConfig`, `SiteAssetConfig`,
  `ToastHtml`, `WebHeaders`, `StagingEngine` (enum), `TemplateVersionDetail`,
  `AuditEventSink` (26-LOC delegator).
- **Indirectly covered** (verified): `NodeOutputModule` (round-trips — an
  edge-case test is Tier 2, not a gap), `FailureCollector` (Tier 2, same logic).
- **`WebCorsConfiguration`** — wiring, but the CORS CONTRACT (origins, methods,
  credentials) is security-relevant and cheap to pin; Tier 1 rather than NOT-list.

Re-flag policy: anything on this list that GAINS behavior (loops, conditions,
new endpoints) leaves the list and enters a tier.

---

## Backfill plan (proposed tiers)

**Tier 1 — behavioral gaps with real risk (do first):**
1. `JdbcUrlGuardTest` — every dialect's refusal set applied to the URL carrier;
   case variants (`Socketfactory`); `?`/`&`/`;` tokenization per driver; credential
   authority `//user:pw@host`; `user=`/`password=` properties; the fail-closed rule.
2. The 15 untested controllers, unit tests in house style (direct instantiation,
   MockK collaborators, assert view name / model / fragment HTML / status mapping /
   `@RequiredScope` presence where the Konsist guard doesn't already force it).
   Start with the seven htmx partials (zero coverage at any layer), then pages,
   then `PromotionController`.
3. `PromotionInventoryService` + `PromotionController` unit tests (E2E exists;
   unit layer missing).
4. `SqlBindTranslatorTest` (dag).
5. `McpRecordingExecutionRunnerTest` (web).
6. `WebCorsConfigurationTest` — pin allowed origins/methods/credentials/headers.

**Tier 2 — second-order partials** (from the list above; each is a focused test
inside an existing test source set).

**Tier 2 status (2026-09-03, landed):** `NodeOutputModuleTest` (the lenient-binding
edges — absent table binds empty, absent target binds caller, APPEND fallback),
`FailureCollectorTest` (accumulation, snapshot immutability, CF-1/CF-2 path hygiene,
the frozen §15.1 grammar), `McpNotFoundTest` (the six §13 code reuses + the §5.3
requireVisible gate), `RedisLastUsedWorkspaceStoreTest` (keyspace + both fail-open
paths), `IfMatchHeaderTest` (new with 056), and `TemplateJsonInstantTest` (strict
ISO-instant binding; offset-lenient parsing recorded as the pinned JDK's actual
semantic). **NOT-list addition:** `ClientAddressResolver.Cidr`'s "partial" status was
an artifact — the class is private and CANNOT appear in a test by name;
`ClientAddressResolverTest` already pins all twelve CIDR/resolution edges. The
`PromotionService.Plan`/`PinnedPipeline` and `ExecutionLauncher.Attach`/`Reservation`
partials stay deliberately un-backfilled: internal helpers inside classes whose
behavioral surface is covered (`PromotionServiceTest`, `ExecutionStreamLauncherTest`),
and a test that names them would pin shape, not behavior.

**Tier 3 — the browser suite** (below) — separate module, separately invoked,
not part of `./gradlew build` or `gate.sh` unless explicitly asked.

---

## Browser test suite — design (proposed, not yet built)

**Goal:** mechanical, repeatable, LLM-free browser tests of the release-critical
UI golden paths — the codified descendant of the ad-hoc `.playwright-mcp` dev
loop. Invoked explicitly before a release: `./gradlew browserTest`.

**Decisions (ratify or veto):**

1. **Playwright for Java** (`com.microsoft.playwright:playwright`, pinned in
   `gradle/libs.versions.toml`). The repo is pure Gradle with zero npm
   infrastructure; the only non-Gradle toolchain precedent (`editorJsTest`,
   `node --test`) was chosen precisely because it adds no package — Playwright
   cannot avoid a dependency, so it goes through the standard catalog + lockfile
   + verification-metadata procedure (DEVELOPMENT.md §6.2, resolveAndLockAll).
   Pinning the library version pins the browser build (Playwright ships a
   versioned driver + browser set).
2. **New module `tests/browser-tests`**, allowed internal dependency `:modules:app`
   (same allowance row as `tests/integration-tests` — requires a module-structure.md
   §3/§4.2 table edit FIRST, per the review-gate rule, plus `settings.gradle.kts`,
   a `NO_COVERAGE_FLOOR_ALLOWLIST` entry with reason, and the catalog/lockfile
   update in the same commit).
3. **Self-contained, deterministic app boot:** `@SpringBootTest(RANDOM_PORT)` +
   Testcontainers Postgres + Redis (the integration-tests pattern), local-auth
   enabled, seeded admin with a known password (reuse `LocalLoginE2eTest`'s
   fixture approach). NOT driven against an already-running stack — the suite
   must be re-runnable to one command with zero manual setup.
4. **Separate invocation, never in the default graph:** root alias task
   `browserTest` → `:tests:browser-tests:test`; NOT wired into `build`/`check`.
   Documented in DEVELOPMENT.md §9 beside `integrationTest`. First run without
   browser binaries FAILS with the `playwright install chromium` instructions —
   a deliberate invocation that silently skips is not a verdict (JarSmoke
   precedent); banner-skip is reserved for involuntary toolchain absence
   (`editorJsTest` precedent), which does not apply to an explicitly-invoked gate.
5. **Chromium only, headless, auto-wait.** No timing sleeps; selectors by stable
   id/role/data attribute (add `data-testid` where the UI lacks a stable hook —
   added attributes are a `web` module change, kept minimal). Traces + screenshots
   saved under `tests/browser-tests/build/reports/` on failure for diagnosis.

**Initial golden paths (the release checklist, ~10 specs):**
1. Login page renders; local login → forced password change → lands on dashboard
2. Wrong password shows the error, no session
3. Datasources: create → appears in list → edit → delete
4. Templates: create → edit → version visible
5. Pipeline editor: create pipeline → node SQL renders → DAG visualization appears
6. Execute from the UI → SSE events stream into the page → results panel populated
7. Execution history lists the run; detail page shows nodes/results; pagination works
8. API keys: mint (secret shown once) → revoke
9. Workspace switcher: switch → surfaces re-scope
10. Site + in-product docs pages render signed-in; `/docs` unreachable signed-out

**Out of scope for v1:** multi-browser matrix (chromium only), visual regression
pixel-diffing, accessibility scans (separate follow-ups if wanted).

---

## Verification

```bash
python3 scripts/test-gap-audit.py          # re-derive the inventory (method above)
./gradlew :modules:datasources:test --tests '*JdbcUrlGuard*'   # after Tier 1.1
./gradlew browserTest                      # after the suite exists
```

## Open questions

1. Ratify the three browser-suite decisions (Playwright-Java, new module, self-contained boot)?
2. Tier 1 order as proposed, or JdbcUrlGuard + browser suite first (security + release confidence) with controllers after?
3. Commit this record + `scripts/test-gap-audit.py` now, before any backfill lands?
