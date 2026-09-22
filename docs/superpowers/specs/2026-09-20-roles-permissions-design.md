# Roles and permissions — design record (2026-09-20, RATIFIED by the owner the same day; draft 2 carries the three settled cells and the lane split as four)

Owner's intent (2026-09-20): "introduce the permissions now … add gates to always test authorization
has been used in any new implementation." This record turns the owner's thirteen rules and eight
rulings into decisions, a role-by-action matrix over the EXISTING action catalogue (26 REST operation
classes + 41 MCP tools — `ScopeMatrix`), the new mechanisms, the gates, and the lane split. Nothing
here introduces permission strings: the owner's standing principle ("we don't have permissions but
actions to put the check on", rbac-design 2026-09-10 l.92) holds — a role is a fixed bundle of
actions, assigned per user per workspace.

## 1. Decisions (the owner's rulings, restated one line each)

| # | Decision | Today | Change? |
|---|---|---|---|
| D1 | Five roles: viewer, author, promoter, workspace admin (ws_admin), super admin. All workspace-bound except super admin. **A member holds exactly ONE role per workspace** (owner 2026-09-20, second ruling): a `role` value on the membership, not three additive flags. Super admin stays a global attribute of the user. | three additive flags (`MembershipFlags`, V23 — which had REPLACED a `role` column) | **yes: back to one role per membership** |
| D2 | Ownership is NOT an authorization dimension. `created_by`/`owner_id` columns stay as facts; any author may modify/delete any pipeline, template, endpoint in the workspace. | same | no |
| D3 | Viewer: read everything in the workspace (datasources, pipelines, templates, endpoints, results) and EXECUTE pipelines. | same | no |
| D4 | Author: viewer + create/update/delete pipelines, templates, endpoints (publish), lake tables, learned facts/semantics; read datasources, never modify them. | same except `datasources_test`/`TEST_DATASOURCE` (ws_admin today — stays) | no |
| D5 | Promoter: reads datasources; sees ONLY released pipelines/templates whose version is newer than the higher environment's (versioning §10.2's rule applied everywhere, not only on /promotion); promotes. Nothing else. Promoters are ops people. | promoter = release + promote + everything a viewer sees | **yes: the promoter lens; promoter loses release** |
| D6 | Workspace admin: everything in the workspace, including promote, release, workspace datasources (add/update/delete/test, workspace-bound only), members, invitations, roles, the workspaces page, audit log. | admin implies promoter today; audit visibility not stated | yes (audit; page) |
| D7 | Super admin: everything everywhere; the only role that creates/updates/deletes GLOBAL datasources and grants them, creates workspaces, administers users. Acting inside a workspace stays audited (`auth.super_admin_acting`). | same | no |
| D8 | Release stays with authors and admins (D5 takes it from promoters). Promotion = promoters, ws_admin, super admin (owner ruling 1 + ruling 3). | RELEASE_VERSION = PROMOTE cap | **yes** |
| D9 | Endpoint publishing: authors and admins. | MANAGE_ENDPOINTS = AUTHOR | no |
| D10 | Released versions are immutable; discard is the only exit. Nobody deletes a release. | same | no |
| D11 | Executions: the list shows the caller's own executions; ws_admin and super admin see all. `executions.executed_by` names the user (an MCP-key run is the key owner; an endpoint-key run has no user and is visible to admins only). | all members see all; column is `triggered_by` | **yes** |
| D12 | Audit log: ws_admin (own workspace) and super admin. | — | yes |
| D13 | Workspaces page: ws_admin (their workspaces, members) and super admin; the workspace SWITCHER stays available to every member (rule 10) and re-issues the token. | page is any member | **yes** |
| D14 | Workspace switching needs no capability; always allowed for a member; new token. | same (`POST /workspace/switch`) | no |
| D15 | Deactivated users and deactivated workspaces are served NOTHING — sessions, user keys, endpoint keys, server keys pinned there — refused at the boundary with one code. | partial (user snapshot re-read; workspace deactivation exists) | yes: one sweep guard |
| D16 | MCP key: exactly ONE user key per user per workspace, minted at first login into that workspace, carrying the user's role (re-read per request, as today). Shown in the top bar (right): first characters + copy button. Rotation = the user deletes it, logs in again, a new one is minted. No on-demand user keys anywhere (UI, REST, MCP). | user keys minted on demand with chosen scopes | **yes** |
| D17 | "API keys" = today's ENDPOINT keys, renamed in the UI and docs. Own page. Created by ws_admin and super admin. Associated with endpoints on the API page (today's bindings). Promotion ignores keys. | endpoint keys on /api-console, any member with MANAGE_OWN_API_KEYS | **yes** |
| D18 | Server keys (the promotion-peer credential, versioning §10.6) are unchanged: super admin, out of D16/D17's scope. | same | no |
| D19 | Learned facts/semantics: authors and admins record/retire; they do NOT ride a promotion (nothing promotes them today) — a separate decision later. | semantics_record = AUTHOR | no |
| D20 | Invitations and role changes: ws_admin invites/assigns; super admin creates workspaces. An invitation carries ONE role. | flags on invitations (V24) | yes (one role) |
| D21 | **Vocabulary.** UI, docs and code say **role** (what a member holds) and **permission** (an action a role may perform — the rows of §2). The word "flags" disappears from UI text, docs and identifiers: `MembershipFlags` → `WorkspaceRole` (enum VIEWER, AUTHOR, PROMOTER, WORKSPACE_ADMIN), `Capability` → `Permission` (`Permission.satisfiedBy(role, superAdmin)`), `MembershipFlags.author/promoter/admin` → `role`. Wire/REST field names follow (`role`), with the change-log rows. A permission is still an ACTION row, never a free string. | "flags" in 24 places in auth.md, a "Flags" column with three checkboxes on the workspaces page, 56 + 96 code references | **yes** |
| D22 | **Members UI.** The workspaces page lists members with their current role in a single-select dropdown of the four workspace roles; changing the selection and saving updates the role (one htmx partial, `MANAGE_WORKSPACE_MEMBERS`); the last-admin rule stays; add-member and invitation forms take a role dropdown too. | three checkboxes + a separate "role" label | **yes** |

