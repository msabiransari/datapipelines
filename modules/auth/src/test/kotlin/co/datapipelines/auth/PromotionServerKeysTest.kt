package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * The promotion server key's two operations (versioning §10.6): the timing-safe compare and
 * the non-reversible fingerprint.
 *
 * ## Why the source is asserted, not the timing
 * A timing assertion on a JIT-compiled JVM is a flake generator; what actually needs pinning
 * is that the COMPARISON FUNCTION is `MessageDigest.isEqual` and not `==` / `String.equals`,
 * which return at the first differing byte and leak the shared prefix length. So the source of
 * `PromotionProperties.kt` is read and asserted directly — the same discipline
 * `DeploymentNameBranchingGuardTest` uses for the no-branching rule. A refactor that swaps the
 * compare for `==` turns this red, which a behavioural test never would: both compares return
 * exactly the same booleans.
 */
class PromotionServerKeysTest {
    @Test
    fun `the comparison function is MessageDigest-isEqual, never string equality`() {
        val source = RepoFiles.read(SOURCE_PATH)
        val matchesBody = source.substringAfter("    fun matches(").substringBefore("\n    /**")

        withClue("PromotionServerKeys.matches must compare with MessageDigest.isEqual (§10.6: timing-safe)") {
            matchesBody.contains("MessageDigest.isEqual") shouldBe true
        }
        // The early return is a null/blank guard on the CONFIGURED and PRESENTED values, which
        // carries no secret-dependent timing; any other equality on the key would.
        withClue("no `==` / .equals on the key material — either would return at the first differing byte") {
            matchesBody.shouldNotContain("configured ==")
            matchesBody.shouldNotContain("presented ==")
            matchesBody.shouldNotContain("configured.equals")
            matchesBody.shouldNotContain(".contentEquals")
        }
    }

    @Test
    fun `an exact match is accepted`() {
        PromotionServerKeys.matches(KEY, KEY) shouldBe true
    }

    @Test
    fun `a wrong key is refused - including one sharing a long prefix and one differing only in the last byte`() {
        PromotionServerKeys.matches(KEY, "wrong") shouldBe false
        PromotionServerKeys.matches(KEY, KEY.dropLast(1)) shouldBe false
        PromotionServerKeys.matches(KEY, KEY.dropLast(1) + "X") shouldBe false
        PromotionServerKeys.matches(KEY, KEY + "x") shouldBe false
        // Case matters: a bearer secret is bytes, not an identifier.
        PromotionServerKeys.matches(KEY, KEY.uppercase()) shouldBe false
    }

    @Test
    fun `no configured key refuses EVERYTHING - the fail-closed rule lives in the compare`() {
        // §10.6: "a deployment that never configured a key must not silently accept pushes".
        // Encoded here rather than at each call site so no caller can forget the null check.
        listOf(null, "", "   ").forEach { configured ->
            withClue("configured=${configured?.let { "'$it'" } ?: "null"}") {
                PromotionServerKeys.matches(configured, KEY) shouldBe false
                PromotionServerKeys.matches(configured, null) shouldBe false
                PromotionServerKeys.matches(configured, "") shouldBe false
                // The degenerate case that would open the door: blank == blank.
                PromotionServerKeys.matches(configured, configured) shouldBe false
            }
        }
    }

    @Test
    fun `a missing or blank presented credential is refused against a configured key`() {
        PromotionServerKeys.matches(KEY, null) shouldBe false
        PromotionServerKeys.matches(KEY, "") shouldBe false
        PromotionServerKeys.matches(KEY, "   ") shouldBe false
    }

    @Test
    fun `the fingerprint identifies a key without carrying it`() {
        val print = PromotionServerKeys.fingerprint(KEY)

        print shouldStartWith "sha256:"
        // 6 bytes → 12 hex chars, plus the 7-character prefix.
        print.length shouldBe "sha256:".length + 12
        withClue("a fingerprint that contains the key is not a fingerprint") {
            print.shouldNotContain(KEY)
        }
        // Stable for one key, different for another — the only two properties an audit trail
        // needs from it.
        PromotionServerKeys.fingerprint(KEY) shouldBe print
        PromotionServerKeys.fingerprint(KEY + "x") shouldNotBe print
    }

    @Test
    fun `an absent key fingerprints as none, not as the digest of an empty string`() {
        PromotionServerKeys.fingerprint(null) shouldBe "none"
        PromotionServerKeys.fingerprint("") shouldBe "none"
        PromotionServerKeys.fingerprint("  ") shouldBe "none"
    }

    @Test
    fun `the properties never print either secret`() {
        // A data class would have printed both the first time anything logged this object —
        // which is exactly how a bearer secret escapes into an operator's terminal.
        val properties = PromotionProperties(serverKey = KEY, target = PromotionProperties.Target("https://uat.example.com", KEY))

        val rendered = properties.toString()
        rendered.shouldNotContain(KEY)
        rendered.contains("https://uat.example.com") shouldBe true
        properties.receives shouldBe true
        properties.target.isConfigured shouldBe true
    }

    @Test
    fun `receives and isConfigured read blank as absent`() {
        PromotionProperties(serverKey = "  ").receives shouldBe false
        PromotionProperties().receives shouldBe false
        PromotionProperties.Target(baseUrl = "  ").isConfigured shouldBe false
        PromotionProperties.Target().isConfigured shouldBe false
    }

    private companion object {
        const val SOURCE_PATH = "modules/auth/src/main/kotlin/co/datapipelines/auth/PromotionProperties.kt"

        /**
         * A FIXTURE, deliberately low-entropy and self-describing. A production key is
         * `openssl rand -base64 32`, but a realistic-looking one in a test file is a secret
         * scanner's true positive by construction — and the compare under test is over bytes,
         * so entropy buys the assertions nothing.
         */
        const val KEY = "promotion-fixture-key-not-a-real-secret"
    }
}
