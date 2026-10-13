(function () {
  "use strict";

  /*
   * The Parameter Sets workspace's ONE runtime, ONE initializer per document (#374) — the pipeline workspace's
   * #358 contract (pipeline-editor/runtime.js), for this page. htmx caches the history element's innerHTML and a cached
   * restore RE-CREATES every <script> in it, so the vendors and modules are NOT bare tags in the fragment: they ride an
   * inert <template id="ps-runtime-scripts" data-page-assets="on-mount"> catalog that the shell's ONE page-script loader
   * (page-assets.mjs, #494) executes IN ORDER, once per document across every page sharing a source. This file is the
   * catalog's last entry, a `data-page-mount`: the loader calls `ParameterWorkspaceMount` once per catalog node — the
   * first arrival, a boosted swap and every cached history restore alike — after every entry before it loaded.
   *
   * Before every history save (the `__dpHistoryStyleCleanups` registry shell.js dispatches through) the runtime
   * unmounts the live tree — disposing the evaluate instance and the graph — and strips the `style` attributes those
   * libraries wrote, so the snapshot is inert and style-free and the next restore starts clean.
   */

  // The loader executes this file once per document; a second evaluation keeps the first runtime.
  if (window.PSRuntime) return;

  function currentRoot() {
    var main = document.getElementById("app-main");
    return main && main.querySelector ? main.querySelector(".ps-root") : null;
  }

  function enter() {
    var root = currentRoot();
    if (!root || root.__psActivation) return;
    root.__psActivation = true;
    try {
      window.PSWorkspace.mount(root);
      window.PSRuntime.activations++;
    } catch (error) {
      root.__psActivation = false;
      var notice = document.createElement("p");
      notice.setAttribute("role", "alert");
      notice.className = "ds-empty-description";
      notice.textContent = "The parameter workspace could not load. Reload the page to try again.";
      root.replaceChildren(notice);
      if (window.console && window.console.error) window.console.error("Parameter workspace runtime failed:", error);
    }
  }

  function beforeHistorySave(historyRoot) {
    var root = historyRoot && historyRoot.querySelector ? historyRoot.querySelector(".ps-root") : null;
    if (!root) return;
    if (window.PSWorkspace) window.PSWorkspace.unmount(root);
    var styled = root.querySelectorAll("[style]");
    for (var i = 0; i < styled.length; i++) styled[i].removeAttribute("style");
    root.removeAttribute("style");
    delete root.__psActivation;
  }

  window.PSRuntime = { enter: enter, activations: 0 };
  /** The loader's mount for this catalog (page-assets.mjs MOUNTS): activate the live root. */
  window.ParameterWorkspaceMount = function () {
    enter();
  };
  window.__dpHistoryStyleCleanups = window.__dpHistoryStyleCleanups || [];
  window.__dpHistoryStyleCleanups.push(beforeHistorySave);
})();
