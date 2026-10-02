
# Dashboards — visualizations, dashboards and the authoring loop

A **visualization** is a versioned chart, table or KPI bound to named inputs: its renderer (`plotly`,
`table` or `kpi`), the renderer's native `config`, the input contracts (named column lists), an optional
pinned transform template that reshapes the inputs, the `bindings` that inject columns into `config`, and
saved test cases. It has no pipeline of its own — it is input-bound and reusable. A **dashboard** pins
released pipelines as `sources`, places occurrences of pinned visualizations, maps each visualization
input to a source, and arranges them with groups, actions and controls on a 12-column grid. Both are
versioned exactly like parameter sets: draft → release → (discard / restore / purge / switch), the same
hash precondition (`body_hash` → `expected_hash`) and the same promoter lens. Every tool after a list
takes the **id** the list returns, never the name.

## The authoring loop

Build in this order — each step's refusal names the path to fix.

1. **List the roots.** `visualizations_list` and `dashboards_list` with `prefix: ""`. Reuse a root; a name
   under a root nobody has used is refused `*.validation.new_root_requires_confirmation` until you ask the
   person and pass `confirm_new_root: true`. `test/` never needs it.
2. **Read the sources' schemas.** A dashboard source pins a RELEASED, read-only pipeline release; its
   parameters must all be bound (to a set parameter, or to a literal). Read the pipeline before you pin it.
3. **Create the visualization** with `visualizations_create`: `renderer`, `inputs` (the columns you will
   feed it), `config` with every path you bind already present (`"x": []`), `bindings` from a path to a
   column of the single input (or of the transform's output), and at least one test case. It lands as a
   DRAFT.
4. **Create the dashboard** with `dashboards_create`: `sources`, one `visualizations` occurrence per chart
   with every input mapped to a source, and a `layout.grid` that places each occurrence exactly once. A
   DRAFT visualization may be pinned at save.
5. **Edit** with `visualizations_update` / `dashboards_update`: send the WHOLE document and the
   `expected_hash` you read from the matching `_get` (or the previous write). A mismatch is
   `*.version.conflict` — re-read and rebase; never retry blindly.
6. **Validate** with `dashboards_validate` whenever a pinned pipeline, set or visualization may have
   changed: it judges the working version against the dependencies as they are now and answers `valid`
   with every failure. `dashboards_get` shows each pin's status and each source's read-only verdict, and
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

## Proving a visualization — the test loop

A visualization's `tests.cases` are its proof: fixture rows for every input and the assertions a
reader checks (`rendered`, `trace_count`, `no_console_errors`, `text_visible`, `no_data`,
`value_visible`). Run them against the real renderer before you hand the work over:

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
- `visualization.validation.config_schema_invalid` — the renderer's configuration (for plotly: a
  non-empty `data` array of supported trace types).
- `dashboard.validation.input_unbound` — a pinned visualization input no source feeds.
- `dashboard.validation.parameter_unbound` — a required pipeline parameter left unbound.
- `dashboard.validation.source_not_released` / `source_not_read_only` — the pinned pipeline release.
- `dashboard.validation.layout_invalid` — an occurrence or control not placed exactly once.
- `dashboard.validation.dependency_not_found` — a pin this workspace does not hold.
- `visualization.test.session_not_found` — the session is not yours (another key started it), or
  the id or token is wrong; `visualization.test.session_expired` — it was already submitted, it
  expired, or the visualization changed since it started: start a new session.

## Rules that hold everywhere here

- A name is a folder path, lower-case, 2–10 segments; it is never renamed.
- The server stores `config` verbatim and never executes it; `presentation.tokens` names theme tokens,
  never colours.
- Validation is exhaustive: fix every failure the list names, then write once.