## 2. The role × action matrix (proposed; every existing action mapped)

Roles are cumulative in this order for READ actions only: viewer ⊂ author ⊂ ws_admin ⊂ super_admin.
The promoter is NOT on that chain (D5): it reads datasources and released-and-newer objects only.
With D1 a member has ONE role, so "author+promoter" cannot exist; the columns are exclusive.

| action (RestOperation / MCP tools) | viewer | author | promoter | ws_admin | super_admin | change |
|---|---|---|---|---|---|---|
| READ_RESOURCES; `pipelines_list/get`, `templates_list/get/used_by`, `datasources_list/get`, `datasources_preview_rows`, `calculators_*`, `endpoints_list/get` | ✓ | ✓ | lens (§3.1) | ✓ | ✓ | promoter lens |
| RETRIEVE_RESULT, execution reads; `executions_list/get/get_result` | own (D11) | own | **✗ (ratified)** | all | all | promoter reads no executions or results |
| EXECUTE_PIPELINE, CANCEL_EXECUTION; `pipelines_execute`, `pipelines_execute_node`, `pipelines_run_checks`, `executions_cancel` | ✓ | ✓ | ✗ | ✓ | ✓ | promoter loses execute |
| MUTATE_PIPELINES_TEMPLATES; `pipelines_create/update`, `templates_create/update/render/purge_draft` | ✗ | ✓ | ✗ | ✓ | ✓ | — |
| MANAGE_ENDPOINTS; `endpoints_create/delete` | ✗ | ✓ | ✗ | ✓ | ✓ | — |
| MUTATE_LAKE_TABLES; `lake_tables_*` | ✗ | ✓ | ✗ | ✓ | ✓ | — |
| semantics_record / semantics_retire (learned facts) | ✗ | ✓ | ✗ | ✓ | ✓ | — |
| INTROSPECT_DATASOURCE; `datasources_get_schemas/tables/columns/table_stats`, `sql_probe` | ✓ | ✓ | ✓ | ✓ | ✓ | ratified: introspection is reading |
| TEST_DATASOURCE; `datasources_test` | ✓ | ✓ | ✗ | ✓ | ✓ | **ratified: follows execute** (every role with execute; promoter has none) |
| MUTATE_WORKSPACE_DATASOURCES (workspace-bound add/update/delete) | ✗ | ✗ | ✗ | ✓ | ✓ | — |
| MUTATE_DATASOURCES (global), MANAGE_DATASOURCE_GRANTS | ✗ | ✗ | ✗ | ✗ | ✓ | — |
| RELEASE_VERSION | ✗ | ✓ | ✗ | ✓ | ✓ | **author gains, promoter loses** |
| SWITCH_SERVED_VERSION | ✗ | ✓ | ✗ | ✓ | ✓ | promoter loses |
| PROMOTE_VERSION (/promotion page + promote) | ✗ | page only (D-owner 13) | ✓ | ✓ | ✓ | page visible to authors; promote = promoter/admins |
| Executions list/detail (D11) | own | own | ✗ | all | all | **new filter** |
| Audit log (D12) | ✗ | ✗ | ✗ | ✓ | ✓ | new |
| WORKSPACES_READ (the page) | ✗ | ✗ | ✗ | ✓ | ✓ | **narrowed**; switcher stays for all |
| MANAGE_WORKSPACE, MANAGE_WORKSPACE_MEMBERS (invitations, roles) | ✗ | ✗ | ✗ | ✓ | ✓ | — |
| WORKSPACE_CREATE, MANAGE_INSTANCE_WORKSPACES, USER_ADMINISTRATION | ✗ | ✗ | ✗ | ✗ | ✓ | — |
| MANAGE_OWN_API_KEYS → split: `VIEW_OWN_MCP_KEY` (top bar, copy, delete) | ✓ | ✓ | ✓ | ✓ | ✓ | **user keys: view/delete only, never create** |
| `MANAGE_API_KEYS` (endpoint keys: create, delete, associate) — new operation | ✗ | ✗ | ✗ | ✓ | ✓ | **new** |
| CURRENT_PRINCIPAL, PROFILE_PREFERENCE, CHANGE_OWN_PASSWORD, workspace switch | ✓ | ✓ | ✓ | ✓ | ✓ | — |
| SERVE_PUBLISHED_ENDPOINT | endpoint key with a matching binding only (unchanged) | | | | | — |

