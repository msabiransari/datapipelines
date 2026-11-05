package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * The packaged resolutions of the BOM-managed H2 and SQLite JDBC drivers (#826), riding the resource
 * [DuckDbVersionTest] already pins to its two oracles. A key the jar does not carry (a generator that
 * dropped one) or a malformed value fails loudly here at the first reader, naming the key — never as a
 * blank on a page or an unguarded literal in the tree.
 */
class DriverVersionsTest {
    @Test
    fun `the packaged resolution carries all three drivers, DuckDB's matching the token`() {
        DriverVersions.CURRENT.jdbc shouldBe DuckDbVersion.CURRENT.jdbc
        DriverVersions.CURRENT.h2 shouldBe DriverVersions.load(DriverVersions::class.java.classLoader).h2
        DriverVersions.CURRENT.sqliteJdbc shouldBe DriverVersions.load(DriverVersions::class.java.classLoader).sqliteJdbc
    }

    @Test
    fun `an absent resource fails fast naming it`() {
        val failure = assertThrows<IllegalStateException> { DriverVersions.load(URLClassLoader(arrayOf(), null)) }
        failure.message.orEmpty() shouldContain DriverVersions.RESOURCE
        failure.message.orEmpty() shouldContain "generateDuckDbVersion"
    }

    @Test
    fun `a resource missing a driver key or with a malformed version fails naming it`() {
        assertThrows<IllegalStateException> { loadFrom("jdbc=9.9.9.9\n") }.message.orEmpty() shouldContain "carries no 'h2' key"
        assertThrows<IllegalStateException> { loadFrom("jdbc=9.9.9.9\nh2=8.8.8.8\n") }.message.orEmpty() shouldContain
            "carries no 'sqlite_jdbc' key"
        listOf("", "9.9", "v9.9.9.9", "9.9.9.9-SNAPSHOT").forEach { value ->
            assertThrows<IllegalArgumentException> {
                loadFrom("jdbc=9.9.9.9\nh2=$value\nsqlite_jdbc=7.7.7.7\n")
            }.message.orEmpty() shouldContain DriverVersions.RESOURCE
        }
        loadFrom("jdbc = 9.9.9.9 \nh2 = 8.8.8.8 \nsqlite_jdbc = 7.7.7.7 \n") shouldBe
            DriverVersions("9.9.9.9", "8.8.8.8", "7.7.7.7")
    }

    private fun loadFrom(text: String): DriverVersions {
        val root = Files.createTempDirectory("driver-versions").toFile()
        try {
            File(root, DriverVersions.RESOURCE).apply { parentFile.mkdirs() }.writeText(text)
            return URLClassLoader(arrayOf(root.toURI().toURL()), null).use { DriverVersions.load(it) }
        } finally {
            root.deleteRecursively()
        }
    }
}
