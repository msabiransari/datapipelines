package co.datapipelines.scripting

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The one factory, made mechanical (#8 A.2): `Engine.newBuilder` and `Context.newBuilder` appear in
 * exactly ONE production file, [JavaScriptRuntime], and that file builds under the UNTRUSTED policy
 * in a spawned isolate. A second builder anywhere in `src/main` could build a Context outside the
 * policy; this scan is red the moment one is written.
 */
class JavaScriptSandboxGuardTest {
    @Test
    fun `Engine and Context are built in exactly one production file`() {
        val sources = productionSources()
        withClue("the scan must see the module's sources") { (sources.size >= MIN_SOURCES) shouldBe true }
        val builders =
            sources.flatMap { file ->
                // Over the whole text: the house style breaks a chain after the receiver.
                val text = file.readText()
                BUILDER.findAll(text).map { file.name to text.substring(0, it.range.first).count { c -> c == '\n' } + 1 }.toList()
            }
        withClue("builder calls: $builders") {
            builders.map { it.first }.distinct() shouldContainExactly listOf("JavaScriptRuntime.kt")
            builders.size shouldBe 2
        }
    }

    @Test
    fun `the factory builds every Engine and Context under the UNTRUSTED policy, in an isolate`() {
        val text = productionSources().single { it.name == "JavaScriptRuntime.kt" }.readText()
        Regex("""\.sandbox\(SandboxPolicy\.UNTRUSTED\)""").findAll(text).count() shouldBe 2
        text.contains(".spawnIsolate(true)") shouldBe true
        text.contains(".allowHostAccess(HostAccess.UNTRUSTED)") shouldBe true
        listOf(
            ".allowPolyglotAccess(PolyglotAccess.NONE)",
            ".allowIO(IOAccess.NONE)",
            ".allowNativeAccess(false)",
            ".allowCreateProcess(false)",
            ".allowCreateThread(false)",
            ".allowEnvironmentAccess(EnvironmentAccess.NONE)",
            ".allowHostClassLookup { false }",
        ).forEach { deny -> withClue(deny) { text.contains(deny) shouldBe true } }
        // No other policy, and no allow-all anywhere in the module.
        productionSources().forEach { file ->
            withClue(file.name) { ALLOW_ALL.containsMatchIn(file.readText()) shouldBe false }
        }
    }

    private fun productionSources(): List<File> {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir =
                dir.parentFile ?: error("no settings.gradle.kts above ${File(".").absolutePath}")
        }
        return File(dir, "modules/scripting/src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        val BUILDER = Regex("""\b(Engine|Context)\s*\.\s*newBuilder\b""")
        val ALLOW_ALL =
            Regex(
                listOf(
                    """allowAllAccess""",
                    """SandboxPolicy\.(TRUSTED|CONSTRAINED|ISOLATED)""",
                    """HostAccess\.(ALL|EXPLICIT)""",
                    """IOAccess\.ALL""",
                    """PolyglotAccess\.ALL""",
                ).joinToString("|"),
            )
        const val MIN_SOURCES = 20
    }
}
