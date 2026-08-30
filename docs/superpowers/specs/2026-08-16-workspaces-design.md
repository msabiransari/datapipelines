# Design: Workspaces + Datasource Scoping & Readonly Semantics

**Status:** approved design, pre-implementation (drafted 2026-08-16; owner-approved same date, D2 ratified)
**Scope:** spec 1 of 2 for the datapipelines.co demo direction. Spec 2
([sample data](2026-08-16-sample-data-design.md)) depends on this one's
`global` + `readonly` datasource semantics and the `auto-per-user`
provisioning mode.
**Authority note:** this document records *design decisions*. Normative text
lands as amendments to `docs/auth.md`, `docs/metadata-db.md`,
`docs/configuration.md`, `docs/datasources.md`, `docs/pipeline-contract.md`,
`docs/enums.md`, `docs/rest-api.md`, `docs/mcp-server.md`, and
`docs/ui-screens.md` at implementation time — **error-code and enum and
config-key amendments in the same commit as the code constants** (drift
tests live on main; standing MISTAKES.md rule). All codes, keys, and DDL
below are PROPOSED until they enter their authority docs.

---

## 1. Summary

A **workspace** is the unit of team isolation. Pipelines and templates belong
to exactly one workspace; users are members of one or more workspaces; every
authenticated request resolves exactly one *active* workspace, and all
authored-content operations are scoped to it. **Datasources are
workspace-bound by default** and become shared infrastructure only when
explicitly flagged `global` (admin-gated). A datasource may additionally be
flagged **`readonly`**, which makes every write-shaped use of it a
save-time validation error and an execution-time failure. The per-execution
H2 tempdb (staging.md) remains the always-writable scratch surface — nothing
new is needed there.

Workspace *provisioning* is config-driven so one codebase serves both
deployment shapes (owner decision, 2026-08-16):

- **datapipelines.co demo:** every first OIDC login auto-creates a personal
  workspace; seeded demo datasources are `global` + `readonly`; users author
  read-shaped pipelines against shared data, isolated from each other.
- **Company deployment:** users create workspaces and join existing ones
  (self-serve v1); admin-invite flow is a deferred tightening, not a
  redesign.

## 2. Decisions record

| # | Fork | Decision | Rationale |
|---|---|---|---|
| D1 | Scope of workspace ownership | Pipelines + templates workspace-owned; datasources workspace-bound by default with explicit `global` flag; executions inherit their pipeline's workspace | Owner decision 2026-08-16. Authored content is team-private; connections are infrastructure that teams may deliberately share |
| D2 | Name uniqueness under workspaces | **RATIFIED (owner, 2026-08-16):** pipeline `name` and template `id` become unique **per workspace**; datasource `name` stays **globally unique** | See §3. Datasource name is the PK, the AES-GCM AAD anchor (datasources §7.1), and the cross-env contract — changing it ripples everywhere for no demo gain. Authored-content names must be per-workspace or the demo's hundreds of agent-authored `sales_report`s collide and leak existence across workspaces. Pre-launch is the cheapest moment for this break |
| D3 | Workspace context resolution | JWT principals: active workspace claim + `DP-Workspace` header to switch (membership-checked). API keys: **pinned to one workspace at issuance**, no header override | An agent key is a workspace-scoped credential; a header-switchable agent key would make every leaked key a skeleton key across the user's workspaces. `DP-` prefix per house rule (rest-api §3.6) |
| D4 | Membership model | `workspace_members(workspace_id, user_id, role)` with roles `owner` \| `member`; global `is_admin` bypasses membership checks | Minimal viable teams. Fine-grained workspace roles deferred with per-datasource ACLs (ROADMAP §auth) |
| D5 | Provisioning modes | Config enum `datapipelines.workspaces.provisioning-mode`: `auto-per-user` \| `self-serve` \| `closed` | Owner decision 2026-08-16: same product, config decides. `closed` (admin creates workspaces and assigns members) is the future invite flow's v1-compatible base |
| D6 | Readonly enforcement | Three layers: save-time validation (primary UX), pool-level read-only connections (defense in depth), read-only DB credentials (deployment guidance, the real boundary) | Owner decision 2026-08-16. SQL cannot be classified statically with certainty; the flag gives agents fast machine-readable feedback, the credential gives the guarantee. Never let SQL parsing be the only wall |
| D7 | Writable surface for readonly-only principals | The existing per-execution H2 tempdb (staging.md) — no new writable datasource concept | Already built, already isolated per execution, already memory-bounded. "Temp writable DB per pipeline request" is exactly what staging is |
| D8 | Datasource management scopes | Workspace-bound datasource CUD: `author` + membership, **config-gated** (`member-datasources-enabled`, default `true`). Global datasource CUD and the `global`/`readonly` flags on any datasource: `admin` only | Teams manage their own connections in a company; the demo sets the gate to `false` because open datasource creation is an SSRF/port-scan primitive from the server's network position (spec 2 §7) |
| D9 | Migration/back-compat | Migration creates workspace `default`; all existing pipelines/templates assigned to it; all existing users become members; existing datasources become `global` (they were shared before, so `global` preserves behavior) | A single-team deployment upgrades to "everything in one workspace" with zero behavior change |
| D10 | Readonly flip on a referenced datasource | Allowed. Existing write-shaped pipelines keep their stored versions; they fail at next save (validation) and at execution (runtime backstop code) | Blocking the flip on references would make readonly un-adoptable on any datasource in use. Mirrors how datasource deletion behaves at runtime (`pipeline.node.datasource_not_found`) |

