# Dashboards

**Status:** v0.1 — the two documents and their lifecycle (#10, lane L1a). The REST routes, the MCP tools and the
permissions (L1b), the transfer routes (L1c), the runtime (L2), the client runtime and the first-party page (L3),
the visualization tests and their release gate (L4) and the `dashboard` key kind (L5) add their sections as they land.
**Owner:** datapipelines.co core
**Depends on:** [Versioning](versioning.md) (§3.5 — the lifecycle table), [Pipeline Contract](pipeline-contract.md)
(§13.22, §13.23 — the codes), [Metadata DB](metadata-db.md) (§4.28–§4.33 — the tables), [Enumerations](enums.md)
(§31–§37), [Configuration](configuration.md) (§3.33 — the bounds)
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
visualization, fixture rows per case, `config` bytes and bindings per visualization —
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
dashboards.

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
