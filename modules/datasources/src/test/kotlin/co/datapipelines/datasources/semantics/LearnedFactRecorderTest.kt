package co.datapipelines.datasources.semantics

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DatasourceRepository
import co.datapipelines.datasources.Fixtures
import co.datapipelines.datasources.JdbcUrlPool
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.SharedPostgres
import co.datapipelines.datasources.SqlProbe
import co.datapipelines.datasources.testEncryptor
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.DriverManager
import java.util.UUID

/**
 * [LearnedFactRecorder] end to end below the principal: a REAL H2 is the datasource whose live
 * introspection validates refs and whose probe runs the evidence, and the shared Postgres is the
 * metadata store the row lands in. Every refusal of design §7.1 / §13.15 is provoked; the
 * evidence summary, the trust level and the fingerprint are read back from the stored row.
 *
 * The drift half — record, then change the H2 table, then read again — is
 * [LearnedFactDriftTest]; this suite proves what the store starts with.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LearnedFactRecorderTest {
    private val h2 = DriverManager.getConnection(H2_URL)
    private val registry = mockk<DatasourceRegistry>()
    private val datasource: Datasource = Fixtures.h2(name = "orders-db", jdbcUrl = H2_URL)

    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: LearnedFactRepository
    private lateinit var recorder: LearnedFactRecorder
    private lateinit var actor: UUID
    private lateinit var workspace: UUID

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        every { registry.get(datasource.name) } returns datasource
        every { registry.poolFor(datasource) } returns JdbcUrlPool(datasource.jdbcUrl, datasource.name)
    }

    @BeforeEach
    fun setUp() {
        h2.createStatement().use { st ->
            st.execute("CREATE TABLE orders (id INT PRIMARY KEY, amount_cents BIGINT, placed_at TIMESTAMP)")
            st.execute("INSERT INTO orders VALUES (1, 1250, '2026-01-01 10:00:00'), (2, 300, '2026-01-02 11:00:00')")
        }
        repository = LearnedFactRepository(jdbc)
        recorder = LearnedFactRecorder(repository, SchemaIntrospector(registry), SqlProbe(registry))
        jdbc.jdbcTemplate.execute("TRUNCATE learned_facts, datasource_workspaces, datasources, workspaces, users CASCADE")
        actor =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject)" +
                        " VALUES ('agent@example.com', 'Agent', 'google', 'sub-agent') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
        workspace =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO workspaces (name, display_name) VALUES ('acme', 'Acme') RETURNING id",
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
        DatasourceRepository(jdbc).create(datasource, testEncryptor().encrypt("p", datasource.name), actor)
    }

    @AfterEach
    fun tearDown() {
        h2.createStatement().use { it.execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `a fact with evidence is observed, summarised from the probe's first rows, and fingerprinted per table`() {
        val stored =
            recorder.record(
                datasource,
                request(
                    evidenceSql = "SELECT amount_cents FROM orders ORDER BY id",
                ),
            )

        assertAll(
            { stored.trust shouldBe LearnedFactTrust.OBSERVED },
            { stored.evidenceSummary shouldBe "AMOUNT_CENTS=1250 | AMOUNT_CENTS=300" },
            { stored.refs shouldContainExactly listOf(FactRef(null, "ORDERS", "AMOUNT_CENTS")) },
            // §3.2: the stored fingerprint names the table and carries the digest introspection yields NOW.
            { stored.schemaFingerprint shouldStartWith "ORDERS=" },
            {
                SchemaFingerprint.segment(stored.schemaFingerprint, "ORDERS") shouldBe
                    SchemaFingerprint.of(SchemaIntrospector(registry).columns(datasource, "ORDERS"))
            },
            { stored.recordedIn shouldBe workspace },
            { stored.workspaceId.shouldBeNull() },
        )
    }

    @Test
    fun `a fact without evidence is asserted, and a given summary is kept over the probe's`() {
        val asserted = recorder.record(datasource, request(evidenceSql = null))
        asserted.trust shouldBe LearnedFactTrust.ASSERTED
        asserted.evidenceSummary.shouldBeNull()

        val given =
            recorder.record(
                datasource,
                request(
                    fact = "placed_at is naive local time (no offset)",
                    column = "PLACED_AT",
                    evidenceSql = "SELECT placed_at FROM orders",
                    evidenceSummary = "two rows, wall-clock values",
                ),
            )
        given.evidenceSummary shouldBe "two rows, wall-clock values"
    }

    @Test
    fun `a WORKSPACE fact binds to the recording workspace`() {
        val stored =
            recorder.record(
                datasource,
                request(scope = LearnedFactScope.WORKSPACE, kind = "definition", fact = "revenue = SUM(amount_cents)"),
            )
        stored.workspaceId shouldBe workspace
    }

    @Test
    fun `an unknown kind, or a kind under the wrong scope, is refused before anything is read`() {
        shouldThrow<DatapipelinesException> { recorder.record(datasource, request(kind = "type")) }.code shouldBe
            SemanticsErrorCodes.KIND_INVALID
        shouldThrow<DatapipelinesException> {
            recorder.record(datasource, request(scope = LearnedFactScope.WORKSPACE, kind = "unit"))
        }.code shouldBe SemanticsErrorCodes.KIND_INVALID
    }

    @Test
    fun `the fact window, an empty ref list and an over-long summary are shape refusals`() {
        shouldThrow<DatapipelinesException> { recorder.record(datasource, request(fact = "short")) }.details["field"] shouldBe "fact"
        shouldThrow<DatapipelinesException> { recorder.record(datasource, request(refs = emptyList())) }.details["field"] shouldBe "refs"
        shouldThrow<DatapipelinesException> {
            recorder.record(datasource, request(evidenceSummary = "x".repeat(301)))
        }.code shouldBe SemanticsErrorCodes.FACT_INVALID
    }

    @Test
    fun `a ref naming an unknown table or column is refused - the store never starts stale`() {
        val table =
            shouldThrow<DatapipelinesException> { recorder.record(datasource, request(refs = listOf(FactRef(null, "SHIPMENTS", null)))) }
        val column = shouldThrow<DatapipelinesException> { recorder.record(datasource, request(column = "AMOUNT")) }
        assertAll(
            { table.code shouldBe SemanticsErrorCodes.REF_UNRESOLVED },
            { column.code shouldBe SemanticsErrorCodes.REF_UNRESOLVED },
            { column.message shouldContain "AMOUNT" },
            { repository.findVisibleByDatasource(datasource.name, workspace) shouldBe emptyList() },
        )
    }

    @Test
    fun `evidence that is not read-only, names a parameter, or fails against the database refuses the record`() {
        val write = shouldThrow<DatapipelinesException> { recorder.record(datasource, request(evidenceSql = "DELETE FROM orders")) }
        val bound =
            shouldThrow<DatapipelinesException> {
                recorder.record(
                    datasource,
                    request(evidenceSql = "SELECT * FROM orders WHERE id = :id"),
                )
            }
        val failed = shouldThrow<DatapipelinesException> { recorder.record(datasource, request(evidenceSql = "SELECT nope FROM orders")) }
        assertAll(
            { write.code shouldBe SemanticsErrorCodes.EVIDENCE_REFUSED },
            { bound.code shouldBe SemanticsErrorCodes.EVIDENCE_REFUSED },
            { bound.details["parameter"] shouldBe "id" },
            { failed.code shouldBe SemanticsErrorCodes.EVIDENCE_FAILED },
            { failed.details["reason"] shouldBe "execution_failed" },
            { repository.findVisibleByDatasource(datasource.name, workspace) shouldBe emptyList() },
        )
    }

    @Test
    fun `an identical live fact is a duplicate naming the existing row - O-4`() {
        val first = recorder.record(datasource, request())

        val refused = shouldThrow<DatapipelinesException> { recorder.record(datasource, request()) }
        refused.code shouldBe SemanticsErrorCodes.DUPLICATE
        refused.details["existing_id"] shouldBe first.id.toString()
    }

    @Test
    fun `a superseding fact retires its predecessor with reason superseded`() {
        val old = recorder.record(datasource, request(fact = "amount_cents is in dollars (wrong, superseded)"))

        val replacement = recorder.record(datasource, request(supersedes = old, evidenceSql = "SELECT amount_cents FROM orders"))

        val rows = repository.findVisibleByDatasource(datasource.name, workspace, includeRetired = true)
        val retired = rows.single { it.id == old.id }
        assertAll(
            { replacement.supersedes shouldBe old.id },
            { retired.trust shouldBe LearnedFactTrust.RETIRED },
            { retired.retiredReason shouldBe LearnedFactRecorder.SUPERSEDED_REASON },
            { retired.retiredAt.shouldNotBeNull() },
            { repository.findVisibleByDatasource(datasource.name, workspace).map { it.id } shouldContainExactly listOf(replacement.id) },
        )
    }

    private fun request(
        scope: LearnedFactScope = LearnedFactScope.DATASOURCE,
        kind: String = "unit",
        fact: String = "amount_cents is in cents, never dollars",
        column: String? = "AMOUNT_CENTS",
        refs: List<FactRef> = listOf(FactRef(null, "ORDERS", column)),
        evidenceSql: String? = null,
        evidenceSummary: String? = null,
        supersedes: LearnedFact? = null,
    ) = LearnedFactRecorder.Request(
        scope = scope,
        kind = kind,
        fact = fact,
        refs = refs,
        evidenceSql = evidenceSql,
        evidenceSummary = evidenceSummary,
        sourcePipelineId = null,
        sourceVersion = null,
        supersedes = supersedes,
        recordedBy = actor,
        recordedVia = "mcp",
        recordedIn = workspace,
    )

    private companion object {
        const val H2_URL = "jdbc:h2:mem:learned_recorder;DB_CLOSE_DELAY=-1"
    }
}
