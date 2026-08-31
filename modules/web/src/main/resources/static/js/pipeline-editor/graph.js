(function () {
  "use strict";

  var NODE_STATES = ["idle", "running", "success", "failed", "aborted"];

  function readDesignTokens() {
    var styles = getComputedStyle(document.documentElement);
    return {
      nodeIdleBg: styles.getPropertyValue("--node-idle-bg").trim() || "#e5e7eb",
      nodeIdleText: styles.getPropertyValue("--node-idle-text").trim() || "#111827",
      nodeRunningBg: styles.getPropertyValue("--node-running-bg").trim() || "#2563eb",
      nodeRunningText: styles.getPropertyValue("--node-running-text").trim() || "#fff",
      nodeSuccessBg: styles.getPropertyValue("--node-success-bg").trim() || "#16a34a",
      nodeSuccessText: styles.getPropertyValue("--node-success-text").trim() || "#fff",
      nodeFailedBg: styles.getPropertyValue("--node-failed-bg").trim() || "#dc2626",
      nodeFailedText: styles.getPropertyValue("--node-failed-text").trim() || "#fff",
      nodeAbortedBg: styles.getPropertyValue("--node-aborted-bg").trim() || "#f59e0b",
      nodeAbortedText: styles.getPropertyValue("--node-aborted-text").trim() || "#000",
      edgeActiveStroke: styles.getPropertyValue("--edge-active-stroke").trim() || "#2563eb",
      edgeIdleStroke: styles.getPropertyValue("--edge-idle-stroke").trim() || "#6b7280",
      // Node cards (pipeline-editor.md §5.3): each new key falls back to the token it
      // supersedes, so a stale theme file cannot blank the graph.
      nodeSurface: styles.getPropertyValue("--node-surface").trim() ||
        styles.getPropertyValue("--node-idle-bg").trim() || "#f9fafb",
      nodeBorder: styles.getPropertyValue("--node-border").trim() || "#d1d5db",
      nodeLabelText: styles.getPropertyValue("--node-label-text").trim() ||
        styles.getPropertyValue("--node-idle-text").trim() || "#111827",
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
        // §5.3 / pipeline-contract §9: the caller (result) node marker.
        selector: "node.caller",
        style: {
          "border-style": "double",
          "border-width": 5,
        },
      },
      {
        selector: "node.running",
        style: {
          "background-color": tokens.nodeRunningBg,
          color: tokens.nodeRunningText,
          "border-color": tokens.nodeRunningBg,
        },
      },
      {
        selector: "node.success",
        style: {
          "background-color": tokens.nodeSuccessBg,
          color: tokens.nodeSuccessText,
          "border-color": tokens.nodeSuccessBg,
        },
      },
      {
        selector: "node.failed",
        style: {
          "background-color": tokens.nodeFailedBg,
          color: tokens.nodeFailedText,
          "border-color": tokens.nodeFailedBg,
        },
      },
      {
        selector: "node.aborted",
        style: {
          "background-color": tokens.nodeAbortedBg,
          color: tokens.nodeAbortedText,
          "border-color": tokens.nodeAbortedBg,
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
        selector: "edge",
        style: {
          "line-color": tokens.edgeIdleStroke,
          "target-arrow-color": tokens.edgeIdleStroke,
          "target-arrow-shape": "triangle",
          "curve-style": "bezier",
          width: 2,
        },
      },
      {
        selector: "edge.active",
        style: {
          "line-color": tokens.edgeActiveStroke,
          "target-arrow-color": tokens.edgeActiveStroke,
          width: 3,
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

  PipelineGraph.prototype.render = function () {
    var self = this;
    var elements = this.buildElements();

    this.cy = cytoscape({
      container: document.getElementById(this.containerId),
      elements: elements,
      style: buildStylesheet(this.tokens),
      layout: { name: "dagre", rankDir: "LR" },
      wheelSensitivity: 0.3,
      minZoom: 0.2,
      maxZoom: 3,
    });
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
  };
  // node --test requires this file directly (the 027b harness); the browser keeps
  // the global the editor's other modules already reference.
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PipelineGraph = PipelineGraph;
})();
