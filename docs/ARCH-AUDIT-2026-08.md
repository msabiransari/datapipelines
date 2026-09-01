# ARCH AUDIT — Service Layer & Multi-Instance Readiness (2026-08)

**Status:** findings record — reviewed 2026-08-31; resolution tagging still open
**Audit date:** 2026-08-31 (three read-only code audits: service-layer inventory, in-memory state, lifecycle/background)
**Audit baseline:** `b844478` (dirty tree — parallel implementation sessions in flight; line numbers may drift)
**Review baseline:** `7f87118` — the findings marked **[verified]** below were independently re-checked against the tree at that commit. Everything unmarked stands on the original audit's own reading.
**Not packaged into the product** (`modules/web/build.gradle.kts` `processResources` exclusion): this is contributor material pending review, alongside `SPEC-REVIEW-2026-08.md` and `semantic-layer-research.md`. Reaching it in-app would present unratified findings as product documentation.

Every finding carries: **Severity**, **Evidence** (file:line), **Failure mode**, **Proposed fix**, and **Verification**. Findings marked **[verified]** were independently re-checked in review against `7f87118`; **[added in review]** marks one finding the review promoted out of two others' footnotes. Findings tagged `code-read` were established by reading source, not by running a multi-instance reproduction; where a conclusion depends only on the absence of a call/branch in code, that is stated. Nothing here has been reviewed or ratified yet — reviewers: tag each finding with a decision or reject it with reasoning, SPEC-REVIEW-2026-08 style.

Related design context (same session): a planned **pipeline-change notification** feature (SSE push to the web editor when MCP/REST updates a pipeline). Its constraints are folded into M10 and the service-layer findings.

---

## Part 1 — Service-layer gap

### S1 — The no-service pattern is project-wide, not pipeline-specific
**Evidence:** ~25 surfaces call repositories/registries directly across pipelines, templates, executions, datasources, and the UI layer:
- REST: `PipelinesController.kt:44`, `TemplatesController.kt:45`, `ExecutionsController.kt:48`, `DatasourcesController.kt:58`, plus all `web/ui/*` Thymeleaf controllers (e.g. `PipelineUiController.kt:17`, `ExecutionHistoryController.kt:11`, `TemplateUiController.kt:17`).
- MCP: every tool class — `PipelineReadTools.kt:10`, `PipelineAuthoringTools.kt:95`, `ExecutionTools.kt:77`, `TemplateReadTools.kt:20`, `TemplateAuthoringTools.kt:45`, `DatasourceTools.kt:39`, `McpResourceReader.kt:29`.

**Exceptions (the pattern is proven, just not adopted):**
- `auth` is the systematic exception: `UserService`, `WorkspaceService`, `ApiKeyService`, `LocalAuthService`, `LocalPasswordService`, `JwtService`. `WorkspacesController.kt:41` is the cleanest controller in the codebase (zero repository contact).
- Ad-hoc de-facto services elsewhere: `PipelineImportService`, `TemplateImportService`, `ExecutionLauncher` (`web/pipelines/ExecutionLauncher.kt:95` — a ~20-dependency orchestrator that is a service in everything but location), `ResultCursor`, `DatasourceWorkspaceRules`, `DefaultDatasourceRegistry` (datasources), `ExecutionCancellationService` + `PipelineExecutor` (dag).

**Mixed controllers** (service for writes, repository for reads): `AuthController.kt:58` (`ApiKeyRepository` direct at :68), `ApiKeysPartialController.kt:23` (:57/:75), `UserSettingsController.kt:23` (`UserRepository` direct at :34/:54/:96).

### S2 — Web↔MCP drift list (the real cost of S1)
Eight use cases are implemented twice (or more), once per surface. A service layer exists to kill exactly this list:

