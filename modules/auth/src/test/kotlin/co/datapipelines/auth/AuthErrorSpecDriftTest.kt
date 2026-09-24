package co.datapipelines.auth

import io.kotest.assertions.withClue
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
                AuthErrorCodes.PERMISSION_UNDECLARED to AccessDeniedUndeclaredException().status,
                AuthErrorCodes.ROLE_REQUIRED to RoleRequiredException(Permission.PIPELINE_UPDATE, "viewer").status,
                AuthErrorCodes.CSRF_INVALID to 403,
                AuthErrorCodes.LOGIN_DOMAIN_NOT_ALLOWED to 403,
                AuthErrorCodes.LOGIN_USER_INACTIVE to 403,
                // The local-auth codes surface as login redirects rather than envelopes
                // (like the two above), so their statuses are pinned as literals.
                AuthErrorCodes.LOGIN_BAD_CREDENTIALS to 401,
                AuthErrorCodes.LOGIN_LOCKED to 403,
                AuthErrorCodes.PASSWORD_CHANGE_REQUIRED to PasswordChangeRequiredException().status,
                AuthErrorCodes.SESSION_REQUIRED to SessionRequiredException("create-local-user").status,
                AuthErrorCodes.PROMOTION_KEY_INVALID to PromotionKeyInvalidException().status,
                AuthErrorCodes.API_KEY_EXPIRY_INVALID to ApiKeyExpiryInvalidException("unknown_preset").status,
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
                WorkspaceErrorCodes.HEADER_FORBIDDEN to WorkspaceHeaderForbiddenException().status,
                WorkspaceErrorCodes.SESSION_REQUIRED to WorkspaceSessionRequiredException().status,
                WorkspaceErrorCodes.NOT_FOUND to WorkspaceNotFoundException("x").status,
                WorkspaceErrorCodes.LAST_ADMIN to WorkspaceLastAdminException("x").status,
                WorkspaceErrorCodes.INACTIVE to WorkspaceInactiveException("x").status,
                WorkspaceErrorCodes.NAME_INVALID to WorkspaceNameInvalidException("X!").status,
                WorkspaceErrorCodes.DUPLICATE_NAME to WorkspaceDuplicateNameException("x").status,
                WorkspaceErrorCodes.IN_USE to WorkspaceInUseException("x", mapOf("pipelines" to 1)).status,
            )
        exceptionStatus.forEach { (code, status) ->
            (code to status) shouldBe (code to docStatus.getValue(code))
        }
    }

    /**
     * #212 — `doc_url` is the catalog page at the anchor of the section that lists the code.
     * The anchors are derived here exactly as the docs renderer derives heading ids
     * (lowercase, dots dropped, anything but letters, digits, spaces and hyphens dropped,
     * spaces to hyphens — `13.7 Authentication / authorization` →
     * `137-authentication--authorization`), so a renamed heading or a code moved between
     * sections goes red here, not in a customer's 404.
     */
    @Test
    fun `every catalogued code's doc_url lands on its own section of pipeline-contract §13`() {
        val doc = RepoFiles.read(RepoFiles.PIPELINE_CONTRACT_PATH)
        val start = doc.indexOf("## 13. Error Code Catalog")
        val end = doc.indexOf("## 14.", start)
        require(start >= 0 && end > start) { "§13 not found" }
        val heading = Regex("""^### (13\.\d+ .+)$""", RegexOption.MULTILINE)
        val row = Regex("""^\|\s*`([a-z_]+(?:\.[a-z_]+)+)`\s*\|""", RegexOption.MULTILINE)
        val checked = mutableListOf<String>()
        val sections = heading.findAll(doc.substring(start, end)).toList()
        sections.forEachIndexed { i, h ->
            val from = start + h.range.last
            val to = if (i + 1 < sections.size) start + sections[i + 1].range.first else end
            val anchor = headingId(h.groupValues[1])
            row.findAll(doc.substring(from, to)).forEach { r ->
                val code = r.groupValues[1]
                withClue("$code is listed under §${h.groupValues[1]}") {
                    AuthErrorCodes.docUrl(code) shouldBe "https://datapipelines.co/docs/pipeline-contract#$anchor"
                }
                checked += code
            }
        }
        withClue("the walk must cover the catalog") { (checked.size >= 100) shouldBe true }
        // Outside every family: the catalog's top, never a 404.
        AuthErrorCodes.docUrl("nothing.like_this") shouldBe "https://datapipelines.co/docs/pipeline-contract#13-error-code-catalog"
    }

    /** The docs renderer's heading-id rule (DocsController's markdown anchors), replicated. */
    private fun headingId(heading: String): String =
        heading
            .lowercase()
            .replace(".", "")
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
            .replace(' ', '-')

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
