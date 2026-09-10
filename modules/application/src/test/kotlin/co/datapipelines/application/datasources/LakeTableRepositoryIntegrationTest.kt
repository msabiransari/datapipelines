package co.datapipelines.application.datasources

import co.datapipelines.application.SharedPostgres
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * [LakeTableRepository] against a real Postgres (metadata-db §4.15, the module's 083 §D rule):
 * the properties under test are statements ABOUT Postgres — a `TEXT[]` round-trip through a
 * real `java.sql.Array`, array equality in the DELETE's predicate, `ON CONFLICT (…) DO NOTHING`
 * with a `RETURNING` clause, and the named UNIQUE violation the service maps to
 * `datasource.lake_table_duplicate`. A mocked template would assert that this module passes
 * strings to Spring, which nobody doubts.
 *
 * Cleans the tables it touches (`TRUNCATE … CASCADE` plus re-seed) — the SharedPostgres
 * discipline every sibling spec follows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LakeTableRepositoryIntegrationTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: LakeTableRepository

    private lateinit var userId: UUID

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        repository = LakeTableRepository(jdbc)
    }

    @BeforeEach
    fun clean() {
        jdbc.update("TRUNCATE lake_tables, datasources, users CASCADE", emptyMap<String, Any>())
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) " +
                "VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by) " +
                "VALUES ('sample-lake', 'Sample lake', 'LAKE', 'jdbc:duckdb:', 'none', :actor)",
            mapOf("actor" to userId),
        )
    }

    private fun registration(
        namespace: List<String> = listOf("nyc", "mobility"),
        name: String = "hvfhv_zone_day",
        format: LakeTableFormat = LakeTableFormat.PARQUET,
        location: String = "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet",
        partitionColumn: String? = null,
    ) = LakeTableRegistration(namespace, name, format, location, partitionColumn)

    @Test
    fun `insert returns the stored row and list reads it back, namespace array intact`() {
        val inserted =
            repository.insert("sample-lake", registration(partitionColumn = "pickup_date"), userId)

        assertAll(
            { inserted.datasourceId shouldBe "sample-lake" },
            { inserted.namespace shouldContainExactly listOf("nyc", "mobility") },
            { inserted.format shouldBe LakeTableFormat.PARQUET },
            { inserted.partitionColumn shouldBe "pickup_date" },
            { inserted.registeredBy shouldBe userId },
        )

        val listed = repository.findByDatasource("sample-lake")
        listed shouldContainExactly listOf(inserted)
    }

    @Test
    fun `the named UNIQUE rejects a re-registered triple - the violation the service maps to 409`() {
        repository.insert("sample-lake", registration(), userId)

        // A different location under the same triple is still a duplicate: the triple IS the
        // table (metadata-db §4.15).
        val violation =
            shouldThrow<DuplicateKeyException> {
                repository.insert("sample-lake", registration(location = "s3://other-bucket/x.parquet"), userId)
            }
        violation.message!!.contains("uq_lake_tables_datasource_namespace_name") shouldBe true
    }

    @Test
    fun `the same triple is legal on a different datasource or under a different namespace`() {
        jdbc.update(
            "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by) " +
                "VALUES ('other-lake', 'Other lake', 'LAKE', 'jdbc:duckdb:', 'none', :actor)",
            mapOf("actor" to userId),
        )
        repository.insert("sample-lake", registration(), userId)
        repository.insert("other-lake", registration(), userId)
        repository.insert("sample-lake", registration(namespace = listOf("nyc")), userId)

        repository.findByDatasource("sample-lake").size shouldBe 2
    }

    @Test
    fun `insertIfAbsent is idempotent - the import path reports rather than fails`() {
        val first = repository.insertIfAbsent("sample-lake", registration(), userId)
        val second = repository.insertIfAbsent("sample-lake", registration(), userId)

        assertAll(
            { (first != null) shouldBe true },
            { (second == null) shouldBe true },
            { repository.findByDatasource("sample-lake").size shouldBe 1 },
        )
    }

    @Test
    fun `delete matches the exact triple - array equality, not a prefix`() {
        repository.insert("sample-lake", registration(namespace = listOf("nyc")), userId)
        repository.insert("sample-lake", registration(namespace = listOf("nyc", "mobility")), userId)

        // Deleting ["nyc"] must NOT touch ["nyc","mobility"] — array equality is the whole point.
        repository.delete("sample-lake", listOf("nyc"), "hvfhv_zone_day") shouldBe true
        repository.delete("sample-lake", listOf("nyc"), "hvfhv_zone_day") shouldBe false

        repository.findByDatasource("sample-lake").map { it.namespace } shouldContainExactly listOf(listOf("nyc", "mobility"))
    }

    @Test
    fun `the format CHECK backstops a row written by hand`() {
        shouldThrow<Exception> {
            jdbc.update(
                "INSERT INTO lake_tables (datasource_id, namespace, name, format, location, registered_by) " +
                    "VALUES ('sample-lake', ARRAY['nyc'], 't', 'delta', 's3://b/x', :actor)",
                mapOf("actor" to userId),
            )
        }
    }

    @Test
    fun `recordViewOutcome stores the error and its stamp - then clears both on the healthy transition`() {
        repository.insert("sample-lake", registration(), userId)

        // The failing transition: text lands, last_error_at is stamped (NULL when healthy is
        // the V20 spelling — a healthy row added later needs no backfill).
        repository.recordViewOutcome("sample-lake", listOf("nyc", "mobility"), "hvfhv_zone_day", "IO Error: not parquet") shouldBe
            true
        val broken = repository.findByDatasource("sample-lake").single()
        assertAll(
            { broken.lastError shouldBe "IO Error: not parquet" },
            { broken.lastErrorAt.shouldNotBeNull() },
        )

        // The healing transition: success after error clears BOTH columns — the recorded state
        // is "healthy", not "healthy since it last failed".
        repository.recordViewOutcome("sample-lake", listOf("nyc", "mobility"), "hvfhv_zone_day", null) shouldBe true
        val healed = repository.findByDatasource("sample-lake").single()
        assertAll(
            { healed.lastError shouldBe null },
            { healed.lastErrorAt shouldBe null },
        )

        // An unregistered triple updates nothing — silently, by design (the row vanished
        // mid-pool; an unregistered table has no outcome to carry).
        repository.recordViewOutcome("sample-lake", listOf("nope"), "gone", "x") shouldBe false
    }
}
