package co.datapipelines.auth

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403

/**
 * The `workspace.*` error codes this slice raises (design §8).
 *
 * The registry of record is [Pipeline Contract §13.12]; `PipelineErrorCodes.Workspace`
 * carries the pipeline-contract-side constants and this object mirrors them exactly —
 * the same duplication pattern as [AuthErrorCodes] vs §13.7, and drift-guarded the same
 * way: `AuthErrorSpecDriftTest` asserts this set equals the doc's §13.12 table.
 *
 * The CRUD-only codes (`workspace.not_found`, `workspace.validation.*`, `workspace.in_use`)
 * are deliberately absent: they land with the REST surface slice (021), which is the slice
 * that raises them.
 */
object WorkspaceErrorCodes {
    /** 403 — the principal is not a member of the addressed workspace (design §5.1/§7). */
    const val MEMBERSHIP_REQUIRED = "workspace.membership_required"

    /** 403 — the provisioning mode forbids this caller creating a workspace (design §7). */
    const val CREATION_FORBIDDEN = "workspace.creation_forbidden"

    /** 400 — `DP-Workspace` was sent on an API-key request; a key's workspace is pinned at issuance (D3). */
    const val HEADER_FORBIDDEN = "workspace.header_forbidden"

    /** The full §13.12 set — the spec-drift test asserts this equals the doc. */
    val ALL: Set<String> = setOf(MEMBERSHIP_REQUIRED, CREATION_FORBIDDEN, HEADER_FORBIDDEN)
}

/**
 * The addressed workspace is not one the principal belongs to (design §5.1): a
 * `DP-Workspace` switch naming a non-membership, or any workspace-scoped operation from a
 * principal with zero memberships (possible under `closed` provisioning, design §7). The
 * same code covers "unknown name" and "not a member" so the switch cannot be used to probe
 * workspace existence.
 */
class WorkspaceMembershipRequiredException(
    detail: String = "Principal is not a member of the addressed workspace",
) : AuthException(
        WorkspaceErrorCodes.MEMBERSHIP_REQUIRED,
        HTTP_FORBIDDEN,
        detail,
        "You are not a member of that workspace.",
    )

/** The provisioning mode forbids this creation (design §7): `closed` refuses non-admins outright. */
class WorkspaceCreationForbiddenException(
    mode: WorkspaceProvisioningMode,
) : AuthException(
        WorkspaceErrorCodes.CREATION_FORBIDDEN,
        HTTP_FORBIDDEN,
        "Workspace creation is not permitted in provisioning mode '${mode.wire}'",
        "Creating a workspace is not permitted on this server. Contact an administrator.",
        details = mapOf("provisioning_mode" to mode.wire),
    )

/**
 * `DP-Workspace` on an API-key request (design §5.2): the key's pinned workspace IS the
 * context, and silently ignoring the header would train agents on a lie — so it is a
 * catalogued refusal, never a quiet no-op.
 */
class WorkspaceHeaderForbiddenException :
    AuthException(
        WorkspaceErrorCodes.HEADER_FORBIDDEN,
        HTTP_BAD_REQUEST,
        "DP-Workspace is not accepted on API-key requests; the key's workspace is pinned at issuance",
        "API keys are pinned to one workspace. Remove the DP-Workspace header and try again.",
    )
