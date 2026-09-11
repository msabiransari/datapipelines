package co.datapipelines.datasources.semantics

import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.Fixtures
import co.datapipelines.datasources.JdbcUrlPool
import co.datapipelines.datasources.SchemaIntrospector
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * Design §6 against a REAL table that changes underneath a recorded fact: the fingerprint is
 * taken from live H2 introspection at "record" time, the table is altered, and the next
 * introspection read yields the verdict — `stale` when a referenced column is gone,
 * `needs_review` when the column set changed around a still-resolving ref, unchanged when
 * nothing moved. Nothing re-maps (D-S6): a rename is a drop to this check, by design.
 *
 * The persistence of the mark (the row update on the read path) is the application service's
 * and is proven where the surfaces are; this suite is the DETECTOR, and it can go red: skip the
 * fingerprint recompute in [LearnedFactDrift.againstColumns] and the `needs_review` case fails.
 */
class LearnedFactDriftTest {
    private val h2 = DriverManager.getConnection(H2_URL)
    private val registry = mockk<DatasourceRegistry>()
    private val datasource: Datasource = Fixtures.h2(name = "drift-db", jdbcUrl = H2_URL)
    private val introspector = SchemaIntrospector(registry)

    @BeforeEach
    fun setUp() {
        every { registry.get(datasource.name) } returns datasource
        every { registry.poolFor(datasource) } returns JdbcUrlPool(datasource.jdbcUrl, datasource.name)
        h2.createStatement().use { it.execute("CREATE TABLE events (id INT PRIMARY KEY, occurred_at TIMESTAMP, reading DOUBLE)") }
    }

    @AfterEach
    fun tearDown() {
        h2.createStatement().use { it.execute("DROP ALL OBJECTS") }
    }

    @Test
    fun `a renamed column makes the fact stale naming the column, and an added column makes it needs_review`() {
        val recorded = factOn("OCCURRED_AT")

        val unchanged = LearnedFactDrift.againstColumns(recorded, "EVENTS", null, columns())
        h2.createStatement().use { it.execute("ALTER TABLE events ALTER COLUMN occurred_at RENAME TO happened_at") }
        val renamed = LearnedFactDrift.againstColumns(recorded, "EVENTS", null, columns())

        val onValue = factOn("READING")
        h2.createStatement().use { it.execute("ALTER TABLE events ADD COLUMN unit VARCHAR(8)") }
        val widened = LearnedFactDrift.againstColumns(onValue, "EVENTS", null, columns())

        assertAll(
            { unchanged.trust shouldBe LearnedFactTrust.OBSERVED },
            { unchanged.drift.shouldBeNull() },
            { renamed.trust shouldBe LearnedFactTrust.STALE },
            { renamed.drift shouldBe "column OCCURRED_AT no longer exists" },
            { widened.trust shouldBe LearnedFactTrust.NEEDS_REVIEW },
            { widened.drift shouldBe "table columns changed since this was recorded" },
        )
    }

    @Test
    fun `the check is one-way - a stale fact is never softened, and a listing without columns can only find a missing table`() {
        val stale = factOn("READING").copy(trust = LearnedFactTrust.STALE)
        val softened = LearnedFactDrift.againstColumns(stale, "EVENTS", null, columns())

        val fine = LearnedFactDrift.againstTables(factOn("READING"), setOf("EVENTS"))
        val gone = LearnedFactDrift.againstTables(factOn("READING"), setOf("OTHER"))

        assertAll(
            { softened.trust shouldBe LearnedFactTrust.STALE },
            { fine.trust shouldBe LearnedFactTrust.OBSERVED },
            { gone.trust shouldBe LearnedFactTrust.STALE },
            { gone.drift shouldBe "table EVENTS no longer exists" },
        )
    }

    @Test
    fun `a fact on another table is not this listing's business`() {
        val other = factOn("READING").copy(refs = listOf(FactRef(null, "OTHER", "READING")), schemaFingerprint = "OTHER=deadbeef")
        h2.createStatement().use { it.execute("ALTER TABLE events ADD COLUMN unit VARCHAR(8)") }

        LearnedFactDrift.againstColumns(other, "EVENTS", null, columns()).trust shouldBe LearnedFactTrust.OBSERVED
    }

    private fun columns() = introspector.columns(datasource, "EVENTS")

    /** A fact "recorded" now: its fingerprint is what live introspection says at this moment. */
    private fun factOn(column: String): LearnedFact =
        LearnedFact(
            id = UUID.randomUUID(),
            scope = LearnedFactScope.DATASOURCE,
            workspaceId = null,
            datasourceName = datasource.name,
            kind = LearnedFactKind.TIME_ZONE,
            fact = "$column is naive local time",
            refs = listOf(FactRef(null, "EVENTS", column)),
            evidenceSql = null,
            evidenceSummary = null,
            trust = LearnedFactTrust.OBSERVED,
            schemaFingerprint = SchemaFingerprint.combine(mapOf("EVENTS" to SchemaFingerprint.of(columns()))),
            recordedBy = UUID.randomUUID(),
            recordedVia = "mcp",
            recordedIn = UUID.randomUUID(),
            sourcePipelineId = null,
            sourceVersion = null,
            recordedAt = Instant.EPOCH,
            verifiedBy = null,
            verifiedAt = null,
            supersedes = null,
            retiredAt = null,
            retiredReason = null,
        )

    private companion object {
        const val H2_URL = "jdbc:h2:mem:learned_drift;DB_CLOSE_DELAY=-1"
    }
}
