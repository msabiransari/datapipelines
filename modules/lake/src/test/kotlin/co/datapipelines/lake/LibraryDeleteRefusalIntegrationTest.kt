package co.datapipelines.lake

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.apache.iceberg.DataFile
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
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** E11 #777 F1 replay: Iceberg itself tries cleanup after a manifest merge; the production IO refuses it. */
class LibraryDeleteRefusalIntegrationTest {
    private val storage = LakeMinio.storage(BUCKET)
    private val catalogs = LakeCatalogs(LakeProperties(enabled = true, storage = storage), LakeTestDb.catalogDatabase())

    @BeforeEach
    fun clean() {
        LakeTestDb.reset()
        val namespace = LakeTestDb.lease(LeaseKind.NAMESPACE, NAMESPACE)
        try {
            catalogs.open(namespace).use { it.createNamespace(NAMESPACE) }
        } finally {
            LakeTestDb.leases.release(namespace.operationId)
        }
    }

    @Test
    fun `the real catalog FileIO records and refuses a deletion without removing the object`() {
        withTable { table ->
            val data = writeParquet(table)
            val lines =
                capture {
                    shouldThrow<IllegalStateException> { table.io().deleteFile(data.location()) }
                }
            lines.single().formattedMessage.contains("event=lake.file.delete_refused") shouldBe true
            lines.single().argumentArray.toList() shouldBe listOf(data.location(), "LibraryDeleteRefusedException")
            table.io().newInputFile(data.location()).exists() shouldBe true
        }
    }

    @Test
    fun `the default hundredth append merges manifests and every library handed key still exists`() {
        withTable { table ->
            table.properties()[TableProperties.MANIFEST_MIN_MERGE_COUNT] shouldBe null
            TableProperties.MANIFEST_MIN_MERGE_COUNT_DEFAULT shouldBe APPENDS
            val lines =
                capture {
                    repeat(APPENDS) {
                        val data = writeParquet(table)
                        table.newTransaction().let { transaction ->
                            transaction.newAppend().appendFile(data).commit()
                            transaction.commitTransaction()
                        }
                    }
                }
            val keys = lines.map { it.argumentArray.first().toString() }
            (keys.isNotEmpty()) shouldBe true
            keys.all { "/metadata/" in it } shouldBe true
            keys.forEach { table.io().newInputFile(it).exists() shouldBe true }
            table.refresh()
            table.currentSnapshot().summary()["total-records"] shouldBe APPENDS.toString()
            val revisions =
                LakeTestDb.jdbc.queryForObject(
                    "SELECT count(*) FROM lake_table_revisions WHERE namespace = ? AND table_name = 'orders'",
                    Int::class.java,
                    NAMESPACE,
                )
            revisions shouldBe APPENDS + 1
            println("(799 E11 delete refusal) appends=$APPENDS, revisions=$revisions, refused=${keys.size}, all keys retained")
        }
    }

    private fun withTable(block: (Table) -> Unit) {
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "$NAMESPACE.orders")
        try {
            catalogs.open(lease).use { session ->
                val table =
                    session.createTable(
                        NAMESPACE,
                        "orders",
                        UUID.randomUUID(),
                        Schema(Types.NestedField.required(1, "id", Types.LongType.get())),
                        PartitionSpec.unpartitioned(),
                        SortOrder.unsorted(),
                        1,
                    )
                block(table)
            }
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    private fun writeParquet(table: Table): DataFile {
        val writer =
            Parquet
                .writeData(table.io().newOutputFile("${table.location()}/data/${UUID.randomUUID()}.parquet"))
                .schema(table.schema())
                .withSpec(table.spec())
                .createWriterFunc(GenericParquetWriter::create)
                .build<Record>()
        writer.use { it.write(GenericRecord.create(table.schema()).copy(mapOf("id" to 1L))) }
        return writer.toDataFile()
    }

    private fun capture(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(DeferringFileIO::class.java) as Logger
        val appender = ConcurrentAppender().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.events.toList()
    }

    private class ConcurrentAppender : AppenderBase<ILoggingEvent>() {
        val events = ConcurrentLinkedQueue<ILoggingEvent>()

        override fun append(event: ILoggingEvent) {
            events += event
        }
    }

    private companion object {
        const val BUCKET = "dp-lake-delete-refusal799"
        const val NAMESPACE = "delete_refusal799"
        const val APPENDS = 100
    }
}
