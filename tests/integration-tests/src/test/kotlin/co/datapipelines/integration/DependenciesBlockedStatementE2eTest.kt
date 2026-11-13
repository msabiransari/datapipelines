package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.config.HttpClientConfig
import io.restassured.config.RestAssuredConfig
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * **A blocked metadata statement is cancelled by the database at its bound, on the real route** (#875 G4 §D; record
 * §25.4). What this proves: DATABASE-ENFORCED bounding — PostgreSQL itself cancels the tree's blocked read
 * (`SET LOCAL statement_timeout`, which the production `PostgresStatementCeiling` sets before each provider statement);
 * the JDBC calls alone are `DependenciesStatementCountTest`'s.
 *
 * With `metadata-timeout-seconds` = 1 AND `statement-timeout-seconds` = 1 (the range check requires statement ≤ metadata),
 * a second connection holds `LOCK TABLE datasources IN ACCESS EXCLUSIVE MODE` in an open transaction — the table the
 * datasource family's one batch read reads — and the datasource tree route answers 503 `dependencies.metadata.unavailable`
 * with `Retry-After: 5` in under 3 s; the lock is released in `finally`, and after it the same request answers 200. The
 * walk's transaction is observed through a recording hook on the metadata DataSource: `SHOW transaction_read_only` = on
 * and `SHOW transaction_isolation` = read committed, on the walk's own connection just before its batch read. The HTTP
 * client's socket timeout and the class's hard timeout make a plant that hangs fail instead of blocking the suite.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class, DependenciesBlockedStatementE2eTest.TransactionRecorder::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class DependenciesBlockedStatementE2eTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a read blocked behind an exclusive lock is cancelled at its one second bound - 503 in under 3 s, then 200`() {
        ensureSeeded()
        withClue("before the lock: the tree answers") { tree().statusCode shouldBe HTTP_OK }
        withClue("the walk reads in a read-only READ COMMITTED transaction, observed on its own connection") {
            OBSERVED.shouldNotBeEmpty()
            OBSERVED.toSet() shouldBe setOf("on" to "read committed")
        }

        val blocker = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        val elapsedMs: Long
        val blocked: Response
        try {
            blocker.autoCommit = false
            blocker.createStatement().use { it.execute("LOCK TABLE datasources IN ACCESS EXCLUSIVE MODE") }
            val started = System.nanoTime()
            blocked = tree()
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        } finally {
            blocker.rollback()
            blocker.close()
        }
        println("event=dependencies.blocked_statement elapsed_ms=$elapsedMs status=${blocked.statusCode} observed=$OBSERVED")

        withClue("${blocked.statusCode} ${blocked.body().asString().take(EXCERPT)}") {
            blocked.statusCode shouldBe HTTP_UNAVAILABLE
            blocked.jsonPath().getString("error.code") shouldBe METADATA_UNAVAILABLE
            blocked.header("Retry-After") shouldBe "5"
        }
        withClue("the statement waited for its bound and no longer: cancelled by the database near one second") {
            elapsedMs shouldBeGreaterThanOrEqual MIN_BLOCKED_MS
            elapsedMs shouldBeLessThan MAX_BLOCKED_MS
        }
        withClue("after the lock is released: the same request answers") { tree().statusCode shouldBe HTTP_OK }
    }

    private fun tree(): Response =
        given()
            .port(port)
            .config(
                RestAssuredConfig.config().httpClient(
                    HttpClientConfig
                        .httpClientConfig()
                        .setParam("http.connection.timeout", HTTP_TIMEOUT_MS)
                        .setParam("http.socket.timeout", HTTP_TIMEOUT_MS),
                ),
            ).asSession(E2eSession.jwt(JWT_SECRET, AUTHOR, "author@blocked875.test", WORKSPACE))
            .accept("application/json")
            .get(DependencyTreeQueries.path("datasource", DATASOURCE))

    /**
     * The recording hook, over JDBC interfaces only (this module compiles against the application's entry point, not its
     * modules): the metadata `dataSource` bean is wrapped so that, on the connection about to prepare the datasource
     * family's batch read ([GRAPH_READ]), the walk transaction's `transaction_read_only` and `transaction_isolation` are
     * read first — on that same connection, so inside the walk's own transaction — then the statement is prepared
     * unchanged. Nothing else is touched.
     */
    @TestConfiguration
    @Suppress("UtilityClassWithPublicConstructor") // a configuration class: its one bean is a static BeanPostProcessor
    class TransactionRecorder {
        companion object {
            @JvmStatic
            @Bean
            fun recordWalkTransaction(): BeanPostProcessor =
                object : BeanPostProcessor {
                    override fun postProcessAfterInitialization(
                        bean: Any,
                        beanName: String,
                    ): Any = if (beanName == DATA_SOURCE_BEAN && bean is DataSource) recording(bean) else bean
                }

            private fun recording(target: DataSource): DataSource =
                proxy(DataSource::class.java) { method, args ->
                    val answer = method.invoke(target, *args)
                    if (method.name == "getConnection" && answer is Connection) recording(answer) else answer
                }

            private fun recording(connection: Connection): Connection =
                proxy(Connection::class.java) { method, args ->
                    if (method.name == "prepareStatement" && (args.firstOrNull() as? String)?.contains(GRAPH_READ) == true) {
                        OBSERVED += show(connection, "transaction_read_only") to show(connection, "transaction_isolation")
                    }
                    method.invoke(connection, *args)
                }

            private fun show(
                connection: Connection,
                setting: String,
            ): String =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SHOW $setting").use { rows ->
                        check(rows.next()) { "SHOW $setting answered a row" }
                        rows.getString(1)
                    }
                }

            @Suppress("UNCHECKED_CAST") // the proxy implements exactly [type]
            private fun <T : Any> proxy(
                type: Class<T>,
                call: (Method, Array<Any?>) -> Any?,
            ): T =
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                    try {
                        call(method, args ?: emptyArray())
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                } as T
        }
    }

    private companion object {
        const val DATA_SOURCE_BEAN = "dataSource"

        /** The datasource family's batch read (`DatasourceRepository.GRAPH_FACTS_SELECT`), the statement the lock blocks. */
        const val GRAPH_READ = "d.incarnation_id, d.generation FROM datasources d"
        const val METADATA_UNAVAILABLE = "dependencies.metadata.unavailable"
        const val HTTP_OK = 200
        const val HTTP_UNAVAILABLE = 503
        const val EXCERPT = 400
        const val HTTP_TIMEOUT_MS = 20_000

        /** The bound is one second; a cancel well under it would be some other failure, not the bound. */
        const val MIN_BLOCKED_MS = 900L
        const val MAX_BLOCKED_MS = 3_000L

        const val WORKSPACE = "blocked875"
        const val DATASOURCE = "tree875_blocked"
        val WORKSPACE_ID = UUID.randomUUID().toString()
        val AUTHOR = UUID.randomUUID().toString()

        /** `(transaction_read_only, transaction_isolation)` per provider batch, as the walk's transaction reported them. */
        val OBSERVED = CopyOnWriteArrayList<Pair<String, String>>()

        val JWT_SECRET = E2eSession.newSecret()
        val ENCRYPTION_KEY = E2eSession.newSecret()
        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()
        private var seeded = false

        /** One workspace, its author and one granted datasource (read by name only — never opened by a graph read). */
        fun ensureSeeded() {
            if (seeded) return
            seeded = true
            E2eClean.beforeSeeding()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { s ->
                    s.execute("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Blocked')")
                    s.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                            "('$AUTHOR', 'author@blocked875.test', 'author', 'test', 'blocked875-author', TRUE, FALSE)",
                    )
                    s.execute("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$AUTHOR', 'author')")
                    s.execute(
                        "INSERT INTO datasources (name, display_name, dialect, jdbc_url, username, credential_encrypted, " +
                            "created_by, owner_workspace_id) VALUES ('$DATASOURCE', 'Blocked', 'H2', 'jdbc:h2:mem:$DATASOURCE', " +
                            "'sa', 'x'::bytea, '$AUTHOR', '$WORKSPACE_ID')",
                    )
                    s.execute(
                        "INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by) " +
                            "VALUES ('$DATASOURCE', '$WORKSPACE_ID', '$AUTHOR')",
                    )
                }
            }
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
            // Both bounds at one second: the statement is the one the database cancels, and the deadline agrees.
            registry.add("datapipelines.release.dependencies.metadata-timeout-seconds") { "1" }
            registry.add("datapipelines.release.dependencies.statement-timeout-seconds") { "1" }
            registry.add("datapipelines.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() = oidc.close()
    }
}
