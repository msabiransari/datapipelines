package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.LocalPasswordService
import co.datapipelines.auth.Scope
import co.datapipelines.auth.SessionRequiredException
import co.datapipelines.auth.User
import co.datapipelines.auth.UserRepository
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID

/**
 * The standing guard that CREDENTIAL-MINTING operations refuse an API-key principal
 * (`auth.session_required`, §13.7).
 *
 * The hole this closes, found by adversarial verification of the merged 026 round and
 * confirmed against the source: `AdminUsersPartialController.requireAdmin` was a pure
 * scope test, and `AuthenticatedPrincipal.isAdmin` is *defined as* holding [Scope.ADMIN] —
 * so it could not tell a browser session from a `dpk_` key. `ApiKeyFilter` has no path
 * test and `ApiKeyCredentialMatcher` makes key requests CSRF-exempt, so an admin-scoped key
 * reached these partials with a single header, created a local admin, read the one-time
 * password out of the response body, and signed in. The resulting `dp_session` is NOT
 * workspace-pinned and SURVIVES revocation of the key that created it — which defeats the
 * revocation contract that is the product's whole answer to a leaked agent key
 * (auth.md §2, §8). `POST /partials/account/password` was the same shape one floor down:
 * reachable at the `read` floor, it let a leaked key guess and then rotate its owner's
 * password.
 *
 * This is the third instance of one escalation class — 96240ed (workspace UI actions),
 * 66fa930 (workspace membership writes), and now credential minting. The lesson each time
 * is identical: a scope test cannot express "an interactive human did this".
 *
 * Non-vacuity is asserted in both directions. Every refusal below has a twin that proves
 * the SAME call succeeds for an OIDC principal, so the suite cannot go green merely because
 * the operations throw for everyone — and the pre-026 operations are pinned as still
 * key-drivable, so the deliberate boundary of the fix has to be confronted, not drifted
 * past. Falsify by deleting either `requireSessionAdmin` call or the controller's
 * `authMethod` check: the refusal tests go red immediately.
 */
class CredentialMintingSessionOnlyTest {
    private val userService = mockk<UserService>(relaxed = true)
    private val localPasswordService = mockk<LocalPasswordService>()
    private val userRepository = mockk<UserRepository>()
    private val themeResolver = mockk<ThemeResolver>()

    private val partials = AdminUsersPartialController(userService, localPasswordService)
    private val settings =
        UserSettingsController(userRepository, themeResolver, UiProperties(theme = "forest"), localPasswordService)

    private val userId = UUID.randomUUID()
    private val targetId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private fun principal(method: AuthMethod) =
        AuthenticatedPrincipal(
            userId = userId,
            email = "admin@example.com",
            displayName = "Admin",
            scopes = setOf(Scope.ADMIN),
            authMethod = method,
            workspace = WorkspaceContext(workspaceId, "acme"),
        )

