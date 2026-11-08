
# Dashboards — visualizations, dashboards and the authoring loop

A **visualization** is a versioned chart, table or KPI bound to named inputs: its renderer (`chart`,
`table` or `kpi`, each at version `"1"`), the renderer's `config`, the input contracts (named column lists), an optional
pinned script template that reshapes the inputs, the `bindings` that connect columns to `config`, and
saved test cases. It has no pipeline of its own — it is input-bound and reusable. A **dashboard** pins
exact pipeline versions as `sources`, places occurrences of pinned visualizations, maps each visualization
input to a source, and arranges them with groups, actions and controls on a 12-column grid. Both are
versioned exactly like parameter sets: draft → release → (discard / restore / purge / switch), the same
hash precondition (`body_hash` → `expected_hash`) and the same promoter lens. Every tool after a list
takes the **id** the list returns, never the name.

In `action_controls`, a control with `parameter` runs its action on that parameter's commit; without
`parameter` it is an explicit button (for example, labelled "Apply") in the filters pane, while
`parameter_scopes` maps parameter changes to the groups they invalidate.

## The authoring loop

Build in this order — each step's refusal names the path to fix.

1. **List the roots.** `visualizations_list` and `dashboards_list` with `prefix: ""`. Reuse a root; a name
   under a root nobody has used is refused `*.validation.new_root_requires_confirmation` until you ask the
   person and pass `confirm_new_root: true`. `test/` never needs it.
2. **Read the sources' schemas.** A draft dashboard source pins a live DRAFT or RELEASED read-only pipeline version; its
   parameters must all be bound (to a set parameter, or to a literal). Read the pipeline before you pin it.
   Published boards and release/import still require released dependencies throughout the graph.
   Preview an explicitly selected draft version in the dashboard workspace; no dependency release is needed.
   Draft edits, including nested pipelines and template imports, invalidate the mounted configuration.
   A SQL source's columns are what its pinned version RECORDED from its last run — `dashboards_validate` judges
   your visualization inputs against that record at save, so run the draft before handing back for release.
