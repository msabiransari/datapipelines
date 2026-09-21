(function () {
  "use strict";

  var NODE_STATES = ["idle", "running", "success", "failed", "aborted"];

  /* 151 (#127) — the states an edge can carry, all STATIC facts about its two nodes:
   * `active` the target is running, `satisfied` the source completed, `unmet` the
   * source failed or was aborted. resetAll strips exactly these. */
  var EDGE_STATES = ["active", "satisfied", "unmet"];

  /* Type glyphs (059 §reference, retinted 080 §A): the glyph rides the card's icon
   * TILE — a rounded square washed in the node type's accent pair (--type-* /
   * --type-*-bg, app tokens 080) — not on the bare card. ONE glyph per card (059b):
   * the engine's identity is the source fact line's TEXT, never a second icon.
   * db/table/boxes/workflow are the sprite's 059 LEGACY ids (db = lucide's database);
   * 085 §B gave CALCULATOR its honest glyph — the sprite's `calculator`, added when
   * the icon set was unfenced — retiring the `file` stand-in this comment used to
   * apologise for. */
  var TYPE_ICONS = { DQL: "db", DML: "table", DDL: "boxes", PIPELINE: "workflow", CALCULATOR: "calculator" };

  /* 150 — the execution boundaries (issue #126): view-only Start and End markers,
   * derived from the authored graph, never authored. Roots are nodes with no
   * depends_on, leaves nodes with no dependents; Start connects to every root,
   * every leaf connects to End. They are NOT executable nodes: no NodeType, no
   * authored JSON, no scheduling, no statistics — and no user-facing controls
   * (nothing to select into Details, no template/edit affordances).
   *
   * Identities are collision-safe BY CONSTRUCTION, not by convention: a reserved
   * base name grown with a leading "_" while it collides with an authored id.
   * An authored node literally named "__execution_start__" merely shifts the
   * synthetic's id; the element KIND is always data (`kind: "boundary"`), never
   * guessed from id text. Synthetic ids never enter editor.nodeStates — marker
   * state is internal to the graph (setMarkerState), so the 135 sweep and every
   * nodeStates consumer see authored nodes only. */
  var BOUNDARY_BASE = { start: "__execution_start__", end: "__execution_end__" };

  /* The End marker's word is the AUTHORITATIVE execution outcome; it never moves
   * on a node event (a finished branch is not a finished execution). */
  var MARKER_LABELS = {
    start: { idle: "Start", running: "Running…", success: "Start", failed: "Start", aborted: "Start" },
    end: { idle: "End", running: "End", success: "Finished", failed: "Failed", aborted: "Stopped" },
  };

  var MARKER_ICONS = { start: "play", end: "square" };

  /* 159 addendum (#151): the least breathing room between two node boxes before a card
   * grown mid-run is allowed to move its neighbours (measureCard / cardOverlaps). */
  var CARD_GAP_MIN = 8;

  /* 159/#148: while the execution runs, the Start disc of a viewer who may execute is the
   * CANCEL control — the toolbar's own Cancel (its square glyph, its verb) on the canvas.
   * The word and the name change with it; the plain marker of a viewer who may not
   * execute keeps MARKER_LABELS' Running…. */
  var MARKER_CANCEL = { word: "Cancel", aria: "Cancel execution", icon: "square" };

  /** Deterministic collision-safe synthetic id for a reserved base name. */
  function syntheticId(nodes, base) {
    var taken = {};
    for (var i = 0; i < nodes.length; i++) taken[nodes[i].id] = true;
    var id = base;
    while (taken[id]) id = "_" + id;
    return id;
  }

  /** The synthetic ids for one authored set — pure, so tests and render agree. */
  function boundaryIdsFor(nodes) {
    return {
      start: syntheticId(nodes, BOUNDARY_BASE.start),
      end: syntheticId(nodes, BOUNDARY_BASE.end),
    };
  }

  /** Roots have no dependencies; leaves have no dependents. Authored nodes only. */
  function rootsAndLeaves(nodes) {
    var hasDeps = {};
    var dependedOn = {};
    var i, j;
    for (i = 0; i < nodes.length; i++) {
      var deps = nodes[i].depends_on;
      if (deps && deps.length) {
        hasDeps[nodes[i].id] = true;
        for (j = 0; j < deps.length; j++) dependedOn[deps[j]] = true;
      }
    }
    var roots = [];
    var leaves = [];
    for (i = 0; i < nodes.length; i++) {
      if (!hasDeps[nodes[i].id]) roots.push(nodes[i].id);
      if (!dependedOn[nodes[i].id]) leaves.push(nodes[i].id);
    }
    return { roots: roots, leaves: leaves };
  }

  /* The tile's accent pair per node type, as the CSS custom-property suffixes of the
   * 080 app-token block. The card sets `--type`/`--type-bg` from these and every
   * state/rule below reads the pair back — the card never names a colour itself. */
  var TYPE_TOKEN = { DQL: "dql", DML: "dml", DDL: "ddl", PIPELINE: "pipeline", CALCULATOR: "calc" };

  /* 105 §B — the arrowhead's size. Cytoscape's triangle scales linearly with
   * arrow-scale at 4.35 model px per unit (MEASURED live on this stack's editor,
   * 2026-09-09: renderedBoundingBox deltas of 3.91px at 0.9 and 8.70px at 2.0).
   * The old 0.9 put the head at 3.9px — under the 8px floor and near-invisible
   * at a 0.27 fit zoom, which is the owner's "the line just disappears behind
   * the node without giving any hint". 2.0 clears the floor (8.7px) and keeps
   * the mock's head-to-card proportion (3.7% of the 236px card). Exported so
   * the browser test asserts the same number the canvas paints. */
  var ARROW_SCALE = 2;
  var ARROW_BASE_PX = 4.35;

  /* 151 / #144 — the boundary markers' Cytoscape box width in model px: the 56px
   * shape plus room for the word under it ("Running…" at the card's small size).
   * The stylesheet anchors the boundary connectors at ±BOUNDARY_W/2 and the edge
   * router (applyEdgeCurves) and minimap read the same number through node.width(). */
  var BOUNDARY_W = 72;

  function iconForType(type) {
    return TYPE_ICONS[String(type || "").toUpperCase()] || "db";
  }

  function typeToken(type) {
    return TYPE_TOKEN[String(type || "").toUpperCase()] || "dql";
  }

  /**
   * COLOUR RESOLUTION (082 addendum P1) — the canvas needs a COLOUR, not a token's
   * declaration text.
   *
   * `getComputedStyle(...).getPropertyValue('--edge')` returns a custom property's
   * TOKEN STREAM verbatim, and four of 080's canvas tokens are `color-mix()`
   * expressions bridged off theme tokens (`app.css`: `--brand-soft`, `--border-faint`,
   * `--grid-dot`, `--edge`). Cytoscape parses colours itself and cannot read
   * `color-mix(in srgb, …)`: it logs `The style property `line-color: color-mix(…)` is
   * invalid` and falls back, which is why the dark theme's edges collapsed into thick
   * grey bands the moment `updateTheme()` re-applied the stylesheet.
   *
   * A PROBE resolves them at the source. Assigning `color: var(--edge)` to a real,
   * in-document element makes the browser do the whole computation — `var()`
   * substitution, `color-mix()`, the theme currently in force — and `getComputedStyle`
   * hands back an `rgb(…)`/`rgba(…)` string every colour consumer understands. One
   * FRESH probe per token (093 — see `probeFor`); each is removed before the next read,
   * so nothing outlives the call.
   *
   * The fallback contract is unchanged: a token that is NOT DECLARED at all still
   * yields the mock's light hex, so a stale theme file cannot blank the graph.
   */
  /**
   * `getComputedStyle(...).color` is NOT always `rgb(…)`. Measured on Chrome 148
   * against the live editor (2026-09-07): a `color-mix(in srgb, …)` token computes to
   * **`color(srgb 0.412745 0.436078 0.480392)`** — CSS Color 4's `color()` form, which
   * Cytoscape parses no better than the `color-mix` it came from. So the computed
   * string is normalised to legacy `rgb()` here. Pure, and exported for node --test:
   * this exact conversion is what stands between the canvas and a grey fallback.
   *
   * Returns null when the string is in no form this can convert, which the caller
   * reads as "leave the raw token" rather than "paint something wrong".
   */
  function toLegacyRgb(computed) {
    if (!computed) return null;
    var value = String(computed).trim();
    if (value.indexOf("rgb") === 0 || value.charAt(0) === "#") return value;
    // color(srgb r g b) and color(srgb r g b / a), components in 0..1.
    var srgb = /^color\(\s*srgb\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s*(?:\/\s*([0-9.]+)\s*)?\)$/.exec(value);
    if (!srgb) return null;
    var byte = function (x) { return Math.max(0, Math.min(255, Math.round(parseFloat(x) * 255))); };
    var rgb = byte(srgb[1]) + ", " + byte(srgb[2]) + ", " + byte(srgb[3]);
    var alpha = srgb[4] === undefined ? 1 : parseFloat(srgb[4]);
    return alpha >= 1 ? "rgb(" + rgb + ")" : "rgba(" + rgb + ", " + alpha + ")";
  }

  function colourResolver() {
    if (typeof document === "undefined" || !document.createElement || !document.body) {
      return { resolve: function (_n, raw) { return raw; }, done: function () {} };
    }
    // ONE PROBE PER READ, never a reused one. 093 photographed every card, edge and label
    // in the brand colour: one span, twelve `style.color = 'var(--x)'` writes, and every
    // getComputedStyle after the first answered the FIRST colour — reported as oklab(),
    // which is what an in-flight colour transition serialises to. A fresh, detached-again
    // element has no previous colour to transition from and no cached style to serve, so
    // it can only answer the token it was asked for. `transition: none` belts the braces.
    function probeFor(name) {
      var probe = document.createElement("span");
      probe.setAttribute("aria-hidden", "true");
      probe.style.position = "absolute";
      probe.style.width = "0";
      probe.style.height = "0";
      probe.style.visibility = "hidden";
      probe.style.pointerEvents = "none";
      probe.style.transition = "none";
      probe.style.color = "var(" + name + ")";
      document.body.appendChild(probe);
      return probe;
    }

    // The general fallback for any colour syntax `toLegacyRgb` does not know: paint it
    // on a 1x1 canvas and read the pixel back. Whatever the browser understood, this
    // returns its sRGB bytes. Created lazily — most reads never need it.
    var ctx = null;
    function sample(value) {
      try {
        if (ctx === null) {
          var canvas = document.createElement("canvas");
          canvas.width = 1;
          canvas.height = 1;
          ctx = (canvas.getContext && canvas.getContext("2d")) || false;
        }
        if (!ctx) return null;
        ctx.clearRect(0, 0, 1, 1);
        ctx.fillStyle = "#000000";
        ctx.fillStyle = value;
        ctx.fillRect(0, 0, 1, 1);
        var d = ctx.getImageData(0, 0, 1, 1).data;
        return d[3] === 255
          ? "rgb(" + d[0] + ", " + d[1] + ", " + d[2] + ")"
          : "rgba(" + d[0] + ", " + d[1] + ", " + d[2] + ", " + (d[3] / 255).toFixed(3) + ")";
      } catch (e) {
        return null;
      }
    }

    return {
      resolve: function (name, raw) {
        var probe = probeFor(name);
        var computed = getComputedStyle(probe).color;
        probe.parentNode.removeChild(probe);
        // The raw token is the last resort — exactly the pre-082 behaviour, never a
        // blank canvas.
        return toLegacyRgb(computed) || sample(computed) || raw;
      },
      done: function () {},
    };
  }

  /**
   * `containerId` is the graph's own container. 082 §A put the card's geometry behind
   * a CONTAINER query on the stage (a wide stage gets a bigger card), and a container
   * query's result is only visible on elements INSIDE the container — reading
   * documentElement would have returned the 236px base for ever, so the Cytoscape node
   * box and the HTML overlay would have disagreed on every wide screen. Colours are
   * :root-level and still come from documentElement, resolved through the probe above.
   */
  function readDesignTokens(containerId) {
    var styles = getComputedStyle(document.documentElement);
    var host = (containerId && document.getElementById(containerId)) || document.documentElement;
    var boxStyles = host === document.documentElement ? styles : getComputedStyle(host);
    // The card's geometry rides the same custom-property bridge as the colours: the
    // HTML card (pipeline-editor.css) and the Cytoscape node box MUST agree on a box,
    // and one token read is the only way they do. Numeric fallbacks match the CSS.
    var cardW = parseInt(boxStyles.getPropertyValue("--pe-card-w"), 10) || 236;
    var cardH = parseInt(boxStyles.getPropertyValue("--pe-card-h"), 10) || 148;
    var cardRadius = styles.getPropertyValue("--radius-lg").trim() || "12px";

    // 080 §A: the canvas reads the mock's tokens straight from the 080 app-token
    // block. Hard hexes stay in the fallback slot so a stale theme file cannot
    // blank the graph; the fallbacks are the mock's light values.
    var probe = colourResolver();
    function colour(name, fallback) {
      var raw = styles.getPropertyValue(name).trim();
      if (!raw) return fallback;
      return probe.resolve(name, raw);
    }
    var tokens = {
      brand: colour("--brand", "#2563eb"),
      brandSoft: colour("--brand-soft", "#dbeafe"),
      edgeIdle: colour("--edge", "#94a3b8"),
      edgeActive: colour("--edge-active", "#2563eb"),
      edgeDone: colour("--edge-done", "#15803d"),
      nodeSurface: colour("--surface-raised", "#ffffff"),
      nodeBorder: colour("--border-subtle", "#b2b7bf"),
      nodeSuccess: colour("--accent-success", "#15803d"),
      nodeFailed: colour("--accent-danger", "#dc2626"),
      nodeAborted: colour("--accent-warning", "#a16207"),
      cardW: cardW,
      cardH: cardH,
      cardRadius: cardRadius,
    };
    probe.done();
    return tokens;
  }

  function buildStylesheet(tokens) {
    // Edges plug into the card's EDGE centres, not its centre point (059 §reference,
    // "Ports"): LR layout puts sources left of targets, so +w/2 is the source port on
    // the right edge and -w/2 the target port on the left. The || defaults keep the
    // pure function callable from node --test with a colour-only token fixture.
    var cardW = tokens.cardW || 236;
    var sourcePort = cardW / 2 + "px 0px";
    var targetPort = -cardW / 2 + "px 0px";
    return [
      {
        // Node card (080 §A): a rectangular card whose TEXT is the HTML overlay
        // (cytoscape-node-html-label). The canvas paints only the chrome — surface,
        // subtle border, state accents, selection ring — one theme-swap path
        // (updateTheme); the overlay stays transparent so the chrome shows through.
        selector: "node",
        style: {
          "background-color": tokens.nodeSurface,
          width: cardW,
          // The token is the FLOOR. The real, per-node height is an element style
          // BYPASS written by syncCardHeights (082 addendum P1) — deliberately not a
          // stylesheet function: `cy.style().fromJson(sheet)`, which updateTheme has to
          // use (below), silently DROPS function values, and a height that survives the
          // first paint but not a theme switch is worse than no height at all. Measured
          // on Chrome 148: with a function here, one theme switch collapsed every node
          // to Cytoscape's default 30px box.
          height: tokens.cardH || 148,
          shape: "round-rectangle",
          "border-width": 1,
          "border-color": tokens.nodeBorder,
          "corner-radius": tokens.cardRadius || "12px",
        },
      },
      {
        // Running: the mock's brand border. The pulsing dot and the indeterminate
        // progress line are HTML/CSS on the card (they animate for free, and
        // prefers-reduced-motion stops them there) — the JS border pulse of 059 is
        // retired with them.
        selector: "node.running",
        style: {
          "border-color": tokens.brand,
          "border-width": 2,
        },
      },
      {
        selector: "node.success",
        style: {
          "border-color": tokens.nodeSuccess,
          "border-width": 2,
        },
      },
      {
        selector: "node.failed",
        style: {
          "border-color": tokens.nodeFailed,
          "border-width": 2,
        },
      },
      {
        // §6.2: aborted reads at 0.5 opacity.
        selector: "node.aborted",
        style: {
          "border-color": tokens.nodeAborted,
          "border-width": 2,
          opacity: 0.5,
        },
      },
      {
        // Selection (080 §A): the mock's `0 0 0 3px var(--brand-soft)` ring with the
        // brand border inside it. Cytoscape has no box-shadow, so the ring is the
        // underlay painted BEHIND the node at brand-soft, padded 3px out — the same
        // read at any zoom. Ordered last so selection wins the border while it holds.
        selector: "node:selected",
        style: {
          "border-width": 2,
          "border-color": tokens.brand,
          "underlay-color": tokens.brandSoft,
          "underlay-opacity": 1,
          "underlay-padding": 3,
          "underlay-shape": "round-rectangle",
        },
      },
      {
        // Edges (080 §A): unbundled-bezier whose per-edge control points are computed
        // once after layout (applyEdgeCurves) so the curve LEAVES the source port
        // horizontally and ENTERS the target port horizontally — the mock's `C` with
        // control offset max(60, dx/2). 2px, round caps, small arrowhead.
        selector: "edge",
        style: {
          "line-color": tokens.edgeIdle,
          "target-arrow-color": tokens.edgeIdle,
          "target-arrow-shape": "triangle",
          "arrow-scale": ARROW_SCALE,
          "curve-style": "unbundled-bezier",
          // 105: WITHOUT this, cytoscape lerps every (weight, distance) control
          // pair against the source→target line's SHAPE-INTERSECTION points (the
          // default, "intersection") — not the ports the pairs are computed from.
          // The pairs applyEdgeCurves writes are port-relative (edgeRouteFor),
          // and with a dragged-back target the intersection baseline diverges so
          // far from the ports that the resolved control points land nowhere near
          // the requested detour (measured live: requested P2 (768,1663), resolved
          // (711,1406)). "endpoints" makes the manual source-endpoint/target-endpoint
          // pair the baseline, so the rendered curve IS the computed one.
          "edge-distances": "endpoints",
          "source-endpoint": sourcePort,
          "target-endpoint": targetPort,
          width: 2,
          "line-cap": "round",
        },
      },
      {
        // 151 (#127): `active` — the TARGET is running. The mock's --edge-active dashed
        // stroke, and NOTHING moves: the dash offset animation is retired, because
        // motion along a dependency read as rows travelling along it. The only moving
        // indicator on the canvas is the producer's output port while a write is
        // MEASURED (buildCardHtml / pipeline-editor.css `.pe-port-writing`).
        selector: "edge.active",
        style: {
          "line-color": tokens.edgeActive,
          "target-arrow-color": tokens.edgeActive,
          "line-style": "dashed",
          "line-dash-pattern": [6, 8],
          width: 2.5,
        },
      },
      {
        // `satisfied` — the SOURCE completed, so this ordering is met and the dependent
        // may start. A fact about the source, in --edge-done, arrow included.
        selector: "edge.satisfied",
        style: {
          "line-color": tokens.edgeDone,
          "target-arrow-color": tokens.edgeDone,
        },
      },
      {
        // `unmet` — the SOURCE failed or was aborted: this ordering cannot be met in
        // this run. Short dashes in the failed accent, dimmed — legible without colour
        // by its pattern (active is [6 8], unmet is [2 6]).
        selector: "edge.unmet",
        style: {
          "line-color": tokens.nodeFailed,
          "target-arrow-color": tokens.nodeFailed,
          "line-style": "dashed",
          "line-dash-pattern": [2, 6],
          opacity: 0.6,
        },
      },
      {
        // 151 / #144: the boundary markers are compact SHAPES (a 56px disc and a 56px
        // square, built in HTML by buildMarkerHtml), so their Cytoscape box is narrow
        // and its chrome transparent — the card-width rectangle 150 painted under the
        // pill is what made a marker read as a node. Ordered after the state rules so
        // a running/success/failed marker never gets a card border.
        selector: "node.boundary",
        style: {
          width: BOUNDARY_W,
          "background-opacity": 0,
          "border-width": 0,
          "underlay-opacity": 0,
        },
      },
      {
        // The boundary connectors leave the Start disc's right edge and enter the End
        // shape's left edge — the marker's own half width, not the card's.
        selector: "edge.boundary-start",
        style: {
          "source-endpoint": BOUNDARY_W / 2 + "px 0px",
        },
      },
      {
        selector: "edge.boundary-end",
        style: {
          "target-endpoint": -BOUNDARY_W / 2 + "px 0px",
        },
      },
      {
        // Reserved for FUTURE secondary relationships (template imports) — defined,
        // deliberately unused this round. Declaring it here means the day it lights up
        // it is a class toggle, not a styling decision.
        selector: "edge.secondary",
        style: {
          "line-style": "dashed",
        },
      },
    ];
  }

  /* ------------------------------------------------------------------ the card */

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function iconSvg(id, cls) {
    return (
      '<svg class="ds-icon ' + (cls || "ds-icon-sm") + '" aria-hidden="true" focusable="false">' +
      '<use href="/vendor/icons/lucide-sprite.svg#' + id + '"></use></svg>'
    );
  }

  /**
   * Left-truncation for hierarchical template names (059 §A line 4): the LEAF stays
   * visible, the ancestry collapses into one leading ellipsis. 043 made names paths;
   * right-ellipsis would hide exactly the part that identifies the template.
   */
  function truncateLeft(name, max) {
    var limit = max || 34;
    var s = String(name || "");
    if (s.length <= limit) return s;
    var leaf = s.slice(s.lastIndexOf("/") + 1);
    if (leaf.length + 1 <= limit) return "…/" + leaf;
    return "…" + leaf.slice(-(limit - 1));
  }

  /** The template fact: `nyc/mobility/trips_by_borough @ v2`, path truncated LEFT. */
  function templateLine(t) {
    if (!t || !t.id) return "";
    return t.version ? truncateLeft(t.id) + " @ v" + t.version : truncateLeft(t.id);
  }

  /**
   * The footer's run numbers (080 §A: `rows · ms`, tabular). The wire carries FLAT
   * `duration_ms` / `rows_out` (SseEventProjection; NOT_MEASURED is -1) — there is no
   * rows_in on the wire, so the mock's `in → out` collapses to the out count; the
   * edge label picks the same number up as the count flowing OUT of the source.
   * A CALCULATOR completion carries context_value instead of rows — or, on a
   * multi-output kind (121), the whole `context_values` set it wrote. The footer
   * COUNTS what flowed — `2 keys`, like `523 rows` — and never inlines the values:
   * T251 (owner screenshot, 2026-09-13) was `= last_quarter_end="2024-12-31",
   * last_quarter_start="2024-10-01" · 2 ms` wrapping to three lines inside a
   * footer laid out for one, the Done dot floating mid-card. The values are the
   * Details pane's and the Events tab's, where they have room.
   */
  function keysWritten(stats) {
    if (!stats) return null;
    if (stats.context_values !== undefined && stats.context_values !== null) {
      return Object.keys(stats.context_values).length;
    }
    if (stats.context_value !== undefined && stats.context_value !== null) return 1;
    return null;
  }

  function countLabel(n, noun) {
    return n.toLocaleString("en-US") + " " + noun + (n === 1 ? "" : "s");
  }

  function formatRunLine(stats) {
    if (!stats) return null;
    var out = "";
    var keys = keysWritten(stats);
    if (keys !== null) {
      out = countLabel(keys, "key");
    } else {
      var r = Number(stats.rows_out);
      if (isFinite(r) && r >= 0) out = countLabel(r, "row");
    }
    var ms = durationText(stats.duration_ms);
    if (ms !== null) out = out ? out + " · " + ms : ms;
    return out || null;
  }

  /** `842 ms` / `5.3 s` / `2m 14s` — the footer's vocabulary, shared with the End marker (151). */
  function durationText(value) {
    var d = Number(value);
    if (!isFinite(d) || d < 0) return null;
    if (d < 1000) return Math.round(d) + " ms";
    if (d < 60000) return (d / 1000).toFixed(1) + " s";
    return Math.floor(d / 60000) + "m " + Math.round((d % 60000) / 1000) + "s";
  }

  /* The footer state label (080 §A): Pending / Running… / Done / Failed / Aborted. */
  var STATE_LABELS = { idle: "Pending", running: "Running…", success: "Done", failed: "Failed", aborted: "Aborted" };

  /**
   * The HTML card (080 §A — the mock's "node card"), top to bottom: the head (icon
   * TILE washed in the type accent, the id, the type eyebrow in the type colour),
   * up to three fact lines in mono (source · dialect / template@v / output, or the
   * calculator / child-pipeline equivalents), a footer with the state dot + label on
   * the left and the last run's numbers on the right. Ports sit on the card edges;
   * the expand affordance appears on hover (JS-toggled class — the label container
   * is pointer-events:none, so CSS :hover never fires) and is the route into the
   * dock's Details tab; the indeterminate progress line shows while running. Every
   * colour is the card's `--type`/`--type-bg` pair or an app token.
   *
   * Pure: node --test drives it with a data snapshot — everything dynamic arrives as
   * `data`, written by buildElements/setNodeState/setNodeStats/applyDialects.
   */
  function buildCardHtml(data) {
    if (!data) return "";
    // 150: the boundaries render their own pill, never a node card.
    if (data.kind === "boundary") return buildMarkerHtml(data);
    var esc = escapeHtml;
    var state = data.state || "idle";
    // 151: `stale` — the stream was lost mid-run (sse.js handleConnectionLoss →
    // markStreamLost). The card keeps the last words it knew and stops every motion:
    // the state is not rewritten, because nothing about the node was observed since.
    var stale = data.stale ? " pe-card-stale" : "";
    // 188: the card names its TYPE (`data-type`); app.css maps it to --type/--type-bg.
    // A `style=` attribute in innerHTML markup is an inline style the CSP refuses.
    var h =
      '<div class="pe-card pe-card-' + esc(state) + stale + '" data-node-id="' + esc(data.id) +
      '" data-type="' + esc(typeToken(data.type)) + '">';

    h += '<span class="pe-card-port pe-card-port-in" aria-hidden="true"></span>';
    h += '<span class="pe-card-port pe-card-port-out" aria-hidden="true"></span>';

    // The card's OWN affordance for the dock's Details tab (080 §B — what the 065
    // inspector button became). pointer-events:auto on itself alone, exactly as 065
    // had it: the label container is pointer-events:none so the canvas keeps pan,
    // zoom, drag and tap.
    h +=
      '<button type="button" class="pe-card-open" data-node-open="' + esc(data.id) +
      '" aria-label="Open details for ' + esc(data.id) + '">' +
      iconSvg("maximize", "ds-icon-xs") + "</button>";

    h +=
      '<div class="pe-card-head"><span class="pe-card-tile">' +
      iconSvg(data.typeIcon || iconForType(data.type), "ds-icon-sm") +
      '</span><div class="pe-card-title"><div class="pe-card-id" title="' + esc(data.id) + '">' +
      esc(data.id) + '</div><div class="pe-card-kind">' + esc(String(data.type || "node").toLowerCase()) +
      "</div></div></div>";

    if (data.facts && data.facts.length) {
      h += '<div class="pe-card-facts">';
      data.facts.slice(0, 3).forEach(function (f) {
        h +=
          '<div class="pe-card-fact" title="' + esc(f.title || f.text) + '">' +
          '<span class="pe-card-mono">' + esc(f.text) + "</span></div>";
      });
      h += "</div>";
    }

    if (data.output) h += buildPortHtml(data);

    h += '<div class="pe-card-progress" aria-hidden="true"><i></i></div>';

    // 149: while the node RUNS, the footer's state word is the measured operation line
    // (node-ops.js `describe().cardLine` — "Writing → tempdb.trips · 11,000 written",
    // "Waiting for tempdb connection", "Querying"), tagged with the operation state so
    // the stylesheet can colour waiting/writing distinctly. A terminal state keeps the
    // plain word and the run numbers: a live line on a finished card would be stale.
    var live = state === "running" && data.op ? data.op : null;
    var opClass = live && data.opState ? " pe-card-op-" + esc(String(data.opState)) : "";
    // The right slot: the last run's numbers once the node is done, the live cumulative count
    // while it runs (149) — one line either way, never both.
    var right = state === "running" ? data.opCounts || "" : data.run || "";
    h +=
      '<div class="pe-card-foot"><span class="pe-card-state' + opClass + '"><i></i><span class="pe-card-st">' +
      esc(live || STATE_LABELS[state] || state) + '</span></span><span class="pe-card-rt">' +
      esc(right) + "</span></div>";

    h += "</div>";
    return h;
  }

  /**
   * 151 (#127) — the OUTPUT PORT row: a short connector stub leading into the
   * destination, the operation's kind word once measured, and the state line on the
   * right. One per node with a configured output; the ONLY thing on the canvas that
   * moves for a write, and only while a `writing` sample is the latest word
   * (`.pe-port-flow`, CSS-animated, stopped by prefers-reduced-motion and by `stale`).
   * The states are 149's reducer through `describe().port` (node-ops.js) — idle before
   * any sample, then pending / combined / waiting / writing / finalizing / done /
   * failed / aborted; a lost stream appends "— stream lost" and freezes the row.
   * `aria-label` is the port's whole meaning in words, usable without colour or motion.
   */
  function buildPortHtml(data) {
    var esc = escapeHtml;
    var out = data.output;
    var port = data.port || null;
    var pstate = port ? port.state : "idle";
    var text = port ? port.text : "";
    if (data.stale) text = (text ? text + " " : "") + "— stream lost";
    var aria = port ? port.a11y : "Output to " + out.text;
    if (data.stale) aria += " — stream lost";
    var flow = pstate === "writing" && !data.stale ? '<i class="pe-port-flow" aria-hidden="true"></i>' : "";
    var h =
      '<div class="pe-port pe-port-' + esc(pstate) + (data.stale ? " pe-port-stale" : "") +
      '" role="img" aria-label="' + esc(aria) + '" title="' + esc(aria) + '">' +
      '<span class="pe-port-stub" aria-hidden="true">' + flow + "</span>" +
      '<span class="pe-port-dest">' + esc(out.text) + "</span>";
    if (port && port.kindLabel) h += '<span class="pe-port-kind">' + esc(port.kindLabel) + "</span>";
    if (text) h += '<span class="pe-port-line">' + esc(text) + "</span>";
    h += "</div>";
    return h;
  }

  /**
   * 150/151 (#126, #144) — the boundary markers: SHAPES, not cards. Start is a compact
   * disc, End a rounded square — two silhouettes a peripheral glance tells apart from
   * each other and from the rectangular cards (the 150 pill shared the cards' box and
   * row grammar, which is what the owner rejected). The word sits BELOW the shape in
   * the card's small type; End adds the elapsed time when the run clock knows it.
   *
   * Start is the run trigger when the viewer MAY execute (`canExecute`, the same
   * server-rendered flag that renders the toolbar's Execute — read off `.pe-root`'s
   * `data-can-execute`, never re-derived in JS): `role="button"`, focusable, Enter/Space
   * (wireMarkerActivation), the disc filled in the SAME accent as `.pe-run` so the two
   * triggers read as one action. While the run is active that same button is the CANCEL
   * control (159/#148, `.pe-marker-cancel`): the toolbar's square glyph, the word Cancel,
   * the name "Cancel execution", never `aria-disabled` — one button, two verbs, exactly
   * as the toolbar's Execute turns into Running… + Cancel. When the run ends (any
   * terminal state) it is Start again. Without the right it is a plain marker
   * (`role="img"`) in every state: an outlined disc, no fill, no affordance, Running…
   * while the execution runs. End is never a trigger — its fill is the authoritative
   * outcome (success/danger/warning).
   *
   * The `.pe-card` root class stays deliberate: syncCardHeights measures `.pe-card`s to
   * size the Cytoscape box; the marker box is BOUNDARY_W wide (stylesheet) and as tall
   * as shape + word. No ports, no open button, no facts, no progress line, no port row.
   * Pure: driven from the element data like the card.
   */
  function buildMarkerHtml(data) {
    var esc = escapeHtml;
    var state = data.state || "idle";
    var side = data.boundary === "end" ? "end" : "start";
    var word = (MARKER_LABELS[side] && MARKER_LABELS[side][state]) || state;
    var running = state === "running";
    var trigger = side === "start" && data.canExecute === true;
    var cancel = trigger && running;
    if (cancel) word = MARKER_CANCEL.word;
    var elapsed = side === "end" && state !== "idle" && state !== "running" && data.elapsed ? String(data.elapsed) : null;
    var aria;
    if (cancel) {
      aria = MARKER_CANCEL.aria;
    } else if (trigger) {
      aria = "Start execution";
    } else if (side === "start") {
      aria = "Execution start" + (running ? " — running" : "");
    } else {
      aria = "Execution end" + (state === "idle" ? "" : " — " + (running ? "running" : word.toLowerCase())) + (elapsed ? " in " + elapsed : "");
    }
    var shape =
      '<div class="pe-marker pe-marker-' + side + (trigger ? " pe-marker-run" : "") + (cancel ? " pe-marker-cancel" : "") +
      '" role="' + (trigger ? "button" : "img") + '"' +
      (trigger ? ' tabindex="0" data-marker-run="' + esc(data.id) + '"' : "") +
      ' aria-label="' + esc(aria) + '">' +
      iconSvg(cancel ? MARKER_CANCEL.icon : MARKER_ICONS[side], "ds-icon-md") +
      "</div>";
    return (
      '<div class="pe-card pe-card-boundary pe-card-boundary-' + side + " pe-card-" + esc(state) +
      (cancel ? " pe-card-cancel" : "") + (data.stale ? " pe-card-stale" : "") +
      '" data-node-id="' + esc(data.id) + '" data-boundary="' + side + '">' +
      shape +
      '<span class="pe-marker-word">' + esc(word) + "</span>" +
      (elapsed ? '<span class="pe-marker-elapsed">' + esc(elapsed) + "</span>" : "") +
      "</div>"
    );
  }

  function PipelineGraph(containerId, nodes, editor) {
    this.containerId = containerId;
    this.nodes = nodes;
    this.editor = editor;
    this.cy = null;
    this.tokens = readDesignTokens(containerId);
    this._mm = null;
  }

  // dagre layout options (pipeline-editor.md §5.1/§5.2), tuned for the 236px card
  // (080 §A): wide ranks and generous separation are the mock's "breathing room".
  // padding is the edge clearance; fit runs once in fitToView() so the floor AND the
  // ceiling clamp can apply after it.
  function layoutOptions() {
    return {
      name: "dagre",
      rankDir: "LR",
      nodeSep: 64,
      rankSep: 160,
      edgeSep: 12,
      padding: 40,
      fit: false,
      nodeDimensionsIncludeLabels: true,
    };
  }

  PipelineGraph.prototype.render = function () {
    var self = this;
    var elements = this.buildElements();
    // 150: the synthetic ids this render derives — setMarkerState resolves the
    // markers through them. Recomputed per render; the authored set is fixed by
    // the time a run streams.
    this._boundaryIds = boundaryIdsFor(this.nodes);

    this.cy = cytoscape({
      container: document.getElementById(this.containerId),
      elements: elements,
      style: buildStylesheet(this.tokens),
      // 082 addendum: 080's `wheelSensitivity: 0.3` is gone. Cytoscape warns on EVERY
      // init that a non-default sensitivity is unsupported ("You have set a custom
      // wheel sensitivity… disable this warning"), the owner sees it in the console on
      // every editor open, and the ± controls are the honest place to adjust the feel.
      minZoom: 0.2,
      maxZoom: 3,
    });

    // The HTML card overlay (§reference route 1): the extension's label container is
    // pointer-events:none by default (verified against its 1.2.2 source), and its
    // whole container carries the pan/zoom transform, so cards scale WITH the canvas
    // rather than fighting it. It re-runs the template on `data`/`style` events —
    // which is exactly how state dots and run lines arrive.
    if (this.cy.nodeHtmlLabel) {
      this.cy.nodeHtmlLabel([
        {
          query: "node",
          halign: "center",
          valign: "center",
          halignBox: "center",
          valignBox: "center",
          tpl: function (data) {
            return buildCardHtml(data);
          },
        },
      ]);
    }

    // ONE delegated listener on the graph container serves every card's open button
    // — the html label re-renders its template on each data/style event, so
    // per-button listeners would be re-attached (and leaked) on every state change.
    // stopPropagation keeps the canvas's tap-to-select from also firing (065 §C).
    // The flag guards the history-restore re-render (080 §B): a restored container
    // already carries the listener from its first render, and a second one would
    // open Details twice per click.
    var container = document.getElementById(this.containerId);
    if (container && container.addEventListener && !container.__peCardClickWired) {
      container.__peCardClickWired = true;
      container.addEventListener("click", function (evt) {
        var t = evt.target;
        var btn = t && t.closest ? t.closest(".pe-card-open") : null;
        if (!btn) return;
        evt.preventDefault();
        evt.stopPropagation();
        var id = btn.getAttribute("data-node-open");
        // Resolve the LIVE component, not the graph instance that wired this: a
        // history-restored container keeps this listener (the flag above) while
        // the component it closed over was destroyed and re-bound (080 §B).
        var ed = (typeof window !== "undefined" && window.__peInstance) || self.editor;
        if (ed && ed.openNodeDetails) ed.openNodeDetails(id, btn);
      });
    }

    // 151/#144: the Start disc's activation — same delegation, same live-component rule.
    this.wireMarkerActivation(container);

    // The mock's hover lift (translateY(-2px) + --shadow-lg) and its hover-only
    // expand icon are CSS — but the label container is pointer-events:none, so CSS
    // :hover never fires on the card. Cytoscape's own pointer events stand in: a
    // class toggled on the card element carries both effects, no re-render.
    this.cy.on("mouseover", "node", function (evt) {
      self.cardElement(evt.target.id(), function (el) { el.classList.add("pe-card-hover"); });
    });
    this.cy.on("mouseout", "node", function (evt) {
      self.cardElement(evt.target.id(), function (el) { el.classList.remove("pe-card-hover"); });
    });

    // Layout runs EXPLICITLY (not via the cytoscape config) so the layoutstop binding
    // is guaranteed to precede it — cy.ready can fire before dagre has applied
    // positions. One pass after layout: the edge curve (horizontal leave/enter), the
    // fit with floor AND ceiling, and the minimap's first paint — then the card
    // measurement (082 addendum P1), which may ask for one more pass.
    this._heightPasses = 0;
    this.runLayout(function () {
      afterPaint(function () { self.syncCardHeights(); });
    });

    // 105 §B: a dragged node must re-take its curves. The (weight, distance)
    // control points are relative to the source→target VECTOR, so without this
    // a drag keeps the old curve re-projected onto the new vector — the
    // arc-over-the-card in the owner's screenshot. dragfree fires once, when the
    // user lets go; layoutstop covers the layout-time recompute.
    this.cy.on("dragfree", "node", function () {
      self.applyEdgeCurves();
    });

    // Live re-theme (§5.3): no page element calls updateTheme() — a theme swap reaches
    // the page as an htmx OOB replacement of #theme-link (partials/theme-swap.html).
    // Watch head for the link being swapped in, and re-read tokens on the NEW sheet's
    // load event: reading earlier races the stylesheet fetch and paints stale values.
    if (typeof MutationObserver !== "undefined" && typeof document !== "undefined") {
      self._themeLink = document.getElementById("theme-link");
      var head = document.head || document.getElementsByTagName("head")[0];
      if (head) {
        new MutationObserver(function () {
          var link = document.getElementById("theme-link");
          if (!link || link === self._themeLink) return;
          self._themeLink = link;
          link.addEventListener("load", function () { self.updateTheme(); }, { once: true });
        }).observe(head, { childList: true, subtree: true });
      }
    }

    // 104 §B: from here on, every pan/zoom that is not one of this class's own view calls
    // means the user moved the view — and a stage resize must then leave it where they put it.
    self.watchViewGestures();

    // 082 §A: the stage's width decides the card box (the container query), so a
    // resize that crosses its threshold has to reach the Cytoscape side too.
    // ResizeObserver watches the STAGE, which is the box the query measures — a
    // window resize listener would miss the rail collapsing, which changes the stage
    // and not the window. refreshCardMetrics is a no-op unless the box really moved.
    if (typeof ResizeObserver !== "undefined" && typeof document !== "undefined") {
      var stage = container && container.closest ? container.closest(".pe-stage") : null;
      if (stage) {
        self._stageWatch = new ResizeObserver(function () {
          /* 104 §B: the card box is only half of it — Cytoscape's own viewport moved too,
             and a dock drag never fires a window resize to tell it. */
          self.handleStageResize();
        });
        self._stageWatch.observe(stage);
      }
    }

    this.loadDialects();
  };

  /** Teardown for the stage watcher (init.js teardown, before cy.destroy()). */
  PipelineGraph.prototype.stopStageWatch = function () {
    if (this._stageWatch) {
      this._stageWatch.disconnect();
      this._stageWatch = null;
    }
  };

  /**
   * One dagre pass and the three things that must follow it, in order. Factored out of
   * render() by 082 addendum P1 because the card measurement can need a SECOND pass:
   * dagre's rank separation is tuned to the card box, so a node that turns out taller
   * than the floor has to be re-laid-out, not merely re-painted.
   */
  /**
   * Run `fn` once the browser has laid the frame out. `layoutstop` fires while the
   * html-label overlay may still be mid-write, and a card measured then can report 0.
   * Under node --test there is no rAF and the call is synchronous, which is what the
   * pure tests want.
   */
  function afterPaint(fn) {
    if (typeof requestAnimationFrame === "function") requestAnimationFrame(fn);
    else fn();
  }

  /**
   * A graph whose Cytoscape instance is gone, or destroyed and not yet dropped.
   *
   * The deferred measurement above runs a FRAME later, and a boosted navigation can
   * destroy the instance in between: init.js's teardown nulls the component's `cy` but
   * this object still holds the destroyed one, and touching it throws
   * "Cannot read properties of null (reading 'isHeadless')" out of Cytoscape's own
   * `headless()`. Seen once in the 082 walk, as a console error on a clean run.
   */
  function isGone(graph) {
    if (!graph.cy) return true;
    return typeof graph.cy.destroyed === "function" && graph.cy.destroyed();
  }

  PipelineGraph.prototype.runLayout = function (onStop) {
    var self = this;
    if (isGone(self)) return;
    self._layoutStale = false;
    var layout = self.cy.elements().layout(layoutOptions());
    layout.one("layoutstop", function () {
      self.applyEdgeCurves();
      self.fitToView();
      self.renderMinimap();
      if (onStop) onStop();
    });
    layout.run();
  };

  /**
   * 082 addendum P1 — the painted box is the CARD, not an assumption about it.
   *
   * `--pe-card-h` was a fixed height, and a card with three fact lines overflowed it:
   * the footer painted below the node's own border. The card is `min-height` now, so
   * its rendered height is whatever its content needs — and Cytoscape has to be told,
   * or the chrome it paints is the wrong size and the ports (which sit at the node
   * box's vertical centre) stop meeting the card's own port dots.
   *
   * `offsetHeight` is deliberate: the html-label container carries the canvas's
   * pan/zoom TRANSFORM, so `getBoundingClientRect()` returns a scaled number and would
   * feed the zoom back into the model. `offsetHeight` is layout, before transforms.
   *
   * Returns whether a re-layout was started. Bounded at two passes: the second one
   * re-measures the same content and finds nothing to change, and a pathological
   * oscillation stops rather than spinning.
   */
  PipelineGraph.prototype.syncCardHeights = function () {
    var self = this;
    if (isGone(self) || typeof document === "undefined") return false;
    var changed = 0;
    self.cy.nodes().forEach(function (n) {
      self.cardElement(n.id(), function (el) {
        var h = Math.ceil(el.offsetHeight);
        if (h > 0 && h !== n.data("cardH")) {
          // `data` for the minimap and for anything reading the model; the STYLE
          // bypass is what Cytoscape paints, and it survives a `fromJson().update()`
          // where a stylesheet function would not.
          n.data("cardH", h);
          n.style("height", h);
          changed++;
        }
      });
    });
    if (!changed) return false;
    self._heightPasses = (self._heightPasses || 0) + 1;
    if (self._heightPasses > 2) return false;
    self.runLayout(function () {
      afterPaint(function () { self.syncCardHeights(); });
    });
    return true;
  };

  /**
   * 159 addendum (#151) — a card that GROWS after render is measured again, per node.
   *
   * syncCardHeights above runs after the initial render and after a theme change; 151's
   * output-port block then added lines to cards DURING a run (`one statement`,
   * `committed · N rows`, the run line) through data writes the html-label re-renders on
   * (a `setTimeout(0)` of its own) — nothing re-measured, the node box kept its pre-run
   * height, and the label, centred on the box, spilled past both edges (the owner's
   * screenshot). Every height-changing write now queues ONE deferred measure for that
   * node: re-armed on each write so it sits behind every re-render the writes queued and
   * measures the final render, never a stale intermediate. The measure is the 082 rule —
   * `offsetHeight`, the `cardH` data and the style BYPASS — and nothing else: no full
   * relayout while the run is in flight (a mid-run relayout moves cards under the reader).
   * The two exceptions are the ones worth moving cards for: a grown card whose box would
   * overlap a neighbour re-lays out at once, and a layout left stale by mid-run growth is
   * re-run once when the run completes (settleCardHeights, from End's terminal state).
   * `_heightPasses` bounds only syncCardHeights' own recursion; this path is never
   * swallowed by it, and the completion pass resets it.
   */
  PipelineGraph.prototype.queueCardMeasure = function (nodeId) {
    var self = this;
    if (typeof document === "undefined") return;
    self._measureTimers = self._measureTimers || {};
    if (self._measureTimers[nodeId]) clearTimeout(self._measureTimers[nodeId]);
    self._measureTimers[nodeId] = setTimeout(function () {
      delete self._measureTimers[nodeId];
      self.measureCard(nodeId);
    }, 0);
  };

  /** Measure ONE card and tell Cytoscape; returns whether the node's box changed. */
  PipelineGraph.prototype.measureCard = function (nodeId) {
    var self = this;
    if (isGone(self) || typeof document === "undefined") return false;
    var node = self.findNode(nodeId);
    if (!node) return false;
    var changed = false;
    self.cardElement(nodeId, function (el) {
      var h = Math.ceil(el.offsetHeight);
      if (h > 0 && h !== node.data("cardH")) {
        node.data("cardH", h);
        node.style("height", h);
        changed = true;
      }
    });
    if (!changed) return false;
    self.renderMinimap();
    if (self.cardOverlaps(node)) {
      self._heightPasses = 0;
      self.runLayout();
    } else {
      self._layoutStale = true;
    }
    return true;
  };

  /**
   * Whether a node's box (its measured height, its own width) intersects any other node's,
   * leaving less than CARD_GAP_MIN between them — the mid-run reason to re-lay out.
   */
  PipelineGraph.prototype.cardOverlaps = function (node) {
    var self = this;
    var p = node.position();
    var w = self.nodeWidth(node);
    var h = node.data("cardH") || self.tokens.cardH;
    var hit = false;
    self.cy.nodes().forEach(function (m) {
      if (hit || m.id() === node.id()) return;
      var q = m.position();
      var mw = self.nodeWidth(m);
      var mh = m.data("cardH") || self.tokens.cardH;
      if (Math.abs(p.x - q.x) < (w + mw) / 2 + CARD_GAP_MIN && Math.abs(p.y - q.y) < (h + mh) / 2 + CARD_GAP_MIN) hit = true;
    });
    return hit;
  };

  /**
   * The run is over: cards grown mid-run kept their positions (measureCard); if any did,
   * the layout is re-run once for the new geometry, then measured as after any layout.
   */
  PipelineGraph.prototype.settleCardHeights = function () {
    var self = this;
    if (typeof document === "undefined") return;
    setTimeout(function () {
      if (isGone(self) || !self._layoutStale) return;
      self._heightPasses = 0;
      self.runLayout(function () {
        afterPaint(function () { self.syncCardHeights(); });
      });
    }, 0);
  };

  /** The live HTML card element for a node id (the html-label overlay's output). */
  PipelineGraph.prototype.cardElement = function (nodeId, fn) {
    if (typeof document === "undefined" || !document.querySelectorAll) return;
    var cards = document.querySelectorAll(".pe-card");
    for (var i = 0; i < cards.length; i++) {
      if (cards[i].getAttribute("data-node-id") === nodeId) {
        fn(cards[i]);
        return;
      }
    }
  };

  /**
   * Datasource dialects (059 §A line 3) live in the registry, not the pipeline body —
   * the body is portable across environments (contract §11.1), so the card resolves
   * `sample-trips · POSTGRES` client-side from the workspace's datasource listing and
   * upgrades the fact when it lands. A failure degrades to the bare source name: the
   * card stays legal, it just says less.
   */
  PipelineGraph.prototype.loadDialects = function () {
    var self = this;
    if (typeof fetch !== "function" || !this.cy) return;
    fetch("/api/v1/datasources?limit=200", { credentials: "same-origin" })
      .then(function (res) {
        return res.ok ? res.json() : null;
      })
      .then(function (json) {
        var items = json && json.data && json.data.items;
        if (!items || !items.length) return;
        var byName = {};
        items.forEach(function (d) {
          if (d && d.name) byName[d.name] = d.dialect;
        });
        self.applyDialects(byName);
      })
      .catch(function () {
        /* degrade: the source name alone */
      });
  };

  PipelineGraph.prototype.applyDialects = function (dialectsByname) {
    if (!this.cy) return;
    this.cy.nodes().forEach(function (n) {
      var name = n.data("sourceName");
      if (!name) return;
      var dialect = dialectsByname[name];
      if (dialect) {
        var facts = (n.data("facts") || []).slice();
        for (var i = 0; i < facts.length; i++) {
          if (facts[i].kind === "source") {
            facts[i] = {
              kind: "source",
              icon: facts[i].icon,
              text: name + " · " + String(dialect).toUpperCase(),
            };
            break;
          }
        }
        n.data("facts", facts);
      }
    });
  };

  /**
   * The mock's bezier (080 §A): two unbundled-bezier control points, one extending
   * horizontally from the source port, one back from the target port, offset
   * max(60, dx/2) — the mock's `C x1+dx y1, x2-dx y2, x2 y2`. Cytoscape 3.34 has no
   * `control-point-positions` (verified against the vendored source), so the
   * (weight, distance) form is computed per edge from post-layout positions.
   *
   * 105 §B: this formula is now the FORWARD regime only (dx ≥ EDGE_FORWARD_MIN_DX).
   * With a NEGATIVE dx it clamps k to 60 and the curve loops over the cards — the
   * owner's photograph (handbacks/105): the arrowhead slid behind the target card
   * and the "731 rows" label floated mid-arc. See edgeRouteFor for the regime split.
   */
  function edgeControlPoints(sx, sy, tx, ty) {
    var k = Math.max(60, (tx - sx) / 2);
    return projectControlPoints(
      { x: sx, y: sy },
      { x: tx, y: ty },
      [
        { x: sx + k, y: sy },
        { x: tx - k, y: ty },
      ],
    );
  }

  /**
   * N control points → the (weights, distances) pair Cytoscape's unbundled-bezier
   * reads: weight = the point's projection on the source→target vector as a
   * fraction of its length, distance = the signed perpendicular offset along
   * CYTOSCAPE's perpendicular — (−Δy, Δx)/|Δ|, per the vendored renderer's
   * `findMidptPtsEtc`/`findBezierPoints` (verified 105: with the opposite sign the
   * resolved control points land mirrored, which the symmetric two-point S-curve
   * hid for four rounds and the asymmetric detour exposed). The inverse, as the
   * node tests pin it: p = S + w·Δ + d·(−Δy, Δx)/|Δ|. Coincident ports (a
   * zero-length vector) have no curve.
   */
  function projectControlPoints(sp, tp, points) {
    var dx = tp.x - sp.x;
    var dy = tp.y - sp.y;
    var len2 = dx * dx + dy * dy;
    if (len2 <= 0) return null;
    var len = Math.sqrt(len2);
    var weights = [];
    var distances = [];
    for (var i = 0; i < points.length; i++) {
      var px = points[i].x - sp.x;
      var py = points[i].y - sp.y;
      weights.push((px * dx + py * dy) / len2);
      distances.push((py * dx - px * dy) / len);
    }
    return { weights: weights, distances: distances };
  }

  /*
   * 105 §B — WHICH CURVE AN EDGE GETS, as a pure function of the two card boxes.
   *
   * Ports are the card's EDGE centres (the stylesheet's source-endpoint /
   * target-endpoint): right edge for the source, left edge for the target. The
   * port-to-port dx decides:
   *
   *   dx ≥ EDGE_FORWARD_MIN_DX   the mock's bezier (edgeControlPoints): the curve
   *                              leaves and enters horizontally, k = max(60, dx/2).
   *   dx < EDGE_FORWARD_MIN_DX   INCLUDING EVERY NEGATIVE dx: an orthogonal detour
   *                              BELOW both cards — OUT right of the source port,
   *                              DOWN past the lower card's bottom edge, across at
   *                              the detour depth, UP, and IN to the target port
   *                              from its left. Four control points, so the
   *                              unbundled-bezier rounds the corners the way the
   *                              mock's single-arc aesthetic would.
   *
   * Why the detour exists: nodes are draggable, and a user can put a dependent
   * LEFT of its source (the owner did — the screenshot this round answers). On a
   * backward edge the forward formula loops the line over the cards and the
   * arrowhead ends up behind one of them; the detour keeps the line in the clear
   * and lands the arrowhead pointing INTO the left port exactly like a forward
   * edge. The stub (EDGE_STUB) is also why the regime boundary is 60: below it
   * the two 60px stubs would cross each other and the mock's S-curve degenerates.
   *
   * `depth` is the clearance below the LOWER card's bottom edge; applyEdgeCurves
   * staggers it per target so parallel backward edges do not overlap exactly.
   *
   * Pure — node --test drives every regime (graph-edges.test.mjs).
   */
  var EDGE_FORWARD_MIN_DX = 60;
  var EDGE_STUB = 60;
  var EDGE_DETOUR_DEPTH = 44;
  var EDGE_DETOUR_STAGGER = 24;

  function edgeRouteFor(source, target, depth) {
    var sp = { x: source.x + source.w / 2, y: source.y };
    var tp = { x: target.x - target.w / 2, y: target.y };
    var dx = tp.x - sp.x;
    if (dx >= EDGE_FORWARD_MIN_DX) {
      var k = Math.max(60, dx / 2);
      var bezier = [
        { x: sp.x + k, y: sp.y },
        { x: tp.x - k, y: tp.y },
      ];
      var projected = projectControlPoints(sp, tp, bezier);
      if (!projected) return null;
      return { kind: "bezier", points: bezier, weights: projected.weights, distances: projected.distances };
    }
    var clear = depth === undefined ? EDGE_DETOUR_DEPTH : depth;
    var yDetour = Math.max(source.y + source.h / 2, target.y + target.h / 2) + clear;
    var points = [
      { x: sp.x + EDGE_STUB, y: sp.y },
      { x: sp.x + EDGE_STUB, y: yDetour },
      { x: tp.x - EDGE_STUB, y: yDetour },
      { x: tp.x - EDGE_STUB, y: tp.y },
    ];
    var detour = projectControlPoints(sp, tp, points);
    if (!detour) return null;
    return { kind: "detour", points: points, weights: detour.weights, distances: detour.distances };
  }

  /**
   * 105 §B — the per-edge routing pass. Every edge's curve is decided from the
   * LIVE card boxes (the measured cardH, like the minimap reads) through
   * edgeRouteFor; backward edges into the same target stagger their detour depth
   * (EDGE_DETOUR_STAGGER per ordinal) so parallel detours do not overlap exactly.
   *
   * Runs at layoutstop AND after a user drag (`dragfree`, wired in render): the
   * (weight, distance) form is RELATIVE to the source→target vector, so a node
   * moved without a recompute keeps its old curve re-projected onto the new
   * vector — the arc-over-the-card the owner photographed. Measured (105 §A.3):
   * an edge laid out at dx=+160, its target then dragged to dx=−400, had its
   * "enter from the left" control land 85px PAST the target port.
   */
  PipelineGraph.prototype.applyEdgeCurves = function () {
    if (!this.cy) return;
    var self = this;
    var boxOf = function (n) {
      return { x: n.position().x, y: n.position().y, w: self.nodeWidth(n), h: n.data("cardH") || self.tokens.cardH };
    };
    var routed = [];
    var detoursPerTarget = {};
    this.cy.edges().forEach(function (edge) {
      var sBox = boxOf(edge.source());
      var tBox = boxOf(edge.target());
      var route = edgeRouteFor(sBox, tBox);
      if (!route) return;
      routed.push({ edge: edge, sBox: sBox, tBox: tBox });
      if (route.kind === "detour") {
        var tid = edge.target().id();
        detoursPerTarget[tid] = (detoursPerTarget[tid] || 0) + 1;
      }
    });
    var ordinal = {};
    routed.forEach(function (r) {
      var route = edgeRouteFor(r.sBox, r.tBox);
      if (route && route.kind === "detour" && detoursPerTarget[r.edge.target().id()] > 1) {
        var tid = r.edge.target().id();
        ordinal[tid] = (ordinal[tid] || 0) + 1;
        route = edgeRouteFor(r.sBox, r.tBox, EDGE_DETOUR_DEPTH + ordinal[tid] * EDGE_DETOUR_STAGGER);
      }
      if (!route) return;
      r.edge.style({
        "control-point-weights": route.weights,
        "control-point-distances": route.distances,
      });
    });
  };

  /* ------------------------------------------------------- view controls (§B) */

  var FIT_PADDING = 48;
  // 080 §A's ceiling ("default zoom too big"): fit never zooms IN past 1.0 — a one-node
  // pipeline used to fit to 3x. That ruling (and 085's) stands; the FLOOR that used to sit
  // beside it does not.
  //
  // 098 §B — why there is no lower bound any more, measured rather than argued.
  // `FIT_MIN_ZOOM = 0.75` was read as 059 §B's "three nodes should fill the pane, not sit in
  // one corner". But a floor can only ever BIND when the natural fit zoom is BELOW it, which
  // is exactly the case where the content is bigger than the canvas — so it never helped the
  // three-node case (whose natural zoom is above 1 and is capped by the ceiling) and it broke
  // every graph that needed to shrink. Measured on the demo stack, 2026-09-08,
  // nyc/mobility/weather_sensitivity_by_borough at 1440x900 with the dock open, after Fit:
  //
  //     canvas box   y 136..604   (h 468, w 880)      cy.zoom() 0.75  ← the floor, exactly
  //     content bb   638 x 828 model units            natural fit zoom (468-96)/828 = 0.449
  //     card stage_daily_by_zone  y  61..178          75 px ABOVE the canvas, clipped
  //     card stage_calendar       y 562..679          75 px BELOW  the canvas
  //
  // 828 * 0.75 = 621 in a 468-tall canvas is 153 px of overflow, centred: 76 each side. The
  // clamp then called `cy.center()`, which is what made the overhang symmetric and looks so
  // much like a padding bug that 093 filed it as one.
  //
  // It is NOT a padding bug, and the two addends the report proposed are both zero here:
  //   - the HTML card overlay does NOT overhang the Cytoscape node box. 082 addendum P1's
  //     `syncCardHeights` measures each card's `offsetHeight` and writes it onto the node, so
  //     the same run reads node w/h = 236x156 and card w/h = 236x156 (177x117 rendered at
  //     0.75). The node box IS the card;
  //   - the canvas does not start under the top bar: `.pe-topbar` ends at y 136 and
  //     `#cy-canvas` begins at y 136, measured in the same evaluate().
  // So FIT_PADDING stays 48 rendered px on every side, and the fix is to let fit fit.
  var FIT_MAX_ZOOM = 1.0;

  /**
   * The zoom at which `content` (model units) fits inside `viewport` (rendered px) with
   * [padding] rendered px on all four sides, never magnified past [FIT_MAX_ZOOM].
   *
   * Pure, and the whole of the decision — `fitToView` no longer asks Cytoscape for a zoom and
   * then argues with it — so `node --test` owns the invariant directly: the returned z always
   * satisfies `content * z + 2 * padding <= viewport` whenever the viewport has room for the
   * padding at all. A viewport too small to hold its own padding, or an empty graph, has no
   * meaningful fit; both answer FIT_MAX_ZOOM rather than 0 or Infinity.
   */
  function fitZoomFor(viewportW, viewportH, contentW, contentH, padding) {
    var availW = viewportW - padding * 2;
    var availH = viewportH - padding * 2;
    if (!(availW > 0) || !(availH > 0) || !(contentW > 0) || !(contentH > 0)) return FIT_MAX_ZOOM;
    return Math.min(FIT_MAX_ZOOM, availW / contentW, availH / contentH);
  }

  /**
   * 104 §B — is the view still THE FIT, or has the user moved it since?
   *
   * A resize must re-fit a graph that is showing the fit and must NOT touch one the user has
   * panned or zoomed to a corner. Nothing in Cytoscape distinguishes the two: `pan`/`zoom`
   * fire identically for a user drag and for `fitToView`'s own `cy.zoom()` + `cy.center()`,
   * so a listener that simply cleared the flag would clear it the moment fit set it. The
   * discriminator is a programmatic BRACKET around this class's own view calls — the same
   * shape as "discriminate on scope liveness, not on the exception you were handed".
   */
  PipelineGraph.prototype.watchViewGestures = function () {
    var self = this;
    if (!self.cy || self._viewWatch) return;
    self._viewWatch = true;
    self.cy.on("pan zoom", function () {
      if (!self._inProgrammaticView) self._fitted = false;
    });
  };

  PipelineGraph.prototype._programmaticView = function (fn) {
    this._inProgrammaticView = true;
    try {
      fn();
    } finally {
      this._inProgrammaticView = false;
    }
  };

  PipelineGraph.prototype.fitToView = function () {
    if (isGone(this)) return;
    var self = this;
    self._programmaticView(function () {
      var bb = self.cy.elements().boundingBox();
      self.cy.zoom(fitZoomFor(self.cy.width(), self.cy.height(), bb.w, bb.h, FIT_PADDING));
      self.cy.center();
    });
    self._fitted = true;
  };

  PipelineGraph.prototype.resetView = function () {
    if (isGone(this)) return;
    var self = this;
    self._programmaticView(function () {
      self.cy.zoom(1);
      self.cy.center();
    });
    /* Reset is a VIEW the user chose, not the fit: a later resize leaves it alone. */
    self._fitted = false;
  };

  PipelineGraph.prototype.zoomBy = function (factor) {
    if (isGone(this)) return;
    var self = this;
    self._programmaticView(function () {
      self.cy.zoom({
        level: self.cy.zoom() * factor,
        renderedPosition: { x: self.cy.width() / 2, y: self.cy.height() / 2 },
      });
    });
    self._fitted = false;
  };

  /**
   * 104 §B — the stage's box changed (the dock was dragged, the rail collapsed, the window
   * resized). Cytoscape re-reads its container only on a WINDOW resize, so a dock drag would
   * otherwise leave `cy` painting into a viewport that no longer exists: the bottom cards
   * vanish under the dock and `cy.height()` still reports the old number. This is the ONE
   * entry point for that, and the stage ResizeObserver below is its only caller.
   */
  PipelineGraph.prototype.handleStageResize = function () {
    if (isGone(this)) return;
    this.cy.resize();
    this.refreshCardMetrics();
    if (this._fitted) this.fitToView();
  };

  /**
   * 151 / #144: a node's box width — the card's for a card, the marker's compact box
   * for a boundary (the stylesheet's `node.boundary` width). Read from Cytoscape so
   * the router and the minimap paint the same box the stylesheet declared; the token
   * is the fallback for a fake without `width()`.
   */
  PipelineGraph.prototype.nodeWidth = function (n) {
    if (n && typeof n.width === "function") {
      var w = n.width();
      if (isFinite(w) && w > 0) return w;
    }
    return this.tokens.cardW;
  };

  /* ------------------------------------------------------------- the minimap */

  /**
   * The minimap (080 §A): nodes as small bars carrying the state colour, the
   * viewport rectangle in --brand. Pure DOM beside the canvas — Cytoscape has no
   * minimap of its own. Bars are positioned from post-layout model coordinates
   * scaled into the container; the viewport rect re-reads cy.extent() on pan/zoom.
   */
  PipelineGraph.prototype.renderMinimap = function () {
    var self = this;
    if (!self.cy || typeof document === "undefined") return;
    var el = document.getElementById("pe-minimap");
    if (!el) return;
    el.innerHTML = "";

    var PAD = 6;
    var w = el.clientWidth || 160;
    var h = el.clientHeight || 96;
    var xs = [];
    var ys = [];
    self.cy.nodes().forEach(function (n) {
      xs.push(n.position().x);
      ys.push(n.position().y);
    });
    if (!xs.length) return;
    // 082 addendum P1: each node carries its OWN height now, so the extent uses the
    // tallest card rather than one assumed box. 151: and its own WIDTH — a boundary's
    // box is the compact marker's (nodeWidth), not the card's.
    var halfW = self.tokens.cardW / 2;
    var nodeH = function (n) { return n.data("cardH") || self.tokens.cardH; };
    var halfH = Math.max.apply(null, self.cy.nodes().map(function (n) { return nodeH(n) / 2; }));
    var minX = Math.min.apply(null, xs) - halfW;
    var maxX = Math.max.apply(null, xs) + halfW;
    var minY = Math.min.apply(null, ys) - halfH;
    var maxY = Math.max.apply(null, ys) + halfH;
    var scale = Math.min((w - PAD * 2) / (maxX - minX), (h - PAD * 2) / (maxY - minY));
    self._mm = { el: el, minX: minX, minY: minY, scale: scale, pad: PAD };

    self.cy.nodes().forEach(function (n) {
      var bar = document.createElement("i");
      var boundary = n.data("kind") === "boundary";
      var state = n.data("state");
      bar.className =
        "pe-mm-node" +
        (state && state !== "idle" ? " pe-mm-" + state : "") +
        (boundary ? " pe-mm-boundary pe-mm-boundary-" + (n.data("boundary") === "end" ? "end" : "start") : "");
      bar.setAttribute("data-id", n.id());
      var nw = self.nodeWidth(n);
      var nh = nodeH(n);
      // 151/#144: a marker keeps its silhouette — a square box the size of the shape,
      // centred where the disc/square sits (the top of the marker's box, above its word).
      if (boundary) {
        var side = Math.max(6, Math.min(nw, nh) * scale);
        bar.style.left = (self._mm.pad + (n.position().x - Math.min(nw, nh) / 2 - minX) * scale) + "px";
        bar.style.top = (self._mm.pad + (n.position().y - nh / 2 - minY) * scale) + "px";
        bar.style.width = side + "px";
        bar.style.height = side + "px";
      } else {
        bar.style.left = (self._mm.pad + (n.position().x - nw / 2 - minX) * scale) + "px";
        bar.style.top = (self._mm.pad + (n.position().y - nh / 2 - minY) * scale) + "px";
        bar.style.width = Math.max(8, nw * scale) + "px";
        bar.style.height = Math.max(5, nh * scale) + "px";
      }
      el.appendChild(bar);
    });

    var view = document.createElement("div");
    view.className = "pe-mm-view";
    el.appendChild(view);
    self.updateMinimapViewport();
    self.cy.on("pan zoom resize", function () {
      self.updateMinimapViewport();
    });
  };

  PipelineGraph.prototype.updateMinimapViewport = function () {
    var mm = this._mm;
    if (!mm || !this.cy) return;
    var view = mm.el.querySelector(".pe-mm-view");
    if (!view) return;
    var ext = this.cy.extent();
    view.style.left = (mm.pad + (ext.x1 - mm.minX) * mm.scale) + "px";
    view.style.top = (mm.pad + (ext.y1 - mm.minY) * mm.scale) + "px";
    view.style.width = Math.max(6, (ext.x2 - ext.x1) * mm.scale) + "px";
    view.style.height = Math.max(6, (ext.y2 - ext.y1) * mm.scale) + "px";
  };

  PipelineGraph.prototype.updateMinimapNode = function (nodeId, state) {
    var mm = this._mm;
    if (!mm) return;
    var bars = mm.el.querySelectorAll(".pe-mm-node");
    for (var i = 0; i < bars.length; i++) {
      if (bars[i].getAttribute("data-id") === nodeId) {
        // 151: a marker's silhouette classes survive its state changes.
        var keep = (bars[i].className.match(/pe-mm-boundary(-start|-end)?/g) || []).join(" ");
        bars[i].className = "pe-mm-node" + (state && state !== "idle" ? " pe-mm-" + state : "") + (keep ? " " + keep : "");
        return;
      }
    }
  };

  // Class emission (pipeline-editor.md §5.1): `idle` is explicit so setNodeState()'s
  // removeClass of all five states stays symmetric; TYPE is a class per node type;
  // `caller` marks the result node, mirroring the server's Node.isCallerNode (which has
  // no type guard): an explicit output.target "caller" on ANY type — contract §4.9
  // permits a standard §4.7 output block on a PIPELINE node — or a DQL node with the
  // output block omitted (the D1 default; DML/DDL forbid the block, so the DQL guard on
  // the omitted arm is load-bearing — without it every DML/DDL node would mark caller).
  // 080 §A: the class stays (the dock's Details pane and tests read it), the canvas's
  // double-border style for it is retired — the mock's caller node carries no marker.
  function nodeClasses(n) {
    var classes = ["idle"];
    var type = (n.type || "").toUpperCase();
    if (type === "PIPELINE") {
      classes.push("pipeline-node");
    } else if (type === "CALCULATOR") {
      classes.push("calculator-node");
    } else if (type === "DQL" || type === "DML" || type === "DDL") {
      classes.push("type-" + type.toLowerCase());
    }
    var caller =
      (n.output && n.output.target === "caller") || (type === "DQL" && !n.output);
    if (caller) classes.push("caller");
    return classes.join(" ");
  }

  /**
   * 151 (#127) — the node's ONE configured output, as the card's port: where its rows go
   * (`tempdb.stg`, `warehouse.facts`, `caller`) and, once 149's samples arrive, whether
   * they are going there right now. Mirrors the server's own node-shape → destination
   * mapping (NodeOperations.operationFor) so the idle port and the measured
   * `destination` name the same thing: a DQL's output block (omitted = caller, contract
   * §4.7); a DML statement writes INTO its source and names no table (no SQL lineage);
   * a PIPELINE node's port is the output it declared for the child's caller rows. DDL,
   * an output-less PIPELINE and a CALCULATOR have NO port — nothing they do is a row
   * write (a calculator's context keys are its fact line). The dock's Details pane
   * carries the long form (init.js outputText); `null` means no port.
   */
  function outputPortFor(n) {
    var type = (n.type || "").toUpperCase();
    var o = n.output;
    if (type === "DML") {
      return n.source === "tempdb"
        ? { kind: "tempdb", text: "tempdb" }
        : { kind: "datasource", datasource: n.source || "—", text: n.source || "—" };
    }
    if (type === "DQL" && !o) return { kind: "caller", text: "caller" };
    if (type !== "DQL" && type !== "PIPELINE") return null;
    if (!o) return null;
    if (o.target === "caller") return { kind: "caller", text: "caller" };
    if (o.target === "tempdb") return { kind: "tempdb", table: o.table || "—", text: "tempdb." + (o.table || "—") };
    if (o.target === "datasource") {
      return { kind: "datasource", datasource: o.datasource || "—", table: o.table || "—", text: (o.datasource || "—") + "." + (o.table || "—") };
    }
    return null;
  }

  /**
   * Node data for the card: `state`, `run` and `facts` are WRITTEN (never derived
   * later) because the html-label template re-runs on `data` events and receives only
   * this snapshot — the canvas classes stay the machine the a11y sweep and the
   * stylesheet read, and this data keeps the HTML card in lockstep with them.
   */
  function nodeCardData(n, settings) {
    var type = (n.type || "").toUpperCase();
    var data = {
      id: n.id,
      type: type,
      typeIcon: iconForType(type),
      state: "idle",
      run: null,
      template: n.template || null,
      facts: [],
    };
    if (type === "PIPELINE") {
      var child = n.pipeline && n.pipeline.name ? n.pipeline.name : "pipeline";
      data.facts.push({ kind: "source", icon: "workflow", text: n.pipeline && n.pipeline.version ? child + " @ v" + n.pipeline.version : child });
      var params = n.parameters ? Object.keys(n.parameters) : [];
      if (params.length) {
        data.facts.push({ kind: "params", icon: "file", text: params.slice(0, 2).join(", ") + (params.length > 2 ? " +" + (params.length - 2) : "") });
      }
    } else if (type === "CALCULATOR") {
      // The fact the 072 brief asks for: `kind → context_key`. It answers the same
      // question a SQL node's source line does — what does this node work on, and
      // what does it leave behind. 121: a multi-output node says what it writes —
      // every key, `kind → window_start, window_end`; the mapping itself (which
      // output feeds which key) is the Details pane's row. The keys are SORTED:
      // body_json is JSONB, which does not preserve an object's insertion order,
      // so the author's `{"start": …, "end": …}` comes back in storage order —
      // a deterministic order beats one that flips with the persistence layer.
      var writes = n.context_keys ? Object.keys(n.context_keys).sort().map(function (o) { return n.context_keys[o]; }) : [n.context_key || "?"];
      data.facts.push({ kind: "source", icon: "calculator", text: (n.kind || "?") + " → " + writes.join(", ") });
      var inputs = n.inputs ? Object.keys(n.inputs) : [];
      if (inputs.length) {
        data.facts.push({ kind: "inputs", icon: "db", text: inputs.slice(0, 3).join(" · ") });
      }
    } else {
      var sourceText = null;
      if (n.source === "tempdb") {
        var engine =
          settings && settings.tempdb && settings.tempdb.engine ? settings.tempdb.engine : "H2";
        sourceText = "tempdb · " + engine;
        data.sourceName = null;
      } else if (n.source) {
        sourceText = n.source;
        data.sourceName = n.source;
      }
      if (sourceText) data.facts.push({ kind: "source", icon: "db", text: sourceText });
      var tpl = templateLine(n.template);
      if (tpl) {
        data.facts.push({
          kind: "template",
          icon: "file",
          text: tpl,
          title: n.template && n.template.id ? n.template.id + (n.template.version ? " @ v" + n.template.version : "") : tpl,
        });
      }
    }
    // 151: the output is the card's PORT row (buildCardHtml), not a fact line — the
    // destination appears once, where its measured write shows.
    data.output = outputPortFor(n);
    return data;
  }

  function buildElements(nodes, settings, options) {
    var elements = [];
    var i, j;
    // 151/#144: whether the Start marker is a run trigger — the page's server-rendered
    // right, handed in; the graph never decides permissions.
    var canExecute = !!(options && options.canExecute === true);

    for (i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      elements.push({
        group: "nodes",
        data: nodeCardData(n, settings),
        classes: nodeClasses(n),
      });
    }

    for (i = 0; i < nodes.length; i++) {
      var deps = nodes[i].depends_on;
      if (deps && deps.length) {
        for (j = 0; j < deps.length; j++) {
          // 151 (#127): a depends_on edge is an ORDERING — "the target waits for the
          // source to finish" — and says so in its kind. It never carries a count and
          // never moves: what a node writes, and where, is its output port (the card
          // row buildCardHtml renders from `output`/`port`), not the arrow.
          elements.push({
            group: "edges",
            data: {
              id: deps[j] + "->" + nodes[i].id,
              source: deps[j],
              target: nodes[i].id,
              kind: "dependency",
            },
            classes: "dependency",
          });
        }
      }
    }

    // 150: the execution boundaries — view-only, derived, never authored. Only a
    // graph WITH nodes has boundaries: an empty or invalid draft keeps its honest
    // empty/validation view. The authored array above is untouched; the markers
    // exist only as elements of the rendered graph.
    if (nodes.length) {
      var ids = boundaryIdsFor(nodes);
      var bounds = rootsAndLeaves(nodes);
      // 151: unselectable — a tap on a marker must not paint the selection ring of a
      // thing that has no Details (init.js's selectOnly already refuses synthetic ids).
      elements.push({
        group: "nodes",
        data: { id: ids.start, kind: "boundary", boundary: "start", state: "idle", canExecute: canExecute },
        classes: "idle boundary",
        selectable: false,
      });
      elements.push({
        group: "nodes",
        data: { id: ids.end, kind: "boundary", boundary: "end", state: "idle", canExecute: canExecute, elapsed: null },
        classes: "idle boundary",
        selectable: false,
      });
      // 151: the connector names its SIDE so the stylesheet can anchor it to the
      // marker's compact shape (edge.boundary-start / edge.boundary-end) instead of
      // the card-width port every other edge uses.
      bounds.roots.forEach(function (rootId) {
        elements.push({
          group: "edges",
          data: { id: ids.start + "->" + rootId, source: ids.start, target: rootId, kind: "boundary", side: "start" },
          classes: "boundary boundary-start",
        });
      });
      bounds.leaves.forEach(function (leafId) {
        elements.push({
          group: "edges",
          data: { id: leafId + "->" + ids.end, source: leafId, target: ids.end, kind: "boundary", side: "end" },
          classes: "boundary boundary-end",
        });
      });
    }

    return elements;
  }

  PipelineGraph.prototype.buildElements = function () {
    var settings = this.editor && this.editor.pipeline ? this.editor.pipeline.settings : null;
    return buildElements(this.nodes, settings, { canExecute: !!(this.editor && this.editor.canExecute === true) });
  };

  PipelineGraph.prototype.findNode = function (id) {
    if (!this.cy) return null;
    var el = this.cy.getElementById(id);
    return el.length ? el : null;
  };

  PipelineGraph.prototype.setNodeState = function (nodeId, state) {
    var node = this.findNode(nodeId);
    if (!node) return;

    NODE_STATES.forEach(function (s) {
      node.removeClass(s);
    });
    node.addClass(state);
    // The card reads state from DATA (the html-label template gets a data snapshot,
    // not classes) — writing it here is what re-renders the card's footer dot.
    node.data("state", state);
    // 151 (#127): an edge is an ORDERING, and its classes are facts about the two
    // nodes it joins — never a transfer, never in motion. INTO a running node the
    // edge is `active` (the consumer is running; a static dashed brand stroke);
    // OUT OF a completed node every dependency is `satisfied` (the dependents may
    // start); out of a failed/aborted node it is `unmet` (it cannot be satisfied
    // this run). The retired `done` (target ran) said nothing a reader needed, and
    // the retired rAF dash flow said something false: rows do not travel along an
    // arrow — a write shows on the producer's output port, from a measured sample.
    var incomers = node.incomers("edge");
    var outgoers = node.outgoers("edge");
    if (state === "running") {
      incomers.forEach(function (e) { e.addClass("active"); });
    } else if (state === "success") {
      incomers.forEach(function (e) { e.removeClass("active"); });
      outgoers.forEach(function (e) { e.removeClass("unmet"); e.addClass("satisfied"); });
    } else if (state === "failed" || state === "aborted") {
      incomers.forEach(function (e) { e.removeClass("active"); });
      outgoers.forEach(function (e) { e.removeClass("satisfied"); e.addClass("unmet"); });
    }
    // Keep the editor's nodeStates mirror complete. sse.js only writes entries for
    // nodes that START, and its execution_aborted sweep falls back to a classes-string
    // compare for the rest — a string the type classes ("idle type-dml") no longer
    // equal. Writing every transition here keeps pending nodes marked "idle", which
    // is what that sweep checks for.
    if (this.editor && this.editor.nodeStates) this.editor.nodeStates[nodeId] = state;
    this.updateMinimapNode(nodeId, state);
    // Mirror execution state to the a11y node list (a11y.js owns the DOM; the call
    // is guarded so the pure module stays loadable under node --test).
    if (typeof window !== "undefined" && window.a11yNodeState) window.a11yNodeState(nodeId, state);
    this.queueCardMeasure(nodeId); // #151: the footer's state line can change the card's height
  };

  /**
   * The run line (080 §A footer): `node_completed` carries FLAT `duration_ms` /
   * `rows_out` (SseEventProjection), plus `context_value` for a CALCULATOR — one data
   * write the html-label template re-renders on. The count stays on the node that
   * produced it: 151 (#127) retired the copy of `rows_out` onto every outgoing edge,
   * which made one staged table read as one write PER CONSUMER (the owner's
   * screenshot: "10,000 rows" on both arrows out of a node that wrote 10,000 rows
   * once). The producer's footer and its output port carry the count, scoped to
   * the operation that wrote it.
   */
  PipelineGraph.prototype.setNodeStats = function (nodeId, stats) {
    var node = this.findNode(nodeId);
    if (!node) return;
    node.data("run", formatRunLine(stats));
    this.queueCardMeasure(nodeId); // #151: the run line is a line the card did not have
  };

  /**
   * 149: the node's measured operation (node-ops.js `describe()`), written as card data —
   * the footer re-renders from it while the node runs. `view` null clears the line.
   */
  PipelineGraph.prototype.setNodeOperation = function (nodeId, view) {
    var node = this.findNode(nodeId);
    if (!node) return;
    node.data("op", view && view.cardLine ? view.cardLine : null);
    node.data("opCounts", view && view.cardCounts ? view.cardCounts : null);
    node.data("opState", view && view.state ? view.state : null);
    // 151: the output port reads the same view — one reducer, one measured truth.
    node.data("port", view && view.port ? view.port : null);
    this.queueCardMeasure(nodeId); // #151: the port block's lines (`one statement`, the count) grow the card
  };

  /**
   * 151: the stream is gone (sse.js handleConnectionLoss). Nothing about any node has
   * been observed since, so nothing is REWRITTEN — every card and marker is marked
   * `stale`, which freezes its motion and appends "— stream lost" to the port, and the
   * consumer-running emphasis leaves every edge. The recovery poll may later set the
   * End marker from the execution's polled status; node outcomes it cannot know.
   */
  PipelineGraph.prototype.markStreamLost = function () {
    if (!this.cy) return;
    this.cy.nodes().forEach(function (node) { node.data("stale", true); });
    this.cy.edges().forEach(function (edge) { edge.removeClass("active"); });
  };

  /** Reduced motion cannot be read from CSS here — the graph is canvas, not DOM. */
  function pulseEnabled(mql) {
    return !(mql && mql.matches);
  }

  PipelineGraph.prototype.resetAll = function () {
    var self = this;
    if (!self.cy) return;
    self.cy.nodes().forEach(function (node) {
      NODE_STATES.forEach(function (s) { node.removeClass(s); });
      node.addClass("idle");
      node.data("state", "idle");
      node.data("run", null);
      node.data("op", null);
      node.data("opCounts", null);
      node.data("opState", null);
      node.data("port", null);
      node.data("stale", null);
      // 150: the markers reset with everything else — Start goes neutral until
      // execution_started re-arms it, End loses the previous run's outcome word.
      if (node.data("kind") === "boundary") {
        var side = node.data("boundary") === "end" ? "end" : "start";
        node.data("label", MARKER_LABELS[side].idle);
        node.data("elapsed", null);
      }
      if (self.editor && self.editor.nodeStates && node.data("kind") !== "boundary") {
        self.editor.nodeStates[node.id()] = "idle";
      }
      self.updateMinimapNode(node.id(), "idle");
      if (typeof window !== "undefined" && window.a11yNodeState && node.data("kind") !== "boundary") {
        window.a11yNodeState(node.id(), "idle");
      }
    });
    self.cy.edges().forEach(function (edge) {
      EDGE_STATES.forEach(function (c) { edge.removeClass(c); });
    });
    // #151: every card just lost its run lines — each box follows (shrinking never overlaps).
    self.cy.nodes().forEach(function (node) { self.queueCardMeasure(node.id()); });
  };

  /**
   * 150: the boundaries' state, internal to the graph. The End marker moves ONLY
   * on the execution's authoritative terminal event — never on a node event —
   * and its word is that outcome (Finished / Failed / Stopped). Start runs while
   * the execution does. Deliberately NOT editor.nodeStates: the markers are not
   * nodes, the 135 sweep must not sweep them, and no nodeStates consumer may see
   * a synthetic id. The minimap bar follows (it is keyed by node id), the a11y
   * node list stays authored-only.
   */
  PipelineGraph.prototype.setMarkerState = function (side, state, extra) {
    if (!this.cy || !this._boundaryIds) return;
    var id = this._boundaryIds[side];
    if (!id) return;
    var node = this.findNode(id);
    if (!node || node.data("kind") !== "boundary") return;
    NODE_STATES.forEach(function (s) { node.removeClass(s); });
    node.addClass(state);
    node.data("state", state);
    node.data("label", (MARKER_LABELS[side] && MARKER_LABELS[side][state]) || state);
    // 151/#144: End shows how long the run took, when the caller knows (the run clock).
    node.data("elapsed", extra && extra.elapsed ? String(extra.elapsed) : null);
    this.updateMinimapNode(id, state);
    this.queueCardMeasure(id); // #151: the elapsed line under End is a line the marker did not have
    // #151: End's terminal state IS the run's end — the moment a layout left stale by
    // mid-run growth may move the cards again.
    if (side === "end" && (state === "success" || state === "failed" || state === "aborted")) this.settleCardHeights();
  };

  /** The disc an event came from, or null: the only element wireMarkerActivation acts on. */
  function markerRunOf(target) {
    return target && typeof target.closest === "function" ? target.closest(".pe-marker-run") : null;
  }

  /**
   * 151/#144 — the Start disc runs the pipeline. ONE delegated click + keydown pair on
   * the graph container (the html-label re-renders the disc on every state change, so a
   * per-element listener would leak), resolving the LIVE component exactly as the card's
   * open button does, and calling its `executePipeline()` — the same method the toolbar's
   * button calls, so parameters, the draft pin and the CSRF path are one code path.
   * 159/#148: while `isExecuting` the same activation calls `cancelExecution()` — the
   * toolbar's Cancel, one code path again — and the disc is drawn as Cancel
   * (buildMarkerHtml). Idempotent per container (a history-restored container keeps its
   * listeners). A viewer's marker has no `.pe-marker-run` element at all, so nothing here
   * can fire for them.
   *
   * THE PRESS GUARD (159/#148, the owner's "Start does nothing" on the live editor). The
   * disc sits in the html-label layer INSIDE the Cytoscape container, so a pointer press on
   * it also reached Cytoscape's own mousedown binding on that container, which
   * `activate()`d the marker node; that emits `style`, and cytoscape-node-html-label
   * answers every `style` by re-parsing the label a `setTimeout(0)` later — the disc under
   * the pointer was REMOVED and a new one put in its place ~5–15 ms after mousedown.
   * A human holds the button 50–150 ms, so mouseup landed on the new element and the
   * browser, with no connected common ancestor for the pair, dispatched no click at all
   * (measured 2026-09-17: mousedown, two mutations at +14 ms, mouseup, no click, no
   * POST …/execute). Automation releases within ~2 ms and beat the timer, which is why
   * the 151 browser arm stayed green. The guard stops the press at the container in the
   * CAPTURE phase — before Cytoscape's bubble-phase binding — for `.pe-marker-run`
   * targets only: the disc is a button, not a canvas gesture (a tap on a marker selects
   * nothing anyway: selectOnly ignores synthetic ids). Nothing is prevented, so focus
   * still lands on the disc. mousedown is the mouse path; pointerdown and touchstart are
   * Cytoscape's touch paths and are stopped for the same reason.
   */
  PipelineGraph.prototype.wireMarkerActivation = function (container) {
    var self = this;
    if (!container || typeof container.addEventListener !== "function" || container.__peMarkerRunWired) return;
    container.__peMarkerRunWired = true;
    var press = function (evt) {
      if (markerRunOf(evt.target)) evt.stopPropagation();
    };
    ["mousedown", "pointerdown", "touchstart"].forEach(function (type) {
      container.addEventListener(type, press, true);
    });
    var activate = function (evt) {
      if (!markerRunOf(evt.target)) return;
      evt.preventDefault();
      evt.stopPropagation();
      var ed = (typeof window !== "undefined" && window.__peInstance) || self.editor;
      if (!ed) return;
      if (ed.isExecuting) {
        if (typeof ed.cancelExecution === "function") ed.cancelExecution();
        return;
      }
      if (typeof ed.executePipeline === "function") ed.executePipeline();
    };
    container.addEventListener("click", activate);
    container.addEventListener("keydown", function (evt) {
      if (evt.key !== "Enter" && evt.key !== " ") return;
      activate(evt);
    });
    this.keepMarkerFocus(container);
  };

  /**
   * 159/#148 — focus survives the disc's re-render. Every state change re-parses the
   * label (see the press guard above), and the browser drops focus to <body> when the
   * focused element is removed — 151 recorded exactly that: activate the disc from the
   * keyboard and the focus is gone the moment the run starts. The keeper remembers which
   * disc has focus (focusin), watches the canvas for the swap while it does, and hands
   * focus to the replacement disc once it exists — only when focus fell to <body>, never
   * when it moved somewhere on purpose. Chrome fires focusout for the removal too, with the
   * old disc STILL connected during the event (measured 2026-09-17), so a focusout is
   * classified one tick later: a disc that is gone by then was re-rendered (keep watching);
   * one still connected was a real blur (Tab, a click elsewhere — stop). The restore itself
   * is deferred one tick as well, and for an ordering reason: a mouse click on a card runs
   * the mousedown listeners (Cytoscape activates that card, the html-label queues its
   * re-render) BEFORE the focus change (the disc's focusout queues its classification), so
   * the re-render's mutation reaches the observer with focus already on <body> and the
   * classification still pending — a synchronous restore would pull focus back to the disc
   * after every click on the canvas. Inert where there is no MutationObserver (node --test).
   */
  PipelineGraph.prototype.keepMarkerFocus = function (container) {
    if (typeof MutationObserver !== "function" || typeof document === "undefined") return;
    var focusedId = null;
    var discOf = function (target) {
      return target && typeof target.closest === "function" ? target.closest("[data-marker-run]") : null;
    };
    var restorePending = false;
    var restore = function () {
      restorePending = false;
      if (!focusedId) return;
      var active = document.activeElement;
      if (active && active !== document.body) return;
      var discs = container.querySelectorAll("[data-marker-run]");
      for (var i = 0; i < discs.length; i++) {
        if (discs[i].getAttribute("data-marker-run") === focusedId) {
          discs[i].focus({ preventScroll: true });
          return;
        }
      }
    };
    var observer = new MutationObserver(function () {
      if (!focusedId || restorePending) return;
      restorePending = true;
      setTimeout(restore, 0);
    });
    container.addEventListener("focusin", function (evt) {
      var disc = discOf(evt.target);
      if (!disc) return;
      focusedId = disc.getAttribute("data-marker-run");
      observer.observe(container, { childList: true, subtree: true });
    });
    container.addEventListener("focusout", function (evt) {
      var disc = discOf(evt.target);
      if (!disc) return;
      setTimeout(function () {
        if (!disc.isConnected) return;
        focusedId = null;
        observer.disconnect();
      }, 0);
    });
  };

  /**
   * Re-apply the stylesheet to a LIVE graph.
   *
   * `cy.style(array)` — what 080 used on every theme switch — does not replace the
   * sheet: it resets the whole style to Cytoscape's DEFAULTS. Measured on the live
   * editor (Chrome 148, 2026-09-07): after one call, `line-color` was `#999`, edge
   * `width` 30px and node `background-color` `#999`. That is the owner's "the
   * re-render on a theme switch collapses edges into thick grey bands", and it is a
   * SECOND defect beside the `color-mix` one — the tokens were fine by then.
   *
   * `cy.style().fromJson(sheet).update()` is the call that re-applies. Its one
   * condition is that the sheet be JSON — every function value is dropped — which is
   * why the node height is a plain token here and a per-element bypass there.
   */
  PipelineGraph.prototype.applyStylesheet = function () {
    if (isGone(this)) return;
    this.cy.style().fromJson(buildStylesheet(this.tokens)).update();
  };

  PipelineGraph.prototype.updateTheme = function () {
    var self = this;
    self.tokens = readDesignTokens(self.containerId);
    if (!self.cy) return;
    self.applyStylesheet();
    // A theme can move a metric (a font stack that failed to load, a different border
    // width), and the measured heights would then be stale. Re-measuring is a no-op
    // unless a card really moved (082 addendum P1).
    self._heightPasses = 0;
    afterPaint(function () { self.syncCardHeights(); });
  };

  /**
   * 082 §A — the wide-stage step-up crossed while the page is open.
   *
   * The card box is read ONCE per graph, so a window resized across the container
   * query's threshold would grow the HTML card while the Cytoscape node it sits on
   * kept the old box: cards overlapping their own edges until a reload. This re-reads
   * the geometry and, only when it actually changed, re-applies the stylesheet and
   * re-runs the layout — dagre's separation is tuned to the card width, so a new box
   * needs new positions, not just a new size.
   *
   * Returns whether it did anything, so a caller (and a test) can tell.
   */
  PipelineGraph.prototype.refreshCardMetrics = function () {
    var self = this;
    if (isGone(self)) return false;
    var next = readDesignTokens(self.containerId);
    if (next.cardW === self.tokens.cardW && next.cardH === self.tokens.cardH) return false;
    self.tokens = next;
    self.applyStylesheet();
    // The floor moved, so the measured heights are stale: re-measure after the pass
    // (082 addendum P1) exactly as the first render does.
    self._heightPasses = 0;
    self.runLayout(function () {
      afterPaint(function () { self.syncCardHeights(); });
    });
    return true;
  };

  var api = {
    PipelineGraph: PipelineGraph,
    buildStylesheet: buildStylesheet,
    buildElements: buildElements,
    buildCardHtml: buildCardHtml,
    readDesignTokens: readDesignTokens,
    toLegacyRgb: toLegacyRgb,
    layoutOptions: layoutOptions,
    pulseEnabled: pulseEnabled,
    fitZoomFor: fitZoomFor,
    formatRunLine: formatRunLine,
    durationText: durationText,
    truncateLeft: truncateLeft,
    templateLine: templateLine,
    edgeControlPoints: edgeControlPoints,
    edgeRouteFor: edgeRouteFor,
    projectControlPoints: projectControlPoints,
    syntheticId: syntheticId,
    boundaryIdsFor: boundaryIdsFor,
    rootsAndLeaves: rootsAndLeaves,
    MARKER_LABELS: MARKER_LABELS,
    ARROW_SCALE: ARROW_SCALE,
    ARROW_BASE_PX: ARROW_BASE_PX,
    BOUNDARY_W: BOUNDARY_W,
    EDGE_FORWARD_MIN_DX: EDGE_FORWARD_MIN_DX,
    EDGE_STUB: EDGE_STUB,
    EDGE_DETOUR_DEPTH: EDGE_DETOUR_DEPTH,
    EDGE_DETOUR_STAGGER: EDGE_DETOUR_STAGGER,
    iconForType: iconForType,
    typeToken: typeToken,
    escapeHtml: escapeHtml,
    FIT_PADDING: FIT_PADDING,
    FIT_MAX_ZOOM: FIT_MAX_ZOOM,
  };
  // node --test requires this file directly (the 027b harness); the browser keeps
  // the global the editor's other modules already reference. PEGraphUtil exposes
  // the pure helpers (iconForType / typeToken / escapeHtml) to init.js's Details
  // pane without a second copy of the maps.
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") {
    window.PipelineGraph = PipelineGraph;
    window.PEGraphUtil = api;
  }
})();
