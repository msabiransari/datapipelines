package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.security.SecureRandom
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #874 G3 §B.4 — the datasource incarnation and generation (V59) on the real application and a real PostgreSQL, the
 * two-connection cases included. Two different locks are in play and each gets its own proof: G0's ADVISORY prerequisite
 * key (connection A takes it with the two int4 values G0's own helper, `PrerequisiteKey.datasource(name).lock`, yields,
 * touching no `datasources` row), and PostgreSQL's ordinary ROW lock, which serializes two writers of one row with or
 * without G0 and so proves nothing about G0. Every wait is on a latch, a future or a `pg_locks` / `pg_stat_activity`
 * read on a THIRD connection — never a sleep; each poll is bounded by [WAIT].
 *
 * - (i-a) a real `update` and a real `grant` wait UNGRANTED on A's advisory key, the generation unmoved, then advance it
 *   by exactly one once A commits;
 * - (i-b) two real writers released from one latch both land: start + 2;
 * - (ii-a) an observation (`recordTestOutcome`) completes while A holds the key and never waits on it;
 * - (ii-b) an observation behind a real same-row write completes after it and leaves the write's generation;
 * - (iii) G1's walk over the datasource node (through the G3 provider): its internal fingerprint moves with an update,
 *   not with an observation;
 * - (iv) the managed LAKE row has an incarnation after boot, and an UNCHANGED re-sync leaves its generation;
 * - (v) the real binder → the registry's `save` → the graph batch read equals a direct SELECT, after a create and after
 *   an update.
 * - (vi) V59's down and up on a `pg_dump` copy of this application's metadata (one soft-deleted row at least): no
 *   NULL incarnation, one distinct incarnation per row, every other column unchanged.
 *
 * **Reaching the real beans.** module-structure §4.2 lets this module compile against `:modules:app` only
 * (`verifyModuleDependencies`), so the writers, the binder, G0's key helper and G1's walker are reached at RUNTIME through
 * [Internal] — the production beans out of the application context and the production classes by name, the
 * `AuditLogRetentionE2eTest` precedent — never a copy and never a new build edge.
 *
 * The identity values themselves never leave this class: the witness lines print lock coordinates and counts only.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceIncarnationE2eTest {
    @Autowired private lateinit var context: ApplicationContext

    @Autowired private lateinit var transactions: PlatformTransactionManager

    private val registry by lazy { Internal.bean(context, "datasources.DatasourceRegistry") }
    private val repository by lazy { Internal.bean(context, "datasources.DatasourceRepository") }
    private val grants by lazy { Internal.bean(context, "datasources.DatasourceGrantRepository") }
    private val families by lazy { Internal.bean(context, "dependencies.FamilyRegistry") }
    private val registrar by lazy { Internal.bean(context, "web.config.ManagedLakeDatasourceRegistrar") }
    private val binder by lazy { Internal.singleton("application.datasources.DatasourcePayloadBinder") }

    private val mapper = ObjectMapper()
    private val threads = Executors.newCachedThreadPool()
    private lateinit var probe: Connection

    @BeforeAll
    fun seed() {
        probe = connection()
        execute(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) " +
                "VALUES ('$ACTOR', 'e2e-inc874@datapipelines.test', 'E2E 874', 'test', 'e2e-inc874-sub', TRUE, TRUE) " +
                "ON CONFLICT (id) DO NOTHING",
        )
        listOf(WORKSPACE, OTHER_WORKSPACE).forEach { id ->
            execute(
                "INSERT INTO workspaces (id, name, display_name, created_by) " +
                    "VALUES ('$id', 'w_${id.toString().replace("-", "")}', 'W', '$ACTOR') ON CONFLICT (id) DO NOTHING",
            )
        }
    }

    @AfterAll
    fun close() {
        threads.shutdownNow()
        probe.close()
        oidc.close()
    }

    @Test
    fun `i-a - a real update and a real grant wait UNGRANTED on the advisory key A holds, then advance by one`() {
        val writers =
            listOf<Pair<String, (String) -> Unit>>(
                "update" to { name -> update(name, "Relabelled") },
                "grant" to { name -> grant(name, OTHER_WORKSPACE) shouldBe true },
            )
        writers.forEach { (writer, write) ->
            val name = create("inc874_ia_$writer")
            val start = generation(name)
            AdvisoryHolder(name).use { a ->
                val running = threads.async { write(name) }
                val waiter = awaitWaiterOrDone(a, running)
                withClue("$writer: the writer's backend waits ungranted on A's advisory key while A holds it") {
                    waiter.shouldNotBeNull()
                    running.isDone shouldBe false
                    generation(name) shouldBe start
                }
                println("WITNESS i-a writer=$writer holder_pid=${a.pid} $waiter generation_while_held=start")
                a.commit()
                running.get(WAIT.seconds, TimeUnit.SECONDS)
            }
            withClue("$writer: completes after A commits, exactly one advance") { generation(name) shouldBe start + 1 }
        }
    }

    @Test
    fun `i-b - two real writers released from one latch both land - start plus two`() {
        val name = create("inc874_ib")
        val start = generation(name)
        val gate = CountDownLatch(1)
        val both =
            listOf(
                threads.async {
                    gate.await()
                    update(name, "Both")
                },
                threads.async {
                    gate.await()
                    grant(name, OTHER_WORKSPACE) shouldBe true
                },
            )
        gate.countDown()
        both.forEach { it.get(WAIT.seconds, TimeUnit.SECONDS) }

        generation(name) shouldBe start + 2
    }

    @Test
    fun `ii-a - an observation completes while A holds the advisory key and never waits on it`() {
        val name = create("inc874_iia")
        val start = generation(name)
        AdvisoryHolder(name).use { a ->
            val observed = threads.async { recordTestOutcome(name, ok = true) }
            val waiter = awaitWaiterOrDone(a, observed)
            withClue("the observation returned while A still holds its key, and no backend waited on that key") {
                waiter shouldBe null
                observed.isDone shouldBe true
                observed.get(WAIT.seconds, TimeUnit.SECONDS) shouldBe true
                a.holds() shouldBe true
            }
            println("WITNESS ii-a holder_pid=${a.pid} observation_done_while_held=true ungranted_on_key=0")
        }
        generation(name) shouldBe start
    }

    @Test
    fun `ii-b - an observation behind a real same-row write completes after it and leaves the write's generation`() {
        val name = create("inc874_iib")
        val start = generation(name)
        val written = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val writer =
            threads.async {
                TransactionTemplate(transactions).executeWithoutResult {
                    update(name, "Held")
                    written.countDown()
                    commit.await()
                }
            }
        written.await(WAIT.seconds, TimeUnit.SECONDS) shouldBe true
        val observed = threads.async { recordTestOutcome(name, ok = false) }
        // PostgreSQL MAY block the observation on the row lock: wait until it is either blocked or done, never assert which.
        awaitUntil { observed.isDone || observationBlocked() }
        commit.countDown()
        writer.get(WAIT.seconds, TimeUnit.SECONDS)
        observed.get(WAIT.seconds, TimeUnit.SECONDS) shouldBe true

        generation(name) shouldBe start + 1
        lastTestOk(name) shouldBe false
    }

    @Test
    fun `iii - the walk's internal fingerprint moves with an update and not with an observation`() {
        val name = create("inc874_iii")
        val before = walk(name)
        update(name, "Changed")
        val updated = walk(name)
        recordTestOutcome(name, ok = true) shouldBe true
        val observed = walk(name)

        listOf(before, updated, observed).forEach { snapshot ->
            withClue("non-vacuity: the datasource is the one RESOLVED node of every walk") { snapshot.resolvedIds shouldBe listOf(name) }
        }
        updated.fingerprint shouldNotBe before.fingerprint
        observed.fingerprint shouldBe updated.fingerprint
    }

    @Test
    fun `iv - the managed LAKE row carries an incarnation after boot and an UNCHANGED re-sync leaves its generation`() {
        // A re-sync also grants the lake to every workspace, so the first call absorbs this class's SQL-seeded
        // workspaces; the second finds everything in place — the boot-after-boot case.
        Internal.call(registrar, "register")
        val settled = generation(LAKE)
        Internal.call(registrar, "register")

        generation(LAKE) shouldBe settled
        count("SELECT count(*) FROM datasources WHERE name = '$LAKE' AND incarnation_id IS NOT NULL") shouldBe 1
        execute(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by, is_deleted) " +
                "VALUES ('inc874_deleted', 'Gone', 'H2', 'jdbc:h2:mem:inc874_deleted', 'none', '$ACTOR', TRUE) " +
                "ON CONFLICT (name) DO NOTHING",
        )
        withClue("non-vacuity: at least one soft-deleted row; every row has an incarnation, no two share one") {
            count("SELECT count(*) FROM datasources WHERE is_deleted") shouldBeGreaterThanOrEqual 1
            count("SELECT count(*) FROM datasources WHERE incarnation_id IS NULL") shouldBe 0
            count("SELECT count(DISTINCT incarnation_id) FROM datasources") shouldBe count("SELECT count(*) FROM datasources")
        }
    }

    @Test
    fun `v - the real binder and save write the identity the graph batch read returns`() {
        val name = create("inc874_v")
        val created = storedIdentity(name)
        graphRead(name) shouldBe created

        update(name, "Second")
        val updated = storedIdentity(name)

        graphRead(name) shouldBe updated
        updated.first shouldBe created.first
        updated.second shouldBe created.second + 1
    }

    /** (vi) V59 down and up on a copy of this application's metadata: [DatasourceIncarnationMigrationRehearsal]. */
    @Test
    fun `vi - V59 up on a copy of the metadata backfills one distinct incarnation per row, soft-deleted rows included`() {
        DatasourceIncarnationMigrationRehearsal.run(ACTOR)
    }

    // ------------------------------------------------------------------------------------------------ the writers

    /** The real create path: the binder over a create payload (no identity on the input), the registry's save, a grant. */
    private fun create(name: String): String {
        Internal.call(registry, "save", bind(payload(name, "Created"), CREATE_SHAPE, null), ACTOR)
        grant(name, WORKSPACE) shouldBe true
        return name
    }

    /** The real update path: the binder over an update payload, the name from the path, the registry's save. */
    private fun update(
        name: String,
        label: String,
    ) {
        Internal.call(registry, "save", bind(payload(name, label), UPDATE_SHAPE, name), ACTOR)
    }

    private fun bind(
        body: JsonNode,
        shape: Boolean,
        pathName: String?,
    ): Any = checkNotNull(Internal.call(binder, "bind", body, shape, pathName))

    private fun grant(
        name: String,
        workspace: UUID,
    ): Boolean = Internal.call(grants, "grant", name, workspace, ACTOR) as Boolean

    /** The observation writer itself (`DatasourceRepository.recordTestOutcome`), as `testConnection` calls it. */
    private fun recordTestOutcome(
        name: String,
        ok: Boolean,
    ): Boolean {
        val outcome = Internal.construct("datasources.DatasourceTestOutcome", Instant.now(), ok, null)
        return Internal.call(repository, "recordTestOutcome", name, outcome) as Boolean
    }

    private fun payload(
        name: String,
        label: String,
    ): JsonNode =
        mapper
            .createObjectNode()
            .put("name", name)
            .put("display_name", label)
            .put("dialect", "H2")
            .put("jdbc_url", "jdbc:h2:mem:$name")
            .set(
                "credential",
                mapper.createObjectNode().put("kind", "none"),
            )

    // ------------------------------------------------------------------------------------------------ the readers

    /** G1's walker over the production family registry, rooted at datasource [name] (ENTITY), in one transaction. */
    private fun walk(name: String): Walked =
        checkNotNull(
            TransactionTemplate(transactions).execute {
                val limits =
                    Internal.construct(
                        "dependencies.GraphLimits",
                        WALK_BOUND,
                        WALK_BOUND,
                        WALK_BOUND,
                        WALK_BOUND,
                        WIRE_BYTES,
                        LOCK_WAIT_SECONDS,
                        WALK_BOUND,
                        WAIT,
                        Duration.ofSeconds(STATEMENT_SECONDS),
                    )
                val cycles = Internal.enumOf("dependencies.CycleHandling", "DIAGNOSE")
                val walker = Internal.construct("dependencies.DependencyWalker", families, limits, Clock.systemUTC(), cycles)
                val snapshot = checkNotNull(Internal.call(walker, "walk", listOf(datasourceRequest(name)), resolveContext()))
                Walked(
                    fingerprint = Internal.call(snapshot, "getInternalFingerprint") as String,
                    resolvedIds =
                        (Internal.call(snapshot, "getNodes") as List<*>).mapNotNull { node ->
                            Internal.call(checkNotNull(node), "getIdentity")?.let { Internal.call(it, "getStableEntityId") as String }
                        },
                )
            },
        )

    private data class Walked(
        val fingerprint: String,
        val resolvedIds: List<String>,
    )

    /** An ENTITY request for datasource [name]; `FamilyKey` is a value class, so the family travels as its `String`. */
    private fun datasourceRequest(name: String): Any {
        val address =
            Internal.construct("dependencies.ObjectAddress", "datasource", Internal.construct("dependencies.LookupScope", WORKSPACE), name)
        return Internal.construct("dependencies.ObjectRequest", address, Internal.singleton("dependencies.Selector\$Entity"))
    }

    private fun resolveContext(): Any {
        val deadline =
            checkNotNull(Internal.call(checkNotNull(Internal.companion("dependencies.Deadline")), "after", WAIT, Clock.systemUTC()))
        return Internal.construct(
            "dependencies.ResolveContext",
            WORKSPACE,
            Internal.enumOf("dependencies.MetadataSnapshot", "READ_COMMITTED"),
            Internal.enumOf("dependencies.Purpose", "INTERACTIVE_READ"),
            Internal.construct("dependencies.Authority\$Member", ACTOR),
            Internal.call(deadline, "withStatementLimit", WAIT),
            Internal.call(checkNotNull(Internal.companion("dependencies.ReadIsolation")), "getNONE"),
        )
    }

    /** The provider's own batch read (§C.0) of [name], as the identity pair it carries. */
    private fun graphRead(name: String): Pair<UUID, Long> {
        val facts = checkNotNull((Internal.call(repository, "findVisibleGraphFacts", listOf(name), WORKSPACE) as List<*>).single())
        return Internal.call(facts, "getIncarnationId") as UUID to Internal.call(facts, "getGeneration") as Long
    }

    // ------------------------------------------------------------------------------------------------ SQL witnesses

    /**
     * Connection A: an open transaction holding datasource [name]'s prerequisite advisory key, taken with the two int4
     * values G0's own helper (`PrerequisiteKey.datasource(name).lock`) yields — the statement production's guard sends.
     * No row is touched.
     */
    private inner class AdvisoryHolder(
        name: String,
    ) : AutoCloseable {
        private val connection = connection().apply { autoCommit = false }
        val pid: Int

        init {
            val key = checkNotNull(Internal.call(checkNotNull(Internal.companion("typesystem.guard.PrerequisiteKey")), "datasource", name))
            val lock = checkNotNull(Internal.call(key, "getLock"))
            connection.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)::text").use { statement ->
                statement.setInt(1, Internal.call(lock, "getLockSpace") as Int)
                statement.setInt(2, Internal.call(lock, "getSubKey") as Int)
                statement.executeQuery().close()
            }
            pid =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            holds() shouldBe true
        }

        /** Whether A's backend holds a GRANTED advisory lock right now (read on the probe connection). */
        fun holds(): Boolean = count("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted AND pid = $pid") == 1

        fun commit() = connection.commit()

        override fun close() {
            runCatching { connection.rollback() }
            connection.close()
        }
    }

    /** A backend waiting UNGRANTED on exactly A's advisory lock (same classid/objid/objsubid), or null. */
    private fun waiterOn(a: AdvisoryHolder): String? =
        probe
            .prepareStatement(
                "SELECT w.pid, w.objsubid FROM pg_locks w JOIN pg_locks h ON h.locktype = 'advisory' AND h.granted AND h.pid = ? " +
                    "AND w.classid = h.classid AND w.objid = h.objid AND w.objsubid = h.objsubid " +
                    "WHERE w.locktype = 'advisory' AND NOT w.granted AND w.pid <> ?",
            ).use { statement ->
                statement.setInt(1, a.pid)
                statement.setInt(2, a.pid)
                statement.executeQuery().use { rows ->
                    if (rows.next()) "waiter_pid=${rows.getInt(1)} locktype=advisory granted=false objsubid=${rows.getInt(2)}" else null
                }
            }

    /** Polls until a backend waits on A's key or [running] finished; the waiter's description, or null when none waited. */
    private fun awaitWaiterOrDone(
        a: AdvisoryHolder,
        running: CompletableFuture<*>,
    ): String? {
        var waiter: String? = null
        awaitUntil {
            waiter = waiterOn(a)
            waiter != null || running.isDone
        }
        return waiter
    }

    /** Whether the observation's UPDATE waits on a lock (another session's row lock) right now. */
    private fun observationBlocked(): Boolean =
        count(
            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%SET last_test_at%' " +
                "AND pid <> pg_backend_pid()",
        ) > 0

    private fun awaitUntil(condition: () -> Boolean) {
        val until = System.nanoTime() + WAIT.toNanos()
        while (!condition()) {
            check(System.nanoTime() < until) { "the condition did not hold within $WAIT" }
            Thread.onSpinWait()
        }
    }

    private fun generation(name: String): Long = storedIdentity(name).second

    /** The stored row's identity pair, read directly — the oracle the batch read is compared with. */
    private fun storedIdentity(name: String): Pair<UUID, Long> =
        probe.prepareStatement("SELECT incarnation_id, generation FROM datasources WHERE name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "no datasource row $name" }
                rows.getObject(1, UUID::class.java) to rows.getLong(2)
            }
        }

    private fun lastTestOk(name: String): Boolean? =
        probe.prepareStatement("SELECT last_test_ok FROM datasources WHERE name = ?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getBoolean(1).takeUnless { rows.wasNull() }
            }
        }

    private fun count(sql: String): Int =
        probe.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next()) { "no row from $sql" }
                rows.getInt(1)
            }
        }

    private fun execute(sql: String) {
        probe.createStatement().use { it.execute(sql) }
    }

    private fun <T> ExecutorService.async(work: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(work, this)

    private companion object {
        val ACTOR: UUID = UUID.fromString("a11e0000-0000-0000-0000-000000000874")
        val WORKSPACE: UUID = UUID.fromString("a11e0000-0000-0000-0000-000000008741")
        val OTHER_WORKSPACE: UUID = UUID.fromString("a11e0000-0000-0000-0000-000000008742")

        /** Every bounded wait and the walk's deadline; the guard's own cumulative lock wait (5 s) is far shorter. */
        val WAIT: Duration = Duration.ofSeconds(30)

        /** The binder's second argument: a create needs the credential (here kind `none`), an update keeps the stored one. */
        const val CREATE_SHAPE = true
        const val UPDATE_SHAPE = false

        /** A one-node walk's limits (`GraphLimits`: depth, nodes, edges, diagnostics … the batch size). */
        const val WALK_BOUND = 16
        const val WIRE_BYTES = 1_000_000
        const val LOCK_WAIT_SECONDS = 5
        const val STATEMENT_SECONDS = 5L

        const val LAKE = "lake874inc"
        const val BUCKET = "dp874-incarnation-e2e"

        /** The app's signing key for this context: random bytes, never a fixture value. */
        val SIGNING_KEY: String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val oidc = OidcDiscoveryStub()

        fun connection(): Connection = SharedE2e.postgres.createConnection("")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            ManagedLakeDeliveryProperties.register(registry, SIGNING_KEY, oidc.issuer, LAKE, BUCKET)
        }
    }
}