Settled 2026-09-20: (a) introspection allowed to every role; (b) promoter reads no executions or
results; (c) the connection test follows EXECUTE — viewer, author, ws_admin, super admin; not promoter.

## 3. New mechanisms

### 3.1 The promoter lens
One predicate, `PromotableView`, evaluated server-side for any principal whose role in the active
workspace is promoter (and not author/admin): a pipeline or template is visible iff it has a RELEASED
version AND that version is newer than the higher environment's inventory entry for the same name
(versioning §10.2's rule; target absent = 0). The lens is applied in the read path (repository-level
filter injected by the interceptor's decision, not per handler), so lists, gets, the editor, search,
`/explore`-style pages, and every MCP read tool see the same set; a hidden object answers not-found
(the 404 rule, auth.md §11A). The inventory call to the higher server is cached (TTL configurable, default
60 s); when the higher server is unreachable the lens shows NOTHING and the page says why (fail closed).

### 3.2 Executions ownership (D11)
Migration: `executions.triggered_by` → `executed_by` (rename, same FK) and `executed_by_key_kind`
(user/endpoint/server) so an endpoint-key run is attributable. Read path: non-admin principals see
`executed_by = self`; admins see all. Applies to REST, UI and `executions_list/get/get_result`.

### 3.3 The login-minted MCP key (D16)
- `api_keys`: kind `user` rows gain `minted_at_login BOOLEAN` and `secret_ciphertext` (encrypted at
  rest with the existing credential-encryption key, the way datasource credentials are), so the top bar
  can copy it again. Uniqueness: one `(user_id, workspace_id)` row with `kind = 'user'`.
- Minting: on login (any method) and on workspace switch, if no row exists for `(user, workspace)`,
  mint one, pinned to that workspace, scopes = the role's full scope (re-derived per request as today —
  `auth.key_issuer_role_lost` semantics unchanged). Existing row → nothing happens.
- Rotation: `DELETE` own row (any role) → next login/switch mints a new one.
- Removal of on-demand user keys: `POST /partials/api-keys` and `POST /api/v1/auth/api-keys` refuse
  `kind = user` (`auth.key_kind_not_mintable`); the console's kind select loses `user`.
