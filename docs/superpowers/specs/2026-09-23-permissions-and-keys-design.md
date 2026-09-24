# Permissions and keys: design record

**Status:** RATIFIED 2026-09-23; **amended 2026-09-24** (§10, the owner's rulings A1–A8 on the
pre-implementation review). The owner ruled PK1–PK9 (§1) and agreed O1–O3 (§9) on 2026-09-23.
**Date:** 2026-09-23.
**Delivery:** [GitHub issue #215](https://github.com/msabiransari/datapipelines/issues/215).
**Amends:** the [roles and permissions design](2026-09-20-roles-permissions-design.md) (ratified
2026-09-20). Its roles, the promoter lens (§3.1), executions ownership (§3.2), deactivation (§3.5)
and membership-bound MCP keys (§3.7) stand. This record replaces its "no permission strings"
principle and its §3.6 (scopes on user keys).
**Precedes:** the scheduler ([#9](https://github.com/msabiransari/datapipelines/issues/9);
[scheduler design revision](2026-09-22-scheduler-design-revision.md) §9).
**Distribution:** contributor design material; not packaged as product documentation.

Everything this record states about main was checked against `10328230`. Every operation and tool in
§2 comes from `ScopeMatrix.kt` (30 `RestOperation` constants, 41 MCP tools), and every route family
comes from `auth.md` §7.6.

## 1. Decisions (owner rulings, 2026-09-23)

| # | Ruling |
|---|---|
| PK1 | Permissions are named `<functionality>.<permission>`, one per piece of functionality (§2). Roles are assigned permissions. Code asks whether a principal holds a permission; it never compares role names. |
| PK2 | Every key carries a role chosen from pre-created roles. Who created a key does not matter when it is used. |
| PK3 | No key can hold workspace admin or super admin. The key dialog never lists them. |
| PK4 | The MCP key stays automatic: one per user per workspace, minted at login, not in the key dialog. It takes the member's current role, with workspace admin and super admin capped at author. A super admin with no membership in the workspace gets viewer. |
| PK5 | Every other key gets its own identity: a `users` row that cannot log in, named after the key and used for attribution everywhere. `users` gains a kind: `human`, `service`, `system`. |
| PK6 | Pre-created key roles: API keys offer `api_caller`; server keys offer `promotion_receiver`. **Amended 2026-09-24 (A1): no key carries the `viewer` role — `viewer` is a member role for the UI, and a key has no secret it belongs to.** The role a scheduler key (#9) carries is decided in the scheduler round. |
| PK7 | Creating a key needs that key type's create permission: API keys, workspace admin and super admin; server keys, super admin; scheduler keys (#9), author and above. |
| PK8 | Key scopes (`read` / `execute` / `author`) are removed; the key's role replaces them. |
| PK9 | Existing keys migrate: API keys get their own identity and `api_caller`; server keys get their own identity and `promotion_receiver`; MCP keys only take the cap. Past executions keep their attribution. |

Unchanged and restated because they constrain this design:
- **D2:** ownership is not an authorization dimension.
- **D7:** a super admin holds every workspace permission.
- **D11:** execution reads are own-only below workspace admin.
- The promoter lens.
- **Roles are fixed.** There are no custom or editable roles (roles design §6).

## 2. The catalog

The legend for the role columns:
- ✓ allowed; ✗ refused
- **own:** the caller's own executions only
- **lens:** the promoter lens narrows what is returned
- **fenced:** reachable only through the promotion server-key route family, whatever the role

For keys: the MCP key follows PK4; the key-only roles (`api_caller`, `promotion_receiver`) are listed
in §3 — no key holds a member role (A1). A key that serves published endpoints serves only the
paths bound to it, whatever else it holds (A1; the path-binding rule of auth §7.7 is unchanged).

For browser sessions, every cell is today's access. A cell marked (C1) is where a *key* holding that
role gains access it lacks today (§5).

### 2.1 Pipelines and templates

| Permission | Today | Surfaces | viewer | author | promoter | ws admin | super admin |
|---|---|---|---|---|---|---|---|
| `pipeline.read` | `READ_RESOURCES` | `GET /api/v1/pipelines/**` (incl. export, versions), pipeline pages and partials; MCP `pipelines_list`, `pipelines_get` | ✓ | ✓ | lens | ✓ | ✓ |
| `pipeline.create` | `MUTATE_PIPELINES_TEMPLATES` | `POST /api/v1/pipelines`; MCP `pipelines_create` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `pipeline.update` | `MUTATE_PIPELINES_TEMPLATES` | `PUT /api/v1/pipelines/{id}`, the editor's write partials; MCP `pipelines_update` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `pipeline.version.manage` | `MUTATE_PIPELINES_TEMPLATES` | `POST …/{id}/draft/discard`, `POST …/{id}/versions/{v}/discard`, `POST …/{id}/versions/{v}/restore`, `DELETE …/{id}/versions/{v}` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `pipeline.delete` | `MUTATE_PIPELINES_TEMPLATES` | `DELETE /api/v1/pipelines/{id}` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `pipeline.import` | `MUTATE_PIPELINES_TEMPLATES` | `POST /api/v1/pipelines/import` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `pipeline.release` | `RELEASE_VERSION` | `POST /api/v1/pipelines/{id}/release`, the release dialog | ✗ | ✓ | ✗ | ✓ | ✓ |
| `pipeline.switch_version` | `SWITCH_SERVED_VERSION` | `POST /api/v1/pipelines/{id}/current` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `pipeline.execute` | `EXECUTE_PIPELINE` | `POST /api/v1/pipelines/{id}/execute`, the editor page; MCP `pipelines_execute` | ✓ | ✓ | ✗ | ✓ | ✓ |
| `pipeline.run_checks` | `EXECUTE_PIPELINE` | `POST …/{id}/versions/{v}/checks/run` (the release-check run); MCP `pipelines_run_checks` | ✓ | ✓ | ✗ | ✓ | ✓ |
| `pipeline.execute_node` | `EXECUTE` + `author` scope | MCP `pipelines_execute_node` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `template.read` | `READ_RESOURCES` | `GET /api/v1/templates/**`, template pages; MCP `templates_list`, `templates_get`, `templates_used_by` | ✓ | ✓ | lens | ✓ | ✓ |
| `template.create` | `MUTATE_PIPELINES_TEMPLATES` | `POST /api/v1/templates`; MCP `templates_create` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `template.update` | `MUTATE_PIPELINES_TEMPLATES` | `PUT /api/v1/templates`, the editor's write partials; MCP `templates_update` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `template.version.manage` | `MUTATE_PIPELINES_TEMPLATES` | `POST /api/v1/templates/draft/discard`, `…/version/discard`, `…/version/restore`, `DELETE /api/v1/templates/version`, the lifecycle dialogs; MCP `templates_purge_draft` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `template.delete` | `MUTATE_PIPELINES_TEMPLATES` | `DELETE /api/v1/templates` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `template.import` | `MUTATE_PIPELINES_TEMPLATES` | `POST /api/v1/templates/import` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `template.render` | `MUTATE_PIPELINES_TEMPLATES` (REST), `AUTHOR` (MCP) | `POST /api/v1/templates/render`, `POST /partials/templates/render` (the editor preview); MCP `templates_render` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `template.release` | `RELEASE_VERSION` | `POST /api/v1/templates/release` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `template.switch_version` | `SWITCH_SERVED_VERSION` | `POST /api/v1/templates/current` | ✗ | ✓ | ✗ | ✓ | ✓ |

Lane 7b (#7) adds `templates_evaluate` and `POST /api/v1/templates/evaluate` on the `templates_render`
row. They become `template.evaluate` with the same cells when this work merges main after 7b.

### 2.2 Executions

| Permission | Today | Surfaces | viewer | author | promoter | ws admin | super admin |
|---|---|---|---|---|---|---|---|
| `execution.read` | `READ_EXECUTIONS` | `GET /api/v1/executions[/{id}]`, the executions screens, the SSE replay; MCP `executions_list`, `executions_get` | own | own | ✗ | ✓ | ✓ |
| `execution.result.read` | `RETRIEVE_RESULT` | `GET /api/v1/executions/{id}/result`; MCP `executions_get_result` | own | own | ✗ | ✓ | ✓ |
| `execution.read_all` | the `isWorkspaceAdmin` read filter | lifts "own" on the two rows above | ✗ | ✗ | ✗ | ✓ | ✓ |
| `execution.cancel` | `CANCEL_EXECUTION` | `DELETE /api/v1/executions/{id}`; MCP `executions_cancel` | own | own | ✗ | ✓ | ✓ |
| `execution.cancel_all` | the in-handler admin check | lifts "own" on cancel | ✗ | ✗ | ✗ | ✓ | ✓ |

`execution.read_all` and `execution.cancel_all` turn two role checks hidden in read paths into
permissions. The own-only filter then asks for a permission instead of `isWorkspaceAdmin`.

### 2.3 Datasources and the lake catalog

| Permission | Today | Surfaces | viewer | author | promoter | ws admin | super admin |
|---|---|---|---|---|---|---|---|
| `datasource.read` | `READ_RESOURCES` | `GET /api/v1/datasources` (metadata); MCP `datasources_list`, `datasources_get`, `datasources_get_table_stats` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `datasource.introspect` | `INTROSPECT_DATASOURCE` (`VIEW` + `author` scope) | `GET …/schemas`, `…/tables`, `…/columns`, the browse partials; MCP `datasources_get_schemas`, `datasources_get_tables`, `datasources_get_columns` | ✓ (C1) | ✓ | ✓ (C1) | ✓ | ✓ |
| `datasource.test` | `TEST_DATASOURCE` (`EXECUTE` + `author` scope) | `POST /api/v1/datasources/{name}/test`; MCP `datasources_test` | ✓ (C1) | ✓ | ✗ | ✓ | ✓ |
| `datasource.preview_rows` | `VIEW` + `author` scope, MCP only | MCP `datasources_preview_rows` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `datasource.sql_probe` | `VIEW` + `author` scope, MCP only | MCP `sql_probe` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `datasource.manage` | `MUTATE_WORKSPACE_DATASOURCES` | `POST`/`PUT`/`DELETE` on `/api/v1/datasources`, the datasource partials; the `member-datasources-enabled` gate and the in-process and instance rules stay in the service | ✗ | ✗ | ✗ | ✓ | ✓ |
| `datasource.grant` | `MANAGE_DATASOURCE_GRANTS` | `POST`/`DELETE …/grants/{workspace}` | ✗ | ✗ | ✗ | ✗ | ✓ |
| `lake_table.manage` | `MUTATE_LAKE_TABLES` | the three lake-table routes; MCP `lake_tables_register`, `lake_tables_import`, `lake_tables_unregister` | ✗ | ✓ | ✗ | ✓ | ✓ |

`datasource.preview_rows` and `datasource.sql_probe` read table data rather than schema. Their cells
keep today's effective access: only an author-scoped key could reach those two MCP tools, and no
browser route reaches them.

### 2.4 Published endpoints, semantics, reference

| Permission | Today | Surfaces | viewer | author | promoter | ws admin | super admin |
|---|---|---|---|---|---|---|---|
| `endpoint.read` | `READ_RESOURCES` | `GET /api/v1/endpoints`; MCP `endpoints_list`, `endpoints_get` | ✓ | ✓ | lens | ✓ | ✓ |
| `endpoint.publish` | `MANAGE_ENDPOINTS` | `POST /api/v1/endpoints`; MCP `endpoints_create` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `endpoint.unpublish` | `MANAGE_ENDPOINTS` | `DELETE /api/v1/endpoints`; MCP `endpoints_delete` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `endpoint.serve` | `SERVE_PUBLISHED_ENDPOINT` | `GET /api/<category>/<version>/<path…>`; the path-binding rule of auth §7.7 is unchanged | ✓ | ✓ | ✓ | ✓ | ✓ |
| `semantic.read` | `VIEW` | MCP `semantics_list` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `semantic.record` | `AUTHOR` | MCP `semantics_record` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `semantic.retire` | `AUTHOR` | MCP `semantics_retire` | ✗ | ✓ | ✗ | ✓ | ✓ |
| `calculator.read` | `VIEW` | MCP `calculators_list`, `calculators_get` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `docs.read` | `VIEW` | MCP `docs_list`, `docs_get` | ✓ | ✓ | ✓ | ✓ | ✓ |

### 2.5 Promotion

| Permission | Today | Surfaces | viewer | author | promoter | ws admin | super admin |
|---|---|---|---|---|---|---|---|
| `promotion.read` | `PROMOTION_READ` | `GET /promotion` | ✗ | ✓ | ✓ | ✓ | ✓ |
| `promotion.promote` | `PROMOTE_VERSION` | `POST /promotion/promote` (the sending side) | ✗ | ✗ | ✓ | ✓ | ✓ |
| `promotion.inventory.read` | `READ_RESOURCES` as the System account | `GET /api/v1/promotion/inventory` (the receiving side) | fenced | fenced | fenced | fenced | fenced |
| `promotion.push` | `MUTATE_PIPELINES_TEMPLATES` as the System account | `POST /api/v1/promotion/push` (the receiving side) | fenced | fenced | fenced | fenced | fenced |

Only the `promotion_receiver` key role holds the two receiving permissions (§3). The route filter
that admits only a server key on `/api/v1/promotion/` stays, so a super admin's session does not
reach these routes even though D7 would grant the permission.

### 2.6 Keys, workspaces, users, profile

| Permission | Today | Surfaces | viewer | author | promoter | ws admin | super admin |
|---|---|---|---|---|---|---|---|
| `mcp_key.own` | `VIEW_OWN_MCP_KEY` | `GET /api/v1/auth/api-keys[/mine]`, own-key delete, the MCP key chip and one-shot secret | ✓ | ✓ | ✓ | ✓ | ✓ |
| `api_key.read` | `MANAGE_API_KEYS` | `GET /api-keys` and its list partials | ✗ | ✗ | ✗ | ✓ | ✓ |
| `api_key.create` | `MANAGE_API_KEYS` | `POST /api/v1/auth/api-keys` (kind `endpoint`), `POST /partials/api-keys` | ✗ | ✗ | ✗ | ✓ | ✓ |
| `api_key.revoke` | `MANAGE_API_KEYS` | `DELETE /partials/api-keys/{keyId}` | ✗ | ✗ | ✗ | ✓ | ✓ |
| `api_key.bind` | `MANAGE_API_KEYS` | `POST /partials/api-keys/{keyId}/bindings`, `POST`/`DELETE /api/v1/endpoints/bindings` | ✗ | ✗ | ✗ | ✓ | ✓ |
| `server_key.create` | `MANAGE_API_KEYS` + a super-admin rule in the service | `POST /api/v1/auth/api-keys` (kind `server`) | ✗ | ✗ | ✗ | ✗ | ✓ |
| `server_key.revoke` | the same | revoking a server key | ✗ | ✗ | ✗ | ✗ | ✓ |
| `workspace.switch` | `WORKSPACE_SWITCH` | `POST /workspace/switch`, `GET /api/v1/workspaces` (own memberships) | ✓ | ✓ | ✓ | ✓ | ✓ |
| `workspace.read` | `WORKSPACES_READ` | `GET /workspaces`, `GET /api/v1/workspaces/{name}[/members]` | ✗ | ✗ | ✗ | ✓ | ✓ |
| `workspace.update` | `MANAGE_WORKSPACE` | `PUT`/`DELETE /api/v1/workspaces/{name}`, the display-name form | ✗ | ✗ | ✗ | ✓ | ✓ |
| `workspace.members.manage` | `MANAGE_WORKSPACE_MEMBERS` | members, roles, invitations, revoke a member's key | ✗ | ✗ | ✗ | ✓ | ✓ |
| `workspace.create` | `WORKSPACE_CREATE` | `POST /api/v1/workspaces`, `POST /workspaces/create` | ✗ | ✗ | ✗ | ✗ | ✓ |
| `workspace.lifecycle` | `MANAGE_INSTANCE_WORKSPACES` | deactivate, reactivate, delete | ✗ | ✗ | ✗ | ✗ | ✓ |
| `user.manage` | `USER_ADMINISTRATION` | `/api/v1/auth/users/**`, `/admin/users` | ✗ | ✗ | ✗ | ✗ | ✓ |
| `user.identity_reset` | `USER_IDENTITY_RESET` | `PATCH /partials/admin/users/{userId}/identity-reset` | ✗ | ✗ | ✗ | ✗ | ✓ |
| `profile.read` | `CURRENT_PRINCIPAL` | `GET /api/v1/auth/me` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `profile.preference` | `PROFILE_PREFERENCE` | `PATCH /partials/profile/theme` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `profile.password` | `CHANGE_OWN_PASSWORD` | `POST /partials/account/password` | ✓ | ✓ | ✓ | ✓ | ✓ |

The catalog holds 64 permissions: 20 in §2.1, 5 in §2.2, 8 in §2.3, 9 in §2.4, 4 in §2.5 and 18 in
§2.6. Every one of the 30 operations and 41 tools maps to exactly one of them.

**Granularity rule.** A permission exists for every action a person can take separately. Two
permissions stay separate even while every role holds both (for example `pipeline.create` and
`pipeline.import`), so a later ruling can split them without touching code.

## 3. Keys

### 3.1 Key types

| Key type (wire kind) | Created by | Roles offered | Acts as | Lifetime |
|---|---|---|---|---|
| MCP key (`user`) | automatic at login (D16) | none; the member's role per PK4 | the member | ends with the membership (#200) |
| API key (`endpoint`) | `api_key.create` | `api_caller` (A1: `viewer` withdrawn) | its own identity (PK5) | until revoked or expired |
| Server key (`server`) | `server_key.create` | `promotion_receiver` | its own identity | until revoked or expired |
| Scheduler key (#9) | `scheduler_key.create` (author and above) | decided in the scheduler round (A1: not `viewer`) | its own identity | until revoked; lands with the scheduler |

### 3.2 Pre-created key roles

| Key role | Permissions | Notes |
|---|---|---|
| `api_caller` | `endpoint.serve` for the paths bound to the key; `execution.result.read` for the executions the key started | Exactly today's endpoint key, which is why every existing API key migrates to it (PK9). The only role an API key may hold (A1). |
| `promotion_receiver` | `promotion.inventory.read`, `promotion.push` | Replaces today's System-account-with-author authority on the receiving side |

Spelling (A5): key roles are `snake_case` everywhere — storage, wire, the CHECK and the dialog's
values — like the member roles (`workspace_admin`); the UI shows a human label.

A key's role is stored on the key row (`api_keys.role`), not on a membership. This is a
recommendation, because putting it on a membership would make every members list, invitation and
"keep one admin" check handle identities that are not people. A database CHECK makes PK3 and PK6
facts:

```
(kind = 'user'     AND role IS NULL)
OR (kind = 'endpoint' AND role = 'api_caller')
OR (kind = 'server'   AND role = 'promotion_receiver')
```

If scheduler keys are stored in `api_keys` (still open, §8), the scheduler round widens this CHECK.

**Creation limit (O3, ruled).** A key's role must not hold a permission its creator lacks. With the roles offered in §3.1 this holds by construction; a test pins it, so that a
later role cannot slip past it.

### 3.3 Identities (PK5)

- `users.kind`: `human` | `service` | `system`. The backfill sets `system` on the existing System
  row (auth §4.5) and `human` on every other row.
- The identity is built exactly like the System account, so login is impossible by construction:
  - it is created in the same transaction as the key
  - `display_name` is the key's name (keys have no rename; A6)
  - `email` is `<key-id>@keys.invalid`, under RFC 2606's unresolvable `.invalid`
  - `provider` is `key`, reserved at startup like `system`, `local` and `bootstrap`
  - `provider_subject` is the key id
  - it has no password and `is_admin` is false
- Identities hold **no membership**; their authority is the key's role in the key's pinned workspace.
- Revoking the key deactivates the identity (the deactivation mechanism of roles design §3.5).
  History keeps it and shows "<key name> (API key)".
- **Lifecycle is derived, never cascaded (A2).** Deactivating or deleting a workspace does NOT
  revoke its keys or deactivate their identities (that would erase the record of manual
  deactivations, and reactivating the workspace would re-enable them). Instead the ONE query that
  resolves a principal's permissions checks that the workspace is active AND that the user,
  identity or key is active and not revoked, and returns NO permissions otherwise — for sessions,
  MCP keys, API keys and server keys alike. Reactivating the workspace restores exactly what was
  active before. A gate per condition (§7, gate 10).
- **Identities are managed only through their key (A3).** The user-admin routes (`user.manage`,
  `user.identity_reset`: list, deactivate, reactivate, identity reset, password) refuse a non-human
  row with not-found semantics, so an admin cannot act on an identity behind its key's back; the
  admin users page, members lists and invitations show `human` rows only.
- Attribution: `pipeline_executions.executed_by`, `audit_log.user_id` and every `created_by`
  written on a key's behalf name the identity. `executed_by_key_kind` stays.
- The per-user concurrency limit (`ExecutionSlots`, keyed on `executed_by`) becomes a per-key
  limit.
- The admin users page, members lists and invitations show `human` rows only. Identities appear on
  the Keys page beside their key.

### 3.4 The MCP key (PK4)

Its role is re-read on every request:
- a member's role, with workspace admin mapped to author
- a super admin who is a member: that membership's role capped at author
- a super admin with no membership: viewer

No MCP tool needs more than author: `MCP_TOOL_MIN_PERMISSION` holds 20 `VIEW`, 8 `EXECUTE` and 13
`AUTHOR` entries.

## 4. Scopes removed (PK8)

This removes:
- the `Scope` enum
- `RestOperation.minScope` and `MCP_TOOL_MIN_SCOPE`
- the `api_keys.scopes` column
- the key-scope tables in auth §7.5 and §7.6
- the issuance code `auth.key_scope_unavailable`

`RestOperation` itself is retired: handlers and tools declare their catalog permission directly.
Codes that still mean something (for example `auth.key_issuer_role_lost`, for an MCP key whose
member lost the role) are kept.

## 5. Behaviour changes (ratified)

**C1. Viewer and promoter keys gain schema introspection, and viewer keys gain the connection
test.**
- On main, the scope minted from the role blocks a viewer's key (`execute` scope) and a promoter's
  key (`read` scope) from `datasources_get_schemas`, `_tables`, `_columns`, `datasources_test`, and
  the REST introspection and test routes. Their browser sessions already do all of this, per the
  2026-09-20 rulings "introspection is reading" and "the test follows execute".
- With scopes gone, the key follows the role.
- `datasource.preview_rows`, `datasource.sql_probe` and `pipeline.execute_node` stay at author and
  above, so no key gains access to table data.
- Ruled: accepted (O1).

**C2. MCP keys follow role changes at once.** On main the scope is fixed when the key is minted, so
a viewer promoted to author needed a new key to author. With this change the promotion takes
effect on the next request.

**C3. Admin MCP keys are capped at author.** A workspace admin's or super admin's MCP key sees only
its own executions over MCP. Nothing else changes over MCP (§3.4).

**C4. The receiving side of promotion narrows.** A server key authorizes inventory and push only,
instead of acting as the System account with author authority. Received versions are attributed to
the key's identity instead of System.

**C5. API key runs are attributed to the key.**
- `executed_by` moves from the key's creator to the key's identity for new runs.
- Visibility is unchanged: such runs were already excluded from the creator's own runs and shown to
  workspace admins (D11).
- The concurrency limit becomes per key.

## 6. Delivery (one branch, three slices, each gated)

Branch `feat/permissions-catalog`, worktree `.claude/worktrees/215`. Code starts after lane 7b
merges: 7b owns `ScopeMatrix`'s MCP rows and the §7.6 counts, and this branch merges main before
the first code commit. Migration: the next free number at that point (7b takes V33).

**(a) The catalog, with no change to anyone's access.**
- `Permission` becomes the §2 catalog (wire form `pipeline.read`), with one role-to-permissions
  table in code.
- Every handler, UI route and MCP tool declares exactly one catalog permission.
- `auth.md` §7.6 is rewritten as the catalog table in the same commits.
- Scopes still exist in this slice, so key behaviour is unchanged — which requires (A4) that each
  catalog permission carries the operation's former `minScope` in a `Permission → Scope` table the
  scope check reads; slice (b) deletes that table with the scopes. `RestOperation` may be retired in
  (a) only if that table replaces it one-for-one.
- Counts are re-derived from the code on the lane's base, never copied from this record (A7): main
  carries 42 MCP tools since lane 7b (`templates_evaluate`, on the `templates_render` row).
- Proof: the role walk passes with unchanged expectations, and the new catalog drift gate fails if
  code and document disagree in either direction.

**(b) Key identities and key roles.**
- `users.kind`, identities, `api_keys.role` with its CHECK, the migration (PK9), and removal of
  scopes (§4). C1–C5 land here.
- The skill's key sentences change in the same commit, with the mirror regenerated
  (`:modules:mcp-server:skillArtifacts`).
- Docs: auth §4 (identities), §7.4, §7.5, §7.7, metadata-db, enums, rest-api §16 (key creation
  takes a role, not scopes), mcp-server.

**(c) The key dialog.** Only the roles §3.1 allows for each type; never an admin role; the create
permission per type. Docs: ui-screens.

## 7. Gates (each falsified when written)

1. **Catalog drift.** auth §7.6's catalog, the `Permission` enum and the role table agree in both
   directions, with counts. This extends `ScopeMatrixSpecDriftTest`.
2. **One permission per surface.** Every handler, UI route and MCP tool declares exactly one catalog
   permission. This extends `RequiredScopeCoverageTest`, `MatrixRowReachabilityTest`, `ReadFloorTest`
   and `MutatingHandlerScopeFloorTest`.
3. **Role walk.** `RoleWalkE2eTest` walks the five member roles, the three key roles, and the MCP key
   of every member role (capped).
4. **Key role CHECK.** An admin role, or a role not allowed for the kind, is refused by the database
   and by the service.
5. **No login as an identity.** Every login path refuses a non-human row, with a test per path: OIDC
   linking, local password, password reset, identity reset, invitation acceptance, workspace switch.
6. **Migration.** Every existing endpoint and server key gets an identity and its role; execution
   and audit counts are identical before and after.
7. **Create permission per key type.** An author cannot create an API or server key; a viewer can
   create no key.
8. **Visibility.** `ExecutionsVisibilityTest` extended: key-identity runs are visible to
   `execution.read_all` holders; an `api_caller` key reads only the results of runs it started.
9. **Promotion receiver.** A server key reaches the inventory and push routes and nothing else, and
   its identity stamps what it receives.
10. **Derived lifecycle (A2).** With a workspace deactivated, then deleted, every principal pinned to
    it — a session, an MCP key, an API key, a server key — resolves to no permissions and is refused;
    reactivating the workspace restores exactly the principals that were active before; a manually
    deactivated identity stays deactivated through a workspace reactivation.
11. **Identities through their key only (A3).** Every user-admin route refuses a `service` or
    `system` row with not-found semantics (extends gate 5's list).

Every slice gets the security pass (auth, keys and the serve path are all touched).

## 8. What the scheduler (#9) inherits

- Key type `scheduler`, created with `scheduler_key.create` (author and above), with its own
  identity per key. This settles scheduler revision §9 B5 (who creates scheduler keys). Its ROLE is
  the scheduler round's decision (A1 withdrew `viewer` as a key role); the cheap option is a
  pre-created `scheduler` key role holding exactly `pipeline.execute` and its own executions' reads.
- **B4 is not settled here.** B4 asks where scheduler keys are stored. §3.2's CHECK assumes an
  `api_keys` row, which would keep one key model and one Keys page. The scheduler review recommends
  a separate table instead: a scheduler key has no bearer secret, and `api_keys.key_hash` is NOT
  NULL. The scheduler round decides, weighing the two.
- New permissions arrive with the scheduler (not reserved here): `schedule.read`, `schedule.create`,
  `schedule.update` (including pause and resume), `schedule.delete`, `schedule.run_now`,
  `scheduler_key.create`, `scheduler_key.revoke`.
- **D2 applies:** ownership is not an authorization dimension, so any author may modify any schedule
  unless the owner rules otherwise. The scheduler draft's "own" column (§4.2) conflicts with D2 and
  is for the scheduler round to settle.
- **Still open for the scheduler:** B7, the credential an application uses to manage schedules over
  REST. The API key roles here do not include schedule management. Role-carrying keys make one
  option cheap: a pre-created key role holding the `schedule.*` permissions, offered to API keys,
  would give an application a least-privilege credential without a new key kind.

## 9. Ruled by the owner (2026-09-23)

- **O1. Accepted.** C1 stands: viewer and promoter keys gain schema introspection, and viewer keys
  gain the connection test. Reading table data stays at author and above.
- **O2. Accepted.** The §2 granularity stands. For example, `pipeline.version.manage` covers
  discarding a draft, discarding and restoring a released version, and deleting a version.
- **O3. Accepted.** The creation limit in §3.2 is a rule, pinned by a test.

## 10. Amendments (owner rulings of 2026-09-24, on the pre-implementation review)

The review is `notes/2026-09-24-permissions-record-review.md` in the orchestration store. Every
claim it verified against main is recorded there; the rulings below are written into the sections
they change (marked A1–A8).

| # | Ruling | Where |
|---|---|---|
| A1 | No key carries the `viewer` role: `viewer` is a member role for the UI and a key has no secret it belongs to. API keys offer `api_caller` only; the scheduler key's role is the scheduler round's. A serving key serves only its bound paths. | PK6, §2 legend, §3.1, §3.2, the CHECK, §8 |
| A2 | Workspace deactivate/delete cascades nothing; the permission-resolving query checks workspace active AND principal active and returns no permissions otherwise. | §3.3, gate 10 |
| A3 | Identities are managed only through their key; user-admin routes refuse non-human rows. | §3.3, gate 11 |
| A4 | Slice (a) keeps a `Permission → Scope` shim until (b) removes the scopes. | §6(a) |
| A5 | `snake_case` for key roles everywhere (`api_caller`, `promotion_receiver`). | §3.2 |
| A6 | "Renaming the key renames the identity" dropped — keys have no rename. | §3.3 |
| A7 | Counts are re-derived on the lane's base (42 tools since 7b). | §6(a) |
| A8 | The permissions work lands BEFORE the remaining transform lanes (7c → 7d → 7e), which were re-worded to this catalog (transform record v0.4). | — |

