package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.security.MessageDigest

/**
 * #195 — Alpine.js, vendored as its CSP build, audited from the CLASSPATH (the
 * [VendoredHtmxAuditTest] shape). The editor's `'unsafe-eval'` exemption was retired because
 * the CSP build evaluates only property and method names; each claim below is one that could
 * quietly become false and bring the exemption back through the side door:
 *
 *  1. **The bytes are the ones recorded.** The manifest's sha256 is the npm tarball's
 *     `dist/cdn.min.js` for `@alpinejs/csp@3.14.1`; a re-download of the STANDARD build (same
 *     version, same file name) would pass every browser test on a laptop that never enforces
 *     the policy and fail only in production.
 *  2. **The file is the CSP build.** Its evaluator refuses anything but a member path with the
 *     message `unable to interpret`; the standard build's `AsyncFunction` compiler string still
 *     appears in the CSP build as unreachable code, so its presence proves nothing and is not
 *     asserted.
 *  3. **The editor loads the vendored path** and nothing loads Alpine from anywhere else.
 */
class VendoredAlpineAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val manifest: String =
        resolver
            .getResource("classpath:static/vendor/design-system/vendor-manifest.json")
            .inputStream
            .readBytes()
            .decodeToString()

    private fun classpathBytes(path: String): ByteArray = resolver.getResource("classpath:$path").inputStream.readBytes()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `the manifest declares the CSP build and the vendored file matches its recorded hash`() {
        manifest shouldContain "\"alpinejs\""
        val block = Regex("\"alpinejs\"\\s*:\\s*\\{[^}]*\\}").find(manifest)?.value
        withClue("the alpinejs block exists") { (block != null) shouldBe true }
        block!! shouldContain "\"package\": \"@alpinejs/csp\""
        block shouldContain "\"license\": \"MIT\""
        val declared = Regex("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(block)?.groupValues?.get(1)
        withClue("the alpinejs block declares a sha256") { (declared != null) shouldBe true }

        sha256(classpathBytes(VENDORED_PATH)) shouldBe declared
    }

    @Test
    fun `the vendored file is the CSP build - its evaluator refuses non-path expressions`() {
        val source = classpathBytes(VENDORED_PATH).decodeToString()
        withClue("the CSP evaluator's refusal text is present") { source shouldContain "unable to interpret" }
    }

    @Test
    fun `the editor loads Alpine from the vendored path and no template loads it from anywhere else`() {
        val editor = classpathBytes("templates/pipelines/editor.html").decodeToString()
        editor shouldContain "@{/vendor/alpinejs/alpine.min.js}"
        resolver.getResources("classpath:templates/**/*.html").forEach { resource ->
            val html = resource.inputStream.readBytes().decodeToString()
            withClue(resource.filename ?: "template") {
                html shouldNotContain "cdn.jsdelivr.net/npm/alpinejs"
                html shouldNotContain "unpkg.com/alpinejs"
            }
        }
    }

    private companion object {
        const val VENDORED_PATH = "static/vendor/alpinejs/alpine.min.js"
    }
}
