# SPEC REVIEW — August 2026 Consistency Campaign

**Status:** work order + permanent decision record
**Review date:** 2026-08-06 (full-doc review: 3 parallel readers over all 18 docs, load-bearing findings independently verified)
**Decisions ratified:** 2026-08-07 by Muhammad
**Baseline commit:** `c105d58`

Every finding below is tagged with a resolution: a decision number (D1–D15) or **[M]** (mechanical — fix with no design decision needed). Editors: apply only the findings for your assigned doc; the Decisions section is the frozen contract — do not re-litigate it.

---

## Part 1 — Ratified Decisions (frozen)

### D1 — DQL `output` semantics
- Omitted `output` on a DQL node → defaults to `caller` (NOT `tempdb` as v1.1 said).
- Any DQL node may target `tempdb`, `datasource`, or `caller` — no positional restriction. Delete `pipeline.validation.dql_sink_missing_caller_target` (§12.3) entirely.
- New validation rule: **at most one node per pipeline resolves to `caller`** (`pipeline.validation.multiple_caller_nodes`). **Zero caller nodes is legal** — pure write-back/ETL pipelines emit no `data_ready` SSE event, only `pipeline_completed` with stats.
- The caller node **is** the result node. This replaces topology-based terminal-node auto-detection everywhere ("terminal node" language survives only as "the caller node, if any").
- Consequence: a mid-DAG node whose data downstream nodes query must declare `output: {target: tempdb, table: ...}` explicitly.

### D2 — Universal save-time validation
No invalid entity ever reaches the database — pipelines, templates, datasources, every saved contract validates fully at save/update time. Stated as a cross-cutting principle in pipeline-contract §2 (design principles) and referenced from templates/datasources validation sections.

### D3 — Template `params_schema` removed
- `params_schema` is deleted from the template entity (JSON, DDL, API payloads, examples).
- Pipeline `parameters` is the single declaration point. The full parameter map (after defaults applied) is the render context passed to every template in the pipeline.
- Save-time validation of a **pipeline** dry-renders every referenced template against the pipeline's declared parameters (defaults or type-appropriate sample values); undefined Freemarker variables fail validation (`pipeline.validation.template_parameter_undeclared` — kept, redefined to mean "dry-render failed on undefined variable").
- Save-time validation of a **template** = parse-only (syntax, forbidden constructs, imports resolvable). No sample-context render at template save (templates don't know their callers' parameters).
- Templates keep free-text `description` for human/agent discoverability.

### D4 — DDL conventions, one authority
- `metadata-db.md` is the ONLY doc that writes DDL. `datasources.md` §7.2 and `templates.md` §12.2 DDL blocks become pointers to metadata-db.
- JSONB columns uniformly use the `*_json` suffix (e.g. `properties_json`, `definition_json`, `parameters_json`) — metadata-db to apply consistently across ALL tables.
- `TIMESTAMPTZ` everywhere internally (never `TIMESTAMP`), values in UTC. Pipeline-side conversion of non-UTC source timestamps → UTC is already normative in type-system §8.4; cross-link instead of restating.

### D5 — Error-code canon
- `pipeline.staging.creation_failed` wins (delete `pipeline.staging.h2_creation_failed`).
- `template.validation.import_cycle` wins (delete `template.import.cycle_detected`).
- Segmentation scheme `{domain}.{entity}.{failure}` documented once in enums.md §16 with the auth codes normalized to it (`auth.api_key.missing`, `auth.api_key.invalid`, `auth.scope.insufficient`, etc.).
- `result.*`, `rate_limit.exceeded`, `idempotency.key_reused_for_different_request` join the central catalog (pipeline-contract §13 gains subsections; enums §16 lists concrete codes, not just domains).
- Single `rate_limit.exceeded` code — `auth.rate_limit.exceeded` is deleted. Rate-limit dimension is **per-user** (keys are cheap to mint; a per-key limit is trivially bypassed).
- New codes from this campaign: `pipeline.validation.multiple_caller_nodes` (D1), `result.too_large`, `result.storage_unavailable`, `result.not_found` (expired/never existed) (D9).

