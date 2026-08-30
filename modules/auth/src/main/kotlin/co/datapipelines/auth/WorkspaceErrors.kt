package co.datapipelines.auth

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409

/**
 * The `workspace.*` error codes this module raises (design §8).
 *
 * The registry of record is [Pipeline Contract §13.12]; `PipelineErrorCodes.Workspace`
 * carries the pipeline-contract-side constants and this object mirrors them exactly —
 * the same duplication pattern as [AuthErrorCodes] vs §13.7, and drift-guarded the same
 * way: `AuthErrorSpecDriftTest` asserts this set equals the doc's §13.12 table.
 */
object WorkspaceErrorCodes {
    /** 403 — the principal is not a member of the addressed workspace (design §5.1/§7). */
    const val MEMBERSHIP_REQUIRED = "workspace.membership_required"

    /** 403 — the provisioning mode forbids this caller creating a workspace (design §7). */
    const val CREATION_FORBIDDEN = "workspace.creation_forbidden"

    /** 400 — `DP-Workspace` was sent on an API-key request; a key's workspace is pinned at issuance (D3). */
    const val HEADER_FORBIDDEN = "workspace.header_forbidden"

    /**
     * 403 — an API-key principal reached a session-only workspace action (the UI's
     * create/join/members/delete/switch). Same refusal family as [HEADER_FORBIDDEN] but
     * its own condition: the request carried no `DP-Workspace` to refuse — the CREDENTIAL
     * class is wrong, and for `switch` the stake is credential minting (a `dp_session`
     * stamped from the user's scopes), which a key must never obtain.
     */
    const val SESSION_REQUIRED = "workspace.session_required"

    /**
     * 404 — an unknown workspace name, for a principal who could otherwise see any workspace
     * (a global `admin`, design §8). For everyone else unknown and non-member are the same
     * 403 [MEMBERSHIP_REQUIRED] — no existence oracle (the 019 precedent).
     */
    const val NOT_FOUND = "workspace.not_found"

    /** 400 — workspace name fails `[a-z0-9_-]+`, 1–63 (metadata-db §4.11). */
    const val NAME_INVALID = "workspace.validation.name_invalid"

    /** 409 — workspace name exists (global namespace, soft-deleted included — house rule). */
    const val DUPLICATE_NAME = "workspace.validation.duplicate_name"

    /** 409 — delete blocked: the workspace still owns non-deleted pipelines/templates/datasources (design §8). */
    const val IN_USE = "workspace.in_use"

    /** The full §13.12 set — the spec-drift test asserts this equals the doc. */
    val ALL: Set<String> =
        setOf(
            MEMBERSHIP_REQUIRED,
            CREATION_FORBIDDEN,
            HEADER_FORBIDDEN,
            SESSION_REQUIRED,
            NOT_FOUND,
            NAME_INVALID,
            DUPLICATE_NAME,
            IN_USE,
        )
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

/**
 * An API-key principal reached a SESSION-ONLY workspace action (025 A2): the UI's
 * create/join/members/delete/switch. `switch` is the sharp case — it MINTS a `dp_session`
 * from `scopesFor(user)`, so admitting a key there trades a read-scoped credential for an
 * author/admin session (the skeleton-key outcome D3's header refusal exists to prevent).
 * The REST surface under `/api/v1/workspaces` is the programmatic path for keys.
 */
class WorkspaceSessionRequiredException :
    AuthException(
        WorkspaceErrorCodes.SESSION_REQUIRED,
        HTTP_FORBIDDEN,
        "Workspace UI actions are session-only; an API key cannot drive them or mint a session",
        "API keys use the workspace REST API. Sign in to manage workspaces from the UI.",
    )

/**
 * An unknown workspace name addressed by a principal who could otherwise see any workspace —
 * a global `admin` (design §8). Members never see this: for them unknown and non-member are
 * the same 403 [WorkspaceMembershipRequiredException], so the name cannot be probed.
 */
class WorkspaceNotFoundException(
    name: String,
) : AuthException(
        WorkspaceErrorCodes.NOT_FOUND,
        HTTP_NOT_FOUND,
        "Workspace '$name' not found.",
        "We couldn't find that workspace.",
        details = mapOf("workspace" to name),
    )

/** A workspace name outside `[a-z0-9_-]{1,63}` (metadata-db §4.11) — a 400 at the CRUD surface. */
class WorkspaceNameInvalidException(
    name: String,
) : AuthException(
        WorkspaceErrorCodes.NAME_INVALID,
        HTTP_BAD_REQUEST,
        "Workspace name '$name' does not match [a-z0-9_-]{1,63}.",
        "Workspace names use lowercase letters, digits, dashes and underscores — 1 to 63 characters.",
        details = mapOf("workspace" to name.take(MAX_ECHOED_NAME_CHARS)),
    )

/**
 * The workspace name is taken (design §8) — the global namespace, soft-deleted rows included
 * (the house "name not reusable until hard-deleted" rule, unchanged from pipelines/templates).
 */
class WorkspaceDuplicateNameException(
    name: String,
) : AuthException(
        WorkspaceErrorCodes.DUPLICATE_NAME,
        HTTP_CONFLICT,
        "A workspace named '$name' already exists.",
        "A workspace with that name already exists. Pick a different name.",
        details = mapOf("workspace" to name),
    )

/**
 * Delete blocked: the workspace still owns non-deleted content (design §8) — or a member
 * removal would orphan it ([blockedBy] names which). [counts] names what blocks, by kind.
 */
class WorkspaceInUseException(
    name: String,
    counts: Map<String, Int>,
    blockedBy: String? = null,
) : AuthException(
        WorkspaceErrorCodes.IN_USE,
        HTTP_CONFLICT,
        if (blockedBy == null) {
            "Workspace '$name' still owns non-deleted content: $counts."
        } else {
            "Workspace '$name' refuses this operation: blocked by $blockedBy."
        },
        if (blockedBy == null) {
            "This workspace still has content in it. Delete its pipelines, templates and datasources first."
        } else {
            "This workspace needs at least one owner. Ownership transfer is not available yet."
        },
        details =
            if (blockedBy == null) {
                mapOf("workspace" to name, "counts" to counts)
            } else {
                mapOf("workspace" to name, "blocked_by" to blockedBy)
            },
    )

private const val MAX_ECHOED_NAME_CHARS = 63
