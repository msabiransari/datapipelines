package co.datapipelines.lake.append

import co.datapipelines.lake.LakeCatalogs
import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.pipeline.LakeAppendInput
import org.apache.iceberg.DataFile
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.SortOrder
import org.apache.iceberg.Table
import org.apache.iceberg.data.Record
import org.apache.iceberg.data.parquet.GenericParquetReaders
import org.apache.iceberg.parquet.Parquet
import org.apache.iceberg.parquet.ParquetValueReader
import org.apache.parquet.format.Util
import org.apache.parquet.schema.MessageType
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID
import java.util.function.Function

/** Real stable DuckDB cursors and real S3 Parquet files; no row getter or Iceberg writer is mocked. */
internal class ParquetWriterHarness {
    val storage = LakeMinio.storage(BUCKET)
    private val catalogs = LakeCatalogs(LakeProperties(enabled = true, storage = storage), LakeTestDb.catalogDatabase())

    fun withTable(
        schema: Schema,
        spec: PartitionSpec = PartitionSpec.unpartitioned(),
        sortOrder: SortOrder = SortOrder.unsorted(),
        block: (Table) -> Unit,
    ) {
        val namespace = LakeTestDb.lease(LeaseKind.NAMESPACE, NAMESPACE)
        try {
            catalogs.open(namespace).use { it.createNamespace(NAMESPACE) }
        } finally {
            LakeTestDb.leases.release(namespace.operationId)
        }
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "$NAMESPACE.orders")
        try {
            catalogs.open(lease).use { session ->
                block(session.createTable(NAMESPACE, "orders", UUID.randomUUID(), schema, spec, sortOrder, 1))
            }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    fun withInput(
        sql: String,
        block: (NativeInput) -> Unit,
    ) {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET memory_limit = '64MB'")
                statement.execute("SET threads = 1")
                statement.execute("SET TimeZone = 'UTC'")
                statement.execute("SET autoinstall_known_extensions = false")
                statement.execute("SET autoload_known_extensions = false")
                statement.execute("SET enable_external_access = false")
                statement.execute("CREATE TABLE append_input799 AS $sql")
            }
            block(NativeInput(connection))
        }
    }

    fun dataKeys(table: Table): List<String> =
        LakeMinio.client().use { client ->
            client
                .listObjectsV2 { it.bucket(BUCKET).prefix("${table.location().removePrefix("s3://$BUCKET/")}/data/") }
                .contents()
                .map { it.key() }
        }

    fun read(
        table: Table,
        files: List<DataFile>,
    ): List<Record> =
        files.flatMap { file ->
            Parquet
                .read(table.io().newInputFile(file.location()))
                .project(table.schema())
                .createReaderFunc(
                    Function<MessageType, ParquetValueReader<*>> { fileSchema ->
                        GenericParquetReaders.buildReader(table.schema(), fileSchema)
                    },
                ).build<Record>()
                .use { records -> records.map { it.copy() }.toList() }
        }

    /** Decode the actual on-disk footer, independently of the writer's configuration. Fixtures are small. */
    fun codecs(
        table: Table,
        file: DataFile,
    ): Set<String> {
        val bytes =
            table
                .io()
                .newInputFile(file.location())
                .newStream()
                .use { it.readBytes() }
        val footerBytes =
            ByteBuffer
                .wrap(bytes, bytes.size - FOOTER_TRAILER_BYTES, Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
        val metadata = Util.readFileMetaData(ByteArrayInputStream(bytes, bytes.size - FOOTER_TRAILER_BYTES - footerBytes, footerBytes))
        return metadata.row_groups
            .flatMap { it.columns }
            .map { it.meta_data.codec.name }
            .toSet()
    }

    class NativeInput(
        private val connection: Connection,
    ) : LakeAppendInput {
        var scans: Int = 0
            private set

        override fun scan(consume: (ResultSet) -> Unit) {
            scans++
            connection.createStatement().use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statement.executeQuery("SELECT * FROM append_input799").use(consume)
            }
        }
    }

    private companion object {
        const val BUCKET = "dp-lake-parquet799"
        const val NAMESPACE = "parquet799"
        const val QUERY_TIMEOUT_SECONDS = 10
        const val FOOTER_TRAILER_BYTES = 8
    }
}
