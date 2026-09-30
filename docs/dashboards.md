# Dashboards

**Status:** v0.4 — the two documents and their lifecycle (#10, lane L1a); the REST routes, the MCP tools and the
permissions (§4, lane L1b); the server runtime (§5, lane L2). The transfer routes (L1c), the client runtime and the
first-party page (L3), the visualization tests and their release gate (L4) and the `dashboard` key kind (L5) add
their sections as they land.
**Owner:** datapipelines.co core
**Depends on:** [Versioning](versioning.md) (§3.5 — the lifecycle table), [Pipeline Contract](pipeline-contract.md)
(§13.22, §13.23 — the codes), [Metadata DB](metadata-db.md) (§4.28–§4.35 — the tables), [Enumerations](enums.md)
(§31–§38), [Configuration](configuration.md) (§3.33, §3.34 — the bounds and the runtime's numbers), [REST API](rest-api.md) (§22, §23 — the routes),
[MCP Server](mcp-server.md) (§6.2.50–§6.2.60 — the tools), [Auth](auth.md) (§7.6 — the permissions)
**Design:** the [dashboard implementation spec](superpowers/specs/2026-09-28-dashboard-implementation-spec.md) and
the [design record](superpowers/specs/2026-09-25-dashboard-authoring-design-draft.md) (decisions D1–D63)
**Last updated:** 2026-09-29

A dashboard presents released pipeline results. It is built from two versioned artifacts: **visualizations** —
a chart, table or KPI bound to named inputs, reusable across dashboards — and **dashboards**, which pin released
pipelines as sources, map their results onto visualization inputs, and arrange visualizations, groups, actions and
parameter controls on a 12-column layout. Both are authored through MCP and follow the draft/release lifecycle
pipelines, templates and parameter sets already have.

---

## 1. The two artifacts

| | Visualization | Dashboard |
|---|---|---|
| What it owns | Its renderer and native configuration, named input contracts, an optional transform pin, the bindings from output columns into the configuration, presentation defaults, saved test cases (D33, D34) | Its pinned pipeline sources and parameter set, the occurrences of pinned visualizations and where their inputs come from, groups, actions, controls, parameter scopes and state, the layout (D30, D33, D40) |
| What it pins | One transform template version (optional) | Pipeline versions, one parameter-set version, visualization versions |
| Identity | A folder-path name (2–10 segments, the pipelines' and templates' grammar), a UUID the surfaces address it by, an ordered list of versions | The same |
| Tables | `visualizations`, `visualization_versions` ([Metadata DB §4.28/§4.29](metadata-db.md)) | `dashboards`, `dashboard_versions` ([§4.30/§4.31](metadata-db.md)) |
| Codes | `visualization.*` ([Pipeline Contract §13.22](pipeline-contract.md)) | `dashboard.*` ([§13.23](pipeline-contract.md)) |

A visualization has no fixed pipeline: it is **input-bound** (D33). A dashboard supplies its inputs, so the same
visualization release can appear in several dashboards — and more than once in one, under different occurrence
names. References between artifacts are `{ "name": "<folder path>", "version": <n> }`, resolved within the workspace
and the kind the containing field names; a name is never renamed and never reused (D36).

Both documents are read by a **strict reader**: an explicit key table at every level, so an unknown key, a wrong JSON
type or a missing key is refused `*.validation.body_invalid` naming the path, every problem reported at once. A JSON
`null` at a schema level is absent. A refusal names a key or a path, never a value from the document. The `name` is
outside the body: the stored body never carries it.

---

## 2. The documents

### 2.1 Visualization

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

(The document as submitted also carries its `name`, e.g. `"finance/visualizations/monthly_revenue"`. The
implementation spec's copy writes the fixture's `amount` as the string `"10.5"`; a `DECIMAL` is a JSON number on the
wire, so it is `10.5` here.)

| Field | Rule |
|---|---|
| `display_name` | Required, 1–120 characters, not blank. `description` at most 2000. Both are content: they ride the release, and the index row shows the current version's |
| `renderer.kind` | `plotly`, `table` or `kpi` ([Enumerations §31](enums.md)). `html` and `svg` are reserved and refused in round one (`visualization.validation.renderer_unsupported`) |
| `renderer.version` | The MAJOR version the host must provide (`"4"`), matched at bootstrap — a positive integer of at most four digits |
| `inputs` | At least one named input (`[a-z][a-z0-9_]{0,63}`), each a non-empty list of columns in the transform contract's vocabulary: a `name` (1–128 characters, no control character, unique in the input), a `type` — any logical type ([Type System](type-system.md)) — and `nullable` (default `false`). A bad contract is `visualization.validation.input_contract_invalid` |
| `transform` | Optional. `template` pins a transform template version; `inputs` maps each of the contract's input names to one of the visualization's inputs. The pin must exist (a DRAFT is accepted here; the release requires it RELEASED — §3.2), be a transform, name exactly the contract's inputs, and each declared input's columns must EQUAL the mapped input's by name, type and nullability; every visualization input must be read. Without a transform the visualization has exactly one input and the renderer binds to it directly. Refusals are `visualization.validation.transform_binding_invalid`, naming the column |
| `config` | The renderer's native configuration, stored VERBATIM (D4) and judged against the renderer's schema (§2.1.2) — `visualization.validation.config_schema_invalid`, naming the path inside it |
| `bindings` | A map from a path inside `config` to a column of the transform's OUTPUT contract — or, without a transform, the input's columns. The path must follow §2.1.1's grammar and exist in the stored `config`; the column must exist (`visualization.validation.binding_unbound`). At render, the runtime replaces each bound path with that column's values as an array; nothing else in the configuration changes |
| `presentation` | Optional: a `title` (≤ 120 characters) and `tokens` — theme-token names for the renderer's semantic slots (`series: categorical`), never CSS (D11, D25) |
| `tests.cases[]` | The saved static-data tests (D34), versioned with the visualization — §2.1.3 |

#### 2.1.1 Binding paths

A key, then any run of `.key` and `[index]` steps: `data[0].x`, `layout.title.text`, `columns[2].values`. A key is
`[A-Za-z_][A-Za-z0-9_]*`, an index `0`–`9999` without leading zeros, 256 characters at most. There is no wildcard, no
filter and no root marker: a path names exactly one place, and that place must exist in the stored configuration —
the author puts a placeholder there (`"x": "$.x"` above) — so the runtime's substitution can never create structure.

#### 2.1.2 Renderer schemas

- **`plotly`** — an object whose `data` is a non-empty array (at most 64) of trace objects, each with a `type` from
  the two vendored bundles: `scatter`, `bar`, `pie`, `histogram`, `box`, `heatmap` (the 2D bundle, the default) and
  `scatter3d`, `surface`, `mesh3d` (the 3D bundle, loaded only for a dashboard that uses one — D63); `layout` and
  Plotly's own `config` are objects. Nothing else at the top level. Deeper Plotly attributes are judged by the
  vendored plot-schema when the tests lane adds it.
- **`table`** — `columns`: a non-empty array (at most 64) of `{label, values, format?, align?}` — `label` a
  non-blank string of at most 120 characters, `values` the placeholder a binding fills, `format` one of `text`,
  `number`, `integer`, `percent`, `date`, `datetime`, `align` one of `left`, `center`, `right`; `page_size` an
  integer 1–1000.
- **`kpi`** — `label` (required, as a table label), `value` (the placeholder a binding fills), `format` one of
  `number`, `integer`, `percent`, `currency`, `unit` (at most 16 characters), `comparison` `{label, value}`.

#### 2.1.3 Test cases

Each case has a unique non-blank `name`, `fixtures` with rows for EVERY input — each row an object of the input's
columns, every value a scalar, no null in a non-nullable column — and at least one assertion from a closed
vocabulary ([Enumerations §32](enums.md)): `rendered`, `trace_count` (`equals`), `no_console_errors`,
`text_visible` (`text`), `no_data`, `value_visible` (`text`). An unrunnable case is
`visualization.validation.test_case_invalid`. Fixture VALUES are judged by the transform engine's type gate when
the case runs; a `DECIMAL` is a JSON number on the wire (type-system §3), so write `10.5`, not `"10.5"`.

### 2.2 Dashboard

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

(The document as submitted also carries its `name`, e.g. `"finance/dashboards/revenue_overview"`.)

| Field | Rule |
|---|---|
| names | The occurrences', groups', actions' and action controls' names and the pinned set's parameter names share ONE namespace (D15): an object name is `[a-z][a-z0-9_]{0,63}`, and no two share a name across kinds (`dashboard.validation.duplicate_name`). Sources are unique among themselves. Every object carries a `type` that matches its list ([Enumerations §34](enums.md)) |
| `parameter_set` | Optional: at most one pinned set version (D40); a DRAFT is accepted here, the release requires it RELEASED |
| `sources[]` | A pinned pipeline version that must be RELEASED (`dashboard.validation.source_not_released`) and pass the read-only rule, child pipelines included (`dashboard.validation.source_not_read_only`, D38). `parameters` binds each pipeline parameter EITHER to a set parameter (`{"parameter": …}`) OR to a literal (`{"value": …}` — a scalar or a list of scalars); every REQUIRED pipeline parameter is bound, here or by an outgoing override (`dashboard.validation.parameter_unbound`) |
| `visualizations[]` | An occurrence of a pinned visualization version (a DRAFT is accepted here); `inputs` maps EVERY input of that visualization to a source (`dashboard.validation.input_unbound`), whose caller output columns must supply each input column by name and type (`dashboard.validation.input_contract_mismatch`, naming the column). `timeout_seconds` is 1–900 |
| `groups[]` | Containers for composition and action scope, never a name or evaluation scope (D47). A member is an occurrence, a group, an action control or a set parameter; nothing belongs to two groups and no group contains itself |
| `actions[]` | A refresh action: `scope` `all` (no targets) or `targets` with a non-empty list of occurrence names (D55, D57); `initial` marks the actions bootstrap invokes (R22). `dashboard.validation.empty_targets`, `dashboard.validation.target_not_visualization` |
| `action_controls[]` | A control bound to an `action`. Without `parameter` it is a button — an explicit action; with `parameter` it binds the committed change of that parameter's control — an automatic action (D42), refused on a PARENT parameter, one with dependents in the pinned set (`dashboard.validation.parent_action_binding`, R2) |
| `parameter_scopes` | Parameter → the groups its change makes stale (D41). A declared scope must include every group that consumes the parameter — directly, or through a parameter that depends on it (`dashboard.validation.scope_omits_consumer`); an absent parameter is unscoped |
| `parameter_state` | Hide/show and enable/disable overrides, dashboard-wide and per parameter: `inherit`, `force_true`, `force_false` ([Enumerations §36](enums.md), D20). A forced-hidden parameter's value is still submitted (D23) |
| `outgoing_overrides` | Per source, per pipeline parameter, the literal the pipeline receives whatever the control showed (D23) |
| `layout` | The whole set's region (`left`, `right`, `top`, `bottom`), per-parameter placements into a group or a region, and the `grid` in 12-column units: every occurrence has exactly one grid item; an action control is placed exactly once — a grid item or a group membership; a group at most once; each item fits the 12 columns. Below `breakpoint_px` (768 when absent) every item spans the full width in grid order (`dashboard.validation.layout_invalid`) |
| `timeouts.refresh_seconds` | 1–900 — the executor tier's cap (the spec's §9.6) |

A reference that names nothing — a member, a target, a control's action, a placement, a scope's group, an
override's source or pipeline parameter — is `dashboard.validation.unknown_object`; a pinned visualization,
pipeline or set version this workspace does not hold is `dashboard.validation.dependency_not_found`. The rules run
whole at save, again at release, and against the pinned dependencies' current state whenever the runtime serves
the dashboard.

### 2.3 Bounds

The readers check each collection's bound BEFORE they read its members (`*.validation.body_invalid` with
`details.reason` `too_many`, or `too_large`, and `details.config_key`): visualizations per dashboard, test cases per
visualization, fixture rows per case, `config` bytes, bindings and inputs per visualization, and columns per
input —
[Configuration §3.33](configuration.md). A renderer configuration nests at most 32 levels and a literal at most 2,
checked by a bounded walk.

