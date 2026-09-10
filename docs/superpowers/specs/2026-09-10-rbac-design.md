# Design: RBAC — capability moves into the workspace membership; five roles, one matrix, one sweep

**Status:** RATIFIED v1.1 (owner rulings D-R1–D-R14 and O-1–O-4, 2026-09-10); implementation prompt 112 (round 1). Implementation is
two rounds (§9) and lands **before the release tag** — the owner: "We are entering serious
territory and I would like to tighten the security now rather than later."

**Not packaged into the product** (`docs/superpowers/` is excluded from the jar). The product
authority moves to `auth.md` (§7.6 becomes the role × scope matrix), `metadata-db.md` (the
migration), `configuration.md` (the provisioning change), `ui-screens.md` (the visibility
rule), `mcp-server.md` (the key rule) and the skill (`error-codes.md`) when the round ships.

**What exists today, so the delta is exact** (verified in source 2026-09-10): workspaces
isolate pipelines, templates, executions, endpoints and keys (`V4__workspaces_rekey`);
`workspace_members(workspace_id, user_id, role)` with `role IN ('owner','member')` carrying no
capability; capability is the user's GLOBAL `users.scopes` (`read | execute | author | admin`)
+ `users.is_admin`; membership is an orthogonal yes/no check in the same interceptor;
`ScopeMatrix` maps every REST operation class and every MCP tool to a minimum scope, drift-
tested against `auth.md §7.6`; keys are pinned to one workspace with scopes ⊆ issuer's; the
session JWT carries `active_workspace`, the rail has a switcher, `DP-Workspace: <name>`
selects per request and a non-member is refused; datasources carry `workspace_id` (NULL =
global); provisioning modes `auto-per-user | self-serve | closed` (`workspaces design §7`).

---

## 0. Decisions (ruled by the owner, 2026-09-10)

