# Pipeline Editor UI Specification

**Status:** v1.1 (revised — see Change Log)
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Contract](pipeline-contract.md), [REST API + SSE](rest-api.md), [Type System](type-system.md), [Enums](enums.md), [Auth](auth.md), [@acme/design-tokens Design System](https://github.com/msabir/design-system-starter)
**Last updated:** 2026-08-05

---

## 1. Purpose

The Pipeline Editor is the primary human-facing screen of datapipelines.co. It renders a pipeline as an interactive **DAG visualization** (nodes + edges), shows its metadata (settings, parameters, datasources), lets the user **execute** it, and shows **real-time execution progress** via SSE — highlighting each node as it runs, succeeds, or fails.

This spec defines:
- The page architecture (server-rendered shell + client-side interactivity).
- Technology stack (Thymeleaf + Alpine.js + Cytoscape.js + @acme/design-tokens + fetch + EventSource).
- Graph rendering (Cytoscape.js with dagre layout).
- Node states and visual styles (idle, running, success, failed, aborted).
- SSE event handling → graph updates.
- Execute button flow.
- Error display.
- Save/edit operations.
- JavaScript vendoring strategy (no CDN, no build step).
- Accessibility and keyboard navigation.

---

## 2. Design Principles

1. **Hybrid rendering.** Thymeleaf renders the page shell + initial data on the server. Client-side JS (Alpine.js + vanilla) handles interactivity. No SPA framework, no build step.
2. **Progressive enhancement.** The page works without JavaScript (read-only: shows pipeline metadata + static graph). JavaScript adds: interactive graph, SSE execution, save/edit, error modals.
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
| **Native fetch API** | browser-built-in | REST calls (save pipeline, execute, fetch details). |
| **Native EventSource** | browser-built-in | SSE consumption for execution progress. |

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

**Default theme for datapipelines.co: `saas`** (modern indigo). Enterprise deployments may choose `professional` (navy/financial) or any other theme. Theme is configurable per deployment via `DATAPIPELINES_UI_THEME` env var (default: `saas`).

**Design system CSS load order** (in every page's `<head>`, before app CSS):

```html
<link rel="stylesheet" href="/vendor/design-system/tokens.css">
<link rel="stylesheet" href="/vendor/design-system/themes/saas.css" id="theme-link">
<link rel="stylesheet" href="/vendor/design-system/base.css">
<link rel="stylesheet" href="/vendor/design-system/motion.css">
<link rel="stylesheet" href="/vendor/design-system/primitives.css">
<link rel="stylesheet" href="/css/app.css">                <!-- app-specific -->
```

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

When the theme changes at runtime, the graph re-reads tokens and re-applies the Cytoscape stylesheet. See §15.1.

---

## 4. Page Architecture

### 4.1 URL

```
GET /pipelines/{id}/editor
GET /pipelines/{id}/versions/{version}/editor    (specific version)
```

Authentication: session cookie (browser flow). See [Auth spec §6](auth.md#6-ui-session-auth).

### 4.2 Server-rendered HTML structure

```html
<!DOCTYPE html>
<html lang="en" xmlns:th="http://www.thymeleaf.org">
<head>
    <title>Pipeline Editor — <span th:text="${pipeline.displayName}">Name</span></title>

    <!-- Design System (load order per @acme/design-tokens spec) -->
    <link rel="stylesheet" href="/vendor/design-system/tokens.css">
    <link rel="stylesheet" th:href="@{/vendor/design-system/themes/{theme}.css(theme=${uiTheme})}"
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
                x-text="running ? 'Executing...' : 'Execute'">Execute</button>
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
            <div id="cy"></div>
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

    <!-- Error modal (uses .ds-modal primitive) -->
    <div class="ds-modal-backdrop" id="error-modal"
         x-data="{ visible: false, error: null }" x-show="visible" x-transition.opacity>
        <div class="ds-modal" @click.away="visible = false">
            <div class="ds-modal__header">
                <h2 class="ds-h4 ds-text--danger">Execution Failed</h2>
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
                status: 'idle',                 // §6
            }
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
- The terminal node (`output.target: "caller"`) is on the right.
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
        // Terminal node (output.target: caller) — distinct visual marker
        {
            selector: 'node.terminal',
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
    // Node click → show details panel
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
```

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

Nodes not yet started when pipeline aborts:
                ┌─────────┐
                │ aborted │  (dependency failed; this node never ran)
                └─────────┘
```

### 6.2 CSS class → visual mapping

All colors derive from app-specific semantic tokens defined in `app.css`, which in turn derive from the `@acme/design-tokens` design system. See Appendix A for the full token mapping.

| State | CSS class | Token (background) | Token (text) | Animation | Meaning |
|---|---|---|---|---|---|
| `idle` | (none — default) | `--node-idle-bg` | `--node-idle-text` | none | Initial state, not yet executed |
| `running` | `.running` | `--node-running-bg` | `--node-running-text` | pulse | Node is currently executing |
| `success` | `.success` | `--node-success-bg` | `--node-success-text` | none | Node completed successfully |
| `failed` | `.failed` | `--node-failed-bg` | `--node-failed-text` | brief flash | Node failed; pipeline aborted |
| `aborted` | `.aborted` | `--node-aborted-bg` | `--node-aborted-text`, 0.5 opacity | none | Node never ran (dependency failed) |

Colors automatically adapt to the active design system theme. No hardcoded hex values.

### 6.3 State transitions via SSE events

| SSE event | Graph action |
|---|---|
| `execution_started` | Reset all nodes to `idle`. Disable Execute button. |
| `node_started` | Node → `running`. Incoming edges → `.active`. |
| `node_completed` | Node → `success`. Outgoing edges → `.active`. |
| `node_failed` | Node → `failed`. Update details panel with error. All pending nodes → `aborted`. |
| `pipeline_completed` | All nodes should be `success` (or `aborted` for side-effect-only paths not on the terminal chain). Show success banner. |
| `pipeline_failed` | Show error modal (§9). |
| `data_ready` | Show result preview panel (§10). |

### 6.4 Implementation: state update on SSE event

```javascript
// sse.js
class SseHandler {
    constructor(graph) {
        this.graph = graph;
    }

    onEvent(eventType, data) {
        switch (eventType) {
            case 'execution_started':
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
        }
    }
}

// graph.js (methods on PipelineGraph)
setNodeStatus(nodeId, status) {
    const node = this.cy.getElementById(nodeId);
    node.removeClass('idle running success failed aborted');
    node.addClass(status);
    node.data('status', status);
}

abortPendingNodes(failedNodeId) {
    // Mark all nodes that haven't started as aborted
    this.cy.nodes().forEach(node => {
        if (!node.hasClass('running') && !node.hasClass('success') && !node.hasClass('failed')) {
            node.addClass('aborted');
            node.data('status', 'aborted');
        }
    });
}

resetAllNodes() {
    this.cy.nodes().removeClass('running success failed aborted');
    this.cy.edges().removeClass('active');
    this.cy.nodes().forEach(n => n.data('status', 'idle'));
}
```

---

## 7. Execute Button Flow

### 7.1 User action

1. User fills in parameter form (if pipeline has parameters).
2. Clicks **Execute**.
3. Execute button becomes disabled, label changes to "Executing...".
4. Page opens SSE connection via `fetch` (POST to `/pipelines/{id}/execute` with `Accept: text/event-stream`).
5. SSE events flow in → graph updates in real-time.
6. On completion: button re-enables, result panel shows (if success) or error modal shows (if failure).

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
            },
            body: JSON.stringify({ parameters }),
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
    } catch (error) {
        window.errorModal.show({
            code: 'pipeline.execution.connection_error',
            message: 'Lost connection to server during execution.',
            details: error.message,
        });
    } finally {
        executeBtn.disabled = false;
        executeBtn.textContent = 'Execute';
    }
}

function collectParameters() {
    const form = document.getElementById('parameter-form');
    const params = {};
    new FormData(form).forEach((value, key) => { params[key] = value; });
    return params;
}

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
| Output | `node.data.output` | For DQL: target + table/mode. For DML/DDL: "side effect" |
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
    constructor() {
        this.element = document.getElementById('error-modal');
        // Alpine.js manages visibility + error data
    }

    show(error) {
        this.element.dispatchEvent(new CustomEvent('show-error', {
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
│  │   "jdbc_url": "jdbc:postgresql://...",     │  │
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

The graph also shows the failed node in red — the modal is supplementary detail.

---

## 10. Result Preview

### 10.1 On `data_ready` (inline delivery)

For small results (under 1 MB), the `data_ready` SSE event contains the schema + rows inline. The editor shows a result preview panel:

```
┌──────────────────────────────────────────────────┐
│  Execution Result                            [×] │
├──────────────────────────────────────────────────┤
│  4,480 rows • 2377 ms • 4 nodes succeeded       │
│                                                  │
│  customer_id │ customer_name │ total_amount      │
│ ─────────────┼───────────────┼────────────────── │
│  1           │ Acme Corp     │ "12345.67"        │
│  2           │ Globex        │ "67890.12"        │
│  3           │ Initech       │ "1234.56"         │
│  ...                                           │ │
│                                                  │
│  [Download JSON] [Download CSV] [Copy as JSON]   │
└──────────────────────────────────────────────────┘
```

- Shows first 100 rows as a table.
- BIGDECIMAL and BIGINTEGER values shown as strings (per Type System wire rules) — the table renders them as-is.
- Download buttons fetch the full result (for inline results, the data is already available; for claim-check, it fetches from `result_url`).

### 10.2 On `data_ready` (claim-check delivery)

For large results (over 1 MB), the event contains `result_url` instead of inline rows:

```
┌──────────────────────────────────────────────────┐
│  Execution Result                            [×] │
├──────────────────────────────────────────────────┤
│  12,450,000 rows • 2377 ms • 4 nodes succeeded   │
│                                                  │
│  Result is large. It's available for 5 minutes.  │
│                                                  │
│  [Download JSON] [Download CSV] [Download Arrow] │
│  [Preview first 100 rows]                        │
└──────────────────────────────────────────────────┘
```

Download buttons hit `result_url` with the appropriate `Accept` header. Preview fetches the first page (offset=0, limit=100).

---

## 11. Save / Edit Operations

### 11.1 Read-only mode (default)

By default, the editor opens in read-only mode for the published version. The graph is non-editable (no drag-and-drop, no node creation). The user can:
- View the pipeline.
- Execute it.
- Inspect nodes.
- Switch versions.

### 11.2 Edit mode

Clicking **Edit** (visible only for users with `author` scope) navigates to `/pipelines/{id}/editor?edit=true`. In edit mode:
- Node descriptions and parameters are editable in the details panel.
- "Save as new version" button → `PUT /pipelines/{id}` with modified JSON.
- Graph topology editing (add/remove nodes, change dependencies) is **not supported in v1** — it requires a more complex editor. Users edit the pipeline JSON directly or via MCP/LLM, then reload.

### 11.3 Why no drag-and-drop graph editing in v1

Drag-and-drop graph editing (add node, connect nodes, reposition) requires:
- A palette of node types to drag from.
- Edge-drawing interaction (`cytoscape-edgehandles` extension).
- Form for node properties (id, source, template, output, depends_on).
- Live validation (no cycles, terminal exists, etc.).

This is a v2 feature. For v1, the graph is a **visualization + execution surface**, not an authoring surface. Pipelines are authored by:
- LLMs via MCP (the primary authoring path for this product).
- Direct JSON editing (developer path).
- The template editor (for SQL templates).

---

## 12. JavaScript Organization

### 12.1 File structure

```
modules/web/src/main/resources/static/
├── vendor/
│   ├── design-system/                      ← @acme/design-tokens (vendored CSS)
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
│   ├── pipeline-editor.css                 ← editor-specific styles (uses design system tokens)
│   └── vendor-manifest.json                ← SHA-256 hashes for all vendored files
└── js/
    └── pipeline-editor/
        ├── init.js                         (page load → graph init)
        ├── graph.js                        (PipelineGraph class)
        ├── sse.js                          (SseHandler class)
        ├── execute.js                      (executePipeline function)
        ├── details.js                      (DetailsPanel class)
        ├── error.js                        (ErrorModal class)
        └── result.js                       (ResultPanel class)
```

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
3. Commit the file + a `vendor-manifest.json` recording:
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
3. Record the version (from `design-system-starter/package.json`) in `vendor-manifest.json`.
4. A CI script (`scripts/sync-design-system.sh`) automates this: builds the design system, copies files, verifies version match.

This keeps the design system as an independent project that can evolve separately, while datapipelines vendors a specific build for reproducibility.

### 13.2 Version upgrades

When upgrading a vendored library:
1. Download the new version.
2. Compute new SHA-256.
3. Update the file + manifest.
4. Run the editor test suite.
5. Commit.

No `npm install` in CI, no `package-lock.json`, no transitive dependency resolution. Fully deterministic.

---

## 14. Accessibility

### 14.1 Keyboard navigation

| Key | Action |
|---|---|
| `Tab` | Move focus between: Execute button → version dropdown → parameter inputs → graph → graph controls |
| `Enter` (on Execute) | Execute pipeline |
| `Tab` / `Shift+Tab` within graph | Cycle through nodes |
| `Enter` (on selected node) | Open details panel |
| `Escape` | Close details panel / error modal / result panel |
| `+` / `-` | Zoom in / out (when graph focused) |
| `F` | Fit graph to viewport |
| `R` | Reset node states to idle (after execution completes) |

### 14.2 ARIA

- Graph container: `role="application"`, `aria-label="Pipeline DAG visualization"`.
- Each node rendered as an SVG element with `role="button"`, `aria-label="{node_id} — {status}"`, `aria-selected="true|false"`.
- Details panel: `role="region"`, `aria-label="Node details"`.
- Error modal: `role="alertdialog"`, `aria-labelledby` pointing to the error title.
- Execute button: `aria-busy="true"` while execution in progress.

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
- **Text**: details panel always shows the status in words.

---

## 15. SSE Stream Reconnection

### 15.1 Connection drop

If the SSE connection drops mid-execution (network blip, server restart):

1. The `fetch` reader throws an error.
2. Error caught in `execute.js`.
3. UI shows a non-blocking warning: "Connection lost. Attempting to reconnect..."
4. Client attempts to reconnect with `Last-Event-Id` header via `GET /pipelines/{id}/execute?execution_id={exec_id}`.
5. If reconnection succeeds within 30 seconds, the stream resumes from the last event. The user sees no data loss.
6. If reconnection fails, the UI shows: "Connection lost. Execution continues on the server. Check execution history for results."

### 15.2 Execution continues server-side

Per the [DAG Executor spec §10](dag-executor.md#10-sse-event-integration), executions complete regardless of SSE client state. A dropped SSE connection does not abort the pipeline. The user can always find the result via `/executions/{execution_id}` after the fact.

---

## 16. Performance

### 16.1 Expected graph sizes

Typical pipelines have 3–20 nodes. Large pipelines might have 50–100. Cytoscape.js handles hundreds of nodes smoothly on canvas.

### 16.2 SSE event throughput

Execution events arrive at human-observable rates (one per node start/complete, ~1-5 seconds apart for real pipelines). No performance concern.

### 16.3 Result preview rendering

The result preview table renders the first 100 rows only. Larger result sets are accessed via download (claim-check). DOM stays small.

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
| SSE reconnection | Disconnect network mid-execution | Reconnects automatically within 30s |
| Large result | Execute pipeline producing > 1MB result | Result panel shows claim-check download links |
| Keyboard nav | Tab through page | All interactive elements reachable via keyboard |

### 17.2 Automated tests

- **Unit tests** (JS, run in headless browser or jsdom):
  - `PipelineGraph.buildElements()` — correct node/edge construction from pipeline JSON.
  - `PipelineGraph.setNodeStatus()` — class transitions correct.
  - `parseSseEvent()` — correct SSE wire format parsing.
  - `collectParameters()` — correct form-to-object mapping.
- **Integration tests** (Playwright or Cypress):
  - Full execute flow: render → execute → SSE events → graph updates → result panel.
  - Failure flow: execute → node fails → error modal → graph retains state.
  - Version switch: dropdown changes → page reloads with correct version.

---

## 18. Stability Promise

### 18.1 Frozen in v1

- The page URL structure (`/pipelines/{id}/editor`).
- The three-panel layout (sidebar + graph + details).
- The Cytoscape.js + dagre LR rendering.
- The 5 node states (idle/running/success/failed/aborted).
- The SSE event → graph update mapping.
- The vendoring strategy (no CDN, no build step).
- The error modal and result panel shapes.

### 18.2 Not frozen

- Specific color values (may be themed in future).
- JS file organization (may be refactored to modules).
- Addition of graph editing (drag-and-drop) in v2.
- Layout algorithm choice (may offer per-pipeline config).

---

## 19. Open Questions / Future Additions

Out of scope for v1, tracked in [ROADMAP](ROADMAP.md):

- **Drag-and-drop graph editing** — add nodes, draw edges, edit in place. Requires node palette, edge-drawing interaction, live validation. v2.
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

1. User selects theme from a dropdown (in the global nav, not the editor itself).
2. JS swaps `document.getElementById('theme-link').href = '/vendor/design-system/themes/' + themeName + '.css'`.
3. All CSS custom properties update instantly across the page.
4. The editor's `PipelineGraph` listens for theme changes and re-reads tokens:
   ```javascript
   window.addEventListener('theme-change', () => {
       const newTokens = readDesignTokens();
       this.cy.style().fromJson(buildGraphStyle(newTokens)).update();
   });
   ```
5. Graph re-styles without re-rendering (Cytoscape's `.style().update()` is incremental).

Available themes (from the design system):
- `saas` (default — modern indigo, ideal for devtools)
- `light` (clean neutral)
- `dark` (dark mode)
- `auto` (follows OS `prefers-color-scheme`)
- `professional` (navy/enterprise)
- `healthcare`, `minimal`, `forest`, `ocean` (domain-specific)

---

## Appendix B: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-05 | v1.0 | initial draft | Initial pipeline editor UI spec: Thymeleaf + Alpine.js + Cytoscape.js 3.34.0 + cytoscape-dagre. Three-panel layout, 5 node states, SSE-driven graph updates, vendoring strategy, accessibility, keyboard nav. |
| 2026-08-05 | v1.1 | design system integration | Integrated `@acme/design-tokens` design system as the styling foundation. All hardcoded colors replaced with design system tokens (`--surface-*`, `--text-*`, `--accent-*`). HTML uses `.ds-*` primitives (`.ds-button`, `.ds-card`, `.ds-modal`, etc.). Cytoscape stylesheet reads tokens via `readDesignTokens()` bridge. App-specific node-state tokens (`--node-*-bg/text`) derive from design system accent tokens. Theme switching (9 themes including dark mode) works at runtime without page reload. Replaced Appendix A entirely. Updated vendoring to include design system CSS. |
