# Pipeline Editor UI Specification

**Status:** v1.10 (revised — see Change Log)
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Contract](pipeline-contract.md), [REST API + SSE](rest-api.md), [Type System](type-system.md), [Enums](enums.md), [Auth](auth.md), [Configuration](configuration.md), [@acme/design-tokens Design System](https://github.com/msabir/design-system-starter)
**Last updated:** 2026-09-06

---

## 1. Purpose

The Pipeline Editor is the primary human-facing screen of datapipelines.co. It renders a pipeline as an interactive **DAG visualization** (nodes + edges), shows its metadata (settings, parameters, datasources), lets the user **execute** it, and shows **real-time execution progress** via SSE — highlighting each node as it runs, succeeds, or fails.

This spec defines:
- The page architecture (server-rendered shell + client-side interactivity).
- Technology stack (Thymeleaf + Alpine.js + Cytoscape.js + @acme/design-tokens + `fetch`).
- Graph rendering (Cytoscape.js with dagre layout).
- Node states and visual styles (idle, running, success, failed, aborted).
- SSE event handling → graph updates, including connection loss (§15).
- Execute button flow.
- Error display and result delivery.
- JavaScript vendoring strategy (no CDN, no build step).
- Accessibility and keyboard navigation (canvas graph + parallel DOM node list).

Graph **authoring** is out of scope: v1 pipelines are authored by LLMs via MCP or by direct JSON editing. The editor is a visualization + execution surface (§11).

---

## 2. Design Principles

1. **Hybrid rendering.** Thymeleaf renders the page shell + initial data on the server. Client-side JS (Alpine.js + vanilla) handles interactivity. No SPA framework, no build step.
2. **Graceful degradation, stated honestly.** The editor **requires JavaScript for its core function.** Without JS the server-rendered shell still shows pipeline metadata (name, version, settings, parameters) and a plain `<ul>` of node ids with their declared type, source, template and `dependsOn` — the same list §14 renders for assistive technology. There is **no graph** (Cytoscape draws to a `<canvas>`), no execution, no result panel, no error modal. A `<noscript>` block states this. We do not claim the page "works without JavaScript".
3. **No CDN, no build step.** All JS libraries (Cytoscape.js, cytoscape-dagre, Alpine.js) and the design system CSS vendored as static files under `/vendor/`. Per the project's no-CDN rule.
4. **Design system is the styling foundation.** All colors, spacing, typography, shadows, and radii come from `@acme/design-tokens`. No hardcoded hex values anywhere — not in CSS, not in Cytoscape styles, not in Thymeleaf templates. The design system's semantic tokens (`--surface-*`, `--text-*`, `--accent-*`, etc.) are the single source of truth. See §3.4.
5. **One page, three panels.** Left sidebar (settings + parameters), center (graph), right panel (node details — slides in on node click).
6. **SSE drives the graph.** When the user clicks Execute, the page opens an SSE connection. Every event updates the graph in real-time. No polling.
7. **Cytoscape's class-based styling is the status mechanism.** Adding/removing CSS classes on nodes (`running`, `success`, `failed`, `aborted`) drives all visual state changes. No manual style manipulation.
8. **Readable identifiers everywhere.** Node IDs in the graph match node IDs in the pipeline JSON (`fetch_orders`, not `node_1`). Users can correlate graph ↔ JSON ↔ logs.

---

## 3. Technology Stack

### 3.1 Server-side

| Technology | Role |
|---|---|
| **Thymeleaf** | Renders the page shell, pipeline metadata, settings/parameters forms, error fragments. |
| **Spring MVC** | Controller serving `GET /pipelines/{id}/editor`. |

### 3.2 Client-side

| Technology | Version (pin at impl time) | Role |
|---|---|---|
| **Alpine.js** | 3.x | Reactive data binding for UI state (execute button state, modal visibility, parameter form values, error display). |
| **Cytoscape.js** | 3.34.0 | Graph rendering + interaction (pan, zoom, click, node selection). |
| **cytoscape-dagre** | matching extension version | DAG auto-layout (left-to-right, levels by topology). |
| **Native fetch API + `ReadableStream`** | browser-built-in | REST calls (execute, fetch details, result cursor) **and** SSE consumption — the execute endpoint is a POST, which `EventSource` cannot issue, so the stream is parsed manually from the response body. See §7.3. |

> `EventSource` is deliberately **not** in this stack. Every SSE consumer in the editor is `fetch` + `ReadableStream` (§7.3).

### 3.3 What we explicitly do NOT use

- **No React, Vue, or Svelte.** The editor is one page; Alpine.js is sufficient. Adding a SPA framework would require a build step (Vite/Webpack), npm dependency tree, and bundle management — friction that doesn't pay for itself on a single page.
- **No htmx for the editor.** htmx is great for the rest of the UI (datasource list, template browser, execution history) where server-rendered partials swap in. The editor's core interactivity (graph updates from SSE) is client-side, not HTML-swap-driven. fetch + direct Cytoscape API calls are the right tool.
- **No CDN.** All libraries and the design system vendored locally (per the project's hard rule).
- **No npm/build step.** All libraries used via their UMD/browser builds, included via `<script>` tags. The design system is pure CSS — no build step either.

### 3.4 Design System (@acme/design-tokens)

The entire UI — not just the editor — uses **`@acme/design-tokens`** (v0.2.0+) as the styling foundation. This is a framework-agnostic, CSS-custom-property-based design system with:

- **Semantic tokens** (`tokens.css`): the contract. Variables like `--surface-default`, `--text-primary`, `--accent-primary`, `--radius-base`, `--space-4`. Never contains concrete values.
- **9 swappable themes** (`themes/*.css`): concrete values per theme. Switch one file → re-skin the entire app at runtime.
- **Primitives** (`primitives.css`): ~80 ready-to-use component classes (`.ds-button`, `.ds-card`, `.ds-input`, `.ds-badge`, `.ds-table`, `.ds-modal`, etc.).
- **Base reset** (`base.css`): global typography + reset.
- **Motion** (`motion.css`): animations + keyframes, all `prefers-reduced-motion` aware.

The active theme is resolved **per request**, not fixed at deployment: `${activeTheme} = users.theme_preference ?: datapipelines.ui.theme` — the authenticated user's stored preference when set ([UI Screens §4.11](ui-screens.md#411-user-settings)), otherwise the deployment default from [Configuration §3.10](configuration.md#310-ui) (name, default and valid theme list defined once there, not restated here). The controller passes the resolved value to the template as `${activeTheme}` (§4.2); both sources are validated against the vendored theme list (config at startup, preference on write), so the editor may assume it names a vendored theme file. The runtime theme-swap mechanism (Appendix A.5's `readDesignTokens()` re-read) is unaffected by which source won.

**Design system CSS load order** (in every page's `<head>`, before app CSS):

```html
<link rel="stylesheet" href="/vendor/design-system/tokens.css">
<link rel="stylesheet" href="/vendor/design-system/themes/saas.css" id="theme-link">
<link rel="stylesheet" href="/vendor/design-system/base.css">
<link rel="stylesheet" href="/vendor/design-system/motion.css">
<link rel="stylesheet" href="/vendor/design-system/primitives.css">
<link rel="stylesheet" href="/css/app.css">                <!-- app-specific, LAST -->
```

`app.css` loads LAST so its rules win equal-specificity ties by document order: its `body` rule re-asserts `background-color: var(--surface-page); color: var(--text-primary)` (born as the guard against Bootstrap's reboot painting `<body>` white under every theme, 024 T40 — Bootstrap itself was removed in 076 §C, and the element defaults its reboot silently provided are now owned by base.css plus the "076 §C" section at the end of `app.css`). Every theme file, `dark.css` included, opens on `:root`: the swap loads exactly one file, so a theme takes effect by being loaded — no `data-theme` attribute is involved anywhere.

Theme switching at runtime: swap the `href` of `#theme-link`. All tokens cascade instantly — no page reload.

**Design system rules we follow strictly:**
- Never use hardcoded hex values in CSS, Thymeleaf templates, or Cytoscape styles. Always reference tokens.
- Never edit `tokens.css` or theme files (they are vendored, not forked).
- App-specific semantic tokens (e.g., `--node-running-accent`) are defined in `app.css` and derive from design system tokens.
- Use `.ds-*` primitives wherever they fit (buttons, inputs, cards, badges, tables, modals). Override or extend only when the primitive doesn't fit.

**Bridging design system tokens → Cytoscape styles:**

Cytoscape uses its own style format (not CSS). We bridge by reading computed CSS custom properties at init time:

```javascript
function readDesignTokens() {
    const cs = getComputedStyle(document.documentElement);
    // Every key falls back to a hard hex so a stale theme file cannot blank the graph
    // (fallbacks elided here). The keys are exactly those §5.3 references.
    return {
        // Node card (§5.3)
        nodeSurface:       cs.getPropertyValue('--node-surface').trim(),
        nodeBorder:        cs.getPropertyValue('--node-border').trim(),
        nodeLabelText:     cs.getPropertyValue('--node-label-text').trim(),
        // Selection (§5.3)
        nodeSelectedRing:  cs.getPropertyValue('--node-selected-ring').trim(),
        nodeSelectedHalo:  cs.getPropertyValue('--node-selected-halo').trim(),
        // State accents (§6.2) — success/failed/aborted fall back to the banner's
        // --node-*-bg tokens so a theme overriding those re-themes both surfaces
        nodeRunningAccent: cs.getPropertyValue('--node-running-accent').trim(),
        nodeSuccessAccent: cs.getPropertyValue('--node-success-accent').trim(),
        nodeFailedAccent:  cs.getPropertyValue('--node-failed-accent').trim(),
        nodeAbortedAccent: cs.getPropertyValue('--node-aborted-accent').trim(),
        // Edges
        edgeIdleStroke:    cs.getPropertyValue('--edge-idle-stroke').trim(),
        edgeActiveStroke:  cs.getPropertyValue('--edge-active-stroke').trim(),
    };
}
```

When the theme changes at runtime, the graph re-reads tokens and re-applies the Cytoscape stylesheet. See Appendix A.5.

---

## 4. Page Architecture

### 4.1 URL

```
GET /pipelines/{id}/editor
GET /pipelines/{id}/versions/{version}/editor    (specific version)
```

Authentication: session cookie carrying the internal JWT (browser flow). See [Auth §6](auth.md#6-session-tokens-internal-jwt). Required scope per the authoritative matrix in [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative): `read` to view, `execute` to run, `execute` to cancel.

### 4.2 Server-rendered HTML structure

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <title>Pipeline Editor — <span th:text="${pipeline.displayName}">Name</span></title>

    <!-- Design System (load order per @acme/design-tokens spec) -->
    <link rel="stylesheet" href="/vendor/design-system/tokens.css">
    <link rel="stylesheet" th:href="@{/vendor/design-system/themes/{theme}.css(theme=${activeTheme})}"
          id="theme-link">
    <link rel="stylesheet" href="/vendor/design-system/base.css">
    <link rel="stylesheet" href="/vendor/design-system/motion.css">
    <link rel="stylesheet" href="/vendor/design-system/primitives.css">

    <!-- App CSS (uses design system tokens; defines app-specific semantic tokens) -->
    <link rel="stylesheet" href="/css/app.css">
    <link rel="stylesheet" href="/css/pipeline-editor.css">
</head>
<body>
    <!-- Top bar: pipeline name, version selector, actions -->
    <header class="ds-header editor-topbar">
        <h1 class="ds-h2" th:text="${pipeline.displayName}">Pipeline Name</h1>
        <span class="ds-badge ds-badge--neutral" th:text="'v' + ${pipeline.version}">v3</span>
        <select id="version-selector" class="ds-select ds-select--sm" x-data x-model="selectedVersion"
                @change="window.location.href = `/pipelines/${pipelineId}/versions/${selectedVersion}/editor`">
            <option th:each="v : ${versions}" th:value="${v.version}"
                    th:text="'v' + ${v.version}"
                    th:selected="${v.version == pipeline.version}"></option>
        </select>
        <button id="execute-btn" class="ds-button ds-button--primary"
                x-data="{ running: false }"
                @click="executePipeline()"
                :disabled="running"
                :aria-busy="running"
                x-text="running ? 'Executing...' : 'Execute'">Execute</button>
        <!-- Cancel: DELETE /executions/{id} (§15.2). Shown only while a stream is open. -->
        <button id="cancel-btn" class="ds-button ds-button--secondary"
                x-data="{ running: false, cancelling: false }"
                x-show="running" @click="cancelExecution()"
                :disabled="cancelling"
                x-text="cancelling ? 'Cancelling...' : 'Cancel'">Cancel</button>
    </header>

    <!-- Main layout: sidebar | graph | details -->
    <main class="editor-layout">
        <!-- Left sidebar: settings + parameters -->
        <aside class="ds-sidebar editor-sidebar">
            <section class="ds-section settings-panel">
                <h2 class="ds-h4">Settings</h2>
                <div th:replace="~{fragments/settings :: body(${pipeline.settings})}"></div>
            </section>
            <section class="ds-section parameters-panel">
                <h2 class="ds-h4">Parameters</h2>
                <form id="parameter-form" class="ds-form">
                    <div class="ds-field" th:each="param : ${pipeline.parameters}">
                        <label class="ds-label" th:for="${param.key}"
                               th:text="${param.key + (param.required ? ' *' : '')}">
                            param_name
                        </label>
                        <input class="ds-input" th:type="${inputType(param.value.type)}"
                               th:id="${param.key}"
                               th:name="${param.key}"
                               th:required="${param.required}"
                               th:value="${param.value.default}">
                    </div>
                </form>
            </section>
        </aside>

        <!-- Center: graph -->
        <section class="editor-graph">
            <!-- Cytoscape draws into a <canvas> inside #cy: no per-node DOM exists. -->
            <div id="cy" role="img" th:attr="aria-label=${graphSummary}"
                 aria-describedby="node-list">Pipeline graph</div>

            <!-- Parallel accessible node list (§14). Server-rendered, so it is also the
                 no-JS fallback. Visually hidden until focused; statuses updated by the
                 same SSE handler that styles the canvas. -->
            <ul id="node-list" class="visually-hidden-until-focus" role="listbox"
                aria-label="Pipeline nodes" tabindex="0">
                <li th:each="n : ${pipeline.nodes}" role="option"
                    th:id="'node-item-' + ${n.id}"
                    th:attr="data-node-id=${n.id},aria-selected=false"
                    th:text="${n.id} + ' — ' + ${n.type} + ' — idle'">node_id — DQL — idle</li>
            </ul>

            <!-- Status announcements for AT; SSE handler writes one sentence per transition. -->
            <div id="graph-status" class="visually-hidden" role="status" aria-live="polite"></div>

            <noscript>
                <p class="ds-text">This editor needs JavaScript for the graph, execution and
                results. Without it you can read the pipeline metadata and the node list above.</p>
            </noscript>

            <div class="graph-controls">
                <button class="ds-button ds-button--ghost ds-button--sm" onclick="window.editor.fit()">Fit</button>
                <button class="ds-button ds-button--ghost ds-button--sm" onclick="window.editor.zoomIn()">+</button>
                <button class="ds-button ds-button--ghost ds-button--sm" onclick="window.editor.zoomOut()">−</button>
            </div>
        </section>

        <!-- Right: node details (hidden by default, slides in on node click) -->
        <aside class="ds-card editor-details" id="node-details"
               x-data="{ visible: false }" x-show="visible" x-transition>
            <!-- Populated dynamically on node click -->
        </aside>
    </main>

    <!-- Error modal (uses .ds-modal primitive).
         The window-level `show-error` listener is what makes §9.1's dispatch do anything —
         ErrorModal.show() dispatches on `window`, Alpine catches it here. -->
    <div class="ds-modal-backdrop" id="error-modal"
         x-data="{ visible: false, error: null }"
         x-on:show-error.window="error = $event.detail; visible = true"
         x-on:keydown.escape.window="visible = false"
         x-show="visible" x-transition.opacity>
        <div class="ds-modal" role="alertdialog" aria-modal="true"
             aria-labelledby="error-modal-title" @click.away="visible = false">
            <div class="ds-modal__header">
                <h2 class="ds-h4 ds-text--danger" id="error-modal-title">Execution Failed</h2>
                <button class="ds-button ds-button--ghost ds-button--sm" @click="visible = false">×</button>
            </div>
            <div class="ds-modal__body">
                <p class="ds-text" x-text="error?.userMessage"></p>
                <pre class="ds-code-block" x-text="error?.details"></pre>
                <a x-show="error?.docUrl" :href="error?.docUrl" class="ds-link" target="_blank">View documentation</a>
            </div>
            <div class="ds-modal__footer">
                <button class="ds-button ds-button--secondary" @click="visible = false">Close</button>
            </div>
        </div>
    </div>

    <!-- Result preview panel (§10) — populated by result.js on data_ready -->
    <aside class="ds-card editor-result" id="result-panel"
           role="region" aria-label="Execution result"
           x-data="{ visible: false }" x-show="visible" x-transition></aside>

    <!-- Connection-loss banner (§15.1) — non-blocking, above the graph -->
    <div class="ds-banner ds-banner--warning" id="connection-banner"
         role="status" x-data="{ visible: false, text: '' }" x-show="visible"
         x-on:connection-lost.window="text = $event.detail.text; visible = true"
         x-text="text"></div>

    <!-- Pipeline JSON embedded for client-side consumption -->
    <script type="application/json" id="pipeline-data" th:utext="${pipelineJson}">
        {}
    </script>

    <!-- Vendored libraries -->
    <script src="/vendor/cytoscape/cytoscape.min.js"></script>
    <script src="/vendor/cytoscape/cytoscape-dagre.js"></script>
    <script src="/vendor/alpinejs/alpine.min.js" defer></script>

    <!-- Editor logic -->
    <script src="/js/pipeline-editor/graph.js"></script>
    <script src="/js/pipeline-editor/sse.js"></script>
    <script src="/js/pipeline-editor/execute.js"></script>
    <script src="/js/pipeline-editor/details.js"></script>
    <script src="/js/pipeline-editor/error.js"></script>
    <script src="/js/pipeline-editor/result.js"></script>
    <script src="/js/pipeline-editor/a11y.js"></script>
    <script src="/js/pipeline-editor/init.js"></script>
</body>
</html>
```

The page also loads `/js/pipeline-editor/sql-highlight.js` (§8.3) and issues one additional partial request at runtime: `GET /partials/pipelines/{id}/nodes/{nodeId}/sql` (scope `READ_RESOURCES`) fills the details panel's SQL section via `htmx.ajax` on node selection — route, wire format and states in §8.3.

### 4.3 Layout dimensions

**The editor is full-bleed** (065 §A). `layouts/default.html` wraps every page in
`<main class="app-container app-main">` and `app.css` caps `.app-container` at
`--app-content-max: 1600px`. That measure is right for prose — lists, settings, docs —
and wrong for the one surface whose value scales with width: on a ~2000px viewport the
editor occupied ~1450px between two dead margins, while the graph was the thing paying
for the cap. A page opts out with a single model attribute:

- `PipelineEditorController` sets `model.addAttribute("fullBleed", true)`. **No other
  page sets it**, and `th:classappend` adds nothing on a null condition, so every other
  screen keeps the 1600px cap by default.
- `layouts/default.html`: `<main class="app-container app-main"
  th:classappend="${fullBleed} ? 'app-main-bleed' : null">`.
- `app.css`: `.app-main-bleed { max-width: none; padding-inline: var(--gap-md); }` —
  tokens only.
- **The nav bar keeps its own `.app-container` cap.** A nav wider than its content reads
  as broken, and a full-bleed nav was never asked for.
- Pinned by `EditorLayoutRenderTest`: the editor's `<main>` carries `app-main-bleed`,
  the pipelines list's does not.
- Prior art, not unified: the template explorer un-caps itself from its own stylesheet
  (`template-tree.css`: `.app-container:has(> .tplx-page) { max-width: none; }`, 059 §C′)
  and keeps the `--gap-lg` gutter. The model attribute is the mechanism to reuse — it is
  declared by the controller, visible in the rendered `<main>`, and testable without a
  stylesheet.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Toolbar: ← Back | Name | [draft badge] [Release] [Discard] [Execute]         │ 48px
├──────────┬───────────────────────────────────────────────────────────────────┤
│          │                                                          ┌────────┴──┐
│ Settings │                                                          │ Node      │
│          │                    Graph (Cytoscape)                     │ details   │
│ Parameters│                                                         │ (overlay, │
│          │            [#cy-canvas, fills the pane]                  │  scrim on │
│          │                                                          │  canvas   │
│  280px   │                     1fr                                  │  only)    │
│          │                                                          │ clamp(720,│
│          │                                                          │  60%,1200)│
├──────────┴──────────────────────────────────────────────────────────┴─────────┤
│ [Results] [Errors ②]                                                    [▁]   │ header
│ …body… (open: ≤ 40vh; minimized: the header row alone)                        │
└───────────────────────────────────────────────────────────────────────────────┘
```

The three panes' dimensions, all in one place:

| Surface | Dimension | Source |
|---|---|---|
| Page | no max-width; `padding-inline: var(--gap-md)` | `.app-main-bleed` (§4.3 above) |
| `.pe-root` height | `calc(100dvh - var(--header-height) - var(--space-px) - (var(--gap-lg) * 2))`, floor 540px | unchanged since 041/059 |
| Left sidebar | `--pe-sidebar-width: 280px` | unchanged |
| Canvas | `1fr` — the layout is a **two**-track grid (sidebar + stage); the inspector's third track died with the 065 overlay, and the overlay itself died with 080 | `.pe-body` |
| Dock, open | the mock's geometry: a 38px tab strip over a 232px pane (`--pe-dock-tabs-h` / `--pe-dock-pane-h`) | `.pe-dock` |
| Dock, collapsed | the tab strip's own height — one row, tokens only | `.pe-dock-collapsed` |
| Node card | `--pe-card-w: 236px` wide; `--pe-card-h: 148px` is a **`min-height`**, not a height (082 addendum) — the card grows to its content and `syncCardHeights()` hands each measured height to Cytoscape. Both step to **272 / 170 with the card type scale +1px** once the STAGE is ≥ 1200px wide (082 §A) | §5.3 |

The dock is the **last flex child of `.pe-root`**, not a `position: fixed` overlay. That
is the whole mechanism behind "the canvas reclaims the rest": `.pe-body` is `flex: 1`,
so whatever the dock stops using, the graph gets. A fixed overlay can only ever cover the
canvas — it can never give the space back.

Responsive: below `--breakpoint-lg` (1024px) the sidebar collapses to a drawer and its
grid track goes to 0, and the Details pane's two columns stack. Below `--breakpoint-md`
(768px) the sidebar drawer is full-width and the minimap hides.

Layout dimensions come from design system tokens wherever a token exists. The pane
heights and the card box are the geometry itself, declared once as custom properties —
the same standing as `--app-content-max` (Appendix A).

---

## 5. Graph Rendering (Cytoscape.js)

### 5.1 Initialization

On page load, `init.js` reads the embedded pipeline JSON and initializes the graph:

```javascript
// init.js
document.addEventListener('DOMContentLoaded', () => {
    const pipelineData = JSON.parse(document.getElementById('pipeline-data').textContent);
    window.editor = new PipelineGraph(pipelineData);
    window.editor.render();
});

// graph.js
class PipelineGraph {
    constructor(pipeline) {
        this.pipeline = pipeline;
        this.cy = null;
    }

    render() {
        const elements = this.buildElements(this.pipeline);
        const layout = this.layoutOptions();

        this.cy = cytoscape({
            container: document.getElementById('cy'),
            elements: elements,
            style: PIPELINE_GRAPH_STYLE,        // §5.3
            layout: layout,
            wheelSensitivity: 0.2,
            minZoom: 0.2,
            maxZoom: 3.0,
        });

        this.wireEventHandlers();               // §5.4
    }

    buildElements(pipeline) {
        // The caller node is the node resolving to output.target === 'caller' — declared, or
        // by an omitted `output` block. At most one exists; zero is legal (pure ETL).
        // See Pipeline Contract §9. NOT topology-derived.
        const isCaller = n => n.type === 'DQL' && (!n.output || n.output.target === 'caller');

        const nodes = pipeline.nodes.map(n => ({
            data: {
                id: n.id,
                label: n.id,
                nodeType: n.type,               // DQL / DML / DDL
                description: n.description,
                source: n.source,
                template: n.template,
                output: n.output,
                dependsOn: n.dependsOn,
                isCaller: isCaller(n),
                status: 'idle',                 // §6
            },
            // Classes MUST be set here — the §5.3 stylesheet selects on them and nothing
            // else adds them later. `idle` is applied at build time so setNodeStatus()'s
            // removeClass('idle running success failed aborted') stays symmetric (§6.4).
            classes: [
                'idle',                          // explicit — §6.2 symmetry
                `type-${n.type.toLowerCase()}`,  // type-dql | type-dml | type-ddl
                                                 // (PIPELINE nodes get `pipeline-node` instead)
                ...(isCaller(n) ? ['caller'] : []),
            ].join(' '),
        }));

        const edges = [];
        pipeline.nodes.forEach(n => {
            n.dependsOn.forEach(dep => {
                edges.push({
                    data: {
                        id: `${dep}->${n.id}`,
                        source: dep,
                        target: n.id,
                    }
                });
            });
        });

        return [...nodes, ...edges];
    }

    // The layout options actually passed (v1.6, retuned for cards — 059 §B): a 264px
    // card needs wider ranks and more breathing room than the 120×44 box ever did.
    // fit is FALSE — fitToView() owns the fit so it can apply padding AND the
    // readable-minimum floor ("three nodes should fill the pane"). marginX/marginY
    // are NOT cytoscape-dagre options (they belong to grid/cose and are silently
    // ignored); padding is the edge clearance.
    layoutOptions() {
        return {
            name: 'dagre',
            rankDir: 'LR',                      // left-to-right
            nodeSep: 64,                        // vertical spacing between nodes at same rank
            rankSep: 176,                       // horizontal spacing between ranks (card + curve)
            edgeSep: 12,
            padding: 40,
            fit: false,                         // fitToView() applies the min-zoom floor
            nodeDimensionsIncludeLabels: true,  // the card box IS the node's whole box
        };
    }
}
```

### 5.2 Dagre layout choice

`cytoscape-dagre` with `rankDir: 'LR'` produces a left-to-right DAG layout where:
- Source nodes (no dependencies) are on the left.
- Sinks (nodes nothing depends on) are on the right. The **caller node** is wherever topology puts it — it carries the `.caller` class as its visual marker, not a fixed position ([Pipeline Contract §9](pipeline-contract.md#9-the-caller-node-result-node)). A pipeline may have no caller node at all.
- Topological levels are visually distinct columns.
- Edges flow left-to-right with arrowheads.

Alternatives if dagre doesn't fit a specific pipeline's shape:
- `rankDir: 'TB'` — top-to-bottom (better for tall, narrow pipelines).
- `cytoscape-elk` with `elk.algorithm: 'layered'` — more sophisticated layout for complex graphs.

v1 ships with dagre LR. The layout choice is configurable per pipeline in a future version.

### 5.3 Graph stylesheet — the node CARD (080, the v2 canvas)

The Cytoscape stylesheet reads design tokens at init time via `readDesignTokens()` (§3.4) and uses them throughout. No hardcoded hex values in the JS — every value resolves through a custom property with a hex fallback. When the theme changes, `updateTheme()` re-reads the tokens and re-applies the stylesheet without a page reload.

**The 080 canvas (owner-approved mock `2026-09-05-editor-canvas-v2.html`):** the stage is a dotted grid (`radial-gradient` on `--grid-dot`, 22px, drawn on the stage so it stays put while the world pans over it), every node is the mock's card, edges are beziers with three states, and the chrome is the mock's: controls bottom-right, a minimap beside them, legend chips bottom-left, a keyboard hint top-left. The canvas tokens live in ONE block at the top of `app.css` (`/* app tokens (080): node-type accents */`): the five node-type accent pairs (`--type-dql/-bg`, `--type-dml/-bg`, `--type-ddl/-bg`, `--type-pipeline/-bg`, `--type-calc/-bg`), `--brand`, `--brand-soft`, `--border-faint`, `--grid-dot`, `--edge`, `--edge-active`, `--edge-done`. Tokens the mock derived from the design system BRIDGE to it (and match its light and dark hexes exactly); the rest are `color-mix` bridges, so all nine themes re-skin the canvas, not just light/dark. 079's shell v2 is told the block exists and must not redeclare it.

Every node is a 236px rectangular card — 272px on a wide stage (082 §A, below) — and as TALL as its content, `--pe-card-h` being a floor rather than a height (082 addendum, below). Inside it, top to bottom:

1. **The head** — an icon **tile** (34px rounded square) washed in the node type's accent pair (`--type` on `--type-bg`, set inline per card), the node **id** (semibold, one line, right-edge ellipsis), and the **type eyebrow** (11px uppercase, `.06em` tracking, in the type colour). The tile's glyph is the card's **ONE** glyph (059b's rule survives): `db` / `table` / `boxes` / `workflow` / `file` from the vendored sprite — generic, never a vendor logo; the engine's identity is the source fact's TEXT.
2. **Up to three fact lines in mono** (11.5px, a muted CSS bullet for a marker — the mock's small kind icons have no glyph in the fenced sprite, and reusing `#db` would duplicate the DQL tile): source · dialect / template `@ v` / output for SQL types; `kind → context_key` and the input names for a CALCULATOR; child `@ v` and the parameter names for a PIPELINE. The dialect is resolved client-side from the workspace's datasource listing (the body is portable across environments, contract §11.1) and upgrades in place when the listing lands. Template paths truncate from the LEFT (the leaf identifies).
3. **The footer** — state dot + label (`Pending` / `Running…` / `Done` / `Failed` / `Aborted`) on the left, the last run's numbers on the right (`5 rows · 37 ms`, tabular; a CALCULATOR shows `= "2026-Q3" · 1 ms`). The wire carries `duration_ms` and `rows_out` only (SseEventProjection) — there is no `rows_in`, so the mock's `in → out` collapses to the out count. Before any run the numbers are absent, never a placeholder.

**State encoding (the mock's).** Running: brand border (canvas), a pulsing state dot and an indeterminate progress line (both pure CSS on the card, so `prefers-reduced-motion` stops them with one media query — the 059 JS border pulse is retired). Done: success dot, and the out-port turns `--edge-done`. Failed: danger border and footer. Hover: `translateY(-2px)` + `--shadow-lg` lift and the hover-only **expand affordance** — both CSS, both driven by a JS-toggled `.pe-card-hover` class because the label container is `pointer-events: none` and CSS `:hover` never fires (Cytoscape's own node `mouseover`/`mouseout` stand in). Selection: brand border over a full-opacity `--brand-soft` underlay padded 3px — the mock's `0 0 0 3px var(--brand-soft)` ring; Cytoscape has no box-shadow, and the underlay paints BEHIND the card so nothing dims.

**Edges — the mock's bezier and its three states.** `unbundled-bezier` with per-edge control points computed once after layout (`applyEdgeCurves`), offset `max(60, dx/2)` — the curve leaves the source port horizontally and enters the target port horizontally. `--edge` at rest (2px, round caps); `.active` while the TARGET runs — `--edge-active`, dashed `[6 8]`, with the `flow` animation stepped on a rAF loop over `line-dash-offset` (canvas has no keyframes; the loop starts when an edge activates, stops itself when none remain, and never starts under `prefers-reduced-motion`); `.done` after the target ran — `--edge-done`, arrow included. The transitions live in `setNodeState()`: the target's state drives its incoming edges. The mock's blurred glow path has no Cytoscape counterpart (no canvas filters); the wider 2.5px active stroke carries the emphasis instead. **Row counts may ride the edge** (owner-undecided, shipped behind a class): when a node completes, its OUTGOING edges take `edge.rows` + the count as `rowLabel` — small mono on a page-coloured backing.

**Fit never zooms IN past 1.0.** The 059 floor stays (`FIT_MIN_ZOOM 0.75` — three nodes fill the pane); 080 adds the ceiling (`FIT_MAX_ZOOM 1.0`) — a one-node pipeline used to fit to 3×, which is the owner's "default zoom too big". `clampFitZoom` is pure; `graph-stylesheet.test.mjs` pins both ends.

**The card scales with the stage, because the zoom may not (082 §A).** The ceiling above is the
reason "at 2560 the cards read small" cannot be answered by fit: a wider window can only ever show
the same card with more empty canvas around it. So the CARD steps up instead. `.pe-stage` is a
`container-type: inline-size` query container; at a stage inline size of **1200px or more** the
step-up lands on `#cy-canvas` — `--pe-card-w: 272px`, `--pe-card-h: 170px` and every
`--pe-card-fs-*` a pixel larger. It lands on the canvas rather than on the stage because a
container cannot be styled by its OWN query, and because `readDesignTokens(containerId)` reads the
geometry back from exactly that element: the Cytoscape node box and the HTML overlay step together
or not at all (reading `documentElement`, as it did before 082, would have returned the 236px base
for ever — a container query is invisible there). The threshold is on the STAGE, not the viewport,
because the same 2560px window has a different stage depending on the rail and the settings
sidebar. A resize that crosses the threshold while the page is open is caught by a `ResizeObserver`
on the stage: `refreshCardMetrics()` re-reads the tokens and, only if the box actually moved,
re-applies the stylesheet and re-runs the layout — dagre's separation is tuned to the card width.
`card-scale.test.mjs` pins the container read, the `:root` colour read that must NOT move, and the
only-when-changed contract.

**Cytoscape is handed COLOURS, never token text (082 addendum P1).** `getComputedStyle(root)
.getPropertyValue('--edge')` returns a custom property's token stream VERBATIM, and four of the
canvas tokens above are `color-mix()` bridges (`--brand-soft`, `--border-faint`, `--grid-dot`,
`--edge`). Cytoscape parses colours itself, cannot read `color-mix(in srgb, …)`, logs *The style
property `line-color: color-mix(…)` is invalid* and falls back — so the first paint was already
degraded and the re-render on a theme switch collapsed the edges into thick grey bands. Every
colour token is therefore resolved through a PROBE before the stylesheet is built: an
`aria-hidden`, zero-size span is appended to `<body>`, `color: var(--token)` is assigned to it, and
`getComputedStyle(probe).color` gives back the `rgb(…)` the browser itself computed — `var()`
substitution, the mix, and the theme in force, all done by the engine that owns the rules. One
probe per read, removed before the function returns. The fallback contract is unchanged: an
UNDECLARED token still yields the mock's light hex, so a stale theme file degrades rather than
blanking the graph. 
**And what the browser answers is not always `rgb()`.** Measured on Chrome 148 against the live
editor (2026-09-07): a `color-mix()` token computes to CSS Color 4's **`color(srgb 0.412745
0.436078 0.480392)`**, which Cytoscape parses no better than the `color-mix` it came from. The
first cut of this fix accepted only strings beginning `rgb`, fell back to the raw token, and left
the defect in place with a green unit test. `toLegacyRgb` normalises the `color(srgb …)` form; a
1x1 canvas sample stands behind it for any syntax it does not know, and the raw token remains the
last resort.

**The theme swap needed a second fix, and it is the one the owner actually saw.** `updateTheme()`
re-applied with `cy.style(array)` — which does NOT replace a live graph's stylesheet. It resets the
whole style to Cytoscape's DEFAULTS. Measured after one call: `line-color` `#999`, edge `width`
30px, node `background-color` `#999` — "the re-render on a theme switch collapses edges into thick
grey bands", exactly. The re-apply is `cy.style().fromJson(sheet).update()`
(`applyStylesheet()`), whose one condition is that the sheet be JSON: **`fromJson` silently drops
every function value**, which is why the node height in the stylesheet is a plain token and the
measured per-node height is an element BYPASS (below). A guard in `card-height.test.mjs` fails the
build if any stylesheet entry becomes a function again. 080's `wheelSensitivity: 0.3` is gone with
it — Cytoscape warns on every init that a custom sensitivity is unsupported, and the ± controls are
the supported way to change the feel. `graph-colour-tokens.test.mjs` pins the resolution, the
fallback and the absent sensitivity; a browser test switches the theme on a live editor and demands
zero complaints plus an edge colour that both parses and CHANGED.

**The card sizes to its content, and the node box follows it (082 addendum P1).** `--pe-card-h` was
a fixed `height`, and a card with three fact lines overflowed it: the footer painted below the
node's own border. `.pe-card` takes it as a `min-height` now. That alone would only move the defect
— the Cytoscape node underneath paints the chrome (surface, border, selection ring) and is the
anchor whose vertical centre the edge ports sit at, so a card taller than its node comes apart from
its own frame. `syncCardHeights()` therefore measures each rendered card after layout and writes
the height onto its node twice: as `cardH` DATA (what the minimap and the model read) and as an
element style BYPASS (what Cytoscape paints). The stylesheet's `height` stays the plain token — the
floor — because a function value there does not survive `fromJson()`, and a height that holds until
the first theme switch and then collapses every node to a 30px box is worse than none. `offsetHeight` is deliberate — the
html-label container carries the pan/zoom transform, so `getBoundingClientRect()` would feed the
zoom back into the model. Because dagre's rank separation is tuned to the card box, a changed height
re-runs the layout (`runLayout`), bounded at two passes: the second measures the same content and
finds nothing to change. The minimap reads the same per-node height. `card-height.test.mjs` owns the
stylesheet function, the measurement, the no-op second call, the zero-height guard and the CSS floor.

**The minimap** (no Cytoscape equivalent — plain DOM, painted by `renderMinimap()` after layout): nodes as small bars carrying the state colour (updated on every `setNodeState`), the viewport rectangle in `--brand` re-read from `cy.extent()` on pan/zoom. It is `pointer-events: none` — orientation, not navigation.

```javascript
function buildStylesheet(t) {        // t = readDesignTokens() output
    const cardW = t.cardW || 236, cardH = t.cardH || 148;
    return [
        { selector: 'node', style: {
            'background-color': t.nodeSurface,          // --surface-raised
            'width': cardW, 'height': cardH,            // --pe-card-w / --pe-card-h
            'shape': 'round-rectangle',
            'border-width': 1, 'border-color': t.nodeBorder,   // --border-subtle
            'corner-radius': t.cardRadius,              // --radius-lg
        } },
        { selector: 'node.running', style: { 'border-color': t.brand, 'border-width': 2 } },
        { selector: 'node.success', style: { 'border-color': t.nodeSuccess, 'border-width': 2 } },
        { selector: 'node.failed',  style: { 'border-color': t.nodeFailed,  'border-width': 2 } },
        { selector: 'node.aborted', style: { 'border-color': t.nodeAborted, 'border-width': 2, 'opacity': 0.5 } },
        // SELECTION — brand border over the brand-soft ring (underlay, BEHIND the card).
        { selector: 'node:selected', style: {
            'border-width': 2, 'border-color': t.brand,
            'underlay-color': t.brandSoft, 'underlay-opacity': 1, 'underlay-padding': 3,
            'underlay-shape': 'round-rectangle',
        } },
        // Edges — unbundled-bezier, control offset max(60, dx/2); three states.
        { selector: 'edge', style: {
            'width': 2, 'line-cap': 'round', 'line-color': t.edgeIdle,
            'target-arrow-color': t.edgeIdle, 'target-arrow-shape': 'triangle', 'arrow-scale': 0.9,
            'curve-style': 'unbundled-bezier',
            'source-endpoint': (cardW / 2) + 'px 0px', 'target-endpoint': -(cardW / 2) + 'px 0px',
        } },
        { selector: 'edge.active', style: {                       // the target is running
            'width': 2.5, 'line-color': t.edgeActive, 'target-arrow-color': t.edgeActive,
            'line-style': 'dashed', 'line-dash-pattern': [6, 8],  // + JS-stepped dash offset
        } },
        { selector: 'edge.done', style: {                         // the target ran
            'line-color': t.edgeDone, 'target-arrow-color': t.edgeDone,
        } },
        { selector: 'edge.rows', style: {                         // row counts, behind a class
            'label': 'data(rowLabel)', 'font-size': 11, 'color': t.edgeLabelText,
            'text-background-color': t.edgeLabelBg, 'text-background-opacity': 1,
        } },
        { selector: 'edge.secondary', style: { 'line-style': 'dashed' } },   // reserved, unused
    ];
}
```

`readDesignTokens()` returns exactly the keys referenced above — `brand`, `brandSoft`, `edgeIdle`, `edgeActive`, `edgeDone`, `nodeSurface`, `nodeBorder`, `nodeSuccess`, `nodeFailed`, `nodeAborted`, `edgeLabelText`, `edgeLabelBg`, `cardW`, `cardH`, `cardRadius` — each read from the custom property with a hard hex fallback (the mock's light values), so a stale theme file cannot blank the graph. The retired `--node-selected-ring`/`--edge-*-stroke` tokens in `app.css` remain for the banner and node-list accents; the canvas no longer reads them.

### 5.4 Event handlers

**Tapping a card SELECTS it and fills the dock's Details tab** (080 §B — the 065
split between select-only and open died with the inspector overlay: there is no second
pane to keep closed). `selectNodeById(id)` sets `selectedNode`, calls `cyNode.select()`
(the `node:selected` pseudo-class the §5.3 stylesheet keys on), mirrors
`aria-selected` onto the §14 DOM list, and calls `dock.selectNode(id)` — the mock's
`select()`, which fills Details and surfaces that tab. Tapping the canvas background
clears the selection (`dock.clearSelection()`), not the tab.

```javascript
// init.js
this.cy.on('tap', 'node', (evt) => this.selectNodeById(evt.target.data().id));
this.cy.on('tap', (evt) => { if (evt.target === this.cy) { this.selectedNode = null; this.dock.clearSelection(); } });
```

| Gesture | Effect |
|---|---|
| Tap a card / click a node-list row | **Select + fill Details** — highlight, `selectedNode`, `aria-selected`, the dock on the Details tab |
| Click a card's `.pe-card-open` expand button | The same — the explicit route in (what the 065 inspector button became) |
| `Enter` / `Space` on the focused node-list row | The keyboard twin of the button |
| Tap the canvas background | Clear the selection |
| `F` (no modifier, not in an input) | Fit the graph — the hint pill's shortcut |

The expand button is part of the html label, so it rides the pan/zoom transform with its
card. The label container is `pointer-events: none` (that is what keeps the canvas's
pan, drag and tap working under it); `.pe-card-open` re-enables pointer events **for
itself alone**, and its handler calls `stopPropagation()` so the graph's tap-to-select
does not also fire. One **delegated** listener on the graph container serves every card
(the html-label re-renders its template on each `data`/`style` event, so per-button
listeners would leak), guarded by a flag on the container so the history-restore
re-render (§7.4) cannot stack a second one.

---

## 6. Node States and Visual Mapping

### 6.1 State machine

```
                ┌─────────┐
                │  idle   │  (initial render)
                └────┬────┘
                     │ node_started
                     ▼
                ┌─────────┐
        ┌────── │ running │ ──────┐
        │       └─────────┘       │
        │ node_completed          │ node_failed
        ▼                         ▼
┌───────────────┐          ┌───────────────┐
│    success    │          │    failed     │
└───────────────┘          └───────────────┘

Nodes that never reach a terminal state of their own:
                ┌─────────┐
                │ aborted │
                └─────────┘
Reached when (a) an upstream node failed and this one never ran, or
(b) the execution was cancelled (execution_aborted) — in which case a
node still `running` also becomes `aborted`, because the server has
interrupted its statement.
```

### 6.2 CSS class → visual mapping

All colors derive from the 080 canvas tokens (`app.css`'s node-type accent block) and the design system. See Appendix A for the full token mapping.

**State is an accent border on the neutral card plus the footer's dot, never a background fill** — colour carries STATE and never competes with the tile for TYPE or the ring for SELECTION. The card's surface and label are constant across all five states.

| State | CSS class | Accent token | Animation | Meaning |
|---|---|---|---|---|
| `idle` | `.idle` | — (neutral card: `--border-subtle`) | none | Initial state. Applied in `buildElements()` so all five statuses are symmetric classes. |
| `running` | `.running` | `--brand` | state-dot pulse + indeterminate progress line (CSS on the card); incoming edges flow (JS-stepped dash offset) | Node is currently executing |
| `success` | `.success` | `--accent-success` | none; the out-port turns `--edge-done` | Node completed successfully |
| `failed` | `.failed` | `--accent-danger` | none | Node failed; pipeline aborted |
| `aborted` | `.aborted` | `--accent-warning`, 0.5 opacity | none | Node never ran (dependency failed), or was interrupted by cancellation |

Every animation the card owns is CSS (pulse, progress slide, status-dot pulse), so one
`prefers-reduced-motion: reduce` media query stops them all — the 059 JS border pulse is
retired. The edge `flow` is the one canvas animation and is JS (a rAF loop stepping
`line-dash-offset`; a Cytoscape stylesheet has no keyframes), gated on the same media
query in JS because the graph is a `<canvas>` CSS cannot reach. Under reduced motion the
dashes stand still and the accents carry the state alone.

Colors automatically adapt to the active design system theme. No hardcoded hex values.

### 6.3 State transitions via SSE events

Event payloads are defined in [REST API §6.4](rest-api.md#64-event-types); the table below is only the graph's reaction to them.

| SSE event | Graph action |
|---|---|
| `execution_started` | Reset all nodes to `idle`, edges to rest. Events log resets; the top bar's clock starts. Disable Execute button. |
| `node_started` | Node → `running`; incoming edges → `.active` (inside `setNodeState`). |
| `node_completed` | Node → `success`; incoming edges → `.done`; the node's run line fills; outgoing edges take the row label. A CALCULATOR's `context_value` reaches the footer and the Context; a PIPELINE node's `child_execution_id` reaches the Details pane. |
| `node_failed` | Node → `failed`; incoming edges clear `.active`. The failure record joins the dock's Errors tab. All pending nodes → `aborted`. |
| `pipeline_completed` | Terminal. Every node is `success` or `aborted`. The ONE success toast; the top bar's status takes its final text. |
| `pipeline_failed` | Terminal. Show error modal (§9); the record joins the Errors tab. |
| `data_ready` | Show the result in the dock's Results tab (§10) — emitted after `pipeline_completed` and **only when the pipeline has a caller node**. |
| `execution_aborted` | Terminal. Every node not already `success`/`failed` → `aborted`; banner "Execution aborted ({reason})" using the event's `reason` (`client_disconnect` \| `cancelled` \| `shutdown`); Execute button re-enabled. See §15. |

**Every event, of every kind, also lands in the dock's Events tab in arrival order**
(§10.6) — `sse.js`'s dispatch calls `editor.logEvent` first, before the switch above.
The toast only ever announces the terminal events.

The stream closes after exactly one terminal event ([REST API §6.5](rest-api.md#65-event-ordering-guarantee)); the editor treats stream close without a terminal event as connection loss (§15.1).

### 6.4 Implementation: state update on SSE event

```javascript
// sse.js
class SseHandler {
    constructor(graph) {
        this.graph = graph;
    }

    onEvent(eventType, data) {
        this.lastEventType = eventType;
        switch (eventType) {
            case 'execution_started':
                this.executionId = data.execution_id;    // needed by §15.1 status poll
                this.graph.resetAllNodes();
                break;
            case 'node_started':
                this.graph.setNodeStatus(data.node_id, 'running');
                break;
            case 'node_completed':
                this.graph.setNodeStatus(data.node_id, 'success');
                break;
            case 'node_failed':
                this.graph.setNodeStatus(data.node_id, 'failed');
                this.graph.abortPendingNodes(data.node_id);
                break;
            case 'pipeline_completed':
                // All nodes should already be success/aborted
                break;
            case 'pipeline_failed':
                window.errorModal.show(data.error);
                break;
            case 'data_ready':
                window.resultPanel.show(data);
                break;
            case 'execution_aborted':
                // Terminal event for every cancellation path (rest-api §6.4.8):
                // client disconnect beyond grace, explicit DELETE, server shutdown.
                this.graph.abortUnfinishedNodes();
                window.banner.warn(`Execution aborted (${data.reason}).`);
                break;
        }
        window.a11y.announce(eventType, data);       // §14.2 live-region announcement
    }

    isTerminal(eventType) {
        return ['pipeline_completed', 'pipeline_failed', 'execution_aborted'].includes(eventType);
    }
}

// graph.js (methods on PipelineGraph)
// The five status classes are mutually exclusive and `idle` is one of them (applied at
// build time in §5.1), so every transition is remove-all-then-add-one.
const STATUS_CLASSES = 'idle running success failed aborted';

setNodeStatus(nodeId, status) {
    const node = this.cy.getElementById(nodeId);
    node.removeClass(STATUS_CLASSES);
    node.addClass(status);
    node.data('status', status);
    window.a11y.syncStatus(nodeId, status);      // §14 DOM list stays in lockstep
}

abortPendingNodes(failedNodeId) {
    // A node failed: nodes that never started can no longer run.
    this.cy.nodes()
        .filter(n => n.hasClass('idle'))
        .forEach(n => this.setNodeStatus(n.id(), 'aborted'));
}

abortUnfinishedNodes() {
    // Execution cancelled (execution_aborted): anything not already terminal is aborted —
    // including nodes still `running`, whose statements the server has interrupted.
    this.cy.nodes()
        .filter(n => !n.hasClass('success') && !n.hasClass('failed'))
        .forEach(n => this.setNodeStatus(n.id(), 'aborted'));
    this.cy.edges().removeClass('active');
}

resetAllNodes() {
    this.cy.edges().removeClass('active');
    this.cy.nodes().forEach(n => this.setNodeStatus(n.id(), 'idle'));
}
```

---

## 7. Execute Button Flow

### 7.1 User action

1. User fills in parameter form (if pipeline has parameters).
2. Clicks **Execute**.
3. Execute button becomes disabled (`aria-busy="true"`), label changes to "Executing..."; the **Cancel** button appears (§15.2).
4. Values are coerced to their declared wire types (§7.2) and posted as typed JSON. Page opens the SSE stream via `fetch` (POST to `/api/v1/pipelines/{id}/execute` with `Accept: text/event-stream`).
5. SSE events flow in → graph, node list and live region update in real-time.
6. On a terminal event: button re-enables, Cancel disappears, and — result panel (`data_ready`, §10), success banner (`pipeline_completed` with no caller node), error modal (`pipeline_failed`, §9), or aborted banner (`execution_aborted`, §15).
7. If the stream ends **without** a terminal event, that is connection loss, not completion (§15.1).

### 7.2 Implementation

```javascript
// execute.js
async function executePipeline() {
    const pipelineId = window.pipelineId;
    const parameters = collectParameters();
    const executeBtn = document.getElementById('execute-btn');

    executeBtn.disabled = true;
    executeBtn.textContent = 'Executing...';

    try {
        const response = await fetch(`/api/v1/pipelines/${pipelineId}/execute`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'text/event-stream',
                // Cookie-authenticated state-changing call → CSRF token from the `dp_csrf`
                // cookie (Auth §8.4). Custom headers use the DP- prefix throughout.
                'DP-CSRF-Token': readCookie('dp_csrf'),
            },
            credentials: 'same-origin',
            body: JSON.stringify({ parameters }),   // typed JSON, never FormData — see collectParameters()
        });

        if (!response.ok) {
            const error = await response.json();
            window.errorModal.show(error.error);
            return;
        }

        // Consume SSE stream
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const events = buffer.split('\n\n');
            buffer = events.pop();         // incomplete event stays in buffer

            for (const eventStr of events) {
                const event = parseSseEvent(eventStr);
                if (event) {
                    window.sseHandler.onEvent(event.type, event.data);
                }
            }
        }
        // Stream ended. If no terminal event arrived, the connection dropped (§15.1).
        if (!window.sseHandler.isTerminal(window.sseHandler.lastEventType)) {
            await handleConnectionLoss(window.sseHandler.executionId);
        }
    } catch (error) {
        // A thrown reader is the same condition as an early close: the stream is gone
        // and the server will cancel after the grace period (§15.1).
        await handleConnectionLoss(window.sseHandler.executionId);
    } finally {
        executeBtn.disabled = false;
        executeBtn.textContent = 'Execute';
    }
}
```

#### Parameter coercion

`collectParameters()` MUST NOT post raw form strings. [Pipeline Contract §6.3](pipeline-contract.md#63-wire-encoding-of-input-parameter-values) makes coercion **strict**: the server rejects a JSON string where a number/boolean is declared and vice versa (`pipeline.execution.invalid_parameter_type`). Since every `<input>` yields a string, the editor converts to the declared wire type before serializing.

```javascript
// execute.js — declared types come from the embedded pipeline JSON (`parameters` map).
function collectParameters() {
    const form = document.getElementById('parameter-form');
    const declared = window.editor.pipeline.parameters || {};
    const params = {};

    new FormData(form).forEach((raw, key) => {
        const decl = declared[key];
        if (!decl) return;                                   // not a declared parameter
        const value = typeof raw === 'string' ? raw.trim() : raw;
        if (value === '' && !decl.required) return;          // omit → server applies the default
        params[key] = coerceParameter(value, decl.type, key);
    });
    return params;
}

function coerceParameter(value, type, key) {
    switch (type) {
        // Number-on-wire types → JSON numbers, never strings.
        case 'INTEGER':
        case 'SMALLINT':
        case 'BIGINT':
        case 'DECIMAL':                 // precision ≤ 15 (Type System §3.1)
        case 'FLOAT':
        case 'DOUBLE': {
            const n = Number(value);
            if (value === '' || Number.isNaN(n)) throw new ParameterError(key, type, value);
            return n;
        }
        // String-on-wire numerics: precision must survive, so they stay strings.
        // Sending them as JSON numbers is an explicit server-side rejection.
        case 'BIGINTEGER':
        case 'BIGDECIMAL':
            return String(value);

        // Boolean-on-wire → JSON true/false, never "true"/"on".
        case 'BOOLEAN':
            return value === true || value === 'true' || value === 'on';

        // TIMESTAMP requires an explicit offset or Z — the server never guesses a zone.
        // <input type="datetime-local"> yields a zone-less string, so we attach the
        // browser's offset explicitly rather than sending it bare.
        case 'TIMESTAMP':
            return withExplicitOffset(value);       // "2026-08-07T14:30" → "2026-08-07T14:30:00+02:00"

        case 'DATE':                    // exact ISO 8601 YYYY-MM-DD
        case 'TIME':                    // exact ISO 8601 HH:MM:SS[.ffffff]
        case 'STRING':
        default:
            return String(value);
    }
}
```

- Unparseable input never leaves the browser: `ParameterError` is caught by the Execute handler, which marks the offending field invalid (`aria-invalid="true"` + `.ds-field--error`) and aborts the POST.
- Type-appropriate input widgets reduce, but do not remove, the need for coercion — `inputType(param.value.type)` (§4.2) maps `INTEGER`→`number`, `BOOLEAN`→`checkbox`, `DATE`→`date`, `TIMESTAMP`→`datetime-local`, everything else →`text`.
- Client-side coercion is a UX convenience, **not** a validation boundary — the server re-validates every parameter regardless.

#### SSE frame parsing

```javascript
function parseSseEvent(raw) {
    let type = null;
    let data = null;
    for (const line of raw.split('\n')) {
        if (line.startsWith('event: ')) type = line.slice(7).trim();
        else if (line.startsWith('data: ')) data = JSON.parse(line.slice(6));
    }
    return type && data ? { type, data } : null;
}
```

Heartbeat frames ([REST API §6.6](rest-api.md#66-heartbeat-keepalive)) are SSE comment lines (`: heartbeat`) with no `event:`/`data:` field, so this parser returns `null` for them and they are skipped — they exist only to keep the connection open through proxy idle timeouts.

### 7.3 Why fetch + ReadableStream, not EventSource

The native `EventSource` API only supports GET requests. Our execute endpoint is `POST /pipelines/{id}/execute` (parameters in the body). So we use `fetch` with `Accept: text/event-stream` and manually parse the SSE stream from the response body via `ReadableStream`. This is a well-known pattern; works in all modern browsers.

---

## 8. The dock's Details tab (was: the node inspector overlay)

**Details is a TAB of the bottom dock** (080 §B, owner ruling 2026-09-05: "move that
pane in the bottom along with Result and Errors, minimizable"). The 065 overlay —
scrim, dialog role, focus trap and all — is **deleted**: `inspector.js`, its DOM and its
CSS are gone, and so is the focus contract they existed for (there is no panel to focus
into or out of). What survived of its state machine is the *re-target-in-place* rule:
selecting a second node while Details is showing replaces the content, never flashes an
empty pane.

**Open.** Selecting a node fills Details and surfaces the tab (§5.4) — the card's
expand affordance and `Enter`/`Space` on a focused node-list row are the same route,
not a second one. The SQL partial loads on **selection** for SQL-backed types (the tab
is the only SQL surface left; the 065 "one request per opening" became "one per
selection", debounced with parameter edits exactly as before).

**Layout.** Two columns inside the pane: the meta column (300px) — the type tile + id,
then the key/value facts — and the statement column, headed by the section label and
the "Open template →" link.

**Content, per node type** (built by `detailsMeta()` / `definitionHtml()` in init.js,
driven per-type by `details-pane.test.mjs`):

- **DQL / DML / DDL** — Source (datasource, or `tempdb (engine, per-execution)`),
  Template (`path @ v`), Output (the §8.1 wording — an omitted block on a DQL is
  "returns result to caller (default)"), Parameters (the pipeline's declared keys),
  and the **rendered SQL** (§8.3) in the statement column.
- **CALCULATOR** — Kind, Inputs (each body's expression beside what the last run
  resolved it to, from the §7.2 Context), Writes (the context key), Value (the last
  run's), and the **evaluation** in the statement column (`kind(inputs) → key = value`
  with the resolved inputs as comments) — no fetch: a calculator has no SQL.
- **PIPELINE** — Child (`path @ v`, linked), Parameters (the mapping passed down),
  Output, Execution (the last child execution id, from `node_completed`'s
  `child_execution_id`), and the **child mapping** in the statement column.

The per-node **Failure** section the inspector carried is deliberately NOT reproduced
here: the dock's Errors tab (§9.1) renders the same record for the whole run, and the
mock's Details has no failure block. The record lost nothing — it has one home instead
of two.

### 8.1 Fields displayed### 8.1 Fields displayed

| Field | Source | Notes |
|---|---|---|
| Node ID | `node.id` | Meta header, beside the type tile |
| Type | `node.type` | The eyebrow in the type accent |
| SQL | `GET /partials/pipelines/{id}/nodes/{nodeId}/sql` | The node's **rendered** SQL — see §8.3 (SQL-backed types only) |
| Source | `node.source` | Datasource name or `tempdb (engine)` |
| Template | `node.template` | `{id, version}` — link to the template editor (§8.2) |
| Output | `node.output` | For DQL: target + table/mode; an **omitted** `output` renders as "returns result to caller (default)" (§9.1). For DML/DDL: "side effect" |
| Parameters | the pipeline's declared keys | The binds the SQL references |
| Calculator | `node.kind`, `node.inputs`, `node.context_key` | Kind / Inputs (expression → resolved) / Writes / Value |
| Child | `node.pipeline`, `node.parameters` | Child `@ v`, the mapping passed down, the child execution id |
| Last run | `nodeStates[node.id]` | Present only after a run touched the node |

Long values wrap via `overflow-wrap: anywhere` rather than widening the pane; every
value also rides on its element's `title` (the §9.4 rule — the truncated text is never
the only copy).

### 8.2 Template link

The statement column's "Open template →" navigates to `/templates/editor?name={id}` — the route that exists (§9.6). The pinned version is not part of the route; the template editor always opens the current version. A PIPELINE node's head instead links "Open child →" to the explorer filtered on the child.

### 8.3 The SQL section (rendered, resolved server-side)

SQL does not live in pipeline nodes — [Pipeline Contract §2](pipeline-contract.md) principle 3: *"SQL/FTL lives in template entities, not inline in the Pipeline."* So "show the SQL for a node" is a resolution problem: the server resolves the node's **pinned** `template: {id, version}` (never the latest), assembles the pipeline's own parameter context, and renders.

```
GET /partials/pipelines/{id}/nodes/{nodeId}/sql?parameters=<url-encoded JSON>
```

- **Scope:** `READ_RESOURCES`, matching every other read partial. The existing `POST /partials/templates/{id}/versions/{version}/render` is deliberately NOT reused: it requires `MUTATE_PIPELINES_TEMPLATES` (an author scope, so a read-only viewer would be refused) and takes a free-form context that bypasses the pipeline's own parameter declarations.
- **Wire format:** the `parameters` query value is a JSON document in [contract §6.3](pipeline-contract.md) wire form, built client-side by the page's own `coerceValue` — the same function the execute path uses (§7.2). One coercion path for both surfaces; `ParameterCoercion` is strict, so raw form strings would be rejected by design. GET, not POST: it is a read, needs no CSRF token, and matches the `/partials/**` GET idiom.
- **Three context outcomes, not one.** *Bound* — every parameter supplied or defaulted; renders with the bound context. *Sampled* — binding rejected only on unsupplied REQUIRED parameters; renders with `ParameterBinder.sampleContext()` (the §12.6 dry-render context: defaults where present, type-appropriate sample values otherwise) and the panel labels which parameters were sampled. *Rejected* — a supplied override failed §6.3 coercion; the partial names the parameter and renders **no SQL** — SQL built from a value the executor would refuse is worse than no SQL.
- **The non-render states.** A PIPELINE node has no template by contract (§4.6) — the partial shows the child-pipeline state (name @ version, linked), not an empty SQL block. A pinned `{id, version}` absent from the workspace registry, a `TemplateRenderException`, and an unknown node id each get their own `.ds-empty` state. `Node.template` is never null server-side (`Node.fromJson` binds `template ?: TemplateRef()`), so the "no template" branch keys on the node type / a blank template id — a null check would never fire.
- **Loading:** `htmx.ajax` **on selection** (080 §B — the Details tab is the only SQL surface left, so selection and opening are the same act), and again (debounced ~300ms, the list-screen search delay) when a parameter override changes. CALCULATOR and PIPELINE nodes skip the fetch — their statement column is the client-built evaluation / child mapping. The response swaps into `#pe-node-sql`; a `.ds-spinner` indicator rides the request.
- **The box (080).** The statement spans the statement column's full width,
  `white-space: pre` (**no wrapping** — wrapping destroys the indentation that carries
  the query's shape), mono 12.5px on `--surface-inset`, and scrolls inside the pane in
  both directions. The 065 overlay's `flex: 1 0 40%` floor died with the overlay: the
  dock's pane has a definite height (232px), so no floor arithmetic is needed — the
  column is a `grid-template-rows: auto 1fr` and the `<pre>` takes the `1fr`.
- **Highlighting and copy.** After the swap, `sql-highlight.js` re-highlights the `<code>` block — a zero-dependency, single-pass tokenizer (keywords, strings, comments, numbers, `${param}`/`:param` parameters), escaping each token's text as it is emitted (tokenize the RAW SQL, never the escaped string; token colours are `--pe-sql-*` custom properties resolving to design-system accents). The copy button reads the SQL from its `data-sql` attribute (or the `<code>` element's `textContent`) — never from the highlighted `innerHTML`, which carries `<span>` markup. The confirmation is the live region plus a 1.5s label swap on the button, **deliberately not a toast**: copy is high-frequency and self-evident, and a 6s notification per copy trains the user to ignore the stack the §9 terminal events need.

---

## 9. Error Display

### 9.1 Where a failure lives: the dock's Errors tab

**The Errors tab is the home of a failure record** (065 §B). Before 065 the 057 record
rendered *inside the results panel*, gated on `resultPanel.failure` — results and
failures are different objects with different lifetimes sharing one pane, and closing the
pane took both away. The record now has its own tab beside Results, and the two are
independently reachable at all times.

Two surfaces, two jobs, one record (080 §B removed the third — the inspector's
per-node Failure section — because the Errors tab renders the same record for the whole
run and the mock's Details has no failure block):

| Surface | Scope | What it shows |
|---|---|---|
| **Errors tab** (§10, the dock) | per **run** | one entry per failed node of the current-or-last run, newest last |
| **Error modal** (§9.2) | the run's terminal event | a one-line summary — a failure detail is not a dialog |

`PEErrorDetails.build(record)` is the one view-model both render; `details.js` owns
it and `sse-node-failure.test.mjs` pins it. Nothing about the wire changed: the
`node_failed` / `pipeline_failed` `error` object, its `caused_by` chain (outermost-first
on the wire, reversed to **root-cause-first** for humans) and the redaction rules are
exactly as 057 left them. This round re-homed what was already on the page.

An Errors entry is the summary line `node id · code`, then the message, the node line,
the correlation id, any `details` JSON, the rendered SQL, the collapsible **Exception**
section with the chain root-cause-first, and one `Copy failure detail` button for the
whole record. Under `error-detail=structured` the SQL and Exception sections are simply
absent — no empty panel, no apology. Empty state: *"No failures in this run."*

`pipeline_failed`'s execution-level record joins the same list (it is the record 057
built the failure view for, and moving the block out of Results without giving it the
Errors tab would have deleted 057's point from the page). Records matching on node, code
and message **dedupe**, so a node failure and the pipeline failure it caused list once.

### 9.2 The error modal — a one-line summary, and only that

`pipeline_failed` still raises the modal, and the modal still says one sentence:

```
┌──────────────────────────────────────────────────┐
│  Execution Failed                            [×] │
├──────────────────────────────────────────────────┤
│  We couldn't reach the 'pg-prod' database.       │
│                                                  │
│                          [Dismiss]               │
└──────────────────────────────────────────────────┘
```

It carries `error.user_message` (falling back to `error.message`). The **detail** —
code, correlation id, node line, SQL, exception chain — is in the Errors tab, on the page
the engineer is already looking at, and stays there after the modal is dismissed. The
modal is `role="alertdialog"`; `Esc` closes it, and it is the topmost rung of the §14.1
Escape ladder.

The `details` object is rendered verbatim wherever it appears, so it must never contain
connection secrets: the server's `node_failed` payload carries `datasource_name` and
`underlying_error`, never `jdbc_url` or credentials ([REST API §6.4.4](rest-api.md#644-node_failed);
redaction mechanism in [Observability](observability.md)). The editor does no redaction
of its own — it has nothing to redact with.

The graph also shows the failed node in red (§6.2), and its card's open button leads
straight to that node's Failure section.

### 9.3 Terminal events: modal for failure, toasts for the rest

The three terminal SSE events report differently:

- `pipeline_failed` **keeps the error modal** (§9.2) for its one-line summary, and puts
  the record in the Errors tab (§9.1).
- `pipeline_completed` and `execution_aborted` report as **toasts** via `DpToast.show`
  (Shape D, [UI Screens §5.1](ui-screens.md)): a stream-borne event has no HTTP response
  to attach an OOB swap to, and this is the one client-side toast builder that exists.
  The abort toast carries the event's `reason` in its body.
- All three also call `announceStatus`, as does the first failure of a run ("Errors (1)")
  — through `a11y.js`'s single live region (`#pe-live-region`), never a second one.

The running-progress banner stays at the toolbar for the `running` state.

---

## 10. The bottom dock: Details | Results | Errors | Events

**One dock, four tabs, two states, no close** (080 §B; the 065 rule stands — the old
panel's `×` set `resultPanel.visible = false` and left no way back short of re-running
the pipeline). The inspector overlay moved IN as the Details tab (§8), so the dock is
**always present**: the 065 `hidden` state has no page left to live on, and `minimized`
is renamed `collapsed` (the mock's chevron).

`dock.js` holds the state and the transitions and nothing else — no DOM, no Alpine, no
fetch — so `node --test` drives every row of the table below (`dock.test.mjs`). The
events log is a second pure module (`events.js`, §10.6). The template binds the fields
directly (`state`, `tab`, `errors.length`, `resultsRows`, `detailsNodeId`); there are no
derived getters to drift from what the browser renders.

- `dock.state ∈ {open, collapsed}`
- `dock.tab ∈ {details, results, errors, events}`
- `dock.errors: FailureRecord[]` — the 057 record, one per failed node of the **current
  or last** run (§9.1)
- `dock.resultsRows` — the Results tab badge: the caller result's row count on success
- `dock.detailsNodeId` — the node the Details tab is showing (§8)

### 10.1 Transitions — the whole table, no others

| Event | From | To |
|---|---|---|
| page load | — | `open`, tab `details` (nothing selected) |
| node selected | any | `detailsNodeId` set; tab `details`; state `open` |
| canvas background tap | any | `detailsNodeId` cleared; state and tab unchanged |
| execute started | any | `errors` cleared; state unchanged; the Results tab shows "previous run" while its page is from an earlier run |
| `data_ready` | any | `resultsRows` set; tab `results` unless `errors.length > 0`; state unchanged |
| `node_failed` (first of this run) | any | `open`, tab `errors` |
| `node_failed` (subsequent) | any | record appended; badge count updates; state and tab unchanged |
| user clicks the **chevron** | `open` ↔ `collapsed` | the other state |
| user clicks a tab | `collapsed` | `open`, that tab |
| user clicks a tab | `open` | that tab |
| user presses `Esc` | any | **no change** — a tab has nothing to close |

Two readings the table leaves open, resolved in the code and pinned by tests: a **first**
failure arriving while the user is on another tab still takes the tab (the failure
outranks the resting tab only once), and an unknown tab name is inert.

There is **no close.** `collapsed` renders the tab strip only — tabs, badges, and the
chevron flipped to restore — and the canvas reclaims the rest, which works because the
dock is the last **flex child** of `.pe-root` (§4.3). The open height is the mock's
232px pane under a 38px tab strip. There is no drag handle: it was not asked for.

### 10.2 The four tabs

**Details** — §8. **Results** — the body §10.4 describes, unchanged. **Errors** — the
list described in §9.1, with the count badge turning danger when `errors.length > 0`.
**Events** — §10.6.

Badges: Results shows the row count on success (the success wash), Errors the danger
count, Events the live event count. The first failure of a run announces
`"Errors (1)"` through `a11y.js`'s existing live region; the dock header carries **no**
`aria-live` of its own (§14.2).

### 10.3 Result delivery (unchanged)

Delivery is **uniform** — there is no inline-vs-claim-check split ([REST API §7](rest-api.md#7-result-delivery)). Every caller result is materialized in Redis before `data_ready` is emitted, and every `data_ready` carries the same fields: full `schema`, the **inline first page**, `total_rows`, `has_more`, `result_url`, `expires_at`.

### 10.4 On `data_ready`

`result.js` renders one body shape for every result size, inside the dock's Results tab ([REST API §6.4.7](rest-api.md#647-data_ready)):

```
┌──────────────────────────────────────────────────┐
│  Execution Result                            [×] │
├──────────────────────────────────────────────────┤
│  4,480 rows • 2377 ms • 4 nodes succeeded        │
│  Available until 14:35:02 (expires in 4m 58s)    │
│                                                  │
│  customer_id │ customer_name │ total_amount      │
│ ─────────────┼───────────────┼────────────────── │
│  1           │ Acme Corp     │ "12345.67"        │
│  2           │ Globex        │ "67890.12"        │
│  3           │ Initech       │ "1234.56"         │
│  ...                                             │
│                       showing 1–1,000 of 4,480   │
│                             [Load next page]     │
│                                                  │
│  [Download JSON] [Download CSV] [Download Arrow] │
└──────────────────────────────────────────────────┘
```

- The panel renders `data.rows` from the event directly — no fetch needed for the first page. When `has_more` is `false` (the common case) the first page IS the whole result and no cursor call is ever made.
- The table renders the first page as delivered. Page size is the server's `datapipelines.result.page-size-rows`, not a client constant — the editor never assumes 100 or 1000 rows.
- The grid is the shared `.ds-table` component ([UI Screens §5](ui-screens.md)) — the editor's bespoke `.pe-result-table` styles are gone; the container supplies scroll only.
- BIGDECIMAL / BIGINTEGER values arrive as strings (Type System wire rules) and are rendered as-is — the editor never parses them into JS numbers.
- Nothing is shown for a **pure-ETL pipeline**: with no caller node there is no `data_ready` event. The completion banner shows execution stats only.

### 10.5 Paging and download via the result cursor

Both "Load next page" and the download buttons use the same cursor endpoint ([REST API §7.2](rest-api.md#72-cursor-endpoint)):

```
GET /api/v1/executions/{execution_id}/result?offset=&limit=&format={json|arrow|csv}
```

- Paging is by `offset`/`limit`; ordering is stable because the result was fully materialized before the cursor existed.
- The editor's Prev/Next row is client-side cursor paging over the stored result — `resultPanel.prevPage()/nextPage()` with `hasPrev`/`hasNext`, `limit`-authoritative and `total_rows`-denominated (027b). That arithmetic is frozen; the execute-page redesign restyled the row (ghost buttons on tokens) without rewiring it.
- The cursor requires normal session auth + `read` scope + ownership — `result_url` is **not** a capability URL, so the editor sends its credentials like any other API call.
- **TTL is fixed.** Reading pages does not extend it. The panel shows the remaining time from `expires_at` and counts down.
- After expiry the endpoint returns `410` with `result.expired`; the panel replaces the table with **"Result expired — re-run the pipeline"** and a re-execute button. It does not retry.
- `result.execution_failed` (410) and `result.execution_not_found` (404) surface through the same error path (§9). The full endpoint error list is [REST API §7.6](rest-api.md#76-endpoint-errors).
- A result exceeding `datapipelines.result.max-size-bytes` never reaches this panel — the execution itself fails with `result.too_large` and the error modal explains that large datasets belong in `output.target: "datasource"` write-back, not in caller results.

### 10.6 The Events tab — every event, in arrival order

The owner: "put all the events in the list". Every SSE event the stream emits lands in
the Events tab as one timeline row — the toast only ever announces the terminal one
(§9.3). The row is the mock's anatomy: `+t.tttS` (t0 = the run's start), the marker
coloured by kind, the kind in mono, `node — text`, the duration. Text per kind:
`execution_started` names the pipeline @ version, the short execution id, the node and
parameter counts; `node_started` says *rendered template, executing on source* /
*evaluating kind* / *spawning child @v*; `node_completed` says *rows → output* /
*key = "value"* / *child id completed*; `node_failed` leads with the error code;
`data_ready` reports rows · columns; the terminal events carry the summary and the
total duration.

The tab carries a **live count badge**. The list **auto-scrolls while running** and
yields the moment the user scrolls up (the pin releases when they return to the bottom)
— a run should never yank the row the user is reading out of view, and a finished run
should never leave the list scrolled off its own tail.

`events.js` is pure: `formatEvent(kind, payload, ctx)` builds the text (defensively — a
field the wire omits degrades the sentence, never throws) and `createEventsLog()` holds
the arrival-order list, t0, the count and the pin, so `events.test.mjs` owns every row.
init.js does the row rendering (escaped by construction) and the scrolling.

**One toast per execution.** The pre-080 defect ("many success toasts on completion")
was NOT in the toast path — `sse.js` fires exactly one on `pipeline_completed`. It was
the 076 boost lifecycle: a history restore brings the editor's DOM back with the
previous component's Alpine state and its `@click` listeners still attached, and the
afterSettle rescue ran a bare `Alpine.initTree(root)`, which Alpine does not guard
against (its re-init marker is only set by `Alpine.clone`). Each restore **stacked
another component** on the same root; one Execute click then fired `executePipeline()`
once per stacked component, and N executions produced N terminal events and N toasts.
The fix is at the source: the rescue destroys the stale tree (`Alpine.destroyTree`)
before re-binding. `editor-toast-once.test.mjs` is the falsifier — two lifecycle passes
and one stream produce exactly one toast.

---

## 11. Editing Scope

### 11.1 The editor is read-only in v1

The editor is a **visualization + execution surface, not an authoring surface.** There is no edit mode, no save action, and no `?edit=true` parameter — the page has exactly one mode. The user can:
- View the pipeline (graph, settings, parameters, node details).
- Execute it and watch progress.
- Cancel a running execution.
- Inspect and page through results.
- Switch versions.

The editor issues no `POST`/`PUT`/`DELETE` against `/pipelines` — its only state-changing calls are execute (§7) and cancel (§15).

### 11.2 Authoring is LLM/MCP-first

Pipelines are authored, in order of intended use:
1. **LLMs via MCP** — the primary authoring path for this product ([MCP Server](mcp-server.md)).
2. **Direct JSON editing** via the REST API (developer path).
3. **The template editor** for the SQL templates a pipeline references (separate spec).

A pipeline authored by any of these routes is validated at save time — no invalid pipeline reaches the database ([Pipeline Contract §2](pipeline-contract.md#2-design-principles)) — so the editor can render whatever it loads without defensive validation.

### 11.3 UI edit mode is a ROADMAP item

In-UI authoring — editable node metadata, "save as new version", and eventually drag-and-drop topology editing (node palette, `cytoscape-edgehandles`, live validation) — is **out of v1 scope** and tracked in [ROADMAP](ROADMAP.md). It is deferred deliberately, not merely unbuilt: the authoring surface has to re-implement the save-time validation rules the server already owns, and the MCP path delivers the same outcome today. Design work on it is explicitly out of scope for the 2026-08 consistency campaign ([SPEC-REVIEW-2026-08 Part 3](SPEC-REVIEW-2026-08.md#part-3--out-of-scope-for-this-campaign)).

---

## 12. JavaScript Organization

### 12.1 File structure

```
modules/web/src/main/resources/static/
├── vendor/
│   ├── design-system/                      ← @acme/design-tokens (vendored CSS)
│   │   ├── vendor-manifest.json            ← versions + SHA-256 for ALL vendored assets
│   │   ├── tokens.css
│   │   ├── base.css
│   │   ├── motion.css
│   │   ├── primitives.css
│   │   ├── themes/
│   │   │   ├── saas.css                    ← default theme
│   │   │   ├── light.css
│   │   │   ├── dark.css
│   │   │   ├── professional.css
│   │   │   └── ...                         ← other themes
│   │   └── icons.css
│   ├── cytoscape/
│   │   ├── cytoscape.min.js                (v3.34.0, vendored)
│   │   ├── cytoscape-dagre.js              (extension, vendored)
│   │   └── style.css                       (cytoscape base styles)
│   ├── alpinejs/
│   │   └── alpine.min.js                   (v3.x, vendored)
│   └── dagre/
│       └── dagre.min.js                    (dagre layout engine, dependency of cytoscape-dagre)
├── css/
│   ├── app.css                             ← app-specific semantic tokens (derive from design system)
│   └── pipeline-editor.css                 ← editor-specific styles (uses design system tokens)
└── js/
    └── pipeline-editor/
        ├── init.js                         (page load → graph init)
        ├── graph.js                        (PipelineGraph class)
        ├── sse.js                          (SseHandler class)
        ├── execute.js                      (executePipeline, collectParameters, coercion)
        ├── details.js                      (DetailsPanel class + PEErrorDetails — §9.1)
        ├── dock.js                         (bottom dock state machine, PURE — §10)
        ├── events.js                       (Events tab log + per-kind text, PURE — §10.6)
        ├── error.js                        (ErrorModal class)
        ├── result.js                       (ResultPanel class — §10)
        ├── sql-highlight.js                (zero-dependency SQL tokenizer + highlighter — §8.3)
        └── a11y.js                         (DOM node list ↔ canvas sync, live region — §14)
```

`vendor-manifest.json` lives at `static/vendor/design-system/vendor-manifest.json` — one manifest for **all** vendored assets (design system CSS, Cytoscape, dagre, Alpine), written there by `scripts/sync-design-system.sh` ([DEVELOPMENT.md](../DEVELOPMENT.md)). It is not under `css/`.

### 12.2 No build step, no modules

All JS files use plain `<script>` tags. No ES modules, no bundler, no transpiler. This is intentional:
- Simpler deployment (static files served by Spring Boot).
- No npm dependency tree to manage.
- No CI build step for frontend.
- Faster iteration (edit JS, refresh page).

Trade-off: no tree-shaking, no minification of our own code. For a page with ~7 JS files totaling < 50KB, this doesn't matter.

### 12.3 Global namespace

All editor code lives under `window.editor` or `window.*Panel` globals. Not ideal for a large app, but fine for a single page with a known, bounded scope. If the editor grows significantly, this can be refactored to ES modules without changing the public behavior.

---

## 13. Vendoring Strategy

### 13.1 How libraries get vendored

Each library is downloaded as a pre-built UMD bundle and committed to `modules/web/src/main/resources/static/vendor/`. The process:

1. Download the specific version from npm CDN (unpkg/jsdelivr) **once** during development.
2. Compute SHA-256 hash.
3. Commit the file + an entry in `static/vendor/design-system/vendor-manifest.json` (the single manifest for every vendored asset — see §12.1) recording:
   ```json
   {
     "design-system": {
       "package": "@acme/design-tokens",
       "version": "0.2.0",
       "source": "../design-system-starter/dist/",
       "files": [
         "tokens.css", "base.css", "motion.css", "primitives.css",
         "themes/saas.css", "themes/light.css", "themes/dark.css",
         "themes/professional.css", "icons.css"
       ],
       "license": "MIT",
       "note": "Vendored from local sibling project. Run 'npm run build' in design-system-starter before copying dist/."
     },
     "cytoscape": {
       "version": "3.34.0",
       "file": "vendor/cytoscape/cytoscape.min.js",
       "sha256": "...",
       "source": "https://unpkg.com/cytoscape@3.34.0/dist/cytoscape.min.js",
       "license": "MIT"
     },
     "cytoscape-dagre": {
       "version": "2.5.0",
       "file": "vendor/cytoscape/cytoscape-dagre.js",
       "sha256": "...",
       "source": "https://unpkg.com/cytoscape-dagre@2.5.0/cytoscape-dagre.js",
       "license": "MIT"
     },
     "dagre": {
       "version": "0.8.5",
       "file": "vendor/dagre/dagre.min.js",
       "sha256": "...",
       "source": "https://unpkg.com/dagre@0.8.5/dist/dagre.min.js",
       "license": "MIT"
     },
     "alpinejs": {
       "version": "3.14.1",
       "file": "vendor/alpinejs/alpine.min.js",
       "sha256": "...",
       "source": "https://unpkg.com/alpinejs@3.14.1/dist/cdn.min.js",
       "license": "MIT"
     }
   }
   ```
4. A CI check verifies the SHA-256 hashes match the manifest.

### 13.2 Design system vendoring specifics

The design system (`@acme/design-tokens`) is a sibling project at `../design-system-starter`. It is **not** installed via npm into the datapipelines project. Instead:

1. Build the design system: `cd ../design-system-starter && npm run build` → produces `dist/`.
2. Copy the needed files from `dist/` into `modules/web/src/main/resources/static/vendor/design-system/`.
3. Record the version (from `design-system-starter/package.json`) in `static/vendor/design-system/vendor-manifest.json`.
4. A CI script (`scripts/sync-design-system.sh`) automates this: builds the design system, copies files, writes the manifest, verifies version match.

This keeps the design system as an independent project that can evolve separately, while datapipelines vendors a specific build for reproducibility.

### 13.3 Version upgrades

When upgrading a vendored library:
1. Download the new version.
2. Compute new SHA-256.
3. Update the file + manifest.
4. Run the editor test suite.
5. Commit.

No `npm install` in CI, no `package-lock.json`, no transitive dependency resolution. Fully deterministic.

---

## 14. Accessibility

**The constraint that shapes this whole section:** Cytoscape.js renders the graph into a **single `<canvas>` element**. There are no per-node DOM elements, no SVG shapes, nothing for a screen reader or the browser's focus engine to reach. Per-node ARIA (`role="button"` on a node, a per-node `aria-label`, tabbing "into" the graph) is **not implementable** on this renderer — v1.1 of this spec required it and was wrong.

The accessible surface is therefore a **parallel DOM structure mirroring the graph**, not annotations on the graph. A server-rendered list sits next to the canvas (markup in §4.2):

```html
<div id="cy" role="img" aria-label="{graphSummary}" aria-describedby="node-list"></div>

<ul id="node-list" role="listbox" aria-label="Pipeline nodes" tabindex="0">
  <li role="option" id="node-item-fetch_orders" data-node-id="fetch_orders"
      aria-selected="false">fetch_orders — DQL — idle</li>
  ...
</ul>
```

- **One `<li role="option">` per node**, in topological order (the same order dagre ranks them), so the list reads as a sequence of execution stages.
- Each option's accessible name is `"{node_id} — {type} — {status}"`, plus `", returns result to caller"` on the caller node and `", depends on: a, b"` when it has dependencies. That sentence is what a screen-reader user hears; it must carry everything the visual node encodes (identity, kind, state, caller marker, edges).
- The list is **visually hidden until focused** (`.visually-hidden-until-focus`), then rendered as a normal panel — sighted keyboard users get the same affordance, which is also how the styling stays testable.
- It is **server-rendered**, so it doubles as the no-JS fallback (§2 principle 2).
- The canvas itself is `role="img"` with `aria-label` = a one-sentence graph summary (`"Pipeline DAG: 6 nodes, 7 dependencies, left to right"`) and `aria-describedby` pointing at the list. `role="application"` is **not** used — it would suppress the browsing mode the list depends on.

### 14.1 Keyboard navigation

| Key | Context | Action |
|---|---|---|
| `Tab` / `Shift+Tab` | page | Execute → version dropdown → parameter inputs → **node list** → graph controls → result panel |
| `Enter` | Execute button | Execute pipeline |
| `↑` / `↓` | node list | Move focus between options (roving `tabindex` — exactly one `<li>` is tabbable) |
| `Home` / `End` | node list | First / last node |
| `Enter` / `Space` | node list | **Open** the focused node's Details tab (the keyboard twin of the card's expand button, §5.4); a plain click on the row only selects |
| `F` | anywhere but an input | Fit the graph (080 §A — the hint pill's shortcut; never with a modifier, never while typing) |
| `Escape` | anywhere | Error modal only — the inspector rung died with the overlay (080 §B). **Never the dock** — it has no close, and losing the results to the key that dismisses the surface above them is the defect 065 removed |

The `+`/`−`/`R` graph-control shortcuts this table once listed were removed (034 F1) because the controls did not exist. The controls exist since 059 §B — Fit / Reset / Zoom in / Zoom out as REAL buttons in a `role="toolbar"` at the canvas's bottom-right (`.pe-graph-controls`), keyboard-reachable by `Tab` + `Enter` — since 059b `.ds-icon-md` (20px) glyph buttons, sized by the `icons.css` link the page carries. `F` returned with 080 because the mock's hint pill advertises it; it is guarded (no modifier, never in an input) and the buttons remain the honest surface.

Selection is bidirectional and single-sourced: a click on a list item calls `selectNodeById()` (§5.4), which calls `cyNode.select()` on the canvas node — the `node:selected` pseudo-class the §5.3 stylesheet styles — and sets `aria-selected="true"` and `tabindex="0"` on the matching `<li>` (roving tabindex). Tapping a node on the canvas runs the same path in reverse. The two representations cannot drift because only one function mutates selection.

**Focus follows the KEYBOARD only (082 §B).** The list is visually clipped and revealed by `:focus-within`, so `.focus()` on a row is what paints it. Every POINTER route — the canvas tap, the card's expand button — therefore selects with `moveFocus = false`: the ring, the selection and the roving tabindex all still move, only the focus call is withheld. Before 082 a plain mouse click floated the picker into the stage's bottom-left corner, on top of the legend, on every click (visible in 080's dark Details screenshot). The overlap itself is now structurally impossible as well: the picker and the legend share ONE bottom-anchored column (`.pe-stage-bl`), picker first, so a revealed picker stacks ABOVE the legend instead of over it. `node-picker-focus.test.mjs` owns the argument, `EditorLayoutRenderTest` the nesting, and `PipelineEditorDetailsBrowserTest` the geometry — after a real pointer click, and again with the picker open.

The graph canvas is **not** in the tab order (`tabindex="-1"`) — focusing an image the user cannot interact with is a trap, and every graph action is reachable from the list or the controls.

### 14.2 ARIA and status announcements

`a11y.js` owns two jobs, both driven by the same SSE handler that styles the canvas (§6.4) — there is no second source of truth for status:

- `a11yNodeState(nodeId, state)` sets the matching `<li>`'s `data-state` attribute (`idle`/`running`/`success`/`failed`/`aborted`), styled with the same accent tokens as the graph's state border — a keyboard user sees execution state without the canvas. It is called from `PipelineGraph.setNodeState()`/`resetAll()`, the same functions that style the canvas, so there is no second source of truth for status.
- `announce(eventType, data)` writes one sentence into `#graph-status` (`role="status"`, `aria-live="polite"`): `"fetch_orders running"`, `"fetch_orders failed: could not reach pg-prod"`, `"Pipeline completed, 4480 rows"`, `"Execution aborted (cancelled)"`. Terminal and failure events use `aria-live="assertive"` via a second region; per-node progress stays polite so a 20-node pipeline does not flood the buffer.
- Rapid node transitions are coalesced (max one announcement per 500 ms, latest wins) — parallel branches otherwise emit faster than speech synthesis can consume.
- The Execute button carries `aria-busy="true"` for the duration of the stream.

Other regions: the dock `aria-label="Node details, execution results, errors and events"` with a `role="tablist"` of four `role="tab"` buttons over four `role="tabpanel"` bodies (the inspector's `role="dialog"` died with the overlay — a tab needs no dialog role, §8); the error modal `role="alertdialog"` with focus moved to it on open. **The dock carries no `aria-live` of its own** — "Errors (1)" is announced through the single `#pe-live-region` this section owns; a second live region on the same page is what this rule exists to prevent.

### 14.3 Color contrast

All node colors derive from design system tokens, which are audited for WCAG AA compliance by the design system's own contrast audit (`npm run audit:contrast` in the design system project). The token mappings in Appendix A ensure:

- **The card is constant across states** (`--node-surface` background, `--node-label-text` label): one pairing to audit, and it is the same surface/text pairing the design system uses for raised cards.
- **State accents are borders, not fills** (`--node-*-accent` on a 2px border): they carry state *in addition to* the shape/type and ring/selection channels, so even a hypothetical low-contrast accent never hides information — the §14 node list repeats every state in words and in its own accent stripe.
- **Selection** (`--node-selected-ring`): the design system's primary/focus accent, AA-compliant as an indicator against both light and dark surfaces.

If a custom theme introduces a contrast issue, the design system's contrast audit catches it at build time. We run `npm run audit:contrast` in the design system project as part of our CI when syncing themes.

For colorblind users, status is also indicated by:
- **Shape**: DML nodes are diamonds, DDL are tags, DQL are rectangles (PIPELINE nodes are hexagons).
- **Opacity**: aborted nodes render at 0.5 opacity — a non-color channel.
- **Text**: the details panel and the §14 node list always show the status in words — no state is conveyed by color alone.

---

## 15. Connection Loss and Cancellation

### 15.1 Connection drop — the execution is cancelled, not resumed

**There is no reconnection.** [REST API §6.8](rest-api.md#68-client-disconnect) is explicit: a disconnected SSE client cancels its own execution. When the stream drops the server starts a grace timer (`datapipelines.sse.disconnect-grace-seconds`) and, if no terminal event has been reached when it elapses, interrupts the in-flight statements and finishes the execution as `ABORTED`. `Last-Event-Id` is ignored; there is no resume endpoint and no `GET` variant of execute to reconnect to. Any UI that promises "reconnecting…" is lying to the user.

What the editor does instead:

1. The `fetch` reader throws, **or** the stream closes without a terminal event (`pipeline_completed` / `pipeline_failed` / `execution_aborted`). Both are the same condition — `handleConnectionLoss()`.
2. A non-blocking banner appears immediately: **"Connection lost — this execution will be cancelled shortly. Checking final status…"** No retry, no countdown that implies recovery.
3. The editor polls `GET /api/v1/executions/{execution_id}` ([REST API §10.2](rest-api.md#102-get-execution-metadata)) **once**, after a short delay, and **at most once more** — enough to catch the abort landing, not a polling loop.
4. On a terminal status the banner is replaced by the real outcome and the graph is finalized:
   - `ABORTED` → `abortUnfinishedNodes()`, banner **"Execution aborted — the connection was lost."**
   - `SUCCESS` → the execution had already finished when the stream died; the result is in Redis for its TTL, so the dock's Results tab is populated from the cursor (§10.5).
   - `FAILED` → the error modal (§9), populated from the execution record.
5. If both polls still report `RUNNING`, the editor stops and shows **"Execution still running — it will be cancelled within {grace} seconds. Reload to see the final status."** It does not keep polling.

```javascript
// execute.js
async function handleConnectionLoss(executionId) {
    window.banner.warn('Connection lost — this execution will be cancelled shortly. Checking final status…');
    if (!executionId) return;                        // stream died before execution_started

    for (const delayMs of [2000, 8000]) {            // two probes, then stop
        await sleep(delayMs);
        const res = await fetch(`/api/v1/executions/${executionId}`, { credentials: 'same-origin' });
        if (!res.ok) continue;
        const { data } = await res.json();
        if (data.status !== 'RUNNING') return finalizeFromRecord(data);
    }
    window.banner.warn('Execution still running — it will be cancelled shortly. Reload to see the final status.');
}
```

### 15.2 Explicit cancellation

While an execution is running, the Execute button is replaced by **Cancel**, which issues `DELETE /api/v1/executions/{execution_id}` ([REST API §10.4](rest-api.md#104-cancel-execution); scope `execute` + ownership) — a cookie-authenticated state-changing call, so it carries the same `DP-CSRF-Token` double-submit header as execute (§7.2, [Auth §8.4](auth.md#84-api-endpoints-auth-via-api-key-or-jwt)).

- The `204` acknowledges the *request*, not completion. The UI shows "Cancelling…" and waits for the `execution_aborted` event on the still-open stream, which is what actually finalizes the graph (§6.3).
- Cancellation works from any server instance (it travels via a Redis flag), so completion can lag by up to one heartbeat interval. The UI must not assume the `204` means the nodes have stopped.
- Cancelling an execution that already reached a terminal state returns `409` with `pipeline.execution.not_running` — the editor swallows this quietly and just renders the terminal state it already has.

### 15.3 What is NOT offered

- **No reconnection or stream resumption** — deliberately removed; see §15.1.
- **No fire-and-forget execution.** Closing the tab cancels the run. A user who needs the pipeline to finish unattended should trigger it via the REST/MCP surface, not the editor.
- **Past executions are still inspectable** after the fact: metadata via `GET /executions/{id}`, the event stream replayable for 1 hour via `GET /executions/{id}/events`, and the result within its TTL via the cursor (§10.5).

---

## 16. Performance

### 16.1 Expected graph sizes

Typical pipelines have 3–20 nodes. Large pipelines might have 50–100. Cytoscape.js handles hundreds of nodes smoothly on canvas.

### 16.2 SSE event throughput

Execution events arrive at human-observable rates (one per node start/complete, ~1-5 seconds apart for real pipelines). No performance concern.

### 16.3 Result preview rendering

The result table renders one page at a time (`datapipelines.result.page-size-rows`); further pages come from the cursor on demand (§10.5) and replace the visible page rather than appending indefinitely. DOM stays small regardless of `total_rows`.

### 16.4 Cytoscape performance tips

- Disable box selection (we don't need it): `boxSelectionEnabled: false`.
- Disable node dragging (read-only graph in v1): `autoungrabify: true`.
- Use `elements: [...]` batch initialization (not `cy.add()` per element) — we already do this.

---

## 17. Testing

### 17.1 Manual test matrix

| Scenario | Steps | Expected |
|---|---|---|
| View pipeline | Navigate to editor URL | Graph renders with all nodes in dagre LR layout |
| Execute simple pipeline | Click Execute | Nodes turn blue → green sequentially; result panel shows |
| Execute with failure | Click Execute on pipeline with unreachable datasource | Failed node turns red; error modal shows; pending nodes turn gray |
| Switch versions | Use version dropdown | Page reloads with selected version's graph |
| Node details | Click node | Details panel slides in with node metadata |
| Caller node marker | Open a pipeline with a caller node, then one without | `.caller` double border on exactly one node; none on the pure-ETL pipeline |
| Zero-caller pipeline | Execute a pure write-back pipeline | Completes with stats banner; **no** `data_ready`, no result panel |
| Connection loss | Kill the network mid-execution | Banner "connection lost — will be cancelled"; **no** reconnect attempt; status poll resolves to `ABORTED`; nodes go gray |
| Explicit cancel | Click Cancel mid-execution | `204`; `execution_aborted` arrives; running + pending nodes → aborted; banner names the reason |
| Multi-page result | Execute a pipeline returning more rows than one page | First page inline from `data_ready`; "Load next page" hits the cursor; order stable |
| Expired result | Wait past the result TTL, then page | "Result expired — re-run" (no retry loop) |
| Parameter coercion | Submit a BOOLEAN + INTEGER + TIMESTAMP parameter set | Request body carries `true`/`42`/offset-bearing timestamp — not `"true"`/`"42"`/zone-less |
| Keyboard nav | Tab through page, arrow through node list | Every action reachable; graph canvas itself is skipped; node list announces status changes |
| No-JS | Load with JS disabled | Pipeline metadata + node list render; `<noscript>` explains the graph is unavailable |

### 17.2 Automated tests

- **Unit tests** (JS, run in headless browser or jsdom):
  - `PipelineGraph.buildElements()` — correct node/edge construction **and classes**: `nodeType{DQL|DML|DDL}`, `idle` on every node, `caller` on exactly the node resolving to `output.target: "caller"` (declared or omitted), and on **no** node for a zero-caller pipeline.
  - `PipelineGraph.setNodeStatus()` — class transitions are exclusive (previous status class removed, including `idle`).
  - `abortUnfinishedNodes()` — running nodes are aborted too; `success`/`failed` are preserved.
  - `parseSseEvent()` — correct SSE wire format parsing; heartbeat comment frames yield `null`.
  - `coerceParameter()` — per declared type: `BOOLEAN`→`true`, `INTEGER`→number, `BIGDECIMAL`→string (never a number), `TIMESTAMP`→offset-bearing string, bad input throws.
  - `a11y.syncStatus()` — the `<li>` text and the canvas class agree after every transition.
  - `dock.js` — every row of the §10.1 transition table, plus "`Esc` is a no-op" and "a minimised dock keeps its badge", plus a check that **no** transition in the module can return the dock to `hidden` (`dock.test.mjs`).
  - `events.js` — per-kind text, the arrival-order log, the t0 offsets and the auto-scroll pin (`events.test.mjs`); the Details pane's per-type content (`details-pane.test.mjs`); the exactly-once toast under a duplicated lifecycle (`editor-toast-once.test.mjs`). (The 065 `inspector.js` and its suite are deleted — the overlay is gone.)
  - `EditorLayoutRenderTest` — the editor's `<main>` renders with `app-main-bleed`, the pipelines list's without it (§4.3).
- **Integration tests** (Playwright or Cypress):
  - Full execute flow: render → execute → SSE events → graph updates → the dock's Results tab with the inline first page.
  - Failure flow: execute → node fails → the dock raises its Errors tab with the record → error modal shows the one-line summary → graph retains state.
  - Dock flow: results open → minimise → the canvas grows → a tab click restores. There is no path that loses the pane.
  - Abort flow: execute → Cancel → `execution_aborted` → all unfinished nodes aborted, banner shown.
  - Connection-loss flow: stream killed mid-execution → banner, no reconnect request issued, status poll fires at most twice.
  - Cursor paging: multi-page result → next page → stable row order, TTL countdown, expiry message.
  - Version switch: dropdown changes → page reloads with correct version.
  - Accessibility: axe scan clean; arrow-key traversal of the node list drives canvas selection; live region announces each status change.

---

## 18. Stability Promise

### 18.1 Frozen in v1

- The page URL structure (`/pipelines/{id}/editor`).
- The three-panel layout (sidebar + graph + details).
- The Cytoscape.js + dagre LR rendering.
- The 5 node states (idle/running/success/failed/aborted) and their class names.
- The SSE event → graph update mapping, including `execution_aborted` as a terminal event.
- The parallel accessible node list as the AT/keyboard surface for the canvas graph.
- The vendoring strategy (no CDN, no build step).
- The error modal and result panel shapes.
- Read-only: the editor never writes pipeline definitions in v1.

### 18.2 Not frozen

- Specific color values (may be themed in future).
- JS file organization (may be refactored to modules).
- Addition of an authoring/edit mode, including drag-and-drop topology editing (ROADMAP, §11.3).
- Layout algorithm choice (may offer per-pipeline config).

---

## 19. Open Questions / Future Additions

Out of scope for v1, tracked in [ROADMAP](ROADMAP.md):

- **UI edit mode** — editable node metadata + "save as new version" (§11.3). Deferred with drag-and-drop, not ahead of it.
- **Drag-and-drop graph editing** — add nodes, draw edges, edit in place. Requires node palette, edge-drawing interaction, live validation. v2.
- **Async / detached execution** — today closing the tab cancels the run (§15.3). Webhook-notified background execution is the ROADMAP answer, not SSE resumption.
- **Live parameter form from JSON Schema** — auto-generate the parameter form from the pipeline's `parameters` declaration (currently Thymeleaf-rendered, which works but isn't dynamic when the pipeline JSON changes client-side).
- **Mini-map** — Cytoscape supports an extension (`cytoscape-navigator`) for a mini-map overview on large graphs.
- **Undo/redo for edits** — if graph editing lands.
- **Template inline preview** — click a node and see its template body rendered (read-only) without leaving the editor.
- **Multi-pipeline view** — show multiple related pipelines on one canvas (for cross-pipeline dependency awareness).
- **Mobile touch support** — Cytoscape supports touch, but the three-panel layout doesn't work on phones. Needs a separate mobile layout.

> **Dark mode** is NOT a future item — it's built into the design system (`themes/dark.css`, `themes/auto.css`). Runtime theme switching works out of the box via the `#theme-link` swap mechanism described in Appendix A.5.

---

## Appendix A: Token Mapping (Design System Integration)

The pipeline editor defines **app-specific semantic tokens** in `app.css` that derive from the `@acme/design-tokens` design system. No hardcoded hex values anywhere in the editor CSS or Cytoscape styles.

### A.1 app.css — app-specific tokens deriving from design system

```css
:root {
    /* ============================================================
       Graph node cards (§5.3) — neutral card; TYPE is the icon badge,
       STATE is an accent border, SELECTION is the ring
       ============================================================ */
    --node-surface:        var(--surface-raised);
    --node-border:         var(--border-default);
    --node-label-text:     var(--text-primary);
    --node-selected-ring:  var(--accent-primary);
    --node-selected-halo:  var(--accent-primary);
    --node-running-accent: var(--accent-primary);
    --node-success-accent: var(--accent-success);
    --node-failed-accent:  var(--accent-danger);
    --node-aborted-accent: var(--accent-warning);

    /* Card geometry (059 §A): one source for the canvas box and the HTML
       overlay — readDesignTokens() parses these for the Cytoscape style;
       pipeline-editor.css uses them for the overlay div. */
    --pe-card-w:           264px;
    --pe-card-h:           164px;

    /* ============================================================
       Banner state fills (pipeline-editor.css .pe-banner) — the
       graph's success/failed/aborted accents fall back to these,
       so a theme override re-themes banner and graph together
       ============================================================ */
    --node-success-bg:   var(--accent-success);
    --node-success-text: var(--accent-primary-text);
    --node-failed-bg:    var(--accent-danger);
    --node-failed-text:  var(--accent-danger-text);
    --node-aborted-bg:   var(--accent-warning);
    --node-aborted-text: var(--accent-primary-text);

    /* ============================================================
       Edge colors
       ============================================================ */
    --edge-idle-stroke:  var(--text-secondary);
    --edge-active-stroke: var(--accent-primary);

    /* ============================================================
       Editor layout — uses design system layout tokens
       ============================================================ */
    --editor-sidebar-width:   var(--sidebar-width);        /* 280px from design system */
    --editor-details-width:   320px;                       /* app-specific; not in design system */
    --editor-topbar-height:   var(--header-height);        /* 60px from design system */
}
```

### A.2 Why app-specific tokens, not direct design system references

We don't reference `--accent-primary` directly in Cytoscape styles because:

1. **Indirection enables restyling.** If we later decide "running nodes should be teal, not indigo," we change `--node-running-accent` in one place, not every Cytoscape selector.
2. **Semantic clarity.** `--node-running-accent` is self-documenting. `--accent-primary` requires the reader to know what accent-primary means in the context of a graph node.
3. **Theme portability.** Some design system themes may have unusual accent colors (e.g., `minimal` theme uses black + white). The app-specific tokens let us remap for edge cases without touching Cytoscape code.

### A.3 Design system tokens used directly (no app wrapper)

These design system tokens are used directly in the editor CSS and HTML because they're already semantic enough:

| Design system token | Used for |
|---|---|
| `--surface-page` | Editor page background |
| `--surface-raised` | Sidebar, details panel background |
| `--surface-overlay` | Modal backdrop |
| `--text-primary` | All primary text |
| `--text-secondary` | Node descriptions, secondary labels |
| `--text-muted` | Placeholder text, disabled state |
| `--border-default` | Panel borders, dividers |
| `--border-focus` | Selected node border |
| `--accent-primary` | Execute button, links, active edge |
| `--accent-danger` | Error modal header, failed node |
| `--radius-base` | Node border radius, input fields |
| `--shadow-md` | Details panel shadow, modal shadow |
| `--space-4`, `--space-6` | Spacing in sidebar, panels |
| `--font-sans` | All text (Cytoscape font-family reads this) |
| `--font-mono` | Code blocks in error details, result data |
| `--header-height` | Topbar height |
| `--sidebar-width` | Sidebar width |
| `--breakpoint-md`, `--breakpoint-lg` | Responsive breakpoints |

### A.4 Primitives used in the editor

| Design system primitive | Where in editor |
|---|---|
| `.ds-button` (`.ds-button--primary`, `--ghost`, `--sm`) | Execute button, graph controls, modal buttons |
| `.ds-card` | Details panel container, result preview container |
| `.ds-input`, `.ds-label`, `.ds-field` | Parameter form |
| `.ds-select` | Version selector dropdown |
| `.ds-badge` (`.ds-badge--neutral`) | Pipeline version badge |
| `.ds-modal`, `.ds-modal-backdrop` | Error modal |
| `.ds-code-block` | Error details JSON, SQL preview |
| `.ds-link` | Documentation links in error modal, template links |
| `.ds-text`, `.ds-text--danger` | Error messages |
| `.ds-h2`, `.ds-h4` | Section headers |

### A.5 Theme switching

The design system supports runtime theme switching by swapping the `#theme-link` href. The editor supports this:

1. The user selects a theme on the profile screen (global nav, not the editor itself). That choice is **persisted to `users.theme_preference`** via `PATCH /partials/profile/theme` ([UI Screens §4.11](ui-screens.md#411-user-settings)) — never held in session state — and becomes the left-hand side of the §3.4 resolution on every subsequent request.
2. The server's response swaps the `#theme-link` stylesheet element out-of-band, so the new theme applies without a page reload (equivalently: `document.getElementById('theme-link').href = '/vendor/design-system/themes/' + themeName + '.css'`).
3. All CSS custom properties update instantly across the page.
4. The editor's `PipelineGraph` listens for theme changes and re-reads tokens — this mechanism is indifferent to whether the active theme came from the user row or the deployment default:
   ```javascript
   window.addEventListener('theme-change', () => {
       const newTokens = readDesignTokens();
       this.cy.style().fromJson(buildGraphStyle(newTokens)).update();
   });
   ```
5. Graph re-styles without re-rendering (Cytoscape's `.style().update()` is incremental).

Themes shipped by the design system — `saas` (modern indigo, devtool-oriented), `light` (clean neutral), `dark`, `auto` (follows OS `prefers-color-scheme`), `professional` (navy/enterprise), plus `healthcare`, `minimal`, `forest`, `ocean`. The **authoritative** valid-value list and the deployment default live in [Configuration §3.10](configuration.md#310-ui); this paragraph describes their character, not their validity.

---

## Appendix B: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-06 | v1.10 | wide-stage card scale and the stray node list (082) | **§5.3/§4.3: the card steps up on a wide stage.** "At 2560 the cards read small" cannot be a zoom change — the owner's ruling caps fit at 1.0 and forbids zooming IN — so `.pe-stage` became a `container-type: inline-size` container and a stage ≥ 1200px wide gets `--pe-card-w: 272px`, `--pe-card-h: 170px` and the card type scale (now tokenised as `--pe-card-fs-*`) a pixel larger. The declarations land on `#cy-canvas` because a container cannot style itself and because `readDesignTokens(containerId)` now reads the geometry from that element — reading `documentElement` would keep the Cytoscape node box at 236px while the HTML card grew. A `ResizeObserver` on the stage re-runs layout when the threshold is crossed live. **§14.1: the node picker is a KEYBOARD surface again** — every pointer route selects with `moveFocus = false`, and the picker now shares one bottom-anchored column with the legend (`.pe-stage-bl`) instead of being anchored to the same corner, which is what put it on top of the legend in 080's dark Details shot. New guards: `card-scale.test.mjs`, `node-picker-focus.test.mjs`, an `EditorLayoutRenderTest` nesting assertion and a `PipelineEditorDetailsBrowserTest` geometry assertion. No wire contract moved. **Addendum (owner's first walk of the merged UI), two P1s:** *the dark theme broke the canvas* — TWO causes, both measured live rather than assumed. (1) `readDesignTokens` handed Cytoscape the raw custom-property text, and four canvas tokens are `color-mix()` bridges it cannot parse; every colour is now resolved through a probe element and normalised to `rgb(…)` — Chrome answers `color(srgb …)`, not `rgb()`, which the first cut of the fix rejected. (2) `updateTheme()` re-applied with `cy.style(array)`, which RESETS a live graph to Cytoscape's defaults (#999 lines, 30px edges, #999 fills — the owner's grey bands); the re-apply is `cy.style().fromJson(sheet).update()`, and because `fromJson` drops function values the per-node card height became an element bypass. 080's `wheelSensitivity` (warned on every init) is gone. *The card footer painted outside the card* — `--pe-card-h` was a fixed height that three fact lines overflow; it is a `min-height` now and `syncCardHeights()` hands each measured height to Cytoscape so the node box, the ports and the minimap follow the real card. Guards: `graph-colour-tokens.test.mjs`, `card-height.test.mjs`, and a live theme-switch browser test demanding zero Cytoscape complaints. |
| 2026-09-05 | v1.9 | editor v2 canvas and dock (080) | The owner-approved mock (`2026-09-05-editor-canvas-v2.html`) becomes the editor. **§5.3 rewritten: the v2 canvas** — dotted-grid stage, the mock's card anatomy (type-accent icon tile, id + type eyebrow, up to three mono facts, footer with state dot + run numbers, ports, hover lift + hover-only expand, running progress line), bezier edges with three states (`--edge` rest / `--edge-active` dashed + JS-stepped flow while the target runs / `--edge-done` after), row counts riding the edge behind the `rows` class, controls + minimap bottom-right, legend bottom-left, keyboard hint top-left, and the fit ceiling: fit never zooms IN past 1.0 (the 059 0.75 floor stays). Canvas tokens are ONE new block in `app.css` (the five node-type accent pairs + `--brand(-soft)` + `--border-faint` + `--grid-dot` + `--edge*`), bridged to the design system so all nine themes re-skin the canvas. The 065 inspector overlay is DELETED — **§8 rewritten: Details is a tab of the dock** (owner ruling 2026-09-05), with per-type meta and the rendered SQL / calculator evaluation / child mapping; the per-node Failure section merged into the per-run Errors tab. **§10 rewritten: the dock is Details \| Results \| Errors \| Events**, always present (`hidden` died with the overlay), `collapsed` the only contraction, still no close; **§10.6 new: the Events tab** — every SSE event in arrival order with a live count badge and a yielding auto-scroll. **The exactly-once toast:** "many success toasts" was the 076 afterSettle rescue stacking Alpine components on a history-restored root (one click → N executions → N terminal toasts); the rescue now destroys the stale tree before re-binding (`editor-toast-once.test.mjs` falsifies the old shape). §4.2/§4.3/§5.4/§6.2/§6.3/§9.1/§12.1/§14 updated to match; `events.js` in, `inspector.js` out. Top bar gains the mock's crumb (folder muted + name bold), version chip and run-status clock (§4.2). No wire contract moved. |
| 2026-09-04 | v1.8 | editor real estate and panes (065) | Four layout/behaviour changes on a read-only editor; **no wire contract moved** — the SSE events, the 027b result cursor, the SQL partial, the 057 failure record and the 059 card facts are exactly as they were. This round re-homed what was already on the page. **§4.3 rewritten: the editor is FULL-BLEED.** `layouts/default.html` capped every page at `--app-content-max: 1600px`; on the owner's ~2000px viewport the editor took ~1450px between two dead margins, and the graph — the one surface that scales with width — was paying for a measure chosen for prose (*"use all the real estate on screen"*, twice). One model attribute (`fullBleed`, set by `PipelineEditorController` and no other page), one `th:classappend`, one `.app-main-bleed` rule (tokens only); the nav keeps its own cap. Pinned by `EditorLayoutRenderTest` in both directions. §4.3 also gains the one table of every pane's dimension. **§10 rewritten: the result panel becomes a DOCK — Results | Errors, three states, no close.** `.pe-result-panel`'s × set `resultPanel.visible = false` with no way back short of re-running; the owner asked for **minimise**. The full transition table is §10.1, implemented in a new PURE `dock.js` (state only, no DOM) and driven row-by-row by `dock.test.mjs`, including "`Esc` is a no-op", "a minimised dock keeps its badge" and "no transition can return the dock to `hidden`". The dock is the last FLEX CHILD of `.pe-root`, not a fixed overlay — that is the mechanism by which the canvas actually reclaims the space (a fixed overlay can cover the canvas but never give it back). Open height is today's 40vh; no drag handle (not asked for, YAGNI). **§9 rewritten: the Errors tab is the home of a failure record.** The 057 record used to render INSIDE the results panel, gated on `resultPanel.failure` — two different objects with different lifetimes in one pane, both lost when the pane closed. Three surfaces now: Errors tab (per RUN), inspector Failure section (per NODE, kept), error modal (one-line summary only). §9.1's old "error modal shows the technical details" wording is DEAD and replaced, not appended. `pipeline_failed`'s execution-level record joins the same list, deduped on node+code+message. **§8/§5.4 rewritten: the inspector opens FROM THE CARD, large.** Tapping a card now SELECTS and nothing else; every `.pe-card` carries a `.pe-card-open` button (`ds-icon-sm`, `stopPropagation`, one DELEGATED listener because the html-label re-renders its template on every `data`/`style` event), with `Enter`/`Space` on the node-list row as its keyboard twin. The fixed 320px drawer and the `has-details` grid shift are GONE: the panel is an overlay inside `.pe-layout` at `clamp(720px, 60%, 1200px)`, full layout height, scrim over the CANVAS only (the sidebar is context, not chrome). Close is ×/`Esc`/scrim; focus moves in on open and returns to the opening control on close — captured as an ELEMENT reference, never a selector, because the card button is re-drawn constantly. Opening from a second card replaces in place with no intermediate closed state (`inspector.js`, pure; `inspector.test.mjs` asserts it against a recorded transition log — a post-hoc `open === true` cannot see a close that already happened). The SQL section is the point: full panel width, `white-space: pre`, scroll in its own box, floored at 40% of the panel via a percentage FLEX-BASIS — a `min-height: 40%` would have resolved against a containing block with no definite height and silently computed to `auto`. The partial now loads on OPEN, not on select (one request per opening, not one per click through the graph). §14.1's Escape ladder loses its middle rung (modal → inspector → nothing); §14.2 records that the dock adds NO second live region; §12.1 gains `dock.js`/`inspector.js`. `graph-card.test.mjs`'s 059b "exactly one svg per card" assertion is deliberately revised to "one GLYPH plus the button's icon" — the one-glyph rule is unchanged, the card simply also has a control now. |
| 2026-09-03 | v1.7 | icon sizing (059b) | The 059 screenshots showed every icon at canvas scale: toolbar glyphs 300×150, card glyphs ~190px, the database glyph drawn TWICE per card. Two causes, both fixed at the source. (1) **`icons.css` was never loaded** — the `.ds-icon` size classes on every emitted `<svg>` were inert, and an svg with no size is the 300×150 replaced-element default. The editor page now links `/vendor/design-system/icons.css` (pinned by `PipelineEditorRenderTest` and the 027b harness); §5.3 item 2 records the rule: every svg the editor emits carries the class pair, never bare. The toolbar is a row of `.ds-icon-md` buttons at the canvas's top-right corner (§14.1 note updated). (2) **The card drew `#db` twice** — the type glyph (line 2) and the engine glyph (line 3, added in 059 beyond the five-line spec) are the same database drawing on every db-backed card. The engine glyph is RETIRED: the card's one glyph is the type glyph, and the engine's identity is the source line's text (`POSTGRES`, `SQLITE`, …) — the "engine glyphs by dialect" sentence leaves §5.3 with it. `iconForDialect` deleted; the sprite keeps its 12 recorded glyphs (`file` unused, harmless). Gates: the live DOM check (every `.pe-graph svg`/toolbar svg ≤ 24×24, exactly one glyph svg per card) red on `5187efd` (toolbar 4×300×150, cards 2 svgs ~190px), green after; measured on the demo stack. |
| 2026-09-03 | v1.6 | graph node cards (059) | **§5.3 rewritten for the CARD, reversing the 2026-08-31 label-below contract** (the operator reviewed the 031 result on the live product, 2026-09-02: an empty box with a caption — *"I want to display total node execution time, dialect, template name and datasource name. It should be INSIDE the box"*). The five lines are specified: name (two-line clamp, `title` carries the full), type badge + vendored Lucide glyph (the per-type SHAPES are RETIRED — the icon badge carries TYPE), datasource · dialect (resolved client-side from `GET /api/v1/datasources`, the body is portable and carries only names; `tempdb · H2` from settings; a PIPELINE card names the child pipeline), template@version LEFT-truncated so the leaf survives (043), and the run line from `node_completed`'s FLAT `duration_ms`/`rows_out` — absent, not a placeholder, before any execution. Rendering: **route 1 decided** — `cytoscape-node-html-label` 1.2.2 vendored (pointer-events:none container, pan/zoom transform — verified against its source) paints the content OVER a canvas that still paints the chrome (state accents §6.2, caller double border, selection ring); state dots and run lines arrive as `data.state`/`data.run` writes the overlay re-renders on. Corner status dot (✓/✕/spinner/–) and edge PORTS specified. Card geometry is one token source (`--pe-card-w/h`). **§B:** the canvas fills the main pane (041 height math), `fitToView()` fits with padding then enforces a readable minimum zoom, dagre retuned (`nodeSep` 64, `rankSep` 176, `fit: false` — §5.1 updated), Fit/Reset/Zoom buttons keyboard-reachable (§14.1 note updated: controls exist, single-key shortcuts did not return). Edges: unbundled-bezier, endpoints on the card's right/left edges, per-edge horizontal control points computed post-layout (no `control-point-positions` in Cytoscape 3.34); a DASHED `edge.secondary` style is defined and deliberately unused (future template-import links). §6.2 unchanged. Appendix A gains the card geometry tokens. |
| 2026-08-31 | v1.5 | recurrence defect round (034) | §14.1 reconciled with the code: `Home`/`End` (first/last option, roving tabindex) and `Escape` (topmost-first close — error modal, result panel, details panel; one surface per press, unconsumed when nothing is open) are now IMPLEMENTED in `a11y.js` and pinned by `a11y.test.mjs`; the `+`/`−`, `F`, `R` rows were REMOVED — the graph zoom/fit controls they name do not exist, so they were spec requirements the code ignored. They return with the round that builds those controls. |
| 2026-08-31 | v1.4 | execute page redesign (032) | **§8 rewritten around the SQL section.** New §8.3: "show the SQL for a node" is a resolution problem, not a display problem — SQL lives in template entities (contract §2.3), so the new `GET /partials/pipelines/{id}/nodes/{nodeId}/sql` (scope `READ_RESOURCES`; the author-scoped free-form template render endpoint deliberately NOT reused) resolves the node's PINNED `{id, version}` and renders against the pipeline's own parameter context. Wire format is §6.3 JSON built by the page's own `coerceValue` — one coercion path for execute and preview. Three context outcomes documented: bound / sampled (`sampleContext()`, labelled) / rejected (named parameter, NO SQL — SQL from a value the executor would refuse is worse than none). PIPELINE nodes show the child-pipeline state, not an empty block. §8.1: SQL row added; the last-execution-stats and per-node error rows marked **not implemented in v1** (they previously read as shipped); long-value wrapping specified. §8.2's route fixed to `/templates/{id}/editor` — the spec previously named `/templates/{id}/versions/{version}/editor`, which does not exist. §8 panel regrouped into Identity / SQL / Configuration / Runtime sections; template is now a real link; Output renders "returns result to caller (default)" instead of `undefined`. **§9.3 new:** terminal SSE events split — `pipeline_failed` keeps the modal; `pipeline_completed` / `execution_aborted` report as toasts via `DpToast.show` (Shape D — the one client-side builder, for events with no HTTP response); all three gain the `announceStatus` call they lacked (an addition, not a preservation). **§10:** the result grid moved onto the shared `.ds-table` (the bespoke `.pe-result-table` styles deleted); §10.2 records that the 027b paging arithmetic is frozen — restyled, not rewired. §4.2/§12.1: the new partial and `sql-highlight.js` join the page structure. The SQL copy confirmation is deliberately NOT a toast (live region + 1.5s label swap) — §8.3 says why. |
| 2026-08-31 | v1.3 | graph design (031) | §5.3 rewritten to the shipped stylesheet: node cards with the label BELOW the shape (was `text-valign: center` inside an 80×40 box, truncated at 20 chars — genuine change, operator contract), per-TYPE shapes (`type-dml` round-diamond, `type-ddl` round-tag, `pipeline-node` hexagon; classes renamed from the never-implemented `nodeTypeDQL` form), the `node.caller` marker (double border, contract §9) now actually emitted and styled, and SELECTION as the `node:selected` PSEUDO-CLASS with ring + underlay halo (was a `.selected` class the code never had; §5.4 corrected to match — selection is `cyNode.select()`, driven by init.js/a11y.js). State becomes an accent border via new `--node-*-accent` tokens (§6.2), superseding the `--node-*-bg/text` fill pairs — the second genuine change; the fill pairs remain only for the `.pe-banner` fills. §6.2: the unimplemented `failed` "brief flash" requirement WITHDRAWN; the running pulse specified honestly as a JS-driven `ele.animate` loop gated on `window.matchMedia("(prefers-reduced-motion: reduce)")` (canvas — CSS media queries cannot reach it). §5.1: layout options recorded as shipped — `edgeSep`, `padding`, and `nodeDimensionsIncludeLabels: true` (mandatory once labels sit below shapes); `marginX`/`marginY` noted as non-dagre options. §14.1/§14.2: node list corrected to roving tabindex (the previous markup gave `<li>`s no `tabindex` — the keyboard path was dead) and the `data-state` execution-state mirror. §3.4 bridge listing and Appendix A token map updated to the shipped token names. |
| 2026-08-05 | v1.0 | initial draft | Initial pipeline editor UI spec: Thymeleaf + Alpine.js + Cytoscape.js 3.34.0 + cytoscape-dagre. Three-panel layout, 5 node states, SSE-driven graph updates, vendoring strategy, accessibility, keyboard nav. |
| 2026-08-07 | v1.2 | consistency campaign | **[D7]** §15 rewritten: no SSE reconnection and no `Last-Event-Id` — a dropped stream cancels the execution after the grace period; the editor warns, polls `GET /executions/{id}` at most twice for the final status, and renders `ABORTED`. `execution_aborted` (rest-api §6.4.8) wired into §6.3/§6.4 as a terminal event; explicit Cancel (`DELETE /executions/{id}`) added (§15.2). **[D9]** §10 rewritten for uniform result delivery: one panel shape, inline first page from `data_ready`, cursor paging/downloads within a fixed TTL, `result.expired` handling; inline-vs-claim-check split deleted. **[D1]** "terminal node" → **caller node** throughout; `.terminal` Cytoscape class renamed `.caller` and actually applied in `buildElements()`; zero-caller pipelines documented (no `data_ready`). **[D8]** Theme resolution corrected to `${activeTheme} = users.theme_preference ?: datapipelines.ui.theme` (per-user override, deployment value as default — ui-screens.md v1.1 §4.11); the config key, its default and the valid theme list are referenced from configuration.md §3.10 instead of restated (§3.4, Appendix A.5). **[M]** "Native EventSource" removed from the §3.2 stack (contradicted §7.3). Styling wiring fixed: `nodeType*` and `idle` classes added at build time, `.selected` managed in `selectNode()`/`clearSelection()`. §4.2 adds `result.js`, `a11y.js` and the `x-on:show-error.window` listener that gives §9.1's dispatch a consumer. **[M]** §7.2 `collectParameters()` coerces values to declared wire types (pipeline-contract §6.3) instead of posting FormData strings. **[M]** §14 accessibility rewritten honestly for canvas rendering: per-node `role="button"` is impossible, replaced by a parallel visually-hidden `<ul role="listbox">`, `role="img"` canvas, and an `aria-live` status region. **[M]** Progressive-enhancement overclaim removed (no-JS = metadata + node list, no graph). **[M]** §11 edit mode removed from v1 scope (`?edit=true` deleted) — authoring is LLM/MCP-first, UI edit mode is a ROADMAP item. **[M]** `vendor-manifest.json` unified to `static/vendor/design-system/`. **[M]** Duplicate `### 13.2` renumbered (version upgrades → §13.3). Anchor fixes: auth §6, dag-executor/rest-api cross-links; `jdbc_url` removed from the §9.2 error mockup. See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) §2.13. |
| 2026-08-05 | v1.1 | design system integration | Integrated `@acme/design-tokens` design system as the styling foundation. All hardcoded colors replaced with design system tokens (`--surface-*`, `--text-*`, `--accent-*`). HTML uses `.ds-*` primitives (`.ds-button`, `.ds-card`, `.ds-modal`, etc.). Cytoscape stylesheet reads tokens via `readDesignTokens()` bridge. App-specific node-state tokens (`--node-*-bg/text`) derive from design system accent tokens. Theme switching (9 themes including dark mode) works at runtime without page reload. Replaced Appendix A entirely. Updated vendoring to include design system CSS. |
