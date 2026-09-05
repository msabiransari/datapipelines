package co.datapipelines.web.config

import co.datapipelines.application.endpoints.EndpointInvalidationPublisher
import co.datapipelines.application.endpoints.EndpointRegistry
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.TestRedis
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The published-endpoint registry against a real Postgres and a real Redis (074, design §4).
 *
 * Two things here can only be proven against live infrastructure, and a double would quietly
 * "prove" both while testing neither:
 *
 * 1. **The §4.1 ambiguity refusal is a read-then-write under an advisory lock.** An in-memory
 *    fake would exercise [co.datapipelines.application.endpoints.EndpointPath.overlaps] and say
 *    nothing about whether the lock, the transaction and the `UNIQUE` constraint cooperate.
 * 2. **Cross-instance cache invalidation.** A single registry CANNOT go red on the defect the
 *    Redis channel exists to close: one instance drops its own snapshot synchronously, so the
 *    observable is identical with and without the channel. This suite therefore runs TWO
 *    registries with DIFFERENT instance ids over the REAL channel and the REAL production
 *    listener — the smallest configuration in which "B never heard about A's publish" is a
 *    distinguishable outcome.
 *
 * What it does not claim: this is not two Spring contexts. It is two registries and two channel
 * participants in one JVM, which is the whole of the invalidation contract (publisher, channel,
 * subscriber, cache). The serving path that consumes the registry is covered by its own E2E.
 */
class PublishedEndpointRegistryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: PublishedEndpointRepository
    private lateinit var transactions: TransactionTemplate
    private lateinit var redis: StringRedisTemplate
    private val containers = mutableListOf<RedisMessageListenerContainer>()

    @BeforeEach
    fun setUp() {
        val dataSource = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        // The repository's advisory lock is transaction-scoped: outside a transaction it would be
        // released the instant the SELECT returned, which is exactly when it is needed.
        transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        repository = PublishedEndpointRepository(jdbc)
        redis = TestRedis.template()
        TestRedis.flush(redis)
        cleanTables()
        seedFkTargets()
    }

    @AfterEach
    fun tearDown() {
        containers.forEach {
            it.stop()
            it.destroy()
        }
        containers.clear()
        cleanTables()
    }

    @Test
    fun `a published endpoint round-trips, carrying its parsed path variables`() {
        publish("/nyc/revenue/{borough}")

        val stored = repository.findByPath("/nyc/revenue/{borough}")
        assertAll(
            { stored?.pipelineId shouldBe PIPELINE_ID },
            { stored?.timeoutSeconds shouldBe 30 },
            { stored?.isEnabled shouldBe true },
            { stored?.pathVariables shouldBe listOf("borough") },
        )
    }

    @Test
    fun `a pattern that could match the same URL as an existing one is refused, naming the other`() {
        publish("/a/b")

        val refused = shouldThrow<DatapipelinesException> { publish("/a/{x}") }

        assertAll(
            { refused.code shouldBe "endpoint.path_conflict" },
            { refused.details["conflicting_path"] shouldBe "/a/b" },
            { refused.message.orEmpty() shouldContain "/a/b" },
            // Nothing was written: the refusal is BEFORE the insert, not a rollback of it.
            { repository.findAll().map { it.pathPattern } shouldBe listOf("/a/b") },
        )
    }

    @Test
    fun `an exact duplicate is refused with the same code as an overlapping one`() {
        // A client must not be able to tell the UNIQUE constraint from the §4.1 rule: both mean
        // "one URL, two meanings", and both answer `endpoint.path_conflict`.
        publish("/a/b")
        shouldThrow<DatapipelinesException> { publish("/a/b") }.code shouldBe "endpoint.path_conflict"
    }

    @Test
    fun `a non-overlapping pattern at the same depth publishes fine`() {
        // The negative control for the two refusals above: if the conflict check were simply
        // "same segment count", these would be refused too and the tests above would pass anyway.
        publish("/a/b")
        publish("/a/c")
        publish("/{x}/{y}/z")
        repository.findAll().size shouldBe 3
    }

    @Test
    fun `a disabled endpoint stays in the registry but leaves the matcher`() {
        publish("/nyc/revenue")
        repository.setEnabled("/nyc/revenue", enabled = false) shouldBe true

        assertAll(
            { repository.findAll().size shouldBe 1 },
            { repository.findAllEnabled().shouldBeEmpty() },
            // §5.6: a disabled endpoint answers exactly like an unknown path, so it must not be
            // matchable — the 404 must not depend on a special case further down the serve path.
            { EndpointRegistry(repository).reload().match("/nyc/revenue") shouldBe null },
        )
    }

    @Test
    fun `the registry serves its cached snapshot until it is invalidated`() {
        val registry = EndpointRegistry(repository)
        registry.matcher().match("/nyc/revenue") shouldBe null

        publish("/nyc/revenue")

        // Still the old snapshot. This is the cache doing its job — and it is also exactly the
        // staleness the channel exists to end on OTHER instances.
        registry.matcher().match("/nyc/revenue") shouldBe null
        registry.invalidateLocally()
        registry.matcher().match("/nyc/revenue").shouldNotBeNull()
    }

    @Test
    fun `a publish on instance A drops instance B's snapshot over the real Redis channel`() {
        val registryB = EndpointRegistry(repository)
        subscribe(registryB, instanceId = INSTANCE_B)
        val registryA = EndpointRegistry(repository, publisherFor(INSTANCE_A))

        assertAll(
            { registryA.matcher().match("/nyc/revenue") shouldBe null },
            { registryB.matcher().match("/nyc/revenue") shouldBe null },
        )

        publish("/nyc/revenue")
        registryA.invalidate()

        // Polling, not sleeping: the claim is "B converges", and a fixed sleep is either flaky or
        // slow. Exhausting the budget means the message never arrived — the M3-shaped defect.
        val seenByB = await { registryB.matcher().match("/nyc/revenue") }

        assertAll(
            {
                registryA
                    .matcher()
                    .match("/nyc/revenue")
                    ?.endpoint
                    ?.pathPattern shouldBe "/nyc/revenue"
            },
            { seenByB?.endpoint?.pathPattern shouldBe "/nyc/revenue" },
        )
    }

    @Test
    fun `an instance ignores its own invalidation message`() {
        // `origin` exists for exactly this: the publisher has already dropped its snapshot
        // synchronously, so reacting to itself would be pointless work on every write. If the
        // skip were removed, the endpoint below would be visible after the self-publish.
        val registry = EndpointRegistry(repository)
        subscribe(registry, instanceId = INSTANCE_A)
        registry.matcher().match("/nyc/revenue") shouldBe null

        publish("/nyc/revenue")
        publisherFor(INSTANCE_A).publish()
        TimeUnit.MILLISECONDS.sleep(SELF_MESSAGE_SETTLE_MS)

        registry.matcher().match("/nyc/revenue") shouldBe null
    }

    @Test
    fun `a malformed channel message is dropped and the subscription survives it`() {
        val registry = EndpointRegistry(repository)
        subscribe(registry, instanceId = INSTANCE_B)
        // WARM the snapshot first. Without this the assertion below is vacuous: the first
        // matcher() call would load the endpoint from the table whether or not the channel ever
        // delivered anything, and the test would pass with the subscriber disabled. (It did,
        // until a falsification run showed only ONE test going red instead of two.)
        registry.matcher().match("/nyc/revenue") shouldBe null

        redis.convertAndSend(EndpointInvalidationChannel.NAME, "{not json")
        TimeUnit.MILLISECONDS.sleep(SELF_MESSAGE_SETTLE_MS)

        // The garbage did not kill the listener thread: a well-formed message still lands.
        publish("/nyc/revenue")
        publisherFor(INSTANCE_A).publish()
        await { registry.matcher().match("/nyc/revenue") }.shouldNotBeNull()
    }

    /** Publishes [pattern] inside a transaction — the repository's advisory lock needs one. */
    private fun publish(pattern: String): PublishedEndpoint =
        transactions.execute {
            repository.insert(
                PublishedEndpoint.of(
                    id = UUID.randomUUID(),
                    workspaceId = WORKSPACE_ID,
                    pathPattern = pattern,
                    pipelineId = PIPELINE_ID,
                    timeoutSeconds = 30,
                    description = "",
                    isEnabled = true,
                    createdBy = USER_ID,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                ),
            )
        }!!

    /** The PRODUCTION publisher, so the message shape under test is the shipped one. */
    private fun publisherFor(instanceId: String): EndpointInvalidationPublisher =
        RedisEndpointInvalidationPublisher(redis, ObjectMapper(), instanceId)

    /** Subscribes [registry] to the real channel through the PRODUCTION listener. */
    private fun subscribe(
        registry: EndpointRegistry,
        instanceId: String,
    ) {
        val container =
            RedisMessageListenerContainer().apply {
                setConnectionFactory(requireNotNull(redis.connectionFactory))
                afterPropertiesSet()
                addMessageListener(
                    EndpointInvalidationListener(registry, ObjectMapper(), instanceId),
                    ChannelTopic(EndpointInvalidationChannel.NAME),
                )
                start()
            }
        containers += container
        // SUBSCRIBE lands asynchronously; publishing before it does would make the test flaky in
        // the direction that looks exactly like the defect.
        val ready = Instant.now().plus(BUDGET)
        while (!container.isRunning && Instant.now().isBefore(ready)) TimeUnit.MILLISECONDS.sleep(POLL_MS)
        TimeUnit.MILLISECONDS.sleep(SUBSCRIBE_SETTLE_MS)
    }

    private fun <T : Any> await(probe: () -> T?): T? {
        val deadline = Instant.now().plus(BUDGET)
        var seen = probe()
        while (seen == null && Instant.now().isBefore(deadline)) {
            TimeUnit.MILLISECONDS.sleep(POLL_MS)
            seen = probe()
        }
        return seen
    }

    /** Each spec cleans the tables it touches — the shared-container discipline (SharedPostgres). */
    private fun cleanTables() {
        jdbc.jdbcTemplate.execute("TRUNCATE endpoint_key_bindings, published_endpoints, pipelines CASCADE")
        jdbc.jdbcTemplate.update("DELETE FROM users WHERE id = '$USER_ID'")
    }

    /** The FK targets an endpoint row needs: a user and a pipeline (the workspace is V4-seeded). */
    private fun seedFkTargets() {
        DriverManager
            .getConnection(SharedPostgres.postgres.jdbcUrl, SharedPostgres.postgres.username, SharedPostgres.postgres.password)
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$USER_ID', 'endpoints-registry@datapipelines.test', 'Endpoints', 'test', 'endpoints-sub', TRUE, TRUE)
                        """.trimIndent(),
                    )
                    statement.execute(
                        """
                        INSERT INTO pipelines (id, workspace_id, name, display_name, description, owner_id, current_version)
                        VALUES ('$PIPELINE_ID', '$WORKSPACE_ID', 'revenue_by_borough', 'Revenue by borough', '', '$USER_ID', 1)
                        """.trimIndent(),
                    )
                }
            }
    }

    private companion object {
        val WORKSPACE_ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e4")
        val PIPELINE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e5")

        const val INSTANCE_A = "instance-A"
        const val INSTANCE_B = "instance-B"

        val BUDGET: Duration = Duration.ofSeconds(10)
        const val POLL_MS = 50L
        const val SUBSCRIBE_SETTLE_MS = 250L
        const val SELF_MESSAGE_SETTLE_MS = 750L
    }
}
