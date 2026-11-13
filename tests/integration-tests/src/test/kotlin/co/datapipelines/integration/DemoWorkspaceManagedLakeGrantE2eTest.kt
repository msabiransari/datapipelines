package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.logcapture.SnapshotListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.UUID

/**
 * #881 (dp-lake record §6, §10 D3): the shipped `demo` receives the managed lake's grant during startup,
 * before the first freshness tick. The ordinary-workspace creation transaction of #823 (owner ruling
 * 2026-11-07) is separate from the demo seeder; these cases do not assert atomic workspace-and-grant visibility.
 *
 * The trace the issue missed: the managed-lake registrar is `@PostConstruct`, so on a fresh database it creates the
 * unowned managed row (and grants it to every workspace that exists then) before any `afterSingletonsInstantiated()`
 * callback runs; `DemoWorkspaceStartup` is one of those callbacks, and the seeder's instance-datasource grant covers
 * every live unowned datasource, the managed row included. These cases pin that, in two orderings:
 *
 * - **first boot**: a fresh database with the lake enabled — the grant row exists for `demo` before any tick;
 * - **later boot**: `demo` was created while the lake was disabled (no managed row, so no grant), then the lake is
 *   enabled on a later boot — the registrar's boot pass grants it, again before any tick.
 *
 * "Before any tick" is a witness, not timing luck: the tick is configured far longer than the case
 * ([NO_TICK_SECONDS]), and Spring's observation of every `@Scheduled` run ([ScheduledJobsSchedulerE2eTest.TickRecorder])
 * counts the freshness job's runs, read AFTER the grant read. The control case boots the same way with a short tick
 * and sees the count move, so a zero is a witness that can see. A healthy tick with nothing to do logs nothing, which
 * is why log silence is never the witness.
 *
 * Each case migrates its own scratch database; the suite runs its own MinIO (an empty bucket: no table is read).
 */
class DemoWorkspaceManagedLakeGrantE2eTest {
    @Test
    fun `a fresh database grants the managed lake to demo at first boot, before any freshness tick`() {
        val database = SharedE2e.scratchDatabase("demo_lake_first_boot")
        val (context, lines) = bootCapturing(database, tickSeconds = NO_TICK_SECONDS)
        context.use {
            val grant = withClue("the demo workspace holds the managed lake's grant") { grantOf(database, DEMO_ID) }
            val ticks = it.getBean(ScheduledJobsSchedulerE2eTest.TickRecorder::class.java).countOf(FRESHNESS_JOB)
            println(
                "#881 first-boot: tick_seconds=$NO_TICK_SECONDS job=$FRESHNESS_JOB ticks_at_read=$ticks " +
                    "grant_present=${grant != null} granted_by_system_actor=${grant?.let { g -> g == systemActorOf(database) }} " +
                    "lake_grant_audit_rows=${lakeGrantAuditRows(database)} " +
                    "registrar=${lines.filter { l -> l.startsWith(REGISTRAR_GRANTED) }} " +
                    "seeder=${lines.filter { l -> l.startsWith(SEEDER_GRANTED) }}",
            )
            withClue("demo's managed-lake grant row, read by (datasource, workspace)") { (grant != null) shouldBe true }
            withClue("the freshness tick ran zero times when the grant was read") { ticks shouldBe 0 }
            assertWrittenByServer(database, grant, lines, seederGranted = true)
            // Which path wrote it: the registrar's boot pass ran (and granted the workspaces that existed then)
            // BEFORE the seeder created demo and granted it its instance datasources, the managed row among them.
            val registrarAt = lines.indexOfFirst { l -> l.startsWith(REGISTRAR_GRANTED) }
            val seederAt = lines.indexOfFirst { l -> l.startsWith(SEEDER_GRANTED) }
            withClue("the registrar's boot grant (line $registrarAt) precedes the seeder's (line $seederAt)") {
                (registrarAt in 0 until seederAt) shouldBe true
            }
        }
    }