## 3. Name uniqueness (D2) — the one breaking change, argued

**Datasource `name` stays global.** It is the table PK (metadata-db §4.10),
the immutability anchor pipelines reference across environments, and the GCM
associated data binding for `password_encrypted` (datasources §7.1). Scoping
it would change the PK, the AAD contract, the pool-registry key, and the
pipeline-contract `source` resolution simultaneously. Workspace binding for
datasources is therefore **visibility/ownership only** (a nullable
`workspace_id` column); the namespace stays flat. Cross-workspace name
collisions on datasource creation return the existing
`datasource.validation.duplicate_name` with its already-generic message.

**Pipeline `name` and template `id` become unique per workspace**
(`UNIQUE(workspace_id, name)`). Consequences, inventoried:

- `pipelines.name UNIQUE` → `UNIQUE(workspace_id, name)`; same for
  `templates` (which needs a surrogate `id UUID` PK, with today's TEXT id
  becoming a `name` column — `template_versions` re-keys onto the surrogate).
  This is the invasive half of the change and the reason to do it pre-launch.
- All by-name resolution (REST path params, MCP `pipelines_get`,
  `templates_get`, template refs in pipeline JSON, PIPELINE-node
  `{name, version}` refs from the composition spec) resolves **within the
  active workspace**. Cross-workspace references do not exist in v1.
- The "name not reusable until hard-deleted" rule (soft-delete uniqueness)
  becomes per-workspace, unchanged in spirit.
- Execution history is keyed by UUID and workspace-scoped via its pipeline;
  unaffected.

