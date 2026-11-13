(function () {
  "use strict";

  /*
   * dependencies/list.js (#876 G6, record §13 "keyboard-accessible list/table alternative") — the SAME nodes and
   * edges the graph draws, as two plain tables: one row per node (kind glyph + word, name, version label, status
   * text, relationship to its parents, constraints) and one row per edge. The node table is a plain `<table>` with
   * `aria-describedby` naming the pane's status note, and the ROVING TABINDEX of pipeline-editor/a11y.js (exactly one
   * row tabbable; ArrowUp/Down/Home/End move it and the focus); Enter or a click selects the row's node. Every
   * string reaches the DOM through textContent — names and messages are author data, never markup.
   */

  var SVG = "http://www.w3.org/2000/svg";
  var SPRITE = "/vendor/icons/lucide-sprite.svg#";
  var TONE_BADGE = { success: "ds-badge-success", warning: "ds-badge-warning", danger: "ds-badge-danger", muted: "ds-badge-default" };

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function glyph(name) {
    var svg = document.createElementNS(SVG, "svg");
    svg.setAttribute("class", "ds-icon ds-icon-sm");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    var use = document.createElementNS(SVG, "use");
    use.setAttribute("href", SPRITE + name);
    svg.appendChild(use);
    return svg;
  }

  /**
   * A folder-path name as text with a line-break opportunity after every "/": a long name wraps between its
   * segments, and only a segment wider than its column breaks inside (the cell's overflow-wrap).
   */
  function pathText(parent, name) {
    var parts = String(name).split("/");
    parts.forEach(function (part, i) {
      parent.appendChild(document.createTextNode(i < parts.length - 1 ? part + "/" : part));
      if (i < parts.length - 1) parent.appendChild(document.createElement("wbr"));
    });
    return parent;
  }

  /** The node's name cell: a link to its exact version when the view built one, plain text otherwise. */
  function nameCell(node) {
    var cell = el("td", "dp-deps-name");
    var target;
    if (node.href) {
      target = pathText(el("a"), node.name);
      target.setAttribute("href", node.href);
    } else {
      target = pathText(el("span"), node.name);
    }
    target.setAttribute("title", node.name);
    cell.appendChild(target);
    if (node.isRoot) cell.appendChild(el("span", "ds-badge ds-badge-primary dp-deps-root-mark", "This object"));
    return cell;
  }

  function header(labels) {
    var thead = el("thead");
    var tr = el("tr");
    labels.forEach(function (label) {
      tr.appendChild(el("th", null, label));
    });
    thead.appendChild(tr);
    return thead;
  }

  /**
   * Renders the view into `container` (replacing its children). Returns `{ select(id), focus() }`.
   * `options.onSelect(id)` is called when a row is chosen; `options.describedBy` is the status note's id.
   */
  function render(container, view, options) {
    var opts = options || {};
    var byId = {};
    view.nodes.forEach(function (n) {
      byId[n.id] = n;
    });
    var incoming = {};
    var constraintsTo = {};
    view.edges.forEach(function (e) {
      var from = byId[e.from];
      (incoming[e.to] = incoming[e.to] || []).push(e.label + (from ? " of " + from.name : ""));
      constraintsTo[e.to] = (constraintsTo[e.to] || []).concat(e.constraints);
    });

    var nodesTable = el("table", "ds-table dp-deps-table");
    nodesTable.setAttribute("data-dp-deps-list", "nodes");
    if (opts.describedBy) nodesTable.setAttribute("aria-describedby", opts.describedBy);
    nodesTable.appendChild(el("caption", "dp-deps-caption", "Objects in this dependency graph"));
    nodesTable.appendChild(header(["Kind", "Name", "Version", "Status", "Relationship", "Constraints"]));
    var body = el("tbody");
    var rows = [];
    view.nodes.forEach(function (n, index) {
      var tr = el("tr", "dp-deps-row");
      tr.setAttribute("data-node-id", n.id);
      tr.setAttribute("data-tone", n.tone);
      tr.setAttribute("tabindex", index === 0 ? "0" : "-1");
      tr.setAttribute("aria-selected", "false");
      // The cell stays a table cell; the glyph and its label sit in one unbroken line inside it.
      var kind = el("td", "dp-deps-kind");
      var kindLine = el("span", "dp-deps-kind-line");
      kindLine.appendChild(glyph(n.glyph));
      kindLine.appendChild(el("span", null, n.familyLabel));
      kind.appendChild(kindLine);
      tr.appendChild(kind);
      tr.appendChild(nameCell(n));
      tr.appendChild(el("td", "dp-deps-nowrap", n.versionLabel));
      var statusCell = el("td", "dp-deps-status");
      statusCell.appendChild(el("span", "ds-badge " + (TONE_BADGE[n.tone] || "ds-badge-default"), n.statusText));
      n.diagnostics.forEach(function (message) {
        statusCell.appendChild(el("span", "dp-deps-diagnostic", message));
      });
      tr.appendChild(statusCell);
      tr.appendChild(pathText(el("td", "dp-deps-path"), n.isRoot ? "Root" : (incoming[n.id] || []).join("; ")));
      tr.appendChild(pathText(el("td", "dp-deps-path"), (constraintsTo[n.id] || []).join("; ")));
      body.appendChild(tr);
      rows.push(tr);
    });
    nodesTable.appendChild(body);

    var edgesTable = el("table", "ds-table dp-deps-table");
    edgesTable.setAttribute("data-dp-deps-list", "edges");
    edgesTable.appendChild(el("caption", "dp-deps-caption", "Relationships"));
    edgesTable.appendChild(header(["From", "Relationship", "To", "Constraints"]));
    var edgeBody = el("tbody");
    view.edges.forEach(function (e) {
      var tr = el("tr");
      tr.setAttribute("data-edge-id", e.id);
      tr.appendChild(pathText(el("td", "dp-deps-path"), byId[e.from] ? byId[e.from].name + " " + byId[e.from].versionLabel : ""));
      tr.appendChild(el("td", "dp-deps-nowrap", e.label));
      tr.appendChild(pathText(el("td", "dp-deps-path"), byId[e.to] ? byId[e.to].name + " " + byId[e.to].versionLabel : ""));
      tr.appendChild(pathText(el("td", "dp-deps-path"), e.constraints.join("; ")));
      edgeBody.appendChild(tr);
    });
    edgesTable.appendChild(edgeBody);

    function move(to) {
      if (rows.length === 0) return;
      var index = Math.max(0, Math.min(rows.length - 1, to));
      rows.forEach(function (row, i) {
        row.setAttribute("tabindex", i === index ? "0" : "-1");
      });
      rows[index].focus();
    }

    function select(id) {
      rows.forEach(function (row) {
        row.setAttribute("aria-selected", row.getAttribute("data-node-id") === id ? "true" : "false");
      });
    }

    body.addEventListener("keydown", function (event) {
      var row = event.target && event.target.closest ? event.target.closest("tr.dp-deps-row") : null;
      if (!row) return;
      var index = rows.indexOf(row);
      var key = event.key;
      if (key === "ArrowDown") move(index + 1);
      else if (key === "ArrowUp") move(index - 1);
      else if (key === "Home") move(0);
      else if (key === "End") move(rows.length - 1);
      else if (key === "Enter" && event.target === row) {
        if (typeof opts.onSelect === "function") opts.onSelect(row.getAttribute("data-node-id"));
      } else return;
      event.preventDefault();
    });
    body.addEventListener("click", function (event) {
      if (event.target && event.target.closest && event.target.closest("a")) return;
      var row = event.target && event.target.closest ? event.target.closest("tr.dp-deps-row") : null;
      if (row && typeof opts.onSelect === "function") opts.onSelect(row.getAttribute("data-node-id"));
    });

    container.replaceChildren(nodesTable, edgesTable);
    return {
      select: select,
      focus: function () {
        move(0);
      },
    };
  }

  var api = { render: render };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.DependencyList = api;
})();
