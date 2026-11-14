package co.datapipelines.lake.maintenance.cleanup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.io.InputFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Cleanup owns each metadata client, including when a read fails before parsing can finish (#962 review F1). */
class CleanupReadsIntegrationTest {
    private val h = CleanupHarness()
    private val clients = mutableListOf<TrackedIo>()

    @BeforeEach
    fun reset() = h.reset()

    @AfterEach
    fun close() {
        clients.filter { it.closes == 0 }.forEach { it.close() }
        h.close()
    }

    @Test
    fun `the current metadata read closes its client exactly once`() {
        val table = h.table("metadata_close")
        val (snapshot, _) = h.append(table)

        val metadata = reads().metadata(table)

        metadata?.currentSnapshot()?.snapshotId() shouldBe snapshot
        clients.size shouldBe 1
        clients.single().closes shouldBe 1
    }

    @Test
    fun `every protected revision read closes its own client`() {
        val table = h.table("revisions_close")
        h.append(table)
        h.append(table)

        val revisions = reads().protectedRevisions(table, h.m.dbNow(), emptySet())

        revisions.size shouldBe 2
        clients.shouldNotBeEmpty()
        clients.forEach { it.closes shouldBe 1 }
    }

    @Test
    fun `a failed current metadata read still closes its client`() {
        val table = h.table("metadata_fail_close")
        h.append(table)

        val failure = shouldThrow<IllegalStateException> { reads(failReads = true).metadata(table) }

        failure.message shouldBe READ_FAILURE
        clients.single().closes shouldBe 1
    }

    @Test
    fun `a failed protected revision read still closes its client`() {
        val table = h.table("revisions_fail_close")
        h.append(table)

        val failure =
            shouldThrow<IllegalStateException> {
                reads(failReads = true).protectedRevisions(table, h.m.dbNow(), emptySet())
            }

        failure.message shouldBe READ_FAILURE
        clients.single().closes shouldBe 1
    }

    private fun reads(failReads: Boolean = false): CleanupReads =
        CleanupReads(
            h.jdbc,
            {
                TrackedIo(
                    h.m.defs.catalogs
                        .metadataFileIo(),
                    failReads,
                ).also { clients += it }
            },
            h.properties.name,
        )

    private class TrackedIo(
        private val delegate: FileIO,
        private val failReads: Boolean,
    ) : FileIO by delegate {
        var closes: Int = 0
            private set

        override fun newInputFile(path: String): InputFile {
            if (failReads) error(READ_FAILURE)
            return delegate.newInputFile(path)
        }

        override fun close() {
            closes++
            delegate.close()
        }
    }

    private companion object {
        const val READ_FAILURE = "metadata read failed"
    }
}
