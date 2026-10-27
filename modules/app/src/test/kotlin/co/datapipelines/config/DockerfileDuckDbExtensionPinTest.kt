package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** Pins the image's complete two-platform bundle to its checksum manifest and deployment contract. */
class DockerfileDuckDbExtensionPinTest {
    private val dockerfile = repoFile("Dockerfile").readText()
    private val names = setOf("httpfs", "aws", "iceberg", "avro")
    private val version =
        requireNotNull(Regex("(?m)^ARG DUCKDB_CORE_VERSION=([0-9.]+)$").find(dockerfile)).groupValues[1]

    @Test
    fun `the manifest covers every extension on both platforms exactly once`() {
        val lines = repoFile("deploy/duckdb-extensions.sha256").readLines().filter { it.isNotBlank() && !it.startsWith('#') }
        val paths =
            lines.map { line ->
                requireNotNull(Regex("([a-f0-9]{64})  (\\S+)").matchEntire(line)) { "Invalid checksum: $line" }.groupValues[2]
            }
        val expected =
            listOf("linux_amd64", "linux_arm64").flatMap { platform ->
                names.map { "v$version/$platform/$it.duckdb_extension" }
            }
        paths.sorted() shouldContainExactly expected.sorted()
        paths shouldContainExactly paths.sorted()
    }

    @Test
    fun `the extension RUN verifies the copied manifest before USER`() {
        val copy = dockerfile.indexOf("\nCOPY deploy/duckdb-extensions.sha256 /tmp/duckdb-extensions.sha256\n")
        val run = dockerfile.indexOf("\nRUN case", copy)
        val end = dockerfile.indexOf("\n\n", run)
        val user = dockerfile.indexOf("\nUSER datapipelines")
        (copy in 1 until run && run < end && end < user) shouldBe true
        val block = dockerfile.substring(run, end).replace("\\\n", " ")
        block.contains("cd /opt/duckdb/extensions &&") shouldBe true
        block.contains("sha256sum -c --ignore-missing --strict /tmp/duckdb-extensions.sha256 &&") shouldBe true
        block.contains("rm /tmp/duckdb-extensions.sha256") shouldBe true
    }

    @Test
    fun `the Dockerfile loop and deployment bullet name the same complete bundle`() {
        val loop = requireNotNull(Regex("for ext in ([a-z ]+); do").find(dockerfile)).groupValues[1].split(' ')
        loop.sorted() shouldContainExactly names.sorted()
        val bullet = repoFile("docs/deployment.md").readLines().single { it.startsWith("- **Bundled DuckDB extensions") }
        val documented =
            Regex("`([a-z]+)`")
                .findAll(bullet)
                .map { it.groupValues[1] }
                .filter { it in names }
                .toList()
        documented.sorted() shouldContainExactly names.sorted()
        bullet.contains("core v$version") shouldBe true
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
