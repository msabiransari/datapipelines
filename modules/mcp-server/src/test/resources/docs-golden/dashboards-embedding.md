
# Embed a dashboard in a page you own

An embedded dashboard is your page hosting the client runtime, the stylesheets and the renderer
scripts from this app's static assets, while the data flows through a server-side `dashboard` key —
never through the page. The working example is `examples/dashboard-embed/` (static HTML plus
`embed.js`); the reference relay is `examples/dashboard-proxy/proxy.mjs`. Embedding is the step
AFTER a release: the authoring loop ends at "GREEN tests, awaiting review", and nothing here changes
what you author. The agent writes the dashboard, the host-page files and the proxy's run line; the
person releases the dashboard and holds the key.

## The key and the folders

- Only a RELEASED dashboard embeds. The key never names a version, so the page shows the current
  release and nothing before a person releases it — and never a draft. When the visualization tests
  are GREEN you say the dashboard is ready for review; the embed comes after that release.
- The credential is a `dashboard` kind key, minted on the Keys page by a workspace admin (a super
  admin outside the workspace) and bound to a folder of the dashboard name space with the
  `dashboard.key.bind` verb — a folder prefix such as `acme/finance/` or the root `/`. The key
  grants the four runtime routes on the dashboards its folders cover and nothing else; a dashboard
  its folders do not cover answers exactly like a missing one. Ask for the mint and the bind by
  naming the folder; you never see, handle or transcribe the key.

## The transport

- The key lives behind your own backend, never in the page. The reference proxy relays the four
  runtime routes — the configuration read, the parameter evaluation, the refresh stream, the abort —
  with the key attached server-side, streams the SSE body as a stream, strips cookies and the CSRF
  header, passes error envelopes verbatim, and refuses every other path with its own 404. Copy it
  behind your own authentication; the runtime in proxy mode sends no cookies and no CSRF header of
  its own. One key is one budget: the SSE stream cap and the rate limit are shared by every visitor
  behind it, so one key per host application, sized for that traffic.

## The page

- The scripts, from the datapipelines origin (public `/js/**`, `/vendor/**`, `/css/**`): exactly one
  Plotly bundle, the `2d` — every chart family the `chart` kind ships is a 2D trace, so there is no
  3D selection rule — declared as
  `<script src="/vendor/plotly/plotly-2d.min.js" data-dp-plotly-bundle="2d"></script>`;
  then the runtime `/js/datapipelines-dashboard.js`; then the `chart` kind's adapter
  `/js/datapipelines-dashboard-chart.js` (registers the kind at script load, before init) and its
  Plotly converter `/js/datapipelines-dashboard-plotly.js` (registers no kind of its own); then the
  other kinds' adapters your dashboards use: `datapipelines-dashboard-table.js`,
  `datapipelines-dashboard-kpi.js` (each registers its renderer kind at script load, before init).
  The runtime refuses a mismatched bundle pair at bootstrap: `renderer.two_bundles` (both bundles
  declared), `renderer.unsupported` (an unknown bundle), `renderer.version_mismatch` (a pinned
  visualization needs a different major).
- The stylesheets, in this order: `/vendor/design-system/tokens.css`, the theme sheet
  (`/vendor/design-system/themes/light.css` for the default theme), `/vendor/design-system/base.css`,
  `/vendor/design-system/primitives.css`, `/css/app.css`, then `/css/dashboards.css`, plus
  `/vendor/plotly/plotly.css`. `dashboards.css` reads dozens of design tokens defined in the
  design-system sheets, not in itself — an `app.css` + `dashboards.css` pair alone renders
  half-styled. A host that brings its own stylesheet must define at least `--dashboard-row-unit`
  (the composite's row unit) and the `--chart-*` set (the chart kind's converters read them).
