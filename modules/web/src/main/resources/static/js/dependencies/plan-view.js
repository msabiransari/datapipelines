(function () {
  "use strict";

  /*
   * dependencies/plan-view.js (#876 G6 §F.2) — the release dialog's ADAPTER: the server-rendered RELEASE PLAN rows of
   * `partials/release-dialog.html` → the renderer's view-model (the shape tree-view.js documents). A different server
   * contract from the interactive tree: the dialog's graph is drawn from the rows the dialog GET already rendered, so
   * opening it makes NO request — no fetch, no JSON script block, no wire payload in the page (record §12).
   *
   * Read from the DOM, as text only: each plan row's `data-node-id`, `data-family`, `data-name`, `data-version`,
   * `data-status` and `data-release-row` (root | draft | released | blocking), and the hidden edge list's
   * `data-from` / `data-to` / `data-path` items. The edge label is the plan's `path` as given (the native internal
   * path, never parsed). A prerequisite row is never a node. Nodes carry no link: the dialog is a consent surface.
   *
   * Selecting a node is the mount's business (it highlights the matching row); nothing here touches a checkbox.
   */

  var STATUS = {
    DRAFT: { text: "Draft", tone: "warning" },
    RELEASED: { text: "Released", tone: "success" },
    MISSING: { text: "Missing — blocks release", tone: "danger" },
    DISCARDED: { text: "Discarded — blocks release", tone: "danger" },
  };

  function glyphs() {
    var ns = typeof window !== "undefined" ? window.DependencyTree || {} : {};
    return { glyphs: ns.GLYPHS || {}, labels: ns.FAMILY_LABELS || {} };
  }

  /**
   * The pure half, exported for `node --test`: plan rows and edges (plain objects) → the view-model. A row whose kind
   * is "root" is the root; a draft dependency reads "Draft — released with this", a root draft "Draft".
   */
  function fromRows(rows, edges, tables) {
    var t = tables || glyphs();
    var nodes = rows.map(function (row) {
      var isRoot = row.kind === "root";
      var s = STATUS[row.status] || { text: String(row.status || ""), tone: "muted" };
      var text = s.text;
      if (row.status === "DRAFT") text = isRoot ? "Draft — released by this action" : "Draft — released with this";
      return {
        id: row.nodeId,
        family: row.family,
        glyph: t.glyphs[row.family] || "workflow",
        familyLabel: t.labels[row.family] || row.family,
        name: row.name,
        versionLabel: "v" + row.version,
        statusText: text,
        tone: s.tone,
        isRoot: isRoot,
        href: null,
        diagnostics: [],
      };
    });
    var known = {};
    nodes.forEach(function (n) {
      known[n.id] = true;
    });
    var viewEdges = edges
      .filter(function (e) {
        return known[e.from] === true && known[e.to] === true;
      })
      .map(function (e, i) {
        return { id: "plan-edge-" + i, from: e.from, to: e.to, label: e.path, constraints: [] };
      });
    var empty = viewEdges.length === 0;
    return { nodes: nodes, edges: viewEdges, notes: empty ? ["No dependencies"] : [], state: empty ? "empty" : "ready" };
  }

  /** The DOM half: the plan rows and the hidden edge list under [dialogRoot] (the `[data-release-plan]` element). */
  function fromReleaseRows(dialogRoot) {
    if (!dialogRoot || !dialogRoot.querySelectorAll) throw new Error("No release plan to draw");
    var rows = Array.prototype.map.call(dialogRoot.querySelectorAll("[data-release-row][data-node-id]"), function (el) {
      return {
        nodeId: el.getAttribute("data-node-id"),
        family: el.getAttribute("data-family"),
        name: el.getAttribute("data-name"),
        version: el.getAttribute("data-version"),
        status: el.getAttribute("data-status"),
        kind: el.getAttribute("data-release-row"),
      };
    });
    var edges = Array.prototype.map.call(dialogRoot.querySelectorAll("[data-release-edges] > li"), function (el) {
      return { from: el.getAttribute("data-from"), to: el.getAttribute("data-to"), path: el.getAttribute("data-path") };
    });
    if (rows.length === 0) throw new Error("No release plan to draw");
    return fromRows(rows, edges);
  }

  var api = { fromRows: fromRows, fromReleaseRows: fromReleaseRows };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") {
    var ns = (window.DependencyTree = window.DependencyTree || {});
    ns.fromReleaseRows = fromReleaseRows;
  }
})();
