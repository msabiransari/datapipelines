(function () {
  "use strict";

  var NODE_STATES = ["idle", "running", "success", "failed", "aborted"];

  /* Type glyphs (059 §reference, retinted 080 §A): the glyph rides the card's icon
   * TILE — a rounded square washed in the node type's accent pair (--type-* /
   * --type-*-bg, app tokens 080) — not on the bare card. ONE glyph per card (059b):
   * the engine's identity is the source fact line's TEXT, never a second icon.
   * db/table/boxes/workflow are the sprite's 059 LEGACY ids (db = lucide's database);
   * 085 §B gave CALCULATOR its honest glyph — the sprite's `calculator`, added when
   * the icon set was unfenced — retiring the `file` stand-in this comment used to
   * apologise for. */
  var TYPE_ICONS = { DQL: "db", DML: "table", DDL: "boxes", PIPELINE: "workflow", CALCULATOR: "calculator" };

  /* The tile's accent pair per node type, as the CSS custom-property suffixes of the
   * 080 app-token block. The card sets `--type`/`--type-bg` from these and every
   * state/rule below reads the pair back — the card never names a colour itself. */
  var TYPE_TOKEN = { DQL: "dql", DML: "dml", DDL: "ddl", PIPELINE: "pipeline", CALCULATOR: "calc" };

  function iconForType(type) {
    return TYPE_ICONS[String(type || "").toUpperCase()] || "db";
  }

  function typeToken(type) {
    return TYPE_TOKEN[String(type || "").toUpperCase()] || "dql";
  }

  function readDesignTokens() {
    var styles = getComputedStyle(document.documentElement);
    // The card's geometry rides the same custom-property bridge as the colours: the
    // HTML card (pipeline-editor.css) and the Cytoscape node box MUST agree on a box,
    // and one token read is the only way they do. Numeric fallbacks match the CSS.
    var cardW = parseInt(styles.getPropertyValue("--pe-card-w"), 10) || 236;
    var cardH = parseInt(styles.getPropertyValue("--pe-card-h"), 10) || 148;
    var cardRadius = styles.getPropertyValue("--radius-lg").trim() || "12px";
    return {
      // 080 §A: the canvas reads the mock's tokens straight from the 080 app-token
      // block. Hard hexes stay in the fallback slot so a stale theme file cannot
      // blank the graph; the fallbacks are the mock's light values.
      brand: styles.getPropertyValue("--brand").trim() || "#2563eb",
      brandSoft: styles.getPropertyValue("--brand-soft").trim() || "#dbeafe",
      edgeIdle: styles.getPropertyValue("--edge").trim() || "#94a3b8",
      edgeActive: styles.getPropertyValue("--edge-active").trim() || "#2563eb",
      edgeDone: styles.getPropertyValue("--edge-done").trim() || "#15803d",
      nodeSurface: styles.getPropertyValue("--surface-raised").trim() || "#ffffff",
      nodeBorder: styles.getPropertyValue("--border-subtle").trim() || "#b2b7bf",
      nodeSuccess: styles.getPropertyValue("--accent-success").trim() || "#15803d",
      nodeFailed: styles.getPropertyValue("--accent-danger").trim() || "#dc2626",
      nodeAborted: styles.getPropertyValue("--accent-warning").trim() || "#a16207",
      edgeLabelText: styles.getPropertyValue("--text-muted").trim() || "#64748b",
      edgeLabelBg: styles.getPropertyValue("--surface-page").trim() || "#f8f9fb",
      cardW: cardW,
      cardH: cardH,
      cardRadius: cardRadius,
    };
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
          "arrow-scale": 0.9,
          "curve-style": "unbundled-bezier",
          "source-endpoint": sourcePort,
          "target-endpoint": targetPort,
          width: 2,
          "line-cap": "round",
        },
      },
      {
        // Active (the target is running): --edge-active, dashed, and the `flow`
        // animation — canvas has no keyframes, so graph.js steps line-dash-offset on
        // a rAF loop while any edge is active (skipped under reduced motion). The
        // mock's blurred glow path has no Cytoscape counterpart (no filters on
        // canvas); the wider 2.5px stroke is the emphasis instead.
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
        // Done (the target ran): --edge-done at rest, arrow included.
        selector: "edge.done",
        style: {
          "line-color": tokens.edgeDone,
          "target-arrow-color": tokens.edgeDone,
        },
      },
      {
        // Row counts riding the edge (080 §A, owner-undecided, behind a class):
        // JS adds `rows` and the `rowLabel` data when the SOURCE node completes —
        // the wire carries rows_out only, so the label is the count flowing out of
        // the source along this edge. Small mono, muted, page-coloured backing so
        // the line does not show through the digits.
        selector: "edge.rows",
        style: {
          label: "data(rowLabel)",
          "font-size": 11,
          "font-family": "ui-monospace, SFMono-Regular, Menlo, monospace",
          color: tokens.edgeLabelText,
          "text-background-color": tokens.edgeLabelBg,
          "text-background-opacity": 1,
          "text-background-padding": 2,
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
   * A CALCULATOR completion carries context_value instead of rows.
   */
  function formatRunLine(stats) {
    if (!stats) return null;
    var out = "";
    if (stats.context_value !== undefined && stats.context_value !== null) {
      out = "= " + JSON.stringify(stats.context_value);
    } else {
      var r = Number(stats.rows_out);
      if (isFinite(r) && r >= 0) out = r.toLocaleString("en-US") + " rows";
    }
    var d = Number(stats.duration_ms);
    if (isFinite(d) && d >= 0) {
      var ms;
      if (d < 1000) ms = Math.round(d) + " ms";
      else if (d < 60000) ms = (d / 1000).toFixed(1) + " s";
      else ms = Math.floor(d / 60000) + "m " + Math.round((d % 60000) / 1000) + "s";
      out = out ? out + " · " + ms : ms;
    }
    return out || null;
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
    var esc = escapeHtml;
    var state = data.state || "idle";
    var h =
      '<div class="pe-card pe-card-' + esc(state) + '" data-node-id="' + esc(data.id) +
      '" style="--type:var(--type-' + esc(typeToken(data.type)) + ");--type-bg:var(--type-" +
      esc(typeToken(data.type)) + '-bg)">';

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

    h += '<div class="pe-card-progress" aria-hidden="true"><i></i></div>';

    h +=
      '<div class="pe-card-foot"><span class="pe-card-state"><i></i><span class="pe-card-st">' +
      esc(STATE_LABELS[state] || state) + '</span></span><span class="pe-card-rt">' +
      (data.run ? esc(data.run) : "") + "</span></div>";

    h += "</div>";
    return h;
  }

  function PipelineGraph(containerId, nodes, editor) {
    this.containerId = containerId;
    this.nodes = nodes;
    this.editor = editor;
    this.cy = null;
    this.tokens = readDesignTokens();
    this._flowRunning = false;
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

    this.cy = cytoscape({
      container: document.getElementById(this.containerId),
      elements: elements,
      style: buildStylesheet(this.tokens),
      wheelSensitivity: 0.3,
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
    // fit with floor AND ceiling, and the minimap's first paint.
    var layout = this.cy.elements().layout(layoutOptions());
    layout.one("layoutstop", function () {
      self.applyEdgeCurves();
      self.fitToView();
      self.renderMinimap();
    });
    layout.run();

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

    this.loadDialects();
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
   */
  function edgeControlPoints(sx, sy, tx, ty) {
    var dx = tx - sx;
    var dy = ty - sy;
    var len2 = dx * dx + dy * dy;
    if (len2 <= 0) return null;
    var len = Math.sqrt(len2);
    var k = Math.max(60, dx / 2);
    var c1x = sx + k;
    var c1y = sy;
    var c2x = tx - k;
    var c2y = ty;
    return {
      weights: [
        ((c1x - sx) * dx + (c1y - sy) * dy) / len2,
        ((c2x - sx) * dx + (c2y - sy) * dy) / len2,
      ],
      distances: [
        ((c1x - sx) * dy - (c1y - sy) * dx) / len,
        ((c2x - sx) * dy - (c2y - sy) * dx) / len,
      ],
    };
  }

  PipelineGraph.prototype.applyEdgeCurves = function () {
    if (!this.cy) return;
    var w = this.tokens.cardW;
    this.cy.edges().forEach(function (edge) {
      var s = edge.source().position();
      var t = edge.target().position();
      var cp = edgeControlPoints(s.x + w / 2, s.y, t.x - w / 2, t.y);
      if (!cp) return;
      edge.style({
        "control-point-weights": cp.weights,
        "control-point-distances": cp.distances,
      });
    });
  };

  /* ------------------------------------------------------- view controls (§B) */

  var FIT_PADDING = 48;
  // 059 §B's floor ("three nodes should fill the pane, not sit in one corner") and
  // 080 §A's ceiling ("default zoom too big"): fit may not zoom below the floor and
  // never zooms IN past 1.0 — a one-node pipeline used to fit to 3x.
  var FIT_MIN_ZOOM = 0.75;
  var FIT_MAX_ZOOM = 1.0;

  /** The clamp, pure so node --test owns both ends of it. */
  function clampFitZoom(z) {
    if (z < FIT_MIN_ZOOM) return FIT_MIN_ZOOM;
    if (z > FIT_MAX_ZOOM) return FIT_MAX_ZOOM;
    return z;
  }

  PipelineGraph.prototype.fitToView = function () {
    if (!this.cy) return;
    this.cy.fit(undefined, FIT_PADDING);
    var clamped = clampFitZoom(this.cy.zoom());
    if (clamped !== this.cy.zoom()) {
      this.cy.zoom(clamped);
      this.cy.center();
    }
  };

  PipelineGraph.prototype.resetView = function () {
    if (!this.cy) return;
    this.cy.zoom(1);
    this.cy.center();
  };

  PipelineGraph.prototype.zoomBy = function (factor) {
    if (!this.cy) return;
    this.cy.zoom({
      level: this.cy.zoom() * factor,
      renderedPosition: { x: this.cy.width() / 2, y: this.cy.height() / 2 },
    });
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
    var halfW = self.tokens.cardW / 2;
    var halfH = self.tokens.cardH / 2;
    var minX = Math.min.apply(null, xs) - halfW;
    var maxX = Math.max.apply(null, xs) + halfW;
    var minY = Math.min.apply(null, ys) - halfH;
    var maxY = Math.max.apply(null, ys) + halfH;
    var scale = Math.min((w - PAD * 2) / (maxX - minX), (h - PAD * 2) / (maxY - minY));
    self._mm = { el: el, minX: minX, minY: minY, scale: scale, pad: PAD };

    self.cy.nodes().forEach(function (n) {
      var bar = document.createElement("i");
      bar.className = "pe-mm-node" + (n.data("state") && n.data("state") !== "idle" ? " pe-mm-" + n.data("state") : "");
      bar.setAttribute("data-id", n.id());
      bar.style.left = (self._mm.pad + (n.position().x - halfW - minX) * scale) + "px";
      bar.style.top = (self._mm.pad + (n.position().y - halfH - minY) * scale) + "px";
      bar.style.width = Math.max(8, self.tokens.cardW * scale) + "px";
      bar.style.height = Math.max(5, self.tokens.cardH * scale) + "px";
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
        bars[i].className = "pe-mm-node" + (state && state !== "idle" ? " pe-mm-" + state : "");
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

  /* The card's output fact (080 §A line 3): short — `tempdb.trips`, `→ caller`,
   * `ds.table`. The dock's Details pane carries the long form (init.js outputText). */
  function outputFact(n) {
    var type = (n.type || "").toUpperCase();
    if (type === "CALCULATOR") return null;
    if (!n.output) return type === "DQL" ? "→ caller" : "side effect";
    var o = n.output;
    if (o.target === "caller") return "→ caller";
    if (o.target === "tempdb") return "tempdb." + (o.table || "—");
    if (o.target === "datasource") return (o.datasource || "—") + "." + (o.table || "—");
    return "side effect";
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
      // what does it leave behind.
      data.facts.push({ kind: "source", icon: "calculator", text: (n.kind || "?") + " → " + (n.context_key || "?") });
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
    var out = outputFact(n);
    if (out) data.facts.push({ kind: "output", icon: "table", text: out });
    return data;
  }

  function buildElements(nodes, settings) {
    var elements = [];
    var i, j;

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
          elements.push({
            group: "edges",
            data: {
              id: deps[j] + "->" + nodes[i].id,
              source: deps[j],
              target: nodes[i].id,
            },
          });
        }
      }
    }

    return elements;
  }

  PipelineGraph.prototype.buildElements = function () {
    var settings = this.editor && this.editor.pipeline ? this.editor.pipeline.settings : null;
    return buildElements(this.nodes, settings);
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
    // Edge state follows the TARGET's state (080 §A): the curve into a running node
    // flows, into a done node it rests in --edge-done. Failure clears the flow.
    var incomers = node.incomers("edge");
    if (state === "running") {
      incomers.forEach(function (e) { e.addClass("active"); });
      this.ensureFlow();
    } else if (state === "success") {
      incomers.forEach(function (e) { e.removeClass("active"); e.addClass("done"); });
    } else if (state === "failed" || state === "aborted") {
      incomers.forEach(function (e) { e.removeClass("active"); });
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
  };

  /**
   * The run line (080 §A footer): `node_completed` carries FLAT `duration_ms` /
   * `rows_out` (SseEventProjection), plus `context_value` for a CALCULATOR — one data
   * write the html-label template re-renders on. The same completion labels the
   * OUTGOING edges with the count flowing out of this node (the class-gated,
   * owner-undecided row labels).
   */
  PipelineGraph.prototype.setNodeStats = function (nodeId, stats) {
    var node = this.findNode(nodeId);
    if (!node) return;
    node.data("run", formatRunLine(stats));
    var r = Number(stats && stats.rows_out);
    if (isFinite(r) && r >= 0) {
      var label = r.toLocaleString("en-US") + " rows";
      node.outgoers("edge").forEach(function (e) {
        e.data("rowLabel", label);
        e.addClass("rows");
      });
    }
  };

  /** Reduced motion cannot be read from CSS here — the graph is canvas, not DOM. */
  function pulseEnabled(mql) {
    return !(mql && mql.matches);
  }

  /**
   * The active-edge `flow` (080 §A): canvas has no keyframes, so while ANY edge is
   * active a rAF loop steps every active edge's line-dash-offset. It stops itself
   * the moment no edge is active; under prefers-reduced-motion the dashes stand
   * still (the class's dash pattern still reads as "in flight").
   */
  PipelineGraph.prototype.ensureFlow = function () {
    var self = this;
    if (self._flowRunning || !self.cy) return;
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") return;
    if (!pulseEnabled(window.matchMedia("(prefers-reduced-motion: reduce)"))) return;
    if (typeof requestAnimationFrame !== "function") return;
    self._flowRunning = true;
    var step = function () {
      if (!self.cy) { self._flowRunning = false; return; }
      var edges = self.cy.edges(".active");
      if (!edges.length) { self._flowRunning = false; return; }
      edges.forEach(function (e) {
        var off = parseFloat(e.style("line-dash-offset")) || 0;
        e.style("line-dash-offset", off - 1);
      });
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  };

  PipelineGraph.prototype.setEdgeActive = function (sourceId, targetId, active) {
    if (!this.cy) return;
    var edge = this.cy.edges("[source = '" + sourceId + "'][target = '" + targetId + "']");
    if (!edge.length) return;
    if (active) {
      edge.addClass("active");
      this.ensureFlow();
    } else {
      edge.removeClass("active");
    }
  };

  PipelineGraph.prototype.resetAll = function () {
    var self = this;
    if (!self.cy) return;
    self.cy.nodes().forEach(function (node) {
      NODE_STATES.forEach(function (s) { node.removeClass(s); });
      node.addClass("idle");
      node.data("state", "idle");
      node.data("run", null);
      if (self.editor && self.editor.nodeStates) self.editor.nodeStates[node.id()] = "idle";
      self.updateMinimapNode(node.id(), "idle");
      if (typeof window !== "undefined" && window.a11yNodeState) window.a11yNodeState(node.id(), "idle");
    });
    self.cy.edges().forEach(function (edge) {
      edge.removeClass("active");
      edge.removeClass("done");
      edge.removeClass("rows");
      edge.data("rowLabel", "");
    });
  };

  PipelineGraph.prototype.setEdgesToNodeActive = function (nodeId, active) {
    if (!this.cy) return;
    var node = this.cy.getElementById(nodeId);
    if (!node.length) return;
    var incomers = node.incomers("edge");
    var self = this;
    incomers.forEach(function (edge) {
      if (active) {
        edge.addClass("active");
      } else {
        edge.removeClass("active");
      }
    });
    if (active) self.ensureFlow();
  };

  PipelineGraph.prototype.setEdgesFromNodeActive = function (nodeId, active) {
    if (!this.cy) return;
    var node = this.cy.getElementById(nodeId);
    if (!node.length) return;
    var outgoers = node.outgoers("edge");
    var self = this;
    outgoers.forEach(function (edge) {
      if (active) {
        edge.addClass("active");
      } else {
        edge.removeClass("active");
      }
    });
    if (active) self.ensureFlow();
  };

  PipelineGraph.prototype.updateTheme = function () {
    this.tokens = readDesignTokens();
    if (this.cy) {
      this.cy.style(buildStylesheet(this.tokens));
    }
  };

  var api = {
    PipelineGraph: PipelineGraph,
    buildStylesheet: buildStylesheet,
    buildElements: buildElements,
    buildCardHtml: buildCardHtml,
    readDesignTokens: readDesignTokens,
    layoutOptions: layoutOptions,
    pulseEnabled: pulseEnabled,
    clampFitZoom: clampFitZoom,
    formatRunLine: formatRunLine,
    truncateLeft: truncateLeft,
    templateLine: templateLine,
    edgeControlPoints: edgeControlPoints,
    iconForType: iconForType,
    typeToken: typeToken,
    escapeHtml: escapeHtml,
    FIT_MIN_ZOOM: FIT_MIN_ZOOM,
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
