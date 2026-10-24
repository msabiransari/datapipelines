package co.datapipelines.scripting

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * The image's GraalJS pin, kept honest (#8 A.6): the Dockerfile fetches ONE platform's isolate from
 * Maven Central by version and checks it against a pinned SHA-256. Three things can drift apart — the
 * catalog's `graalvm` version, the Dockerfile's `GRAALJS_VERSION`, and the two sums — and each drift
 * ships an image whose isolate is not the one the tests ran. This test reads all three: the version
 * from the catalog, the sums computed over the two linux binaries the test runtime resolved (the
 * aggregator pom puts every platform on `java.class.path`, checksum-verified by Gradle).
 *
 * The ENTRYPOINT points `polyglot.engine.userResourceCache` at the cache the build filled and names
 * no `-Dloader.path` (the isolate reaches the class path through `LOADER_PATH`, which a system
 * property would REPLACE — owner ruling 2026-10-21).
 */
class DockerfileGraalJsPinTest {
    private val dockerfile = repoFile("Dockerfile").readText()

    @Test
    fun `the Dockerfile's version is the catalog's graalvm version`() {
        val toml = repoFile("gradle/libs.versions.toml").readText()
        val catalog = Regex("""(?m)^graalvm = "([^"]+)"""").find(toml)?.groupValues?.get(1)
        withClue("catalog graalvm") { (catalog != null) shouldBe true }
        arg("GRAALJS_VERSION") shouldBe catalog
    }

    @Test
    fun `the two pinned sums are the sums of the binaries the tests ran`() {
        val version = arg("GRAALJS_VERSION")
        val sums = mapOf("linux-amd64" to "GRAALJS_SHA256_LINUX_AMD64", "linux-aarch64" to "GRAALJS_SHA256_LINUX_AARCH64")
        sums.forEach { (platform, argName) ->
            val name = "js-isolate-$platform-community-$version.jar"
            val jars = classPath().filter { it.name == name }
            withClue("$name on java.class.path") { jars shouldHaveSize 1 }
            withClue(argName) { arg(argName) shouldBe sha256(jars.single()) }
        }
    }

    @Test
    fun `the ENTRYPOINT points the resource cache at the build's directory and sets no loader path`() {
        val entrypoint = Regex("""(?m)^ENTRYPOINT (.+)$""").findAll(dockerfile).toList()
        entrypoint shouldHaveSize 1
        val line = entrypoint.single().groupValues[1]
        line.contains("\"-Dpolyglot.engine.userResourceCache=/opt/graaljs/resources\"") shouldBe true
        line.contains("resourcePath") shouldBe false
        line.contains("loader.path") shouldBe false
        Regex("""(?m)^ENV LOADER_PATH=/app/lib,/opt/graaljs$""").containsMatchIn(dockerfile) shouldBe true
    }

    @Test
    fun `the fetch checks the sum and the resources are installed before USER`() {
        dockerfile.contains("""echo "${'$'}sum  /opt/graaljs/js-isolate.jar" | sha256sum -c -""") shouldBe true
        val resources = dockerfile.indexOf("co.datapipelines.scripting.JavaScriptResourcesMain")
        val user = dockerfile.indexOf("\nUSER datapipelines")
        withClue("the pre-install runs as root, before USER") { (resources in 1 until user) shouldBe true }
        Regex("""chmod -R a\+rX,a-w /opt/graaljs/resources$""", RegexOption.MULTILINE).containsMatchIn(dockerfile) shouldBe true
    }

    private fun arg(name: String): String =
        Regex("""(?m)^ARG $name=(\S+)$""").find(dockerfile)?.groupValues?.get(1) ?: throw AssertionError("no ARG $name in the Dockerfile")

    private fun classPath(): List<File> = System.getProperty("java.class.path").split(File.pathSeparator).map(::File)

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.exists()) { "missing $relative" } }
    }
}
