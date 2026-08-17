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
    };
  }

  function buildStylesheet(tokens) {
    return [
      {
        selector: "node",
        style: {
          "background-color": tokens.nodeIdleBg,
          color: tokens.nodeIdleText,
          label: "data(label)",
          "text-valign": "center",
          "text-halign": "center",
          "font-size": "11px",
          "text-wrap": "wrap",
          "text-max-width": "100px",
          width: 80,
          height: 40,
          shape: "round-rectangle",
          "border-width": 2,
          "border-color": tokens.edgeIdleStroke,
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

  PipelineGraph.prototype.buildElements = function () {
    var elements = [];
    var nodeIds = new Set();
    var i, j;

    for (i = 0; i < this.nodes.length; i++) {
      var n = this.nodes[i];
      var label = (n.display_name || n.name || n.id);
      if (label.length > 20) label = label.substring(0, 18) + "...";
      var isPipelineNode = n.type === "PIPELINE";
      elements.push({
        group: "nodes",
        data: {
          id: n.id,
          label: label,
        },
        classes: isPipelineNode ? "idle pipeline-node" : "idle",
      });
      nodeIds.add(n.id);
    }

    for (i = 0; i < this.nodes.length; i++) {
      var deps = this.nodes[i].depends_on;
      if (deps && deps.length) {
        for (j = 0; j < deps.length; j++) {
          elements.push({
            group: "edges",
            data: {
              id: deps[j] + "->" + this.nodes[i].id,
              source: deps[j],
              target: this.nodes[i].id,
            },
          });
        }
      }
    }

    return elements;
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

  window.PipelineGraph = PipelineGraph;
})();
