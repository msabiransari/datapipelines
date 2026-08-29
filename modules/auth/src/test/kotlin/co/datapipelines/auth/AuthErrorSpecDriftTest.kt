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
 *
 * The same gate covers the `workspace.*` codes this module raises (§13.12): the
 * constants here mirror `PipelineErrorCodes.Workspace` (auth cannot depend on
 * pipeline-contract), and both sides are asserted against the doc — transitively
 * equal, with the document as the single authority.
 */
class AuthErrorSpecDriftTest {
    @Test
    fun `AuthErrorCodes ALL equals the auth codes in pipeline-contract §13-7`() {
        val fromDoc = parseSection("### 13.7", "### 13.8", "auth").keys
        AuthErrorCodes.ALL shouldContainExactlyInAnyOrder fromDoc
    }

    @Test
    fun `WorkspaceErrorCodes ALL equals the workspace codes in pipeline-contract §13-12`() {
        val fromDoc = parseSection("### 13.12", "## 14", "workspace").keys
        WorkspaceErrorCodes.ALL shouldContainExactlyInAnyOrder fromDoc
    }

    @Test
    fun `each code's HTTP status matches the exception it maps to`() {
        val docStatus = parseSection("### 13.7", "### 13.8", "auth")
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

    @Test
    fun `each workspace code's HTTP status matches the exception it maps to`() {
        val docStatus = parseSection("### 13.12", "## 14", "workspace")
        val exceptionStatus =
            mapOf(
                WorkspaceErrorCodes.MEMBERSHIP_REQUIRED to WorkspaceMembershipRequiredException().status,
                WorkspaceErrorCodes.CREATION_FORBIDDEN to
                    WorkspaceCreationForbiddenException(WorkspaceProvisioningMode.CLOSED).status,
                WorkspaceErrorCodes.HEADER_FORBIDDEN to WorkspaceHeaderForbiddenException().status,
                WorkspaceErrorCodes.NOT_FOUND to WorkspaceNotFoundException("x").status,
                WorkspaceErrorCodes.NAME_INVALID to WorkspaceNameInvalidException("X!").status,
                WorkspaceErrorCodes.DUPLICATE_NAME to WorkspaceDuplicateNameException("x").status,
                WorkspaceErrorCodes.IN_USE to WorkspaceInUseException("x", mapOf("pipelines" to 1)).status,
            )
        exceptionStatus.forEach { (code, status) ->
            (code to status) shouldBe (code to docStatus.getValue(code))
        }
    }

    private fun parseSection(
        startMarker: String,
        endMarker: String,
        domain: String,
    ): Map<String, Int> {
        val doc = RepoFiles.read(RepoFiles.PIPELINE_CONTRACT_PATH)
        val start = doc.indexOf(startMarker)
        require(start >= 0) { "$startMarker not found in ${RepoFiles.PIPELINE_CONTRACT_PATH}" }
        val end = doc.indexOf(endMarker, start)
        val section = doc.substring(start, if (end >= 0) end else doc.length)

        val rowRegex = Regex("""\|\s*`($domain\.[a-z_.]+)`\s*\|\s*(\d{3})\s*\|""")
        return rowRegex.findAll(section).associate { it.groupValues[1] to it.groupValues[2].toInt() }
    }
}
