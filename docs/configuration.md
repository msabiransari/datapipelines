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
| `datapipelines.executor.max-concurrent-executions-global` | `100` | Global concurrent execution limit |
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

### 3.4 Auth

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.auth.jwt.ttl-hours` | `8` | Session JWT TTL |
| `datapipelines.auth.allowlist.domains` | (empty) | Comma-separated allowed email domains. Binds to `List<String>` (comma-split; empty string = empty list = open provisioning) |
| `datapipelines.auth.api-keys.cache-ttl-seconds` | `60` | Cache TTL for validated API keys and user `is_active` checks ([Auth §11.4](auth.md#114-api-key-validation-cache)) |
| `datapipelines.auth.api-keys.default-scopes` | `read` | Default scope for new API keys |
| `datapipelines.auth.rate-limit.login-per-minute` | `10` | Per-IP OIDC login attempts per minute |
| `datapipelines.auth.bootstrap-admin-email` | (none) | Bootstrap admin: when a user with exactly this email is provisioned via OIDC first login, `is_admin` is set true (idempotent, audit-logged as `auth.user.admin_granted` with actor `bootstrap`). The ONLY way a fresh deployment gets its first admin — "first login wins" is explicitly rejected ([Auth §4.4](auth.md#44-bootstrap-admin)) |

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

### 3.10 UI

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.ui.theme` | `saas` | Design system theme name. Validated at startup against the vendored themes in `modules/web/src/main/resources/static/vendor/design-system/` |

### 3.11 Execution History

| YAML path | Default | Description |
|---|---|---|
| `datapipelines.executions.event-retention-days` | `7` | How long to keep `execution_events` rows (Postgres, the durable record). The post-completion Redis event log lives for 1 hour, not configurable |
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
        - name: google
          client-id: ${GOOGLE_CLIENT_ID}
          client-secret: ${GOOGLE_CLIENT_SECRET}
          issuer-uri: https://accounts.google.com
          display-name: "Sign in with Google"
        - name: microsoft
          client-id: ${MICROSOFT_CLIENT_ID}
          client-secret: ${MICROSOFT_CLIENT_SECRET}
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

  executor:
    max-parallel-nodes: ${DATAPIPELINES_EXECUTOR_MAX_PARALLEL_NODES:4}
    max-concurrent-executions-per-user: ${DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_EXECUTIONS_PER_USER:10}
    max-concurrent-executions-global: ${DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_EXECUTIONS_GLOBAL:100}
    node-query-timeout-seconds: ${DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT_SECONDS:60}
    execution-timeout-seconds: ${DATAPIPELINES_EXECUTOR_EXECUTION_TIMEOUT_SECONDS:600}

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
    url: jdbc:postgresql://localhost:5432/datapipelines
    username: datapipelines
    password: datapipelines

datapipelines:
  redis:
    host: localhost
    port: 6379

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

---

## 7. Config Validation

On startup, the app validates:
- All required keys present → fail-fast with a clear error if missing.
- `DATAPIPELINES_JWT_SECRET` ≥ 32 bytes decoded.
- `DATAPIPELINES_DB_ENCRYPTION_KEY` is exactly 32 bytes decoded.
- `DATAPIPELINES_UI_THEME` matches a vendored theme directory.
- At least one OIDC provider with non-empty `client-id`, `client-secret`, and `issuer-uri`.
- `result.ttl-min-seconds` ≤ `result.ttl-default-seconds` ≤ `result.ttl-max-seconds`.
- **Dev-profile guard:** when the `dev` profile is active and any production indicator is present (non-localhost `spring.datasource.url`, non-localhost `datapipelines.redis.host`, or a `prod`/`production` profile also active), startup fails with a clear error. Dev convenience settings must never run against production infrastructure.
- **Redis auth warning:** when `datapipelines.redis.password` is empty and `datapipelines.redis.host` is not loopback, log a structured WARN (production Redis holds materialized caller results — [Deployment §7.3](deployment.md#9-security-hardening-checklist-deployment)).

The validator's own test suite must assert that the documented dev setup (env vars from `.env.local`) passes the **production** rules — so a broken dev value gets fixed at the data, never by weakening the check.

Validation runs in `@PostConstruct` of a `ConfigValidator` bean. Failures stop startup with a clear log message listing every missing/invalid key.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Complete configuration reference: 6 required keys + OIDC, ~30 optional keys, full application.yml template, dev profile, startup validation |
| 2026-08-07 | v1.1 | consistency campaign | Authority + naming-derivation rules (§1); §3 tables and §5 YAML reconciled (unit-suffixed names win); added result.* (D9), sse.disconnect-grace-seconds (D7), idempotency, templates, audit, staging result-batch-size, login rate-limit keys; removed large-result-threshold-bytes and redis.ttl-seconds (superseded by result.*); rate limits per-user; encryption key required with no fallback; precedence section. See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) |
