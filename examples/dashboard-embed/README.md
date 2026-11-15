# The embedded-dashboard example

`index.html` is a static host page that embeds one RELEASED dashboard: the page
hosts the client runtime, the design-system stylesheets and the renderer scripts
from the datapipelines origin, while the data flows to a relay you run — never
from the browser to this app, and never with a key in the page.

What the page shows: a full-width board container (`#dashboard`) that
`embed.js` boots into the first-party composite adapter, with a sibling
`#parameters` div for the filters pane when the dashboard has a parameter set.

## The four values a host changes

1. the Datapipelines origin — every Datapipelines asset in `index.html` is
   written against the placeholder `https://dp.example.co`; replace that origin
   with your deployment's, everywhere it appears, or nothing loads (the page
   sits on a separate host origin, so a root-relative asset URL would resolve
   to the host);
2. `data-dp-proxy-base` — your relay's base URL (placeholder `https://app.example.co/dp`).
3. `data-dp-dashboard-id` — the dashboard's UUID from `dashboards_get`, never its name
   (placeholder is the all-zero UUID).
4. the theme sheet — `themes/light.css` here; swap it if you serve another theme.

## The relay

Copy `../dashboard-proxy/proxy.mjs` and run it behind your own authentication —
it holds the dashboard key server-side and relays the four runtime routes with
the stream as a stream:

```
DP_BASE_URL=https://dp.internal.example DASHBOARD_KEY=<the dashboard key> PORT=8080 node ../dashboard-proxy/proxy.mjs
```

One key is one budget: the stream cap and the rate limit are shared by every
visitor behind the relay.

## Script order

1. the chart engine's files under `/js/js-rendered/`, in the engine's load order
   (`00-namespace.js` first, `types/area.js` last) — the same files the server
   draws its pictures with;
2. `/js/datapipelines-dashboard.js` — the runtime;
3. `/js/datapipelines-dashboard-chart.js` — the `chart` kind's adapter; it
   registers the kind at script load, before init;
4. `/js/datapipelines-dashboard-table.js` and `/js/datapipelines-dashboard-kpi.js`
   — the table and KPI kinds' adapters, each registering its kind at load;
5. `embed.js` — mounts the container and inits.

The boot glue passes no chart library: charts are drawn by the datapipelines
client library, and `init` refuses a `charts` option.

## Stylesheets, in this order

`tokens.css`, the theme sheet, `base.css`, `primitives.css`, `app.css`, then
`dashboards.css`. `dashboards.css` reads design tokens the design-system sheets
define; loading only `app.css` + `dashboards.css` renders half-styled. The
charts' colours, sizes and font are the `--chart-*` tokens `app.css` sets; a
host may set its own on the board container.

## Any framework

The runtime is DOM-only — one script, no dependencies. In React, Angular, Vue
or server-rendered HTML: call the boot glue when the component mounts,
`dispose()` when it unmounts, keep the container element stable between the
two, and never re-render the container's children yourself.