    @Test
    fun `a demo created while the lake was disabled is granted by the boot that enables it, before any tick`() {
        val database = SharedE2e.scratchDatabase("demo_lake_later_boot")
        bootCapturing(database, tickSeconds = NO_TICK_SECONDS, lakeEnabled = false).first.use {
            withClue("the disabled boot created demo") { workspaceExists(database, DEMO_ID) shouldBe true }
            withClue("with the lake disabled there is no managed row, so no grant") { grantOf(database, DEMO_ID) shouldBe null }
        }
        val (context, lines) = bootCapturing(database, tickSeconds = NO_TICK_SECONDS)
        context.use {
            val grant = grantOf(database, DEMO_ID)
            val ticks = it.getBean(ScheduledJobsSchedulerE2eTest.TickRecorder::class.java).countOf(FRESHNESS_JOB)
            println(
                "#881 later-boot: tick_seconds=$NO_TICK_SECONDS job=$FRESHNESS_JOB ticks_at_read=$ticks " +
                    "grant_present=${grant != null} lake_grant_audit_rows=${lakeGrantAuditRows(database)} " +
                    "registrar=${lines.filter { l -> l.startsWith(REGISTRAR_GRANTED) }}",
            )
            withClue("demo's managed-lake grant row after the enabling boot") { (grant != null) shouldBe true }
            withClue("the freshness tick ran zero times when the grant was read") { ticks shouldBe 0 }
            withClue("the registrar's boot pass wrote it: $lines") {
                lines.filter { l -> l.startsWith(REGISTRAR_GRANTED) }.size shouldBeGreaterThanOrEqual 1
            }
            assertWrittenByServer(database, grant, lines, seederGranted = false)
        }
    }

