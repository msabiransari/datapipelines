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
   * The Preview partial arrives by an htmx swap into the pane: ONE document listener (a window
   * registry, #437) answers it for the workspace on show, resolved at event time.
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

  /** The document-wide registry key of the ONE afterSwap listener (see registerPreviewSwap). */
  var SWAP_REGISTRY = "__dpVizPreviewSwap";

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
    // Cached history carries attributes, but no live node wiring (the panes.js guard pattern).
    if (!block || block.__dpVizWired === true) return;
    block.__dpVizWired = true;
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

  /**
   * The Preview pane of the visualization workspace on show NOW, or null: resolved at EVENT time
   * from the document, scoped to this family (`.viz-workspace`) so another family's pane carrying
   * the same `data-dp-pane` marker is never matched, and never a pane that left the document.
   */
  function livePreviewPane() {
    var root = document.querySelector(".viz-workspace .dp-ws-root");
    if (!root || root.isConnected === false) return null;
    return root.querySelector('[data-dp-pane="preview"]');
  }

  /** The current evaluation's answer to a swap: wires the live Preview pane, and only that pane. */
  function onPreviewSwap(event) {
    var target = event && event.detail ? event.detail.target : null;
    if (!target) return;
    var live = livePreviewPane();
    if (live && target === live) wirePreview(live);
  }

  /** Save fixture shells, never a mounted runtime marker or Plotly's generated style attributes. */
  function onPreviewHistorySave(historyRoot) {
    var live = livePreviewPane();
    if (!live || !historyRoot || !historyRoot.contains(live)) return;
    Object.keys(preview.instances).forEach(function (index) {
      var instance = preview.instances[index];
      if (instance && typeof instance.dispose === "function") instance.dispose();
    });
    preview.instances = {};
    var sections = live.querySelectorAll("[data-viz-case-index]");
    for (var i = 0; i < sections.length; i++) {
      sections[i].removeAttribute("data-dp-ready");
      sections[i].removeAttribute("data-dp-error");
      var board = live.querySelector('[data-viz-case-board="' + sections[i].getAttribute("data-viz-case-index") + '"]');
      if (board) board.replaceChildren();
    }
    // The board page removes this same global scratch SVG; Plotly recreates it on the next draw.
    var tester = document.getElementById("js-plotly-tester");
    if (tester && tester.parentElement) tester.parentElement.removeChild(tester);
  }

  /**
   * ONE htmx:afterSwap listener per document, however many times this glue is evaluated (#437): a
   * restored (cloned) root re-runs the script (#426), and a listener per wiring would stay bound to
   * the previous root's pane. The listener dispatches through the REGISTRY's handler, so the latest
   * evaluation answers with ITS state — the same `preview` its select and onReveal handlers read —
   * and never the evaluation that registered first (the pattern of workspace/history.js).
   */
  function registerPreviewSwap(handler) {
    var reg = window[SWAP_REGISTRY];
    if (!reg) reg = window[SWAP_REGISTRY] = { handler: null, listeners: 0 };
    reg.handler = handler;
    reg.historySave = onPreviewHistorySave;
    if (!reg.historyCleanup) {
      reg.historyCleanup = true;
      window.__dpHistoryStyleCleanups = window.__dpHistoryStyleCleanups || [];
      window.__dpHistoryStyleCleanups.push(function (historyRoot) {
        reg.historySave(historyRoot);
      });
    }
    if (reg.listeners === 0) {
      reg.listeners = 1;
      document.body.addEventListener("htmx:afterSwap", function (event) {
        if (typeof reg.handler === "function") reg.handler(event);
      });
    }
  }

  function wire(main) {
    if (!window.WorkspacePanes) return;
    var root = main && main.querySelector ? main.querySelector(".dp-ws-root") : document.querySelector(".dp-ws-root");
    if (!root || root.__dpWsWired) return;
    preview = { data: null, instances: {}, notifications: [] };
    var wired = window.WorkspacePanes.wireWorkspace({
      root: root,
      family: "visualizations",
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
    // Only an evaluation that WIRED a root answers swaps: a second pass over a root an earlier
    // evaluation already wired returns null above and leaves that evaluation's handler (and its
    // select/onReveal state) in charge.
    registerPreviewSwap(onPreviewSwap);
    // A partial that landed before this listener existed (a fast first paint).
    wirePreview(previewPane);
  }

  if (typeof module !== "undefined" && module.exports) module.exports = logic;
  if (typeof window !== "undefined" && typeof document !== "undefined") {
    window.VisualizationWorkspaceLogic = logic;
    window.VisualizationWorkspaceMount = wire;
    if (window.DatapipelinesPageMountManaged || document.querySelector("template[data-chart-assets]")) return;
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", wire);
    } else {
      wire();
    }
  }
})();
