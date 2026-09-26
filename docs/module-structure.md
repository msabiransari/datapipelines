# Module Structure Specification

**Status:** v1.5 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** (all other specs — this spec operationalizes them into code structure)
**Last updated:** 2026-09-01

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
│   ├── calculators/                     # [Calculators catalog] — pure kinds, layer 0 (§5.14)
│   ├── scripting/                       # Transform script engine seam, layer 0 (§5.15)
│   ├── pipeline-contract/               # [Pipeline Contract spec]
│   ├── templates/                       # [Templates spec]
│   ├── datasources/                     # [Datasources spec]
│   ├── staging/                         # [Staging spec]
│   ├── dag/                             # [DAG Executor spec]
│   ├── auth/                            # [Auth spec]
│   ├── scheduler/                       # [Scheduler reference] — durable occurrences, #9 (§5.16)
│   ├── application/                     # cross-aggregate use cases (§5.13)
│   ├── mcp-server/                      # [MCP Server spec]
│   ├── web/                             # REST API, SSE, Thymeleaf UI
│   └── app/                             # Spring Boot entry point, assembles everything
├── tests/
│   ├── integration-tests/           # cross-module integration tests
│   └── browser-tests/               # Playwright browser suite (separate invocation: `./gradlew browserTest`)
└── docs/                                # these specs
```

### 3.1 Module responsibility matrix

| Module | Spec | Responsibility | Owns persistence for |
|---|---|---|---|
| `typesystem` | [type-system.md](type-system.md) | The 11 canonical types, per-dialect mappers, H2 mapping, schema envelope. Foundation. | — (no persistence) |
| `scripting` | (this spec, §5.15) | The transform engine seam: `ScriptEngine` (JSONata in round one), the evaluation pool, the type gate, canonical JSON. Pure library — evaluates untrusted script bodies with no I/O surface. | — (no persistence) |
| `pipeline-contract` | [pipeline-contract.md](pipeline-contract.md) | Pipeline JSON model, validation, ExecutionContext type. | `PipelineRepository` → `pipelines`, `pipeline_versions` |
| `templates` | [templates.md](templates.md) | Freemarker integration, library macros, template registry, versioning. | `TemplateRepository` → `templates`, `template_versions` |
| `datasources` | [datasources.md](datasources.md) | Connection registry, HikariCP pools, dialect adapters, credential encryption. | `DatasourceRepository` → `datasources` |
| `staging` | [staging.md](staging.md) | H2 lifecycle, staging interface, type-aware batch inserts. | — (tempdb is per-execution, never persisted) |
| `dag` | [dag-executor.md](dag-executor.md) | DAG data structure, executor (coroutines), node runner, SSE event emitter interface, Redis-backed result store. | `ExecutionRepository` → `pipeline_executions`; `ExecutionEventRepository` → `execution_events`; Redis keys for results / idempotency / cancel flags |
| `auth` | [auth.md](auth.md) | Users, workspaces + membership (resolution, provisioning, CRUD/member rules — 019's recorded placement: the workspace is an identity concept, membership-checked on every authenticated request exactly like `users`), API keys, JWT sessions, scopes, audit log. | `UserRepository` → `users`; `WorkspaceRepository` → `workspaces`, `workspace_members` (metadata-db §4.11/§4.12); `ApiKeyRepository` → `api_keys`; `AuditLogger` → `audit_log` |
| `scheduler` | [scheduler.md](scheduler.md) | The pipeline-agnostic scheduler (#9): schedules, their occurrence function, the one dispatcher over db-scheduler, runs and their append-only trail, the generic executor port, capacity admission and reconciliation. Knows no pipeline semantics — the executor adapter lives in `web` (§5.16). | `ScheduleRepository` → `schedules`; `ScheduleRunRepository` → `schedule_runs`, `schedule_run_events`; db-scheduler owns `scheduled_tasks` (created by `app`'s V38) |
| `application` | (this spec, §5.13) | **Cross-aggregate use cases** — the ones that need more than one domain module and so belong to none of them. Sits below `web` and `mcp-server` so both surfaces share one implementation (ARCH-AUDIT-2026-08 S4, ruling R6). | — (delegates to the owning modules' repositories) |
| `mcp-server` | [mcp-server.md](mcp-server.md) | MCP transport (Streamable HTTP), tool/resource/prompt definitions. Thin adapter over the same services the REST layer uses. | — (delegates to the owning modules' repositories) |
| `web` | [rest-api.md](rest-api.md) | Spring Boot REST controllers, SSE endpoints, Thymeleaf UI, error handling, CORS. | — (delegates); Redis keys for the post-completion SSE event log and per-user rate-limit counters |
| `app` | (this spec) | Spring Boot `main()`, assembles all modules, configuration, runnable JAR, **Flyway dependency + migration scripts**. | Owns schema *creation* (Flyway), not data access |
| `tests/integration-tests` | (this spec) | Cross-module integration tests (Testcontainers for real databases). | — |
| `tests/browser-tests` | (this spec §5.12) | Mechanical browser E2E of the UI golden paths (Playwright for Java, chromium-only). Separately invoked via `./gradlew browserTest` — NOT part of `build`/`check`. | — |

#### Persistence ownership rule (normative)

1. **Repositories live in their owning domain module**, never in a shared `persistence` module and never in `web`. Each owning module takes `org.springframework:spring-jdbc` (via `spring-boot-starter-jdbc`) and uses `NamedParameterJdbcTemplate` per §8.1.
2. **The schema is owned by one module: `app`.** The Flyway dependency (`flyway-core` + `flyway-database-postgresql`) and every migration script under `src/main/resources/db/migration/` live in `app` only. Domain modules read and write tables; they never create or alter them. DDL authority is [Metadata DB §4](metadata-db.md#4-table-definitions); the migration file layout is [Metadata DB §7.1](metadata-db.md#71-file-structure).
3. **Redis is a dependency of exactly two modules**: `dag` (result store, idempotency keys, cancellation flags) and `web` (post-completion SSE event log, per-user rate-limit counters, and — since 050 — the datasource pool-invalidation pub/sub channel, the one Redis *message* beside all the Redis *state*). Both take `spring-boot-starter-data-redis` (Lettuce client, the starter's default). No other module talks to Redis.
4. **The `DataSource` bean for the metadata DB is a single app-level bean** (Spring Boot autoconfiguration from the `spring.datasource.*` keys defined in [configuration.md §3](configuration.md)). Modules inject `NamedParameterJdbcTemplate`, never construct their own pool. This is distinct from the *user* datasource pools owned by `ConnectionPoolManager` in the `datasources` module.

---

## 4. Dependency Direction

### 4.1 Layered dependency graph

This diagram is a **rendering of the normative table in §4.2** — it carries no information the table does not. When the two disagree, §4.2 wins and the diagram is the bug. `verifyModuleDependencies` checks that every module the table names appears below; edge fidelity (the `←` lists) is a human read against the table, because ASCII arrows cannot be parsed honestly.

```
layer 0 — no internal deps
┌──────────────┐
│  typesystem  │
└──────────────┘