### D6 — Staging lifecycle (H2)
- Remove `DB_CLOSE_DELAY=-1` from the JDBC URL. (It keeps the in-memory DB alive until JVM exit — a guaranteed leak in a long-lived server. The doc's claim that it ties lifetime to open connections was factually wrong.)
- Lifecycle: the executor opens the single staging connection at execution start and holds it for the execution's duration; default H2 semantics (DB dies when last connection closes) plus an explicit `DROP ALL OBJECTS` + connection close in the executor's `finally` as belt-and-braces. No reliance on GC.
- Single connection is guarded by an explicit `Mutex` — a JDBC `Connection` does not safely serialize concurrent callers on its own.
- New normative rule: **identifier safety** — staged table names are already validated by pipeline-contract §10; source **column names** are attacker-adjacent (they come from user SQL) and MUST be validated against `[A-Za-z_][A-Za-z0-9_]{0,62}` and double-quoted in all generated DDL/DML; duplicate or invalid column names → `pipeline.staging.invalid_column_name`.
- Fix `StageResult` mismatch: `columns: List<ColumnSchema>` (the §10 interface signature wins; §3.2 example updated).
- `StagingFactory.create(executionId, engine: StagingEngine = H2)` — the dag-executor signature wins; staging.md aligns. Precedence: pipeline `settings.tempdb.config.max_memory_mb` overrides global `datapipelines.staging.h2.max-memory-mb` when present.

### D7 — Cancellation & connection control
- Client disconnect (SSE consumer gone) cancels the execution after a grace period: `datapipelines.sse.disconnect-grace-seconds`, default 30. Rationale: never hold datasource connections for a caller that left.
- Explicit cancel endpoint: `DELETE /api/v1/executions/{id}` → `ABORTED`. `ABORTED` production paths are now: client disconnect beyond grace, explicit DELETE, server shutdown.
- dag-executor gains the plumbing spec: each running node registers its `Statement`; cancellation = `Statement.cancel()` then coroutine cancellation; tempdb connection closed in `finally`.
- rest-api §6.8 rewritten: no resumption, no "poll to recover a running execution" story. On disconnect the client should assume the execution will be cancelled after grace. `Last-Event-Id` references deleted.
- Datasource `properties` becomes two namespaced passthrough maps: `properties.hikari.*` (any HikariCP property, fed to `HikariConfig` verbatim) and `properties.jdbc.*` (driver-level properties). Validation of keys is delegated to Hikari/the driver at pool build; unknown keys fail datasource save (D2) via a test pool build. `datasource.validation.properties_invalid` redefined accordingly.

### D8 — Configuration authority
- `configuration.md` is the ONLY doc that defines config keys (YAML path + env var + default + description). All other docs reference keys by name and link; they never restate defaults or redefine names.
- Unit-suffixed names win every conflict: `-seconds`, `-bytes`, `-minutes` (e.g. `node-query-timeout-seconds`, `large-result-threshold-bytes` → superseded by D9 keys).
- Typo `DATAPIPLEINES_DB_ENCRYPTION_KEY` (datasources.md §7.1) → `DATAPIPELINES_DB_ENCRYPTION_KEY`.
- `DATAPIPELINES_DB_ENCRYPTION_KEY` is **required, fail-fast**. The KMS/auto-generated-file fallback chain in datasources §7.1 is deleted (KMS integration remains a v1.1 ROADMAP item; silent key-file generation is how data gets lost on redeploy).
- New keys this campaign introduces (canonical definitions land in configuration.md): `datapipelines.sse.disconnect-grace-seconds` (30), `datapipelines.result.ttl-default-seconds` (300), `datapipelines.result.ttl-min-seconds` (60), `datapipelines.result.ttl-max-seconds` (3600), `datapipelines.result.max-size-bytes` (104857600), `datapipelines.result.page-size-rows` (1000), plus adoption of the previously-orphaned keys: `datapipelines.staging.h2.result-batch-size`, `datapipelines.audit.retention-days`, `datapipelines.templates.cache-size`, `datapipelines.templates.render-timeout-ms`, `datapipelines.ui.theme` (with the valid theme list).

### D9 — Result delivery contract (rest-api §7 rewrite)
- **Every** caller result is written to Redis on completion — the 1MB inline-vs-claim-check threshold split is deleted (it was the root of the "inline results unretrievable" hole).
- `data_ready` SSE event carries: full schema (always), the **first page inline** (up to `result.page-size-rows`), `total_rows`, and `result_url`. For small results the first page is the whole result — the common case stays one round-trip.
- `GET /api/v1/executions/{id}/result?offset=&limit=&format={json|arrow|csv}` works uniformly for any completed execution within TTL. Results are fully materialized in Redis before the cursor is issued → stable ordering, stable paging.
- TTL: client may request via **`DP-Result-TTL-Seconds`** header; effective TTL = `clamp(requested, ttl-min, ttl-max)`, default `ttl-default`. **Fixed** expiry (page reads do not extend it).
- Hard cap `result.max-size-bytes` (default 100MB) → execution fails with `result.too_large`. The durable path for large data is `output.target: datasource` — stated as an explicit NOT-goal for result delivery.
- Redis unavailable at result-write time → execution fails with `result.storage_unavailable`. No silent fallback to inline (would reintroduce the dual path).
- Expired/unknown result → `result.not_found` (404).
- Cursor access requires normal auth + `read` scope + ownership of the execution. `result_url` is NOT a capability URL.
- MCP `executions_get_result` uses the same cursor semantics (offset/limit tool args); binary columns base64-inlined only up to a small cap (1MB), else the tool returns the REST cursor URL.
- Post-completion event log lives in Redis for 1h (same store); `execution_events` (Postgres) is the durable 7-day record. rest-api §6.8's "event log" language points at this explicitly.
- Deployment guidance: Redis `maxmemory-policy noeviction` (LRU eviction would silently destroy idempotency keys and results).
- Observability: metrics for result-store bytes written, cursor hits, TTL expiries.

### D10 — Header convention
- All custom headers use the `DP-` prefix: `X-API-Key` → `DP-API-Key`, `X-Correlation-Id` → `DP-Correlation-Id`. New: `DP-Result-TTL-Seconds`.
- `Idempotency-Key` stays as-is (de-facto standard, IETF draft).
- CORS allow-lists updated to match; CSRF token header named explicitly (`DP-CSRF-Token`).

### D11 — MCP auth
- `POST/GET /mcp` accepts `DP-API-Key: dpk_...` AND `Authorization: Bearer dpk_...` — both feed the same API-key validation path (many MCP clients can only set the Authorization header).
- `/mcp` is explicitly added to the Spring Security chain spec: CSRF-exempt, API-key-only (no session cookies), same scope enforcement as REST.

### D12 — Template imports
- `imports` array entries are `{id, version, alias}`. The template **body never contains `<#import>` directives** — the engine synthesizes them from the array at render time (registry-backed template loader keyed on `id@version`).
- Validation: aliases unique per template; imported template must exist at that exact version and be `is_library: true`; transitive depth cap 10 and cycle detection unchanged (`template.validation.import_cycle` per D5).
- `is_library: true` templates contain only macro definitions; for them `body` holds the macros (so `body` stays required — the "no main body" phrasing is corrected to "no output outside macro definitions").

### D13 — Revocation latency
Every authenticated request re-checks user `is_active` and API-key revocation through the existing cache mechanism (60s TTL — same cache auth.md §11.4 already defines for key hashes). JWT requests do a cached user lookup; deactivation takes effect within ~1 minute. The "deactivated users keep 8h JWTs" hole is closed; auth.md principle 8 ("backed by Postgres lookups") becomes true.

### D14 — Scopes at login (v1 rule)
`is_admin = true` → `admin`; every other active user → `author`. (Finer per-user scope assignment and IdP group sync remain v2 — auth §15.) The JWT `scopes` claim is derived from this rule at token issue time.

### D15 — Scope ↔ operation matrix
One authoritative matrix lands in auth.md (new section): every REST endpoint family, every MCP tool (all 15), and every UI screen action mapped to its minimum scope. rest-api.md, mcp-server.md, and ui-screens.md reference the matrix instead of asserting scopes locally. mcp-server §4.1 `read-only` → `read`. MCP datasource management tools require `admin` (matching UI); `datasources_test` requires `author`; `pipelines_execute` requires `execute`; `executions_get_result` requires `read`.

---

## Part 2 — Findings by target doc

Legend: **[Dn]** = resolved by decision n. **[M]** = mechanical fix. Section numbers refer to the baseline (`c105d58`) versions.

### 2.1 pipeline-contract.md
1. [D1] §4.7 vs §12.4 vs enums §3 vs dag-executor §4 four-way contradiction on omitted `output` → new rule: default `caller`, at-most-one caller node, zero legal.
2. [D1] §12.3 `dql_sink_missing_caller_target` contradicts the doc's own worked examples (§9.3, §16.3) → delete rule; examples stay legal.
3. [D1] §9 terminal-node auto-detection from topology → replaced by "the caller node is the result node".
4. [D3] §12.6 `template_parameter_undeclared` redefined as dry-render check; §6.3/§7.2 context rules restated (pipeline parameters only; defaults applied before render).
5. [D5] §13 catalog gains `result.*`, `rate_limit.exceeded`, `idempotency.key_reused_for_different_request`, `pipeline.validation.multiple_caller_nodes`; loses `dql_sink_missing_caller_target`.
6. [D2] §2 design principles gain the universal save-time-validation principle.
7. [M] §11.3 promotion flow says `GET /pipelines/{id}?version={v}`; §14 says `GET /pipelines/{id}/versions/{version}` — §14 form wins (matches rest-api).
8. [M] §12.6 `template_dialect_mismatch` hardcodes H2 for `source: tempdb` — derive from `settings.tempdb.engine` instead.
9. [M] §10.1 `output.table` global uniqueness over-constrains: scope uniqueness to (target=tempdb) ∪ (per-datasource for target=datasource).
10. [M] §17.1 `NodeOutput` nested-sealed-`Target` vs dag-executor §4 sealed-interface — dag-executor's flat sealed interface wins; align.
11. [M] §11.4 `forbidden_env_specific_value` — specify the scanned fields (all string values in `nodes[].source`, `settings`, template refs) and the heuristic (hostname/IP/jdbc-URL regexes); add the code to §12 tables.
12. [M] Parameter coercion rules (§6.3): specify JSON-number-where-BIGDECIMAL-declared → reject (`pipeline.execution.parameter_type_mismatch`); TIMESTAMP params require `Z`/offset.
13. [M] Versioning disambiguation: one normative subsection naming the three counters (doc revision, `schema_version` per entity family, entity `version`) — `schema_version` on Pipeline JSON vs Template JSON vs type-system envelope are independent counters; rename discussion resolved by namespacing the *description*, not the field.

### 2.2 rest-api.md
1. [D9] §7 rewritten wholesale (always-Redis cursor model, `DP-Result-TTL-Seconds`, caps, error codes, NOT-goal note).
2. [D7] §6.8 rewritten: cancel-on-disconnect after grace; delete `Last-Event-Id` resumption language (§6.2, §6.7); new `DELETE /api/v1/executions/{id}`.
3. [M] §3.2 references `/auth/login` and `/auth/refresh` — deleted (auth.md v2.1 removed them; login is `GET /oauth2/authorization/{provider}`).
4. [M] New §: `/api/v1/auth/api-keys` CRUD (POST create — returns secret once; GET list; DELETE {id} revoke) + user admin endpoints (GET/PATCH `/api/v1/users`, activate/deactivate, admin-only) — request/response envelopes defined here, scopes per D15 matrix.
5. [D10] Header sweep: `X-API-Key`→`DP-API-Key`, `X-Correlation-Id`→`DP-Correlation-Id`; CORS allow-list updated.
6. [D5] §12.2 uses single `rate_limit.exceeded`; §12.1 limits become per-user; config keys referenced from configuration.md (no local definitions per D8).
7. [M] §11.1 health payload is canonical: `{"status", "version", "components": {"database", "redis", "h2_factory"}}` at root-level `/health`, `/ready`, `/info` (NOT under `/api/v1`) — observability.md aligns to this.
8. [M] §10.2 malformed JSON example (duplicated closing brace).
9. [M] §4.2 broken link → pipeline-contract §13 (correct anchor).
10. [M] SSE §6.4: `node_completed` is success-only (matches enums §11); event payload asymmetries noted and normalized (`data_ready` gains `pipeline_id`).

### 2.3 auth.md
1. [D15] New section: scope↔operation matrix (REST families × MCP tools × UI actions).
2. [D13] §6.3 `JwtAuthenticationFilter` does a cached `is_active` lookup (60s TTL, same cache as §11.4); principle 8 now accurate.
3. [D14] New subsection: scope derivation at login (`is_admin`→admin, else author).
4. [D11] §8 chain: `/mcp` matcher — CSRF-exempt, API-key or Bearer `dpk_`, no cookies.
5. [D10] Headers: `DP-API-Key`; CSRF token delivery specified (`DP-CSRF-Token` header + cookie name).
6. [D5] §9: `auth.rate_limit.exceeded` deleted in favor of `rate_limit.exceeded`; login rate-limit key defined in configuration.md (D8), referenced here.
7. [M] §10.2 audit example `"provider": "GOOGLE"` → `"google"` (free-text lowercase per §4.1).
8. [M] §6.3 `principal.authorities` — doesn't exist on `AuthenticatedPrincipal`; fix sample to `principal.scopes`.
9. [M] §11.1 `name` optional-vs-required contradiction with §5.2 code → required.
10. [M] §4.2 step 3 "described in §10" → §4.3/§11.
11. [M] API-key management flows (§7.4) now point at the rest-api endpoints (2.2.4).

### 2.4 enums.md
1. [M] New section: **case & serialization convention** — wire JSON uses the exact strings cataloged here; Kotlin enums are UPPER_SNAKE with explicit `@JsonValue`/`@JsonCreator` mapping; the catalog string is normative.
2. [D5] §16 lists concrete codes (not bare domains) for `pipeline.*`, `template.*`, `datasource.*`, `result.*`, `rate_limit.*`, `idempotency.*`, `auth.*` (normalized segmentation); adjudications from D5 applied.
3. [D1] §3 `OutputTarget`: omitted-default changes to `caller`.
4. [M] §1 `DECIMAL` description restored to include approximate-numeric (scale-omitted) case per type-system §3.4.
5. [M] §5 `Dialect` single authority = type-system §5 (datasources §4 becomes consumer).
6. [D7] §10 `ExecutionStatus.ABORTED` production paths now defined (disconnect-grace, DELETE, shutdown).
7. [M] Seven broken `Source:` links fixed (auth §5→§7.5, auth §9.1→§10.1, auth §7→§9, rest-api §2.6→§2, etc.); reserved-future values marked with a `(reserved)` tag.
8. [M] Validation Discipline section: add `scripts/docs-audit.sh` as the mechanical enforcement of step 2.

### 2.5 configuration.md
1. [D8] §3 tables vs §4 YAML five-key mismatch → suffixed forms everywhere; §4 template regenerated to match §3 exactly.
2. [D8] Missing keys added: D9 result keys, D7 grace key, `staging.h2.result-batch-size`, `audit.retention-days`, `templates.cache-size`, `templates.render-timeout-ms`, `ui.theme` (+ valid theme list for §6 validation), SSE heartbeat interval, login rate-limit, idempotency TTL.
3. [D8] Encryption key: required fail-fast; note pointing to ROADMAP for KMS.
4. [M] `data_dir` either defined as a key or the master-key-file reference deleted (deleted, per D8 — no file fallback).
5. [M] Changelog "10 required keys" → 6 + ≥1 OIDC provider.
6. [M] Precedence subsection: env > profile YAML > base YAML; pipeline-level `settings.tempdb.config` overrides global staging keys (D6).
7. [M] `allowlist.domains` binding type stated (`List<String>` via comma-split; empty string = empty list).
8. [M] `large-result-threshold-bytes` deleted (superseded by D9 keys).

### 2.6 dag-executor.md
1. [M] §5.2 semaphore deadlock: acquire permit AFTER `awaitAll(deps)`.
2. [M] §5.2/§6: `node_completed` emitted on success only; single `NodeFailed` emission (remove the duplicate in `execute`'s catch).
3. [M] `NodeResult` defined (fields incl. `callerResultRef`, stats); relationship to `NodeStats` stated.
4. [M] Execution timeout: `withTimeout(execution-timeout-seconds)` wrapping the scope; execution-slot semaphore acquisition added to §5.2 (matches §5.1 step 2).
5. [M] `PipelineExecutionFailed` constructor aligned with §8.1.
6. [D7] Cancellation plumbing: `Statement` registry per node, `Statement.cancel()` on cancel/disconnect-grace/DELETE; grace key referenced.
7. [D9] §6.4.2 caller path: ResultSet fully materialized to Redis INSIDE `connection.use`, `data_ready` built from the stored result (first page + cursor).
8. [M] `Dispatchers.IO` → `ExecutorDispatcher` (per §15.2).
9. [M] `Dag` fixes: remove dead no-op loop; `dependencies[id]!!` → safe default `emptySet()`; `NodeExecutionException` uses `cause` via constructor super, not shadowing.
10. [M] `independentBatches()` marked as diagnostic/UI API (not used by executor) or removed from §14 test requirements — mark diagnostic.
11. [D6] §9/`StagingFactory` signature is canonical here; staging.md aligns. §12.1 claim "no concurrent tempdb access" corrected: concurrent access exists, serialized by the staging `Mutex`.
12. [M] §8.2 error-mapping table completed (`writeback_failed`, `writeback_target_missing`, `staging.engine_unavailable`, `staging.memory_limit_exceeded`) and `h2_creation_failed` → `creation_failed` [D5].
13. [M] §8.2 broken link → pipeline-contract §13.
14. [M] §15.3 "(future)" annotation on observability link removed.
15. [D1] Terminal-node language → caller-node; zero-caller executions skip `data_ready`.

### 2.7 staging.md
1. [D6] §3.1 URL loses `DB_CLOSE_DELAY=-1`; §3.4/§3.5 lifecycle rewritten (explicit drop + close in `finally`, no GC reliance).
2. [D6] Identifier-safety section added (column-name validation + quoting, `pipeline.staging.invalid_column_name`).
3. [D6] Single-connection `Mutex` rule; §9 updated.
4. [D6] `StageResult.columns: List<ColumnSchema>` (§3.2 example fixed).
5. [D6] `StagingFactory.create(executionId, engine)` aligned with dag-executor; pipeline `settings.tempdb.config.max_memory_mb` precedence stated.
6. [M] §4.1 broken link → pipeline-contract §10 (output table naming).
7. [M] Undefined helpers (`h2SqlType`, `readValue`, `fromH2`) given signatures; `H2TypeMapper` split into `H2IngressMapper` (H2→canonical) and `H2EgressMapper` (canonical→H2) — module-structure updates the same names.
8. [M] §8 memory accounting: define estimation (H2 `MEMORY_USED()` polled per stage op) or mark measured-at-implementation; pick `MEMORY_USED()` polling.
9. [D5] Staging error codes stated here referencing the central catalog (`creation_failed`, `cleanup_failed`, `memory_limit_exceeded`, `invalid_column_name`).
10. [D8] §7 config keys become references to configuration.md (`result-batch-size` adopted there).
11. [M] Behavior on duplicate staged table at runtime: defensive `pipeline.staging.table_already_exists` error (write-time validation is the primary guard).

### 2.8 templates.md
1. [D3] `params_schema` removed everywhere (entity, examples, §8.1 render flow, validation).
2. [D12] §6 imports: `{id, version, alias}`, synthesized `<#import>`, body never imports; §3.1/§6.3/Appendix A examples rewritten; §6.2 example macro names made consistent with §3.1 usage.
3. [D12] `is_library` semantics corrected ("no output outside macro definitions"; `body` required).
4. [D5] `template.import.cycle_detected` → `template.validation.import_cycle`.
5. [M] Line 137 "Verification needed: Freemarker config keys" — resolve against real Freemarker 2.3.x API: `Configuration.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER)`, `setAPIBuiltinEnabled(false)`, `DefaultObjectWrapperBuilder` w/o exposure of statics, no `ObjectConstructor`/`Execute` in shared vars, `StringTemplateLoader`-only (no `FileTemplateLoader`); delete the invented `externalsCatchAll`/`builtin_classes` settings. (Editor: verify key names against Freemarker docs via context7 before writing.)
6. [M] Render guards: `templates.render-timeout-ms` (configuration.md, D8) enforced via `Environment` interruption; output size cap = staging batch memory rules; "sample context" language deleted (D3 moved dry-render to pipeline save).
7. [M] §12.2 DDL block → pointer to metadata-db.md [D4]; metadata-db owns `template_versions` incl. `engine`, `is_library` columns.
8. [M] Header "v1 (frozen contract)" → v1.2 + change-log entry.
9. [M] §10 tempdb dialect note: derive from `settings.tempdb.engine` (matches pipeline-contract fix 2.1.8).

### 2.9 datasources.md
1. [D8] §7.1: typo fixed; fallback chain deleted; encryption key required (pointer to configuration.md).
2. [D7] §5/`properties`: `properties.hikari.*` + `properties.jdbc.*` passthrough; §9 `properties_invalid` redefined (test-pool-build validation, D2).
3. [D4] §7.2 DDL block → pointer to metadata-db.md §4.10 (which wins: `properties_json`, TIMESTAMPTZ, FK on created_by, dialect CHECK; `name` keeps a `CHECK (char_length(name) <= 63)` + identifier regex in metadata-db).
4. [M] `description` optionality: OPTIONAL wins (metadata-db drops `NOT NULL DEFAULT ''`; §3.3 updated).
5. [D5] §10.3 `datasource.validation.driver_not_loaded` → `datasource.driver_not_loaded` (enums canon); added to §9 table.
6. [D5] §8.2 `pipeline.execution.datasource_unreachable` registered in the central catalog.
7. [M] Return types named in §6.1 (`DeleteResult`, `TestResult`, `ValidationResult`) given field lists.
8. [M] §7.4 per-lease decrypt+audit corrected: decrypt once at pool build (Hikari holds the credential); audit event on pool build/rebuild, not per lease.
9. [M] Query-timeout precedence: per-datasource `query_timeout_seconds` overrides `executor.node-query-timeout-seconds` when set.
10. [M] §11 paths get the `/api/v1` prefix; rename semantics stated (name is immutable — delete+create, blocked while referenced).
11. [M] Pool concurrency: `poolFor()` lazy init documented as thread-safe (computeIfAbsent).
12. [M] MySQL licensing row: keep "verify redistribution" flag but link deployment.md's driver matrix (2.15.6).

### 2.10 metadata-db.md
1. [D4] `*_json` suffix applied to every JSONB column across all tables (`definition_json`, `parameters_json`, `properties_json`, `stats_json`, `details_json`, ...); all consumer docs use the new names.
2. [D4] Owns ALL DDL: absorbs datasource-table divergences (2.9.3/2.9.4) and template_versions columns (`engine`, `is_library`, `current_version NOT NULL`).
3. [D3] `templates`/`template_versions`: `params_schema` column removed.
4. [M] FK added: `pipeline_executions(pipeline_id, pipeline_version)` → `pipeline_versions(pipeline_id, version)`.
5. [M] §5 index summary regenerated from §4 (no phantom `uq_*` names); redundant `idx_events_execution` dropped (the UNIQUE constraint's index suffices).
6. [M] `updated_at` maintenance stated: app sets it in every UPDATE statement (no triggers); column added to `templates` and `users` for consistency.
7. [M] §6.1 `create()` example rewritten as valid single-CTE SQL with a real RowMapper.
8. [M] §8.3 stale-execution sweep parameterized by `stale-timeout-minutes` (config reference, D8).
9. [M] Note: idempotency keys and results are Redis-only (no table) — stated explicitly.
10. [M] Soft-delete partial index added for `datasources` (parity with other tables).
11. [D13] Note on `users.is_active`: consulted per-request via cache (auth.md D13).

### 2.11 mcp-server.md
1. [D11] §3.2/§4.1: auth = `DP-API-Key` or `Authorization: Bearer dpk_...`; security-chain note added.
2. [D15] §6.2: scope column added to all 15 tool tables (from auth matrix); §4.1 `read-only` → `read`; `admin` scope acknowledged.
3. [D9] §6.2.15 `executions_get_result`: cursor semantics (offset/limit args), 1MB base64 cap, REST cursor URL beyond cap.
4. [M] §6.2.1 `pipelines_list` drops `datasources_used` (v1.1 residue).
5. [M] §5.1 `tools.listChanged: false` in v1 (dynamic tools are v2).
6. [M] §8.2 `create_pipeline_for_question` prompt removed from v1 surface (moved to ROADMAP note beside schema-introspection tools).
7. [M] §3.1 protocol-version: keep the verification-needed marker but reframe as an implementation-gate checklist item with the exact check to run.
8. [M] `resources/list` pagination: cursor param specified (opaque, page size 100); executions listed only from the last 24h.
9. [D3] Tool schemas for `templates_create`/`pipelines_create` updated (no `params_schema`; imports `{id, version, alias}` [D12]).
10. [D7] Long-running executions: tool returns after completion or `execution-timeout`; note on MCP progress notifications deferred (ROADMAP), document the timeout behavior.

### 2.12 ui-screens.md
1. [M] Route convention stated: UI pages at root (`/pipelines`), htmx partials under `/partials/**` returning HTML fragments, JSON API under `/api/v1/**` — htmx never calls `/api/v1` (fix §4.10 to `/partials/api-keys`).
2. [M] §5 htmx example fixed (`hx-include`/`hx-vals` for the query param, not static interpolation).
3. [D15] Scope column references the auth matrix; §4.10 key-scope escalation rule from matrix (a key's scopes ⊆ creator's scopes).
4. [M] §4.11 theme preference stored on the user row (PATCH `/partials/profile/theme` → UPDATE users), not session state.
5. [M] §4.11 provider badge shows the configured provider `display_name` (no hardcoded names).
6. [D9] §4.9 result panel: works via the uniform cursor within TTL; beyond TTL shows "result expired — re-run" (explicitly specified).
7. [M] Standard states subsection: empty/loading/error rendering rule for htmx swaps (error envelope → `#toast` fragment).

### 2.13 pipeline-editor.md
1. [M] §3.2 stack list: remove "Native EventSource" (contradicts §7.3 fetch-based SSE).
2. [D7] §15.1 rewritten: no reconnection; on SSE drop show "connection lost — execution will be cancelled after grace"; poll `GET /executions/{id}` once for final state.
3. [M] Styling wiring: `buildElements()` adds `nodeType*` classes; `.terminal` → `.caller` class added on the caller node [D1]; `.selected` applied on tap; `idle` class added at init.
4. [M] §4.2 script tags include `result.js`; `show-error` CustomEvent listener wired in `ErrorModal` (Alpine `x-on:show-error.window`).
5. [M] §14 a11y rewritten: canvas has no per-node DOM — parallel visually-hidden DOM list (`<ul role="listbox">`, one `<li>` per node, synced statuses) provides keyboard/AT access; graph canvas gets `role="img"` + `aria-label` summary.
6. [M] Duplicate §13.2 renumbered.
7. [M] `collectParameters()` coerces values per declared parameter types before POST (boolean/number/timestamp), matching pipeline-contract §6.3 coercion rules (2.1.12).
8. [M] Progressive-enhancement claim corrected: no-JS = server-rendered node list + metadata (no graph); stated honestly.
9. [M] Edit mode (§11.2): out of v1 scope — section moved to ROADMAP reference (LLM/MCP authoring is the v1 path).
10. [M] `vendor-manifest.json` path unified to `static/vendor/design-system/vendor-manifest.json` (matches DEVELOPMENT.md sync script).
11. [D8] `DATAPIPELINES_UI_THEME` referenced from configuration.md.

### 2.14 observability.md
1. [M] §6.1 health payload/paths aligned to rest-api §11.1 (root-level, snake_case components, no diskSpace, `version` field).
2. [M] Stale metrics removed (`login.attempts{outcome=locked}` — no lockout exists); `http.server.requests` noted as Boot default name (unprefixed).
3. [D9] Result-store metrics added (bytes written, cursor hits, expiries); SSE stream count/duration; idempotency hits.
4. [M] §8.1 `errors.total{class, method}` tag set reduced to `{domain}` (own cardinality rule).
5. [M] §9 redaction: mechanism = Logback `MessageConverter` + JSON field filter on the known-sensitive key list; `node_failed` SSE `details` must exclude `jdbc_url` (rest-api §6.4.4 fixed accordingly — flagged there).
6. [D10] `X-Correlation-Id` → `DP-Correlation-Id`.
7. [M] Trace propagation into SSE events + MCP calls: correlation id echoed in every SSE event payload; MCP tool results carry `correlation_id` in meta.

### 2.15 deployment.md
1. [D8] §5 env-var tables replaced by a pointer to configuration.md + the required-keys list only (no name duplicates).
2. [M] Appendix A compose fixed: OIDC provider env vars + `DATAPIPELINES_REDIS_PASSWORD` wired; boots against auth.md §5.2 fail-fast.
3. [D9] Redis guidance: `noeviction`, sizing note (results + idempotency + events share the store).
4. [D7] §8.3 graceful shutdown mechanism: stop accepting new executions → drain up to `execution-timeout-seconds` → cancel stragglers; k8s `preStop` + `terminationGracePeriodSeconds` = timeout + 30.
5. [M] §6.2 diagram residue (`Broadcast)`) removed; §11 malformed bullet fixed.
6. [M] Driver matrix subsection: which JDBC drivers ship in the image (PG, MSSQL, H2, DuckDB, SQLite) vs opt-in profile (`-Poracle`, `-Pmysql`) vs `lib/` drop-in.
7. [M] Resource sizing note: heap ≥ staging max-memory × max-concurrent-executions + baseline; container limit guidance.

### 2.16 module-structure.md
1. [M] Persistence ownership: repositories live in their owning domain modules (`pipeline-contract` → PipelineRepository, `templates`, `datasources`, `auth`, `dag` → execution repos) with `spring-jdbc`; Flyway dep + migrations in `app`; Redis client (`spring-boot-starter-data-redis` + Lettuce) in `dag` (results/idempotency) and `web` (SSE event log) — version catalog updated with flyway/lettuce entries.
2. [M] §4.1 ASCII graph regenerated to match §5.x dependency lists (incl. `dag`→templates/datasources/staging, `mcp-server`→auth).
3. [M] §4.2 layering rules restated as one machine-checkable rule (allowed-dependency list per module).
4. [M] §5.11 `db2` Testcontainer removed.
5. [M] Duplicate §8.2 renumbered.
6. [M] §5.1 `H2TypeMapper` split into `H2IngressMapper`/`H2EgressMapper` (matches 2.7.7).
7. [M] `-Poracle`/`-Pmysql` implementation sketch: conditional `runtimeOnly` deps in `datasources/build.gradle.kts` gated on Gradle properties; `lib/` drop-in via `bootRun`/`bootJar` classpath note.
8. [M] Both "Verification needed" markers (MCP SDK coordinates, version catalog) kept but converted to implementation-gate checklist items with exact verification commands.
9. [D3] `templates` module public API drops params-schema types.

### 2.17 type-system.md
1. [M] §7.1 column descriptor: add optional `nullable` field; `additionalProperties: false` → `true` with a "clients MUST ignore unknown fields" rule (fixes the §9.2 additive-evolution contradiction).
2. [M] PG unsized `numeric` adjudicated: schema envelope reports `BIGDECIMAL(131072, 0)`? No — **`BIGDECIMAL` with precision omitted** (unsized); §4/§5.1 aligned on "precision omitted = unbounded".
3. [M] §6 H2 DECIMAL limit contradiction resolved: H2 2.x supports precision ≤ 100000; overflow threshold = 100000; note fixed.
4. [M] §5.7 typo `FLOATA`; BLOB-affinity rows reconciled (declared BLOB → BINARY; undeclared/no-type → STRING).
5. [M] §2 principle 6 link → §9.
6. [M] Egress normative rules: TIMESTAMP/TIME serialize with exactly 6 fractional digits (microseconds); BINARY = standard base64 with padding.
7. [M] JVM-UTC deployment precondition promoted to a normative rule (§8.4) + deployment.md note (`-Duser.timezone=UTC` in the image).
8. [M] §11.2 `forDialect` gains the §8.2 unknown-type fallback branch.
9. [D1]/[D3] No impact. (Type system was the cleanest doc.)

### 2.18 DEVELOPMENT.md
1. [D10] curl examples: `DP-API-Key`.
2. [D3]/[D12] §8.2 template example: no `params_schema`; imports shape if shown.
3. [D1] §8.3 pipeline example: omitted `output` (defaults to caller) — simplify the example accordingly.
4. [M] §11 project tree gains `scripts/docs-audit.sh` and `docs/README.md`.
5. [M] Verification section mentions running `scripts/docs-audit.sh` for doc changes.

### 2.19 Cross-references (all docs) [M]
Fix every broken anchor catalogued in review (≥10): staging→pipeline-contract §10; metadata-db→pipeline-contract §17.3; module-structure→pipeline-contract §12; dag-executor→pipeline-contract §13; templates→pipeline-contract §6; type-system §2→§9; enums→auth §7.5/§10.1/§9; enums→rest-api §2; rest-api→pipeline-contract §13; pipeline-editor→auth §6; observability→auth §10; auth §4.2→§4.3. `scripts/docs-audit.sh` check (a) enforces this class permanently.

---

## Part 3 — Out of scope for this campaign

- Writing any code (the repo is still spec-only).
- ROADMAP feature re-prioritization (only decision-log entries + the KMS annotation).
- Resolving the two implementation-gate verifications (MCP SDK coordinates, version catalog against Maven Central) — they are build-time gates, kept as explicit markers.
- pipeline-editor edit-mode design (moved to ROADMAP).

## Appendix: Change Log
| Date | Change |
|---|---|
| 2026-08-07 | Initial: findings from 2026-08-06 review + ratified resolutions D1–D15 |
