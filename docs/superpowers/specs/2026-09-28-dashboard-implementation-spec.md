# Dashboards — implementation specification (round one)

**Status:** implementation specification for review; freezes the contracts the design record left
open. Written 2026-09-28 against `main` at `e4cd69e2`; every existing name, table, number and file
cited below was read from that tree, not recalled.
**Implements:** [the dashboard design record](2026-09-25-dashboard-authoring-design-draft.md) —
decisions D1–D63 and rulings R1–R28 are binding here and are not restated; this document says HOW.
Where this document chooses a number or a name the record did not, the choice is marked
**(confirmed 2026-09-28)** and collected in §17.
**Related issue:** [#10](https://github.com/msabiransari/datapipelines/issues/10); the audit
prerequisite is [#266](https://github.com/msabiransari/datapipelines/issues/266) (D62).
**Distribution:** contributor design material under `docs/superpowers/specs/`, outside the product
documentation packaging allowlist. The product documents this creates are listed in §15.

## 0. What ships in round one, and what does not

Ships: the `visualization` module with both artifacts and their full lifecycle; the REST and MCP
authoring surfaces; the server runtime and its four viewing operations; the pure-JavaScript
client runtime with the Plotly adapter, the KPI and table adapters; the first-party page with the
`Home` rename, the `Dashboards` sidebar tree and the events pane; visualization tests with agent
evidence and the server's mechanical check; promotion, import and export; the `dashboard` key
kind with bindings and a reference proxy. Prerequisite: #266 (D62).

Does not ship (each has its extension point named in the record): user layouts (D32), chart/table
actions, drill-through, saved selections, inspect/export (R25–R28), HTML/SVG visualizations (D13,
D46 — the renderer kinds are reserved, the content policy is a later spec), the headless-browser
rendered-state check and screenshot capture by the server (D56 (b) third clause — the test runs
and the interface exist, the browser check is a later lane), reports (D59), the derived-critical-
path timeout defaults (R8 — round one ships explicit timeouts with executor-tier defaults, §9.6).

## 1. Modules and dependency edges

| Module | Owns | Depends on (the allowed-dependency map, `build.gradle.kts:170` region) |
|---|---|---|
| `modules/visualization` (NEW, layer 5 — `parameters` is layer 4; module-structure §5.20, §5.19 being 266's `persistence`) | The two documents and their readers/validators, name grammars, repositories, lifecycle services (draft/release/discard/restore/purge/switch), transfer (export/import envelopes), the test-run and evidence repositories, the refresh record repository, the error codes | `typesystem`, `pipeline-contract` (ReadLens, name grammar, `TemplateRef`, `PipelineVersionStatus`), `templates` (the transform contract types and the template repository for pin checks), `parameters` (the parameter-set repository for the set pin) |
| `modules/application` | `DashboardRuntime` (the composition: admission, source fan-out through `ExecutionLauncher`, result retention, transformer evaluation through `TemplateEvaluateService`, status), `DashboardPromotion`, the promoter lens's dashboard/visualization fields | + `visualization` |
| `modules/web` | REST controllers, the SSE stream, the runtime routes, the first-party pages, the vendored client runtime and adapters, key bindings | + `visualization` |
| `modules/mcp-server` | The thirteen tools and the manual's `dashboards` area | + `visualization` |
| `modules/auth` | The permission rows, the `DASHBOARD_VIEWER` key role, the `dashboard` key kind's confinement | unchanged edges |

Guards the new module inherits (all in one commit with the module): a `COVERAGE_FLOORS` entry
(`buildSrc/src/main/kotlin/CommonConventionsPlugin.kt:441` region) — **90 (confirmed 2026-09-28)**
until the first measured baseline, then baseline − 2; the allowed-dependency rows above; rows in `docs/module-structure.md` (the module tree, the dependency matrix, and a §5.19 section after §5.18 `parameters`); the `ArchitectureGuardTest` inventory for every direct repository read a controller or tool makes.

## 2. Data model — migration `V42__visualizations_and_dashboards.sql`

The mould is `V39__parameter_sets.sql`, copied line for line with the names changed; every
constraint it carries (`chk_*_status`, the one-draft unique index, the release/discard stamp
checks, `chk_*_body` = `jsonb_typeof(body_json) = 'object' AND NOT (body_json ? 'name')`, the
`via` check) is kept. `FlywayMigrationIntegrationTest`: the applied-migration list rows (`42|visualizations and dashboards|true`, `43|dashboard executions and keys|true`) plus a dedicated DDL test per migration in V39's shape (`:413`: the constraint list, the one-draft index's indexdef, the columns) and the table/index/constraint list entries (`:916-918`, `:1001-1005`, `:1137-1143` today).

### 2.1 Artifacts (V42)

| Table | Columns beyond the mould |
|---|---|
| `visualizations` | as `parameter_sets` (`id` UUID PK, `workspace_id`, `name` unique per workspace, `display_name`, `description`, `current_version`, timestamps, `created_by`) |
| `visualization_versions` | as `parameter_set_versions`; `body_json` holds §3.1; `body_hash` the database projection |
| `dashboards` | as `parameter_sets` |
| `dashboard_versions` | as `parameter_set_versions`; `body_json` holds §3.2 |
| `visualization_test_runs` | `id` UUID PK, `visualization_id` FK, `version` INT, `body_hash` TEXT (the candidate content — the run is void when the version's hash no longer matches), `session_id` UUID, `started_by` UUID FK users, `started_at`, `completed_at`, `status` CHECK IN ('RUNNING','GREEN','RED','INCOMPLETE','EXPIRED'), `cases_json` JSONB (per case: name, verdict, notes ≤ 2,000 chars), `environment_json` JSONB (agent-reported: theme, viewport, browser, locale, renderer version), `mechanical_json` JSONB (the server's §11.3 outcome, written at run completion AND re-run at release), UNIQUE (visualization_id, version, session_id) |
| `visualization_test_screenshots` | `run_id` UUID PK FK runs ON DELETE CASCADE, `media_type` CHECK IN ('image/png','image/webp'), `bytes` BYTEA, `sha256` TEXT, `width` INT, `height` INT, `depicted_case` TEXT, `uploaded_by`, `uploaded_at`; `octet_length(bytes) <= 4194304` CHECK **(confirmed 2026-09-28)** |

Retention rule (D35), enforced by the service not the schema: for a DRAFT version, at most one
completed run keeps its screenshot (the latest — the previous run's row is deleted on completion);
for a RELEASED version, the run whose GREEN result qualified the release is kept for the
version's life; a run whose `body_hash` no longer matches its version's content is `EXPIRED` on
read and can never qualify.

### 2.2 Runtime and keys (V43)

| Table / change | Definition |
|---|---|
| `pipeline_executions.triggered_via` | the CHECK gains `'DASHBOARD'` (V38:196–198 is the current list); `ExecutionTrigger.DASHBOARD` added (`modules/dag/src/main/kotlin/co/datapipelines/executor/ExecutionRepository.kt:11`) |
| `dashboard_refreshes` | `id` UUID PK (the client-minted `refresh_id`, validated as UUID v4), `dashboard_id` FK, `dashboard_version` INT, `workspace_id` FK, `instance_id` UUID, `principal_user_id` UUID NULL, `principal_key_id` TEXT NULL (exactly one non-null — CHECK), `scope` CHECK IN ('ALL','TARGETS'), `targets_json` JSONB, `parameter_revision` INT, `selections_json` JSONB (≤ 64 KiB — CHECK on `pg_column_size`), `status` CHECK IN ('RUNNING','COMPLETED','PARTIAL','FAILED','ABORTED','TIMED_OUT'), `started_at`, `finished_at`, `summary_json` JSONB (per target: outcome, stage, reason, bytes) |
| `dashboard_refresh_executions` | `refresh_id` FK ON DELETE CASCADE, `source_name` TEXT, `execution_id` UUID FK `pipeline_executions`, `shared` BOOLEAN (the invocation served more than one target); PK (`refresh_id`, `source_name`) — the link D52 requires; the events pane joins here, never copies |
| `api_keys.kind` | `chk_api_keys_kind` gains `'dashboard'` (V37:110); `chk_api_keys_role` (V37:132) gains the disjunct `(kind = 'dashboard' AND role IS NOT NULL AND role = 'dashboard_viewer')`; `pipeline_executions.executed_by_key_kind`'s CHECK (V37:122) gains `'dashboard'` |
| `dashboard_key_bindings` | as `endpoint_key_bindings` (V11:69): `name_prefix` TEXT (a folder node: `finance/dashboards` binds every dashboard beneath; the root `/` allowed), `api_key_id` TEXT FK, `workspace_id` FK, `created_by`, `created_at`; PK (`name_prefix`, `api_key_id`); the deeper-binding-replaces-inherited rule of auth §7.7 applies verbatim |

Retention of `dashboard_refreshes`: the executions' own retention policy, applied by the same
sweep **(confirmed 2026-09-28)**.

## 3. The documents — frozen field names

Both documents are read by a strict reader (the `ParameterSetReader` shape: unknown key →
`body_invalid` naming the path; every literal typed; the name outside the body). Both are served
back exactly as stored plus the server-assigned `id`, `version`, `status`, `body_hash`.

### 3.1 Visualization body

```json
{
  "display_name": "Monthly revenue",
  "description": "",
  "renderer": { "kind": "plotly", "version": "4" },
  "inputs": {
    "revenue": { "columns": [ { "name": "month", "type": "DATE", "nullable": false },
                              { "name": "amount", "type": "DECIMAL", "nullable": false } ] }
  },
  "transform": { "template": { "name": "finance/transforms/revenue_bars", "version": 2 },
                 "inputs": { "rows": "revenue" } },
  "config": { "data": [ { "type": "bar", "x": "$.x", "y": "$.y" } ], "layout": { "title": { "text": "Revenue" } } },
  "bindings": { "data[0].x": "month_labels", "data[0].y": "amounts" },
  "presentation": { "title": "Monthly revenue", "tokens": { "series": "categorical" } },
  "tests": { "cases": [ { "name": "twelve months", "fixtures": { "revenue": [ { "month": "2026-01-01", "amount": 10.5 } ] },
                          "assertions": [ { "kind": "rendered" }, { "kind": "trace_count", "equals": 1 } ] } ] }
}
```

- `renderer.kind` ∈ `plotly | table | kpi | html | svg` (the last two reserved: a body naming
  them is refused `visualization.validation.renderer_unsupported` in round one); `version` is the
  major the host must provide, matched at bootstrap.
- `inputs`: named input contracts, each a column list in the transform contract's vocabulary
  (`ContractColumn`: name, `LogicalType`, nullable). Every `LogicalType` the type system has is
  allowed.
- `transform`: optional. When present, the pinned template must be a transform (a DRAFT pin is accepted at SAVE and released by the §11.4 cascade under `release_pinned_templates`, or refused at release — L1a resolved the §3.1/§11.4 contradiction this way) whose contract's input names match `transform.inputs`' keys and whose declared input columns equal the
  named visualization inputs' columns (checked at save: `visualization.validation.transform_binding_invalid`,
  naming the column). When absent, the renderer binds directly to one input.
- `config`: the renderer's native configuration, stored verbatim, validated at save against the
  renderer's schema (§11.3 — for Plotly the vendored `plot-schema.json`, trace types restricted to
  the vendored bundle's list, §10.4).
- `bindings`: a map from a JSON path into `config` to a column of the transform's OUTPUT (or of the
  single input when there is no transform). A path must resolve inside `config` and a column must
  exist in the output contract (`visualization.validation.binding_unbound`). At render the runtime
  substitutes each bound path with the column's values as an array. Only these paths change
  between the stored configuration and the rendered one; that is the whole data-injection contract.
- `tests.cases[]`: each case names fixtures for EVERY input (rows in the input's column
  vocabulary, the `TransformTestInput.rows` shape) and assertions from a closed vocabulary:
  `rendered` (the renderer reported completion), `trace_count {equals}`, `no_console_errors`,
  `text_visible {text}` (a title/legend/cell string), `no_data` (the case expects the empty state),
  `value_visible {text}` (a KPI value). Unknown kinds refuse the save. At least one case is required
  for release (`visualization.release.tests_missing`).

### 3.2 Dashboard body

```json
{
  "display_name": "Revenue overview",
  "description": "",
  "parameter_set": { "name": "finance/parameters/reporting_period", "version": 1 },
  "sources": [
    { "name": "revenue_source", "pipeline": { "name": "finance/pipelines/monthly_revenue", "version": 7 },
      "parameters": { "year": { "parameter": "year" }, "currency": { "value": "USD" } } }
  ],
  "visualizations": [
    { "name": "revenue_chart", "type": "visualization",
      "visualization": { "name": "finance/visualizations/monthly_revenue", "version": 3 },
      "inputs": { "revenue": { "source": "revenue_source" } }, "timeout_seconds": 120 }
  ],
  "groups": [ { "name": "overview_group", "type": "group", "members": [ "year", "revenue_chart", "refresh_button" ] } ],
  "actions": [ { "name": "refresh_overview", "type": "refresh", "scope": "targets", "targets": [ "revenue_chart" ], "initial": true } ],
  "action_controls": [ { "name": "refresh_button", "type": "action_control", "action": "refresh_overview", "label": "Apply" } ],
  "parameter_scopes": { "year": [ "overview_group" ] },
  "parameter_state": { "dashboard": { "visible": "inherit", "enabled": "inherit" },
                       "parameters": { "currency": { "visible": "force_false" } } },
  "outgoing_overrides": { "revenue_source": { "currency": { "value": "USD" } } },
  "layout": { "parameter_set": { "position": "left" }, "parameter_placements": { "year": { "group": "overview_group" } },
              "grid": [ { "name": "revenue_chart", "x": 0, "y": 0, "w": 6, "h": 4 } ], "columns": 12 },
  "timeouts": { "refresh_seconds": 300 }
}
```

- Object names (`name` of every visualization occurrence, group, action, action control) and the
  parameter set's parameter names share ONE namespace per dashboard: grammar `[a-z][a-z0-9_]{0,63}`,
  unique across types (`dashboard.validation.duplicate_name`). `type` is mandatory and must match
  the list it sits in.
- `sources[].parameters`: each pipeline parameter of the pinned release bound EITHER to a set
  parameter (`{parameter}`) or to a literal (`{value}`); every REQUIRED pipeline parameter must be
  bound (`dashboard.validation.parameter_unbound`); a bound set parameter must exist in the pinned
  set; a literal is coerced by the pipeline's own binder at refresh.
- `visualizations[].inputs`: every named input of the pinned visualization release mapped to a
  source (`dashboard.validation.input_unbound`); a source's output contract (the pipeline's caller
  output columns of its pinned release) must satisfy the input's columns by name and type
  (`dashboard.validation.input_contract_mismatch`, naming the column).
