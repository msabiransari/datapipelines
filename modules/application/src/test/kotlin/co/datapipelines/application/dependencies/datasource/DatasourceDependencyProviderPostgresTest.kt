package co.datapipelines.application.dependencies.datasource

import co.datapipelines.application.SharedPostgres
import co.datapipelines.application.dependencies.HandClock
import co.datapipelines.application.dependencies.HeldTableLock
import co.datapipelines.application.dependencies.NonArtifactSeed
import co.datapipelines.application.dependencies.ObservedStatements
import co.datapipelines.application.dependencies.PostgresStatementCeiling
import co.datapipelines.application.dependencies.StatementCancelled
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.dependencies.Authority
import co.datapipelines.dependencies.Deadline
import co.datapipelines.dependencies.DeadlineExceeded
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.GraphLimits
import co.datapipelines.dependencies.LookupScope
import co.datapipelines.dependencies.NodeFacts
import co.datapipelines.dependencies.ObjectAddress
import co.datapipelines.dependencies.ObjectRequest
import co.datapipelines.dependencies.Operational
import co.datapipelines.dependencies.Outcome
import co.datapipelines.dependencies.OwnerScope
import co.datapipelines.dependencies.ResolveContext
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.VersionInfo
import co.datapipelines.dependencies.conformance.ProviderConformance
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #874 G3 §C.0–C.1 on a real PostgreSQL, over the descriptor exactly as `DependencyGraphNonArtifactConfiguration` builds
 * it (a repository over the ceiling's own template). Statement counter: [ObservedStatements] (every real statement on
 * the connection, the ceiling's own `set_config` excluded). Hidden and missing are one outcome; one statement per call;
 * a spent deadline sends nothing; a statement blocked past its bound is cancelled by PostgreSQL; a result that arrives
 * after the deadline is discarded and refused.
 */
class DatasourceDependencyProviderPostgresTest {
    private val clock = HandClock()
    private var afterStatement: () -> Unit = {}
    private val statements = ObservedStatements(SharedPostgres.dataSource()) { afterStatement() }
    private val seed = NonArtifactSeed(NamedParameterJdbcTemplate(SharedPostgres.dataSource()))
    private val manager = DataSourceTransactionManager(statements.dataSource)
    private val ceiling = PostgresStatementCeiling(statements.dataSource)
    private val descriptor = DatasourceDependencyFamily.descriptor(DatasourceRepository(ceiling.jdbc), ceiling)
    private val lock = HeldTableLock(SharedPostgres.dataSource())

    @BeforeEach
    fun reset() {
        seed.reset()
        seed.datasource("visible", readonly = true)
        seed.datasource("also_visible")
        seed.datasource("theirs", grantedTo = listOf(seed.otherWorkspaceId))
        seed.datasource("gone", deleted = true)
        seed.datasource("unrequested")
        statements.observed.clear()
    }

    @AfterEach
    fun release() {
        lock.close()
    }

    @Test
    fun `the datasource provider conforms - a declared leaf, absent and hidden names MISSING`() {
        val violations =
            inTransaction {
                ProviderConformance.check(
                    ProviderConformance.Fixture(
                        descriptor = descriptor,
                        context = seed.context(),
                        present = listOf(request("visible"), request("also_visible")),
                        absent = listOf(request("theirs"), request("gone"), request("absent")),
                        references = ProviderConformance.References.None,
                    ),
                )
            }

        violations.shouldBeEmpty()
    }

    @Test
    fun `a mixed batch answers each request once in ONE statement - visible nodes, hidden and absent MISSING`() {
        val versioned = ObjectRequest(address("visible"), Selector.ExactVersion(1))
        val batch = listOf(request("visible"), request("also_visible"), request("theirs"), request("gone"), request("absent"), versioned)

        val outcomes = inTransaction { descriptor.provider.resolveMany(batch, seed.context()) }

        withClue(statements.observed.map { it.first }) { statements.observed shouldHaveSize 1 }
        outcomes.keys shouldBe batch.toSet()
        listOf("theirs", "gone", "absent").forEach { outcomes[request(it)] shouldBe Outcome.Missing }
        outcomes[versioned] shouldBe Outcome.Unsupported
        val visible = outcomes[request("visible")].shouldBeInstanceOf<Outcome.Resolved<*>>()
        val (incarnation, generation) = seed.datasourceIdentity("visible")
        visible.identity shouldBe ResolvedIdentity(FamilyKey.DATASOURCE, OwnerScope.Deployment, "visible", null)
        visible.facts shouldBe
            NodeFacts(
                name = "visible",
                versionInfo = VersionInfo.Unversioned,
                lifecycle = null,
                displayName = "DS visible",
                subtype = Dialect.H2.wire,
                operational = Operational.Datasource(readonly = true),
                generation = DatasourceDependencyFamily.fingerprintGeneration(incarnation, generation),
            )
        outcomes[request("also_visible")].shouldBeInstanceOf<Outcome.Resolved<*>>().facts.operational shouldBe
            Operational.Datasource(readonly = false)
    }

    /**
     * The fingerprint input moves with the stored generation AND with the incarnation, and with nothing the node shows: the
     * oracle is two reads of the same row, never [DatasourceDependencyFamily.fingerprintGeneration] itself.
     */
    @Test
    fun `the node's fingerprint input moves with the generation and the incarnation - not with a label`() {
        val jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        val before = generationOf("visible")

        jdbc.update("UPDATE datasources SET display_name = 'Relabelled' WHERE name = 'visible'", emptyMap<String, Any>())
        val relabelled = generationOf("visible")
        jdbc.update("UPDATE datasources SET generation = generation + 1 WHERE name = 'visible'", emptyMap<String, Any>())
        val advanced = generationOf("visible")
        jdbc.update(
            "UPDATE datasources SET incarnation_id = gen_random_uuid(), generation = :g WHERE name = 'visible'",
            mapOf("g" to seed.datasourceIdentity("visible").second - 1),
        )
        val recreated = generationOf("visible")

        relabelled shouldBe before
        advanced shouldNotBe before
        withClue("same generation as the first read, another incarnation") {
            seed.datasourceIdentity("visible").second shouldBe 1L
            recreated shouldNotBe before
        }
    }

    @Test
    fun `the system authority's batch returns exactly the requested live names - any workspace, never the unrequested`() {
        val batch = listOf(request("visible"), request("theirs"), request("gone"), request("absent"))

        val outcomes = inTransaction { descriptor.provider.resolveMany(batch, seed.context(authority = Authority.System)) }

        statements.observed shouldHaveSize 1
        outcomes
            .filterValues { it is Outcome.Resolved }
            .keys
            .map { it.address.lookupKey }
            .toSet() shouldBe setOf("visible", "theirs")
        outcomes[request("gone")] shouldBe Outcome.Missing
        outcomes[request("absent")] shouldBe Outcome.Missing
    }

    @Test
    fun `a batch with no ENTITY request sends no statement`() {
        val outcomes =
            inTransaction { descriptor.provider.resolveMany(listOf(ObjectRequest(address("visible"), Selector.Current)), seed.context()) }

        outcomes.values.single() shouldBe Outcome.Unsupported
        statements.observed.shouldBeEmpty()
    }

    @Test
    fun `a batch above the walk's maximum is refused on entry - never read per key`() {
        val batch = (0..GraphLimits.MAX_RESOLVE_BATCH_SIZE).map { request("n$it") }

        shouldThrow<IllegalArgumentException> { inTransaction { descriptor.provider.resolveMany(batch, seed.context()) } }
        statements.observed.shouldBeEmpty()
    }

    @Test
    fun `an already expired deadline throws before any statement is sent`() {
        val deadline = Deadline.after(Duration.ofSeconds(1), clock)
        clock.now = clock.now.plusSeconds(2)

        shouldThrow<DeadlineExceeded> { inTransaction { descriptor.provider.resolveMany(listOf(request("visible")), context(deadline)) } }
        statements.observed.shouldBeEmpty()
    }

    @Test
    fun `in flight - a read blocked on another session's lock is cancelled by PostgreSQL at the one second left`() {
        lock.lock("datasources")
        val witnessed = AtomicBoolean(false)
        val witness =
            Thread {
                while (!witnessed.get() &&
                    !Thread.currentThread().isInterrupted
                ) {
                    witnessed.set(lock.waiting("datasources"))
                }
            }
        witness.isDaemon = true
        witness.start()

        val started = System.nanoTime()
        val failure =
            shouldThrow<DeadlineExceeded> {
                inTransaction {
                    descriptor.provider.resolveMany(
                        listOf(request("visible")),
                        seed.context(deadline = Deadline.after(Duration.ofSeconds(1))),
                    )
                }
            }
        val took = Duration.ofNanos(System.nanoTime() - started)
        witness.interrupt()

        withClue("the statement waited on the lock (pg_locks) and PostgreSQL cancelled it (57014) at its 1 s bound") {
            witnessed.get() shouldBe true
            failure.cause.shouldBeInstanceOf<StatementCancelled>()
            took shouldBeLessThan Duration.ofSeconds(4)
            statements.timeouts().single().let { it == "1s" || it.removeSuffix("ms").toInt() <= MILLIS_LEFT } shouldBe true
        }
    }

    @Test
    fun `success after expiry - rows read after the deadline passed are discarded and the call throws`() {
        val deadline = Deadline.after(Duration.ofSeconds(5), clock).withStatementLimit(Duration.ofSeconds(5))
        afterStatement = { clock.now = clock.now.plusSeconds(6) }

        shouldThrow<DeadlineExceeded> { inTransaction { descriptor.provider.resolveMany(listOf(request("visible")), context(deadline)) } }
        withClue("the read itself succeeded: exactly one statement ran") { statements.observed shouldHaveSize 1 }
    }

    private fun generationOf(name: String): Long? =
        inTransaction { descriptor.provider.resolveMany(listOf(request(name)), seed.context()) }
            .values
            .single()
            .shouldBeInstanceOf<Outcome.Resolved<*>>()
            .facts.generation

    private fun context(deadline: Deadline): ResolveContext = seed.context(deadline = deadline)

    private fun address(name: String) = ObjectAddress(FamilyKey.DATASOURCE, LookupScope(seed.workspaceId), name)

    private fun request(name: String) = ObjectRequest(address(name), Selector.Entity)

    private fun <T> inTransaction(read: () -> T): T = checkNotNull(TransactionTemplate(manager).execute { read() })

    private companion object {
        /** The in-flight case's whole budget: its one statement's bound is at most this. */
        const val MILLIS_LEFT = 1000
    }
}
