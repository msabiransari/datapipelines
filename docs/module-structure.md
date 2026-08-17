# Module Structure Specification

**Status:** v1.3 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** (all other specs — this spec operationalizes them into code structure)
**Last updated:** 2026-08-15

---

## 1. Purpose

This spec defines how the datapipelines.co codebase is organized into **Gradle subprojects (modules)**, the **dependency direction** between them, the **version catalog** for pinned dependencies, and the **build / test conventions** that apply to every module.

A multi-module structure (vs. a single monolithic codebase) gives us:
- Clear **ownership boundaries** — each spec maps to one module.
- Independent **testability** — modules are unit-tested in isolation.
- Future **artifact extraction** — `typesystem` could be published as a client library; `mcp-server` could be embedded in another product.
- Build **incrementality** — change in `templates` doesn't rebuild `dag`.

---

## 2. Design Principles

1. **One module per spec.** Each major spec becomes a Gradle module. The spec is the contract; the module is the implementation.
2. **Layered dependencies, no cycles.** Foundational modules (typesystem) depend on nothing internal. Higher-level modules depend on lower. The dependency graph is a DAG, like pipelines.
3. **Kotlin-first.** Kotlin DSL for Gradle (`build.gradle.kts`). Kotlin code for implementations. Java interop where libraries require it.
4. **Version catalog.** All third-party dependency versions pinned in `gradle/libs.versions.toml`. No version literals in module build files.
5. **Convention over configuration.** Common build settings (Kotlin version, JVM target, test framework, linting) defined once in the root `build.gradle.kts`, applied via `common-conventions` plugin.
6. **Test parallelism.** Each module's tests run independently. Root `test` aggregates results. Integration tests in a separate module to keep unit-test runs fast.

---

## 3. Module List

```
datapipelines/
├── settings.gradle.kts
├── build.gradle.kts                     # root build, applies conventions
├── gradle/
│   └── libs.versions.toml               # version catalog
├── buildSrc/
│   └── src/main/kotlin/
│       ├── CommonConventionsPlugin.kt
│       └── KotlinConventionsPlugin.kt
├── modules/
│   ├── typesystem/                      # [Type System spec]
│   ├── pipeline-contract/               # [Pipeline Contract spec]
│   ├── templates/                       # [Templates spec]
│   ├── datasources/                     # [Datasources spec]
│   ├── staging/                         # [Staging spec]
│   ├── dag/                             # [DAG Executor spec]
│   ├── auth/                            # [Auth spec]
│   ├── mcp-server/                      # [MCP Server spec]
│   ├── web/                             # REST API, SSE, Thymeleaf UI
│   └── app/                             # Spring Boot entry point, assembles everything
├── tests/
│   └── integration-tests/               # cross-module integration tests
└── docs/                                # these specs
```

### 3.1 Module responsibility matrix

