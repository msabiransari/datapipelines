# Module Structure Specification

**Status:** v1 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** (all other specs — this spec operationalizes them into code structure)
**Last updated:** 2026-08-05

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

| Module | Spec | Responsibility |
|---|---|---|
| `typesystem` | [type-system.md](type-system.md) | The 11 canonical types, per-dialect mappers, H2 mapping, schema envelope. Foundation. |
| `pipeline-contract` | [pipeline-contract.md](pipeline-contract.md) | Pipeline JSON model, validation, ExecutionContext type. |
| `templates` | [templates.md](templates.md) | Freemarker integration, library macros, template registry, versioning. |
| `datasources` | [datasources.md](datasources.md) | Connection registry, HikariCP pools, dialect adapters, credential encryption. |
| `staging` | [staging.md](staging.md) | H2 lifecycle, staging interface, type-aware batch inserts. |
| `dag` | [dag-executor.md](dag-executor.md) | DAG data structure, executor (coroutines), node runner, SSE event emitter interface. |
| `auth` | [auth.md](auth.md) | Users, API keys, JWT sessions, scopes, audit log. |
| `mcp-server` | [mcp-server.md](mcp-server.md) | MCP transport (Streamable HTTP), tool/resource/prompt definitions. Thin adapter over REST. |
| `web` | [rest-api.md](rest-api.md) | Spring Boot REST controllers, SSE endpoints, Thymeleaf UI, error handling, CORS. |
| `app` | (this spec) | Spring Boot `main()`, assembles all modules, configuration, runnable JAR. |
| `tests/integration-tests` | (this spec) | Cross-module integration tests (Testcontainers for real databases). |

---

## 4. Dependency Direction

### 4.1 Layered dependency graph

```
                          ┌─────────────────────────────┐
                          │          typesystem          │   ← foundation
                          └──────────┬──────────────────┘
                                     │
       ┌───────────────┬─────────────┼─────────────┬────────────────┐
       │               │             │             │                │
┌──────▼──────┐ ┌──────▼─────┐ ┌─────▼──────┐ ┌────▼──────┐ ┌──────▼─────┐
│   auth      │ │ datasources│ │ templates  │ │ staging   │ │ pipeline-  │
│             │ │            │ │            │ │           │ │ contract   │
└──────┬──────┘ └──────┬─────┘ └─────┬──────┘ └────┬──────┘ └──────┬─────┘
       │               │             │             │               │
       │               │             │             │       ┌───────▼────────┐
       │               │             │             │       │      dag       │
       │               │             │             │       │   (executor)   │
       │               │             │             │       └───────┬────────┘
       │               │             │             │               │
       │               │             │             │       ┌───────▼────────┐
       │               │             │             │       │  mcp-server    │
       │               │             │             │       │                │
       │               │             │             │       └───────┬────────┘
       │               │             │             │               │
       └───────────────┴─────────────┴─────────────┴───────────────┤
                                                                   │
                                                          ┌────────▼────────┐
                                                          │       web       │
                                                          └────────┬────────┘
                                                                   │
                                                          ┌────────▼────────┐
                                                          │       app       │
                                                          └─────────────────┘
```

### 4.2 Rules

1. **No cycles.** Enforced by Gradle. A module may not transitively depend on itself.
2. **No upward dependencies.** A module may only depend on modules in the same layer or below.
3. **No skipping layers.** `dag` (high layer) does not depend on `auth` directly — that's a sibling. It depends on lower-layer modules only.
4. **`typesystem` is the foundation.** No internal dependencies. Pure type definitions and mappers.
5. **`web` aggregates everything.** Has the most internal dependencies.
6. **`app` is the bootstrap.** Depends on `web` only (which transitively pulls everything else). Contains `main()` and configuration.

### 4.3 Cross-cutting concerns

Some concerns touch every module:
- **Logging** — SLF4J + Logback (or structured logging via `minlog`/`logstash-logback-encoder`). Each module logs via SLF4J API; the actual logback config lives in `app`.
- **Error handling** — every module's exceptions extend a base `DatapipelinesException` (in `typesystem` or a tiny `common` module).
- **Configuration** — typed config classes per module, composed into the global `app` config.
- **Metrics** — Micrometer API in modules, actual metrics registry configured in `app`.

---

## 5. Module Specs (Detailed)

### 5.1 `typesystem`

**Dependencies (internal):** none.

**Dependencies (external):** `kotlinx.serialization.json` (JSON for schema envelope).