- Top bar: prefix (`dpk_<id>…`, first 12 chars) + copy + delete, every role; `VIEW_OWN_MCP_KEY`.

### 3.4 API keys page (D17)
`/api-keys` (UI) lists endpoint keys of the workspace; create/delete = `MANAGE_API_KEYS` (ws_admin,
super admin); the API page ("Published endpoints") gains an "associate keys" control that writes
today's bindings. Wording: "API key" everywhere the UI/docs said "endpoint key"; the enum value and
the wire `kind` stay `endpoint` (the REST contract does not rename). The MCP connection card moves
next to the user's MCP key (3.3).

### 3.5 Deactivation (D15)
ONE shared predicate (`PrincipalLiveness`: user active ∧ pinned/resolved workspace active, both through
the `AuthCache` TTL), judged where each credential becomes a principal — the earliest point a refusal
can happen, and the one every surface passes through because the security chain is global:
`JwtAuthenticationFilter` (sessions), `ApiKeyService.validate` (user and endpoint keys, which is what
feeds REST, `/mcp` and the published-endpoint route) and `ApiKeyService.validateServerKey` (the promotion
peer). The published-endpoint serve service adds the RESOURCE half — an endpoint whose own workspace is
deactivated is unknown — with `EndpointAuthorizer` kept pure. Nothing is restated at `ScopeInterceptor`
or `McpAuthFilter`: a principal cannot reach either while deactivated, so a check there could never
fire (lane 180, orchestrator ruling 2026-09-21).

Two older ratified rules constrain the CODE, so it is one predicate but not one code everywhere
(orchestrator resolution 2026-09-21, lane 180):
- a deactivated **user** — session, user key, endpoint key owner, server key owner — answers the new
  `auth.principal_deactivated` (401 on API surfaces; a session additionally has its cookie cleared and
  an HTML navigation is sent to `/login?error=inactive`) on every surface except the promotion peer;
- a deactivated **workspace** keeps the 404 rule (auth.md §11A.1: indistinguishable from a workspace
  that does not exist) — `auth.key_workspace_inactive` for a key pinned there, `workspace.not_found`
  for a session switch, "unknown endpoint" for a published endpoint whose workspace it is;
