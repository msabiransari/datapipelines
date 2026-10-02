(function () {
  "use strict";

  /*
   * The Parameter Sets workspace's STATIC dependency graph (#374, workspace spec §6.2): one node per parameter, an
   * edge per `depends_on`, the badge = the parameter's source kind. Cytoscape + dagre, laid out left to right.
   * Static means the graph is the set's STRUCTURE — it does not animate evaluation (the live, streamed node states
   * are #383's, on the seam initParameters leaves).
   *
   * Every colour comes from a design token resolved through a probe element (the editor graph's measured recipe,
   * pipeline-editor/graph.js: `getComputedStyle(...).getPropertyValue('--x')` returns a colour-mix() token's TEXT,
   * which Cytoscape cannot parse). Names are DATA: an id is `getElementById`'s argument, never a selector string.
   */

  var TOKENS = {
    surface: ["--surface-raised", "--surface-default"],
    border: ["--border-subtle"],
    text: ["--text-primary"],
    muted: ["--text-secondary"],
    edge: ["--edge", "--border-default"],
    accent: ["--brand", "--accent-primary"],
    accentSoft: ["--brand-soft", "--surface-selected"],
    dependent: ["--accent-warning"],
    dependency: ["--accent-info"],
  };

  function toLegacyRgb(computed) {
    if (!computed) return null;
    var value = String(computed).trim();
    if (value.indexOf("rgb") === 0 || value.charAt(0) === "#") return value;
    var srgb = /^color\(\s*srgb\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s*(?:\/\s*([0-9.]+)\s*)?\)$/.exec(value);
    if (!srgb) return null;
    var byte = function (x) {
      return Math.max(0, Math.min(255, Math.round(parseFloat(x) * 255)));
    };
    var rgb = byte(srgb[1]) + ", " + byte(srgb[2]) + ", " + byte(srgb[3]);
    var alpha = srgb[4] === undefined ? 1 : parseFloat(srgb[4]);
    return alpha >= 1 ? "rgb(" + rgb + ")" : "rgba(" + rgb + ", " + alpha + ")";
  }

  /** One FRESH probe per read (093's lesson: a reused probe answers the first colour it was asked for). */
  function resolveToken(names) {
    var styles = getComputedStyle(document.documentElement);
    for (var i = 0; i < names.length; i++) {
      if (!styles.getPropertyValue(names[i]).trim()) continue;
      var probe = document.createElement("span");
      probe.setAttribute("aria-hidden", "true");
      probe.style.position = "absolute";
      probe.style.visibility = "hidden";
      probe.style.transition = "none";
      probe.style.color = "var(" + names[i] + ")";
      document.body.appendChild(probe);
      var computed = getComputedStyle(probe).color;
      probe.parentNode.removeChild(probe);
      var converted = toLegacyRgb(computed);
      if (converted) return converted;
    }
    return null;
  }

  function readTokens() {
    var out = {};
    Object.keys(TOKENS).forEach(function (key) {
      // A token that is not declared at all yields the cascade's own colour — never a literal of ours.
      out[key] = resolveToken(TOKENS[key]) || getComputedStyle(document.body).color;
    });
    var styles = getComputedStyle(document.documentElement);
    out.font = styles.getPropertyValue("--font-sans").trim() || "sans-serif";
    return out;
  }

  function stylesheet(t) {
    return [
      {
        selector: "node",
        style: {
          shape: "round-rectangle",
          width: 148,
          height: 52,
          "background-color": t.surface,
          "border-width": 1,
          "border-color": t.border,
          label: "data(text)",
          "text-wrap": "wrap",
          "text-max-width": 132,
          "text-valign": "center",
          "text-halign": "center",
          "font-family": t.font,
          "font-size": 12,
          color: t.text,
        },
      },
      { selector: "edge", style: { width: 1.5, "line-color": t.edge, "target-arrow-color": t.edge, "target-arrow-shape": "triangle", "curve-style": "bezier" } },
      { selector: "node.ps-selected", style: { "border-width": 3, "border-color": t.accent, "background-color": t.accentSoft } },
      { selector: "node.ps-dependent", style: { "border-width": 2, "border-color": t.dependent } },
      { selector: "node.ps-dependency", style: { "border-width": 2, "border-color": t.dependency } },
      { selector: "edge.ps-on-path", style: { width: 2.5, "line-color": t.accent, "target-arrow-color": t.accent } },
    ];
  }

  /**
   * Mounts the graph into [container]. `elements` is PSModel.graphElements(set); `onSelect(name)` is called with
   * a parameter name when a node is tapped. Returns {select, destroy, retheme, cy}.
   */
  function create(container, elements, onSelect) {
    if (typeof window.cytoscape !== "function") throw new Error("cytoscape is not loaded");
    var tokens = readTokens();
    var cy = window.cytoscape({
      container: container,
      elements: elements.nodes
        .map(function (n) {
          return { group: "nodes", data: { id: n.id, text: n.label + "\n[" + n.badge + "]" } };
        })
        .concat(
          elements.edges.map(function (e) {
            return { group: "edges", data: { id: e.id, source: e.source, target: e.target } };
          }),
        ),
      style: stylesheet(tokens),
      layout: { name: "dagre", rankDir: "LR", nodeSep: 24, rankSep: 56 },
      // The fit-to-container zoom is capped at 1: a one- or two-node graph is drawn at its natural size, never magnified.
      maxZoom: 1,
      userZoomingEnabled: false,
      boxSelectionEnabled: false,
      autoungrabify: true,
    });
    cy.on("tap", "node", function (event) {
      onSelect(event.target.id());
    });

    function select(name) {
      cy.elements().removeClass("ps-selected ps-dependent ps-dependency ps-on-path");
      var node = cy.getElementById(name);
      if (!node || node.empty()) return;
      node.addClass("ps-selected");
      node.successors("node").addClass("ps-dependent");
      node.predecessors("node").addClass("ps-dependency");
      node.connectedEdges().addClass("ps-on-path");
    }

    function retheme() {
      cy.style(stylesheet(readTokens()));
    }

    // A theme swap reaches the page as an htmx OOB replacement of #theme-link (partials/theme-swap.html): re-read
    // the tokens on the NEW sheet's load, never earlier (the stylesheet fetch would still be in flight).
    var observer = null;
    var themeLink = document.getElementById("theme-link");
    if (typeof MutationObserver === "function" && document.head) {
      observer = new MutationObserver(function () {
        var link = document.getElementById("theme-link");
        if (!link || link === themeLink) return;
        themeLink = link;
        link.addEventListener("load", retheme, { once: true });
      });
      observer.observe(document.head, { childList: true, subtree: true });
    }

    return {
      cy: cy,
      select: select,
      retheme: retheme,
      destroy: function () {
        if (observer) observer.disconnect();
        cy.destroy();
      },
    };
  }

  window.PSGraph = { create: create, _toLegacyRgb: toLegacyRgb };
})();