    @Test
    fun `control - the same boot with a short tick sees the freshness job run, so a zero count is a witness that can see`() {
        val database = SharedE2e.scratchDatabase("demo_lake_tick_control")
        bootCapturing(database, tickSeconds = CONTROL_TICK_SECONDS).first.use {
            val recorder = it.getBean(ScheduledJobsSchedulerE2eTest.TickRecorder::class.java)
            val deadline = System.nanoTime() + Duration.ofSeconds(CONTROL_TICK_SECONDS * CONTROL_TICKS_BUDGET).toNanos()
            while (recorder.countOf(FRESHNESS_JOB) < 1 && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
            val ticks = recorder.countOf(FRESHNESS_JOB)
            println("#881 control: tick_seconds=$CONTROL_TICK_SECONDS job=$FRESHNESS_JOB ticks=$ticks jobs=${recorder.jobs()}")
            withClue("the recorder observed the freshness job (${recorder.jobs()})") { ticks shouldBeGreaterThanOrEqual 1 }
        }
    }

    /**
     * The row's grantor is the system actor, and the grant wrote no `datasource.granted` audit row (§8C.10: the
     * server's grants write none). [seederGranted]: the seeder's own line reports a grant for `demo`.
     */
    private fun assertWrittenByServer(
        database: SharedE2e.SharedPostgresRef,
        grantedBy: UUID?,
        lines: List<String>,
        seederGranted: Boolean,
    ) {
        withClue("granted_by is the server's system actor") { grantedBy shouldBe systemActorOf(database) }
        withClue("the server's grant writes no datasource.granted audit row for the lake") { lakeGrantAuditRows(database) shouldBe 0 }
        val seeder = lines.filter { it.startsWith(SEEDER_GRANTED) }
        if (seederGranted) {
            withClue("the seeder granted demo its instance datasources, the managed lake among them: $seeder") {
                seeder.map { it.substringAfter("grants=").substringBefore(' ').toInt() }.single() shouldBeGreaterThanOrEqual 1
            }
        } else {
            withClue("the enabling boot did not re-create demo: $seeder") { seeder shouldBe emptyList() }
        }
    }

    // ------------------------------------------------------------------ boot

    /**
     * One boot of the application with the tick witness, capturing the product's log lines from before the refresh.
     * The appender is attached by an initializer: Boot re-initializes Logback while it prepares the environment,
     * which drops an appender attached before `run`, and initializers run after that and before any bean exists.
     */
    private fun bootCapturing(
        database: SharedE2e.SharedPostgresRef,
        tickSeconds: Long,
        lakeEnabled: Boolean = true,
    ): Pair<ConfigurableApplicationContext, List<String>> {
        val capture = SnapshotListAppender()
        val logger = { LoggerFactory.getLogger(PRODUCT_LOGGERS) as Logger }
        try {
            val context =
                SpringApplicationBuilder(
                    DatapipelinesApplication::class.java,
                    ScheduledJobsSchedulerE2eTest.TickRecorderConfiguration::class.java,
                ).initializers(ApplicationContextInitializer<ConfigurableApplicationContext> { logger().addAppender(capture) })
                    .run(
                        "--server.port=0",
                        *bootProperties(database, tickSeconds, lakeEnabled).map { (k, v) -> "--$k=$v" }.toTypedArray(),
                    )
            return context to capture.messages()
        } finally {
            logger().detachAppender(capture)
            capture.stop()
        }
    }

    // Command-line args, not builder `.properties(...)`: those are DEFAULT properties and application.yml's
    // `${SPRING_DATASOURCE_URL}` would override them; args win (ManagedLakeReadsE2eTest's boot, copied).
    private fun bootProperties(
        database: SharedE2e.SharedPostgresRef,
        tickSeconds: Long,
        lakeEnabled: Boolean,
    ): List<Pair<String, String>> =
        listOf(
            "management.server.port" to "0",
            "spring.datasource.url" to database.jdbcUrl,
            "spring.datasource.username" to database.username,
            "spring.datasource.password" to database.password,
            "datapipelines.redis.password" to "",
            "datapipelines.redis.host" to SharedE2e.redisHost,
            "datapipelines.redis.port" to SharedE2e.redisPort.toString(),
            "datapipelines.jwt.secret" to SECRET,
            "datapipelines.db.encryption-key" to SECRET,
            "datapipelines.auth.oidc.providers[0].name" to "google",
            "datapipelines.auth.oidc.providers[0].client-id" to "test-google-client-id",
            "datapipelines.auth.oidc.providers[0].client-secret" to "test-google-client-secret",
            "datapipelines.auth.oidc.providers[0].issuer-uri" to oidc.issuer,
            "datapipelines.auth.oidc.providers[0].display-name" to "Test google",
            "datapipelines.auth.base-url" to "http://localhost:8080",
            "datapipelines.lake.enabled" to lakeEnabled.toString(),
            "datapipelines.lake.name" to LAKE,
            "datapipelines.lake.storage.bucket" to BUCKET,
            "datapipelines.lake.storage.region" to "us-east-1",
            "datapipelines.lake.storage.endpoint" to endpoint,
            "datapipelines.lake.storage.path-style" to "true",
            "datapipelines.lake.storage.access-key" to WRITER_ACCESS,
            "datapipelines.lake.storage.secret-key" to WRITER_SECRET,
            "datapipelines.lake.reads.max-generation-age-seconds" to MAX_AGE_SECONDS.toString(),
            "datapipelines.lake.reads.freshness-tick-seconds" to tickSeconds.toString(),
        )

    // ------------------------------------------------------------------ reads (an independent JDBC connection)

    private fun <T> metadata(
        database: SharedE2e.SharedPostgresRef,
        block: (Connection) -> T,
    ): T = DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use(block)

    /** The managed lake's grant row for [workspaceId]: its `granted_by`, or null when there is no row. */
    private fun grantOf(
        database: SharedE2e.SharedPostgresRef,
        workspaceId: UUID,
    ): UUID? =
        metadata(database) { connection ->
            connection
                .prepareStatement("SELECT granted_by FROM datasource_workspaces WHERE datasource_name = ? AND workspace_id = ?")
                .use { ps ->
                    ps.setString(1, LAKE)
                    ps.setObject(2, workspaceId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                }
        }

    private fun workspaceExists(
        database: SharedE2e.SharedPostgresRef,
        workspaceId: UUID,
    ): Boolean =
        metadata(database) { connection ->
            connection.prepareStatement("SELECT 1 FROM workspaces WHERE id = ?").use { ps ->
                ps.setObject(1, workspaceId)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }

    private fun systemActorOf(database: SharedE2e.SharedPostgresRef): UUID? =
        metadata(database) { connection ->
            connection.prepareStatement("SELECT id FROM users WHERE email = ?").use { ps ->
                ps.setString(1, SYSTEM_ACTOR_EMAIL)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
            }
        }

    /** `datasource.granted` audit rows that name the managed lake, for any workspace. */
    private fun lakeGrantAuditRows(database: SharedE2e.SharedPostgresRef): Int =
        metadata(database) { connection ->
            connection
                .prepareStatement(
                    "SELECT COUNT(*) FROM audit_log WHERE event = 'datasource.granted' AND details_json::text LIKE ?",
                ).use { ps ->
                    ps.setString(1, "%\"$LAKE\"%")
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    companion object {
        private const val LAKE = "lake"
        private const val BUCKET = "dp-lake-881"

        /** `DemoWorkspaceSeeder.DEMO_WORKSPACE_ID`, restated: this module compiles against `app` only. */
        private val DEMO_ID: UUID = UUID.fromString("de000000-0000-0000-0000-000000000001")

        /** `UserService.SYSTEM_ACTOR_EMAIL`, restated for the same reason. */
        private const val SYSTEM_ACTOR_EMAIL = "system@system.invalid"

        /**
         * A tick that cannot fire during a case (its initial delay is the same value), below the age bound as
         * `LakeRules` requires, and inside the five-term staleness rule with the shipped protection window
         * (ManagedLakeReadsE2eTest's F boots the same pair).
         */
        private const val NO_TICK_SECONDS = 600L
        private const val MAX_AGE_SECONDS = 900L
        private const val CONTROL_TICK_SECONDS = 2L
        private const val CONTROL_TICKS_BUDGET = 10L
        private const val POLL_MILLIS = 200L

        private const val FRESHNESS_JOB = "co.datapipelines.web.config.ManagedLakeFreshnessScheduler.tick"
        private const val PRODUCT_LOGGERS = "co.datapipelines"
        private const val REGISTRAR_GRANTED = "event=lake.managed_datasource_granted"
        private const val SEEDER_GRANTED = "event=auth.workspace.demo_datasources_granted"

        private const val MINIO_PORT = 9000
        private const val WRITER_ACCESS = "dpwriter881"

        /** Generated per run: no literal credential in a fixture. */
        private val WRITER_SECRET = "w-" + UUID.randomUUID().toString().replace("-", "")
        private val SECRET = Base64.getEncoder().encodeToString(ByteArray(32))

        private val oidc = OidcDiscoveryStub()

        private val minio: GenericContainer<*> by lazy {
            GenericContainer(DockerImageName.parse(SharedE2e.MINIO_IMAGE))
                .withEnv("MINIO_ROOT_USER", WRITER_ACCESS)
                .withEnv("MINIO_ROOT_PASSWORD", WRITER_SECRET)
                .withCommand("server", "/data")
                .withExposedPorts(MINIO_PORT)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(MINIO_PORT))
                .also { container ->
                    container.start()
                    createBucket(container)
                }
        }

        private val endpoint get() = "http://localhost:${minio.getMappedPort(MINIO_PORT)}"

        private fun createBucket(container: GenericContainer<*>) {
            S3Client
                .builder()
                .endpointOverride(URI.create("http://localhost:${container.getMappedPort(MINIO_PORT)}"))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(WRITER_ACCESS, WRITER_SECRET)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build()
                .use { s3 -> s3.createBucket { it.bucket(BUCKET) } }
        }

        @AfterAll
        @JvmStatic
        fun close() {
            oidc.close()
        }
    }
}