    private fun authenticateAs(method: AuthMethod) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal(method), null, emptyList())
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun sampleUser() =
        User(
            id = targetId,
            email = "user@example.com",
            displayName = "Test User",
            profilePictureUrl = null,
            provider = "local",
            providerSubject = "user@example.com",
            isActive = true,
            isAdmin = false,
            createdAt = Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-08-01T00:00:00Z"),
            lastLoginAt = null,
            themePreference = null,
        )

    // ---- the refusals -------------------------------------------------------------

    @Test
    fun `an admin-scoped API key cannot create a local user`() {
        authenticateAs(AuthMethod.API_KEY)

        val thrown =
            shouldThrow<SessionRequiredException> {
                partials.createLocalUser("attacker@evil.example", "svc")
            }
        thrown.code shouldBe "auth.session.required"

        // The decisive assertion: the service is never reached, so no credential is minted
        // and nothing lands in the response body for the key holder to read.
        verify(exactly = 0) { localPasswordService.createLocalUser(any(), any(), any()) }
    }

    @Test
    fun `an admin-scoped API key cannot reset another user's password`() {
        authenticateAs(AuthMethod.API_KEY)

        shouldThrow<SessionRequiredException> { partials.toggle(targetId, "reset-password") }

        verify(exactly = 0) { localPasswordService.resetPassword(any(), any()) }
    }

    @Test
    fun `an admin-scoped API key cannot disable local access or unlock an account`() {
        authenticateAs(AuthMethod.API_KEY)

        shouldThrow<SessionRequiredException> { partials.toggle(targetId, "disable-local") }
        shouldThrow<SessionRequiredException> { partials.toggle(targetId, "unlock") }

        verify(exactly = 0) { localPasswordService.disableLocalAccess(any(), any()) }
        verify(exactly = 0) { localPasswordService.unlock(any(), any()) }
    }

    @Test
    fun `an API key cannot change the account password - the read-floor oracle is closed`() {
        authenticateAs(AuthMethod.API_KEY)

        shouldThrow<SessionRequiredException> {
            settings.changeOwnPassword("guess-1", "new-password-1", "new-password-1")
        }

        // Never reaching the service is what makes it useless as a guessing oracle: a key
        // holder learns nothing about whether "guess-1" was right.
        verify(exactly = 0) { localPasswordService.changeOwn(any(), any(), any()) }
    }

    // ---- non-vacuity: the same calls work for a real session ------------------------

    @Test
    fun `an OIDC admin session drives every one of those operations`() {
        authenticateAs(AuthMethod.OIDC)
        every { localPasswordService.createLocalUser(any(), any(), any()) } returns
            LocalPasswordService.CreateResult.Success(sampleUser(), "ABCD-EFGH-IJKL")
        every { localPasswordService.resetPassword(any(), any()) } returns "MNOP-QRST-UVWX"
        every { localPasswordService.disableLocalAccess(any(), any()) } returns true
        every { localPasswordService.unlock(any(), any()) } returns true
        every { userService.snapshot(targetId) } returns sampleUser()

        shouldNotThrowAny {
            partials.createLocalUser("new@example.com", "New User")
            partials.toggle(targetId, "reset-password")
            partials.toggle(targetId, "disable-local")
            partials.toggle(targetId, "unlock")
        }
    }

    @Test
    fun `an OIDC session can still change its own password`() {
        authenticateAs(AuthMethod.OIDC)
        // The handler now reads the gate's key before changing (forced vs voluntary response
        // shape); this test is about the credential boundary, so a voluntary change is enough.
        every { userRepository.findById(userId) } returns null
        every { localPasswordService.changeOwn(userId, "current-password-1", "new-password-1") } returns
            LocalPasswordService.ChangeResult.Success

        settings
            .changeOwnPassword("current-password-1", "new-password-1", "new-password-1")
            .statusCode
            .value() shouldBe 200
    }

    // ---- the deliberate boundary ----------------------------------------------------

    @Test
    fun `the pre-026 user-administration actions remain key-drivable - the fix is scoped to credential minting`() {
        authenticateAs(AuthMethod.API_KEY)
        every { userService.snapshot(targetId) } returns sampleUser()

        // activate/deactivate/promote/demote administer users WITHOUT emitting a usable
        // credential, and are already ratified for keys through the documented
        // /api/v1/auth/users REST twin (§7.6 USER_ADMINISTRATION). If a later change
        // wants these session-only too, that is a scope-matrix decision to argue in §7.6 —
        // this test exists so the boundary is confronted rather than silently crossed.
        shouldNotThrowAny {
            partials.toggle(targetId, "activate")
            partials.toggle(targetId, "deactivate")
            partials.toggle(targetId, "promote")
            partials.toggle(targetId, "demote")
        }
    }

    @Test
    fun `a non-admin session is still refused before the session check ever applies`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                principal(AuthMethod.OIDC).copy(scopes = setOf(Scope.READ)),
                null,
                emptyList(),
            )

        // Scope remains the first gate; the session check is an ADDITIONAL requirement on
        // top of admin, never a replacement for it.
        shouldThrow<org.springframework.security.access.AccessDeniedException> {
            partials.createLocalUser("new@example.com", "New User")
        }
    }
}
