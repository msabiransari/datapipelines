# Deployment & Packaging Specification

**Status:** v1 draft (deferred — to be elaborated before first production deployment)
**Owner:** datapipelines.co core
**Depends on:** all other specs
**Last updated:** 2026-08-05

---

## 1. Purpose

This spec defines **how datapipelines.co is packaged, distributed, and deployed** in self-hosted environments. The product is open-source and self-hosted; this document covers the artifacts we produce and the deployment patterns we support.

This is **lighter than the core specs** — most operational concerns are the operator's responsibility (we ship the artifact, they run it). We document our artifacts, required infrastructure, configuration surface, and recommended deployment patterns.

---

## 2. Design Principles

1. **One artifact, many deployments.** A single Docker image runs in dev, staging, prod. Configuration via env vars + mounted files; no build-time decisions.
2. **Stateless app, externalized state.** The app has no local persistent state. Metadata DB (Postgres) and Redis (claim-check cache) are external. H2 staging is per-request in-memory.
3. **Container-first.** Primary distribution is a Docker image. JVM-only deployments (no container) are supported but secondary.
4. **k8s-native but not k8s-required.** Kubernetes is the recommended target. Docker Compose works for small / single-node deployments. Bare JVM works for development.
5. **Configurable without forking.** Every deployment-relevant knob is an env var or config-file value. Operators do not edit source code to deploy.
6. **Open-source distribution.** The image and JARs are published to public registries / Maven Central. No proprietary distribution channel.

---

## 3. Build Artifacts

### 3.1 Docker image

Published to `ghcr.io/datapipelines/datapipelines:{version}` and `docker.io/datapipelines/datapipelines:{version}` (mirror).

- **Base image**: `eclipse-temurin:21-jre-jammy` (LTS JDK 21, Ubuntu Jammy).
- **Layers**: multi-stage build (`gradle:8.7-jdk21` builder → `eclipse-temurin:21-jre-jammy` runtime).
- **Size target**: < 250 MB compressed.
- **User**: non-root (`datapipelines` user, UID 1000).
- **Entrypoint**: `java -jar /app/app.jar`.

### 3.2 JAR distribution

- Published to Maven Central: `co.datapipelines:datapipelines-app:{version}`.
- Executable Spring Boot fat JAR (`./gradlew bootJar`).
- Runnable directly: `java -jar datapipelines-app.jar`.

### 3.3 Module artifacts (for embedding)

- Each module published to Maven Central: `co.datapipelines:typesystem`, `co.datapipelines:dag`, etc.
- Allows third parties to embed parts of datapipelines.co in their own products (e.g., use our type system, or our MCP server in a different application).
- Marked "experimental API" until v1.1 stabilizes module boundaries.

### 3.4 Source distribution

- GitHub release per version, with signed tag.
- Source tarball attached to the release.
- Reproducible builds (Gradle lockfile committed, exact JDK pinned via toolchain).

---

## 4. Required Infrastructure

### 4.1 Postgres (metadata DB)

- **Version**: 14+ recommended.
- **Purpose**: persistent storage for pipelines, templates, datasources, executions, audit log.
- **Provisioning**: operator-managed. We support connecting to an existing Postgres instance.
- **Database size**: small (a typical deployment has < 1 GB metadata).

Configured via:
- `SPRING_DATASOURCE_URL=jdbc:postgresql://...`
- `SPRING_DATASOURCE_USERNAME=...`
- `SPRING_DATASOURCE_PASSWORD=...`
- `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=10`

### 4.2 Redis (claim-check cache)

- **Version**: 6+ recommended.
- **Purpose**: temporary storage for large pipeline results (claim-check pattern).
- **Provisioning**: operator-managed.
- **Memory**: depends on result-size patterns. Default config assumes 512 MB.

Configured via:
- `DATAPIPELINES_REDIS_HOST=...`
- `DATAPIPELINES_REDIS_PORT=6379`
- `DATAPIPELINES_REDIS_PASSWORD=...` (optional)
- `DATAPIPELINES_REDIS_TTL_SECONDS=300`