| # | Decision | Rationale (the owner's words where they were) |
|---|---|---|
| **D-R1** | **Capability lives in the membership, not the user.** A person has a role per workspace; `users.scopes` goes away except that `is_admin` becomes **super admin**. | "A user may be a viewer in a workspace but author in another." |
| **D-R2** | **Five roles.** `viewer` → `author` → `promoter` (release only) / `workspace admin` (the old owner) → `super admin` (instance). Roles are **additive flags on the membership row** (`author`, `promoter`, `admin`; a member with no flags is a viewer), so "author who also releases" and "DevOps who only releases" are both one row. | Promoter: "They are the DevOps guys — can only release." Workspace admin: "convert owner to workspace admin and call admin super admin." |
| **D-R3** | **Viewers execute.** Read everything in the workspace, run pipelines, read results, probe read-only SQL. Cannot modify. | "Viewer means he can do everything read-only… executing them should be fine." |
| **D-R4** | **Authors own the authoring verbs including discard / restore / switch / purge.** Only `release` and `promote` are withheld. | "These actions are also authoring. We are safe because the system does not allow purging of a release." |
| **D-R5** | **Non-members get 404**, by id or by name, same body as a missing resource — never 403 — on every surface (REST, MCP, UI, partials). Enforced by a mechanical sweep (§8). | "If he tries to access a resource, it should return 404 since that resource technically does not exist." |
| **D-R6** | **Entities are strictly workspace-bound and never shared**: pipelines, templates, executions, endpoints, keys, schedules. Two teams wanting the same template each have a copy. | "I don't care if the same template is used by two different teams. No sharing. That's the core purpose of the workspace." |
| **D-R7** | **Datasources are shared by grant, and "global" is gone.** A datasource is registered once (credentials are instance secrets) and granted to N workspaces. Super admin registers + grants; a workspace admin may register one bound to their own workspace only. Every grant is audited. v1. | "No global. Always workspace bound. Multiple workspaces can share the same datasource. Let's do it in v1." |
| **D-R8** | **Super admins are implicitly members of every workspace**; every action they take in a workspace where they hold no explicit membership is audited with `acting_via=super_admin`. Workspace admins are always explicit members. | "Only super admins are implicitly part of all the workspaces." |
| **D-R9** | **Workspace selection stays JWT `active_workspace` + the switcher + `DP-Workspace`** (already built). No subdomain per workspace (wildcard DNS/TLS, per-workspace OIDC redirect URIs, no localhost demo); a `/w/<name>/…` cosmetic path is a later, auth-neutral option. Keys stay pinned; `DP-Workspace` on a key stays rejected. | Owner: "ok with keeping it in JWT but then login should determine it" — login stamps last-used else first membership, as today. |
| **D-R10** | **Deactivate, never delete**: users and workspaces. A deactivated workspace cannot be selected, its endpoints answer 404, its keys are refused, its schedules do not fire; nothing is purged. | "Maybe we just deactivate the user/workspace?" |
| **D-R11** | **Workspaces are created by super admins.** `auto-per-user` and `self-serve` provisioning go; the out-of-the-box workspace is **`demo`**, and every social-login user becomes a **viewer of `demo`** on first login. Personal workspaces are removed. | "Only workspace which comes out of the box is demo and every user who logs in using social will be a member of demo workspace as viewer." |
| **D-R12** | **Keys are issued by authors and above**, scope ≤ the issuer's capability in that workspace, re-checked on every request against the issuer's CURRENT membership (a demoted or removed issuer's keys die with the change). | "Allow author to be able to do that. In higher environments there won't be an author but admins anyway." |
| **D-R13** | **Viewers exist on non-dev servers** (support reads executions); the hardened posture keeps refusing authoring writes as today — roles and posture are two checks, neither replaces the other. | "I like the idea of having viewers on non-dev." |
| **D-R14** | **Migration**: `owner → workspace admin`, `member → author`; `users.scopes` dropped; `is_admin → super admin`; existing personal workspaces stay as ordinary workspaces (their sole member becomes its admin); `global` datasources become granted-to-all-existing-workspaces rows. | Ruled "yes" on the migration table. |

---

## 1. The roles, precisely

A membership row: `(workspace_id, user_id, author BOOLEAN, promoter BOOLEAN, admin BOOLEAN)`.
"Viewer" is the row with all three false. `admin` implies `author` for capability purposes
(a workspace admin can author) — the flag stays explicit so the matrix reads directly off
the row; the migration and the UI keep the invariant `admin → author`.

| Capability (operation class) | viewer | author | promoter | ws admin | super admin |
|---|---|---|---|---|---|
| Read pipelines/templates/datasources (no credentials)/executions/results/endpoints/keys-of-own | ✓ | ✓ | ✓ | ✓ | ✓ |
| Execute pipelines (any version), read results, cancel OWN runs | ✓ | ✓ | ✓ | ✓ | ✓ |
| `sql_probe`, `datasources_preview_rows` (row data) | ✓ (read-only SELECT, capped) | ✓ | ✓ | ✓ | ✓ |
| Create/edit drafts (pipelines, templates), register lake tables, publish/unpublish endpoints over released versions, bind keys to endpoint paths | | ✓ | | ✓ | ✓ |
| discard / restore / purge (versions and entities) | | ✓ | | ✓ | ✓ |
| **switch** the served version (the rollback lever, O-1) | | ✓ | ✓ | ✓ | ✓ |
| **release** | | | ✓ | ✓ | ✓ |
| **promote** to the higher environment | | | ✓ | ✓ | ✓ |
| Issue/revoke keys (scope ≤ own capability); revoke any key in the workspace | | ✓ (own) | | ✓ (all) | ✓ |
| Register a datasource bound to THIS workspace; test it | | | | ✓ | ✓ |
| Grant/revoke a datasource to workspaces; register an instance datasource | | | | | ✓ |
| Members and roles (add, remove, change flags); cannot remove the last admin | | | | ✓ | ✓ |
| Read the workspace's audit trail | | | | ✓ | ✓ |
| Create/deactivate workspaces; users (activate/deactivate/super-admin); instance config; read the instance audit log | | | | | ✓ |

Keys map onto the same table: scope `read` = viewer minus execute; `execute` = viewer;
`author` = author. There is no `promoter` or `admin` scope for keys — release, promote and
membership are human verbs (D4 of versioning stands; the promotion peer uses the server key,
unchanged).

## 2. One matrix, two axes

`ScopeMatrix` today maps `RestOperation` / MCP tool → minimum **scope**. It gains a second
column, minimum **role capability**, and ONE function answers both: `allowed(principal,
operation, workspace)`:

- session principal → the membership row's flags in the ACTIVE workspace (super admin →
  all true, audited as D-R8);
