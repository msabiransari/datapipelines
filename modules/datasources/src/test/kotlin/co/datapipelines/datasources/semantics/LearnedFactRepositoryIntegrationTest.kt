package co.datapipelines.datasources.semantics

import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.Fixtures
import co.datapipelines.datasources.SharedPostgres
import co.datapipelines.datasources.testEncryptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * [LearnedFactRepository] against the shipped schema — `learned_facts` as V25 creates it
 * (metadata-db §4.18). The visibility predicate (D-S1/D-S9), the normalised-refs duplicate
 * key (O-4), the one-way trust mark (§6) and retire-never-delete (D-S11) are each proven
 * against real rows; the CHECK constraints are proven by INSERTs that must fail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LearnedFactRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: LearnedFactRepository
    private lateinit var actor: UUID
    private lateinit var alpha: UUID
    private lateinit var beta: UUID

    private val encryptor = testEncryptor()

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    }

    @BeforeEach
    fun setUp() {
        repository = LearnedFactRepository(jdbc)
        jdbc.jdbcTemplate.execute("TRUNCATE learned_facts, datasource_workspaces, datasources, workspaces, users CASCADE")
        actor = insertUser("learner@example.com", "sub-learner")
        alpha = insertWorkspace("alpha")
        beta = insertWorkspace("beta")
        DatasourceRepository(jdbc).create(Fixtures.postgres(name = "warehouse"), encryptor.encrypt("p", "warehouse"), actor)
    }

    @Test
    fun `a DATASOURCE fact is visible to every workspace and a WORKSPACE fact to its own only`() {
        val shared = repository.insert(newFact(LearnedFactScope.DATASOURCE, workspaceId = null, kind = LearnedFactKind.UNIT))
        val alphaOnly = repository.insert(newFact(LearnedFactScope.WORKSPACE, workspaceId = alpha, kind = LearnedFactKind.DEFINITION))

        repository.findVisibleByDatasource("warehouse", alpha).map { it.id } shouldContainExactly listOf(shared.id, alphaOnly.id)
        repository.findVisibleByDatasource("warehouse", beta).map { it.id } shouldContainExactly listOf(shared.id)
        repository.findVisible(alphaOnly.id, beta).shouldBeNull()
        repository.findVisible(alphaOnly.id, alpha).shouldNotBeNull()
    }

    @Test
    fun `refs are stored normalised so an equal ref set in another order is the same duplicate key`() {
        val refs = listOf(FactRef(null, "orders", "amount"), FactRef(null, "customers", "id"))
        repository.insert(newFact(LearnedFactScope.DATASOURCE, null, LearnedFactKind.JOIN, refs = refs))

        val duplicate =
            repository.findDuplicate(
                LearnedFactScope.DATASOURCE,
                null,
                "warehouse",
                LearnedFactKind.JOIN,
                refs.reversed(),
                FACT_TEXT,
            )
        duplicate.shouldNotBeNull()
        // The stored refs come back sorted by their canonical key — customers before orders.
        duplicate.refs.map { it.table } shouldContainExactly listOf("customers", "orders")
        repository
            .findDuplicate(
                LearnedFactScope.DATASOURCE,
                null,
                "warehouse",
                LearnedFactKind.JOIN,
                refs,
                "$FACT_TEXT, but different",
            ).shouldBeNull()
    }

    @Test
    fun `markTrust is one-way and never touches a retired row`() {
        val fact = repository.insert(newFact(LearnedFactScope.DATASOURCE, null, LearnedFactKind.GRAIN))

        repository.markTrust(fact.id, LearnedFactTrust.NEEDS_REVIEW) shouldBe true
        // Same mark again: nothing to change.
        repository.markTrust(fact.id, LearnedFactTrust.NEEDS_REVIEW) shouldBe false
        repository.retire(fact.id, "superseded") shouldBe true
        repository.retire(fact.id, "again") shouldBe false
        repository.markTrust(fact.id, LearnedFactTrust.STALE) shouldBe false

        val retired = repository.findVisibleByDatasource("warehouse", alpha, includeRetired = true).single()
        retired.trust shouldBe LearnedFactTrust.RETIRED
        retired.retiredReason shouldBe "superseded"
        retired.retiredAt.shouldNotBeNull()
        // The listing without include_retired no longer serves it (§6).
        repository.findVisibleByDatasource("warehouse", alpha) shouldBe emptyList()
        // A retired fact is not a duplicate: re-recording it is how a retirement is corrected.
        repository
            .findDuplicate(
                LearnedFactScope.DATASOURCE,
                null,
                "warehouse",
                LearnedFactKind.GRAIN,
                retired.refs,
                FACT_TEXT,
            ).shouldBeNull()
    }

    @Test
    fun `since narrows the listing to facts recorded at or after the instant`() {
        val first = repository.insert(newFact(LearnedFactScope.DATASOURCE, null, LearnedFactKind.WINDOW))
        val earlier = repository.insert(newFact(LearnedFactScope.DATASOURCE, null, LearnedFactKind.SAMPLING))
        jdbc.update("UPDATE learned_facts SET recorded_at = recorded_at - interval '1 minute' WHERE id = :id", mapOf("id" to earlier.id))

        repository.findVisibleByDatasource("warehouse", alpha, since = first.recordedAt).map { it.id } shouldContainExactly listOf(first.id)
        repository.findVisibleByDatasource("warehouse", alpha, since = Instant.EPOCH).size shouldBe 2
    }

    @Test
    fun `the database refuses a kind under the wrong scope and a fact outside the length window`() {
        shouldThrow<DataIntegrityViolationException> {
            repository.insert(newFact(LearnedFactScope.WORKSPACE, alpha, LearnedFactKind.UNIT))
        }.message shouldContain "chk_learned_facts_kind_scope"
        shouldThrow<DataIntegrityViolationException> {
            repository.insert(newFact(LearnedFactScope.DATASOURCE, null, LearnedFactKind.UNIT, fact = "short"))
        }.message shouldContain "chk_learned_facts_fact_length"
    }

    private fun newFact(
        scope: LearnedFactScope,
        workspaceId: UUID?,
        kind: LearnedFactKind,
        refs: List<FactRef> = listOf(FactRef(null, "orders", "amount")),
        fact: String = FACT_TEXT,
    ) = LearnedFactRepository.NewFact(
        scope = scope,
        workspaceId = workspaceId,
        datasourceName = "warehouse",
        kind = kind,
        fact = fact,
        refs = refs,
        evidenceSql = "SELECT amount FROM orders LIMIT 5",
        evidenceSummary = "amount: 12.50, 3.00",
        trust = LearnedFactTrust.OBSERVED,
        schemaFingerprint = "orders=abc",
        recordedBy = actor,
        recordedVia = "mcp",
        recordedIn = alpha,
        sourcePipelineId = null,
        sourceVersion = null,
        supersedes = null,
    )

    private fun insertUser(
        email: String,
        subject: String,
    ): UUID =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO users (email, display_name, provider, provider_subject)" +
                    " VALUES (:email, 'Learner', 'google', :subject) RETURNING id",
                mapOf("email" to email, "subject" to subject),
                UUID::class.java,
            ),
        )

    private fun insertWorkspace(name: String): UUID =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO workspaces (name, display_name) VALUES (:name, :name) RETURNING id",
                mapOf("name" to name),
                UUID::class.java,
            ),
        )

    private companion object {
        const val FACT_TEXT = "amount is in cents, never dollars"
    }
}