### 4.3 Network egress

The app must reach:
- Configured datasources (PG, Oracle, MSSQL, MySQL, DuckDB files, SQLite files).
- The metadata Postgres.
- Redis.
- (Optional) OTLP collector, Sentry, KMS — if those features are configured.

The app **does not** require inbound network access beyond the HTTP/MCP ports it serves.

---

## 5. Configuration

### 5.1 Environment variables (required)

| Variable | Description |
|---|---|
| `SPRING_DATASOURCE_URL` | Metadata Postgres JDBC URL. |
| `SPRING_DATASOURCE_USERNAME` | Metadata DB username. |
| `SPRING_DATASOURCE_PASSWORD` | Metadata DB password. |
| `DATAPIPELINES_REDIS_HOST` | Redis host for claim-check cache. |
| `DATAPIPELINES_REDIS_PORT` | Redis port. |
| `DATAPIPELINES_JWT_SECRET` | JWT signing secret (≥ 32 bytes random, base64). |
| `DATAPIPELINES_DB_ENCRYPTION_KEY` | Master key for encrypting datasource credentials (32 bytes base64). |

### 5.2 Environment variables (optional, with defaults)

| Variable | Default | Description |
|---|---|---|
| `DATAPIPELINES_REDIS_PASSWORD` | (none) | Redis password. |
| `DATAPIPELINES_REDIS_TTL_SECONDS` | 300 | Claim-check TTL. |
| `DATAPIPELINES_EXECUTOR_MAX_PARALLEL_NODES` | 4 | Max parallel nodes per execution. |
| `DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_EXECUTIONS_PER_USER` | 10 | Per-user concurrency limit. |
| `DATAPIPELINES_EXECUTOR_MAX_CONCURRENT_EXECUTIONS_GLOBAL` | 100 | Global concurrency limit. |
| `DATAPIPELINES_STAGING_MAX_MEMORY_MB` | 1024 | Per-execution H2 memory limit. |
| `DATAPIPELINES_AUTH_JWT_TTL_HOURS` | 8 | Session token TTL. |
| `DATAPIPELINES_AUTH_RATE_LIMIT_LOGIN_PER_MINUTE` | 10 | Login rate limit. |
| `DATAPIPELINES_LARGE_RESULT_THRESHOLD_BYTES` | 1048576 | Inline-vs-claim-check threshold. |
| `SERVER_PORT` | 8080 | HTTP port. |
| `SERVER_TLS_ENABLED` | false | TLS termination at app (recommended: terminate at proxy/load balancer). |

### 5.3 Full config file

Operators can mount `application.yml` at `/etc/datapipelines/application.yml` (configurable via `SPRING_CONFIG_ADDITIONAL_LOCATION`). All env vars above are also expressible as YAML keys.

---

## 6. Deployment Patterns

### 6.1 Single-instance (dev / small team)

One instance handles everything — UI, API, MCP, pipeline execution. Docker Compose or bare JVM.

Suitable for: dev, evaluation, small teams (< 20 users).

### 6.2 Multi-instance horizontal scaling (production)

The application is **stateless** for all CRUD operations, UI, MCP, and auth. Multiple instances run behind a load balancer and serve requests interchangeably.

```
                    ┌─────────────┐
                    │ Load Balancer│
                    └──┬───┬───┬──┘
                       │   │   │
              ┌────────┘   │   └────────┐
              ↓            ↓            ↓
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │Instance A│ │Instance B│ │Instance C│   ← stateless web/API/MCP/UI
        └────┬─────┘ └────┬─────┘ └────┬─────┘
             │            │            │
             └────────────┴────────────┘
                      │     │
              ┌───────┘     └────────┐
              ↓                      ↓
        ┌──────────┐           ┌──────────┐
        │ Postgres │           │  Redis   │
        │Broadcast)│           │(shared)  │
        └──────────┘           └──────────┘
```

**No sticky sessions required.** Each request is independent.

#### What's stateless (any instance serves any request)

