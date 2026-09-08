package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.security.MessageDigest

/**
 * 096 §B (review finding F2) — htmx, vendored, audited from the CLASSPATH rather than
 * recalled.
 *
 * htmx was the one library this app did not vendor. It arrived as `org.webjars.npm:htmx.org`
 * and was served from `/webjars/` — which meant the security allowlist carried a glob over a
 * servlet namespace whose CONTENTS are decided by the dependency graph, not by this
 * repository. Add a webjar (or have one arrive transitively) and its files are public with
 * no decision taken and no diff to review. That is the same class of trust as the CDN
 * `<script src>` MISTAKES.md forbids: the bytes and the URL space belong to someone else.
 *
 * The fix was to vendor it beside Alpine, Cytoscape and dagre. This test is the guard on
 * that arrangement, and each claim is one that has a way of quietly becoming false:
 *
 *  1. **The file is on the classpath.** A `.gitignore` rule or a partial `git add` drops it
 *     and every htmx attribute in every template silently stops working — the app renders
 *     and does nothing, which reads as "the UI is broken" rather than "a file is missing".
 *  2. **Its bytes are the ones we recorded.** The manifest's sha256 is the same hash the
 *     removed webjar's `dist/htmx.min.js` carried; a re-download from a different release
 *     is caught here rather than by a reviewer's eye.
 *  3. **Its licence travels with it.** htmx ships Zero-Clause BSD; the AGPL-3.0
 *     distribution of this app redistributes the file, so the licence is vendored beside it.
 *  4. **No layout reaches back to `/webjars/`.** This is the claim that matters: the
 *     allowlist entry is gone, so a reintroduced `/webjars/` reference would 401 rather
 *     than work — and the fastest "fix" for that is to put the entry back.
 */
class VendoredHtmxAuditTest {
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

    private fun template(name: String): String = classpathBytes("templates/layouts/$name").decodeToString()

    @Test
    fun `the manifest declares htmx and the vendored file matches its recorded hash`() {
        // Non-vacuity first: a manifest that stopped carrying the block would make the hash
        // comparison below compare two empty strings.
        manifest shouldContain "\"htmx\""
        val declared =
            Regex("\"htmx\"\\s*:\\s*\\{[^}]*\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"")
                .find(manifest)
                ?.groupValues
                ?.get(1)
        withClue("the htmx block declares a sha256") { (declared != null) shouldBe true }

        sha256(classpathBytes(VENDORED_PATH)) shouldBe declared
    }

    @Test
    fun `the Zero-Clause BSD licence is vendored beside the file`() {
        val licence = classpathBytes("static/vendor/htmx/LICENSE").decodeToString()
        licence shouldContain "Zero-Clause BSD"
    }

    @Test
    fun `both layouts load htmx from the vendored path and no layout reaches a webjar`() {
        listOf("auth.html", "default.html").forEach { name ->
            withClue(name) {
                val html = template(name)
                html shouldContain "@{/vendor/htmx/htmx.min.js}"
                // The allowlist entry is gone (auth.md §8.3), so this would 401 — and the
                // cheapest way to make that green again is to put the glob back.
                html shouldNotContain "/webjars/"
            }
        }
    }

    private companion object {
        const val VENDORED_PATH = "static/vendor/htmx/htmx.min.js"
    }
}