3. **Create the visualization** with `visualizations_create`: `renderer`, `inputs` (the columns you will
   feed it), `config`, and at least one test case. A chart's `config` and rules are "Charts" below; its
   `bindings` are derived from its encoding — omit them. A table or KPI binds a path that `config` already holds (`"values": []`) to a
   column of the single input (or of the transform's output). It lands as a
   DRAFT.
4. **Create the dashboard** with `dashboards_create`: `sources`, one `visualizations` occurrence per chart
   with every input mapped to a source, and a `layout.grid` that places each occurrence exactly once. A
   DRAFT visualization may be pinned at save.
5. **Edit** with `visualizations_update` / `dashboards_update`: send the WHOLE document and the
   `expected_hash` you read from the matching `_get` (or the previous write). A mismatch is
   `*.version.conflict` — re-read and rebase; never retry blindly.
6. **Validate** with `dashboards_validate` whenever a pinned pipeline, set or visualization may have
   changed: it judges the working version against the dependencies as they are now and answers `valid`
   with every failure. A SQL source is judged against the columns its release recorded from its last
   run; a release whose pipeline never ran records nothing (`not_observed`) and the input check is
   left to the runtime — execute the draft before relying on the verdict. `dashboards_get` shows each
   pin's status and each source's read-only verdict, and
   `last_refresh` — the latest refresh made under YOUR identity, which for a key is null today: no tool refreshes a
   dashboard (people do, in the UI, as themselves).
7. **Test every visualization you wrote** — the loop in "Proving a visualization" below. A
   visualization is released only on a GREEN test run of its exact current content: the release
   refuses `visualization.release.tests_missing` with no run (or an open one, `run_open`),
   `tests_stale` after any edit, `tests_red` on a red or incomplete run.
8. **Release and import are human verbs.** No tool releases anything, and no tool imports an exported
   envelope — `POST /api/v1/visualizations|dashboards/import` is a workspace-admin verb, and a
   promotion lands artifacts through the promotion wire, never through the import route. When the
   tests are GREEN, tell the person the visualization and the dashboard are ready for review.
9. **Clean up** a draft you abandoned with `visualizations_purge_draft` / `dashboards_purge_draft`
   (`expected_hash` required). A visualization draft a live dashboard pins is refused
   `visualization.version.pinned` — the refusal names the dashboards.

## Charts — one document for every host

A chart is ONE library-neutral document. The runtime converts it into the configuration of whichever charting
library the host page draws with (the product's own pages draw with Plotly), so you never write a library's
configuration, and one document serves every host. Its contract is the JSON Schema served without a key at
`/schemas/chart/1/bar.schema.json` (it includes `/schemas/chart/1/skeleton.schema.json`): check your document
against it before you write. Where this page and the schema disagree, the schema wins.

```json
{
  "renderer": { "kind": "chart", "version": "1" },
  "inputs": { "sales": { "columns": [ { "name": "region", "type": "STRING", "nullable": false },
                                      { "name": "product", "type": "STRING", "nullable": false },
                                      { "name": "amount", "type": "DECIMAL", "nullable": false } ] } },
  "config": {
    "title": { "text": "Sales by region" },
    "mark": { "type": "bar" },
    "encoding": {
      "x": { "field": "region", "type": "nominal", "sort": "-y" },
      "y": { "field": "amount", "type": "quantitative", "aggregate": "sum", "stack": "zero", "format": "currency(EUR)" },
      "color": { "field": "product", "type": "nominal", "sort": "ascending" }
    }
  }
}
```

(Plus `name`, `display_name` and `tests`, as for any visualization.) A chart reads exactly one input (or the one
output of its pinned script template), and every `field` names one of its columns.

- **Five bar forms.** `x` the band and `y` the value draws **vertical** bars; `y` the band and `x` the value,
  **horizontal** ones. A `color` field splits each band into series, and then the value's `stack` is required:
  `"zero"` **stacked** (above), `null` **grouped** side by side, `"normalize"` **normalized** to 100 % of each band
  (its `format` must be `percent`). Without `color`, leave `stack` out.
- **Nothing is left to a library's default.** The band's `sort` is required: `"ascending"`, `"descending"`, `null`
  (input order), an explicit list of values, or by each band's total — `"y"`/`"-y"` vertical, `"x"`/`"-x"`
  horizontal. The value's `aggregate` is required: `sum`, `mean`, `min`, `max`, or `count` (which takes no
  `field`). A series' `sort` is required too, in the same forms without the by-total ones.
- **Types.** The band is `nominal`, `ordinal` or `temporal` (a DATE or TIMESTAMP column); the value is
  `quantitative` over a numeric column; the band and series columns are not nullable. A temporal band is
  grouped and labelled in UTC, its `format` one of `date`, `month`, `year`, `datetime`.
- **Named number formats only:** `integer`, `decimal(n)` (n 0–6), `percent`, `compact`, `currency(XXX)` (an ISO
  currency code). The objects are closed — no format string, colour, font, size or library option; the host's
  theme styles the chart.
- **Text is plain.** A title or label is at most 200 characters, no control characters; `<` and `>` are allowed
  and drawn as text, never as markup. A channel's `title` absent is its default (the field name, `Sum of amount`
  for the value), `null` is none; without `config.title` the chart draws no title (the card heading names it).
- **Never write `bindings`.** The save derives them from the encoding (`{"amount": "amount", ...}`) and the read
  returns them; any other map is refused. A chart has no `presentation`.
- **Count bars with `mark_count`:** the distinct band × series cells with a value. The example with 2 regions ×
  2 products in its fixture has `mark_count` 4.

A chart refusal is `visualization.validation.config_schema_invalid` with `details.renderer: "chart"`; each
failure's `path` names the field. Its `details.reason` says what to fix:

| `details.reason` | Fix |
|---|---|
| `schema` | the document breaks the schema at `details.pointer` (`details.keyword` says how) — often a missing `sort`, `aggregate` or `stack`, or an unknown key |
| `chart.inputs.cardinality` / `chart.transform.output_unknown` | several inputs and no script template, so the chart cannot know its columns: keep one input, or pin a script template that combines them |
| `chart.field.unknown` / `chart.field.binary` / `chart.field.null_type` | name a column that exists and is neither BINARY nor NULL-typed |
| `chart.measure.not_numeric` | put a numeric column on the value |
| `chart.category.type_mismatch` | a `temporal` band needs a DATE or TIMESTAMP column — or make it `nominal` |
| `chart.category.nullable` / `chart.color.nullable` | use a non-nullable band or series column |
| `chart.scale.domain_normalize` / `chart.scale.domain_invalid` | drop `scale.domain` on a normalized stack; else make it increasing and include zero |
| `chart.format.normalize_percent` / `chart.format.currency_unknown` | format a normalized value `percent`; use a known currency code |
| `chart.bindings.mismatch` | delete `bindings` — the save derives them |
| `chart.unsupported.<field>` | the library cannot draw that field: drop it |