**Public API:**
- `LogicalType` enum
- `ColumnSchema` data class
- `IngressTypeMapper` interface + per-dialect implementations (`PostgresTypeMapper`, `OracleTypeMapper`, `MssqlTypeMapper`, `MysqlTypeMapper`, `H2TypeMapper`, `DuckDbTypeMapper`, `SqliteTypeMapper`)
- `H2TypeMapper` (canonical → H2 type)
- `JsonEncoder` (canonical value → wire representation)
- `SchemaEnvelope` data class

**Tests:** unit tests for every mapper; round-trip tests for every type.

### 5.2 `pipeline-contract`

**Dependencies (internal):** `typesystem`.

**Dependencies (external):** `kotlinx.serialization.json`.

**Public API:**
- `Pipeline` data class (top-level entity)
- `Node`, `NodeType`, `NodeSource` data classes
- `Parameter`, `ParameterSchema` data classes
- `TemplateRef` data class
- `PipelineValidator` — runs all §10 validations from the spec
- `PipelineSerializer` / `PipelineDeserializer` (kotlinx.serialization)
- `ExecutionContext` — runtime mutable map

**Tests:** unit tests for validator (every check + every code path); serialization round-trip tests.

### 5.3 `templates`

**Dependencies (internal):** `typesystem`, `pipeline-contract` (for `Parameter` shape).

**Dependencies (external):** `org.freemarker:freemarker` (pinned).

**Public API:**
- `Template`, `TemplateVersion` data classes
- `TemplateRegistry` interface
- `TemplateEngine` — wraps Freemarker
- `TemplateValidator`
- `LibraryResolver` — transitive import resolution

**Tests:** unit tests for validator; round-trip tests for sample templates; security tests for forbidden-construct rejection.

### 5.4 `datasources`

**Dependencies (internal):** `typesystem` (for `IngressTypeMapper` per dialect).

**Dependencies (external):**
- `com.zaxxer:HikariCP` — connection pooling.
- `org.postgresql:postgresql` — bundled PG driver.
- `com.microsoft.sqlserver:mssql-jdbc` — bundled MSSQL driver.
- `com.h2database:h2` — bundled H2 driver (also for staging).
- `org.duckdb:duckdb_jdbc` — bundled DuckDB driver.
- `org.xerial:sqlite-jdbc` — bundled SQLite driver.
- `org.bouncycastle:bcprov-jdk18on` — crypto primitives.
- Optional: `com.oracle.database.jdbc:ojdbc11` (via `-Poracle` Gradle profile).
- Optional: `com.mysql:mysql-connector-j` (via `-Pmysql` profile).

**Public API:**
- `Datasource` data class
- `Dialect` enum
- `DatasourceRegistry` interface
- `DialectAdapter` interface + per-dialect implementations
- `CredentialEncryptor` — AES-256-GCM
- `ConnectionPoolManager`

**Tests:** unit tests for adapter URL validation; integration tests via Testcontainers (real DB containers).

### 5.5 `staging`

**Dependencies (internal):** `typesystem`.

**Dependencies (external):** `com.h2database:h2`.

**Public API:**
- `Staging` interface
- `StagingFactory` interface
- `H2Staging`, `H2StagingFactory` implementations
- `StageResult`, `StagingStats` data classes

**Tests:** unit tests for type mapping; integration tests for staging round-trip; streaming tests for memory-bounded behavior.

### 5.6 `dag`

**Dependencies (internal):** `pipeline-contract`, `templates`, `datasources`, `staging`, `typesystem`.

**Dependencies (external):**
- `org.jetbrains.kotlinx:kotlinx-coroutines-core`
- `io.projectreactor:reactor-core` (for SSE event emission, optional)

**Public API:**
- `Dag<T>` data structure (the ~150-line implementation)
- `PipelineExecutor`
- `ExecutableNode`, `NodeSource`, `NodeType`
- `NodeStats`, `NodeStatus`
- `EventEmitter` interface
- `ExecutionEvent` sealed class

**Tests:** unit tests for `Dag<T>` algorithms; unit tests for executor (mocked dependencies); integration tests with real H2 + Testcontainers sources.

### 5.7 `auth`

**Dependencies (internal):** none beyond `typesystem` (for shared exceptions, if needed).

**Dependencies (external):**
- `de.mkammerer:argon2-jvm`
- `io.jsonwebtoken:jjwt-api`, `jjwt-impl`, `jjwt-jackson`
- `org.springframework.security:spring-security-web`, `spring-security-config`
- `org.bouncycastle:bcprov-jdk18on`

**Public API:**
- `User`, `ApiKey` data classes
- `Scope` enum
- `PasswordHasher`, `JwtService`, `ApiKeyService`
- `AuthFilter` — Spring web filter
- `@RequiredScope` annotation + interceptor
- `AuditLogger`

**Tests:** unit tests for each component; integration tests for full auth flow.

