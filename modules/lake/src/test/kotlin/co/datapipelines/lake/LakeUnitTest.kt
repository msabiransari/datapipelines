package co.datapipelines.lake

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.apache.iceberg.exceptions.CommitFailedException
import org.apache.iceberg.inmemory.InMemoryFileIO
import org.apache.iceberg.jdbc.UncheckedSQLException
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/**
 * The lake's pure pieces: the properties' redaction and path derivation, the SQLSTATE classification of a catalog
 * failure (pipeline-contract §13.25), the deferring FileIO, and the lake names.
 */
class LakeUnitTest {
    @Test
    fun `every credential is redacted at every level that holds one`() {
        val secrets = listOf("AKIA-writer-access", "writer-secret-value", "AKIA-reader-access", "reader-secret-value")
        val storage =
            LakeProperties.Storage(
                bucket = "b",
                accessKey = secrets[0],
                secretKey = secrets[1],
                readerAccessKey = secrets[2],
                readerSecretKey = secrets[3],
            )
        val printed = listOf(storage.toString(), LakeProperties(storage = storage).toString())
        printed.forEach { text -> secrets.forEach { text shouldNotContain it } }
        storage.toString() shouldContain "secretKey=<redacted>"
        LakeProperties.Storage().toString() shouldContain "secretKey=<empty>"
        CatalogDatabase("jdbc:postgresql://h/db", "dp", "catalog-password").toString() shouldNotContain "catalog-password"
    }

    @Test
    fun `the prefix is normalised and the root has no trailing slash`() {
        fun root(prefix: String) = LakeProperties.Storage(bucket = "b", prefix = prefix).let { it.normalisedPrefix to it.root }
        root("lake/") shouldBe ("lake/" to "s3://b/lake")
        root("/lake") shouldBe ("lake/" to "s3://b/lake")
        root("a/b//") shouldBe ("a/b/" to "s3://b/a/b")
        root("") shouldBe ("" to "s3://b")
        root("/") shouldBe ("" to "s3://b")
        LakeProperties.Storage(accessKey = "a").hasStaticCredentials shouldBe false
        LakeProperties.Storage(accessKey = "a", secretKey = "s").hasStaticCredentials shouldBe true
    }

    @Test
    fun `a catalog failure is classified by the first SQLSTATE in its cause chain`() {
        val fenced =
            LakeCatalogException
                .classify(UncheckedSQLException(SQLException("x", "DP001"), "Unknown failure"))
                .shouldBeInstanceOf<LakeCatalogException.Fenced>()
        fenced.code shouldBe "lake.catalog.fenced"
        LakeCatalogException
            .classify(CommitFailedException(SQLException("y", "DP002"), "wrapped"))
            .shouldBeInstanceOf<LakeCatalogException.Restoring>()
            .code shouldBe "lake.catalog.restoring"
        LakeCatalogException
            .classify(UncheckedSQLException(SQLException("eof", "08006"), "Unknown failure"))
            .shouldBeInstanceOf<LakeCatalogException.OutcomeUnknown>()
            .code shouldBe "lake.catalog.outcome_unknown"
        // Neither a fence state nor a connection class: not the lake's to name.
        LakeCatalogException.classify(UncheckedSQLException(SQLException("dup", "23505"), "x")).shouldBeNull()
        LakeCatalogException.classify(IllegalStateException("no sql")).shouldBeNull()
        // Already classified: returned as is.
        LakeCatalogException.classify(fenced).shouldBeSameInstanceAs(fenced)
        // Static messages: no token, no row, no path.
        fenced.message shouldBe "the lake catalog refused a write without a live lease of this operation"
    }

    @Test
    fun `the deferring FileIO never deletes and records what it deferred`() {
        val path = "mem://lake-unit/${UUID.randomUUID()}/m.json"
        val delegate = InMemoryFileIO().apply { addFile(path, byteArrayOf(1)) }
        val deferred = mutableListOf<String>()
        val io = DeferringFileIO(delegate) { deferred += it }

        io.deleteFile(path)

        deferred shouldContainExactly listOf(path)
        delegate.fileExists(path) shouldBe true
        io.newInputFile(path).exists() shouldBe true
    }

    @Test
    fun `lake names are lower-case segments and a table lease is ns dot table`() {
        LakeNames.tableLeaseName("sales", "orders") shouldBe "sales.orders"
        listOf("sales", "a_1", "x".repeat(63)).forEach { LakeNames.requireName(it) }
        listOf("Sales", "a-b", "", "x".repeat(64), "a.b").forEach { name ->
            runCatching { LakeNames.requireName(name) }.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
        }
    }
}
