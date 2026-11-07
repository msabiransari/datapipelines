package co.datapipelines.application.datasources

import co.datapipelines.application.SharedPostgres
import co.datapipelines.datasources.ManagedLakeTable
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertAll
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID
import javax.sql.DataSource

/**
 * #818 (dp-lake P5, design record §10): [ManagedLakeTableRepository] against a real Postgres, over the shipped V53/V54
 * schema. Catalog rows are written the only way the fence admits, under a live table lease, so the revision trigger
 * runs as in production. Properties under test:
 *
 * - the view location is the CATALOG's `metadata_location`, never `lake_tables.location` (the lagging projection);
 * - a managed row with no catalog row, or no lifecycle row, still comes back, marked unavailable;
 * - the effective status is the stricter of the table's and its namespace's, and a deprecation carries its notice;
 * - only this lake's managed rows: external rows, other datasources, other catalogs and non-TABLE catalog rows stay out.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagedLakeTableRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: ManagedLakeTableRepository
    private lateinit var userId: UUID

    @BeforeAll
    fun connect() {
        dataSource = SharedPostgres.dataSource()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        repository = ManagedLakeTableRepository(jdbc)
    }

    @BeforeEach
    fun clean() {
        // iceberg_tables and lake_table_revisions are not truncated by CASCADE from users; the fence trigger fires
        // on row-level DELETE only, so TRUNCATE clears the catalog without a lease.
        jdbc.update(
            "TRUNCATE iceberg_tables, lake_table_revisions, lake_leases, lake_tables_managed, lake_namespaces, " +
                "lake_tables, datasources, users CASCADE",
            emptyMap<String, Any>(),
        )
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to userId, "email" to "u$userId@example.com", "sub" to "sub-$userId"),
        )
        listOf(LAKE, "other_lake").forEach { name ->
            jdbc.update(
                "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by) " +
                    "VALUES (:name, 'Lake', 'LAKE', 'jdbc:duckdb::memory:', 'none', :actor)",
                mapOf("name" to name, "actor" to userId),
            )
        }
    }

    @Test
    fun `the view location is the catalog's row, not the lagging registry projection`() {
        val ns = namespace("sales")
        lifecycle(ns, "orders")
        registryRow(LAKE, "sales", "orders", location = "s3://dp-lake/lake/sales/orders/metadata/00001-a.metadata.json")
        catalogRow(LAKE, "sales", "orders", "s3://dp-lake/lake/sales/orders/metadata/00002-b.metadata.json")

        val orders = repository.managedTables(LAKE).single()
        assertAll(
            { orders.namespace shouldContainExactly listOf(LAKE, "sales") },
            { orders.metadataLocation shouldBe "s3://dp-lake/lake/sales/orders/metadata/00002-b.metadata.json" },
            { orders.status shouldBe "active" },
            { orders.unavailableReason.shouldBeNull() },
            { orders.deprecationMessage.shouldBeNull() },
        )
    }

    @Test
    fun `a row with no catalog row and a row with no lifecycle row both come back, unavailable`() {
        val ns = namespace("sales")
        lifecycle(ns, "orders")
        registryRow(LAKE, "sales", "orders")
        registryRow(LAKE, "sales", "stray")
        catalogRow(LAKE, "sales", "stray", "s3://dp-lake/lake/sales/stray/metadata/00001-a.metadata.json")

        val byName = repository.managedTables(LAKE).associateBy { it.name }
        assertAll(
            { byName.keys shouldBe setOf("orders", "stray") },
            { byName.getValue("orders").unavailableReason shouldBe ManagedLakeTable.REASON_NO_CATALOG_ROW },
            { byName.getValue("stray").unavailableReason shouldBe ManagedLakeTable.REASON_NO_LIFECYCLE_ROW },
        )
    }

    @Test
    fun `the stricter of table and namespace status wins, and a deprecation carries its notice`() {
        val sales = namespace("sales")
        val replacement = lifecycle(sales, "orders_v2")
        lifecycle(sales, "orders", status = "deprecated", replacementId = replacement, deprecatedAt = "2026-11-03T22:00:00Z")
        val archive = namespace("archive", status = "archived")
        lifecycle(archive, "old_orders")
        val legacy = namespace("legacy", status = "deprecated", deprecatedAt = "2026-11-01T10:00:00Z")
        lifecycle(legacy, "events")
        listOf("sales" to "orders", "sales" to "orders_v2", "archive" to "old_orders", "legacy" to "events").forEach { (n, t) ->
            registryRow(LAKE, n, t)
            catalogRow(LAKE, n, t, "s3://dp-lake/lake/$n/$t/metadata/00001-a.metadata.json")
        }

        val byName = repository.managedTables(LAKE).associateBy { it.name }
        assertAll(
            { byName.getValue("orders").status shouldBe "deprecated" },
            { byName.getValue("orders").unavailableReason.shouldBeNull() },
            {
                byName.getValue("orders").deprecationMessage shouldBe
                    "This table is deprecated since 2026-11-03. Use sales.orders_v2 instead."
            },
            { byName.getValue("old_orders").status shouldBe "archived" },
            { byName.getValue("old_orders").unavailableReason shouldBe "status archived" },
            { byName.getValue("events").status shouldBe "deprecated" },
            { byName.getValue("events").deprecationMessage shouldBe "Its namespace is deprecated since 2026-11-01." },
            { byName.getValue("orders_v2").status shouldBe "active" },
        )
    }

    @Test
    fun `only this lake's managed rows, in tree order, and only TABLE catalog rows of this catalog`() {
        val sales = namespace("sales")
        lifecycle(sales, "orders")
        lifecycle(sales, "customers")
        registryRow(LAKE, "sales", "orders")
        registryRow(LAKE, "sales", "customers")
        // A view-type catalog row of the same name, and another catalog's row: neither is this table's pointer.
        catalogRow(LAKE, "sales", "customers", "s3://dp-lake/x/v.metadata.json", type = "VIEW")
        catalogRow("other_lake", "sales", "orders", "s3://other/orders.metadata.json")
        // An external registration on the same datasource, and the other lake's managed row: not returned.
        jdbc.update(
            "INSERT INTO lake_tables (datasource_id, namespace, name, format, location, registered_by) " +
                "VALUES (:lake, ARRAY['ref'], 'zones', 'parquet', 's3://dp-ref/zones.parquet', :by)",
            mapOf("lake" to LAKE, "by" to userId),
        )
        registryRow("other_lake", "sales", "orders")

        val tables = repository.managedTables(LAKE)
        assertAll(
            { tables.map { it.name } shouldContainExactly listOf("customers", "orders") },
            { tables.map { it.metadataLocation } shouldContainExactly listOf(null, null) },
        )
    }

    // ------------------ fixtures

    private fun namespace(
        name: String,
        status: String = "active",
        deprecatedAt: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO lake_namespaces (id, name, status, deprecated_at, deprecated_by, archived_at, archived_by, created_by) " +
                "VALUES (:id, :name, :status, CAST(:dep AS timestamptz), :depBy, :arch, :archBy, :by)",
            mapOf(
                "id" to id,
                "name" to name,
                "status" to status,
                "dep" to (deprecatedAt ?: if (status != "active") "2026-11-01T00:00:00Z" else null),
                "depBy" to if (status != "active") userId else null,
                "arch" to if (status == "archived") java.sql.Timestamp.from(java.time.Instant.parse("2026-11-02T00:00:00Z")) else null,
                "archBy" to if (status == "archived") userId else null,
                "by" to userId,
            ),
        )
        return id
    }

    private fun lifecycle(
        namespaceId: UUID,
        name: String,
        status: String = "active",
        replacementId: UUID? = null,
        deprecatedAt: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO lake_tables_managed (id, namespace_id, name, status, deprecated_at, deprecated_by, replacement_id, " +
                "policy_json, created_by) VALUES (:id, :ns, :name, :status, CAST(:dep AS timestamptz), :depBy, :rep, '{}', :by)",
            mapOf(
                "id" to id,
                "ns" to namespaceId,
                "name" to name,
                "status" to status,
                "dep" to deprecatedAt,
                "depBy" to deprecatedAt?.let { userId },
                "rep" to replacementId,
                "by" to userId,
            ),
        )
        return id
    }

    private fun registryRow(
        datasource: String,
        namespace: String,
        name: String,
        location: String = "s3://dp-lake/lake/$namespace/$name/metadata/00001-a.metadata.json",
    ) {
        jdbc.update(
            "INSERT INTO lake_tables (datasource_id, namespace, name, format, location, registered_by, origin, revision_seq) " +
                "VALUES (:ds, ARRAY[CAST(:ds AS text), CAST(:ns AS text)], :name, 'iceberg', :loc, :by, 'managed', 1)",
            mapOf("ds" to datasource, "ns" to namespace, "name" to name, "loc" to location, "by" to userId),
        )
    }

    /** One catalog pointer row, written under a live table lease, the only write the V53 fence admits. */
    private fun catalogRow(
        catalog: String,
        namespace: String,
        name: String,
        metadataLocation: String,
        type: String = "TABLE",
    ) {
        val token = UUID.randomUUID()
        val operation = UUID.randomUUID()
        val leaseName = "$namespace.$name"
        jdbc.update(
            "INSERT INTO lake_leases (kind, name, operation_id, token, acquired_at, expires_at, deadline_at, operation, holder_instance) " +
                "VALUES ('table', :name, :op, :token, now(), now() + interval '1 minute', now() + interval '1 minute', 'test', 'test') " +
                "ON CONFLICT (kind, name) DO UPDATE SET operation_id = EXCLUDED.operation_id, token = EXCLUDED.token",
            mapOf("name" to leaseName, "op" to operation, "token" to token),
        )
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("SELECT set_config('dp.lease_token', ?, true), set_config('dp.operation_id', ?, true)").use {
                it.setString(1, token.toString())
                it.setString(2, operation.toString())
                it.executeQuery().close()
            }
            connection
                .prepareStatement(
                    "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, " +
                        "previous_metadata_location, iceberg_type) VALUES (?, ?, ?, ?, NULL, ?)",
                ).use {
                    it.setString(1, catalog)
                    it.setString(2, namespace)
                    it.setString(3, name)
                    it.setString(4, metadataLocation)
                    it.setString(5, type)
                    it.executeUpdate()
                }
            connection.commit()
        }
    }

    private companion object {
        const val LAKE = "lake"
    }
}