layer 1 — typesystem only
┌──────────────┐ ┌─────────────┐ ┌──────────────┐ ┌───────────┐ ┌────────┐
│ calculators  │ │  scripting  │ │ datasources  │ │  staging  │ │  auth  │  ← typesystem
└──────────────┘ └─────────────┘ └──────────────┘ └───────────┘ └────────┘

layer 2
┌───────────────────┐
│ pipeline-contract │  ← typesystem, calculators
└───────────────────┘

layer 3
┌──────────────┐ ┌─────────────┐
│  templates   │ │  scheduler  │  templates ← typesystem, pipeline-contract, scripting
└──────────────┘ └─────────────┘  scheduler ← typesystem, pipeline-contract (the name grammar only)

layer 4
┌──────────────┐
│     dag      │  ← typesystem, calculators, pipeline-contract, templates,
│  (executor)  │    datasources, staging, scripting
└──────────────┘

layer 5
┌──────────────┐
│ application  │  ← typesystem, scripting, pipeline-contract, templates,
│ (use cases)  │    datasources, dag, auth
└──────────────┘

layer 6
┌──────────────┐
│  mcp-server  │  ← typesystem, calculators, pipeline-contract, templates,
│              │    datasources, dag, auth, application
└──────────────┘

layer 7
┌──────────────┐
│     web      │  ← typesystem, calculators, scripting, pipeline-contract, templates,
│              │    datasources, staging, dag, auth, application, mcp-server, scheduler
│              │    (declared explicitly, not transitively)
└──────────────┘

layer 8
┌──────────────┐
│     app      │  ← web only
└──────────────┘

