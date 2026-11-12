(function () {
  "use strict";

  /*
   * dependencies/renderer.js (#876 G6, record §13) — the ONE dependency graph renderer. It draws a neutral
   * VIEW-MODEL (tree-view.js builds it from the tree payload; plan-view.js will build it from the release dialog's
   * rows), never a wire payload, so two server contracts share one drawing:
   *
   *   { nodes: [{ id, family, familyLabel, name, versionLabel, statusText, tone, isRoot, href?, diagnostics? }],
   *     edges: [{ id, from, to, label, constraints: [text] }], notes: [text] }
   *
   * Cytoscape 3.34.0 + dagre (vendored; laid out left to right), drawn ONCE from the already-deduplicated nodes and
   * edges (a shared node is one node however many branches reach it; paths are never expanded). Labels are plain
   * Cytoscape `label` data — the node-html-label extension is NOT used, so no name ever becomes markup. Every node's
   * label carries its status TEXT (colour is never alone); tones come from the design tokens through the measured
   * probe recipe below. Interactions: pan (drag), zoom/fit/reset buttons with text labels, root emphasis, a node's
   * selection highlighting its incoming and outgoing edges and the objects at their ends (a shared node shows every
   * dependent), and a selection panel listing both directions with their relationship labels and constraints and a
   * link to the object page at the node's exact version. Selecting never switches anything's current version.
   *
   * Token bridge: COPIED from parameter-workspace/graph.js:17-125 (toLegacyRgb, the fresh-probe resolveToken,
   * readTokens) — that file is outside this lane's fence and exports none of them; the copy keeps its recipe.
   *
   * Family glyphs (§A.2, the view-model's `glyph`): a DOM layer over the canvas holds one
   * `<svg><use href="/vendor/icons/lucide-sprite.svg#…">` per drawn node — the list's and the Flow graph's own way of
   * drawing a lucide glyph. Cytoscape cannot draw a sprite `<symbol>` (an image URL cannot address one), and an image
   * on the canvas would not inherit `currentColor`; in the DOM the glyph takes its colour from the stylesheet, so a
   * theme switch recolours it with no token read, and no extra request or string-built image is needed. The layer
   * ignores the pointer (pan, zoom and tap stay the canvas's), follows every render through a CSSOM transform (no
   * style attribute markup, CSP-clean) and carries no payload text: the glyph name and the node id ride as attributes.
   */

  var TOKENS = {
    surface: ["--surface-raised", "--surface-default"],
    border: ["--border-default", "--border-subtle"],
    text: ["--text-primary"],
    muted: ["--text-muted", "--text-secondary"],
    edge: ["--edge", "--border-default"],
    accent: ["--brand", "--accent-primary"],
    accentSoft: ["--brand-soft", "--surface-selected"],
    success: ["--accent-success"],
    warning: ["--accent-warning"],
    danger: ["--accent-danger"],
  };

  /** The longest name the graph label draws; the full name rides in the list, the panel and their titles. */
  var LABEL_NAME_MAX = 48;
  /** The longest name segment one label line holds at the label's width and font (about 6.5px a character). */
  var LABEL_SEGMENT_MAX = 22;
  /** A zero-width space: a wrap point Cytoscape honours (its wrap splits on whitespace and U+200B). */
  var WRAP_POINT = "\u200b";

  var SVG = "http://www.w3.org/2000/svg";
  var SPRITE = "/vendor/icons/lucide-sprite.svg#";
  /** Node box and the strip at its left the glyph sits in (model units; the label is shifted right to clear it). */
  var NODE_WIDTH = 196;
  var NODE_HEIGHT = 64;
  var GLYPH_INSET = 10;
  var LABEL_SHIFT = 12;

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
      out[key] = resolveToken(TOKENS[key]) || getComputedStyle(document.body).color;
    });
    out.font = getComputedStyle(document.documentElement).getPropertyValue("--font-sans").trim() || "sans-serif";
    return out;
  }

  function stylesheet(t) {
    return [
      {
        selector: "node",
        style: {
          shape: "round-rectangle",
          width: NODE_WIDTH,
          height: NODE_HEIGHT,
          "background-color": t.surface,
          "border-width": 2,
          "border-color": t.border,
          label: "data(text)",
          "text-wrap": "wrap",
          "text-max-width": NODE_WIDTH - 2 * (GLYPH_INSET + LABEL_SHIFT) - 4,
          "text-margin-x": LABEL_SHIFT,
          "text-valign": "center",
          "text-halign": "center",
          "font-family": t.font,
          "font-size": 11,
          color: t.text,
        },
      },
      {
        selector: "edge",
        style: {
          width: 1.5,
          "line-color": t.edge,
          "target-arrow-color": t.edge,
          "target-arrow-shape": "triangle",
          "curve-style": "bezier",
          label: "data(text)",
          "font-family": t.font,
          "font-size": 9,
          color: t.muted,
          "text-background-color": t.surface,
          "text-background-opacity": 1,
          "text-background-padding": 2,
          "text-rotation": "autorotate",
        },
      },
      { selector: "node.dp-tone-success", style: { "border-color": t.success } },
      { selector: "node.dp-tone-warning", style: { "border-color": t.warning } },
      { selector: "node.dp-tone-danger", style: { "border-color": t.danger, "border-style": "dashed" } },
      { selector: "node.dp-tone-muted", style: { "border-color": t.border, color: t.muted } },
      { selector: "node.dp-root", style: { "border-width": 4, "font-weight": "bold" } },
      { selector: "node.dp-related", style: { "background-color": t.accentSoft } },
      { selector: "node.dp-selected", style: { "border-width": 4, "border-color": t.accent, "background-color": t.accentSoft } },
      { selector: "edge.dp-related", style: { width: 3, "line-color": t.accent, "target-arrow-color": t.accent, color: t.text } },
    ];
  }

  /**
   * A node's two-line label: kind, name and version, then the status text. Cytoscape wraps only at whitespace and
   * U+200B, and a folder-path name has neither, so a long name ran past the wrap width — over the family glyph and out
   * of its box. The drawn name gets a U+200B after every "/" and any one segment longer than a line is ellipsised; the
   * list, the panel and their titles carry the full name.
   */
  function graphLabel(node) {
    var name = node.name.length > LABEL_NAME_MAX ? node.name.slice(0, LABEL_NAME_MAX - 1) + "…" : node.name;
    var drawn = name
      .split("/")
      .map(function (segment) {
        return segment.length > LABEL_SEGMENT_MAX ? segment.slice(0, LABEL_SEGMENT_MAX - 1) + "…" : segment;
      })
      .join("/" + WRAP_POINT);
    return node.familyLabel + " · " + drawn + " " + node.versionLabel + "\n" + node.statusText;
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  /**
   * The glyph layer: one sprite glyph per node, positioned over the node's left strip on every render. Returns
   * `{ update() }`; the caller calls it from Cytoscape's `render` event.
   */
  function glyphLayer(stage, canvas, cy, view) {
    var layer = el("div", "dp-deps-glyphs");
    layer.setAttribute("aria-hidden", "true");
    layer.setAttribute("data-dp-deps-glyphs", "");
    stage.appendChild(layer);
    var glyphs = view.nodes.map(function (n) {
      var svg = document.createElementNS(SVG, "svg");
      svg.setAttribute("class", "ds-icon ds-icon-sm dp-deps-node-glyph");
      svg.setAttribute("aria-hidden", "true");
      svg.setAttribute("focusable", "false");
      svg.setAttribute("data-dp-deps-glyph", n.glyph);
      svg.setAttribute("data-node", n.id);
      var use = document.createElementNS(SVG, "use");
      use.setAttribute("href", SPRITE + n.glyph);
      svg.appendChild(use);
      layer.appendChild(svg);
      return { svg: svg, node: cy.getElementById(n.id) };
    });
    function update() {
      // The canvas's border offsets Cytoscape's rendered coordinates inside the stage.
      var offsetX = canvas.offsetLeft + canvas.clientLeft;
      var offsetY = canvas.offsetTop + canvas.clientTop;
      var zoom = cy.zoom();
      glyphs.forEach(function (g) {
        if (!g.node || g.node.empty()) return;
        var box = g.node.renderedBoundingBox({ includeLabels: false, includeOverlays: false });
        // The computed height is the icon token's, untouched by the transform (origin top left, in the CSS).
        var size = parseFloat(getComputedStyle(g.svg).height) || 0;
        var x = offsetX + box.x1 + GLYPH_INSET * zoom;
        var y = offsetY + box.y1 + (box.h - size * zoom) / 2;
        g.svg.style.transform = "translate(" + x + "px, " + y + "px) scale(" + zoom + ")";
      });
    }
    return { update: update };
  }

  /** The selection panel's content for one node: identity, link, both directions, diagnostics. */
  function fillPanel(panel, view, id) {
    var byId = {};
    view.nodes.forEach(function (n) {
      byId[n.id] = n;
    });
    var node = byId[id];
    if (!node) {
      panel.replaceChildren(el("p", "u-secondary u-text-sm", "Select an object to see its relationships."));
      return;
    }
    var title = el("p", "dp-deps-panel-title");
    title.appendChild(el("strong", null, node.name));
    title.appendChild(document.createTextNode(" " + node.versionLabel + " · " + node.familyLabel));
    title.setAttribute("title", node.name);
    var parts = [title, el("p", "dp-deps-panel-status", node.statusText)];
    if (node.href) {
      var link = el("a", null, "Open this " + node.familyLabel.toLowerCase() + " at " + node.versionLabel);
      link.setAttribute("href", node.href);
      var linkLine = el("p");
      linkLine.appendChild(link);
      parts.push(linkLine);
    }
    (node.diagnostics || []).forEach(function (message) {
      parts.push(el("p", "dp-deps-diagnostic", message));
    });
    var incoming = view.edges.filter(function (e) {
      return e.to === id;
    });
    var outgoing = view.edges.filter(function (e) {
      return e.from === id;
    });
    function list(heading, edges, end) {
      parts.push(el("p", "dp-deps-panel-heading", heading));
      var ul = el("ul", "dp-deps-panel-list");
      if (edges.length === 0) ul.appendChild(el("li", "u-secondary", "None"));
      edges.forEach(function (e) {
        var other = byId[e[end]];
        var text = e.label + " — " + (other ? other.name + " " + other.versionLabel : "");
        if (e.constraints.length > 0) text += " (" + e.constraints.join("; ") + ")";
        ul.appendChild(el("li", null, text));
      });
      parts.push(ul);
    }
    list(incoming.length > 1 ? "Used here by " + incoming.length + " objects" : "Used here by", incoming, "from");
    list("Depends on", outgoing, "to");
    panel.replaceChildren.apply(panel, parts);
  }

  /**
   * Mounts the graph into `host` (its children are replaced): a toolbar, the Cytoscape canvas and the selection
   * panel. `options.onSelect(id)` hears a node tap. Returns `{ select(id), retheme(), destroy() }`.
   */
  function mount(host, view, options) {
    if (typeof window.cytoscape !== "function") throw new Error("cytoscape is not loaded");
    var opts = options || {};
    var toolbar = el("div", "dp-deps-toolbar");
    toolbar.setAttribute("role", "group");
    toolbar.setAttribute("aria-label", "Graph view controls");
    var stage = el("div", "dp-deps-stage");
    var canvas = el("div", "dp-deps-canvas");
    stage.appendChild(canvas);
    canvas.setAttribute("tabindex", "0");
    canvas.setAttribute("role", "group");
    canvas.setAttribute("aria-label", "Dependency graph. Arrow keys move between objects; the list view shows the same objects.");
    var panel = el("div", "dp-deps-panel");
    panel.setAttribute("aria-live", "polite");
    host.replaceChildren(toolbar, stage, panel);

    var cy = window.cytoscape({
      container: canvas,
      elements: view.nodes
        .map(function (n) {
          var classes = ["dp-tone-" + n.tone];
          if (n.isRoot) classes.push("dp-root");
          return { group: "nodes", data: { id: n.id, text: graphLabel(n) }, classes: classes.join(" ") };
        })
        .concat(
          view.edges.map(function (e) {
            return { group: "edges", data: { id: "edge:" + e.id, source: e.from, target: e.to, text: e.label } };
          }),
        ),
      style: stylesheet(readTokens()),
      layout: { name: "dagre", rankDir: "LR", nodeSep: 28, rankSep: 72 },
      maxZoom: 1.5,
      minZoom: 0.2,
      userZoomingEnabled: false,
      boxSelectionEnabled: false,
      autoungrabify: true,
    });
    var glyphs = glyphLayer(stage, canvas, cy, view);
    cy.on("render", glyphs.update);
    glyphs.update();
    var initialZoom = cy.zoom();
    var initialPan = cy.pan();
    var order = view.nodes.map(function (n) {
      return n.id;
    });
    var selected = null;

    function select(id) {
      selected = id;
      cy.elements().removeClass("dp-selected dp-related");
      var node = cy.getElementById(id);
      if (node && !node.empty()) {
        node.addClass("dp-selected");
        node.connectedEdges().addClass("dp-related");
        node.neighborhood("node").addClass("dp-related");
      }
      fillPanel(panel, view, id);
    }

    function button(label, action) {
      var b = el("button", "ds-button ds-button-ghost ds-button-sm", label);
      b.setAttribute("type", "button");
      b.addEventListener("click", action);
      toolbar.appendChild(b);
    }
    button("Fit", function () {
      cy.fit(undefined, 24);
    });
    button("Zoom in", function () {
      cy.zoom({ level: cy.zoom() * 1.25, renderedPosition: { x: canvas.clientWidth / 2, y: canvas.clientHeight / 2 } });
    });
    button("Zoom out", function () {
      cy.zoom({ level: cy.zoom() / 1.25, renderedPosition: { x: canvas.clientWidth / 2, y: canvas.clientHeight / 2 } });
    });
    button("Reset", function () {
      cy.zoom(initialZoom);
      cy.pan(initialPan);
    });

    cy.on("tap", "node", function (event) {
      var id = event.target.id();
      select(id);
      if (typeof opts.onSelect === "function") opts.onSelect(id);
    });
    canvas.addEventListener("keydown", function (event) {
      if (order.length === 0) return;
      var index = selected === null ? -1 : order.indexOf(selected);
      var next = null;
      if (event.key === "ArrowDown" || event.key === "ArrowRight") next = Math.min(order.length - 1, index + 1);
      else if (event.key === "ArrowUp" || event.key === "ArrowLeft") next = Math.max(0, index - 1);
      else if (event.key === "Home") next = 0;
      else if (event.key === "End") next = order.length - 1;
      if (next === null) return;
      event.preventDefault();
      select(order[next]);
      if (typeof opts.onSelect === "function") opts.onSelect(order[next]);
    });
    fillPanel(panel, view, null);

    function retheme() {
      cy.style(stylesheet(readTokens()));
    }
    // A theme swap replaces #theme-link out of band (partials/theme-swap.html): re-read the tokens once the NEW
    // sheet has loaded (graph.js's recipe).
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
      select: select,
      retheme: retheme,
      destroy: function () {
        if (observer) observer.disconnect();
        cy.destroy();
        host.replaceChildren();
      },
    };
  }

  var ns = typeof window !== "undefined" ? (window.DependencyTree = window.DependencyTree || {}) : {};
  ns.mount = mount;
  ns._graphLabel = graphLabel;
  ns._toLegacyRgb = toLegacyRgb;
  if (typeof module !== "undefined" && module.exports) module.exports = { mount: mount, graphLabel: graphLabel, toLegacyRgb: toLegacyRgb };
})();