A write answers `warnings` beside the version — `[]` when clean, else `{code: "chart.degraded.<field>", path,
library}` for a field the library draws only approximately. A warning does not refuse the save; read it, and
check that field in the preview.

## Proving a visualization — the test loop

A visualization's `tests.cases` are its proof: fixture rows for every input and the assertions a
reader checks (`rendered`, `mark_count` — a chart's bars, `no_console_errors`, `text_visible`, `no_data`,
`value_visible`). The server's mechanical feasibility check scans configuration strings and bound
strings or INTEGER/DECIMAL scalar numeric text (for example, `42` and `10.5`) by substring, without
numeric normalization. Locale, currency, percent and rounded display text still need your visual
check in the preview; the mechanical rendered state is `not_available`. Run the cases against the
real renderer before you hand the work over:

1. **Start** with `visualizations_test_start` (`id`). It tests the WORKING version (the draft) and
   answers `preview_url`, the case names and `expires_at`. Any edit afterwards voids the session —
   finish editing first.
2. **Open `preview_url` in a browser** (Playwright or any headless browser). The page needs no
   login: the token inside the URL is its only credential, so never paste the URL anywhere else.
   It renders every case from its saved fixtures through the same runtime the dashboards use — no
   pipeline runs, no live data. Each case is a `section[data-dp-case="<name>"]`; it gains
   `data-dp-ready="true"` once mounted (or `data-dp-error="<code>"`), and the chart's status chip
   is `.dp-dashboard-status[data-dp-state]` (`success` or `ready` once rendered; `no-data`; `error`). Check
   each case's assertions there. Add `&theme=light` or `&theme=dark` to see both themes.
3. **Submit** with `visualizations_test_submit` — the SAME key that started: `id`, `session_id`,
   one `{name, verdict: "green" | "red", notes}` per case, and optionally the `environment` you
   looked in (`theme`, `viewport`, `browser`, `locale`, `renderer_version`). The server re-runs its
   own mechanical test and answers the status: GREEN only when every verdict is green and that
   test passes; a missing verdict is INCOMPLETE. The preview link stops working.
4. **Upload one screenshot** when the answer is GREEN: `upload` names the `url`, the `header`
   (`DP-Upload-Token`) and the single-use `token`. POST the PNG or WebP bytes (raw body, `Content-Type:
   image/png` or `image/webp`, at most 4 MiB) to `url` with the token in that header — from your
   browser or any HTTP client; your MCP key is not used there. This is the ONE REST call an agent
   makes, and the server audits it. The token works once; a second upload is refused.
   The screenshot is review evidence for the person, not a server check.
5. **Hand over.** Tell the person the run is GREEN and the visualization awaits their release in
   the UI. A RED or INCOMPLETE run: fix the visualization (or the case), then start a new session.

## What the refusals mean

- `visualization.validation.body_invalid` / `dashboard.validation.body_invalid` — the document's shape:
  an unknown key, a wrong JSON type, a missing key, or a collection over its bound. `details.path` names it.
- `visualization.validation.binding_unbound` — a binding path that is not in `config`, or a column the
  input (or the transform's output) does not have.
- `visualization.validation.config_schema_invalid` — the renderer's configuration: for a chart, the v1
  JSON Schema (`details.keyword` and `details.pointer`) or a chart save rule (`details.reason`, a `chart.*` code —
  the table in "Charts" above).
- `dashboard.validation.input_unbound` — a pinned visualization input no source feeds.
- `dashboard.validation.parameter_unbound` — a required pipeline parameter left unbound.
- `dashboard.validation.source_not_released` / `source_not_read_only` — release requires a released pipeline; every source must stay transitively read-only.
- `dashboard.validation.layout_invalid` — an occurrence or control not placed exactly once.
- `dashboard.validation.dependency_not_found` — a pin this workspace does not hold.
- `visualization.test.session_not_found` — the session is not yours (another key started it), or
  the id or token is wrong; `visualization.test.session_expired` — it was already submitted, it
  expired, or the visualization changed since it started: start a new session.

## Rules that hold everywhere here

- A name is a folder path, lower-case, 2–10 segments; it is never renamed.
- The server stores `config` verbatim and never executes it; `presentation.tokens` (table and KPI)
  names theme tokens, never colours.
- Validation is exhaustive: fix every failure the list names, then write once.