### 5.8 `mcp-server`

**Dependencies (internal):** `pipeline-contract`, `templates`, `datasources`, `typesystem`, `auth` (for principal lookup).

**Dependencies (external):**
- `io.modelcontextprotocol:mcp-core` (the MCP SDK — pinned, currently `0.x`).

> **Verification needed:** Confirm the canonical Maven coordinates for the MCP SDK against current releases. This is fast-moving; pin to a specific version at implementation time.

**Public API:**
- `McpServer` — Spring Boot autoconfiguration
- `McpTool`, `McpResource`, `McpPrompt` annotation markers
- Tool/resource/prompt implementations (one per top-level entity operation)
- `McpAuthFilter` — translates API key into MCP session

**Tests:** unit tests for tool dispatch; integration tests using an in-process MCP client.

### 5.9 `web`

**Dependencies (internal):** everything — this is the aggregation layer.

**Dependencies (external):**
- `org.springframework.boot:spring-boot-starter-web`
- `org.springframework.boot:spring-boot-starter-thymeleaf`
- `org.springframework.boot:spring-boot-starter-validation`
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
- `org.springframework.boot:spring-boot-starter-actuator`

**Public API:**
- `DatapipelinesApplication.kt` — `@SpringBootApplication main()`
- `application.yml` — top-level config
- `logback-spring.xml` — logging config

**Tests:** smoke tests via `@SpringBootTest` (full context load).

### 5.11 `tests/integration-tests`

**Dependencies (internal):** `app` (full app context for end-to-end tests).

**Dependencies (external):**
- `org.springframework.boot:spring-boot-starter-test`
- `org.testcontainers:postgresql`, `mysql`, `mssql`, `oracle-xe`, `db2` (only the ones we test)
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
kotlinx-serialization = "1.6.3"
jackson = "2.17.2"
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
bouncycastle = "1.78.1"
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
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
# ... (every dependency declared here, used by ref from build.gradle.kts files)

spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web", version.ref = "spring-boot" }
# etc.
```

> **Verification needed:** All versions are illustrative — must be checked against Maven Central at implementation time for current stable. Pin to specific versions (no `+`, no `latest.integration`, no SNAPSHOT in production).

### 6.1 Versioning rules

- **Pin every dependency to an exact version.** No ranges, no `+`, no SNAPSHOT in production.
- **Upgrade deliberately.** Dependabot or Renovate can open PRs, but no auto-merge.
- **Track security advisories.** GitHub Dependabot alerts + Snyk (or equivalent) on the repo.
- **Lockfile committed.** Gradle generates `gradle.lockfile` for repeatable builds.

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
                freeCompilerArgs.add("-Xcontext-receivers")
                allWarningsAsErrors = true           // strict
            }
        }

        project.dependencies {
            testImplementation(libs.junit.jupiter)
            testImplementation(libs.mockk)
            testImplementation(libs.kotest.runner)
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
- Test source root convention: `src/test/kotlin`, `src/integrationTest/kotlin`.

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

Pattern per module:
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

### 8.2 Configuration properties per module

Each module exposes typed configuration:

```kotlin
@ConfigurationProperties(prefix = "datapipelines.templates")
data class TemplatesProperties(
    val cacheSize: Int = 1000,
    val renderTimeoutMs: Long = 10_000
)
```

Composed into `application.yml` in `app`:

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

### 8.3 Beans and DI

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

- Live in `src/integrationTest/kotlin` of each module OR in `tests/integration-tests/` (for cross-module).
- Run on `./gradlew integrationTest`.
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
- The dependency direction (the layered graph in §4).
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

Before considering the module structure "ready":

- [ ] `./gradlew build` succeeds from clean state.
- [ ] All unit tests pass.
- [ ] All integration tests pass.
- [ ] ktlint and detekt clean.
- [ ] `./gradlew bootJar` produces an executable JAR.
- [ ] `java -jar app/build/libs/datapipelines-app-*.jar` starts the app and serves `/health`.
- [ ] Every module has a README.
- [ ] Every module's public API has KDoc.
- [ ] Version catalog has exact versions for every dependency (no `+`, no SNAPSHOT).
- [ ] Gradle lockfile committed.
- [ ] No internal module depends on `app` (one-way dependency).
- [ ] `tests/integration-tests` runs end-to-end pipeline against a real PG container.

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial module structure spec: 10 modules + integration tests, dependency graph, version catalog, build conventions, Spring Boot conventions |
| 2026-08-05 | v1.1 | design system integration | Added `@acme/design-tokens` as the styling foundation for the `web` module. Documented vendoring approach (CSS files, not npm). Referenced Pipeline Editor spec for integration details. |