| # | Use case | Copies | Note |
|---|---|---|---|
| D1 | Pipeline save validation | `PipelinesController.kt:161-167` vs `PipelineAuthoringTools.kt:28-34` (`PipelineSaveSupport`) | identical deserialize→validate→canonical triple |
| D2 | Pipeline list filtering | `PipelinesController.kt:141-158` vs `PipelineReadTools.kt:35-58` | separate owner/datasource/q implementations |
| D3 | Template create/update | `TemplatesController.kt:56-63,:118-128` vs `TemplateAuthoringTools.kt:62-79` + update tool | draft assembly duplicated |
| D4 | Template render | 4 surfaces: `TemplatesController.kt:145`, `TemplatesRenderTool` (`TemplateAuthoringTools.kt:174-182`), `TemplateEditorController.kt:44`, `PipelineNodeSqlPartialController.kt:200` | each resolves versions its own way |
| D5 | Execution result fetch | `ExecutionsController.kt:157` (`ResultCursor`) vs `ExecutionsGetResultTool.kt:79-104` vs `ExecutionDetailController.kt:41-49` | own cap/probe/pagination logic each |
| D6 | Pipeline execute | REST via `ExecutionLauncher` (idempotency, `ParameterBinder`, SSE) vs `PipelineExecuteTool.kt:104-130` | **behavioral divergence: MCP execute has no idempotency support** — a bug wearing a duplication costume; fix first |
| D7 | Execution list visibility | `ExecutionsController.kt:44` vs `ExecutionTools.kt:70-77` | admin/user branch implemented twice |
| D8 | Datasource write gates | REST/UI share `DatasourceWorkspaceRules`, but MCP cannot use it (lives in web) | third copy waiting to happen |

### S3 — Zero transaction demarcation in main code **[verified]**
**Evidence:** no `@Transactional`/`TransactionTemplate` anywhere in `modules/*/src/main`. Repositories rely on atomic single-statement CTEs (KDoc at `PipelineRepository.kt:30`). A service layer that composes multiple repository calls per use case breaks that invariant — the service layer and the transaction boundary decision must land together.

### S4 — Module constraint: services cannot live in `modules/web`
**Evidence:** dependency rule is web → mcp-server, never reverse (documented in `McpExecutionRunner.kt:11-13`). Shared use-case services must live in the domain modules (`pipeline-contract`, `templates`, `dag`, `datasources`) or a new module both surfaces depend on. This is already biting: D8 exists because `DatasourceWorkspaceRules` is unreachable from MCP.

### S5 — Proposed direction (for review)
Per-aggregate services (`PipelineService`, `TemplateService`, `ExecutionService`, `DatasourceService`) in their domain modules, absorbing D1–D8; auth's service-first shape is the in-repo exemplar. The planned pipeline-change notification hook (listener on pipeline save) should hang off `PipelineService`, not the repository — today `PipelineRepository.update` (`PipelineRepository.kt:256`) is the single choke point both surfaces share, which is what makes either hook viable.

---

## Part 2 — Multi-instance findings

Ranked by severity. The request path is genuinely multi-instance-ready in places (idempotency, results, event log, cancellation flags, rate limiting are all Redis-backed) — the failures concentrate in **crash/shutdown recovery, connection pooling, and per-JVM limits**.

### M1 — Shutdown drain implemented but never wired — CRITICAL **[verified]**
**Evidence:** `ExecutionCancellationService.cancelAllLocal()` exists (`modules/dag/.../executor/CancellationFlags.kt:113`, KDoc: "The shutdown drain (§8.3)") with **zero production callers** (only tests). `WebSurfaceConfiguration.kt:133` closes `executionScope` with `job.cancel()` only — no statement cancellation, no drain. No `server.shutdown: graceful` in any YAML; no `SmartLifecycle`/`ContextClosedEvent` listener; `/ready` (`HealthController.kt:50`) never flips on shutdown.
**Failure mode:** SIGTERM (rolling deploy, scale-down) kills executions mid-run. JDBC `Statement.cancel()` never happens (source DBs run queries to their own timeout), no `execution_aborted` event, no status update → rows stuck `RUNNING` forever.
**Docs contradiction (see M11):** `deployment.md` §8.3.1/§8.3.2 describe readiness-fail → drain-to-timeout → `cancelAll(shutdown)` → flush, plus `terminationGracePeriodSeconds`/`preStop` guidance, **as shipped**. The `deploy/helm/` chart the docs reference does not exist in the repo.
**Proposed fix:** lifecycle bean calling `cancelAllLocal()` before `executionScope.close()`; `server.shutdown: graceful`; Spring availability probes. All primitives exist — this is wiring, not design.
**Verification:** code-read (zero callers is a grep-level fact).

**[resolved — 036, branch `fix/multi-instance`]** `ExecutionDrainLifecycle` (web/config) is the
missing caller: on stop it publishes `ReadinessState.REFUSING_TRAFFIC` **first** (order asserted
by `ExecutionDrainLifecycleTest`), then `cancelAllLocal()`, then a bounded flush wait on
`liveExecutions` (added to the `CancellationRegistry` interface for exactly this). Default
`SmartLifecycle` phase stops it before the web server's graceful-shutdown lifecycle and before
`executionScope.close()`. `server.shutdown: graceful` set in `application.yml`. **B3 confirmed:**
`cancelAllLocal()` → `registry.cancelAll` → per-execution `cancel()` → `cancelStatements()` →
JDBC `Statement.cancel()` — the drain reaches the source database, it is not a status flip.

