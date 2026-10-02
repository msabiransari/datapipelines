# Parameter Sets workspace — implementation specification

**Status:** implementation specification for review; freezes what [issue #357](https://github.com/msabiransari/datapipelines/issues/357)
left open. Written 2026-10-02 against `main` at `0b2c0922`; every existing name, table, route, count
and file cited below was read from that tree, not recalled.
**Implements:** the issue's body and its two owner comments of 2026-10-01 — the rulings are binding
here and are not restated: **R1** (SSE evaluation tracing is exclusive to the Parameter Sets
inspection page, by consuming flow, no deployment-mode switch; ordinary evaluation keeps the
completed response and produces no trace events; each observed evaluation has its own identity; the
UI rejects superseded attempts; the stream ends with the result or an explicit terminal error) and
**R2** (durable tracking of parameter-set query executions and errors, analogous to pipeline
execution history, independent of the page-only SSE, recorded ONCE per actual attempt including
terminal/failure paths) and **R3** (the history is exposed on the Parameter Sets workspace with the
house table component, reusing execution-history conventions for access control, bounded
diagnostics/redaction and retention). Where this document chooses a name or a number the rulings did
not, the choice is marked **(spec 2026-10-02)** and collected in §11.
**Mould:** the dashboard implementation spec (`2026-09-28-dashboard-implementation-spec.md`) — same
section shape, same FROZEN/CHOSEN discipline. **Route shape:** the pipeline workspace's
(#348, landed on this base).
**Distribution:** contributor design material under `docs/superpowers/specs/`, outside the product
documentation packaging allowlist (`docs/README.md` indexes no spec — 0 matches on this base). The
product documents this creates are listed in §9.

## 0. What ships, in three slices, and what does not

The issue: parameter sets are independent versioned artifacts with no navigation entry, no browse
surface and no evaluation workspace. The engine (#194, all lanes landed) ships REST + MCP only; the
dashboards (L3a/L3b, landed) render parameter controls for a PINNED RELEASED set inside a board.
This specification adds the first-party Parameter Sets workspace. Three bounded implementation
lanes, in dependency order:

- **S1 — navigation + the version-explicit definition workspace** (first, alone): the `Build` rail
  entry with its lazy tree (#350's pattern when it lands; the Dashboards-style branch until then —
  #350 is in flight on this base, not merged), the tree page `GET /parameter-sets`, the canonical
  workspace `GET /parameter-sets/{id}?version=&tab=` in the #348 shape (resolve rules of
  `PipelineWorkspaceModel.resolve`, `:52`; `parseRequestedVersion`, `:208`), the dependency graph
  with the per-node inspector, and the live parameter form driven by the **EXISTING** evaluate
  response (`POST /api/v1/parameter-sets/{id}/evaluate` unchanged, `ParameterSetsController.evaluate`
  `:361–:406`). **Fence (closed):** `templates/layouts/default.html` (one branch), `AppNav.kt`,
  `templates/parameter-sets/**`, `static/js/datapipelines-dashboard.js` (the parameters-only entry,
  §6.3), `static/js/parameter-workspace/**` (new, the graph + glue), the controllers/models/partials
  for the two routes and their tree partial, `ParameterSetsUiController`/`ParameterSetsBrowseModel`
  (new, `modules/web`), their tests, and the doc rows of §9 that S1 owns. **No engine change** —
  S1 touches nothing in `modules/parameters`. **Acceptance, falsified both ways:** the §12
  scenarios 1–4 and 8; the resolve matrix (explicit version → current → draft-labelled →
  choose-a-version) proven by a browser case per arm, each falsified by breaking the resolver's
  corresponding rule (clamp, silent fallback, lens leak) and going red on the SAME assertion.
- **S2 — the observed evaluation (R1) + the graph driven by it:** the frozen protocol of §4
  (`POST /api/v1/parameter-sets/{id}/evaluations`, the event kinds, the supersede and terminal
  rules, the per-event authority), the observer port in `modules/parameters`, the streaming adapter
  in `modules/web`, the graph's live states. **Fence (closed):** the observer port + the evaluator's
  one new optional parameter (`ParameterEvaluator`, `modules/parameters`), the stream controller +
  stream/authority classes (new, `modules/web`), the protocol's event projection (`modules/web`),
  the golden test that pins `POST /{id}/evaluate` byte-for-byte and the guard that ordinary callers
  attach nothing, the graph live-state rendering in `static/js/parameter-workspace/**`, the
  protocol's doc rows (rest-api §21.x new). **Acceptance:** §12 scenarios 3–7 and 10; the golden
  test red if `/evaluate` gains so much as a header, the observer guard red on any ordinary call
  site referencing the observer type, the supersede rule red on a planted late-frame accept.
- **S3 — the durable history (R2/R3):** the two tables of §2, the recorder on the evaluator's
  lifecycle (every caller, every outcome), the history tab (house table) + the record detail, the
  retention sweep row, the bounds. **Fence (closed):** the migration (§2), the repository + recorder
  port and its implementation (`modules/parameters`), the recorder wiring + sweep registration
  (`modules/web`), the history partials + browse model (`modules/web`), the retention/cleanup tests,
  the doc rows of §9 that S3 owns. **Acceptance:** §12 scenarios 3, 5–7, 9–10; the once-per-attempt
  invariant falsified by planting a double-write (two rows for one statement) and a swallowed
  terminal path (a TIMEOUT or FAILED evaluation recording no terminal write → the sweeper's
  INCOMPLETE row and the test red); the refusal-vs-executed distinction falsified by deleting the
  outcome column's refusal arm (a REFUSED row becomes indistinguishable → red).

**Does not ship (each has its extension point named):** an incremental/incremental-only evaluation
semantics change (the engine evaluates the whole graph per submission — the issue fixes this); a
parameter-set authoring UI (lifecycle verbs stay REST/MCP; the workspace is read + evaluate); an
MCP tool or REST surface for the history (§7); an abort route for an observed evaluation (§11.7);
binding a set to a pipeline's `parameters` (the record's §13); dashboards' server-side parameter
state (draft §4.3 — dashboards', not this spec's); a deployment-mode or environment switch for
tracing (R1 withdraws the question).

## 1. Modules and dependency edges

No new module. The layering table (`docs/module-structure.md` §4.2, `:172`; the root build's
`allowedInternalDependencies`, `build.gradle.kts:123`) already carries every edge this work needs;
**no edge is added or changed** — `verifyModuleDependencies` (which parses §4.2 and the root map
against each other in both directions, `build.gradle.kts:276–:302`) is the build-time proof.

| Concern | Home | Edges used (existing) |
|---|---|---|
| The observation port R1 hangs off | `modules/parameters` — `ParameterEvaluationObserver` (new interface, beside `ParameterEvaluator`), passed as ONE optional argument of `ParameterEvaluator.evaluate` (`:103`), default `NONE` | none (a port with a no-op constant in the same module) |
| The durable recorder R2 hangs off | `modules/parameters` — `ParameterEvaluationRecorder` (new interface + the repository-backed implementation in the same module; the repository beside `ParameterSetRepository`) | `parameters`' own spring-jdbc (already declared, `:modules:parameters` row `build.gradle.kts:175–:183`) |
| The evaluator's lifecycle (the ONE place both attach) | `ParameterEvaluator` — every caller funnels through `evaluate`/`evaluateBlocking` (`ParameterSetsController.evaluate` `:404`, `DashboardRuntime` `:105`/`:156`, `ParameterSetsTools`) | — |
| The SSE stream (R1's transport) | `modules/web` — a controller + stream class in `co.datapipelines.web.parameters`, reusing the `sse` package's framing and authority shapes (`ExecutionStreamAuthority` `:56`, the `RefreshStream`/`RefreshStreamAuthority` mould `:41`/`:188`) | `web → parameters` (existing, the `:modules:web` row) |
| The history repository | `modules/parameters` (the evaluation is a parameter-engine record; its tables join `parameter_sets`) | its own |
| The retention sweep | `modules/web` — one step on the existing hourly housekeeping (`RetentionSchedulingConfiguration`, `web/config`; the `DashboardRefreshHousekeeping` shape, `web/dashboards/runtime`) | `web → parameters` |
| The pages, partials, tree, graph glue | `modules/web` (templates, static assets, UI controllers/models) | `web → parameters` |

`ArchitectureGuardTest` keeps `modules/parameters` free of `co.datapipelines.web.*` / `co.datapipelines.mcp.*`
imports — the observer port and the recorder interface must therefore carry no servlet/SSE type;
frames and records are plain data. The migration lands in `modules/app/src/main/resources/db/migration/`
(V39 `parameter_sets.sql` is the mould; the number rule is §2).

## 2. Data model — one migration, two tables

The migration is **the next free `V__` at the build lane's dispatch, recorded in its handback**
(on this base the latest applied is V43 `dashboard_refreshes`; main now carries V44 (353) and V46 (332), V45 is L5's (367), unmerged;
the residue lane may take one — a lane never guesses, it reads `db/migration/` at dispatch). The
mould is V39 (`parameter_sets.sql`) for the constraint style; the principal/identity columns copy
V43's `dashboard_refreshes` (metadata-db §4.34, `:1304`).

### 2.1 `parameter_evaluations` — one row per evaluation attempt

| Column | Definition |
|---|---|
| `id` | UUID PK. On the observed route it is the client-minted `evaluation_id` (§3); everywhere else the server mints it. Never re-issued (C29's rule). |
| `workspace_id` | UUID FK workspaces. |
| `parameter_set_id` | UUID FK `parameter_sets` **ON DELETE CASCADE** — the entity purge takes the set's history (a history of a purged artifact answers as absent, the dashboard refreshes' rule). |
| `parameter_set_version` | INT NOT NULL — the version actually evaluated (the page's explicit one; the resolved served one elsewhere). NO FK to `parameter_set_versions`: purging a version must not delete the record that says it ran. |
| `caller` | CHECK IN (`'PAGE'`, `'DASHBOARD'`, `'PIPELINE'`, `'REST'`, `'MCP'`) — R2's caller/consumer discriminator, all five values declared now (widening a CHECK is a migration; the ruling names all five; `PIPELINE` rows simply do not occur until the record §13's consumer binding lands). |
| `principal_user_id` | UUID NULL FK users. |
| `principal_key_id` | TEXT NULL. Exactly one non-null — the `chk_dashboard_refreshes_principal` CHECK's shape (`metadata-db.md:1325`). |
| `correlation_id` | TEXT NULL — the caller's correlation identity when one exists (the dashboard runtime fills the refresh id; REST/MCP/page leave null — the evaluation id is already the identity). |
| `status` | CHECK IN (`'RUNNING'`, `'COMPLETED'`, `'TIMEOUT'`, `'FAILED'`, `'INCOMPLETE'`). `COMPLETED` = the response was produced (`valid` set); `TIMEOUT` = the whole-request `parameter.evaluate.timeout`; `FAILED` = an uncatalogued error (500-class) or a whole-request refusal after work began (`outcome_code` names it, e.g. `parameter.evaluate.response_too_large`); `INCOMPLETE` = no terminal write ever landed (instance crash, persistence failure) — written only by the sweeper (§2.3). |
| `outcome_code` | TEXT NULL — the catalogued whole-request terminal code when there is one; never a driver message. |
| `valid` | BOOLEAN NULL — the response's `valid` flag at COMPLETED (an evaluation of an invalid form is a COMPLETED, `valid = false` evaluation — never a failure). |
| `outcomes_json` | JSONB NULL — per parameter, one bounded entry `{name, outcome: resolved\|reset\|error, error_code?, detail?}`, `detail ≤ 200` chars, **never a value** (the executions' no-selection-values rule, dashboards §13; values ride no history row). CHECK `pg_column_size(outcomes_json) <= 8192`. `required_missing`, `constraint_violation`, `selector_rows_invalid` and every per-parameter error land here — the history page shows them without fabricating query rows for parameters that ran none. |
| `started_at`, `finished_at` | TIMESTAMPTZ; `finished_at` NULL until the terminal write. |

Indexes: `(workspace_id, parameter_set_id, started_at DESC)` for the history page; a partial index
on `started_at WHERE status = 'RUNNING'` for the sweeper's cutoff scan (the
`idx_dashboard_refreshes_finished` shape, `metadata-db.md:1462`).

### 2.2 `parameter_evaluation_queries` — one row per actual statement attempt

| Column | Definition |
|---|---|
| `id` | UUID PK, server-minted. |
| `evaluation_id` | UUID FK `parameter_evaluations` **ON DELETE CASCADE**. |
| `parameter` | TEXT NOT NULL — the parameter whose selector (or database-fed INPUT source) produced the attempt. |
| `datasource` | TEXT NOT NULL — the resolved datasource name (re-resolved per evaluate, C25). |
| `template_id`, `template_version` | NOT NULL — the pin that rendered the statement (every attempt comes from a pinned template; the record's P22). |
| `queued_at`, `started_at`, `ended_at` | TIMESTAMPTZ NULLs — queued (admitted to the `SelectorPool`), started (the statement handed to the driver), ended. **Concurrency is preserved in the evidence**: each row carries its own three stamps and nothing serialises them; overlapping `started_at`/`ended_at` ranges ARE the proof two selectors ran concurrently (§12 scenario 5). |
| `outcome` | CHECK IN (`'EXECUTED'`, `'REFUSED'`, `'FAILED'`, `'TIMEOUT'`). A refusal before execution is a row with NO statement outcome — distinguishable by THIS column, never by absence (R2). `REFUSED` covers: the read-only gate, `bind_undeclared`/`sql_parameter_missing`, a render failure at evaluate, admission saturation (`parameter.evaluate.selectors_saturated`), `too_many_binds`, a datasource unreachable. `TIMEOUT` = the deadline fired `Statement.cancel()` + `ConnectionPool.discard` (the record's §5.2 step 3) — the worker may still be running; the row never claims it stopped. |
| `refusal_code` | TEXT NULL — the catalogued code when `outcome = 'REFUSED'`. |
| `error_code` | TEXT NULL — the owning subsystem's catalogued code when `outcome = 'FAILED'` (C20's mapping: `pipeline.execution.datasource_unreachable`, `pipeline.node.query_execution_failed`, …). |
| `row_count` | INT NULL — rows read, when known (`EXECUTED` only; the option-row count, bounded by `max-options-per-selector`). |

**Never stored, by name:** any SQL text (rendered or pinned — the statement re-renders deterministically
from the pin + the parents' effective values; storing it would double the binds' provenance), any
bind value, any selection or resolved parameter value, any result row, any driver message string.
This is R2's last paragraph read as a schema rule: no column exists that could carry them.

**Constants and free inputs are resolution steps, not query rows** (R2): a `constants` selector and
a plain `default_value` INPUT produce NO row in `parameter_evaluation_queries`; their outcomes ride
`outcomes_json`. An INPUT with a template source DOES run a statement and gets a row.

**Probe/validation provenance (R2's explicit question):** the SAVE-time probe (`SelectorProbe`,
§4 step 6 of the record, maxRows = 2, C24's off-bulkhead path) is **not** an evaluation attempt and
records nothing here — it is authoring validation, visible as a datasource read through the request
interceptor exactly as today. Only evaluate-time attempts are recorded, once each.

### 2.3 Lifecycle and retention

The recorder writes the `parameter_evaluations` row (RUNNING) when the evaluator admits the
attempt and the terminal write at its end, whatever the ending (client gone, deadline passed,
process stopping) — the terminal bookkeeping runs non-cancellably at the one point that owns it
(the dashboard refreshes' `NonCancellable` rule). **Persistence failure never fails an
evaluation**: a failed START logs one structured ERROR (evaluation id, workspace, set, code) and
the evaluation proceeds unrecorded; a failed terminal write logs the same and leaves the row
RUNNING. The hourly housekeeping sweep gains one step (the `DashboardRefreshSweeper` shape,
metadata-db §8.4 `:1777`): a RUNNING row whose `started_at` is older than
`evaluate-timeout-seconds` + one sweep margin becomes `INCOMPLETE` — it never asserts a TIMEOUT
that may not have happened.

**Retention: the executions' EVENT retention policy, applied by the same sweep** (the dashboard
refreshes' precedent — the implementation spec's §18.7 ruled "the executions' policy was no
policy", so the refresh rows follow the EVENT retention; the same holds here):
`datapipelines.executions.event-retention-days` (configuration §3.11 `:185`), the cutoff scan on
`started_at`, one DELETE batch per tick on the existing hourly step
(`RetentionSchedulingConfiguration`). Evaluation records are bounded rows (no values, no SQL, ≤ 64
query rows each — `max-parameters-per-set`) with no result payload; a dedicated key buys nothing
in round one. A future own key (`datapipelines.parameters.history-retention-days`) is an additive
change (§11.5).

## 3. Names and identities

- **`evaluation_id`** — the identity R1 grants each observed evaluation and R2's record carries.
  On the observed route it is **client-minted UUID v4, fresh per attempt** (the refresh_id shape,
  dashboards.md §5.5 — a reused or malformed one is refused `parameter.validation.body_invalid`
  naming the field); everywhere else the server mints it for the record. The UI's stale rule is
  client-side and needs no server registry: every frame carries `evaluation_id`, and the page
  drops any frame whose id is not its current attempt's (R1's superseded-attempt rule).
- **`instance_id`** — client-minted UUID v4 per open page instance (the dashboard instance's
  shape), carried on the observed request for diagnostics only; the server keeps no per-instance
  state in round one (no abort route — §11.7).
- FQNs, ids, addressing: the engine's (P24 — the id in the path, names never in a path segment).
- Stream frame ids: monotonic per stream, the execution stream's framing.

## 4. The observed-evaluation protocol — FROZEN

### 4.1 The request

`POST /api/v1/parameter-sets/{id}/evaluations` **(spec 2026-10-02 — the route R1 left to this
spec)** — a distinct route, not an option on `POST /{id}/evaluate`, so the ordinary evaluate's
byte-for-byte stability is provable (§4.5). Session-authenticated, CSRF as every POST route,
`@RequiredScope(Permission.PARAMETER_SET_EVALUATE)` — the SAME action observed, no new permission
row: the observed route runs the same engine, the same bulkhead, the same caps, on datasources the
caller's evaluate already reaches. **`version` is REQUIRED here** (the page is version-explicit —
a present non-integer is `parameter.validation.body_invalid`; the ordinary route keeps its
served-default). Body:

```json
{ "version": 4, "selections": { "country": "USA" },
  "evaluation_id": "<uuid v4, fresh>", "instance_id": "<uuid v4>" }
```

`selections` follows §5.1 of the engine record exactly (every parameter, absent/null/[] = nothing
chosen, unknown key = the whole-request `parameter.evaluate.unknown_parameter` 400). Refusal order
is the refresh stream's: body shape first, then the set (lens-hidden = the family 404, `:397`'s
rule), then the version (explicit, never clamped), then `parameter_set.evaluate`, then the stream
cap — a refused observation writes nothing and starts nothing.

### 4.2 The stream

Framing is the execution stream's (`event:` + monotonic `id:` + `data:` JSON, a `: heartbeat`
every 15 s, the disconnect grace of rest-api §6.8). **A disconnect does NOT cancel the
evaluation**: the work is deadline-bounded (≤ `evaluate-timeout-seconds`, default 30), the
evaluation runs to its end, its record completes (R2: durable, independent of the page), and the
stream simply ends without a terminal frame — the client treats a stream that ends without one as
a transport failure (the dashboards §5.3 rule) while the history tab shows the truth. No abort
route exists (§11.7): the deadline and the pool's cancel+discard remain the only cancellation
paths, exactly as for every ordinary caller.

Event kinds — FROZEN names; frames carry identity and progress, **never values or options** (the
terminal response is the one truth for state; nothing is said twice):

| Event | `data` |
|---|---|
| `evaluation_started` | `{evaluation_id, parameter_set_id, version, deadline_at}` — always first |
| `parameter_waiting` | `{evaluation_id, parameter, waiting_on: [parents]}` — its coroutine awaits its parents |
| `parameter_admitted` | `{evaluation_id, parameter}` — template-backed; the `SelectorPool` permit is held |
| `parameter_running` | `{evaluation_id, parameter, datasource, template: {id, version}}` — the statement is at the driver |
| `parameter_resolved` | `{evaluation_id, parameter, origin, reset, rows?}` — resolved (options/values ride the terminal frame; `rows` = the option count when a query produced them) |
| `parameter_failed` | `{evaluation_id, parameter, code, detail?}` — the catalogued per-parameter code, `detail` ≤ 200 chars, code-only (the stream's redaction rule); a saturated selector is THIS event with `parameter.evaluate.selectors_saturated` |
| `evaluation_completed` | `{evaluation_id, response: <the §5.3 EvaluateResponseJson, unchanged>}` — always the last frame on success |
| `evaluation_failed` | `{evaluation_id, code, message?}` — the terminal error (the whole-request `parameter.evaluate.timeout`; `parameter.evaluate.response_too_large`); always the last frame on failure |

**Terminal rule:** `evaluation_completed` or `evaluation_failed` is ALWAYS the last frame.
**Per-parameter coverage:** every parameter of the set gets exactly one terminal per-parameter
frame (`parameter_resolved` or `parameter_failed`) — hidden and disabled included (they are
evaluated and submitted like any other, P5/2026-09-25) — so the graph can always render the
cascade's end state. Constants-resolved and input-typed parameters get `parameter_resolved` with
no query frames — the stream, like the history, never fabricates SQL (R2).

### 4.3 The observer (R1's attachment point)

`ParameterEvaluator.evaluate(workspaceId, set, selections, observation: ParameterEvaluationObserver = NONE)`
— ONE optional argument on the ONE lifecycle every caller shares. The observed route passes a
streaming observer; **ordinary callers pass nothing** — the engine produces no trace events, no
buffers and no stream for them (R1). The recorder (R2) is a constructor collaborator of the
evaluator, not part of the observer: every evaluation records, observed or not, page or dashboard
or REST or MCP (R2's second comment, first paragraph).

### 4.4 Per-event authority (#343's rule)

Before every write the stream re-judges its subscriber — session expiry, workspace membership and
`parameter_set.evaluate` (`ExecutionStreamAuthority.judge`'s shape, `:109`; `RefreshStreamAuthority.judge`
`:213`): a revoked membership, an expired session or a lost permission ENDS THE STREAM at that
write. The evaluation itself continues (it is the recorded act, not the stream). The stream joins
the house per-user SSE connection cap (the refresh stream's registry rule).

### 4.5 The ordinary evaluate is untouched — provable

The golden test the build lane writes: `POST /api/v1/parameter-sets/{id}/evaluate`'s response
bytes equal a recorded fixture, before and after S2 — byte-for-byte (R1: the completed response
and its clients are preserved). The guard: an architecture test that no ordinary call site
(`ParameterSetsController`, `DashboardRuntime`, `ParameterSetsTools`) references
`ParameterEvaluationObserver` — grep-able by TYPE, so a leak is red, not silent.

## 5. Permissions — the roles table (no new action is created by this specification)

Two existing `parameter_set.*` rows carry every surface this spec proposes; the catalog stays at
99 (`Permission.kt`, `:20`, entries read on this base), the matrix at its current ten columns —
L5's `dashboard_viewer` column (issue 367, in flight) lands none of these surfaces, and no
delegated act is created: the page's observed evaluation is the caller's OWN `parameter_set.evaluate`,
judged directly, never through another row.

| Surface (proposed) | viewer | author | promoter | ws_admin | super_admin | api_caller | promotion_receiver | mcp:author | mcp:promoter | mcp:ws_admin | Permission / guard |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `GET /parameter-sets` (tree page), `GET /partials/parameter-sets/tree`, the nav branch | ✓ | ✓ | lens | ✓ | ✓ | ✗ | ✗ | n/a | n/a | n/a | `parameter_set.read` (the `parameter_set.read` row's cells, auth §7.6 `:895`); the `RoleWalkE2eTest` row, `ReadFloorTest`, `ShellRenderTest`/`RoleVisibilityRenderTest`, lensed browser cases |
| `GET /parameter-sets/{id}?version=&tab=` (the workspace; both tabs), its partials | ✓ | ✓ | lens | ✓ | ✓ | ✗ | ✗ | n/a | n/a | n/a | `parameter_set.read` + the lens (the working-version read rule for a draft); same guards |
| `GET /parameter-sets/{id}/evaluations` (history partial), the record detail partial | ✓ | ✓ | lens | ✓ | ✓ | ✗ | ✗ | n/a | n/a | n/a | `parameter_set.read` — the §11.2 ruling; `RoleWalkE2eTest`, lens-hidden-set 404 parity per route |
| `POST /api/v1/parameter-sets/{id}/evaluations` (the observed stream) | ✓ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | n/a | n/a | n/a | `parameter_set.evaluate` (the row at auth §7.6 `:903` — viewer ✓ author ✓ promoter ✗ ws_admin ✓ super_admin ✓, read and quoted on this base; it matches the issue's sentence, so no conflict to rule on); lands with its §7.6 Surfaces row + `RoleWalkE2eTest` expectation in ONE commit (AGENTS.md §4.9) |
| The existing `POST /{id}/evaluate`, unchanged | ✓ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ | unchanged rows; the golden test |
| MCP tools | — | — | — | — | — | — | — | ✓ (read) | lens (read) | ✓ (read) | no tool changes (§7) |

The page needs ONE new `RoleModel` capability boolean for the evaluate controls —
`canEvaluateParameterSets` via `context.permits(Permission.PARAMETER_SET_EVALUATE)` (the
`:85–:94` pattern of `RoleModel.kt`, whose `Roles` booleans at `:61–:70` today carry no
parameter-set capability) — a RENDER flag only; the wire stays permission-gated (a UI permission is
never inferred from a boolean, and a promoter's page omits every evaluate control and issues no
evaluate POST — proven by the browser case, not by markup absence alone).

## 6. The pages

### 6.1 Navigation and the tree

The rail gains one **Build** entry after Dashboards: `Item("/parameter-sets", "Parameter Sets", BUILD)`
in `AppNav.ITEMS` (`:44–:64`) and the hand-rendered branch in `layouts/default.html` (`:260–:360`;
the Dashboards branch `:288–:304` is the exemplar): `div.app-nav-branch` → the link
(`data-nav-section="/parameter-sets"`, `data-nav-group="Build"`) →
`details.app-nav-branch-tree > summary[hx-get=/partials/parameter-sets/tree(scope='nav')][hx-trigger="click once"]`
→ `div.tpl-level.tpl-level-pending#params-tree-nav` — lazy, ONE level per click, no JS beyond the
house tree conventions. **Icon (§11.1):** the existing `chevrons-up-down` glyph — the sprite
(`/vendor/icons/lucide-sprite.svg`, 43 ids read on this base) has no sliders/variable glyph, and
`IconSpriteAuditTest` keeps referenced = vendored = manifest subset, so an existing id is free and
a new one is a vendoring step (§11.1's alternative).

The tree partial is the dashboards tree's shape (`partials/dashboard-tree-level.html`: folders
`:63+`, the leaf `:93–:104`, the pager `:115–:119`, the lens-unavailable notice `:42–:54` — "no
sets" would lie about WHY, 178's rule): `DashboardBrowseModel`'s mechanics (`fillLevel` `:50`,
`PAGE_SIZE 25` `:217`, `NAV_ROOT_ID` `:226`, `SCOPE_PAGE`/`SCOPE_NAV` `:248–:249`) re-read per
request, never cached across workspaces or lenses. **The leaf NAVIGATES** (`hx-boost="false"`,
`<a>`) to `GET /parameter-sets/{id}` — the dashboards behaviour, not the pipelines explorer's
select-into-pane (`pipeline-tree-level.html:98` is a `<button>` because #349/#350 compose a
destination pane; parameter sets have no pane to select into, and the workspace IS the full-page
destination) (§11.9). Each leaf names the working version (`v` badge) and a draft badge when
unreleased, the dashboards leaf's exact semantics.

`GET /parameter-sets` is the tree page over the same partial (the `dashboards/list.html` shape —
36 lines, one pane), `parameter_set.read`, the promoter's lens applied server-side: a set her lens
hides is ABSENT (never a disabled row), and the lens-unavailable notice renders when the lens
answers nothing.

### 6.2 The canonical workspace

`GET /parameter-sets/{id}?version=&tab=` **(the #348 shape, adopted)** — `parameter_set.read`,
the promoter's lens; `version` is a String parsed by `parseRequestedVersion`'s rule (non-numeric →
the house 400); resolve, in order, `PipelineWorkspaceModel.resolve`'s rules (`:52`): an explicit
admitted version, else the house 404 (never clamps, never falls back silently) → the actual
current pointer if the lens admits it → an accessible draft, labelled → a choose-a-version/empty
state. Fields describing the viewed version come from that version's row/body. The page ALWAYS
evaluates the VIEWED version explicitly — both evaluate routes carry `version`; the served-default
path (`:396`) is never exercised from this page. A DRAFT-ONLY set renders labelled for its author
(the working-version read rule admits her evaluate), and as the empty/choose state for everyone
else — a promoter's lens answers absent.

Tabs: the closed set `workspace | history` (unknown/missing → `workspace`). The two
`<script type="application/json">` blocks (the #348 pattern, `editor.html` `:53`/`:58`) ride
`ScriptSafeJson.forScriptBlock`; the mapper is a field, not a constructor parameter (the
`ObjectMapperDefaultParameterKonsistTest` rule).

**The workspace tab** — the issue's combined view, three regions:

1. **The dependency graph** — Cytoscape, the pipeline editor's exact stack: the vendored
   `cytoscape 3.34.0` / `cytoscape-dagre 2.5.0` / `cytoscape-node-html-label 1.2.2` / `dagre`
   (the manifest pins), loaded in the dagre → cytoscape → cytoscape-dagre → node-html-label order
   THROUGH a runtime template, never as bare tags (the #358 rule: htmx re-executes every script it
   restores from the history cache; the editor's `pe-runtime-scripts` template + `runtime.js` is
   the pattern since the fix — a second graph page copies the mechanism, not the bare-tag shape the
   brief's fact pass recorded). **No CSP change**: `SecurityHeaders` (`modules/auth`) hashes
   Cytoscape's ONE injected stylesheet (`CYTOSCAPE_STYLESHEET` `:87`, the hash `:89–:94`) into the
   ONE policy that covers every route (KDoc `:40–:59`; the test derives the hash from the vendored
   file, so no `SecurityHeaders` edit is needed — the browser suite's zero-violation collector is
   the proof). One node per parameter; an edge per `depends_on`; node badge = the source kind
   (input / constants / template); node state = idle / waiting / admitted / running / resolved /
   failed / reset (S2's stream drives it; S1 renders the static graph from the working version).
   A node click selects: the inspector opens, dependents highlight, graph width is unchanged.
2. **The live form** — the parameter controls, rendered by the house renderer (§6.3), beside the
   graph; submitted WHOLE on every change (P27 — every parameter, every time, hidden/disabled
   included; the client runs no dependency logic). A submission mints a fresh `evaluation_id`.
3. **The inspector** — per selected node: label/type/kind/cardinality/required/default/constraints/presentation;
   the source — a constants table (`{value, display_value, is_default}`) or the pinned template +
   datasource (name, version, the datasource's own read boundary applies to any link out — the
   issue's sentence; the workspace links the datasource NAME only and never inlines its detail
   past what the reader's `datasource.read` admits, the lens applying there as everywhere);
   `depends_on`/`dependents` (both informational, P14); the hidden/disabled expressions (read as
   stored ASTs, labelled); the node's last evaluation outcome (origin, reset, errors) once an
   evaluation has run.

**Graph + form synchronisation — the issue's five cases, each a stated rule with its named test:**

| Rule | Behaviour | Test |
|---|---|---|
| Defaults | After every evaluation the controls show the response's values; a value whose `origin` is `default`/`first`/`source` carries a visible origin hint; the first render submits `{}` | browser: first render shows defaults + hints (§12 scenario 2) |
| Resets | A `reset: true` parameter shows a reset indicator and its `computed_default` affordance; its dependents' re-resolution is visible on the graph | browser: change country → state/city marked reset with indicators (scenario 3) |
| Hidden / disabled | Hidden parameters render collapsed-but-accounted (their values still submitted — P5's clarification); disabled render inert; a hidden parameter's errors still surface | browser: an expression-hidden parameter keeps submitting and stays accounted (scenario 8) |
| Failed selectors | The server's errors render beneath the control verbatim (§13a.1 — invented none), the node shows failed, dependents show resolved-against-null | browser: unreachable datasource → error text + node state (scenario 6) |
| Rapid successive changes | The attempt-generation rule: each submit mints a fresh `evaluation_id`; every frame/response of a superseded attempt is dropped before it touches the DOM; the form locks while one attempt is in flight, released by the terminal frame or the evaluate deadline | browser: two rapid submits — the first's late `evaluation_completed` changes nothing; its stream's frames are dropped (scenario 7) |

Version switching re-resolves the page as ONE view transition (no document reload — the pipeline
workspace's rule); an in-flight evaluation of the previous version is superseded (new generation),
its events dropped, its history record still landing (the server does not know about UI
generations). Parameter values never enter URLs, history or storage (the workspace spec's rule).

### 6.3 The renderer — one renderer, a runtime entry, no second implementation

The house renderer is `datapipelines-dashboard.js` (2011 lines): it exports
`window.DatapipelinesDashboard = {init, registerRenderer, adapters, DashboardError, isDashboardError,
_internal}` (`:1980–:2010`); the composite adapter `adapters(container)` (`:1630`) provides
`renderParameters` (`:1721`), `readSelections` (`:1909`, typed wire values, hidden/disabled
included), `onEdit`/`onCommit` (`:1920–:1925`). Its input is `EvaluateResponseJson`'s shape; it
does not read `origin`, `reset` or `computed_default` today — the origin hint and reset indicator
of §6.2 are additive renderer concerns in this lane's fence. **Standalone use is partial as the
tree stands**: the adapter's root mounts only under `this.container || this.defaultSlot` (both set
by `mountLayout`, `:1653–:1680`), and the lock / supersede / deadline logic lives in
`DashboardInstance._evaluateParameters` (`:611`) and `_acceptParameterResponse` (`:684`), not in
the adapter. **Decision (§11.8): the runtime grows a parameters-only entry** — one new public
factory (name at build: `initParameters`) that reuses the adapter and the instance's parameter
machinery (attempt generations, the lock, the deadline) against the parameter-sets routes, with no
board dependency — never a page-local copy of the stale-attempt logic (duplicate drift is how the
supersede rule rots). The dashboard's own binding (`DashboardBody.parameterSet` →
`/api/v1/dashboards/{id}/runtime/parameters`, answering `EvaluateResponseJson` UNCHANGED plus
`overrides_applied`/`parents`/`parameter_revision` — `RuntimeViews.kt` `:126–:170`) is untouched:
different routes, same renderer, one implementation.

### 6.4 The history tab (R3)

`GET /parameter-sets/{id}?tab=history` renders the house table (`dt-frame > dt-viewport >
table.ds-table`, `data-table.js`, ui-screens §3.7 `:440`; `data-table.js` is loaded once by the
shell — no second renderer, the workspace spec's D4): one row per evaluation record — started,
caller, principal, version, status, valid, parameter outcomes count, query count, took. The list
is bounded and paged (25 per page, the house pager), newest first, **lensed** (only sets the
caller's lens admits — which on a set's own page is settled by the page's own 404). A row opens
the record detail (a partial, the execution-detail pattern): the evaluation's header (caller,
principal, correlation id when present, status/outcome_code, started/finished) + the per-parameter
outcome list (`outcomes_json` rendered: resolved/reset/error + the catalogued error codes) + the
query-attempt table (parameter, datasource, template id+version, queued/started/ended, outcome,
row_count, refusal_code/error_code) — concurrent attempts visibly overlapping, never re-serialised
(R2). A record of a version since purged still renders (the version column is data, not a join);
a record whose SET was purged answers absent (the CASCADE). Evaluations that originated in a
dashboard, REST or MCP client appear exactly like the page's own (R2: "even when the evaluation
originated elsewhere or no page was open") — with the caller column naming which.

## 7. Tools

**No MCP tool changes.** The catalog keeps its six parameter tools (`McpToolCatalog.kt:136–:141`)
and its count as read at dispatch (60 entries on this base; the pinned NAMES count 59 — L4's two
test-session tools land with 353; neither is this spec's). Evaluation exists as
`parameter_sets_evaluate` (unchanged, no trace events — R1). **History reads: NO tool in round
one** (§11.6) — no agent surface consumes the history today, the wire would need its own
redaction/lens review, and the UI need is met; exposing it later is additive rows in the
`parameter_set.read` Surfaces cell plus the tool, one commit, the usual guards.

## 8. Error codes

**No new code is required.** Every refusal this surface produces exists in pipeline-contract
§13.20 (`:1417`) and is already mapped: the body/refusal shapes reuse `parameter.validation.body_invalid`;
the observed stream's terminal errors are `parameter.evaluate.timeout` and
`parameter.evaluate.response_too_large`; the per-parameter stream failures are the §5.4 codes the
evaluator already emits (`selectors_saturated`, `required_missing`, …). The protocol's one new
REFUSAL SITUATION — a reused or malformed `evaluation_id`/`instance_id`, a missing `version` on
the observed route — is a body-shape refusal and rides `parameter.validation.body_invalid` with
`details.path` (the #300 shape), as the refresh stream's `dashboard.validation.body_invalid` does
for a reused refresh id. The spec-drift guard needs no row: nothing is added to §13.20, so
`ParameterErrorCodesSpecDriftTest` moves only if a lane touches the file's neighbours (it does
not, by fence).

## 9. Documents and guards this creates

| Document | Row this spec creates (the build lane lands it in the same commit as its code) |
|---|---|
| `docs/ui-screens.md` | **§4.22 Parameter Sets** (the next catalogue entry — §4.21 Dashboards `:1718` is the last at v1.94, `:1950`; the number follows landing order, confirmed "next" at the lane's merge), the change-log row, and the §3 shell/nav wording for the new branch |
| `docs/rest-api.md` | §21 gains the observed route (a new 21.x under Parameter Sets, `:2410`'s section) and the page routes' UI-route annotations per the house style; §21's "No UI page exists" sentence is REPLACED (§14, row C1) |
| `docs/auth.md` | §7.6: the new routes ride their existing rows' Surfaces cells (the observed route onto `parameter_set.evaluate` `:903`, the reads onto `parameter_set.read` `:895`); `:895`'s "no UI page (the record's §13)" is REPLACED (§14, C1); the stale count note (§14, C2) is corrected in passing by whichever lane touches the file |
| `docs/enums.md` | No new audit event kind in round one: evaluations are VISIBLE through the history surfaces this spec adds, and the request-interceptor logging plus (for MCP) `mcp.tool.called` cover the audit trail exactly as rest-api §21 states today. If review rules an `parameter.evaluation` audit event necessary (the `dashboard.refresh` precedent, enums §15 `:490–:494`), it is one awaited row at the evaluation's end — decided at S3 review, named here so it is not invented silently |
| `docs/metadata-db.md` | §4.x for the two tables (the V39/V43 style), the retention rule (§2.3), the sweeper step beside §8.4 (`:1777`) |
| `docs/configuration.md` | No new key in round one (§2.3's retention reuses §3.11 `:185`). If S3's review rules an own retention key, it is §3.35 beside §3.34 `:574` with its `*ConfigKeysSpecDriftTest` row |
| The manual (`parameters` area) | One sentence S3 lands if the history ships: "a failed or timed-out evaluation is visible on the Parameter Sets page's History tab, whoever ran it." The manual today directs agents to REST/MCP only; this sentence is the issue's visibility rule, written where agents read |
| `docs/module-structure.md` | No row changes (no new module, no new edge — §1) |

**Guards, per slice** — S1: `ShellRenderTest` + `AdminNavRenderTest` (the AppNav row), `RoleVisibilityRenderTest`
(the branch's visibility), `IconSpriteAuditTest` (the glyph), the resolve-matrix browser cases,
`ObjectMapperDefaultParameterKonsistTest` (the mapper field), the ScriptSafeJson parser witness on
both script blocks; S2: the golden `/evaluate` test, the observer-leak architecture test, the
stream authority's revocation test (the #343 rule, red on a planted write-past-revocation), the
per-user SSE cap test, `RefreshStreamAuthorityTest`'s mould extended by twin classes; S3: the
once-per-attempt invariant test (a latch-held statement records exactly one row), the
refusal-vs-executed column test, the sweeper's INCOMPLETE test, the retention-cutoff test, the
bounds' CHECK tests, `RoleWalkE2eTest`/`ScopeMatrixSpecDriftTest`/`MatrixRowReachabilityTest`/`ReadFloorTest`
for the new routes' rows. Every new browser case runs in both themes (the
`DashboardPagesBrowserTest` mould — its CSP collector, its `seedLocalUser(role="promoter")` and
`[data-lens-unavailable]` arms, `:39`/`:71`/`:95`/`:167`/`:249`).

**Demo content (§12's scenario 1):** `scripts/sample-data/content/` ships NO parameter set today
(0 matches in `examples.json`/`examples-lake.json` for a set — the `year` parameters there are
PIPELINE parameters). S1's lane adds the demo set the issue's acceptance names: a
`<root>/parameters/geo_filters` set over the sample datasources — `country` (template SELECT,
sample-reference), `state` (template SELECT on `country`), `city` (template SELECT on `state`),
one constants-backed SELECT and one INPUT with `min: 0` — its selector templates pinned and
RELEASED, the set RELEASED, so every role's page has content; the sample verify script gains its
row.

## 10. Lane cut

| Slice | Fence | Base / order | Acceptance (falsified both ways) |
|---|---|---|---|
| **S1** (first, alone) | §0's S1 list — nav, tree, the canonical workspace, static graph + inspector, the live form over the EXISTING evaluate | latest main at dispatch (≥ this spec's base) | §12 scenarios 1, 2, 4, 8; the resolve matrix per arm; the renderer's origin/reset additions; zero CSP violations; both themes; the promoter's lensed page with NO evaluate POST issued |
| **S2** | §0's S2 list — the protocol, the observer, the stream, the graph's live states | on S1 (the page is the stream's consumer) | §12 scenarios 3, 6, 7, 10; the golden test + observer guard; the authority revocation; the supersede rule; the SSE cap |
| **S3** | §0's S3 list — the two tables, the recorder, the history tab + detail, the sweep | on S1 (independent of S2 — R2's independence is structural: the recorder rides the evaluator, not the stream; S2 and S3 MAY run in parallel once S1 lands, disjoint fences) | §12 scenarios 3, 5–7, 9, 10; the once-per-attempt and refusal-distinguishable invariants; the sweeper; retention; the concurrency evidence |

S2 ∩ S3 = `ParameterEvaluator`'s constructor area (the recorder collaborator) — frozen by S3's
interface landing first OR coordinated in one review pass; both briefs carry the same interface
names from this spec so the fences meet without negotiation.

## 11. Numbers and choices to confirm

Only what R1–R3 leave open. Each with a recommendation; changing one later is a spec amendment.

1. **Rail icon** — Recommendation: the existing `chevrons-up-down` (a closed choice set; zero new
   vendored bytes; `IconSpriteAuditTest` unchanged). Alternative: vendor lucide
   `sliders-horizontal` through the sprite + manifest sync as a build-lane step (a truer
   "parameters" glyph, one vendoring PR).
2. **History read permission** — Recommendation: `parameter_set.read`, lensed (no new row). The
   executions' split (`execution.read` own / `read_all`) exists because executions cross artifact
   boundaries and carry result payloads; an evaluation record is bounded to one artifact and
   carries NO values, NO SQL, NO results (§2.2) — anyone who may read the set may read what ran
   against it, and the promoter's lens applying to the set applies to its history. Alternative:
   an `execution.read`-shaped split (own vs workspace-all) — rejected for round one: two new
   catalog rows (99 → 101) and a second gate for data the set's own read already admits.
3. **`evaluation_id` minting** — Recommendation: client-minted UUID v4 on the observed route (the
   `refresh_id` shape), server-minted elsewhere; the stale rule stays client-side, no server
   registry. Alternative: server-minted at stream open — costs a round trip and moves the
   supersede rule server-side for no gain (the evaluation is bounded by its own deadline).
4. **The observed route's name** — Recommendation: `POST /api/v1/parameter-sets/{id}/evaluations`
   (SSE), `/evaluate` byte-for-byte frozen. Alternative: an option on `/evaluate` — rejected: one
   route answering two response types cannot prove the golden byte-stability as cleanly, and the
   dashboards runtime already splits parameters (`/runtime/parameters`) from the stream
   (`/runtime/visualizations`).
5. **Retention** — Recommendation: the executions' EVENT retention (`event-retention-days`), the
   same hourly step. Alternative: an own `datapipelines.parameters.history-retention-days`
   (additive later; nothing in the record model needs it — bounded rows, no payloads).
6. **History over REST/MCP** — Recommendation: none in round one (UI partials only). Alternative:
   `GET /api/v1/parameter-sets/{id}/evaluations` on `parameter_set.read` — an additive later row
   with its own redaction review; no consumer exists today.
7. **Disconnect/cancel** — Recommendation: NO abort route; a disconnect never cancels (the work is
   deadline-bounded ≤ 30 s; the record completes; the stream just ends). Alternative: the refresh
   rule (disconnect past the grace aborts) — rejected: cancelling the awaiting coroutine drives
   the same deadline path (cancel + discard) the timeout does, buys nothing, and orphans the
   record's terminal write behind a browser.
8. **Renderer strategy** — Recommendation: the runtime grows a parameters-only entry reusing the
   adapter + the instance's attempt machinery (§6.3). Alternative: a page-local module — rejected:
   it re-implements the supersede/lock logic and drifts.
9. **Tree leaf behaviour** — Recommendation: navigate (dashboards-style), the workspace being a
   full-page destination with no pane to select into. Alternative: the pipelines' select-into-pane
   (#349/#350's shape) — rejected: it presumes an explorer composition parameter sets do not have.
10. **`caller` CHECK** — Recommendation: all five values now (`PIPELINE` included, dormant until
    the record §13's consumer binding lands). Alternative: three now, a migration to widen —
    rejected: the ruling names five; widening a CHECK later is a migration this schema can avoid.

## 12. The acceptance scenarios (the issue's proposal, numbered)

Demo content first: the sample `geo_filters` set of §9 (country → state → city with DISTINCT SQL
sources across the sample datasources, a constants-backed parameter, an INPUT with constraints),
RELEASED, in both sample bundles.

1. **Browse and resolve** — the rail branch expands one lazy level; the tree page lists sets; a
   leaf navigates un-boosted to `GET /parameter-sets/{id}`; the current version renders; explicit
   `?version=N` renders N; a foreign/hidden version 404s (never clamps); a draft-only set renders
   labelled for its author and empty for a promoter.
2. **First render** — `{}` selections: every parameter resolves by the priority; typed values with
   origin hints; the graph shows the `depends_on` shape; the inspector shows each node's source
   (constants table / template pin + datasource / input default).
3. **The cascade, observed (R1's core)** — change the country: the form locks, a fresh
   `evaluation_id` mints, the stream shows `parameter_waiting` → `parameter_admitted` →
   `parameter_running` → `parameter_resolved` for state then city (their true sequencing), reset
   indicators on the invalidated selections, and `evaluation_completed` with the full §5.3
   response; the graph nodes carry each state as it happens; no elapsed-time simulation anywhere —
   every progress frame is a server fact.
4. **Inspect each source** — per node: the constants rows, or the pin's template id + version +
   datasource; the datasource link honours the reader's own `datasource.read` lens.
5. **Parallel independent parameters** — a set with two independent template-backed parameters:
   their `parameter_running` frames interleave; the history's two query rows carry OVERLAPPING
   started/ended stamps — concurrency preserved in the evidence (R2), never a serial story.
6. **Errors** — a selector whose datasource is unreachable (drop the sample datasource): the
   stream's `parameter_failed` with the catalogued code, the form shows the server's error
   verbatim, dependents render resolved-against-null; the history row shows the attempt
   `REFUSED` (or `FAILED`), distinguishable by column from an executed statement; a timeout case
   (a `pg_sleep` selector past the deadline) ends `evaluation_failed` with
   `parameter.evaluate.timeout` and the query row `TIMEOUT` — with no claim the worker stopped.
7. **Late events after another change / version / workspace** — start an observed evaluation on
   v1; switch to v2 (or another set; or switch workspace): v1's frames are dropped by the client,
   the form never mixes generations, and v1's history record still lands complete.
8. **Both themes, keyboard, synchronisation** — the five sync rules of §6.2 each demonstrated
   (defaults, resets, hidden/disabled accounted, failed selectors, rapid changes), light/dark
   screenshots, keyboard tree/graph/inspector traversal.
9. **Role/lens behaviour** — viewer: reads + evaluates; author: the same; promoter: the tree and
   pages show only admitted sets, every evaluate control absent AND no evaluate POST issued
   (network proof), history visible for admitted sets, a hidden set answers absent on EVERY route
   including the history partial and the observed evaluate; ws_admin/super_admin: full.
10. **Ordinariness (R1/R2's boundary)** — while a page holds an open stream, an MCP
    `parameter_sets_evaluate` (and a REST evaluate) completes: byte-identical response, NO stream
    events on the page's stream, and a history row with `caller = MCP`/`REST` — recorded once,
    like every attempt.

## 13. Security

For every proposed surface (the lane's own diff is a specification; the answers bind the build
lanes):

- **CSRF and transport** — the two new POST-surface routes (`/evaluations` observed; nothing else
  mutates) ride the session CSRF protection as every POST route; the stream is a response type of
  one POST, not a socket.
- **Per-event authority** — §4.4: session, membership and `parameter_set.evaluate` re-judged
  before EVERY write; a revoked membership ends the stream at that write (the #343 rule);
  frames resolve the workspace captured at open and require it to match — membership in another
  workspace preserves nothing.
- **What the trace events carry** — identity and progress only: parameter NAMES, datasources,
  template pins, catalogued codes, `origin`/`reset`/row counts. Never a selection value, never a
  resolved value, never options, never SQL text, never a bind, never a driver message (§2.2's
  never-stored list; the executions' bounded-diagnostics rule with redaction at the SOURCE — the
  schema has no column that could carry the sensitive forms).
- **Enumeration** — a set the lens hides answers ABSENT on every new route: the tree (not
  rendered), the workspace and its partials (the house 404, `:397`'s rule), the history partial,
  and the observed evaluate (identical to the ordinary evaluate's 404). No route confirms
  existence across workspaces; the history list is lens-filtered server-side, never by CSS.
- **Bounds** — the observed route inherits the engine's caps (the request body bound, the evaluate
  deadline, the bulkhead, the response budget) plus the house per-user SSE connection cap; the
  history reads are paged (25) and the record detail is bounded by the schema's own CHECKs
  (`outcomes_json ≤ 8 KiB`, ≤ 64 query rows).
- **Retention and purge** — §2.3: the executions' event retention, one sweep step; the set's
  entity purge cascades its history; a version purge never rewrites history.
- **MCP keys** — what a key can read is UNCHANGED: the six tools stay, none exposes the history or
  the stream (§7); an MCP key reaches no MVC route, so the new surfaces are session-only by the
  existing confinement.
- **Datasource/template detail links** — the inspector names a datasource and a template pin; any
  link out of the workspace lands on those artifacts' own routes, which enforce their OWN read
  boundaries and lenses (the issue's sentence) — the workspace never inlines another artifact's
  detail past what the reader may read.

## 14. Corrections this spec records (C-rows, for the build lanes to land)

| # | Record says | As the tree has it, and what replaces it |
|---|---|---|
| C1 | `docs/auth.md:895` (`parameter_set.read`'s row) and `docs/rest-api.md:2412` (§21's intro) both state there is **no UI page** ("no UI page (the record's §13)" / "No UI page exists (the record's §13) — this section and the MCP tools are the whole surface") | Both sentences predate this work and are now false by ratification of this spec. Replacement sentences, landed by S1's lane in the same commit as the routes: auth.md — "the Parameter Sets workspace (the [parameter-set workspace spec](superpowers/specs/2026-10-02-parameter-set-workspace-spec.md)) reads and evaluates through these rows: the tree and workspace pages and their partials on `parameter_set.read` (the promoter's lens), the observed evaluation on `parameter_set.evaluate`."; rest-api.md — "The first-party Parameter Sets workspace (ui-screens §4.22) reads and evaluates through this section; the observed evaluation stream is §21.x." |
| C2 | `docs/auth.md:859` says the catalog is "**98 on this base**" | The catalog is **99** on this base (the `:870` heading is right; `Permission.kt` carries 99 entries). Stale since the transfer's L1c row; not this spec's file to fix, but S1's lane touches auth.md for C1 anyway and corrects the number in the same commit. |
| C3 | The parameter engine record's §1 (`:80`) and §13 (`:952`) place "any UI page" out of scope, owned by "dashboards (#10) or its own issue" | This spec is that issue's resolution: the record's §13 row is superseded by this document for the workspace surfaces (read-only workspace + observed evaluation + history); the record's §13 out-of-scope LIST is otherwise untouched (authoring UI, consumer binding, options cache and the rest remain out). Landed as one sentence in the record's §13 row by S1's lane IF the record is edited at all — otherwise this C-row IS the amendment: the record is a design record, and its "or its own issue" clause resolves here without touching the file. |
| C4 | The brief's fact pass (2026-10-01 18:40 UTC, at 3c0d6098) recorded the engine record's header as draft 5.7 "all five lanes landed" and the vendor scripts as bare tags at `editor.html:13–:20` | On this base (`0b2c0922`) the record's change log reaches **draft 5.10** (2026-09-29, C36) while its header still reads 5.7 — quote the change log, not the header. And the vendor scripts moved behind `editor.html`'s `pe-runtime-scripts` template + `runtime.js` (the #358 stacked-bindings fix): a second graph page must load them the SAME way — this spec's §6.2 bakes that in. |
