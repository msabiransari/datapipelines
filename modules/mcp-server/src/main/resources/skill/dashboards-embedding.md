---
area: dashboards
layer: reference
purpose: Embedding a released dashboard in a host page outside this app — the dashboard key, the proxy, the client library's one script and one stylesheet, the theme, the fonts and the lifecycle.
---

# Embed a dashboard in a page you own

An embedded dashboard is your page loading the client library — one script and one stylesheet —
from this app's origin, while the data flows through a server-side `dashboard` key — never through
the page. The working example is `examples/dashboard-embed/` (static HTML plus
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

## Theme and stylesheet

Read this before you write the host page.

- One stylesheet, `/css/datapipelines.css`. It carries the light theme and every design token the
  dashboard reads; the host defines none.
- Another theme: load that theme's sheet AFTER it, `/vendor/design-system/themes/<name>.css` (the
  names: `auto`, `dark`, `forest`, `healthcare`, `light`, `minimal`, `ocean`, `professional`,
  `saas`), and set `data-theme="<name>"` on `<html>`. With the sheet in the page before the
  dashboard starts, the charts are drawn in the theme from the first draw.
- Changing the theme while the dashboard is shown: add the theme's sheet, wait for its `load`
  event, then set `data-theme`. The cards follow the sheet; the charts are drawn pictures and are
  redrawn when that attribute changes. Without the attribute the dark theme's tooltip shadow stays
  the light one.
- The fonts (Noto Sans, JetBrains Mono) are named by the stylesheet and served from the
  datapipelines origin with the header that lets any page use them: the host hosts no font file. A
  page with a Content-Security-Policy adds the datapipelines origin to `font-src` beside
  `script-src` and `style-src`; no `'unsafe-inline'` is needed.
- The stylesheet styles the WHOLE page it is loaded on — the body's font, size and margins, its
  colours, headings and form controls — and defines its tokens on the page's root. When the host
  page has styles of its own, put the dashboard on a page or a frame of its own.

## The page

- Two files from the datapipelines origin (public `/js/**`, `/css/**`): the stylesheet
  `/css/datapipelines.css` in the page's head, and the script `/js/datapipelines.js` as one plain
  script tag before your boot code. The script is the whole client library: the chart engine that
  draws every chart, the runtime `DatapipelinesDashboard`, the charts' interaction layer (tooltip,
  highlight, legend switch, keyboard) and the `chart`, `table` and `kpi` kinds' adapters, each
  registering its kind at load, before init. A new chart type arrives inside the same file: the
  page does not change. The example page `examples/dashboard-embed/index.html` is that page. The
  per-file URLs the app's own pages load (`/js/js-rendered/<name>`, `/js/datapipelines-dashboard.js`
  and the others) still answer; a host page needs none of them.
  The runtime refuses at bootstrap a renderer no loaded script registered (`renderer.unsupported`)
  and a pinned visualization that needs a different major (`renderer.version_mismatch`).
- A host that does not load `/css/datapipelines.css` at all and brings its own stylesheet must
  define at least `--dashboard-row-unit` (the composite's row unit); a `--chart-*` token it leaves
  undeclared draws its default, and a value that token may not hold draws its default with a
  `chart.token-refused` warning naming it. With the library's stylesheet loaded, a host may set its
  own `--chart-*` tokens on the board container.
- Two elements: the board container and — when the dashboard has a parameter set — a parameters
  pane. Build the composite adapter and init:

  ```js
  var adapter = DatapipelinesDashboard.adapters(container, { parametersContainer: paramsEl });
  var instance = DatapipelinesDashboard.init({
    server: { baseUrl: "https://your-proxy.example", credentials: { proxyBaseUrl: "https://your-proxy.example" } },
    dashboard: { id: DASHBOARD_ID, version: "released" },
    container: container,
    adapter: adapter,
  });
  await instance.ready;
  ```

  `credentials` is `"session"` or `{ proxyBaseUrl }`; in proxy mode the proxy base is the only base
  used, and `baseUrl` may be omitted. There is no `charts` option: `init` given one throws
  `init.invalid` ("charts is no longer an option: charts are drawn by the datapipelines client
  library"). `init` refuses `init.version_unsupported` (the version is
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
- The client library is DOM-only — one script, no dependencies, a container element. In React, Angular,
  Vue or server-rendered HTML: init when the component mounts, `dispose()` when it unmounts, keep
  the container element stable between the two, and never re-render the container's children
  yourself.
- A page with a Content-Security-Policy names the datapipelines origin in `script-src`, `style-src`
  and `font-src`, and your proxy's origin in `connect-src`; nothing else. The library writes no
  inline style and no markup string, so no `'unsafe-inline'` is needed.

## What goes wrong

- `init.version_unsupported` — the version is neither `"released"` nor a positive integer.
- `DashboardAlreadyMounted` — the container already hosts an instance; dispose first.
- `init.invalid` naming `charts` — remove the option; the client library draws the charts.
- `renderer.unsupported` / `renderer.version_mismatch` — fix the script tag, not the code: load
  `/js/datapipelines.js` before init.
- A chart card in error, with a browser console line saying the chart engine is not loaded — the
  page loads the runtime's own file without the engine; load `/js/datapipelines.js` instead.
- Text in a fallback font, with a browser console line refusing a font — the page's
  Content-Security-Policy leaves the datapipelines origin out of `font-src`.
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
| The agent | authors and validates the dashboard, runs its visualization tests to GREEN, writes the host page (the two data attributes, the library's stylesheet and script) and the proxy's run line with placeholders, and names the folder to bind. |
| The person | releases the dashboard, mints and binds the `dashboard` key, deploys the proxy with the key in its environment behind their own authentication. |

The agent never calls its own work live.
