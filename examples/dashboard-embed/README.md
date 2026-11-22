# The embedded-dashboard example

`index.html` is a static host page that embeds one RELEASED dashboard: the page
loads the client library — one stylesheet and one script — from the
datapipelines origin, while the data flows to a relay you run — never from the
browser to this app, and never with a key in the page.

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
4. the theme — `datapipelines.css` carries the light theme; for another, link
   `/vendor/design-system/themes/<name>.css` from the same origin AFTER it and
   set `data-theme="<name>"` on `<html>`.

## The relay

Copy `../dashboard-proxy/proxy.mjs` and run it behind your own authentication —
it holds the dashboard key server-side and relays the four runtime routes with
the stream as a stream:

```
DP_BASE_URL=https://dp.internal.example DASHBOARD_KEY=<the dashboard key> PORT=8080 node ../dashboard-proxy/proxy.mjs
```

One key is one budget: the stream cap and the rate limit are shared by every
visitor behind the relay.

## The two files

- `/css/datapipelines.css` — the design tokens, the light theme, the base
  layers and the dashboard styles in one sheet. It names the fonts, which the
  same origin serves to any page: a host hosts no font file. The charts'
  colours, sizes and font are its `--chart-*` tokens; a host may set its own on
  the board container.
- `/js/datapipelines.js` — the chart engine, the runtime, the charts'
  interaction layer and the chart, table and KPI adapters, which register their
  kinds at load. A new chart type arrives inside it: the page does not change.

Load the stylesheet in `<head>` and the script before `embed.js`, which mounts
the container and inits. The boot glue passes no chart library: `init` refuses
a `charts` option. A page with a Content-Security-Policy names the
datapipelines origin in `script-src`, `style-src` and `font-src`, and the
relay's origin in `connect-src`; no `'unsafe-inline'` is needed.

## Any framework

The client library is DOM-only — one script, no dependencies. In React, Angular, Vue
or server-rendered HTML: call the boot glue when the component mounts,
`dispose()` when it unmounts, keep the container element stable between the
two, and never re-render the container's children yourself.