### M2 — Crash sweep implemented but never scheduled — CRITICAL **[verified]**
**Evidence:** `ExecutionRepository.sweepStaleRunning(olderThan)` (`modules/dag/.../executor/ExecutionRepository.kt:362`) flips stale `RUNNING` rows to `ABORTED` with `pipeline.execution.instance_lost`. **Only test code calls it.** The config key it consumes (`datapipelines.executions.stale-timeout-minutes`, `application.yml:201`) is dead.
**Failure mode:** combined with M1, every pod-killed execution stays `RUNNING` forever — directly contradicting `deployment.md:226` (see M11) ("swept to ABORTED by the stale-execution sweep"). Secondary: `DELETE /executions/{id}` on such a row returns 204 and writes a Redis cancellation flag no live instance polls — silent no-op.
**Proposed fix:** a `@Scheduled` sweeper (would be the first in the codebase). The `UPDATE … WHERE status='RUNNING' AND started_at < :t` is naturally idempotent — all N replicas may run it without leader election.
**Verification:** code-read.

### M3 — Datasource connection pools never expire cross-instance — CRITICAL **[verified]**
**Evidence:** `modules/datasources/.../datasources/pooling/ConnectionPoolManager.kt:60,63` (note the `pooling` subpackage — an earlier revision of this line omitted it, and a grep on the shorter path finds nothing) — `pools` ConcurrentHashMap, `poolFor` = `computeIfAbsent` keyed by datasource name only; no TTL, no version check. Eviction happens only on the writing instance (`DefaultDatasourceRegistry.kt:130` update, `:150` delete).
**Failure mode:** operator repoints a datasource or rotates its password via instance A. Instance B's `DatasourceMetadataCache` TTL-expires after 60s and serves fresh metadata — but every execution on B still leases from the **old Hikari pool** (old URL, old credentials) **until B restarts**. If old credentials are invalidated: permanent execution failures on B. If the old host stays up: B silently reads/writes the wrong database. A soft-deleted datasource keeps serving queries on B indefinitely. This defeats the TTL design's own DS-SEC-15 reasoning — the pool layer reintroduces unbounded staleness the metadata layer explicitly bounded.
**Proposed fix:** Redis pub/sub invalidation that calls `poolManager.evict(name)` on all peers (shares infrastructure with M10), or row-version check at lease time.
**Verification:** code-read (no expiry/version comparison exists on the path); not reproduced with two instances.

### M4 — `ExecutionSlots` limits are per-JVM, not global — HIGH
**Evidence:** `ExecutionSlots.kt:27-28` — `global` AtomicInteger + `perUser` map. `max-concurrent-executions-global` (default 100) and per-user (10) enforced in-memory.
**Failure mode:** N replicas → effective limits are N× configured. The named `-global` guarantee is void; source-DB protection is N times weaker than the operator set.
**Proposed fix:** Redis-shared slot accounting (pattern precedent: `RedisRateLimiter`, `web/ratelimit/RateLimiter.kt:65`) — though atomic check-and-hold slots are harder than counters and need a lease/expiry design, or document as per-replica limits explicitly.
**Verification:** code-read.

### M5 — First-boot seeder races — MEDIUM
**Evidence:** find-then-insert without duplicate tolerance:
- `BootstrapDatasourceRegistrar.kt:50-59` (`existsIncludingDeleted` → `save` against `datasources.name` UNIQUE)
- `LocalAdminSeeder.kt:63-82` → `UserService.provisionBootstrapActor` (`UserService.kt:82-95`, against `users.email` UNIQUE)

**Failure mode:** two pods starting against a fresh DB both pass the check; the loser throws `DuplicateKeyException` inside `afterSingletonsInstantiated()` → context fails → one crash-loop, then the restart succeeds. Self-healing but can briefly mark a rollout degraded.
**Proposed fix:** catch `DuplicateKeyException` and re-read — precedent exists at `LocalPasswordService.kt:153`.
**Verification:** code-read.