- REST API (all CRUD, list, detail, execute endpoints)
- MCP server
- UI (all screens — Thymeleaf server-rendered)
- Auth (JWT sessions, API key validation — both backed by Postgres)
- Template rendering (reads from Postgres, per-instance LRU cache)
- Pipeline validation (reads from Postgres)

#### What's instance-local (tied to the instance that started it)

- **In-flight pipeline executions.** When `POST /pipelines/{id}/execute` hits Instance A, the execution runs on Instance A's JVM (coroutines + per-request H2 staging). The SSE stream is a direct connection from Instance A to the client.
- **Implication:** if the SSE connection drops mid-execution and the client reconnects, the reconnection may hit Instance B. Instance B doesn't have the execution. **The execution continues on Instance A regardless** — the client polls `GET /executions/{execution_id}` until completion, then fetches the result.
- **Why this is fine:** pipelines are short-running (seconds to a few minutes). The execution completes on the originating instance; the client gets the result via REST. Instance crashes lose only the in-flight execution (H2 staging is in-memory and non-recoverable).

#### Multi-instance checklist

| Requirement | How |
|---|---|
| Shared Postgres | All instances connect to the same external Postgres. |
| Shared Redis | All instances connect to the same Redis (claim-check cache + idempotency keys). |
| Identical image | Same Docker image on every instance — design system CSS, JS, templates baked in. |
| DB migrations | Flyway uses Postgres advisory locks — concurrent startup is safe (one runs, others wait). |
| SSE heartbeat | Server sends `: heartbeat` comments every 15s to prevent LB idle-timeout kills. See [REST API §6.6](rest-api.md#66-heartbeat-keepalive). |
| LB idle timeout | Configure to ≥ 120s (or rely on heartbeat). |
| Health checks | `/health` and `/ready` work independently per instance. |

### 6.3 Docker Compose (dev / evaluation)

Reference `docker-compose.yml` provided in `deploy/docker-compose.yml`. Single instance + Postgres + Redis.

### 6.4 Kubernetes (recommended for production)

Reference Helm chart in `deploy/helm/`. Includes:
- `Deployment` (N+ replicas, behind a `Service`).
- Externalized Postgres (managed recommended).
- Externalized Redis (managed recommended).
- `HorizontalPodAutoscaler` (scales on CPU + memory).
- `PodDisruptionBudget` (availability during node drains).

No sticky session affinity needed. Standard `ClusterIP` service with round-robin or random load balancing.

### 6.5 Bare JVM

For development:
```
java -jar datapipelines-app.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/datapipelines \
  --datapipelines.redis.host=localhost
```

Not recommended for production.

---

## 7. Persistence & Backup

### 7.1 Metadata DB

- Backup: operator's standard Postgres backup practice (`pg_dump`, managed snapshots, etc.).
- Restore tested regularly by operator (runbook provided).
- **Critical data**: pipelines, templates, datasources (with credentials), audit log.
- **Disposable data**: executions table (can be truncated; only used for history).

### 7.2 Redis

- **Volatile by design**. Claim-check data is, by definition, temporary.
- No backup needed. Restart loses in-flight claim-checks; clients retry their executions.
- Persistent Redis configuration NOT required (and not recommended — keeps semantics clean).

### 7.3 H2 staging

- **In-memory only**. Per-request. No persistence. No backup. No recovery.

### 7.4 Restore drill

Operators should run a quarterly restore drill: restore metadata DB from backup to a sandbox instance, verify pipelines still execute. Documented in the runbook (future).

---

## 8. Upgrades

### 8.1 Versioning

- Semantic versioning: `MAJOR.MINOR.PATCH`.
- **PATCH**: bug fixes only. Safe to upgrade in place.
- **MINOR**: new features, backward-compatible. Schema migrations applied automatically on app startup.
- **MAJOR**: breaking changes. Migration guide published.

### 8.2 Database migrations

- **Flyway** for metadata DB migrations.
- Migrations are versioned SQL files in `app/src/main/resources/db/migration/`.
- Applied automatically on app startup.
- Forward-only; no rollback (restore from backup if needed).

### 8.3 Upgrade procedure (recommended)

1. Review release notes for breaking changes.
2. Backup metadata DB.
3. Drain in-flight executions (stop accepting new requests, wait for current to finish).
4. Stop the old version.
5. Start the new version (migrations apply on startup).
6. Verify `/health` returns UP.
7. Restore traffic.

For k8s: rolling update via `kubectl rollout`. The Helm chart defaults to a safe rolling strategy.

### 8.4 Rollback

- App rollback: redeploy previous image. Schema migrations are forward-only — if a migration was applied, you can't run the old app version against the new schema.
- **For MINOR upgrades**: schema changes are additive; rollback usually works.
- **For MAJOR upgrades**: take a DB backup before upgrade; restore it to roll back.

---

## 9. Security Hardening Checklist (Deployment)

- [ ] TLS termination at load balancer / proxy (let it handle cert renewal).
- [ ] Metadata DB password rotated and not in source control.
- [ ] `DATAPIPELINES_JWT_SECRET` is high-entropy (≥ 32 bytes random).
- [ ] `DATAPIPELINES_DB_ENCRYPTION_KEY` is high-entropy (32 bytes random) and stored in a secret manager, not a plaintext env file.
- [ ] Redis password set if Redis is networked.
- [ ] NetworkPolicy restricts app's egress.
- [ ] Service account / IAM role has least privilege.
- [ ] Container runs as non-root user (enforced in Dockerfile).
- [ ] Container filesystem read-only except for configured volume mounts.
- [ ] Resource limits set (CPU + memory) per deployment.
- [ ] Audit log retained per compliance policy.
- [ ] `/actuator/*` endpoints except `/health`, `/ready`, `/info`, `/prometheus` either disabled or admin-scoped.

---

## 10. Distribution License

- **Code**: Apache License 2.0 (permissive, business-friendly, compatible with all our dependencies).
- **Dependencies**: only those with compatible licenses bundled by default. Oracle and MySQL drivers optional via Gradle profile (operator's responsibility to accept their licenses).

LICENSE and NOTICE files at repo root.

---

## 11. What's Out of Scope for v1

- **Managed / SaaS deployment**: future commercial offering.
- **Multi-tenant isolation**: single-tenant v1.
- **High-availability Postgres in our chart**: operator provides managed PG.
- **Backup automation**: operator responsibility; we provide runbook.
- **Air-gapped deployment**: should work (no phoning-home telemetry) but not explicitly tested in v1.
- ** Federated deployments** (multiple instances sharing state): not supported.

---

## Appendix A: Reference docker-compose.yml Sketch

```yaml
version: '3.8'
services:
  datapipelines:
    image: ghcr.io/datapipelines/datapipelines:1.0.0
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/datapipelines
      SPRING_DATASOURCE_USERNAME: datapipelines
      SPRING_DATASOURCE_PASSWORD: ${METADATA_DB_PASSWORD}
      DATAPIPELINES_REDIS_HOST: redis
      DATAPIPELINES_JWT_SECRET: ${JWT_SECRET}
      DATAPIPELINES_DB_ENCRYPTION_KEY: ${ENCRYPTION_KEY}
    depends_on:
      - postgres
      - redis
    restart: unless-stopped

  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: datapipelines
      POSTGRES_USER: datapipelines
      POSTGRES_PASSWORD: ${METADATA_DB_PASSWORD}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD}
    restart: unless-stopped

volumes:
  postgres-data:
```

---

## Appendix B: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 draft | initial draft | Initial deployment spec sketch — Docker image, infra requirements, configuration, deployment patterns, upgrade/rollback, security checklist |
| 2026-08-05 | v1.1 | horizontal scaling | Added multi-instance horizontal scaling section. Application is stateless for all CRUD/UI/MCP/auth. In-flight executions are instance-local (acceptable for short-running pipelines). No sticky sessions required. Added multi-instance checklist. Added LB idle-timeout + SSE heartbeat note. |