- key principal → the key's scope, AND the issuer's current flags in the pinned workspace
  (a key cannot exceed what its issuer can do NOW).

`auth.md §7.6` becomes the role × scope matrix (one table, both columns);
`ScopeMatrixSpecDriftTest` parses both columns; `RequiredScopeCoverageTest` extends to "no
handler without a role row". Default-deny stays: an operation absent from the matrix is
refused, and the test fails the build.

**Owner's principle (his #12):** "we don't have permissions but actions to put the check on"
— the matrix is keyed by ACTION (operation class), never by an abstract permission string.

## 3. The 404 rule and how it is enforced

Every entity read resolves `(workspace_id, id|name)` — the workspace comes from the
principal, never from the request body or path. A row that exists in another workspace is
indistinguishable from a row that does not exist: same code, same body, same status. This is
already the endpoint registry's rule ("not enumerable one URL at a time", rest-api §19.6);
it becomes the rule everywhere.

`WorkspaceIsolationSweepTest` (integration): seeds workspaces A and B with one of every
entity (pipeline with two versions, template, execution + result, endpoint + binding, key,
datasource granted to B only, lake table), then as a member of A walks EVERY REST route and
EVERY MCP tool with B's ids and names — and that A's listings never contain a B row. The
route list comes from the same reflective enumeration `PublicRouteWalkerTest` uses, so a new
route is swept automatically. **This test is the guard the whole design rests on.**

