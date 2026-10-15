package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** Pins both CSRF descriptions to the matcher constructors in the real filter-chain call (#515). */
class SecurityConfigCsrfExemptionParityTest {
    private val root = repoRoot()
    private val source = root.resolve("modules/auth/src/main/kotlin/co/datapipelines/auth/SecurityConfig.kt").readText()

    @Test
    fun `class KDoc names every configured CSRF exemption`() {
        assertKdocParity(source)
    }

    @Test
    fun `auth sketch names every configured CSRF exemption`() {
        val sketch = root.resolve("docs/auth.md").readText()
        val documented = configuredMatchers(sketch)
        withClue("auth.md CSRF sketch is missing configured matchers") {
            (configuredMatchers(source) - documented) shouldBe emptySet()
        }
    }

    @Test
    fun `class KDoc makes no only exemption claim`() {
        withClue("class KDoc must describe all CSRF exemptions without an only exemption claim") {
            Regex("""only\s+exemption""", RegexOption.IGNORE_CASE).containsMatchIn(csrfKdoc(source)) shouldBe false
        }
    }

    @Test
    fun `extractor detects a fourth matcher added only to a scratch source string`() {
        val call = MATCHER_CALL.find(source) ?: error("SecurityConfig has no CSRF exemption call")
        val arguments = requireNotNull(call.groups[1])
        val scratch = source.replaceRange(arguments.range, "${arguments.value}, FourthCredentialMatcher()")
        withClue("scratch call must expose the added matcher missing from the class KDoc") {
            missingFromKdoc(scratch) shouldBe (missingFromKdoc(source) + "FourthCredentialMatcher")
        }
    }

    private fun assertKdocParity(text: String) {
        withClue("class KDoc CSRF section is missing configured matchers") {
            missingFromKdoc(text) shouldBe emptySet()
        }
    }

    private fun missingFromKdoc(text: String): Set<String> {
        val section = csrfKdoc(text)
        return configuredMatchers(text).filterNot { Regex("""\b$it\b""").containsMatchIn(section) }.toSet()
    }

    private fun csrfKdoc(text: String): String {
        val classKdoc = text.substringBefore("@Configuration").substringAfter("/**").substringBefore("*/")
        val heading = "## CSRF follows the credential, not the path"
        check(classKdoc.contains(heading)) { "SecurityConfig class KDoc has no CSRF section" }
        return classKdoc.substringAfter(heading).substringBefore("\n * ## ")
    }

    private fun configuredMatchers(text: String): Set<String> {
        val calls = MATCHER_CALL.findAll(text).toList()
        withClue("expected exactly one csrf.ignoringRequestMatchers call") { calls.size shouldBe 1 }
        val names = MATCHER_CONSTRUCTOR.findAll(calls.single().groupValues[1]).map { it.groupValues[1] }.toSet()
        withClue("CSRF exemption call must contain matcher constructors") { names.isNotEmpty() shouldBe true }
        return names
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parent != null && !dir.resolve("settings.gradle.kts").isFile) dir = dir.parentFile
        return dir
    }

    private companion object {
        val MATCHER_CALL = Regex("""csrf\.ignoringRequestMatchers\(([\s\S]*?)\)\s*(?:\r?\n|$)""")
        val MATCHER_CONSTRUCTOR = Regex("""\b([A-Z]\w*)\s*\(""")
    }
}