- the promotion peer keeps its one-answer rule (`PromotionServerKeyFilter`, "a caller must not
  classify a credential"): every refusal on `/api/v1/promotion/**`, deactivated owner or deactivated
  pin included, is `auth.promotion.key_invalid`.

If the owner rules "one code everywhere", the change is the mapping in `PrincipalLiveness.Refusal`:
the workspace case's exception becomes `PrincipalDeactivatedException` and the promotion filter's fold
is left as is.

### 3.6 Scopes on user keys
With D16 every user key's scope equals its role, so the credential axis carries information only for
endpoint and server keys. Proposal: keep `Scope` and the two-axis `ScopeMatrix` (the code and 5 guards
are sound) but stop issuing user keys with chosen scopes; document that user-key scope = role.
Alternative (larger): fold the axis for user kinds. Recommend the former for this round.

### 3.7 Keys and membership (owner rulings, 2026-09-21; lane #200)
A login-minted `user` key is tied to its user AND its workspace — it is the credential the membership
mints, not a possession that outlives it (§3.3):

1. **When a user is removed from a workspace, their `user` key pinned there is removed as well.**
   Removal revokes the key in the same act (one transaction; a failure removes nothing and revokes
   nothing). Before this ruling a removed member's key kept authenticating — the §3.3 viewer fallback
   let `Permission.EXECUTE` read pipelines, run them and read results in the workspace they had been
   removed from. The viewer fallback stays for totality (a super admin without a membership resolves
   through `issuerContext`, never through it), but the removed-member case no longer reaches it.
2. **No automatic rotation on password or identity events.** The product cannot tell a forgotten
   password from a compromise, so a login that stops working is not a security signal. Recovery is an
   admin act — removing the user from the workspace, or explicitly removing their key (ruling 3).
3. **A workspace admin can remove a member or remove that member's key**, in addition to a super admin
   doing either. The key revoke is a separate verb from member removal: revoking the key is not
   deactivation — the member's session keeps working, and their next login or workspace entry mints a
   fresh key. `MANAGE_WORKSPACE_MEMBERS` carries both verbs (its §7.6 row already admits ws_admin and
   super admin to every member management act); the audit names which act happened
   (`auth.api_key.revoked_by_admin`, reason `member_removed` | `admin_revoked`).

## 4. Gates (mechanical; every one falsified at birth)
1. `RequiredScopeCoverageTest`/Konsist twin/`MutatingHandlerScopeFloorTest`/`PublicRouteWalkerTest`
   scan EVERY module that declares a controller (today only `co.datapipelines.web`).
2. New `ReadFloorTest`: every GET's operation is the LOWEST that still fits the matrix row for its
   resource family — a GET returning admin data under READ_RESOURCES fails by name.
3. New `MatrixRowReachabilityTest`: every `RestOperation` and every MCP tool is claimed by ≥1 handler
   or tool; a row nothing uses is a lie in the doc.
4. New `RoleWalkTest` (E2E): for each of the five roles, walk EVERY REST route and EVERY MCP tool with a
   principal of that role and assert allowed/refused exactly per the matrix table in auth.md §7.6 —
   the table is parsed, so the doc IS the expectation (extends `ScopeMatrixSpecDriftTest`).
5. New `PromoterLensSweepTest` (E2E): as a promoter, every read route and read tool returns no draft
   and nothing at or below the target's version; non-vacuity: the fixture holds ≥1 of each hidden kind.
6. New `ExecutionsVisibilityTest`: own-only for viewer/author/promoter, all for admins, via REST and MCP.
7. New `DeactivationSweepTest`: every route and tool as a deactivated user / in a deactivated workspace
   / with keys pinned to either → exactly the code §3.5's table owes each (`auth.principal_deactivated`
   for the user, the 404 rule for the workspace, the promotion peer's one answer), nothing else leaks;
   the deactivated-vs-unknown differential is zero on every route.
8. `RoleVisibilityRenderTest`'s "route-guarded exemption" list shrinks to zero: every verb control is
   inside a role guard.
9. PROCESS gate: (a) the store's prompt template gains a mandatory "Roles" section — every lane prompt
   states which roles may perform each new action and which guard pins it; (b) product `AGENTS.md` gains
   the rule "a new handler or tool lands with its matrix row, its §7.6 doc row and its RoleWalk
   expectation in the same commit"; (c) `docs/auth.md` §7.6 is rewritten role-first (the table in §2).

## 5. Lane split (each gated; order matters; cut along verification lines)
- **R1 — the matrix + the role model**: D1/D21/D22 (one `role` per membership and invitation — migration from the three flags: admin→WORKSPACE_ADMIN, else promoter→PROMOTER, else author→AUTHOR, else VIEWER; the `WorkspaceRole`/`Permission` rename; the members dropdown), every row of §2 in `ScopeMatrix` + auth.md §7.6 rewritten role-first, executions
  ownership (`executed_by` migration + own/all filter), the workspaces page narrowing + switcher, the
  audit-log visibility row, gates 1, 2, 3, 4, 6, 8, 9. No lens yet: promoters simply lose what §2 says.
- **R2 — the promoter lens** (§3.1) + gate 5. Depends on R1's matrix.
- **R3 — keys** (§3.3, §3.4): login-minted MCP key, top bar, no on-demand user keys, API keys page +
  association, wording; skill edit + mirror; gate 4 re-run over the new operations.
- **R4 — deactivation** (§3.5) + gate 7 + docs consolidation.
- Skill: R2 changes what an agent is told about keys (`references/endpoints.md`, the connection
  instructions) — skill edit in the same commit, mirror regenerated.

## 6. Out of scope (explicit)
Per-workspace matrices / custom roles (owner: one matrix, roles per workspace); permission strings;
promoting learned facts; per-datasource ACLs (auth.md §15 stays open); OIDC group sync.

## 7. Follow-up specs (filed, not designed here)
- Promoting the semantic layer (learned facts) to a higher DEV server: the owner's insight (2026-09-20)
  is that the semantic layer serves AUTHORING only — non-development servers do not need it — so the
  question is dev→dev transfer, not production promotion. Separate spec.
