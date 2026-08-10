package co.datapipelines.auth

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The auth error catalog must match [Pipeline Contract §13.7] EXACTLY — the registry
 * of record (auth.md §9 points here). This is the spec-drift gate: adding a code to
 * the doc without wiring it (or emitting a code the registry does not list) fails.
 *
 * The HTTP status each auth exception carries is also asserted against the doc's
 * status column, so a code cannot silently drift to the wrong status.
 */
class AuthErrorSpecDriftTest {
    @Test
    fun `AuthErrorCodes ALL equals the auth codes in pipeline-contract §13-7`() {
        val fromDoc = parseSection().keys
        AuthErrorCodes.ALL shouldContainExactlyInAnyOrder fromDoc
    }

    @Test
    fun `each code's HTTP status matches the exception it maps to`() {
        val docStatus = parseSection()
        val exceptionStatus =
            mapOf(
                AuthErrorCodes.API_KEY_MISSING to ApiKeyMissingException().status,
                AuthErrorCodes.API_KEY_INVALID to ApiKeyInvalidException().status,
                AuthErrorCodes.API_KEY_EXPIRED to ApiKeyExpiredException().status,
                AuthErrorCodes.SESSION_INVALID to SessionInvalidException().status,
                AuthErrorCodes.SESSION_EXPIRED to SessionExpiredException().status,
                AuthErrorCodes.SCOPE_INSUFFICIENT to ScopeInsufficientException(Scope.ADMIN, emptySet()).status,
                AuthErrorCodes.CSRF_INVALID to 403,
                AuthErrorCodes.LOGIN_DOMAIN_NOT_ALLOWED to 403,
                AuthErrorCodes.LOGIN_USER_INACTIVE to 403,
            )
        exceptionStatus.forEach { (code, status) ->
            (code to status) shouldBe (code to docStatus.getValue(code))
        }
    }

    private fun parseSection(): Map<String, Int> {
        val doc = RepoFiles.read(RepoFiles.PIPELINE_CONTRACT_PATH)
        val start = doc.indexOf("### 13.7")
        require(start >= 0) { "§13.7 not found in ${RepoFiles.PIPELINE_CONTRACT_PATH}" }
        val end = doc.indexOf("### 13.8", start)
        val section = doc.substring(start, if (end >= 0) end else doc.length)

        val rowRegex = Regex("""\|\s*`(auth\.[a-z_.]+)`\s*\|\s*(\d{3})\s*\|""")
        return rowRegex.findAll(section).associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }
}
