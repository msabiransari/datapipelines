# Configuration Reference

**Status:** v1.1 (single source of truth for every config key)
**Owner:** datapipelines.co core
**Last updated:** 2026-08-07

---

## 1. Purpose

Every configuration key for datapipelines.co, in one place. Environment variables, YAML paths, defaults, and descriptions. A developer or operator should never need to search across 15 specs to find a config key.

**Authority rule:** this document is the ONLY place a configuration key is defined. Other specs reference keys by name and link here — they never restate defaults or introduce keys of their own. A key that does not appear in this document does not exist. (Enforced by `scripts/docs-audit.sh`.)

**Naming rules:**
- YAML paths carry explicit units as suffixes: `-seconds`, `-minutes`, `-hours`, `-days`, `-ms`, `-bytes`, `-mb`, `-rows`.
- Env var names are derived mechanically: `datapipelines.` prefix → `DATAPIPELINES_`, then the YAML path upper-snake-cased. Example: `datapipelines.executor.node-query-timeout-seconds` → `DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_SECONDS`. No abbreviations, no exceptions — the env var is always derivable from the YAML path.

---

## 2. Required Configuration

**The app will not start without these.** Fail-fast on missing values.

| YAML path | Env var | Description |
|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | Metadata DB JDBC URL. Example: `jdbc:postgresql://host:5432/datapipelines` |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | Metadata DB username |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | Metadata DB password |
| `datapipelines.redis.host` | `DATAPIPELINES_REDIS_HOST` | Redis host (results, idempotency, post-completion event log) |
| `datapipelines.jwt.secret` | `DATAPIPELINES_JWT_SECRET` | Internal JWT signing secret. ≥ 32 bytes random, base64-encoded |
| `datapipelines.db.encryption-key` | `DATAPIPELINES_DB_ENCRYPTION_KEY` | AES-256 master key for datasource password encryption. Exactly 32 bytes, base64-encoded. **Required — there is no fallback source.** (KMS-sourced keys are a v1.1 item, see [ROADMAP §2](ROADMAP.md#2-v11-candidates).) |

**OIDC provider configuration** is also required — at least one provider must be configured in `datapipelines.auth.oidc.providers` (in `application.yml`). Each provider requires `client-id`, `client-secret`, and `issuer-uri`. The client-id and client-secret are typically referenced from env vars. See [Auth spec §11.1](auth.md#111-oidc-provider-configuration) for the full format.

The specific env var names depend on which provider(s) the deployment chooses. Examples:

| Env var pattern | For |
|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google Workspace |
| `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET` | Microsoft Entra ID |
| `OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET` | Okta |
| `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` | Keycloak |

The deployment defines these env var names in `application.yml` — they're not hardcoded by the app. (OIDC provider env vars are the one deliberate exception to the naming derivation rule in §1, since the deployment names them.)

---

## 3. Optional Configuration (with defaults)

### 3.1 Redis

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.redis.port` | `6379` | Redis port |
| `datapipelines.redis.password` | (none) | Redis password |

> Env vars are derived per §1 (e.g. `DATAPIPELINES_REDIS_PORT`) and are omitted from the tables below for brevity.

### 3.2 Executor

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.executor.max-parallel-nodes` | `4` | Max parallel nodes within one execution |
| `datapipelines.executor.max-concurrent-executions-per-user` | `10` | Per-user concurrent execution limit |
| `datapipelines.executor.max-concurrent-executions-per-instance` | `100` | Instance-wide concurrent execution limit — **per instance** (050/R2): N replicas admit N × this in total ([Deployment §6.2](deployment.md#62-multi-instance-horizontal-scaling-production)) |
| `datapipelines.executor.max-concurrent-executions-global` | `unset` | **Deprecated alias** for `max-concurrent-executions-per-instance` (one release, 050/R2). Set alone → its value runs and startup logs one WARN naming the new key; set together with the new key and differing → startup refuses. The limit was always per JVM — the old name was false at N replicas |
| `datapipelines.executor.node-query-timeout-seconds` | `60` | Per-node JDBC query timeout. A datasource's own `query_timeout_seconds`, when set, overrides this for nodes on that datasource ([Datasources §5](datasources.md#5-connection-pool-configuration)) |
| `datapipelines.executor.execution-timeout-seconds` | `600` | Overall execution timeout |

### 3.3 Staging (tempdb)

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.staging.h2.mode` | `PostgreSQL` | H2 compatibility mode |
| `datapipelines.staging.h2.max-memory-mb` | `1024` | Per-execution memory limit. A pipeline's `settings.tempdb.config.max_memory_mb`, when present, overrides this for that pipeline ([Pipeline Contract §5](pipeline-contract.md#5-settings)) |
| `datapipelines.staging.h2.insert-batch-size` | `1000` | Rows per INSERT batch when staging source data |
| `datapipelines.staging.h2.result-batch-size` | `10000` | Rows per fetch batch when reading staged data out |
| `datapipelines.staging.h2.query-timeout-seconds` | `60` | H2 query timeout |

> **`max-memory-mb` is a *per-execution* ceiling, not a process-wide one.** Every concurrent execution gets its own tempdb with its own budget, so the aggregate tempdb heap a node can reach on ONE INSTANCE is `max-memory-mb` × `datapipelines.executor.max-concurrent-executions-per-instance` — with the defaults, 1024 MB × 100 **per instance** (050/R2: the multiplier is per-instance; N replicas multiply it again — [Deployment §6.6](deployment.md#66-resource-sizing)). Size the two **together** against the container's heap; setting `max-memory-mb` alone bounds one execution, not the box. A process-wide staging gate is deferred ([ROADMAP](ROADMAP.md)).
>
> A pipeline's `settings.tempdb.config.max_memory_mb` override is **clamped to ≤ this value** — it may lower the operator's ceiling for that pipeline, never raise it. Save-time validation only checks `> 0`, so without the clamp an author could declare an arbitrarily large budget and disable the only ceiling the executor's `withConnection` paths have ([DAG Executor §9](dag-executor.md#9-tempdb-lifecycle-integration)).

### 3.4 Auth

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.auth.base-url` | (none) | The deployment's exact external origin, e.g. `https://dp.example.com` (scheme + host [+ port], no trailing slash). OIDC redirect URIs are built absolutely from it ([Auth §5.2](auth.md#52-clientregistration-bean-built-at-startup)) — never from request headers. **Startup fails when unset while any OIDC provider is configured.** |
| `datapipelines.auth.jwt.ttl-hours` | `8` | Session JWT TTL |
| `datapipelines.auth.allowlist.domains` | (empty) | Comma-separated allowed email domains. Binds to `List<String>` (comma-split; empty string = empty list = open provisioning) |
| `datapipelines.auth.api-keys.cache-ttl-seconds` | `60` | Cache TTL for validated API keys and user `is_active` checks ([Auth §11.4](auth.md#114-api-key-validation-cache)) |
| `datapipelines.auth.api-keys.default-scopes` | `read` | Default scope for new API keys |
| `datapipelines.auth.rate-limit.login-per-minute` | `10` | Per-IP login attempts per minute (OIDC and local) |
| `datapipelines.auth.trusted-proxies` | (empty) | CIDRs of proxies whose `X-Forwarded-For` names the real client — the login limiter and every audit `source_ip` resolve through it ([Deployment §6.2](deployment.md#62-multi-instance-horizontal-scaling-production)). Empty (the default) = the header is ignored entirely and the direct peer is the client — a bare deployment behaves exactly as before. Each entry must parse as a CIDR (a bare IP is a host CIDR, e.g. `10.0.0.5` = `10.0.0.5/32`); anything else refuses startup. Resolution is spoof-safe: an untrusted peer cannot forge a client by setting the header |
| `datapipelines.auth.bootstrap-admin-email` | (none) | Bootstrap admin: when a user with exactly this email is provisioned via OIDC first login, `is_admin` is set true (idempotent, audit-logged as `auth.user.admin_granted` with actor `bootstrap`). The ONLY way a fresh deployment gets its first admin — "first login wins" is explicitly rejected ([Auth §4.4](auth.md#44-bootstrap-admin)) |
| `datapipelines.auth.local.enabled` | `false` | Optional local username/password accounts ([Auth §5A](auth.md#5a-local-password-accounts-optional)) — a second sign-in method for deployments without an IdP. Disabled = the deployment behaves exactly as OIDC-only |
| `datapipelines.auth.local.bootstrap-password-hash` | (none) | Initial credential for the FIRST ADMIN ONLY (the `bootstrap-admin-email` account), as a pre-computed Argon2id hash — the preferred form (produce one with the `hashPassword` Gradle task, [Deployment §5](deployment.md#5-configuration)). Seeding is create-if-absent and idempotent, forces a first-login change, and never applies to ordinary users — passwords are not a config medium |
| `datapipelines.auth.local.bootstrap-password` | (none) | Plaintext alternative to the hash form, accepted for zero-setup demos: always sets `must_change_password`, is never logged, and startup is refused when both forms are set |
| `datapipelines.auth.local.lockout.max-failures` | `5` | Consecutive failed local logins that lock the account — per-account, complementing the per-IP `rate-limit.login-per-minute`, which cannot stop a slow spray against one account |
| `datapipelines.auth.local.lockout.duration-minutes` | `15` | How long a locked account refuses local login. An admin unlock or password reset clears the lock early |

### 3.5 Results

Every completed execution's caller result is stored in Redis and read through the result cursor ([REST API §7](rest-api.md#7-result-delivery)).

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.result.ttl-default-seconds` | `300` | Result TTL when the client sends no `DP-Result-TTL-Seconds` header |
| `datapipelines.result.ttl-min-seconds` | `60` | Lower clamp for client-requested TTL |
| `datapipelines.result.ttl-max-seconds` | `3600` | Upper clamp for client-requested TTL |
| `datapipelines.result.max-size-bytes` | `104857600` | Hard cap on a caller result (100 MB). Exceeding it fails the execution with `result.too_large` |
| `datapipelines.result.page-size-rows` | `1000` | Rows in the inline first page of `data_ready`, and the default `limit` for cursor reads |
| `datapipelines.result.page-max-rows` | `100000` | Upper bound on the cursor `limit` parameter |

### 3.6 SSE

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.sse.heartbeat-interval-seconds` | `15` | SSE heartbeat comment interval |
| `datapipelines.sse.disconnect-grace-seconds` | `30` | Grace period after client disconnect before the in-flight execution is cancelled ([REST API §6.8](rest-api.md#68-client-disconnect)) |
| `datapipelines.sse.max-streams-per-user` | `50` | Concurrent SSE streams per user |

### 3.7 Rate Limiting

Limits are **per user** (an API key inherits its owner's budget — minting more keys does not raise the limit).

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.rate-limit.requests-per-second` | `100` | Per-user requests per second |
| `datapipelines.rate-limit.requests-per-minute` | `1000` | Per-user requests per minute |

### 3.8 Idempotency

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.idempotency.ttl-seconds` | `86400` | Retention of `Idempotency-Key` records in Redis |

### 3.9 Templates

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.templates.cache-size` | `500` | Parsed-template cache entries |
| `datapipelines.templates.render-timeout-ms` | `5000` | Hard limit on a single template render |
| `datapipelines.templates.max-body-chars` | `262144` | Max template `body` length accepted at save (256K chars); over-cap bodies are rejected with `template.validation.syntax_error` before parsing ([Templates §4.2](templates.md#42-allowed-freemarker-constructs)) — bounds parse cost and heap against an adversarial body |

### 3.10 UI

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.ui.theme` | `saas` | Design system theme name. Validated at startup against the vendored themes in `modules/web/src/main/resources/static/vendor/design-system/` |

### 3.11 Execution History

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.executions.event-retention-days` | `7` | How long to keep `execution_events` rows (Postgres, the durable record) past their execution's completion — enforced by the hourly retention job (050/T60, safe for N replicas: one idempotent `DELETE`; `pipeline_executions` rows are never touched). The post-completion Redis event log lives for 1 hour, not configurable |
| `datapipelines.executions.stale-timeout-minutes` | `60` | Mark RUNNING executions older than this as ABORTED (crash sweep) |

### 3.12 Audit

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.audit.retention-days` | `365` | Retention of audit-log rows |

### 3.13 Server

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` | HTTP port |
| `spring.datasource.hikari.maximum-pool-size` | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `10` | Metadata DB connection pool size |

### 3.14 Framework wiring keys

These framework key paths appear in `application.yml` as internal wiring. They are listed here so the §1 authority sentence stays literally true. With the single exception of `management.server.port` (below), operators do not set them and deployments must not override them:

| Path | Why it exists |
|---|---|
| `spring.application.name` | Log/metric attribution (`datapipelines`) |
| `spring.data.redis.host` / `.port` / `.password` | Bridge binding the canonical `DATAPIPELINES_REDIS_*` env vars onto Spring Boot's Redis autoconfiguration (see the binding note under §5) |
| `spring.flyway.enabled` / `.locations` / `.baseline-on-migrate` | Migration wiring — Flyway always runs on startup ([Deployment §8.2](deployment.md#82-database-migrations)) |
| `management.endpoints.web.exposure.include` | `"health"` — served on the **management port** only; `prometheus` joins it when the metrics registry lands ([Observability §6.4](observability.md#64-actuator-security)). **Must not be set to `""` or `exclude: "*"`:** Spring's `@ConditionalOnAvailableEndpoint` keys bean creation off exposure, so an empty include falls back to Boot's default set (re-exposing health on whatever port serves actuator), and excluding everything deletes the `HealthEndpoint` bean the root `/health` controller injects — context startup fails outright. |
| `management.health.diskspace.enabled` | `false` — the health contract has no disk component ([REST API §11.1](rest-api.md#111-health-check)) |

**Operator-tunable exception:**

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `management.server.port` | `MANAGEMENT_SERVER_PORT` | `9090` | Separate management port serving `/actuator/health` (and later `/actuator/prometheus`) — never publish it; nothing actuator is routable on the application port. |
| `management.server.address` | `MANAGEMENT_SERVER_ADDRESS` | `127.0.0.1` | **Loopback by default** — the management surface is silently-public on no deployment shape. Kubernetes scraping (scraper → pod IP) requires explicitly setting `0.0.0.0`, paired with a NetworkPolicy restricting the port to the monitoring namespace ([Deployment §9](deployment.md#9-security-hardening-checklist-deployment)). Explicit opt-open, never default-open. |

### 3.15 Observability

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.observability.tracing.enabled` | `DATAPIPELINES_OBSERVABILITY_TRACING_ENABLED` | `false` | Enable OpenTelemetry tracing |
| `datapipelines.observability.tracing.endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | (none) | OTLP collector endpoint (standard OTel env var, exception to §1 derivation) |
| `datapipelines.observability.logging.format` | `DATAPIPELINES_OBSERVABILITY_LOGGING_FORMAT` | `json` (prod), `console` (dev) | Log output format |

### 3.16 Pipelines

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.pipelines.max-composition-depth` | `5` | Deepest admitted chain of pipelines executing pipelines (a PIPELINE node spawning a child execution). Enforced at save time and again at runtime; must be ≥ 1 |

### 3.17 Workspaces

Workspace provisioning mode (design 2026-08-16-workspaces §7, [auth.md §4.2/§5.6](auth.md#56-workspace-resolution--the-dp-workspace-header)): `auto-per-user` mints a personal workspace on first OIDC login; `self-serve` lets any authenticated user create workspaces; `closed` restricts creation to `admin`.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.workspaces.provisioning-mode` | `self-serve` | `auto-per-user` \| `self-serve` \| `closed` |
| `datapipelines.workspaces.open-join` | `false` | `self-serve` only: `true` lists all workspaces as joinable by any authenticated user; `false` = members are added by a workspace owner. `open-join: true` under `closed` provisioning is refused at startup (§7) — it would re-open the membership surface `closed` exists to keep admin-only |
| `datapipelines.workspaces.member-datasources-enabled` | `true` | May non-admin members create workspace-bound datasources (datasource visibility gate) |

### 3.18 Bootstrap

Config-declared content applied at startup (design 2026-08-16-sample-data §6/§6.1). Both keys name a **file already on the container's filesystem** — the app never fetches an artifact at runtime; downloading and verifying artifacts is a deployment step (D5) — and for both, **unset (or empty) means the feature is off**. There is no separate enable flag to disagree with the path.

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.bootstrap.datasources-file` | (none) | Path to a YAML file of datasource definitions registered **create-if-absent** at startup ([Datasources §8A](datasources.md#8a-bootstrap-registration-config-declared-datasources)). Set-but-unreadable, unparseable, or carrying an entry that fails [§9 validation](datasources.md#9-validation-rules) = fail-fast startup |
| `datapipelines.bootstrap.examples-file` | (none) | Path to a JSON file of example templates and pipelines seeded into each personal workspace at `auto-per-user` provisioning, through the same import services as `POST /pipelines/import` and `POST /templates/import`. Shape: `{"templates": [...], "pipelines": [...]}`, each array element exactly what its import endpoint takes. Set-but-unreadable or unparseable = fail-fast startup; an entry that fails import validation fails the provisioning login |

**Cross-key rule:** `datasources-file` set while [`datapipelines.auth.bootstrap-admin-email`](#34-auth) is unset is a startup refusal naming both keys. Bootstrap-registered datasources are `created_by` that user, and registration runs before anyone has logged in, so the row is pre-provisioned from that address ([Auth §4.4](auth.md#44-bootstrap-admin)). `examples-file` carries no such rule: seeding runs at first login, under the identity of the user logging in.

**Cross-key rule (`examples-file`):** `examples-file` set while [`datapipelines.workspaces.provisioning-mode`](#317-workspaces) is anything but `auto-per-user` — including its **shipped default** `self-serve` — is a startup refusal naming both keys. Seeding runs only when first login provisions a personal workspace; under any other mode the file is read and validated at startup and then never seeded, silently. Set the mode, or unset the file.

### 3.19 Deployment

The deployment-role settings ([Versioning §5.5](versioning.md#55-drafts-are-a-deployment-capability-039)) — grouped one-per-concern like `auth`, `executor`, `staging`. **`name` is a LABEL only**: nothing branches on it (a single-server deployment honestly writes `prod` and must not be treated differently for it — the no-branching rule is pinned by a guard test). It is logged once at startup beside the authoring state, and is deliberately not exposed on `/info`.

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.deployment.name` | `DATAPIPELINES_DEPLOYMENT_NAME` | (empty) | The deployment's label (e.g. `dev`, `prod`). No behaviour depends on it — its only consumer is the startup posture log line. A later round may surface it to signed-in users in the UI banner |
| `datapipelines.deployment.authoring-enabled` | `DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED` | `true` | The authoring **capability**. When `false`, every pipeline/template authoring write (create, update/draft, release, discard, delete) is refused with `pipeline.authoring.disabled` / `template.authoring.disabled` — fail-closed, naming the reason. Reads, execution and **import are unaffected** (promotion imports RELEASED versions; that is the one writer a receiver must accept). Startup REFUSES if drafts still exist while this is `false` (§7) |

The `datapipelines.deployment.promotion.*` sub-block (the receiver's `server-key` and the sender's `target.{base-url, server-key}`) is **reserved but not shipped** — it lands with promotion itself ([Versioning §10.6](versioning.md#106-the-promotion-peer-credential--a-shared-server-key-ratified-2026-09-01) keeps the shape in a fenced sample). This document is the authority for shipped keys only.

---

## 4. Precedence

Resolution order for any key, highest first:

1. Environment variable (via the `${ENV:default}` placeholder).
2. Active profile YAML (`application-dev.yml`, etc.).
3. Base `application.yml` default.

Two documented per-entity overrides sit above global config at runtime (they are data, not config):
- Pipeline `settings.tempdb.config.max_memory_mb` overrides `datapipelines.staging.h2.max-memory-mb` for that pipeline.
- Datasource `query_timeout_seconds` overrides `datapipelines.executor.node-query-timeout-seconds` for nodes on that datasource.

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
  data:
    redis:                       # §3.14 bridge — operators set only DATAPIPELINES_REDIS_*
      host: ${DATAPIPELINES_REDIS_HOST}
      port: ${DATAPIPELINES_REDIS_PORT:6379}
      password: ${DATAPIPELINES_REDIS_PASSWORD:}
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false

server:
  port: ${SERVER_PORT:8080}

management:                      # §3.14 — actuator on the management port ONLY
  server:
    port: ${MANAGEMENT_SERVER_PORT:9090}
    address: ${MANAGEMENT_SERVER_ADDRESS:127.0.0.1}   # loopback default; 0.0.0.0 only with a NetworkPolicy
  endpoints:
    web:
      exposure:
        include: "health"        # never "" and never exclude:"*" — see §3.14
  health:
    diskspace:
      enabled: false

datapipelines:
  redis:
    host: ${DATAPIPELINES_REDIS_HOST}
    port: ${DATAPIPELINES_REDIS_PORT:6379}
    password: ${DATAPIPELINES_REDIS_PASSWORD:}

  jwt:
    secret: ${DATAPIPELINES_JWT_SECRET}

  db:
    encryption-key: ${DATAPIPELINES_DB_ENCRYPTION_KEY}

  auth:
    oidc:
      providers:
        # client-id defaulting to empty = the entry is IGNORED with a WARN (§7);
        # a local-accounts-only deployment starts with zero OIDC providers.
        - name: google
          client-id: ${GOOGLE_CLIENT_ID:}
          client-secret: ${GOOGLE_CLIENT_SECRET:}
          issuer-uri: https://accounts.google.com
          display-name: "Sign in with Google"
        - name: microsoft
          client-id: ${MICROSOFT_CLIENT_ID:}
          client-secret: ${MICROSOFT_CLIENT_SECRET:}
          issuer-uri: https://login.microsoftonline.com/common/v2.0
          display-name: "Sign in with Microsoft"
        # Add more providers as needed (Okta, Auth0, Keycloak, etc.)
    jwt:
      ttl-hours: ${DATAPIPELINES_AUTH_JWT_TTL_HOURS:8}
    allowlist:
      domains: ${DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS:}
    api-keys:
      cache-ttl-seconds: ${DATAPIPELINES_AUTH_API_KEYS_CACHE_TTL_SECONDS:60}
      default-scopes: ${DATAPIPELINES_AUTH_API_KEYS_DEFAULT_SCOPES:read}
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

  executor:
    max-parallel-nodes: ${DATAPIPELINES_EXECUTOR_MAX_PARALLEL_NODES:4}
    max-concurrent-executions-per-user: ${DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_EXECUTIONS_PER_USER:10}
    # Per-INSTANCE ceiling (050/R2); the deprecated `max-concurrent-executions-global` alias is
    # deliberately absent here — it binds only when an operator still sets it.
    max-concurrent-executions-per-instance: ${DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_EXECUTIONS_PER_INSTANCE:100}
    node-query-timeout-seconds: ${DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_SECONDS:60}
    execution-timeout-seconds: ${DATAPIPELINES_EXECUTOR_EXECUTION_TIMEOUT_SECONDS:600}

  pipelines:
    max-composition-depth: ${DATAPIPELINES_PIPELINES_MAX_COMPOSITION_DEPTH:5}

  deployment:
    name: ${DATAPIPELINES_DEPLOYMENT_NAME:}
    authoring-enabled: ${DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED:true}

  workspaces:
    provisioning-mode: ${DATAPIPELINES_WORKSPACES_PROVISIONING_MODE:self-serve}
    open-join: ${DATAPIPELINES_WORKSPACES_OPEN_JOIN:false}
    member-datasources-enabled: ${DATAPIPELINES_WORKSPACES_MEMBER_DATASOURCES_ENABLED:true}

  staging:
    h2:
      mode: ${DATAPIPELINES_STAGING_H2_MODE:PostgreSQL}
      max-memory-mb: ${DATAPIPELINES_STAGING_H2_MAX_MEMORY_MB:1024}
      insert-batch-size: ${DATAPIPELINES_STAGING_H2_INSERT_BATCH_SIZE:1000}
      result-batch-size: ${DATAPIPELINES_STAGING_H2_RESULT_BATCH_SIZE:10000}
      query-timeout-seconds: ${DATAPIPELINES_STAGING_H2_QUERY_TIMEOUT_SECONDS:60}

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

  ui:
    theme: ${DATAPIPELINES_UI_THEME:saas}

  executions:
    event-retention-days: ${DATAPIPELINES_EXECUTIONS_EVENT_RETENTION_DAYS:7}
    stale-timeout-minutes: ${DATAPIPELINES_EXECUTIONS_STALE_TIMEOUT_MINUTES:60}

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
```

> **Note:** OIDC provider config is in the app's own YAML namespace (`datapipelines.auth.oidc.providers`), NOT in Spring Security's native `spring.security.oauth2.client.*` namespace. Our `OidcConfig` bean reads this list and builds `ClientRegistration` objects programmatically. See [Auth spec §5.2](auth.md#52-clientregistration-bean-built-at-startup).

> **Internal binding note (Redis, 2026-08-07):** Spring Boot's Redis autoconfiguration reads `spring.data.redis.*`, so `application.yml` carries an internal bridge (`spring.data.redis.host: ${DATAPIPELINES_REDIS_HOST}` etc.) mapping the canonical `datapipelines.redis.*` keys onto it. This introduces NO operator-facing keys — operators set only the `DATAPIPELINES_REDIS_*` variables defined here. The bridge is an implementation detail and may be replaced by an explicit `LettuceConnectionFactory` bound to `@ConfigurationProperties("datapipelines.redis")`.

---

## 6. Dev Profile (`application-dev.yml`)

Overrides for local development. Activated via `--spring.profiles.active=dev`.

**No literal secrets, even in dev (2026-08-07 security review):** the dev profile references `${DATAPIPELINES_JWT_SECRET}` and `${DATAPIPELINES_DB_ENCRYPTION_KEY}` exactly like production — the values come from the developer's git-ignored `.env.local` ([DEVELOPMENT.md §4](../DEVELOPMENT.md), generated with `openssl rand -base64 32`). Earlier revisions embedded working literals here; those were packaged into every production jar (`src/main/resources`), meaning one stray `SPRING_PROFILES_ACTIVE=dev` in a production manifest would have run real infrastructure on publicly-known keys — forgeable admin JWTs and decryptable datasource credentials. (The literals were also invalid: the "32-byte" AES key decoded to 28 bytes, and the JWT secret was not legal base64 — either would have failed the §7 validator.) The `ConfigValidator` additionally refuses to start when the `dev` profile is active against non-localhost infrastructure (§7).

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5434/datapipelines   # host port 5434 — see note below
    username: datapipelines
    password: datapipelines

datapipelines:
  redis:
    host: localhost
    port: 6381

  jwt:
    secret: ${DATAPIPELINES_JWT_SECRET}         # from .env.local — never a literal, even in dev (see note below)

  db:
    encryption-key: ${DATAPIPELINES_DB_ENCRYPTION_KEY}   # from .env.local — never a literal, even in dev

  auth:
    allowlist:
      domains: ""                  # open provisioning in dev

  ui:
    theme: saas

  observability:
    logging:
      format: console               # human-readable in dev
```

> **Dev host ports (2026-08-12):** the dev Postgres listens on host port **5434** and dev Redis on **6381** — not the universal defaults 5432/6379, which collide with other local stacks on developer machines (Postgres.app/brew default to 5432). The host mapping lives in `deploy/docker-compose.dev.yml`; these YAML values must stay in sync with it and with DEVELOPMENT.md §2/§4. In production, `SPRING_DATASOURCE_URL` and `DATAPIPELINES_REDIS_*` are operator-set and unaffected.

---

## 7. Config Validation

On startup, the app validates:
- All required keys present → fail-fast with a clear error if missing.
- `DATAPIPELINES_JWT_SECRET` ≥ 32 bytes decoded.
- `DATAPIPELINES_DB_ENCRYPTION_KEY` is exactly 32 bytes decoded.
- `DATAPIPELINES_UI_THEME` matches a vendored theme directory.
- At least one authentication method: a fully-configured OIDC provider (non-empty `client-id`, `client-secret`, and `issuer-uri`) or `datapipelines.auth.local.enabled=true`. A provider entry with an empty `client-id` is ignored with a WARN — it does not count, and it is not a violation on its own.
- `datapipelines.auth.local.bootstrap-password` and `datapipelines.auth.local.bootstrap-password-hash` are never both set; either seed requires `datapipelines.auth.local.enabled=true` AND `datapipelines.auth.bootstrap-admin-email` — each violation names both keys.
- `datapipelines.auth.local.lockout.max-failures` and `datapipelines.auth.local.lockout.duration-minutes` are positive integers.
- The deprecated executor alias `datapipelines.executor.max-concurrent-executions-global` (050/R2): set alone → its value runs and startup logs one WARN naming `max-concurrent-executions-per-instance`; set together with the new key and differing → startup REFUSES naming both keys.
- `result.ttl-min-seconds` ≤ `result.ttl-default-seconds` ≤ `result.ttl-max-seconds`.
- `datapipelines.workspaces.provisioning-mode` is one of `auto-per-user` | `self-serve` | `closed`.
- `datapipelines.workspaces.open-join: true` together with `closed` provisioning is refused, naming both keys (§3.17) — open-join is a `self-serve` knob, and under `closed` it would let any authenticated user self-join any workspace, the exact surface `closed` exists to close.
- Every `datapipelines.auth.trusted-proxies` entry parses as a CIDR (a bare IP is a host CIDR); anything else refuses startup at the auth module's resolver construction — a typo'd range must not silently widen proxy trust.
- `datapipelines.bootstrap.datasources-file` is not set without `datapipelines.auth.bootstrap-admin-email` (§3.18) — the violation names both keys.
- `datapipelines.bootstrap.examples-file` is not set while `datapipelines.workspaces.provisioning-mode` is anything but `auto-per-user` (§3.18) — the violation names both keys. Only `auto-per-user` provisions the personal workspace the examples are seeded into, so any other mode (the shipped default included) leaves the configured file permanently unseeded. A mode that is misspelled is reported by the mode check alone, not twice.
- No OIDC provider is **named** `bootstrap` or `local`. Those are the `users.provider` values the system writes for identities it creates itself (§6.1 bootstrap actor, §5A local accounts), and a provider's configured name is written to that column verbatim — an external provider under either name would be indistinguishable from them. The reservation is case-insensitive and applies to an entry with a blank `client-id` too.
- **Dev-profile guard:** when the `dev` profile is active and any production indicator is present (non-localhost `spring.datasource.url`, non-localhost `datapipelines.redis.host`, or a `prod`/`production` profile also active), startup fails with a clear error. Dev convenience settings must never run against production infrastructure.
- **Redis auth warning:** when `datapipelines.redis.password` is empty and `datapipelines.redis.host` is not loopback, log a structured WARN (production Redis holds materialized caller results — [Deployment §7.3](deployment.md#9-security-hardening-checklist-deployment)).
- **Deployment posture (§3.19):** the deployment `name` and the authoring state are logged once at boot (the label's only consumer — no code branches on it, pinned by a guard test). When a promotion receiver key is configured AND `datapipelines.deployment.authoring-enabled=true`, log a structured WARN — a promotion receiver should not author (Versioning D7), though a one-box deployment may legitimately be both. **This check is currently one-sided**: the promotion sub-block is reserved, so its half wires in when promotion ships. And when authoring is DISABLED while draft pipeline/template versions still exist, startup FAILS naming them: someone authored on a receiver and version alignment may already be broken (Versioning §5.5/§9.3).

The validator's own test suite must assert that the documented dev setup (env vars from `.env.local`) passes the **production** rules — so a broken dev value gets fixed at the data, never by weakening the check.

Validation runs in `@PostConstruct` of a `ConfigValidator` bean. Failures stop startup with a clear log message listing every missing/invalid key.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
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
