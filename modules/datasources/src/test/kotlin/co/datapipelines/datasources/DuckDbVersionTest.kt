package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.sql.DriverManager

/**
 * The packaged DuckDB version token (#781). Two oracles that are NOT the token: the engine itself
 * (`PRAGMA version` on an embedded connection reports the core it really is) and the version catalog
 * (the declaration the token must be the resolution of). A token that drifted from either is red.
 */
class DuckDbVersionTest {
    @Test
    fun `the packaged token is the core the embedded engine reports`() {
        val reported =
            DriverManager.getConnection("jdbc:duckdb:").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA version").use { rows ->
                        check(rows.next()) { "PRAGMA version returned no row" }
                        rows.getString("library_version")
                    }
                }
            }
        reported shouldBe "v${DuckDbVersion.CURRENT.core}"
    }

    @Test
    fun `the packaged token is the catalog's declared duckdb-jdbc`() {
        val catalog = repoFile("gradle/libs.versions.toml").readText()
        val declared = requireNotNull(Regex("""(?m)^duckdb-jdbc = "([^"]+)"""").find(catalog)) { "no duckdb-jdbc in the catalog" }
        DuckDbVersion.CURRENT.jdbc shouldBe declared.groupValues[1]
    }

    @Test
    fun `core is the first three components of the driver version`() {
        DuckDbVersion("1.5.5.1").core shouldBe "1.5.5"
        DuckDbVersion("2.0.0").core shouldBe "2.0.0"
        DuckDbVersion("10.20.30.40").core shouldBe "10.20.30"
    }

    @Test
    fun `an absent resource fails fast naming it`() {
        val failure = assertThrows<IllegalStateException> { DuckDbVersion.load(URLClassLoader(arrayOf(), null)) }
        failure.message.orEmpty() shouldContain DuckDbVersion.RESOURCE
        failure.message.orEmpty() shouldContain "generateDuckDbVersion"
    }

    @Test
    fun `a resource without the key or with a malformed version fails naming it`() {
        assertThrows<IllegalStateException> { loadFrom("other=1.5.5.1\n") }.message.orEmpty() shouldContain "carries no 'jdbc' key"
        listOf("", "1.5", "v1.5.5.1", "1.5.5.1-SNAPSHOT", "\${duckdb}").forEach { value ->
            assertThrows<IllegalArgumentException> { loadFrom("jdbc=$value\n") }.message.orEmpty() shouldContain DuckDbVersion.RESOURCE
        }
        loadFrom("jdbc = 1.5.6.0 \n") shouldBe DuckDbVersion("1.5.6.0")
    }

    private fun loadFrom(text: String): DuckDbVersion {
        val root = Files.createTempDirectory("duckdb-version").toFile()
        try {
            File(root, DuckDbVersion.RESOURCE).apply { parentFile.mkdirs() }.writeText(text)
            return URLClassLoader(arrayOf(root.toURI().toURL()), null).use { DuckDbVersion.load(it) }
        } finally {
            root.deleteRecursively()
        }
    }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }
}