/**
 * The production classes this module may not compile against (module-structure §4.2), reached by NAME at runtime —
 * beans out of the application context, Kotlin objects and companions by their JVM fields, constructors and methods by
 * arity. A Kotlin value-class parameter is erased to its underlying type and moves the constructor to a synthetic one
 * ending in `DefaultConstructorMarker`, which [construct] admits (passing null for the marker). The cause of a reflective
 * call's failure is rethrown unwrapped, so an assertion inside production code reads as itself.
 */
private object Internal {
    private const val ROOT = "co.datapipelines."
    private const val MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"

    fun type(name: String): Class<*> = Class.forName(ROOT + name)

    fun bean(
        context: ApplicationContext,
        name: String,
    ): Any = context.getBean(type(name))

    fun singleton(name: String): Any = type(name).getField("INSTANCE").get(null)

    fun companion(name: String): Any? = type(name).getField("Companion").get(null)

    fun enumOf(
        name: String,
        constant: String,
    ): Any = type(name).enumConstants.single { (it as Enum<*>).name == constant }

    fun construct(
        name: String,
        vararg args: Any?,
    ): Any {
        val constructors = type(name).constructors
        val constructor =
            constructors.firstOrNull { it.parameterCount == args.size }
                ?: constructors.single { it.parameterCount == args.size + 1 && it.parameterTypes.last().name == MARKER }
        val actual = if (constructor.parameterCount == args.size) args else arrayOf(*args, null)
        return unwrapped { constructor.newInstance(*actual) }
    }

    fun call(
        target: Any,
        method: String,
        vararg args: Any?,
    ): Any? {
        val found =
            target.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == args.size && !it.isBridge && !it.isSynthetic }
                ?: error("${target.javaClass.name} has no public $method with ${args.size} parameters")
        return unwrapped { found.invoke(target, *args) }
    }

    private fun <T> unwrapped(call: () -> T): T =
        try {
            call()
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
}
