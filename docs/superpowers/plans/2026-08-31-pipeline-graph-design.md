# Pipeline Graph Design Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the graph's current look — 80×40 boxes with 11px labels truncated at 20 characters and crammed inside, no selection style, and states rendered as loud full-fills — with nodes that read as labeled cards: the name BELOW a clean shape, an unmistakable selected state, execution states as accents, and a layout with room to breathe.

**Architecture:** Cytoscape 3.34.0 + cytoscape-dagre, both vendored — no new dependencies, no layout-engine change. Every visual value flows through the EXISTING token plumbing: `graph.js:6-22` (`readDesignTokens`) reads `--node-*` / `--edge-*` custom properties via `getComputedStyle`, and `app.css:2-18` maps them onto design-system accents. This plan extends that map; nothing is hardcoded in JS, and `updateTheme()` (`graph.js:235-240`, already wired to theme swaps) keeps working.

**Read this before anything else:** most of what looks like new design here is already WRITTEN in `docs/pipeline-editor.md` §5.3 and §6.2 and was never implemented — selection styling, per-node-type differentiation, the caller-node marker, the running pulse. See **Spec vs. code** below. Three of the five tasks close that gap; only the label-below and accent-over-fill decisions genuinely change the spec. Do not "rewrite" §5.3 in a way that quietly drops a requirement the code simply never reached.

**Tech Stack:** Cytoscape.js stylesheet JSON, cytoscape-dagre, design tokens. Tests: `node --test` via the `editorJsTest` Gradle task (`modules/web/build.gradle.kts:82-117`; `fileTree("src/test/js") { include("*.test.mjs") }`, so a new test file is picked up with no build change). Browser screenshots as visual evidence.

**Spec:** `docs/pipeline-editor.md` §5.1 (initialization), §5.2 (dagre choice), §5.3 (graph stylesheet), §6.2 (state → visual mapping); `docs/ui-screens.md` §4.4 if it names graph visuals.