**The invariant, stated the way 112 found it had to be** (its first two cuts — "never 2xx,
never 403" and "any 2xx is a leak" — each mis-flagged a legitimate surface): *the response
must not depend on whether the foreign row exists.* Every route is called twice — once with
B's id or name, once with a well-formed id that exists nowhere — and the status AND a body
fingerprint must match. That is the form that survives the fix this guard most needs to
survive: patching an isolation hole with a permission check yields 403-for-existing against
404-for-missing, a differing pair, red. User ids are not in the substitution table: users are
a global entity managed by super admins (D-R8), so another user's id is not a cross-workspace
probe — the 404 rule is about what a workspace CONTAINS.

## 4. Datasources: registration and grants

`datasources.workspace_id` (nullable, "global" when NULL) is replaced by a grant table:

```sql
CREATE TABLE datasource_workspaces (
    datasource_id UUID NOT NULL REFERENCES datasources(id),
    workspace_id  UUID NOT NULL REFERENCES workspaces(id),
    granted_by    UUID NOT NULL REFERENCES users(id),
    granted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (datasource_id, workspace_id)
);
```

`datasources.owner_workspace_id` (nullable): set when a workspace admin registered it (then
it is granted to that workspace and cannot be granted elsewhere by them); NULL when a super
admin registered it as an instance datasource. Visibility = the grants. Credentials remain
never-returned (datasources §7). The migration grants every former `global` datasource to
every existing workspace (D-R14) — so nothing that worked yesterday stops today — and every
grant is an audit event.

`readonly` stays a property of the datasource (workspaces design §6). The grant is the
visibility; readonly is the write permission; neither replaces the other.

## 5. Provisioning and the demo workspace

`datapipelines.workspaces.provisioning-mode` loses `auto-per-user` and `self-serve`; the
only mode is `closed` and the key is retired (refused at boot if set — the config validator
names the removed key). Seed: a `demo` workspace at first boot; `datapipelines.auth.
social-login-default-role` fixed at viewer of `demo` (not configurable in v1 — the owner
ruled the behaviour, not a knob). Super admins create workspaces from the Workspaces screen
and the REST route; there is no MCP tool for it.

## 6. Deactivation

`users.is_active` exists; `workspaces` gains `deactivated_at TIMESTAMPTZ NULL, deactivated_by UUID
NULL`. Deactivated workspace: not selectable (switcher hides it; `DP-Workspace` → 404 like a
non-member), endpoints under it 404, keys pinned to it refused with `auth.key_workspace_inactive`,
schedules (092) skipped, listings for super admin show it greyed with the date. Reactivation
is a super-admin verb, audited. Nothing is purged by deactivation — ever.

## 7. UI

The role decides what is rendered; the server decides what is allowed. Explorer verbs
(102's dialogs), the editor's Release button, the datasource register/edit/delete actions,
the keys screen, the members screen: each is rendered only when the ACTIVE membership's
flags permit it, and the same check refuses the POST. The Workspaces screen gains members
with three checkboxes (author / promoter / admin) and the last-admin rule. The rail's
switcher lists active memberships; super admins see every active workspace.

## 8. Tests (the gate)

1. `ScopeMatrixSpecDriftTest` over both columns; `RequiredScopeCoverageTest` for role rows.
2. `WorkspaceIsolationSweepTest` (§3) — the IDOR sweep; falsified by removing one route's
   workspace predicate and watching it name that route.
3. Role matrix behavioural tests: one class per role, each asserting the allowed and the
   refused verbs through the real interceptor (MockMvc + the real matrix), for REST and for
   the MCP dispatcher.
4. Key rules: scope ≤ issuer capability at issuance (400 `auth.key_scope_exceeds_role`);
   issuer demoted → key refused within the validation-cache TTL (60 s); issuer removed →
   refused; workspace deactivated → refused.
5. Migration test (`FlywayMigrationIntegrationTest` + a data test): owner→admin,
   member→author, global datasource → grants to all workspaces, scopes dropped, personal
   workspaces intact with their sole member as admin.
6. Super-admin audit flag: an action in a non-member workspace carries `acting_via`.
7. Deactivation: the five effects of §6, each asserted.
8. Browser: a viewer's explorer shows no Release/Purge/Register; an author's shows all but
   Release; a promoter's shows Release only; the members screen enforces the last-admin rule.

## 9. Rounds

- **Round 1 (auth core):** migration (memberships flags, grant table, workspace deactivation,
  scopes dropped), `ScopeMatrix` two-axis + `allowed()`, the interceptor, the 404 rule on
  every repository read, key rules, super-admin audit, provisioning change + demo seed, the
  sweep test, the matrix tests, `auth.md` §7.6 rewrite, `metadata-db.md`, `configuration.md`.
- **Round 2 (surfaces):** members/roles UI, datasource grants UI + REST + MCP read shape,
  visibility-driven verbs across explorers/editor/keys/datasources, deactivation screens,
  `ui-screens.md`, the skill (`error-codes.md`: the new refusals and what an agent does —
  "your key's issuer lost the role; ask an admin").

## 10. Rulings on the open items (owner, 2026-09-10)

| # | Ruling |
|---|---|
| O-1 | **Yes** — a promoter (and a workspace admin) may `switch` the served version on any server; it is the operational half of promotion. |
| O-2 | **Viewers never mint keys.** Keys are author and above, full stop. |
| O-3 | **Yes** — `demo` is deactivated like any workspace; the seed does not recreate it. |
| O-4 | **Correct** — a promotion import lands only in a workspace that already exists on the receiver, created by its super admin to MATCH the lower environment, exactly as datasources are registered manually there as part of setup. A missing workspace refuses the batch (`workspace.not_found`); nothing is auto-created. |

## Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-10 | v1.1 | 112 (in flight) | §3: the sweep's invariant restated as "the response must not depend on whether the foreign row exists" (two calls, matching status + body fingerprint); user ids excluded from the substitution table. |
| 2026-09-10 | v1.0 | owner ratification | O-1–O-4 ruled (§10); status RATIFIED; prompt 112 is round 1. |
| 2026-09-10 | v0.1 | orchestrator, after the owner's rulings | Initial record: fourteen decisions from the conversation, the role table, the two-axis matrix, the 404 sweep, datasource grants, provisioning + demo, deactivation, rounds, open items. |