| Module | Spec | Responsibility | Owns persistence for |
|---|---|---|---|
| `typesystem` | [type-system.md](type-system.md) | The 11 canonical types, per-dialect mappers, H2 mapping, schema envelope. Foundation. | — (no persistence) |
| `pipeline-contract` | [pipeline-contract.md](pipeline-contract.md) | Pipeline JSON model, validation, ExecutionContext type. | `PipelineRepository` → `pipelines`, `pipeline_versions` |
| `templates` | [templates.md](templates.md) | Freemarker integration, library macros, template registry, versioning. | `TemplateRepository` → `templates`, `template_versions` |
| `datasources` | [datasources.md](datasources.md) | Connection registry, HikariCP pools, dialect adapters, credential encryption. | `DatasourceRepository` → `datasources` |
| `staging` | [staging.md](staging.md) | H2 lifecycle, staging interface, type-aware batch inserts. | — (tempdb is per-execution, never persisted) |
| `dag` | [dag-executor.md](dag-executor.md) | DAG data structure, executor (coroutines), node runner, SSE event emitter interface, Redis-backed result store. | `ExecutionRepository` → `pipeline_executions`; `ExecutionEventRepository` → `execution_events`; Redis keys for results / idempotency / cancel flags |
| `auth` | [auth.md](auth.md) | Users, API keys, JWT sessions, scopes, audit log. | `UserRepository` → `users`; `ApiKeyRepository` → `api_keys`; `AuditLogger` → `audit_log` |
| `mcp-server` | [mcp-server.md](mcp-server.md) | MCP transport (Streamable HTTP), tool/resource/prompt definitions. Thin adapter over the same services the REST layer uses. | — (delegates to the owning modules' repositories) |
| `web` | [rest-api.md](rest-api.md) | Spring Boot REST controllers, SSE endpoints, Thymeleaf UI, error handling, CORS. | — (delegates); Redis keys for the post-completion SSE event log and per-user rate-limit counters |
| `app` | (this spec) | Spring Boot `main()`, assembles all modules, configuration, runnable JAR, **Flyway dependency + migration scripts**. | Owns schema *creation* (Flyway), not data access |
| `tests/integration-tests` | (this spec) | Cross-module integration tests (Testcontainers for real databases). | — |

#### Persistence ownership rule (normative)

1. **Repositories live in their owning domain module**, never in a shared `persistence` module and never in `web`. Each owning module takes `org.springframework:spring-jdbc` (via `spring-boot-starter-jdbc`) and uses `NamedParameterJdbcTemplate` per §8.1.
2. **The schema is owned by one module: `app`.** The Flyway dependency (`flyway-core` + `flyway-database-postgresql`) and every migration script under `src/main/resources/db/migration/` live in `app` only. Domain modules read and write tables; they never create or alter them. DDL authority is [Metadata DB §4](metadata-db.md#4-table-definitions); the migration file layout is [Metadata DB §7.1](metadata-db.md#71-file-structure).
3. **Redis is a dependency of exactly two modules**: `dag` (result store, idempotency keys, cancellation flags) and `web` (post-completion SSE event log, per-user rate-limit counters). Both take `spring-boot-starter-data-redis` (Lettuce client, the starter's default). No other module talks to Redis.
4. **The `DataSource` bean for the metadata DB is a single app-level bean** (Spring Boot autoconfiguration from the `spring.datasource.*` keys defined in [configuration.md §3](configuration.md)). Modules inject `NamedParameterJdbcTemplate`, never construct their own pool. This is distinct from the *user* datasource pools owned by `ConnectionPoolManager` in the `datasources` module.

---

## 4. Dependency Direction

### 4.1 Layered dependency graph

This diagram is a **rendering of the normative table in §4.2** — it carries no information the table does not. When the two disagree, §4.2 wins and the diagram is the bug.

```
                              ┌──────────────┐
                              │  typesystem  │              layer 0 — no internal deps
                              └──────┬───────┘
             ┌──────────────┬────────┴────────┬──────────────┐
             │              │                 │              │
      ┌──────▼──────┐ ┌─────▼──────┐   ┌──────▼──────┐ ┌─────▼─────┐
      │  pipeline-  │ │ datasources│   │   staging   │ │   auth    │  layer 1
      │  contract   │ │            │   │             │ │           │
      └──┬───────┬──┘ └─────┬──────┘   └──────┬──────┘ └─────┬─────┘
         │       │          │                 │              │
   ┌─────▼─────┐ │          │                 │              │
   │ templates │ │          │                 │              │        layer 2
   └─────┬─────┘ │          │                 │              │
         │       │          │                 │              │
         └───────┴────┬─────┴─────────────────┘              │
                      │                                      │
               ┌──────▼──────┐                               │
               │     dag     │  ← + typesystem               │        layer 3
               │  (executor) │                               │
               └──────┬──────┘                               │
                      │                                      │
               ┌──────▼──────┐                               │
               │  mcp-server │ ◄─────────────────────────────┘        layer 4
               │             │  ← + typesystem, pipeline-contract,
               └──────┬──────┘    templates, datasources
                      │
               ┌──────▼──────┐
               │     web     │  ← + every module in layers 0–3          layer 5
               └──────┬──────┘    (declared explicitly, not transitively)
                      │
               ┌──────▼──────┐
               │     app     │  ← web only                              layer 6
               └─────────────┘
```

### 4.2 The dependency rule (machine-checkable)

There is **one** layering rule, and it is a table lookup, not a judgment call:

> **A module's `dependencies { implementation(project(...)) }` block MUST list a subset of its row below, and every module it uses at compile time MUST be listed explicitly (no reliance on transitive `api` leakage).**

| Module | Allowed internal dependencies (exhaustive) |
|---|---|
| `typesystem` | *(none)* |
| `pipeline-contract` | `typesystem` |
| `templates` | `typesystem`, `pipeline-contract` |
| `datasources` | `typesystem` |
| `staging` | `typesystem` |
| `auth` | `typesystem` |
| `dag` | `typesystem`, `pipeline-contract`, `templates`, `datasources`, `staging` |
| `mcp-server` | `typesystem`, `pipeline-contract`, `templates`, `datasources`, `dag`, `auth` |
| `web` | `typesystem`, `pipeline-contract`, `templates`, `datasources`, `staging`, `dag`, `auth`, `mcp-server` |
| `app` | `web` |
| `tests/integration-tests` | `app` |

Notes on the shape (explanatory, not additional rules):

- The table is acyclic by construction, so "no cycles" needs no separate rule — Gradle enforces it anyway.
- `dag` does **not** list `auth`: the executor is handed an already-authenticated principal by its caller. `mcp-server` **does** list `auth` (it authenticates its own transport, [MCP Server §3.2](mcp-server.md)) and `dag` (the `pipelines_execute` / `executions_*` tools drive the executor directly rather than looping back through HTTP).
- `web` lists everything it touches **explicitly**. It could reach most of these transitively through `mcp-server`; declaring them is what makes the table checkable.
- `app` lists `web` only. It contains `main()`, configuration, logback config, and the Flyway migrations (§3.1) — no domain code.
- A module may use `api(project(...))` instead of `implementation(...)` only where its own public API exposes the other module's types (e.g. `pipeline-contract` exposes `ColumnSchema` from `typesystem`). The allowed-set is the same either way.

**Enforcement:** a Gradle verification task compares each subproject's declared project dependencies against this table and fails the build on any extra entry. Adding an edge means editing this table first — that is the review gate.

### 4.3 Cross-cutting concerns

Some concerns touch every module:
- **Logging** — SLF4J + Logback (or structured logging via `minlog`/`logstash-logback-encoder`). Each module logs via SLF4J API; the actual logback config lives in `app`.
- **Error handling** — every module's exceptions extend a base `DatapipelinesException`, which lives in `typesystem`. There is deliberately **no `common` module**: the §4.2 table is exhaustive, and a catch-all module is where layering rules go to die.
- **Configuration** — typed config classes per module, composed into the global `app` config.
- **Metrics** — Micrometer API in modules, actual metrics registry configured in `app`.

---

## 5. Module Specs (Detailed)

### 5.1 `typesystem`

**Dependencies (internal):** none.

**Dependencies (external):** `com.fasterxml.jackson.module:jackson-module-kotlin` (JSON for the schema envelope). Jackson is the project-wide JSON library — the `@JsonValue` / `@JsonCreator` enum mapping in [Enums §1](enums.md) is normative, so there is no second serialization stack.

**Public API:**
- `LogicalType` enum
- `ColumnSchema` data class
- `IngressTypeMapper` interface + per-dialect implementations (`PostgresTypeMapper`, `OracleTypeMapper`, `MssqlTypeMapper`, `MysqlTypeMapper`, `H2IngressMapper`, `DuckDbTypeMapper`, `SqliteTypeMapper`)
- `H2IngressMapper` (H2 JDBC metadata → canonical `ColumnSchema`) and `H2EgressMapper` (canonical → H2 DDL type string + `java.sql.Types` code) — **two objects, not inverses**; signatures in [Staging §5.3](staging.md#53-mappers-and-helper-signatures)
- `JsonEncoder` (canonical value → wire representation)
- `SchemaEnvelope` data class

**Tests:** unit tests for every mapper; round-trip tests for every type.

### 5.2 `pipeline-contract`

**Dependencies (internal):** `typesystem`.

**Dependencies (external):**
- `com.fasterxml.jackson.module:jackson-module-kotlin` — Pipeline JSON ser/deser ([Pipeline Contract §17.1](pipeline-contract.md#171-where-this-lives-in-the-codebase) specifies Jackson).
- `org.springframework.boot:spring-boot-starter-jdbc` (brings `org.springframework:spring-jdbc`) — for `PipelineRepository`.

**Public API:**
- `Pipeline` data class (top-level entity)
- `Node`, `NodeType`, `NodeSource` data classes
- `NodeOutput` — flat sealed interface (`Tempdb`, `Caller`, `Datasource`)
- `PipelineSettings` (with nested `TempdbSettings`)
- `Parameter` data class
- `TemplateRef` data class (`{id, version}`)
- `PipelineValidator` — runs all [§12](pipeline-contract.md#12-validation-rules) validations from the spec
- `PipelineSerializer` / `PipelineDeserializer` (Jackson; an omitted `output` on a DQL node deserializes to `NodeOutput.Caller`)
- `CallerNodeResolver` — resolves the caller node per [Pipeline Contract §9](pipeline-contract.md#9-the-caller-node-result-node). **Replaces topology-based terminal-node detection, which no longer exists in any form** (D1).
- `ExecutionContext` — runtime mutable map
- `PipelineRepository` — `NamedParameterJdbcTemplate` access to `pipelines` / `pipeline_versions` (§8.1)

**Tests:** unit tests for validator (every check + every code path); serialization round-trip tests; `PipelineRepository` integration tests against a Postgres Testcontainer.

### 5.3 `templates`

**Dependencies (internal):** `typesystem`, `pipeline-contract` (for `Parameter` shape).

**Dependencies (external):**
- `org.freemarker:freemarker` (pinned).
- `org.springframework.boot:spring-boot-starter-jdbc` — for `TemplateRepository`.

**Public API:**
- `Template`, `TemplateVersion` data classes
- `TemplateImport` data class (`{id, version, alias}`) — D12
- `TemplateRegistry` interface — lookup by `id@version`, caching
- `RegistryTemplateLoader` — the Freemarker `TemplateLoader`; resolves only `"{id}@{version}"` keys against the registry
- `TemplateEngine` — wraps Freemarker; owns the render guards ([Templates §4.3](templates.md))
- `TemplateValidator`
- `LibraryResolver` — transitive import resolution (depth cap, cycle detection, alias uniqueness)
- `TemplateRepository` — `NamedParameterJdbcTemplate` access to `templates` / `template_versions` (§8.1)

**The module's public API carries no parameter-schema types.** The template entity's parameter-schema field was removed entirely (D3) — pipeline `parameters` is the single declaration point, so there is no `ParamsSchema`, `ParamSpec`, or equivalent type here. Matches [Templates §12.1](templates.md#121-where-this-lives).

**Tests:** unit tests for validator; round-trip tests for sample templates; security tests for forbidden-construct rejection; `TemplateRepository` integration tests against a Postgres Testcontainer.

### 5.4 `datasources`

**Dependencies (internal):** `typesystem` (for `IngressTypeMapper` per dialect).

**Dependencies (external):**
- `com.zaxxer:HikariCP` — connection pooling.
- `org.postgresql:postgresql` — bundled PG driver.
- `com.microsoft.sqlserver:mssql-jdbc` — bundled MSSQL driver.
- `com.h2database:h2` — bundled H2 driver (also for staging).
- `org.duckdb:duckdb_jdbc` — bundled DuckDB driver.
- `org.xerial:sqlite-jdbc` — bundled SQLite driver.
- `org.springframework.boot:spring-boot-starter-jdbc` — for `DatasourceRegistry`'s `DatasourceRepository`.

> **BouncyCastle removed (2026-08-07, security review MEDIUM-6).** The AES-256-GCM the credential store needs ([Datasources §7](datasources.md#7-credential-storage)) is fully served by the JDK's SunJCE (`AES/GCM/NoPadding`); no spec names a primitive requiring an external provider, and carrying an 8 MB provider with a steady advisory cadence for an unnamed capability fails the dependency rules. If a future implementation genuinely needs one, it comes back through the spec-deviation flow with the primitive named.
- Optional: `com.oracle.database.jdbc:ojdbc11` (via `-Poracle` Gradle property).
- Optional: `com.mysql:mysql-connector-j` (via `-Pmysql` Gradle property).

**Public API:**
- `Datasource` data class
- `Dialect` enum — **declared in `typesystem`** (2026-08-08: the single authoring authority is [Type System §5](type-system.md#5-source-to-canonical-mapping-tables) per enums.md §5, and `TypeMappers.forDialect(dialect: Dialect)` lives in typesystem, which depends on nothing internal — so the type cannot be declared here). This module consumes and re-exposes it through its typesystem dependency.
- `DatasourceRegistry` interface
- `DialectAdapter` interface + per-dialect implementations
- `JdbcDrivers` — driver class lookup / availability check
- `CredentialEncryptor` — AES-256-GCM
- `ConnectionPoolManager` — HikariCP wrapper for **user** datasources (distinct from the metadata-DB pool, §3.1 rule 4)
- `DatasourceRepository` — `NamedParameterJdbcTemplate` access to `datasources` (§8.1)

#### 5.4.1 Optional driver profiles — implementation sketch

Both optional drivers are `runtimeOnly` (nothing compiles against them; `JdbcDrivers` resolves them reflectively by class name — [Datasources §10.3](datasources.md#103-driver-class-lookup)). They are gated on Gradle **project properties**, not Gradle *profiles* (Gradle has no profiles):

```kotlin
// modules/datasources/build.gradle.kts
dependencies {
    implementation(project(":modules:typesystem"))
    implementation(libs.hikaricp)
    implementation(libs.spring.boot.starter.jdbc)

    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mssql.jdbc)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.duckdb.jdbc)
    runtimeOnly(libs.sqlite.jdbc)

    // ./gradlew -Poracle build   → OTN-licensed driver bundled (operator accepts the licence)
    if (project.hasProperty("oracle")) {
        runtimeOnly(libs.ojdbc11)
    }
    // ./gradlew -Pmysql build    → GPL+FOSS-exception driver bundled
    if (project.hasProperty("mysql")) {
        runtimeOnly(libs.mysql.connector.j)
    }
}
```

Two consequences worth stating, because both are easy to get wrong:

- **The flag must be passed to every task that builds the artifact.** `-Poracle` on `build` and not on `bootJar` produces a JAR without the driver. CI publishes the optional variants as separate jobs (`./gradlew -Poracle bootJar`), never as a post-hoc patch of the default JAR.
- **Property presence, not value, is the switch.** `-Poracle=false` still enables it (`hasProperty` is true). Documented here so nobody "disables" it that way.

**`lib/` drop-in (no rebuild).** The licence-clean alternative is deploy-time: the operator drops `ojdbc11.jar` into a `lib/` directory beside the application JAR. This is **not** automatic — the default `JarLauncher` only reads `BOOT-INF/lib/`. Extra classpath entries require `PropertiesLauncher`, selected by the JAR manifest:

```kotlin
// modules/app/build.gradle.kts
tasks.named<BootJar>("bootJar") {
    // PropertiesLauncher is the only launcher that honours loader.path.
    manifest {
        attributes("Main-Class" to "org.springframework.boot.loader.launch.PropertiesLauncher")
    }
}

tasks.named<BootRun>("bootRun") {
    classpath += files("lib")   // dev parity: same drop-in directory, no packaging
}
```

At runtime the operator points the launcher at the directory with `loader.path` — a comma-separated list of directories, archives, or directories within archives, resolved relative to `loader.home` (default: the process working directory). It can be supplied as a system property, the `LOADER_PATH` environment variable, or a `loader.properties` file:

```
java -Dloader.path=lib -jar datapipelines-app.jar
# or, in the container image:
ENV LOADER_PATH=lib
```

The deployment image therefore ships an empty `lib/` and sets `LOADER_PATH=lib` so a drop-in works with no rebuild and no re-configuration ([Deployment §6](deployment.md) owns the image contents).

The wiring lives in `app` (it owns the runnable artifact) even though the drivers are a `datasources` concern. A driver that is neither bundled nor dropped in fails datasource save with `datasource.driver_not_loaded` — see [Datasources §10](datasources.md#10-jdbc-driver-packaging) for the licensing rationale and the full driver matrix.

**Tests:** unit tests for adapter URL validation; integration tests via Testcontainers (real DB containers); a build-level check that `-Poracle bootJar` contains `ojdbc11` and the default `bootJar` does not.

### 5.5 `staging`

**Dependencies (internal):** `typesystem`.

**Dependencies (external):**
- `com.h2database:h2`
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — the single staging connection is serialized by an explicit `Mutex` ([Staging §9.2](staging.md#92-serialization-is-explicit--mutex-not-the-driver)).

**Public API:**
- `Staging` interface
- `StagingFactory` interface — `create(executionId, engine: StagingEngine = H2)`
- `H2Staging`, `H2StagingFactory` implementations
- `StageResult` (`columns: List<ColumnSchema>`), `StagingStats` data classes

No repository: tempdb lives and dies with one execution and is never persisted (§3.1).

**Tests:** unit tests for type mapping; integration tests for staging round-trip; streaming tests for memory-bounded behavior.

### 5.6 `dag`

**Dependencies (internal):** `pipeline-contract`, `templates`, `datasources`, `staging`, `typesystem`.

**Dependencies (external):**
- `org.jetbrains.kotlinx:kotlinx-coroutines-core`
- `org.springframework.boot:spring-boot-starter-data-redis` (Lettuce, the starter's default client) — result store, idempotency keys, cancellation flags
- `org.springframework.boot:spring-boot-starter-jdbc` — for the execution repositories
- `io.micrometer:micrometer-core` — executor metrics ([DAG Executor §15.3](dag-executor.md#153-monitoring))

**Public API:**
- `Dag<T>` data structure (the ~150-line implementation)
- `PipelineExecutor`
- `ExecutableNode`, `NodeSource`, `NodeType`
- `NodeResult` — the executor's **internal** in-flight per-node value ([§7.1](dag-executor.md#71-noderesult--the-executors-in-flight-per-node-value)); carries `callerResultRef`, a Redis key, never a live `ResultSet`
- `NodeStats`, `NodeStatus` — the wire-facing projection of `NodeResult`
- `CancellationRegistry`, `CancellationHandle` — per-node `Statement` registration and cancel ([§8.3.1](dag-executor.md#831-the-registry))
- `ExecutionAbortedException`, `AbortReason` (`CLIENT_DISCONNECT`, `CANCELLED`, `SHUTDOWN`) — `ExecutionAbortedException` extends `CancellationException`, maps to no error code
- `ResultStore` — Redis-backed caller-result materialization + paging cursor (D9)
- `ExecutionSlots` — per-user + global concurrency permits
- `ExecutorDispatcher` — the module's own bounded IO dispatcher (executor code never touches `Dispatchers.IO`)
- `EventEmitter` interface
- `ExecutionEvent` sealed class
- `ExecutionRepository` — `pipeline_executions`; `ExecutionEventRepository` — `execution_events` (durable 7-day record; the 1h Redis event log is `web`'s, §5.9)

**Tests:** unit tests for `Dag<T>` algorithms; unit tests for executor (mocked dependencies); cancellation tests covering all three `AbortReason` paths incl. the cross-instance Redis flag; integration tests with real H2 + Testcontainers sources + a Redis container.

### 5.7 `auth`

**Dependencies (internal):** `typesystem` (shared exception base only).

**Dependencies (external):**
- `de.mkammerer:argon2-jvm`
- `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, `jjwt-jackson`
- `org.springframework.boot:spring-boot-starter-oauth2-client` (Spring Security web/config + OAuth2 client + Jose, per [Auth §12.2](auth.md#122-dependencies) — which correctly lists no BouncyCastle; see the §5.4 removal note)
- `org.springframework.boot:spring-boot-starter-jdbc` — for the user / key / audit repositories

**Public API:**
- `User`, `ApiKey`, `AuthenticatedPrincipal` data classes
- `Scope` enum
- `JwtService`, `ApiKeyService`, `UserService`
- `OidcSuccessHandler`, `JwtAuthenticationFilter`, `ApiKeyFilter`, `SecurityConfig`
- `@RequiredScope` annotation + `ScopeInterceptor`
- `AuditLogger` — writes `audit_log`
- `UserRepository` — `users`; `ApiKeyRepository` — `api_keys` (§8.1)

The per-request `is_active` / revocation re-check (D13) reads through the same 60s cache as the key-hash lookup; the cache is owned by this module and is **in-process per instance, not Redis** — it is a read-through cache of Postgres truth, not shared state ([Auth §11.4](auth.md#114-api-key-validation-cache)).

**Tests:** unit tests for each component; integration tests for full auth flow; repository integration tests against a Postgres Testcontainer.

### 5.8 `mcp-server`

**Dependencies (internal):** `typesystem`, `pipeline-contract`, `templates`, `datasources`, `dag`, `auth`.

`dag` is a real dependency, not an accident of layering: `pipelines_execute` and the `executions_*` tools drive `PipelineExecutor` and `ResultStore` directly. `mcp-server` is a thin adapter over the same **service layer** the REST controllers use — it never loops back through HTTP, and it must not (that would make `web` a dependency and create a cycle).

**Dependencies (external):**
- `io.modelcontextprotocol.sdk:mcp-core` **2.0.0** and `io.modelcontextprotocol.sdk:mcp-json-jackson2` **2.0.0**.

> **Gate G1 — RESOLVED 2026-08-07** (verified by downloading and inspecting the published jars, not docs). The earlier draft's `io.modelcontextprotocol:mcp-core` group id was indeed wrong — the real group is `io.modelcontextprotocol.sdk`. Facts that bind the `mcp-server` implementation:
> - `mcp-core-2.0.0` ships plain Jakarta-servlet Streamable HTTP transports (`HttpServletStreamableServerTransportProvider`, `HttpServletStatelessServerTransport`) — drop onto Spring MVC directly. The stateless variant matches [MCP Server §3.3](mcp-server.md#33-session-lifecycle).
> - **Use `mcp-json-jackson2`, never the `mcp` aggregator** — the aggregator pulls `mcp-json-jackson3` (Jackson 3.x) onto a Jackson 2.x classpath.
> - `mcp-spring-webmvc` stopped at 0.18.3 (two majors stale) — do not use it. The Ktor-based `io.modelcontextprotocol:kotlin-sdk` was rejected (second HTTP stack).
> - Protocol versions compiled into 2.0.0: `2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25`. Which to advertise remains the [MCP Server §3.1](mcp-server.md) gate.

**Public API:**
- `McpServer` — Spring Boot autoconfiguration
- `McpTool`, `McpResource`, `McpPrompt` annotation markers
- Tool/resource/prompt implementations (one per top-level entity operation)
- `McpAuthFilter` — translates API key into MCP session

**Tests:** unit tests for tool dispatch; integration tests using an in-process MCP client.

### 5.9 `web`

**Dependencies (internal):** `typesystem`, `pipeline-contract`, `templates`, `datasources`, `staging`, `dag`, `auth`, `mcp-server` — the aggregation layer, declared explicitly per §4.2.

**Dependencies (external):**
- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-thymeleaf`
- `org.springframework.boot:spring-boot-starter-validation`
- `org.springframework.boot:spring-boot-starter-data-redis` — post-completion SSE event log (1h) and per-user rate-limit counters. The durable 7-day event record is `dag`'s `ExecutionEventRepository`; these are two different stores for two different retention windows (D9).
- `io.micrometer:micrometer-core`
- `com.fasterxml.jackson.module:jackson-module-kotlin`
- `org.webjars:bootstrap` (for UI)
- `org.webjars.npm:htmx.org` (for reactive UI without writing JS — pairs well with Thymeleaf)

**Styling foundation:** `@acme/design-tokens` (v0.2.0+) — vendored CSS-only design system. NOT an npm dependency; vendored as static files under `static/vendor/design-system/`. Provides semantic tokens (`--surface-*`, `--text-*`, `--accent-*`), 9 swappable themes (default: `saas`), and ~80 `.ds-*` primitive component classes. All UI colors, spacing, typography, shadows, and radii reference design system tokens. No hardcoded hex values anywhere. See [Pipeline Editor spec §3.4](pipeline-editor.md#34-design-system-acmedesign-tokens) for integration details.

**Public API:**
- `RestController`s — REST endpoints (see [REST API spec](rest-api.md))
- `Controller`s — Thymeleaf UI controllers
- SSE endpoints (via Spring WebFlux's `Flux` or `SseEmitter`)
- Global exception handler (`@RestControllerAdvice`)
- CORS filter
- Design system CSS vendoring + theme configuration (`DATAPIPELINES_UI_THEME` env var, default: `saas`)

**Tests:** controller unit tests (`@WebMvcTest`); SSE flow tests; full integration tests in `tests/integration-tests`.

### 5.10 `app`

**Dependencies (internal):** `web` (transitively pulls everything).

**Dependencies (external):**
- `org.springframework.boot:spring-boot-starter`
- `org.springframework.boot:spring-boot-starter-web` — serves the root-level `/health` and `/ready` probes (rest-api.md §11), which lived here from P0 (added 2026-08-07; if the probe controller moves to `web`, this dependency reverts with it — the §4.2 internal table is unaffected either way).
- `org.springframework.boot:spring-boot-starter-actuator`
- `org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-postgresql` — schema migration. **This module is the only one that depends on Flyway** (§3.1 rule 2). The Postgres module is a separate artifact since Flyway 10 and is required for a Postgres target.
- `org.postgresql:postgresql` — the metadata-DB driver at runtime (also bundled by `datasources` for user datasources; the version catalog pins it once).

**Public API:**
- `DatapipelinesApplication.kt` — `@SpringBootApplication main()`
- `application.yml` — top-level config
- `logback-spring.xml` — logging config
- `src/main/resources/db/migration/V*.sql` — the Flyway migrations, generated from [Metadata DB §4](metadata-db.md#4-table-definitions) per [§7.1](metadata-db.md#71-file-structure)
- `bootJar` manifest wiring for the `lib/` driver drop-in (§5.4.1)

**Tests:** smoke tests via `@SpringBootTest` (full context load); a migration test that runs Flyway against a clean Postgres Testcontainer and asserts the resulting schema matches [Metadata DB §4](metadata-db.md#4-table-definitions).

### 5.11 `tests/integration-tests`

**Dependencies (internal):** `app` (full app context for end-to-end tests).

**Dependencies (external):**
- `org.springframework.boot:spring-boot-starter-test`
- `org.testcontainers:postgresql`, `mysql`, `mssql`, `oracle-xe` — one container module per **supported dialect** that needs a real server. DuckDB, SQLite and H2 are embedded (no container). There is **no DB2 container — DB2 is not a supported dialect** ([Type System §5](type-system.md#5-source-to-canonical-mapping-tables) / [Datasources §4.1](datasources.md#41-dialect-catalog) list the seven).
- A Redis container (`org.testcontainers:testcontainers` generic container, or the Redis module) — required for result delivery, idempotency, and cancellation tests (D9/D7).
- `io.rest-assured:rest-assured` or `spring-boot-starter-webflux` (for reactive test client)

**Purpose:** end-to-end tests that spin up real database containers, register datasources, create templates, build pipelines, execute them, verify results. The "cold executable" check — if the integration tests pass, the app works.

---

## 6. Version Catalog

`gradle/libs.versions.toml` — single source of truth for third-party versions.

```toml
[versions]
kotlin = "1.9.24"
spring-boot = "3.3.2"
kotlinx-coroutines = "1.8.1"
jackson = "2.17.2"
flyway = "10.17.0"
slf4j = "2.0.13"
logback = "1.5.6"
freemarker = "2.3.33"
hikari = "5.1.0"
postgresql = "42.7.3"
mssql-jdbc = "12.6.1.jre11"
mysql-connector-j = "8.4.0"
h2 = "2.2.224"
duckdb-jdbc = "1.0.0"
sqlite-jdbc = "3.46.1.0"
ojdbc11 = "23.4.0.24.114"
argon2-jvm = "2.11"
jjwt = "0.12.6"
micrometer = "1.13.2"
spring-boot-starter-test = "3.3.2"
testcontainers = "1.20.1"
mcp-sdk = "0.10.0"
htmx = "2.0.0"
webjars-bootstrap = "5.3.3"
junit-jupiter = "5.10.2"
mockk = "1.13.12"
kotest = "5.9.1"

[libraries]
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
jackson-module-kotlin = { module = "com.fasterxml.jackson.module:jackson-module-kotlin", version.ref = "jackson" }
# ... (every dependency declared here, used by ref from build.gradle.kts files)

spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web", version.ref = "spring-boot" }
spring-boot-starter-jdbc = { module = "org.springframework.boot:spring-boot-starter-jdbc", version.ref = "spring-boot" }
spring-boot-starter-data-redis = { module = "org.springframework.boot:spring-boot-starter-data-redis", version.ref = "spring-boot" }
spring-boot-starter-oauth2-client = { module = "org.springframework.boot:spring-boot-starter-oauth2-client", version.ref = "spring-boot" }

# Flyway — `app` module only (§3.1 rule 2). Since Flyway 10 the Postgres support is a
# separate artifact; flyway-core alone cannot migrate a Postgres target.
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
flyway-database-postgresql = { module = "org.flywaydb:flyway-database-postgresql", version.ref = "flyway" }
# etc.
```

**Redis client:** no explicit entry — `spring-boot-starter-data-redis` brings **Lettuce** as its default client, version-managed by the Spring Boot BOM. Adding a second, separately-pinned Lettuce entry would let it drift from the BOM; if Jedis is ever wanted instead, that is an explicit exclusion + dependency, and a spec change here.

> **Gate G2 — RESOLVED 2026-08-07** (lockfile sub-item closed 2026-08-15, §7.6). Every entry was resolved against `repo1.maven.org` `maven-metadata.xml`, pre-releases rejected, and BOM-managed artifacts (verified by parsing `spring-boot-dependencies:3.5.16` + its 43 imported BOMs) declared version-less. **`gradle/libs.versions.toml` is now the ratified source of truth for versions; the table above is historical.** Anchor decisions: Spring Boot 3.5.16 (4.x exists but §11.1 freezes 3.x for v1), Kotlin 2.4.10 (with an empirically-verified conflict-resolution win over the BOM's kotlin-bom 1.9.25 — see the toml comment). `gradle.lockfile` (transitive pinning) landed 2026-08-15 per §7.6. The original G2 procedure is retained below for future catalog changes:
> 1. Resolve the current stable release: `curl -s 'https://search.maven.org/solrsearch/select?q=g:"org.flywaydb"+AND+a:"flyway-core"&core=gav&rows=5&wt=json' | jq -r '.response.docs[].v'` (repeat per artifact; or run `./gradlew dependencyUpdates` once the build exists).
> 2. Reject anything that is not a released stable version — **no ranges, no `+`, no `latest.release`, no SNAPSHOT, no RC/M/beta** (§6.1, and the project-wide version-pinning rule).
> 3. Check BOM-managed artifacts are **not** pinned here at all: anything the `spring-boot` BOM manages (Jackson, Micrometer, Lettuce, HikariCP, the Spring modules) takes its version from the BOM. A local pin that disagrees with the BOM is a silent runtime-incompatibility source.
> 4. Verify JDK 21 compatibility for each pinned artifact, then commit `gradle.lockfile` (§6.1) in the same commit as the catalog.
> 5. Record the date the catalog was verified in the change log below.
>
> This gate is the reason the versions above are safe to leave stale: they are explicitly labelled unverified, so nobody builds on them believing otherwise.

### 6.1 Versioning rules

- **Pin every dependency to an exact version.** No ranges, no `+`, no SNAPSHOT in production.
- **Upgrade deliberately.** Dependabot or Renovate can open PRs, but no auto-merge.
- **Track security advisories.** GitHub Dependabot alerts + Snyk (or equivalent) on the repo.
- **Lockfile committed.** Gradle generates `gradle.lockfile` for repeatable builds (§7.6).

---

## 7. Build Conventions

### 7.1 `CommonConventionsPlugin`

Applied to every module. Configures:

```kotlin
class CommonConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        project.plugins.apply("io.spring.dependency-management")

        project.extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        project.extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                freeCompilerArgs.add("-Xjsr305=strict")
                // NOTE (2026-08-07): -Xcontext-receivers deliberately NOT set — the flag
                // was removed from the Kotlin compiler in 2.2 and is a hard error on 2.4.x.
                // Its successor (-Xcontext-parameters) is unused by v1.
                allWarningsAsErrors = true           // strict
            }
        }

        project.dependencies {
            testImplementation(libs.junit.jupiter)
            testImplementation(libs.mockk)
            // Kotest ASSERTIONS only — deliberately NOT kotest-runner-junit5 (2026-08-08).
            // The runner registers a second JUnit Platform TestEngine in every test JVM;
            // with zero Kotest spec classes it discovers nothing but participates in
            // result reporting, and it has open Gradle 9 incompatibilities that surface
            // as truncated test-result stores (intermittent EOFException in
            // SerializableTestResultStore). §7.4's convention is JUnit 5 as the platform,
            // Kotest as the assertion library. A module that wants Kotest SPECS adds the
            // runner to its own build file and owns that engine's Gradle compatibility.
            testImplementation(libs.kotest.assertions)
        }

        project.tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                showExceptions = true
                showCauses = true
            }
        }
    }
}
```

### 7.2 JVM target

- **JDK 21** (current LTS as of 2025). Use virtual threads (`Project Loom`) for blocking-IO work in v1.1+.
- Toolchain configured in Gradle; the build fails if the wrong JDK is used (rather than silently producing Java 17 bytecode).

### 7.3 Linting / formatting

- **ktlint** via plugin, enforced on build. No code merges with ktlint violations.
- **detekt** for static analysis. Configured in `detekt.yml`. Some rules relaxed where they conflict with Kotlin idioms.
- Pre-commit hook runs `./gradlew ktlintCheck detekt` (optional but recommended).

### 7.4 Test conventions

- **JUnit 5** as the platform.
- **MockK** for mocking (idiomatic for Kotlin, vs. Mockito which requires extra setup for final classes).
- **Kotest** for assertion library (more expressive than JUnit's built-in).
- **Testcontainers** for integration tests requiring real databases.
- Test file naming: `*Test.kt` for unit tests, `*IntegrationTest.kt` for integration tests (different Gradle task).
- Test source root convention: unit tests in each module's `src/test/kotlin`. Cross-module integration tests live in `tests/integration-tests/src/test/kotlin` — there is **no per-module `src/integrationTest` source set in v1**; the root `integrationTest` task simply depends on `:tests:integration-tests:test`. A module needing module-local integration tests (e.g. against a Testcontainer) puts them in its own `src/test/kotlin` with a `*IntegrationTest` name; the `verifyTestsExecuted` guard scans `src/test/kotlin` only, so tests placed anywhere else are unguarded — don't.

### 7.5 Build commands

```
./gradlew build                       # everything: compile, test, lint
./gradlew test                        # unit tests only
./gradlew integrationTest             # integration tests
./gradlew :modules:dag:test           # one module's tests
./gradlew bootJar                     # produce executable app JAR
./gradlew -Poracle build              # build with Oracle driver bundled
./gradlew -Pmysql build               # build with MySQL driver bundled
./gradlew clean build                 # full rebuild
```

### 7.6 Dependency locking

Every module — and buildSrc itself — resolves dependencies against a committed
`gradle.lockfile`. Locking covers **all configurations**
(`lockAllConfigurations()`) in **STRICT** mode, applied by
`CommonConventionsPlugin` to every module and declared directly in
`buildSrc/build.gradle.kts` (an included build is not reached by the main
build's locking). A resolution that drifts from the lock — a version bump, a
new or removed dependency, a shifting transitive — **fails the build**. This is
the mechanical twin of §6: the catalog records what we ASK for, the lockfile
records what was actually RESOLVED, and STRICT mode additionally fails on any
locked configuration with no recorded lock state, so a newly added
configuration cannot escape silently.

- Maintenance procedure (the only way locks may change): DEVELOPMENT.md §6.2 —
  `./gradlew resolveAndLockAll --write-locks`, then review and commit the diff
  with the dependency change that caused it.
- `resolveAndLockAll` is the documented "lock all configurations in a single
  build execution" pattern (docs.gradle.org dependency_locking); the built-in
  `dependencies` task resolves only one project's configurations, so each
  project registers its own copy.
- Exactly two artifacts are excluded from lock validation, both declared with
  their reason in `modules/datasources/build.gradle.kts`: the §5.4.1
  flag-gated drivers `ojdbc11` and `mysql-connector-j`. One committed lockfile
  cannot validate both the default build and `-Poracle` / `-Pmysql` builds.
- The root project locks too (STRICT, same `resolveAndLockAll` flow, declared
  directly in the root `build.gradle.kts`): it resolves the `kover` aggregation
  configuration for the cross-module coverage report (§7.7).

### 7.7 Coverage (Kover)

Every module gets the Kover plugin (`org.jetbrains.kotlinx.kover`, pinned in
the catalog) from `CommonConventionsPlugin`. The root project applies it too
and merges the modules into an aggregated report via `kover(project(...))`
dependencies — wired **reactively** through
`pluginManager.withPlugin("org.jetbrains.kotlinx.kover")` (012/F3), so a new
module joins the aggregate automatically and a subproject without the Kover
plugin cannot break root resolution. `tests/integration-tests` is
deliberately excluded from the ROOT aggregate: pulling its Testcontainers
tests into the report's task graph made `koverHtmlReport` need a Docker
daemon, and its integration coverage would break comparability with the
unit-only 2026-08-15 baseline the coverage floors derive from. Its own
module-level report still exists; only the root aggregate excludes it.
`./gradlew koverHtmlReport` produces per-module reports plus the aggregate.

`check` depends on `koverVerify`. Each module carries a minimum **line
coverage** floor in `COVERAGE_FLOORS` (CommonConventionsPlugin): the module's
measured baseline from the first Kover run (2026-08-15) minus 2 points,
rounded down. Floors are a regression tripwire, not a coverage target — raise
one only when coverage genuinely improved; never lower one to force a build
green. A module absent from `COVERAGE_FLOORS` **fails configuration** unless
it is in `NO_COVERAGE_FLOOR_ALLOWLIST` with its reason — currently only
`tests/integration-tests` (no main sources).

Escape hatch: `-Pkover.off` runs tests without the coverage agent attached
(for timing-sensitive diagnosis). The flag also skips the floor rules and the
`check`→`koverVerify` wiring — with instrumentation off there is no coverage
data, so a registered floor would fail on absent data. It is for targeted
diagnosis, not for making a red build green: the floors still enforce on
every normal build.

### 7.8 Architecture-as-tests (Konsist)

House layering rules that previously existed only as prose are encoded as
Konsist tests (pinned in the catalog, TEST dependency only):

- `modules/web` — `RequiredScopeKonsistTest`: every HTTP handler on a
  `@RestController` declares `@RequiredScope`. Deliberately redundant with the
  reflection-based `RequiredScopeCoverageTest`: one proves it on the live
  classpath, the other statically from sources.
- `tests/integration-tests` — `ArchitectureGuardTest`, scanning every module's
  production sources from the cross-module suite: no field injection
  (`@Autowired` on properties/fields), and `@Transactional` only on
  `*Service` classes (the house service-layer naming).

Konsist lives in existing test source sets only — no dedicated Gradle module.

---

## 8. Spring Boot Conventions

### 8.1 Persistence layer: NamedParameterJdbcTemplate

**Decision: `NamedParameterJdbcTemplate` exclusively. No JPA, no Hibernate, no Exposed.**

Reasons:
- **Predictable SQL.** Every query is explicit SQL — no generated SQL, no N+1 surprises, no lazy-loading traps. What you write is what runs.
- **Named parameters** (`:start_date`, `:pipeline_id`) are far more readable than positional `?` for queries with 5+ parameters.
- **Full control** over type mapping, JSONB handling, array columns. No fighting the ORM.
- **Lighter** — no Hibernate dependency, no second-level cache, no entity manager.
- **Spring-native** — `NamedParameterJdbcTemplate` is part of Spring JDBC, auto-configured by Spring Boot.

**Where repositories live:** in the module that owns the entity, never in `web` and never in a shared persistence module — see the §3.1 persistence-ownership rule for the full mapping and the Flyway/Redis boundaries. Each owning module takes `spring-boot-starter-jdbc`; the `DataSource` bean itself is app-level.

Pattern per module (`pipeline-contract`):
```kotlin
@Repository
class PipelineRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun findById(id: UUID): Pipeline? =
        jdbc.query(
            "SELECT * FROM pipelines WHERE id = :id AND is_deleted = FALSE",
            mapOf("id" to id),
            PipelineRowMapper
        ).singleOrNull()

    fun create(pipeline: Pipeline): Pipeline =
        jdbc.queryForObject(
            """INSERT INTO pipelines (id, name, display_name, description, owner_id)
               VALUES (:id, :name, :displayName, :description, :ownerId)
               RETURNING *""",
            mapOf(
                "id" to pipeline.id,
                "name" to pipeline.name,
                "displayName" to pipeline.displayName,
                "description" to pipeline.description,
                "ownerId" to pipeline.ownerId
            ),
            PipelineRowMapper
        )!!
}
```

See [Metadata DB spec §6](metadata-db.md#6-data-access-pattern-namedparameterjdbctemplate) for the full pattern.

### 8.2 Module auto-configuration

Modules that provide Spring beans expose them via a `@AutoConfiguration` class in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. This lets `app` pull in module configuration without explicit `@Import` per module.

Example (`templates` module):

```kotlin
@AutoConfiguration
@ConditionalOnClass(TemplateEngine::class)
@EnableConfigurationProperties(TemplatesProperties::class)
class TemplatesAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun templateEngine(registry: TemplateRegistry, props: TemplatesProperties): TemplateEngine =
        TemplateEngine(registry, props)
}
```

### 8.3 Configuration properties per module

Each module exposes typed configuration:

```kotlin
@ConfigurationProperties(prefix = "datapipelines.templates")
data class TemplatesProperties(
    val cacheSize: Int = 1000,
    val renderTimeoutMs: Long = 10_000
)
```

Key names, defaults, and env-var bindings are defined **once** in [configuration.md](configuration.md) (D8); the snippets here are shape illustrations, not a second definition. Composed into `application.yml` in `app`:

```yaml
datapipelines:
  templates:
    cache-size: 2000
    render-timeout-ms: 5000
  staging:
    h2:
      max-memory-mb: 2048
  executor:
    max-parallel-nodes: 8
  auth:
    jwt:
      ttl-hours: 12
```

### 8.4 Beans and DI

- **Constructor injection only.** No `@Autowired` on fields (per the user's global rules).
- **`@Service` / `@Repository` / `@Configuration`** used per Spring conventions.
- **Open classes**: Spring's `kotlin-spring` plugin opens classes that need CGLIB proxying. No manual `open` keyword required.

---

## 9. Test Categories

### 9.1 Unit tests

- Live in `src/test/kotlin` of each module.
- Run on every `./gradlew build`.
- Fast (target: full unit test suite < 60 seconds).
- No Spring context (use plain Kotlin test setup, manual DI).
- Mock external dependencies.

### 9.2 Integration tests

- Live in `tests/integration-tests/src/test/kotlin` (cross-module), or in the owning module's `src/test/kotlin` named `*IntegrationTest` (module-local, e.g. against a Testcontainer) — see §7.4; no per-module `src/integrationTest` source set exists in v1.
- Cross-module suite runs on `./gradlew integrationTest` (delegates to `:tests:integration-tests:test`); module-local ones run with the module's normal `test` task.
- Slower; use real databases (Testcontainers), real H2, real HTTP layer.
- Cover: end-to-end pipeline execution, MCP tool calls, REST endpoints, SSE streams.

### 9.3 Smoke tests

- Live in `app` module.
- Run on every `./gradlew build` (full Spring context loads, smoke-tests critical endpoints).
- Run before deployment.

### 9.4 Performance / load tests

- Live in `tests/performance/` (separate directory).
- Run on demand, not in CI fast path.
- Use JMeter or k6 scripts.

---

## 10. Documentation Conventions

### 10.1 Specs

- Live in `docs/` (this directory).
- One spec per file.
- Versioned (status: v1 frozen, additive-only).
- Cross-referenced via Markdown links.

### 10.2 KDoc

- Every public class, function, and property has KDoc.
- KDoc explains **what** the API does and **why**, not **how** (the code is the how).
- Examples for non-trivial APIs.

### 10.3 README per module

Each `modules/{name}/README.md` is one page:
- What the module does.
- Its public API.
- Its dependencies.
- How to test it locally.

---

## 11. Stability Promise

### 11.1 Frozen in v1

- The module list (the 10 modules + integration tests).
- The dependency direction — specifically the **allowed-dependency table in §4.2**, which is the normative form. Adding an edge is a spec change, not a build-file change.
- Persistence ownership (§3.1): repositories in their owning module, Flyway only in `app`, Redis only in `dag` and `web`.
- The version catalog as the single source of dependency versions.
- Kotlin + JDK 21 as the language/runtime baseline.
- Spring Boot 3.x as the application framework.

### 11.2 Not frozen

- Specific third-party versions (updated deliberately per §6.1).
- Internal class names (only public API is the contract).
- Test framework choice (could evolve — Kotest + MockK is v1 default).
- Build commands (could evolve with Gradle versions).

---

## 12. Open Questions / Future Additions

Out of scope for v1:

- **Module extraction**: publishing `typesystem` to Maven Central for clients to consume (typed client SDKs).
- **Gradle version catalogs merging**: when the project grows, split catalogs per concern (database drivers, web libs, etc.).
- **Build performance**: Gradle configuration-cache, build-cache sharing across CI runs.
- **Multi-platform (KMP)**: extract `typesystem` to a KMP module so the same types can be consumed by JS / .NET (via tooling) for client SDKs. Probably never needed.
- **Polyglot modules**: if we add Python SDK or CLI, separate `python/` directory at root, not in `modules/`.

---

## 13. Verification Checklist

### 13.1 Implementation gates (must be closed before the first build lands)

These are the two items this spec deliberately does **not** resolve on paper. Each has an exact check; neither may be closed by recall.

- [x] **G1 — MCP SDK coordinates** (§5.8): **resolved 2026-08-07** — `io.modelcontextprotocol.sdk:mcp-core:2.0.0` + `mcp-json-jackson2:2.0.0`, Streamable HTTP transports confirmed by jar inspection; see the resolved gate note in §5.8.
- [x] **G2 — version catalog vs Maven Central** (§6): **resolved 2026-08-07** — all entries verified, BOM-managed artifacts version-less, `gradle/libs.versions.toml` is the ratified source. Lockfile sub-item **closed 2026-08-15** (§7.6): committed `gradle.lockfile` per module + buildSrc, STRICT mode.

### 13.2 Build checklist

Before considering the module structure "ready":

- [ ] `./gradlew build` succeeds from clean state.
- [ ] The §4.2 allowed-dependency verification task passes (no module declares an edge outside its row).
- [ ] `./gradlew -Poracle bootJar` produces a JAR containing `ojdbc11`; the default `bootJar` does not.
- [ ] A driver dropped into `lib/` is picked up at runtime with `LOADER_PATH=lib` (§5.4.1).
- [ ] Flyway migrations live only in `app`; no other module declares a Flyway dependency.
- [ ] Only `dag` and `web` declare a Redis dependency.
- [ ] All unit tests pass.
- [ ] All integration tests pass.
- [ ] ktlint and detekt clean.
- [ ] `./gradlew bootJar` produces an executable JAR.
- [ ] `java -jar app/build/libs/datapipelines-app-*.jar` starts the app and serves `/health`.
- [ ] Every module has a README.
- [ ] Every module's public API has KDoc.
- [ ] Version catalog has exact versions for every dependency (no `+`, no SNAPSHOT).
- [x] Gradle lockfile committed (§7.6 — per-module `gradle.lockfile`, STRICT mode, 2026-08-15).
- [ ] No internal module depends on `app` (one-way dependency).
- [ ] `tests/integration-tests` runs end-to-end pipeline against a real PG container.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial module structure spec: 10 modules + integration tests, dependency graph, version catalog, build conventions, Spring Boot conventions |
| 2026-08-05 | v1.1 | design system integration | Added `@acme/design-tokens` as the styling foundation for the `web` module. Documented vendoring approach (CSS files, not npm). Referenced Pipeline Editor spec for integration details. |
| 2026-08-07 | v1.2 | consistency campaign | **Persistence ownership** (§3.1): repositories live in their owning domain module (`PipelineRepository`, `TemplateRepository`, `DatasourceRepository`, `UserRepository`/`ApiKeyRepository`, `ExecutionRepository`/`ExecutionEventRepository`), each taking `spring-boot-starter-jdbc`; Flyway dep + migrations confined to `app`; Redis (`spring-boot-starter-data-redis`, Lettuce) confined to `dag` and `web`; catalog gains flyway-core + flyway-database-postgresql. **§4.1** graph regenerated to match the §5.x lists; **§4.2** ambiguous layering rules replaced by one machine-checkable allowed-dependency table (+ Gradle verification task). **§5.1** `H2TypeMapper` → `H2IngressMapper` / `H2EgressMapper` (staging §5.3). **§5.2** `TerminalDetector` → `CallerNodeResolver` [D1], `PipelineRepository` added, Jackson named as the ser/deser stack. **§5.3** params-schema types dropped [D3]. **§5.4.1** new: `-Poracle`/`-Pmysql` conditional `runtimeOnly` sketch + `lib/` drop-in via `PropertiesLauncher`/`loader.path`. **§5.6** dag API gains `NodeResult`, `CancellationRegistry`, `CancellationHandle`, `ResultStore`, `ExecutionSlots`, `ExecutionAbortedException`, `AbortReason`, `ExecutorDispatcher`. **§5.11** `db2` Testcontainer removed (not a supported dialect); Redis container added. Both "Verification needed" markers converted to implementation gates G1/G2 with exact commands (§13.1). Duplicate `### 8.2` renumbered (→ 8.3/8.4). See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) |
| 2026-08-15 | v1.3 | dependency locking | **§7.6** new: STRICT `lockAllConfigurations()` dependency locking applied by `CommonConventionsPlugin` to every module and declared in `buildSrc/build.gradle.kts`; committed `gradle.lockfile` per module + buildSrc + settings; `resolveAndLockAll --write-locks` is the documented regeneration path (DEVELOPMENT.md §6.2). §5.4.1's flag-gated drivers (`ojdbc11`, `mysql-connector-j`) are the only lock-validation exclusions, declared in `modules/datasources/build.gradle.kts`. Closes the §13.1 G2 sub-item (lockfile was deferred to P9) and the §13.2 "Gradle lockfile committed" checklist row. Zero dependency version changes — the locks record current resolution. |
