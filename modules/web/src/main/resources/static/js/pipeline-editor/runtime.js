(function () {
  "use strict";

  /*
   * #358 — the pipeline workspace's ONE runtime, ONE initializer per document.
   *
   * Why this file exists: htmx 2.0.10 caches the history element's innerHTML and a cached
   * restore RE-CREATES every <script> in it (allowScriptTags, verified in the vendored
   * dist). While the editor's vendors and modules were bare script tags in the fragment,
   * every cache-hit restore replayed Alpine itself: a second Alpine instance booted, its
   * start walk found the restored `.pe-root` (JavaScript expandos do not survive the
   * snapshot, so no `_x_dataStack`) and initialised it, and init.js's afterSettle rescue
   * initialised it again — the stacked bindings 348-b witnessed (two `#pe-node-sql`
   * panes, one Execute click, TWO execute POSTs).
   *
   * The contract now, in order:
   *
   *  1. The layout gives `#app-main` `hx-history-elt` — the cache holds the workspace
   *     region only, never the footer scripts. The body fragment carries its script tags
   *     inside an inert `<template id="pe-runtime-scripts">` catalog, so a restored
   *     snapshot has NOTHING executable except this guarded bootstrap.
   *  2. This runtime loads the catalog IN ORDER on the first visit of the document
   *     (dagre, cytoscape, the editor modules, Alpine LAST so the `alpine:init`
   *     registration is in place before it boots) and then — and only then — activates
   *     the root. A global one of the vendors already provides (planted or otherwise
   *     pre-loaded) is not loaded twice: the loader skips it and the activation
   *     registers the component directly if Alpine booted without the editor.
   *  3. The served and cached `.pe-root` carries `x-ignore` — Alpine never initialises
   *     an ignored subtree (its own walk honours it; its MutationObserver never
   *     initialises added roots at all, verified in the vendored build). Activation is
   *     therefore EXCLUSIVE to this runtime: one component per root, per document.
   *  4. Before every history save (the `__dpHistoryStyleCleanups` registry shell.js
   *     dispatches through) the runtime invalidates pending activation, destroys the
   *     live tree, re-arms `x-ignore`, empties the canvas/minimap DOM it generated and
   *     strips the `style` attributes those libraries wrote — so the snapshot is inert
   *     and style-free (Cytoscape's re-applied inline style was the CSP collector's
   *     third #358 symptom) and the next restore starts clean.
   *
   * On a cached restore this file is the ONE script that replays; the guard below turns
   * the replay into another `enter()` on the restored root. On a cache MISS (and on the
   * first boosted arrival) the server-rendered fragment runs it the ordinary way.
   */

  if (window.PEPipelineRuntime) {
    window.PEPipelineRuntime.enter();
    return;
  }

  var ready = null;
  var loaded = {};
  var generation = 0;
  var EPOCH =
    "pe-rt-" + Date.now().toString(36) + "-" + Math.floor(Math.random() * 1e9).toString(36);

  /** The global each vendored catalog entry provides — a pre-existing one is not re-loaded. */
  var PROVIDED = {
    "alpine.min.js": "Alpine",
    "dagre.min.js": "dagre",
    "cytoscape.min.js": "cytoscape",
    "cytoscape-dagre.js": "cytoscape",
    "cytoscape-node-html-label.js": "cytoscape",
  };

  function providedBy(source) {
    var file = String(source).split("?")[0].split("/").pop();
    return PROVIDED[file] || null;
  }

  function loadScript(source) {
    if (loaded[source]) return Promise.resolve();
    return new Promise(function (resolve, reject) {
      var script = document.createElement("script");
      script.src = source;
      script.onload = function () {
        loaded[source] = true;
        resolve();
      };
      script.onerror = function () {
        script.remove();
        reject(new Error("Could not load the pipeline workspace runtime: " + source));
      };
      // The head is outside the history element: a cached restore must never replay these.
      document.head.appendChild(script);
    });
  }

  function catalogSources() {
    var catalog = document.getElementById("pe-runtime-scripts");
    if (!catalog) return [];
    return Array.prototype.map.call(
      catalog.content.querySelectorAll("script[src]"),
      function (script) {
        return script.getAttribute("src");
      },
    );
  }

  function dependencies() {
    if (ready) return ready;
    ready = catalogSources()
      .reduce(function (previous, source) {
        var provided = providedBy(source);
        if (provided && window[provided]) {
          // Singleton loading: the global is already there, so the catalog entry is
          // skipped — a second copy would boot a second Alpine (a second observer).
          loaded[source] = true;
          return previous;
        }
        return previous.then(function () {
          return loadScript(source);
        });
      }, Promise.resolve())
      .catch(function (error) {
        ready = null;
        throw error;
      });
    return ready;
  }

  function currentRoot() {
    var main = document.getElementById("app-main");
    return main && main.querySelector ? main.querySelector(".pe-root") : null;
  }

  async function enter() {
    var root = currentRoot();
    if (!root || root.__peActivation) return;
    root.__peActivation = true;
    var ownGeneration = generation;
    try {
      await dependencies();
      // Let the pending added-node records see `x-ignore` BEFORE the explicit init:
      // mutateDom QUEUES the records it displaces rather than discarding them, and
      // processing them must never initialize the root (they cannot — the observer
      // does not initialize roots — but the nextTick also lets the loaded modules'
      // own DOMContentLoaded work settle first).
      await window.Alpine.nextTick();
      if (!root.isConnected || ownGeneration !== generation || currentRoot() !== root) return;
      window.Alpine.mutateDom(function () {
        window.Alpine.destroyTree(root);
        root.removeAttribute("x-ignore");
        // The context BEFORE the component binds: the restored block is this page's
        // pin (workspace.js's one read path also clears a stale refusal flag).
        if (typeof window.PEWorkspaceRead === "function") window.PEWorkspaceRead();
        // A pre-booted Alpine never saw the editor's alpine:init registration (the
        // singleton gate's planted case); the registry fills here instead.
        if (window.pipelineEditor && window.Alpine.data && !window.__peComponentRegistered) {
          window.Alpine.data("pipelineEditor", window.pipelineEditor);
          window.__peComponentRegistered = true;
        }
        window.Alpine.initTree(root);
        root.setAttribute("data-pe-runtime-epoch", EPOCH);
      });
      window.PEPipelineRuntime.activations++;
    } catch (error) {
      if (!root.isConnected || ownGeneration !== generation) return;
      root.__peActivation = false;
      var notice = document.createElement("p");
      notice.setAttribute("role", "alert");
      notice.className = "ds-empty-description";
      notice.textContent = "The pipeline workspace could not load. Reload the page to try again.";
      root.replaceChildren(notice);
      if (window.console && window.console.error) window.console.error("Pipeline workspace runtime failed:", error);
    }
  }

  /**
   * The before-history-save cleanup (shell.js's registry calls it with the history
   * element — `#app-main` since the layout's `hx-history-elt`). Runs BEFORE htmx
   * clones the element (301 #301), so the snapshot holds the CLEANED tree: no live
   * bindings, no generated canvas DOM, no `style` attributes to trip the CSP on
   * restore, `x-ignore` re-armed for whoever might touch the restored markup.
   *
   * Every page's history save passes through here: a page without `.pe-root` is a
   * no-op, not a walk.
   */
  function beforeHistorySave(historyRoot) {
    var root = historyRoot && historyRoot.querySelector ? historyRoot.querySelector(".pe-root") : null;
    if (!root) return;
    generation++;
    function clean() {
      if (window.Alpine && window.Alpine.destroyTree) window.Alpine.destroyTree(root);
      root.setAttribute("x-ignore", "");
      var generated = root.querySelectorAll("#cy-canvas, #pe-minimap");
      for (var i = 0; i < generated.length; i++) generated[i].replaceChildren();
      var styled = root.querySelectorAll("[style]");
      for (var j = 0; j < styled.length; j++) styled[j].removeAttribute("style");
      root.removeAttribute("style");
      // The activation stamp dies with the snapshot: a restored root is
      // pre-activation until THIS runtime binds it again.
      root.removeAttribute("data-pe-runtime-epoch");
      delete root.__peActivation;
    }
    if (window.Alpine && window.Alpine.mutateDom) window.Alpine.mutateDom(clean);
    else clean();
  }

  window.PEPipelineRuntime = {
    enter: enter,
    /** Stable per-document identity (observable in the browser proof): restored pages
        keep the epoch — a page that boots a SECOND runtime fails the identity assert. */
    epoch: EPOCH,
    activations: 0,
  };

  window.__dpHistoryStyleCleanups = window.__dpHistoryStyleCleanups || [];
  window.__dpHistoryStyleCleanups.push(beforeHistorySave);

  enter();
})();
