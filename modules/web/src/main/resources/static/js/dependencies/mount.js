(function () {
  "use strict";

  /*
   * dependencies/mount.js (#876 G6, brief §A.6) — the Dependencies pane's mount, pinned to the page-script loader as
   * it is (page-assets.mjs): this file is a catalog entry carrying `data-page-mount`, and the loader calls
   * `window.DependenciesMount(main)` once per catalog node — a cold load, a boosted navigation and every history
   * restore. The function finds the panes itself (`[data-dp-deps]` under main) and binds each pane ELEMENT once (a
   * WeakSet): a second call binds only panes it has not seen, so a restore — new elements — binds again.
   * `DependenciesMount.scan(root)` does the same under any root (a page that clones a pane later).
   *
   * A pane is static server markup: `<div data-dp-deps data-family=… data-name=… data-version=… | data-id=…>` with a
   * status note, a graph host and a list host. Activation is LAZY and uniform: the first load runs when the pane is
   * visible — at bind time if no ancestor is `hidden` or a closed `<details>`, otherwise when `hidden` leaves the
   * ancestor that carried it (one MutationObserver, attributeFilter ["hidden"]; every workspace strip hides its panes
   * with the attribute) or the `<details>` opens. A hidden pane makes no request. An artifact pane without a version
   * is the choose-a-version state: a note and NO request. A `data-version` change aborts the pending load and loads
   * again (a hidden pane goes "stale" and loads when shown); a response for a pane that left the document is dropped
   * (source.js's generation guard drops a superseded one, A26).
   *
   * Before every history save (the `__dpHistoryStyleCleanups` registry shell.js dispatches) each bound pane under the
   * saved root is torn down — Cytoscape destroyed, its style attributes gone, the note back to idle — so the
   * snapshot is inert and CSP-clean and the restored pane starts fresh. The pane root carries the ready marker
   * `data-dp-deps-state`: idle | no-version | loading | ready | empty | incomplete | cycle | limit | not-available |
   * try-again | server-failure | stale.
   */

  if (typeof window === "undefined" || window.DependenciesMount) return;

  var NOTES = {
    idle: "Dependencies load when this tab is opened.",
    "no-version": "Select a version to see its dependencies.",
    loading: "Loading dependencies…",
    "not-available": "These dependencies are not available.",
    "try-again": "The dependencies couldn't be read right now. Try again in a few seconds.",
    "server-failure": "The dependencies could not be loaded.",
    stale: "Version changed — reload to see this version's dependencies.",
  };
  var VIEW_KEY = "dp.deps.view";
  var bound = new WeakSet();
  var live = [];

  function child(pane, attribute, tag, className) {
    var found = pane.querySelector("[" + attribute + "]");
    if (found) return found;
    var node = document.createElement(tag);
    node.setAttribute(attribute, "");
    if (className) node.className = className;
    pane.appendChild(node);
    return node;
  }

  /** The ancestor that hides the pane right now (a `hidden` element or a closed details), or null. */
  function hider(pane) {
    for (var node = pane; node && node.nodeType === 1; node = node.parentElement) {
      if (node.hasAttribute("hidden")) return node;
      if (node.tagName === "DETAILS" && !node.open && node !== pane) return node;
    }
    return null;
  }

  function selectorOf(pane) {
    return {
      family: pane.getAttribute("data-family"),
      name: pane.getAttribute("data-name"),
      version: pane.getAttribute("data-version"),
      id: pane.getAttribute("data-id"),
    };
  }

  function preferredView() {
    try {
      var stored = window.localStorage ? window.localStorage.getItem(VIEW_KEY) : null;
      if (stored === "graph" || stored === "list") return stored;
    } catch (_) {
      /* storage refused (privacy mode): the default stands */
    }
    var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    return reduced ? "list" : "graph";
  }

  function rememberView(view) {
    try {
      if (window.localStorage) window.localStorage.setItem(VIEW_KEY, view);
    } catch (_) {
      /* not persisted; the toggle still works for this page */
    }
  }

  function bind(pane) {
    if (bound.has(pane)) return;
    bound.add(pane);
    var note = child(pane, "data-dp-deps-note", "p", "dp-deps-note");
    note.setAttribute("role", "status");
    if (!note.id) note.id = "dp-deps-note-" + Math.random().toString(36).slice(2, 10);
    var toggle = child(pane, "data-dp-deps-toggle", "div", "dp-deps-toggle");
    var notes = child(pane, "data-dp-deps-notes", "div", "dp-deps-notes");
    var graphHost = child(pane, "data-dp-deps-graph", "div", "dp-deps-graph");
    var listHost = child(pane, "data-dp-deps-list-host", "div", "dp-deps-list");
    var loader = window.DependencySource.createLoader();
    var state = {
      pane: pane,
      loaded: false,
      stale: false,
      view: null,
      graph: null,
      list: null,
      mode: preferredView(),
      observer: null,
      versionObserver: null,
      watched: null,
      retryTimer: null,
      selected: null,
    };

    function setState(name, extra) {
      pane.setAttribute("data-dp-deps-state", name);
      note.textContent = extra !== undefined ? extra : NOTES[name] || "";
    }

    function clearViews() {
      if (state.graph) state.graph.destroy();
      state.graph = null;
      state.list = null;
      state.view = null;
      graphHost.replaceChildren();
      listHost.replaceChildren();
      notes.replaceChildren();
      toggle.replaceChildren();
      graphHost.hidden = true;
      listHost.hidden = true;
      toggle.hidden = true;
    }

    function onSelect(id) {
      state.selected = id;
      if (state.list) state.list.select(id);
      if (state.graph) state.graph.select(id);
    }

    function showMode() {
      var graph = state.mode === "graph";
      graphHost.hidden = !graph;
      listHost.hidden = graph;
      Array.prototype.forEach.call(toggle.querySelectorAll("button"), function (b) {
        b.setAttribute("aria-pressed", b.getAttribute("data-dp-deps-view") === state.mode ? "true" : "false");
      });
      if (graph && !state.graph && state.view) {
        // Cytoscape lays out against the container's size: a pane hidden again before its answer arrived draws
        // when it is shown, never into a zero-sized box.
        var blocker = hider(pane);
        if (blocker) {
          watch(blocker);
          return;
        }
        try {
          state.graph = window.DependencyTree.mount(graphHost, state.view, { onSelect: onSelect });
          if (state.selected) state.graph.select(state.selected);
        } catch (error) {
          // A graph that cannot draw (the vendor failed to load) leaves the list as the view.
          if (window.console && console.error) console.error("Dependency graph could not render:", error);
          state.mode = "list";
          showMode();
        }
      }
    }

    function buildToggle() {
      toggle.setAttribute("role", "group");
      toggle.setAttribute("aria-label", "Dependency view");
      ["graph", "list"].forEach(function (mode) {
        var b = document.createElement("button");
        b.type = "button";
        b.className = "ds-button ds-button-secondary ds-button-sm";
        b.setAttribute("data-dp-deps-view", mode);
        b.textContent = mode === "graph" ? "Graph" : "List";
        b.addEventListener("click", function () {
          state.mode = mode;
          rememberView(mode);
          showMode();
        });
        toggle.appendChild(b);
      });
      toggle.hidden = false;
    }

    function render(data) {
      var view;
      try {
        view = window.DependencyTree.fromTreePayload(data);
      } catch (_) {
        setState("server-failure");
        return;
      }
      state.view = view;
      view.notes.forEach(function (text) {
        var p = document.createElement("p");
        p.className = "dp-deps-text";
        p.textContent = text;
        notes.appendChild(p);
      });
      buildToggle();
      state.list = window.DependencyList.render(listHost, view, { onSelect: onSelect, describedBy: note.id });
      var count = view.nodes.length + (view.nodes.length === 1 ? " object, " : " objects, ") +
        view.edges.length + (view.edges.length === 1 ? " relationship." : " relationships.");
      setState(view.state, count);
      showMode();
    }

    function retryButton(after) {
      var b = document.createElement("button");
      b.type = "button";
      b.className = "ds-button ds-button-secondary ds-button-sm";
      b.textContent = "Try again";
      b.disabled = after > 0;
      b.addEventListener("click", load);
      notes.appendChild(b);
      if (after > 0) {
        state.retryTimer = setTimeout(function () {
          b.disabled = false;
        }, after * 1000);
      }
    }

    async function load() {
      if (state.retryTimer) clearTimeout(state.retryTimer);
      state.retryTimer = null;
      clearViews();
      state.loaded = true;
      state.stale = false;
      var selector = selectorOf(pane);
      if (window.DependencySource.requestUrl(selector) === null) {
        loader.abort();
        setState("no-version");
        return;
      }
      setState("loading");
      var result = await loader.request(selector);
      if (result === null) return; // superseded or aborted: never rendered
      if (!pane.isConnected) {
        release();
        return;
      }
      if (result.state === "ready") render(result.data);
      else {
        setState(result.state);
        if (result.state === "try-again") retryButton(result.retryAfter || 0);
        if (result.state === "server-failure") retryButton(0);
      }
    }

    function release() {
      loader.abort();
      if (state.observer) state.observer.disconnect();
      if (state.versionObserver) state.versionObserver.disconnect();
      if (state.watched && state.watched.tagName === "DETAILS") state.watched.removeEventListener("toggle", onVisibility);
      if (state.retryTimer) clearTimeout(state.retryTimer);
      state.observer = null;
      state.versionObserver = null;
      state.watched = null;
      var index = live.indexOf(state);
      if (index !== -1) live.splice(index, 1);
    }

    function onVisibility() {
      if (!pane.isConnected) {
        release();
        return;
      }
      var blocker = hider(pane);
      if (blocker) {
        watch(blocker);
        return;
      }
      unwatch();
      if (!state.loaded || state.stale) load();
      else if (state.mode === "graph" && state.view && !state.graph) showMode();
    }

    function unwatch() {
      if (state.observer) state.observer.disconnect();
      if (state.watched && state.watched.tagName === "DETAILS") state.watched.removeEventListener("toggle", onVisibility);
      state.observer = null;
      state.watched = null;
    }

    function watch(blocker) {
      if (state.watched === blocker) return;
      unwatch();
      state.watched = blocker;
      if (blocker.tagName === "DETAILS" && !blocker.hasAttribute("hidden")) {
        blocker.addEventListener("toggle", onVisibility);
      } else if (typeof MutationObserver === "function") {
        state.observer = new MutationObserver(onVisibility);
        state.observer.observe(blocker, { attributes: true, attributeFilter: ["hidden"] });
      }
    }

    if (typeof MutationObserver === "function") {
      state.versionObserver = new MutationObserver(function () {
        if (!pane.isConnected) {
          release();
          return;
        }
        loader.abort();
        if (!state.loaded) return; // never opened: the first activation reads the new version
        if (hider(pane)) {
          clearViews();
          state.stale = true;
          setState("stale");
          onVisibility();
        } else load();
      });
      state.versionObserver.observe(pane, { attributes: true, attributeFilter: ["data-version", "data-name", "data-id"] });
    }

    state.teardown = function () {
      release();
      clearViews();
      setState("idle");
      var styled = pane.querySelectorAll("[style]");
      for (var i = 0; i < styled.length; i++) styled[i].removeAttribute("style");
    };
    live.push(state);
    // The restored copy of a snapshot carries whatever the saved page drew: start from nothing.
    clearViews();
    setState("idle");
    onVisibility();
  }

  function scan(root) {
    if (!root || !root.querySelectorAll || !window.DependencySource || !window.DependencyTree || !window.DependencyList) return;
    var panes = root.querySelectorAll("[data-dp-deps]");
    for (var i = 0; i < panes.length; i++) bind(panes[i]);
  }

  /** History save: tear down every bound pane inside the saved root (CSP-clean, inert snapshot). */
  function beforeHistorySave(historyRoot) {
    live.slice().forEach(function (state) {
      if (!historyRoot || !historyRoot.contains || historyRoot.contains(state.pane)) state.teardown();
    });
  }

  window.DependenciesMount = function (main) {
    scan(main || document.getElementById("app-main") || document.body);
  };
  window.DependenciesMount.scan = scan;
  window.__dpHistoryStyleCleanups = window.__dpHistoryStyleCleanups || [];
  window.__dpHistoryStyleCleanups.push(beforeHistorySave);
})();