- `actions[]`: `scope` ∈ `all | targets` (D55); `targets` present and non-empty iff `scope ==
  targets`, every entry an occurrence name of type visualization (`dashboard.validation.target_not_visualization`,
  `dashboard.validation.empty_targets`); `initial` marks the action(s) R22 invokes at bootstrap.
- `parameter_scopes`: parameter → groups (D41); a parameter absent here is unscoped; a declared
  scope that omits a group consuming that parameter (directly or through a dependent) is refused
  (`dashboard.validation.scope_omits_consumer`). An action bound to a PARENT parameter's control
  is refused (`dashboard.validation.parent_action_binding`) — the binding is `action_controls[].parameter`, an optional key L1a added (D42's automatic action: the control fires its action when that parameter changes); parent = a parameter whose
  `dependents` is non-empty in the pinned set's evaluate response.
- `parameter_state`: `inherit | force_true | force_false` per dimension (D20, §4.4 of the record);
  `outgoing_overrides`: per source, per pipeline parameter, a literal (D23).
- `layout.grid`: 12-column units **(confirmed 2026-09-28)**; every visualization occurrence and action control appears exactly once, a group AT MOST once (the worked example places `overview_group` nowhere — L1a's ruling, confirmed at review); groups nest by listing members; responsive rule: below
  `layout.breakpoint_px` (default 768) every item spans the full width in grid order.
- `timeouts.refresh_seconds` and per-occurrence `timeout_seconds`: §9.6.
- Validation runs whole at save (`PUT`), again at release (§11.4) and at every `runtime/config`
  read against the pinned dependencies' CURRENT state (a source whose release was purged is
  `dashboard.runtime.dependency_missing` at config time, never a 500).

## 4. Names and identities

FQNs use the existing folder grammar (`PipelineNameGrammar`: 2–10 segments, ≤ 200 characters, no
leading slash, the 094 new-root confirmation on MCP creates). References are `{ "name", "version" }`;
the server resolves within the workspace and kind. UUIDs address REST and MCP by id (P24) — the
routes below are by id, and `name` is discovered through list/browse. Occurrence names are §3.2's
grammar. Refresh ids are client-minted UUID v4; instance ids likewise.

## 5. Permissions (D50, D60) — `modules/auth`

`Permission` gains eighteen rows (the nine `PARAMETER_SET_*` rows are the mould; `EXECUTE` takes `EVALUATE`'s place for dashboards, and visualizations have no evaluate): `VISUALIZATION_{READ,CREATE,UPDATE,VERSION_MANAGE,DELETE,IMPORT,RELEASE,SWITCH_VERSION}`,
`DASHBOARD_{READ,CREATE,UPDATE,VERSION_MANAGE,DELETE,IMPORT,RELEASE,SWITCH_VERSION}`, `DASHBOARD_EXECUTE`,
and, for the key family, `DASHBOARD_KEY_BIND` (asked on the binding routes, the `api_key.bind`
twin). Role cells copy the `parameter_set.*` rows of auth §7.6 exactly. Declaration is per handler (`@RequiredScope(Permission.X)`, `RequiredScope.kt:20-22`) and per tool (`McpToolCatalog.Entry(name, mutating, permission)`); the role sets live in `RolePermissions.kt` (`VIEWER` :107, `AUTHOR` :139, `PROMOTER` :190, `WORKSPACE_ADMIN` :218) — there is no `RestOperation` enum and no `MCP_TOOL_MIN_*` map (AGENTS.md's authorization paragraph predates #215). `dashboard.execute`:
viewer ✓, author ✓, promoter **lens**, ws_admin ✓, super_admin ✓, api_caller ✗, promotion_receiver ✗,
mcp:author ✓, mcp:promoter lens, mcp:ws_admin ✓, and the new column **dashboard_viewer ✓** —
the `dashboard` kind's one role (`KeyRole.DASHBOARD_VIEWER`), which holds exactly `dashboard.read` (lens = the bindings) and `dashboard.execute` — a transport role like `API_CALLER` (`isMemberKeyRole` false), never a member role. `ScopeMatrixSpecDriftTest.PERMISSION_COUNT` 82 → 100; the column set gains `dashboard_viewer`; `MatrixRowReachabilityTest`, `ReadFloorTest`,
`RoleWalkE2eTest` and `PermissionSeamE2eTest` gain their rows/pairs in the same commit as the handlers (AGENTS.md §4.9). **Landing split (orchestrator, 2026-09-29, forced by `MatrixRowReachabilityTest:43`, which fails any `Permission` no handler, tool or main-source check claims, and by its `:55`, which refuses a §7.6 route no handler maps):** L1b lands the fourteen lifecycle rows (`PERMISSION_COUNT` 82 → 96), L1c the two `IMPORT` rows with the import routes (→ 98), L2 `DASHBOARD_EXECUTE` (→ 99), L5 `DASHBOARD_KEY_BIND` (→ 100). The `dashboard_viewer` column and `KeyRole.DASHBOARD_VIEWER` are L5's: a 13th column changes both matrix parsers (`RoleMatrixDoc.kt`, `RoleMatrixDocE2e.kt`).

Delegated execution (D50): `DashboardRuntime` checks `dashboard.execute` on the principal for the
dashboard (lensed for the promoter: the dashboard is visible iff every pinned pipeline is admitted)
and then executes sources, evaluates the set and runs transforms WITHOUT consulting the
principal's `pipeline.execute`, `parameter_set.evaluate` or `template.evaluate`. Every execution it
starts records `executed_by` = the principal (user id, or the key's identity), `triggered_via =
DASHBOARD`, and the refresh id in `dashboard_refresh_executions` (§2.2). Audit rows name the
principal; the `dashboard.execute` check is the one authorization event.

Confinement of the `dashboard` kind: `ScopeInterceptor` admits it on `/api/v1/dashboards/{id}/runtime/**`
and `/api/v1/dashboards/{id}/refreshes/**` only; a `dashboard` key on any other route, and any
other kind on the runtime routes without `dashboard.execute`, is refused before the handler
(`ApiKeyKind.DASHBOARD`; auth §7.7 gains the row). `McpAuthFilter` refuses it on `/mcp`.

## 6. REST routes — `modules/web`

By id, the parameter-set mould (`ParameterSetsController`); every handler carries its
`@RequiredScope`; every body route parses under `RequestLimits` (298); the route table lands with
its auth §7.6 rows and the role-walk expectation in the same commit.

### 6.1 Visualizations (`visualization.*`)

| Route | Permission |
|---|---|
| `GET /api/v1/visualizations` (flat, `?prefix=` browse), `GET /{id}`, `GET /{id}/versions`, `GET /{id}/versions/{v}`, `GET /{id}/export` | `visualization.read` (lensed: a visualization is admitted iff a released dashboard the lens admits pins it, or the caller authored it — **(confirmed 2026-09-28)**) |
| `POST /api/v1/visualizations` (201), `PUT /{id}` (If-Match on `body_hash`) | create / update |
| `POST /{id}/release?release_pinned_templates=` (D61), `POST /{id}/current` | release / switch_version |
| `POST /{id}/draft/discard`, `POST /{id}/versions/{v}/discard`, `POST /{id}/versions/{v}/restore`, `DELETE /{id}/versions/{v}` | version.manage |
| `DELETE /{id}` (only when its only version is a DRAFT) | delete |
| `POST /api/v1/visualizations/import` (200, the set-import shape: templates first, the exported id kept, C29) | import |
| `POST /{id}/tests/sessions` → `{session_id, preview_url, expires_at}`; `POST /{id}/tests/sessions/{sid}/results`; `POST /{id}/tests/sessions/{sid}/screenshot` (raw `image/png` or `image/webp` body, ≤ 4 MiB **(confirmed 2026-09-28)**, its own cap independent of the platform's 2 MiB — a dedicated filter exemption listed in auth §8.6's entry inventory); `GET /{id}/tests/runs`, `GET /{id}/tests/runs/{run}`, `GET /{id}/tests/runs/{run}/screenshot` | update (sessions, results, screenshot); read (runs) |
| `POST /{id}/versions/{v}/check` — runs §11.3's mechanical test on demand, returns its outcome | read |

### 6.2 Dashboards (`dashboard.*`)

The same sixteen lifecycle routes under `/api/v1/dashboards`, plus:

| Route | Permission |
|---|---|
| `POST /{id}/validate` — §3.2's validation against current dependency state, no write | `dashboard.read` |
| `GET /{id}/runtime/config` | `dashboard.execute` |
| `POST /{id}/runtime/parameters` | `dashboard.execute` |
| `POST /{id}/runtime/visualizations` (SSE) | `dashboard.execute` |
| `POST /{id}/runtime/refreshes/{refresh_id}/abort` (202) | `dashboard.execute`, and the refresh's owner (principal + instance) or `execution.cancel_all` |
| `GET /{id}/refreshes?limit=&before=` (the pane's list), `GET /{id}/refreshes/{refresh_id}` (the record + its executions' ids and their event routes) | `dashboard.execute` for own refreshes; `execution.read_all` lifts own |
| `POST /api/v1/dashboards/bindings`, `DELETE /api/v1/dashboards/bindings` | `dashboard.key.bind` |

### 6.3 Pages (session-authenticated, `/partials` conventions)

`GET /dashboards` (the tree — also the sidebar's lazy source: `GET /partials/dashboards/tree?prefix=`),
`GET /dashboards/{id}` (the released view), `GET /dashboards/{id}/preview?version=` (a draft, for
`dashboard.read` holders with `dashboard.execute`), `GET /visualizations/{id}/preview?session=`
(the test preview, §11.2). The landing page's label becomes `Home` (D58): the nav entry at
`templates/layouts/default.html:254` (`data-nav-label="Dashboard"` → `Home`), its tests
(`ShellRenderTest`, the nav pins in `RoleVisibilityRenderTest`), ui-screens §3.4.

## 7. MCP tools — `modules/mcp-server`

**Landing note (orchestrator, 2026-09-29):** L1b ships eleven of the thirteen (`McpToolCatalog.NAMES` 48 → 59, pinned at eight places); `visualizations_test_start` and `visualizations_test_submit` land with L4's sessions. L4 needs a ruling on the screenshot: an MCP key reaches NO MVC route (`ScopeInterceptor.reachableBy`: `ApiKeyKind.MCP` → `/mcp` only), so "the screenshot goes through REST — the tool answers with the upload URL" cannot work for an agent as written.

Thirteen entries in `McpToolCatalog.ENTRIES` (48 → 61; every pinned count moves with its test:
`MatrixRowReachabilityTest.TOOL_COUNT`, `DatasourcesCreateRemovedTest`'s `NAMES.size shouldBe 48`, `RoleWalkE2eTest`/`PermissionSeamE2eTest` floors,
`WebsiteFactsGuardTest`'s heading, §6.1's count), `mcp-server.md` §6.2.50–62, the manual's
`dashboards` area (`skill/dashboards.md` + the `DocArea` mapping):

| Tool | Permission | Notes |
|---|---|---|
| `visualizations_list`, `visualizations_get` | `visualization.read` | the list carries `used_by` dashboards (D30) |
| `visualizations_create`, `visualizations_update` | create / update | the 094 new-root rule; `expected_hash` on update |
| `visualizations_purge_draft` | version.manage | |
| `visualizations_test_start` | update | starts a session; returns `preview_url`, the cases, `expires_at` |
| `visualizations_test_submit` | update | per-case verdicts + notes + the agent's environment; the screenshot goes through REST (§6.1) — the tool answers with the upload URL |
| `dashboards_list`, `dashboards_get` | `dashboard.read` | `get` carries the resolved dependency state and the last refresh summary |
| `dashboards_create`, `dashboards_update`, `dashboards_purge_draft` | create / update / version.manage | |
| `dashboards_validate` | `dashboard.read` | §3.2 validation, no write — the agent's loop before release |

No tool releases anything (the house rule); no tool executes a dashboard — the agent previews in
the app (`/dashboards/{id}/preview`).

## 8. The runtime protocol — frozen

### 8.1 `GET /runtime/config`

Response `data`: `{ configuration_id, dashboard: {id, name, version, status}, layout, parameter_set:
{name, version} | null, visualizations: [ {name, artifact: {id, name, version}, renderer: {kind, version},
config (with bindings unresolved), presentation, timeout_seconds} ], groups, actions, action_controls,
parameter_scopes, timeouts: {refresh_seconds, parameter_lock_seconds}, budgets: {max_bytes_per_source,
max_bytes_per_refresh} }`. `configuration_id` = `sha256(dashboard_id | version | body_hash | every pinned
dependency's version and body_hash)`; every later operation sends it and a mismatch answers
`dashboard.runtime.configuration_stale` (409) — the client reloads.

### 8.2 `POST /runtime/parameters`

Request `{ configuration_id, instance_id, selections: {name: value}, intent: "bootstrap" | "parent_change" | "retry" }`.
The runtime evaluates the pinned set through `ParameterEvaluator.evaluate` with the submitted
selections (every parameter, hidden/disabled included — D23), applies `parameter_state` overrides,
and answers `EvaluateResponseJson` unchanged plus `overrides_applied: {name: {visible, enabled}}`,
`parents: [names whose dependents are non-empty]`, `parameter_revision: n` (server-assigned,
monotonic per instance). Bounded by the engine's own limits; a refusal is the engine's code
inside the §4 envelope.

### 8.3 `POST /runtime/visualizations` — the SSE stream

Request `{ configuration_id, instance_id, refresh_id (UUID v4, fresh), parameter_revision, selections,
scope: "all" | "targets", targets: [] }`. The server: validates identity and scope (D55), records
the `dashboard_refreshes` row (status RUNNING), resolves the target closure (every source the
targets need; identical invocations after outgoing overrides shared — §5.2 of the record), checks
admission (§9.4), and streams. Framing is the execution stream's: `event:` + `id:` (monotonic per
refresh) + `data:` JSON, a `heartbeat` every 15 s, the request-body cap and the disconnect grace of
rest-api §6.8 (the server cancels the refresh's executions after the grace).

| Event | `data` |
|---|---|
| `refresh_started` | `{refresh_id, targets: [names], sources: [{name, shared}], deadline_at}` |
| `source_started` / `source_completed` / `source_failed` | `{refresh_id, source, execution_id, rows?, bytes?, error?: {code, message}}` — the pane links `execution_id` to the execution's own events route |
| `visualization_status` | `{refresh_id, name, type: "visualization", state: in-progress|error|abort|no-data, stage: source|transform|budget|timeout|abort, reason?: {code, message}}` |
| `visualization_data` | `{refresh_id, name, type: "visualization", bindings: {path: [values]}, rows: n, bytes: n}` — the resolved bindings only (§3.1), never the whole configuration again |
| `refresh_completed` | `{refresh_id, status: COMPLETED|PARTIAL|FAILED|ABORTED|TIMED_OUT, targets: {name: {outcome, stage?, reason?}}}` — always the last frame; a stream that ends without it is a transport failure the client treats as §5.3 of the record |

The client applies the record's freshness rule per occurrence (latest refresh id + generation) to
every event; `ready`/`success`/`stale` are client-side states (the server never sends them).

### 8.4 `POST /runtime/refreshes/{refresh_id}/abort`

Body `{ instance_id }`. 202 when the refresh is RUNNING and owned by the principal + instance;
404 `dashboard.refresh.not_found` otherwise (also for a refresh that already finished — idempotent
from the client's view). Server cancellation: every execution the refresh owns and no other RUNNING
refresh shares is cancelled through the executor's cancel path; shared ones are released, not
cancelled. Multi-instance: the refresh row carries the executing node id; an abort arriving
elsewhere is forwarded through the existing cancel channel (the executor's `cancelPollIntervalSeconds`
poll already covers a row-level cancel flag — the abort SETS the flag; nothing new).

## 9. The server runtime — `application.DashboardRuntime`

1. **Resolve** the pinned dashboard version and every dependency's pinned version (config time).
2. **Eligibility** (D38): every source's pinned pipeline release passes `ReadOnlyPipelineRule`
   (transitively); checked at save, release AND at each config read (`dashboard.validation.source_not_read_only`).
3. **Admission** (D53, §9.4).
4. **Fan-out**: one `ExecuteRequest` per distinct invocation with `triggeredVia = DASHBOARD`,
   `userId` = the principal, `parameters` = the resolved inputs after outgoing overrides,
   `directSink` = a bounded collector (rows copied into a typed in-memory table under §9.5's caps;
   the sink refuses past the cap and the execution is cancelled with `dashboard.refresh.result_too_large`),
   `resultTtlSeconds` = the minimum (the Redis materialisation stays for history but is not read),
   `parentExecutionId` = null, `correlationId` = the refresh id. Started concurrently through a `SourceStarter` seam the runtime declares in `application` and `web` implements with `ExecutionStreamLauncher.startFresh`'s composition (an `ExecutionContext` carrying `triggeredVia = DASHBOARD` and the principal's `executedByKeyKind`, a `WebEventEmitter` with `stream = null`, `newExecutor`, `runExecution`) — the emitter and the persistence path are reused, never copied; `ExecutionLauncher.decide` is NOT consulted (a refresh's executions are never idempotency-aliased across refreshes). Each is recorded in `dashboard_refresh_executions` before its first event.
5. **Dependency scheduling**: a transformer runs once when all its named inputs' sources completed;
   a failed source fails every dependent target (D37) and never substitutes an empty input.
6. **Transform**: `TemplateEvaluateService.evaluate(workspaceId, template.name, template.version,
   TransformTestInput(rows = the input's rows, inputs = the other named inputs), now)` — the same
   bounded pool and TypeGate as authoring; the output rows bound to the configuration's paths.
7. **Stream** per §8.3; **finish**: the refresh row's status and summary written under
   `withContext(NonCancellable)` (the coroutine lesson: terminal bookkeeping runs even on cancellation);
   in-memory results released when the last consumer finished or the refresh ended.
8. **Audit**: one `dashboard.refresh` audit event per refresh (D27's fire-and-forget path from
   #266) with the refresh id, principal, dashboard, scope, outcome; the executions carry their own.

### 9.4 Admission (D53)

Config keys (`datapipelines.dashboards.admission.*`, all **(confirmed 2026-09-28)**):
`max-concurrent-refreshes-per-workspace = 4`, `max-executions-per-refresh = 16`,
`max-concurrent-dashboard-executions-per-instance = 40` (a share of `maxConcurrentExecutionsPerInstance`).
Dashboard executions are exempt from `maxConcurrentExecutionsPerUser`. A refresh reserves its N
slots atomically or is refused `dashboard.refresh.saturated` (429, `Retry-After`) — never queued
past `admission.max-wait-seconds = 10`.

### 9.5 Result caps (D54)

`datapipelines.dashboards.results.max-bytes-per-source = 4 MiB`, `max-bytes-per-refresh = 32 MiB`
**(confirmed 2026-09-28)**; measured on the typed in-memory table (the executor's `ResultConfig`
byte accounting). Over the source cap → that source fails (`dashboard.refresh.result_too_large`,
its dependents error, others continue); over the refresh cap → the refresh ends PARTIAL with the
remaining targets errored.

### 9.6 Timeouts (R8, round one)

Explicit `timeouts.refresh_seconds` (default `datapipelines.dashboards.timeouts.default-refresh-seconds
= 600`, cap `max-refresh-seconds = 900` — the executor's `executionTimeoutSeconds`/`nodeTimeoutMaxSeconds`
tiers) and per-occurrence `timeout_seconds` (default = the refresh's); the earliest deadline wins;
a source's own executor limits still apply. The parameter lock (record §5.6):
`parameter-lock-seconds = 30`. The client's render allowance: `render-seconds = 20`. The derived
critical-path default is a later change to the SAME fields.

## 10. The client runtime and adapters — `modules/web` static assets

### 10.1 The artifact

`static/js/datapipelines-dashboard.js` (ES2019, no dependencies, one IIFE exporting
`window.DatapipelinesDashboard`), versioned in the vendor manifest with its sha256 like the other
assets, served under the app's CSP (no eval, no inline). Its node tests live in
`modules/web/src/test/js/dashboard-*.test.mjs`; its browser conformance fixtures in
`tests/browser-tests` (§10.5).

### 10.2 The API

```js
const instance = DatapipelinesDashboard.init({
  server: { baseUrl, credentials: "session" | { proxyBaseUrl } },
  dashboard: { id, version: "released" | number },
  container: HTMLElement,
  adapter: { /* §10.3 */ },
  options: { renderTimeoutMs, onNotification }
});
instance.ready            // Promise<void> — resolves after bootstrap step 4 (R22)
instance.refresh({ scope, targets })   // authorized programmatic action (gated like a button)
instance.abort(refreshId)
instance.reset()          // §5.8 of the record
instance.resize()
instance.dispose()
```

Bootstrap is the record's four-step barrier; `init` on an owned container throws
`DashboardAlreadyMounted`.

### 10.3 The adapter contract (D22, R21)

Required functions (initialisation rejects a missing one; a no-op is not conformant — §10.5):
`mountLayout(layout) → Promise`, `mountVisualization(occurrence, renderer) → Promise`,
`renderParameters(state) → Promise`, `readSelections() → {name: value}`,
`onEdit/onCommit/onAction(callback)` registration, `renderData(occurrence, refreshId, bindings) →
Promise<'rendered' | 'no-data'>`, `renderStatus(occurrence, {state, stale, reason})`,
`notify(notification) → void` (with `recover(intent)` reporting), `resize()`, `dispose()`.
Every callback carries `{instanceId, name, type, refreshId}`. The Plotly adapter
(`datapipelines-dashboard-plotly.js`) implements `renderData` as `Plotly.react` with the bound
arrays substituted into the stored configuration, resolves `rendered` on `plotly_afterplot`, and
reads the resolved theme tokens from CSS custom properties into `layout` at each render (D25);
`table` and `kpi` adapters ship beside it.

### 10.4 The Plotly bundles (D63, revised 2026-09-28)

Plotly's 3D traces are WebGL — there is no lighter 3D in Plotly — so the size question is
answered by never shipping WebGL to a dashboard that has no 3D visualization. Two self-contained
custom bundles from `plotly.js@4.1.1`, built with the package's own `CUSTOM_BUNDLE.md` procedure
(`git clone --branch v4.1.1`, `npm i`, `npm run custom-bundle -- --traces … --strict --out …`),
both vendored under `static/vendor/plotly/` with the trace list, the build command and each
file's sha256 in the vendor manifest, pinned by a `VendoredPlotlyAuditTest` (the Alpine test's
shape):

| Bundle | Traces | Size bound (measured on the closest published partial, 2026-09-28) |
|---|---|---|
| `plotly-2d.min.js` — the default | `scatter`, `bar`, `pie`, `histogram`, `box`, `heatmap` **(confirmed 2026-09-28)** | between `basic` (1.19 MB minified, 395 KB gzip) and `cartesian` (1.50 MB, 496 KB gzip) |
| `plotly-3d.min.js` — only for a dashboard with a 3D visualization | the six above + `scatter3d`, `surface`, `mesh3d` | about the `gl3d` partial: 1.75 MB minified, 556 KB gzip |

The server chooses at config time: `runtime/config` carries `renderer.bundle: "2d" | "3d"`
derived from the pinned visualizations' trace types (validated at save, §11.3), and the page
loads exactly one bundle; the two are never on one page. The configuration validator refuses any
trace type outside the loaded bundle's list. No partial bundle contains a `new Worker` site
(measured: 0 in `basic`, `cartesian` and `gl3d`; the full bundle's two are outside them), so the
expected CSP need is `style-src` for Plotly's injected sheet (hashed like Cytoscape's) only —
measured by the browser suite's collector on the lane instance before either bundle is accepted;
a `blob:` or `worker-src` need would be a finding to design around, not to allow. Dropping 3D
from round one is one row of this table and the `3d` value; adding a trace type later is a bundle
rebuild and a manifest change, gated by the audit test.

### 10.5 Conformance and the first-party page

`tests/browser-tests`: a `DashboardConformanceBrowserTest` over a plain-JavaScript host and the
first-party page, exercising the record's acceptance scenarios 1, 3, 4, 10–14, 17, 18 (bootstrap
order, freshness across overlapping refreshes, the parameter lock and its deadline, notifications,
abort, connection loss, disposal, two instances of one dashboard). The first-party page
(`/dashboards/{id}`) mounts the same runtime with the session credential, renders the events pane
from `GET /refreshes` (linking executions), and the sidebar's `Dashboards` item expands into the
lazy tree (`/partials/dashboards/tree`). Light/dark screens of both are handback evidence.

## 11. Visualization tests and the release gate (D34, D35, D56)

### 11.1 Cases live in the body

`tests.cases[]` (§3.1) are versioned with the visualization; editing them changes `body_hash`,
which voids every run (§2.1). A release needs ≥ 1 case.

### 11.2 The session (D56 (a))

`visualizations_test_start` / `POST /{id}/tests/sessions` creates a run row (RUNNING) and a
**preview token**: a 32-byte random capability, TTL `tests.session-ttl-minutes = 60`
**(confirmed 2026-09-28)**, stored hashed on the run, granting `GET /visualizations/{id}/preview?session=`
for THAT visualization version only — the page serves the runtime with the fixture inputs in
place of pipeline results, no session cookie, no other route; the token is the agent's browser's
only credential and is revoked on submit or expiry. The agent runs its Playwright checks against
the page, submits per-case verdicts (`visualizations_test_submit`), and uploads one screenshot
(§6.1). Submit completes the run: `GREEN` iff every case's verdict is green AND the server's
mechanical test (§11.3) passed at that moment; `RED` otherwise; `INCOMPLETE` when a case has no
verdict. Notes and environment are bounded; nothing else in the request is stored.

### 11.3 The mechanical visualization test (D56 (b))

Server-run, no browser, sub-second, at submit and again at release:

1. **Schema**: `config` validated against the renderer's schema — for Plotly the vendored
   `dist/plot-schema.json` (3.9 MB, reduced at build time to the supported trace types' attribute
   trees; the reducer is a Gradle task whose output is committed and hash-pinned); unknown
   attributes, wrong types and unsupported trace types are refused naming the path.
2. **Binding**: every `bindings` path resolves in `config`; every bound column exists in the
   transform's output contract (or the input's) with a type the renderer accepts for that path
   (numeric for `y`, any for `x`, and so on — the small per-path type table is part of the
   validator and pinned by test).
3. **Fixture run**: for every case, the pinned transformer evaluates the fixtures through
   `TemplateEvaluateService` (the type gate included); the output must bind (every bound column
   present in every row's projection); an evaluation refusal or a missing column fails the case
   with the code and path.
4. **Assertion feasibility**: `trace_count` compared with `config.data.length`; `no_data` cases
   must produce zero output rows; `text_visible`/`value_visible` strings must appear in the
   configuration or the bound values (a static check; the visual check is the agent's).

When a headless browser exists (a later lane), step 5 renders each case and asserts `rendered`,
the trace count and `no_console_errors` in the browser; the interface for step 5 exists from day
one (`RenderedStateCheck`, a no-op implementation that records `not_available`).

### 11.4 Release (D56, D61)

`POST /{id}/release` for a visualization: in ONE transaction — the version is a DRAFT; its
`body_hash` equals the latest run's `body_hash`; that run is GREEN; §11.3 re-run now passes;
pinned transform templates are RELEASED (or released with `release_pinned_templates=true`, the 142
cascade); then the version becomes RELEASED and the run is retained for it. Refusals:
`visualization.release.tests_missing | tests_stale | tests_red | mechanical_failed |
dependency_not_released`, each naming the case or path. `POST /{id}/release` for a dashboard: §3.2 validation against current state; every pinned visualization RELEASED (or released through the cascade under `release_pinned_visualizations` — the visualization's OWN gate runs, and its DRAFT template pins are NOT consented by the dashboard's flag: they need that visualization's `release_pinned_templates` first — L1a's conservative D61 reading, confirmed); every pinned pipeline release RELEASED and read-only; the
set release RELEASED; refusals `dashboard.release.*` naming the dependency.

## 12. Promotion, import, export (D61)

Export envelopes follow rest-api §21.4's shape (`manifest`, the artifact node, `templates` at the
root for visualizations; for dashboards `visualizations` at the root — their envelopes — and the
pinned set's and pipelines' references, NOT their bodies: a dashboard export assumes its
pipelines and set are promoted first). Import binds by the 194e helper's convention (the
lifecycle keys stripped by name, then the strict reader), keeps the exported id (C29 `id_taken`
never re-issues), verifies the hash, lands templates → visualizations → the artifact in the
stated order. Promotion (`PromotionWire` — there is no plan class; the page's plan is computed from the inventory): `Inventory` gains `visualizations` and `dashboards` (`Entry` lists beside `parameterSets`, `:29-48`), `Batch` the matching `JsonNode` lists (`:84-113`), `Applied` the two counts (`:142-154`); the sender's `PromotionService.promote` gains their roots the way the four-argument overload carries set roots (and #313 decides which surface passes them); the receiver applies templates → parameter sets → pipelines → visualizations → dashboards (D61) inside its one transaction. A template-backed set's receive stays blocked by #302 until that lane lands, so a dashboard pinned to such a set cannot be promoted before #302 — the runbook says so. **(stale — #302 landed 2026-09-29 as c3617544/0fad8d75: validate outside the transaction with the overlay engine, land inside; L1c validates and lands visualizations and dashboards INSIDE the transaction after templates, sets, pipelines and endpoints, with the production ports — neither validator opens a customer datasource, and the overlay renderer answers a null contract for a batch-brought template.)** **Import verb roles — OWNER'S RULING PENDING (recommended at L1a's review: workspace admin + super admin only, the `api_key.bind` cells; the wire, not the verb, lands promoted artifacts; the manifest carries the evidence summary; a non-int `version` or non-array bundle is refused). The transfer binds every import/receive payload through the family's READER so the §2.3/§3.2 bounds hold on import and receive as on save.**

## 13. Audit and diagnostics (D27, D28, D52)

#266's ordered per-execution queue carries the refresh audit event and the executions' events
unchanged. The events pane reads `dashboard_refreshes` + `dashboard_refresh_executions` and each
execution's existing durable events; nothing dashboard-specific is duplicated. Logs: refresh id,
dashboard id, principal id, source names, execution ids, byte counts, close reasons — never a
selection value, a row or a body (the 286/298 rule).

## 14. Error codes — pipeline-contract §13.22 and §13.23

`PipelineErrorCodes.Visualization` and `.Dashboard` objects (the `Request` object's shape), the
catalogue rows, `SECTION_13_ROW_COUNT` 307 → 371 (L1a: +64) in the same commit, `AuthErrors.SECTION_ANCHORS`
`"visualization." → "1322-visualizations"`, `"dashboard." → "1323-dashboards"`.

§13.22 `visualization.*` (30 rows on main — L1a: the 21 below plus the lifecycle refusals the §6.1 routes need, `name_invalid`, `name_taken` 409 for the artifact-name clash (`duplicate_name` belongs to the dashboard's object namespace), the `version.*` preconditions, `authoring.disabled`, `version.pinned` for the pin guard): `not_found` 404; `validation.body_invalid`, `renderer_unsupported`,
`input_contract_invalid`, `transform_binding_invalid`, `config_schema_invalid`, `binding_unbound`,
`test_case_invalid`, `new_root_requires_confirmation` 400; `version.conflict` 409;
`release.tests_missing`, `tests_stale`, `tests_red`, `mechanical_failed`, `dependency_not_released` 409;
`import.id_taken` 409, `import.missing_template` 400; `test.session_not_found` 404,
`test.session_expired` 410, `test.screenshot_too_large` 413, `test.screenshot_invalid` 400.

§13.23 `dashboard.*` (34 rows on main — the 24 below plus the same lifecycle family): `not_found` 404; `validation.body_invalid`, `duplicate_name`,
`unknown_object`, `target_not_visualization`, `empty_targets`, `parent_action_binding`,
`scope_omits_consumer`, `source_not_released`, `source_not_read_only`, `parameter_unbound`,
`input_unbound`, `input_contract_mismatch`, `layout_invalid`, `new_root_requires_confirmation` 400;
`version.conflict` 409; `release.dependency_not_released` 409; `import.id_taken` 409;
`runtime.configuration_stale` 409; `runtime.dependency_missing` 409; `refresh.saturated` 429;
`refresh.result_too_large` 422 (inside the stream: a `visualization_status` reason);
`refresh.not_found` 404; `key.kind_refused` 403.

## 15. Documents and guards this creates

`docs/dashboards.md` (the product spec: the two documents, the runtime protocol, the adapter
contract, the tests and gate, the key kind — registered in `DocsCatalog.GROUPING` (a packaged doc without a group fails the context at boot) and in docs-audit's check C alternation at `scripts/docs-audit.sh:216–217` (`visualization|dashboard`)), rest-api §22 (visualizations) and §23 (dashboards), auth
§7.6 rows and §7.7's `dashboard` row, mcp-server §6.2.50–62 and §6.1's count, pipeline-contract
§13.22–23, versioning §3.5's lifecycle table (the two families), module-structure rows, ui-screens §4.21
(the page, the tree, the pane, `Home`), metadata-db (V42, V43), configuration §3.32 (the keys),
deployment (the Plotly bundle's provenance, the screenshot cap's filter exemption), the skill area
`skill/dashboards.md` with its goldens and `SkillHasNoDemoContentTest`. Guards: every existing
drift test moved with its row; `VendoredPlotlyAuditTest`; the browser CSP collector; the
conformance suite; `RenderedStateCheck`'s no-op pinned by a test that expects `not_available`.

## 16. Lane cut

| Lane | Scope (fence) | Base / order | Acceptance (falsified both ways) |
|---|---|---|---|
| **L0 #266** | audit + event batching with the ordered per-execution queue; `dashboard.refresh` event kind reserved | first | the record's §5.9 acceptance list |
| **L1 visualization module** (split 2026-09-29: L1a the module — LANDED fd18fc76; L1b the lifecycle surfaces + eleven tools + fourteen permissions + docs + walk; L1c export/import routes + `TemplateBundle` + the promotion wire + the two `IMPORT` rows, on L1b's merge) | `modules/visualization` (documents, readers, grammars, repositories, lifecycle, transfer), V42, §14 codes, §5 permission rows (all eighteen + the key role, no routes yet for execute), REST §6.1 + §6.2's lifecycle routes, the thirteen tools, promotion/import/export (§12), docs | on L0 | save/refuse cases per §3 (every code reachable once), release cascade, C29, promotion order on three deployments (the 194e shape), koverVerify on every touched module |
| **L2 server runtime** | `DashboardRuntime`, V43 (`DASHBOARD` trigger, refresh tables), the four runtime routes + refreshes routes, admission, caps, timeouts, abort, the events-pane data | on L1 | scenarios 2–4, 7, 15, 18 of the record; a fan-out over three sources with one shared; result-cap and saturation refusals; abort across two instances; PARTIAL outcomes; the `NonCancellable` finish; `ReadOnlyPipelineRule` refusal at config time |
| **L3 client runtime + adapters + first-party page** | the JS artifact, Plotly/table/kpi adapters, the two bundles (§10.4) + CSP measurement, `/dashboards` pages, `Home` rename, the sidebar tree, the events pane, conformance suite | on L2 | §10.5's scenarios; zero CSP violations; two instances; light/dark screens |
| **L4 tests + gate** | sessions, preview token page, evidence tables, screenshot route + cap, the mechanical test with the reduced plot-schema, release atomicity, `RenderedStateCheck` interface | on L1 (parallel with L2) | red/green on each refusal; a stale run cannot release; a screenshot over the cap refused; the token's confinement (no other route, expiry) proven on the wire |
| **L5 `dashboard` key kind** | `ApiKeyKind.DASHBOARD`, `KeyRole.DASHBOARD_VIEWER`, bindings table + routes, `ScopeInterceptor`/`McpAuthFilter` confinement, the reference proxy example with its streaming conformance test | on L2 | the key reaches only the runtime routes; unbound = unservable; deeper binding replaces; two users of one key with independent refreshes; the proxy streams (not buffers) |
| **L6 (later)** | headless render check + screenshot capture by the server (D56 step 5); the derived timeout defaults; HTML/SVG renderers | after L4 | — |

Every lane brief carries the store template's Roles and Security sections, runs each touched
module's `koverVerify` in its pregate, and names its dispatch base. L2 and L4 share L1's base and
disjoint fences (runtime vs tests); L3 and L5 both build on L2.

## 17. Numbers and choices — confirmed by the owner on 2026-09-28 ("yes to all ten"); the L1a bounds confirmed by the orchestrator at review on 2026-09-29

Every `(confirmed 2026-09-28)` marker in the body refers to a row of this table. These values are the defaults the briefs carry; changing one later is a spec amendment, not a lane's call.

| Item | Value | Where |
|---|---|---|
| `visualization` module coverage floor | 90 until measured | §1 |
| Screenshot cap | 4 MiB, PNG/WebP | §2.1, §6.1 |
| Refresh-record retention | the executions' policy | §2.2 |
| Layout grid | 12 columns, breakpoint 768 px | §3.2 |
| `visualization.read` lens rule for the promoter | admitted iff pinned by an admitted released dashboard, or own | §6.1 |
| Admission | 4 refreshes per workspace, 16 executions per refresh, 40 dashboard executions per instance, 10 s max wait | §9.4 |
| Result caps | 4 MiB per source, 32 MiB per refresh | §9.5 |
| Timeouts | refresh 600 s default / 900 s cap, lock 30 s, render 20 s | §9.6 |
| Plotly bundles | 2D default (six traces); the 3D bundle (nine) only for dashboards with a 3D visualization | §10.4 |
| Session TTL | 60 minutes | §11.2 |
| L1a document bounds (`datapipelines.visualization.*`) | max-visualizations-per-dashboard 50, max-cases-per-visualization 20, max-fixture-rows-per-case 1,000, max-config-bytes 262,144, max-bindings-per-visualization 64, max-inputs-per-visualization 8, max-columns-per-input 256 (the last two added by L1a's security pass: the fixture check costs rows × columns) | L1a brief §B.3, `VisualizationConfig` |

## 18. Corrections recorded at the L2 brief (2026-09-29, read-only inventory on ccba12bf)

The runtime sections (§2.2, §8, §9) were written before L1a/L1b landed and against premises the tree does not hold. The L2 brief (`prompts/L2-dashboard-runtime.md` in the store) carries these resolutions; where §8/§9 and this section disagree, this section wins.

1. **Audit.** No fire-and-forget audit path exists (`AuditLogger` has only the awaited `log()`; `BatchingWriter.submit` has no production caller and `application` may not depend on `persistence`). One `dashboard.refresh` audit row per refresh through `AuditEventSink.log`, awaited, at the refresh's end under `NonCancellable`; enums §15 gains the row. No event name was ever "reserved".
2. **Admission.** `ExecutionSlots` has no instance-only or multi-slot acquire and rejects rather than queues. L2 adds `acquireInstanceOnly(n, maxWait)` — N instance slots atomically, no per-user slot, a bounded wait of `admission.max-wait-seconds`; the workspace and dashboard-execution caps are JVM-local (the record's 050/R2 shape).
3. **Order.** Admission BEFORE the refresh row; a 429 refusal writes no row and sets `Retry-After` in the handler.
4. **Launch seam.** `ExecutionStreamLauncher.startFresh` is private and REST-bound; the seam is `RecordingExecutionRunner.run(request, ws, DASHBOARD, failClosed = true) { recorded -> link }` (the scheduler's mould), the `dashboard_refresh_executions` row written in `onRecorded` — before the first event by construction. `ExecutionLauncher.decide` stays skipped, so the `ParameterBinder` pre-bind runs explicitly per source.
5. **Results.** A `directSink` run never writes the `ResultStore`: a dashboard execution has no stored result and no `result_row_count`; `summary_json` carries the per-source byte and row counts; the byte accounting is lifted from `RedisResultStore` into a public helper.
6. **Abort.** A refresh-level Redis flag (`dp:refresh-abort:{refresh_id}`) polled at every stage boundary plus the per-execution flags; the row carries the client `instance_id` only (no "executing node id"). No cross-refresh sharing exists, so "shared executions are released" is void; `execution.cancel_all` overrides ownership.
7. **Retention.** `pipeline_executions` is never deleted, so "the executions' policy" was no policy: refresh rows follow the EVENT retention (a step on the hourly tick, the event-retention cutoff); the `dashboard_id` FK is `ON DELETE CASCADE`.
8. **A stale-refresh sweep** (`DashboardRefreshSweeper`, the stale-execution sweeper's mould) marks RUNNING rows an instance crash left as TIMED_OUT.
9. **V43 is L2's only:** the `chk_triggered_via` CHECK gains `DASHBOARD`, `dashboard_refreshes`, `dashboard_refresh_executions`; the pin reads `43|dashboard refreshes|true`. L5's key rows (`api_keys` kind/role, `executed_by_key_kind = 'dashboard'`, `dashboard_key_bindings`) are V44.
10. **`configuration_id`** needs hashes the ports do not carry: read `PipelineRepository.findVersionDetail.bodyHash` and `ArtifactVersionDetail.bodyHash`, or grow the ports (L2 chooses and says why).
11. **Save-vs-run:** the reader bounds `sources[]` at 400 while `max-executions-per-refresh` is 16 — one new validation code, `dashboard.validation.too_many_invocations` (§13.23 +1), refuses at save more distinct invocations than the cap.
12. **No released dashboard can exist before L4** (`ReleaseEvidence.NOT_INSTALLED`): L2's E2Es seed RELEASED rows by SQL; the runtime serves the current RELEASED version only; the draft preview is L4's (§6.3).
Also settled: the refresh stream has its own registry and counts against the per-user SSE cap; a promoter holds `dashboard.execute` (lensed) but no `execution.read`, so execution links are shown only to principals the executions' visibility admits; a DASHBOARD-triggered execution is cancelled only through its refresh's abort; a reused or malformed `refresh_id` is `body_invalid`; the first refresh judges SQL result nodes' columns (#328) as `source_failed` naming the column.
