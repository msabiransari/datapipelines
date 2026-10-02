# Dashboards

**Status:** v0.21 — a SQL source is judged against the release's record (#328, §4.3); the two documents and their lifecycle (#10, lane L1a); the REST routes, the MCP tools and the
permissions (§4, lane L1b); the transfer routes and their limits' honest contract (§3.3, lanes L1c/L1c-b/L1c-c: the
import's atomicity, the RELEASE rules on a landing, the aggregate count ceiling, the wire's per-family arms); the server
runtime (§5, lane L2; #343's released pins and stream authority); the client runtime (§6, lanes L3a/L3a-b/L3a-c); the
first-party pages (§7, lane L3b); the tests' backend — sessions, capabilities, the mechanical check and the release
gate — is §3.4 (lane L4a, #352), and its workflow on the wire — the session routes, the preview page, the screenshot
upload and the two test tools — §3.4.1 (lane L4b, #353). The dashboard draft preview (#369) and the `dashboard` key kind (L5)
add their sections as they land.
**Owner:** datapipelines.co core
**Depends on:** [Versioning](versioning.md) (§3.5 — the lifecycle table), [Pipeline Contract](pipeline-contract.md)
(§13.22, §13.23 — the codes), [Metadata DB](metadata-db.md) (§4.28–§4.35 — the tables), [Enumerations](enums.md)
(§31–§38), [Configuration](configuration.md) (§3.33, §3.34 — the bounds and the runtime's numbers), [REST API](rest-api.md) (§22, §23 — the routes),
[MCP Server](mcp-server.md) (§6.2.50–§6.2.62 — the tools), [Auth](auth.md) (§7.6 — the permissions)
**Design:** the [dashboard implementation spec](superpowers/specs/2026-09-28-dashboard-implementation-spec.md) and
the [design record](superpowers/specs/2026-09-25-dashboard-authoring-design-draft.md) (decisions D1–D63)
**Last updated:** 2026-10-01

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
  Plotly's own `config` are objects. Nothing else at the top level. Deeper attributes are judged against the
  vendored 4.1.1 plot-schema (reduced to the nine supported traces' attribute trees, plus layout and config —
  §3.4): an unknown attribute, a wrong-typed value and an unsupported trace are refused
  `visualization.validation.config_schema_invalid` naming the path. A binding placeholder (`$.x`, §2.1.1) is the
  substitution grammar, not a value; the save-time walk accepts it where a leaf is expected.
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
(`*.authoring.disabled`) and still imports and switches. **The lifecycle verbs audit** (#332): the five human
verbs and the release each write their `dashboard.*` (and cascaded `visualization.*`) event — enums.md §15's
rows — beside the transfer pair's, the pipelines mould's audit with nothing riding the row but ids, names,
versions and counts.

### 3.1 Releasing a visualization

In this order, in ONE transaction for the last three steps: the DRAFT declares at least one test case
(`visualization.release.tests_missing`); the §2.1 rules pass against the pin as it is now; the transform pin is
RELEASED — or a DRAFT released with the visualization when the caller consents (`release_pinned_templates`, the
cascade pipelines use for templates), otherwise `visualization.release.dependency_not_released` naming the pin; the
release evidence passes — the agent's GREEN run for this exact content and the server's mechanical check (D56;
`visualization.release.tests_stale`, `visualization.release.tests_red`, `visualization.release.mechanical_failed`).
The gate is installed (#352, §3.4): in the release's one transaction it re-reads the exact DRAFT under a row
lock (a candidate whose draft moved underneath it is `tests_stale`), judges THE LATEST run of the version — none
`tests_missing` (reason `no_runs`; an OPEN session — a start never submitted, or a session opened after a GREEN run — `run_open`: a start without a verdict is not evidence), EXPIRED — written, or a RUNNING row past its deadline that no read swept — or for another hash `tests_stale`, RED or INCOMPLETE `tests_red` — and re-runs the
mechanical check against the current pins (`mechanical_failed`, whatever an earlier GREEN recorded). A refusal
leaves no partial release: the version stays DRAFT and no template is cascaded.

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
pin's templates travel with it, read without the template lens — and `{"dashboard": …, "visualizations": [each pinned visualization's envelope],
"manifest": {…}}`, whose manifest names the pinned pipelines and set by reference: a dashboard assumes they were
promoted first (D61's order: templates, parameter sets, pipelines, visualizations, dashboards). The manifest carries
`evidence: null` until the test sessions land (L4) — the exported release's evidence summary rides it from then on,
and an importing deployment records `imported_with_evidence` on its audit row verbatim. An import lands the
templates, then the visualizations, then the dashboard, each at its exported version with its exported id; the
envelope's shape is judged before anything lands, the lifecycle fields beside a body are ignored by name and any
other unknown key refuses; the same version with the same hash is a no-op; a pin the target lacks is
`visualization.import.missing_template` or `dashboard.import.missing_dependency`; an id another artifact on the
server holds is `*.import.id_taken` — never re-issued (C29).

**What the artifact pins travels unlensed** (the owner's ruling of 2026-10-02, #344; [Auth §11A.1](auth.md#11a1-the-404-rule)'s lens
clause): the promoter lens decides whether a visualization or dashboard exports at all — a hidden one is the absent
`404` — never what its envelope carries. A dashboard's pinned visualizations ride whatever the visualization lens says,
and each one's templates whatever the template lens says; the same holds for a promotion batch.

**The import is ONE transaction for a dashboard** (the L1c-b round, F1): templates → bundled visualizations → the
dashboard inside one transaction, so a refused dashboard leaves NOTHING landed — the visualization import keeps the
parameter-set mould's accepted shape (templates then the artifact, each idempotent). The dashboard import's audit
carries one `visualization.imported` row per LANDED bundled visualization, each naming its id, its version and its
own envelope manifest's evidence flag — nothing landed is unaudited. **A landing is judged by the RELEASE rules**
(O2): an import lands RELEASED, so a pin that exists here but is not RELEASED — a draft, or a discarded version —
refuses with the family's release code (`visualization.release.dependency_not_released` /
`dashboard.release.dependency_not_released`); a pin this deployment lacks stays the import lens' precise code.
**The envelope's arrays are count-bounded** (O7): at most `datapipelines.visualization.max-visualizations-per-dashboard`
entries in a dashboard's bundle or a template closure, refused `body_invalid` with `reason: too_many` before any
member is parsed. **The ceiling is AGGREGATE (the L1c-c round):** the same value also caps each whole promotion
batch arm (`visualizations`, `dashboards`) and each whole envelope array — an ADDITIONAL ceiling on top of the
per-document bound the key's name describes, not a consequence of it. Two dashboards that are each individually
valid — 30 distinct pins apiece under a 50 ceiling — can together present 60 entries, and such a batch refuses
WHOLE: one refusal naming the configured key and the count, nothing landed, no automatic split. A genuine batch at
exactly the configured ceiling lands whole. A separately scoped aggregate bound may be proposed as a follow-up;
until one is ratified, this documented behaviour is the shipped behaviour.

**The verb is a workspace-admin's, by the owner's ruling (2026-09-29, the `api_key.bind` cells):** the import lands
RELEASED with no evidence re-run — the D56 promise traveled WITH the exported release, and an authoring deployment
never re-runs it — so an author never holds `visualization.import` / `dashboard.import`. The promotion receiver
lands promoted artifacts through the WIRE, never through the import verb: a batch's entries are the versions'
payloads (never the envelopes), bound through the same strict readers (the bounds hold on receive as on save, the
arms count-bounded before any member binds), and landed INSIDE the receive's one transaction after the batch's
templates, sets, pipelines and endpoints, where the just-landed rows are what the import lens resolves against.
**A dashboard root's pins travel as entries of the batch's `visualizations` arm** (O1) — deduplicated against the
explicit visualization roots — and the `dashboards` arm carries dashboards alone; the receiver binds each arm's
entries with that family's reader and lands the visualizations before the dashboards that pin them. An export is
lensed BEFORE it is built — a visualization or dashboard the promoter lens hides is the same 404 an absent id gets.

### 3.4 The test sessions and the evidence (#352, the spec's §11)

A visualization's release needs evidence: an agent's run of the saved test cases against the exact draft content
plus the server's own mechanical check. The backend is this section (#352); the workflow on the wire — the routes,
the preview page, the upload and the two tools — is §3.4.1 (#353).

- **A session** (`tests.cases`' run) pins workspace, artifact, version, body hash, the case inventory and the
  actor; it opens over the artifact's WORKING version and lives for `datapipelines.visualization.session-ttl-minutes`
  (60, [Configuration §3.33](configuration.md)). At start the server mints a **preview capability** — 32 random
  bytes, base64url, stored hash-only — which is the future preview page's only credential (§11.2 of the spec); it
  is revoked by the submit and by expiry.
- **A submission** answers every case by name with `green` or `red` and bounded per-case notes (≤ 2,000
  characters); the environment field set is CLOSED (`theme`, `viewport`, `browser`, `locale`, `renderer_version`,
  each ≤ 120 characters) and is the agent's REPORT, never a server measurement. Unknown or duplicate case names
  refuse; an omitted case lands the run `INCOMPLETE`; any `red` verdict — or a mechanical failure — lands `RED`;
  GREEN requires every case green AND the mechanical check passing at submit.
- **The mechanical check** (§11.3, D56 (b)) is server-run, no browser, sub-second (measured: one case, 1,000
  fixture rows through the deep schema, ~60 ms): the renderer's schema (for Plotly the reduced vendored
  4.1.1 plot-schema — unknown attributes, wrong types and unsupported traces refused with the path), every
  binding's resolution and per-path type rules, every case's fixtures through the REAL bounded evaluator (a DRAFT
  transform pin is valid here — authoring; the runtime keeps its RELEASED-only rule), every projected bound
  column present in every row, and static assertion feasibility (`trace_count` against `config.data.length`,
  `no_data` against zero produced rows, `text_visible`/`value_visible` strings present in the configuration or
  the bound values). The rendered-state check records `not_available` today — a headless render check is a later
  lane, and nothing here claims a browser saw anything. The report is stored on the run and RE-RUN at release.
- **At a successful submission the server mints a SECOND, separate capability** for the screenshot upload —
  random, hash-only, bound to the exact run and purpose, expiring no later than the session's original deadline,
  its raw form shown exactly once. Submit revokes the preview and does NOT touch this one; atomic image storage
  consumes it; a failed upload consumes nothing; two competing valid uploads have exactly one winner; a completed
  run is never re-submitted, so no replacement capability exists.
- **The screenshot** is one image per run: the DETECTED media type (a declared Content-Type, a filename or a
  header is not type validation — the bytes are parsed), independently read positive dimensions, the computed
  SHA-256, the depicted case (when given, it must be a case of that run), the actor and the stamps; ≤ 4 MiB and
  PNG/WebP only. The server does not decode, and parses only the bytes it already holds.
- **Retention** (D35): for a DRAFT version, the newest completed run keeps its screenshot and an older run's is
  deleted; for a RELEASED version, the run whose GREEN result qualified the release — and its screenshot — is
  kept for the version's life. Expiry touches RUNNING sessions only: a completed GREEN record is never
  retrospectively erased, and a run whose version's hash moved on reads `EXPIRED` and can never qualify a release.
  **An author who re-tests a draft many times keeps only the LAST screenshot**: each newer upload for the same
  draft version deletes the older runs' images (the runs themselves, their verdicts and reports, stay).

#### 3.4.1 The workflow on the wire (#353)

An agent proves a visualization in five steps; the routes are [REST API §22.2](rest-api.md#222-routes), the tools
[MCP Server §6.2.61–62](mcp-server.md), the permissions [Auth §7.6](auth.md) (`visualization.update` for the
session and the verdicts, `visualization.read` for the evidence and the on-demand check):

1. **Start** — `POST /api/v1/visualizations/{id}/tests/sessions` or `visualizations_test_start`: a session on the
   WORKING version; the answer's `preview_url` carries the preview capability, shown once.
2. **Preview** — the agent's browser opens `GET /visualizations/{id}/preview?session=<capability>` (ui-screens §4.22):
   no login, no cookie read or set; the page mounts the SAME vendored runtime (§6) in its **fixture mode**, one
   instance per case, with the case's saved fixtures evaluated exactly as the mechanical check evaluates them —
   no pipeline, no datasource, no request to `/api/v1`. `?theme=light|dark` picks the theme.
3. **Submit** — `POST …/tests/sessions/{sessionId}/results` or `visualizations_test_submit`, by the SAME principal:
   the verdicts, notes and environment; the server's mechanical check decides GREEN with them; the preview dies.
4. **Upload** — on GREEN only, `POST …/tests/sessions/{sessionId}/screenshot` with the raw PNG/WebP (≤ 4 MiB, the
   route's OWN cap — the platform's 2 MiB stays on every other route) and the single-use capability in
   `DP-Upload-Token`: no session and no key — an `mcp` key reaches no REST route, which is why this capability
   exists (the owner's ruling (b)) — and no CSRF pair, because no cookie is read there.
5. **Release** — a person, in the UI or over REST, through §3.1's gate: the latest run GREEN for the exact content,
   the mechanical check passing again now. No tool releases.

**Confinement.** Each capability opens ONE route for ONE run: the preview its page for that visualization's version,
until the submit or the deadline; the upload its screenshot route, once. Neither opens any other route — on a
lifecycle route, a runtime route or `/mcp` the request is simply anonymous. Because a capability-bearing request has
no principal, the run's STARTER is re-judged at the moment of use: a member demoted below `visualization.update`, a
member removed, a revoked or expired key, a deactivated person, identity or workspace all void the capability. Every
failure on these two routes — absent, malformed, wrong, consumed, expired-and-swept, another visualization's, a starter
who lost the right — is one indistinguishable answer (the page's one 404; the upload's `session_not_found`). The one
exception (#373): a presented token that MATCHES an unconsumed upload capability past its deadline is `410`
`session_expired` (`details.reason: capability_expired`), judged before any byte of the body — the holder of the right
token learns only what it already knows; a wrong or consumed token keeps the one 404. The
capabilities ride a URL and a header, never a cookie, are never logged by the application, and the page's
`<meta name="referrer" content="no-referrer">` keeps them out of its subresources' `Referer`; the deadline (60 minutes) and the revocation at submit bound
what a leaked preview URL can show — the saved fixtures of one version, nothing live.

**Evidence reads.** `GET …/tests/runs` (the newest 100), `GET …/tests/runs/{runId}` and
`GET …/tests/runs/{runId}/screenshot` answer the redacted runs — capabilities as presence and stamps only — and the
stored image; `POST …/versions/{version}/check` runs the mechanical check on demand over a stored version and records
nothing. All four pass the caller's lens: a promoter reads evidence only of versions the lens admits (RELEASED ones).
The agent's `notes` and `environment` are JSON fields, never rendered as markup.

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
id. The two import rows landed with the transfer routes (L1c — the workspace-admin verb, the ruling above), the execute
row with the runtime (L2), and the key-binding row and the `dashboard_viewer` key column with the key kind (L5, #367):
a `dashboard` key's ONE role is `dashboard_viewer`, holding exactly `dashboard.read` and `dashboard.execute` —
`bound` in both cells, because the BINDINGS ARE THE LENS. The key's bindings are folders of the dashboard NAME space
(`dashboard_key_bindings`, the `endpoint_key_bindings` twin): a folder prefix binds every dashboard beneath it, a
deeper binding REPLACES an inherited one (R-EP2 verbatim), and an unbound key serves NOTHING — the family's ordinary
404. The kind reaches the runtime and refreshes routes only; every other family refuses it centrally
([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)).

### 4.2 Validate is an author verb

`POST /api/v1/dashboards/{id}/validate` and `dashboards_validate` run §2.2's rules on the WORKING version against the
dependencies as they are now, write nothing, and answer the verdict (`valid` and every failure) with a `200`. The
validator reads the pinned pipelines', set's and visualizations' statuses without a lens, so a refusal can name the
state of a pin the caller could not otherwise see; the verb therefore sits on `dashboard.update`, not
`dashboard.read` (owner ruling 2026-09-29): a viewer and a promoter never reach it.

### 4.3 What a source must declare

A dashboard source's release is judged at save by its status, its read-only verdict (the published-endpoint rule,
through child pipelines) and its parameters. Its OUTPUT columns are the release's own answer, in #328's precedence
(pipeline-contract §3.3.1):

- A **transform** caller node's pinned contract names the columns — the declared answer, and it outranks everything
  else.
- Any other (SQL) caller node's columns are **the release's record**: what the release copied from the version's
  latest run when the checks ran (`caller_output_json`; D1). A release with no qualifying run records nothing, and
  answers `caller_output = not_observed` — **the one remaining skip**: the save-time
  `dashboard.validation.input_contract_mismatch` check is left to the runtime (L2), which judges the real columns.
- A release with **no caller node** answers an empty output (every mapped input is refused).

The record outlives the datasource: it is read from the release, never from the live schema, so an `ALTER TABLE`
after the release does not change what save-time validation judges (the runtime still fails on the real columns —
that contrast is the point of the record).

### 4.4 The tools

| Tool | Permission | What |
|---|---|---|
| `visualizations_list`, `visualizations_get` | `visualization.read` | Browse a level (each row with `used_by`: the dashboards that pin it); read the working version |
| `visualizations_create`, `visualizations_update` | create / update | Write the document (the arguments ARE its keys); the new-root confirmation on create; `expected_hash` on update |
| `visualizations_purge_draft` | `visualization.version.manage` | Purge a draft at its hash — never one a live dashboard pins |
| `visualizations_test_start`, `visualizations_test_submit` | `visualization.update` | §3.4.1's start and submit — the preview URL on start, the single-use upload on a GREEN submit (the upload itself is the REST route) |
| `dashboards_list`, `dashboards_get` | `dashboard.read` | Browse; read the working version with each pin's status, each source's release and read-only verdict (lensed), and `last_refresh` (the CALLER's own latest refresh — id, version, status, stamps — or null; through an MCP key, which is its own identity, it is null today: no tool refreshes a dashboard, §5) |
| `dashboards_create`, `dashboards_update`, `dashboards_purge_draft` | create / update / version.manage | As the visualization tools |
| `dashboards_validate` | `dashboard.update` | §4.2 |

No tool releases or executes anything. The served manual's `dashboards` guide is the authoring loop in the order
an agent needs it, including the test loop of §3.4.1.

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
- **Every runtime pin is released.** Configuration reads and refreshes require RELEASED visualization and parameter-set
  versions, and RELEASED transform templates for every visualization that pins one. A missing or changed pin is
  `dashboard.runtime.dependency_missing` (409); a transform that changes status after configuration resolution fails
  that target in the already-open stream before evaluation.
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
increasing — a hint the client compares, held in memory, that starts again after a restart. This is the D50 delegated
act: `dashboard.execute` authorizes evaluation of the dashboard's pinned set without separately requiring
`parameter_set.evaluate`. The unchanged response retains the evaluator's existing bounded selector diagnostics,
including its safe echo for a failed or unreachable selector; the refresh stream's code-only rule applies to stream
frames, not this response. No additional diagnostic redaction is applied here. A dashboard with no set answers an empty
evaluation.

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
write). Events and heartbeats resolve the workspace captured when the stream opened without session-navigation
fallback, and require the resolved immutable workspace id to match; membership in another workspace does not preserve
access to this stream. The refresh itself runs to its end. The three source frames include `execution_id` only when the subscriber's
current principal also holds `execution.read`; a role change takes effect under the auth cache's membership window.
The internal event and durable refresh-execution link retain the id either way.

### 5.6 Abort

`POST …/refreshes/{refresh_id}/abort` with `{ instance_id }` answers 202 without waiting. It requires `dashboard.execute`
plus OWN (the refresh's principal AND client instance) or `execution.cancel_all`; anything else — no such refresh,
another dashboard's, another person's, one already finished — is `dashboard.refresh.not_found`. It sets a refresh-level
flag the OWNING instance polls (`dp:refresh-abort:{refresh_id}`), pulls the local trigger when this IS the owner, and
cancels each execution the refresh has started through the executor's own path. The refresh ends `ABORTED`, every
not-yet-started source is skipped and `refresh_completed` is last. A client that disconnects and stays away past the grace
aborts its refresh the same way.

**An abort before the row (#356).** The refresh id is the CLIENT's mint, so an abort can outrun `insertRunning` — the
row exists only after the selections are evaluated and admission granted. The start therefore registers a transient,
TTL'd marker (the abort flag's twin in the same Redis keyspace) as soon as the dashboard is resolved, and removes it on
EVERY exit; the bound is the caller's own stream cap, and a principal at the bound is refused the saturated 429 rather
than let a newer start steal an older start's authorization. An abort with no row consults the marker: a match (same
workspace and dashboard, the caller's own principal AND instance, or `execution.cancel_all`) records the abort intent
under the id exactly like any abort and answers the same 202 — and the engine's check at job start ends the refresh
`ABORTED` with `refresh_completed` last before any source runs, the row closed and audited like every other ending.
No marker, a marker for another dashboard, or a marker for someone else is the same `dashboard.refresh.not_found` as
every other no — an unknown id, another person's running id, another instance's id and a finished id all answer the
identical body.

### 5.7 The record

`dashboard_refreshes` and `dashboard_refresh_executions` ([Metadata DB §4.34–§4.35](metadata-db.md)) hold every refresh that
was admitted. The row is closed however the refresh ends — the client gone, the deadline passed, the process stopping —
because the terminal work runs non-cancellably at the one point that owns it; a refresh an instance crash left
`RUNNING` is closed `TIMED_OUT` by the sweeper ([§8.4](metadata-db.md)). Finished refreshes are retained like execution
events. One `dashboard.refresh` audit row per refresh is written awaited at its end ([Enums §15](enums.md)).

The events pane links a refresh's executions only for a person who may read executions: a promoter refreshes a
dashboard she can read but holds no `execution.read`, so her refresh names no execution.

### 5.8 What is not here

The dashboard draft preview (#369, unowned after the L4 split) and the visualization tests (L4). The `dashboard` key kind IS here now (L5, #367): an external
application's backend holds a `dashboard` key and proxies the runtime routes — §6.5 is the wire contract and
[Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings) the kind's specification. No MCP tool refreshes a
dashboard. The first-party pages are §7 (L3b).

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
| `renderParameters(state) → Promise` | render the FULL server state, hidden and disabled included | awaited INSIDE the parameter gate — the lock releases only after the render resolves |
| `readSelections() → {name: value}` | return every current committed value, in the wire's type | merged over the server state at each evaluation; hidden and disabled values included (D23) |
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

**The parameter state the adapter receives is the writer's** (`EvaluateResponseJson` — rest-api
§21.3): every parameter is a FLAT object — the stored definition's fields (`name`, `label`, `type`,
`kind`, `cardinality`, `presentation.control`) beside `dependents` and a `state` object holding
`value` (in its WIRE type: a JSON number for INTEGER, a string for BIGDECIMAL, an array for a MULTI,
`null` unresolved), `options` (`{value, display_value, is_default}` with typed values), `hidden`,
`disabled` and `errors`. The response's `overrides_applied` (the dashboard's `parameter_state`
overrides) wins over the engine's `hidden`/`disabled`, and `valid: false` (any parameter in error)
refuses actions until a commit's re-evaluation restores it. The composite renders each control from
the definition — a `<select>` for a SINGLE dropdown/list, a radio group for `radio`, a checkbox group
for a MULTI, a free input for an `INPUT` (a BOOLEAN `INPUT` renders the house tri-state select —
`— not given —` / `true` / `false`, the schedules form's control — so the unresolved `null` is
displayed and read as null, never silently as false; the `toggle`/`checkbox` hints are not honoured
yet, P23 lets a renderer ignore a hint) — and reads selections back in the WIRE type (option
identity is the typed value, never a DOM string; radio groups are named per adapter instance, so two
boards in one document never share a native radio group); repeated renders REPLACE the rows, and
`dispose` removes every element the composite mounted (a host sibling stands).

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
- **`{ proxyBaseUrl }`** — an external application's backend holds a `dashboard` key (L5, #367) and proxies
  the SAME FOUR runtime paths under its base. The runtime sends the same request bodies and Accept
  headers to `{proxyBaseUrl}/api/v1/dashboards/{id}/runtime/…` with `credentials: "omit"` and NO CSRF
  header — the key never reaches the browser, and the browser offers it nothing. THE PROXY WIRE
  CONTRACT (what L5's reference proxy implements, and its conformance test drives): proxy the four
  routes byte-for-byte — the SSE stream proxied as a STREAM (never buffered), the `DP-CSRF-Token`
  header absent, no cookie forwarded, and the §4 error envelopes passed through verbatim. The proxy
  authorizes its own application user; Datapipelines sees the key.
- **Fixture mode — `server: { fixtures: { config, results } }`** (#353, the visualization test preview, §3.4.1) —
  no server at all: the configuration read answers `fixtures.config`, a refresh's stream answers each target's
  `results[name]` (`{bindings, rows}`, `{rows: 0}` for the empty state, `{error: {code, message}}` for an evaluator
  refusal) as the frames a live refresh would send, stamped with the client's own refresh id so the freshness gate
  judges them unchanged, and an abort answers at once. The runtime issues NO `fetch` in this mode, and `init` refuses
  `fixtures` beside a `baseUrl` or `credentials`; the lifecycle, the adapters, the renderers and the layout are the
  same code a board runs.
- **The reference proxy is `examples/dashboard-proxy/proxy.mjs`** (dependency-free Node >= 18; `DP_BASE_URL` and
  `DASHBOARD_KEY` from env; `node proxy.mjs` — the README beside it). Its conformance test is
  `modules/web/src/test/js/dashboard-proxy.test.mjs` (a stub upstream proving the byte-for-byte relay, the header
  stripping, the four-route fence, the streaming timing and the envelope pass-through — it FAILS on a buffered
  proxy), and `tests/integration-tests/.../DashboardKeyE2eTest.kt` runs the script against the real application
  with a real key. `/refreshes` is deliberately NOT relayed: the refreshes routes are owner-scoped by the key, so
  a host relaying them would let one end user read (and abort) another's refresh of the shared key — the fence is
  the proxy (a fifth path answers the proxy's own 404), and the host may build its own per-user history.
- **One key = one budget.** The SSE stream cap (`datapipelines.sse.max-streams-per-user`,
  [Configuration §3.6](configuration.md)) and the rate limit are keyed on the key's identity, so every end user
  behind one proxy shares that key's budget. Size `max-streams-per-user` for the host's expected concurrency; the
  refresh records of a key principal carry `principal_key_id` (never a person), and its executions are attributed
  `executed_by_key_kind = 'dashboard'`.

### 6.6 The states, notifications and the CSP

Each occurrence carries the record's states client-side (`ready`, `in-progress`, `error`, `abort`,
`no-data`, a brief `success` that settles to `ready`); `stale` rides BESIDE the state. Freshness is
per instance, per occurrence: the newest refresh owns the target, an event touches an occurrence only
through its owner, and a finished run cannot overwrite a newer view — an old run's late completion
detaches silently. A stream that ends without `refresh_completed` is a transport failure: content is
RETAINED, the pending occurrences go stale, and one notification with `recover: "retry"` offers the
new refresh; nothing replays itself. An abort click renders the `abort` state at once, with the reason
naming it `abort.requested` — a REQUEST, not an outcome. The server's answer is the honesty boundary
(#356): a `202` sets `abortAcked` (explicit cancellation was recorded) and the terminal frame closes
the refresh; a `404` — the finished-refresh idempotence, or an abort that was never the caller's to
make — sets nothing, the chips re-render `abort not confirmed`, and the terminal frame (or the
stream's end) decides what really happened: a target whose real outcome was `ok` has its delivered
state restored, because a server-aborted target never reports `ok`. The stream stays open through the
abort either way — the server's own terminal frame, never the click, ends the refresh client-side.

Notifications are structured — `{instanceId, scope, severity, code, message, retryable, recover}` —
deduplicated per outcome, delivered to the adapter and `options.onNotification`, and recovered ONLY
through `instance.recover(intent)`. A `dashboard.runtime.configuration_stale` (409) publishes the
outcome with `recover: "reload"` and disposes the instance.

The parameter lock (§5.6 of the record) covers the WHOLE attempt — the server call AND the host's
asynchronous application of the accepted state: the gate releases, the committed state installs and
the timer clears only after `renderParameters` resolves, with the attempt re-validated first (liveness,
its finished flag, lock ownership). The absolute deadline stays live through the render: an expiry
mid-render publishes the timeout, frees the gate for a NEWER attempt, and the late render changes
nothing and releases nothing it does not own. The deadline is enforced by the ABSOLUTE clock at both
admission points — a response is refused before the adapter is invoked with it, and a resolved render
again before anything commits — so an overdue arrival changes nothing even when the timer's callback
has not fired yet; the boundary is inclusive (an arrival exactly at the deadline is admitted,
strictly after it is `parameters.superseded`). A rejected render (or one that throws synchronously)
follows the same bounded path: the attempt terminates exactly once and a recoverable
`parameters.render_failed` outcome is published. Reset's install yields the same way — a baseline
re-rendered while a newer evaluation was accepted does not clobber the newer revision.

The CSP design-around: Plotly's bundle would inject one `<style id="plotly.js-style-global">` and fill
it with `insertRule` at load. The runtime pre-places that element with the class
`no-inline-styles` (Plotly's own opt-out, `src/lib/dom.js`), and the page loads the vendored
`plotly.css` — the release build's own strict-CSP sheet, the same rules the bundle would inject — as a
real stylesheet under `style-src 'self'`. The bundle injects nothing; no policy directive is widened;
the conformance suite proves the rules APPLY and is red when the stylesheet is removed.

## 7. The first-party pages (L3b)

The app's own surfaces over the runtime: the tree page (`GET /dashboards`), the board page
(`GET /dashboards/{id}`), the sidebar's Dashboards branch and the events pane. Every handler is
a session-authenticated GET declaring `dashboard.read` — D50 makes every reader an executor, and
the pages must not declare `dashboard.execute` to "simplify" — except the events pane's
fragment, which floors at `dashboard.execute` with the refreshes route it mirrors (auth §7.6's
Surfaces cells carry the routes).

**The tree page and the sidebar branch (D58).** The landing item is RENAMED Home — the route
`/dashboard` and its exact-match nav section are unchanged. Build's last rail item is
**Dashboards**: the link to the tree page, and beside it a chevron disclosure that expands IN
PLACE into the hierarchy — lazy (the summary's first click fetches ONE level), collapsible,
hidden with the collapsed rail. Both the page's tree and the branch render the SAME
one-level-per-request fragment (`GET /partials/dashboards/tree?prefix=&scope=`), the pipelines
explorer's shape: virtual folders, server-side prefix queries through the dashboards service's
LENSED reads (a hidden dashboard is absent from the tree and its counts; nothing is filtered in
a template), the root level listing folders only. Level ids are derived per scope (`page`,
`nav`) because the two instances can coexist in one document. A leaf opens the board page as a
FULL navigation — `hx-boost="false"`, §6.4's rule: the two bundles must never travel between
pages, and the layout's logout/members links are the precedent.

**The board page.** The server resolves the runtime configuration ONCE at render to learn the
ONE bundle (`renderer.bundle`, §6.4) and writes the script tag with its
`data-dp-plotly-bundle` declaration — the same read the client's bootstrap performs, so the
declaration and the judged pair cannot disagree; the runtime's two-bundle refusal is the
backstop. The glue (`static/js/dashboards-page.js`, a file — the CSP allows no inline script)
reads exactly two data attributes (the dashboard id on the container; the bundle name on the
script tag), inits the runtime with `credentials: "session"` and `version: "released"`, and
owns the notification/refusal regions. A dashboard the caller cannot see is the family's 404; a
dashboard that exists for the caller but cannot run (no release yet, a purged pin, a source no
longer read-only) renders the page's REFUSAL state — the code and sentence the runtime's read
refused with, and the `onNotification` sink's region for a later boot failure — never a blank
pane, and a refusal page loads no bundle at all. `dashboards.css` and the vendored
`plotly.css` (§6.6's design-around) load from the LAYOUT head: ui-screens §3.0 is normative —
no page template carries its own stylesheet link, and a page-scoped sheet on any route paints
an unstyled first frame.

**Navigation onto the route is never boosted; disposal is the `htmx:beforeHistorySave` hook.**
Every link TO a board carries `hx-boost="false"` — §6.4's rule: the two bundles must never
travel between pages, and a boosted board-to-board swap would run one page's bundle under the
other's document. But LEAVING the board can still be boosted (the layout's nav links are), and
htmx saves a history snapshot of the current document when it goes — a snapshot that would
carry the container WITH its mounted marker, making the restored page's fresh `init` refuse
`DashboardAlreadyMounted` (found by the pages' browser suite on its first run: the Back button
showed the refusal state where the board was). So the glue disposes on
`htmx:beforeHistorySave` — the instance is torn down and the container unmarked BEFORE the
snapshot is taken (dispose also removes the mounted DOM, so the cached markup is the page's
shell). The pages' browser suite proves exactly one mount across a back/forward pass.

**The events pane.** The caller's refreshes of the open dashboard, newest first
(`GET /partials/dashboards/{id}/refreshes` — the REST refreshes route's own read through the
runtime, so the lens and the own/`execution.read_all` visibility are the route's), beside the
board. Each row shows status, scope and age and links its executions to `/executions/{id}` —
shown only to a reader of executions; a promoter's refresh names none (§5.7, rest-api §23.3).
Updates are a BOUNDED POLL (`load delay:15s`, then `every 15s`; the pane re-fetches itself),
deliberately not §6's notification hooks: the pane's source of truth is the DURABLE record
(including the `execution.read_all` reader's wider view), the hooks fire only for refreshes
this browser started and would need a second client-side row renderer duplicating the server's
markup, and the poll keeps working when the instance failed to boot. The first re-fetch is
delayed because the server just rendered the rows; the poll dies with the page (leaving is a
full navigation).

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-10-02 | v0.21 | 328 (#328) a SQL source is judged against the release's record | **§4.3 rewritten:** a SQL caller node's columns are the release's RECORD (`caller_output_json`, pipeline-contract §3.3.1) — judged at save like a declared contract; `not_observed` (no qualifying run at release) is the one remaining skip, left to the runtime (L2). The record outlives the datasource: read from the release, never from the live schema. No tool, route, permission or error-shape change (`dashboards_validate`'s input is unchanged). NEXT: renumber at the final merge of main. |
| 2026-10-02 | v0.20 | 373 (#373) the upload gate judges expiry before the body | §3.4.1's confinement sentence names the one exception to the one-answer rule: a presented token matching an UNCONSUMED upload capability past its deadline is 410 `capability_expired`, judged before any byte of the body; a wrong or consumed token keeps the one 404 `session_not_found`. Authority: rest-api §22.2's error ladder. |
| 2026-10-02 | v0.19 | 344 (#344) the envelope is the artifact's pins | **§3.3:** what the artifact pins travels unlensed — a dashboard's pinned visualizations and each one's templates ride whatever the visualization and template lenses say; only the root is lensed (the owner's ruling of 2026-10-02, auth §11A.1's lens clause). No behaviour changed. |
| 2026-10-01 | v0.18 | L5 (#367, #10) the `dashboard` key kind | **§4.1: the `dashboard_viewer` column and the binding row are HERE** — the key's one role holds `dashboard.read` + `dashboard.execute`, `bound` in both cells, the bindings ARE the lens (`dashboard_key_bindings`, R-EP2 verbatim: deeper replaces, unbound = the family 404). **§5.8:** the key kind is no longer "not here" — the runtime routes have a non-session caller. **§6.5:** the wire contract gained its implementation — the reference proxy is `examples/dashboard-proxy/proxy.mjs`, its conformance test `dashboard-proxy.test.mjs` (the streaming timing is the buffering detector) and the real-stack E2E; `/refreshes` is deliberately not relayed (owner-scoped by the key; one key = one budget, sized by `max-streams-per-user`); refresh rows carry `principal_key_id`, executions `executed_by_key_kind = 'dashboard'`. |
| 2026-10-01 | v0.17 | L4b (#353) the test workflow on the wire | **New §3.4.1** — the five-step workflow over REST and MCP (start, the session-less preview page, submit, the single-use screenshot upload, the human release), the confinement (each capability opens one route for one run; the starter re-judged at the moment of use; one indistinguishable answer for every capability failure) and the lensed evidence reads; §3.4's retention bullet gains the UI sentence (an author who re-tests keeps only the last screenshot). **§6.5:** the runtime's fixture mode (the transport swapped, nothing else; no `fetch`). §4.4 names the two test tools; §5.8 no longer lists the test surfaces. |
| 2026-10-01 | v0.16 | 332 (#332, #330, #331) the lifecycle audit + the pins projection | **§3:** the five human verbs and the release audit (the `dashboard.*` events, the cascaded visualization releases named with `cascade_from_dashboard_id`) — the pipelines mould, ids/names/versions/counts only. |
| 2026-10-01 | v0.15 | L4a (#352) the test sessions and the release gate — renumbered at merge after #356's v0.14 | **New §3.4 The test sessions and the evidence** — the durable run per session over the exact draft hash, the two hash-only capabilities (preview; the single-use screenshot upload minted at submit), the mechanical check (the reduced vendored 4.1.1 plot-schema at save AND release, binding type rules, the real fixture evaluation, static assertion feasibility, `not_available` rendered state), the screenshot's detected-type validation and retention (D35), and the release gate's installed verdicts (§3.1). §2.1.2's Plotly schema is the deep one now; §4.4's test tools and §5.8's test surfaces are explicitly L4b's (#353), the dashboard draft preview is #369's and marked unavailable until then. No new route, tool or permission row. **At merge (the orchestrator's follow-up):** an OPEN latest session refuses `tests_missing`/`run_open` and a RUNNING row past its deadline is `run_expired` without a sweep (F1/F6); the mechanical report keeps its first 100 failures and COUNTS the rest (`failures_dropped`, F2); the upload consume stamps the app clock (F3); a 12-byte or top-bit RIFF WebP is `truncated`, not a 500 (F4); the gate's draft lock is `FOR NO KEY UPDATE` so two releases serialize (F5). |
| 2026-10-01 | v0.14 | #356 the abort before the row — renumbered at merge after L3b's v0.13 | **§5.6:** an abort arriving before `insertRunning` writes its row is honoured — the start registers a transient, per-principal-bounded marker, a matching caller is answered the same 202 and the refresh ends `ABORTED` before any source runs; the four no-cases (unknown, another person's, another instance's, finished) answer the identical `dashboard.refresh.not_found`. **§6.6:** the abort chip renders `abort.requested` until the server answers; a 202 sets `abortAcked`, a 404 re-renders `abort not confirmed` and the terminal frame decides — a delivered `ok` restores its state, and the stream stays open through the abort. Merge follow-up: the marker is written once (SET NX, before the bound set) — a replayed start of an id already in flight is the reused-id 400 and cannot delete the first start's marker or slot. |
| 2026-10-01 | v0.13 | L3b (#10) the first-party pages — renumbered at merge after #343's v0.12 | **New §7 The first-party pages** — the tree page and the sidebar's Dashboards branch (D58; the landing item renamed Home, the route unchanged), the board page (the server-declared ONE bundle, the glue as a file, the refusal state for a board that cannot run, never a blank pane) and the events pane (the caller's refreshes, execution links by the reader's visibility, a bounded poll chosen over the runtime's notification hooks, with the why stated). The one-bundle rule's navigation half is stated as the brief's rule it is: every link to a board carries `hx-boost="false"`, which is also the §10.5 disposal answer — full navigation, no htmx history, no second instance. Permissions: the pages on `dashboard.read`, the pane fragment on `dashboard.execute` beside the refreshes route it mirrors (auth §7.6's Surfaces cells). `dashboards.css` and `plotly.css` load from the layout head (ui-screens §3.0 is normative). |
| 2026-09-30 | v0.12 | #343 stream workspace authority | Recheck the opening workspace by immutable id for every event and heartbeat; a different membership cannot keep the stream alive, while the refresh continues. |
| 2026-09-30 | v0.11 | #343 dashboard runtime residue — renumbered at merge after L1c-c's v0.10 | Require RELEASED visualization, set and transform pins at runtime; clarify the unchanged bounded parameter response and current-authority execution-id projection on source frames. |
| 2026-09-30 | v0.10 | L1c-c (#10) transfer limits and evidence | **§3.3:** the count ceiling is stated as AGGREGATE — `max-visualizations-per-dashboard` also caps each whole envelope array and each whole promotion batch arm, an additional ceiling on top of the per-document bound (two individually valid dashboards can together exceed it; the batch refuses whole, no partial writes, no split; a batch at exactly the ceiling lands whole — E2E-proven both ways). The promotion page's `body_invalid` flash renders its toast (it was unmapped, hence silent; the `missing_datasources` and `key_invalid` branches' `&#39;` entities inside fragment-expression literals were a latent render-500, fixed with typographic apostrophes). The transfer service now receives the operator's configured value (the bean factory passed nothing; the constructor default stood in silently). |
| 2026-09-30 | v0.9 | L1c-b (#10) the transfer round's corrections | **§3.3:** the dashboard import is ONE transaction (a refused dashboard leaves nothing landed) and its audit carries one `visualization.imported` row per landed bundled visualization (F1); a landing's pins are judged by the RELEASE rules — a present-but-not-RELEASED pin refuses the family's `release.dependency_not_released` (O2); the envelope's bundle and the batch's family arms are count-bounded by `max-visualizations-per-dashboard` before any member binds (O7); a dashboard root's pins travel as entries of the batch's `visualizations` arm, the dashboard arm carrying dashboards alone (O1). |
| 2026-09-29 | v0.8 | L1c (#10) the transfer — renumbered at merge after L3a-c's v0.7 | **§3.3** names the manifest's `evidence: null` (L4 fills it) and the import audit's `imported_with_evidence`; the import verb is the workspace-admin verb by the owner's ruling (the `api_key.bind` cells — the envelope lands RELEASED with no evidence re-run), and the promotion wire's receive order and reader binding are stated (the bounds hold on receive as on save; the import lens resolves inside the receive's transaction). §4.1's "the import rows land with the transfer routes" is past tense. |
| 2026-09-30 | v0.7 | L3a-c (#10) typed controls and the absolute deadline | The composite's BOOLEAN `INPUT` renders the house tri-state select (`— not given —` / `true` / `false`) so an unresolved null is read as null and a visible edit travels as a wire boolean (§6.2); radio groups are named per adapter instance, so two boards in one document never share a native group (§6.2). The lock's absolute deadline is enforced by the clock at both admission points — before the adapter is invoked with a response and again before a resolved render commits — with the inclusive boundary stated (§6.6). The completed target's REAL wire outcome (`ok`, rest-api §23.3) no longer errors a delivered target: completion never clobbers the state the data frame set. |
| 2026-09-30 | v0.6 | L3a-b (#10) the client runtime corrections | The client consumed the WIRE now (corrections on the delivered tip, the server wire authoritative): §6.2 states the parameter state's real writer shape (flat definition + `state`, typed values, `overrides_applied`, `valid`), the composite's per-definition controls and typed selections, the row-replacing renders and dispose's DOM removal; §6.6 states the lock's corrected coverage — held through the host's asynchronous render, deadline live through it, late renders and reset installs yield to a newer attempt. The abort route is the controller's one-`runtime`-segment path (§5's route table unchanged). |
| 2026-09-30 | v0.5 | L3a (#10) the client runtime | **New §6 The client runtime** — the vendored artifact and what it owns (§6.1's API), the twelve-function adapter contract (§6.2), the three renderers and the data-is-text rule (§6.3), the two Plotly bundles and the one-bundle rule (§6.4), both credential modes' wire contract including the proxy contract L5's reference proxy implements (§6.5), and the states, notifications and the CSP design-around (§6.6). §5.8's "not here" loses the client runtime; the pages remain L3b's. |
| 2026-09-29 | v0.4 | L2 (#10) the runtime — renumbered at merge after 320's v0.3 | **New §5 The runtime** — the delegated act (D50) and what keeps it safe, the six routes, `configuration_id`, the parameter evaluation, a refresh (order, sharing, admission, caps, dependencies, deadlines, the stream), abort, the record. §4.4's `last_refresh` is live (the caller's own). |
| 2026-09-30 | v0.3 | 320 (#320) dependency guards | §3.1: the guard's other direction — a pipeline release, parameter set or transform template a dashboard or visualization pins can no longer be discarded or purged from under it (`pipeline.version.pinned`, `parameter.in_use`, `template.in_use`; [Versioning §3.5.3](versioning.md#353-the-reverse-arrows-into-other-families-320)); why the visualization's own LIVE-only guard is complete; restoring a DISCARDED dashboard version re-judges its dependencies. |
| 2026-09-29 | v0.2 | L1b (#10) the surfaces | **New §4 The surfaces** — the fourteen permission rows and the promoter's lens (§4.1), validate as an author verb (§4.2, owner ruling), what a source must declare for the save-time input check (§4.3), and the eleven MCP tools (§4.4); the REST routes are rest-api §22/§23. The status line names what L1b added. |
| 2026-09-29 | v0.1 | L1a (#10) the module | The two documents, their bounds and their lifecycle (§1–§3). |
