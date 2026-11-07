package co.datapipelines.application

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.pipeline.LakeAppendGrant
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant

/** Shared by every execute surface; no authority is inferred from a user id or a key's creator. */
object LakeAppendAuthorization {
    /** [at] is the entry point's check time; no grant exists for an ordinary read-only execution. */
    fun grant(
        principal: AuthenticatedPrincipal,
        writesManagedLake: Boolean,
        at: Instant,
    ): LakeAppendGrant? {
        if (!writesManagedLake) return null
        if (!principal.holds(Permission.LAKE_APPEND)) {
            throw DatapipelinesException(
                PipelineErrorCodes.Lake.APPEND_FORBIDDEN,
                "This principal does not hold the lake.append permission.",
                mapOf("required" to Permission.LAKE_APPEND.wire, "held" to principal.heldRole),
            )
        }
        val kind =
            when (principal.authMethod) {
                AuthMethod.OIDC -> "session"
                AuthMethod.API_KEY -> principal.keyKind?.wire ?: "api_key"
                AuthMethod.SYSTEM -> "system"
                AuthMethod.PROMOTION -> "promotion"
            }
        return LakeAppendGrant(principal.userId, kind, at)
    }
}
