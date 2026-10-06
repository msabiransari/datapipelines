(function () {
  "use strict";

  /*
   * #399 — the workspace PANE glue, generic over a family's closed tab set: the DOM half that
   * dashboards/workspace.js (#400) wrote for one family, lifted so the next family's page is a
   * configuration, not a copy. The admission/transition machine stays the shared core
   * (workspace/tabs.js, window.WorkspaceTabs); this file owns what the core deliberately does not:
   *
   *  - the strip: aria-selected moves; panes hide through the `hidden` ATTRIBUTE (never an
   *    inline style); re-selecting the active tab is inert;
   *  - LAZY panes: a pane's `data-lazy-url` partial loads ONCE, on the tab's first activation
   *    (a hidden tab causes no fetch);
   *  - the URL: a tab change PUSHES an entry of the workspace's own (#426 — workspace/history.js,
   *    the family the page names, tab only: `?tab=` with every other parameter kept), and
   *    Back/Forward re-select the entry's tab IN PAGE through the helper's ONE window listener;
   *    an entry this root cannot replay (it left the document) is handed to htmx's own restore.
   *    Without a family (or without the helper) a change replaceStates `?tab=` and a per-root
   *    popstate listener re-selects from the URL — the pre-#426 behaviour, kept so the module
   *    stays generic;
   *  - a per-family `onReveal(tab, pane)` hook for what a revealed pane must re-fit.
   *
   * The markup contract: `.dp-ws-root[data-active-tab]` holding `[data-dp-tab]` buttons and
   * `[data-dp-pane]` panes (board.html's and visualizations/workspace.html's shape).
   *
   * The decision half is pure and exported for `node --test`; the DOM wiring is guarded (a
   * restored document re-runs a family's glue — the guard makes a second pass over the SAME
   * root inert, and lets a restored, freshly cloned root wire again).
   */

  /** The pure half, over one family's closed set (default first unless named). */
  function createLogic(allTabs, defaultTab) {
    var fallback = defaultTab || allTabs[0];
    var logic = {
      /** The rendered strip IS the admission: the closed set's order, filtered by what rendered. */
      admittedTabs: function (renderedTabs) {
        return allTabs.filter(function (tab) {
          return renderedTabs.indexOf(tab) !== -1;
        });
      },
      /** The URL's `tab`, only when the strip admits it; anything else is the default. */
      tabFromUrl: function (url, renderedTabs) {
        var match = /[?&]tab=([a-z-]+)/.exec(url || "");
        var raw = match ? match[1] : null;
        var tabs = logic.admittedTabs(renderedTabs || allTabs);
        return tabs.indexOf(raw) !== -1 ? raw : fallback;
      },
      /** The same path with only `tab` replaced — a reload re-resolves the SAME page. */
      urlWithTab: function (url, tab) {
        var parts = (url || "").split("?");
        var params = new URLSearchParams(parts[1] || "");
        params.set("tab", tab);
        return parts[0] + "?" + params.toString();
      },
    };
    return logic;
  }

  /**
   * Wires one workspace root. Returns `{ select(tab) }`, or null when the page has no root
   * (the choose-a-version state) or was already wired.
   *
   * @param {Object} spec
   * @param {string[]} spec.tabs           the family's closed set, in strip order
   * @param {string} spec.defaultTab       the server enum's default
   * @param {string} [spec.family]         the history family (#426); absent = replaceState only
   * @param {function(string, Element)} [spec.onReveal]  called after a pane becomes visible
   */
  function wireWorkspace(spec) {
    var root = spec.root || document.querySelector(".dp-ws-root");
    // The guard is an EXPANDO, never the attribute (#402): htmx's history snapshot is the
    // region's innerHTML, so a restored root carries `data-dp-ws-wired="1"` from the page it
    // was cloned from — an attribute guard left every restored strip unwired (dead tabs).
    // The attribute stays as the visible marker.
    if (!root || root.__dpWsWired === true) return null;
    root.__dpWsWired = true;
    root.setAttribute("data-dp-ws-wired", "1");

    var logic = createLogic(spec.tabs, spec.defaultTab);
    var buttons = Array.prototype.slice.call(root.querySelectorAll("[data-dp-tab]"));
    if (buttons.length === 0) return null;
    var rendered = buttons.map(function (b) { return b.getAttribute("data-dp-tab"); });
    var tabs = window.WorkspaceTabs
      ? window.WorkspaceTabs.createTabSet({
          tabs: logic.admittedTabs(rendered),
          defaultTab: spec.defaultTab,
          initial: root.getAttribute("data-active-tab"),
        })
      : null;

    function paneFor(tab) {
      return root.querySelector('[data-dp-pane="' + tab + '"]');
    }

    function loadLazy(pane) {
      var url = pane.getAttribute("data-lazy-url");
      if (!url || pane.getAttribute("data-lazy-loaded") === "1") return;
      pane.setAttribute("data-lazy-loaded", "1");
      if (window.htmx) window.htmx.ajax("GET", url, { target: pane, swap: "innerHTML" });
    }

    var helper = window.WorkspaceHistory && spec.family ? window.WorkspaceHistory : null;
    var active = null;

    function apply(tab, options) {
      var previous = active;
      var next = tabs ? tabs.select(tab) : (rendered.indexOf(tab) !== -1 ? tab : spec.defaultTab);
      active = next;
      // #402: the root's tab attribute is what a restored root's first paint re-reads — kept on
      // the ACTIVE tab (the resolved name only), so a cached restore shows the tab it left.
      root.setAttribute("data-active-tab", next);
      buttons.forEach(function (button) {
        button.setAttribute("aria-selected", button.getAttribute("data-dp-tab") === next ? "true" : "false");
      });
      spec.tabs.forEach(function (name) {
        var pane = paneFor(name);
        if (!pane) return;
        if (name === next) {
          pane.removeAttribute("hidden");
          loadLazy(pane);
          if (typeof spec.onReveal === "function") spec.onReveal(name, pane);
        } else {
          pane.setAttribute("hidden", "hidden");
        }
      });
      if (!options || options.url !== false) {
        if (helper) {
          helper.push(spec.family, { version: null, tab: previous }, { version: null, tab: next });
        } else {
          window.history.replaceState({ dpTab: next }, "", logic.urlWithTab(window.location.href, next));
        }
      }
      return next;
    }

    buttons.forEach(function (button) {
      button.addEventListener("click", function () {
        apply(button.getAttribute("data-dp-tab"));
      });
    });
    if (helper) {
      // #426: the helper's ONE window listener replays this family's entries; the LIVE root
      // answers (re-registering replaces an earlier root's handler), and a root that left the
      // document — or a URL that is not this page's — answers false (htmx restores it).
      var wiredPath = window.location.pathname;
      helper.listen(spec.family, function (version, tab) {
        if (!root.isConnected || window.location.pathname !== wiredPath) return false;
        apply(typeof tab === "string" ? tab : spec.defaultTab, { url: false });
        return true;
      });
    } else {
      // No family or no helper: the per-root listener of #399 — the only path that keeps one.
      window.addEventListener("popstate", function () {
        apply(logic.tabFromUrl(window.location.href, rendered), { url: false });
      });
    }
    // First paint: the server resolved the tab; a non-default one loads its pane now.
    apply(root.getAttribute("data-active-tab") || spec.defaultTab, { url: false });

    return { select: apply, root: root };
  }

  var api = { createLogic: createLogic, wireWorkspace: wireWorkspace };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.WorkspacePanes = api;
})();
