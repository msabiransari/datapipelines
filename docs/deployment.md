# Deployment & Packaging Specification

**Status:** v1.7
**Owner:** datapipelines.co core
**Depends on:** all other specs
**Last updated:** 2026-09-01

---

## 1. Purpose

This spec defines **how datapipelines.co is packaged, distributed, and deployed** in self-hosted environments. The product is open-source and self-hosted; this document covers the artifacts we produce and the deployment patterns we support.

This is **lighter than the core specs** — most operational concerns are the operator's responsibility (we ship the artifact, they run it). We document our artifacts, required infrastructure, configuration surface, and recommended deployment patterns.

---

## 2. Design Principles

1. **One artifact, many deployments.** A single Docker image runs in dev, staging, prod. Configuration via env vars + mounted files; no build-time decisions.
2. **Stateless app, externalized state.** The app has no local persistent state. Metadata DB (Postgres) and Redis (result store, idempotency keys, cancellation flags, post-completion event log) are external. H2 staging is per-execution in-memory.
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
- **Entrypoint**: `java $JAVA_OPTS -Duser.timezone=UTC -jar /app/app.jar`.

**`-Duser.timezone=UTC` is normative, not a suggestion.** The type system assumes a UTC JVM: ingest normalizes timezone-aware source values to UTC and treats naive source timestamps as already-UTC ([Type System §8.4](type-system.md#84-timestamp-timezone-normalization)), and every internal `TIMESTAMPTZ` is stored and rendered in UTC. A container whose JVM default zone is anything else silently shifts rendered timestamps. The flag is baked into the image entrypoint; bare-JVM and JAR deployments (§3.2, §6.5) MUST pass it themselves.

Heap and container-memory sizing: §6.6.

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

### 3.5 JDBC driver matrix (what ships in the image)

Which drivers are present is a **packaging** property of the artifact, not a configuration one. The licensing rationale and the driver-class lookup live in [Datasources §10](datasources.md#10-jdbc-driver-packaging); this table is the operator's view of the published image.

| Dialect | In the published image? | How to get it otherwise |
|---|---|---|
| `POSTGRES` | Yes (bundled) | — |
| `MSSQL` | Yes (bundled) | — |
| `H2` | Yes (bundled — also the staging engine) | — |
| `DUCKDB` | Yes (bundled) | — |
| `SQLITE` | Yes (bundled) | — |
| `ORACLE` | **No** (OTN license) | Rebuild with `./gradlew -Poracle bootJar`, **or** drop `ojdbc11.jar` into `lib/` |
| `MYSQL` | **No** (GPL-2.0 + FOSS exception, redistribution unverified) | Rebuild with `./gradlew -Pmysql bootJar`, **or** drop `mysql-connector-j.jar` into `lib/` |

**The `lib/` drop-in.** Spring Boot's loader adds `lib/` (relative to the JAR's working directory) to the application classpath. For the container, mount the JAR at `/app/lib/`; for bare JVM, place it beside `datapipelines-app.jar`. This is the no-rebuild path — the operator accepts the driver's license by supplying it.

Registering a datasource whose dialect has no driver on the classpath fails at save time with `datasource.driver_not_loaded` ([Datasources §9](datasources.md#9-validation-rules)) — a deployment/packaging error, not a bad payload. The same payload succeeds after the driver is supplied.

---

## 4. Required Infrastructure

### 4.1 Postgres (metadata DB)

- **Version**: 14+ recommended.
- **Purpose**: persistent storage for pipelines, templates, datasources, executions, audit log.
- **Provisioning**: operator-managed. We support connecting to an existing Postgres instance.
- **Database size**: small (a typical deployment has < 1 GB metadata).

Connection keys (URL, credentials, Hikari pool size) are defined in [Configuration §2 / §3.13](configuration.md#2-required-configuration).

### 4.2 Redis (result store and coordination)

- **Version**: 6+ recommended.
- **Provisioning**: operator-managed.
- **Purpose** — four distinct workloads share one store:
  1. **Caller results.** Every completed execution's caller result is materialized to Redis and read through the cursor ([REST API §7](rest-api.md#7-result-delivery)). There is no inline-vs-large split.
  2. **Idempotency keys** (`Idempotency-Key` records, retained per `datapipelines.idempotency.ttl-seconds`).
  3. **Cancellation flags** (`dp:cancel:{execution_id}`) — how `DELETE /executions/{id}` reaches the executing instance ([DAG Executor §8.3.1](dag-executor.md#831-the-registry)).
  4. **Post-completion event log**, 1 hour, backing SSE replay ([REST API §10.3](rest-api.md#103-replay-sse-stream)).

Connection keys are defined in [Configuration §2 / §3.1](configuration.md#2-required-configuration); TTL and size limits in [Configuration §3.5](configuration.md#35-results).

#### 4.2.1 Required Redis configuration

**`maxmemory-policy noeviction`.** This is not tuning advice — it is a correctness requirement. Any LRU/LFU eviction policy lets Redis silently discard keys under memory pressure, and every key class above is load-bearing:

- an evicted **result** turns a completed execution into a spurious "expired" 404 before its TTL;
- an evicted **idempotency key** lets a client retry execute the pipeline a second time;
- an evicted **cancellation flag** makes `DELETE /executions/{id}` a no-op, leaving the execution running;
- an evicted **event-log entry** silently truncates SSE replay.

Every key the app writes carries an explicit TTL, so `noeviction` does not leak: the store drains on its own schedule. Under genuine memory exhaustion the app fails loudly (`result.storage_unavailable` on the write path) rather than corrupting semantics quietly.

#### 4.2.2 Sizing

Budget for the peak sum of all four workloads:

```
peak ≈ (datapipelines.result.max-size-bytes × concurrent recent executions)   ← dominant term
     + idempotency keys (small, × datapipelines.idempotency.ttl-seconds window)
     + 1h of post-completion event logs (small, proportional to nodes × executions)
     + cancellation flags (negligible)
```

The result term dominates and is the only one worth arithmetic. Results live for their TTL (`datapipelines.result.ttl-default-seconds`, clamped between ttl-min/ttl-max — [Configuration §3.5](configuration.md#35-results)), so "concurrent recent executions" means executions completed within one TTL window, not executions running right now. Worst case with defaults (100 MB cap, 300 s TTL) is dominated by how many callers actually pull 100 MB results; the practical lever is lowering `result.max-size-bytes` and pointing bulk workloads at `output.target: datasource` instead — the [explicit NOT-goal](rest-api.md#7-result-delivery) of result delivery is bulk data transfer.

512 MB is a reasonable starting point for small deployments; raise it before raising `result.max-size-bytes`.

### 4.3 Network egress

The app must reach:
- Configured datasources (PG, Oracle, MSSQL, MySQL, DuckDB files, SQLite files).
- The metadata Postgres.
- Redis.
- (Optional) OTLP collector, Sentry, KMS — if those features are configured.

The app **does not** require inbound network access beyond the HTTP/MCP ports it serves.

---

## 5. Configuration

**[configuration.md](configuration.md) is the single authority for every configuration key** — YAML path, env var name, default, and description. This spec deliberately does **not** restate key names or defaults: a duplicated default is a default that drifts. What follows is only the operator's startup checklist and the file-mounting mechanics.

Env var names are derivable, never memorized: `datapipelines.` → `DATAPIPELINES_`, remaining YAML path upper-snake-cased ([Configuration §1](configuration.md#1-purpose)).

### 5.1 What the app requires to start

The app **fail-fasts** on startup if any of the following is missing. Full definitions: [Configuration §2](configuration.md#2-required-configuration).

1. Metadata Postgres: `spring.datasource.url`, `spring.datasource.username`, `spring.datasource.password`.
2. Redis: `datapipelines.redis.host`.
3. `datapipelines.jwt.secret` — internal JWT signing secret.
4. `datapipelines.db.encryption-key` — AES-256 master key for datasource credentials. **There is no fallback source**: no KMS lookup, no auto-generated key file. Lose it and every stored datasource credential is unrecoverable, so it belongs in a secret manager and in the operator's backup plan. (KMS sourcing is a [ROADMAP §2](ROADMAP.md#2-v11-candidates) item.)
5. **At least one authentication method**: a fully-configured OIDC provider under `datapipelines.auth.oidc.providers` (each with `client-id`, `client-secret`, and `issuer-uri`), **or** local password accounts (`datapipelines.auth.local.enabled=true`, [Auth §5A](auth.md#5a-local-password-accounts-optional)). The first admin comes from `datapipelines.auth.bootstrap-admin-email` either way ([Auth §4.4](auth.md#44-bootstrap-admin)). A provider entry whose `client-id` is empty is ignored with a WARN, not counted. The client-id/secret env var names are chosen by the deployment (`GOOGLE_CLIENT_ID`, `OKTA_CLIENT_ID`, …) — they are the one deliberate exception to the derivation rule above.

   **The operator's first-admin story with local accounts** (no IdP): set `datapipelines.auth.bootstrap-admin-email` to the admin address, enable `datapipelines.auth.local.enabled`, and seed the one-time credential — preferably as a pre-computed hash from `./gradlew :modules:auth:hashPassword` into `datapipelines.auth.local.bootstrap-password-hash` (the plaintext form exists for demos). Hand the password to the admin out-of-band; the app forces a change at first login ([Auth §5A.2](auth.md#5a2-seeding-the-first-admin)), and a deployment still running the seeded credential announces itself with a startup WARN. Every account after the first is created by an admin on the user-administration screen — there is no self-registration and no email reset (the product has no SMTP): a forgotten password means an admin resets it there, which issues a new one-time credential under the same forced-change rule.

Everything else has a default and is optional.

### 5.2 Everything else

Optional keys — executor concurrency, staging memory, result TTLs and caps, SSE heartbeat and disconnect grace, rate limits, idempotency TTL, template cache, UI theme, retention windows, observability — are cataloged with their defaults in [Configuration §3](configuration.md#3-optional-configuration-with-defaults). Resolution precedence (env > profile YAML > base YAML, plus the two per-entity runtime overrides) is [Configuration §4](configuration.md#4-precedence).

The keys an operator most often changes at deploy time are `datapipelines.result.max-size-bytes` (Redis sizing, §4.2.2), `datapipelines.executor.max-concurrent-executions-per-instance` and `datapipelines.staging.h2.max-memory-mb` (heap sizing, §6.6), and `datapipelines.executor.execution-timeout-seconds` (the wall clock that bounds any single execution).

### 5.3 Full config file

Operators can mount `application.yml` at `/etc/datapipelines/application.yml` (configurable via `SPRING_CONFIG_ADDITIONAL_LOCATION`). Every key is expressible either as YAML or as its derived env var; the OIDC provider list is a nested structure and is normally supplied as YAML with `${...}` placeholders for the secrets. A complete annotated template is in [Configuration §5](configuration.md#5-full-applicationyml-template).

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
        │ (shared) │           │ (shared) │
        └──────────┘           └──────────┘
```

**No sticky sessions required.** Each request is independent.

**Client addresses behind the load balancer (`datapipelines.auth.trusted-proxies`).**
Every instance sees the LB's address as `remoteAddr`, so anything keyed or recorded on
it — the per-IP login rate limiter, every audit `source_ip` — collapses to one
deployment-wide value: without configuration, the login budget (default 10/min) is a
single shared bucket any client can exhaust for everyone. Set the key to the CIDRs of
proxies you control (a bare IP is a host CIDR):

```yaml
datapipelines:
  auth:
    trusted-proxies: 10.0.0.0/8   # the LB's network
```

The resolution is spoof-safe: when the direct peer is NOT in the list, the
`X-Forwarded-For` header is ignored entirely — an untrusted peer cannot forge its way
past the limiter by setting it. When the peer IS trusted, the client is the rightmost
header entry that is not itself trusted. Each entry must parse as a CIDR or startup is
refused ([Configuration §3.4/§7](configuration.md#34-auth)) — a typo'd range must not
silently widen proxy trust. The shipped default is EMPTY: a deployment with no proxy
in front behaves exactly as before, and the header stays ignored.

#### What's stateless (any instance serves any request)

- REST API (all CRUD, list, detail, execute endpoints)
- MCP server
- UI (all screens — Thymeleaf server-rendered)
- Auth (JWT sessions, API key validation — both backed by Postgres)
- Template rendering (reads from Postgres, per-instance LRU cache)
- Pipeline validation (reads from Postgres)

#### What's instance-local (tied to the instance that started it)

- **In-flight pipeline executions.** When `POST /pipelines/{id}/execute` hits Instance A, the execution runs on Instance A's JVM (coroutines + per-execution H2 staging). The SSE stream is a direct connection from Instance A to the client.
- **Implication:** if the SSE connection drops mid-execution, there is **no reconnection or resumption path**. Instance A starts a grace timer (`datapipelines.sse.disconnect-grace-seconds`, default 30) and, if the execution has not reached a terminal event by the time it elapses, cancels it — the execution ends `ABORTED` ([REST API §6.8](rest-api.md#68-client-disconnect)). A client that loses its stream should assume the abort and re-execute. This is deliberate: an execution nobody is waiting for must not keep holding source-database connections and staging memory.
- **Cross-instance cancel works anyway.** `DELETE /api/v1/executions/{id}` may land on Instance B, which has never heard of the execution. Instance B writes a Redis cancellation flag; Instance A honors it on its next heartbeat tick or node boundary — worst-case latency ≈ one heartbeat interval ([REST API §10.4](rest-api.md#104-cancel-execution), [DAG Executor §8.3.1](dag-executor.md#831-the-registry)). No sticky sessions are needed for cancellation to be reliable.
- **Completed executions are not instance-local at all.** Results live in Redis for their TTL and are readable from any instance via the cursor ([REST API §7](rest-api.md#7-result-delivery)); execution metadata is in Postgres; the 1-hour event log is in Redis. Only the *running* execution is pinned to one JVM.
- **Instance crash:** loses only that instance's in-flight executions (H2 staging is in-memory and non-recoverable). Their rows are swept to `ABORTED` by the stale-execution sweep (marked `pipeline.execution.instance_lost` once they are older than `datapipelines.executions.stale-timeout-minutes`; every replica runs the idempotent sweep, so no surviving-instance coordination is needed). Completed results and history are unaffected; the lost work must be re-executed.

#### Multi-instance checklist

| Requirement | How |
|---|---|
| Shared Postgres | All instances connect to the same external Postgres. |
| Shared Redis | All instances connect to the same Redis (results, idempotency keys, cancellation flags, event log, datasource pool invalidation) with `maxmemory-policy noeviction` — §4.2.1. A per-instance Redis breaks cross-instance cancel, result reads and pool invalidation. |
| Identical image | Same Docker image on every instance — design system CSS, JS, templates baked in. |
| DB migrations | Flyway uses Postgres advisory locks — concurrent startup is safe (one runs, others wait). |
| SSE heartbeat | Server sends `: heartbeat` comments every 15s to prevent LB idle-timeout kills. See [REST API §6.6](rest-api.md#66-heartbeat-keepalive). |
| LB idle timeout | Configure to ≥ 120s (or rely on heartbeat). |
| Health checks | `/health` and `/ready` work independently per instance. |
| Size per-instance limits by replica count | The execution-slot ceiling `datapipelines.executor.max-concurrent-executions-per-instance` (default 100) is **per instance** (050/R2): N replicas admit **N × the setting** in total against the source databases, and the tempdb heap multiplier of [Configuration §3.2](configuration.md#32-executor) applies per box the same way. Raise replicas with that multiplication in mind, not just the per-instance number. |

### 6.3 Docker Compose (dev / evaluation)

Reference `docker-compose.yml` provided in `deploy/docker-compose.yml`. Single instance + Postgres + Redis.

The app service passes **every** `DATAPIPELINES_*` variable the app binds, each with
the same default `application.yml` ships — so a key that works against a host-run app
(by exporting the variable) reaches the container too, and leaving it unset yields the
shipped default. `scripts/compose-env-audit.sh` diffs the compose block against
`application.yml`'s placeholders and fails on a missing pass-through or a diverged
default.

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
java -Duser.timezone=UTC -jar datapipelines-app.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5434/datapipelines \
  --datapipelines.redis.host=localhost \
  --datapipelines.redis.port=6381
```

`-Duser.timezone=UTC` is required here too (§3.1) — the image sets it for you, a bare JVM does not.

Not recommended for production.

### 6.6 Resource sizing

Two numbers matter: JVM heap, and the container memory limit that must contain it.

**Heap.** The dominant consumer is H2 staging — each executing pipeline holds up to `datapipelines.staging.h2.max-memory-mb` (default 1024 MB), or its own `settings.tempdb.config.max_memory_mb` override:

```
heap ≥ (staging max-memory-mb × concurrent executions on THIS instance) + ~512 MB baseline
```

The multiplier is the **per-instance** execution ceiling `max-concurrent-executions-per-instance` (050/R2): every execution runs on exactly one instance, and this instance can legitimately hold the full setting's worth at an unbalanced moment. The default 100 × 1024 MB is the worst case ONE box must absorb — size the container for it (with N replicas behind a round-robin LB the typical share is `setting / N`, but size for the ceiling, not the share; cap the exposure by lowering the setting — or `max-memory-mb` — per box, or scale on smaller per-instance limits). The ~512 MB baseline covers Spring context, Hikari pools, the template cache, and SSE buffers.

Worked example — 4 concurrent executions per instance at the 1024 MB default: `4 × 1024 + 512 ≈ 4.6 GB` heap.

**Container memory limit.**

```
container limit ≈ heap × 1.5
```

The 0.5 covers what the heap number does not: metaspace, code cache, thread stacks, and — significant here — JDBC direct/native buffers, which scale with result-set width and fetch size. A limit set equal to `-Xmx` gets the container OOM-killed by the kernel rather than getting a clean `OutOfMemoryError`, which is strictly worse to diagnose. For the example above: ~7 GB limit.

**JVM flags.** Either `-XX:MaxRAMPercentage=65` (heap tracks the container limit — preferred for k8s, where limits change without an image rebuild) or an explicit `-Xmx`. Do not set both. Always `-Duser.timezone=UTC` (§3.1).

**Servlet threads (MCP blocking calls).** An MCP `pipelines_execute` call ([MCP Server §6.2.3](mcp-server.md#623-pipelines_execute)) is a **single blocking HTTP request** that holds one servlet thread until the execution reaches a terminal state or `datapipelines.executor.execution-timeout-seconds` (default 600) elapses. Per-user concurrency is bounded (`max-concurrent-executions-per-user`, default 10) but the default Tomcat pool is 200 threads, so on the order of ~20 concurrent long-running MCP callers can saturate it and starve REST/UI traffic on the same instance for minutes. Size `server.tomcat.threads.max` at or above the expected count of simultaneously-blocking MCP executions plus normal REST concurrency, or isolate `/mcp` on its own connector/instance. This is in addition to raising proxy/LB idle timeouts above `execution-timeout-seconds` (MCP Server §6.2.3).

**CPU.** Execution is coroutine-based and largely I/O-bound on source databases; 2 vCPU per instance is a reasonable floor. Scale out on `max-concurrent-executions-per-instance` pressure, not CPU.

### 6.7 Marketing site & in-product docs

Since v1.4 the app serves the marketing site and the documentation itself — the site and the product are ONE deployment (owner decision 2026-08-31). There is no separate static deploy to keep available, and the docs shipped in the jar always match the version running them.

- **`GET /`** — the marketing site (public). Template `templates/site/index.html`, assets under `static/site/**`, referencing the app's vendored design system at `/vendor/design-system/**` (the retired `website/` directory carried a second vendored copy — the app copy is now the single sync target of `scripts/sync-design-system.sh`). The only dynamic fact (the MCP tool count) is a compile-time constant baked at render time; public routes touch no database.
- **`GET /dashboard`** — the signed-in dashboard, moved off `/`. There is no auto-redirect: signed-in users hitting `/` get the marketing page.
- **`GET /docs`** — the packaged spec set (`docs/*.md` minus the contributor/research exclusions, packaged by `processResources` in `modules/web/build.gradle.kts`), session-authenticated. Public doc access remains the GitHub repo.
- **Public-surface defence is cache headers, not a rate limiter.** `/` is `Cache-Control: public, max-age=300`; `/site/**` is public with a 1-hour TTL plus `Last-Modified` revalidation. The login rate limiter is deliberately NOT applied here (033/D1): the content is constant between deploys, so a shared-cache TTL costs nothing per request — and the T46 remoteAddr-keying concern is closed regardless: the limiter now resolves the CLIENT address through `datapipelines.auth.trusted-proxies` (§6.2), so pointing it at `/` would no longer create an LB-address-wide bucket.
- **Allowlist.** `/` and `/site/**` join the `permitAll` list in `SecurityConfig` with their reasons inline; nothing else was widened.

**S3/CloudFront cold fallback (kept, drop if it rots unused).** If the app is down but marketing must stay up, the same template renders to static files with facts baked:

```bash
./gradlew :modules:web:websiteExport          # → modules/web/build/website-export/
aws s3 sync modules/web/build/website-export/ s3://YOUR-BUCKET/ --delete
aws cloudfront create-invalidation --distribution-id YOUR_DIST_ID --paths "/*"
```

The export renders `/`-rooted links (`/site/...`, `/vendor/design-system/...`), which the bucket layout mirrors exactly — upload the CONTENTS of `website-export/`, not the folder. S3 static website hosting (or CloudFront with an origin access control) serves `index.html` as the index document. Nothing fingerprinted: keep TTLs short for `index.html` and longer for `vendor/**` (those files change only when the design system is re-vendored). This is an emergency procedure, not a second primary deploy — the app is the primary.

---

## 7. Persistence & Backup

### 7.1 Metadata DB

- Backup: operator's standard Postgres backup practice (`pg_dump`, managed snapshots, etc.).
- Restore tested regularly by operator (runbook provided).
- **Critical data**: pipelines, templates, datasources (with credentials), audit log.
- **Disposable data**: executions table (can be truncated; only used for history).

### 7.2 Redis

- **Volatile by design.** Every key is TTL-bounded: results, idempotency records, cancellation flags, the 1-hour event log (§4.2).
- **No backup needed.** A Redis restart loses unexpired results (clients re-execute), unexpired idempotency records (a retry may execute a second time), and pending cancellation flags. All are recoverable by re-running; none is a durable record. The durable records — pipelines, templates, datasources, execution history and `execution_events` — are all in Postgres.
- Persistence (RDB/AOF) is **not required** and not recommended; it buys nothing that a re-run does not.
- **`maxmemory-policy noeviction` is required regardless** (§4.2.1). "Volatile" means TTL-expiring, not evictable — eviction breaks correctness in a way that restart does not, because it happens silently during normal operation.

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
3. Signal shutdown and let the instance drain (§8.3.1 — this is automatic, not a manual step).
4. Start the new version (migrations apply on startup).
5. Verify `/health` returns UP.
6. Restore traffic.

For k8s: rolling update via `kubectl rollout`. Each terminated pod flips its readiness and cancels its in-flight executions on the way out (§8.3.1); the `preStop` and `terminationGracePeriodSeconds` settings in §8.3.2 keep that orderly.

#### 8.3.1 Graceful shutdown mechanism

Shutdown is a defined sequence, not "stop the process and hope". On `SIGTERM` the instance, in this order:

1. **Fails readiness first.** `ReadinessState.REFUSING_TRAFFIC` is published before anything is cancelled, so `/ready` starts returning 503 while the process is still fully up and the load balancer / k8s Service bleeds traffic off. The order is the contract: flipping after the drain would keep fresh work flowing into an instance that is already cancelling it. `/health` keeps reporting UP, so nothing kills the pod for being unhealthy mid-drain.
2. **Cancels every in-flight execution** through the ordinary cancellation path ([DAG Executor §8.3](dag-executor.md#83-cancellation)): `Statement.cancel()` on every registered statement first — which is what actually stops the query on the source database, so the drain is more than a status update — then the root `Job` cancellation, `execution_aborted` (`reason: "shutdown"`) emitted to connected streams, tempdb dropped and connections released in `finally`, and the row written `ABORTED`. The drain **cancels; it does not wait for executions to finish** — an execution in flight at SIGTERM ends `ABORTED`, not `SUCCESS`.
3. **Waits for the flush, bounded** (20 seconds). An execution leaves the live count only after its `ABORTED` status and events are written, so the wait means the bookkeeping reached Postgres and Redis before exit; the bound means a wedged execution meets the kubelet's deadline rather than hanging shutdown forever.
4. **Exits.** The web server's own graceful shutdown (`server.shutdown: graceful`) runs after the drain, so SSE clients are still connected while their terminal events arrive, and in-flight ordinary requests complete rather than being cut off.

**The accepted loss, stated plainly:** an execution running when an instance stops is **cancelled, not preserved**. It ends `ABORTED` with `reason: "shutdown"`, is visible as such in execution history, and its client must re-execute. There is no execution hand-off to another instance and no resumption — in-memory H2 staging makes migration impossible, and pretending otherwise would be worse than the honest abort. Bounded, visible loss beats a silent hang.

One residual race, honestly: a request that reaches the instance *between* the readiness flip and the web server stopping can still launch an execution. The drain re-cancels on every flush tick, so such an execution is cancelled within ~100ms of starting — but the right answer is that traffic should have stopped at the readiness flip, which is what the `preStop` in §8.3.2 exists to give the endpoints controller time to do.

#### 8.3.2 Kubernetes pod lifecycle

```yaml
terminationGracePeriodSeconds: 30      # covers the bounded drain flush (§8.3.1 step 3)
lifecycle:
  preStop:
    exec:
      command: ["sleep", "5"]
```

- **`terminationGracePeriodSeconds: 30`.** The drain cancels rather than waits (§8.3.1), so the only clock that matters is the bounded flush — 30 seconds covers it with margin. Anything shorter risks the kubelet `SIGKILL`ing mid-flush: executions die without their `finally` blocks, so no `execution_aborted` event and no status update — rows left `RUNNING` until the stale-execution sweep marks them `ABORTED` (§6.2).
- **`preStop: sleep 5`** closes the standard k8s race: endpoint removal and `SIGTERM` are concurrent, so without it the pod can flip readiness microseconds before the Service stops routing to it. Five seconds of overlap is enough for Endpoints propagation — and it is what makes step 1's readiness flip actually take traffic off the pod before step 2 cancels.
- A `PodDisruptionBudget` (§6.4) keeps node drains from taking every replica's drain at once.

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
- [ ] Redis password set if Redis is networked (`requirepass` on the server, `datapipelines.redis.password` on every app instance — they must match).
- [ ] Redis `maxmemory-policy noeviction` (§4.2.1) — correctness, not tuning.
- [ ] OIDC client secrets from a secret manager, not a plaintext env file; at least one provider configured (§5.1).
- [ ] NetworkPolicy restricts app's egress.
- [ ] Service account / IAM role has least privilege.
- [ ] Container runs as non-root user (enforced in Dockerfile).
- [ ] Container filesystem read-only except for configured volume mounts.
- [ ] Resource limits set (CPU + memory) per deployment.
- [ ] Audit log retained per compliance policy.
- [ ] No `/actuator/*` path reachable on the application port; `/actuator/prometheus` on the management port (`management.server.port`), cluster-internal only ([Observability §4.2](observability.md#42-exposure)). The management port is never published to a host or load balancer; `MANAGEMENT_SERVER_ADDRESS` defaults to loopback — setting it to `0.0.0.0` (required for k8s scraping) demands an accompanying NetworkPolicy confining the port to the monitoring namespace.
- [ ] Internet-exposed deployments may prefer to omit `-Pdatapipelines.commit` at build time — a public `/info` commit hash maps the instance to exact source revisions.
- [ ] The `lib/` driver drop-in mount is **read-only** in the container, populated at image build or by a trusted init container, never writable by the app user; `LOADER_PATH`, if set, comes from the image — never inherited from the deployment environment (a writable `lib/` is code-execution-by-file-drop).
- [ ] Production Redis requires `requirepass` (or ACLs) **and** TLS, or is confined to a private network segment with a NetworkPolicy — it holds fully materialized caller results for up to an hour (D9). The app logs a structured WARN at startup when the Redis password is empty and the host is not loopback ([Configuration §7](configuration.md#7-config-validation)).

---

## 10. Distribution License

- **Code**: AGPL-3.0 (see [LICENSE](../LICENSE); contributions under the [CLA](../CLA.md)).
- **Dependencies**: only those with compatible licenses bundled by default. Oracle and MySQL drivers optional via Gradle profile (operator's responsibility to accept their licenses).

LICENSE and NOTICE files at repo root.

---

## 11. What's Out of Scope for v1

- **Managed / SaaS deployment**: future commercial offering.
- **Multi-tenant isolation**: single-tenant v1.
- **High-availability Postgres in our chart**: operator provides managed PG.
- **Backup automation**: operator responsibility; we provide runbook.
- **Air-gapped deployment**: should work (no phoning-home telemetry) but not explicitly tested in v1.
- **Federated deployments** (independent installations sharing state or federating queries across each other): not supported. Multi-instance horizontal scaling (§6.2) is a different thing and *is* supported.
- **Execution hand-off / resumption**: an execution is pinned to the instance that started it and is aborted rather than migrated (§6.2, §8.3.1).

---

## Appendix A: Reference docker-compose.yml Sketch

This sketch **boots** — it satisfies every §5.1 startup requirement. Removing any of the marked items produces a container that exits during context startup, not one that runs degraded.

```yaml
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
      # REQUIRED whenever redis runs with --requirepass. Must be the SAME value
      # the redis service below is started with, or every Redis call fails at runtime.
      DATAPIPELINES_REDIS_PASSWORD: ${REDIS_PASSWORD}

      # REQUIRED, no fallback. Generate once: openssl rand -base64 32
      DATAPIPELINES_JWT_SECRET: ${JWT_SECRET}
      # REQUIRED, no fallback. Exactly 32 bytes base64: openssl rand -base64 32
      # Losing this makes every stored datasource credential unrecoverable.
      DATAPIPELINES_DB_ENCRYPTION_KEY: ${ENCRYPTION_KEY}

      # REQUIRED: at least ONE authentication method (ConfigValidator §7) — an OIDC
      # provider configured below, or local accounts (auth.md §5A: set
      # DATAPIPELINES_AUTH_LOCAL_ENABLED=true plus a one-time seed for
      # DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL, which names the first admin either way).
      # These names are referenced by the providers list in application.yml below;
      # Google is only an example — Microsoft/Okta/Keycloak/any OIDC IdP works.
      GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID}
      GOOGLE_CLIENT_SECRET: ${GOOGLE_CLIENT_SECRET}
    volumes:
      # The OIDC provider list is a nested structure — supply it as YAML.
      - ./application.yml:/etc/datapipelines/application.yml:ro
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
    # noeviction is REQUIRED (§4.2.1): eviction silently destroys results,
    # idempotency keys, and cancellation flags. Same password as the app above.
    command: >
      redis-server
      --requirepass ${REDIS_PASSWORD}
      --maxmemory 512mb
      --maxmemory-policy noeviction
    restart: unless-stopped

volumes:
  postgres-data:
```

The mounted `application.yml` needs only the provider list (everything else has a default or is set above):

```yaml
datapipelines:
  auth:
    oidc:
      providers:
        - name: google
          client-id: ${GOOGLE_CLIENT_ID}
          client-secret: ${GOOGLE_CLIENT_SECRET}
          issuer-uri: https://accounts.google.com
          display-name: "Sign in with Google"
```

Format and further provider examples: [Auth §5.1](auth.md#51-provider-configuration-generic) and [§11.1](auth.md#111-oidc-provider-configuration). Full key template: [Configuration §5](configuration.md#5-full-applicationyml-template).

---

## Appendix B: Demo Quickstart — the Published Sample Data

The sample data is a **published, versioned artifact set**, not something the app
downloads: loading it is a deployment step (sample-data design D5). Any
deployment that pulls the same version gets the same databases, byte for byte in
content. The build scripts that produce it live at
[`scripts/sample-data/`](../scripts/sample-data/README.md); everything below is
the consuming side.

What you get: NYC TLC yellow-taxi trips on Postgres (~4.9M sampled rows, plus
daily and monthly rollups), NOAA weather for five NYC-area stations on MySQL, and
TLC zones / rate codes / payment types / a holiday calendar on SQLite —
registered as three `global` + `readonly` datasources, with two cross-datasource
example pipelines seeded into every new personal workspace.

### One command

The published artifacts live at
`https://datapipelines-co.s3.amazonaws.com/sample-data/mobility/v2/` (us-east-1;
`./app.sh --start --demo` defaults to it). For the raw compose path, fill in the
`SAMPLE_*` block of [`deploy/.env.example`](../deploy/.env.example) — the base
URL above, `SAMPLE_VERSION=v2`, and the demo login's passwords — then:

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.local.yml \
  --profile demo up -d --wait
```

That is the whole thing. `--wait` returns when the app is healthy, and by then
the artifacts have been downloaded, every checksum verified, the databases
restored, the SELECT-only demo login created, and the three datasources
registered. From a checkout that builds its own image:

```bash
./app.sh --start --demo
```

`--demo` wraps the same profile and additionally builds the jar with `-Pmysql`
(see the driver note below). `./app.sh --stop --demo` and `--status --demo` take
the same flag, so the demo services are not left running invisibly.

**MySQL driver.** MySQL Connector/J is GPL with a FOSS exception and is *not* in
the default build (§3.5, [Datasources §10.2](datasources.md#102-strategy)). The
`sample-weather` datasource is MYSQL, and bootstrap registration fail-fasts
startup with `datasource.driver_not_loaded` without it. Build with the driver:

```bash
./gradlew -Pmysql :modules:app:bootJar && docker build -t datapipelines:local .
```

or drop the jar into `lib/`. `./app.sh --start --demo` does the `-Pmysql` build
for you.

### Point an agent at it — three steps

1. **Log in** at `http://localhost:8080` with the **local account**
   `demo-admin@demo.local` — the demo needs **no OIDC client at all**
   ([Auth §5A](auth.md#5a-local-password-accounts-optional)); the app asks
   you to set a new password on first sign-in. (An OIDC provider configured in
   `deploy/.env` works too.) The **password depends on what your first
   `./app.sh --start` scaffolded**: with Google creds already in `.env.local`
   it is the demo seed `demo-admin`; on a clean checkout `app.sh` scaffolds
   local accounts FIRST (a no-OIDC machine cannot start without them) with a
   GENERATED one-time password, which beats the demo file's seed — read it
   back with `grep DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD deploy/.env`
   (it was also printed once when `deploy/.env` was written). The first login
   provisions your personal workspace and seeds the example pipelines into it.
2. **Mint an API key** from the UI (or `POST /api/v1/auth/api-keys`). The secret
   is shown exactly once.
3. **Give the agent the MCP endpoint** `http://localhost:8080/mcp` and that key.
   It can list the seeded pipelines, read the three sample datasources' schemas,
   and execute `revenue_by_borough` or `rainy_vs_dry_ridership` immediately —
   see [MCP Server](mcp-server.md).

### What the demo profile turns on

The profile adds a `mysql` service and two one-shot loaders, and points the app
at the files they place on a read-only volume. It enables **local password
accounts** with a one-time seed (`demo-admin@demo.local`, forced to change at
first login — [Auth §5A.2](auth.md#5a2-seeding-the-first-admin)), so the demo
needs no OIDC client. The seed value is `demo-admin` **when the demo env file
supplies it** — i.e. when your first `./app.sh --start` found Google creds and
did not scaffold a generated password into `deploy/.env`. On a clean checkout
`app.sh` enables local accounts itself at first scaffold (a machine with no
OIDC client cannot start otherwise) with a GENERATED bootstrap password, and
`deploy/.env` takes precedence over the demo file — that generated password,
not `demo-admin`, is what logs you in (see the three steps above for reading it
back). It also sets the §7 demo posture: `auto-per-user` provisioning (every
visitor gets their own workspace) and `member-datasources-enabled=false` (an
open datasource form on a public server is an SSRF and port-scan primitive —
demo users get the seeded datasources only). Without `--profile demo` none of
it exists: both `datapipelines.bootstrap.*` keys are paths and empty means
off, so the non-demo stack is configured exactly as it was.

The demo datasources are protected in three independent layers: workspace-scoped
access, the `is_readonly` flag on the datasource row
([Datasources §5.7](datasources.md#57-readonly-datasources-flag-semantics-and-enforcement-layers)),
and a database login granted `SELECT` and nothing else — created by the loader,
never assumed. The SQLite entry additionally opens its file with the driver's
read-only mode ([Datasources §8A.4](datasources.md#8a4-sqlite-read-only-open-mode)).
On a public demo, add the §9 hardening item that belongs with this posture:
egress-restrict the app container's network, so a datasource anyone can reach
cannot become a path to anywhere else.

### The loader

`deploy/sample-data/load.sh <base-url> <version>` is what the one-shot services
run. Its contract, in order: download every artifact the manifest names, verify
**every** checksum, and only then touch an engine — a corrupted download can
never leave a half-loaded database behind. Each engine then gets a
`_sample_meta(version)` marker, written **last**, so a re-run of a loaded
deployment skips it and a failed engine is retried cleanly on the next start.

It runs as two services because no pinned image carries both a Postgres and a
MySQL client, and installing one at container start would put an unpinned package
fetch in the one place that has to be reproducible.

### Resetting an engine volume desyncs the demo login

The loader creates the `dp_demo_ro` login with the passwords from the env files,
and bootstrap registration is **create-if-absent — it never updates an existing
datasource row** (§8A). So if you delete an engine's data volume
(`docker volume rm deploy_mysql-data`) or rotate a `SAMPLE_*` password, the
freshly created login no longer matches the credential the app stored at the
original bootstrap, and that datasource fails validation in the UI while the
engine itself is fine. The repair is to re-run registration for the stale entry
only:

```bash
docker exec deploy-postgres-1 sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "delete from datasources where name='"'"'sample-weather'"'"';"'
docker restart deploy-datapipelines-1   # bootstrap re-registers with the current env password
```

(2026-08-30: hit live — a recreated MySQL volume left `sample-weather` failing
UI validation until its row was re-registered this way.)

### Re-publishing is a new version

Version directories are immutable (design §4). Any change to the data — a wider
window, a different sample, a corrected lookup, even a typo — is published as
`v2` under a new prefix; `v1` is never edited. Consumers pin the version
(`SAMPLE_VERSION`), and nothing references a `latest` alias. This is what makes
"the same version means the same data" a fact rather than a hope, and it is why
the loader refuses a manifest whose `version` does not match the directory it
came from.

### Publish confirmation & release rehearsal — the drift guards

Two guards bracket every publish and every release rehearsal. Both exist
because of T70 (2026-09-02): the published `v1` `examples.json` still carried
the `${}` interpolations 042 had already migrated out of the repo copy, the
demo 500ed on every fresh first login for two days, and nothing compared the
published bytes with the repo's.

1. **Before confirming an upload** (and in every release rehearsal), run the
   published-drift guard for the version the demo pins:

   ```bash
   ./scripts/sample-data/check-published.sh v2   # or whatever SAMPLE_VERSION pins
   ```

   It fetches the published manifest and `examples.json`, and fails unless the
   published copy, the published manifest's declared checksum, and the repo's
   `content/examples.json` all agree. Against an unpublished version it fails
   on the manifest fetch — which is the upload gate itself. Set
   `SAMPLE_BASE_URL` to check a mirror or a locally staged build
   (`file://…/scripts/sample-data/work`, or the local-serve recipe in `app.sh`).
   Network by nature, so it is a rehearsal step, never part of `./gradlew build`.
2. **The repo copy is validated in `build`** — the templates module's
   `SampleDataExamplesContentTest` runs every shipped template and pipeline
   through the app's own save-time validators (049 C1), so content the seeder
   would refuse cannot merge. `verify.sh` step 5 remains structural only; the
   two checks are different jobs and each says which it does.

### Licence gate — before serving this data publicly

Every `provenance` row in `manifest.json` ships `license_verified: null`. The
build verifies no licence and claims none; the licence strings are research
claims carried with their evidence links in `scripts/sample-data/sources.lock`.

**Publishing with any `license_verified` still null blocks go-live** (design §8).
Verify each source's current terms, record the date, and swap — do not ship — any
dataset that fails. This applies to datapipelines.co; a self-hosted evaluation
loading the artifacts for its own use is a different question, and one for the
operator.

---

## Appendix C: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-02 | v1.7 | 051 auth/config sweep | §6.2 gains `datapipelines.auth.trusted-proxies` (R8/T46): behind the LB the login limiter and every audit `source_ip` must resolve the client through the trusted-proxy list — empty default keeps bare deployments on `remoteAddr`, header ignored. §6.7's homepage note updated (T46 closed; no-limiter decision stands on its own grounds). §6.3 documents the compose env contract with its `scripts/compose-env-audit.sh` guard (T32). Appendix B tells the truth about the demo password (T47/T73): on a clean checkout the scaffolded GENERATED password wins over the `demo-admin` seed — `grep DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD deploy/.env`. app.sh's `up --wait` timeout now reports "still starting" and exits 0 when the app container is running (T75; a cold JVM can outrun the HEALTHCHECK window — 243s in the 2026-09-02 rehearsal) |
| 2026-09-02 | v1.9 | multi-instance round 2 (050) | **§6.2 checklist gains the per-instance-limits row** (R2/M4/M7): `max-concurrent-executions-per-instance` is per instance and N replicas admit N × it — the multiplication stated once, plainly; the Redis row names the datasource pool-invalidation channel. §6.6 heap paragraph rewritten around the per-instance multiplier (the old text read the key as a "cluster-wide ceiling" — false at N > 1, and the exact trap the rename closed); §5.2 deploy-time keys updated to the renamed key. |
| 2026-09-02 | v1.8 | demo artifact v2 + guards (049) | **Appendix B gains "Publish confirmation & release rehearsal — the drift guards"**: `scripts/sample-data/check-published.sh` (published `examples.json` vs repo vs manifest, 049 C2) is now a named pre-upload / rehearsal step, and the repo copy's semantic validation is pinned in `build` (`SampleDataExamplesContentTest`, 049 C1) — the two guards T70 (published-v1 drift → first-login 500 ×2 days) proved missing. A v2 artifact is staged from unchanged pins with the licence gate re-stamped 2026-09-02; this row's commit is the held version bump — it moved the demo pin (`SAMPLE_VERSION=v2`) and every current-version citation, and merges only after the owner's upload is confirmed live (`check-published.sh v2` against the bucket). |
| 2026-09-01 | v1.7 | multi-instance readiness (036) | **The stale-execution sweep now ships (ARCH-AUDIT M2), and the §6.2 crash bullet is true again.** Every replica runs the idempotent sweep on a one-minute cadence (the project's first `@Scheduled`); `RUNNING` rows older than `datapipelines.executions.stale-timeout-minutes` are marked `ABORTED` with `pipeline.execution.instance_lost`. §8.3.2's SIGKILL-mid-flush consequence restored to match. Side effect: `DELETE /executions/{id}` on a crashed instance's stale row stops being a silent no-op — once swept, the row is terminal and the cancel is refused `pipeline.execution.not_running` instead of returning 204. |
| 2026-09-01 | v1.6 | multi-instance readiness (036) | **The drain now ships (ARCH-AUDIT M1), and §8.3.1/§8.3.2 describe it.** On SIGTERM: readiness flips to REFUSING_TRAFFIC first, every local execution is cancelled through the ordinary path (`Statement.cancel()` first, `execution_aborted` with `reason: "shutdown"`, row `ABORTED`), the flush is awaited with a 20s bound, and `server.shutdown: graceful` lets in-flight requests end. The drain CANCELS rather than draining-to-completion — the v1.2 text's "drain up to `execution-timeout-seconds`" behavior is deliberately not what shipped; `terminationGracePeriodSeconds: 30` (not 630) and `preStop: sleep 5` are the matching pod settings, and the Helm chart carries both. The §6.2 crash bullet is still honest (the sweep lands next). |
| 2026-09-01 | v1.5 | multi-instance readiness (036) | **Honesty fix (ARCH-AUDIT M11): the doc promised three things the code does not do.** §8.3.1/§8.3.2 rewritten as shutdown behavior *as shipped* — no drain, no `server.shutdown: graceful`, no readiness flip, no sweep; the old drain-to-timeout/`cancelAll(shutdown)` sequence and the `terminationGracePeriodSeconds: 630` + `preStop` guidance described unimplemented code. §6.2 instance-crash bullet no longer claims rows are "swept to `ABORTED`" (the sweep exists but has no caller). §5.2's "shutdown grace" gloss on `execution-timeout-seconds` removed. §6.4's `deploy/helm/` reference made real: a minimal chart (Deployment, Service, optional HPA/PDB) now ships. The drain and sweep claims return with the code that implements them. |
| 2026-08-31 | v1.4 | website + docs in-app (033) | New §6.7: the app serves the marketing site (`/`, public) and the packaged spec set (`/docs`, session-only); the dashboard moved to `/dashboard`; the standalone `website/` static deploy retires to the `websiteExport` cold-fallback procedure; public surface defended by cache headers, NOT the login rate limiter (OPEN-ITEMS T46). Header version corrected (v1.3's entry had not bumped it). |
| 2026-08-05 | v1.0 draft | initial draft | Initial deployment spec sketch — Docker image, infra requirements, configuration, deployment patterns, upgrade/rollback, security checklist |
| 2026-08-05 | v1.1 | horizontal scaling | Added multi-instance horizontal scaling section. Application is stateless for all CRUD/UI/MCP/auth. In-flight executions are instance-local (acceptable for short-running pipelines). No sticky sessions required. Added multi-instance checklist. Added LB idle-timeout + SSE heartbeat note. |
| 2026-08-07 | v1.2 | consistency campaign | Applied [SPEC-REVIEW-2026-08 §2.15](SPEC-REVIEW-2026-08.md#215-deploymentmd): §5 env-var tables replaced by the startup-requirements list + pointer to configuration.md; the inline-vs-claim-check threshold key (superseded by the D9 result keys) and every other key configuration.md does not define were deleted [D8]; §4.2 rewritten as the result store with required `maxmemory-policy noeviction` and a sizing model [D9]; §8.3.1/§8.3.2 graceful-shutdown mechanism (readiness fail → drain to `execution-timeout-seconds` → `cancelAll(shutdown)` → exit) with k8s `preStop` + `terminationGracePeriodSeconds`, accepted loss stated [D7]; §6.2 instance-local story updated to cancel-on-disconnect + cross-instance cancel via Redis flag [D7]; Appendix A compose made bootable (OIDC provider env vars, Redis password wired to `requirepass`, noeviction, mounted provider YAML); new §3.5 JDBC driver matrix (bundled vs `-Poracle`/`-Pmysql` vs `lib/` drop-in); new §6.6 resource sizing (heap, container limit, `-XX:MaxRAMPercentage`); `-Duser.timezone=UTC` made normative in the image and bare-JVM entrypoints ([Type System §8.4](type-system.md#84-timestamp-timezone-normalization)); §6.2 diagram residue and §11 malformed bullet fixed |
| 2026-08-29 | v1.3 | local password auth | §5.1 item 5 becomes "at least one authentication method": OIDC provider OR local accounts (auth.md §5A), with the operator's first-admin story for the no-IdP case (hash-seeded one-time credential, forced first-login change, admin resets — no SMTP, no self-registration). Appendix A compose comment and Appendix B quickstart updated: the demo now logs in with a local account (`demo-admin@demo.local` / `demo-admin`, one-time) and needs no OIDC client. |
