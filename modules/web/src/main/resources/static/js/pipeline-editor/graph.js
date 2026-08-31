(function () {
  "use strict";

  var NODE_STATES = ["idle", "running", "success", "failed", "aborted"];

  function readDesignTokens() {
    var styles = getComputedStyle(document.documentElement);
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
      nodeRunningAccent: styles.getPropertyValue("--node-running-accent").trim() || "#2563eb",
      nodeSuccessAccent: styles.getPropertyValue("--node-success-accent").trim() ||
        styles.getPropertyValue("--node-success-bg").trim() || "#16a34a",
      nodeFailedAccent: styles.getPropertyValue("--node-failed-accent").trim() ||
        styles.getPropertyValue("--node-failed-bg").trim() || "#dc2626",
      nodeAbortedAccent: styles.getPropertyValue("--node-aborted-accent").trim() ||
        styles.getPropertyValue("--node-aborted-bg").trim() || "#f59e0b",
    };
  }

  function buildStylesheet(tokens) {
    return [
      {
        // Node card (pipeline-editor.md §5.3): a neutral surface with the label BELOW
        // the shape — TYPE is carried by shape, STATE by an accent border (§6.2),
        // SELECTION by the ring (node:selected below). Truncation is a stylesheet
        // concern now (text-max-width + ellipsis), not a buildElements one.
        selector: "node",
        style: {
          "background-color": tokens.nodeSurface,
          color: tokens.nodeLabelText,
          label: "data(label)",
          "text-valign": "bottom",
          "text-halign": "center",
          "text-margin-y": 8,
          "font-size": "12px",
          "text-wrap": "ellipsis",
          "text-max-width": "160px",
          width: 120,
          height: 44,
          shape: "round-rectangle",
          "border-width": 1,
          "border-color": tokens.nodeBorder,
        },
      },
      {
        // §5.3 per-type shapes: DML is a side-effect, DDL a schema change.
        selector: "node.type-dml",
        style: { shape: "round-diamond" },
      },
      {
        selector: "node.type-ddl",
        style: { shape: "round-tag" },
      },
      {
        // Execution states (§6.2) are ACCENTS on the card border, never a repaint of
        // the card — colour carries state alone, so it never fights type (shape) or
        // selection (ring) for the same pixels.
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
        // Composition (design §7): a PIPELINE node is a distinct kind — it runs another
        // pipeline as a child execution. Shape only; state colors still apply on top.
        selector: "node.pipeline-node",
        style: {
          shape: "hexagon",
        },
      },
      {
        // §5.3 required a selected style and the code never had one. The pseudo-class,
        // not a .selected class — a11y.js already drives cyNode.select(). Ring +
        // underlay halo: the ring reads at any zoom, the halo survives a state accent
        // on the same border. underlay-* paints BEHIND the node; an overlay would dim
        // the label. Ordered last so selection wins the border while it holds.
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
        selector: "edge",
        style: {
          "line-color": tokens.edgeIdleStroke,
          "target-arrow-color": tokens.edgeIdleStroke,
          "target-arrow-shape": "triangle",
          "arrow-scale": 1.2,
          "curve-style": "bezier",
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
    ];
  }

  function PipelineGraph(containerId, nodes, editor) {
    this.containerId = containerId;
    this.nodes = nodes;
    this.editor = editor;
    this.cy = null;
    this.tokens = readDesignTokens();
  }

  // dagre layout options (pipeline-editor.md §5.1/§5.2). nodeDimensionsIncludeLabels
  // is MANDATORY with the label below the shape: at its default false, dagre lays out
  // on the node box alone and one rank's labels collide with the next. marginX/marginY
  // do not exist here (they are grid/cose options) — padding is the edge clearance, and
  // fit defaults to true, so no manual cy.fit().
  function layoutOptions() {
    return {
      name: "dagre",
      rankDir: "LR",
      nodeSep: 50,
      rankSep: 100,
      edgeSep: 12,
      padding: 30,
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
      layout: layoutOptions(),
      wheelSensitivity: 0.3,
      minZoom: 0.2,
      maxZoom: 3,
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
  };

  // Class emission (pipeline-editor.md §5.1): `idle` is explicit so setNodeState()'s
  // removeClass of all five states stays symmetric; TYPE is a class per node type;
  // `caller` marks the result node — omitted output means caller (pipeline-contract
  // §4.7/§9) and only DQL nodes can resolve to it (DML/DDL forbid an output block).
  function nodeClasses(n) {
    var classes = ["idle"];
    var type = (n.type || "").toUpperCase();
    if (type === "PIPELINE") {
      classes.push("pipeline-node");
    } else if (type === "DQL" || type === "DML" || type === "DDL") {
      classes.push("type-" + type.toLowerCase());
    }
    if (type === "DQL" && (!n.output || n.output.target === "caller")) {
      classes.push("caller");
    }
    return classes.join(" ");
  }

  function buildElements(nodes) {
    var elements = [];
    var i, j;

    for (i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      elements.push({
        group: "nodes",
        data: {
          id: n.id,
          label: (n.display_name || n.name || n.id),
        },
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
    return buildElements(this.nodes);
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
    if (state === "running") this.startPulse(node);
    // Mirror execution state to the a11y node list (a11y.js owns the DOM; the call
    // is guarded so the pure module stays loadable under node --test).
    if (typeof window !== "undefined" && window.a11yNodeState) window.a11yNodeState(nodeId, state);
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
    readDesignTokens: readDesignTokens,
    layoutOptions: layoutOptions,
    pulseEnabled: pulseEnabled,
  };
  // node --test requires this file directly (the 027b harness); the browser keeps
  // the global the editor's other modules already reference.
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PipelineGraph = PipelineGraph;
})();
