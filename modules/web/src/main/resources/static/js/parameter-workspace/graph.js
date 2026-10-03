(function () {
  "use strict";

  /*
   * The Parameter Sets workspace's dependency graph (#374, workspace spec §6.2): one node per parameter, an
   * edge per `depends_on`, the badge = the parameter's source kind. Cytoscape + dagre, laid out left to right.
   * #383 — the graph is also the evaluation's LIVE STATE: while the observed-evaluation stream runs, each
   * applied frame moves its parameter's node through waiting → admitted → running → resolved (a failed
   * selector → failed), the terminal response's reset parameters carry a reset mark, and the states clear
   * when the next attempt starts. The state classes style through the same design tokens as the structure.
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
    waiting: ["--accent-info"],
    admitted: ["--brand", "--accent-primary"],
    running: ["--accent-warning"],
    resolved: ["--accent-success"],
    failed: ["--accent-danger"],
    reset: ["--accent-warning"],
  };

  /** The per-parameter stream state → its Cytoscape class (one node carries at most one). */
  var STATE_CLASSES = {
    waiting: "ps-waiting",
    admitted: "ps-admitted",
    running: "ps-running",
    resolved: "ps-resolved",
    failed: "ps-failed",
  };
  var ALL_STATE_CLASSES = Object.keys(STATE_CLASSES)
    .map(function (key) {
      return STATE_CLASSES[key];
    })
    .concat("ps-reset")
    .join(" ");

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
      // #383 — the evaluation's live node states, declared BEFORE the selection rules (later rules win in
      // Cytoscape, so a selected node keeps its selection look); a state shows through its border (the failed
      // node's label takes the danger colour).
      { selector: "node.ps-waiting", style: { "border-width": 3, "border-color": t.waiting } },
      { selector: "node.ps-admitted", style: { "border-width": 3, "border-color": t.admitted } },
      { selector: "node.ps-running", style: { "border-width": 3, "border-color": t.running } },
      { selector: "node.ps-resolved", style: { "border-width": 3, "border-color": t.resolved } },
      { selector: "node.ps-failed", style: { "border-width": 3, "border-color": t.failed, color: t.failed } },
      // A terminal response's reset mark rides BESIDE the state (dashed warning border, the state colour stays).
      { selector: "node.ps-reset", style: { "border-style": "dashed", "border-color": t.reset } },
      { selector: "node.ps-selected", style: { "border-width": 3, "border-color": t.accent, "background-color": t.accentSoft } },
      { selector: "node.ps-dependent", style: { "border-width": 2, "border-color": t.dependent } },
      { selector: "node.ps-dependency", style: { "border-width": 2, "border-color": t.dependency } },
      { selector: "edge.ps-on-path", style: { width: 2.5, "line-color": t.accent, "target-arrow-color": t.accent } },
    ];
  }

  /**
   * Mounts the graph into [container]. `elements` is PSModel.graphElements(set); `onSelect(name)` is called with
   * a parameter name when a node is tapped. Returns {cy, select, setState, clearStates, markResets, retheme, destroy} —
   * the three state calls are #383's live evaluation, driven by the page's applied stream frames.
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

    /** #383 — the parameter's live stream state: exactly one state class, or none for an unknown state. */
    function setState(name, state) {
      var node = cy.getElementById(name);
      if (!node || node.empty()) return;
      node.removeClass(ALL_STATE_CLASSES);
      var cls = STATE_CLASSES[state];
      if (cls) node.addClass(cls);
    }

    /** A new attempt starts: every node is idle again (states and reset marks clear). */
    function clearStates() {
      cy.nodes().removeClass(ALL_STATE_CLASSES);
    }

    /** The terminal response's reset marks — the parameters whose previous selection was dropped. */
    function markResets(names) {
      cy.nodes().removeClass("ps-reset");
      (Array.isArray(names) ? names : []).forEach(function (name) {
        var node = cy.getElementById(name);
        if (node && !node.empty()) node.addClass("ps-reset");
      });
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
      setState: setState,
      clearStates: clearStates,
      markResets: markResets,
      retheme: retheme,
      destroy: function () {
        if (observer) observer.disconnect();
        cy.destroy();
      },
    };
  }

  window.PSGraph = { create: create, _toLegacyRgb: toLegacyRgb };
})();
