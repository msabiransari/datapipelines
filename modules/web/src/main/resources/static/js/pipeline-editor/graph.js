(function () {
  "use strict";

  var NODE_STATES = ["idle", "running", "success", "failed", "aborted"];

  /* Type glyphs (059 §reference): TYPE is an ICON on a rectangular card, not a shape —
   * the per-type shapes (round-diamond / round-tag / hexagon) are retired with the
   * label-below contract. Engine glyphs are GENERIC (database / file), never a vendor
   * logo — trademarks stay out of the repo. */
  var TYPE_ICONS = { DQL: "db", DML: "table", DDL: "boxes", PIPELINE: "workflow" };
  var FILE_EMBEDDED_DIALECTS = ["SQLITE", "DUCKDB"];

  function iconForType(type) {
    return TYPE_ICONS[String(type || "").toUpperCase()] || "db";
  }

  function iconForDialect(dialect) {
    return FILE_EMBEDDED_DIALECTS.indexOf(String(dialect || "").toUpperCase()) >= 0 ? "file" : "db";
  }

  function readDesignTokens() {
    var styles = getComputedStyle(document.documentElement);
    // The card's geometry rides the same custom-property bridge as the colours: the
    // HTML card (pipeline-editor.css) and the Cytoscape node box MUST agree on a box,
    // and one token read is the only way they do. Numeric fallbacks match the CSS.
    var cardW = parseInt(styles.getPropertyValue("--pe-card-w"), 10) || 264;
    var cardH = parseInt(styles.getPropertyValue("--pe-card-h"), 10) || 164;
    var cardRadius = styles.getPropertyValue("--radius-md").trim() || "8px";
    return {
      edgeActiveStroke: styles.getPropertyValue("--edge-active-stroke").trim() || "#2563eb",
      edgeIdleStroke: styles.getPropertyValue("--edge-idle-stroke").trim() || "#6b7280",
      // Node cards (pipeline-editor.md §5.3): every key falls back to a hard hex so a
      // stale theme file cannot blank the graph. The success/failed/aborted accents
      // fall back to the banner's --node-*-bg tokens (pipeline-editor.css), so a theme
      // that overrides those keeps banner and graph on the same hue.
      nodeSurface: styles.getPropertyValue("--node-surface").trim() || "#f9fafb",
      nodeBorder: styles.getPropertyValue("--node-border").trim() || "#d1d5db",
      nodeLabelText: styles.getPropertyValue("--node-label-text").trim() || "#111827",
      nodeSelectedRing: styles.getPropertyValue("--node-selected-ring").trim() || "#2563eb",
      nodeSelectedHalo: styles.getPropertyValue("--node-selected-halo").trim() || "#2563eb",
      nodeRunningAccent: styles.getPropertyValue("--node-running-accent").trim() ||
        styles.getPropertyValue("--node-running-bg").trim() || "#2563eb",
      nodeSuccessAccent: styles.getPropertyValue("--node-success-accent").trim() ||
        styles.getPropertyValue("--node-success-bg").trim() || "#16a34a",
      nodeFailedAccent: styles.getPropertyValue("--node-failed-accent").trim() ||
        styles.getPropertyValue("--node-failed-bg").trim() || "#dc2626",
      nodeAbortedAccent: styles.getPropertyValue("--node-aborted-accent").trim() ||
        styles.getPropertyValue("--node-aborted-bg").trim() || "#f59e0b",
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
    var cardW = tokens.cardW || 264;
    var cardH = tokens.cardH || 164;
    var sourcePort = cardW / 2 + "px 0px";
    var targetPort = -cardW / 2 + "px 0px";
    return [
      {
        // Node card (pipeline-editor.md §5.3, revised 2026-09-02): a rectangular card
        // whose TEXT is an HTML overlay (cytoscape-node-html-label, §reference route 1)
        // — Cytoscape text cannot carry icons, per-line styling or the run line. The
        // canvas box still paints the chrome: surface, muted border, state accent,
        // caller double border, selection ring — one theme-swap path (updateTheme),
        // and the overlay stays transparent so the chrome shows through.
        selector: "node",
        style: {
          "background-color": tokens.nodeSurface,
          color: tokens.nodeLabelText,
          width: cardW,
          height: cardH,
          shape: "round-rectangle",
          "border-width": 1,
          "border-color": tokens.nodeBorder,
          "corner-radius": tokens.cardRadius || "8px",
        },
      },
      {
        // Execution states (§6.2) are ACCENTS on the card border, never a repaint of
        // the card — colour carries state alone, so it never fights type (the icon)
        // or selection (ring) for the same pixels.
        selector: "node.running",
        style: {
          "border-color": tokens.nodeRunningAccent,
          "border-width": 2,
        },
      },
      {
        selector: "node.success",
        style: {
          "border-color": tokens.nodeSuccessAccent,
          "border-width": 2,
        },
      },
      {
        selector: "node.failed",
        style: {
          "border-color": tokens.nodeFailedAccent,
          "border-width": 2,
        },
      },
      {
        // §6.2: aborted reads at 0.5 opacity.
        selector: "node.aborted",
        style: {
          "border-color": tokens.nodeAbortedAccent,
          "border-width": 2,
          opacity: 0.5,
        },
      },
      {
        // §5.3 / pipeline-contract §9: the caller (result) node marker. Ordered AFTER
        // the state accents so the double border survives a state change — the accent
        // colour still shows through, on the double border itself.
        selector: "node.caller",
        style: {
          "border-style": "double",
          "border-width": 5,
        },
      },
      {
        // §5.3 required a selected style and the code never had one. The pseudo-class,
        // not a .selected class — a11y.js already drives cyNode.select(). Ring +
        // underlay halo: the ring reads at any zoom, the halo survives a state accent
        // on the same border. underlay-* paints BEHIND the node; an overlay would dim
        // the card. Ordered last so selection wins the border while it holds.
        selector: "node:selected",
        style: {
          "border-width": 3,
          "border-color": tokens.nodeSelectedRing,
          "underlay-color": tokens.nodeSelectedHalo,
          "underlay-opacity": 0.18,
          "underlay-padding": 6,
        },
      },
      {
        // Edges (059 §reference): unbundled-bezier whose per-edge control points are
        // computed once after layout (applyEdgeCurves) so the curve LEAVES the source
        // port horizontally and ENTERS the target port horizontally. Small arrowhead.
        selector: "edge",
        style: {
          "line-color": tokens.edgeIdleStroke,
          "target-arrow-color": tokens.edgeIdleStroke,
          "target-arrow-shape": "triangle",
          "arrow-scale": 0.9,
          "curve-style": "unbundled-bezier",
          "source-endpoint": sourcePort,
          "target-endpoint": targetPort,
          width: 1.5,
        },
      },
      {
        selector: "edge.active",
        style: {
          "line-color": tokens.edgeActiveStroke,
          "target-arrow-color": tokens.edgeActiveStroke,
          width: 2.5,
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
    if (leaf.length + 1 <= limit) return "\u2026/" + leaf;
    return "\u2026" + leaf.slice(-(limit - 1));
  }

  /** The card's line 4: `sample_trips_daily.sql @ v1`, path truncated from the LEFT. */
  function templateLine(t) {
    if (!t || !t.id) return "";
    return t.version ? truncateLeft(t.id) + " @ v" + t.version : truncateLeft(t.id);
  }

  /**
   * The card's line 5, from `node_completed`'s flat stats fields (`duration_ms`,
   * `rows_out`; NOT_MEASURED is -1). Absent before any execution and when the wire
   * carried nothing — never a placeholder.
   */
  function formatRunLine(stats) {
    if (!stats) return null;
    var out = "";
    var d = Number(stats.duration_ms);
    if (isFinite(d) && d >= 0) {
      if (d < 1000) out = Math.round(d) + " ms";
      else if (d < 60000) out = (d / 1000).toFixed(1) + " s";
      else out = Math.floor(d / 60000) + "m " + Math.round((d % 60000) / 1000) + "s";
    }
    var r = Number(stats.rows_out);
    if (isFinite(r) && r >= 0) out += (out ? " \u00b7 " : "") + r + " rows";
    return out || null;
  }

  var STATE_DOT_ICONS = { success: "check", failed: "x", aborted: "minus" };

  /**
   * The HTML card (059 §A), top to bottom: name (title, two lines then ellipsis),
   * type badge + type icon, datasource · dialect (or the child pipeline's name),
   * template@version, run line (absent until a completion carries stats). Ports sit
   * on the card's right/left edges; the state dot (✓ / ✕ / spinner / –) makes a
   * static screenshot read without the legend, beside §6.2's accent border which the
   * canvas paints under the transparent overlay. Pure: node --test drives it with a
   * data snapshot — everything dynamic arrives as `data`, written by
   * buildElements/setNodeState/setNodeStats/applyDialects.
   */
  function buildCardHtml(data) {
    if (!data) return "";
    var name = data.label != null ? data.label : data.id;
    var type = String(data.type || "").toUpperCase();
    var esc = escapeHtml;
    var h = '<div class="pe-card" data-node-id="' + esc(data.id) + '">';

    h += '<div class="pe-card-title" title="' + esc(name) + '">' + esc(name) + "</div>";

    h +=
      '<div class="pe-card-head"><span class="pe-card-badge">' + esc(type || "NODE") + "</span>" +
      iconSvg(data.typeIcon || iconForType(type), "ds-icon-xs pe-card-type-icon") +
      "</div>";

    if (data.sourceLabel) {
      h +=
        '<div class="pe-card-line pe-card-source">' +
        (data.engineIcon ? iconSvg(data.engineIcon, "ds-icon-xs pe-card-engine-icon") : "") +
        "<span>" + esc(data.sourceLabel) + "</span></div>";
    }

    var tpl = templateLine(data.template);
    if (tpl) {
      h +=
        '<div class="pe-card-line pe-card-template" title="' +
        esc(data.template && data.template.id ? data.template.id + (data.template.version ? " @ v" + data.template.version : "") : "") +
        '"><span>' + esc(tpl) + "</span></div>";
    }

    if (data.run) {
      h += '<div class="pe-card-run">' + esc(data.run) + "</div>";
    }

    var dot = STATE_DOT_ICONS[data.state];
    if (dot) {
      h += '<span class="pe-card-dot pe-card-dot-' + esc(data.state) + '">' + iconSvg(dot, "ds-icon-xs") + "</span>";
    } else if (data.state === "running") {
      h += '<span class="pe-card-dot pe-card-dot-running" aria-hidden="true"></span>';
    }

    h += '<span class="pe-card-port pe-card-port-in" aria-hidden="true"></span>';
    h += '<span class="pe-card-port pe-card-port-out" aria-hidden="true"></span>';
    h += "</div>";
    return h;
  }

  function PipelineGraph(containerId, nodes, editor) {
    this.containerId = containerId;
    this.nodes = nodes;
    this.editor = editor;
    this.cy = null;
    this.tokens = readDesignTokens();
  }

  // dagre layout options (pipeline-editor.md §5.1/§5.2), retuned for CARDS (059 §B):
  // a 264px-wide card needs wider ranks than a 120×44 box ever did, and generous
  // node separation is what keeps the reference's "breathing room". padding is the
  // edge clearance; fit runs once in fitToView() so the readable-minimum clamp can
  // apply after it.
  function layoutOptions() {
    return {
      name: "dagre",
      rankDir: "LR",
      nodeSep: 64,
      rankSep: 176,
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

    // Layout runs EXPLICITLY (not via the cytoscape config) so the layoutstop binding
    // is guaranteed to precede it — cy.ready can fire before dagre has applied
    // positions. One pass after layout: the n8n-style edge curve (horizontal
    // leave/enter) and the fit-with-floor. The curve math is idempotent given the
    // same positions.
    var layout = this.cy.elements().layout(layoutOptions());
    layout.one("layoutstop", function () {
      self.applyEdgeCurves();
      self.fitToView();
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

  /**
   * Datasource dialects (059 §A line 3) live in the registry, not the pipeline body —
   * the body is portable across environments (contract §11.1), so the card resolves
   * `sample-trips · POSTGRES` client-side from the workspace's datasource listing and
   * upgrades the line when it lands. A failure degrades to the bare source name: the
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
        n.data("sourceLabel", name + " \u00b7 " + String(dialect).toUpperCase());
        n.data("engineIcon", iconForDialect(dialect));
      }
    });
  };

  /**
   * The n8n edge curve: two unbundled-bezier control points, one extending
   * horizontally from the source port, one back from the target port. Cytoscape 3.34
   * has no `control-point-positions` (verified against the vendored source), so the
   * (weight, distance) form is computed per edge from post-layout positions.
   */
  function edgeControlPoints(sx, sy, tx, ty) {
    var dx = tx - sx;
    var dy = ty - sy;
    var len2 = dx * dx + dy * dy;
    if (len2 <= 0) return null;
    var len = Math.sqrt(len2);
    var k = Math.min(72, Math.abs(dx) * 0.45);
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
  // "Three nodes should fill the pane, not sit in one corner" (059 §B): fit may not
  // zoom below this floor. Above it, fit behaves normally; the maxZoom (3) caps the
  // other end.
  var FIT_MIN_ZOOM = 0.75;

  PipelineGraph.prototype.fitToView = function () {
    if (!this.cy) return;
    this.cy.fit(undefined, FIT_PADDING);
    if (this.cy.zoom() < FIT_MIN_ZOOM) {
      this.cy.zoom(FIT_MIN_ZOOM);
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

  // Class emission (pipeline-editor.md §5.1): `idle` is explicit so setNodeState()'s
  // removeClass of all five states stays symmetric; TYPE is a class per node type;
  // `caller` marks the result node, mirroring the server's Node.isCallerNode (which has
  // no type guard): an explicit output.target "caller" on ANY type — contract §4.9
  // permits a standard §4.7 output block on a PIPELINE node — or a DQL node with the
  // output block omitted (the D1 default; DML/DDL forbid the block, so the DQL guard on
  // the omitted arm is load-bearing — without it every DML/DDL node would mark caller).
  function nodeClasses(n) {
    var classes = ["idle"];
    var type = (n.type || "").toUpperCase();
    if (type === "PIPELINE") {
      classes.push("pipeline-node");
    } else if (type === "DQL" || type === "DML" || type === "DDL") {
      classes.push("type-" + type.toLowerCase());
    }
    var caller =
      (n.output && n.output.target === "caller") || (type === "DQL" && !n.output);
    if (caller) classes.push("caller");
    return classes.join(" ");
  }

  /**
   * Node data for the card: `state` and `run` are WRITTEN (never derived later)
   * because the html-label template re-runs on `data` events and receives only this
   * snapshot — the canvas classes stay the machine the a11y sweep and the stylesheet
   * read, and this data keeps the HTML card in lockstep with them.
   */
  function nodeCardData(n, settings) {
    var type = (n.type || "").toUpperCase();
    var data = {
      id: n.id,
      label: n.display_name || n.name || n.id,
      type: type,
      typeIcon: iconForType(type),
      state: "idle",
      run: null,
      template: n.template || null,
    };
    if (type === "PIPELINE") {
      data.sourceLabel = n.pipeline && n.pipeline.name ? n.pipeline.name : "pipeline";
      data.engineIcon = null;
    } else if (n.source === "tempdb") {
      var engine =
        settings && settings.tempdb && settings.tempdb.engine ? settings.tempdb.engine : "H2";
      data.sourceLabel = "tempdb \u00b7 " + engine;
      data.sourceName = null;
      data.engineIcon = "db";
    } else if (n.source) {
      data.sourceLabel = n.source;
      data.sourceName = n.source;
      data.engineIcon = "db";
    }
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
    // not classes) — writing it here is what re-renders the card's status dot.
    node.data("state", state);
    if (state === "running") this.startPulse(node);
    // Keep the editor's nodeStates mirror complete. sse.js only writes entries for
    // nodes that START, and its execution_aborted sweep falls back to a classes-string
    // compare for the rest — a string the type classes ("idle type-dml") no longer
    // equal. Writing every transition here keeps pending nodes marked "idle", which
    // is what that sweep checks for.
    if (this.editor && this.editor.nodeStates) this.editor.nodeStates[nodeId] = state;
    // Mirror execution state to the a11y node list (a11y.js owns the DOM; the call
    // is guarded so the pure module stays loadable under node --test).
    if (typeof window !== "undefined" && window.a11yNodeState) window.a11yNodeState(nodeId, state);
  };

  /**
   * The run line (059 §A line 5): `node_completed` carries FLAT `duration_ms` /
   * `rows_out` (SseEventProjection), and this is the whole trip from wire to card —
   * one data write the html-label template re-renders on.
   */
  PipelineGraph.prototype.setNodeStats = function (nodeId, stats) {
    var node = this.findNode(nodeId);
    if (!node) return;
    node.data("run", formatRunLine(stats));
  };

  /** Reduced motion cannot be read from CSS here — the graph is canvas, not DOM. */
  function pulseEnabled(mql) {
    return !(mql && mql.matches);
  }

  // A Cytoscape stylesheet has no keyframes, so the running pulse is a JS-driven
  // border-width loop. It stops itself the moment the node leaves `running`; the
  // still fallback under reduced motion is the accent border the class already sets.
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

  PipelineGraph.prototype.setEdgeActive = function (sourceId, targetId, active) {
    if (!this.cy) return;
    var edge = this.cy.edges("[source = '" + sourceId + "'][target = '" + targetId + "']");
    if (!edge.length) return;
    if (active) {
      edge.addClass("active");
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
      if (typeof window !== "undefined" && window.a11yNodeState) window.a11yNodeState(node.id(), "idle");
    });
    self.cy.edges().forEach(function (edge) {
      edge.removeClass("active");
    });
  };

  PipelineGraph.prototype.setEdgesToNodeActive = function (nodeId, active) {
    if (!this.cy) return;
    var node = this.cy.getElementById(nodeId);
    if (!node.length) return;
    var incomers = node.incomers("edge");
    incomers.forEach(function (edge) {
      if (active) {
        edge.addClass("active");
      } else {
        edge.removeClass("active");
      }
    });
  };

  PipelineGraph.prototype.setEdgesFromNodeActive = function (nodeId, active) {
    if (!this.cy) return;
    var node = this.cy.getElementById(nodeId);
    if (!node.length) return;
    var outgoers = node.outgoers("edge");
    outgoers.forEach(function (edge) {
      if (active) {
        edge.addClass("active");
      } else {
        edge.removeClass("active");
      }
    });
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
    formatRunLine: formatRunLine,
    truncateLeft: truncateLeft,
    templateLine: templateLine,
    edgeControlPoints: edgeControlPoints,
    iconForType: iconForType,
    iconForDialect: iconForDialect,
    escapeHtml: escapeHtml,
    FIT_MIN_ZOOM: FIT_MIN_ZOOM,
  };
  // node --test requires this file directly (the 027b harness); the browser keeps
  // the global the editor's other modules already reference.
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PipelineGraph = PipelineGraph;
})();