The considered-and-rejected fallback (recorded so it isn't re-litigated):
global uniqueness everywhere — fully additive migration, but cross-workspace
collision friction, a name-existence leak, and the same break deferred to v2
at strictly higher cost. **Owner ratified taking the break now (2026-08-16).**

## 4. Entity model (DDL authority: metadata-db.md at implementation)

New tables:

```sql
CREATE TABLE workspaces (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         TEXT NOT NULL UNIQUE,          -- [a-z0-9_-]+, 1–63, immutable (referenced in config/UX)
    display_name TEXT NOT NULL,
    is_personal  BOOLEAN NOT NULL DEFAULT FALSE, -- TRUE only for auto-per-user provisioned workspaces
    created_by   UUID NOT NULL REFERENCES users(id),
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE workspace_members (
    workspace_id UUID NOT NULL REFERENCES workspaces(id),
    user_id      UUID NOT NULL REFERENCES users(id),
    role         TEXT NOT NULL DEFAULT 'member',   -- 'owner' | 'member' (CHECK)
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (workspace_id, user_id)
);
```

Column additions (all via new Flyway migration):

| Table | Column | Notes |
|---|---|---|
| `pipelines` | `workspace_id UUID NOT NULL REFERENCES workspaces(id)` | Backfilled to `default` workspace; `UNIQUE(name)` → `UNIQUE(workspace_id, name)` |
| `templates` | `workspace_id` + surrogate-PK restructure | Per §3; backfilled to `default` |
| `datasources` | `workspace_id UUID NULL REFERENCES workspaces(id)` | **NULL = global** (existing rows backfill to NULL, preserving shared behavior, D9) |
| `datasources` | `is_readonly BOOLEAN NOT NULL DEFAULT FALSE` | §6 |
| `api_keys` | `workspace_id UUID NOT NULL REFERENCES workspaces(id)` | D3 pinning; existing keys backfill to `default` |

Every membership/visibility check reads through the same 60s liveness-cache
discipline as `users.is_active` (auth §6.3) — workspace revocation takes
effect within ~1 minute, and the staleness window is identical to the one
already accepted for deactivation.

## 5. Workspace resolution & authorization

1. **Session (JWT):** login stamps an `active_workspace` claim — last-used,
   else the user's first membership, else (auto-per-user mode) the freshly
   provisioned personal workspace. The UI workspace switcher and API callers
   change it per-request with `DP-Workspace: <workspace name>`; a value the
   principal isn't a member of → 403 `workspace.membership_required`.
2. **API key / MCP:** the key's pinned `workspace_id` IS the context.
   Issuance restricted to workspaces the creator is a member of (extends the
   existing "key scopes ⊆ creator scopes" guard, auth §7.4). `DP-Workspace`
   on an API-key request is rejected, not ignored (silent ignoring would
   train agents on a lie).
3. **Visibility:** list/get/execute/author operations see: the active
   workspace's pipelines/templates/executions + workspace-bound datasources,
   plus all `global` datasources. Global `is_admin` bypasses membership
   checks (can address any workspace via `DP-Workspace`) but gets NO
   implicit merged view — admin listings scope to the ACTIVE workspace like
   every principal (ratified 019 ruling, Deviation-9; a cross-workspace
   admin view is a future feature, not this spec).
4. **Scope matrix (auth §7.6) additions** — workspace CRUD + membership
   endpoints (`/api/v1/workspaces/**`): create per provisioning mode; update/
   member-management: workspace `owner` (or admin); list-own/read: any
   authenticated. Existing operation rows gain no new scopes — workspace
   membership is a second, orthogonal check, enforced in the same
   interceptor layer, default-deny.

## 6. Readonly semantics

`is_readonly` on a datasource forbids the **three and only three**
write-shaped uses in the pipeline contract:

1. `DML` node with `source` = that datasource (contract §4.4)
2. `DDL` node with `source` = that datasource (contract §4.5)
3. Any node with `output.target: "datasource"` naming it (contract §4.7)

`DQL` reads and everything involving `tempdb` are untouched.

**Layer 1 — save-time validation (contract §12, codes PROPOSED):**

| Proposed code | Check |
|---|---|
| `pipeline.validation.datasource_readonly` | No DML/DDL node sources a readonly datasource; no `output.datasource` names one |

One code, `details` carrying node id + datasource name + which of the three
shapes — the agent needs the pointer, not three enum values.

**Layer 2 — execution-time backstop:** the executor re-checks the live
registry entry per node (covers D10 flips between save and run) and fails the
node with proposed runtime code `pipeline.node.datasource_readonly` (§13.4
family, 500-class like its siblings). Additionally the dialect adapter builds
the pool for a readonly datasource with Hikari's `readOnly=true`; treated as
defense in depth, not proof — JDBC read-only enforcement strength varies by
driver, and (empirically corrected at 020: probed on the pinned HikariCP
6.3.0 + H2 2.3.232) the flag reaches the pool, NOT the leased connection —
see datasources.md §5.7 layer 3 for the verified wording. `properties.hikari.readOnly`
joins the server-managed refusal set (§5.6) — operator passthrough must not
silently flip the flag either way on a readonly datasource.

**Layer 3 — credentials (normative deployment guidance, not code):**
datasources.md gains a sentence: the readonly flag is contract, not
containment — a datasource whose data must not change gets a SELECT-only DB
user regardless. Spec 2's seeded datasources do exactly this.

`is_readonly` is editable by whoever may edit the datasource (D8), **except**
that only `admin` may set it on/off for `global` datasources (same gate as
the `global` flag itself — flipping shared infrastructure is an admin act).

## 7. Provisioning modes (config authority: configuration.md at implementation)

Proposed keys, `datapipelines.workspaces.*` (env derivation per configuration
§1, e.g. `DATAPIPELINES_WORKSPACES_PROVISIONING_MODE`):

| YAML path | Default | Description |
|---|---|---|
| `provisioning-mode` | `self-serve` | `auto-per-user` \| `self-serve` \| `closed` |
| `open-join` | `false` | `self-serve` only: `true` lists all workspaces as joinable by any authenticated user; `false` = members are added by a workspace owner |
| `member-datasources-enabled` | `true` | D8 gate: may non-admin members create workspace-bound datasources |

Mode behavior:

- **`auto-per-user`** (demo): on first OIDC login (auth §4.2 step between
  provisioning and JWT issuance), create workspace `is_personal = TRUE`,
  name derived from the lowercased email local-part (sanitized to the name
  regex, collision-suffixed), creator = `owner`. Users may still create
  additional workspaces (it's a superset of self-serve).
- **`self-serve`** (company default): any authenticated user creates
  workspaces; join per `open-join`.
- **`closed`**: only `admin` creates workspaces and manages membership —
  the base the future invite flow tightens into (invites: ROADMAP, not v1).

A user with zero memberships (possible under `closed`) authenticates fine
but every workspace-scoped operation 403s with `workspace.membership_required`
— the UI shows an empty state, not an error page.

## 8. Error codes (PROPOSED — enter enums §16 + contract §13 with code constants, same commit)

New domain `workspace.*`:

| Code | HTTP | Meaning |
|---|---|---|
| `workspace.not_found` | 404 | Unknown workspace name / not visible to principal |
| `workspace.membership_required` | 403 | Principal not a member of the addressed workspace |
| `workspace.validation.name_invalid` | 400 | Name fails `[a-z0-9_-]+`, 1–63 |
| `workspace.validation.duplicate_name` | 409 | Name exists (global namespace, incl. soft-deleted — house rule) |
| `workspace.creation_forbidden` | 403 | Provisioning mode forbids self-creation |
| `workspace.in_use` | 409 | Delete blocked: workspace still owns non-deleted pipelines/templates/datasources |

Plus §6's `pipeline.validation.datasource_readonly` and
`pipeline.node.datasource_readonly`, and `datasource.validation.workspace_forbidden`
(400: non-admin attempted `global`/`readonly`-on-global, or workspace binding
to a workspace they're not in).

## 9. Surfaces

- **REST:** new `/api/v1/workspaces` CRUD + `/members` sub-resource
  (rest-api.md amendment); every existing collection endpoint's behavior
  becomes workspace-scoped per §5.3 — paths do not change.
- **MCP:** no new tools in v1 (workspace management is a human act); all 17
  existing tools operate inside the key's pinned workspace. The MCP
  instructions/resource text must state the workspace context so agents
  don't reason about invisible siblings.
- **UI (ui-screens.md amendment):** workspace switcher in the shell; a
  workspace admin screen (create/join per mode, member list for owners);
  the datasource form gains `global` (admin-only, visible-disabled
  otherwise) and `readonly` checkboxes — owner-requested screen, 2026-08-16.
- **Datasource GET/list payloads** gain `workspace` (name, null = global)
  and `readonly` fields — additive.

## 10. Testing

- **auth/metadata:** membership resolution, `DP-Workspace` switching + 403s,
  API-key pinning (issuance restriction, header rejection), liveness-cache
  revocation timing, migration backfill (default workspace, NULL-global
  datasources).
- **pipeline-contract:** the readonly validation row against all three write
  shapes + the readonly-flip runtime backstop; per-workspace uniqueness
  (same name, two workspaces = legal; same workspace = `duplicate_name`).
- **datasources:** `readOnly` pool flag on readonly datasources; refusal-set
  addition; D8 permission matrix (member vs admin × workspace-bound vs
  global × gate on/off).
- **integration:** two workspaces, one global readonly datasource — assert
  full isolation of pipelines/templates/executions and shared read access;
  a write-shaped pipeline against the readonly datasource fails at save AND
  (stored pre-flip) at execution.

## 11. Explicitly out of scope (deferred)

### 11.1 The delete check-then-act race (022/F10 — decided 2026-08-29, 025 A3: accepted and documented, detected)

Workspace delete counts the workspace's non-deleted pipelines/templates/datasources, then
soft-deletes the workspace. Nothing spans the two steps: the counted tables live in three
other modules, reached through the `WorkspaceContentCheck` port — auth can neither
transaction over them nor lock them. A content create that resolves the workspace before
the soft delete and commits after the count therefore strands its rows: invisible to every
listing (memberships join `is_deleted = FALSE`), the workspace name permanently held.

**Decision: accept for v1, with detection.** The alternatives were rejected on cost and
blast radius, not feasibility:

- *Advisory lock* (`pg_advisory_xact_lock` keyed by workspace id around count+delete)
  closes the race only if EVERY content-save path — pipeline create/update/import, template
  create/update/render-import, datasource save, example seeding, bootstrap registration —
  takes the same lock. That is a cross-module locking protocol invented at the last code
  round before launch; one uncovered writer reopens the exact race while looking guarded.
- *Two-phase delete* (a `deleting` status claimed atomically before the count, restored on
  `in_use`) narrows the bad state but does not close the window — the stranding insert can
  still land between the post-claim count and the commit, and refusing it needs the same
  cross-module write-path checks as the lock protocol.
- *Content-table triggers* refusing inserts into soft-deleted workspaces close it
  hermetically, but surface as raw SQL exceptions on three modules' save paths with no
  catalogued code, and introduce hidden DB behavior the codebase has none of. This is the
  recorded v2 closure if stranded content becomes an operator-visible problem.

What v1 ships instead: a **post-delete recount** in `WorkspaceService.delete` — content
found after the soft delete emits the `auth.workspace.stranded_content` audit event plus an
ERROR log naming the SQL recovery (un-delete the workspace or remove the stranded rows).
The detector is best-effort and narrow: it sees only commits that land between the
pre-delete count and the recount — a commit after the recount still strands, silently.

The honest window is much wider than the check-then-act gap. Workspace and membership
resolution are served by `AuthCache` — in-process per instance, TTL
`datapipelines.auth.api-keys.cache-ttl-seconds` (default 60s, `AuthProperties`) — and
`delete` evicts only the deleting instance's entries (`invalidateMemberships` /
`invalidateWorkspace`); every other instance converges at TTL expiry. In the multi-instance
deployment the docs ship, a member whose workspace was deleted on one instance keeps
resolving it on another for up to one TTL — no concurrent create required, just ordinary
traffic — and content written through that stale resolution strands after the recount,
invisible to the detector. The accept-for-v1 call stands (the alternatives above are
unchanged), but the cache-TTL window is what makes content-table triggers the recorded v2
closure rather than optional hardening.

- Admin invite/approval flow (`closed` + owner-adds-members covers v1).
- Per-workspace roles beyond owner/member; per-datasource ACLs (ROADMAP).
- Per-workspace quotas/rate limits (demo abuse control is deployment-level
  in v1 — existing global rate limiting §3.7; revisit before public launch).
- Personal-workspace TTL cleanup for the demo (spec 2 operational note).
- Cross-workspace references (pipeline composition across workspaces).
- Workspace rename (name immutable v1, like datasources).
