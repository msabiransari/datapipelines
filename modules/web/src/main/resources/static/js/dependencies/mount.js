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
   *
   * A RELEASE-PLAN pane (`data-dp-deps-source="release-plan"`, the release dialog's "Show graph" region, §F.2) is the
   * same pane with a different source: its view is built by `DependencyTree.fromReleaseRows` from the plan rows the
   * dialog GET rendered around it (the nearest `[data-release-plan]`), so it makes NO request. Selecting one of its
   * nodes marks the matching plan row (`data-release-selected`) and never touches a checkbox; the Graph/List choice
   * is the page's stored one, and `unmount(dialog container)` tears it down like any pane.
   */

  if (typeof window === "undefined" || window.DependenciesMount) return;

  var NOTES = {
    idle: "Dependencies load when this tab is opened.",
    "idle-section": "Dependencies load when this section is opened.",
    "no-version": "Select a version to see its dependencies.",
    loading: "Loading dependencies…",
    "not-available": "These dependencies are not available.",
    "try-again": "The dependencies couldn't be read right now. Try again in a few seconds.",
    "server-failure": "The dependencies could not be loaded.",
    stale: "Version changed — reload to see this version's dependencies.",
  };
  /*
   * The Graph/List choice is per PAGE (brief §A.3): one localStorage key holds a JSON list of [path, view] pairs,
   * newest last, at most VIEW_PAGES_MAX — a new page drops the oldest, so the key never grows past that bound. The page
   * identity is the path without its query string, so a page keeps its choice across its own `?tab=` and
   * `?version=` changes. Only the path and the word "graph" or "list" are written; every pair is validated on read
   * and anything else is ignored (the page's default stands). A page never chosen on: List under
   * prefers-reduced-motion, Graph otherwise.
   */
  var VIEW_KEY = "dp.deps.views";
  var VIEW_PAGES_MAX = 50;
  var VIEWS = ["graph", "list"];
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

  function pageIdentity() {
    return window.location.pathname;
  }

  /** The stored pairs that are well formed; a missing, refused or malformed key reads as none. */
  function storedViews() {
    var raw = null;
    try {
      raw = window.localStorage ? window.localStorage.getItem(VIEW_KEY) : null;
    } catch (_) {
      return []; // storage refused (privacy mode): every page keeps its default
    }
    if (raw === null) return [];
    var parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (_) {
      return [];
    }
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter(function (pair) {
        return Array.isArray(pair) && pair.length === 2 && typeof pair[0] === "string" && VIEWS.indexOf(pair[1]) !== -1;
      })
      .slice(-VIEW_PAGES_MAX);
  }

  function preferredView() {
    var page = pageIdentity();
    var pairs = storedViews();
    for (var i = pairs.length - 1; i >= 0; i--) if (pairs[i][0] === page) return pairs[i][1];
    var reduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    return reduced ? "list" : "graph";
  }

  function rememberView(view) {
    if (VIEWS.indexOf(view) === -1) return;
    var page = pageIdentity();
    var pairs = storedViews().filter(function (pair) {
      return pair[0] !== page;
    });
    pairs.push([page, view]);
    try {
      if (window.localStorage) window.localStorage.setItem(VIEW_KEY, JSON.stringify(pairs.slice(-VIEW_PAGES_MAX)));
    } catch (_) {
      /* not persisted (refused or full); the toggle still works for this page */
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
      // A pane in a details section (schedule, datasource, API pages) is opened, not selected as a tab.
      var key = name === "idle" && pane.closest("details") ? "idle-section" : name;
      note.textContent = extra !== undefined ? extra : NOTES[key] || "";
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
      if (planRoot()) markPlanRow(id);
    }

    /** The release dialog's plan this pane draws, or null for a tree pane. */
    function planRoot() {
      if (pane.getAttribute("data-dp-deps-source") !== "release-plan") return null;
      return pane.closest("[data-release-plan]");
    }

    /** Marks the plan row of node [id] (compared as text, never built into a selector); other rows lose the mark. */
    function markPlanRow(id) {
      var root = planRoot();
      if (!root) return;
      var rows = root.querySelectorAll("[data-release-row][data-node-id]");
      for (var i = 0; i < rows.length; i++) {
        if (rows[i].getAttribute("data-node-id") === id) rows[i].setAttribute("data-release-selected", "");
        else rows[i].removeAttribute("data-release-selected");
      }
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
      renderView(view);
    }

    function renderView(view) {
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
      var plan = planRoot();
      if (pane.getAttribute("data-dp-deps-source") === "release-plan") {
        // The release dialog's graph: drawn from the rendered plan rows, never fetched (record §12).
        var planView = null;
        try {
          planView = plan && window.DependencyTree.fromReleaseRows ? window.DependencyTree.fromReleaseRows(plan) : null;
        } catch (_) {
          planView = null;
        }
        if (planView) renderView(planView);
        else setState("server-failure");
        return;
      }
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
      // A save that does NOT replace the page (a pushed URL over a partial swap) leaves this pane live: bind it again
      // once the save's task is over, so it never stays blank. A replaced page has disconnected it by then.
      bound.delete(pane);
      setTimeout(function () {
        if (pane.isConnected) bind(pane);
      }, 0);
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

  /** History save or pane replacement: tear down every bound pane inside the root (CSP-clean, inert snapshot). */
  function beforeHistorySave(historyRoot) {
    live.slice().forEach(function (state) {
      if (!historyRoot || !historyRoot.contains || historyRoot.contains(state.pane)) state.teardown();
    });
  }

  window.DependenciesMount = function (main) {
    scan(main || document.getElementById("app-main") || document.body);
  };
  window.DependenciesMount.scan = scan;
  // Call before removing a client-rendered pane: abort its read, disconnect observers and destroy its graph.
  window.DependenciesMount.unmount = beforeHistorySave;
  // The per-page view choice, for the unit test that drives this file in a VM (no page code calls these).
  window.DependenciesMount._views = { preferred: preferredView, remember: rememberView, key: VIEW_KEY, max: VIEW_PAGES_MAX };
  window.__dpHistoryStyleCleanups = window.__dpHistoryStyleCleanups || [];
  window.__dpHistoryStyleCleanups.push(beforeHistorySave);
})();
