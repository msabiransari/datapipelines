# Dashboards

**Status:** v0.37 — the board-width breakpoint on the composite host, with a 640 px default (#412) beside the read-only rule is stated as declarative (§5.1, #463), then draft dashboard dependencies (#459), then the Release dialogs post the draft hash they read (§7, #416) beside numeric bound-value assertion feasibility (§3.4, #377) beside the visualizations workspace's tab switches on the shared history helper, the parameter-set decision (§7, #426) beside the pipeline editor runs the shared tab core (§7, #420) beside the Versions tables' created/released relative with the UTC stamp on hover (§7, #422) beside Back/Forward across the dashboards workspace's tab switches (§7, #402) beside the visualizations workspace (§7, #399) beside the dashboards workspace (§5.2's `version`, §7's workspace, #400; #409 closes with it) beside the
board's small-slot margins and its breakpoint collapse (§6.2, §6.3, #386/#387), 328's release-record judgement (§4.3)
permissions (§4, lane L1b); the transfer routes and their limits' honest contract (§3.3, lanes L1c/L1c-b/L1c-c: the
import's atomicity, the RELEASE rules on a landing, the aggregate count ceiling, the wire's per-family arms); the server
runtime (§5, lane L2; #343's released pins and stream authority); the client runtime (§6, lanes L3a/L3a-b/L3a-c); the
first-party pages (§7, lane L3b, the draft preview #369); the tests' backend — sessions, capabilities, the mechanical
check and the release gate — is §3.4 (lane L4a, #352), and its workflow on the wire — the session routes, the preview
page, the screenshot upload and the two test tools — §3.4.1 (lane L4b, #353). The `dashboard` key kind (L5) is §4.1/§5.8/§6.5.
**Owner:** datapipelines.co core
**Depends on:** [Versioning](versioning.md) (§3.5 — the lifecycle table), [Pipeline Contract](pipeline-contract.md)
(§13.22, §13.23 — the codes), [Metadata DB](metadata-db.md) (§4.28–§4.35 — the tables), [Enumerations](enums.md)
(§31–§38), [Configuration](configuration.md) (§3.33, §3.34 — the bounds and the runtime's numbers), [REST API](rest-api.md) (§22, §23 — the routes),
[MCP Server](mcp-server.md) (§6.2.50–§6.2.62 — the tools), [Auth](auth.md) (§7.6 — the permissions)
**Design:** the [dashboard implementation spec](superpowers/specs/2026-09-28-dashboard-implementation-spec.md) and
the [design record](superpowers/specs/2026-09-25-dashboard-authoring-design-draft.md) (decisions D1–D63)
**Last updated:** 2026-10-09

A dashboard presents pipeline results. Draft development admits draft dependencies; published boards require released dependencies. It is built from two versioned artifacts: **visualizations** —
a chart, table or KPI bound to named inputs, reusable across dashboards — and **dashboards**, which pin exact pipeline versions as sources, map their results onto visualization inputs, and arrange visualizations, groups, actions and
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
| `sources[]` | A pinned live pipeline version (DRAFT or RELEASED during authoring; RELEASED at release/import, `dashboard.validation.source_not_released`) that must pass the read-only rule, child pipelines included (`dashboard.validation.source_not_read_only`, D38). `parameters` binds each pipeline parameter EITHER to a set parameter (`{"parameter": …}`) OR to a literal (`{"value": …}` — a scalar or a list of scalars); every REQUIRED pipeline parameter is bound, here or by an outgoing override (`dashboard.validation.parameter_unbound`) |
| `visualizations[]` | An occurrence of a pinned visualization version (a DRAFT is accepted here); `inputs` maps EVERY input of that visualization to a source (`dashboard.validation.input_unbound`), whose caller output columns must supply each input column by name and type (`dashboard.validation.input_contract_mismatch`, naming the column). `timeout_seconds` is 1–900 |
| `groups[]` | Containers for composition and action scope, never a name or evaluation scope (D47). A member is an occurrence, a group, an action control or a set parameter; nothing belongs to two groups and no group contains itself |
| `actions[]` | A refresh action: `scope` `all` (no targets) or `targets` with a non-empty list of occurrence names (D55, D57); `initial` marks the actions bootstrap invokes (R22). `dashboard.validation.empty_targets`, `dashboard.validation.target_not_visualization` |
| `action_controls[]` | A control bound to an `action`. Without `parameter` it is a button — an explicit action; with `parameter` it binds the committed change of that parameter's control — an automatic action (D42), refused on a PARENT parameter, one with dependents in the pinned set (`dashboard.validation.parent_action_binding`, R2) |
| `parameter_scopes` | Parameter → the groups its change makes stale (D41). A declared scope must include every group that consumes the parameter — directly, or through a parameter that depends on it (`dashboard.validation.scope_omits_consumer`); an absent parameter is unscoped |
| `parameter_state` | Hide/show and enable/disable overrides, dashboard-wide and per parameter: `inherit`, `force_true`, `force_false` ([Enumerations §36](enums.md), D20). A forced-hidden parameter's value is still submitted (D23) |
| `outgoing_overrides` | Per source, per pipeline parameter, the literal the pipeline receives whatever the control showed (D23) |
| `layout` | The whole set's region (`left`, `right`, `top`, `bottom`), per-parameter placements into a group or a region, and the `grid` in 12-column units: every occurrence has exactly one grid item; an action control is placed exactly once — a grid item or a group membership; a group at most once; each item fits the 12 columns. Below `breakpoint_px` (640 when absent), measured on the board's own host width, every item spans the full width in grid order; a 0-wide host holds the stored grid until it is laid out (`dashboard.validation.layout_invalid`) |
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
  transform pin is valid here and in an explicitly selected draft dashboard; published runtime requires RELEASED pins), every projected bound
  column present in every row, and static assertion feasibility (`trace_count` against `config.data.length`,
  `no_data` against zero produced rows, `text_visible`/`value_visible` strings present in the configuration or
  the bound values). Bound strings and INTEGER/DECIMAL values participate by their existing scalar text
  (for example, `42` and `10.5`), using substring matching without numeric normalization. Locale, currency,
  percent and rounded display text still need the agent's visual check in the preview. The rendered-state
  check records `not_available` today — a headless render check is a later
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
addressed by id (P24); a multi-segment name never travels in a path; a person reads both and drives their lifecycle verbs from the first-party pages (§7). Every body a surface
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
  latest successful run after the draft's last edit (`caller_output_json`; D1). A release with no qualifying run
  records nothing, and answers `caller_output = not_observed` — **the one remaining skip**: the save-time
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

- **Sources are read-only.** Every source pins a live pipeline that passes the read-only rule (D38),
  transitively through its child pipelines. It is checked at save, at release, at every configuration read AND at
  every refresh and against every exact loaded child body immediately before execution; a pin that stops holding is `dashboard.runtime.dependency_missing` (409), never a run.
  The rule is declarative: it admits a pipeline by its nodes' declared types, output targets and sources. It does
  not run the source's statements on a read-only connection or transaction. Database-enforced read-only is
  [#463](https://github.com/msabiransari/datapipelines/issues/463); this matters most for a draft preview, whose
  source bodies are unreviewed.
- **Lifecycle follows the dashboard.** A released dashboard requires RELEASED dependencies. An explicitly selected
  draft dashboard admits live DRAFT or RELEASED visualizations, parameter sets, source and nested pipelines, and
  node, selector, transform and imported templates. Missing or DISCARDED references refuse in both modes. A missing or changed pin is
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

The dashboard served is its CURRENT RELEASED version — and since #369 the four runtime routes above take an optional
`version` query parameter naming a DRAFT or RELEASED version (a bounded positive integer, the execute route's 400
otherwise); absent means exactly today's read. The named version must resolve for the caller — absent, DISCARDED, or
hidden under a narrowing lens is the family 404 naming the version they named — and a `dashboard` key never names one:
the version routes are the session-authenticated workspace's ([§7](#7-the-first-party-pages-l3b)), and a version
parameter would be a second credential for the same principal (refused `dashboard.key.kind_refused` before anything is
looked up). An explicitly selected DRAFT dashboard admits live DRAFT or RELEASED dependencies throughout its graph
(#459, owner requirement superseding #369 R1). Released dashboards retain the RELEASED-only policy. Missing,
DISCARDED or unsafe dependencies refuse `dashboard.runtime.dependency_missing`. A dashboard with no release is absent
from the default released runtime read; its explicitly selected draft remains available to an authorized session.

### 5.3 The configuration

`configuration_id` is `sha256(dashboard id | version | body_hash | every pinned dependency's kind, name, version and
body_hash)` — the pinned visualizations, the parameter set and each source pipeline. For a draft this also walks
nested pipeline bodies, node/selector/transform templates and their transitive imports; a same-version draft edit
changes the identity. Every later call sends it; a mismatch
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
identical body. A late abort changes nothing on a refresh that delivered (#370): the engine's ending consults the
work's outcome, not the flag's timing — a flag raised after every target delivered `ok`/no-data leaves the refresh
DONE (its delivered frames stand; a chip already settled to success stays there), while an abort that actually
interrupted the work — targets unrecorded or failed — ends it ABORTED as before.

### 5.7 The record

`dashboard_refreshes` and `dashboard_refresh_executions` ([Metadata DB §4.34–§4.35](metadata-db.md)) hold every refresh that
was admitted. The row is closed however the refresh ends — the client gone, the deadline passed, the process stopping —
because the terminal work runs non-cancellably at the one point that owns it; a refresh an instance crash left
`RUNNING` is closed `TIMED_OUT` by the sweeper ([§8.4](metadata-db.md)). Finished refreshes are retained like execution
events. One `dashboard.refresh` audit row per refresh is written awaited at its end ([Enums §15](enums.md)).

The events pane links a refresh's executions only for a person who may read executions: a promoter refreshes a
dashboard she can read but holds no `execution.read`, so her refresh names no execution.

### 5.8 What is not here

The visualization tests' remaining human surfaces (L4). The `dashboard` key kind IS here now (L5, #367): an external
application's backend holds a `dashboard` key and proxies the runtime routes — §6.5 is the wire contract and
[Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings) the kind's specification. No MCP tool refreshes a
dashboard. The first-party pages are §7 (L3b, since #400 the workspace); the dashboard draft preview IS here now (#369, §5.2's
`version` parameter — since #400 a 303 redirect onto the workspace's named-version view).

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
  dashboard: { id, version: "released" | 2 },  // the preview's named version (§5.2, #369)
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

`version` is `"released"` — the current release and the first-party page's own DEFAULT (the workspace writes no
version attribute unless the URL named one) — or, since #369, a positive INTEGER naming the version a named-version
view is looking at (§5.2; the server refuses a version the caller may not see, and a `dashboard` key never names one). Anything else is refused with `init.version_unsupported`. Re-initialising
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
| `mountLayout(layout) → Promise` | build the grid from the system layout; the first-party adapter's rows are each one `--dashboard-row-unit` (app.css, default `var(--space-20)` = 5rem), so a slot of `h` rows is `h` units plus the gaps between them tall and a figure's height is its slot's; the empty default slot takes no row (`dashboards.css`); below `layout.breakpoint_px` (640 when absent; clamped to the server's 1–10 000, a non-number is the default), measured on the composite host's content width, the adapter places every item across all 12 columns, stacked in grid order (by `y`, then `x`) with its own row span — the row unit unchanged. A 0-wide host holds the stored grid until layout; the host's `ResizeObserver` re-places on reveal and at a narrow/wide crossing, resizing mounted renderers on crossings, and disconnects on `dispose`; without `ResizeObserver` the stored grid holds. The gap is the `--space-4` token | awaited before anything mounts |
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
  names it does not know are ignored. **The size defaults (#386):** Plotly's own margins (about 100 px
  top, 80 px elsewhere) would consume a 2-row slot (176 px), so the adapter starts every figure from a
  compact frame — `margin` `{l: 8, r: 8, t: 8, b: 8, pad: 0}`, `t: 36` when the figure has a title —
  and sets `automargin: true` on `xaxis`, `yaxis` and every numbered axis the layout declares, so
  each axis's tick labels and title take what they need and no more. **Two precedences, opposite:**
  the size defaults sit UNDER the author — a stored `layout.margin` key or a stored `automargin`
  (`false` included) wins, key by key; the theme's colours sit OVER the author — a stored
  `paper_bgcolor` or grid colour loses to the token at render. Neither touches the other's keys.
- **Table.** `columns[]` (`label`, `values` — the path the binding fills, `format`, `align`),
  `page_size` capping the rows. Every cell is `textContent`.
- **KPI.** `label`, `value` (the bound path), `format` (`number|integer|percent|currency`), `unit`,
  and an optional `comparison` bound through the same map. A zero renders — a zero is a value, not
  `no-data`.

### 6.4 The two bundles

Plotly is vendored as two self-contained custom bundles (D63): `plotly-2d.min.js` (scatter, bar, pie,
histogram, box, heatmap — the default) and `plotly-3d.min.js` (those plus scatter3d, surface, mesh3d —
WebGL). The SERVER's `runtime/config` carries `renderer.bundle: "2d" | "3d"` derived from pinned trace types.
Standalone previews/embeds load exactly one matching bundle and declare it with `data-dp-plotly-bundle`.
The runtime refuses two declarations, unsupported renderers, incompatible versions and a 3D config on a 2D bundle.

The signed-in shell (#460) lazily loads the existing 3D superset once and reuses it for 2D and 3D artifacts.
Artifact/catalog/version links preserve the rail through main-content navigation. Destination validation and
asset preparation precede outgoing disposal. Cached history contains inert markup and mounts once; pipeline
observation detaches without cancelling execution, while dashboard disposal retains refresh cancellation.
The loader establishes the existing CSP style marker before executing Plotly and adds no CSP allowance.

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

The app's own surfaces over the runtime: the flat catalog (`GET /dashboards`), the tabbed,
version-explicit workspace (`GET /dashboards/{id}?version=&tab=`), the sidebar's Dashboards
branch and the events pane. Every handler is a session-authenticated GET declaring
`dashboard.read` — D50 makes every reader an executor, and the pages must not declare
`dashboard.execute` to "simplify" — except the events pane's fragment, which floors at
`dashboard.execute` with the refreshes route it mirrors, the Keys tab's fragment, which
floors at `dashboard.key.bind` (the lowest row whose cells match the tab's visibility), and
the lifecycle dialogs, which floor at their verbs' own permissions
([Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)'s Surfaces cells carry the routes).

**The catalog and the sidebar branch (D58, #400).** `/dashboards` is the flat CATALOG — the
pipelines catalog's shape, one row per dashboard the caller may read (or the matches of
`?q=`), each row linking the workspace. The L3b tree page is retired: the folder tree is the
SIDEBAR's navigating-tree branch (the link to the catalog, a separate toggle, the lazy panel)
— using the reusable REST search-and-tree component since #460 (ui-screens §3.4).
Initialization loads closed top-level nodes only; expansion exhausts bounded pages of immediate
children. Name search returns every matching path with ancestors, initially expanded; clear closes
all folders back to the normal top level. Both browse and search filter through the service's lens
before metadata projection. The flat catalog retains its existing server-rendered search contract.
A row — catalog or tree leaf —
opens the workspace through prepared main-content navigation (#460, §6.4). A draft-only dashboard's row says so (`draft v1 pending
release`): the list page's promise, kept as a ROW state.

**The workspace.** One URL names one dashboard, one viewed version and one tab. The
resolution is the family's: the working-version read through the lens first (absent,
foreign or lens-hidden is the family's 404); `?version=N` through
`findServedVersion` (#369's rule — a DRAFT only to the everything-lens, DISCARDED to
nobody, anything else the family's 404 NAMING the version); the default is the current
RELEASED version. A version that resolves but cannot run (a pin that does not hold, a
source no longer read-only) is the Board pane's REFUSAL state — the code and sentence the
runtime's read refused with, the NAME still in the `h1`, never a blank pane, and no bundle
loads. **No served release and none named is NOT an error (#409): the choose-a-version
state** — the heading shows the dashboard's name, the draft (when the caller's lens shows
one) is offered with its own link, and the sentence says there is no release yet. The old
board page answered an empty `h1` and "Dashboard '<uuid>' not found." for a dashboard the
tree had just linked; it does not any more. The five tabs:

- **Board** — today's board page exactly: the ONE bundle the server chose for the VIEWED
  version declared on its script tag (§6.4; the same read the client's bootstrap performs),
  the glue (`static/js/dashboards-page.js`) UNCHANGED — it reads the dashboard id and, when
  the URL named a version, the integer on `data-dp-dashboard-version` (the default writes
  none, so its `init` stays `version: "released"`) — the refusal region, and the events pane
  beside the board. Navigation onto it is never boosted; disposal stays the
  `htmx:beforeHistorySave` hook.
- **Overview** — the viewed version's definition, read-only: the sources with their pinned
  pipeline versions and statuses, the parameter set, the pinned visualizations with each
  pin's status, and the layout summary. DRAFT pins are available during draft preview and
  must be released before publication. No authoring control anywhere: the browser
  never authors a dashboard (#396) — agents author over MCP, and a person releases from the
  Versions tab.
- **Refreshes** — the events pane full width; the board pane keeps its own beside the board.
- **Versions** — the admitted history (newest first, served/draft/discarded markers; created and
  released relative in the cell, absolute UTC on hover — the keys page's shape, #422) and the
  lifecycle verbs' dialogs in the pipeline dialogs' shape: Release (the ONE D61 consent —
  "Also release these N draft visualizations" — cascading a DRAFT visualization pin through
  its own gate in the dashboard's transaction; the set and the sources have no cascade and
  refuse as before; the form posts `bodyHash`, the hash of the draft the dialog read, and the
  release is made AT it — a draft changed after the dialog opened is refused
  `dashboard.version.conflict`, never released silently, #416; #397's generic dependency-aware
  dialog is HELD and is NOT this), Purge
  draft, Discard (naming where the pointer falls back, D60), Restore, Purge version, Switch
  (make current), Purge dashboard (typed confirm, sole-draft only). Every dialog's POST
  calls the SAME service the REST route wires, is session-only, is audited exactly as the
  REST route audits it (#332: each cascaded visualization's own event first, then the
  dashboard's own), and answers `HX-Redirect` back onto this tab with a flash toast — a
  lifecycle change moves the version set the whole page reads. The typed confirm is checked
  BEFORE the service; refusals ride the family's real 4xx.
- **Keys** — the `dashboard` keys bound to this dashboard (the folder itself or an
  ancestor), read-only, each row linking the Keys page's binding editor. Rendered — and
  fetched — only for a caller with `dashboard.key.bind`.

Tab state is IN-PAGE (`static/js/dashboards/workspace.js` over the SHARED tab core
`static/js/workspace/tabs.js`, the one admission and transition rule; the pipeline editor's
`pipeline-editor/tabs.js` runs the same machine since #420): a tab switch swaps no version and cancels no poll, the
URL's `?tab=` moves with a pushed history entry of the workspace's own (#402,
`static/js/workspace/history.js`, tab only — version switches use prepared main-content navigation) so
Back/Forward re-select the tab in page (a cached restore re-wires the strip once and shows the
tab it left), and lazy tabs (Overview,
Refreshes, Versions, Keys) load their fragment once, on the tab's first activation — a
hidden tab causes no fetch. The Board pane's reveal runs the runtime instance's OWN
`resize()`, and the composite host observer places the grid when a `?tab=versions` deep link
reveals its 0-wide board, so a chart that booted while hidden re-fits its slot.
Every version switch preserves the rail through prepared main-content navigation (#460, the compatible superset bundle), and
the version selector over the admitted history renders those deep links. **The draft
preview is the named-version view**: `/dashboards/{id}/preview?version=N` answers 303 onto
`/dashboards/{id}?version=N&tab=board`, so #369's deep links land in the workspace's Board
tab viewing exactly that version — the banner became the viewed-version chip the top bar
prints (the truth about what is on screen, "v2 · draft", never "latest release").

**The visualizations workspace (#399).** `/visualizations` is the flat catalog (`q` searches name,
display name and description) and the rail's Visualizations branch is the same lazy tree with the
same search box. `/visualizations/{id}?version=&tab=` is the version-explicit workspace on
`visualization.read` alone — a DISCARDED or lens-hidden version is the family's 404, a malformed
version a 400 that never echoes it. Its five tabs ([ui-screens §4.24](ui-screens.md)):

- **Preview (test fixtures)** — the default: the viewed version's saved test cases in the
  runtime's FIXTURE mode (§3.4.1's capability page's mount and builder — `PreviewViews.page` writes
  the same per-case configuration, keyed by the version), one case at a time. It renders FIXTURES
  only; live data runs inside a dashboard, under `dashboard.execute` — the page never executes.
- **Overview** — the version's definition, read-only (the renderer and its bundle, the inputs,
  the transform pin and its status, the test-case count, the body hash).
- **Evidence** — the version's test runs (§3.4), newest first, capped at 100 and saying so; a
  run's screenshot through the existing screenshot route, else "no screenshot".
- **Used by** — the dashboard versions pinning this visualization, read through the caller's
  dashboard lens, each linking its dashboard's workspace.
- **Versions** — the history (created and released relative in the cell, absolute UTC on hover,
  #422) and the lifecycle verbs' dialogs: Release (the refusals before the
  button, in the service's order — §3.1's test case and transform pin, then §3.4's evidence gate —
  and the ONE `release_pinned_templates` consent for a DRAFT transform pin, D61; the form posts
  `body_hash`, the hash of the draft the dialog read, and the release is made AT it — a draft
  changed after the dialog opened is refused `visualization.version.conflict`, except that §3.4's
  evidence gate judges the CURRENT body and answers first when the new draft has no green run,
  #416), Purge draft,
  Discard (naming the D60 fallback), Restore, Purge version, Switch, Purge visualization (typed
  confirm, sole-draft only), and the Export link (§3.3). Every POST calls the SAME service the REST
  route wires, is session-only, is audited as the REST route audits it (a cascaded template's own
  event first) and answers `HX-Redirect` back onto this tab.

Every version switch uses prepared main-content navigation (§6.4), retaining the rail and the
single compatible Plotly bundle. The choose-a-version state mounts no chart.

The visualizations workspace's tab switches go through the same history helper as the dashboards'
(`static/js/workspace/history.js`, family `visualizations`, tab only — the pushed-entry wording of §7's
dashboards workspace above; the shared pane glue `workspace/panes.js` carries it, so Back/Forward
re-select the tab in page and a cached restore re-wires the strip once, #426). The parameter-set
workspace does not adopt tab-only history: its sections use prepared main-content links
(`?version=&tab=history` — the History arm renders no live form), there is no in-page
switch to make navigable, and the browser's own history already carries every section and version
change (#426). The templates workspace adopts the helper after #398's rework lands (follow-up to #426).

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-10-09 | v0.37 | #412 the layout breakpoint measures the composite host, default 640 — renumbered in recovery after #460's v0.36 | **§2.2 and §6.2:** `breakpoint_px` is judged against the composite host's width; a zero-width host holds its stored grid until layout, and the `ResizeObserver` re-places on reveal or a crossing. The default is 640 so a 694 px board at a 1280 px viewport keeps its grid, while opening the Pipelines tree can narrow the board below the threshold. The client, KDoc and implementation spec use the same rule. |
| 2026-10-06 | v0.36 | #460 reusable REST navigation | §6.4/§7: one lazy compatible bundle in the signed-in shell, explicit per-container mounts and prepared navigation; standalone bundle choice and refresh cancellation preserved. |
| 2026-10-06 | v0.35 | 459 merge follow-up | "Sources are read-only" now says what the rule checks: declared node types, output targets and sources, enforced at the listed points. It is not a read-only database connection, and with draft previews running unreviewed source bodies that difference matters. Database-enforced read-only is #463. "Last updated" brought current (the lane's v0.34 row left it at 2026-10-04). |
| 2026-10-05 | v0.34 | #459 draft dashboard dependencies | Draft save and explicitly selected draft runtime admit live draft dependencies transitively. Released runtime, release and import remain strict. Configuration identity includes mutable nested pipeline and template/import content; each loaded source/child is admitted for lifecycle and read-only execution. Overview explains draft availability and release requirements. Supersedes #369 R1 for draft development. |
| 2026-10-04 | v0.33 | #416 the Release dialogs post the hash they read | **§7:** the dashboards and visualizations workspaces' Release dialog forms carry a hidden hash of the draft the dialog read (`bodyHash`; `body_hash` for visualizations), the POST requires it (a missing one is a 400 at binding) and releases AT it. Before, the POST re-read the draft and released whatever hash it found, so a draft changed after the dialog opened went live unseen. A stale hash is `*.version.conflict` (409) and, for a cascading release, rolls the cascade back (§3.2). The same change covers the pipelines and templates dialogs ([UI Screens §4.3d](ui-screens.md)). No service, REST or matrix change. |
| 2026-10-03 | v0.32 | #377 numeric bound-value assertion feasibility | **§3.4:** bound INTEGER/DECIMAL scalar text joins strings in the static substring scan; `42` and `10.5` can satisfy `text_visible`/`value_visible`. Browser formatting remains the agent's preview check; rendered state remains `not_available`. |
| 2026-10-03 | v0.31 | #426 the visualizations workspace on the shared history helper — renumbered at merge after 420's v0.30 | **§7:** the visualizations workspace's tab switches push tab-only `visualizations` entries through `workspace/history.js` (via `workspace/panes.js`), Back/Forward re-select in page, a restored root re-wires once; the parameter-set workspace stays on full-document section links — the helper is not adopted there (decision recorded); the templates workspace follows after #398's rework. |
| 2026-10-03 | v0.30 | #420 the pipeline editor re-adopts the shared tab core | **§7:** the tab-core sentence no longer says the editor keeps its own copy — `pipeline-editor/tabs.js` delegates admission and transition to `workspace/tabs.js` (its 18 getters keep reading `this`, so Alpine's proxy tracks them), so the dashboards, visualizations and pipeline workspaces run one machine. No dashboards behaviour changes. |
| 2026-10-03 | v0.29 | #422 the Versions tables' time shape | **§7:** the dashboards and visualizations Versions tabs render created/released relative in the cell with the absolute UTC stamp on `title` (the keys page's shape, computed in the model's fill); the dashboards partial gains its own `dp-versions-pane` class so the framed table scrolls inside its viewport at 390 px. |
| 2026-10-03 | v0.28 | #402 Back/Forward across the dashboards workspace's tab switches | **§7:** a tab switch PUSHES a tab-only entry through `workspace/history.js` (it was `replaceState`, so Back left the page); Back/Forward re-select in page through the helper's one window listener; a cached restore re-wires the strip once and re-paints the tab it left. |
| 2026-10-02 | v0.27 | #399 the visualizations workspace (#396) | **§4:** "There is no UI page yet (L3)" leaves — a person reads both families and drives their lifecycle verbs from §7's pages. **§7:** the visualizations workspace subsection — the catalog and the searchable sidebar branch, and the version-explicit workspace's five tabs: Preview (test FIXTURES only, §3.4.1's builder keyed by the version), Overview, Evidence (the 100-run cap; the screenshot or "no screenshot"), Used by (through the dashboard lens) and Versions (the seven dialogs, the one `release_pinned_templates` consent, the Export link). No route, permission, code or lifecycle rule changed. |
| 2026-10-02 | v0.26 | #400 the dashboards workspace (#409 closes with it) | **§5.2:** the version routes' page is the session workspace (the preview route a 303 redirect onto it). **§5.8:** the draft preview named where it lives now. **§6.1:** `init`'s `"released"` is the workspace's DEFAULT (no version attribute unless the URL named one). **§7 rewritten:** the flat catalog (`GET /dashboards`, the tree retired to the sidebar, the branch gaining the Pipelines branch's search), the tabbed version-explicit workspace (`?version=&tab=`; Board = the L3b page exactly with the glue unchanged; Overview read-only with the pins' statuses and the R1 hint; Refreshes; Versions with the seven lifecycle dialogs — the ONE D61 consent, no #397; Keys on `dashboard.key.bind`), #409's choose-a-version state (the name, the draft, "no release yet" — never the empty `h1` and the "not found"), the 303 preview redirect, in-page tab state over the shared `workspace/tabs.js` core, full-navigation version switches (the one-bundle rule). No MCP tool, no migration, no new permission row. |
| 2026-10-02 | v0.25 | 386 (#386, #387) the small-slot margins and the breakpoint collapse | **§6.3:** the Plotly adapter's size defaults — a compact margin (`t` opens for a title only) and `automargin` on the axes, UNDER the author's stored `margin`/`automargin` key by key, beside the theme's colours OVER the author (both precedences side by side); a 2-row slot keeps a plot area of at least half its height (measured by `DashboardGridRowUnitBrowserTest`). **§6.2:** the first-party adapter implements §2.2's promise — below `breakpoint_px` (768 when absent) every item spans the 12 columns in grid order with its own row span, the row unit kept; a viewport crossing re-places and resizes; the grid gap is the `--space-4` token. §2.2 is unchanged: its sentence is now true. |
| 2026-10-02 | v0.24 | #369 the dashboard draft preview — renumbered at merge (main sat at v0.23) | **§5.2:** the four runtime routes take an optional `version` query parameter naming a DRAFT or RELEASED version (R2, owner-confirmed): absent = the current RELEASED version unchanged; a value is a bounded positive integer, must resolve for the caller (the family 404 naming it), and is refused to a `dashboard` key (`dashboard.key.kind_refused` — the version routes are the session page's). R1 (owner-confirmed): the pin rule is RELEASED-only on a draft exactly as on a release — a draft pinning a DRAFT pin is `dashboard.runtime.dependency_missing`/`not_released` naming the pin, the message carrying the release hint. **§6.1:** `init` admits `"released"` or a positive integer; the integer rides `?version=N` on every runtime path the instance builds. **§7:** the draft preview page (`GET /dashboards/{id}/preview?version=N`) — the board template for a named version, the banner with the way back to the released view, the version on the `data-dp-dashboard-version` attribute channel, refusals in place, the promoter's 404, session-only. No MCP tool, no migration, no new permission row. |
| 2026-10-02 | v0.23 | 328 (#328) a SQL source is judged against the release's record | **§4.3 rewritten:** a SQL caller node's columns are the release's RECORD (`caller_output_json`, pipeline-contract §3.3.1) — judged at save like a declared contract; `not_observed` (no qualifying run at release) is the one remaining skip, left to the runtime (L2). The record outlives the datasource: read from the release, never from the live schema. No tool, route, permission or error-shape change (`dashboards_validate`'s input is unchanged). |
| 2026-10-02 | v0.22 | 371 (#371) the board grid has a row unit | **§6.2:** the first-party adapter's grid rows are each `--dashboard-row-unit` (app.css, `var(--space-20)`, 5rem) — a FIXED `grid-auto-rows` track, so a slot of `h` rows is `h` units plus its gaps tall and a Plotly figure fills its slot instead of collapsing (measured on the base: 34 px for a 4-row slot). The adapter's empty default slot is hidden, so the board is exactly its grid's rows tall. One unscoped rule serves the board page and the visualization preview; #353's preview-scoped copy is retired. Routes, permissions, roles: none changed. |
| 2026-10-02 | v0.21 | 370 (#370) the late-abort ending | **§5.6:** a late abort changes nothing on a refresh that delivered — the ending consults the work's outcome, not the flag's timing: every target delivered `ok`/no-data ends the refresh DONE with its frames standing, an abort that actually interrupted the work still ends ABORTED. |
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
