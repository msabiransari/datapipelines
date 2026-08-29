package co.datapipelines.web.workspaces

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.auth.Workspace
import co.datapipelines.auth.WorkspaceMemberRow
import co.datapipelines.auth.WorkspaceMembership
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.api.ApiResponse
import co.datapipelines.web.api.currentPrincipal
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
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
 * owner-or-admin management, open-join, `workspace.in_use` — and this controller binds
 * payloads and projects responses, exactly the house division of labor. `read` is the
 * scope floor for every row (the §7.6 convention for "any authenticated"); the real
 * gates are the service's membership/role checks, default-deny.
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

    /** §17.5 — soft delete; `409 workspace.in_use` while content remains (each kind counts). Owner or admin. */
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE)
    fun delete(
        @PathVariable name: String,
    ) {
        workspaces.delete(currentPrincipal(), name)
    }

    /** §17.6 — the member listing; any member of the workspace (or an admin) may read it. */
    @GetMapping("/{name}/members")
    @RequiredScope(ScopeMatrix.RestOperation.WORKSPACES_READ)
    fun members(
        @PathVariable name: String,
    ): ApiResponse<List<Map<String, Any?>>> = ApiResponse.of(workspaces.members(currentPrincipal(), name).map { it.toResponse() })

    /**
     * §17.7 — add a member by email. Owner or admin — except `open-join`, where adding
     * your own email is the self-service join. An unknown email is the §16.3
     * unknown-user stand-in (§13.7 has no `auth.user.not_found`).
     */
    @PostMapping("/{name}/members")
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE)
    fun addMember(
        @PathVariable name: String,
        @RequestBody body: JsonNode,
    ): ApiResponse<Map<String, Any?>> {
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
        val added =
            try {
                workspaces.addMember(currentPrincipal(), name, email)
            } catch (e: WorkspaceService.UnknownMemberEmailException) {
                throw unknownUser(e.email, e) // the §16.3 stand-in mapping IS the handler
            }
        return ApiResponse.of(added.toResponse())
    }

    /** §17.8 — remove a member. Owner or admin; removing an owner is the `in_use` 409 (`blocked_by: owner_membership`). */
    @DeleteMapping("/{name}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiredScope(ScopeMatrix.RestOperation.MANAGE_WORKSPACE)
    fun removeMember(
        @PathVariable name: String,
        @PathVariable userId: UUID,
    ) {
        workspaces.removeMember(currentPrincipal(), name, userId)
    }

    private fun unknownUser(
        email: String,
        cause: Throwable?,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Execution.NOT_FOUND,
            "No user with email '$email'.",
            mapOf(ApiErrors.REASON to "user_not_found", "email" to email),
            cause,
        )

    /** §17.2's wire shape — the fields a reader is entitled to. */
    private fun Workspace.toResponse(): Map<String, Any?> =
        mapOf(
            "name" to name,
            "display_name" to displayName,
            "is_personal" to isPersonal,
            "created_at" to createdAt.toString(),
        )

    /** §17.1's list-own row — a membership: the workspace's name plus the caller's role and join date. */
    private fun WorkspaceMembership.toResponse(): Map<String, Any?> =
        mapOf(
            "name" to workspaceName,
            "role" to role.wire,
            "joined_at" to joinedAt.toString(),
        )

    private fun WorkspaceMemberRow.toResponse(): Map<String, Any?> =
        mapOf(
            "user_id" to userId.toString(),
            "email" to email,
            "display_name" to displayName,
            "role" to role.wire,
            "joined_at" to joinedAt.toString(),
        )
}
