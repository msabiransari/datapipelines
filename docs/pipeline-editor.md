# Pipeline Editor UI Specification

**Status:** v1.2 (revised — see Change Log)
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Contract](pipeline-contract.md), [REST API + SSE](rest-api.md), [Type System](type-system.md), [Enums](enums.md), [Auth](auth.md), [Configuration](configuration.md), [@acme/design-tokens Design System](https://github.com/msabir/design-system-starter)
**Last updated:** 2026-08-07

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
<link rel="stylesheet" href="/webjars/bootstrap/5.3.8/css/bootstrap.min.css">  <!-- grid/utilities/modal -->
<link rel="stylesheet" href="/css/app.css">                <!-- app-specific, LAST -->
```

Bootstrap sits BETWEEN the design system and `app.css`: its reboot declares `body { background-color: var(--bs-body-bg) }` — concrete white — and were it the final sheet it would paint `<body>` white under every theme (024 T40; `app.css` re-asserts `background-color: var(--surface-page); color: var(--text-primary)` over it). Every theme file, `dark.css` included, opens on `:root`: the swap loads exactly one file, so a theme takes effect by being loaded — no `data-theme` attribute is involved anywhere.

Theme switching at runtime: swap the `href` of `#theme-link`. All tokens cascade instantly — no page reload.

**Design system rules we follow strictly:**
- Never use hardcoded hex values in CSS, Thymeleaf templates, or Cytoscape styles. Always reference tokens.
- Never edit `tokens.css` or theme files (they are vendored, not forked).
- App-specific semantic tokens (e.g., `--node-running-bg`) are defined in `app.css` and derive from design system tokens.
- Use `.ds-*` primitives wherever they fit (buttons, inputs, cards, badges, tables, modals). Override or extend only when the primitive doesn't fit.

**Bridging design system tokens → Cytoscape styles:**

Cytoscape uses its own style format (not CSS). We bridge by reading computed CSS custom properties at init time:

```javascript
function readDesignTokens() {
    const cs = getComputedStyle(document.documentElement);
    return {
        surfaceDefault:    cs.getPropertyValue('--surface-default').trim(),
        surfaceRaised:     cs.getPropertyValue('--surface-raised').trim(),
        textPrimary:       cs.getPropertyValue('--text-primary').trim(),
        textMuted:         cs.getPropertyValue('--text-muted').trim(),
        textInverted:      cs.getPropertyValue('--text-inverted').trim(),
        accentPrimary:     cs.getPropertyValue('--accent-primary').trim(),
        accentPrimaryText: cs.getPropertyValue('--accent-primary-text').trim(),
        accentDanger:      cs.getPropertyValue('--accent-danger').trim(),
        accentDangerText:  cs.getPropertyValue('--accent-danger-text').trim(),
        borderDefault:     cs.getPropertyValue('--border-default').trim(),
        borderFocus:       cs.getPropertyValue('--border-focus').trim(),
        // Node-state tokens (defined in app.css, derive from design system)
        nodeIdleBg:        cs.getPropertyValue('--node-idle-bg').trim(),
        nodeRunningBg:     cs.getPropertyValue('--node-running-bg').trim(),
        nodeRunningText:   cs.getPropertyValue('--node-running-text').trim(),
        nodeSuccessBg:     cs.getPropertyValue('--node-success-bg').trim(),
        nodeSuccessText:   cs.getPropertyValue('--node-success-text').trim(),
        nodeFailedBg:      cs.getPropertyValue('--node-failed-bg').trim(),
        nodeFailedText:    cs.getPropertyValue('--node-failed-text').trim(),
        nodeAbortedBg:     cs.getPropertyValue('--node-aborted-bg').trim(),
        nodeAbortedText:   cs.getPropertyValue('--node-aborted-text').trim(),
        edgeDefault:       cs.getPropertyValue('--edge-default').trim(),
        edgeActive:        cs.getPropertyValue('--edge-active').trim(),
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

### 4.3 Layout dimensions

```
┌────────────────────────────────────────────────────────────────────┐
│ Topbar: Name | v3 | [version dropdown] | [Execute]                 │ 60px
├──────────┬───────────────────────────────────────┬─────────────────┤
│          │                                       │                 │
│ Settings │                                       │  Node Details   │
│          │                                       │  (slide-in)     │
│ Parameters│             Graph (Cytoscape)        │                 │
│          │                                       │  — node id      │
│ (forms)  │           [#cy div, fills space]      │  — type         │
│          │                                       │  — template     │
│          │                                       │  — source       │
│          │                                       │  — output       │
│ 280px    │           flex: 1                     │  — dependencies │
│          │                                       │  — stats (last) │
│          │                                       │  — error (if)   │
│          │                                       │  320px (when    │
│          │                                       │  visible)       │
└──────────┴───────────────────────────────────────┴─────────────────┘
```

Responsive: on screens below `--breakpoint-lg` (1024px), sidebar collapses to a top drawer (hamburger menu). On screens below `--breakpoint-md` (768px), graph fills the screen; settings/parameters/details become overlay panels.

All layout dimensions come from design system tokens (`--sidebar-width`, `--header-height`, `--breakpoint-*`). No hardcoded px values in CSS.

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
        const layout = this.buildLayout();

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
                `nodeType${n.type}`,            // nodeTypeDQL | nodeTypeDML | nodeTypeDDL
                'idle',
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

    buildLayout() {
        return {
            name: 'dagre',
            rankDir: 'LR',                      // left-to-right
            nodeSep: 50,                        // vertical spacing between nodes at same rank
            rankSep: 100,                       // horizontal spacing between ranks
            directed: true,
            padding: 30,
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

### 5.3 Graph stylesheet

The Cytoscape stylesheet reads design system tokens at init time via `readDesignTokens()` (§3.4) and uses them throughout. No hardcoded hex values. When the theme changes, the graph re-reads tokens and re-applies the stylesheet.

```javascript
function buildGraphStyle(t) {        // t = readDesignTokens() output
    return [
        // Default node
        {
            selector: 'node',
            style: {
                'label': 'data(label)',
                'text-valign': 'center',
                'text-halign': 'center',
                'color': t.textInverted,
                'text-outline-color': t.nodeIdleBg,
                'text-outline-width': 2,
                'background-color': t.nodeIdleBg,
                'border-width': 2,
                'border-color': t.borderDefault,
                'shape': 'round-rectangle',
                'width': 140,
                'height': 50,
                'font-size': 12,
                'font-weight': 'bold',
                'font-family': 'Inter, ui-sans-serif, system-ui, sans-serif',
            }
        },
        // Node type indicators via class
        {
            selector: 'node.nodeTypeDQL',
            style: { 'background-color': t.accentPrimary }
        },
        {
            selector: 'node.nodeTypeDML',
            style: { 'shape': 'round-diamond' }          // distinct shape for side-effects
        },
        {
            selector: 'node.nodeTypeDDL',
            style: { 'shape': 'round-tag' }              // distinct shape for schema changes
        },
        // Execution states (§6) — all colors from design system tokens
        {
            selector: 'node.running',
            style: {
                'background-color': t.nodeRunningBg,
                'border-color': t.nodeRunningBg,
                'border-width': 3,
                'color': t.nodeRunningText,
                'text-outline-color': t.nodeRunningBg,
            }
        },
        {
            selector: 'node.success',
            style: {
                'background-color': t.nodeSuccessBg,
                'border-color': t.nodeSuccessBg,
                'color': t.nodeSuccessText,
                'text-outline-color': t.nodeSuccessBg,
            }
        },
        {
            selector: 'node.failed',
            style: {
                'background-color': t.nodeFailedBg,
                'border-color': t.nodeFailedBg,
                'border-width': 3,
                'color': t.nodeFailedText,
                'text-outline-color': t.nodeFailedBg,
            }
        },
        {
            selector: 'node.aborted',
            style: {
                'background-color': t.nodeAbortedBg,
                'opacity': 0.5,
                'color': t.nodeAbortedText,
                'text-outline-color': t.nodeAbortedBg,
            }
        },
        // Selected node
        {
            selector: 'node.selected',
            style: {
                'border-width': 4,
                'border-color': t.accentPrimary,        // design system focus ring color
            }
        },
        // Caller node (resolves to output.target: caller) — distinct visual marker.
        // Applied in buildElements() (§5.1). At most one node carries it; a pure-ETL
        // pipeline has none.
        {
            selector: 'node.caller',
            style: {
                'border-style': 'double',
                'border-width': 5,
            }
        },
        // Edges
        {
            selector: 'edge',
            style: {
                'width': 2,
                'line-color': t.edgeDefault,
                'target-arrow-color': t.edgeDefault,
                'target-arrow-shape': 'triangle',
                'curve-style': 'bezier',
            }
        },
        // Active edge (downstream of a running/success node)
        {
            selector: 'edge.active',
            style: {
                'line-color': t.edgeActive,
                'target-arrow-color': t.edgeActive,
                'width': 3,
            }
        },
    ];
}
```

**Key difference from v1.0:** all colors are now variable (read from the design system at init time), not hardcoded constants. A theme switch (light → dark → professional) changes every color in the graph without page reload.

### 5.4 Event handlers

```javascript
wireEventHandlers() {
    // Node click → select + show details panel
    this.cy.on('tap', 'node', (event) => {
        const node = event.target;
        this.selectNode(node.id());
        window.detailsPanel.show(node.data());
    });

    // Background click → clear selection, hide details
    this.cy.on('tap', (event) => {
        if (event.target === this.cy) {
            this.clearSelection();
            window.detailsPanel.hide();
        }
    });
}

// The `.selected` class in §5.3 is applied HERE and nowhere else — Cytoscape's own
// :selected pseudo-state is not used, so the class must be managed explicitly.
// Selection is exclusive: at most one node carries `.selected`.
selectNode(nodeId) {
    this.clearSelection();
    const node = this.cy.getElementById(nodeId);
    node.addClass('selected');
    this.selectedNodeId = nodeId;
    window.a11y.syncSelection(nodeId);       // mirrors aria-selected on the §14 DOM list
}

clearSelection() {
    this.cy.nodes().removeClass('selected');
    this.selectedNodeId = null;
    window.a11y.syncSelection(null);
}
```

Selecting a node from the accessible DOM list (§14) calls the same `selectNode()`, so canvas and list never disagree.

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

All colors derive from app-specific semantic tokens defined in `app.css`, which in turn derive from the `@acme/design-tokens` design system. See Appendix A for the full token mapping.

| State | CSS class | Token (background) | Token (text) | Animation | Meaning |
|---|---|---|---|---|---|
| `idle` | `.idle` | `--node-idle-bg` | `--node-idle-text` | none | Initial state, not yet executed. Applied in `buildElements()` (§5.1) so all five statuses are symmetric classes — there is no implicit "no class" state. |
| `running` | `.running` | `--node-running-bg` | `--node-running-text` | pulse | Node is currently executing |
| `success` | `.success` | `--node-success-bg` | `--node-success-text` | none | Node completed successfully |
| `failed` | `.failed` | `--node-failed-bg` | `--node-failed-text` | brief flash | Node failed; pipeline aborted |
| `aborted` | `.aborted` | `--node-aborted-bg` | `--node-aborted-text`, 0.5 opacity | none | Node never ran (dependency failed), or was interrupted by cancellation |

Colors automatically adapt to the active design system theme. No hardcoded hex values.

### 6.3 State transitions via SSE events

Event payloads are defined in [REST API §6.4](rest-api.md#64-event-types); the table below is only the graph's reaction to them.

| SSE event | Graph action |
|---|---|
| `execution_started` | Reset all nodes to `idle`. Disable Execute button. |
| `node_started` | Node → `running`. Incoming edges → `.active`. |
| `node_completed` | Node → `success`. Outgoing edges → `.active`. (Success-only event — failures arrive as `node_failed`.) |
| `node_failed` | Node → `failed`. Update details panel with error. All pending nodes → `aborted`. |
| `pipeline_completed` | Terminal. Every node is `success` or `aborted`. Show success banner. |
| `pipeline_failed` | Terminal. Show error modal (§9). |
| `data_ready` | Show result preview panel (§10). Emitted after `pipeline_completed` and **only when the pipeline has a caller node** — a pure-ETL pipeline completes with no `data_ready` and no result panel. |
| `execution_aborted` | Terminal. Every node not already `success`/`failed` → `aborted`; banner "Execution aborted ({reason})" using the event's `reason` (`client_disconnect` \| `cancelled` \| `shutdown`); Execute button re-enabled. See §15. |

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

## 8. Node Details Panel

When a node is clicked, the right panel slides in showing:

### 8.1 Fields displayed

| Field | Source | Notes |
|---|---|---|
| Node ID | `node.data.id` | Header |
| Description | `node.data.description` | Below header |
| Type | `node.data.nodeType` | DQL / DML / DDL badge |
| Source | `node.data.source` | Datasource name or `tempdb` |
| Template | `node.data.template` | `{id, version}` — clickable link to template editor |
| Output | `node.data.output` | For DQL: target + table/mode; an **omitted** `output` renders as "returns result to caller (default)", not as "none" ([Pipeline Contract §9.1](pipeline-contract.md#91-resolution)). For DML/DDL: "side effect" |
| Depends on | `node.data.dependsOn` | List of parent node IDs (clickable) |
| Status | `node.data.status` | Current execution status (idle/running/success/failed/aborted) |
| Last execution stats | fetched via `/api/v1/executions?pipeline_id={id}&limit=1` | Duration, rows_out, error |
| Error (if failed) | from `node_failed` SSE event or last execution | Code, message, details, doc_url |

### 8.2 Template link

Clicking the template `{id, version}` navigates to `/templates/{id}/versions/{version}/editor` — the template editor page (separate spec, future).

---

## 9. Error Display

### 9.1 Error modal

When `pipeline_failed` event arrives, or when the execute call returns an HTTP error:

```javascript
// error.js
class ErrorModal {
    // Alpine owns the modal's visibility + error data. The consumer of this event is the
    // `x-on:show-error.window` listener on #error-modal (§4.2) — the event MUST be
    // dispatched on `window` for that listener to fire.
    show(error) {
        window.dispatchEvent(new CustomEvent('show-error', {
            detail: {
                code: error.code,
                message: error.message,
                userMessage: error.user_message || error.message,
                details: JSON.stringify(error.details, null, 2),
                docUrl: error.doc_url,
                failedNode: error.details?.failed_node_id,
            }
        }));
    }
}
```

### 9.2 Modal content

```
┌──────────────────────────────────────────────────┐
│  Execution Failed                            [×] │
├──────────────────────────────────────────────────┤
│                                                  │
│  Node "fetch_orders" failed.                     │
│                                                  │
│  We couldn't reach the 'pg-prod' database.       │
│  Check that the database is online.              │
│                                                  │
│  Technical details:                              │
│  Code: pipeline.node.datasource_connection_failed│
│  ┌────────────────────────────────────────────┐  │
│  │ {                                           │  │
│  │   "datasource_name": "pg-prod",            │  │
│  │   "underlying_error": "Connection refused" │  │
│  │ }                                           │  │
│  └────────────────────────────────────────────┘  │
│                                                  │
│  [View documentation]  [Close]                   │
└──────────────────────────────────────────────────┘
```

The error modal shows:
- **User-friendly message** (`user_message` from error envelope) — large, prominent.
- **Failed node ID** — which node in the graph caused the failure. Clicking it selects the node in the graph.
- **Technical details** (collapsible) — the full error object in JSON, for developers.
- **Documentation link** (`doc_url`) — opens error-specific docs in a new tab.
- **Close button** — dismisses modal. Graph retains the failed-node coloring.

The `details` object is rendered verbatim, so it must never contain connection secrets: the server's `node_failed` payload carries `datasource_name` and `underlying_error`, never `jdbc_url` or credentials ([REST API §6.4.4](rest-api.md#644-node_failed); redaction mechanism in [Observability](observability.md)). The editor does no redaction of its own — it has nothing to redact with.

The graph also shows the failed node in red — the modal is supplementary detail.

---

## 10. Result Preview

Delivery is **uniform** — there is no inline-vs-claim-check split ([REST API §7](rest-api.md#7-result-delivery)). Every caller result is materialized in Redis before `data_ready` is emitted, and every `data_ready` carries the same fields: full `schema`, the **inline first page**, `total_rows`, `has_more`, `result_url`, `expires_at`.

### 10.1 On `data_ready`

`result.js` renders one panel shape for every result size ([REST API §6.4.7](rest-api.md#647-data_ready)):

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
- BIGDECIMAL / BIGINTEGER values arrive as strings (Type System wire rules) and are rendered as-is — the editor never parses them into JS numbers.
- Nothing is shown for a **pure-ETL pipeline**: with no caller node there is no `data_ready` event. The completion banner shows execution stats only.

### 10.2 Paging and download via the result cursor

Both "Load next page" and the download buttons use the same cursor endpoint ([REST API §7.2](rest-api.md#72-cursor-endpoint)):

```
GET /api/v1/executions/{execution_id}/result?offset=&limit=&format={json|arrow|csv}
```

- Paging is by `offset`/`limit`; ordering is stable because the result was fully materialized before the cursor existed.
- The cursor requires normal session auth + `read` scope + ownership — `result_url` is **not** a capability URL, so the editor sends its credentials like any other API call.
- **TTL is fixed.** Reading pages does not extend it. The panel shows the remaining time from `expires_at` and counts down.
- After expiry the endpoint returns `410` with `result.expired`; the panel replaces the table with **"Result expired — re-run the pipeline"** and a re-execute button. It does not retry.
- `result.execution_failed` (410) and `result.execution_not_found` (404) surface through the same error path (§9). The full endpoint error list is [REST API §7.6](rest-api.md#76-endpoint-errors).
- A result exceeding `datapipelines.result.max-size-bytes` never reaches this panel — the execution itself fails with `result.too_large` and the error modal explains that large datasets belong in `output.target: "datasource"` write-back, not in caller results.

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
        ├── details.js                      (DetailsPanel class)
        ├── error.js                        (ErrorModal class)
        ├── result.js                       (ResultPanel class — §10)
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
| `↑` / `↓` | node list | Move the active option (`aria-activedescendant` on the `<ul>`), selecting as it moves |
| `Home` / `End` | node list | First / last node |
| `Enter` / `Space` | node list | Open the details panel for the active node |
| `Escape` | anywhere | Close details panel → result panel → error modal (topmost first) |
| `+` / `−` | node list or graph controls | Zoom in / out |
| `F` | node list or graph controls | Fit graph to viewport |
| `R` | node list or graph controls | Reset node states to idle (only after a terminal event) |

Selection is bidirectional and single-sourced: arrowing the list calls `PipelineGraph.selectNode()` (§5.4), which sets `.selected` on the canvas node, centres the viewport on it, and sets `aria-selected="true"` on the matching `<li>`. Tapping a node on the canvas runs the same path in reverse. The two representations cannot drift because only one function mutates selection.

The graph canvas is **not** in the tab order (`tabindex="-1"`) — focusing an image the user cannot interact with is a trap, and every graph action is reachable from the list or the controls.

### 14.2 ARIA and status announcements

`a11y.js` owns two jobs, both driven by the same SSE handler that styles the canvas (§6.4) — there is no second source of truth for status:

- `syncStatus(nodeId, status)` rewrites the matching `<li>`'s text (`fetch_orders — DQL — running`). Because the option text changes rather than an ARIA attribute alone, screen readers announce it when the option is active.
- `announce(eventType, data)` writes one sentence into `#graph-status` (`role="status"`, `aria-live="polite"`): `"fetch_orders running"`, `"fetch_orders failed: could not reach pg-prod"`, `"Pipeline completed, 4480 rows"`, `"Execution aborted (cancelled)"`. Terminal and failure events use `aria-live="assertive"` via a second region; per-node progress stays polite so a 20-node pipeline does not flood the buffer.
- Rapid node transitions are coalesced (max one announcement per 500 ms, latest wins) — parallel branches otherwise emit faster than speech synthesis can consume.
- The Execute button carries `aria-busy="true"` for the duration of the stream.

Other regions: details panel `role="region" aria-label="Node details"`; result panel `role="region" aria-label="Execution result"`; error modal `role="alertdialog" aria-modal="true" aria-labelledby="error-modal-title"` with focus moved to it on open and restored to Execute on close.

### 14.3 Color contrast

All node state colors derive from design system tokens, which are audited for WCAG AA compliance by the design system's own contrast audit (`npm run audit:contrast` in the design system project). The specific token mappings in Appendix A ensure:

- **Idle** (`--text-muted` background, `--text-inverted` text): meets AA for large text (node labels are 12px bold with outline).
- **Running** (`--accent-primary` background, `--accent-primary-text` text): design system guarantees this pairing is AA-compliant (it's the same pairing used for primary buttons).
- **Success** (`--text-success` background, `--text-inverted` text): verified per-theme in the design system.
- **Failed** (`--accent-danger` background, `--accent-danger-text` text): design system guarantees this pairing is AA-compliant (same as danger buttons).

If a custom theme introduces a contrast issue, the design system's contrast audit catches it at build time. We run `npm run audit:contrast` in the design system project as part of our CI when syncing themes.

For colorblind users, status is also indicated by:
- **Shape**: DML nodes are diamonds, DDL are tags, DQL are rectangles.
- **Icon**: success shows ✓, failed shows ✗, running shows ⟳ (added as node label suffix).
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
   - `SUCCESS` → the execution had already finished when the stream died; the result is in Redis for its TTL, so the panel is populated from the cursor (§10.2).
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
- **Past executions are still inspectable** after the fact: metadata via `GET /executions/{id}`, the event stream replayable for 1 hour via `GET /executions/{id}/events`, and the result within its TTL via the cursor (§10.2).

---

## 16. Performance

### 16.1 Expected graph sizes

Typical pipelines have 3–20 nodes. Large pipelines might have 50–100. Cytoscape.js handles hundreds of nodes smoothly on canvas.

### 16.2 SSE event throughput

Execution events arrive at human-observable rates (one per node start/complete, ~1-5 seconds apart for real pipelines). No performance concern.

### 16.3 Result preview rendering

The result table renders one page at a time (`datapipelines.result.page-size-rows`); further pages come from the cursor on demand (§10.2) and replace the visible page rather than appending indefinitely. DOM stays small regardless of `total_rows`.

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
- **Integration tests** (Playwright or Cypress):
  - Full execute flow: render → execute → SSE events → graph updates → result panel with the inline first page.
  - Failure flow: execute → node fails → error modal → graph retains state.
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
       Node state colors — derive from design system accent tokens
       ============================================================ */

    /* Idle: muted background, inverted text (readable on dark node) */
    --node-idle-bg:      var(--text-muted);
    --node-idle-text:    var(--text-inverted);

    /* Running: primary action color (indigo in saas theme) */
    --node-running-bg:   var(--accent-primary);
    --node-running-text: var(--accent-primary-text);

    /* Success: success text color (dark green in saas theme, works as node bg with white text) */
    --node-success-bg:   var(--text-success);
    --node-success-text: var(--text-inverted);

    /* Failed: danger accent (red in all themes) */
    --node-failed-bg:    var(--accent-danger);
    --node-failed-text:  var(--accent-danger-text);

    /* Aborted: muted, reduced opacity applied via Cytoscape */
    --node-aborted-bg:   var(--text-muted);
    --node-aborted-text: var(--text-inverted);

    /* ============================================================
       Edge colors
       ============================================================ */
    --edge-default:      var(--border-default);
    --edge-active:       var(--accent-primary);

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

1. **Indirection enables restyling.** If we later decide "running nodes should be teal, not indigo," we change `--node-running-bg` in one place, not every Cytoscape selector.
2. **Semantic clarity.** `--node-running-bg` is self-documenting. `--accent-primary` requires the reader to know what accent-primary means in the context of a graph node.
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
| 2026-08-05 | v1.0 | initial draft | Initial pipeline editor UI spec: Thymeleaf + Alpine.js + Cytoscape.js 3.34.0 + cytoscape-dagre. Three-panel layout, 5 node states, SSE-driven graph updates, vendoring strategy, accessibility, keyboard nav. |
| 2026-08-07 | v1.2 | consistency campaign | **[D7]** §15 rewritten: no SSE reconnection and no `Last-Event-Id` — a dropped stream cancels the execution after the grace period; the editor warns, polls `GET /executions/{id}` at most twice for the final status, and renders `ABORTED`. `execution_aborted` (rest-api §6.4.8) wired into §6.3/§6.4 as a terminal event; explicit Cancel (`DELETE /executions/{id}`) added (§15.2). **[D9]** §10 rewritten for uniform result delivery: one panel shape, inline first page from `data_ready`, cursor paging/downloads within a fixed TTL, `result.expired` handling; inline-vs-claim-check split deleted. **[D1]** "terminal node" → **caller node** throughout; `.terminal` Cytoscape class renamed `.caller` and actually applied in `buildElements()`; zero-caller pipelines documented (no `data_ready`). **[D8]** Theme resolution corrected to `${activeTheme} = users.theme_preference ?: datapipelines.ui.theme` (per-user override, deployment value as default — ui-screens.md v1.1 §4.11); the config key, its default and the valid theme list are referenced from configuration.md §3.10 instead of restated (§3.4, Appendix A.5). **[M]** "Native EventSource" removed from the §3.2 stack (contradicted §7.3). Styling wiring fixed: `nodeType*` and `idle` classes added at build time, `.selected` managed in `selectNode()`/`clearSelection()`. §4.2 adds `result.js`, `a11y.js` and the `x-on:show-error.window` listener that gives §9.1's dispatch a consumer. **[M]** §7.2 `collectParameters()` coerces values to declared wire types (pipeline-contract §6.3) instead of posting FormData strings. **[M]** §14 accessibility rewritten honestly for canvas rendering: per-node `role="button"` is impossible, replaced by a parallel visually-hidden `<ul role="listbox">`, `role="img"` canvas, and an `aria-live` status region. **[M]** Progressive-enhancement overclaim removed (no-JS = metadata + node list, no graph). **[M]** §11 edit mode removed from v1 scope (`?edit=true` deleted) — authoring is LLM/MCP-first, UI edit mode is a ROADMAP item. **[M]** `vendor-manifest.json` unified to `static/vendor/design-system/`. **[M]** Duplicate `### 13.2` renumbered (version upgrades → §13.3). Anchor fixes: auth §6, dag-executor/rest-api cross-links; `jdbc_url` removed from the §9.2 error mockup. See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) §2.13. |
| 2026-08-05 | v1.1 | design system integration | Integrated `@acme/design-tokens` design system as the styling foundation. All hardcoded colors replaced with design system tokens (`--surface-*`, `--text-*`, `--accent-*`). HTML uses `.ds-*` primitives (`.ds-button`, `.ds-card`, `.ds-modal`, etc.). Cytoscape stylesheet reads tokens via `readDesignTokens()` bridge. App-specific node-state tokens (`--node-*-bg/text`) derive from design system accent tokens. Theme switching (9 themes including dark mode) works at runtime without page reload. Replaced Appendix A entirely. Updated vendoring to include design system CSS. |
