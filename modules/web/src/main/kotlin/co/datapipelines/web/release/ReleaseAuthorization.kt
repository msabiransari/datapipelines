package co.datapipelines.web.release

import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.currentPrincipal

/**
 * Who is releasing, and may they flip this family (record §5, FROZEN): a cascade needs the caller's native `*.release`
 * for the root AND for each version it actually flips — a parent's release is never delegated authority over another
 * family. The route's `@RequiredScope` has already admitted the root; the coordinator asks again per flipped family,
 * before any preparation or flip.
 */
interface ReleaseAuthorization {
    /** The authenticated caller the audit rows name. */
    fun principal(): AuthenticatedPrincipal

    /** Refuses with the matrix's own code (`auth.role_required`) unless the caller may release [family]. */
    fun requireRelease(family: ReleaseFamily)

    /**
     * The same decision for an EXPLICIT [principal] — a boot-time act with no request (#786: the demo seeder releases
     * its API as the system actor through the coordinator, with the same matrix question and the same strict audit).
     */
    fun requireRelease(
        principal: AuthenticatedPrincipal,
        family: ReleaseFamily,
    )
}

/** Production: the request's principal, judged by THE authorization function the interceptor also calls. */
class MatrixReleaseAuthorization : ReleaseAuthorization {
    override fun principal(): AuthenticatedPrincipal = currentPrincipal()

    override fun requireRelease(family: ReleaseFamily) = requireRelease(principal(), family)

    override fun requireRelease(
        principal: AuthenticatedPrincipal,
        family: ReleaseFamily,
    ) {
        val decision = ScopeMatrix.allowed(principal, permissionOf(family), principal.workspace)
        if (decision is ScopeMatrix.Decision.Refused) {
            throw DatapipelinesException(code = decision.code, message = decision.message, details = decision.details)
        }
    }

    companion object {
        /** Each family's native release permission — the six rows of auth §7.6, no new string. */
        fun permissionOf(family: ReleaseFamily): Permission =
            when (family) {
                ReleaseFamily.PIPELINE -> Permission.PIPELINE_RELEASE
                ReleaseFamily.TEMPLATE -> Permission.TEMPLATE_RELEASE
                ReleaseFamily.PARAMETER_SET -> Permission.PARAMETER_SET_RELEASE
                ReleaseFamily.VISUALIZATION -> Permission.VISUALIZATION_RELEASE
                ReleaseFamily.DASHBOARD -> Permission.DASHBOARD_RELEASE
                ReleaseFamily.ENDPOINT -> Permission.ENDPOINT_RELEASE
            }
    }
}