### M6 — First-login races (transient 500s) — MEDIUM
**Evidence:** `UserService.findOrCreateByEmail` (`UserService.kt:32`) — find-then-insert, no duplicate catch on this path. `WorkspaceService.ensurePersonalWorkspace` (via `WorkspaceService.kt:129`) is deliberately loud on failure (`PersonalWorkspaceSeeder.kt:21`).
**Failure mode:** a user's two concurrent first OIDC logins on different replicas → one 500; retry succeeds.
**Proposed fix:** same catch-and-reread as M5.
**Verification:** code-read.

### M7 — Per-JVM caps multiply by N — LOW
- **SSE per-user stream cap** — `ExecutionStreamRegistry.kt:50,76,100`: `max-streams-per-user` is effectively ×N.
- **LoginRateLimitFilter** — `auth/LoginRateLimitFilter.kt:38`: per-JVM; KDoc documents this as deliberate ("a brute-force damper, not a distributed quota"). A `RedisRateLimiter` exists if the decision is revisited. **Related but distinct: OPEN-ITEMS T46** — the login limiter keys on `request.remoteAddr`, which behind the documented LB is the LB's own address, collapsing the per-IP budget to ONE deployment-wide bucket. T46 is a wrong-key defect; M7 is a not-shared defect. Whoever fixes either should read the other, since a Redis-backed limiter keyed on the same wrong value would be no better.

### M8 — Accepted staleness (documented, listed for completeness)
- **AuthCache** (`auth/AuthCache.kt:47-58`): user deactivation, API-key revocation, membership revocation propagate to non-mutating instances within the 60s TTL — the accepted D13 contract. Within the TTL a revoked key keeps passing on a warm `verifiedSecrets` hit.
- **Per-JVM gauges** (`ExecutorMetrics.kt:86-91`, `ExecutionStreamRegistry.kt:58`): correct only when summed across instances; cosmetic.

