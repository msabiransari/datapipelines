# Configuration Reference

**Status:** v1.77 (operator reference; source-traced configuration authority)
**Owner:** datapipelines.co core
**Last updated:** 2026-11-05

---

## 1. Purpose

This is the official reference for configuring the shipped application. Each row explains what a setting controls; §5 records the exact packaged values and environment placeholders, §6 records posture overrides, and §8 identifies the source bindings and consumers used to verify the descriptions. The same document is packaged for the browser documentation and agent documentation lookup.

**Authority rule:** define application configuration keys here. Other specifications link here for defaults and use their own documents for workflows, API schemas and pipeline definitions. Existing group-specific drift tests and the whole-reference checks in §9 enforce the mechanical parts of this contract.

**How to use this reference:**

- Start with §2 for credentials and connectivity, then select the environment/posture in §3.23. Follow [Deployment](deployment.md) or [Environments](environments.md) for the loader you use.
- A table default is the application default unless a profile or deployment override is explicitly stated. `deploy/env/defaults.env` includes laptop-specific overrides; it is not the default of a bare jar. Empty, absent, zero and `false` are distinct where the row says so.
- Use the **actual environment placeholder in §5**, or an explicit Env var cell. Most names use upper snake case, but bootstrap, promotion, providers and dashboard runtime have deliberate exceptions. Do not invent an environment name from a dotted path. Map/list members use YAML; `[]` and `<dialect>` describe variable members, not literal key names.
- Application properties are read during startup/binding. Restart the application (recreate the container when changing its environment) to apply configuration changes; persisted object settings, user preferences and stored API-key rotation have their own lifecycle described in the relevant row.
- Keep passwords, signing/encryption keys, client secrets, bootstrap credentials and promotion keys in the deployment's secret source. Examples contain placeholders, never working credentials. Do not put secret values on command lines or in support transcripts.

**Scope:** §§2–7 cover application properties, including framework wiring and reserved settings that currently have no runtime effect. §10 covers launcher/JVM inputs and points to the deployment chart and build options. Pipeline parameters, datasource definitions, dashboard definitions and HTTP/MCP request arguments are *data contracts*, not application startup configuration: see [Pipeline contract](pipeline-contract.md), [Datasources](datasources.md), [Parameter sets](rest-api.md), [REST API](rest-api.md) and [MCP server](mcp-server.md). §3.24 retains the existing datasource-specific LAKE limits because they participate in application-limit precedence.

---

## 2. Required Configuration

**The app will not start without these.** Fail-fast on missing values.