**The user-facing contract (operator's words, verbatim):** *"a shape for select"* — a selected node is unmistakable; *"label contained below, not inside"* — the node's name renders beneath the shape, full text, not truncated inside a box.

---

## Verified state — checked 2026-08-31; do not re-derive

### Blocker: `graph.js` has no test surface

`graph.js:242` publishes `window.PipelineGraph` and nothing else. `readDesignTokens` and `buildStylesheet` are **private closure functions** — not reachable from a `node --test` require, and there is no `module.exports` branch at all (compare `toast.js:69`, which has one). `buildElements` is a prototype method and is reachable through the constructor, but the constructor calls `readDesignTokens()`, which calls `getComputedStyle(document.documentElement)`.

**Consequence: Task 1 Step 1 cannot be written until an export surface exists.** Task 1 opens by adding one, in the shape the other tested modules already use. This is not optional scaffolding — without it every "failing test first" step in this plan is unwritable.

### Spec vs. code — the gap this plan closes

| `pipeline-editor.md` requires | `graph.js` today | This plan |
|---|---|---|
| §5.3 `node.selected` → `border-width: 4`, `border-color: accentPrimary` | **nothing** — no selected selector at all | Task 2 implements it (and upgrades it to a ring + halo) |
| §5.3 `node.nodeTypeDQL/DML/DDL` → per-type shapes (`round-diamond`, `round-tag`) | **no type classes emitted** (`buildElements:144` sets only `idle` / `pipeline-node`) | Task 1 implements per-type differentiation — **decision below** |
| §5.3 `node.caller` → `border-style: double`, `border-width: 5` (marks the result node, pipeline-contract §9) | **not emitted, not styled** | Task 1 implements it |
| §5.3 node `width: 140`, `height: 50`, `font-size: 12` | `width: 80`, `height: 40`, `font-size: 11px` (`:34-38`) | Task 1 replaces both with the card + label-below geometry |
| §6.2 `running` → pulse; `failed` → brief flash; `aborted` → 0.5 opacity | no animation, no opacity (`:44-75`) | Task 2 implements pulse + reduced-motion gate; §6.2 is amended for accent-over-fill |
| §5.1/§5.2 dagre `rankDir: 'LR'` | matches (`:121`) | Task 3 adds spacing |

Two genuine spec CHANGES, both from the operator's contract: the label moves outside the shape (§5.3's `text-valign: center` → `bottom`), and states become accents instead of full background fills (§6.2's background/text token pairs → accent tokens). Everything else in the table is implementation of what is already written.

**Decided (operator, 2026-08-31) — per-type differentiation is SHAPE, not an accent bar.** §5.3 already specifies distinct shapes per node type; an earlier revision of this plan proposed a 3px left accent bar instead, which would have overridden a written spec silently. The ratified rule: **shape carries TYPE, colour carries STATE, and the ring carries SELECTION** — three channels, never competing for the same pixels. Shape also survives greyscale, colour-blindness and a theme swap, which a colour bar does not. Do not reopen this; `border-left-width` exists in this Cytoscape version but is not used for node type.

### Cytoscape 3.34.0 capabilities — verified by grepping the vendored bundle's property table

`modules/web/src/main/resources/static/vendor/cytoscape/cytoscape.min.js` (v3.34.0 per `pipeline-editor.md:1127`) supports every property this plan needs:

- Per-side borders: `border-left-width`, `border-right-width`, `border-top-width`, `border-bottom-width`, plus `border-position`, `border-style`, `border-cap`, `border-join`, `border-dash-pattern`. (Per-side widths are a recent addition — they exist in this pinned version; do not assume they exist if the vendored version is ever rolled back.)
- Halos: `overlay-color`, `overlay-opacity`, `overlay-padding`, `overlay-shape`, `overlay-corner-radius`, and the same five `underlay-*`. **`underlay-*` is the better choice for a selection halo** — `overlay-*` paints ON TOP of the node and dims its label.
- Labels: `text-valign`, `text-halign`, `text-margin-x/y`, `text-max-width`, `text-wrap` (`"none" | "wrap" | "ellipsis"`), `text-overflow-wrap`, `text-outline-*`, `text-background-*`.
- Motion: `transition-property`, `transition-duration`, `transition-delay`, `transition-timing-function` — these are ONE-SHOT transitions on a style change. **Cytoscape stylesheets have no keyframes**, so a repeating pulse must be driven by `ele.animate({...}, {duration, complete: loop})` or by toggling a class on a timer. Plan for that; a `@keyframes` rule in a stylesheet entry is silently ignored.

### cytoscape-dagre layout options — verified from `cytoscape-dagre.js:305-368`

Supported: `nodeSep`, `edgeSep`, `rankSep`, `rankDir`, `align`, `acyclicer`, `ranker`, `minLen`, `edgeWeight`, `fit` (**default `true`**), `padding` (**default `30`**), `spacingFactor`, `nodeDimensionsIncludeLabels` (**default `false`**), `animate`, `animateFilter`, `animationDuration`, `animationEasing`, `boundingBox`, `transform`, `ready`, `sort`, `stop`.

- **There is no `marginX` / `marginY`.** The previous revision's Task 3 asserted both; they are grid/cose options and dagre ignores them. Use `padding`.
- **`nodeDimensionsIncludeLabels: true` is mandatory once the label sits below the node** — at the default `false`, dagre lays out on the node box alone and the labels of one rank overlap the next. This is the single option that decides whether label-below looks deliberate or broken.
- `fit: true` + `padding` are already defaults, so the previously planned explicit `cy.fit(padding)` after layout is redundant. Set `padding` in the layout options.

### Token map and reduced motion

- `app.css:2-18` is the `:root` block; the `--node-*` / `--edge-*` pairs are lines **6-17** (the previous revision said 6-13). Every value already resolves through a design-system variable with a hex fallback — keep that shape for new tokens.
- `prefers-reduced-motion: reduce` blocks in `primitives.css` are at lines 402, 511, 621, 812, 888 and **1441** (not 1451). None of them can reach a Cytoscape canvas, which is drawn to `<canvas>`, not styled by CSS — the gate has to be read in JS with `window.matchMedia("(prefers-reduced-motion: reduce)")`.

### A11y finding — the keyboard path is dead today

`a11y.js:19-44` builds `<li class="pe-node-list-item" role="option">` elements with click and keydown handlers, but **gives them no `tabindex`**, while the `<ul id="pe-node-list">` carries `tabindex="0"` (`editor.html:101`). An element without `tabindex` cannot receive focus, so `focusNextSibling`'s `el.focus()` (`a11y.js:48-56`) is a no-op and the `<li>` keydown handlers never fire — arrow keys and Enter do nothing. **Task 5's browser evidence step "keyboard-only selection visibly mirroring" cannot pass on today's code.**

The minimal correct fix is the roving-tabindex pattern: `li.setAttribute("tabindex", i === 0 ? "0" : "-1")` in `buildNodeList`, and in `a11ySyncNode` set the selected item's tabindex to `0`, every other to `-1`, and call `.focus()` on it. **This is adjacent scope.** Task 2 Step 4 includes it because that task already owns the a11y mirror and the evidence depends on it; if the operator would rather it were a separate change, drop that step and strike the keyboard line from Task 5's evidence — do not leave the evidence claim standing over a dead path.

### No demo pipeline exists

`./app.sh --start --demo` seeds **datasources only** — `sample-trips` (POSTGRES), `sample-weather` (MYSQL), `sample-reference` (SQLITE), from `deploy/sample-data/bootstrap-datasources.yml`. There are no seeded pipelines or templates, so `/pipelines/{id}/editor` has nothing to open. `big_result` and `slow_scan`, named as demo fixtures in the previous revision, do not exist — `slow_scan` is a node id inside `tests/integration-tests/.../PipelineCompositionE2eTest.kt:245`.

**Task 0 creates the fixture.** Every browser-evidence step in this plan and in the execute-page plan depends on it.

### No drift test parses these docs

Nothing under `modules/*/src/test` or `tests/` reads `pipeline-editor.md` or `ui-screens.md` (the `*SpecDriftTest` family covers config keys, auth scopes, error codes and the MCP surface). Editing these two docs cannot turn `main` red the way an error-code catalog can — but `./scripts/docs-audit.sh` still gates heading/link integrity.

---

## Global Constraints

- Branch: `feat/graph-design` via worktree (`superpowers:using-git-worktrees`). Independent of the table and toast plans. **Sequence before the execute-page plan** — both touch `pipeline-editor.css` and `editor.html`; never run them in parallel worktrees.
- Design tokens only. New `--node-*` / `--edge-*` values are added to the `app.css:2-18` map and REFERENCE design-system variables (`--accent-*`, `--surface-*`, `--border-*`, `--text-*`) with a hex fallback, matching the existing entries. The vendored design-system CSS is never edited.
- Reduced motion is read in JS (`window.matchMedia`), not CSS — see above. Every animated property needs a still fallback.
- The a11y surface (`a11y.js` node list, `#pe-live-region`) keeps behavioural parity: every visual state change has a matching `aria-selected` or text treatment.
- Falsification discipline (027b): every new `node --test` goes RED against the base `graph.js` before it goes green, and the red output is pasted into the handback.
- Full gate before merge: `./gradlew build ktlintCheck detekt editorJsTest --rerun-tasks`. `editorJsTest` is named explicitly because it SKIPS (not fails) when node is absent — confirm the log shows it ran.

---

### Task 0: The demo fixture (prerequisite for every browser-evidence step)

**Files:** none in the repo — this creates data in the running demo stack. Record the exact requests in the handback so the next session can recreate it.

- [ ] **Step 1: Start the demo stack.**

Run: `./app.sh --start --demo`
Expected: services healthy; `deploy/.env.demo` scaffolded on first use.

- [ ] **Step 2: Sign in and mint a key.** Use the demo's local login (auth.md §5A — the demo needs no OIDC client), then create an API key with `author` scope on the API Keys screen. Export it as `DP_KEY`.

- [ ] **Step 3: Create three templates** — one per node type, so the graph has something to differentiate. Against `POST /api/v1/templates` (`TemplatesController.kt:53`):

```bash
curl -sS -X POST "$BASE/api/v1/templates" -H "Authorization: Bearer $DP_KEY" \
  -H 'Content-Type: application/json' -d '{
  "id": "trips_by_day.sql", "dialect": "POSTGRES", "display_name": "Trips by day",
  "description": "Daily trip counts for the requested window",
  "body": "SELECT pickup_date, COUNT(*) AS trips\nFROM trips\nWHERE pickup_date >= DATE ${start_date}\nGROUP BY pickup_date\nORDER BY pickup_date"
}'
```

Repeat for `stage_trips.sql` (a DDL `CREATE TABLE` against tempdb) and `top_days.sql` (a DQL reading the staged table). Keep every body short — this fixture exists to be LOOKED at, and Task 5's screenshots include the SQL panel once the execute-page plan lands.

- [ ] **Step 4: Create the pipeline** — four nodes so the layout has two ranks and a fan-in, one of each SQL type plus one caller node. Against `POST /api/v1/pipelines` (`PipelinesController.kt:52`), following the `pipeline-contract.md` §3.1 shape: `name: "graph_fixture"`, one `parameters` entry (`start_date`, type `DATE`, with a default — a default matters, see the execute-page plan), and nodes `stage_trips` (DDL, `source: "tempdb"`), `trips_by_day` (DQL, `source: "sample-trips"`, `output.target: "tempdb"`, `depends_on: ["stage_trips"]`), `top_days` (DQL, `source: "tempdb"`, no `output` block → resolves to `caller`, `depends_on: ["trips_by_day"]`).

- [ ] **Step 5: Confirm the fixture renders.** Open `/pipelines/{id}/editor`, screenshot the graph as the BEFORE image for Task 5, and record the pipeline id in the handback.

- [ ] **Step 6:** Nothing to commit. If the operator already has a suitable pipeline in the demo workspace, use it and record its id instead.

---

### Task 1: Test surface, node cards, label-below, per-type and caller classes

**Files:**
- Modify: `modules/web/src/main/resources/static/js/pipeline-editor/graph.js`
- Modify: `modules/web/src/main/resources/static/css/app.css` (extend the `:root` map at `:2-18`)
- Test: `modules/web/src/test/js/graph-stylesheet.test.mjs` (new)

**Interfaces produced — Tasks 2 and 3 consume these:**
- `graph.js` exports `{ PipelineGraph, buildStylesheet, buildElements, readDesignTokens }` via `module.exports` when `module` is defined, and keeps `window.PipelineGraph` for the browser.
- `buildElements(nodes)` becomes a **pure function** taking the node array and returning the elements array, with the prototype method delegating to it. Class emission: `idle`, plus `type-dql` / `type-dml` / `type-ddl` / `pipeline-node` by `n.type`, plus `caller` when the node resolves to the caller target (`!n.output || n.output.target === "caller"`, pipeline-contract §4.7 — an omitted `output` block means caller).
- New tokens, each `var(--design-system-var, #hexfallback)` like its neighbours: `--node-surface`, `--node-border`, `--node-label-text`, `--node-selected-ring`, `--node-selected-halo`, `--node-running-accent`, `--node-success-accent`, `--node-failed-accent`, `--node-aborted-accent`.
- Node geometry: `width: 120`, `height: 44`, `shape: round-rectangle` (`hexagon` for `pipeline-node`, `round-diamond` for `type-dml`, `round-tag` for `type-ddl` — §5.3's shapes), `background-color: --node-surface`, `border-width: 1`, `border-color: --node-border`; label OUTSIDE via `text-valign: "bottom"`, `text-halign: "center"`, `text-margin-y: 8`, `font-size: 12`, `text-max-width: 160`, `text-wrap: "ellipsis"`, `color: --node-label-text`.

- [ ] **Step 1: Add the export surface.** At `graph.js:242`, replace the single `window` assignment:

```js
  var api = {
    PipelineGraph: PipelineGraph,
    buildStylesheet: buildStylesheet,
    buildElements: buildElements,
    readDesignTokens: readDesignTokens,
  };
  // node --test requires this file directly (the 027b harness); the browser keeps
  // the global the editor's other modules already reference.
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PipelineGraph = PipelineGraph;
```

Extract the current prototype `buildElements` body into a module-level `function buildElements(nodes)` and make the method `return buildElements(this.nodes);` — the tests drive the pure function, and `render()` keeps working unchanged.

- [ ] **Step 2: Write the failing tests** in `modules/web/src/test/js/graph-stylesheet.test.mjs`, modelled on `toast.test.mjs`'s loader (`createRequire` + `delete require.cache`):

```js
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");

function loadGraph() {
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}

/** The stylesheet is a flat array; find the entry for a selector. */
function styleFor(sheet, selector) {
  const entry = sheet.find((e) => e.selector === selector);
  assert.ok(entry, `no stylesheet entry for selector ${selector}`);
  return entry.style;
}

// Distinct sentinel values per key: a token the stylesheet forgets to read then
// asserts as undefined === undefined and the test passes vacuously. Task 2 adds
// selection/state keys here — keep every value unique.
const TOKENS = {
  nodeSurface: "#f01", nodeBorder: "#f02", nodeLabelText: "#f03",
  nodeSelectedRing: "#f04", nodeSelectedHalo: "#f05",
  nodeRunningAccent: "#f06", nodeSuccessAccent: "#f07",
  nodeFailedAccent: "#f08", nodeAbortedAccent: "#f09",
  edgeIdleStroke: "#f0a", edgeActiveStroke: "#f0b",
};

test("the node label renders BELOW the shape, not inside it", () => {
  const style = styleFor(loadGraph().buildStylesheet(TOKENS), "node");
  assert.equal(style["text-valign"], "bottom");
  assert.ok(style["text-margin-y"] > 0, "the label needs clearance from the shape");
  // The 80x40 box the redesign replaces.
  assert.notEqual(style.width, 80);
  assert.notEqual(style.height, 40);
});

test("a long display name survives buildElements un-truncated", () => {
  const name = "a_very_long_node_display_name_beyond_twenty";
  const els = loadGraph().buildElements([{ id: "n1", display_name: name, type: "DQL" }]);
  assert.equal(els[0].data.label, name);      // truncation is now a stylesheet concern
  assert.ok(!els[0].data.label.includes("..."));
});

test("buildElements emits a type class per node type", () => {
  const els = loadGraph().buildElements([
    { id: "a", type: "DQL" }, { id: "b", type: "DML" },
    { id: "c", type: "DDL" }, { id: "d", type: "PIPELINE" },
  ]);
  assert.match(els[0].classes, /\btype-dql\b/);
  assert.match(els[1].classes, /\btype-dml\b/);
  assert.match(els[2].classes, /\btype-ddl\b/);
  assert.match(els[3].classes, /\bpipeline-node\b/);
  els.forEach((e) => assert.match(e.classes, /\bidle\b/));   // §6.2: idle is explicit
});

test("the caller node is marked — omitted output means caller (contract §4.7)", () => {
  const els = loadGraph().buildElements([
    { id: "staged", type: "DQL", output: { target: "tempdb", table: "t" } },
    { id: "result", type: "DQL" },
  ]);
  assert.ok(!/\bcaller\b/.test(els[0].classes));
  assert.match(els[1].classes, /\bcaller\b/);
});

test("edges are still built from depends_on", () => {
  const els = loadGraph().buildElements([
    { id: "a", type: "DQL" }, { id: "b", type: "DQL", depends_on: ["a"] },
  ]);
  const edges = els.filter((e) => e.group === "edges");
  assert.equal(edges.length, 1);
  assert.deepEqual([edges[0].data.source, edges[0].data.target], ["a", "b"]);
});
```

- [ ] **Step 3: Run and verify RED.**

Run: `./gradlew :modules:web:editorJsTest`
Expected: FAIL. Paste the output into the handback — it is the falsification record. If the log says `editorJsTest SKIPPED — node not on PATH`, install Node ≥ 18; a skipped guard is not evidence.

- [ ] **Step 4: Extend the token map** in `app.css`, inside the existing `:root` block, keeping the `var(--ds-var, #fallback)` shape:

```css
  /* Graph node cards (pipeline-editor.md §5.3). The card is a neutral surface; TYPE is
     carried by shape and STATE by an accent border — colour never has to carry both. */
  --node-surface: var(--surface-raised, #f9fafb);
  --node-border: var(--border-default, #d1d5db);
  --node-label-text: var(--text-primary, #111827);
  --node-selected-ring: var(--accent-primary, #2563eb);
  --node-selected-halo: var(--accent-primary, #2563eb);
  --node-running-accent: var(--accent-primary, #2563eb);
  --node-success-accent: var(--accent-success, #16a34a);
  --node-failed-accent: var(--accent-danger, #dc2626);
  --node-aborted-accent: var(--accent-warning, #f59e0b);
```

Keep the existing `--node-*-bg` / `--node-*-text` entries for now: `readDesignTokens` falls back to them while the map rolls out, and Task 2 decides which become dead. Delete dead tokens in Task 2, not here.

- [ ] **Step 5: Implement `readDesignTokens` additions and the `node` stylesheet entry.** Each new key reads its property and falls back to the current value, so a stale theme file cannot blank the graph:

```js
      nodeSurface: styles.getPropertyValue("--node-surface").trim() ||
        styles.getPropertyValue("--node-idle-bg").trim() || "#f9fafb",
```

- [ ] **Step 6: Delete the truncation** at `graph.js:135-136` — `text-max-width` + `text-wrap: "ellipsis"` now own it.

- [ ] **Step 7: Run and verify GREEN.**

Run: `./gradlew :modules:web:editorJsTest`

- [ ] **Step 8: Commit.**

```bash
git add modules/web/src/main/resources/static/js/pipeline-editor/graph.js \
        modules/web/src/main/resources/static/css/app.css \
        modules/web/src/test/js/graph-stylesheet.test.mjs
git commit -m "feat(web): graph node cards, labels below the shape, type and caller classes (031)"
```

---

### Task 2: Selection, state accents, the running pulse, and the a11y mirror

**Files:**
- Modify: `graph.js` (stylesheet + a pulse driver), `app.css` (retire dead tokens), `modules/web/src/main/resources/static/css/pipeline-editor.css:219-230` (the a11y list selected style), `a11y.js` (roving tabindex — see the a11y finding)
- Test: `graph-stylesheet.test.mjs` (extend)

- [ ] **Step 1: Write the failing tests.**

```js
test("a selected node is unmistakable — ring plus halo", () => {
  const style = styleFor(loadGraph().buildStylesheet(TOKENS), "node:selected");
  assert.ok(style["border-width"] >= 3);
  assert.equal(style["border-color"], TOKENS.nodeSelectedRing);
  // underlay, not overlay: an overlay paints over the node and dims its label.
  assert.ok(style["underlay-opacity"] > 0);
  assert.ok(style["underlay-padding"] > 0);
});

test("states are accents, not full fills", () => {
  const sheet = loadGraph().buildStylesheet(TOKENS);
  const accent = {
    running: TOKENS.nodeRunningAccent, success: TOKENS.nodeSuccessAccent,
    failed: TOKENS.nodeFailedAccent, aborted: TOKENS.nodeAbortedAccent,
  };
  Object.keys(accent).forEach((s) => {
    const style = styleFor(sheet, "node." + s);
    assert.equal(style["background-color"], undefined,
      `state ${s} must not repaint the card background`);
    // Assert the VALUE, not merely presence: a border-color left on the old
    // --node-*-bg token would satisfy a truthiness check and change nothing.
    assert.equal(style["border-color"], accent[s]);
  });
  assert.equal(styleFor(sheet, "node.aborted").opacity, 0.5);   // §6.2 already required this
});

test("the pulse is gated on the reduced-motion preference", () => {
  const graph = loadGraph();
  assert.equal(graph.pulseEnabled({ matches: true }), false);   // reduce → still
  assert.equal(graph.pulseEnabled({ matches: false }), true);
});
```

- [ ] **Step 2: Run and verify RED.** `./gradlew :modules:web:editorJsTest`

- [ ] **Step 3: Implement selection.** Cytoscape's own `:selected` state is already driven by `a11y.js:71-75` (`cyNode.select()`), so style the pseudo-class rather than inventing a class:

```js
      {
        // §5.3 required a selected style and the code never had one. Ring + underlay
        // halo: the ring reads at any zoom, the halo survives a state accent sitting
        // on the same border. underlay-* paints BEHIND the node, so the label stays legible.
        selector: "node:selected",
        style: {
          "border-width": 3,
          "border-color": tokens.nodeSelectedRing,
          "underlay-color": tokens.nodeSelectedHalo,
          "underlay-opacity": 0.18,
          "underlay-padding": 6,
        },
      },
```

- [ ] **Step 4: Implement state accents**, replacing the four full-fill entries at `graph.js:44-75`. Each sets `border-color` (and `border-width: 2`) only; `aborted` additionally sets `opacity: 0.5`, which §6.2 already requires. Retire whichever `--node-*-bg` / `--node-*-text` tokens no longer have a reader, in `app.css` and in `readDesignTokens`, in this same commit — a token nothing reads is drift.

- [ ] **Step 5: Implement the pulse.** A Cytoscape stylesheet has no keyframes, so the running pulse is driven in JS. Add a pure `pulseEnabled(mediaQueryList)` helper (so the test above can drive it without a browser) and a driver that starts on `setNodeState(id, "running")` and stops on any other state:

```js
  /** Reduced motion cannot be read from CSS here — the graph is canvas, not DOM. */
  function pulseEnabled(mql) {
    return !(mql && mql.matches);
  }

  PipelineGraph.prototype.startPulse = function (node) {
    if (!pulseEnabled(window.matchMedia("(prefers-reduced-motion: reduce)"))) return;
    var loop = function () {
      if (!node.hasClass("running")) return;
      node.animate({ style: { "border-width": 5 } }, { duration: 600, complete: function () {
        node.animate({ style: { "border-width": 2 } }, { duration: 600, complete: loop });
      } });
    };
    loop();
  };
```

The still fallback is the accent border itself, which is already applied — with reduced motion the node is unambiguously "running", just not animated.

- [ ] **Step 6: Mirror in the a11y list.** `pipeline-editor.css:228` already styles `.pe-node-list-item[aria-selected="true"]` together with `:hover` — split them so selection is distinct from hover, and give the selected item the same `--node-selected-ring` treatment via tokens (never a copy of the canvas colours). Add a state indicator per item (a `data-state` attribute set alongside `aria-selected`, styled with the same accent tokens) so a keyboard user sees execution state without the canvas.

- [ ] **Step 7: Fix the dead keyboard path** (see the a11y finding — drop this step if the operator wants it separate, and strike the keyboard claim from Task 5). In `a11y.js:19-24` add `li.setAttribute("tabindex", i === 0 ? "0" : "-1")`, and in `a11ySyncNode` set the matched item to `tabindex="0"`, the rest to `-1`, then `.focus()` the matched item. Verify by keyboard in Task 5, not by unit test — the failure is a browser focus behaviour.

- [ ] **Step 8: Run and verify GREEN**, then **commit.**

```bash
git commit -am "feat(web): visible graph selection, state accents, reduced-motion pulse (031)"
```

---

### Task 3: Edges and layout breathing room

**Files:**
- Modify: `graph.js` (`buildStylesheet` edge entries; `render()` layout options at `:121`), `app.css` (edge tokens)
- Test: `graph-stylesheet.test.mjs` (extend)

- [ ] **Step 1: Write the failing tests.** Assert only options cytoscape-dagre actually reads — `marginX`/`marginY` are NOT among them:

```js
test("the layout gives the graph room, and counts labels as part of a node", () => {
  const opts = loadGraph().layoutOptions();
  assert.equal(opts.name, "dagre");
  assert.equal(opts.rankDir, "LR");
  assert.ok(opts.nodeSep >= 40);
  assert.ok(opts.rankSep >= 90);
  assert.ok(opts.edgeSep >= 12);
  assert.ok(opts.padding >= 24);
  // Without this, dagre lays out on the node box alone and the below-shape labels
  // of one rank collide with the next rank (cytoscape-dagre default is false).
  assert.equal(opts.nodeDimensionsIncludeLabels, true);
  assert.equal(opts.marginX, undefined, "dagre has no marginX — do not pin an ignored key");
});
```

- [ ] **Step 2: Run and verify RED**, then extract the layout literal at `graph.js:121` into an exported `layoutOptions()` and give it the values above. Leave `fit` alone — it defaults to `true`, and `padding` is what keeps the graph off the edge; **do not add a manual `cy.fit()`**, it duplicates the layout's own fit.

- [ ] **Step 3: Edge styling.** `curve-style: "bezier"` (already), `width: 1.5` idle / `2.5` on `.active`, `target-arrow-shape: "triangle"`, `arrow-scale: 1.2`, idle stroke `--edge-idle-stroke`, active `--edge-active-stroke`. Tune `control-point-step-size` in the browser only if edges overlap at the fan-in; pin whatever value ships into the test.

- [ ] **Step 4: Run and verify GREEN**, then **commit.**

```bash
git commit -am "feat(web): dagre spacing that accounts for below-shape labels, edge styling (031)"
```

---

### Task 4: Spec updates — implement-vs-change, kept distinct

- [ ] **Step 1: `docs/pipeline-editor.md` §5.3.** Rewrite the stylesheet listing to the shipped one. Preserve, as requirements, everything the table in **Spec vs. code** shows the code is now implementing: `node:selected` (note it is the pseudo-class, not a `.selected` class), the per-type shapes, and `node.caller` with its `pipeline-contract` §9 link. Change two things deliberately and say why in the section text: the label moves outside the shape, and state is an accent rather than a background fill. Update the token names in the listing to the ones `readDesignTokens` actually returns — the current listing names `t.textInverted`, `t.borderDefault`, `t.accentPrimary`, `t.edgeDefault`, `t.edgeActive`, none of which exist in the code.

- [ ] **Step 2: §6.2.** Rewrite the state table: the Token column becomes the accent token per state, `aborted` keeps its 0.5 opacity, the Animation column records "pulse (border-width, JS-driven; still under `prefers-reduced-motion: reduce`)" for running and "none" for failed — **the previous "brief flash" for `failed` is not being implemented; either implement it or remove it, do not leave a spec requirement the code ignores.** Add a sentence naming `window.matchMedia` as the gate, because the CSS media queries cannot reach the canvas.

- [ ] **Step 3: §5.1 / §5.2.** Record the layout options actually passed, including `nodeDimensionsIncludeLabels: true` and why. Note that `marginX`/`marginY` are not dagre options, so no future reader re-adds them.

- [ ] **Step 4: `docs/ui-screens.md`** §4.4 — it currently defers to the pipeline-editor spec entirely; add a graph-visuals line only if that stays true.

- [ ] **Step 5: Run `./scripts/docs-audit.sh`** (exit 0) and **commit** `docs(pipeline-editor): graph stylesheet, state mapping, layout options (031)`.

---

### Task 5: Gate, visual evidence, merge

- [ ] **Step 1: Full gate.**

Run: `./gradlew build ktlintCheck detekt editorJsTest --rerun-tasks`
Expected: BUILD SUCCESSFUL, with `editorJsTest` shown as RUN. Read the log's last line, not the wrapper's exit code.

- [ ] **Step 2: Falsification, one per guard.** In a scratch copy revert each change and confirm its test goes red: the label-below entry, the type/caller classes, the selection entry, the state-accent entries, `nodeDimensionsIncludeLabels`. Record each run.

- [ ] **Step 3: Browser evidence** on the Task 0 fixture (`/pipelines/{id}/editor`), against the BEFORE screenshot taken in Task 0 Step 5:
  - labels render below their shapes, full text, no `...` at 20 characters;
  - the four node types are distinguishable by shape, and the caller node carries its marker;
  - clicking a node shows the ring + halo; clicking another moves it;
  - an execution run shows running (pulsing) → success accents, and an aborted node at reduced opacity;
  - a theme swap re-themes the graph live (`updateTheme`) with no reload;
  - keyboard-only: Tab to the node list, arrow through it, Enter selects, and the canvas selection follows (this depends on Task 2 Step 7 — if that step was dropped, strike this bullet);
  - the same page with the OS set to "reduce motion": the running node is accented but still.

- [ ] **Step 4: Handback** at `datapipelines-orchestration/handbacks/031-graph-design.md` — screenshots, the falsification runs, the per-type decision (shapes vs accent bar) as taken, whether Task 2 Step 7 was included, and the §6.2 `failed`-flash resolution.

- [ ] **Step 5: Merge** from the MAIN checkout after operator review. Check `git symbolic-ref HEAD` — the ref, not the SHA — and `git status` for foreign modified files before committing there. After pushing, verify `git merge-base --is-ancestor <your-sha> origin/main`, then a full build on main.

---

## Explicitly NOT in this plan

- Layout persistence across reloads, a minimap, or zoom-control UI.
- Sub-graph rendering for PIPELINE nodes (the standing decision in `pipeline-editor.md` §7 / composition design).
- Graph EDITING — drag, add/remove nodes. `pipeline-editor.md` §11.1 makes the editor read-only in v1; this plan changes how it looks, never what it can do.
- Replacing dagre or Cytoscape, or bumping either vendored version.
- The details panel, result grid, and SQL display — `2026-08-31-execute-page-redesign.md`.
- Beyond Task 2 Step 7's roving tabindex, any wider a11y rework of the node list.
