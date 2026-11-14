package co.datapipelines.lake.maintenance.keep

import co.datapipelines.lake.maintenance.keep.KeepFixtures.UTC
import co.datapipelines.lake.maintenance.keep.KeepFixtures.days
import co.datapipelines.lake.profiles.KeepPeriod
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.iceberg.BaseTable
import org.apache.iceberg.DataFile
import org.apache.iceberg.DataFiles
import org.apache.iceberg.LocationProviders
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.TableMetadata
import org.apache.iceberg.TableMetadataParser
import org.apache.iceberg.TableOperations
import org.apache.iceberg.inmemory.InMemoryFileIO
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.io.LocationProvider
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * #960 T9 (record row K1): Iceberg honours a keep's end. Integration with Iceberg 1.12.0 in process: a real table
 * (in-memory metadata and files, [InMemoryFileIO]), the plan [KeepReconcile] computes, committed through the same
 * [KeepTags.update] the run commits, and Iceberg's own `expireSnapshots()`.
 *
 * **The clock.** `RemoveSnapshots` reads `System.currentTimeMillis()` once and keeps a tag while `now − its snapshot's
 * timestamp ≤ maxRefAgeMs` (1.12.0 bytecode, `computeRetainedRefs`). So "day d" is simulated by back-dating every
 * snapshot timestamp and snapshot-log entry by `d` days (rewritten in the metadata JSON): Iceberg then measures, on
 * its own clock, the age the snapshot would have on day d, and its own rule decides. Nothing in Iceberg is stubbed.
 */
class KeepTagsExpiryTest {
    private val io = InMemoryFileIO()
    private val ops = MemoryOps(io)
    private val schema = Schema(Types.NestedField.required(1, "id", Types.LongType.get()))
    private val spec = PartitionSpec.unpartitioned()
    private val mapper = ObjectMapper()

    private fun dataFile(name: String): DataFile {
        val path = "$LOCATION/data/$name.parquet"
        io.addFile(path, byteArrayOf(1, 2, 3))
        return DataFiles
            .builder(spec)
            .withPath(path)
            .withFileSizeInBytes(3)
            .withRecordCount(1)
            .build()
    }

    /** Rewrites every snapshot's timestamp and its snapshot-log entries to [times] (snapshot id to instant). */
    private fun stamp(times: Map<Long, Instant>) {
        val json = mapper.readTree(TableMetadataParser.toJson(requireNotNull(ops.current())))
        listOf("snapshots", "snapshot-log").forEach { array ->
            json.get(array).forEach { node ->
                val entry = node as ObjectNode
                entry.put("timestamp-ms", times.getValue(entry.get("snapshot-id").asLong()).toEpochMilli())
            }
        }
        ops.replace(TableMetadataParser.fromJson(json))
    }

    /** Simulates [day] (an instant after the base clock [base]): every stamp moves back by `day − the real now`. */
    private fun simulate(
        table: Table,
        base: Map<Long, Instant>,
        day: Instant,
    ) {
        val shift = Duration.between(Instant.now(), day)
        stamp(base.mapValues { it.value - shift })
        table.refresh()
    }

    @Test
    fun `a snapshot 100 days old tagged today for 90 days survives an expiry on day 89 and goes at the first after day 90`() {
        ops.replace(TableMetadata.newTableMetadata(schema, spec, LOCATION, emptyMap()))
        val table = BaseTable(ops, "keep.t9")
        val held = dataFile("held")
        table.newAppend().appendFile(held).commit()
        val old = table.currentSnapshot().snapshotId()
        table
            .newOverwrite()
            .deleteFile(held)
            .addFile(dataFile("current"))
            .commit()
        val current = table.currentSnapshot().snapshotId()

        val now = Instant.now().truncatedTo(ChronoUnit.MILLIS) // the metadata stores milliseconds
        val midnight =
            now
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        val base = mapOf(old to now - Duration.ofDays(100), current to midnight + Duration.ofMillis(1))
        stamp(base)
        table.refresh()

        val plan = KeepReconcile.plan(listOf(days(KeepPeriod.DAY, 90)), UTC, now - Duration.ofDays(200), KeepHistories.of(table), now)
        val today = plan.creates.maxBy { it.end }
        today.snapshotId shouldBe old
        today.end shouldBe midnight + Duration.ofDays(90)
        KeepTags.update(table, plan).commit()
        table.refresh()
        table.refs()[today.name].shouldNotBeNull().maxRefAgeMs() shouldBe Duration.between(base.getValue(old), today.end).toMillis()

        simulate(table, base, today.end - Duration.ofDays(1))
        table.expireSnapshots().expireOlderThan(System.currentTimeMillis()).commit()
        table.refresh()
        table.refs()[today.name].shouldNotBeNull().snapshotId() shouldBe old
        table.snapshot(old).shouldNotBeNull()
        io.fileExists(held.location()) shouldBe true

        simulate(table, base, today.end + Duration.ofMinutes(1))
        table.expireSnapshots().expireOlderThan(System.currentTimeMillis()).commit()
        table.refresh()
        table.refs()[today.name].shouldBeNull()
        table.snapshot(old).shouldBeNull()
        table.currentSnapshot().snapshotId() shouldBe current
    }

    /**
     * Table operations over one in-memory metadata value. Every commit and replace stores it as if written to a new
     * numbered metadata file (re-read with its location, as a catalog would): Iceberg carries the pending changes of a
     * metadata value that has no file location into the next commit, which would drop the append's snapshot-log entry
     * as an "intermediate" snapshot.
     */
    private class MemoryOps(
        private val io: FileIO,
    ) : TableOperations {
        private var metadata: TableMetadata? = null
        private var version = 0

        fun replace(next: TableMetadata) {
            metadata = written(next)
        }

        private fun written(next: TableMetadata): TableMetadata {
            version += 1
            return TableMetadataParser.fromJson("$LOCATION/metadata/v$version.metadata.json", TableMetadataParser.toJson(next))
        }

        override fun current(): TableMetadata? = metadata

        override fun refresh(): TableMetadata? = metadata

        override fun commit(
            base: TableMetadata?,
            metadata: TableMetadata,
        ) {
            check(base === this.metadata) { "the table moved under the commit" }
            this.metadata = written(metadata)
        }

        override fun io(): FileIO = io

        override fun metadataFileLocation(fileName: String): String = "$LOCATION/metadata/$fileName"

        override fun locationProvider(): LocationProvider {
            val current = requireNotNull(metadata)
            return LocationProviders.locationsFor(current.location(), current.properties())
        }
    }

    private companion object {
        const val LOCATION = "mem://lake/keep/t9"
    }
}