### M9 — Existing execution SSE: narrower gap than assumed
**Evidence:** the live-emitter registry (`ExecutionStreamRegistry.kt:49`) is only read on the instance running the execution (`WebEventEmitter.kt:129`). `POST /pipelines/{id}/execute` returns the stream **as its response** (single connection — execute-then-watch never crosses instances). `GET /executions/{id}/events` (`ExecutionsController.kt:143-154`) replays from the Redis `SseEventLog`; idempotent-retry attach (`ExecutionLauncher.kt:197-207`) follows the same Redis log — both work on any instance. Cross-instance cancellation works (`RedisCancellationFlags`, `CancellationFlags.kt:39-81`).
**Residual gaps:** a browser that loses its execute connection cannot re-attach to the *live* stream from another instance (falls back to 250ms-polled Redis-log follow — acceptable per rest-api §6.8's no-resumption stance), and the ×N stream cap (M7).
**Verification:** code-read.

### M10 — Any new server-push channel needs Redis fan-out (design constraint)
The planned pipeline-change notification (reload the editor when MCP/REST updates a pipeline) cannot use a per-JVM emitter registry alone: the MCP write can land on instance A while the user's SSE lives on B. Required shape: Redis pub/sub topic (e.g. `pipeline-changes`, workspace-scoped payloads carrying `{pipelineId, version, updatedBy, at}`) → each instance fans out to its local emitters. The same topic can carry datasource-change invalidations, making M3's fix nearly free. Note this introduces a new pattern: the codebase's existing cross-instance answers are Redis *state* (flags, logs, counters) and TTL caches — not messaging. Worth an explicit decision in review.

---

### M11 — `deployment.md` describes three things that do not exist — HIGH **[verified, added in review]**

M1 and M2 each note a docs contradiction in passing, and open question 5 asks what to do
about them. Collecting them here because they are **independently fixable and far cheaper
than the code**, and because an operator reads them as shipped guarantees today:

| Claim | Where | Reality |
|---|---|---|
| Graceful drain: readiness-fail → drain-to-timeout → `cancelAll(shutdown)` → flush | `deployment.md` §8.3.1/§8.3.2 | `cancelAllLocal()` has zero production callers; no `server.shutdown: graceful` in any YAML (M1) |
| "Their rows are swept to `ABORTED` by the stale-execution sweep" | `deployment.md:226` | `sweepStaleRunning` has zero production callers; the config key it consumes is bound but never read (M2) |
| "Reference Helm chart in `deploy/helm/`" | `deployment.md:246` | `deploy/helm/` does not exist in the repo |

**Why this is its own finding rather than a footnote.** This is the claim-honesty class the
project has treated as blocking before (the 024 website round was held on exactly it): a
document asserting a safety property the code does not implement is worse than silence,
because an operator plans around it — sizing `terminationGracePeriodSeconds`, trusting that
a killed pod's executions self-heal. The code fixes (M1, M2) are real work; **the doc fix is
an afternoon and removes the false guarantee immediately.** They should not be bundled: fix
the docs now, then let M1/M2 restore the claims when they ship.

**Verification:** code-read, re-checked in review — `deploy/helm` absent, `deployment.md:226`
and `:246` read as quoted, zero production callers for both primitives.

**[resolved — 036, branch `fix/multi-instance`]** `deployment.md` v1.5: §8.3.1/§8.3.2 rewritten
to shutdown behavior as shipped (no drain, no sweep, no readiness flip), the §6.2 sweep claim
corrected, and `deploy/helm/` made real with a minimal chart (Deployment/Service/HPA/PDB,
`helm template` + `helm lint` clean). The drain/sweep claims return with M1/M2 below.

## Verified safe — explicit NOT-list (do not re-flag)

- **Cross-instance cancellation** — `RedisCancellationFlags`: any replica's DELETE writes `dp:cancel:{id}`; the owning instance polls per heartbeat tick and at node boundaries.
- **Results** — `RedisResultStore` fully materializes; REST cursor and MCP read from any replica.
- **Idempotency** — `RedisIdempotencyStore` `SET NX` (`IdempotencyStore.kt:72-86`): cross-replica retries dedupe atomically.
- **Rate limiting (web)** — `RedisRateLimiter`, shared counters.
- **Template caches** — `RepositoryTemplateRegistry` LRU, `InterruptibleConfiguration.postProcessed`, `WorkspaceTemplateEngines` LRU: all keyed by immutable `{id}@{version}`; an edit is a new version = a new key. Cross-instance staleness is not representable.
- **MCP transport** — `HttpServletStatelessServerTransport` (`McpServerFactory.kt:67`), no session state.
- **JWT/OAuth2 login** — stateless HS256 shared secret; encrypted-cookie OAuth2 state; instance-agnostic.
- **Flyway** — 11.7.2 with `flyway-database-postgresql`, in-app at startup; the lock table serializes concurrent instance startups.
- **No `@Scheduled`/Quartz/`GlobalScope`/hand-rolled cron anywhere** (the M2 sweeper would be the first). Background threads are per-instance infrastructure, all properly closed.
- **No filesystem writes** (no `MultipartFile` anywhere); bootstrap files are read-only mounts.
- **In-process locks** (`H2Staging` Mutex, `SecretHasher` Semaphore, `PipelineExecutor` node-parallelism Semaphore) — local resource guards, none assume fleet-wide single-writer.
- **No hostname/pod-identity assumptions**; only loopback binding is the management port (`application.yml:71`), deliberate.

---

## Recommended order (for review)

0. **M11 — correct `deployment.md` first, on its own.** Hours, not days, and it removes a false safety guarantee an operator may already be planning around. Deliberately ahead of M1/M2 and deliberately NOT bundled with them: the doc fix must not wait on the code, and the code should not ship under cover of a doc that already claims it.
1. **M1 + M2** — wire the drain, schedule the sweep. Small diffs, everything they need exists; together they close the "stuck RUNNING forever" hole, and they are what let M11's claims be restored truthfully.
2. **D6 (MCP execute idempotency)** — behavioral bug, independent of the broader service-layer effort.
3. **M3 + M10** — Redis pub/sub invalidation; one infrastructure decision fixes pool staleness and unblocks pipeline-change notifications.
4. **M5/M6** — three-line duplicate-tolerance fixes.
5. **Service layer (S1–S5)** — per-aggregate services in domain modules; decide the transaction-boundary story (S3) in the same breath.
6. **M4** — decide: Redis-coordinated global slots, or documented per-replica limits.

## Open questions for reviewers

1. Redis pub/sub is a new pattern for this codebase (state/flags yes, messaging no). Ratify or pick the lease/poll alternative for M3/M10.
2. Service-layer module placement: domain modules vs a new `use-cases` module both web and mcp-server depend on (S4).
3. With multiple repository calls per service method, where does `@Transactional` live, and does the atomic-CTE-only convention (S3) survive?
4. `ExecutionSlots` global guarantee (M4): enforce fleet-wide via Redis, or redefine the config as per-replica?
5. ~~Docs that currently describe unshipped behavior: correct now, or track as part of M1?~~ **Answered in review — correct now, separately (M11, step 0).** Bundling the doc fix into M1 keeps a false guarantee live for the length of the code work, and this project has treated that class as merge-blocking before. Restore the claims when M1/M2 ship.
