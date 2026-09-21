package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [AuthenticatedPrincipal.isLensed] — the one predicate that decides whether the promoter
 * lens applies (roles design §3.1, D5; 178). Its truth table is the whole contract: a
 * PROMOTER role in the active workspace and nothing else; a super admin never; no workspace
 * never; every other role never. Both credential kinds are lensed alike — a promoter's key is
 * a promoter's key — and the promotion peer (no workspace) is out by construction.
 */
class PromoterLensPredicateTest {
    @Test
    fun `only a promoter is lensed - every other role, and a super admin, sees everything`() {
        WorkspaceRole.entries.forEach { role ->
            withClue("session with role $role") {
                principal(role = role).isLensed shouldBe (role == WorkspaceRole.PROMOTER)
            }
            withClue("api key with role $role — the credential axis does not change the answer") {
                principal(role = role, method = AuthMethod.API_KEY).isLensed shouldBe (role == WorkspaceRole.PROMOTER)
            }
            withClue("a super admin holding an explicit $role membership is never lensed") {
                principal(role = role, superAdmin = true).isLensed shouldBe false
            }
        }
    }

    @Test
    fun `no active workspace means no lens - and the promotion peer has none`() {
        principal(role = null).isLensed shouldBe false
        principal(role = null, method = AuthMethod.PROMOTION).isLensed shouldBe false
    }

    @Test
    fun `isLensed is not isPromoter - an admin promotes and is not lensed, a promoter is both`() {
        val admin = principal(role = WorkspaceRole.WORKSPACE_ADMIN)
        admin.isPromoter shouldBe true
        admin.isLensed shouldBe false

        val promoter = principal(role = WorkspaceRole.PROMOTER)
        promoter.isPromoter shouldBe true
        promoter.isLensed shouldBe true
    }

    private fun principal(
        role: WorkspaceRole?,
        method: AuthMethod = AuthMethod.OIDC,
        superAdmin: Boolean = false,
    ): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = UUID.randomUUID(),
            email = "p@example.com",
            displayName = "P",
            scopes = if (method == AuthMethod.API_KEY) setOf(Scope.READ, Scope.EXECUTE, Scope.AUTHOR) else emptySet(),
            authMethod = method,
            workspace = role?.let { WorkspaceContext(UUID.randomUUID(), "ws", role = it, superAdmin = superAdmin) },
            superAdmin = superAdmin,
        )
}