layer 9 — the test suites
┌─────────────────────────┐ ┌─────────────────────┐
│ tests/integration-tests │ │ tests/browser-tests │  ← app
└─────────────────────────┘ └─────────────────────┘
```

### 4.2 The dependency rule (machine-checkable)

There is **one** layering rule, and it is a table lookup, not a judgment call:

> **A module's `dependencies { implementation(project(...)) }` block MUST list a subset of its row below, and every module it uses at compile time MUST be listed explicitly (no reliance on transitive `api` leakage).**

| Module | Allowed internal dependencies (exhaustive) |
|---|---|
| `typesystem` | *(none)* |
| `calculators` | `typesystem` |
| `scripting` | `typesystem` |
| `pipeline-contract` | `typesystem`, `calculators` |
| `templates` | `typesystem`, `pipeline-contract`, `scripting` |
| `datasources` | `typesystem` |
| `staging` | `typesystem` |
| `auth` | `typesystem` |
| `scheduler` | `typesystem`, `pipeline-contract` |
| `dag` | `typesystem`, `calculators`, `pipeline-contract`, `templates`, `datasources`, `staging`, `scripting` |
| `application` | `typesystem`, `scripting`, `pipeline-contract`, `templates`, `datasources`, `dag`, `auth` |
| `mcp-server` | `typesystem`, `calculators`, `pipeline-contract`, `templates`, `datasources`, `dag`, `auth`, `application` |
| `web` | `typesystem`, `calculators`, `scripting`, `pipeline-contract`, `templates`, `datasources`, `staging`, `dag`, `auth`, `application`, `mcp-server`, `scheduler` |
| `app` | `web` |
| `tests/integration-tests` | `app` |
| `tests/browser-tests` | `app` |

Notes on the shape (explanatory, not additional rules):

- The table is acyclic by construction, so "no cycles" needs no separate rule — Gradle enforces it anyway.
- `calculators`' row is the shortest one in the table on purpose (072, calculators design §0.4/C12). A calculator kind is a **pure function of its inputs**; a row that admitted `datasources` or `dag` would make that a hope rather than a fact, and the executor's freedom to evaluate a kind anywhere, in any order, rests on it. Adding an entry to that row is the edit a reviewer must refuse.
- `scheduler` lists `pipeline-contract` for exactly ONE thing, the published `PipelineNameGrammar` (a schedule is named like a pipeline — scheduler design revision §6, A2); its `SchedulerBoundaryTest` fails on any other `co.datapipelines.pipeline` import, so the edge cannot quietly become pipeline knowledge. It lists no `dag`, `auth` or `application`: the executor adapter, the capacity lease and the system principal live on `web`'s side of its port.
- `dag` does **not** list `auth`: the executor is handed an already-authenticated principal by its caller. `mcp-server` **does** list `auth` (it authenticates its own transport, [MCP Server §3.2](mcp-server.md)) and `dag` (the `pipelines_execute` / `executions_*` tools drive the executor directly rather than looping back through HTTP).
- `web` lists everything it touches **explicitly**. It could reach most of these transitively through `mcp-server`; declaring them is what makes the table checkable.
- `application` is where a use case goes when it needs MORE THAN ONE aggregate. The rule, in one sentence: **cross-aggregate use cases live in `application`; single-aggregate ones live with the aggregate that owns them.** `PipelineService` is therefore in `pipeline-contract`, and `ExecutionLauncher` — which needs the pipeline aggregate AND `dag`'s reservation store — is in `application`. Nothing in `application` may import a `web` or `mcp` type; `ArchitectureGuardTest` fails the build on one.
- `app` lists `web` only. It contains `main()`, configuration (including the single `metadataTransactionManager`, §8.5), logback config, and the Flyway migrations (§3.1) — no domain code.
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

**Dependencies (internal):** `typesystem`, `pipeline-contract` (for `Parameter` shape), `scripting` (transform bodies parse and evaluate through its seam, never through Freemarker — transform-nodes design §2.1, D-T9).

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
- `org.jetbrains.kotlinx:kotlinx-coroutines-core` — pool admission is a coroutine `Semaphore` and every staging operation is `suspend` ([Staging §9.2](staging.md#92-one-owner-per-connection-and-session-state-is-per-lease)).

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
- `StaleExecutionSweeper` — the crash sweep's idempotent `UPDATE` (safe for every replica to run; no leader election by design). Its scheduling lives in `web` (§5.9): `web`'s `SweepSchedulingConfiguration` is the project's one `@EnableScheduling`/`@Scheduled` surface, introduced 2026-09-01 (036). Before that, "no `@Scheduled`/Quartz/cron anywhere" was a verified property of the codebase — any NEW scheduled job is a lifecycle-surface change and belongs on this record.
- `ExecutionEventRetention` — the `execution_events` retention job (050/T60): one idempotent `DELETE` past the retention window, safe for every replica, never touching `pipeline_executions`. Scheduled by `web`'s `RetentionSchedulingConfiguration` (`@Scheduled`, fixed-delay 1h, riding the sweep's single-thread scheduler — still exactly ONE `@EnableScheduling`).

**Tests:** unit tests for `Dag<T>` algorithms; unit tests for executor (mocked dependencies); cancellation tests covering all three `AbortReason` paths incl. the cross-instance Redis flag; integration tests with real H2 + Testcontainers sources + a Redis container.

### 5.7 `auth`

**Dependencies (internal):** `typesystem` (shared exception base only).

**Dependencies (external):**
- `de.mkammerer:argon2-jvm`
- `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, `jjwt-jackson`
- `org.springframework.boot:spring-boot-starter-oauth2-client` (Spring Security web/config + OAuth2 client + Jose, per [Auth §12.2](auth.md#122-dependencies) — which correctly lists no BouncyCastle; see the §5.4 removal note)
- `org.springframework.boot:spring-boot-starter-jdbc` — for the user / key / audit repositories

**Public API:**
- `User`, `ApiKey`, `AuthenticatedPrincipal`, `Workspace`, `WorkspaceMembership`, `WorkspaceMemberRow`, `WorkspaceContext` data classes; `WorkspaceRole`, `WorkspaceProvisioningMode` enums; `WorkspacesProperties` (configuration §3.17)
- `Scope` enum
- `JwtService`, `ApiKeyService`, `UserService`, `WorkspaceService`
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
- `org.springframework.boot:spring-boot-starter-data-redis` — post-completion SSE event log (1h), per-user rate-limit counters, and the `dp:datasource-invalidated` pub/sub channel (050 §5.7). The durable 7-day event record is `dag`'s `ExecutionEventRepository`; these are two different stores for two different retention windows (D9).
- `io.micrometer:micrometer-core`
- `com.fasterxml.jackson.module:jackson-module-kotlin`

**Vendored front-end libraries:** htmx 2.0.10, Alpine 3.14.1, Cytoscape 3.34.0 (+ `dagre`
and `node-html-label`), dagre 0.8.5 — all static files under `static/vendor/`, none of them
a build dependency. htmx was `org.webjars.npm:htmx.org` until 096 §B; a webjar puts a
`/webjars/` glob on the public allowlist over a namespace the dependency graph fills, so it
was vendored like the rest (`VendoredHtmxAuditTest` pins the bytes and the licence).

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
- `org.testcontainers:postgresql`, `mysql`, `mssql`, `oracle-xe` — one container module per **supported dialect** that needs a real server. DuckDB, SQLite, H2 and LAKE are embedded (no container; LAKE's suite uses a MinIO object-storage container instead). There is **no DB2 container — DB2 is not a supported dialect** ([Type System §5](type-system.md#5-source-to-canonical-mapping-tables) / [Datasources §4.1](datasources.md#41-dialect-catalog) list the eight).
- A Redis container (`org.testcontainers:testcontainers` generic container, or the Redis module) — required for result delivery, idempotency, and cancellation tests (D9/D7).
- `io.rest-assured:rest-assured` or `spring-boot-starter-webflux` (for reactive test client)

**Purpose:** end-to-end tests that spin up real database containers, register datasources, create templates, build pipelines, execute them, verify results. The "cold executable" check — if the integration tests pass, the app works.

### 5.12 `tests/browser-tests`

**Dependencies (internal):** `:modules:app` (full app context, same allowance as §5.11).

**Dependencies (external):**
- `com.microsoft.playwright:playwright` — pinned in the catalog; the pin covers the
  driver AND the bundled browser build (a Playwright release pins its browser set).
- `org.springframework.boot:spring-boot-starter-test`, Testcontainers
  (PostgreSQL + Redis) — same as §5.11.
- `de.mkammerer:argon2-jvm` — same declared-exception rationale as §5.11's seeding
  note: the seeded admin's stored hash must be a real Argon2id of the known password.

**Purpose:** the mechanical, LLM-free browser suite of the release-critical UI golden
paths (TEST-GAP-2026-09.md): login → forced password change → dashboard, datasource
CRUD, template/pipeline editors, execute+SSE, history, API keys, workspace switching.
Codifies the ad-hoc `.playwright-mcp` dev loop as a re-runnable gate.

**Design rules (ratified 2026-09-03):**
- **Self-contained boot:** `@SpringBootTest(RANDOM_PORT)` + Testcontainers Postgres +
  Redis + local auth with a seeded admin — one command, zero manual setup, no
  dependency on an operator's running stack.
- **Separate invocation:** the root `browserTest` lifecycle task →
  `:tests:browser-tests:test`. Deliberately NOT wired into `build`/`check`/`gate.sh` —
  the browser binary download (~150 MB, first run only) and the chromium launch make
  it too heavy for every-build; it is invoked deliberately before a release.
- **Fails loud, never skips:** a deliberate invocation without browser binaries FAILS
  with the install instructions — a silent skip is not a verdict. (The banner-skip
  precedent of `editorJsTest` is for INVOLUNTARY toolchain absence; it does not apply.)
- **Chromium only, headless, auto-wait:** no timing sleeps; selectors by stable
  `id` first (the templates carry them), role/text otherwise. Traces + screenshots
  saved under `tests/browser-tests/build/reports/` on failure.
- **Out of scope for v1:** multi-browser matrix, visual regression pixel-diffing,
  accessibility scans.

### 5.13 `application`

**Added by round 056 (ARCH-AUDIT-2026-08 S1–S5, ruling R6).** The number is append-order, not
layer-order: `web` is §5.9 and `app` is §5.10, and renumbering them would break every inbound
anchor in the spec set and the code. Layer position is §4.1's job; this heading is an address.

**Dependencies (internal):** `typesystem`, `pipeline-contract`, `templates`, `datasources`, `dag`,
`auth`. Declared in the build file only as they are actually used at compile time (§4.2) — slice A
uses `typesystem`, `pipeline-contract`, `dag` and `auth`.

**Dependencies (external):** `jackson-module-kotlin`, `slf4j-api`.

**Purpose:** the use cases that need MORE THAN ONE aggregate, so they belong to no single domain
module — and, before this module existed, ended up in `web` because nowhere else could hold them.
That was S4's finding: `mcp-server` cannot depend on `web` (the arrow runs the other way), so any
use case parked in `web` is invisible to the MCP surface and gets reimplemented there. Eight of
them had been (S2's D1–D8), and one of the copies had a real behavioural difference.

**The placement rule, in one sentence:**

> **Cross-aggregate use cases live in `application`; single-aggregate ones live with the aggregate
> that owns them.**

So `PipelineService` lives in `pipeline-contract` (it needs only the pipeline aggregate) and
`ExecutionLauncher` lives here (it needs the pipeline aggregate AND `dag`'s reservation store).

**Public API (slice A):**
- `ExecutionLauncher` — parameter binding + the idempotency reservation, shared by
  `POST /pipelines/{id}/execute` and the `pipelines_execute` MCP tool. Sharing it is what gave MCP
  execute idempotency support, which it had never had (S2's D6).
- `ExecutionLaunch`, `LaunchDecision`, `IdempotencyMetrics` — its request, its answer, and the
  counter port `web` adapts onto `WebMetrics`.

**Constraints:**
- **No web or MCP types, ever.** No `HttpStatus`, no `ResponseEntity`, no `ApiResponse`, no MCP
  wire type. The mapping to a status code stays in `ApiErrorCatalog`; the mapping to an MCP error
  stays in the tool. `ArchitectureGuardTest` fails the build on an offending import, so the rule
  cannot decay into a convention.
- **`DatapipelinesException` with catalogued codes is the error contract**, as everywhere else.
- **Every workspace-scoped operation takes `workspaceId` explicitly** — `TemplateRepository`'s
  "no default anywhere: a missed caller is a compile error" rule applies to services too.
- It ships **no Spring configuration of its own**: `web` is the aggregation layer (§5.9) and
  declares the beans, exactly as it already does for `pipeline-contract`'s repository.

**Tests:** unit tests over the use cases with the domain modules' own fixtures. The surfaces' own
tests shrink to "the controller/tool calls it and maps the result".

**Coming in slices B and C:** the import services, 055's promotion orchestrator, and the
`ExecutionService`/`TemplateService`/`DatasourceService` split of the remaining D3–D8 rows.

---

---


### 5.14 `calculators`

**Dependencies (internal):** `typesystem` — and nothing else, ever (see the §4.2 note).

**Dependencies (external):** none.

**Public API:**
- `CalculatorKind` interface — one pure `inputs → output` function, with its declared input types, output type and worked example
- `CalculatorInput` / `CalculatorExample` — the declaration shape the catalog doc, the validator and `calculators_list` all read
- `CalculatorRegistry` — every shipped kind, by `kind`; additive forever (D4)
- `CalculatorEvaluationException` — a refusal naming the offending input; the executor maps it to `pipeline.node.calculator_failed`

**Why it is its own module.** The kinds are the one part of the system that must be provably free of I/O: the executor evaluates one at an arbitrary DAG position, the validator type-checks its inputs at save time, and a future editor preview would evaluate one with no execution at all. Purity as a build rule (this row) plus a source rule (`CalculatorPurityTest`) is what makes those three uses safe. It deliberately does **not** depend on `pipeline-contract`: JSON literals, `$` references, `context_key` collisions and error codes are contract concerns, and a catalog that knew about them would be a catalog nobody could reuse.

**Tests:** unit tests per kind (boundary values; the fiscal kinds against both a calendar and a non-calendar fiscal start; `add_business_days` across a weekend and a holiday; `tz_shift` across a DST boundary; `date_parse` with a bad pattern), a registry-invariant test, `CalculatorPurityTest`, and `CalculatorRegistrySpecDriftTest` against `docs/calculators.md`.

### 5.15 `scripting`

**Dependencies (internal):** `typesystem` — and nothing else, ever (§4.2 row; the root
build's `allowedInternalDependencies` map carries the same closed set).

**Dependencies (external):**
- `com.dashjoin:jsonata` (pinned, 0.9.10 at introduction) — the round-one transform engine; Apache-2.0. Bus-factor note for the dependency review: a small upstream project (99 stars, 17 open issues, last push 2026-07-08 at dispatch) — recorded on purpose; not a blocker while the seam keeps it replaceable.
- `com.fasterxml.jackson.core:jackson-databind` (BOM-managed) — the one `ObjectMapper` behind `CanonicalJson`, private to the module.
- `org.slf4j:slf4j-api` (BOM-managed) — the evaluation pool's abandonment log; the registry lives in `app`.

**Public API:**
- `ScriptEngine` — compile once, evaluate many; JSON-shaped values in and out; `JsonataEngine` is the round-one implementation
- `ScriptLanguage`, `EvaluationLimits`, `EngineCapabilities` — the seam's vocabulary; capabilities state which limits an engine can actually enforce, measured by the breach suite, never intended
- `CompiledScript` — opaque engine-produced handle
- `ScriptEvaluationPool` — the bounded pool every production evaluate call runs on; `ScriptClock` is its injected time source
- `TypeGate` (+ `GateRefusal`/`GateResult`) — the §5.3/R1 output contract of the transform design; DECIMAL rounds half-even, BIGDECIMAL is exact
- `CanonicalJson` — the §5.5 canonical form: keys sorted, shortest doubles, plain decimals
- `ScriptingException` and its five typed refusals (`ScriptSyntaxException`, `ScriptEvaluationException`, `ScriptTimeoutException`, `ScriptResourceLimitException`, `ScriptPoolExhaustedException`) — the codes they carry are the transform design's §7 mapping

**Why it is its own module.** The engine evaluates UNTRUSTED script bodies as pure functions of their JSON input (transform design D-T4). That is safe because the module cannot reach anything else: one internal edge, an I/O-refusing source guard (`ScriptingPurityTest`), and resource bounds that are measured, not believed (`JsonataBreachTest` writes `build/reports/jsonata-breach.md`, and dag-executor.md's honest-bounds table is pasted from it). It deliberately does **not** depend on `pipeline-contract`: modes, contracts, error codes and the Context are callers' concerns (7b/7c), and an engine that knew them would be an engine nobody could sandbox.

**Tests:** the conformance suite parameterised over every engine (`ScriptEngineConformanceTest`), the breach suite with its three-way outcome record (`JsonataBreachTest`), the 32-thread determinism proof, the pool contract, the §5.3 type-gate table (42 cases), `CanonicalJsonTest`, and `ScriptingPurityTest`.

### 5.16 `scheduler`

**Dependencies (internal):** `typesystem`, `pipeline-contract` (the name grammar only — §4.2 note).

**Dependencies (external):**
- `com.github.kagkarlsson:db-scheduler-spring-boot-starter` (pinned, 16.12.0 at introduction; brings `db-scheduler` core and `db-scheduler-spring-common` at the same version) — the durable queue, cluster-wide picking and dead-task detection; Apache-2.0. Its Java-serialization default is replaced by the JSON serializer (scheduler design revision §2.2, A8), and its health indicator is disabled (the record §7.2).
- `spring-boot-starter-jdbc`, `io.micrometer:micrometer-core`, Jackson (all BOM-managed).

**Public API:**
- `ScheduleService` — the management use cases REST calls (create, edit, pause/resume/unblock, delete, Run now, reads, preview). The only scheduler type a transport may name.
- `JobExecutor` (+ `JobExecutors`) — the executor port: validate, prepare, start, inspect, lens; standardized outcomes, no error-code parsing
- `CapacityGate` / `CapacityLease` — the acquire-before-claim port (R4); `web` implements it over `dag`'s `ExecutionSlots`
- `OccurrenceFunction` — the ONE occurrence computation (the record §3.2) the dispatcher and the preview share
- `SchedulerProperties` — `datapipelines.scheduler.*` ([Configuration §3.29](configuration.md#329-scheduler-9))
- the jobs `ScheduleDispatcher`, `ScheduledRunWorker`, `RunReconciler` — declared as db-scheduler tasks by the module's own `SchedulerAutoConfiguration`; no transport may name them (`ArchitectureGuardTest`, B5)

**Why it is its own module.** The scheduler must stay pipeline-agnostic (the owner's boundary, record §5): timing, occurrence identity, admission and the run trail are generic, and a report executor is expected to plug into the same port. A module with no `dag`/`application`/`auth` edge makes that a build fact; the fake executor in its own suite proves it at runtime.

**Tests:** the occurrence function (DST gap/fold against the measured A9 case, month/year boundaries, the min-interval guard); the repositories and the dispatcher/worker/reconciler against a real Postgres with a controlled clock (`ManualScheduler`, `SettableClock`) — two instances, one occurrence, one launch; the crash windows; capacity retries; the two spike proofs; `SchedulerBoundaryTest`.

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
  production sources from the cross-module suite:
  - no field injection (`@Autowired` on properties/fields) — constructor
    injection only;
  - no DI stereotypes — no class, interface, or `object` carries `@Service`,
    `@Component`, or `@Repository` (zero allowlist; every bean is an explicit
    `@Bean` method, §8.4);
  - **every `@Transactional` names the metadata transaction manager** (056,
    §8.5) — a bare one fails the build. This replaced the pre-056 outright ban,
    which was correct while there was no transaction manager at all. The check
    is a source text scan rather than an annotation walk, so it can name file
    and line and cannot be satisfied by a constant that reads as bare on the
    page; it asserts non-vacuity first, because a scan that finds nothing would
    otherwise pass forever;
  - **nothing below the surfaces imports a web or MCP type** (056, §5.13):
    `application` and every domain module are refused
    `co.datapipelines.web.*` / `co.datapipelines.mcp.*` imports. Gradle's
    `verifyModuleDependencies` enforces the declared EDGES; this enforces the
    source-level rule, names the offending import, and would still fire on a
    type reached through some future transitive edge;
  - the production scope itself is non-empty, and the layering scan actually
    covers `modules/application` (a guard scanning nothing proves nothing).

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

Pattern per module (`pipeline-contract`; declared as a `@Bean` in the consuming
configuration — no `@Repository` stereotype, §8.4):
```kotlin
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
- **Zero DI stereotypes.** No production class carries `@Service`, `@Component`,
  or `@Repository` — every bean is declared explicitly as a `@Bean` method in the
  module's `@Configuration` class (auth: `AuthConfiguration`; engine/domain
  repositories: `EngineConfiguration` / `DomainConfiguration` where their
  consumers are already wired; templates: `TemplatesConfiguration`). Component
  scanning stays on, but the only annotated production classes are
  configurations (`@Configuration` / `@AutoConfiguration`),
  `@ConfigurationProperties`, and the web edge (`@Controller`, `@RestController`,
  `@ControllerAdvice`). Enforced by `ArchitectureGuardTest` (§7.8).
- **Transactions:** see §8.5 — `@Transactional` is allowed since 056 and must
  always NAME its manager.
- **Open classes**: Spring's `kotlin-spring` plugin opens `@Configuration`
  classes and `@Transactional` ones that need CGLIB proxying. No manual `open`
  keyword required — and §8.5 explains why that is not a detail.

### 8.5 Transactions (056, ARCH-AUDIT S3 / ruling R6)

**One transaction, one database.** There is exactly ONE Spring transaction
manager, `metadataTransactionManager` (declared in `app`'s
`TransactionConfiguration`, over the metadata `DataSource`), and N Hikari pools
for CUSTOMER databases which are not Spring transaction resources and must
never become one. No XA, no JTA, no two-phase commit. The full consistency
model, including what a partially-failed pipeline leaves behind, is
[dag-executor §16](dag-executor.md#16-consistency-model).

Four rules, each mechanical:

1. **The manager is always named:**
   `@Transactional("metadataTransactionManager")`, never bare. A bare one binds
   to whichever manager Spring finds — correct by accident with one manager, a
   trap with two, and silent about which database it means either way.
   `ArchitectureGuardTest` (§7.8) fails the build on a bare one.
2. **Transactions live on the service layer**, never on a repository or a
   controller. First-choice atomicity is still a single data-modifying CTE (the
   `PipelineRepository` stance, unchanged); the annotation is for a unit of
   work that genuinely spans statements. Two writes that are ALTERNATIVES
   selected by a caught constraint violation are not such a unit — see
   `PipelineService.discard` and `PipelineService.update`, both deliberately
   un-annotated with the reasoning on their KDoc.
3. **No customer-datasource I/O inside a metadata transaction.**
   `ConnectionLease` refuses to lease while a transaction is active on the
   thread, with the catalogued `datasource.lease_in_transaction`.
4. **Rollback is proven, not assumed.** `@Transactional` needs a CGLIB proxy; a
   Kotlin class is final unless `kotlin("plugin.spring")` opens it, and an
   advice that cannot be applied produces **no error and no transaction**.
   `TransactionRollbackE2eTest` asserts two writes plus a throw persist nothing,
   with a commit control beside it so the assertion cannot pass vacuously.

`PromotionReceiveService`'s explicit `TransactionTemplate` (055) remains valid:
it is the same manager, demarcated in code rather than by annotation.

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
- **One container per module per test JVM, not one per suite** (round 060): each module's suites share a `SharedPostgres`/`SharedRedis` singleton started and reset on first touch; `app` and the E2E module migrate through the first context's Flyway, the domain modules apply the shipped migrations once over JDBC (§3.1 rule 2 keeps the Flyway dependency in `app`). Sharing is safe by rule, and the rule is load-bearing: **each spec cleans the tables it touches** (`TRUNCATE ... CASCADE` + re-seed), seeds carry suite-unique identities, suites with global or exact-set assertions reset via the module's clean helper before seeding, and partial-migration suites take a scratch *database* on the shared container. Suites that legitimately need isolated deployments keep their own containers (the promotion E2E pair). Verified by shuffled-method-order runs (two seeds, `DEVELOPMENT.md` §9.3); adding a suite that relies on a fresh container instead of cleaning will fail those runs.

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
| 2026-09-25 | #9 scheduler lane 1 | scheduler-1 | New module `scheduler` (§5.16): the pipeline-agnostic scheduler core over db-scheduler 16.12.0 (the catalog's one new version, two library aliases). §3, §3.1, §4.1 and §4.2 gain the row (`scheduler` → `typesystem`, `pipeline-contract` for the name grammar alone); `web` gains `scheduler`. The root build's allowed-dependency map and `COVERAGE_FLOORS` carry the module; its `gradle.lockfile` and the db-scheduler verification entries ship in the same commit. The scheduler's beans come from the module's own `@AutoConfiguration` (§8.2 — the second module to use it, after `mcp-server`), so no db-scheduler type leaves the module. |
| 2026-09-23 | 7b (#7) | lane 7b — the template model | §4.2: `templates` and `application` gain `scripting` (transform bodies parse and evaluate through its seam — never through Freemarker), and `web` gains it for the EngineConfiguration pool bean (the fence's "templates and application, nothing else" was written before the bean's home was known; the diagram and the map moved with the table, and the #214 check proves they cannot drift). §5.3's dependency line and the §4.1 `←` lists updated in the same commits. |
| 2026-09-23 | #214 | lane 7b (#7) | **§4.1 redrawn from §4.2** — the old diagram predated `calculators` and `scripting` entirely, omitted the two test modules, and its `mcp-server` annotation dropped the `calculators`, `dag` and `auth` edges; the redraw renders every module and every edge as a per-layer `←` list. **The drift is now mechanical:** `verifyModuleDependencies` also parses the §4.2 table and fails when it and the root build's `allowedInternalDependencies` disagree in either direction, and when a table module is absent from the §4.1 diagram; a sibling `verifyVerificationMetadataDocs` check keeps hand-written comments out of `gradle/verification-metadata.xml` (their home is DEVELOPMENT.md §6.3's hand-verified table, which the same check cross-references). Both are wired into `check`. |
| 2026-09-23 | 7a (#7) | #7 lane 7a — the script engine | New layer-0 module `scripting` (§5.15): the `ScriptEngine` seam with the JSONata engine (`com.dashjoin:jsonata` 0.9.10 — the catalog's one new version + library), the evaluation pool, the type gate, and canonical JSON; the transform design's §4.1/§4.5/§5.3/§5.5 as a library with no product surface. §3, §3.1 and §4.2 gain the row (`scripting` → `typesystem`, the calculators shape); the root build's allowed-dependency map and `COVERAGE_FLOORS` (measured 91.4 − 2) carry the module. Its `gradle.lockfile` and the jsonata verification entries ship in the same commit. |
| 2026-08-05 | v1.0 | initial draft | Initial module structure spec: 10 modules + integration tests, dependency graph, version catalog, build conventions, Spring Boot conventions |
| 2026-08-05 | v1.1 | design system integration | Added `@acme/design-tokens` as the styling foundation for the `web` module. Documented vendoring approach (CSS files, not npm). Referenced Pipeline Editor spec for integration details. |
| 2026-08-07 | v1.2 | consistency campaign | **Persistence ownership** (§3.1): repositories live in their owning domain module (`PipelineRepository`, `TemplateRepository`, `DatasourceRepository`, `UserRepository`/`ApiKeyRepository`, `ExecutionRepository`/`ExecutionEventRepository`), each taking `spring-boot-starter-jdbc`; Flyway dep + migrations confined to `app`; Redis (`spring-boot-starter-data-redis`, Lettuce) confined to `dag` and `web`; catalog gains flyway-core + flyway-database-postgresql. **§4.1** graph regenerated to match the §5.x lists; **§4.2** ambiguous layering rules replaced by one machine-checkable allowed-dependency table (+ Gradle verification task). **§5.1** `H2TypeMapper` → `H2IngressMapper` / `H2EgressMapper` (staging §5.3). **§5.2** `TerminalDetector` → `CallerNodeResolver` [D1], `PipelineRepository` added, Jackson named as the ser/deser stack. **§5.3** params-schema types dropped [D3]. **§5.4.1** new: `-Poracle`/`-Pmysql` conditional `runtimeOnly` sketch + `lib/` drop-in via `PropertiesLauncher`/`loader.path`. **§5.6** dag API gains `NodeResult`, `CancellationRegistry`, `CancellationHandle`, `ResultStore`, `ExecutionSlots`, `ExecutionAbortedException`, `AbortReason`, `ExecutorDispatcher`. **§5.11** `db2` Testcontainer removed (not a supported dialect); Redis container added. Both "Verification needed" markers converted to implementation gates G1/G2 with exact commands (§13.1). Duplicate `### 8.2` renumbered (→ 8.3/8.4). See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) |
| 2026-08-15 | v1.3 | dependency locking | **§7.6** new: STRICT `lockAllConfigurations()` dependency locking applied by `CommonConventionsPlugin` to every module and declared in `buildSrc/build.gradle.kts`; committed `gradle.lockfile` per module + buildSrc + settings; `resolveAndLockAll --write-locks` is the documented regeneration path (DEVELOPMENT.md §6.2). §5.4.1's flag-gated drivers (`ojdbc11`, `mysql-connector-j`) are the only lock-validation exclusions, declared in `modules/datasources/build.gradle.kts`. Closes the §13.1 G2 sub-item (lockfile was deferred to P9) and the §13.2 "Gradle lockfile committed" checklist row. Zero dependency version changes — the locks record current resolution. |
| 2026-08-17 | v1.4 | explicit bean wiring | **§7.8** rewritten to the three-guard reality: `ArchitectureGuardTest` now bans field injection, DI stereotypes (`@Service`/`@Component`/`@Repository`, zero allowlist), and declarative transactions (`@Transactional` anywhere) across every module's production sources, plus the scope-non-emptiness guard; the stale `@Transactional`-on-`@Service` rule is gone. **§8.4** rewritten: zero DI stereotypes — every bean is an explicit `@Bean` method in a `@Configuration` class; the only annotated production classes are configurations, `@ConfigurationProperties`, and the web edge; transaction policy codified (`TransactionTemplate` sanctioned, `@Transactional` banned); the "open classes" bullet now names configurations as the CGLIB target. **§8.1** pattern snippet drops the `@Repository` annotation to match. |
| 2026-09-01 | v1.5 | multi-instance readiness (036) | **§5.6** gains `StaleExecutionSweeper` and records the project's FIRST scheduled surface: `web`'s `SweepSchedulingConfiguration` is the only `@EnableScheduling`, running the crash sweep (`@Scheduled`, fixed-delay 60s, default single-thread scheduler — decisions in its KDoc). "No `@Scheduled`/Quartz/cron anywhere" stops being a codebase property with this change; new scheduled jobs must be recorded here. (Header version catch-up: the header had been left at v1.3 when v1.4 landed.) |
| 2026-09-02 | v1.6 | multi-instance round 2 (050) | **§5.6** gains `ExecutionEventRetention` — the SECOND scheduled job (retention, fixed-delay 1h), riding the same single `@EnableScheduling`; the sweep record above now reads as the pattern, not the exception. §5.9's `web` list gains `DatasourceInvalidationConfiguration` (the §5.7 pool-invalidation channel — the codebase's first Redis pub/sub message, `dp:datasource-invalidated`). dag's `ExecutionSlots`/`ExecutorConfig` rename `maxConcurrentExecutionsGlobal` → `maxConcurrentExecutionsPerInstance` (050/R2: the limit was always per JVM). |
| 2026-08-28 | §3.1 amendment (promised at 019's merge) | The workspace domain's placement recorded in the responsibility matrix and the `auth` module spec: `WorkspaceRepository` → `workspaces`/`workspace_members`, `WorkspaceService`/types in the public API. No module moves; this documents what 019 built where it built it. |
| 2026-09-08 | 089 dialect-count fix | The integration-tests dependency note said the dialect catalogs "list the seven" — they have listed eight since 087's LAKE (§4.1). One word, plus the fact that LAKE is embedded too (its suite's container is MinIO, object storage, not a database server). |
