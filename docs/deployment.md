# Deployment & Packaging Specification

**Status:** v1.2
**Owner:** datapipelines.co core
**Depends on:** all other specs
**Last updated:** 2026-08-07

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
5. **At least one OIDC provider** under `datapipelines.auth.oidc.providers`, each with `client-id`, `client-secret`, and `issuer-uri`. There is no local password login and no bootstrap admin account — with zero providers the `ClientRegistrationRepository` bean fails construction and the context never starts ([Auth §5.1](auth.md#51-provider-configuration-generic), [§5.2](auth.md#52-clientregistration-bean-built-at-startup)). The client-id/secret env var names are chosen by the deployment (`GOOGLE_CLIENT_ID`, `OKTA_CLIENT_ID`, …) — they are the one deliberate exception to the derivation rule above.

Everything else has a default and is optional.

### 5.2 Everything else

Optional keys — executor concurrency, staging memory, result TTLs and caps, SSE heartbeat and disconnect grace, rate limits, idempotency TTL, template cache, UI theme, retention windows, observability — are cataloged with their defaults in [Configuration §3](configuration.md#3-optional-configuration-with-defaults). Resolution precedence (env > profile YAML > base YAML, plus the two per-entity runtime overrides) is [Configuration §4](configuration.md#4-precedence).

The keys an operator most often changes at deploy time are `datapipelines.result.max-size-bytes` (Redis sizing, §4.2.2), `datapipelines.executor.max-concurrent-executions-global` and `datapipelines.staging.h2.max-memory-mb` (heap sizing, §6.6), and `datapipelines.executor.execution-timeout-seconds` (shutdown grace, §8.3.1).

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
- **Instance crash:** loses only that instance's in-flight executions (H2 staging is in-memory and non-recoverable). Their rows are swept to `ABORTED` by the stale-execution sweep (`datapipelines.executions.stale-timeout-minutes`). Completed results and history are unaffected.

#### Multi-instance checklist

| Requirement | How |
|---|---|
| Shared Postgres | All instances connect to the same external Postgres. |
| Shared Redis | All instances connect to the same Redis (results, idempotency keys, cancellation flags, event log) with `maxmemory-policy noeviction` — §4.2.1. A per-instance Redis breaks cross-instance cancel and result reads. |
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
java -Duser.timezone=UTC -jar datapipelines-app.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/datapipelines \
  --datapipelines.redis.host=localhost
```

`-Duser.timezone=UTC` is required here too (§3.1) — the image sets it for you, a bare JVM does not.

Not recommended for production.

### 6.6 Resource sizing

Two numbers matter: JVM heap, and the container memory limit that must contain it.

**Heap.** The dominant consumer is H2 staging — each executing pipeline holds up to `datapipelines.staging.h2.max-memory-mb` (default 1024 MB), or its own `settings.tempdb.config.max_memory_mb` override:

```
heap ≥ (staging max-memory-mb × concurrent executions on THIS instance) + ~512 MB baseline
```

The multiplier is the instance's realistic share, **not** `max-concurrent-executions-global`. That key is a cluster-wide ceiling; with N instances behind a round-robin LB the per-instance share is roughly `global / N`, but the ceiling is what an unbalanced moment can actually land on one instance — size for the share, and cap the exposure by lowering `max-concurrent-executions-global` (or `max-memory-mb`) rather than by hoping for even distribution. The ~512 MB baseline covers Spring context, Hikari pools, the template cache, and SSE buffers.

Worked example — 4 concurrent executions per instance at the 1024 MB default: `4 × 1024 + 512 ≈ 4.6 GB` heap.

**Container memory limit.**

```
container limit ≈ heap × 1.5
```

The 0.5 covers what the heap number does not: metaspace, code cache, thread stacks, and — significant here — JDBC direct/native buffers, which scale with result-set width and fetch size. A limit set equal to `-Xmx` gets the container OOM-killed by the kernel rather than getting a clean `OutOfMemoryError`, which is strictly worse to diagnose. For the example above: ~7 GB limit.

**JVM flags.** Either `-XX:MaxRAMPercentage=65` (heap tracks the container limit — preferred for k8s, where limits change without an image rebuild) or an explicit `-Xmx`. Do not set both. Always `-Duser.timezone=UTC` (§3.1).

**CPU.** Execution is coroutine-based and largely I/O-bound on source databases; 2 vCPU per instance is a reasonable floor. Scale out on `max-concurrent-executions-global` pressure, not CPU.

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
4. Stop the old version.
5. Start the new version (migrations apply on startup).
6. Verify `/health` returns UP.
7. Restore traffic.

For k8s: rolling update via `kubectl rollout`. The Helm chart defaults to a safe rolling strategy — see §8.3.2 for the pod-lifecycle settings that make it graceful.

#### 8.3.1 Graceful shutdown mechanism

Shutdown is a defined sequence, not "stop the process and hope". On `SIGTERM` the instance:

1. **Stops accepting new executions.** `/ready` starts failing immediately, so the load balancer / k8s Service stops routing new traffic to this instance. New `POST /pipelines/{id}/execute` requests that still arrive (in-flight LB decisions) are rejected — they belong on another instance. `/health` keeps reporting UP while draining, so nothing kills the pod for being unhealthy mid-drain.
2. **Drains in-flight executions** for up to `datapipelines.executor.execution-timeout-seconds` (default 600). Executions already running are allowed to finish normally and stream their terminal SSE event; the drain window is bounded by the same timeout that already bounds any single execution, so a healthy instance drains fully.
3. **Cancels the stragglers** at the drain deadline via the ordinary cancellation path — `CancellationRegistry.cancelAll(reason = shutdown)`: `Statement.cancel()` on every registered statement, root `Job` cancellation, `execution_aborted` (`reason: "shutdown"`) emitted to connected streams, tempdb dropped and connections released in `finally` ([DAG Executor §8.3](dag-executor.md#83-cancellation)).
4. **Exits** after the Redis result writes and Postgres status updates for those aborts have flushed.

**The accepted loss, stated plainly:** an execution still running when the drain deadline expires is **cancelled, not preserved**. It ends `ABORTED` with `reason: "shutdown"`, is visible as such in execution history, and its client must re-execute. There is no execution hand-off to another instance and no resumption — in-memory H2 staging makes migration impossible, and pretending otherwise would be worse than the honest abort. Bounded, visible loss beats a silent hang.

#### 8.3.2 Kubernetes pod lifecycle

```yaml
terminationGracePeriodSeconds: 630     # execution-timeout-seconds (600) + 30
lifecycle:
  preStop:
    exec:
      command: ["sleep", "5"]
```

- **`terminationGracePeriodSeconds` = `execution-timeout-seconds` + 30.** Anything shorter and the kubelet `SIGKILL`s mid-drain — executions die without their `finally` blocks, so no `execution_aborted` event, no status update, and rows left `RUNNING` until the stale-execution sweep catches them. The +30 covers step 4's flush. **If you change `datapipelines.executor.execution-timeout-seconds`, change this value with it** — they are one setting expressed in two places.
- **`preStop: sleep 5`** closes the standard k8s race: endpoint removal and `SIGTERM` are concurrent, so without it the pod can stop accepting work microseconds before the LB stops sending it. Five seconds of overlap is enough for Endpoints propagation.
- A `PodDisruptionBudget` (§6.4) keeps node drains from taking every replica's drain window at once.

A long `execution-timeout-seconds` therefore has a real deployment cost: it is also the worst-case rolling-update step time. Instances with long-running pipelines drain slowly by design.

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

      # REQUIRED: at least ONE OIDC provider must be configured, or startup fails
      # (auth.md §5.2 — no local password login, no bootstrap admin account exists).
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

## Appendix B: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 draft | initial draft | Initial deployment spec sketch — Docker image, infra requirements, configuration, deployment patterns, upgrade/rollback, security checklist |
| 2026-08-05 | v1.1 | horizontal scaling | Added multi-instance horizontal scaling section. Application is stateless for all CRUD/UI/MCP/auth. In-flight executions are instance-local (acceptable for short-running pipelines). No sticky sessions required. Added multi-instance checklist. Added LB idle-timeout + SSE heartbeat note. |
| 2026-08-07 | v1.2 | consistency campaign | Applied [SPEC-REVIEW-2026-08 §2.15](SPEC-REVIEW-2026-08.md#215-deploymentmd): §5 env-var tables replaced by the startup-requirements list + pointer to configuration.md; the inline-vs-claim-check threshold key (superseded by the D9 result keys) and every other key configuration.md does not define were deleted [D8]; §4.2 rewritten as the result store with required `maxmemory-policy noeviction` and a sizing model [D9]; §8.3.1/§8.3.2 graceful-shutdown mechanism (readiness fail → drain to `execution-timeout-seconds` → `cancelAll(shutdown)` → exit) with k8s `preStop` + `terminationGracePeriodSeconds`, accepted loss stated [D7]; §6.2 instance-local story updated to cancel-on-disconnect + cross-instance cancel via Redis flag [D7]; Appendix A compose made bootable (OIDC provider env vars, Redis password wired to `requirepass`, noeviction, mounted provider YAML); new §3.5 JDBC driver matrix (bundled vs `-Poracle`/`-Pmysql` vs `lib/` drop-in); new §6.6 resource sizing (heap, container limit, `-XX:MaxRAMPercentage`); `-Duser.timezone=UTC` made normative in the image and bare-JVM entrypoints ([Type System §8.4](type-system.md#84-timestamp-timezone-normalization)); §6.2 diagram residue and §11 malformed bullet fixed |
