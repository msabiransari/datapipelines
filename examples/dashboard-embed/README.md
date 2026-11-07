# The embedded-dashboard example

`index.html` is a static host page that embeds one RELEASED dashboard: the page
hosts the client runtime, the design-system stylesheets and the renderer scripts
from the datapipelines origin, while the data flows to a relay you run — never
from the browser to this app, and never with a key in the page.

What the page shows: a full-width board container (`#dashboard`) that
`embed.js` boots into the first-party composite adapter, with a sibling
`#parameters` div for the filters pane when the dashboard has a parameter set.

## The three values a host changes

1. `data-dp-proxy-base` — your relay's base URL (placeholder `https://app.example.co/dp`).
2. `data-dp-dashboard-id` — the dashboard's UUID from `dashboards_get`, never its name
   (placeholder is the all-zero UUID).
3. the theme sheet — `themes/light.css` here; swap it if you serve another theme.

## The relay

Copy `../dashboard-proxy/proxy.mjs` and run it behind your own authentication —
it holds the dashboard key server-side and relays the four runtime routes with
the stream as a stream:

```
DP_BASE_URL=https://dp.internal.example DASHBOARD_KEY=<the dashboard key> PORT=8080 node ../dashboard-proxy/proxy.mjs
```

One key is one budget: the stream cap and the rate limit are shared by every
visitor behind the relay.

## Script order, and why the bundle attribute matters

1. exactly one Plotly bundle (`plotly-2d.min.js` or `plotly-3d.min.js`) with
   `data-dp-plotly-bundle="2d"` (or `"3d"`) on the tag — `3d` only when a pinned
   visualization is `scatter3d`, `surface` or `mesh3d`; the runtime reads these
   declarations and refuses a mismatched pair at bootstrap;
2. `/js/datapipelines-dashboard.js` — the runtime;
3. the renderer adapters for the kinds your dashboards use (`-plotly.js`,
   `-table.js`, `-kpi.js`) — each registers at script load, before init;
4. `embed.js` — reads the container's data attributes and inits.

## Stylesheets, in this order

`tokens.css`, the theme sheet, `base.css`, `primitives.css`, `app.css`,
`dashboards.css`, then `plotly.css`. `dashboards.css` reads design tokens the
design-system sheets define; loading only `app.css` + `dashboards.css` renders
half-styled. The empty `<template id="plotly.js-style-global">` must stand in
`<head>` before any script, so Plotly writes no inline styles and the chart
rules come from `plotly.css`.

## Any framework

The runtime is DOM-only — one script, no dependencies. In React, Angular, Vue
or server-rendered HTML: call the boot glue when the component mounts,
`dispose()` when it unmounts, keep the container element stable between the
two, and never re-render the container's children yourself.
