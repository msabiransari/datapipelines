package co.datapipelines.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The loader agreement is checked through the production validator; the files really exist. */
class ScriptJavaScriptRulesTest {
    @TempDir
    lateinit var directory: Path

    private fun violations(
        js: Map<String, String?>,
        loaderPath: String? = null,
    ): List<String> =
        ConfigValidator
            .validate(
                ConfigSnapshots.valid().copy(scriptJs = js, scriptJsLoaderPath = loaderPath, scriptPoolSize = 4),
            ).violations

    @Test
    fun `isolate dir must be absolute, in the launcher list and contain js-isolate jar`() {
        violations(mapOf("$PREFIX.isolate-dir" to "relative")).any { it.contains("absolute") } shouldBe true
        violations(mapOf("$PREFIX.isolate-dir" to directory.toString()), directory.toString())
            .any { it.contains("must contain js-isolate.jar") } shouldBe true
        Files.createFile(directory.resolve("js-isolate.jar"))
        violations(mapOf("$PREFIX.isolate-dir" to directory.toString()), "/app/lib")
            .any { it.contains("must be one entry") } shouldBe true
        violations(mapOf("$PREFIX.isolate-dir" to directory.toString()), "/app/lib, $directory/") shouldBe emptyList()
    }

    @Test
    fun `the shared isolate must be strictly larger than all pool slots`() {
        for (memory in listOf("512", "1024")) {
            violations(mapOf("$PREFIX.max-heap-mb" to "256", "$PREFIX.isolate-memory-mb" to memory))
                .any { it.contains("all pool slots share one isolate") } shouldBe true
        }
        violations(mapOf("$PREFIX.max-heap-mb" to "256", "$PREFIX.isolate-memory-mb" to "1280")) shouldBe emptyList()
        violations(mapOf("$PREFIX.max-heap-mb" to "256", "$PREFIX.isolate-memory-mb" to "")) shouldBe emptyList()
    }

    @Test
    fun `bounds refuse malformed or undersized values by key`() {
        for ((key, value) in listOf(
            "max-output-bytes" to "4095",
            "max-result-mb" to "0",
            "max-input-mb" to "0",
            "max-statements" to "oops",
            "max-stack-frames" to "0",
            "max-ast-depth" to "0",
            "max-heap-mb" to "0",
        )) {
            violations(mapOf("$PREFIX.$key" to value)).any { it.startsWith("$PREFIX.$key ") } shouldBe true
        }
        violations(mapOf("$PREFIX.max-output-bytes" to "4096")) shouldBe emptyList()
    }

    @Test
    fun `native isolate floors and derived byte overflow are named config errors`() {
        violations(mapOf("$PREFIX.max-heap-mb" to "1", "$PREFIX.isolate-memory-mb" to "5"))
            .any { it.contains("GraalJS isolate floor") } shouldBe true
        ConfigValidator
            .validate(
                ConfigSnapshots.valid().copy(
                    scriptJs = mapOf("$PREFIX.max-heap-mb" to Int.MAX_VALUE.toString()),
                    scriptPoolSize = Int.MAX_VALUE.toLong(),
                ),
            ).violations
            .any { it.contains("overflows the isolate byte budget") } shouldBe true
    }

    @Test
    fun `the launcher system property wins over all environment aliases including an empty property`() {
        val env = mapOf("loader.path" to "/lower-dot", "loader_path" to "/lower-underscore", "LOADER_PATH" to "/upper")
        ScriptRules.effectiveLoaderPath("/system", env) shouldBe "/system"
        ScriptRules.effectiveLoaderPath("", env) shouldBe ""
        ScriptRules.effectiveLoaderPath(null, env) shouldBe "/lower-dot"
        ScriptRules.effectiveLoaderPath(null, env - "loader.path") shouldBe "/lower-underscore"
        ScriptRules.effectiveLoaderPath(null, mapOf("LOADER_PATH" to "/upper")) shouldBe "/upper"
        ScriptRules.effectiveLoaderPath(null, emptyMap()) shouldBe null
    }

    private companion object {
        const val PREFIX = "datapipelines.script.js"
    }
}
