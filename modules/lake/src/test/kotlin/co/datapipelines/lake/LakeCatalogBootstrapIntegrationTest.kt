package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.iceberg.DataFile
import org.apache.iceberg.HasTableOperations
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.Table
import org.apache.iceberg.TableProperties
import org.apache.iceberg.data.GenericRecord
import org.apache.iceberg.data.Record
import org.apache.iceberg.data.parquet.GenericParquetWriter
import org.apache.iceberg.parquet.Parquet
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The catalog end to end on the real stack (#785 A1–A4): V53's tables and triggers in Postgres, Iceberg's
 * `JdbcCatalog` through [LakeCatalogs] (fenced pool, deferred deletes, no DDL), `S3FileIO` against MinIO with
 * throwaway credentials, and a Parquet append through the Hadoop runtime (E1 F16: every Parquet write builds a
 * Hadoop `Configuration`, so the runtime jars must be on the classpath).
 */
class LakeCatalogBootstrapIntegrationTest {
    private val jdbc = LakeTestDb.jdbc
    private val storage = LakeMinio.storage(bucket = BUCKET)
    private val properties = LakeProperties(enabled = true, storage = storage)
    private val catalogs = LakeCatalogs(properties, LakeTestDb.catalogDatabase())

    @BeforeEach
    fun clean() {
        LakeTestDb.reset()
    }

    @Test
    fun `a namespace and a table are created at root-ns-tableId with the closed property list`() {
        val operation = UUID.randomUUID()
        val tableId = UUID.randomUUID()
        createTable(operation, tableId)

        // Read back through a fresh session: a Table's operations live only as long as the session's pool.
        catalogs.open(currentTableLease(operation)).use { session ->
            val table = session.loadTable("sales", "orders")
            table.location() shouldBe "s3://$BUCKET/lake/sales/$tableId"
            val expected = LakeTableProperties.create(DEFINITION_VERSION, emptyList()) - TableProperties.FORMAT_VERSION
            table.properties().entries.map { it.toPair() } shouldContainAll expected.entries.map { it.toPair() }
            // format-version is a reserved key: Iceberg applies it to the metadata instead of storing it as a property.
            (table as HasTableOperations).operations().current().formatVersion() shouldBe 2
        }
        jdbc.queryForObject("SELECT iceberg_type FROM iceberg_tables", String::class.java) shouldBe "TABLE"
        jdbc.queryForObject(
            "SELECT property_value FROM iceberg_namespace_properties WHERE namespace = 'sales' AND property_key = 'exists'",
            String::class.java,
        ) shouldBe "true"
    }

    @Test
    fun `a Parquet append commits under the lease and logs one revision per pointer move with the operation id`() {
        val operation = UUID.randomUUID()
        val tableId = UUID.randomUUID()
        createTable(operation, tableId)
        val lease = currentTableLease(operation)

        catalogs.open(lease).use { session ->
            val table = session.loadTable("sales", "orders")
            val file = writeParquet(table, listOf(1L to "a", 2L to "b", 3L to "c"))
            session.write { table.newAppend().appendFile(file).commit() }
            val snapshot = session.loadTable("sales", "orders").currentSnapshot().shouldNotBeNull()
            snapshot.summary()["added-records"] shouldBe "3"
        }

        val revisions = jdbc.queryForList("SELECT operation_id, superseded_at FROM lake_table_revisions ORDER BY seq")
        revisions.size shouldBe 2
        revisions.forEach { it["operation_id"] shouldBe operation }
        revisions.count { it["superseded_at"] == null } shouldBe 1
        // The objects landed under the table's own prefix in the bucket.
        LakeMinio.client().use { s3 ->
            val keys = s3.listObjectsV2 { it.bucket(BUCKET).prefix("lake/sales/$tableId/") }.contents().map { it.key() }
            keys.shouldNotBeEmpty()
            keys.any { it.endsWith(".parquet") } shouldBe true
            keys.any { it.contains("/metadata/") } shouldBe true
        }
    }

    @Test
    fun `an unleased or stale handle is fenced and nothing lands in the catalog`() {
        val operation = UUID.randomUUID()
        createTable(operation, UUID.randomUUID())
        val fabricated = LeaseHandle(LeaseKind.TABLE, "sales.items", UUID.randomUUID(), UUID.randomUUID())
        catalogs.open(fabricated).use { session ->
            val refused =
                shouldThrow<LakeCatalogException.Fenced> {
                    session.createTable("sales", "items", UUID.randomUUID(), SCHEMA, UNPARTITIONED, UNSORTED, DEFINITION_VERSION)
                }
            refused.code shouldBe "lake.catalog.fenced"
            refused.message.orEmpty() shouldNotContain fabricated.token.toString()
        }
        val lease = currentTableLease(operation)
        jdbc.update("UPDATE lake_leases SET expires_at = now() - interval '1 second' WHERE kind = 'table'")
        catalogs.open(lease).use { session ->
            val table = session.loadTable("sales", "orders")
            shouldThrow<LakeCatalogException.Fenced> { session.write { table.updateProperties().set("k", "v").commit() } }
        }
        jdbc.queryForObject("SELECT count(*) FROM iceberg_tables", Long::class.java) shouldBe 1L
        jdbc.queryForObject("SELECT count(*) FROM lake_table_revisions", Long::class.java) shouldBe 1L
    }

    @Test
    fun `no credential reaches a toString`() {
        storage.toString() shouldNotContain LakeMinio.secret
        properties.toString() shouldNotContain LakeMinio.secret
        LakeTestDb.catalogDatabase().toString() shouldNotContain LakeTestDb.password
    }

    private fun createTable(
        operation: UUID,
        tableId: UUID,
    ) {
        LakeTestDb.lease(LeaseKind.NAMESPACE, "sales", operationId = operation).let { ns ->
            catalogs.open(ns).use { it.createNamespace("sales") }
        }
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "sales.orders", operationId = operation)
        catalogs.open(lease).use { session ->
            session.createTable("sales", "orders", tableId, SCHEMA, PartitionSpec.unpartitioned(), UNSORTED, DEFINITION_VERSION)
        }
    }

    /** The table lease [operation] already holds, read back by its identity (kind, name, operation). */
    private fun currentTableLease(operation: UUID): LeaseHandle =
        LeaseHandle(
            LeaseKind.TABLE,
            "sales.orders",
            operation,
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT token FROM lake_leases WHERE kind = 'table' AND name = 'sales.orders' AND operation_id = ?",
                    UUID::class.java,
                    operation,
                ),
            ),
        )

    private fun writeParquet(
        table: Table,
        rows: List<Pair<Long, String>>,
    ): DataFile {
        val writer =
            Parquet
                .writeData(table.io().newOutputFile("${table.location()}/data/${UUID.randomUUID()}.parquet"))
                .schema(table.schema())
                .withSpec(table.spec())
                .createWriterFunc(GenericParquetWriter::create)
                .overwrite()
                .build<Record>()
        writer.use { w ->
            rows.forEach { (id, label) ->
                w.write(GenericRecord.create(table.schema()).copy(mapOf("id" to id, "label" to label)))
            }
        }
        return writer.toDataFile()
    }

    private companion object {
        const val BUCKET = "dp-lake-bootstrap"
        const val DEFINITION_VERSION = 1L
        val UNSORTED: SortOrder = SortOrder.unsorted()
        val UNPARTITIONED: PartitionSpec = PartitionSpec.unpartitioned()
        val SCHEMA =
            Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "label", Types.StringType.get()),
            )
    }
}
