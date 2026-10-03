(function () {
  "use strict";

  /*
   * visualizations/workspace.js (#399) — the visualization WORKSPACE's glue: the closed tab set
   * (Preview | Overview | Evidence | Used by | Versions, Preview the default — the server's
   * VisualizationWorkspaceModel.Tab resolves by the SAME rule) wired through the shared pane glue
   * (workspace/panes.js over workspace/tabs.js), plus the one thing this family owns: the
   * Preview pane's ONE-CASE-AT-A-TIME fixture mount.
   *
   * The Preview partial carries one `<section data-viz-case-index=N>` per test case (all but the
   * selected one `hidden`), a `<select data-viz-case-select>` and the `{visualization, cases}`
   * block (#viz-preview-data, ScriptSafeJson). A case mounts LAZILY, on its first selection,
   * through window.DatapipelinesPreviewMount — the capability page's own mount, fixture mode, no
   * server base URL — so a ten-case visualization boots one Plotly instance, not ten. A case
   * revealed again re-fits through the instance's own resize.
   *
   * Version state does NOT live here: every version switch is a full navigation (the
   * one-bundle rule). The pure half (caseIndexFrom) is exported for `node --test`.
   */

  var TABS = ["preview", "overview", "evidence", "used-by", "versions"];
  var DEFAULT_TAB = "preview";

  var logic = {
    tabs: TABS,
    defaultTab: DEFAULT_TAB,
    /** A select's value as a case index inside [0, count): anything else is case 0. */
    caseIndexFrom: function (raw, count) {
      var n = /^\d+$/.test(String(raw)) ? parseInt(raw, 10) : 0;
      return n >= 0 && n < count ? n : 0;
    },
  };

  var preview = { instances: {}, data: null, notifications: [] };

  function showCase(pane, index) {
    var sections = Array.prototype.slice.call(pane.querySelectorAll("[data-viz-case-index]"));
    sections.forEach(function (section) {
      var mine = section.getAttribute("data-viz-case-index") === String(index);
      if (mine) section.removeAttribute("hidden");
      else section.setAttribute("hidden", "hidden");
    });
    var runtime = window.DatapipelinesDashboard;
    var mount = window.DatapipelinesPreviewMount;
    if (!preview.data || !runtime || !mount) return;
    var existing = preview.instances[index];
    if (existing) {
      if (typeof existing.resize === "function") {
        try {
          existing.resize();
        } catch (e) {
          /* a case whose bootstrap failed has nothing to resize */
        }
      }
      return;
    }
    var testCase = (preview.data.cases || [])[index];
    var section = pane.querySelector('[data-viz-case-index="' + index + '"]');
    var board = pane.querySelector('[data-viz-case-board="' + index + '"]');
    if (!testCase || !section || !board) return;
    preview.instances[index] = mount(runtime, preview.data, testCase, section, board, preview.notifications);
  }

  /** The Preview partial arrived: read its block once and mount the selected case. */
  function wirePreview(pane) {
    var block = pane.querySelector("#viz-preview-data");
    if (!block || block.getAttribute("data-viz-wired") === "1") return;
    block.setAttribute("data-viz-wired", "1");
    preview.data = JSON.parse(block.textContent);
    preview.instances = {};
    window.VisualizationWorkspacePreview = preview;
    var count = (preview.data.cases || []).length;
    var select = pane.querySelector("[data-viz-case-select]");
    var initial = logic.caseIndexFrom(select ? select.value : 0, count);
    if (select) {
      select.addEventListener("change", function () {
        showCase(pane, logic.caseIndexFrom(select.value, count));
      });
    }
    if (count > 0) showCase(pane, initial);
  }

  function wire() {
    if (!window.WorkspacePanes) return;
    var wired = window.WorkspacePanes.wireWorkspace({
      tabs: TABS,
      defaultTab: DEFAULT_TAB,
      onReveal: function (tab, pane) {
        if (tab !== "preview") return;
        // A preview revealed after its first load re-fits the visible case (it may have mounted
        // into a hidden pane on a deep link).
        var select = pane.querySelector("[data-viz-case-select]");
        if (preview.data && select) showCase(pane, logic.caseIndexFrom(select.value, (preview.data.cases || []).length));
      },
    });
    if (!wired) return;
    var previewPane = wired.root.querySelector('[data-dp-pane="preview"]');
    if (!previewPane) return;
    document.body.addEventListener("htmx:afterSwap", function (event) {
      if (event.detail && event.detail.target === previewPane) wirePreview(previewPane);
    });
    // A partial that landed before this listener existed (a fast first paint).
    wirePreview(previewPane);
  }

  if (typeof module !== "undefined" && module.exports) module.exports = logic;
  if (typeof window !== "undefined" && typeof document !== "undefined") {
    window.VisualizationWorkspaceLogic = logic;
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", wire);
    } else {
      wire();
    }
  }
})();