| YAML path | Env var | Description |
|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | Metadata DB JDBC URL, used by the application's Hikari datasource and startup Flyway migrations. Example: `jdbc:postgresql://host:5432/datapipelines`. This is the product's metadata database, separate from datasources registered for pipeline queries. |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | Metadata DB login, used for application reads/writes and startup migrations. Must be non-blank. |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | Metadata DB password. Must be non-blank; this is a secret, independent of datasource-credential encryption below. |
| `datapipelines.redis.host` | `DATAPIPELINES_REDIS_HOST` | Host used by `RedisClientConfiguration` to create the shared Lettuce connection factory (results, idempotency, event replay, rate limiting and cross-instance invalidation). Must be non-blank; Redis port, password and timeouts are in §3.1. |
| `datapipelines.jwt.secret` | `DATAPIPELINES_JWT_SECRET` | Internal session-JWT signing secret: valid base64 decoding to at least 32 random bytes. Also derives the MAC key for the OAuth authorization-request cookie. HS256 is fixed in code. Changing this secret invalidates previously signed sessions and in-flight authorization cookies; keep it consistent across replicas. |
| `datapipelines.db.encryption-key` | `DATAPIPELINES_DB_ENCRYPTION_KEY` | AES-256 data key for datasource password encryption. Exactly 32 bytes, base64-encoded. **Required — there is no fallback source.** It is **key version 1**, forever: every credential written under it carries `0x01` as its first byte ([Datasources §7.1](datasources.md#71-encryption-at-rest)). Rotation and KMS-backed sources are §3.20's `key-provider` seam. |

**Provider configuration** is also required — at least one OIDC or OAuth 2.0 provider must be configured in `datapipelines.auth.oidc.providers` (in `application.yml`), unless local accounts are enabled. An OIDC provider requires `client-id`, `client-secret`, and `issuer-uri`; an OAuth 2.0 provider (`protocol: oauth2`, GitHub, #655) requires `client-id`, `client-secret`, its three endpoints, `user-name-attribute` and `scopes` instead (§3.4). The client-id and client-secret are typically referenced from env vars. See [Auth spec §11.1](auth.md#111-oidc-provider-configuration) for the full format.

The specific env var names depend on which provider(s) the deployment chooses. Examples:

| Env var pattern | For |
|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google Workspace |
| `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET` | Microsoft Entra ID |
| `OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET` | Okta |
| `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` | Keycloak |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | GitHub (OAuth 2.0, shipped entry; empty lines in `deploy/secrets.env.example`) |

The deployment defines these env var names in `application.yml` — they're not hardcoded by the app. (Provider variable names are deployment-chosen; consult the exact shipped examples in §5.)

---

## 3. Optional Configuration (with defaults)

### 3.1 Redis

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.redis.port` | `6379` | TCP port of the standalone Redis server used by the shared Lettuce connection factory. Integer 1–65535; startup refuses values outside that range |
| `datapipelines.redis.password` | (none) | Redis password. Empty while `datapipelines.redis.host` is not loopback is a WARN under `development` and a **refusal under `hardened`** (§3.23, §7; #189) |
| `datapipelines.redis.command-timeout` | `2s` | (#482) The Redis client's per-command READ timeout in `RedisClientProperties`, the binding Lettuce reads. Lettuce's own default is 60 s, which made a Redis outage stall every rate-limited request on the order of a minute per call before the documented `429 rate_limit.unavailable` ([REST API §12.3](rest-api.md#123-when-the-limiter-itself-is-unavailable)). Sized at ≥ 10× headroom over the slowest legitimate command the suites exercise (the replay log's 7,000-entry Lua append — the #482 evidence records the measurement). **Validated at boot (§7, #488): a duration above 0 and at most `60s`** — Lettuce reads a command timeout of `0` as NO bound (and as an immediate connection-handshake deadline), so `0s` is refused rather than silently restoring the stall; a blank value is refused too |
| `datapipelines.redis.connect-timeout` | `2s` | (#482) The TCP connect timeout in `RedisClientProperties`, the binding Lettuce reads — bounds the client's (re)connect attempt against a Redis host that drops packets instead of refusing. **Validated at boot (§7, #488): a duration above 0 and at most `60s`**; a blank value is refused too. Lettuce's own connect default is 10 s |

> Where an Env var column is omitted, §5 gives the exact shipped placeholder (for example, `DATAPIPELINES_REDIS_PORT`).

### 3.2 Executor

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.executor.max-parallel-nodes` | `4` | Maximum eligible DAG nodes running concurrently within one execution, enforced by the executor's semaphore; positive integer. Together with the instance execution limit, also sizes the SQL worker-thread ceiling |
| `datapipelines.executor.max-concurrent-executions-per-user` | `10` | Concurrent execution slots for one user **on one instance**, enforced by in-memory admission counters; positive integer. Ordinary execution admission refuses immediately when full. Scheduled runs substitute `datapipelines.scheduler.max-concurrent-runs` for their system identity; dashboard refreshes reserve instance slots without consuming this per-user budget |
| `datapipelines.executor.max-concurrent-executions-per-instance` | `100` | Instance-wide concurrent execution limit — **per instance** (050/R2): N replicas admit N × this in total ([Deployment §6.2](deployment.md#62-multi-instance-horizontal-scaling-production)) |
| `datapipelines.executor.max-concurrent-executions-global` | `unset` | **Deprecated alias** for `max-concurrent-executions-per-instance` (one release, 050/R2). Set alone → its value runs and startup logs one WARN naming the new key; set together with the new key and differing → startup refuses. The limit was always per JVM — the old name was false at N replicas |
| `datapipelines.executor.node-query-timeout-seconds` | `60` | Per-node JDBC query timeout. A datasource's own `query_timeout_seconds`, when set, overrides this for nodes on that datasource ([Datasources §5](datasources.md#5-connection-pool-configuration)). Also the `sql_probe` timeout ceiling for every dialect without a by-dialect value below (#167, [Datasources §7D](datasources.md#7d-the-sql-probe)) — raising it lets a probe hold a connection as long as a node may |
| `datapipelines.executor.execution-timeout-seconds` | `600` | Positive wall-clock timeout around the pipeline executor's run. Expiry cancels the execution; the same value is the lifetime of its cross-instance cancellation flag in Redis |
| `datapipelines.executor.node-timeout-seconds` | `300` | **The per-node WALL-CLOCK deadline** the executor enforces: RENDER → CONNECT → EXECUTE → STAGE → MATERIALIZE, staging included. Overridable per node by `node.settings.timeout_seconds` ([pipeline-contract §4.11](pipeline-contract.md)). Unlike `node-query-timeout-seconds`, which is the DRIVER's bound on one `execute*` call, this one is the executor's own and fires whatever the driver does |
| `datapipelines.executor.node-timeout-max-seconds` | `900` | The ceiling a node's own `settings.timeout_seconds` may not exceed. A pipeline declaring more is refused at SAVE with `pipeline.validation.node_timeout_invalid` — refused rather than clamped, so an author who asks for 4 hours is not left debugging a silent 15 minutes |
| `datapipelines.executor.node-query-timeout-max-seconds` | `900` | (156, #2) The ceiling a pipeline's or a node's own `settings.query_timeout_seconds` may not exceed ([pipeline-contract §4.11/§5.3](pipeline-contract.md)) — refused at SAVE with `pipeline.validation.pipeline_query_timeout_invalid` / `pipeline.validation.node_query_timeout_invalid`, same reasoning as `node-timeout-max-seconds`. Independent of that key: the two settings bound different budgets |
| `datapipelines.executor.node-query-timeout-seconds-by-dialect.<dialect>` | `LAKE: 180`; every other dialect unset | (156, #2) The operator's per-dialect default statement timeout, consulted when neither the node, the pipeline nor the datasource declares one — the innermost fallback before the flat `node-query-timeout-seconds` above, and the `sql_probe` timeout ceiling for that dialect (#167). LAKE ships higher because a LAKE engine re-reads cold from object storage, and less memory the engine can keep means a longer cold read (§E below). Each value is validated positive and ≤ `node-query-timeout-max-seconds` at boot, fail-fast. **One explicit YAML line per overridden dialect, never a bare env var for a dialect not already named here** — Spring's relaxed binding cannot reliably reverse-map an arbitrary `..._BY_DIALECT_<X>` environment variable onto this map's dashed key (measured, `ExecutorPropertiesDialectMapBindingTest`); the shipped form is `lake: ${DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_SECONDS_BY_DIALECT_LAKE:180}` |
| `datapipelines.executor.cancel-grace-seconds` | `5` | After a node's deadline fires, how long the executor waits for the cancelled statement to actually return before abandoning it. Past it the node fails **on schedule** and the leaked statement is logged once with the execution id (`event=node.statement_abandoned`). Never a wait on the query itself — a driver that ignores `cancel()` cannot extend a node's budget, only leak a connection until its own pool reclaims it |
| `datapipelines.executor.source-fetch-size` | `1000` | The JDBC `fetchSize` set on every DQL **source** cursor — what makes a source node stream instead of materialising its whole result inside the driver. pgjdbc uses a server-side cursor only with `autoCommit=false` **and** `fetchSize > 0`, so the executor also takes a Postgres source connection out of autocommit for the read and commits unconditionally on the way out (so a multi-statement template's side effects persist exactly as they did under autocommit); the pool restores autocommit when the lease returns. MySQL is the exception the driver forces: Connector/J streams only at `Integer.MIN_VALUE`, which the executor passes for that dialect and this key does not affect. **`0` turns streaming off** and leaves every source statement exactly as pre-108 code left it — the escape hatch for the one shape that can behave differently: a DQL template whose SQL is several statements, since pgjdbc's server-side-cursor path uses the extended query protocol, which carries only one |
| `datapipelines.executor.progress-write-interval-seconds` | `5` | The floor between two THROTTLED live-progress writes for one execution. A staging drain reports per batch — thousands of times for a large node — and one UPDATE per batch would put a metadata-DB write on the insert path. Node boundaries are **not** throttled: a node starting or finishing is the event a watcher is waiting for, and delaying it would leave the screen naming the wrong node |
| `datapipelines.executor.progress-sample-interval-seconds` | `1` | The floor between two **periodic** `node_progress` samples of one node operation ([REST API §6.4.9](rest-api.md#649-node_progress), 149). A sample is also taken, unthrottled, at each FIRST entry into a measured state and at the operation's end, so this key bounds only how often the cumulative counts refresh — never whether a state is seen. Every sample is a live SSE event, an `execution_events` row and a Redis-log entry, so raising it lowers event volume on long nodes; at the default a node emits at most one periodic sample per second plus its (at most six) first-entry samples and one terminal sample |
| `datapipelines.executor.heartbeat-seconds` | `15` | How often a running execution stamps `pipeline_executions.heartbeat_at` (V21). The crash sweep reaps a `RUNNING` row whose stamp is older than **three** of these — one missed beat is a slow tick, two a loaded box, three an instance that is gone — so this also sets how fast a dead instance's rows are reaped: ~45 s plus one 15 s sweep tick, against the sixty MINUTES `datapipelines.executions.stale-timeout-minutes` alone gave. That key survives as the backstop for rows a pre-V21 instance left with no stamp at all |
| `datapipelines.executor.lifecycle-write-timeout-seconds` | `10` | (#311) The bound on the two `pipeline_executions` lifecycle writes: the RUNNING insert (on `execution_started`) and the terminal UPDATE. Two layers read the one value: a JDBC `queryTimeout` on exactly those two statements (`ExecutionRepository` — a database that answers slowly is cancelled at the statement and its thread freed), and the emitter's caller-side wait (`WebEventEmitter` — the layer that bounds a database that never answers, which a statement timeout cannot reach; measured, pgjdbc's cancel is undeliverable to a paused Postgres). A write past the bound is STATED, never fabricated: the insert's failure is the existing `recorded = false` path (fail-closed for scheduled runs, WARN-and-continue for interactive ones), the terminal UPDATE leaves the row RUNNING for the stale sweep and is counted (`datapipelines.executions.lifecycle_write_failed`, [Observability §4.1](observability.md#41-metric-naming)). Mirrored in `application.yml`, `defaults.env`, `secrets.env.example`, `compose.yml`; `WebPropertiesSpecDriftTest` pins the default |

**Three budgets, one precedence** (the same table appears in [pipeline-contract §4.11](pipeline-contract.md#411-settingstimeout_seconds--settingsquery_timeout_seconds--a-nodes-own-deadline-and-statement-budget) and [dag-executor §5.3](dag-executor.md)):

| Bound | Setting | Scope | Enforced by |
|---|---|---|---|
| Execution | `datapipelines.executor.execution-timeout-seconds` (600) | the whole execution | the executor |
| Node | `node.settings.timeout_seconds`, else `datapipelines.executor.node-timeout-seconds` (300) | one node, wall clock, all five phases | the executor |
| Statement | `node.settings.query_timeout_seconds` (156, §5.3), else pipeline `settings.query_timeout_seconds`, else the datasource's `query_timeout_seconds`, else `node-query-timeout-seconds-by-dialect.<dialect>` above, else `datapipelines.executor.node-query-timeout-seconds` (60) | one `execute*` call | the JDBC driver |

**Instances are moderate, so the statement bound must be sized for a COLD read, not a warm one (156, #2).** This deployment is expected to run as several instances of moderate size, not one box with all the memory a query could want — so a LAKE engine sharing that box's memory with everything else keeps less of the object store cached, and a query that lands on an instance whose cache is cold re-reads from S3 at network speed instead of from RAM. The per-dialect operator default above exists for exactly that reason, sized to the cold read of the largest scan a deployment runs, not the warm one a developer's laptop measures. When cold reads on a given box routinely approach the default, the right levers are raising `node-query-timeout-seconds-by-dialect.lake` (this section) or raising the engine's own `memory-limit` (§3.25) so more of the data stays cached between queries — see also [#141](https://github.com/msabiransari/datapipelines/issues/141) on the shared-engine memory budget these two knobs both bound against.

Read downward. The statement bound is the driver's and drivers honour it unevenly — measured on the shipped drivers (108 §1), which is precisely why the middle row exists. The operator defaults are not required to follow execution ≥ node ≥ statement: a node deadline above the execution's is never reached because the outer bound fires first, and a lower node deadline stops work before the driver's statement timeout. `node-timeout-max-seconds` is not constrained against the execution timeout. **A node's explicit `settings.query_timeout_seconds` is different:** save-time `SettingsRules` refuses it above that node's effective wall-clock deadline (its own `timeout_seconds`, or the operator default, capped by `node-timeout-max-seconds`). The runtime also clamps node wall-clock deadlines to `node-timeout-max-seconds`, including already-stored pipelines after an operator lowers the ceiling.

### 3.3 Staging (tempdb)

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.staging.h2.mode` | `PostgreSQL` | H2 compatibility mode placed in each execution's temporary database JDBC URL and used for scratch SQL probes. Must be a bare name matching `[A-Za-z][A-Za-z0-9]*`; H2 itself decides whether the name is supported when opening the database |
| `datapipelines.staging.h2.max-memory-mb` | `1024` | The memory-guard THRESHOLD each execution compares against the whole JVM's used heap — a shared, sampled measurement, not an isolated per-execution budget (see below; [Staging §8.2](staging.md#82-memory-accounting--measured-not-estimated)). A pipeline's `settings.tempdb.config.max_memory_mb`, when present, changes the threshold for that execution ([Pipeline Contract §5](pipeline-contract.md#5-settings)) — never which allocations are measured |
| `datapipelines.staging.h2.insert-batch-size` | `1000` | Positive number of rows per JDBC INSERT batch when draining source data into staging |
| `datapipelines.staging.h2.result-batch-size` | `10000` | Positive JDBC fetch size for staging `withQuery` reads; also the SCRIPT node's staged-input read batch size. This is a fetch/batch setting, not a result row cap |
| `datapipelines.staging.h2.query-timeout-seconds` | `60` | Nonnegative JDBC statement timeout on staging `withQuery` reads. `0` disables that driver's timeout. It is not applied to every staging statement: node execution also has the executor's separate statement and wall-clock budgets |
| `datapipelines.staging.h2.max-connections` | `4` | Cap on the **operational** H2 connections one execution's staging pool may hold open at once ([Staging §9](staging.md#9-connection-model)). A ceiling, not an allocation: the pool starts with one connection and grows on demand. `1` is legal and is the diagnosis/comparison setting — every tempdb operation then queues behind one connection |

> **`max-connections` is capacity, not parallelism.** It bounds how many tempdb operations of ONE execution can be inside H2 at the same instant; it creates no extra eligible DAG nodes (`executor.max-parallel-nodes` decides those) and promises no speedup — H2 keeps its own transaction, row and catalog locks, so two nodes touching one table still serialise inside the engine. Set it lower than `max-parallel-nodes` and nodes queue safely for a connection; set it higher and the surplus is never opened. Aggregate cost: every connection is one H2 session with its own query working memory, and `max-concurrent-executions-per-instance` executions can each hold up to this many — the staged tables themselves are shared by the connections of one execution and are not multiplied. Session state (`SET SCHEMA`, `SET @var`, local temporary tables, an open transaction) is per operation and is reset when a connection is returned; a node must not rely on it from another node — express data flow through ordinary tables and `depends_on`, whatever the cap ([Staging §9.2](staging.md#92-one-owner-per-connection-and-session-state-is-per-lease)).

> **`max-memory-mb` is a per-execution THRESHOLD over a JVM-WIDE measurement — not an isolated per-execution budget.** The guard samples used heap for the WHOLE JVM (in-process `MemoryMXBean.heapMemoryUsage.used`, [Staging §8.2](staging.md#82-memory-accounting--measured-not-estimated)), so every concurrent execution's check reads the same shared number: one heavy execution can trip a lighter one's check, and the guard is a sampled circuit breaker — not a hard reservation, and no guarantee against OOM. Concurrent executions retain separate staging data, but multiplying this threshold by the admission limit is neither measured heap demand nor an enforced memory ceiling. Size `-Xmx` and the container limit from observed concurrent workloads, with headroom for the rest of the application and native DuckDB/JavaScript memory ([Deployment §6.6](deployment.md#66-resource-sizing)). A per-execution accounting redesign is a separately decided follow-up, not this guard's promise.
>
> **Startup capacity warning (108 §C).** `ConfigValidator` warns when `max-concurrent-executions-per-instance × max-memory-mb > 0.8 × ` the JVM's max heap, naming the numbers and ratio. This is a sizing heuristic, not a startup refusal, a reservation or a prediction that OOM must occur. The runtime measurement remains JVM-wide as described above; validate capacity using the deployment's actual workloads.
>
> A pipeline's `settings.tempdb.config.max_memory_mb` override is **clamped to ≤ this value** — it may lower the operator's ceiling for that pipeline, never raise it. Save-time validation only checks `> 0`, so without the clamp an author could declare an arbitrarily large budget and disable the only ceiling the executor's `withConnection` paths have ([DAG Executor §9](dag-executor.md#9-tempdb-lifecycle-integration)).

### 3.4 Auth

Provider-field defaults below describe a new list entry. The shipped YAML declares Google and GitHub entries with their own endpoints, display names and picture hosts; both remain disabled until their client IDs are set. When overriding the provider list in a higher-precedence configuration source, supply the complete desired list: Spring's binder replaces the list, rather than merging each entry with the shipped list by index.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.auth.base-url` | (none) | The deployment's external origin, e.g. `https://dp.example.com` (scheme + host [+ port]). Provider callback URLs are built as `<base-url>/login/oauth2/code/<provider-name>` ([Auth §5.2](auth.md#52-clientregistration-bean-built-at-startup)), never from request headers; trailing slashes are removed by the URL consumers. Also supplies login links in mail and the default Secure-cookie decision. **Required with any enabled OIDC/OAuth2 provider or configured mail.** |
| `datapipelines.auth.jwt.ttl-hours` | `8` | Lifetime in hours of newly issued session JWTs, also used for the session/CSRF cookie maximum age. Does not extend an existing token's signed expiry. |
| `datapipelines.auth.allowlist.domains` | (empty) | Comma-separated allowed email domains. Binds to `List<String>` (comma-split; empty string = empty list = open provisioning) |
| `datapipelines.auth.api-keys.cache-ttl-seconds` | `60` | Per-instance TTL for successful API-key secret verification, key records, user liveness, workspace rows and memberships ([Auth §11.4](auth.md#114-api-key-validation-cache)). Administrative changes invalidate the affected local entries immediately; other replicas converge when their cached entries expire. Longer TTLs increase that revocation/deactivation delay. |
| `datapipelines.auth.rate-limit.login-per-minute` | `10` | Per-client-IP request budget over a fixed 60-second window for paths starting `/login` or `/oauth2/` (including page requests, not just failed password submissions). Exceeding it returns 429 with `Retry-After: 60`. Per instance; values ≤ 0 disable this limiter. |
| `datapipelines.auth.trusted-proxies` | (empty) | CIDRs of proxies whose `X-Forwarded-For` names the real client — the login limiter and every audit `source_ip` resolve through it ([Deployment §6.2](deployment.md#62-multi-instance-horizontal-scaling-production)). Empty (the default) = the header is ignored entirely and the direct peer is the client — a bare deployment behaves exactly as before. Each entry must parse as a CIDR (a bare IP is a host CIDR, e.g. `10.0.0.5` = `10.0.0.5/32`); anything else refuses startup. Resolution is spoof-safe: an untrusted peer cannot forge a client by setting the header |
| `datapipelines.auth.bootstrap-admin-email` | (none) | Address whose newly created user row receives the instance-admin grant (trimmed and case-normalized). Used both on provider provisioning and when startup pre-provisions the actor for datasource bootstrap or a local bootstrap credential. Existing rows are not re-granted on restart. Changing it does not rename an account or reset its password ([Auth §4.4](auth.md#44-bootstrap-admin)). |
| `datapipelines.auth.local.enabled` | `false` | Optional local username/password accounts ([Auth §5A](auth.md#5a-local-password-accounts-optional)) — a second sign-in method for deployments without an IdP. Disabled = the deployment behaves exactly as OIDC-only |
| `datapipelines.auth.local.bootstrap-password-hash` | (none) | Initial credential for the FIRST ADMIN ONLY (the `bootstrap-admin-email` account), as a pre-computed Argon2id hash — the preferred form (produce one with the `hashPassword` Gradle task, [Deployment §5](deployment.md#5-configuration)). Seeding is create-if-absent and idempotent, forces a first-login change, and never applies to ordinary users — passwords are not a config medium |
| `datapipelines.auth.local.bootstrap-password` | (none) | Plaintext alternative to the hash form, accepted for zero-setup demos: always sets `must_change_password`, is never logged, and startup is refused when both forms are set |
| `datapipelines.auth.local.lockout.max-failures` | `5` | Consecutive wrong-password checks before locking the account; used for local login and current-password verification during password changes. Positive integer, per-account, complementing the per-IP `rate-limit.login-per-minute`. |
| `datapipelines.auth.local.lockout.duration-minutes` | `15` | Positive integer minutes a locked account refuses local login. An admin unlock or password reset clears the lock early. |
| `datapipelines.auth.cookie-secure` | (empty) | The `Secure` flag on the cookies this deployment mints (`dp_session`, `dp_oauth2_authz`, `dp_csrf`). **Empty = derived from `base-url`'s scheme**: an explicit `http://` base-url drops the flag so local login works over plain HTTP, anything else keeps it. `true`/`false` pin it. The `hardened` posture ships `true` (§3.23) — a hardened deployment runs https, and a dropped flag there puts the session cookie on the wire |
| `datapipelines.auth.allow-local-only` | `false` | The operator's explicit acknowledgement that a `hardened` deployment authenticates with local accounts without a complete OIDC/OAuth2 provider. Inert under `development`; under `hardened`, absence of a complete provider requires `true` and logs the acknowledgement (§3.23, §7). This does not enable local accounts: `local.enabled` must still be true to satisfy the at-least-one-authentication-method rule. |
| `datapipelines.auth.oidc.provider-read-timeout-seconds` | `10` | Connect and read timeout in seconds for the code-for-token exchange (both protocols), OAuth2 user-info, emails and OIDC user-info reads. Positive integer, at most 2147483 seconds (the HTTP client stores signed 32-bit milliseconds); zero means infinite in the HTTP client and is refused. The ID-token decoder's JWKS fetch is bounded by the same key through a `JwtDecoderFactory` bean (#686); startup discovery is separate. Mirrored in application.yml, defaults.env, secrets.env.example, compose.yml. |
| `datapipelines.auth.oidc.providers[].name` | (empty) | Provider registration identifier, required and non-blank for an enabled entry. Appears in `/oauth2/authorization/<name>`, `/login/oauth2/code/<name>` and stored user provider identities. Names `bootstrap`, `local`, `system` and `key` are reserved, case-insensitively. Keep registration identifiers stable for existing users. |
| `datapipelines.auth.oidc.providers[].client-id` | (empty) | Client/application identifier registered with the identity provider. **Blank disables this provider entry** with a startup warning; its endpoints are not discovered and its picture hosts do not enter the active avatar allowlist. |
| `datapipelines.auth.oidc.providers[].client-secret` | (empty) | Secret used to authenticate the application during the authorization-code exchange. Required when `client-id` is non-blank; store in deployment secrets, never in a committed provider block. |
| `datapipelines.auth.oidc.providers[].issuer-uri` | (empty) | Required for an enabled `oidc` provider: discovery begins from this issuer during startup and supplies authorization, token, user-info and JWKS metadata. Must be empty for `oauth2`; that protocol uses explicit endpoints. |
| `datapipelines.auth.oidc.providers[].display-name` | (none) | Human-readable provider name passed to the client registration and sign-in button. When absent, the registration uses `name`; it does not change the provider identifier or callback path. |
| `datapipelines.auth.oidc.providers[].protocol` | `oidc` | Per-provider (auth.md §5.1, #655): `oidc` builds the entry through discovery from `issuer-uri`; `oauth2` builds it from the five keys below with no HTTP at startup — for a provider that speaks OAuth 2.0 without OpenID Connect (GitHub). Anything else refuses startup naming the provider |
| `datapipelines.auth.oidc.providers[].authorization-uri` | (empty) | Per-provider, `oauth2` only and required there (auth.md §5.1): the consent endpoint. `https://`, or plain `http://` to `localhost` / `127.0.0.1` only. Set on an `oidc` entry it refuses startup — discovery would ignore it |
| `datapipelines.auth.oidc.providers[].token-uri` | (empty) | Per-provider, `oauth2` only and required there: the code-for-token exchange, called with `client_secret_basic` and `Accept: application/json`. Same scheme rule |
| `datapipelines.auth.oidc.providers[].user-info-uri` | (empty) | Per-provider, `oauth2` only and required there: the profile read, the access token as bearer. Same scheme rule |
| `datapipelines.auth.oidc.providers[].user-name-attribute` | (empty) | Per-provider, `oauth2` only and required there: the user-info attribute that is the stable subject stored in `users.provider_subject` (GitHub: `id`) |
| `datapipelines.auth.oidc.providers[].scopes` | (empty) | Per-provider, `oauth2` only and required there: the scopes requested, a YAML list or comma-separated. Never `openid` — that is the `oidc` protocol, and it would route the login to the OIDC user service |
| `datapipelines.auth.oidc.providers[].emails-uri` | (none) | Per-provider, `oauth2` only, optional (auth.md §5.5): a list of `{email, primary, verified}` rows. When set it is the ONLY source of the address — the user-info `email` is discarded, only the `primary` row is read, a row not `verified: true` refuses the login, and `trust-email-without-verified-claim` is not consulted. Same scheme rule |
| `datapipelines.auth.oidc.providers[].trust-email-without-verified-claim` | `false` | Per-provider (auth.md §5.1, #187): set `true` only for an IdP trusted to supply a verified email without the `email_verified` claim. A missing or unrecognized claim is then accepted and audited (`auth.login.email_verified_assumed`). Boolean/string `false` is refused regardless; boolean/string `true` verifies without this exception. Ignored when `emails-uri` supplies the verified-primary address. The default refuses missing/unrecognized claims ([Auth §4.2](auth.md#42-user-provisioning)). |
| `datapipelines.auth.oidc.providers[].picture-hosts` | (empty) | Per-provider (auth.md §11.1, #197): bare hostnames the avatar proxy may fetch the stored `users.profile_picture_url` from, so the browser never loads an image from an identity provider's host and the CSP `img-src` stays `'self' data:` ([Deployment §6.2](deployment.md#62-multi-instance-horizontal-scaling-production)). NOT derivable from `issuer-uri` (Google: `accounts.google.com` issues, `lh3.googleusercontent.com` serves pictures) — the operator names the hosts the deployment trusts. **Empty = no fetch**: every avatar falls back to initials. Each entry must be a bare hostname (no scheme, port, path, userinfo or wildcard); anything else refuses startup. Only providers with a non-blank `client-id` contribute; a dormant entry's hosts are validated for shape but never allowlisted (#651). **No port**: an entry names a host, never a port, and a stored picture URL that names one is refused (#246) — as are non-raster answers and a fetch past its 5 s budget (auth.md §11.1) |

### 3.5 Results

Every completed execution's caller result is stored in Redis and read through the result cursor ([REST API §7](rest-api.md#7-result-delivery)).

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.result.ttl-default-seconds` | `300` | Result TTL when the client sends no `DP-Result-TTL-Seconds` header |
| `datapipelines.result.ttl-min-seconds` | `60` | Lower clamp for client-requested TTL |
| `datapipelines.result.ttl-max-seconds` | `3600` | Upper clamp for client-requested TTL |
| `datapipelines.result.max-size-bytes` | `104857600` | Positive cap on the accumulated UTF-8 serialized rows of a caller result (100 MiB), measured during Redis materialization. Exceeding it deletes the partial result and fails the execution with `result.too_large`; it is not a cap on source rows or JVM heap |
| `datapipelines.result.page-size-rows` | `1000` | Rows in the inline first page of `data_ready`, and the default `limit` for cursor reads |
| `datapipelines.result.page-max-rows` | `100000` | Upper bound on the cursor `limit` parameter |

### 3.6 SSE

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.sse.heartbeat-interval-seconds` | `15` | Positive heartbeat-comment interval for execution, dashboard-refresh and observed-evaluation streams. Also the executor's polling interval for cross-instance cancellation flags in Redis |
| `datapipelines.sse.disconnect-grace-seconds` | `30` | Nonnegative grace period after a stream is first observed disconnected before its in-flight execution is cancelled ([REST API §6.8](rest-api.md#68-client-disconnect)) — the same grace aborts a dashboard refresh (§23.3) and an observed parameter-set evaluation ([REST API §21.5](rest-api.md#215-the-observed-evaluation)) whose client stayed away. Detection and expiry are checked on heartbeat ticks, so this is not an exact timer from the network disconnect |
| `datapipelines.sse.max-streams-per-user` | `50` | Positive concurrent SSE stream admission limit per user **on one instance** — execution, refresh and observed-evaluation registries count together; these counters are process-local |

### 3.7 Rate Limiting

Limits are **per user** (an API key inherits its owner's budget — minting more keys does not raise the limit).

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.rate-limit.requests-per-second` | `100` | Positive per-user request budget in a Redis counter keyed by epoch second. Both the second and minute windows are consumed on every limiter call; exceeding either refuses the request |
| `datapipelines.rate-limit.requests-per-minute` | `1000` | Positive per-user request budget in a Redis counter keyed by epoch minute. Instances sharing Redis share these fixed-window counters |

There is deliberately **no fail-open key**. When the limiter's Redis is unreachable the limiter refuses the request (`429 rate_limit.unavailable`, [REST API §12.3](rest-api.md#123-when-the-limiter-itself-is-unavailable)); a per-deployment toggle would only ever be reached for at exactly the moment the refusal is the correct answer.

### 3.8 Idempotency

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.idempotency.ttl-seconds` | `86400` | Lifetime, from reservation, of the Redis `Idempotency-Key` record containing user, request hash and execution ID. A matching replay attaches to that execution while the record exists; after expiry a new reservation may execute again. Use positive seconds: this binding has no startup range check and the value is passed directly to Redis expiry |

### 3.9 Templates

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.templates.cache-size` | `500` | Entry threshold for each workspace engine's parsed SQL and HTML template caches, and the workspace registry's version cache. The parsed caches clear when they grow above the threshold; the registry evicts its eldest entry. This is not a deployment-wide byte budget |
| `datapipelines.templates.render-timeout-ms` | `5000` | Maximum caller wait for one FreeMarker render, in milliseconds. On timeout the engine cancels the worker and returns a render failure; injected interruption checks let the render stop cooperatively |
| `datapipelines.templates.max-body-chars` | `262144` | Max template `body` length accepted at save (256K chars); over-cap bodies are rejected with `template.validation.syntax_error` before parsing ([Templates §4.2](templates.md#42-allowed-freemarker-constructs)) — bounds parse cost and heap against an adversarial body |

### 3.10 UI

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.ui.theme` | `dark` | Deployment-default design-system theme. An authenticated user's saved theme preference overrides it; unauthenticated pages and users without a preference use this value. Validated at startup against the vendored themes in `modules/web/src/main/resources/static/vendor/design-system/` |
| `datapipelines.ui.actively-developed-days` | `14` | Days since a draft's last write within which an object counts as actively developed on every lifecycle home (Pipelines since #598; Templates, Dashboards, Visualizations and Parameter Sets since #599). Integer ≥ 1. |

### 3.11 Execution History

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.executions.error-detail` | `full` | How much of a failure's detail travels to every surface that carries an error (the SSE `node_failed`/`pipeline_failed` `error` objects, `pipeline_executions.error_json`, the editor's failure panel, MCP). `full` includes the exception chain with stack frames and the rendered SQL in `:name` form; `structured` omits both. Choose `structured` for a deployment whose pipeline authors are not trusted to see driver internals ([DAG executor §8.4](dag-executor.md#84-the-failure-record)) |
| `datapipelines.executions.event-retention-days` | `7` | Positive retention period after completion for `execution_events`, finished dashboard-refresh records and observed parameter-evaluation history, enforced by the hourly retention job. Related child records cascade with their parent history. `pipeline_executions` rows are not deleted. The post-completion Redis event log has its separate fixed one-hour lifetime |
| `datapipelines.executions.stale-timeout-minutes` | `60` | Positive age-from-start cutoff for marking a RUNNING execution ABORTED **only when its heartbeat is null**. Rows with a heartbeat instead use three times `datapipelines.executor.heartbeat-seconds`; this setting is the legacy-row fallback, not the normal crash-detection interval |

**Not a key: the scheduled jobs' statement bounds (#326, #636).** The stale sweep's UPDATE and the dashboard refresh sweep's UPDATE are cancelled after 10 s and the hourly retention's metadata statements (the `execution_events` DELETE, the audit retention's cutoff read and batches, the keys purge's FK read and two DELETEs, the dashboard refreshes' DELETE, the parameter evaluations' sweep UPDATE and batch DELETE) after 25 s — a JDBC `queryTimeout`, failing the tick with SQLState `57014` ([Metadata DB §8.1](metadata-db.md#81-execution-event-cleanup), [§8.3](metadata-db.md#83-stale-execution-sweep), [§8.4](metadata-db.md#84-stale-dashboard-refresh-sweep), [§8.5](metadata-db.md#85-stale-parameter-evaluation-sweep)). They are code constants beside the cadences they serve (`SWEEP_STATEMENT_TIMEOUT_SECONDS`, `REFRESH_SWEEP_STATEMENT_TIMEOUT_SECONDS`, `RETENTION_STATEMENT_TIMEOUT_SECONDS`), as the cadences are, not keys: each is set by its own cadence and the 30 s shutdown phase, not by a deployment.

### 3.12 Audit

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.audit.retention-days` | `365` | How long an `audit_log` row is kept. Enforced by the retention sweep's last step, hourly (#310, [Metadata DB §8.2](metadata-db.md#82-audit-log-retention), [Auth §10.3](auth.md#103-retention)): a row whose `timestamp` is older than the database's `NOW()` minus this many days is deleted — every event alike, one cutoff read from the database's clock once per tick. **Bounds 30–3650**, refused at startup outside them: a `1` stops the deployment rather than erasing the trail, and there is no "off" — a deployment that wants the trail longer sets a larger value. The delete is batched: at most 5,000 rows per statement and 50 statements or 2 seconds per tick, whichever comes first; a tick that stops with rows left logs `event=audit.retention_incomplete` (WARN) and the next tick continues ([Observability §7](observability.md#7-audit-log)). The hourly cadence and the batch bounds are code constants, not keys |

### 3.13 Server

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` | HTTP port |
| `spring.datasource.hikari.maximum-pool-size` | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `10` | Metadata DB connection pool size |
| `server.tomcat.max-swallow-size` | `SERVER_TOMCAT_MAX_SWALLOW_SIZE` | `2MB` | How much of a REFUSED request body Tomcat may drain before closing the connection (#279) — the counterpart of the request-body cap ([§3.31](#331-web-request-limits)); an abandoned over-cap upload costs the connection, never the server. The screenshot upload route reads its own refused declaration of up to its cap + 2 MiB (6 MiB, a fixed bound beside the route's cap — this key does not move it) before the close, so that connection ends on a FIN after the client's last byte; Tomcat's swallow applies beyond it (#467) |

### 3.14 Framework wiring keys

These framework key paths appear in `application.yml` as internal wiring. They are listed here so the §1 authority sentence stays literally true. Except for the management listener settings below, these are shipped framework wiring, not ordinary tuning controls. Preserve them when supplying an external configuration file:

| Path | Why it exists |
|---|---|
| `spring.application.name` | Log/metric attribution (`datapipelines`) |
| `spring.flyway.enabled` | `true`: enables startup metadata migrations through Boot's Flyway auto-configuration. |
| `spring.flyway.locations` | `classpath:db/migration`: migration scripts packaged by the app module. |
| `spring.flyway.baseline-on-migrate` | `false`: do not automatically baseline a pre-existing nonempty schema; follow [Deployment §8.2](deployment.md#82-database-migrations). |
| `spring.profiles.active` | Resolves the selected posture; do not independently select a conflicting profile (§6). |
| `server.shutdown` | `graceful`: Boot/Tomcat stops accepting new requests and lets active requests finish during shutdown; the execution drain runs first. |
| `server.tomcat.use-relative-redirects` | `true`: redirects use relative locations, preserving the browser's external HTTPS origin behind a TLS-terminating proxy. |
| `management.health.db-scheduler.enabled` | `false`: disables the library health contributor; scheduler operation is observed through metrics rather than making API-mode instances unhealthy. |
| `db-scheduler.threads` | Delegates to the scheduler worker count in §3.29. |
| `db-scheduler.polling-interval` | Delegates to the scheduler polling interval in §3.29, adding the duration unit `s`. |
| `db-scheduler.heartbeat-interval` | Delegates to the scheduler heartbeat interval in §3.29, adding `s`. |
| `db-scheduler.missed-heartbeats-limit` | `6`: the library's dead-execution detection threshold. |
| `db-scheduler.shutdown-max-wait` | Delegates to the scheduler shutdown budget in §3.29, adding `s`. |
| `db-scheduler.table-name` | `scheduled_tasks`: metadata table used by the scheduler library. |
| `db-scheduler.immediate-execution-enabled` | `true`: wake the local scheduler when an execution is scheduled. |
| `management.endpoints.web.exposure.include` | `"health"` — served on the **management port** only; `prometheus` joins it when the metrics registry lands ([Observability §6.4](observability.md#64-actuator-security)). **Must not be set to `""` or `exclude: "*"`:** Spring's `@ConditionalOnAvailableEndpoint` keys bean creation off exposure, so an empty include falls back to Boot's default set (re-exposing health on whatever port serves actuator), and excluding everything deletes the `HealthEndpoint` bean the root `/health` controller injects — context startup fails outright. |
| `management.health.diskspace.enabled` | `false` — the health contract has no disk component ([REST API §11.1](rest-api.md#111-health-check)) |

**Operator-tunable exception:**

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `management.server.port` | `MANAGEMENT_SERVER_PORT` | `9090` | Separate management port serving `/actuator/health` (and later `/actuator/prometheus`) — never publish it; nothing actuator is routable on the application port. |
| `management.server.address` | `MANAGEMENT_SERVER_ADDRESS` | `127.0.0.1` | **Loopback by default** — the management surface is silently-public on no deployment shape. Kubernetes scraping (scraper → pod IP) requires explicitly setting `0.0.0.0`, paired with a NetworkPolicy limiting management-port access to the declared scraper peers ([Deployment §9](deployment.md#9-security-hardening-checklist-deployment)). Explicit opt-open, never default-open. The reference Helm chart ships this opt-open as the `management` block (chart 0.2.1): `management.expose` pins `0.0.0.0` and the management port, and `management.networkPolicy.enabled` adds a complete ingress NetworkPolicy: the scraper rule plus a required app-port declaration (`management.networkPolicy.appIngress.from` or an explicit `allowAll: true`). Its allowances are unioned with every other policy selecting the pods — [Deployment §6.4](deployment.md#64-kubernetes-recommended-for-production). |

### 3.15 Observability

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.observability.tracing.enabled` | `DATAPIPELINES_OBSERVABILITY_TRACING_ENABLED` | `false` | **Reserved; currently no runtime effect.** Shipped in YAML and deployment forwarding, but there is no property consumer, tracing bridge or OpenTelemetry exporter dependency in the application. Setting `true` does not enable tracing. |
| `datapipelines.observability.tracing.endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | (none) | **Reserved; currently no runtime effect.** This proposed collector setting has no YAML mapping or application reader. It does not configure an exporter in this build. |
| `datapipelines.observability.logging.format` | `DATAPIPELINES_OBSERVABILITY_LOGGING_FORMAT` | `json` (application); `console` in `defaults.env` | Log output format — the closed set `json` \| `console` ([Observability §3.1](observability.md#31-format)). **Bound at startup (#337)**: resolved before logging initialises, and an unknown value refuses startup with the offending value named. `console` is the human-readable development output (`defaults.env` ships it for local runs); `json` is the structured §3.1 line a log collector parses (raw Compose interpolation falls back to `json`; `app.sh` loads `defaults.env`, which supplies `console`, until the operator overrides it). `json` also switches Spring's startup banner off, so stdout carries JSON lines only; `console` keeps it (#337-b). |

### 3.16 Pipelines

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.pipelines.max-composition-depth` | `5` | Deepest admitted chain of pipelines executing pipelines (a PIPELINE node spawning a child execution). Enforced at save time and again at runtime; must be ≥ 1 |

### 3.17 Workspaces

Workspaces are created by **super admins** ([auth.md §4.2/§5.6](auth.md#56-workspace-resolution--the-dp-workspace-header), [§12](auth.md#11a-roles)). The one workspace the product ships is `demo`, and a user with no membership becomes a **viewer** of it on first login.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.workspaces.member-datasources-enabled` | `false` | May a workspace ADMIN register a datasource bound to their own workspace? `false` makes datasource registration a super-admin-only act instance-wide. Visibility is still the grant either way ([auth.md §11A](auth.md#11a-roles)). **In-process engines (H2 `mem:`/`file:`, DuckDB, SQLite) and any file-backed URL are super-admin-only regardless of this flag (#186)** — see [`file-roots`](#326-datasource-pools) |

**Removed in RBAC round 1** (`provisioning-mode`, `open-join`). Capability moved onto the workspace membership, and with it went the modes that decided who could create a workspace: `auto-per-user` (a personal workspace per login), `self-serve` (anyone creates) and `closed` (admin only), plus `open-join` (anyone self-joins). **Both keys are refused BY NAME at startup** (§7) rather than ignored — a deployment that still says `auto-per-user` is a deployment expecting a personal workspace per user, and silently giving it something else is how an operator finds out from a user. Delete the key; there is no replacement to set, because the behaviour is no longer a knob.

### 3.18 Bootstrap

Config-declared content applied at startup (design 2026-08-16-sample-data §6/§6.1). Both keys name **files already on the container's filesystem** — the app never fetches an artifact at runtime; downloading and verifying artifacts is a deployment step (D5) — and for both, **unset (or empty) means the feature is off**. There is no separate enable flag to disagree with the path.

Both values are **comma-separated LISTS of paths** (one entry per sample-data family; the demo profiles compose the list from the active families). A single value with no comma is the one-file shape older deployments ship — the list semantics are backward compatible by construction. Empty entries (a leading comma when a family is off, or a whitespace-only entry) are dropped, so a list built by shell conditional expansion never turns the feature on by accident. The files are processed **in declared order**.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.bootstrap.datasources-file` | (none) | Path(s) to YAML file(s) of datasource definitions registered **create-if-absent** at startup ([Datasources §8A](datasources.md#8a-bootstrap-registration-config-declared-datasources)). Files are read and entries registered in order; unreadable/unparseable files or a new entry failing [§9 validation](datasources.md#9-validation-rules) stop startup. Existing names, including soft-deleted names, are not recreated. There is one credential-only reconciliation exception: if the stored credential fails to connect and the file's credential succeeds, the stored credential is updated. Other fields stay unchanged; if neither credential works or the stored one cannot be decrypted, startup logs the fault and keeps the row. LAKE table seeds are reapplied idempotently to live entries on every boot. |
| `datapipelines.bootstrap.examples-file` | (none) | Path(s) to JSON files loaded and structurally checked at startup. Content is imported when startup **creates** the `demo` workspace, as the system actor; an existing workspace is not reseeded. Optional arrays are `templates`, `parameter_sets` and `pipelines`: all templates across files import first, then all parameter sets, then pipelines. Optional `requires_datasources` lists names that must be visible to the target workspace; otherwise that file is skipped with a log entry. Import validation failures stop this startup seeding. Files are deployment fixtures, not an update mechanism for an already-created demo workspace. |
| `datapipelines.bootstrap.demo-api-key` | `dpk_DEMOPUBLIC42.THISKEYISPUBLICBYDESIGNDEMOONLYREADONLY222222222` | The full `dpk_<id>.<secret>` plaintext the demo workspace's public `api_caller` key is minted from (#224, rest-api §19). Public by design: the key's whole reach is the demo endpoints bound to it, and the same value renders on the site's demo-data page. **Blank = off** (no key minted, no demo endpoints, the page section hidden); a changed value rotates the key on the next boot (the old key is revoked, the configured one minted and bound). A malformed value, or an operator-selected non-default value under `hardened`, is a startup refusal (§7). |

**Cross-key rule:** `datasources-file` set while [`datapipelines.auth.bootstrap-admin-email`](#34-auth) is unset is a startup refusal naming both keys. Bootstrap-registered datasources are `created_by` that user, and registration runs before anyone has logged in, so the row is pre-provisioned from that address ([Auth §4.4](auth.md#44-bootstrap-admin)). `examples-file` carries no such rule: demo content seeding uses the system actor at startup.

**`demo-api-key` (v1.29):** its explicit environment variable is `DATAPIPELINES_DEMO_API_KEY`. Blank is the kill switch: on restart, the seeder retracts managed demo endpoints and revokes managed demo keys. The shipped default is the committed public value the demo-data page renders. The validator exempts that exact default under `hardened`; an operator-selected non-default value under `hardened` is refused at boot (§7). Seeding publishes no endpoints and no key when no demo family produced pipelines for it; it also retracts previously managed rows in that case.

**No cross-key rule for `examples-file` (dropped in v1.9 with the provisioning modes, §3.17).** The rule refused the file while `provisioning-mode` was anything but `auto-per-user`, because only that mode provisioned the personal workspace the examples were seeded into. Since RBAC round 1 the seeder runs when `demo` is created, in every deployment, so there is no mode left for the file to be unreachable under and `ConfigValidator` carries no such check. The file is still read and structurally checked at startup (fail-fast on an unreadable or unparseable file).

### 3.19 Deployment

The deployment-role settings ([Versioning §5.5](versioning.md#55-drafts-are-a-deployment-capability-039)) — grouped one-per-concern like `auth`, `executor`, `staging`.

> **Renamed in 075.** `datapipelines.deployment.name` — the deployment LABEL — is now **`datapipelines.env`** (`DATAPIPELINES_ENV`), documented in §3.23 beside the posture it is deliberately separate from. The old key is honoured for **one release**: set alone it still names the deployment and startup logs `event=config.deployment_name_deprecated`; set together with `datapipelines.env` and disagreeing, startup is refused naming both. See [Environments](environments.md).

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.deployment.authoring-enabled` | `DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED` | `true` (`false` under the `hardened` posture — §3.23) | The authoring capability for pipelines, templates, parameter sets, visualizations and dashboards. `false` refuses their authoring writes with the family's `authoring.disabled` error and refuses startup if any of those families still holds drafts. Reads, released execution and import remain available; draft-pointer eligibility also follows this capability. A promotion receiver must set it to `false`: inbound pushes to an authoring-enabled deployment are refused. |
| `datapipelines.deployment.name` | `DATAPIPELINES_DEPLOYMENT_NAME` | (unset) | Deprecated alias of `datapipelines.env`. Used, with a warning, when the new key is blank or still `local`; a different non-default new value and a different alias refuse startup. This alias is deliberately absent from the shipped YAML; use `datapipelines.env` for new deployments. |

#### Promotion (the deployment-to-deployment channel)

Promotion is one deployment writing RELEASED content into exactly one configured higher environment ([Versioning §10](versioning.md#10-promotion-ui-driven-separate-use-case)). The credential is a server-kind API key (or the deprecated shared config secret below), presented as `DP-Promotion-Key`. `PromotionServerKeyFilter` creates a principal with the `promotion_receiver` key role: a stored key uses its key identity, while the legacy config secret uses the system actor. A deployment may hold the receiver half, the sender half, both, or neither.

> **The receiver half is now a KEY, and the config value below is DEPRECATED (091).** Mint a `server`-kind API key on the API screen ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)) and give it to the sending deployment as its `target.server-key`. A stored key is everything a config value cannot be: expiring, revocable, listed beside every other credential, rotatable without a restart, and stamped with a last-used time. `datapipelines.deployment.promotion.server-key` is still accepted for **one release** so an upgrade does not break an existing pair — and it WARNs at boot when set (§7). Both credentials open the same routes; the receiver tries the configured value first, then the key store.

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.deployment.promotion.server-key` | `DATAPIPELINES_DEPLOYMENT_PROMOTION_SERVER_KEY` | (empty) | **Receiver half — DEPRECATED (091), removed next release.** The pre-shared secret an inbound promotion may present in the `DP-Promotion-Key` header, superseded by a `server`-kind API key. **Empty is not "promotion is open"** — with no configured value AND no live `server` key, every push is refused; fail closed. Compared in constant time, before the key store is consulted; never logged. Consulted on `/api/v1/promotion/**` and nowhere else, and it grants no read access outside that pair. Set ⇒ one WARN at boot |
| `datapipelines.deployment.promotion.target.base-url` | `DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_URL` | (empty) | **Sender half.** The single higher environment's base URL (e.g. `https://uat.example.com`). Empty = this deployment promotes nowhere and the promotion screen says so. Exactly one target — multi-target promotion is deliberately not a feature |
| `datapipelines.deployment.promotion.target.server-key` | `DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_KEY` | (empty) | **Sender half.** The plaintext of a live server-kind API key minted on the receiver, or the receiver's matching legacy shared config secret. Sent in `DP-Promotion-Key` for inventory and push requests. A `base-url` set without this key refuses startup (§7). Changing it requires restarting the sender to bind the new credential. |
| `datapipelines.deployment.promotion.inventory-cache-ttl-seconds` | `DATAPIPELINES_DEPLOYMENT_PROMOTION_INVENTORY_CACHE_TTL_SECONDS` | `60` | **Sender half (178).** How long the target's inventory is remembered, per workspace, for the **promoter lens** ([Auth §11A.1](auth.md#11a1-the-404-rule)): the lens evaluates [Versioning §10.2](versioning.md#102-the-listing-rule-what-the-ui-shows)'s rule on every read a promoter makes, so one target call serves a window of reads. An UNREACHABLE target is remembered for the same window — one probe and one `pipeline.promotion.lens_unavailable` WARN per window, during which the promoter sees nothing (fail closed). A successful push invalidates the workspace's entry; the push path always reads a fresh inventory (§10.3). `0` disables the cache |

**On the SENDER, the value is whatever the receiver issued**: the `dpk_<id>.<secret>` plaintext of the receiver's `server` key, shown exactly once at minting. To rotate, mint a second server key on the receiver, update the sender's secret and restart the sender, verify promotion, then revoke the first key. The receiver needs no restart for stored-key minting or revocation. For the deprecated config secret, generate it with `openssl rand -base64 32`, configure the same value at both ends and restart both. Stored server keys authenticate as their own key identities, not the human who created them; revocation, expiration, a deactivated identity or a deactivated pinned workspace can refuse the credential.

**Both keys are bearer secrets.** They are never logged, never in an error message, never in an audit `details` map, and never in a `toString()`. A receiver records only a truncated SHA-256 fingerprint of the key a push presented, so two keys are distinguishable across a rotation without either being carried.

### 3.20 Credential key provider

Where the AES data keys for `datasources.credential_encrypted` come from ([Datasources §7.1](datasources.md#71-encryption-at-rest)). The same encryptor opens legacy sealed login-key copies through `SecretSealer`; newly minted keys do not store such copies. Keep old keys while any ciphertext still needs their version. The seam permits a customer's AWS/GCP/Azure/Vault key store implementation without changing the crypto — [Key providers](key-providers.md) is the implementer's guide; only the `env` implementation ships. Every ciphertext carries the key version as its first byte, which makes rotation lazy-safe.

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.db.key-provider` | `DATAPIPELINES_DB_KEY_PROVIDER` | `env` | Which provider supplies data keys. `env` is the only one this build ships and is today's behaviour, so a deployment that predates the seam needs **no config edit**. An unknown name refuses startup, listing what is shipped (§7) |
| `datapipelines.db.encryption-keys` | Explicit YAML placeholder per entry | (unset) | `env` only. Additional keys as `version: base64`, for a rotation — e.g. `2: ${DATAPIPELINES_DB_ENCRYPTION_KEY_V2}`. Supply the map in YAML (or another structured Spring property source) and explicitly pass any referenced secret variable into the process. Each value is exactly 32 bytes base64-encoded; additional versions are integers `2..255`. Version `1` may **not** appear here: that version is `datapipelines.db.encryption-key` and has exactly one spelling. |
| `datapipelines.db.encryption-key-current` | `DATAPIPELINES_DB_ENCRYPTION_KEY_CURRENT` | the highest configured version | `env` only. Which configured version NEW encryptions use. Existing rows keep decrypting under the version they carry, so flipping this rotates lazily rather than all at once ([Datasources §7.3](datasources.md#73-key-rotation)) |

**Every configured key is a bearer secret.** None of them is logged, put in an error message, put in an audit `details` map, or rendered by a `toString()` — the §7 violations name the *property* and the defect, never the value.

**Rotating.** Generate a key (`openssl rand -base64 32`), add it as version N+1, set `encryption-key-current: N+1`, restart. New writes carry N+1; rows written under earlier versions keep decrypting, and are rewritten under the current key the next time their password is saved. Keep the old keys configured until no row still carries their version — [Datasources §7.3](datasources.md#73-key-rotation) has the one SQL query that answers that.

### 3.21 Organisation

The organisation's own facts — its currency, when its fiscal year starts, which day its week starts on, which timezone "today" means. They are **configuration, not pipeline data**: identical for every pipeline in the deployment, changed about once a decade, and a value that differs between deployments must be visible in the deployment's yml rather than copied into every body.

Every key here enters every execution's Context as an `org_*` key — the yml path minus the `datapipelines.org.` prefix, with dots and dashes as `_` ([Pipeline Contract §7.2](pipeline-contract.md#72-whats-in-the-context--one-namespace-five-tiers)). A node binds `:org_currency_symbol`; a `CALCULATOR` node references `$org_fiscal_start_date`. None of them is a secret, and a pipeline may override any of them by declaring a parameter of the same name.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.org.currency.name` | `Dollar` | Non-blank currency label exposed as context key `org_currency_name`. Templates/calculators must reference it; it does not convert currencies or automatically change every report. |
| `datapipelines.org.currency.symbol` | `$` | Non-blank currency symbol exposed as context key `org_currency_symbol`, for templates/calculators that render amounts. Does not automatically format every numeric result. |
| `datapipelines.org.fiscal-start-date` | `01-01` | `MM-DD` — the day the fiscal year starts; `01-01` is the calendar year. Month names are refused (§7). `02-29` is accepted and resolves to 02-28 in a non-leap year. Context key `org_fiscal_start_date` |
| `datapipelines.org.week-start` | `monday` | `monday` \| `sunday`, exposed as context key `org_week_start`. Pass that context value to a calculator's week-start input to apply the organisation's choice; it does not rewrite independent calculator inputs or SQL. |
| `datapipelines.org.timezone` | `UTC` | An IANA zone id. `current_date` is evaluated in it, so a deployment in Sydney on a UTC host still sees its own today. Context key `org_timezone` |

Env vars follow §1's derivation rule: `DATAPIPELINES_ORG_CURRENCY_NAME`, `DATAPIPELINES_ORG_CURRENCY_SYMBOL`, `DATAPIPELINES_ORG_FISCAL_START_DATE`, `DATAPIPELINES_ORG_WEEK_START`, `DATAPIPELINES_ORG_TIMEZONE`.

**Restart to change.** There is no runtime override and no per-user setting: an org value that could change mid-run would make two nodes of one execution disagree about what year it is. A bad value stops the server (§7) rather than silently defaulting — a wrong fiscal start is a wrong number in every report the deployment produces.

### 3.22 Published endpoints

The timeout bounds for a released pipeline published as a `GET` endpoint at `/api/<category>/<version>/<path…>` (round 074, re-rooted by 172's R-EP5). `timeout-default-seconds` is what a publish that names no timeout is stored with. The min/max pair clamps requested values at publish time **and clamps the stored value again when serving**. Retuning the bounds therefore changes the wait budget of existing endpoints after restart without rewriting their rows. The wait expiring returns `202`; it does not cancel the pipeline execution, which keeps its own executor deadlines.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.endpoints.timeout-default-seconds` | `30` | Timeout a publish that omits one is stored with |
| `datapipelines.endpoints.timeout-min-seconds` | `1` | Lower clamp for a published endpoint's timeout |
| `datapipelines.endpoints.timeout-max-seconds` | `300` | Upper clamp, and the longest a serve may block before it answers `202` and leaves the execution running |
| `datapipelines.endpoints.key-request-budget.window-seconds` | `60` | (#224) The fixed window of the serve path's per-key request budget — at most `max-requests` requests per window for EVERY `api_caller` key, then `429 rate_limit.exceeded` with `Retry-After` (rest-api §19). Per instance: N replicas admit N × `max-requests` (the same per-instance note as the login limiter, auth §11.5) |
| `datapipelines.endpoints.key-request-budget.max-requests` | `60` | (#224) The per-key request ceiling per window. `0` turns the budget off — and with it the lake family's endpoint publishing, because the lake's endpoints pay S3 egress per run and are published only when a budget stands behind them |

**There is deliberately no `page-rows-max` key here.** The `DP-Result-Page-Rows` request header is **one contract** across a published endpoint and `POST /pipelines/{id}/execute`, and both clamp it to `datapipelines.result.page-max-rows` (§3.5). A second key that had to equal the first would be a second authority for one bound, and an operator who retuned one and not the other would get two different clamps on one documented header.

### 3.23 Environment and posture

The two variables an organisation sets (round 075). [Environments](environments.md) is the page written for the DevOps engineer who deploys this; this section is the key reference.

**The environment's NAME belongs to the org; the POSTURE belongs to the product.** Organisations have `dev`, `qa`, `uat`, `perf`, `sandbox-eu`, `prod` — any names, any count — and the product must never branch on one: a single-server user honestly writes `prod` as their label, and the first `if (env == "prod")` anywhere locks them out of authoring on the only server they have. What the product branches on is a **closed, two-valued posture** with the documented semantics below, and the org maps each of its environments onto one.

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.env` | `DATAPIPELINES_ENV` | `local` | The org's label for this deployment. Grammar: `[a-z0-9][a-z0-9_-]{0,31}` — lowercase letters, digits, `_` and `-`, at most 32 characters. It is the boot line's identity and the `source_env` a promotion receiver records; **nothing branches on it** (pinned by a guard test) and it is deliberately not on `/info`, which is permitAll |
| `datapipelines.posture` | `DATAPIPELINES_POSTURE` | (none) | The product's stance: `development` or `hardened`. **There is no shipped default.** §7 supplies `development` only when `datapipelines.env` is `local`; any other environment with no posture refuses to start, naming both variables — explicit beats guessed |
| `datapipelines.demo` | `DATAPIPELINES_DEMO` | (empty) | Comma-separated sample-data families selected by the launcher: `nyc`, `trade`, `lake`. `app.sh` uses this selection to choose Compose profiles, mounts and bootstrap file paths; the JVM itself logs the parsed names and refuses a non-empty selection under `hardened`. Setting this property alone on a bare jar does not download files or provision demo databases: configure the bootstrap paths and registered datasources separately. Empty = no launcher-selected demo families. |

#### The posture table (normative)

| Rule | `development` | `hardened` |
|---|---|---|
| `datapipelines.deployment.authoring-enabled` default | `true` | **`false`** — a promotion receiver; explicitly settable either way |
| `datapipelines.demo` | allowed | **refused** at boot |
| local bootstrap password (`datapipelines.auth.local.bootstrap-password[-hash]`) | allowed | **refused** at boot. Local accounts themselves stay allowed — a seeded credential is what has no place in a hardened deployment |
| loopback metadata DB / Redis | allowed | **refused** at boot |
| passwordless Redis off loopback (`datapipelines.redis.password` empty, host not loopback) | WARN (`config.redis_no_password`) | **refused** at boot, naming the key and the host (#189) |
| `datapipelines.auth.cookie-secure` default | derived from `base-url`'s scheme | **`true`** |
| OIDC | optional | **required unless `DATAPIPELINES_AUTH_ALLOW_LOCAL_ONLY=true`** (an explicit acknowledgement, logged) |
| boot line | `event=config.posture env=<env> posture=development authoring=on demo=nyc,trade` | same shape |

#### The posture IS the Spring profile

`spring.profiles.active` is **derived from `DATAPIPELINES_POSTURE`** in `application.yml`, and `application-development.yml` / `application-hardened.yml` carry the posture's non-secret defaults (the first two rows above). One variable therefore gives every loader — Compose, a bare `java -jar`, systemd, Kubernetes, ECS, Nomad — the right defaults, and nothing the product needs lives only in a launcher. Setting `SPRING_PROFILES_ACTIVE` yourself is unnecessary; a value that disagrees with the posture refuses startup (§7) rather than silently loading one posture's defaults while every posture RULE judges the other. 075 renamed the `dev` profile to `development`; a deployment still asking for `dev` is refused, not ignored.

#### Nothing lives only in a compose file

The contract is environment variables, in **two files**: `deploy/env/defaults.env` (tracked — every non-secret variable this build binds, with the value it ships) and `deploy/secrets.env` (git-ignored — every credential, plus every override that belongs to this deployment). `deploy/secrets.env.example` is the template for the second and names every variable you may set. `scripts/compose-env-audit.sh` — which runs on every `./gradlew build` — fails when those files, `deploy/compose.yml` and `application.yml` disagree about which variables exist or what they default to, and when a variable is declared in both tracked files or in neither. Settings are tracked in the repo; secrets never are.

### 3.24 Lake datasource engine limits (dp-lake)

A `LAKE` datasource runs its queries **on the app's own box** — DuckDB is embedded in the app process, so its memory, threads and spill disk are operator knobs, not someone else's infrastructure. These are **datasource properties** (`properties.dialect.*`, set per datasource through the REST API or a bootstrap datasources file — [Datasources §12.1](datasources.md)), **not** `datapipelines.*` keys: they do not belong in `application.yml` or an env file, they are validated per key at save time (an unknown key or a bad value is refused), and a change takes effect at the datasource's next pool build, with no app restart. The existing execution bounds apply unchanged beside them: the result row cap and `node-query-timeout-seconds`.

**Scope: one engine per datasource pool, shared by its connections (152).** Each LAKE datasource pool build opens ONE embedded DuckDB instance that every pooled connection joins ([Datasources §5.2](datasources.md#52-pool-lifecycle)); the three properties below are applied once to that instance and are ONE budget for the pool — `properties.hikari.maximumPoolSize` does not multiply them, and concurrent queries on the datasource share them. Size the box for `memory_limit` × the number of LAKE datasources on the instance (each has its own engine), with headroom for a retiring pool's engine while it drains after a datasource save. The engine's file cache lives with that instance, so a warm lake stays warm across HikariCP's routine connection replacement; a datasource save or a lake-table registration rebuilds the pool and starts a fresh engine, cold.

| Datasource property | Default | Semantics |
|---|---|---|
| `properties.dialect.memory_limit` | `datapipelines.duckdb.memory-limit` if set, else 25 % of the container's memory, clamped to 64 MiB – 4 GiB | The engine's memory budget, e.g. `512MB` or `2GB` (B, KB, MB, GB, TB — a `%` is refused). Precedence: this per-datasource property, if set, always wins; otherwise the deployment's operator default (§3.25) applies verbatim; otherwise the default reads the container limit the container-aware JVM reports (`OperatingSystemMXBean.totalMemorySize`, the same figure DuckDB's own default reads), takes a quarter of it because the engine shares the box with the JVM, and **hard-caps at 4 GiB** so a large host does not hand one lake query enough to evict the app itself. An explicit value — this property or the operator default — is not capped |
| `properties.dialect.threads` | (engine default — no statement emitted) | The engine's worker threads — a positive integer, e.g. `4`. Unset means DuckDB chooses (its own default tracks the box's cores) |
| `properties.dialect.temp_directory` | Anonymous in-memory URL: unique `datapipelines-lake-<UUID>` directory under absolute JVM `java.io.tmpdir` (normally `/tmp`); named/file URL: retain engine setting | Where oversized operators spill, e.g. `/data/spill`. Validation requires an absolute path with no quotes, backslashes, whitespace or control characters. Provision a writable directory backed by sufficient disk; the validator does **not** verify that the path is inside a mounted data volume or check its available space |

For the supported anonymous in-memory URLs (`jdbc:duckdb:` and `jdbc:duckdb::memory:`), the default spill directory belongs to one engine initialization: pooled connections share it, while independent in-memory pool generations receive different paths. DuckDB creates it lazily when spilling and removes it on normal instance shutdown. The parent directory must exist, be writable by the application user, and have enough disk space for concurrent queries; the Helm chart provides disk-backed `/tmp`. A bare JVM can select the parent with `-Djava.io.tmpdir=/writable/path`. An explicit override is used exactly as supplied: dedicate that directory to the engine, keep unrelated files out, and avoid sharing it between independent engines or overlapping generations. Named in-memory and file-backed URLs can join an existing JVM-wide engine; when no override is supplied, its current spill setting is left untouched. Configure a writable path explicitly for those URLs, consistently across every pool sharing that engine. A crash or a driver that refuses shutdown can leave spill files behind; reclaim them only after the owning process has stopped.

**`threads` is deliberately left unset, and the measurement is why — though not the way the
hypothesis expected (108 §C).** In-process DuckDB shares the JVM's CPUs with the executor, so the
obvious guard is to cap the engine's worker threads; `max(2, cores/2)` was the proposal. Measured on
a 10-core box as the median of 5 PAIRED runs (each pair times the scan alone and then immediately
under load, so a drifting box moves both numbers):

| `threads` | 2 staging drains | 8 staging drains |
|---|---|---|
| unset (all 10) | 1.07× slower | 1.11× slower |
| `SET threads = 5` | 0.92× | 1.13× |

**A 0.92× is a scan that ran FASTER under load than alone, which is not a result — it is the noise
floor.** On a box shared with other work the contention effect is smaller than the measurement's own
variance, and an earlier single-run shape produced swings from 1.13× to 1.82× for the same cell. So
the honest reading is: **no evidence for a thread cap, and none against one either** — the effect
this knob would manage is not resolvable here. The default therefore stays unset (DuckDB's own
scheduler handles oversubscription), an operator who needs the engine bounded sets `threads`
explicitly, and anyone who wants to settle it should re-run
`scripts/measure/03-pressure.sh` on a dedicated box.

What the runs DO agree on: a lake read on a busy box loses on the order of 10 % or more, and no
`threads` value observed changed that.

Every LAKE engine initialization emits `SET memory_limit = '<explicit-or-default>'` (there is always a budget) and `SET preserve_insertion_order = false`. These apply to the shared instance for the pool; opening another pooled connection does not create a separate memory budget. Preserving insertion order is not configurable through the datasource properties.

### 3.25 DuckDB extension directory (dp-lake)

A `LAKE` datasource whose data is on S3 needs DuckDB's `httpfs` and `aws` extensions, and the Iceberg catalog kinds additionally need `iceberg` (which itself requires `avro`). Where those binaries come from is this key. It is an **operator-level** key — one value for the whole deployment, restart-to-change — deliberately NOT a per-datasource `properties.dialect.*` entry: which extension files the engine may load is a deployment security posture, not row data.

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.duckdb.extension-directory` | `DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY` | (empty) | A directory holding pre-populated DuckDB extensions in the engine's own layout (`<dir>/v<core-version>/<platform>/<name>.duckdb_extension`). Must be an absolute path with no quotes, backslashes, whitespace or control characters (it is interpolated into a `SET` statement; a value that would need escaping is refused, matching the `temp_directory` grammar) |
| `datapipelines.duckdb.memory-limit` | `DATAPIPELINES_DUCKDB_MEMORY_LIMIT` | (empty) | (153, #136) The deployment-wide default `memory_limit` every `LAKE` engine build gets when a datasource declares no `properties.dialect.memory_limit` of its own — likewise **operator-level**, restart-to-change, deliberately not a datasource property: it is a capacity decision about the box, not row data. Empty keeps §3.24's derived 25 %-of-container default exactly. A non-empty value is a DuckDB size string, the same grammar as `properties.dialect.memory_limit` (`512MB`, `2GB`, …; a `%` is refused), used **verbatim, with no cap** — the operator's own number. **Validated at startup**: an invalid value fails fast at context start naming this key and the grammar, rather than silently falling back to the derived default. Precedence is §3.24's per-datasource `properties.dialect.memory_limit` first, then this key, then the derived default |

**Operator instruction:** set `DATAPIPELINES_DUCKDB_MEMORY_LIMIT=8GB` in `deploy/secrets.env` (or the deployment's own env file) and restart the app — the value is read once at wiring, not per pool build, so every `LAKE` datasource without its own `properties.dialect.memory_limit` picks it up on its next pool build after the restart (a fresh cold engine; the first query after restart is cold, the next warm). Confirm it took effect over a `LAKE` connection with `SELECT current_setting('memory_limit')` (reports the exact configured value, e.g. `8.0GB`) or `SELECT * FROM duckdb_memory()` (the engine's own memory-accounting view, `docs/staging.md` §"Memory accounting" — its buffer manager budget reflects the same limit). Helm operators (`deploy/helm/`) set these two keys through the chart's `duckdb.memoryLimit` and `duckdb.extensionDirectory` values instead of an env file — the chart renders them as these env vars when non-empty (an empty value renders no entry, inheriting the image's bundled extension directory), and an `extraEnv` double mapping of either is refused at render time (#140, deployment.md §6.4).

**Set (the shipped image):** a lake with `catalog.kind` configured initializes its engine with `SET extension_directory = '<dir>'` followed by bare `LOAD`s — `LOAD httpfs; LOAD aws` for `catalog.kind: s3`, plus `LOAD avro; LOAD iceberg` for the Iceberg kinds — and **never an `INSTALL`**. The catalog adapter emits none when `catalog.kind` is absent; registered Iceberg tables have their own extension setup, described below. Loading the pre-populated files needs **no extension-download egress**; this does not remove the network access required by a remote catalog or its data. The published image bundles the four extensions for DuckDB core **v1.5.5** under `/opt/duckdb/extensions/v1.5.5/<platform>/`, the `<platform>` following the image build's architecture (`linux_amd64` or `linux_arm64` — the Dockerfile maps BuildKit's `TARGETARCH`; hard-coding one architecture leaves the other LOAD-only against an empty directory, a pool-init failure at connect — found by the 089 live gate), and exports this variable from the Dockerfile; `deploy/compose.yml` defaults to the same path. The bundle adds ~108 MB uncompressed to the image (~39 MB downloaded at build). **A hardened dp-lake deployment with the bundled directory needs no egress to `extensions.duckdb.org` at all.**

**Unset (a bare `java -jar`, a developer machine):** when `catalog.kind` is configured, the adapter emits explicit `INSTALL`+`LOAD` pairs for its required extensions. A missing extension is downloaded into DuckDB's extension cache; a fresh ephemeral filesystem has no previous download to reuse. Independently of `catalog.kind`, a registered Iceberg table's view initialization loads `avro` and `iceberg`, using the same configured-directory/`INSTALL` choice. A lake with neither `catalog.kind` nor registered Iceberg tables emits no extension setup statements.

**Version coupling:** the bundled binaries are valid for exactly one DuckDB core version. The pinned `duckdb_jdbc` is `1.5.5.1`, whose bundled core reports **v1.5.5** — the directory's `v1.5.5` component is the CORE version, not the JDBC patch version. A `duckdb_jdbc` upgrade must re-download all four extensions and rename the directory accordingly (the Dockerfile pins both in one `ARG`).

### 3.26 Datasource pools

How long a **retired** connection pool may keep connections out before it is closed regardless ([Datasources §5.2](datasources.md#52-pool-lifecycle), round 094). A datasource that is edited or deleted has its pool taken out of the live map at once and soft-evicted; the pool itself is closed when the statements already running on it finish — or at this ceiling, whichever comes first, so a genuinely hung statement cannot pin a deleted datasource's pool forever. Each hard close logs one WARN naming the datasource and the connections it took down, and increments `datapipelines.datasource.pool.hard_closed` ([Observability §4.1](observability.md#41-metric-naming)).

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.datasources.retire-ceiling-seconds` | `derived` | Positive seconds a retired pool may hold connections before the reaper hard-closes it. **`derived` means `datapipelines.executor.node-query-timeout-seconds` + 30** (so `90` with the shipped defaults). This derivation uses only the flat timeout: it does not include longer per-dialect, datasource, pipeline or node overrides. Set an explicit ceiling when those longer-running statements must finish while a retired pool drains |
| `datapipelines.datasources.file-roots` | (none) | Comma-separated absolute directories a **file-backed** in-process datasource (H2 `file:`/bare path, SQLite, DuckDB file) may live under (#186). **Empty = no file-backed datasource is registrable by anyone, super admin included** — in-process engines run their SQL inside the server's JVM, so their files live only where the deployment declares. Each root must exist and be a directory or startup refuses, naming the key. Registration normalizes the path (symlinked parents AND the file itself resolved — a final-component symlink aimed outside every root, or a dangling one, is refused; the parent must exist; `..`, `~` and URL-encoded separators are refused before normalization is trusted) and requires it under a root — re-checked at every pool build, so a file moved out of its roots refuses its next build ([Datasources §4.2A](datasources.md#42a-connect-time-setup-and-typed-dialect-configuration)). The demo's sample datasources need the read-only sample volume: `app.sh --demo …` exports `/srv/sample` for you ([Datasources §9](datasources.md#9-validation-rules)) |

**Why the default is derived rather than a number.** The ceiling and the node query timeout are the same fact seen twice: a deployment that raises `node-query-timeout-seconds` to 600 and leaves a literal `90` here would start hard-closing pools out from under statements that are still legitimately running, and nothing would tell it. Deriving makes the two move together; an explicit value opts out.

### 3.27 Mail

Outbound mail ([Auth §5A.8](auth.md#5a8-mail-the-welcome-mail-and-the-new-user-notice)) — the SMTP transport for welcome/password-reset mail, new-user notices to sys-ops, and scheduler notifications when separately enabled (§3.29). It uses host, port, username, password and STARTTLS; Postmark's message-stream header is optional. SMTP connect time is bounded to 10 seconds and read/write time to 30 seconds in `SpringMailSender`; these bounds are code constants, not additional settings.

**Mail is enabled exactly when it is configured — there is no `enabled` key.** Mail is on iff `host` AND `from` are both set. A flag that could disagree with the fields it summarises is the YAML-boolean trap the house has already logged; "configured" is the only honest definition of "on" for an outbound adapter. Every half-configured shape refuses startup naming the field (§7).

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.mail.host` | (none) | SMTP host. Empty = mail off |
| `datapipelines.mail.port` | `587` | SMTP server port, validated in `1..65535`. The transport uses SMTP with optional required STARTTLS; setting port 465 does not enable implicit TLS. Use the port and STARTTLS mode your relay actually exposes. |
| `datapipelines.mail.username` | (none) | SMTP username. Non-blank enables SMTP authentication; blank disables it for a relay that accepts unauthenticated submission. With mail enabled under `hardened`, a non-blank username requires a non-blank password. |
| `datapipelines.mail.password` | (none) | SMTP password. Never logged, never in the properties' `toString`. Postmark: the server token |
| `datapipelines.mail.starttls` | `true` | STARTTLS on the submission connection — **required, not opportunistic**: `true` refuses a server that does not upgrade rather than falling back to plaintext. With mail enabled, the `hardened` posture refuses `false` (§3.23, §7). |
| `datapipelines.mail.from` | (none) | The sender — a bare address or `Display Name <address>`. Half of "enabled" |
| `datapipelines.mail.reply-to` | (none) | `Reply-To` on every message — the human a user writes back to. Empty = `from` |
| `datapipelines.mail.ops-to` | (none) | The sys-ops sink for the "New user" notice — one address or a comma list. Empty = no new-user notices, even with mail on. Set without `host` = refused (a sink nobody can reach is a misconfiguration, not a preference) |
| `datapipelines.mail.message-stream` | (none) | Postmark's `X-PM-Message-Stream` header. Set = added to every message; empty = nothing is added |

**Cross-key rule:** mail on requires `datapipelines.auth.base-url` (§3.4) — the welcome mail carries the login URL, which is built from it and never from a request's origin ([Auth §5.2](auth.md#52-clientregistration-bean-built-at-startup)).

**What the admin screen shows** follows from the derivation: with mail on, the create-user and reset-password actions no longer display the one-time password — it went to the user, and the screen says so with the send's outcome; with mail off they show it as before ([UI §4.12](ui-screens.md#412-admin-user-management-admin-scope-only)).

### 3.28 Script evaluation (7b, #7)

The budgets every script evaluation runs under — the save/release test suite of a script template, `templates_evaluate` / `POST /api/v1/templates/evaluate`, the editor preview, and the SCRIPT node (7c). ONE pool bean serves all of them: every SCRIPT node of every execution draws from the same slots the template surfaces use, and each row-mode batch and each declared invariant is its own evaluation taking one. The pool is the record's §4.3 bulkhead: at most `pool-size` evaluations run at once, and `pool-queue` bounds the evaluations ADMITTED in total (running plus waiting — an abandoned evaluation keeps its slot until its thread ends). Admission is immediate: a submission past the admission bound is refused at once, while an evaluation admitted just inside the bound waits for a running slot for at most its own wall clock — for a SCRIPT node, its deadline minus the cancellation grace, so the wait spends the node's budget. A submission past the bound, or a wait outliving that wall clock, is the record §4.3's pool-refusal (§13.18, 7c); it fails the node, and the executor's fail-fast (dag-executor §2) aborts the siblings.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.script.evaluate-timeout-seconds` | `10` | Positive evaluation wall-clock budget used by `templates_evaluate` and each script test case, with the separate abandonment grace below. Must not exceed `suite-timeout-seconds` (§7). A SCRIPT node instead derives its evaluation budget from its node deadline and cancellation grace |
| `datapipelines.script.suite-timeout-seconds` | `60` | Positive deadline checked **before starting each test case** in the save/release suite, starting after script/invariant compilation. It does not interrupt a case already running or reject an overrun after the final case; each evaluation retains its own budget and abandonment grace |
| `datapipelines.script.pool-size` | `4` | Positive cap on concurrently running script evaluations in this process; abandoned workers keep their running slot until their thread ends |
| `datapipelines.script.pool-queue` | `64` | Evaluations admitted in total, running plus waiting — never "the wait queue" (the bulkhead holds an abandoned evaluation's slot until its thread ends). Must be at least `pool-size` |
| `datapipelines.script.abandon-grace-seconds` | `30` | Grace past the wall clock before an evaluation is abandoned, counted (`script.evaluations.abandoned`) and logged at ERROR. Must be ≥ 1 (§7) |
| `datapipelines.script.max-input-rows` | `100000` | Row cap, two bounds by path. At `templates_evaluate` and in the save/release suite, the `rows` batch and each table input are capped before any evaluation (`pipeline.script.input_too_large`). At run, a `table`/`value` node counts each table input before loading it (same code); a `row` node streams its single input with no row cap of its own; and the same key bounds the declared invariants' read-back — the written outputs and rejects plus the table inputs (row mode adds the streamed input and, for a caller output, its buffered rows and rejects) TOGETHER, refused as `pipeline.script.invariants_too_large` (§13.18, 7c) |
| `datapipelines.script.max-value-bytes` | `1048576` | Cap on a value/object output (the record §4.3's value-cap code, §13.18, 7c) — a Context value is not a table |
| `datapipelines.script.max-string-bytes` | `1048576` | Positive UTF-8 byte cap on a string checked by the script type gate, including typed inputs and returned rows/values (the same §4.3 code, with the row number where applicable) |
| `datapipelines.script.max-depth` | `100` | Expression depth bound; also the JS stack-frame bound (plus four prelude frames, capped by `js.max-stack-frames`) — counted at every evaluate entry and exit by the engine itself: nested evaluation, non-tail lambda recursion included; set-level work over hundreds of rows evaluates (the 7a `isParallelCall` leak is gone, #260); a tail-recursive lambda is trampolined by the library and the wall clock is its bound (the engine's measured bounds, dag-executor §5.3) |
| `datapipelines.script.js.isolate-dir` | `empty` | Absolute directory containing `js-isolate.jar`; must be one entry of `loader.path` (system property) or `LOADER_PATH` (environment). No class-path mutation. Unset and no isolate on the class path disables JavaScript, reported once at INFO; JSONata remains available. |
| `datapipelines.script.js.max-heap-mb` | `256` | Per-Context heap, MiB; SOFT. All concurrent Contexts share the isolate heap, outside the JVM heap. |
| `datapipelines.script.js.isolate-memory-mb` | `empty` | Unset → `pool-size × max-heap-mb + 256` MiB (1280 at defaults). Explicit value must be at least 32 MiB and strictly larger than `pool-size × max-heap-mb`; a derived byte budget that overflows is refused by name. |
| `datapipelines.script.js.max-statements` | `100000000` | Hard guest-statement ceiling; internal prelude excluded. |
| `datapipelines.script.js.max-ast-depth` | `1000` | Parse-time nesting ceiling, including runtime eval. |
| `datapipelines.script.js.max-stack-frames` | `1000` | Guest stack-frame ceiling; minimum of this and `max-depth + 4` prelude frames. |
| `datapipelines.script.js.max-output-bytes` | `1048576` | Each console stream cap; at least 4096 bytes. Excess output refuses with resource_limit / OUTPUT; returned console lines are separately capped at 200 × 2000 chars. |
| `datapipelines.script.js.max-result-mb` | `64` | Serialized result budget, MiB, at least 1; measured before host parsing. |
| `datapipelines.script.js.max-input-mb` | `64` | Serialized input budget, MiB, at least 1; refused before the Context starts. |

**Operator instruction:** Helm operators (`deploy/helm/`) set these nine keys through the chart's `script.js.*` values instead of an env file (deployment.md §6.4) — all nine empty by default, an empty value rendering no env entry (the image's own isolate directory and the app's derived isolate memory stay in force), an `isolateDir` outside the image's launcher-path entries refused at render time, and an `extraEnv` double mapping of any of the nine refused like the DuckDB keys.

**Removed keys (script-node-rename lane, owner ruling 2026-10-20):** the nine budgets lived at `datapipelines.transform.*` before the TRANSFORM→SCRIPT node-type rename. There is no alias: a `datapipelines.transform.*` key binds nothing, and startup refuses BY NAME (§7, `checkRemovedTransformKeys`) telling the operator to rename it. This is the §3.17 rule again — a removed key is refused for being set at all, value notwithstanding.

### 3.29 Scheduler (#9)

The scheduler's knobs ([Scheduler](scheduler.md)). Every instance serves the schedule REST routes; `enabled` decides whether THIS instance also dispatches. The library underneath (db-scheduler) reads its own `db-scheduler.*` prefix, and `application.yml` feeds it from these keys (§5) — the library's own defaults (ten threads, a 30-minute shutdown wait) never apply, and `db-scheduler.*` is not an operator surface. Each bound below is enforced when the keys bind: a value outside it refuses startup, naming the key.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.scheduler.enabled` | `true` | This instance dispatches. `false` is **API mode**: schedules can be created, edited and Run now recorded here, but nothing fires from this instance — another instance with `true` runs them. Run at least one dispatching instance, or nothing ever fires |
| `datapipelines.scheduler.threads` | `2` | Worker threads. A run task holds one only while it admits and launches a run, never for the pipeline's runtime. ≥ 1 |
| `datapipelines.scheduler.polling-interval-seconds` | `10` | How often due tasks are polled. ≥ 1 |
| `datapipelines.scheduler.heartbeat-interval-seconds` | `30` | A running task's heartbeat; a task whose instance misses six in a row is revived elsewhere, and a run claimed longer ago than six heartbeats with no execution becomes `unknown`. ≥ 1 |
| `datapipelines.scheduler.tick-interval-seconds` | `10` | Fixed delay between completed dispatcher, reconciler and notification-sender tasks. This is not a maximum dispatch-lateness guarantee: task duration, polling and worker availability also affect when the next tick runs. ≥ 1 |
| `datapipelines.scheduler.max-concurrent-runs` | `4` | Scheduled executions in flight on this instance at once. A run that finds no slot retries every 30 s within its lateness window, then ends `not_started` / `capacity`. ≥ 1 |
| `datapipelines.scheduler.lateness-seconds` | `600` | The admission window: how long after its occurrence (after its recording, for a catch-up or manual run) a run may still start. 1–86 400 |
| `datapipelines.scheduler.catch-up-max-age-seconds` | `86400` | How old the latest missed occurrence may be for `missed_run_policy: latest` to still run it once. 1–604 800 |
| `datapipelines.scheduler.shutdown-wait-seconds` | `5` | On shutdown, how long to wait for launches in progress to reach "started" before the executions are drained; also the library's shutdown wait. 1–15 — Spring's per-phase stop timeout is 30 s and the execution drain needs its own 20 s |
| `datapipelines.scheduler.min-interval-seconds` | `300` | A cron whose consecutive occurrences come closer than this (in the 14 days after the save) is refused, `schedule.validation.interval_too_short`. ≥ 60 |
| `datapipelines.scheduler.max-schedules-per-workspace` | `100` | Live schedules per workspace; one more is `schedule.limit.per_workspace`. ≥ 1 |
| `datapipelines.scheduler.notifications.max-recipients` | `20` | Maximum normalized recipients accepted when creating or updating one schedule's notification settings. 1–100 at binding |
| `datapipelines.scheduler.notifications.enabled` | `false` | Scheduler notification mail is **off unless `true`** (owner ruling 2026-10-03, #442). `true` needs the shared mail transport (`datapipelines.mail.host` and `.from`, §3.27) and `datapipelines.auth.base-url` (§3.4, the link every notice carries), or startup refuses, naming the keys. With `false`, saved recipients and events are kept, nothing is queued and nothing leaves, and the schedule form and details say so. This is the scheduler's flag; the mail transport itself has no `enabled` key (§3.27). See [Deployment §8.3](deployment.md#83-upgrade-procedure-recommended) before enabling on a mixed-version fleet |
| `datapipelines.scheduler.notifications.recipient-domains` | *(empty)* | The recipient-domain allowlist, comma-separated. An entry is a domain — `example.com` admits exactly that domain — or a subdomain wildcard — `*.example.com` admits every domain ending in `.example.com`, not `example.com` itself (list both to admit both); compared lower-cased; anything else refuses startup. Non-empty: a recipient outside it is refused at save (`schedule.validation.notifications_invalid`, reason `domain_not_allowed`) and a stored one is re-checked at send (`failed` / `domain_not_allowed`). **Empty under the `development` posture: nothing sends** (delivery `off` / `no_allowlist`). Empty under `hardened`: no domain restriction |
| `datapipelines.scheduler.notifications.max-attempts` | `3` | Tries per delivery for a send that provably never reached the relay (connection refused, an SMTP 4xx). A send that may have been accepted is never retried automatically — it ends `uncertain`. 1–10 |
| `datapipelines.scheduler.notifications.retry-backoff-seconds` | `30,120,600` | The wait after attempt 1, 2, 3 … before the next one; past the list's end its last entry repeats, so raising `max-attempts` needs no edit here. At least one entry, each 1–86 400 |
| `datapipelines.scheduler.notifications.batch-size` | `20` | Maximum deliveries the sender claims and settles per tick. The sender task uses `tick-interval-seconds` as its fixed delay and runs on a dispatching instance only. 1–200 |
| `datapipelines.scheduler.notifications.backlog-max` | `1000` | Hard cap on pending deliveries per deployment, with serialized admission (enqueue and resend share one transaction-scoped advisory lock). Resend at the cap is refused with `409 schedule.notification.backlog_full`. Beyond it a new delivery is written `failed` / `backlog_full` — visible on the run, never dropped silently. ≥ 1 |

Scheduled runs keep the instance's execution timeout (`datapipelines.executor.execution-timeout-seconds`) and ask for the maximum result TTL (`datapipelines.result.ttl-max-seconds`); there is no scheduler-specific budget in v1. One Spring Boot key is set with them: `management.health.db-scheduler.enabled: false` — the library's health indicator reports DOWN on an API-mode instance (it never starts there), and a dispatcher stall must not restart a pod; watch the scheduler's metrics instead ([Observability §4](observability.md#4-metrics)).

### 3.30 Parameter engine (#194)

The parameter engine's limits ([the design record](superpowers/specs/2026-09-21-parameter-engine-design.md) §11). The save-time validator reads the collection, expression and option caps (every one enforced BEFORE the set's graph is built, and the collection caps before their members are even read), and the shared value validator reads `max-regex-steps` and `max-input-length` — the latter is the ENGINE's `max_length` default for a STRING or BINARY `INPUT` that declares none (a pipeline parameter keeps none, record P34). The evaluate keys (the timeouts, the selector bulkhead, the binds and the response budget) are read by the runtime. Each bound below is enforced at boot (§7) and again when the keys bind (`ParametersProperties` → `ParametersConfig`), naming the key.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.parameters.max-parameters-per-set` | `64` | Parameters in one set; also caps an individual `depends_on` list before its members are read. 1..256 |
| `datapipelines.parameters.max-options-per-selector` | `200` | Options in a saved constants list or returned by one selector. Runtime reads at most the cap plus one to detect excess options. 1..10000 |
| `datapipelines.parameters.max-multi-bind-values` | `1000` | Values in one MULTI selection, checked on saved defaults and submitted evaluation values. 1..1000 |
| `datapipelines.parameters.max-binds-per-statement` | `2000` | Placeholders in one rendered selector statement, every expanded one counted. 1..2000 |
| `datapipelines.parameters.max-option-value-chars` | `1024` | Characters in one option value. 1..65536 |
| `datapipelines.parameters.max-option-label-chars` | `256` | Characters in one option label. 1..4096 |
| `datapipelines.parameters.max-input-length` | `4096` | The `max_length` an INPUT of type STRING or BINARY gets when it declares none. An explicit `max_length` replaces this fallback; it is not a global ceiling on explicit declarations. 1..65536 |
| `datapipelines.parameters.max-expression-depth` | `16` | Nesting depth of a hidden/disabled expression. 1..64 |
| `datapipelines.parameters.max-expression-nodes` | `128` | Operators in one expression. 1..1024 |
| `datapipelines.parameters.max-regex-steps` | `100000` | The read budget of a pattern constraint's match. 1000..10000000 |
| `datapipelines.parameters.evaluate-timeout-seconds` | `30` | Wall clock for one evaluate. 1..300 |
| `datapipelines.parameters.selector-query-timeout-seconds` | `10` | Ceiling on a selector's JDBC statement timeout. A datasource's lower `query_timeout_seconds` tightens it; the evaluate wall-clock deadline still applies. 1..evaluate-timeout-seconds |
| `datapipelines.parameters.max-concurrent-selector-queries` | `4` | Selector statements running at once per JVM, shared across evaluations. An abandoned JDBC worker retains its slot until its driver returns, so timeouts cannot create unbounded workers. 1..32 |
| `datapipelines.parameters.max-waiting-selector-queries` | `64` | Selector statements admitted beyond the running ones per JVM. Once running plus waiting capacity is full, admission refuses immediately. `0` permits no waiting capacity. 0..1024 |
| `datapipelines.parameters.max-evaluate-response-bytes` | `4194304` | An evaluate response's byte budget. 65536..67108864 |

No options cache in round one (`selector-cache-ttl-seconds` is deliberately absent — the record's §11: authority-aware keys and invalidation are its price, and it lands when a measured query count says it must).

Retention of the evaluation history (`parameter_evaluations` and their `parameter_evaluation_queries`, #376) has no key of its own — the dashboard refreshes' precedent: finished records are deleted by the execution-event retention step, on the same hourly tick and the same cutoff as `datapipelines.executions.event-retention-days` (§3.11, 7 days), one bounded batch per tick; a record left `RUNNING` past `evaluate-timeout-seconds` plus a fixed one-minute margin is closed `INCOMPLETE` on that tick — see [Metadata DB §8.1/§8.5](metadata-db.md). A retention key of its own would be an additive change (the workspace spec's §11.5).

### 3.31 Web request limits

The request-body cap (pipeline-contract §13.21, [REST API §4.2](rest-api.md#42-error-envelope)) applies to BOTH JSON surfaces — `/api/v1` and `/mcp`. The servlet filter runs before authentication: an over-cap `Content-Length` is refused unread, while a body without that declaration is counted as downstream code consumes its wrapped stream and is refused on a read beyond the cap. The usual response is `413 request.body_too_large`; the screenshot upload exception is stated in the table. The filter does not buffer the body. Jackson's request `StreamReadConstraints` — nesting depth **100**, string length **4,000,000** characters, number length **1000** digits — are stated explicitly on the request mappers of both surfaces. A within-cap body that exceeds those parser limits is refused as a 400; routes parsing their own text body use a constrained copy of their domain mapper (pipeline-contract §13.21 records the scope). These parser limits are constants, not configuration keys.

The bound below is enforced at boot (§7, `checkRequestLimits`) and again when the key binds (`RequestLimitsProperties`).

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.web.max-request-bytes` | `2097152` | Request-body limit in bytes (2 MiB) on `/api/v1` and `/mcp`, refused with `413 request.body_too_large` (`details.limit_bytes`). GET, HEAD and OPTIONS bypass the filter. The visualization-test screenshot upload route uses its fixed 4 MiB limit and `visualization.test.screenshot_too_large` instead; this key does not change that limit or UI form limits. 65536..67108864 |

Tomcat's swallow budget is the cap's counterpart on the connection: `server.tomcat.max-swallow-size` (default `2MB`, [§3.13](#313-server)) bounds how much of a REFUSED body the container drains before closing it, so an abandoned over-cap upload costs the connection, never the server.

### 3.32 Persistence batching (#266)

The batching writers in front of three stores ([DAG Executor §10](dag-executor.md#10-sse-event-integration), [Auth §10](auth.md)): the audit log (`audit_log`), the durable execution-event record (`execution_events`) and the one-hour Redis replay log. The audit log's writer **ships switched off** (`audit.enabled`, below): by default only the two execution-event stores are batched. Callers await a bounded durability attempt; an execution-event write that remains unresolved at its wait bound is reported as `indeterminate`, not claimed committed. The execution row itself (`pipeline_executions`: the RUNNING insert and the terminal UPDATE) is never batched. Each store gets its OWN writer and admission accounting. The byte counts are sink-provided item sizes, not measured heap, and each empty writer may admit one item larger than its byte bound; do not treat three times `queue-max-bytes` as an absolute process-memory cap. Every bound is validated when the keys bind (`PersistenceProperties` → `BatchingConfig`), naming the key.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.persistence.enabled` | `true` | `false` restores the pre-#266 path: every event row and replay-log entry — and every audit row, whatever `audit.enabled` says — written by its own caller, one statement each |
| `datapipelines.persistence.audit.enabled` | `false` | Whether audit rows go through the audit writer at all (only while `enabled` is also true). Off, every audit row is the direct INSERT — on the caller's transaction when it holds one, on its own otherwise ([Auth §10.1A](auth.md#101a-delivery-durable-before-log-returns-266)). Ships off by measurement (#266 C): at 500 and 5,000 audit rows/s the writer never formed a batch (mean size 1.0–1.1) and raised the rows' p99 (+4 ms at 100 concurrent executions), while event batching earned ×2.2–3.5 |
| `datapipelines.persistence.batch-max-events` | `200` | Items per committed batch at most. 1..7000: one replay-log batch runs inside one Redis Lua script, and Lua refuses to unpack more than ~7,990 values (the script also pushes each execution's entries in chunks of 1,000) |
| `datapipelines.persistence.batch-max-bytes` | `1048576` | Bytes per batch at most (1 MiB); one item larger than this is its own batch. ≥ 1 |
| `datapipelines.persistence.linger-ms` | `0` | How long a BUSY writer (≥ 2 items queued behind its previous commit) waits for a fuller batch. Never applies at idle: a lone item — including a sequential caller's next one — is written at once. ≥ 0 |
| `datapipelines.persistence.queue-max-events` | `10000` | Items admitted and not yet finished (queued or inside a commit), per writer. Full → an awaited caller waits up to `record-max-wait-ms`, then writes its own row. ≥ 1 |
| `datapipelines.persistence.queue-max-bytes` | `33554432` | Byte admission bound (32 MiB) per writer. ≥ 1. One item larger than this is admitted when the writer has no outstanding items, so this is not an absolute memory cap; subsequent admissions wait until capacity is released |
| `datapipelines.persistence.record-max-wait-ms` | `2000` | How long a caller waits for room and then for its batch before it takes its item back and writes it itself (the direct path). The execution-event emitter waits at most this long again for that direct write — or for an item already inside a commit — and then records the outcome as `indeterminate` and moves on: a hung store never hangs an execution, though each event it emits meanwhile pays the bound ([DAG Executor §10.1](dag-executor.md#101-how-an-emitted-event-becomes-durable-266)). ≥ 1 |
| `datapipelines.persistence.writers` | `4` | Writer threads per store (= partitions; one execution's, or one key's, items always share one). It also sizes the `dp-event-persist` pool that the execution row's RUNNING insert, its terminal UPDATE and every writer's direct fallback share — so `1` serialises all three behind one fallback whose JDBC call outlives its bound (the bound stops the wait, not the driver call). ≥ 1 |
| `datapipelines.persistence.shutdown-drain-ms` | `10000` | How long the shutdown drain flushes — all writers in parallel, after the web server's graceful drain — before it reports what it could not write. 0..25000 (inside Spring's 30 s shutdown phase) |

What these keys never change: an audit row written inside the caller's transaction joins that transaction (it commits and rolls back with it; [Auth §10](auth.md)); per-key and per-execution order; the retry dedup (the event record's `ON CONFLICT (execution_id, event_id)`, the replay's first-copy-per-event-id); the loss window, which exists only for the non-awaited `submit` path — no production caller yet ([Observability §7](observability.md)).

---

### 3.33 Visualizations and dashboards (#10)

The bounds of the two dashboard documents' strict readers ([Dashboards §2](dashboards.md), the [dashboard implementation spec](superpowers/specs/2026-09-28-dashboard-implementation-spec.md) §3). Each collection bound is checked BEFORE the collection's members are read, so a document cannot make the read work in proportion to its own size; a document over one is refused `visualization.validation.body_invalid` / `dashboard.validation.body_invalid` with `details.reason` `too_many` (or `too_large` for the configuration) and `details.config_key` naming the key. The runtime's keys are in §3.34. The test-session TTL uses the flat `session-ttl-minutes` key — the shipped key grammar is exactly two levels (`datapipelines.visualization.<key>`); §3.33's row is its definition. Each bound below is enforced at boot (§7) and again when the keys bind (`VisualizationProperties` → `VisualizationConfig`), naming the key.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.visualization.max-visualizations-per-dashboard` | `50` | Visualization occurrences in one dashboard — refused `too_many` before any is read. The SAME value is also the transfer families' aggregate ceiling (L1c-c): it caps each whole export/import envelope array (`visualizations` bundle, `templates` closure) and each whole promotion batch arm (`visualizations`, `dashboards`), refused whole before any member binds — on top of this per-document bound, not instead of it, so two individually valid dashboards can together exceed it. 1..500 |
| `datapipelines.visualization.max-cases-per-visualization` | `20` | Test cases in one visualization's `tests.cases` — refused before any case is read. 1..200 |
| `datapipelines.visualization.max-fixture-rows-per-case` | `1000` | Fixture rows in one test case, all inputs together — refused before any row is read. 1..100000 |
| `datapipelines.visualization.max-config-bytes` | `262144` | A visualization's `config` (the renderer-native configuration) in bytes as serialised, 256 KiB — refused `too_large` before any rule reads it. 1024..2097152 |
| `datapipelines.visualization.max-bindings-per-visualization` | `64` | Entries in one visualization's `bindings` — refused before any binding is read. 1..1024 |
| `datapipelines.visualization.max-inputs-per-visualization` | `8` | Named inputs of one visualization — refused before any input contract is read. 1..64 |
| `datapipelines.visualization.max-columns-per-input` | `256` | Columns of one input contract — refused before any column is read; the fixture check is rows × this. 1..4096 |
| `datapipelines.visualization.session-ttl-minutes` | `60` | A visualization test session's TTL in minutes (the spec's §11.2, owner-confirmed 2026-09-28): the run's `expires_at`, the preview capability's deadline, and the latest deadline the submit-minted upload capability may carry. 1..1440 |

A renderer configuration also nests at most 32 levels and a literal parameter value at most 2 (a scalar, or a list of scalars) — constants, like the request mappers' Jackson constraints (§3.31), refused `too_deep` by a bounded walk that never uses the host stack.

---

### 3.34 The dashboard runtime (#10 L2)

The server side of a dashboard refresh ([Dashboards §5](dashboards.md), the [implementation spec](superpowers/specs/2026-09-28-dashboard-implementation-spec.md) §9.4–§9.6): how many refreshes and executions may run at once, how many bytes a source and a refresh may hold in memory, and the deadlines. Every default is the number the owner confirmed on 2026-09-28. Each key is bound-checked at boot (§7) and again when the keys bind (`DashboardRuntimeProperties` → `DashboardRuntimeConfig`), naming the key; four RELATIONS are refused and one is warned (below the table).

**The admission counters are JVM-LOCAL**, like `ExecutionSlots` (the parameter-engine record's 050/R2 shape): two API instances each admit `max-concurrent-refreshes-per-workspace` refreshes of one workspace and `max-concurrent-dashboard-executions-per-instance` executions. A cluster-wide count is a later decision; nothing here is a per-user cap — a dashboard execution takes no per-user slot (D53), so a refresh never starves the viewer's own runs.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.dashboards.admission.max-concurrent-refreshes-per-workspace` | `4` | Refreshes admitted at once in one workspace on this instance. A further refresh waits up to `max-wait-seconds`; if capacity remains full it is refused `429 dashboard.refresh.saturated` with `Retry-After` and writes no row. 1..100 |
| `datapipelines.dashboards.admission.max-executions-per-refresh` | `16` | Distinct executions (a pipeline release plus its resolved parameters) one refresh may start. The dashboard validator refuses, at save, a document that needs more (`dashboard.validation.too_many_invocations`). The count is DISTINCT executions, not raw sources: two sources pinning one release with the same bindings are one. Must not exceed `datapipelines.executor.max-concurrent-executions-per-instance` (§3.2) — a refresh reserves one executor slot per distinct execution, all at once — or startup refuses (#762). 1..200 |
| `datapipelines.dashboards.admission.max-concurrent-dashboard-executions-per-instance` | `40` | Dashboard execution places reserved on this instance, alongside the shared `datapipelines.executor.max-concurrent-executions-per-instance` limit. A refresh reserves all its places or none; its dashboard places remain reserved until the refresh ends, even when individual sources finish earlier. Above the executor's `max-concurrent-executions-per-instance`, startup logs one WARN `event=config.dashboard_executions_above_executor_ceiling` naming both keys and values for operator review (#762). The two counters have different lifetimes: executor slots release per completed execution, while dashboard reservations remain until the whole refresh ends. Overlapping refreshes can reach the higher dashboard cap, which still limits admission. 1..1000 |
| `datapipelines.dashboards.admission.max-wait-seconds` | `10` | The longest a refresh waits for room before it is refused `saturated`; never queued past it. 0..120 (0 = refuse at once) |
| `datapipelines.dashboards.results.max-bytes-per-source` | `4194304` | A source's result held in memory, 4 MiB, counted with the result store's own byte accounting; over it that source fails `dashboard.refresh.result_too_large` and its dependents error. 1024..268435456 |
| `datapipelines.dashboards.results.max-bytes-per-refresh` | `33554432` | Result rows collected across one refresh, 32 MiB, using the result store's byte accounting. Shared invocations count once. A collector refuses the first row that would exceed the remaining budget, releases its partial rows and marks the refresh PARTIAL. This is a result-byte budget, not a JVM heap limit. 1024..1073741824 |
| `datapipelines.dashboards.timeouts.default-refresh-seconds` | `600` | A refresh's deadline when the dashboard states no `timeouts.refresh_seconds`. 1..3600 |
| `datapipelines.dashboards.timeouts.max-refresh-seconds` | `900` | The cap on any refresh deadline — the dashboard validator's bound on `timeouts.refresh_seconds` and on every occurrence's `timeout_seconds`. 1..7200 |
| `datapipelines.dashboards.timeouts.parameter-lock-seconds` | `30` | How long the client holds the parameter lock while a parent change evaluates. 1..600 |
| `datapipelines.dashboards.timeouts.render-seconds` | `20` | The browser dashboard runtime's default render allowance per visualization, supplied in its runtime config. A host's positive `renderTimeoutMs` mount option overrides it. This is not the server's pipeline execution deadline. 1..600 |

The five relations, each judged at boot naming both keys and values. Four are REFUSED: `default-refresh-seconds` ≤ `max-refresh-seconds`; `max-bytes-per-source` ≤ `max-bytes-per-refresh`; `max-executions-per-refresh` ≤ `max-concurrent-dashboard-executions-per-instance`; and `max-executions-per-refresh` ≤ the executor's `max-concurrent-executions-per-instance` (§3.2; the deprecated `…-global` alias's value when that is what runs) — the last two because a maximal refresh could otherwise never be admitted, a permanent 429. One is a WARNING: `max-concurrent-dashboard-executions-per-instance` ≤ the executor's ceiling (a higher dashboard cap is permitted and still limits overlapping refresh reservations; executor leases release earlier, per execution). A refresh that needs more distinct executions than the smaller of `max-executions-per-refresh` and the executor's ceiling is refused at once — not after `max-wait-seconds` — as `429 dashboard.refresh.saturated` with `details.reason = never_admissible`, the count, the ceiling and its key ([Pipeline contract §13.23](pipeline-contract.md#1323-dashboards)); a genuinely full instance answers `details.reason = busy`. Save and promotion import both validate against this instance's `max-executions-per-refresh`, so only a dashboard saved or imported before a bound was lowered can reach that state; saved dashboards whose static all-source invocation upper bound exceeds the ceiling are named once at boot in a WARN `event=config.dashboards_above_refresh_ceiling` (`workspace/name@version`, each upper bound, the ceiling and its key). This is a conservative warning: equal evaluated bindings or a targeted refresh can need fewer executions. Review the dashboard and ceiling before raising the key or reducing sources. The earliest deadline wins: the refresh's own, an occurrence's `timeout_seconds`, and the source's own executor limits.

**Shipped environment names:** this group's placeholders omit the intermediate YAML group. Use the names below with the packaged `application.yml`; do not derive them by inserting `ADMISSION`, `RESULTS` or `TIMEOUTS`.

| Path below `datapipelines.dashboards` | Environment variable |
|---|---|
| `admission.max-concurrent-refreshes-per-workspace` | `DATAPIPELINES_DASHBOARDS_MAX_CONCURRENT_REFRESHES_PER_WORKSPACE` |
| `admission.max-executions-per-refresh` | `DATAPIPELINES_DASHBOARDS_MAX_EXECUTIONS_PER_REFRESH` |
| `admission.max-concurrent-dashboard-executions-per-instance` | `DATAPIPELINES_DASHBOARDS_MAX_CONCURRENT_DASHBOARD_EXECUTIONS_PER_INSTANCE` |
| `admission.max-wait-seconds` | `DATAPIPELINES_DASHBOARDS_MAX_WAIT_SECONDS` |
| `results.max-bytes-per-source` | `DATAPIPELINES_DASHBOARDS_MAX_BYTES_PER_SOURCE` |
| `results.max-bytes-per-refresh` | `DATAPIPELINES_DASHBOARDS_MAX_BYTES_PER_REFRESH` |
| `timeouts.default-refresh-seconds` | `DATAPIPELINES_DASHBOARDS_DEFAULT_REFRESH_SECONDS` |
| `timeouts.max-refresh-seconds` | `DATAPIPELINES_DASHBOARDS_MAX_REFRESH_SECONDS` |
| `timeouts.parameter-lock-seconds` | `DATAPIPELINES_DASHBOARDS_PARAMETER_LOCK_SECONDS` |
| `timeouts.render-seconds` | `DATAPIPELINES_DASHBOARDS_RENDER_SECONDS` |

Retention of the `dashboard_refreshes` rows has no key of its own: they are deleted by the execution-event retention step, on the same hourly tick and the same cutoff as `datapipelines.executions.event-retention-days` (§3.11, 7 days) — see [Metadata DB §8](metadata-db.md).

---

### 3.35 Release dependencies

The bounds a cascading release works inside ([Versioning §5.3](versioning.md#53-release-lock), the
[release-dependencies record](superpowers/specs/2026-10-13-release-dependencies-design.md) §1.2, ruled §17 row 4), and the
workspace mutation guard's lock wait (record §4.3). The closure of a release — every version the root pins, transitively,
across the dependency sources registered by its caller — is enumerated with an explicit stack, memoised per exact version.
The shipped cascading coordinator registers pipeline and template sources. Depth, version and edge limits are checked during
that walk; wire size is checked when the plan request is read and when canonical bytes are produced. Exceeding those limits
raises `<family>.release.dependency_limit` (422, `details.limit` naming the bound and `details.maximum` its value).
`max-blockers` limits refusal detail, not closure traversal. Each key is range-checked when the keys bind
(`ReleaseDependencyProperties`), refusing startup by key name. The mutation lock and blocker-detail bounds are also shared by
other family lifecycle paths that receive `ReleaseBounds`.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.release.dependencies.max-depth` | `32` | The longest dependency path from the root, in edges (`limit: depth`). 1..256 |
| `datapipelines.release.dependencies.max-versions` | `1024` | Distinct versions in one closure, the root included (`limit: versions`). 1..100000 |
| `datapipelines.release.dependencies.max-edges` | `4096` | Pin occurrences in one closure — every incoming field path counted (`limit: edges`). 1..1000000 |
| `datapipelines.release.dependencies.max-blockers` | `100` | Blockers one `dependency_not_released` refusal lists in `details.blockers`; the rest set `blockers_truncated`. 1..1000 |
| `datapipelines.release.dependencies.max-wire-bytes` | `1048576` | Bytes of one release-plan request, and of a plan's canonical serialisation, 1 MiB (`limit: wire_bytes`). 1024..16777216 |
| `datapipelines.release.dependencies.lock-wait-seconds` | `5` | How long a pin-affecting write or a release waits for its workspace's mutation guard (a row lock on the workspace, `SET LOCAL lock_timeout`) before it is refused `<family>.release.busy` with nothing written. 1..60 |

### 3.36 Managed lake (dp-lake, #785)

The managed lake ([Module structure §5.21](module-structure.md#521-lake), the dp-lake design record §6): Iceberg tables
whose catalog is DP's own metadata Postgres and whose files live under one S3 bucket prefix. It is server configuration,
never UI, and it is **off unless `enabled` is `true`**: with `false` no lake bean exists and none of these keys is judged
or used. With `true`, startup validates the keys (§7) and the after-Flyway boot check verifies the catalog and the store
(§7, the `lake.boot.*` refusals). The four credential values are secrets: environment only
(`deploy/secrets.env.example`), redacted wherever the binding is printed, never in a log line. Sizes use Spring's
`DataSize` units, where `GB` means 1024³ bytes (the record writes GiB). P2 (#785) consumes `enabled`, `name`, `storage.*`
(except `landing-prefix` and the reader pair) and `catalog.*`; P5 (#818) consumes `reads.*`, the reader pair and the
storage keys the managed datasource is built from ([Datasources §8C.10](datasources.md#8c10-the-managed-lake-datasource));
the other keys bind with their defaults and are consumed by
the lanes named in their rows, so that one key set is documented and validated from the module's first release.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.lake.enabled` | `false` | The lake exists on this server. `false`: the module is inert, with no catalog, no S3 client and no boot check |
| `datapipelines.lake.name` | `lake` | The lake's logical name: `catalog_name` in the Iceberg catalog tables and the first segment of `<lake>.<namespace>.<table>`. `[a-z0-9_]{1,63}` |
| `datapipelines.lake.mode` | `normal` | `normal`, `restoring` or `replaying` (design record §9). The catalog's operative mode is the `lake_control` row, which the fence reads; this key is validated now and consumed by the recovery procedure (P7) |
| `datapipelines.lake.storage.kind` | `s3` | The object store. Only `s3` exists |
| `datapipelines.lake.storage.bucket` | (empty) | The bucket. Required when enabled |
| `datapipelines.lake.storage.prefix` | `lake/` | Key prefix of the managed lake under the bucket; every table's location is `s3://<bucket>/<prefix><namespace>/<table id>`. Normalised to one trailing `/`; empty = the bucket root. The boot check sends a HEAD on it and writes one marker, `<prefix>dp/conditional-put-probe` |
| `datapipelines.lake.storage.landing-prefix` | `landing/` | Where deliveries land before ingestion. Consumed by delivery (P4) |
| `datapipelines.lake.storage.region` | (empty) | The bucket's region. A region or an `endpoint` is required when enabled; with only an endpoint, requests are signed for `us-east-1` |
| `datapipelines.lake.storage.endpoint` | (empty) | An S3-compatible endpoint, `http://` or `https://` with a host and no credentials, query or fragment (startup refuses them); empty = AWS for `region`. The scheme decides TLS for both the Java client and DuckDB's secret (`USE_SSL`) |
| `datapipelines.lake.storage.path-style` | `false` | Path-style addressing (`<endpoint>/<bucket>/<key>`), which most S3-compatible stores need |
| `datapipelines.lake.storage.access-key` | (empty) | **Secret.** The writer's access key (the Java side). Set with `secret-key` or not at all; both empty = the SDK's default credential chain (instance or pod credentials) |
| `datapipelines.lake.storage.secret-key` | (empty) | **Secret.** The writer's secret key; see `access-key` |
| `datapipelines.lake.storage.reader-access-key` | (empty) | **Secret.** An optional read-only pair for DuckDB's managed reads (P5). Set with `reader-secret-key` or not at all. When set, it is the managed lake datasource's credential; when empty, the writer pair is; when both are empty, DuckDB uses the instance's credential chain. Read-only by **operator contract**: DP cannot verify the pair's permissions (DuckDB returns no readback of a secret's grants), so a writer-capable reader pair is a deployment mistake, not something startup refuses |
| `datapipelines.lake.storage.reader-secret-key` | (empty) | **Secret.** See `reader-access-key` |
| `datapipelines.lake.admission.max-concurrent-ingestions` | `2` | Executions holding a delivery at once, from landing to the last append (P1/P4). ≥ 1 |
| `datapipelines.lake.admission.max-concurrent-maintenance` | `1` | Maintenance runs at once (P6). ≥ 1 |
| `datapipelines.lake.admission.max-delivery-bytes` | `2GB` | The largest delivery, summed over its objects (P4). `max-delivery-bytes × staging-expansion-factor` must not exceed `scratch-bytes` |
| `datapipelines.lake.admission.staging-expansion-factor` | `3` | Scratch reserved per ingestion = delivery bytes × this factor (P1). ≥ 1 |
| `datapipelines.lake.admission.scratch-bytes` | `20GB` | Scratch space across every staging database, writer and compaction (P1/P6) |
| `datapipelines.lake.admission.staging-memory-limit` | `1GB` | DuckDB `memory_limit` per staging database (P1; provisional until E10b measures the profile) |
| `datapipelines.lake.admission.max-open-partitions-per-append` | `64` | Partitions one append may write at once (P4). ≥ 1 |
| `datapipelines.lake.admission.job-timeout` | `2h` | Every attempt's absolute deadline, fixed at admission and inherited by its leases (P4/P6). Enters the `orphan-grace-hours` rule |
| `datapipelines.lake.admission.lease-wait-seconds` | `60` | How long an operation waits for a busy lease (P4/P6). Must be below `datapipelines.executor.node-query-timeout-max-seconds` (§3.2) |
| `datapipelines.lake.catalog.lock-timeout-seconds` | `5` | `lock_timeout` on every catalog connection the lake opens (design record §8a), and the longest one lease acquire waits on a lease row another transaction holds before it counts as busy (#812; set for that acquire's transaction only, never on the shared pool). ≥ 1 |
| `datapipelines.lake.catalog.statement-timeout-seconds` | `30` | `statement_timeout` on every catalog connection the lake opens. ≥ 1; enters the `orphan-grace-hours` rule |
| `datapipelines.lake.reads.max-generation-age-seconds` | `900` | A managed reader generation older than this is retired before its next lease, or by the pool reaper when nothing leases from it, and the next lease builds a new one (P5). ≥ 1; enters the reads rule (§7) |
| `datapipelines.lake.reads.freshness-tick-seconds` | `60` | How often each live managed generation is compared with the catalog rows; a stale generation is retired early (P5). ≥ 1 and below `max-generation-age-seconds`; enters the reads rule (§7) |
| `datapipelines.lake.reads.memory-limit` | `512MB` | DuckDB `memory_limit` of every managed reader instance, written onto the managed datasource row (P5). Positive. The managed instance cannot spill to disk, so this is its whole working set; a query that needs more fails with DuckDB's out-of-memory error |
| `datapipelines.lake.maintenance.window` | `02:00-05:00` | When maintenance may run, `HH:MM-HH:MM` in **UTC** (P6) |
| `datapipelines.lake.maintenance.max-bytes-rewritten-per-run` | `50GB` | The data one maintenance run may rewrite (P6) |
| `datapipelines.lake.retention.protection-days` | `7` | How long a superseded catalog revision stays protected, which is also the replay horizon (P6/P7). ≥ 1 |
| `datapipelines.lake.retention.orphan-grace-hours` | `72` | The age past which an unreferenced file may be removed (P6). A safety premise: `orphan-grace-hours × 3600` must exceed `job-timeout` + `statement-timeout-seconds` + 3600, or startup is refused |

---

## 4. Precedence

There are two separate resolution steps: the deployment loader constructs the process environment, then Spring resolves application properties.

For a normal bare-jar launch, the relevant Spring sources run from command-line `--key=value`, through `SPRING_APPLICATION_JSON`, JVM `-Dkey=value`, process environment, and then configuration files (profile files override the corresponding base file). Additional external configuration locations can also override packaged files. The product does not install a custom command-line parser: `DatapipelinesApplication.main` delegates to Spring Boot. Avoid setting the same key through several mechanisms.

The `${ENV:default}` expressions in §5 are explicit lookups: a supplied environment value replaces that expression's fallback. They also explain why the documented underscore-separated environment names work even when they differ from Spring's relaxed-binding spelling. The nested posture expression accepts either `DATAPIPELINES_POSTURE` or the dotted property; §7 checks profile/posture alignment.

For `app.sh`, Compose reads `deploy/env/defaults.env` then `deploy/secrets.env`; the later file overrides the earlier file, and exported shell variables override Compose file interpolation. The `--env`, `--posture` and `--demo` options are explicit launcher overrides. A Compose env file is an **interpolation source**, not an instruction to forward every variable into the container: the service's `environment` mapping decides what reaches the JVM. For a bare jar or systemd, the loader must export the values itself; the jar does not read these `.env` files.

Runtime object overrides are separate from startup precedence. Examples: datasource/node/pipeline statement budgets (§3.2), pipeline staging settings (§3.3), datasource LAKE limits (§3.24), and a user's theme preference (§3.10). Use those rows for exact ordering and ceilings; no single global precedence rule applies to all of them.

---

## 5. Full `application.yml` Template

Complete — a deployment assembled from this block gets the framework wiring (§3.14) too. Omitting the `management:` block in particular re-serves the actuator on the application port, which is exactly the exposure the management port exists to prevent.

```yaml
spring:
  application:
    name: datapipelines
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    hikari:
      maximum-pool-size: ${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE:10}

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false

  profiles:
    active: ${DATAPIPELINES_POSTURE:${datapipelines.posture:}}

server:
  port: ${SERVER_PORT:8080}
  shutdown: graceful
  tomcat:
    use-relative-redirects: true
    max-swallow-size: ${SERVER_TOMCAT_MAX_SWALLOW_SIZE:2MB}

management:
  server:
    port: ${MANAGEMENT_SERVER_PORT:9090}
    address: ${MANAGEMENT_SERVER_ADDRESS:127.0.0.1}
  endpoints:
    web:
      exposure:
        include: "health"
  health:
    diskspace:
      enabled: false
    db-scheduler:
      enabled: false

datapipelines:
  redis:
    host: ${DATAPIPELINES_REDIS_HOST}
    port: ${DATAPIPELINES_REDIS_PORT:6379}
    password: ${DATAPIPELINES_REDIS_PASSWORD:}
    command-timeout: ${DATAPIPELINES_REDIS_COMMAND_TIMEOUT:2s}
    connect-timeout: ${DATAPIPELINES_REDIS_CONNECT_TIMEOUT:2s}

  jwt:
    secret: ${DATAPIPELINES_JWT_SECRET}

  db:
    key-provider: ${DATAPIPELINES_DB_KEY_PROVIDER:env}
    encryption-key: ${DATAPIPELINES_DB_ENCRYPTION_KEY}

  auth:
    base-url: ${DATAPIPELINES_AUTH_BASE_URL:}
    bootstrap-admin-email: ${DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL:}
    oidc:
      provider-read-timeout-seconds: ${DATAPIPELINES_AUTH_OIDC_PROVIDER_READ_TIMEOUT_SECONDS:10}
      providers:
        - name: google
          client-id: ${GOOGLE_CLIENT_ID:}
          client-secret: ${GOOGLE_CLIENT_SECRET:}
          issuer-uri: https://accounts.google.com
          display-name: "Sign in with Google"
          picture-hosts: ${DATAPIPELINES_AUTH_GOOGLE_PICTURE_HOSTS:lh3.googleusercontent.com}
        - name: github
          protocol: oauth2
          client-id: ${GITHUB_CLIENT_ID:}
          client-secret: ${GITHUB_CLIENT_SECRET:}
          authorization-uri: https://github.com/login/oauth/authorize
          token-uri: https://github.com/login/oauth/access_token
          user-info-uri: https://api.github.com/user
          user-name-attribute: id
          scopes: [read:user, user:email]
          emails-uri: https://api.github.com/user/emails
          display-name: "Sign in with GitHub"
          picture-hosts: avatars.githubusercontent.com
    jwt:
      ttl-hours: ${DATAPIPELINES_AUTH_JWT_TTL_HOURS:8}
    allowlist:
      domains: ${DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS:}
    api-keys:
      cache-ttl-seconds: ${DATAPIPELINES_AUTH_API_KEYS_CACHE_TTL_SECONDS:60}
    rate-limit:
      login-per-minute: ${DATAPIPELINES_AUTH_RATE_LIMIT_LOGIN_PER_MINUTE:10}
    trusted-proxies: ${DATAPIPELINES_AUTH_TRUSTED_PROXIES:}
    local:
      enabled: ${DATAPIPELINES_AUTH_LOCAL_ENABLED:false}
      bootstrap-password-hash: ${DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD_HASH:}
      bootstrap-password: ${DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD:}
      lockout:
        max-failures: ${DATAPIPELINES_AUTH_LOCAL_LOCKOUT_MAX_FAILURES:5}
        duration-minutes: ${DATAPIPELINES_AUTH_LOCAL_LOCKOUT_DURATION_MINUTES:15}
    cookie-secure: ${DATAPIPELINES_AUTH_COOKIE_SECURE:}
    allow-local-only: ${DATAPIPELINES_AUTH_ALLOW_LOCAL_ONLY:false}

  executor:
    max-parallel-nodes: ${DATAPIPELINES_EXECUTOR_MAX_PARALLEL_NODES:4}
    max-concurrent-executions-per-user: ${DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_EXECUTIONS_PER_USER:10}
    max-concurrent-executions-per-instance: ${DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_EXECUTIONS_PER_INSTANCE:100}
    node-query-timeout-seconds: ${DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_SECONDS:60}
    execution-timeout-seconds: ${DATAPIPELINES_EXECUTOR_EXECUTION_TIMEOUT_SECONDS:600}
    node-timeout-seconds: ${DATAPIPELINES_EXECUTOR_NODE_TIMEOUT_SECONDS:300}
    node-timeout-max-seconds: ${DATAPIPELINES_EXECUTOR_NODE_TIMEOUT_MAX_SECONDS:900}
    node-query-timeout-max-seconds: ${DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_MAX_SECONDS:900}
    node-query-timeout-seconds-by-dialect:
      lake: ${DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_SECONDS_BY_DIALECT_LAKE:180}
    cancel-grace-seconds: ${DATAPIPELINES_EXECUTOR_CANCEL_GRACE_SECONDS:5}
    source-fetch-size: ${DATAPIPELINES_EXECUTOR_SOURCE_FETCH_SIZE:1000}
    progress-write-interval-seconds: ${DATAPIPELINES_EXECUTOR_PROGRESS_WRITE_INTERVAL_SECONDS:5}
    progress-sample-interval-seconds: ${DATAPIPELINES_EXECUTOR_PROGRESS_SAMPLE_INTERVAL_SECONDS:1}
    heartbeat-seconds: ${DATAPIPELINES_EXECUTOR_HEARTBEAT_SECONDS:15}
    lifecycle-write-timeout-seconds: ${DATAPIPELINES_EXECUTOR_LIFECYCLE_WRITE_TIMEOUT_SECONDS:10}

  pipelines:
    max-composition-depth: ${DATAPIPELINES_PIPELINES_MAX_COMPOSITION_DEPTH:5}

  deployment:
    authoring-enabled: ${DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED:true}
    promotion:
      server-key: ${DATAPIPELINES_DEPLOYMENT_PROMOTION_SERVER_KEY:}
      target:
        base-url: ${DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_URL:}
        server-key: ${DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_KEY:}
      inventory-cache-ttl-seconds: ${DATAPIPELINES_DEPLOYMENT_PROMOTION_INVENTORY_CACHE_TTL_SECONDS:60}

  workspaces:
    member-datasources-enabled: ${DATAPIPELINES_WORKSPACES_MEMBER_DATASOURCES_ENABLED:false}

  staging:
    h2:
      mode: ${DATAPIPELINES_STAGING_H2_MODE:PostgreSQL}
      max-memory-mb: ${DATAPIPELINES_STAGING_H2_MAX_MEMORY_MB:1024}
      insert-batch-size: ${DATAPIPELINES_STAGING_H2_INSERT_BATCH_SIZE:1000}
      result-batch-size: ${DATAPIPELINES_STAGING_H2_RESULT_BATCH_SIZE:10000}
      query-timeout-seconds: ${DATAPIPELINES_STAGING_H2_QUERY_TIMEOUT_SECONDS:60}
      max-connections: ${DATAPIPELINES_STAGING_H2_MAX_CONNECTIONS:4}

  result:
    ttl-default-seconds: ${DATAPIPELINES_RESULT_TTL_DEFAULT_SECONDS:300}
    ttl-min-seconds: ${DATAPIPELINES_RESULT_TTL_MIN_SECONDS:60}
    ttl-max-seconds: ${DATAPIPELINES_RESULT_TTL_MAX_SECONDS:3600}
    max-size-bytes: ${DATAPIPELINES_RESULT_MAX_SIZE_BYTES:104857600}
    page-size-rows: ${DATAPIPELINES_RESULT_PAGE_SIZE_ROWS:1000}
    page-max-rows: ${DATAPIPELINES_RESULT_PAGE_MAX_ROWS:100000}

  sse:
    heartbeat-interval-seconds: ${DATAPIPELINES_SSE_HEARTBEAT_INTERVAL_SECONDS:15}
    disconnect-grace-seconds: ${DATAPIPELINES_SSE_DISCONNECT_GRACE_SECONDS:30}
    max-streams-per-user: ${DATAPIPELINES_SSE_MAX_STREAMS_PER_USER:50}

  rate-limit:
    requests-per-second: ${DATAPIPELINES_RATE_LIMIT_REQUESTS_PER_SECOND:100}
    requests-per-minute: ${DATAPIPELINES_RATE_LIMIT_REQUESTS_PER_MINUTE:1000}

  idempotency:
    ttl-seconds: ${DATAPIPELINES_IDEMPOTENCY_TTL_SECONDS:86400}

  templates:
    cache-size: ${DATAPIPELINES_TEMPLATES_CACHE_SIZE:500}
    render-timeout-ms: ${DATAPIPELINES_TEMPLATES_RENDER_TIMEOUT_MS:5000}
    max-body-chars: ${DATAPIPELINES_TEMPLATES_MAX_BODY_CHARS:262144}

  ui:
    theme: ${DATAPIPELINES_UI_THEME:dark}
    actively-developed-days: ${DATAPIPELINES_UI_ACTIVELY_DEVELOPED_DAYS:14}

  executions:
    event-retention-days: ${DATAPIPELINES_EXECUTIONS_EVENT_RETENTION_DAYS:7}
    stale-timeout-minutes: ${DATAPIPELINES_EXECUTIONS_STALE_TIMEOUT_MINUTES:60}
    error-detail: ${DATAPIPELINES_EXECUTIONS_ERROR_DETAIL:full}

  audit:
    retention-days: ${DATAPIPELINES_AUDIT_RETENTION_DAYS:365}

  observability:
    tracing:
      enabled: ${DATAPIPELINES_OBSERVABILITY_TRACING_ENABLED:false}
    logging:
      format: ${DATAPIPELINES_OBSERVABILITY_LOGGING_FORMAT:json}

  bootstrap:
    datasources-file: ${DATAPIPELINES_BOOTSTRAP_DATASOURCES_FILE:}
    examples-file: ${DATAPIPELINES_BOOTSTRAP_EXAMPLES_FILE:}
    demo-api-key: ${DATAPIPELINES_DEMO_API_KEY:dpk_DEMOPUBLIC42.THISKEYISPUBLICBYDESIGNDEMOONLYREADONLY222222222}

  org:
    currency:
      name: ${DATAPIPELINES_ORG_CURRENCY_NAME:Dollar}
      symbol: ${DATAPIPELINES_ORG_CURRENCY_SYMBOL:$}
    fiscal-start-date: ${DATAPIPELINES_ORG_FISCAL_START_DATE:01-01}
    week-start: ${DATAPIPELINES_ORG_WEEK_START:monday}
    timezone: ${DATAPIPELINES_ORG_TIMEZONE:UTC}
  endpoints:
    timeout-default-seconds: ${DATAPIPELINES_ENDPOINTS_TIMEOUT_DEFAULT_SECONDS:30}
    timeout-min-seconds: ${DATAPIPELINES_ENDPOINTS_TIMEOUT_MIN_SECONDS:1}
    timeout-max-seconds: ${DATAPIPELINES_ENDPOINTS_TIMEOUT_MAX_SECONDS:300}
    key-request-budget:
      window-seconds: ${DATAPIPELINES_ENDPOINTS_KEY_REQUEST_BUDGET_WINDOW_SECONDS:60}
      max-requests: ${DATAPIPELINES_ENDPOINTS_KEY_REQUEST_BUDGET_MAX_REQUESTS:60}

  env: ${DATAPIPELINES_ENV:local}
  posture: ${DATAPIPELINES_POSTURE:}
  demo: ${DATAPIPELINES_DEMO:}

  duckdb:
    extension-directory: ${DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY:}
    memory-limit: ${DATAPIPELINES_DUCKDB_MEMORY_LIMIT:}
  datasources:
    retire-ceiling-seconds: ${DATAPIPELINES_DATASOURCES_RETIRE_CEILING_SECONDS:}
    file-roots: ${DATAPIPELINES_DATASOURCES_FILE_ROOTS:}
  mail:
    host: ${DATAPIPELINES_MAIL_HOST:}
    port: ${DATAPIPELINES_MAIL_PORT:587}
    username: ${DATAPIPELINES_MAIL_USERNAME:}
    password: ${DATAPIPELINES_MAIL_PASSWORD:}
    starttls: ${DATAPIPELINES_MAIL_STARTTLS:true}
    from: ${DATAPIPELINES_MAIL_FROM:}
    reply-to: ${DATAPIPELINES_MAIL_REPLY_TO:}
    ops-to: ${DATAPIPELINES_MAIL_OPS_TO:}
    message-stream: ${DATAPIPELINES_MAIL_MESSAGE_STREAM:}
  script:
    evaluate-timeout-seconds: ${DATAPIPELINES_SCRIPT_EVALUATE_TIMEOUT_SECONDS:10}
    suite-timeout-seconds: ${DATAPIPELINES_SCRIPT_SUITE_TIMEOUT_SECONDS:60}
    pool-size: ${DATAPIPELINES_SCRIPT_POOL_SIZE:4}
    pool-queue: ${DATAPIPELINES_SCRIPT_POOL_QUEUE:64}
    abandon-grace-seconds: ${DATAPIPELINES_SCRIPT_ABANDON_GRACE_SECONDS:30}
    max-input-rows: ${DATAPIPELINES_SCRIPT_MAX_INPUT_ROWS:100000}
    max-value-bytes: ${DATAPIPELINES_SCRIPT_MAX_VALUE_BYTES:1048576}
    max-string-bytes: ${DATAPIPELINES_SCRIPT_MAX_STRING_BYTES:1048576}
    max-depth: ${DATAPIPELINES_SCRIPT_MAX_DEPTH:100}
    js:
      isolate-dir: ${DATAPIPELINES_SCRIPT_JS_ISOLATE_DIR:}
      max-heap-mb: ${DATAPIPELINES_SCRIPT_JS_MAX_HEAP_MB:256}
      isolate-memory-mb: ${DATAPIPELINES_SCRIPT_JS_ISOLATE_MEMORY_MB:}
      max-statements: ${DATAPIPELINES_SCRIPT_JS_MAX_STATEMENTS:100000000}
      max-ast-depth: ${DATAPIPELINES_SCRIPT_JS_MAX_AST_DEPTH:1000}
      max-stack-frames: ${DATAPIPELINES_SCRIPT_JS_MAX_STACK_FRAMES:1000}
      max-output-bytes: ${DATAPIPELINES_SCRIPT_JS_MAX_OUTPUT_BYTES:1048576}
      max-result-mb: ${DATAPIPELINES_SCRIPT_JS_MAX_RESULT_MB:64}
      max-input-mb: ${DATAPIPELINES_SCRIPT_JS_MAX_INPUT_MB:64}
  scheduler:
    enabled: ${DATAPIPELINES_SCHEDULER_ENABLED:true}
    threads: ${DATAPIPELINES_SCHEDULER_THREADS:2}
    polling-interval-seconds: ${DATAPIPELINES_SCHEDULER_POLLING_INTERVAL_SECONDS:10}
    heartbeat-interval-seconds: ${DATAPIPELINES_SCHEDULER_HEARTBEAT_INTERVAL_SECONDS:30}
    tick-interval-seconds: ${DATAPIPELINES_SCHEDULER_TICK_INTERVAL_SECONDS:10}
    max-concurrent-runs: ${DATAPIPELINES_SCHEDULER_MAX_CONCURRENT_RUNS:4}
    lateness-seconds: ${DATAPIPELINES_SCHEDULER_LATENESS_SECONDS:600}
    catch-up-max-age-seconds: ${DATAPIPELINES_SCHEDULER_CATCH_UP_MAX_AGE_SECONDS:86400}
    shutdown-wait-seconds: ${DATAPIPELINES_SCHEDULER_SHUTDOWN_WAIT_SECONDS:5}
    min-interval-seconds: ${DATAPIPELINES_SCHEDULER_MIN_INTERVAL_SECONDS:300}
    max-schedules-per-workspace: ${DATAPIPELINES_SCHEDULER_MAX_SCHEDULES_PER_WORKSPACE:100}
    notifications:
      max-recipients: ${DATAPIPELINES_SCHEDULER_NOTIFICATIONS_MAX_RECIPIENTS:20}
      enabled: ${DATAPIPELINES_SCHEDULER_NOTIFICATIONS_ENABLED:false}
      recipient-domains: ${DATAPIPELINES_SCHEDULER_NOTIFICATIONS_RECIPIENT_DOMAINS:}
      max-attempts: ${DATAPIPELINES_SCHEDULER_NOTIFICATIONS_MAX_ATTEMPTS:3}
      retry-backoff-seconds: ${DATAPIPELINES_SCHEDULER_NOTIFICATIONS_RETRY_BACKOFF_SECONDS:30,120,600}
      batch-size: ${DATAPIPELINES_SCHEDULER_NOTIFICATIONS_BATCH_SIZE:20}
      backlog-max: ${DATAPIPELINES_SCHEDULER_NOTIFICATIONS_BACKLOG_MAX:1000}
  parameters:                      # §3.30 — the parameter engine (#194)
    max-parameters-per-set: ${DATAPIPELINES_PARAMETERS_MAX_PARAMETERS_PER_SET:64}
    max-options-per-selector: ${DATAPIPELINES_PARAMETERS_MAX_OPTIONS_PER_SELECTOR:200}
    max-multi-bind-values: ${DATAPIPELINES_PARAMETERS_MAX_MULTI_BIND_VALUES:1000}
    max-binds-per-statement: ${DATAPIPELINES_PARAMETERS_MAX_BINDS_PER_STATEMENT:2000}
    max-option-value-chars: ${DATAPIPELINES_PARAMETERS_MAX_OPTION_VALUE_CHARS:1024}
    max-option-label-chars: ${DATAPIPELINES_PARAMETERS_MAX_OPTION_LABEL_CHARS:256}
    max-input-length: ${DATAPIPELINES_PARAMETERS_MAX_INPUT_LENGTH:4096}
    max-expression-depth: ${DATAPIPELINES_PARAMETERS_MAX_EXPRESSION_DEPTH:16}
    max-expression-nodes: ${DATAPIPELINES_PARAMETERS_MAX_EXPRESSION_NODES:128}
    max-regex-steps: ${DATAPIPELINES_PARAMETERS_MAX_REGEX_STEPS:100000}
    evaluate-timeout-seconds: ${DATAPIPELINES_PARAMETERS_EVALUATE_TIMEOUT_SECONDS:30}
    selector-query-timeout-seconds: ${DATAPIPELINES_PARAMETERS_SELECTOR_QUERY_TIMEOUT_SECONDS:10}
    max-concurrent-selector-queries: ${DATAPIPELINES_PARAMETERS_MAX_CONCURRENT_SELECTOR_QUERIES:4}
    max-waiting-selector-queries: ${DATAPIPELINES_PARAMETERS_MAX_WAITING_SELECTOR_QUERIES:64}
    max-evaluate-response-bytes: ${DATAPIPELINES_PARAMETERS_MAX_EVALUATE_RESPONSE_BYTES:4194304}

  web:
    max-request-bytes: ${DATAPIPELINES_WEB_MAX_REQUEST_BYTES:2097152}

  persistence:
    enabled: ${DATAPIPELINES_PERSISTENCE_ENABLED:true}
    batch-max-events: ${DATAPIPELINES_PERSISTENCE_BATCH_MAX_EVENTS:200}
    batch-max-bytes: ${DATAPIPELINES_PERSISTENCE_BATCH_MAX_BYTES:1048576}
    linger-ms: ${DATAPIPELINES_PERSISTENCE_LINGER_MS:0}
    queue-max-events: ${DATAPIPELINES_PERSISTENCE_QUEUE_MAX_EVENTS:10000}
    queue-max-bytes: ${DATAPIPELINES_PERSISTENCE_QUEUE_MAX_BYTES:33554432}
    record-max-wait-ms: ${DATAPIPELINES_PERSISTENCE_RECORD_MAX_WAIT_MS:2000}
    writers: ${DATAPIPELINES_PERSISTENCE_WRITERS:4}
    shutdown-drain-ms: ${DATAPIPELINES_PERSISTENCE_SHUTDOWN_DRAIN_MS:10000}
    audit:
      enabled: ${DATAPIPELINES_PERSISTENCE_AUDIT_ENABLED:false}
  visualization:                   # §3.33 — the dashboard documents' bounds (#10)
    max-visualizations-per-dashboard: ${DATAPIPELINES_VISUALIZATION_MAX_VISUALIZATIONS_PER_DASHBOARD:50}
    max-cases-per-visualization: ${DATAPIPELINES_VISUALIZATION_MAX_CASES_PER_VISUALIZATION:20}
    max-fixture-rows-per-case: ${DATAPIPELINES_VISUALIZATION_MAX_FIXTURE_ROWS_PER_CASE:1000}
    max-config-bytes: ${DATAPIPELINES_VISUALIZATION_MAX_CONFIG_BYTES:262144}
    max-bindings-per-visualization: ${DATAPIPELINES_VISUALIZATION_MAX_BINDINGS_PER_VISUALIZATION:64}
    max-inputs-per-visualization: ${DATAPIPELINES_VISUALIZATION_MAX_INPUTS_PER_VISUALIZATION:8}
    max-columns-per-input: ${DATAPIPELINES_VISUALIZATION_MAX_COLUMNS_PER_INPUT:256}
    session-ttl-minutes: ${DATAPIPELINES_VISUALIZATION_SESSION_TTL_MINUTES:60}

  dashboards:                      # §3.34 — the dashboard runtime (#10 L2)
    admission:
      max-concurrent-refreshes-per-workspace: ${DATAPIPELINES_DASHBOARDS_MAX_CONCURRENT_REFRESHES_PER_WORKSPACE:4}
      max-executions-per-refresh: ${DATAPIPELINES_DASHBOARDS_MAX_EXECUTIONS_PER_REFRESH:16}
      max-concurrent-dashboard-executions-per-instance: ${DATAPIPELINES_DASHBOARDS_MAX_CONCURRENT_DASHBOARD_EXECUTIONS_PER_INSTANCE:40}
      max-wait-seconds: ${DATAPIPELINES_DASHBOARDS_MAX_WAIT_SECONDS:10}
    results:
      max-bytes-per-source: ${DATAPIPELINES_DASHBOARDS_MAX_BYTES_PER_SOURCE:4194304}
      max-bytes-per-refresh: ${DATAPIPELINES_DASHBOARDS_MAX_BYTES_PER_REFRESH:33554432}
    timeouts:
      default-refresh-seconds: ${DATAPIPELINES_DASHBOARDS_DEFAULT_REFRESH_SECONDS:600}
      max-refresh-seconds: ${DATAPIPELINES_DASHBOARDS_MAX_REFRESH_SECONDS:900}
      parameter-lock-seconds: ${DATAPIPELINES_DASHBOARDS_PARAMETER_LOCK_SECONDS:30}
      render-seconds: ${DATAPIPELINES_DASHBOARDS_RENDER_SECONDS:20}

  release:
    dependencies:
      max-depth: ${DATAPIPELINES_RELEASE_DEPENDENCIES_MAX_DEPTH:32}
      max-versions: ${DATAPIPELINES_RELEASE_DEPENDENCIES_MAX_VERSIONS:1024}
      max-edges: ${DATAPIPELINES_RELEASE_DEPENDENCIES_MAX_EDGES:4096}
      max-blockers: ${DATAPIPELINES_RELEASE_DEPENDENCIES_MAX_BLOCKERS:100}
      max-wire-bytes: ${DATAPIPELINES_RELEASE_DEPENDENCIES_MAX_WIRE_BYTES:1048576}
      lock-wait-seconds: ${DATAPIPELINES_RELEASE_DEPENDENCIES_LOCK_WAIT_SECONDS:5}
  # §3.36 — the managed lake; inert unless enabled. The four S3 credentials are secrets (environment only).
  lake:
    enabled: ${DATAPIPELINES_LAKE_ENABLED:false}
    name: ${DATAPIPELINES_LAKE_NAME:lake}
    mode: ${DATAPIPELINES_LAKE_MODE:normal}
    storage:
      kind: ${DATAPIPELINES_LAKE_STORAGE_KIND:s3}
      bucket: ${DATAPIPELINES_LAKE_S3_BUCKET:}
      prefix: ${DATAPIPELINES_LAKE_S3_PREFIX:lake/}
      landing-prefix: ${DATAPIPELINES_LAKE_S3_LANDING_PREFIX:landing/}
      region: ${DATAPIPELINES_LAKE_S3_REGION:}
      endpoint: ${DATAPIPELINES_LAKE_S3_ENDPOINT:}
      path-style: ${DATAPIPELINES_LAKE_S3_PATH_STYLE:false}
      access-key: ${DATAPIPELINES_LAKE_S3_ACCESS_KEY:}
      secret-key: ${DATAPIPELINES_LAKE_S3_SECRET_KEY:}
      reader-access-key: ${DATAPIPELINES_LAKE_S3_READER_ACCESS_KEY:}
      reader-secret-key: ${DATAPIPELINES_LAKE_S3_READER_SECRET_KEY:}
    admission:
      max-concurrent-ingestions: ${DATAPIPELINES_LAKE_ADMISSION_MAX_CONCURRENT_INGESTIONS:2}
      max-concurrent-maintenance: ${DATAPIPELINES_LAKE_ADMISSION_MAX_CONCURRENT_MAINTENANCE:1}
      max-delivery-bytes: ${DATAPIPELINES_LAKE_ADMISSION_MAX_DELIVERY_BYTES:2GB}
      staging-expansion-factor: ${DATAPIPELINES_LAKE_ADMISSION_STAGING_EXPANSION_FACTOR:3}
      scratch-bytes: ${DATAPIPELINES_LAKE_ADMISSION_SCRATCH_BYTES:20GB}
      staging-memory-limit: ${DATAPIPELINES_LAKE_ADMISSION_STAGING_MEMORY_LIMIT:1GB}
      max-open-partitions-per-append: ${DATAPIPELINES_LAKE_ADMISSION_MAX_OPEN_PARTITIONS_PER_APPEND:64}
      job-timeout: ${DATAPIPELINES_LAKE_ADMISSION_JOB_TIMEOUT:2h}
      lease-wait-seconds: ${DATAPIPELINES_LAKE_ADMISSION_LEASE_WAIT_SECONDS:60}
    catalog:
      lock-timeout-seconds: ${DATAPIPELINES_LAKE_CATALOG_LOCK_TIMEOUT_SECONDS:5}
      statement-timeout-seconds: ${DATAPIPELINES_LAKE_CATALOG_STATEMENT_TIMEOUT_SECONDS:30}
    reads:
      max-generation-age-seconds: ${DATAPIPELINES_LAKE_READS_MAX_GENERATION_AGE_SECONDS:900}
      freshness-tick-seconds: ${DATAPIPELINES_LAKE_READS_FRESHNESS_TICK_SECONDS:60}
      memory-limit: ${DATAPIPELINES_LAKE_READS_MEMORY_LIMIT:512MB}
    maintenance:
      window: ${DATAPIPELINES_LAKE_MAINTENANCE_WINDOW:02:00-05:00}
      max-bytes-rewritten-per-run: ${DATAPIPELINES_LAKE_MAINTENANCE_MAX_BYTES_REWRITTEN_PER_RUN:50GB}
    retention:
      protection-days: ${DATAPIPELINES_LAKE_RETENTION_PROTECTION_DAYS:7}
      orphan-grace-hours: ${DATAPIPELINES_LAKE_RETENTION_ORPHAN_GRACE_HOURS:72}

db-scheduler:
  threads: ${datapipelines.scheduler.threads}
  polling-interval: ${datapipelines.scheduler.polling-interval-seconds}s
  heartbeat-interval: ${datapipelines.scheduler.heartbeat-interval-seconds}s
  missed-heartbeats-limit: 6
  shutdown-max-wait: ${datapipelines.scheduler.shutdown-wait-seconds}s
  table-name: scheduled_tasks
  immediate-execution-enabled: true
```

> **Note:** OIDC provider config is in the app's own YAML namespace (`datapipelines.auth.oidc.providers`), NOT in Spring Security's native `spring.security.oauth2.client.*` namespace. Our `OidcConfig` bean reads this list and builds `ClientRegistration` objects programmatically. See [Auth spec §5.2](auth.md#52-clientregistration-bean-built-at-startup).

> **Redis binding (#500):** `dag` owns `RedisClientProperties` (`@ConfigurationProperties("datapipelines.redis")`) and `RedisClientConfiguration`, which builds the one `LettuceConnectionFactory` from that binding. Operators set the `DATAPIPELINES_REDIS_*` variables defined here. The factory retains enabled command expiry, Boot's 100 ms shutdown timeout and the shared, metrics-customized `ClientResources`; Boot still supplies `StringRedisTemplate` and Redis health. SSL bundles, client names and read-from settings have no house key and are not applied. **Operators change nothing:** the shipped `deploy/` compose and env files already set only `DATAPIPELINES_REDIS_*`. A `SPRING_DATA_REDIS_*` variable set by hand outside them (a database index, SSL, a username — none ever a house key) is now **ignored**, not merged: this binding is the client's only authority.

---

## 6. Posture Profiles (`application-development.yml` / `application-hardened.yml`)

The POSTURE's non-secret defaults (§3.23). `spring.profiles.active` is derived from `DATAPIPELINES_POSTURE` in `application.yml`, so setting that one variable loads the right file under every loader — Compose, a bare `java -jar`, systemd, Kubernetes. Setting `SPRING_PROFILES_ACTIVE` yourself is unnecessary, and a value that disagrees with the posture refuses startup (§7).

**These files carry POSTURE, never INFRASTRUCTURE.** Before 075 this section documented `application-dev.yml`, which was really a LAPTOP file: `localhost:5434`, console logging, one developer's host ports. An org's `dev` environment on Kubernetes runs the `development` posture too and must not inherit any of that. The laptop's values are ordinary lines in `deploy/env/defaults.env`, which every loader reads ([DEVELOPMENT.md §2/§4](../DEVELOPMENT.md)).

**And they carry the posture's defaults ALONE (081).** No env file may name `DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED` or `DATAPIPELINES_AUTH_COOKIE_SECURE`: there is one tracked settings file, it is loaded for every posture, and an environment variable outranks a profile — so a value there would be one posture's answer imposed on both. 075 did exactly that and a `hardened` stack booted with `authoring=on`. `deploy/compose.yml` passes those two in Compose's valueless form (`DATAPIPELINES_AUTH_COOKIE_SECURE:`, nothing after the colon), the only form that leaves a variable *unset* rather than empty — an empty variable outranks a profile just as a set one does. An operator can still override either in `deploy/secrets.env`. `PostureDefaultsSpecDriftTest` and `compose-env-audit.sh` check 7 hold the two halves.

**No literal secrets, in either file (2026-08-07 security review):** both ship inside every production jar (`src/main/resources`), so a working literal there means one stray `SPRING_PROFILES_ACTIVE` on a production manifest runs real infrastructure on publicly-known keys — forgeable admin JWTs and decryptable datasource credentials. Every secret is a placeholder resolved from the deployment's own environment, in every posture.

```yaml
# application-development.yml
datapipelines:
  deployment:
    authoring-enabled: ${DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED:true}
  auth:
    cookie-secure: ${DATAPIPELINES_AUTH_COOKIE_SECURE:}
```

```yaml
# application-hardened.yml
datapipelines:
  deployment:
    authoring-enabled: ${DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED:false}
  auth:
    cookie-secure: ${DATAPIPELINES_AUTH_COOKIE_SECURE:true}
```

Everything else the posture means is a §7 REFUSAL, which no YAML file can carry: demo, a seeded bootstrap credential, loopback infrastructure and a missing OIDC provider are all refused under `hardened`. The full table is §3.23.

The laptop's own settings, for reference ([DEVELOPMENT.md §2/§4](../DEVELOPMENT.md)) — ordinary lines in `deploy/env/defaults.env`, read FIRST like every other default, with no inversion of the secrets-last rule:

```bash
DATAPIPELINES_ENV=local
DATAPIPELINES_POSTURE=development
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/datapipelines
SPRING_DATASOURCE_USERNAME=datapipelines
DATAPIPELINES_REDIS_HOST=localhost
DATAPIPELINES_REDIS_PORT=6381
DATAPIPELINES_OBSERVABILITY_LOGGING_FORMAT=console
DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS=
```

The two passwords are absent on purpose: they are secrets, they live only in `deploy/secrets.env`, and `deploy/compose.laptop-infra.yml` starts its Postgres and Redis **from those same two variables** (each keeping its historical fixed dev value as the interpolation default). Before 081 that file hard-coded `datapipelines` and no Redis password at all, and a third env file had to be loaded after the secrets to undo them — the one inversion this design used to have. `DATAPIPELINES_AUTH_BASE_URL` is likewise absent: it is this deployment's own identity and lives in `secrets.env`, where the scaffold writes `http://localhost:<port>`.

> **Dev host ports (2026-08-12):** the laptop's Postgres listens on host port **5434** and Redis on **6381** — not the universal defaults 5432/6379, which collide with other local stacks on developer machines (Postgres.app/brew default to 5432). The host mapping lives in `deploy/compose.laptop-infra.yml`; `SPRING_DATASOURCE_URL` and `DATAPIPELINES_REDIS_PORT` in `deploy/env/defaults.env` must stay in sync with it and with DEVELOPMENT.md §2/§4. In production, `SPRING_DATASOURCE_URL` and `DATAPIPELINES_REDIS_*` are operator-set and unaffected.

---

## 7. Config Validation

On startup, the app validates:
- All required keys present → fail-fast with a clear error if missing.
- `DATAPIPELINES_JWT_SECRET` ≥ 32 bytes decoded.
- `DATAPIPELINES_DB_ENCRYPTION_KEY` is exactly 32 bytes decoded.
- **Key provider (§3.20):** `datapipelines.db.key-provider` names a provider this build ships (a violation lists them and points at `docs/key-providers.md`), and that provider's own settings are present and well-formed. For `env`: every entry of `datapipelines.db.encryption-keys` has an integer version in `1..255` that is not `1`, and a value that decodes to exactly 32 bytes; `datapipelines.db.encryption-key-current` is an integer naming a configured version (the violation lists which versions ARE configured). An unknown provider name short-circuits the rest — reporting `env`'s key rules to an operator who asked for `aws-kms` is noise they cannot act on.
- `DATAPIPELINES_UI_THEME` matches a vendored theme directory.
- **Actively developed window (§3.10, #598b):** `datapipelines.ui.actively-developed-days`, when present, is an integer ≥ 1 — `0`, a negative or a non-integer refuses startup naming the key and the value. An absent key is not a violation: the binder default (14) applies.
- At least one authentication method: a fully-configured provider or `datapipelines.auth.local.enabled=true`. Fully configured means non-empty `client-id` and `client-secret` plus, for `protocol: oidc`, `issuer-uri`, and for `protocol: oauth2`, `authorization-uri`, `token-uri`, `user-info-uri`, `user-name-attribute` and a non-empty `scopes`. A provider entry with an empty `client-id` is ignored with a WARN — it does not count, and it is not a violation on its own.
- **Each provider entry against its protocol (#655, auth.md §5.1),** every violation naming the provider and the key: `protocol` is `oidc` or `oauth2`; an `oidc` entry has an `issuer-uri` and none of the `oauth2`-only keys; an `oauth2` entry has the five required keys, no `issuer-uri` and no `openid` scope, and every endpoint it names (`emails-uri` included) is `https://` with a host — plain `http://` only to `localhost` / `127.0.0.1`. The provider list is read as the application binds it (the Binder), so a higher-precedence source that re-declares the list replaces it whole and never inherits a key from `application.yml`'s entry at the same index. The `hardened` posture's "a provider is configured" (§3.23) uses the same per-protocol completeness.
- `datapipelines.auth.local.bootstrap-password` and `datapipelines.auth.local.bootstrap-password-hash` are never both set; either seed requires `datapipelines.auth.local.enabled=true` AND `datapipelines.auth.bootstrap-admin-email` — each violation names both keys.
- `datapipelines.auth.oidc.provider-read-timeout-seconds` is a positive integer no greater than 2147483 (§3.4; signed 32-bit milliseconds); zero would disable the HTTP timeout and is refused.
- `datapipelines.auth.local.lockout.max-failures` and `datapipelines.auth.local.lockout.duration-minutes` are positive integers.
- The deprecated executor alias `datapipelines.executor.max-concurrent-executions-global` (050/R2): set alone → its value runs and startup logs one WARN naming `max-concurrent-executions-per-instance`; set together with the new key and differing → startup REFUSES naming both keys.
- `result.ttl-min-seconds` ≤ `result.ttl-default-seconds` ≤ `result.ttl-max-seconds`.
- `endpoints.timeout-min-seconds` ≤ `endpoints.timeout-default-seconds` ≤ `endpoints.timeout-max-seconds`, and the minimum is ≥ 1 (§3.22). Unlike the result TTLs, an ABSENT key is not a violation here — the binder default applies — so only a present, out-of-order triple refuses startup.
- **Demo API key (§3.18, #224):** `datapipelines.bootstrap.demo-api-key`, when set to a value other than the shipped default or blank, must be a well-formed `dpk_<id>.<secret>` credential (auth §7.1: `dpk_`, 12 base32 `[A-Z2-7]` chars, a dot, a non-empty secret, ≤ 80 chars) — a malformed configured key would otherwise surface as a refused login instead of a refused boot. An operator-set value under the `hardened` posture is refused outright, the same treatment the demo flag gets (the shipped default itself does not refuse a hardened boot — "set" means the operator chose it). Blank is the kill switch, never a violation.
- **Endpoint key budget (§3.22, #224):** `datapipelines.endpoints.key-request-budget.window-seconds` ≥ 1 and `max-requests` ≥ 0 (`0` = the budget is off). An absent key is not a violation — the binder default applies.
- `datapipelines.workspaces.provisioning-mode` and `datapipelines.workspaces.open-join` are NOT set — both keys were removed in v1.9 (§3.17, RBAC round 1) and a deployment still carrying either is refused by name. The value check and the open-join/mode agreement check they used to carry went with them.
- Every `datapipelines.auth.trusted-proxies` entry parses as a CIDR (a bare IP is a host CIDR); anything else refuses startup at the auth module's resolver construction — a typo'd range must not silently widen proxy trust.
- `datapipelines.bootstrap.datasources-file` is not set without `datapipelines.auth.bootstrap-admin-email` (§3.18) — the violation names both keys.
- (Dropped in v1.9.) `datapipelines.bootstrap.examples-file` used to be refused while `provisioning-mode` was anything but `auto-per-user`; the seeder now runs at `demo`'s creation in every deployment (§3.18), so the rule has no case left and `ConfigValidator` does not carry it.
- No OIDC provider is **named** `bootstrap`, `local`, `system` or `key`. Those are the `users.provider` values the system writes for identities it creates itself (§6.1 bootstrap actor, §5A local accounts, [Auth §4.5](auth.md#45-the-system-service-account-r7) system service account, [Auth §4.7](auth.md#47-key-identities) an API or server key's own identity), and a provider's configured name is written to that column verbatim — an external provider under any of them would be indistinguishable from them, and for `system` and `key` that indistinguishability is the whole of the account's safety argument. The reservation is case-insensitive and applies to an entry with a blank `client-id` too.
- **Environment name (§3.23):** `datapipelines.env` matches `[a-z0-9][a-z0-9_-]{0,31}`. The deprecated `datapipelines.deployment.name` alias: set alone it still names the deployment and startup logs `event=config.deployment_name_deprecated`; set together with `datapipelines.env` and disagreeing, startup is REFUSED naming both keys.
- **Posture (§3.23):** `datapipelines.posture` is `development` or `hardened`. A named environment with **no** posture refuses to start, naming both variables — the sole exception is the environment named `local`, which resolves to `development`. Explicit beats guessed: the product cannot infer a stance from a name it is forbidden to branch on.
- **Posture / profile alignment (§3.23):** `spring.profiles.active` is derived from the posture, so setting `SPRING_PROFILES_ACTIVE` to a DIFFERENT posture refuses startup naming both — otherwise one posture's defaults would load while every posture rule below judged the other. The `dev` profile was renamed `development` in 075; a deployment still asking for `dev` is refused rather than silently getting no posture file at all.
- **The `hardened` posture (§3.23),** each refusal naming the variable and the posture: `datapipelines.demo` must be empty; no local bootstrap credential may be set (`bootstrap-password` or `bootstrap-password-hash` — local accounts themselves stay allowed); `spring.datasource.url` and `datapipelines.redis.host` must not be loopback (039's dev-profile guard, inverted and reused — "dev convenience must never touch production infrastructure" is the same fact as "a hardened deployment does not run against a laptop's database"); and at least one OIDC provider must be fully configured unless `datapipelines.auth.allow-local-only=true`, which is logged as `event=config.auth_local_only`.
- **Organisation (§3.21):** `datapipelines.org.fiscal-start-date` is `MM-DD` and a day the calendar has — `02-30` and `13-01` are refused, and a month name (`SEP-15`) is refused with a message naming the `MM-DD` form; `datapipelines.org.week-start` is `monday` or `sunday`; `datapipelines.org.timezone` is an IANA zone id (a fixed offset such as `+02:00` is not one); `datapipelines.org.currency.name` and `.symbol` are non-blank. All four report together — every value is in every Context, so a wrong one is a wrong number in every report the deployment produces.
- **Script (§3.28, 7b):** `datapipelines.script.evaluate-timeout-seconds` ≤ `suite-timeout-seconds`; `abandon-grace-seconds` ≥ 1; `pool-size` ≥ 1 and `pool-queue` ≥ `pool-size`; `max-input-rows`, `max-value-bytes`, `max-string-bytes` and `max-depth` each ≥ 1 — every refusal naming the keys.
- **Removed script keys (§3.28, the TRANSFORM→SCRIPT node-type rename):** any set `datapipelines.transform.*` key refuses startup BY NAME — the nine budgets moved to `datapipelines.script.*` with no alias, so a stale key would bind nothing and the deployment would run on defaults silently. Presence, not value, is the signal (the §3.17 rule).
- **Parameter engine (§3.30, #194):** every `datapipelines.parameters.*` key is an integer within its row's bounds, and `selector-query-timeout-seconds` ≤ `evaluate-timeout-seconds` — one statement must fit inside its evaluate; every refusal names the key.
- **Audit retention (§3.12, #310):** `datapipelines.audit.retention-days` is an integer in 30..3650 — refused while the key binds, naming it (the retention job deletes every audit row older than it, so a floor protects the trail from a typo).
- **Visualizations and dashboards (§3.33, #10):** every `datapipelines.visualization.*` key is an integer within its row's bounds; every refusal names the key.
- **Dashboard runtime (§3.34, #10 L2):** every `datapipelines.dashboards.*` key is an integer within its row's bounds; `default-refresh-seconds` ≤ `max-refresh-seconds`, `max-bytes-per-source` ≤ `max-bytes-per-refresh`, `max-executions-per-refresh` ≤ `max-concurrent-dashboard-executions-per-instance` and `max-executions-per-refresh` ≤ `datapipelines.executor.max-concurrent-executions-per-instance` (#762); every refusal names the key(s). `max-concurrent-dashboard-executions-per-instance` above the executor's ceiling is a **WARN** (`event=config.dashboard_executions_above_executor_ceiling`), not a refusal. A separate boot check (`AuthoringStartupCheck`) WARNs once naming saved dashboards whose static all-source invocation upper bound exceeds the smaller ceiling (`event=config.dashboards_above_refresh_ceiling`).
- **Request limits (§3.31, #279):** `datapipelines.web.max-request-bytes` is an integer within its row's bounds (65536..67108864) — the cap bounds every JSON request body on `/api/v1` and `/mcp`; a malformed or out-of-window value names the key and the window.
- **Redis auth:** when `datapipelines.redis.password` is empty (after trimming) and `datapipelines.redis.host` is not loopback — under `development`, log a structured WARN `event=config.redis_no_password` (production Redis holds materialized caller results — [Deployment §9](deployment.md#9-security-hardening-checklist-deployment)); under the **`hardened` posture** it is a REFUSAL naming the key and the host, the same treatment Postgres's password gets as a §2 required key (#189). The refusal replaces the warning; a hardened boot never logs both.
- **Redis client bounds (§3.1, #488):** `datapipelines.redis.command-timeout` and `datapipelines.redis.connect-timeout`, when present, parse as a duration above 0 and at most `60s` — every refusal names the key and the value. Zero is the case the rule exists for: Lettuce reads a command timeout of 0 as no bound at all, so `0s` would silently restore the outage stall #482 removed (`RedisZeroCommandTimeoutIntegrationTest` shows it against a stopped Redis); a blank value is refused too; `60s`, Lettuce's own command default, is the ceiling. The validator judges the one `RedisClientProperties` binding from which `RedisClientConfiguration` builds Lettuce. `TestRedisDefaultsTest` pins the test factories to the shipped operator defaults.
- `datapipelines.deployment.promotion.server-key` set ⇒ **WARN** (091): the value is deprecated in favour of a `server`-kind API key and is removed next release. Presence only — the warning never carries the secret.
- `datapipelines.deployment.promotion.target.base-url` is not set without `datapipelines.deployment.promotion.target.server-key` (§3.19) — the violation names both keys. The target's pre-shared key is what authenticates the push, so a target without one would have every promotion refused at the far end, at the end of a UI action a human took. The reverse is not a violation: a `server-key` with no target is an ordinary receiver.
- **Mail (§3.27, 137):** `datapipelines.mail.host` and `datapipelines.mail.from` are set together or not at all — one without the other refuses startup naming the missing one; `datapipelines.mail.ops-to` without `host` is refused (a sink nobody can reach); mail on (both set) without `datapipelines.auth.base-url` is refused (the welcome mail's login URL); `port` is a port in `1..65535`; `from` is an address (a bare address or `Display Name <address>`). Under the **`hardened` posture**, with mail on: `starttls` must be `true`, and `username` set requires `password` set — each refusal naming the variable and the posture. The SMTP password is carried as PRESENCE only; no violation ever prints it.
- **Managed lake (§3.36, #785)**, judged only when `datapipelines.lake.enabled=true`, every refusal naming the key(s) and never a credential's value: `storage.kind` is `s3`; a `bucket`, and a `region` or an `endpoint`; an `endpoint` is an `http://` or `https://` URL with a host; `mode` is `normal`, `restoring` or `replaying`; `name` matches `[a-z0-9_]{1,63}`; each credential pair (`access-key`/`secret-key`, `reader-access-key`/`reader-secret-key`) is set together or not at all; `protection-days` ≥ 1; **`orphan-grace-hours × 3600 > job-timeout + statement-timeout-seconds + 3600`** (the remover's safety premise, design record §8a part (b)); `lease-wait-seconds` < `datapipelines.executor.node-query-timeout-max-seconds`; `max-delivery-bytes × staging-expansion-factor ≤ scratch-bytes`; every other count, size and timeout positive; `maintenance.window` is `HH:MM-HH:MM`; `reads.freshness-tick-seconds` < `reads.max-generation-age-seconds`, and **`max-generation-age-seconds + 2 × reaper delay + retire ceiling + freshness-tick-seconds ≤ protection-days × 86400 / 24`** (#818, #818c; design record §10 RULING 4 as amended 2026-11-05: no reader generation outlives an hour of each protected day), where the retire ceiling is `datapipelines.datasources.retire-ceiling-seconds` when set, else `datapipelines.executor.node-query-timeout-seconds` + 30 ([Datasources §5.2](datasources.md#52-pool-lifecycle)), and the reaper delay is 260 s, a constant derived from the scheduled jobs, not a key ([Datasources §8C.10](datasources.md#8c10-the-managed-lake-datasource)); the refusal prints all five numbers. With the shipped keys: 900 + 520 + 90 + 60 = 1570 ≤ 25 200. Deferred to the lanes that own their inputs: `scratch-bytes` against the pod's `/tmp` limit and the cgroup memory sum (P1/P6; E10b is their acceptance; the sum's `reads.memory-limit` term is a #818 follow-up).
- **Managed lake boot check (§3.36, #785)** — after Flyway, when the lake is enabled, startup is REFUSED with the catalogued id leading the message: `lake.boot.catalog_shape` (the two Iceberg catalog tables are not V53's six- and four-column shape); `lake.boot.trigger_missing` (the fence trigger on either catalog table, or either revision trigger, is missing, disabled or calls another function — the catalog would be unfenced); `lake.boot.control_row` (`lake_control` does not hold exactly one row); `lake.boot.storage_unreachable` (HEAD on the bucket does not answer 200, a HEAD on the prefix answers other than 200 or 404, or the store does not answer); `lake.boot.conditional_put` (two PUTs of `<prefix>dp/conditional-put-probe` with `If-None-Match: *` — the second must answer 412); `lake.boot.datasource_name_taken` (#818: a datasource that is not the managed lake, live or soft-deleted, holds `datapipelines.lake.name`; raised by the managed-datasource registrar, which runs after the check). Those HEADs and PUTs are the only requests the check sends; the marker is never deleted. The catalog itself runs with `jdbc.init-catalog-tables=false` and issues no DDL.
- **The boot line (§3.23):** `event=config.posture env=<env> posture=<posture> authoring=<on|off> demo=<families>` is logged once (the label's only consumer — no code branches on it, pinned by a guard test). When a promotion receiver key is configured AND `datapipelines.deployment.authoring-enabled=true`, log a structured WARN — a promotion receiver should not author (Versioning D7), though a one-box deployment may legitimately be both. And when authoring is DISABLED while draft pipeline/template versions still exist, startup FAILS naming them: someone authored on a receiver and version alignment may already be broken (Versioning §5.5/§9.3).

The validator's own test suite must assert that the documented laptop setup (`deploy/env/defaults.env` + `deploy/secrets.env`, §6) passes the **production** rules — so a broken local value gets fixed at the data, never by weakening the check.

Validation runs in `@PostConstruct` of a `ConfigValidator` bean. Failures stop startup with a clear log message listing every missing/invalid key.

---

## 8. Source trace

The following index records the bindings, wiring and effect-producing consumers inspected for this reference. Keys grouped in a row share that trace; §3 remains the explanation/default authority. Paths are relative to the repository. Framework settings are consumed by the pinned Spring Boot/Tomcat/Flyway/db-scheduler libraries rather than a product `@ConfigurationProperties` class.

| Settings | Binding and validation source | Runtime consumer and effect |
|---|---|---|
| §3.1: all Redis keys, plus required `host` in §2 | `modules/dag/src/main/kotlin/co/datapipelines/redis/RedisClientProperties.kt` → `RedisClientConfiguration.redisClientConnectionFactory` in the same directory; app `ConfigValidator` checks timeout and authentication posture | `RedisClientConfiguration.kt` constructs one `RedisStandaloneConfiguration` from host/port/password and applies `commandTimeout` and `SocketOptions.connectTimeout` to Lettuce. All injected `StringRedisTemplate` consumers share this factory. Port range is checked in the binding; timeout bounds are checked by app startup validation. |
| §3.2: parallel-node and both execution-admission limits, deprecated global alias | `modules/web/src/main/kotlin/co/datapipelines/web/config/WebProperties.kt` (`ExecutorProperties`) → `EngineConfiguration.executorConfig`, `executionSlots`, `executorDispatcher` in that directory | `modules/dag/src/main/kotlin/co/datapipelines/executor/PipelineExecutor.kt` creates a per-execution semaphore; `ExecutionSlots.kt` keeps process-local user and instance counters; `ExecutorDispatcher.kt` sizes the SQL-thread ceiling from instance slots × parallel nodes. `ExecutorProperties.effectiveMaxConcurrentExecutionsPerInstance` selects the alias when supplied; `ConfigValidator` checks alias agreement. |
| §3.2: execution timeout, node timeout, node timeout ceiling and cancellation grace | `WebProperties.kt` (`ExecutorProperties`) → `EngineConfiguration.executorConfig` | `modules/dag/src/main/kotlin/co/datapipelines/executor/PipelineExecutor.kt` (`withTimeout`, `runNodes` and cancellation cleanup), `ExecutorConfig.nodeTimeoutSecondsFor` and `CancellationFlags.kt`; `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/SettingsRules.kt` checks saved node overrides. The node deadline is runtime-clamped, the overall timeout bounds the execution, and cancellation cleanup has its own grace. |
| §3.2: flat query timeout, per-dialect map and query ceiling | `WebProperties.kt` (`ExecutorProperties`) → `EngineConfiguration.executorConfig`; `DomainConfiguration.pipelineValidator` | `modules/dag/src/main/kotlin/co/datapipelines/executor/ExecutorConfig.kt` (`queryTimeoutSecondsFor`) and `NodeRunner.kt` resolve node → pipeline → datasource → dialect → flat default. `modules/datasources/src/main/kotlin/co/datapipelines/datasources/SqlProbe.kt` uses the flat/per-dialect value as a probe ceiling, tightened by a datasource's smaller timeout. `SettingsRules.kt` validates saved overrides and refuses a node's explicit statement timeout above its effective wall-clock deadline. |
| §3.2: source fetch size | `WebProperties.kt` (`ExecutorProperties`) → `EngineConfiguration.executorConfig` | `modules/dag/src/main/kotlin/co/datapipelines/executor/NodeRunner.kt` calls `SourceStreaming.enable` and `fetchSizeFor`; `SourceStreaming.kt` handles Postgres autocommit and the MySQL sentinel. Zero opts out of streaming setup. |
| §3.2: progress-write interval, progress-sample interval and execution heartbeat | `WebProperties.kt` (`ExecutorProperties`) → `SweepSchedulingConfiguration.executionProgress` / `staleExecutionSweeper`, and `EngineConfiguration.executorConfig` | `modules/dag/src/main/kotlin/co/datapipelines/executor/ExecutionProgress.kt` throttles progress writes; `PipelineExecutor.kt` supplies `NodeOperations`' sample interval and starts heartbeat writes. `StaleExecutionSweeper.kt` uses three heartbeat intervals as its stale cutoff. Boundary progress writes are not throttled. |
| §3.2: lifecycle-write timeout | `WebProperties.kt` (`ExecutorProperties`) → `EngineConfiguration.executionRepository` and `executorConfig` | `modules/dag/src/main/kotlin/co/datapipelines/executor/ExecutionRepository.kt` applies JDBC query timeouts to start/terminal lifecycle statements; `modules/web/src/main/kotlin/co/datapipelines/web/sse/WebEventEmitter.kt` bounds the caller's waits. A timed-out terminal write can leave a RUNNING row for the stale sweep. |
| §3.3: all six H2 staging keys | `WebProperties.kt` (`StagingH2Properties`) → `DomainConfiguration.stagingFactory` → `modules/staging/src/main/kotlin/co/datapipelines/staging/H2StagingProperties.kt` | `StagingFactory.kt` uses mode in the JDBC URL and max-connections in `H2ConnectionPool`; `H2Staging.kt` uses insert batches, query fetch size/timeout and the sampled JVM-wide heap guard. `PipelineExecutor.kt` clamps per-pipeline memory thresholds; `ScriptConfiguration.scriptSupport` also uses result-batch-size for SCRIPT input reads. |
| §3.5: all six result keys | `WebProperties.kt` (`ResultProperties`) → `EngineConfiguration.resultConfig` | `modules/dag/src/main/kotlin/co/datapipelines/executor/ExecutorConfig.kt` (`ResultConfig.effectiveTtlSeconds` and `effectiveLimit`) clamps client requests; `RedisResultStore.kt` applies expiry, first-page size and serialized UTF-8 row-byte cap. `modules/web/src/main/kotlin/co/datapipelines/web/executions/ResultCursor.kt` reads through the result configuration. TTL ordering and positive minimum, positive byte cap and page size within 1..page-max are validated by `ResultConfig`. |
| §3.6: all three SSE keys | `WebProperties.kt` (`SseProperties`) → stream registry beans and `EngineConfiguration.executorConfig` | `modules/web/src/main/kotlin/co/datapipelines/web/sse/ExecutionStreamRegistry.kt`, `dashboards/runtime/RefreshStreamRegistry.kt` and `parameters/stream/ParameterEvaluationStreamRegistry.kt` schedule heartbeat ticks, detect disconnect-grace expiry and add their local user stream counts for admission. `PipelineExecutor.kt` also polls Redis cancellation flags on the heartbeat interval. Stream admission checks are not atomic across registrations, so concurrent opens can overshoot the limit. |
| §3.7: both rate-limit keys | `WebProperties.kt` (`RateLimitProperties`) → web rate-limiter wiring | `modules/web/src/main/kotlin/co/datapipelines/web/ratelimit/RateLimiter.kt` (`RedisRateLimiter.consume` / `hit`) consumes both Redis fixed windows keyed by user and epoch second/minute. Both limits must be positive; backend failure refuses requests. |
| §3.8: idempotency TTL | `WebProperties.kt` (`IdempotencyProperties`) → `WebSurfaceConfiguration.executionLauncher` | `modules/application/src/main/kotlin/co/datapipelines/application/ExecutionLauncher.kt` passes the TTL to `modules/dag/src/main/kotlin/co/datapipelines/executor/IdempotencyStore.kt` (`RedisIdempotencyStore.reserve`), which uses Redis `setIfAbsent` with that expiry and records the same expiration timestamp. No binding-time positivity check exists. |
| §3.9: all three template keys | `modules/templates/src/main/kotlin/co/datapipelines/templates/TemplatesProperties.kt` → `TemplatesConfiguration.workspaceTemplateEngines` / `templateValidator` | In the same directory, `WorkspaceTemplateEngines.kt`, `RepositoryTemplateRegistry.kt` and `InterruptibleConfiguration.kt` apply cache limits per workspace; `TemplateEngine.kt` times out the render future and interrupts its worker; `TemplateValidator.kt` rejects over-cap bodies before parsing. The body limit counts Kotlin string characters, not UTF-8 bytes. |
| §3.10: both UI keys | `modules/web/src/main/kotlin/co/datapipelines/web/ui/UiProperties.kt`; app `ConfigValidator` checks theme and positive days | `ThemeResolver.kt` in the same directory prefers a saved user theme to the deployment default; `OverviewController.kt` passes actively-developed-days to `FamilyInventory.chartsFor`. The source of the metric is the family's last draft write. |
| §3.11: all three execution-history keys | `WebProperties.kt` (`ExecutionsProperties`) → `EngineConfiguration`, `SweepSchedulingConfiguration` and `RetentionSchedulingConfiguration` | `modules/dag/src/main/kotlin/co/datapipelines/executor/PipelineExecutor.kt` and `NodeRunner.kt` apply full/structured error detail. `StaleExecutionSweeper.kt` applies stale-timeout only to heartbeat-null rows. Retention wiring feeds event-retention-days to `ExecutionEventRetention`, `DashboardRefreshRetention` and `ParameterEvaluationRetention`; these remove completed history, not pipeline execution rows. |
| §3.12: audit retention | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuditProperties.kt` → web `RetentionSchedulingConfiguration.auditLogRetention` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuditLogRetention.kt` reads a database-clock cutoff and deletes bounded batches. The binding and consumer both enforce 30..3650 days. |
| §3.16: maximum composition depth | `WebProperties.kt` (`PipelineProperties`) → `DomainConfiguration.pipelineValidator`, `EngineConfiguration.executorConfig` and `EndpointsConfiguration.readOnlyPipelineRule` | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/PipelineValidator.kt` checks saved composition; `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/SubPipelineExecutionRunner.kt` checks runtime child depth; `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/ReadOnlyPipelineRule.kt` bounds endpoint graph traversal. Binding requires at least one. |
| §3.17: member datasource management | `modules/auth/src/main/kotlin/co/datapipelines/auth/WorkspacesProperties.kt` | `modules/web/src/main/kotlin/co/datapipelines/web/datasources/DatasourceWorkspaceRules.kt` (`requireMemberDatasourcesGate`, creation/update rules) gates writes below super admin, with separate ownership and in-process-engine restrictions; `ui/DatasourceUiController.kt` exposes availability to the UI. Disabling this affects management, not visibility grants. |
| §3.24: memory_limit, threads and temp_directory; §3.25: both DuckDB operator keys | `modules/web/src/main/kotlin/co/datapipelines/web/config/DomainConfiguration.kt` (`datasourceRegistry`) reads the two operator properties from `Environment` | `modules/datasources/src/main/kotlin/co/datapipelines/datasources/DialectAdapters.kt` (`LakeDialectAdapter.limitStatements`, `limitValueError`, `extensionStatements`) validates and emits engine settings. `pooling/LakeInstanceOwner.kt` owns a shared engine per pool. `LakeViewStatements.kt` independently adds Iceberg extension setup for registered Iceberg tables. Absolute spill-path syntax is checked, volume membership and free space are not. The memory operator default is eagerly validated at startup; extension-path grammar is checked when its adapter/view setup is constructed. |
| §3.26: retired-pool ceiling | `WebProperties.kt` (`DatasourcesProperties.ceiling`) → `DomainConfiguration.datasourceRegistry` | `modules/datasources/src/main/kotlin/co/datapipelines/datasources/DefaultDatasourceRegistry.kt` passes the value to `pooling/ConnectionPoolManager.kt`, whose `retire` computes the retirement deadline and `reapRetiring` closes a drained or expired pool. The derivation includes only the flat query timeout plus 30 seconds. Explicit overrides must be positive. |
| §3.26: datasource file roots | `modules/datasources/src/main/kotlin/co/datapipelines/datasources/DatasourceFileRoots.kt` (`DatasourceFileRootsProperties`) → `DomainConfiguration.datasourceRegistry` | The same file's `DatasourceFileRoots` validates normalized/symlink-resolved paths; `DatasourceValidator.kt` uses it on registration and `pooling/ConnectionPoolManager.kt` on pool construction. Binding requires each configured root to be absolute, existing and a directory. Empty roots deny file-backed datasources. |
| §3.28: evaluate/suite timeouts, pool size/queue and abandonment grace | `WebProperties.kt` (`ScriptProperties`) → `ScriptConfiguration.scriptEvaluationPool` / `scriptTestRunner`; app `ScriptRules.checkScriptBounds` | `modules/scripting/src/main/kotlin/co/datapipelines/scripting/ScriptEvaluationPool.kt` uses separate admission/running semaphores, waits up to one evaluation budget for a running slot, then waits up to the evaluation budget plus grace for completion. `modules/templates/src/main/kotlin/co/datapipelines/templates/ScriptTestRunner.kt` checks suite expiry only before starting each case. SCRIPT nodes instead derive wall-clock limits in `modules/dag/src/main/kotlin/co/datapipelines/executor/ScriptNodeRuns.kt`. |
| §3.28: input-row, value-byte, string-byte and expression-depth caps | `WebProperties.kt` (`ScriptProperties`) → `ScriptConfiguration.scriptTestRunner` / `scriptSupport` | `modules/templates/src/main/kotlin/co/datapipelines/templates/ScriptTestRunner.kt` and `modules/dag/src/main/kotlin/co/datapipelines/executor/ScriptNodeRuns.kt` pass limits to evaluation and type gates. `modules/scripting/src/main/kotlin/co/datapipelines/scripting/TypeGate.kt` measures string/value UTF-8 sizes; `JsonataEvaluationGuard.kt` enforces expression depth; `JavaScriptEngine.kt` bounds stack frames by depth + four prelude frames and the JS ceiling. All four operator values must be positive. |
| §3.28: all nine JavaScript keys | `WebProperties.kt` (`ScriptProperties.Js`) → `ScriptConfiguration.javaScriptRuntime`; `modules/app/src/main/kotlin/co/datapipelines/config/ScriptRules.kt` validates sizes and loader-path agreement | `modules/scripting/src/main/kotlin/co/datapipelines/scripting/JavaScriptRuntime.kt` and `JavaScriptEngine.kt` apply isolate/Context heap, statement, AST-depth, stack-frame, output-stream, serialized-input and serialized-result budgets. The isolate directory validates an already-configured launcher path; it does not load jars itself. `JavaScriptStartupCheck.kt` reports the runtime availability. |
| §3.32: all ten persistence keys | `modules/auth/src/main/kotlin/co/datapipelines/auth/PersistenceProperties.kt` → `AuthConfiguration` / web `PersistenceConfiguration`; `EngineConfiguration.eventPersistenceExecutor` also reads writers | `modules/persistence/src/main/kotlin/co/datapipelines/persistence/BatchingWriter.kt` applies count/byte admission, partitions, batch size, busy-writer linger, wait/fallback and shutdown-drain bounds. `BatchingContract.kt` validates positive capacities and nonnegative linger/drain; `PersistenceProperties` adds maximum batch-events/drain bounds. Empty-writer admission permits one oversized item. `PersistenceConfiguration.executionEventRecorder` selects direct/batched execution-event storage; auth wiring applies both enabled switches for audit writes. |
| §2 `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password` | `modules/app/src/main/resources/application.yml`; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — `checkRequiredKeys` requires all three non-blank | Spring Boot's metadata datasource and Flyway configuration; `modules/app/src/main/kotlin/co/datapipelines/config/TransactionConfiguration.kt` — `metadataTransactionManager` wraps that datasource. These are metadata database credentials, not a registered customer datasource. |
| §2 `datapipelines.redis.host` | `modules/dag/src/main/kotlin/co/datapipelines/redis/RedisClientProperties.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — required-key check; `modules/app/src/main/kotlin/co/datapipelines/config/PostureRules.kt` — hardened loopback refusal | `modules/dag/src/main/kotlin/co/datapipelines/redis/RedisClientConfiguration.kt` — `redisClientConnectionFactory` builds the shared standalone Lettuce factory. |
| §2 `datapipelines.jwt.secret`; §3.4 `datapipelines.auth.jwt.ttl-hours` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt` — `JwtProperties.secret` and `AuthProperties.Jwt.ttlHours`; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — required/base64/decoded-length checks | `modules/auth/src/main/kotlin/co/datapipelines/auth/JwtService.kt` — constructor derives HS256 key; `issue` stamps expiry; `deriveSubkey` feeds `CookieOAuth2AuthorizationRequestRepository`; `OidcSuccessHandler` and `SecurityConfig` in the same directory use TTL for cookie age. Secret replacement invalidates signatures; TTL changes only newly issued tokens/cookies. |
| §2 `datapipelines.db.encryption-key`; §3.20 `datapipelines.db.key-provider`, `datapipelines.db.encryption-keys`, `datapipelines.db.encryption-key-current` | `modules/web/src/main/kotlin/co/datapipelines/web/config/DomainConfiguration.kt` — `credentialEncryptor` / `SpringKeyProviderConfig` (`Environment` plus map `Binder`); `modules/datasources/src/main/kotlin/co/datapipelines/datasources/crypto/KeyProviders.kt` and `EnvKeyProvider.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — key-provider, key length/version/current checks | `modules/datasources/src/main/kotlin/co/datapipelines/datasources/crypto/CredentialEncryptor.kt` — current key cached at construction for encryption; version byte chooses decryption key. `DomainConfiguration.secretSealer` also opens legacy sealed login-key copies used by `modules/auth/src/main/kotlin/co/datapipelines/auth/ApiKeyService.kt` — `openSealedSecret`; no new API key stores a sealed copy. |
| §3.4 `datapipelines.auth.base-url`, `datapipelines.auth.cookie-secure` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt` — `baseUrl`, `cookieSecure`, `secureCookies`; `modules/app/src/main/kotlin/co/datapipelines/config/MailRules.kt` — mail needs base URL; posture files override cookie default | `modules/auth/src/main/kotlin/co/datapipelines/auth/OidcConfig.kt` — `requireBaseUrl` / `toRegistration` remove trailing slashes and construct callbacks; `MailNotifier.kt` builds login links; `SecurityConfig.kt`, `OidcSuccessHandler.kt` and `CookieOAuth2AuthorizationRequestRepository.kt` apply Secure-cookie choice. |
| §3.4 `datapipelines.auth.allowlist.domains` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt` — `Allowlist`, `isDomainAllowed` trims/lowercases domain entries and treats empty as open | `modules/auth/src/main/kotlin/co/datapipelines/auth/OidcSuccessHandler.kt` — domain gate before provisioning; `WorkspaceService.kt` — invitation domain gate. This is not a general outbound-mail recipient allowlist. |
| §3.4 `datapipelines.auth.api-keys.cache-ttl-seconds` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt` — `ApiKeys.cacheTtlSeconds` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthCache.kt` — TTL on key records, successful secret checks, user liveness, memberships and workspace caches; immediate local invalidation, TTL convergence across replicas. |
| §3.4 `datapipelines.auth.rate-limit.login-per-minute`, `datapipelines.auth.trusted-proxies` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt`; `AuthConfiguration.kt` — `clientAddressResolver`; `ClientAddressResolver.kt` validates CIDRs at construction | `modules/auth/src/main/kotlin/co/datapipelines/auth/LoginRateLimitFilter.kt` — fixed per-IP window on `/login` and `/oauth2/` prefixes, ≤ 0 bypasses limit; the resolver also supplies audit client addresses. The counter is per JVM. |
| §3.4 `datapipelines.auth.bootstrap-admin-email`, `datapipelines.auth.local.enabled`, `datapipelines.auth.local.bootstrap-password-hash`, `datapipelines.auth.local.bootstrap-password` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — exclusive seed forms, enabled/email dependencies; `PostureRules.kt` — hardened seed refusal | `modules/auth/src/main/kotlin/co/datapipelines/auth/LocalAdminSeeder.kt` — one-time create-if-absent seed with forced change; `UserService.kt` — normalized bootstrap address grant on creation; `modules/web/src/main/kotlin/co/datapipelines/web/ui/LocalLoginController.kt` — disabled local login returns 404. `OidcConfig.kt` also uses `local.enabled` to allow no provider registrations. |
| §3.4 `datapipelines.auth.local.lockout.max-failures`, `datapipelines.auth.local.lockout.duration-minutes` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt` — `Lockout`; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — positive integer checks | `modules/auth/src/main/kotlin/co/datapipelines/auth/LocalAuthService.kt` — wrong-password path passes both values to `UserRepository.recordLocalLoginFailure`; `LocalPasswordService.kt` uses the same bounds for password-change verification failures. |
| §3.4 `datapipelines.auth.allow-local-only` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/PostureRules.kt` — `checkHardenedPosture` | Startup acknowledgement only: under hardened, permits absence of a complete provider, with a warning. It does not itself enable local login; the separate at-least-one-authentication-method rule still requires local accounts. |
| §3.4 `datapipelines.auth.oidc.provider-read-timeout-seconds` | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/AuthProviderRules.kt` — `1..2147483` seconds | `modules/auth/src/main/kotlin/co/datapipelines/auth/OidcConfig.kt` — `boundedRequestFactory` / `boundedRestTemplate` feed token exchange, OIDC user-info and ID-token decoder; `OAuth2IdentityUserService.kt` uses the same bounded client for profile/emails. Startup issuer discovery does not use this factory. |
| §3.4 provider entry fields `name`, `client-id`, `client-secret`, `issuer-uri`, `display-name`, `protocol`, `authorization-uri`, `token-uri`, `user-info-uri`, `user-name-attribute`, `scopes` (under `datapipelines.auth.oidc.providers[]`) | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt` — `Provider`, `isEnabled`; `modules/app/src/main/kotlin/co/datapipelines/config/AuthProviderRules.kt` — protocol-specific required/forbidden fields; `ConfigValidator.kt` — non-blank client secret and reserved names | `modules/auth/src/main/kotlin/co/datapipelines/auth/OidcConfig.kt` — `clientRegistrationRepository`, `toRegistration`, `oidcBuilder`, `oauth2Builder`. Blank client ID skips registration; OIDC discovers endpoints and fixes scopes to openid/profile/email; OAuth2 uses explicit endpoints/scopes. Provider name becomes registration identity and callback suffix; display name becomes client name. |
| §3.4 provider entry fields `emails-uri`, `trust-email-without-verified-claim`, `picture-hosts` (under `datapipelines.auth.oidc.providers[]`) | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuthProperties.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/AuthProviderRules.kt` validates OAuth2 emails URI; `modules/web/src/main/kotlin/co/datapipelines/web/ui/AvatarController.kt` — `AvatarHosts` validates host entries even for dormant providers | `modules/auth/src/main/kotlin/co/datapipelines/auth/OAuth2IdentityUserService.kt` chooses primary verified email; `OidcSuccessHandler.kt` — `claimsUnverifiedOrRedirect` applies the trust exception only without emails URI and audits absent/unrecognized claims; `AvatarController.kt` only permits enabled providers' configured picture hosts. |
| §3.18 `datapipelines.bootstrap.datasources-file` | `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/BootstrapProperties.kt` — comma list to paths; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — requires bootstrap admin email | `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/BootstrapDatasourceStartup.kt` — startup iteration; `modules/datasources/src/main/kotlin/co/datapipelines/datasources/BootstrapDatasourceRegistrar.kt` — per-entry create-if-absent, credential reconciliation via probes, idempotent LAKE table seed. |
| §3.18 `datapipelines.bootstrap.examples-file` | `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/BootstrapProperties.kt`; `ExampleContentSeeder.kt` loads and structurally checks every configured file at construction | `modules/web/src/main/kotlin/co/datapipelines/web/config/DemoWorkspaceStartup.kt` → `modules/auth/src/main/kotlin/co/datapipelines/auth/DemoWorkspaceSeeder.kt` — only creation seeds content, as system actor. `ExampleContentSeeder.seed` applies requires-datasources gate, then templates, parameter sets and pipelines in that order. |
| §3.18 `datapipelines.bootstrap.demo-api-key` | `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/BootstrapProperties.kt`; explicit placeholder `DATAPIPELINES_DEMO_API_KEY` in shipped YAML; `modules/app/src/main/kotlin/co/datapipelines/config/DemoApiRules.kt` — blank and exact committed default exempt; custom hardened value/malformed key refused | `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/DemoEndpointSeeder.kt` — reconciles managed endpoints and key on restart, retracts/revokes for blank or no wanted paths; `modules/web/src/main/kotlin/co/datapipelines/web/ui/site/SitePagesController.kt` displays configured public key. |
| §3.19 `datapipelines.deployment.authoring-enabled` | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/AuthoringGuard.kt` — `Environment` read; base/profile YAML supplies default | `AuthoringGuard` and family services (`ParameterSetService`, `ArtifactLifecycle`) refuse authoring and govern draft-pointer eligibility; `modules/web/src/main/kotlin/co/datapipelines/web/config/AuthoringStartupCheck.kt` refuses drafts across five families when disabled; `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PromotionReceiveService.kt` refuses inbound promotion when enabled. |
| §3.19 `datapipelines.deployment.name`; §3.23 `datapipelines.env`, `datapipelines.posture`, `datapipelines.demo` | `modules/web/src/main/kotlin/co/datapipelines/web/config/DeploymentEnv.kt` — constants and resolvers; `modules/app/src/main/kotlin/co/datapipelines/config/PostureRules.kt`; profile selection in shipped YAML | `modules/web/src/main/kotlin/co/datapipelines/web/config/AuthoringStartupCheck.kt` logs resolved state; `PromotionConfiguration.kt` supplies environment label as promotion data. `app.sh` selects demo profiles and exports sample-family markers; the JVM only validates/logs demo selection. Posture files change authoring/cookie defaults; posture validator enforces its refusals. |
| §3.19 `datapipelines.deployment.promotion.server-key` | `modules/auth/src/main/kotlin/co/datapipelines/auth/PromotionProperties.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — deprecation warning | `modules/auth/src/main/kotlin/co/datapipelines/auth/PromotionServerKeyFilter.kt` — constant-time legacy compare first, then stored server-key validation; accepted legacy secret authenticates as system actor, stored key as key identity. |
| §3.19 `datapipelines.deployment.promotion.target.base-url`, `datapipelines.deployment.promotion.target.server-key`, `datapipelines.deployment.promotion.inventory-cache-ttl-seconds` | `modules/auth/src/main/kotlin/co/datapipelines/auth/PromotionProperties.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — target URL requires target key | `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PromotionTargetClient.kt` — builds inventory/push URLs and DP-Promotion-Key header; caches inventory or unreachable result per workspace for TTL; 0 disables caching; push fetches fresh inventory and invalidates cached entry. Bound target credentials change only after sender restart. |
| §3.21 all five `datapipelines.org.*` keys: `currency.name`, `currency.symbol`, `fiscal-start-date`, `week-start`, `timezone` | `modules/web/src/main/kotlin/co/datapipelines/web/config/WebProperties.kt` — `OrgProperties.toContext`; `OrgConfiguration.kt` creates `OrgContext`; `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt` — `checkOrgSettings` | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/OrgContext.kt` / `ContextKeys`; `modules/dag/src/main/kotlin/co/datapipelines/executor/RunContext.kt` — initial org tier then platform tier then bound parameters. Timezone determines platform current_date before parameter overrides; currency/fiscal/week values are context inputs, not automatic formatting/bucketing switches. |
| §3.22 `datapipelines.endpoints.timeout-default-seconds`, `datapipelines.endpoints.timeout-min-seconds`, `datapipelines.endpoints.timeout-max-seconds` | `modules/web/src/main/kotlin/co/datapipelines/web/config/WebProperties.kt` — `EndpointsProperties`, positive floor and ordered triple; `ConfigValidator.kt` repeats key-named ordering check | `modules/web/src/main/kotlin/co/datapipelines/web/config/EndpointsConfiguration.kt` → `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/EndpointPublishService.kt` — default/clamp at publication; `modules/web/src/main/kotlin/co/datapipelines/web/endpoints/PublishedEndpointServeService.kt` re-clamps stored timeout at serve, bounds await only, then returns 202 without cancelling execution. |
| §3.22 `datapipelines.endpoints.key-request-budget.window-seconds`, `datapipelines.endpoints.key-request-budget.max-requests` | `modules/web/src/main/kotlin/co/datapipelines/web/config/WebProperties.kt` — `KeyRequestBudget`, window > 0 / max ≥ 0; `modules/app/src/main/kotlin/co/datapipelines/config/DemoApiRules.kt` | `modules/web/src/main/kotlin/co/datapipelines/web/ratelimit/EndpointKeyBudgetFilter.kt` — per-key/JVM fixed window on published serves, 429 after max; `modules/web/src/main/kotlin/co/datapipelines/web/bootstrap/DemoEndpointSeeder.kt` omits LAKE endpoints when max is 0. |
| §3.27 all nine `datapipelines.mail.*` keys: `host`, `port`, `username`, `password`, `starttls`, `from`, `reply-to`, `ops-to`, `message-stream` | `modules/auth/src/main/kotlin/co/datapipelines/auth/MailProperties.kt` — enabled iff host/from non-blank, reply-to fallback, ops comma list; `modules/app/src/main/kotlin/co/datapipelines/config/MailRules.kt` — half-configured, port, base URL, hardened TLS/credential checks | `modules/auth/src/main/kotlin/co/datapipelines/auth/MailConfiguration.kt` chooses real/no-op sender; `SpringMailSender.kt` configures SMTP/auth/TLS and From/Reply-To; `MailNotifier.kt` selects ops recipients and adds stream header; `modules/web/src/main/kotlin/co/datapipelines/web/schedules/SmtpNotificationMailer.kt` uses the same transport/stream header. |
| §3.35 `datapipelines.release.dependencies.max-depth`, `max-versions`, `max-edges` | `modules/web/src/main/kotlin/co/datapipelines/web/config/ReleaseDependencyProperties.kt` validates ranges and converts to `ReleaseBounds`; `modules/web/src/main/kotlin/co/datapipelines/web/release/ReleaseConfiguration.kt` wires bounds | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/release/ReleaseGraph.kt` checks longest depth, unique versions and each pin edge during traversal; `modules/web/src/main/kotlin/co/datapipelines/web/release/ReleaseCoordinator.kt` also bounds included-dependency/override entries with max-versions. |
| §3.35 `datapipelines.release.dependencies.max-blockers`, `max-wire-bytes`, `lock-wait-seconds` | `modules/web/src/main/kotlin/co/datapipelines/web/config/ReleaseDependencyProperties.kt` — ranges/defaults; `ReleaseConfiguration.kt` passes lock timeout | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/release/ReleaseBlockers.kt` caps details; `TreeHash.kt` checks canonical bytes; `modules/web/src/main/kotlin/co/datapipelines/web/release/ReleasePlanBody.kt` checks request UTF-8 bytes; `JdbcReleaseMutationGuard.kt` sets local PostgreSQL lock_timeout and maps timeout to release.busy. |
| §3.36 every `datapipelines.lake.*` key | `modules/lake/src/main/kotlin/co/datapipelines/lake/LakeProperties.kt` (`LakeProperties`, bound by `LakeAutoConfiguration` only when `enabled=true`); app `LakeRules.kt` (§7 rules, credentials carried as presence only) | `LakeAutoConfiguration.kt` builds `LakeCatalogs` (catalog name, storage root, S3FileIO keys, `catalog.*` timeouts set on every connection by `FencedJdbcClientPool`), the boot check's S3 client (`LakeS3Clients`) and `LakeBootCheck`, run after Flyway by `modules/web/src/main/kotlin/co/datapipelines/web/config/LakeCatalogStartupCheck.kt`. `admission.*`, `maintenance.*`, `retention.*`, `mode` and `landing-prefix` have no consumer yet beyond validation. P5 (#818): `modules/web/src/main/kotlin/co/datapipelines/web/config/ManagedLakeDatasourceRegistrar.kt` builds the managed datasource row from `name`, `storage.*` (region, endpoint, path-style, the reader or writer pair) and `reads.memory-limit`; `DomainConfiguration.datasourceRegistry` passes `reads.max-generation-age-seconds` to the pool manager; `PoolReaperSchedulingConfiguration.ManagedLakeFreshnessScheduler` ticks every `reads.freshness-tick-seconds`. |
| `datapipelines.scheduler`: `enabled` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` (`SchedulerProperties`) | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerTasks.kt` (`SchedulerStartGate.doStart`) starts the shared scheduler only when enabled; the scheduler bean remains available as the API enqueue client. |
| `datapipelines.scheduler`: `threads`, `polling-interval-seconds`, `heartbeat-interval-seconds`, `shutdown-wait-seconds` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` validates the values; `modules/app/src/main/resources/application.yml` bridges them to the db-scheduler starter's `threads`, `polling-interval`, `heartbeat-interval`, `shutdown-max-wait`. | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerAutoConfiguration.kt` (`schedulerAdmission`) also passes shutdown wait to `SchedulerAdmission`; `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/RunReconciler.kt` uses `startGrace` (six heartbeat intervals) to settle unconfirmed starts. `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerNotificationConfiguration.kt` passes the same grace to `ScheduleNotifier` for interrupted deliveries. |
| `datapipelines.scheduler`: `tick-interval-seconds` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` (`tickInterval`) | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerTasks.kt` gives dispatcher, reconciler and notifier recurring tasks this `FixedDelay`. It is a delay after task completion, not a worst-case lateness bound. |
| `datapipelines.scheduler`: `max-concurrent-runs` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` | `modules/web/src/main/kotlin/co/datapipelines/web/config/SchedulerWebConfiguration.kt` (`CapacityGate` bean) passes the cap to `SlotCapacityGate`; `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/ScheduledRunWorker.kt` retries capacity refusal at a fixed 30-second interval within the admission window. |
| `datapipelines.scheduler`: `lateness-seconds`, `catch-up-max-age-seconds` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` (`lateness`, `catchUpMaxAge`) | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/ScheduleDispatcher.kt` (`dispatch`, `recordLatest`) bounds the occurrence search and sets `admitBy`; `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/ScheduleService.kt` sets manual runs' admission deadline. The dispatcher uses the larger of lateness and catch-up age for the latest policy's search. |
| `datapipelines.scheduler`: `min-interval-seconds`, `max-schedules-per-workspace` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/ScheduleService.kt` checks occurrence spacing and live workspace count during writes. |
| §3.29 `notifications` group: `max-recipients` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` (`Notifications`) | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/ScheduleService.kt` passes the maximum to `NotificationSettings.resolve` on create/update. |
| §3.29 `notifications` group: `enabled`, `recipient-domains` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` validates domain entries; `modules/app/src/main/kotlin/co/datapipelines/config/SchedulerNotificationRules.kt` checks enabled mail prerequisites. | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/NotificationPolicy.kt` computes off/no-allowlist posture and save/send domain decisions; `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/NotificationOutbox.kt` and `ScheduleNotifier.kt` enforce policy at enqueue and delivery. |
| §3.29 `notifications` group: `max-attempts`, `retry-backoff-seconds`, `batch-size` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` (`Notifications`, `backoffAfter`) | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/ScheduleNotifier.kt` (`tick`, settlement) caps claimed deliveries per tick and retries only retryable outcomes while attempts remain. Backoff repeats its last element beyond the supplied list. |
| §3.29 `notifications` group: `backlog-max` | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/SchedulerProperties.kt` (`Notifications`) | `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/NotificationOutbox.kt` limits enqueue admission; `modules/scheduler/src/main/kotlin/co/datapipelines/scheduler/ScheduleService.kt` checks the same pending-delivery cap on resend. |
| `datapipelines.parameters`: `max-parameters-per-set`, `max-options-per-selector` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParametersConfig.kt` (`ParametersProperties.toConfig`, `ParametersKey`); `modules/app/src/main/kotlin/co/datapipelines/config/ParametersRules.kt` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParameterSetReader.kt` bounds parameters, dependency lists and constant options; `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParameterEvaluator.kt` requests cap-plus-one selector rows, and `SelectorRows.kt` refuses excess options. |
| `datapipelines.parameters`: `max-multi-bind-values`, `max-option-value-chars`, `max-option-label-chars` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParametersConfig.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/ParametersRules.kt` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/DefinitionRules.kt` checks saved defaults/options; `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParameterEvaluator.kt` checks submitted MULTI length; `modules/parameters/src/main/kotlin/co/datapipelines/parameters/SelectorRows.kt` checks returned option value/label lengths. |
| `datapipelines.parameters`: `max-binds-per-statement`, `selector-query-timeout-seconds` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParametersConfig.kt`; selector timeout must not exceed evaluate timeout. | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/SelectorRunner.kt` translates binds under the cap and opens the statement with the timeout ceiling; `modules/datasources/src/main/kotlin/co/datapipelines/datasources/ReadOnlyStatementLease.kt` clamps the datasource's own query timeout to that ceiling. |
| `datapipelines.parameters`: `max-input-length`, `max-regex-steps` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParametersConfig.kt` (`valueLimits`) | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParameterSetValidator.kt` and `ParameterEvaluator.kt` construct the shared value validator; `modules/typesystem/src/main/kotlin/co/datapipelines/typesystem/DeclarationCompiler.kt` supplies the absent STRING/BINARY max-length fallback, and `ParameterValueValidator.kt` passes the regex read budget to `PatternGuard`. Explicit max-length constraints replace the fallback. |
| `datapipelines.parameters`: `max-expression-depth`, `max-expression-nodes` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParametersConfig.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/ParametersRules.kt` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ExpressionParser.kt` counts nesting and operator nodes, stopping at the first exhausted budget before graph construction. |
| `datapipelines.parameters`: `evaluate-timeout-seconds`, `max-evaluate-response-bytes` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParametersConfig.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/ParametersRules.kt` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParameterEvaluator.kt` applies coroutine timeout and serialized response-size check; `modules/web/src/main/kotlin/co/datapipelines/web/config/RetentionSchedulingConfiguration.kt` passes evaluate timeout to the stale-evaluation sweeper. |
| `datapipelines.parameters`: `max-concurrent-selector-queries`, `max-waiting-selector-queries` | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParametersConfig.kt` | `modules/web/src/main/kotlin/co/datapipelines/web/config/ParametersConfiguration.kt` constructs one process-wide pool; `modules/parameters/src/main/kotlin/co/datapipelines/parameters/SelectorPool.kt` enforces running plus waiting admission and keeps abandoned workers' running slots until return. |
| `datapipelines.web.max-request-bytes` | `modules/web/src/main/kotlin/co/datapipelines/web/config/WebProperties.kt` (`RequestLimitsProperties`); `modules/app/src/main/kotlin/co/datapipelines/config/RequestLimitsRules.kt` | `modules/web/src/main/kotlin/co/datapipelines/web/requestlimits/RequestBodyCapFilter.kt` applies the declared-length and streaming checks to REST/MCP paths, bypasses bodyless methods, and selects the separate fixed screenshot cap. |
| `datapipelines.visualization`: `max-visualizations-per-dashboard`, `max-inputs-per-visualization` | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/VisualizationConfig.kt` (`VisualizationProperties.toConfig`, `VisualizationKey`); `modules/app/src/main/kotlin/co/datapipelines/config/VisualizationRules.kt` | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/DashboardReader.kt` bounds occurrences and their inputs; it derives source count as occurrences × inputs. `VisualizationReader.kt` bounds visualization inputs; `ArtifactTransferService.kt` also applies the occurrence maximum to transfer collection sizes. |
| `datapipelines.visualization`: `max-cases-per-visualization`, `max-fixture-rows-per-case`, `max-config-bytes`, `max-bindings-per-visualization`, `max-columns-per-input` | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/VisualizationConfig.kt`; `modules/app/src/main/kotlin/co/datapipelines/config/VisualizationRules.kt` | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/VisualizationReader.kt` checks the corresponding case, aggregate fixture-row, serialized config, binding and input-column limits before walking those members. |
| `datapipelines.visualization.session-ttl-minutes` | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/VisualizationConfig.kt` | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/VisualizationTestSessionService.kt` (`start`, `submit`) sets the session/preview deadline and bounds the upload capability by that same deadline. |
| `datapipelines.dashboards.admission`: all four keys | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/DashboardRuntimeConfig.kt` (`DashboardRuntimeProperties.toConfig`); `modules/app/src/main/kotlin/co/datapipelines/config/DashboardRuntimeRules.kt` | `modules/application/src/main/kotlin/co/datapipelines/application/dashboards/RefreshAdmission.kt` atomically reserves workspace, dashboard and instance capacity within one wait deadline. `modules/visualization/src/main/kotlin/co/datapipelines/visualization/DashboardValidator.kt` checks maximum distinct invocations at save. |
| `datapipelines.dashboards.results`: both keys | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/DashboardRuntimeConfig.kt`; source maximum must not exceed refresh maximum. | `modules/application/src/main/kotlin/co/datapipelines/application/dashboards/RefreshEngine.kt` constructs one refresh byte budget and one bounded collector per invocation; `BoundedCollector.kt` uses `ResultBytes.rowBytes`, counts shared invocations once, and returns the failed collector's partial budget. |
| `datapipelines.dashboards.timeouts`: `default-refresh-seconds`, `max-refresh-seconds` | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/DashboardRuntimeConfig.kt`; default must not exceed maximum. | `modules/web/src/main/kotlin/co/datapipelines/web/dashboards/runtime/RuntimeViews.kt` resolves the refresh deadline; `modules/visualization/src/main/kotlin/co/datapipelines/visualization/DashboardValidator.kt` checks dashboard and occurrence timeouts; `modules/application/src/main/kotlin/co/datapipelines/application/dashboards/RefreshEngine.kt` enforces refresh/target deadlines. |
| `datapipelines.dashboards.timeouts`: `parameter-lock-seconds`, `render-seconds` | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/DashboardRuntimeConfig.kt` | `modules/web/src/main/kotlin/co/datapipelines/web/dashboards/runtime/RuntimeViews.kt` emits client timeout fields; `modules/web/src/main/resources/static/js/datapipelines-dashboard.js` creates the parameter-attempt deadline and applies render timeout (positive host `renderTimeoutMs` overrides the server default). |
| §3.13–3.14: server, metadata datasource, migrations, management and scheduler bridge | `modules/app/src/main/resources/application.yml`; metadata checks in `modules/app/src/main/kotlin/co/datapipelines/config/ConfigValidator.kt`; dependency pins in `gradle/libs.versions.toml` | Spring Boot creates the HTTP/management listeners, Hikari pool and Flyway runner. `modules/app/src/main/kotlin/co/datapipelines/health/HealthController.kt` uses the actuator health bean for root probes; `modules/web/src/main/kotlin/co/datapipelines/web/config/ExecutionDrainLifecycle.kt` drains executions before web shutdown. Scheduler starter consumes the `db-scheduler` bridge; §3.29 traces the product inputs. |
| §3.15: logging format | `modules/app/src/main/kotlin/co/datapipelines/logging/ObservabilityLoggingFormatPostProcessor.kt` validates the format before logging starts and sets structured-logging properties | `modules/app/src/main/resources/logback-spring.xml` selects `modules/app/src/main/kotlin/co/datapipelines/logging/FormatSwitchingEncoder.kt`; JSON uses redaction/customization and disables the startup banner. |
| §3.15: reserved tracing controls | `tracing.enabled` is declared in packaged YAML; `tracing.endpoint` has no packaged binding | No runtime consumer, OpenTelemetry SDK/exporter dependency or tracing bridge is present in the shipped source/dependency graph. These declarations do not enable telemetry export; [Observability §9.1](observability.md#91-configuration-keys) also records the deferred wiring. |

## 9. Keeping this reference current

`ConfigurationReferenceSpecDriftTest` runs in the normal app test/build lifecycle. It independently flattens packaged YAML with Spring's loader and discovers compiled `@ConfigurationProperties` annotations (including constant prefixes), recursively walking constructor-bound fields and list/map members. It checks:

- Exact key/value equality between §5 and packaged base YAML, including complete environment placeholder expressions. A changed default, alias, nested fallback, added key or removed key fails.
- Scalar defaults in explanatory tables agree with the packaged fallbacks; derived and map defaults keep their domain-specific checks.
- Exact equality of each packaged posture file with its §6 block; a new posture file also requires a documented block.
- An explanatory table row for every packaged leaf and every discovered bound field, including fields omitted from packaged YAML. A prose mention alone does not count.
- Documentation for direct literal property reads and named key constants; legacy settings explicitly refused at startup remain documented as removals.
- Launcher case arguments and tracked sample-data defaults stay documented; the directly configurable launcher wait/port/project defaults match their shell fallbacks.
- Reverse coverage of application-key rows, against compiled/direct declarations, with explicit reserved exceptions for the unimplemented tracing controls. Shipped YAML alone cannot satisfy this reverse check.

The existing group-specific tests still check code defaults, enum bounds, validator rules and deployment mappings. `scripts/compose-env-audit.sh` checks the Compose/env-file forwarding contract; chart drift tests check the Helm mappings. Gradle's shared test convention declares repository files as task inputs, so a documentation-only edit re-runs the checks. `DocsRenderTest`, `DocsLinkRewriteTest` and `scripts/docs-audit.sh` check the packaged document and links.

Run the repository's normal checks (`scripts/pregate.sh` for a development change), or the unfiltered app/auth/web module checks when working on this reference. `:modules:mcp-server:docsExport` supplies the served manual input required by `scripts/docs-audit.sh`.

**Limits:** these checks verify declarations, defaults, mappings and references; they cannot prove that a prose description matches an arbitrary behavioral change. Method-level property factories, mutable-bean-only bindings and new input APIs require an inventory extension. The direct-read scan covers literal `getProperty`/`getRequiredProperty` calls and constants named as keys/properties, not arbitrary computed strings. Dynamic key-provider maps, datasource-file environment substitutions and external framework configuration remain source-review responsibilities. A new input mechanism must extend the inventory instead of bypassing it. When a consumer, validator or binding changes, re-trace the relevant §8 path and update its description in the same change. No live deployment, credential values or production configuration is read by these guards.

## 10. Launch and deployment inputs

These inputs configure the launcher, image or sample-data services. They are separate from the Spring application properties in §§2–3. The sources are `app.sh`, `Dockerfile`, `deploy/compose.yml`, `deploy/compose.local-build.yml`, `deploy/env/defaults.env` and the code paths named below. Changing a launcher environment variable affects the next invocation; changing an image build argument requires rebuilding the image.

### 10.1 Local launcher arguments

`app.sh` reads `deploy/env/defaults.env` first and `deploy/secrets.env` second and passes both to Compose. The `--env`, `--posture` and `--demo` options are stripped from the argument list before choosing the command, so they apply to the invocation as a whole. For these three values, an explicit option wins over a non-empty process environment value, which wins over values read from those files. The launcher's file lookup retains the last **non-empty** value; it is not a general dotenv parser. Use the supported forms below.

| Argument | Use and effect |
|---|---|
| `--scaffold` | Creates missing `deploy/secrets.env` from the example, sets mode `600`, generates credentials, enables local accounts, and chooses `admin@local.test` as the bootstrap address. An existing file is left intact. Edit the address before the first start if a different administrator is wanted; the account is seeded once. |
| `--start` | Scaffolds missing secrets, builds the jar in the pinned JDK container, builds the local image, then starts the Compose stack. It checks the application's `/health` response and, when selected, the demo stages' exits. |
| `--start --no-build` | Reuses the selected local image; refuses if it does not exist. For `nyc` or `trade`, the reused image must already contain the MySQL driver. |
| `--stop` | Stops this Compose project's services and retains data volumes. It also detects and stops running demo services when `--demo` was omitted. |
| `--clean` | Stops and removes this project's containers/network and deletes only its `<project>-postgres-data` metadata volume. This removes application content, users, keys, workspaces and datasource registrations; demo MySQL data and downloaded sample artifacts remain. Requires typing the project name at a terminal. |
| `--yes` | Supplies the confirmation for `--clean`, including when no terminal is attached. It does not change what is deleted. |
| `--status` | Prints the selected services, probes `/health`, and reads the seeded administrator from the metadata database. It can print a still-pending one-time login credential; treat its output accordingly. |
| `--logs` | Follows the application container's logs. |
| `--env <label>` | Sets `DATAPIPELINES_ENV` (§3.23). Defaults to `local`; any other label requires a posture. |
| `--posture development\|hardened` | Sets `DATAPIPELINES_POSTURE` (§3.23). `local` with no posture uses `development`; other missing or unknown postures are refused before launch. |
| `--demo nyc,trade,lake` | Sets `DATAPIPELINES_DEMO` (§3.23); accepts any comma-separated subset of these families, stripping whitespace. Selects matching Compose profiles and bootstrap-file markers. `nyc`/`trade` also add `-Pmysql` to the jar build. Demo is refused under `hardened`. |

There is no `--help` command: no command or an unrecognized command prints usage and exits `2`. The removed `--demo-nyc` and `--demo-trade` forms are explicitly refused with migration guidance.

### 10.2 Launcher environment and Compose inputs

The following launcher controls are read from the **invoking process environment**, not through the launcher's `from_files` helper. Set them in the shell running `app.sh`. Application properties already catalogued in §§2–3 continue to use their documented Compose mappings.

| Input | Default | Use and effect |
|---|---|---|
| `APP_COMPOSE_PROJECT` | `dp` | Passed as Compose `-p`; scopes containers, network and volumes. A non-default project requires an explicitly supplied `APP_HOST_PORT`. |
| `APP_HOST_PORT` | `8080` | Host publication of the fixed container port `8080`; accepts a port or address-and-port such as `127.0.0.1:8080`. Also determines the launcher's health URL and the base URL written when scaffolding new secrets. It does not change `server.port`. |
| `IMAGE_TAG` | `datapipelines:local` for `dp`; otherwise `datapipelines:local-<project>` | Local image built or reused by the launcher and selected by `compose.local-build.yml`. |
| `HEALTH_WAIT_SECONDS` | `360` | Launcher's `/health` wait budget, checked in five-second steps. A non-running container fails the invocation; budget expiry while it remains running prints “still starting” and exits `0`. This is not a readiness guarantee: the image healthcheck uses `/ready` separately. |
| `LOADER_WAIT_SECONDS` | `900` | Shared wait budget across selected demo stages, checked in five-second steps after app health succeeds. Any absent, failed or still-running stage causes exit `1`. |
| `DATAPIPELINES_DATASOURCES_FILE_ROOTS` | See §3.26 | When demo is selected and this value is empty in the invoking environment, `app.sh` exports `/srv/sample`. A non-empty process value is preserved; this derived export outranks an env-file value. |
| `COMPOSE_PROJECT_NAME` | `dp` in the Compose volume expressions | Raw Compose project identity; `app.sh` supplies `-p`, which also supplies this interpolation value. Volume names are `<project>-postgres-data`, `<project>-mysql-data` and `<project>-sample-data`. Use `APP_COMPOSE_PROJECT` with `app.sh`, or Compose's own `-p` for a raw invocation. |
| `SAMPLE_NYC_ON`, `SAMPLE_TRADE_ON`, `SAMPLE_LAKE_ON` | Derived: `1` for selected families, empty otherwise | `app.sh` overwrites these markers from `--demo`/`DATAPIPELINES_DEMO`. Compose uses them to assemble the bootstrap datasource/example-file lists. With raw Compose, select the corresponding profiles and markers together as shown in [Deployment Appendix B](deployment.md#appendix-b-demo-quickstart--the-published-sample-data). |

`app.sh` has no configurable builder-image or Gradle-cache input: its builder image and `.gradle-docker` cache path are assigned in the script. Its generated `DP_GEN_*` and `DP_APP_URL` variables are internal scaffold values, not supported operator settings.

### 10.3 Sample-data inputs

These defaults are the values in the tracked `deploy/env/defaults.env`, used by `app.sh` and the documented Compose command. They are not server property defaults. `deploy/sample-data/load.sh` consumes the artifact URLs/versions as positional arguments from Compose, verifies manifest checksums before restoring an engine, and records the loaded version. [Deployment Appendix B](deployment.md#appendix-b-demo-quickstart--the-published-sample-data) gives the setup and reset procedures.

| Input | Tracked default | Use and effect |
|---|---|---|
| `SAMPLE_BASE_URL` | `https://datapipelines-co.s3.amazonaws.com/sample-data/mobility` | Artifact root for the `nyc` Postgres/SQLite/MySQL stages. Each fetch uses `<base>/<version>/...`. |
| `SAMPLE_VERSION` | `v8` | Immutable mobility artifact directory and engine version marker. Raw `compose.yml` without the env files has an older `v6` fallback; load the tracked defaults to obtain the supported pin. |
| `SAMPLE_TRADE_BASE_URL` | `https://datapipelines-co.s3.amazonaws.com/sample-data/trade` | Artifact root for the trade file/MySQL stages. |
| `SAMPLE_TRADE_VERSION` | `v4` | Immutable trade artifact directory and engine version marker; also the raw Compose fallback. |
| `SAMPLE_LAKE_BASE_URL` | `https://datapipelines-co.s3.amazonaws.com/sample-data/lake` | Root used to construct the lake manifest URL. The app imports the manifest at bootstrap; lake queries read the data objects in place. |
| `SAMPLE_LAKE_VERSION` | `v1` | Lake manifest's version directory. The lake stage copies repository bootstrap/example files; it does not download or restore the dataset. |
| `SAMPLE_DB_USER` | `dp_demo_ro` | SELECT-only login created by the Postgres/MySQL loader and used by the bootstrap datasource definitions. The loader requires a lowercase SQL identifier matching `[a-z_][a-z0-9_]{0,62}`. |
| `SAMPLE_PG_PASSWORD` | No shared default; scaffold generates it | Secret for the demo Postgres login. Passed both to the loader and application bootstrap placeholders. The loader refuses the literal dollar-quote delimiter `$sd_pw$` inside this password. |
| `SAMPLE_MYSQL_PASSWORD` | No shared default; scaffold generates it | Secret for the demo MySQL login, passed to its loader and application bootstrap placeholders. |
| `SAMPLE_MYSQL_ROOT_PASSWORD` | No shared default; scaffold generates it | Demo MySQL initialization/admin secret; Compose supplies it as `MYSQL_ROOT_PASSWORD` to the server and loaders. Changing the env file does not rewrite an already initialized MySQL volume's root password. |
| `SAMPLE_LAKE_MANIFEST_URL` | Derived by Compose | `<SAMPLE_LAKE_BASE_URL>/<SAMPLE_LAKE_VERSION>/manifest.json`, passed to the app and substituted in the lake bootstrap file. Compose assigns this expression directly; overriding this name alone in an env file does not replace that expression. |
| `SAMPLE_LAKE_CATALOG_REF` | Empty | Optional root declared by the lake datasource bootstrap file. A local mirror can use `file:///srv/lake-mirror` with a matching mounted layout and base URL; the manifest loader checks the URL against the datasource's admitted roots. An empty placeholder omits the field. |

For direct use of `deploy/sample-data/load.sh`, the additional inputs are: required `<base-url> <version>` (falling back to `SAMPLE_BASE_URL`/`SAMPLE_VERSION`); `--engines <comma-separated-engines>` or `--engines=...` (default: all reachable supported engines); `--family nyc|trade` or `--family=...` (default `SAMPLE_FAMILY`, otherwise `nyc`); and `SAMPLE_DIR` (default `/srv/sample`, also used by `place-lake.sh`). The Postgres arm requires `PGHOST`, `PGUSER` and `SAMPLE_PG_PASSWORD`, uses `PGDATABASE` or `postgres` for its admin connection, and uses the database-client environment for credentials (`PGPASSWORD` in Compose). `SAMPLE_PG_DB` defaults to `dp_sample_trips`. The MySQL arm requires `MYSQL_HOST`, `MYSQL_ROOT_PASSWORD` and `SAMPLE_MYSQL_PASSWORD`; `SAMPLE_MYSQL_DB` defaults to `dp_sample_weather` for `nyc` or `dp_sample_trade` for `trade`. Database names use the same identifier validation as the demo login. Compose fixes these service addresses and `SAMPLE_DIR`; putting a different value in its env files does not override a fixed service field. Custom database names also require matching artifacts/bootstrap definitions; the input does not rewrite their SQL or JDBC URLs.

### 10.4 Process inputs outside Spring configuration

| Input | Source and behavior |
|---|---|
| `DATAPIPELINES_SEED_PASSWORD` | Read only by `modules/auth/src/main/kotlin/co/datapipelines/auth/PasswordHashTool.kt`, invoked with `./gradlew :modules:auth:hashPassword`. A non-empty value is hashed with the production Argon2 hasher; otherwise the tool prompts without echo and fails if no console exists. This is a password-hashing utility input, not a server bootstrap-password alias. |
| `loader.path` system property; environment `loader.path`, `loader_path`, `LOADER_PATH` | `ScriptRules.effectiveLoaderPath` in `modules/app/src/main/kotlin/co/datapipelines/config/ScriptRules.kt` reads these in that order; an empty higher-priority value still wins. They describe the launcher's classpath, not a Spring property. The image owns `LOADER_PATH=/app/lib,/opt/graaljs`; changing an application YAML value does not add jars. The configured JavaScript isolate directory must already be in the effective list and contain `js-isolate.jar`. See [Deployment §3.5](deployment.md#35-jdbc-driver-matrix-what-ships-in-the-image) for the trusted driver drop-in. |
| `java.io.tmpdir` system property | `LakeDialectAdapter.limitStatements` in `modules/datasources/src/main/kotlin/co/datapipelines/datasources/DialectAdapters.kt` uses the JVM temporary-directory location for a generated `datapipelines-lake-<UUID>` spill directory on an anonymous in-memory LAKE engine, unless the datasource supplies `temp_directory` (§3.24). The app does not assign this system property's default. |
| `polyglot.engine.userResourceCache` system property | The image entrypoint sets `/opt/graaljs/resources`, the native-resource cache preinstalled by the Docker build. A custom read-only deployment must arrange an equivalent readable cache; changing the Spring isolate-directory setting does not move this cache. See [Deployment §6.4](deployment.md#64-kubernetes-recommended-for-production). |
| Bootstrap-file `${NAME}` placeholders | `BootstrapDatasourceFileReader` in `modules/datasources/src/main/kotlin/co/datapipelines/datasources/BootstrapDatasourceFile.kt` resolves these directly with `System::getenv` in every string value. Names are deployment-defined and match `[A-Za-z_][A-Za-z0-9_]*`; Spring properties and `${NAME:default}` syntax do not supply a fallback here. An unset variable refuses startup. When an object field consists entirely of one placeholder whose variable is set but empty, the field is omitted; an embedded empty value expands to an empty string. See [Datasources §8A.2](datasources.md#8a2-env_var-resolution). |

### 10.5 Build and Helm settings

`modules/datasources/build.gradle.kts` recognizes `-Pmysql` and `-Poracle` to include the optional drivers in the jar's runtime dependencies. Presence enables them, so `-Pmysql=false` still includes MySQL. `modules/app/build.gradle.kts` recognizes `-Pdatapipelines.buildTime=<ISO-8601 instant>` for the build timestamp and `-Pdatapipelines.commit=<commit>` for build-info commit metadata. Absent, their fields are omitted rather than derived. `app.sh` supplies the current UTC time when building. [Deployment §3](deployment.md#3-build-artifacts) and [§3.5](deployment.md#35-jdbc-driver-matrix-what-ships-in-the-image) remain the packaging and driver-license authority.

The Dockerfile's build arguments are `TARGETARCH` (BuildKit platform, only `amd64` and `arm64` accepted), `DUCKDB_CORE_VERSION` (DuckDB extension download/layout version), `GRAALJS_VERSION` (isolate artifact version), and `GRAALJS_SHA256_LINUX_AMD64` / `GRAALJS_SHA256_LINUX_AARCH64` (the expected isolate checksums). The exact version/checksum defaults are pinned in `Dockerfile`, `deploy/duckdb-extensions.sha256` and the version catalog and checked by the existing image drift tests. These are coordinated dependency pins, not independent runtime tuning inputs. The build also preinstalls native resources using `-Dloader.main=co.datapipelines.scripting.JavaScriptResourcesMain`; this is a build utility selection, not the application's normal main class.

The Helm input reference already lives in the commented `deploy/helm/datapipelines/values.yaml` and [Deployment §6.4](deployment.md#64-kubernetes-recommended-for-production). Keep chart defaults there: `replicaCount`, `image`, `existingSecret`, `extraEnv`, `duckdb`, `script.js`, `service`, `management`, `resources`, `temporaryStorage`, `libVolume`, `autoscaling`, `podDisruptionBudget` and `terminationGracePeriodSeconds` are consumed by the templates under `deploy/helm/datapipelines/templates/`. The chart requires an existing Secret; it does not generate credentials. Its dedicated runtime groups map to the application settings in this reference. The chart refuses duplicate dedicated env mappings, launcher-path overrides and the JVM option variables `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` in `extraEnv`; Secret contents passed by `envFrom` cannot be inspected at render time. [Deployment §6.4](deployment.md#64-kubernetes-recommended-for-production) documents those boundaries and the existing `scripts/helm-check.sh` render checks.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-11-05 | v1.77 | 818c (#818) managed reader lifetime | §3.36 `reads.max-generation-age-seconds`: the pool reaper also retires an over-age generation nobody leases from. §7 reads rule gains 2 × the reaper delay (260 s, derived, not a key); the refusal prints it. No key, default or template change. |
| 2026-11-04 | v1.76 | #818 dp-lake P5 managed reads | §3.36: new key `reads.memory-limit` (thirty-two keys); `reads.*` rows say what each bounds; the reader pair's row states the credential order and that read-only is an operator contract DP cannot verify. §5 template gains the key. §7 gains the reads rule (tick below the age bound; age + retire ceiling + tick within an hour of each protected day) and the `lake.boot.datasource_name_taken` refusal. §8 traces the P5 consumers. |
| 2026-11-04 | v1.75 | #780 script-invariants docs | **§3.28 says what the code does**: the intro states the ONE shared pool (every SCRIPT node of every execution, each row-mode batch and each invariant an evaluation), the immediate admission vs the wall-clock slot wait, and the fail-fast that aborts siblings on a pool refusal; the `max-input-rows` row states both bounds by path — evaluate/test caps the `rows` batch and each table input before evaluation, run caps table/value inputs before loading while a `row` input streams uncapped, and the invariants' read-back is bounded on the COMBINED count (outputs + rejects + inputs, row mode adding the streamed input and a caller output's buffered rows). No key, default or template change. — Renumbered at merge after #810. |
| 2026-11-04 | v1.74 | 810 (#812) | §3.36 `catalog.lock-timeout-seconds` also bounds a lease acquire's wait on a lease row another transaction holds; the acquire then counts as busy. |
| 2026-11-02 | v1.73 | #785 landing, security pass F2 | §3.36 `storage.endpoint`: a URL carrying userinfo, a query or a fragment is refused at startup (only scheme + host were checked); the row says so. |
| 2026-11-02 | v1.72 | #785 dp-lake P2 catalog core | New **§3.36 Managed lake**: the thirty-one `datapipelines.lake.*` keys of the design record §6 (enabled, name, mode, `storage.*` with the two secret credential pairs, `admission.*`, `catalog.*`, `reads.*`, `maintenance.*`, `retention.*`), inert unless enabled. §5 template gains the `lake:` block. §7 gains the lake rules (judged only when enabled) and the after-Flyway `lake.boot.*` refusals. §8 traces the binding. |
| 2026-11-01 | v1.71 | #762 never-admissible refresh | §3.34: `max-executions-per-refresh` must not exceed the executor's `max-concurrent-executions-per-instance` (startup refuses) and the dashboard execution cap above it WARNs; the count is distinct executions; a refresh over the smaller ceiling is refused at once with `details.reason = never_admissible`; saved dashboards whose static all-source upper bound exceeds the ceiling are named at boot as possible refusals. §7 likewise. |
| 2026-10-31 | v1.70 | #769 | Source-traced operator reference: corrected behavioral scope, provider fields, environment aliases, reserved tracing controls and precedence; synchronized the full YAML template; added source index, launch inputs and whole-reference build guards. |
| 2026-10-24 | v1.69 | #517 release dependencies, core (S1) | **§3.35 Release dependencies:** `datapipelines.release.dependencies.*` — the closure bounds (depth, versions, edges, wire bytes), the blocker cap and the writer guard's lock wait; §5's template gains the block. |
| 2026-10-25 | v1.68 | #712 integration | §3.14: the chart’s complete ingress policy requires the app-port declaration and unions allowances with other selecting policies. |

| 2026-10-25 | v1.67 | 442c (#442) | §3.29: serialized deployment-wide pending admission, including resend refusal at capacity; activation pointer for a mixed-version fleet. |
| 2026-10-24 | v1.66 | 442b (#442) | §3.29 gains six scheduler notification keys — `notifications.enabled` (default `false`; `true` needs the mail transport and `auth.base-url` or startup refuses), `recipient-domains` (the allowlist: `example.com` / `*.example.com`; empty under `development` sends nothing, under `hardened` restricts nothing), `max-attempts` (3), `retry-backoff-seconds` (30,120,600), `batch-size` (20) and `backlog-max` (1000); `max-recipients` loses its "settings only" note. Compose and `defaults.env` carry them. |
| 2026-10-24 | v1.65 | #688 (Helm portion) — renumbered at merge after #686’s v1.64 | §3.28: the nine JavaScript keys are exposed as the chart's `script.js.*` values (deployment.md §6.4) — empty renders no env entry, the isolate directory must be one of the image's launcher-path entries, `extraEnv` double mappings are refused. No key, default, route or permission changes. |
| 2026-10-24 | v1.64 | #686 JWKS fetch joins the provider read bound | §3.4's `provider-read-timeout-seconds` row: the ID-token decoder's JWKS fetch is bounded by the same key through a `JwtDecoderFactory` bean; discovery stays separate. No key added, no default change. |
| 2026-10-22 | v1.63 | #651 enabled-provider avatar hosts | §3.4's `picture-hosts` allowlist includes only providers with a non-blank `client-id`, using the same predicate as sign-in registration. Dormant entries are still validated for shape at startup but contribute no hosts. No key, default, route or permission changes. |
| 2026-10-22 | v1.62 | 8b (#8) — renumbered at merge after #682's v1.61 | §3.28 gains the nine JavaScript keys, their measured defaults, derived isolate memory and launcher agreement; max-depth also bounds JavaScript stack frames. |
| 2026-10-22 | v1.61 | #682, answers #683 | Configurable provider connect/read timeout, deploy mirrors and startup validation (§3.4, §5, §7); per-provider defaults drift-guarded. |
| 2026-10-21 | v1.60 | 655 (#655) GitHub sign-in, answers #154 part 2 | **§3.4 gains seven per-provider rows:** `protocol` (default `oidc`), `authorization-uri`, `token-uri`, `user-info-uri`, `user-name-attribute`, `scopes` (all empty by default, required for `oauth2`) and `emails-uri` (unset, optional). §1's provider sentence names both protocols and the GitHub env pair; the §5 YAML gains the `github` entry as shipped; §7's authentication-method rule is per protocol and gains the entry-against-protocol rule (unknown protocol, missing key, `issuer-uri` on `oauth2`, `oauth2` keys on `oidc`, `openid` scope, non-https endpoint — each naming the provider and the key). `deploy/secrets.env.example` gains empty `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` lines. |
| 2026-10-21 | v1.59 | 636 (#636) the housekeeping statements' bounds | **§3.11**'s "not a key" note names the four housekeeping statements #636 bounds — the dashboard refresh sweep's UPDATE at 10 s (`REFRESH_SWEEP_STATEMENT_TIMEOUT_SECONDS`), the dashboard refreshes' DELETE and the parameter evaluations' sweep UPDATE and batch DELETE at the retention tick's 25 s — and links metadata-db §8.4 and §8.5. No key added. |
| 2026-10-20 | v1.58 | V50 (#640, script-node-rename lane, owner ruling 2026-10-20) | **The TRANSFORM→SCRIPT node-type rename, §3.28's keys move with it**: the nine script budgets now live at `datapipelines.script.*` (env `DATAPIPELINES_SCRIPT_*`) with no alias; **§7 gains `checkRemovedTransformKeys`** (`CHECK_COUNT` 35 → 36): any set `datapipelines.transform.*` key refuses startup BY NAME pointing at its replacement — presence, not value, is the signal (the §3.17 rule). `ScriptConfigKeysSpecDriftTest` (7b's, renamed) still pins the new keys in this document, `application.yml` and the validator. Renumbered at merge after #326's v1.57. |
| 2026-10-20 | v1.57 | 326 (#326) the scheduled jobs' statement bounds | **§3.11** gains a "not a key" note: the stale sweep's 10 s and the hourly retention's 25 s statement bounds are code constants beside their cadences, not configuration keys. No key is added, renamed or removed. |
| 2026-10-20 | v1.56 | 599 merge follow-up (#599) the window counts on every lifecycle home | **§3.10 row** `datapipelines.ui.actively-developed-days`: the description names every lifecycle home (the four object families read the same window since #599), not the Pipelines home only. No key, default or validation change. |
| 2026-10-20 | v1.55 | 598b (#598) the Pipelines home's activity chart | New **§3.10 row** `datapipelines.ui.actively-developed-days` (default `14`, `DATAPIPELINES_UI_ACTIVELY_DEVELOPED_DAYS`): the window of the Pipelines home's "actively developed" count — a pipeline whose draft was last written within that many days ([UI screens §4.25](ui-screens.md)). §5's template gains the line; **§7** gains the rule (`UiRules.checkUiActivelyDevelopedDays`, registered in `ConfigValidator`; `CHECK_COUNT` 34 → 35): a present value that is not an integer ≥ 1 refuses startup naming the key and the value. `UiConfigKeysSpecDriftTest` pins the `datapipelines.ui.*` keys in this document, `application.yml` and the validator. |
| 2026-10-16 | v1.54 | 467 (#467) the over-cap screenshot refusal reaches the client | **§3.13's `server.tomcat.max-swallow-size` row** names the screenshot route's own bounded drain (its cap + 2 MiB, fixed; the key is unchanged and does not move it) and the `application.yml` comment says the same. No key, default or validation change |
| 2026-10-15 | v1.53 | 500 (#500) | Redis has one authority: `RedisClientProperties` binds §3.1 and builds the Lettuce factory in `dag`; the framework bridge is removed from §3.14 and §5. The §7 validator judges that binding. Test registrations use operator keys with explicit passwords, and the defaults guard reads the operator block. Merge follow-up: the binding note states that operators change nothing and that a hand-set `SPRING_DATA_REDIS_*` variable is now ignored. |
| 2026-10-09 | v1.52 | 488 (#488) | **§3.1's two Redis timeout rows** and a new **§7 rule** (`RedisRules.checkRedisTimeoutBounds`, check 34): `datapipelines.redis.command-timeout` / `.connect-timeout` must parse as a duration above 0 and at most `60s` (the owner's ceiling: Lettuce's own default, never more); malformed, blank, zero, negative and over-ceiling values refuse startup naming the key and the value. Verified in Lettuce 6.6's source and by `RedisZeroCommandTimeoutIntegrationTest`: a zero command timeout is NO bound (a GET against a stopped Redis still pending at 3 s while the shipped 2 s client failed in 2,002 ms) and also a zero handshake deadline (a silent server fails the connect in one timer tick, ~100 ms). `0s` on the connect timeout and negative values were already loud (Lettuce asserts); zero on the command timeout was the silent case. |
| 2026-10-09 | v1.51 | 482 (#482) | New **§3.1 rows**: `datapipelines.redis.command-timeout` and `.connect-timeout` (both `2s`) — no client timeout was configured anywhere, so Lettuce's 60 s per-command default made a Redis outage stall every rate-limited request on the order of a minute before the documented `429 rate_limit.unavailable` (and the stopped-Redis suite case alone took ~242 s). §3.14's bridge row extended: `spring.data.redis.timeout` / `.connect-timeout` carry the SAME `DATAPIPELINES_REDIS_COMMAND_TIMEOUT` / `DATAPIPELINES_REDIS_CONNECT_TIMEOUT` placeholders; §5's template and the deploy mirrors updated. No consumer code changed — every Redis fault path already routes a timeout (a `DataAccessException` subclass) through its existing failure handling; `TestRedis` builds its factories with the same two bounds under a placeholder-parity test. |
| 2026-10-03 | v1.50 | 442a (#442) | Saved schedule notification settings: recipients and five event choices, validated and shown by role; omitted PUT settings preserved; mail remains off pending part b. |
| 2026-10-02 | v1.49 | S3 (#376) the evaluation history's retention | §3.30 states that the parameter-evaluation history has NO key of its own: the execution-event retention (§3.11) deletes finished records on its hourly tick, and the stale sweep's cutoff is `evaluate-timeout-seconds` plus a fixed one-minute margin. No key, default or bound added or changed (the owner's §11.5 ruling). |
| 2026-10-02 | v1.48 | 140 (#140) the Helm chart maps the DuckDB operator keys | §3.25's operator instruction names the Helm route: the reference chart's `duckdb.extensionDirectory` and `duckdb.memoryLimit` values render these two env vars when non-empty, an empty value renders no entry (inheriting the image's bundled extension directory), and an `extraEnv` double mapping is refused at render time. No key, default or bound changed. |
| 2026-10-02 | v1.47 | 167 (#167) the probe's timeout ceiling | §3.2: `node-query-timeout-seconds` and `node-query-timeout-seconds-by-dialect.<dialect>` are documented as ALSO the `sql_probe` timeout ceiling (was a static 30 s in code). No key, default or bound added or changed — the existing knob is the one truth. |
| 2026-10-02 | v1.46 | 204 (#204 L4/L5) the in-process boundaries at the pool build — renumbered at merge after 375's v1.45 | §3.26's `file-roots` row: the file itself is resolved (a final-component symlink aimed outside every root, or a dangling one, is refused) and the rule is re-checked at every pool build ([Datasources §4.2A](datasources.md)). No key, default or bound changed. |
| 2026-10-02 | v1.45 | S2 (#375) the observed parameter-set evaluation | §3.6: no new key. `disconnect-grace-seconds` names the observed evaluation beside the execution (and the dashboard refresh it already governed): a client gone past the grace aborts its evaluation (the owner's §11.7 ruling; [REST API §21.5](rest-api.md#215-the-observed-evaluation)) — with the defaults the grace and `parameters.evaluate-timeout-seconds` are both 30, so the deadline usually ends it first. `max-streams-per-user` counts execution, refresh and observed-evaluation streams together (#375 D7). |
| 2026-10-01 | v1.44 | L4a (#352) the visualization test sessions — renumbered at merge after L1c-c's v1.43 | §3.33 gains **`datapipelines.visualization.session-ttl-minutes`** (`60`, 1..1440): a test session's TTL — the run's `expires_at`, the preview capability's deadline and the ceiling on the submit-minted upload capability (the spec's §11.2 `tests.session-ttl-minutes`, flattened to the shipped two-level key grammar). In the §5 template, `application.yml`, `deploy/compose.yml`, `deploy/env/defaults.env`, `deploy/secrets.env.example` and `ConfigValidator` (§7). |
| 2026-09-30 | v1.43 | L1c-c (#10) transfer limits and evidence — renumbered at merge after 337's v1.42 | **§3.33's `max-visualizations-per-dashboard` row**: the value is documented as ALSO the transfer families' aggregate ceiling (L1c-c) — it caps each whole export/import envelope array (`visualizations` bundle, `templates` closure) and each whole promotion batch arm (`visualizations`, `dashboards`), refused whole before any member binds. An additional ceiling on top of the per-document bound, not a consequence of it: two individually valid dashboards can together exceed it, and such a batch refuses with no partial writes and no automatic split. No key, default or bound changed; the production transfer bean now receives the operator's configured value (L1c-c's C1 — an override was silently ignored at the default before). |
| 2026-09-30 | v1.42 | 337 (#337) | §3.15's `logging.format` row is now TRUE instead of aspirational: the key is bound before logging initialises (environment post-processor), the value set is closed (`json` \| `console`), an unknown value refuses startup, and the row documents what each value produces and which deploy shape ships which. No new key, no YAML or deploy-mirror change — the declared key just works. |
| 2026-09-29 | v1.41 | L2 (#10) the dashboard runtime | New **§3.34 The dashboard runtime**: `datapipelines.dashboards.*`, the ten admission / result / deadline numbers of the spec's §9.4–§9.6, each the owner-confirmed default (4 / 16 / 40 / 10 s; 4 MiB / 32 MiB; 600 / 900 / 30 / 20 s), with three boot-time relations, in the §5 template, `application.yml`, `deploy/compose.yml`, `deploy/env/defaults.env`, `deploy/secrets.env.example` and `ConfigValidator` (§7). The admission counters are stated JVM-LOCAL. §3.33 had already promised these keys. |
| 2026-09-29 | v1.40 | 306/#311 (the execution row's order and bounds) — renumbered at merge: L1a took v1.39 | §3.2 gains **`datapipelines.executor.lifecycle-write-timeout-seconds`** (`10`): the bound on the two `pipeline_executions` lifecycle writes, read by two layers — a JDBC `queryTimeout` on exactly the RUNNING insert and the terminal UPDATE (`ExecutionRepository`; a database that answers slowly is cancelled at the statement) and the emitter's caller-side wait (`WebEventEmitter`; the layer that bounds a never-answering database, which a statement timeout cannot reach — measured). A past-bound write is stated, never fabricated: insert failure = the existing `recorded = false` path, terminal failure = the row left RUNNING for the stale sweep, counted (`datapipelines.executions.lifecycle_write_failed`). Mirrored in `application.yml`, `defaults.env`, `secrets.env.example`, `compose.yml`; `WebPropertiesSpecDriftTest` pins the default. |
| 2026-09-29 | v1.39 | L1a (#10) the visualization module | New **§3.33 Visualizations and dashboards**: `datapipelines.visualization.*`, the seven collection bounds of the two documents' readers — `max-visualizations-per-dashboard` 50, `max-cases-per-visualization` 20, `max-fixture-rows-per-case` 1000, `max-config-bytes` 262144, `max-bindings-per-visualization` 64, `max-inputs-per-visualization` 8, `max-columns-per-input` 256 (proposed; confirmed at review) — in the §5 template, `application.yml`, `deploy/compose.yml`'s pass-through, `deploy/env/defaults.env` and `ConfigValidator` (§7). The spec reserved §3.32 for the dashboard keys; 266 took it. |
| 2026-09-29 | v1.38 | 310 (#310) audit-log retention — numbered after 266b's v1.37 | §3.12's `datapipelines.audit.retention-days` now BINDS (`AuditProperties`) and is ENFORCED: the retention sweep's last step deletes `audit_log` rows older than the database's `NOW()` minus the key, one cutoff for every event, in bounded batches (5,000 rows a statement, 50 statements or 2 s a tick). Before #310 the key was documented and shipped but read by nothing. **New bounds 30–3650**, refused at bind (§7 gains the rule); the default (365) is unchanged, and no shipped env file sets a value below the floor |
| 2026-09-29 | v1.37 | 266 (#266) persistence batching, with its correction round 266b — the dashboard implementation spec reserved "§3.32" for its keys; this lane takes it and the spec renumbers | New **§3.32 Persistence batching**: the ten `datapipelines.persistence.*` keys of the batching writers in front of the audit log, the execution-event record and the replay log (enabled, `audit.enabled`, batch and queue bounds, linger, record-max-wait, writers, shutdown drain), enforced at binding (`PersistenceProperties`). `audit.enabled` ships **false** (the orchestrator's ruling on the measurement: audit rows never formed batches and the writer raised their tail), so by default only the execution-event stores are batched. `batch-max-events` is bounded at 7000 (the replay log's Lua script); `writers` also sizes the `dp-event-persist` pool, whose thread count was a code constant (4), and the row says what `1` serialises. §5's template gains the `persistence:` block appended after the whole `datapipelines:` tree. No existing key changed. |
| 2026-09-28 | v1.36 | 298 (#291) request constraints on every body | §3.31's prose: the stated Jackson `StreamReadConstraints` hold on every route that parses its own text body as well (a request copy of its domain mapper, pipeline-contract §13.21), not only on the two surfaces' shared mappers. The v1.35 row, which had landed above this table's header, moved under it. No key changed. |
| 2026-09-28 | v1.35 | 279 (#279) the request-body cap | New **§3.31 Web — request limits**: `datapipelines.web.max-request-bytes` (default 2 MiB, 65536..67108864), the platform-wide request-body cap refused `413 request.body_too_large` on `/api/v1` and `/mcp` before any handler or parser (pipeline-contract §13.21); §3.13 gains the `server.tomcat.max-swallow-size` row (default 2MB) that bounds draining a refused body; §5's template gains the `web:` block appended after the whole `datapipelines:` tree; §7 lists the rule (check 31). The stated Jackson `StreamReadConstraints` (nesting 100, string 4M chars, number 1000 digits) are recorded in §3.31's prose — they are constants of the request mappers, not operator keys. |
| 2026-09-26 | v1.34 | 194b (#194) parameter engine lane B — numbered after origin/main's v1.33 (260) | New **§3.30 Parameter engine**: the fifteen `datapipelines.parameters.*` keys of the design record's §11 with their bounds (enforced at boot by `ConfigValidator` and at binding by `ParametersProperties`); §5's template gains the `parameters:` block as the last child of `datapipelines:`; §7 lists the rule. No options cache (the record's §11). |
| 2026-09-26 | v1.33 | 260 the depth-accounting fix (#260) — numbered after origin/main's v1.32 (246) | §3.28's `max-depth` row re-stated: the depth bound is counted at every evaluate entry and exit by the engine itself (the library `Timebox` skipped `isParallelCall` frames, leaking a unit per item on set-level shapes until #260), nested evaluation and non-tail lambda recursion included; a tail-recursive lambda is trampolined by the library and the wall clock is its bound. No key, default or binding changed. |
| 2026-09-26 | v1.32 | 246 (#246) the avatar proxy hardened — numbered after scheduler lane 1's v1.31 (#9, merging at dispatch) | §3.4's `picture-hosts` row says **no port** on both sides: an entry is a bare hostname (unchanged), and a stored picture URL naming a port is refused; it points at auth.md §11.1 for the two other #246 rules (raster types only, one 5 s budget for the whole fetch). No key, default or binding changed. |
| 2026-09-26 | v1.31 | scheduler lane 1 (#9) — numbered after origin/main's v1.30 (197) | New **§3.29 Scheduler**: the eleven `datapipelines.scheduler.*` keys (dispatch on/off — API mode, threads, polling, heartbeat, tick, max concurrent runs, lateness, catch-up reach, shutdown wait, the two save guards) with their bounds, enforced at binding; `management.health.db-scheduler.enabled: false`. §5 template gains the `scheduler:` block and the top-level `db-scheduler:` wiring fed from it. Status caught up (it read v1.27 after v1.29's row; main's v1.30 row is 197's). |
| 2026-09-25 | v1.30 | 197 (#197) the avatar proxy | §3.4 gains `datapipelines.auth.oidc.providers[].picture-hosts` (per-provider, default **empty** = the avatar proxy fetches nothing and avatars render initials): bare hostnames only — a scheme, port, path, userinfo or wildcard entry refuses startup, enforced where the allowlist is built (the `trusted-proxies` precedent). The demo `google` provider ships `lh3.googleusercontent.com` — measured, NOT derived from the issuer (`accounts.google.com`). [Auth §11.1](auth.md#111-oidc-provider-configuration) carries the full key story; [Deployment §6.2](deployment.md#62-multi-instance-horizontal-scaling-production) the narrowed CSP row. |
| 2026-09-24 | v1.29 | 224 (#224) | §3.18 gains `datapipelines.bootstrap.demo-api-key` (committed default, blank = off, changed = rotation) and §3.22 the `datapipelines.endpoints.key-request-budget.*` pair (window 60 s, 60 requests, `0` = off); §7 gains the demo-key shape/hardened check and the budget bounds check (checks 28 and 29). The demo key and its page section are rest-api §19's worked example. |
| 2026-09-23 | v1.28 | 7b (#7) | New **§3.28 Transform evaluation**: the nine `datapipelines.transform.*` budgets (evaluate/suite timeouts, pool size/queue, abandon grace, the input/output caps, max depth) behind the transform test suite, `templates_evaluate` and the evaluate route; §7 gains the transform bounds check (check 27: evaluate ≤ suite, grace ≥ 1, pool-queue ≥ pool-size, the caps ≥ 1). §5 template block appended after `mail` (the whole `datapipelines:` tree, as every append). |
| 2026-09-21 | v1.27 | 186 in-process datasource containment (#186) | §3.26 gains `datapipelines.datasources.file-roots` (default **empty** = no file-backed in-process datasource is registrable by anyone; each root must be an existing directory at boot). §3.17's `member-datasources-enabled` default flips to **`false`**, matching the shipped posture (`deploy/env/defaults.env` has shipped `false` since the workspaces round) — a documented default must match the shipped posture, and the drift guards now pin all three to the same value. §3.17's row also states the flag-independent rule: in-process engines (H2 `mem:`/`file:`, DuckDB, SQLite) are super-admin-only regardless. |
| 2026-09-21 | v1.26 | 188 (#189) | §3.23's posture table and the §7 rule list gain the Redis-password row: passwordless Redis off loopback stays a WARN under `development` and is a REFUSAL under `hardened` (`PostureRules.checkHardenedRedisPassword`, check 26); the §3 Redis row says so. |
| 2026-09-21 | v1.25 | 180 (#180, #182) | Doc-only, no key change. §3.18's `examples-file` row, its cross-key paragraph and the three §7 bullets (`provisioning-mode` value, open-join/mode agreement, examples-file/mode) described rules `ConfigValidator` dropped in v1.9 — the document contradicted its own §3.17 and v1.9 row. Each now states that the rule was dropped and why (the seeder runs at `demo`'s creation in every deployment). |
| 2026-09-19 | v1.24 | 172 (#172) | §3.22: the published-endpoint surface re-rooted to `/api/<category>/<version>/<path…>` (R-EP5); the `datapipelines.endpoints.*` keys and their bounds are unchanged. |
| 2026-09-17 | v1.23 | 155 / #122 staging memory guard scope | §3.3: `max-memory-mb` re-described truthfully — the threshold is per-execution (the pipeline override changes the number one execution compares, never what is measured), but the MEASUREMENT is the whole JVM's used heap, sampled, so the guard is a shared circuit breaker, not an isolated budget, a reservation, or an OOM guarantee (Staging §8.2 says the same; one cross-link, not two copies). Sizing guidance rewritten from the demand side: worst-case heap demand stays `max-memory-mb` × `max-concurrent-executions-per-instance` per instance and `-Xmx`/container memory must cover it. No behaviour change; the 108 §C startup warn line is unchanged. |
| 2026-09-17 | v1.22 | 153 operator memory-limit config (#136) | New **§3.25 row**: `datapipelines.duckdb.memory-limit` (`DATAPIPELINES_DUCKDB_MEMORY_LIMIT`, default empty). Deployment-wide default `memory_limit` for every LAKE engine build when a datasource sets none, restart-to-change, validated at startup with `properties.dialect.memory_limit`'s own grammar. §3.24's `memory_limit` row gains the three-way precedence sentence (datasource property > this key > derived 25%). §3.25's title and intro widened to cover both operator keys. §5 template block appended after `extension-directory` (same block, not a new one). |
| 2026-09-17 | v1.21 | Writable LAKE spill default (#133) | §3.24: unique engine-owned absolute path under `java.io.tmpdir`; writable disk requirement, explicit override and cleanup semantics. |
| 2026-09-17 | v1.20 | 152 LAKE engine scope (#128) | **§3.24 gains the resource-scope paragraph**: a LAKE datasource pool owns ONE embedded DuckDB instance shared by all its pooled connections, so `memory_limit` / `threads` / `temp_directory` are one budget per datasource pool (not per connection, not multiplied by `maximumPoolSize`), the sizing rule is per LAKE datasource on the instance plus a draining generation's headroom, and the file cache survives Hikari's connection replacement but not a pool rebuild. No key added or changed. Status line re-synced to the changelog (it read v1.1 above rows that ended at v1.18) |
| 2026-09-16 | v1.19 | 149 / #125 node_progress cadence | §3.2 gains **`datapipelines.executor.progress-sample-interval-seconds`** (`1`): the floor between two periodic `node_progress` samples of one operation; first entries into a state and the terminal sample are never throttled by it. Mirrored in `application.yml`, `defaults.env`, `secrets.env.example`, `compose.yml`; `WebPropertiesSpecDriftTest` pins the default. |
| 2026-09-14 | v1.18 | 137 mail notices | New **§3.27 Mail**: `datapipelines.mail.host` / `port` (587) / `username` / `password` / `starttls` (true, required-not-opportunistic) / `from` / `reply-to` (empty = from) / `ops-to` (comma list) / `message-stream` (Postmark's optional header). **No `enabled` key — mail is on exactly when `host` and `from` are both set.** §5 template block appended after `duckdb:`; §7 gains the shape rules (host↔from, ops-to without host, mail on without `auth.base-url`, port range, from is an address) and the two `hardened` refusals (`starttls` must be true; a username needs a password) — checks 23 and 24 |
| 2026-09-08 | v1.17 | 091 keys | **§3.19: `datapipelines.deployment.promotion.server-key` is DEPRECATED** in favour of a `server`-kind API key ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)) — mintable by an admin on the API screen, expiring, revocable, rotatable without a restart, and visible in the key list. Both credentials are accepted for one release; the configured value is compared FIRST (so a deployment that has not migrated pays no database read) and its presence raises one WARN at boot. §7 gains that rule (check 23). Fail-closed is unchanged and now has two halves: no configured value AND no live `server` key ⇒ every push refused. |
| 2026-09-07 | v1.16 | 089 dp-lake §D extension bundling | New **§3.25 DuckDB extension directory (dp-lake)**: `datapipelines.duckdb.extension-directory` (`DATAPIPELINES_DUCKDB_EXTENSION_DIRECTORY`, default empty). Set — as the shipped image does via the Dockerfile, which now bundles `httpfs`/`aws`/`iceberg`/`avro` for DuckDB core v1.5.5 for the build's architecture (linux_amd64 or linux_arm64 via `TARGETARCH`, +108 MB uncompressed, ~39 MB at build) — lake connections `SET extension_directory` and emit bare `LOAD`s, never an `INSTALL`, so a hardened dp-lake deployment needs no egress to `extensions.duckdb.org`. Unset keeps the INSTALL+LOAD behavior developer machines rely on. §5 template block appended after `demo:` |
| 2026-09-07 | v1.15 | 089 dp-lake phases B–D | New **§3.24 Lake datasource engine limits (dp-lake)**: the `properties.dialect.memory_limit` / `threads` / `temp_directory` datasource properties (datasource-row keys, NOT `datapipelines.*` env keys — validated per key at save), the default memory limit (25 % of the container's memory as the cgroup-aware JVM reports it, hard-capped at 4 GiB, floored at 64 MiB), and the always-on `preserve_insertion_order = false`. Phases B/C add no operator keys: per-table views and registry-backed introspection are behavior, not configuration |
| 2026-09-06 | v1.14 | 081 one env file | **§3.23, §4 and §6 rewritten to two files.** The contract is `deploy/env/defaults.env` (tracked, every non-secret variable with its shipped value) then `deploy/secrets.env` (git-ignored, credentials and this deployment's own overrides), in that order under every loader; `deploy/secrets.env.example` is the template and names every variable. The five files 075 shipped under `deploy/env/` are deleted, and with them the laptop's load-order inversion — `deploy/compose.laptop-infra.yml` now starts Postgres and Redis from `SPRING_DATASOURCE_PASSWORD` and `DATAPIPELINES_REDIS_PASSWORD`. §6: the posture's two default rows live ONLY in the profile ymls, and no env file may name them. `compose-env-audit.sh` gains the one-authority proof (each bound variable declared in exactly one tracked file) and the valueless-form check. |
| 2026-09-05 | v1.13 | 075 environments and posture | New **§3.23 Environment and posture**: `datapipelines.env` (`DATAPIPELINES_ENV`, default `local` — the ORG's label, renamed from §3.19's `datapipelines.deployment.name`, which survives one release as a WARNing alias), `datapipelines.posture` (`development` \| `hardened`, no default — only the env named `local` gets one for free) and `datapipelines.demo` (a FLAG: the sample-data families; refused under `hardened`), with the normative posture table. §3.4 gains `cookie-secure` (empty = derived from base-url's scheme; `hardened` ships `true`) and `allow-local-only` (the explicit no-OIDC acknowledgement). §6 is now the POSTURE profiles — `application-dev.yml` was really a laptop file and its infrastructure moved to `deploy/env/laptop.env`; `application-development.yml` and `application-hardened.yml` carry the posture defaults, and `spring.profiles.active` is derived from `DATAPIPELINES_POSTURE`. §7 gains four rules (env grammar + alias, posture required, posture/profile alignment, the hardened refusals) and loses the dev-profile guard, which those refusals subsume. §4 and §5 updated. [Environments](environments.md) is the new operator page. |
| 2026-09-05 | v1.12 | 074 published endpoints | New **§3.22 Published endpoints**: `datapipelines.endpoints.timeout-default-seconds` (30) / `-min-seconds` (1) / `-max-seconds` (300); one new §7 rule (min ≤ default ≤ max, min ≥ 1 — the four report together). Deliberately no `page-rows-max`: `DP-Result-Page-Rows` clamps to `result.page-max-rows` on both surfaces (R-EP4). §5 template block appended after `org:`. |
| 2026-09-04 | v1.11 | 072 calculators | New **§3.21 Organisation**: `datapipelines.org.currency.name` / `.symbol`, `fiscal-start-date` (`MM-DD`), `week-start`, `timezone` — five keys that enter every execution Context as `org_*` (calculators design §0.1/§0.2, [Pipeline Contract §7.2](pipeline-contract.md#72-whats-in-the-context--one-namespace-five-tiers)). §5 template block and one new §7 validation rule (all four org checks report together; a month name in `fiscal-start-date` is refused with a message naming `MM-DD`) |
| 2026-09-04 | v1.10 | 068 key-provider seam | New **§3.20 credential key provider**: `datapipelines.db.key-provider` (default `env`, so no deployment needs a config edit), plus the `env` provider's optional `encryption-keys` rotation map and `encryption-key-current`. §2's `encryption-key` row now states that it is key version 1, forever. §5 template and §7 updated — one new validation rule: the provider name must be one this build ships, and that provider's own settings must be present and well-formed (an unknown name short-circuits the rest). |
| 2026-09-02 | v1.9 | 051 auth/config sweep | Added §3.4 `datapipelines.auth.trusted-proxies` (CIDR list, default empty = header ignored; the login limiter and every auth `source_ip` resolve the client through it — R8/T46, deployment.md §6.2) with the §5 template line and two §7 rules (each entry must parse as a CIDR or startup is refused; enforced at the auth module's resolver construction). §3.17: `open-join: true` + `closed` provisioning now refused at startup, naming both keys (T45 — the self-join branch gates on `open-join` alone, so the pair would re-open the membership surface `closed` exists to keep admin-only); §7 gains the rule |
| 2026-08-05 | v1.0 | initial draft | Complete configuration reference: 6 required keys + OIDC, ~30 optional keys, full application.yml template, dev profile, startup validation |
| 2026-08-07 | v1.1 | consistency campaign | Authority + naming-derivation rules (§1); §3 tables and §5 YAML reconciled (unit-suffixed names win); added result.* (D9), sse.disconnect-grace-seconds (D7), idempotency, templates, audit, staging result-batch-size, login rate-limit keys; removed large-result-threshold-bytes and redis.ttl-seconds (superseded by result.*); rate limits per-user; encryption key required with no fallback; precedence section. See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) |
| 2026-08-12 | v1.2 | dev infra ports | §6 dev profile now targets host ports 5434 (Postgres) / 6381 (Redis) instead of the colliding defaults 5432/6379; added the dev-host-ports note. Operator-facing keys and production defaults unchanged. |
| 2026-08-17 | v1.3 | pipeline composition | Added §3.16 `datapipelines.pipelines.max-composition-depth` (default 5) — the depth guard for PIPELINE-node composition |
| 2026-08-26 | v1.3.1 | workspaces (slice 019) | Added §3.17 Workspaces: `datapipelines.workspaces.provisioning-mode` (`auto-per-user` \| `self-serve` \| `closed`, default `self-serve`), `open-join` (default `false`), `member-datasources-enabled` (default `true`). (This row was written later than the section — v1.4's note promised the backfill, but the row itself never landed; recorded and landed 2026-08-29, 025 D3. Keys unchanged by the delay.) |
| 2026-08-28 | v1.4 | sample data, slice A | Added §3.18 Bootstrap: `datapipelines.bootstrap.datasources-file` and `datapipelines.bootstrap.examples-file` (both unset = off), the cross-key rule pairing `datasources-file` with `datapipelines.auth.bootstrap-admin-email`, and the matching §7 validation bullet and §5 template block. Backfills the §3.17 Workspaces row this log was missing (added 2026-08-26 with slice 019, keys unchanged here) |
| 2026-08-29 | v1.5 | local password auth | Added §3.4 `datapipelines.auth.local.*`: `enabled`, `bootstrap-password-hash` / `bootstrap-password` (first-admin seed only, forced first-login change), `lockout.max-failures` / `lockout.duration-minutes` — plus the §5 template block. The §7 "at least one OIDC provider" rule becomes "at least one authentication method"; a provider entry with an empty `client-id` is now ignored with a WARN instead of counting (the stock `google` entry binds empty when its env vars are unset, so a local-accounts-only deployment starts with zero providers). `rate-limit.login-per-minute` description widened to OIDC and local |
| 2026-09-02 | v1.7 | 048 bootstrap seeding fixes | Two §7 rules added, no new keys: (a) the `examples-file` cross-key rule pairing it with `datapipelines.workspaces.provisioning-mode` = `auto-per-user` (021/F5 — the pair validated green while the seeder was structurally unreachable, on the shipped default); (b) `bootstrap` and `local` reserved as OIDC provider names (021/F8 — the `users.provider` placeholders were squatting in an operator-configurable namespace with nothing reserving them). §3.18 gains the matching cross-key paragraph |
| 2026-09-02 | v1.8 | 050 multi-instance round 2 | §3.2: `max-concurrent-executions-global` renamed `max-concurrent-executions-per-instance` (the limit was always per JVM — the old name false at N replicas; 050/R2), old key kept as a one-release deprecated alias (alone → WARN naming the new key; both set and differing → §7 refusal); §3.2 heap note and §5 template updated to the per-instance multiplier; §7 gains the alias rule. §3.11: `event-retention-days` now bound and scheduled (the hourly retention job, M2's sibling) |
| 2026-09-01 | v1.6 | 039 deployment role | Added §3.19 Deployment: `datapipelines.deployment.name` (label only — logged once at boot beside the authoring state; nothing branches on it, pinned by a guard test; deliberately not on `/info`) and `datapipelines.deployment.authoring-enabled` (default `true`; `false` turns the deployment into a promotion receiver whose authoring writes refuse with `*.authoring.disabled`; startup refuses if drafts exist while disabled). The reserved `datapipelines.deployment.promotion.*` sub-block is deliberately NOT declared here — it ships with promotion (Versioning §10.6's fenced sample), per this doc's shipped-keys-only rule. Matching §7 bullets (the one-sided receiver-also-authors WARN; the refuse-on-existing-drafts rule) and §5 template block |
| 2026-09-10 | v1.9 | 112 RBAC round 1 | §3.17: `datapipelines.workspaces.provisioning-mode` and `datapipelines.workspaces.open-join` **removed** (D-R11 — capability moved onto the workspace membership, so who may create a workspace is a role, not a mode). Both are refused BY NAME at startup rather than ignored, and both are gone from the §5 template, `application.yml` and the shipped `.env` — a placeholder with a default is a set key to Spring's relaxed binding, so leaving them there would have refused the product's own boot. §7 loses the two rules that read them (the mode-value check and the `open-join`-under-`closed` check) and the `examples-file` cross-key rule (example seeding now runs once, when `DemoWorkspaceSeeder` creates `demo`); it gains one rule that names both removed keys. `member-datasources-enabled` survives, re-described: it gates whether a workspace ADMIN may register a datasource bound to their own workspace |