- Two elements: the board container and — when the dashboard has a parameter set — a parameters
  pane. Build the composite adapter and init:

  ```js
  var adapter = DatapipelinesDashboard.adapters(container, { parametersContainer: paramsEl });
  var instance = DatapipelinesDashboard.init({
    server: { baseUrl: "https://your-proxy.example", credentials: { proxyBaseUrl: "https://your-proxy.example" } },
    dashboard: { id: DASHBOARD_ID, version: "released" },
    container: container,
    adapter: adapter,
    charts: { library: "plotly", instance: window.Plotly },
  });
  await instance.ready;
  ```

  `credentials` is `"session"` or `{ proxyBaseUrl }`; in proxy mode the proxy base is the only base
  used, and `baseUrl` may be omitted. `charts` declares the library you loaded — with the `2d`
  bundle it is `{ library: "plotly", instance: window.Plotly }`; when no bundle is loaded omit the
  key, and a pinned chart visualization is refused `chart.library_undeclared` rather than drawn by
  a guess. `init` refuses `init.version_unsupported` (the version is
  neither `"released"` nor a positive integer) and throws `DashboardAlreadyMounted` when the
  container already hosts an instance — never init twice on one container. `dispose()` on teardown;
  after disposal the same container may host a fresh init.
- The host page carries exactly two values as data attributes: `data-dp-proxy-base` (your proxy's
  base URL) and `data-dp-dashboard-id` — the dashboard's `id` from `dashboards_get` (or the
  listing), a UUID, never the name.
- The composite adapter is the default and is enough for most hosts. Write your own only when you
  need your own grid, controls or renderer — `init` refuses an adapter missing any of the twelve
  functions: `mountLayout` (build the grid), `mountVisualization` (place one card),
  `renderParameters` (draw the parameter form), `readSelections` (current parameter values),
  `onEdit` (open edit), `onCommit` (apply parameters), `onAction` (run a board action),
  `renderData` (draw one visualization's rows), `renderStatus` (empty, error, stale),
  `notify` (deliver a notification), `resize` (respond to layout changes), `dispose` (unmount all
  of it). A custom renderer kind registers before init with
  `DatapipelinesDashboard.registerRenderer({ kind, version, create })`.
- The runtime is DOM-only — one script, no dependencies, a container element. In React, Angular,
  Vue or server-rendered HTML: init when the component mounts, `dispose()` when it unmounts, keep
  the container element stable between the two, and never re-render the container's children
  yourself.
- A page with a Content-Security-Policy must allow the scripts and stylesheets from the datapipelines
  origin, and pre-place the empty opt-out template
  `<template id="plotly.js-style-global" class="no-inline-styles"></template>` in `<head>` BEFORE any
  script, then load `/vendor/plotly/plotly.css` as a real stylesheet: Plotly checks for that template
  before injecting inline styles, and with it placed the chart rules come from `plotly.css`. The
  runtime itself injects no inline styles.

## What goes wrong

- `init.version_unsupported` — the version is neither `"released"` nor a positive integer.
- `DashboardAlreadyMounted` — the container already hosts an instance; dispose first.
- The bundle refusals above — fix the script declaration, not the code.
- `chart.library_undeclared` — a pinned chart visualization and no `charts` at init; load the
  bundle and pass the declaration.
- `chart.converter_missing` — the chart adapter is loaded but no converter is registered for the
  declared library and the occurrence's family; load `-plotly.js` with `-chart.js`.
- A rejected `ready` — the rejection is the runtime's one error type: `code` (the stable reason),
  `message`, `retryable` and `details` (possibly empty); it carries no step field. A bootstrap
  failure is ALSO published as a `bootstrap` notification — `{ scope: "bootstrap"`, `severity`,
  `code`, `message`, `retryable`, `recover`, `configurationStale }` — to the `onNotification`
  option, which the example does not install; a failed bootstrap disposes the instance, so a retry
  is a fresh `init` on a clean container.
- The proxy's own 404 — the request was outside the four routes; fix the caller, not the proxy.
- A server 404 on a valid route — the key's folder binding does not cover this dashboard; an
  unbound dashboard is served to no key.
- Streams failing under load — the shared budget: every visitor behind one key shares its stream
  cap.

## Who does what

| Side | Does |
| ---- | ---- |
| The agent | authors and validates the dashboard, runs its visualization tests to GREEN, writes the host page (the two data attributes, the bundle declaration, the scripts) and the proxy's run line with placeholders, and names the folder to bind. |
| The person | releases the dashboard, mints and binds the `dashboard` key, deploys the proxy with the key in its environment behind their own authentication. |

The agent never calls its own work live.
