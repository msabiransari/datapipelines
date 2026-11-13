package co.datapipelines.application.lake

import co.datapipelines.application.SharedPostgres
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.NewPipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.pipeline.release.ReleaseFamily
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [LakeReferenceIndexRepository] against a real Postgres (metadata-db §4.41–§4.42, V52; the module's 083 §D rule): the
 * properties are statements ABOUT Postgres — a `TEXT[]` built from a JSON parameter keeping its segment order, the
 * references cascading with their scan row, array equality in the by-table lookup, and the backfill worklist's
 * `NOT EXISTS` over two version tables.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LakeReferenceIndexRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var index: LakeReferenceIndexRepository
    private lateinit var pipelines: PipelineRepository
    private lateinit var owner: UUID

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        index = LakeReferenceIndexRepository(jdbc)
        pipelines = PipelineRepository(jdbc)
    }

    @BeforeEach
    fun clean() {
        jdbc.jdbcTemplate.execute("TRUNCATE lake_reference_scans, pipelines, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE', 'default', 'Default') ON CONFLICT (id) DO NOTHING",
        )
        owner =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO users (email, display_name, provider, provider_subject) " +
                        "VALUES (:email, 'Owner', 'google', :sub) RETURNING id",
                    mapOf("email" to "e778-${UUID.randomUUID()}@example.com", "sub" to "sub-${UUID.randomUUID()}"),
                    UUID::class.java,
                ),
            )
    }

    private fun version(
        entityId: UUID = UUID.randomUUID(),
        version: Int = 1,
        family: ReleaseFamily = ReleaseFamily.PIPELINE,
    ) = ScannedVersion(WORKSPACE, family, entityId, version)

    private fun reference(
        ordinal: Int,
        resolved: LakeTableIdentity?,
        datasource: String = "lake-a",
        nodeId: String? = "read",
        selector: String? = null,
    ) = LakeTableReference(
        ordinal = ordinal,
        nodeId = nodeId,
        selector = selector,
        templateId = "e778/read.sql",
        templateVersion = 1,
        datasourceName = datasource,
        writtenCatalog = null,
        writtenSchema = resolved?.namespace?.last(),
        writtenTable = resolved?.table ?: "bare",
        resolved = resolved,
        timeTravel = false,
        statementIndex = 0,
    )

    private fun scan(
        version: ScannedVersion,
        references: List<LakeTableReference>,
        status: LakeScanStatus = LakeScanStatus.EXACT,
        scannerVersion: Int = 1,
        reasons: List<LakeScanReason> = emptyList(),
    ) = LakeReferenceScan(version, "e778/scanned", status, reasons, scannerVersion, references)

    private val trips = LakeTableIdentity(listOf("nyc", "mobility"), "trips")
    private val events = LakeTableIdentity(listOf("lk"), "events")

    @Test
    fun `a scan round-trips - namespace order, an unresolved row, a selector site`() {
        val v = version()
        val refs =
            listOf(
                reference(0, trips),
                reference(1, null),
                reference(2, events, nodeId = null, selector = "state"),
            )
        index.replace(scan(v, refs, LakeScanStatus.PARTIAL, reasons = listOf(LakeScanReason("node:read", "if", "<#if x>"))))

        index.findScan(v) shouldBe LakeReferenceIndexRepository.StoredScan("e778/scanned", LakeScanStatus.PARTIAL, 1)
        index.references(v) shouldContainExactly refs
        jdbc.queryForObject(
            "SELECT reasons_json::text FROM lake_reference_scans WHERE entity_id = :id",
            mapOf("id" to v.entityId),
            String::class.java,
        ) shouldBe """[{"kind": "if", "site": "node:read", "detail": "<#if x>"}]"""
    }

    @Test
    fun `a re-scan by a newer scanner replaces the rows - none of the old references survive`() {
        val v = version()
        index.replace(scan(v, listOf(reference(0, trips), reference(1, events))))
        index.replace(scan(v, listOf(reference(0, events)), scannerVersion = 2))

        index.findScan(v)?.scannerVersion shouldBe 2
        index.references(v) shouldContainExactly listOf(reference(0, events))
        jdbc.queryForObject("SELECT count(*) FROM lake_table_references", emptyMap<String, Any>(), Int::class.java) shouldBe 1
    }

    @Test
    fun `the by-table lookup finds every version reading the table, on that datasource only`() {
        val a = version()
        val b = version(family = ReleaseFamily.PARAMETER_SET)
        val elsewhere = version()
        index.replace(scan(a, listOf(reference(0, trips), reference(1, events))))
        index.replace(scan(b, listOf(reference(0, trips, nodeId = null, selector = "zone"))))
        index.replace(scan(elsewhere, listOf(reference(0, trips, datasource = "lake-b"))))

        val onA = index.versionsReading("lake-a", trips)
        onA.size shouldBeGreaterThanOrEqual 1
        onA.toSet() shouldBe setOf(a, b)
        index.versionsReading("lake-b", trips) shouldContainExactly listOf(elsewhere)
        index.versionsReading("lake-a", events) shouldContainExactly listOf(a)
        // The array compares whole and in order: a prefix or a reordering is another table.
        index.versionsReading("lake-a", LakeTableIdentity(listOf("mobility", "nyc"), "trips")).shouldBeEmpty()
        index.versionsReading("lake-a", LakeTableIdentity(listOf("nyc"), "trips")).shouldBeEmpty()
    }

    @Test
    fun `the backfill worklist holds released versions with no scan row or an older scanner's`() {
        val released = createReleasedPipeline("e778/released_a")
        val scanned = createReleasedPipeline("e778/released_b")
        val stale = createReleasedPipeline("e778/released_c")
        index.replace(scan(version(scanned), emptyList(), scannerVersion = 1))
        index.replace(scan(version(stale), emptyList(), scannerVersion = 1))

        val worklist = index.unscannedReleased(scannerVersion = 2, limit = 10)
        worklist shouldContain version(released)
        worklist shouldContain version(stale)

        val current = index.unscannedReleased(scannerVersion = 1, limit = 10)
        current shouldContain version(released)
        current shouldNotContain version(scanned)
        current shouldNotContain version(stale)
        index.unscannedReleased(scannerVersion = 1, limit = 0).shouldBeEmpty()
    }

    private fun createReleasedPipeline(name: String): UUID {
        val body =
            """{"schema_version":1,"name":"$name","display_name":"E778","description":"d","parameters":{},""" +
                """"settings":{"tempdb":{"engine":"H2"}},""" +
                """"nodes":[{"id":"read","type":"DQL","source":"lake-a","template":{"id":"e778/read.sql","version":1},"depends_on":[]}]}"""
        val pipeline = PipelineDeserializer().readOrThrow(body)
        return pipelines
            .create(WORKSPACE, NewPipeline.from(pipeline, ownerId = owner), body, owner, CreateLifecycle.RELEASED, WriteSurface.SESSION)
            .id
    }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
    }
}
