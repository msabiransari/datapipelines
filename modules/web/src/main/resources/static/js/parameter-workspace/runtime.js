(function () {
  "use strict";

  /*
   * The Parameter Sets workspace's ONE runtime, ONE initializer per document (#374) — the pipeline workspace's
   * #358 contract (pipeline-editor/runtime.js), for this page. htmx caches the history element's innerHTML and a cached
   * restore RE-CREATES every <script> in it, so the vendors and modules are NOT bare tags in the fragment: they ride an
   * inert <template id="ps-runtime-scripts"> catalog this runtime loads IN ORDER, once per document, and the only
   * executable script a restored snapshot holds is this guarded bootstrap (a replay becomes another `enter()`).
   *
   * Before every history save (the `__dpHistoryStyleCleanups` registry shell.js dispatches through) the runtime
   * unmounts the live tree — disposing the evaluate instance and the graph — and strips the `style` attributes those
   * libraries wrote, so the snapshot is inert and style-free and the next restore starts clean.
   */

  if (window.PSRuntime) {
    window.PSRuntime.enter();
    return;
  }

  var ready = null;
  var loaded = {};
  var generation = 0;

  /** The global each vendored catalog entry provides — one that is already there is not loaded twice. */
  var PROVIDED = {
    "dagre.min.js": "dagre",
    "cytoscape.min.js": "cytoscape",
    "datapipelines-dashboard.js": "DatapipelinesDashboard",
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
        reject(new Error("Could not load the parameter workspace runtime: " + source));
      };
      document.head.appendChild(script); // the head is outside the history element
    });
  }

  function catalogSources() {
    var catalog = document.getElementById("ps-runtime-scripts");
    if (!catalog) return [];
    return Array.prototype.map.call(catalog.content.querySelectorAll("script[src]"), function (script) {
      return script.getAttribute("src");
    });
  }

  function dependencies() {
    if (ready) return ready;
    ready = catalogSources()
      .reduce(function (previous, source) {
        var provided = providedBy(source);
        if (provided && window[provided]) {
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
    return main && main.querySelector ? main.querySelector(".ps-root") : null;
  }

  async function enter() {
    var root = currentRoot();
    if (!root || root.__psActivation) return;
    root.__psActivation = true;
    var own = generation;
    try {
      await dependencies();
      if (!root.isConnected || own !== generation || currentRoot() !== root) return;
      window.PSWorkspace.mount(root);
      window.PSRuntime.activations++;
    } catch (error) {
      if (!root.isConnected || own !== generation) return;
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
    generation++;
    if (window.PSWorkspace) window.PSWorkspace.unmount(root);
    var styled = root.querySelectorAll("[style]");
    for (var i = 0; i < styled.length; i++) styled[i].removeAttribute("style");
    root.removeAttribute("style");
    delete root.__psActivation;
  }

  window.PSRuntime = { enter: enter, activations: 0 };
  window.__dpHistoryStyleCleanups = window.__dpHistoryStyleCleanups || [];
  window.__dpHistoryStyleCleanups.push(beforeHistorySave);

  enter();
})();
