package co.datapipelines.auth

import java.util.UUID

/**
 * #187 — an OIDC login arrived with an email whose stored row already belongs to a DIFFERENT
 * sign-in identity: a different provider, a different subject under the same provider, or a
 * `local`/`system` row. No row is updated; the login is refused.
 *
 * The stored identity is linked ONCE and never silently re-linked — the explicit path is the
 * super admin's identity reset (§4.2, `auth.user.identity_reset`), which returns the row to the
 * bootstrap placeholder so the NEXT sign-in with that email can claim it. Carries the STORED
 * and INCOMING provider names and never the subject values: the audit and the redirect must
 * not leak credential material.
 */
class IdentityMismatchException(
    val userId: UUID,
    val storedProvider: String,
    val incomingProvider: String,
) : RuntimeException("Email already linked to a different sign-in identity ($storedProvider)")
