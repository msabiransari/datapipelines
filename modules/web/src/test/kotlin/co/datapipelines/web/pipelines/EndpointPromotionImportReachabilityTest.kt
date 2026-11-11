package co.datapipelines.web.pipelines

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File

/**
 * #786 §F.2 — the receiver-only import is reachable ONLY from the promotion receiver. `EndpointPromotionImport` writes
 * an API without the authoring capability, so the one thing that keeps it a receiver operation is who can reach it: no
 * route, no MCP tool, no bean another component could inject. The scan reads every tracked production Kotlin file
 * (`git ls-files` under `modules/` and `src/main/kotlin`) and pins the exact set:
 *
 * - the type is named only by its own file, `EndpointPromotion` (its one collaborator) and `PromotionConfiguration`
 *   (which constructs it INSIDE the `EndpointPromotion` bean method);
 * - `importPromoted(` is called only by `EndpointPromotion`;
 * - no function anywhere returns the type — so no `@Bean` method exposes it to the context.
 *
 * Non-vacuity: a scanned-file floor, and each expected file must really carry its reference.
 */
class EndpointPromotionImportReachabilityTest {
    @Test
    fun `the receiver-only import is named, built and called only on the promotion receiver's path`() {
        val root = repoRoot()
        val files = productionKotlin(root)
        if (files.size < SCAN_FLOOR) fail("the scan saw only ${files.size} production Kotlin files (floor $SCAN_FLOOR)")
        val texts = files.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

        val naming =
            texts
                .filterValues { TYPE_TOKEN.containsMatchIn(it) }
                .keys
                .map { it.substringAfterLast('/') }
                .toSet()
        withClue("files that name EndpointPromotionImport") { naming shouldBe setOf(OWN_FILE, COLLABORATOR, ASSEMBLY) }

        val callers =
            texts
                .filterValues { CALL_TOKEN.containsMatchIn(it) }
                .keys
                .map { it.substringAfterLast('/') }
                .toSet()
        withClue("files that call importPromoted(") { callers shouldBe setOf(COLLABORATOR) }

        val exposing = texts.filterValues { RETURNS_TYPE.containsMatchIn(it) }.keys
        withClue("a function returning EndpointPromotionImport would let a @Bean expose it to every component") {
            exposing.shouldBeEmpty()
        }
    }

    @Test
    fun `the matchers see the spellings they exist for`() {
        TYPE_TOKEN.containsMatchIn("co.datapipelines.application.endpoints.EndpointPromotionImport(") shouldBe true
        CALL_TOKEN.containsMatchIn("importer.importPromoted(") shouldBe true
        CALL_TOKEN.containsMatchIn("    fun importPromoted(") shouldBe false
        RETURNS_TYPE.containsMatchIn("fun importer(x: Int): co.datapipelines.application.endpoints.EndpointPromotionImport =") shouldBe true
        RETURNS_TYPE.containsMatchIn("fun importer(): EndpointPromotionImport {") shouldBe true
        RETURNS_TYPE.containsMatchIn("private val importer: EndpointPromotionImport,") shouldBe false
    }

    private fun productionKotlin(root: File): List<File> {
        val process = ProcessBuilder("git", "ls-files", "-z", "modules").directory(root).redirectErrorStream(true).start()
        val listing = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) fail("git ls-files exited $exit in $root: ${listing.take(ERROR_EXCERPT)}")
        return listing
            .split('\u0000')
            .filter { it.endsWith(".kt") && "/src/main/kotlin/" in it }
            .map { File(root, it) }
            .filter { it.isFile }
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "repository root not found" }
    }

    private companion object {
        const val OWN_FILE = "EndpointPromotionImport.kt"
        const val COLLABORATOR = "EndpointPromotion.kt"
        const val ASSEMBLY = "PromotionConfiguration.kt"
        const val SCAN_FLOOR = 500
        const val ERROR_EXCERPT = 400
        val TYPE_TOKEN = Regex("""\bEndpointPromotionImport\b""")
        val CALL_TOKEN = Regex("""(?<!fun\s)\bimportPromoted\s*\(""")

        /** `): EndpointPromotionImport` with an optional package prefix — a function's declared return type. */
        val RETURNS_TYPE = Regex("""\)\s*:\s*(?:[\w.]+\.)?EndpointPromotionImport\b""")
    }
}
