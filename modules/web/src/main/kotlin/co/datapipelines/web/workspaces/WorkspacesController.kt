package co.datapipelines.web.workspaces

import co.datapipelines.auth.MembershipFlags
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceInvitation
import co.datapipelines.auth.WorkspaceMemberRow
import co.datapipelines.auth.WorkspaceMembership
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The workspace endpoints (rest-api.md §17; workspaces design §9).
 *
 * [WorkspaceService] owns every rule — provisioning modes, the no-oracle 403/404 split,
 * owner-or-admin management, the pinned-workspace check for key principals (§5.6, D3),
 * open-join, `workspace.in_use` — and this controller binds payloads and projects
 * responses, exactly the house division of labor. The read rows stay floored at `read`
 * (the §7.6 convention for "any authenticated"); the five mutations are floored at
 * `author` (025 defect round — a `read`-scoped API key authenticates on every path
 * CSRF-exempt, so the floor, not only the service's role checks, must refuse it).
 */
@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspacesController(
    private val workspaces: WorkspaceService,
) {
    /** §17.1 — the caller's own memberships (design §9 "list-own"; admins list their own too — no merged view). */
    @GetMapping
    @RequiredScope(ScopeMatrix.RestOperation.WORKSPACES_READ)
    fun list(): ApiResponse<List<Map<String, Any?>>> = ApiResponse.of(workspaces.listOwn(currentPrincipal()).map { it.toResponse() })

    /** §17.2 — one workspace. Members share one 403 for unknown and not-a-member; only an admin gets the 404. */
    @GetMapping("/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.WORKSPACES_READ)
    fun get(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of(workspaces.read(currentPrincipal(), name).toResponse())

    /** §17.3 — create per provisioning mode; the creator enters as `owner`. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiredScope(ScopeMatrix.RestOperation.WORKSPACE_CREATE)
    fun create(
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val name =
            body.get("name")?.takeIf { it.isTextual }?.asText()
                ?: throw ApiException(
                    PipelineErrorCodes.Workspace.NAME_INVALID,
                    "A workspace name is required.",
                    mapOf("field" to "name"),
                )
        val displayName = body.get("display_name")?.takeIf { it.isTextual }?.asText() ?: name
        return ApiResponse.of(workspaces.create(currentPrincipal(), name, displayName).toResponse())
    }

    /** §17.4 — rename the display name; `name` is immutable v1. Owner or admin. An absent `display_name` keeps the current one. */
    @PutMapping("/{name}")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE)
    fun update(
        @PathVariable name: String,
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
        val principal = currentPrincipal()
        val current = workspaces.read(principal, name)
        val displayName = body.get("display_name")?.takeIf { it.isTextual }?.asText() ?: current.displayName
        return ApiResponse.of(workspaces.updateDisplayName(principal, name, displayName).toResponse())
    }

    /**
     * §17.5 — soft delete; `409 workspace.in_use` while content remains (each kind counts).
     *
     * An INSTANCE verb since D-R10, like create/deactivate/reactivate: deleting a workspace is
     * a super admin's, and the annotation says so rather than leaving the service to be the
     * only one who knows. Deactivation is what operators actually want; delete stays for the
     * empty case, and `workspace.in_use` refuses it otherwise.
     */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_INSTANCE_WORKSPACES)
    fun delete(
        @PathVariable name: String,
    ) {
        workspaces.delete(currentPrincipal(), name)
    }

    /**
     * §17.6 — the member listing; any member of the workspace (or an admin) may read it.
     *
     * Two arrays, never mixed (113 §B.4): `members[]` are real memberships — a client that
     * counts members counts people — and `invitations[]` are the pending, email-keyed rows
     * (auth.md §4.6) whose `users` row does not exist yet. A ghost never appears in
     * `members[]`, and a pending invitation never upgrades anybody.
     */
    @GetMapping("/{name}/members")
    @RequiredScope(ScopeMatrix.RestOperation.WORKSPACES_READ)
    fun members(
        @PathVariable name: String,
    ): ApiResponse<Map<String, List<Map<String, Any?>>>> {
        val listing = workspaces.membersWithInvitations(currentPrincipal(), name)
        return ApiResponse.of(
            mapOf(
                "members" to listing.members.map { it.toResponse() },
                "invitations" to listing.invitations.map { it.toResponse() },
            ),
        )
    }

    /**
     * §17.7 — add a member by email, with their capability flags (RBAC design §1). Workspace
     * admin or super admin; `open-join` went with the provisioning modes (D-R11). Absent flags
     * mean a VIEWER, which is the D-R11 default and the one the demo path uses.
     *
     * TWO outcomes, distinguishable by status AND body (113 §B.1), so a client never mistakes
     * a ghost for a member:
     *
     * - `200` with the membership row — the user existed and is now (or already was) a member;
     * - `202` with `{"invited": true, "email", "author", "promoter", "admin"}` — nobody by
     *   that email exists yet; an invitation (auth.md §4.6) was created, upserting over any
     *   earlier one, and their first login materialises it.
     *
     * An email the §4.3 domain allowlist would refuse at login is refused here with the SAME
     * code the login would use (`auth.login.domain_not_allowed`) at a 400 — an invitation
     * that could never be honoured is refused when it is created. A deactivated workspace is
     * `workspace.inactive` (404).
     */
    @PostMapping("/{name}/members")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun addMember(
        @PathVariable name: String,
        @RequestBody body: JsonNode,
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        val email =
            body.get("email")?.takeIf { it.isTextual }?.asText()
                ?: throw ApiException(
                    // The catalog has no workspace-domain payload code and adding one is a
                    // contract change; the surface's generic bad-parameter code (the one
                    // ApiExceptionHandler uses for missing/wrong-typed parameters) is the
                    // honest stand-in — NOT the datasource-domain code this used to emit
                    // (022 review, below-cap).
                    PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                    "A member email is required.",
                    mapOf("field" to "email"),
                )
        return when (val outcome = workspaces.addMember(currentPrincipal(), name, email, flagsOf(body))) {
            is WorkspaceService.AddMemberOutcome.Added -> {
                ResponseEntity.ok(ApiResponse.of(outcome.row.toResponse()))
            }

            is WorkspaceService.AddMemberOutcome.Invited -> {
                ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.of(outcome.toResponse()))
            }
        }
    }

    /**
     * §17.8 — remove a member. Workspace admin or super admin; removing the LAST admin is
     * `workspace.last_admin` (409), because a workspace with no admin is unmanageable.
     */
    @DeleteMapping("/{name}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun removeMember(
        @PathVariable name: String,
        @PathVariable userId: UUID,
    ) {
        workspaces.removeMember(currentPrincipal(), name, userId)
    }

    /**
     * §17.9 — revoke a pending invitation (113 §B.4). Workspace admin or super admin. An
     * email with no invitation here is `workspace.invitation.not_found` (404) — the workspace
     * resolved, so the not-found thing is the invitation. The email is normalized lowercase
     * (§4.2) before the lookup: the revoke of `Bob@Company.com` finds the row the invite of
     * `bob@company.com` created.
     */
    @DeleteMapping("/{name}/invitations/{email}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun revokeInvitation(
        @PathVariable name: String,
        @PathVariable email: String,
    ) {
        workspaces.revokeInvitation(currentPrincipal(), name, email)
    }

    /**
     * §17.10 — set an existing member's capability flags (RBAC design §1). Workspace admin or
     * super admin; demoting the LAST admin is `workspace.last_admin` (409).
     *
     * A PUT rather than a PATCH: the three flags are REPLACED wholesale, so a caller that
     * sends `{"author": true}` gets exactly an author — not an author with whatever promoter
     * flag happened to be there. Roles are additive, which makes a partial update ambiguous in
     * precisely the way this surface must not be.
     */
    @PutMapping("/{name}/members/{userId}")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE_MEMBERS)
    fun setMemberFlags(
        @PathVariable name: String,
        @PathVariable userId: UUID,
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.of(workspaces.setMemberFlags(currentPrincipal(), name, userId, flagsOf(body)).toResponse())

    /**
     * D-R10 — deactivate a workspace. Super admin. Nothing is purged, ever: it stops being
     * selectable, its endpoints 404, its keys are refused, its schedules are skipped and a
     * super admin's listing greys it. Reversible by [reactivate].
     */
    @PostMapping("/{name}/deactivate")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_INSTANCE_WORKSPACES)
    fun deactivate(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of(workspaces.deactivate(currentPrincipal(), name).toResponse())

    /** D-R10 — reactivate a deactivated workspace. Super admin, audited. */
    @PostMapping("/{name}/reactivate")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_INSTANCE_WORKSPACES)
    fun reactivate(
        @PathVariable name: String,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.of(workspaces.reactivate(currentPrincipal(), name).toResponse())

    /**
     * The three capability flags off a request body (RBAC design §1). Absent means FALSE:
     * a body that says nothing asks for a viewer, which is the least a membership can be and
     * the only safe reading of silence on a permission grant.
     *
     * `admin` normalisation (admin → author) is the service's, not this surface's — one place,
     * beside the database constraint that makes it true.
     */
    private fun flagsOf(body: JsonNode): MembershipFlags =
        MembershipFlags(
            author = body.get("author")?.asBoolean() == true,
            promoter = body.get("promoter")?.asBoolean() == true,
            admin = body.get("admin")?.asBoolean() == true,
        )

    /** §17.2's wire shape — the fields a reader is entitled to. */
    private fun Workspace.toResponse(): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to displayName,
            "is_personal" to isPersonal,
            "created_at" to createdAt.toString(),
            // D-R10: deactivation is a state a caller must be able to see, not infer from a
            // 404 somewhere else. `active` is the answer; `deactivated_at` is the evidence the
            // super admin's listing renders greyed beside it (design §6).
            "active" to isActive,
            "deactivated_at" to deactivatedAt?.toString(),
        )

    /**
     * §17.1's list-own row — a membership: the workspace's name, the caller's capability flags
     * and the join date.
     *
     * `role` is GONE from the wire with the column (D-R1/D-R2). It is replaced by the three
     * booleans, not by a computed "highest role" string: additive flags are the whole point
     * ("author who also releases" and "DevOps who only releases" are both one row), and any
     * single label would have to lie about one of them. `active` carries D-R10's state.
     */
    private fun WorkspaceMembership.toResponse(): Map<String, Any?> =
        mapOf(
            "name" to workspaceName,
            "author" to flags.author,
            "promoter" to flags.promoter,
            "admin" to flags.admin,
            "active" to workspaceActive,
            "joined_at" to joinedAt.toString(),
        )

    private fun WorkspaceMemberRow.toResponse(): Map<String, Any?> =
        mapOf(
            "user_id" to userId.toString(),
            "email" to email,
            "display_name" to displayName,
            "author" to flags.author,
            "promoter" to flags.promoter,
            "admin" to flags.admin,
            "joined_at" to joinedAt.toString(),
        )

    /**
     * §17.9's invitation row — the decision the admin made, waiting for its person (auth.md
     * §4.6). No user_id: there is no user yet; the email IS the identity.
     */
    private fun WorkspaceInvitation.toResponse(): Map<String, Any?> =
        mapOf(
            "email" to email,
            "author" to flags.author,
            "promoter" to flags.promoter,
            "admin" to flags.admin,
            "invited_by" to invitedBy.toString(),
            "invited_at" to invitedAt.toString(),
        )

    /** §17.7's `202` body — the invitation echo. `invited: true` is the flag a client checks. */
    private fun WorkspaceService.AddMemberOutcome.Invited.toResponse(): Map<String, Any?> =
        mapOf(
            "invited" to true,
            "email" to email,
            "author" to flags.author,
            "promoter" to flags.promoter,
            "admin" to flags.admin,
        )
}