---

## 3. The lifecycle

Both families follow [Versioning §3.5](versioning.md#35-the-lifecycle-table) exactly: an authoring create lands
version 1 as a DRAFT with no current version; the first change after a release opens the next version as a DRAFT
(copy-on-write; an identical write changes nothing); every write, release, discard and purge carries the
`body_hash` the caller based it on and is refused `*.version.conflict` when it is stale; a release flips the DRAFT
and moves the pointer; a RELEASED version is discarded, never purged, and can be restored; the pointer can be
switched to any live version this deployment's posture admits. The hash is computed by the database over the
stored JSON, so key order and whitespace never change it. A promotion receiver refuses every authoring write
(`*.authoring.disabled`) and still imports and switches.

### 3.1 Releasing a visualization

In this order, in ONE transaction for the last three steps: the DRAFT declares at least one test case
(`visualization.release.tests_missing`); the §2.1 rules pass against the pin as it is now; the transform pin is
RELEASED — or a DRAFT released with the visualization when the caller consents (`release_pinned_templates`, the
cascade pipelines use for templates), otherwise `visualization.release.dependency_not_released` naming the pin; the
release evidence passes — the agent's GREEN run for this exact content and the server's mechanical check (D56;
`visualization.release.tests_stale`, `visualization.release.tests_red`, `visualization.release.mechanical_failed`).
The evidence gate is installed by the tests lane (L4); until then every visualization release is refused
`visualization.release.tests_missing` with `details.reason = gate_not_installed`.

A version that a live (DRAFT or RELEASED) dashboard version pins is never discarded or purged, and a visualization
with any pinned version is never purged whole — `visualization.version.pinned`, `details.pinned_by` naming the
dashboards. This guard lives in the visualization module and counts LIVE dashboard versions only, which is complete:
an entity purge is legal only while the visualization's only version is a DRAFT, and a DISCARDED dashboard version can
never have pinned such a visualization (a RELEASED dashboard pins only a RELEASED visualization, and a RELEASED version
never returns to DRAFT). #320 (Versioning §3.5.3) guards the OTHER direction — the things a dashboard or a visualization
pins: a pipeline release a dashboard source pins is refused `pipeline.version.pinned` (`referencing_dashboards`), a
parameter set a dashboard pins `parameter.in_use`, a transform template a visualization pins `template.in_use`
(`referencing_visualizations`). **Restoring a DISCARDED dashboard version re-judges its dependencies against today's state**
(a DISCARDED version protects nothing, so its pins may have been discarded meanwhile) and is refused
`dashboard.validation.dependency_not_found` naming the dead pin; only the dependency failures block a restore.

### 3.2 Releasing a dashboard

The §2.2 rules pass against the dependencies' current state; the pinned set version is RELEASED; every pinned
visualization version is RELEASED — or, when the caller consents (`release_pinned_visualizations`, D61), a DRAFT pin
is released through the visualization's own release (§3.1, its gate included) in the dashboard's transaction, the
dashboard's flip last, so a stale hash rolls every cascaded release back. Anything not released is
`dashboard.release.dependency_not_released`, `details.dependencies_not_released` listing each. The consent does not
reach a visualization's own draft transform pin, which needs that visualization's consent.

### 3.3 Export and import

An export is the CURRENT release's envelope: `{"visualization": …, "templates": […], "manifest": {…}}` — the transform
pin's templates travel with it — and `{"dashboard": …, "visualizations": [each pinned visualization's envelope],
"manifest": {…}}`, whose manifest names the pinned pipelines and set by reference: a dashboard assumes they were
promoted first (D61's order: templates, parameter sets, pipelines, visualizations, dashboards). An import lands the
templates, then the visualizations, then the dashboard, each at its exported version with its exported id; the
envelope's shape is judged before anything lands, the lifecycle fields beside a body are ignored by name and any
other unknown key refuses; the same version with the same hash is a no-op; a pin the target lacks is
`visualization.import.missing_template` or `dashboard.import.missing_dependency`; an id another artifact on the
server holds is `*.import.id_taken` — never re-issued (C29).

---

## 4. The surfaces

Both families are authored over REST ([REST API §22, §23](rest-api.md)) and MCP ([MCP Server §6.2.50–§6.2.60](mcp-server.md)),
addressed by id (P24); a multi-segment name never travels in a path. There is no UI page yet (L3). Every body a surface
takes is read by §1's strict reader with §2.3's bounds, so REST and MCP refuse the same documents with the same codes.

### 4.1 Permissions

Fourteen rows of the permission catalog ([Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)), the
parameter sets' shape:

| Permission | viewer | author | promoter | workspace admin | super admin | What it governs |
|---|---|---|---|---|---|---|
| `visualization.read`, `dashboard.read` | ✓ | ✓ | lens | ✓ | ✓ | The reads: the flat listing, the `?prefix=` browse, the working version, the versions |
| `visualization.create`, `dashboard.create` | ✗ | ✓ | ✗ | ✓ | ✓ | Create (version 1 DRAFT) |
| `visualization.update`, `dashboard.update` | ✗ | ✓ | ✗ | ✓ | ✓ | The draft write — and, for dashboards, VALIDATE (§4.2) |
| `visualization.version.manage`, `dashboard.version.manage` | ✗ | ✓ | ✗ | ✓ | ✓ | Purge a draft, discard / restore / purge a version |
| `visualization.delete`, `dashboard.delete` | ✗ | ✓ | ✗ | ✓ | ✓ | The entity purge (only a draft-only artifact) |
| `visualization.release`, `dashboard.release` | ✗ | ✓ | ✗ | ✓ | ✓ | Release (§3.1, §3.2) |
| `visualization.switch_version`, `dashboard.switch_version` | ✗ | ✓ | ✗ | ✓ | ✓ | Switch the served version |

The two transport key roles (`api_caller`, `promotion_receiver`) hold none of them. **The promoter's lens:** a
RELEASED dashboard newer than the promotion target's whose EVERY source pipeline her pipeline lens admits — so her
dashboards never outrun her pipelines — and the visualizations those dashboards pin; anything else answers as an absent
id. The two import rows land with the transfer routes (L1c), the execute row with the runtime (L2), and the key-binding
row and the `dashboard_viewer` key column with the key kind (L5).

### 4.2 Validate is an author verb

`POST /api/v1/dashboards/{id}/validate` and `dashboards_validate` run §2.2's rules on the WORKING version against the
dependencies as they are now, write nothing, and answer the verdict (`valid` and every failure) with a `200`. The
validator reads the pinned pipelines', set's and visualizations' statuses without a lens, so a refusal can name the
state of a pin the caller could not otherwise see; the verb therefore sits on `dashboard.update`, not
`dashboard.read` (owner ruling 2026-09-29): a viewer and a promoter never reach it.

### 4.3 What a source must declare

A dashboard source's release is judged at save by its status, its read-only verdict (the published-endpoint rule,
through child pipelines) and its parameters. Its OUTPUT columns are judged against the visualization input it feeds
(`dashboard.validation.input_contract_mismatch`) only when the release DECLARES them — when its caller node is a
transform whose contract names a table output. A release with no caller node declares an empty output (every mapped
input is refused); a SQL caller node declares nothing, and the check is left to the runtime, which judges the real
columns (L2) — a guess would refuse or admit on nothing.

### 4.4 The tools

| Tool | Permission | What |
|---|---|---|
| `visualizations_list`, `visualizations_get` | `visualization.read` | Browse a level (each row with `used_by`: the dashboards that pin it); read the working version |
| `visualizations_create`, `visualizations_update` | create / update | Write the document (the arguments ARE its keys); the new-root confirmation on create; `expected_hash` on update |
| `visualizations_purge_draft` | `visualization.version.manage` | Purge a draft at its hash — never one a live dashboard pins |
| `dashboards_list`, `dashboards_get` | `dashboard.read` | Browse; read the working version with each pin's status, each source's release and read-only verdict (lensed), and `last_refresh` (the CALLER's own latest refresh — id, version, status, stamps — or null; through an MCP key, which is its own identity, it is null today: no tool refreshes a dashboard, §5) |
| `dashboards_create`, `dashboards_update`, `dashboards_purge_draft` | create / update / version.manage | As the visualization tools |
| `dashboards_validate` | `dashboard.update` | §4.2 |

No tool releases or executes anything; the visualization test tools (`visualizations_test_start`,
`visualizations_test_submit`) land with the test sessions (L4). The served manual's `dashboards` guide is the
authoring loop in the order an agent needs it.

---

## 5. The runtime

A person opens a RELEASED dashboard and it comes alive: the server runs the dashboard's pinned source pipelines, hands
the results to its visualizations, and streams them. This section is the SERVER half (lane L2); the browser half —
the client runtime, the renderer adapters and the first-party page — is L3's. Six routes, all under
`/api/v1/dashboards/{id}`, all `dashboard.execute` ([Auth §7.6](auth.md)).

### 5.1 The delegated act (D50)

`dashboard.execute` is the ONE authorization event. A viewer who holds it refreshes a dashboard whose source pipelines,
parameter set and transform templates they could not run or evaluate themselves: the sources run WITHOUT consulting the
caller's `pipeline.execute`, the set is evaluated without `parameter_set.evaluate`, the transform without
`template.evaluate`. Two things keep that safe:

- **Sources are read-only.** Every source pins a RELEASED pipeline that passes the read-only rule (D38),
  transitively through its child pipelines. It is checked at save, at release, at every configuration read AND at
  every refresh; a pin that stops holding is `dashboard.runtime.dependency_missing` (409), never a run.
- **Isolation.** Every read is workspace-scoped; the dashboard is read through the caller's lens (a promoter refreshes
  a dashboard only if every source pipeline her lens admits — otherwise the family's 404); a refresh belongs to the
  person who started it.

Each source execution is `executed_by` the refreshing person with `triggered_via = DASHBOARD` ([Enums §18](enums.md)); it
is visible like any of their runs, but it is cancelled ONLY through its refresh's abort — the executions route refuses
it for everyone — and it writes NO stored result (§5.5).

### 5.2 The routes

| Route | Answers |
|---|---|
| `GET /{id}/runtime/config` | The runtime configuration and its `configuration_id`. |
| `POST /{id}/runtime/parameters` | The pinned set evaluated against the submitted selections. |
| `POST /{id}/runtime/visualizations` | One refresh, as a server-sent stream. |
| `POST /{id}/runtime/refreshes/{refresh_id}/abort` | 202; aborts a RUNNING refresh the caller owns. |
| `GET /{id}/refreshes`, `GET /{id}/refreshes/{refresh_id}` | The caller's own refreshes; every refresh with `execution.read_all`. |

The dashboard served is its CURRENT RELEASED version, never a draft (the draft preview is L4's). A dashboard with no
release is absent, exactly like a hidden one.

### 5.3 The configuration

`configuration_id` is `sha256(dashboard id | version | body_hash | every pinned dependency's kind, name, version and
body_hash)` — the pinned visualizations, the parameter set and each source pipeline. Every later call sends it; a mismatch
is `dashboard.runtime.configuration_stale` (409) and the client reloads. The answer carries the layout, each
occurrence's renderer, configuration and `bindings` (a configuration path to a column, filled later from
`visualization_data`), the groups, actions, controls, scopes and parameter-state overrides, the resolved refresh
deadline, and the budgets the client is held to.

### 5.4 Parameters

`POST /runtime/parameters` answers the parameter engine's evaluate response UNCHANGED plus `overrides_applied` (only the
parameters whose hide/show or enable/disable the dashboard's `parameter_state` changed, with both effective values),
`parents` (parameters that have dependents) and `parameter_revision`, assigned by the server per client instance and
increasing — a hint the client compares, held in memory, that starts again after a restart. A dashboard with no set
answers an empty evaluation.

### 5.5 A refresh

The order matters. A request is judged whole first (a malformed body is `dashboard.validation.body_invalid` naming the
FIELD, never its value); then the stream cap, the served dashboard and `configuration_id`; then the selections are
evaluated — an invalid selection is a 400 before anything is held; then the plan; then **admission**; and only then the
`RUNNING` row. A refused refresh writes nothing.

- **Sharing.** Two sources whose pinned release AND resolved parameters — after the outgoing overrides — are identical are
  ONE execution, fed to every consumer. Sharing is within one refresh; nothing is ever aliased across refreshes.
- **Admission (D53).** A refresh reserves one instance slot per distinct execution, all or none, plus one of the
  workspace's refresh places and its share of the instance's dashboard-execution cap — waiting at most
  `max-wait-seconds`. It takes no per-user slot, so a refresh never starves the viewer's own runs. A full instance is
  `429 dashboard.refresh.saturated` with `Retry-After`. The counters are JVM-local ([Configuration §3.34](configuration.md)).
  A dashboard needing more distinct executions than one refresh may run is refused at SAVE
  (`dashboard.validation.too_many_invocations`), so no valid document is permanently saturated.
- **Caps (D54).** A source's rows are held in a bounded collector counted with the result store's own byte accounting;
  at the first row over `max-bytes-per-source` that source fails `dashboard.refresh.result_too_large` and its dependents
  error; over `max-bytes-per-refresh` the refresh ends PARTIAL. A dashboard run writes NO result store entry and no
  `result_row_count`; `GET /api/v1/executions/{id}/result` answers `not_found` for it.
- **Dependencies.** A target waits for ALL its inputs; a failed source fails every target reading it, and a transform
  never runs on a stand-in for a missing input. The first refresh judges the input contract against the source's real
  columns (a SQL result node's columns are unknown until it runs, #328): a mismatch fails the target naming the column.
- **Deadlines (§9.6).** The refresh's own (`timeouts.refresh_seconds`, default 600, cap 900), each occurrence's
  `timeout_seconds` and each source's own executor limits — the earliest that applies wins.

The stream is the execution stream's framing (`event:`, monotonic `id:`, `data:`, a `: heartbeat` every 15 s, the
disconnect grace of rest-api §6.8): `refresh_started`, `source_started` / `source_completed` / `source_failed`,
`visualization_status`, `visualization_data`, and `refresh_completed` ALWAYS last. A source is LINKED to the refresh
before it is announced, so a pane can open its execution the moment `source_started` arrives. `source_failed` names a
code, never a driver's message; the execution's own events (visible to its owner) carry the detail. A subscriber is
re-judged before every write (a revoked session, a removed member or a lost `dashboard.execute` cuts the STREAM at that
write); the refresh itself runs to its end.

### 5.6 Abort

`POST …/refreshes/{refresh_id}/abort` with `{ instance_id }` answers 202 without waiting. It requires `dashboard.execute`
plus OWN (the refresh's principal AND client instance) or `execution.cancel_all`; anything else — no such refresh,
another dashboard's, another person's, one already finished — is `dashboard.refresh.not_found`. It sets a refresh-level
flag the OWNING instance polls (`dp:refresh-abort:{refresh_id}`), pulls the local trigger when this IS the owner, and
cancels each execution the refresh has started through the executor's own path. The refresh ends `ABORTED`, every
not-yet-started source is skipped and `refresh_completed` is last. A client that disconnects and stays away past the grace
aborts its refresh the same way.

### 5.7 The record

`dashboard_refreshes` and `dashboard_refresh_executions` ([Metadata DB §4.34–§4.35](metadata-db.md)) hold every refresh that
was admitted. The row is closed however the refresh ends — the client gone, the deadline passed, the process stopping —
because the terminal work runs non-cancellably at the one point that owns it; a refresh an instance crash left
`RUNNING` is closed `TIMED_OUT` by the sweeper ([§8.4](metadata-db.md)). Finished refreshes are retained like execution
events. One `dashboard.refresh` audit row per refresh is written awaited at its end ([Enums §15](enums.md)).

The events pane links a refresh's executions only for a person who may read executions: a promoter refreshes a
dashboard she can read but holds no `execution.read`, so her refresh names no execution.

### 5.8 What is not here

The draft preview and the visualization tests (L4), the first-party PAGES — `/dashboards`, the sidebar tree, the events pane (L3b) — and the `dashboard` key kind and its confinement (L5): until L5, only signed-in sessions reach these routes and no MCP tool refreshes a dashboard.

## 6. The client runtime (L3a)

The browser half is one vendored script, `static/js/datapipelines-dashboard.js` — plain ES2019, one IIFE,
no dependencies, no build step — publishing `window.DatapipelinesDashboard`, versioned in the vendor
manifest with its sha256 like every vendored asset (`VendoredPlotlyAuditTest` pins the bytes; a hand
edit must move the manifest in the same commit). It owns the protocol below and NOTHING else: it never
renders a chart (the renderers do), never evaluates a parameter (the server does), and never lets a
stale frame touch the DOM.

### 6.1 The API

```js
const instance = DatapipelinesDashboard.init({
  server: { baseUrl, credentials: "session" | { proxyBaseUrl } },
  dashboard: { id, version: "released" },
  container: HTMLElement,
  adapter: DatapipelinesDashboard.adapters(container),  // or the host's own §6.2 object
  options: { renderTimeoutMs, onNotification },
});
instance.ready   // Promise — resolves after the bootstrap barrier, rejects with the failing step
instance.refresh({ scope, targets })   // the authorized programmatic action, gated like a button
instance.abort(refreshId)
instance.reset()      // restores the last APPLIED parameter state; never executes
instance.resize(); instance.dispose();
instance.recover("retry" | "reload"); instance.on("edit" | "commit" | "action", listener);
```

`version` is `"released"` only — the server serves the current release and nothing else (§5.2); a
numeric version is refused with a clear error until the preview lane (L4) defines it. Re-initialising
a container that already mounts an instance throws `DashboardAlreadyMounted`; after `dispose()` the
container is unmarked and a fresh `init` is legitimate.

Bootstrap is the four-step barrier, each step awaited: validate the adapter and the renderer/bundle
pair → fetch the configuration → mount the layout → fetch and render the parameter state (an explicit
no-op without a set) → mount the occurrences → invoke the `initial` actions. Any failure rejects
`ready`, publishes a notification naming the step, and disposes the instance — nothing half-mounted
ever executes.

### 6.2 The adapter contract

The adapter is the host's DOM. Twelve functions, all required — `init` refuses an adapter missing any
of them, and a no-op is not conformant (the conformance suite drives real behaviour):

| Function | The host does | The runtime guarantees |
|---|---|---|
| `mountLayout(layout) → Promise` | build the grid from the system layout | awaited before anything mounts |
| `mountVisualization(occurrence, renderer) → Promise<handle>` | create the placeholder and its renderer | the handle's `renderData` is the ONLY data path |
| `renderParameters(state) → Promise` | render the FULL server state, hidden and disabled included | awaited inside the parameter gate |
| `readSelections() → {name: value}` | return every current committed value | merged over the server state at each evaluation |
| `onEdit/onCommit/onAction(callback)` | register the interaction callbacks | every callback carries `{instanceId, name, type, refreshId}` |
| `renderData(occurrence, refreshId, rows, bindings) → Promise<'rendered'\|'no-data'>` | render native data | deadline-bounded; a late acknowledgment is discarded |
| `renderStatus(occurrence, {state, stale, reason})` | show the state accessibly, colour never alone | a throwing hook is isolated and reported |
| `notify(notification)` | show the structured notification | deduplicated per outcome; never a raw server message |
| `resize()`, `dispose()` | the lifecycle | disposal invalidates every pending callback |

The first-party composite (`DatapipelinesDashboard.adapters(container)`) implements all twelve over a
CSS grid, text-only parameter controls and the three shipped renderers, which register themselves at
load: `plotly` major `4` (`datapipelines-dashboard-plotly.js`), `table` major `1` and `kpi` major `1`
(the table and kpi versions are the seeded fixtures' wire value). A host registers its own kind the
same way (`DatapipelinesDashboard.registerRenderer({kind, version, create})`) before `init`; the
renderer `kind` and `version` on each occurrence's `renderer` are matched against the registrations at
bootstrap, before any execution.

### 6.3 The renderers

- **Plotly.** `renderData` substitutes the resolved bindings into a CLONE of the stored configuration
  (the stored bytes are never mutated and no structure is ever invented — a path that resolves nowhere
  is skipped whole; bindings were validated to resolve at save) and calls `Plotly.react`. Bound
  STRINGS are escaped (`&`, `<`, `>`) before Plotly sees them: Plotly renders a subset of HTML in
  text, hover and titles, and a data cell is DATA, never markup — an `<img onerror>` in a column
  renders as text. The theme reaches the chart at EACH render (D25): the adapter resolves the app's
  `--chart-*` tokens (app.css, bridged off the theme) through a probe element and maps them onto
  `paper_bgcolor`, `plot_bgcolor`, the grid, the font and the categorical `colorway`.
  `presentation.tokens` is an OPEN map: `series: "categorical"` is the one name the adapter knows;
  names it does not know are ignored.
- **Table.** `columns[]` (`label`, `values` — the path the binding fills, `format`, `align`),
  `page_size` capping the rows. Every cell is `textContent`.
- **KPI.** `label`, `value` (the bound path), `format` (`number|integer|percent|currency`), `unit`,
  and an optional `comparison` bound through the same map. A zero renders — a zero is a value, not
  `no-data`.

### 6.4 The two bundles

Plotly is vendored as two self-contained custom bundles (D63): `plotly-2d.min.js` (scatter, bar, pie,
histogram, box, heatmap — the default) and `plotly-3d.min.js` (those plus scatter3d, surface, mesh3d —
WebGL). The SERVER chooses: `runtime/config` carries `renderer.bundle: "2d" | "3d"` derived from the
pinned visualizations' trace types, and the page loads exactly one — the two are never on one page.
The host DECLARES what it loaded on the script tag: `<script src="…/plotly-2d.min.js"
data-dp-plotly-bundle="2d">`. The runtime judges the pair at bootstrap: two bundle declarations on one
page, a renderer kind nothing registered, a version mismatch, or a 3D board on a page that loaded the
2D bundle all fail `ready` before anything executes.

Because the two bundles must never travel between pages, a host that navigates with htmx boosting
opts its dashboard links OUT (`hx-boost="false"`, the layout's precedent for routes that must not
boost); the first-party pages apply that (L3b), and the runtime's two-bundle refusal is the backstop.

### 6.5 Credentials: session and proxy

- **`"session"`** — the signed-in app: every request carries the session cookie (`credentials:
  "same-origin"`) and every POST carries the `DP-CSRF-Token` double-submit header read from the
  `dp_csrf` cookie, exactly like the app's own scripts.
- **`{ proxyBaseUrl }`** — an external application's backend holds a `dashboard` key (L5) and proxies
  the SAME FOUR runtime paths under its base. The runtime sends the same request bodies and Accept
  headers to `{proxyBaseUrl}/api/v1/dashboards/{id}/runtime/…` with `credentials: "omit"` and NO CSRF
  header — the key never reaches the browser, and the browser offers it nothing. THE PROXY WIRE
  CONTRACT (what L5's reference proxy implements, and its conformance test drives): proxy the four
  routes byte-for-byte — the SSE stream proxied as a STREAM (never buffered), the `DP-CSRF-Token`
  header absent, no cookie forwarded, and the §4 error envelopes passed through verbatim. The proxy
  authorizes its own application user; Datapipelines sees the key.

### 6.6 The states, notifications and the CSP

Each occurrence carries the record's states client-side (`ready`, `in-progress`, `error`, `abort`,
`no-data`, a brief `success` that settles to `ready`); `stale` rides BESIDE the state. Freshness is
per instance, per occurrence: the newest refresh owns the target, an event touches an occurrence only
through its owner, and a finished run cannot overwrite a newer view — an old run's late completion
detaches silently. A stream that ends without `refresh_completed` is a transport failure: content is
RETAINED, the pending occurrences go stale, and one notification with `recover: "retry"` offers the
new refresh; nothing replays itself.

Notifications are structured — `{instanceId, scope, severity, code, message, retryable, recover}` —
deduplicated per outcome, delivered to the adapter and `options.onNotification`, and recovered ONLY
through `instance.recover(intent)`. A `dashboard.runtime.configuration_stale` (409) publishes the
outcome with `recover: "reload"` and disposes the instance.

The CSP design-around: Plotly's bundle would inject one `<style id="plotly.js-style-global">` and fill
it with `insertRule` at load. The runtime pre-places that element with the class
`no-inline-styles` (Plotly's own opt-out, `src/lib/dom.js`), and the page loads the vendored
`plotly.css` — the release build's own strict-CSP sheet, the same rules the bundle would inject — as a
real stylesheet under `style-src 'self'`. The bundle injects nothing; no policy directive is widened;
the conformance suite proves the rules APPLY and is red when the stylesheet is removed.

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-30 | v0.5 | L3a (#10) the client runtime | **New §6 The client runtime** — the vendored artifact and what it owns (§6.1's API), the twelve-function adapter contract (§6.2), the three renderers and the data-is-text rule (§6.3), the two Plotly bundles and the one-bundle rule (§6.4), both credential modes' wire contract including the proxy contract L5's reference proxy implements (§6.5), and the states, notifications and the CSP design-around (§6.6). §5.8's "not here" loses the client runtime; the pages remain L3b's. |
| 2026-09-29 | v0.4 | L2 (#10) the runtime — renumbered at merge after 320's v0.3 | **New §5 The runtime** — the delegated act (D50) and what keeps it safe, the six routes, `configuration_id`, the parameter evaluation, a refresh (order, sharing, admission, caps, dependencies, deadlines, the stream), abort, the record. §4.4's `last_refresh` is live (the caller's own). |
| 2026-09-30 | v0.3 | 320 (#320) dependency guards | §3.1: the guard's other direction — a pipeline release, parameter set or transform template a dashboard or visualization pins can no longer be discarded or purged from under it (`pipeline.version.pinned`, `parameter.in_use`, `template.in_use`; [Versioning §3.5.3](versioning.md#353-the-reverse-arrows-into-other-families-320)); why the visualization's own LIVE-only guard is complete; restoring a DISCARDED dashboard version re-judges its dependencies. |
| 2026-09-29 | v0.2 | L1b (#10) the surfaces | **New §4 The surfaces** — the fourteen permission rows and the promoter's lens (§4.1), validate as an author verb (§4.2, owner ruling), what a source must declare for the save-time input check (§4.3), and the eleven MCP tools (§4.4); the REST routes are rest-api §22/§23. The status line names what L1b added. |
| 2026-09-29 | v0.1 | L1a (#10) the module | The two documents, their bounds and their lifecycle (§1–§3). |
