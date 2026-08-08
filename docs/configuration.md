# Configuration Reference

**Status:** v1 (single source of truth for every config key)
**Owner:** datapipelines.co core
**Last updated:** 2026-08-05

---

## 1. Purpose

Every configuration key for datapipelines.co, in one place. Environment variables, YAML paths, defaults, and descriptions. A developer or operator should never need to search across 15 specs to find a config key.

---

## 2. Required Configuration

**The app will not start without these.** Fail-fast on missing values.

| YAML path | Env var | Description |
|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | Metadata DB JDBC URL. Example: `jdbc:postgresql://host:5432/datapipelines` |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | Metadata DB username |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | Metadata DB password |
| `datapipelines.redis.host` | `DATAPIPELINES_REDIS_HOST` | Redis host (claim-check cache + idempotency) |
| `datapipelines.jwt.secret` | `DATAPIPELINES_JWT_SECRET` | Internal JWT signing secret. ≥ 32 bytes random, base64-encoded |
| `datapipelines.db.encryption-key` | `DATAPIPELINES_DB_ENCRYPTION_KEY` | AES-256 master key for datasource password encryption. 32 bytes, base64-encoded |

**OIDC provider configuration** is also required — at least one provider must be configured in `datapipelines.auth.oidc.providers` (in `application.yml`). Each provider requires `client-id`, `client-secret`, and `issuer-uri`. The client-id and client-secret are typically referenced from env vars. See [Auth spec §11.1](auth.md#111-oidc-provider-configuration) for the full format.

The specific env var names depend on which provider(s) the deployment chooses. Examples:

| Env var pattern | For |
|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google Workspace |
| `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET` | Microsoft Entra ID |
| `OKTA_CLIENT_ID`, `OKTA_CLIENT_SECRET` | Okta |
| `KEYCLOAK_CLIENT_ID`, `KEYCLOAK_CLIENT_SECRET` | Keycloak |

The deployment defines these env var names in `application.yml` — they're not hardcoded by the app.

---

## 3. Optional Configuration (with defaults)

### 3.1 Redis

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.redis.port` | `DATAPIPELINES_REDIS_PORT` | `6379` | Redis port |
| `datapipelines.redis.password` | `DATAPIPELINES_REDIS_PASSWORD` | (none) | Redis password |
| `datapipelines.redis.ttl-seconds` | `DATAPIPELINES_REDIS_TTL_SECONDS` | `300` | Claim-check result TTL |

### 3.2 Executor

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.executor.max-parallel-nodes` | `DATAPIPELINES_EXECUTOR_MAX_PARALLEL_NODES` | `4` | Max parallel nodes within one execution |
| `datapipelines.executor.max-concurrent-executions-per-user` | `DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_PER_USER` | `10` | Per-user concurrent execution limit |
| `datapipelines.executor.max-concurrent-executions-global` | `DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_GLOBAL` | `100` | Global concurrent execution limit |
| `datapipelines.executor.node-query-timeout-seconds` | `DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT` | `60` | Per-node JDBC query timeout |
| `datapipelines.executor.execution-timeout-seconds` | `DATAPIPELINES_EXECUTOR_TIMEOUT` | `600` | Overall execution timeout |

### 3.3 Staging (tempdb)

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.staging.h2.mode` | `DATAPIPELINES_STAGING_H2_MODE` | `PostgreSQL` | H2 compatibility mode |
| `datapipelines.staging.h2.max-memory-mb` | `DATAPIPELINES_STAGING_H2_MAX_MEMORY_MB` | `1024` | Per-execution memory limit |
| `datapipelines.staging.h2.insert-batch-size` | `DATAPIPELINES_STAGING_H2_INSERT_BATCH` | `1000` | Rows per INSERT batch |
| `datapipelines.staging.h2.query-timeout-seconds` | `DATAPIPELINES_STAGING_H2_QUERY_TIMEOUT` | `60` | H2 query timeout |

### 3.4 Auth

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.auth.jwt.ttl-hours` | `DATAPIPELINES_AUTH_JWT_TTL_HOURS` | `8` | Session JWT TTL |
| `datapipelines.auth.allowlist.domains` | `DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS` | (empty) | Comma-separated allowed email domains. Empty = open provisioning |
| `datapipelines.auth.api-keys.cache-ttl-seconds` | `DATAPIPELINES_AUTH_API_KEY_CACHE_TTL` | `60` | In-memory cache TTL for validated API keys |
| `datapipelines.auth.api-keys.default-scopes` | `DATAPIPELINES_AUTH_API_KEY_DEFAULT_SCOPES` | `read` | Default scope for new API keys |

### 3.5 REST API + SSE

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.large-result-threshold-bytes` | `DATAPIPELINES_LARGE_RESULT_THRESHOLD` | `1048576` | Inline vs claim-check threshold (1 MB) |
| `datapipelines.sse.heartbeat-interval-seconds` | `DATAPIPELINES_SSE_HEARTBEAT_INTERVAL` | `15` | SSE heartbeat interval |
| `datapipelines.rate-limit.requests-per-second` | `DATAPIPELINES_RATE_LIMIT_RPS` | `100` | Per-API-key requests per second |
| `datapipelines.rate-limit.requests-per-minute` | `DATAPIPELINES_RATE_LIMIT_RPM` | `1000` | Per-API-key requests per minute |

### 3.6 UI

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.ui.theme` | `DATAPIPELINES_UI_THEME` | `saas` | Design system theme name |

### 3.7 Execution History

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.executions.event-retention-days` | `DATAPIPELINES_EXECUTIONS_EVENT_RETENTION` | `7` | How long to keep execution_events rows |
| `datapipelines.executions.stale-timeout-minutes` | `DATAPIPELINES_EXECUTIONS_STALE_TIMEOUT` | `60` | Mark RUNNING executions older than this as ABORTED |

### 3.8 Server

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` | HTTP port |
| `spring.datasource.hikari.maximum-pool-size` | `SPRING_DATASOURCE_HIKARI_MAX_POOL` | `10` | Metadata DB connection pool size |

### 3.9 Observability

| YAML path | Env var | Default | Description |
|---|---|---|---|
| `datapipelines.observability.tracing.enabled` | `DATAPIPELINES_TRACING_ENABLED` | `false` | Enable OpenTelemetry tracing |
| `datapipelines.observability.tracing.endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | (none) | OTLP collector endpoint |
| `datapipelines.observability.logging.format` | `DATAPIPELINES_LOGGING_FORMAT` | `json` (prod), `console` (dev) | Log output format |

---

## 4. Full `application.yml` Template

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME}
    password: ${SPRING_DATASOURCE_PASSWORD}
    hikari:
      maximum-pool-size: ${SPRING_DATASOURCE_HIKARI_MAX_POOL:10}

server:
  port: ${SERVER_PORT:8080}

datapipelines:
  redis:
    host: ${DATAPIPELINES_REDIS_HOST}
    port: ${DATAPIPELINES_REDIS_PORT:6379}
    password: ${DATAPIPELINES_REDIS_PASSWORD:}
    ttl-seconds: ${DATAPIPELINES_REDIS_TTL_SECONDS:300}

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
      cache-ttl-seconds: ${DATAPIPELINES_AUTH_API_KEY_CACHE_TTL:60}
      default-scopes: ${DATAPIPELINES_AUTH_API_KEY_DEFAULT_SCOPES:read}

  executor:
    max-parallel-nodes: ${DATAPIPELINES_EXECUTOR_MAX_PARALLEL_NODES:4}
    max-concurrent-per-user: ${DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_PER_USER:10}
    max-concurrent-global: ${DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_GLOBAL:100}
    node-query-timeout: ${DATAPIPELINES_EXECUTOR_NODE_QUERY_TIMEOUT:60}
    execution-timeout: ${DATAPIPELINES_EXECUTOR_TIMEOUT:600}

  staging:
    h2:
      mode: ${DATAPIPELINES_STAGING_H2_MODE:PostgreSQL}
      max-memory-mb: ${DATAPIPELINES_STAGING_H2_MAX_MEMORY_MB:1024}
      insert-batch-size: ${DATAPIPELINES_STAGING_H2_INSERT_BATCH:1000}
      query-timeout: ${DATAPIPELINES_STAGING_H2_QUERY_TIMEOUT:60}

  large-result-threshold-bytes: ${DATAPIPELINES_LARGE_RESULT_THRESHOLD:1048576}

  sse:
    heartbeat-interval-seconds: ${DATAPIPELINES_SSE_HEARTBEAT_INTERVAL:15}

  rate-limit:
    requests-per-second: ${DATAPIPELINES_RATE_LIMIT_RPS:100}
    requests-per-minute: ${DATAPIPELINES_RATE_LIMIT_RPM:1000}

  ui:
    theme: ${DATAPIPELINES_UI_THEME:saas}

  executions:
    event-retention-days: ${DATAPIPELINES_EXECUTIONS_EVENT_RETENTION:7}
    stale-timeout-minutes: ${DATAPIPELINES_EXECUTIONS_STALE_TIMEOUT:60}

  observability:
    tracing:
      enabled: ${DATAPIPELINES_TRACING_ENABLED:false}
    logging:
      format: ${DATAPIPELINES_LOGGING_FORMAT:json}
```

> **Note:** OIDC provider config is in the app's own YAML namespace (`datapipelines.auth.oidc.providers`), NOT in Spring Security's native `spring.security.oauth2.client.*` namespace. Our `OidcConfig` bean reads this list and builds `ClientRegistration` objects programmatically. See [Auth spec §5.2](auth.md#52-clientregistration-bean-built-at-startup).

---

## 5. Dev Profile (`application-dev.yml`)

Overrides for local development. Activated via `--spring.profiles.active=dev`.

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
    secret: dev-secret-only-not-for-production-use-at-least-32-bytes

  db:
    encryption-key: ZGV2LWVuY3J5cHRpb24ta2V5LTMyLWJ5dGVzIQ==

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

## 6. Config Validation

On startup, the app validates:
- All required env vars present → fail-fast with clear error if missing.
- `DATAPIPELINES_JWT_SECRET` ≥ 32 bytes decoded.
- `DATAPIPELINES_DB_ENCRYPTION_KEY` is exactly 32 bytes decoded.
- `DATAPIPELINES_UI_THEME` is one of the vendored theme names.
- OIDC client IDs and secrets are non-empty.

Validation runs in `@PostConstruct` of a `ConfigValidator` bean. Failures stop startup with a clear log message listing every missing/invalid key.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Complete configuration reference: 10 required keys, ~30 optional keys, full application.yml template, dev profile, startup validation |
