(function () {
  "use strict";

  /*
   * dashboards/workspace.js (#400) — the dashboard WORKSPACE's tab state: the client half of
   * the page's closed tab set (Board | Overview | Refreshes | Versions | Keys, Board the
   * default; the server's DashboardWorkspaceTab enum resolves the page's tab by the SAME
   * rule). The admission/transition machine is the SHARED CORE (static/js/workspace/tabs.js —
   * built for every workspace; the pipeline editor adopts it under #420); what this
   * glue owns is the DOM effects the core deliberately does not:
   *
   *  - the tab strip: aria-selected moves, panes hide through the `hidden` ATTRIBUTE (never
   *    an inline style), re-selecting the active tab is inert;
   *  - the LAZY panes: Overview/Refreshes/Versions/Keys load their partial ONCE, on the
   *    tab's first activation (a hidden tab causes no fetch — the pipeline editor's rule);
   *  - the URL: a tab change PUSHES a history entry of the workspace's own (#402 —
   *    workspace/history.js, family `dashboards`, tab only: `?tab=` with every other
   *    parameter kept), and Back/Forward re-select the entry's tab IN PAGE through the
   *    helper's ONE window listener; an entry this root cannot replay (it left the
   *    document) is handed to htmx's own restore;
   *  - the Board pane's reveal: a chart the runtime booted while the pane was hidden (a
   *    `?tab=versions` deep link, a lifecycle redirect) recovers its slot size through the
   *    runtime instance's OWN resize (`window.__dpPage.instance.resize()` — the page's
   *    seam, the resize the runtime documents; no init option, no glue change).
   *
   * Version state does NOT live here, deliberately: every version switch is a FULL
   * navigation (hx-boost="false", dashboards.md §6.4's one-bundle rule — a draft's Plotly
   * bundle can differ from the release's), so the board's version pin stays where the
   * server wrote it, in `data-dp-dashboard-version` (dashboards-page.js reads it, unchanged).
   *
   * The decision core is pure and exported for `node --test`; the DOM wiring is guarded
   * (a restored document re-runs this file — the marker makes the second pass inert).
   */

  var DASHBOARD_TABS = ["board", "overview", "refreshes", "versions", "keys"];
  var DEFAULT_TAB = "board";

  /** The pure half: the tab set the RENDERED strip admits (Keys renders only for a caller
   *  with the binding permission, so the buttons ARE the admission), and the `?tab=` URL a
   *  selection states — always the same path, only the query changing. */
  var logic = {
    admittedTabs: function (renderedTabs) {
      return DASHBOARD_TABS.filter(function (tab) {
        return renderedTabs.indexOf(tab) !== -1;
      });
    },
    tabFromUrl: function (url, renderedTabs) {
      var match = /[?&]tab=([a-z]+)/.exec(url || "");
      var raw = match ? match[1] : null;
      var tabs = logic.admittedTabs(renderedTabs || DASHBOARD_TABS);
      return tabs.indexOf(raw) !== -1 ? raw : DEFAULT_TAB;
    },
    urlWithTab: function (url, tab) {
      // Keep every other query parameter (a named `version`, the layout's flash) — only
      // `tab` is the tab strip's own; a reload after a switch re-resolves the SAME page.
      var parts = (url || "").split("?");
      var params = new URLSearchParams(parts[1] || "");
      params.set("tab", tab);
      return parts[0] + "?" + params.toString();
    },
  };

  function wire() {
    var root = document.querySelector(".dp-ws-root");
    // The guard is an EXPANDO, never the attribute (#402): htmx's history snapshot is the
    // region's innerHTML, so a restored root carries `data-dp-ws-wired="1"` from the page it
    // was cloned from — an attribute guard left every restored strip unwired (dead tabs).
    // The attribute stays as the visible marker.
    if (!root || root.__dpWsWired === true) return;
    root.__dpWsWired = true;
    root.setAttribute("data-dp-ws-wired", "1");

    var buttons = Array.prototype.slice.call(root.querySelectorAll("[data-dp-tab]"));
    if (buttons.length === 0) return;
    var rendered = buttons.map(function (b) { return b.getAttribute("data-dp-tab"); });
    var tabs = window.WorkspaceTabs
      ? window.WorkspaceTabs.createTabSet({
          tabs: logic.admittedTabs(rendered),
          defaultTab: DEFAULT_TAB,
          initial: root.getAttribute("data-active-tab"),
        })
      : null;

    var loaded = {};

    function paneFor(tab) {
      return root.querySelector('[data-dp-pane="' + tab + '"]');
    }

    function loadLazy(pane) {
      var url = pane.getAttribute("data-lazy-url");
      if (!url || pane.getAttribute("data-lazy-loaded") === "1") return;
      pane.setAttribute("data-lazy-loaded", "1");
      if (window.htmx) {
        window.htmx.ajax("GET", url, { target: pane, swap: "innerHTML" });
      }
    }

    function revealBoard(pane) {
      // The runtime may have booted into this pane while it was hidden; its OWN resize
      // re-fits every chart to the slot the layout gave it.
      if (window.__dpPage && window.__dpPage.instance && typeof window.__dpPage.instance.resize === "function") {
        try {
          window.__dpPage.instance.resize();
        } catch (e) {
          /* a board that never booted has nothing to resize */
        }
      }
      if (window.htmx) window.htmx.process(pane);
    }

    var active = null;

    function apply(tab, options) {
      var previous = active;
      var next = tabs ? tabs.select(tab) : (rendered.indexOf(tab) !== -1 ? tab : DEFAULT_TAB);
      active = next;
      // #402: the root's tab attribute is what a restored root's first paint re-reads — kept
      // on the ACTIVE tab (the resolved name only), so a cached restore shows the tab it left.
      root.setAttribute("data-active-tab", next);
      buttons.forEach(function (button) {
        var name = button.getAttribute("data-dp-tab");
        button.setAttribute("aria-selected", name === next ? "true" : "false");
      });
      DASHBOARD_TABS.forEach(function (name) {
        var pane = paneFor(name);
        if (!pane) return;
        var visible = name === next;
        if (visible) {
          pane.removeAttribute("hidden");
          loadLazy(pane);
          if (name === "board") revealBoard(pane);
        } else {
          pane.setAttribute("hidden", "hidden");
        }
      });
      if (!options || options.url !== false) {
        if (window.WorkspaceHistory) {
          window.WorkspaceHistory.push("dashboards", { version: null, tab: previous }, { version: null, tab: next });
        } else {
          window.history.replaceState({ dpTab: next }, "", logic.urlWithTab(window.location.href, next));
        }
      }
    }

    buttons.forEach(function (button) {
      button.addEventListener("click", function () {
        apply(button.getAttribute("data-dp-tab"));
      });
    });

    // #402: the helper's ONE window listener replays this family's entries; the LIVE root
    // answers (re-registering replaces an earlier root's handler), and a root that left the
    // document — or a URL that is not this page's — answers false (htmx restores it).
    var wiredPath = window.location.pathname;
    if (window.WorkspaceHistory) {
      window.WorkspaceHistory.listen("dashboards", function (version, tab) {
        if (!root.isConnected || window.location.pathname !== wiredPath) return false;
        apply(typeof tab === "string" ? tab : DEFAULT_TAB, { url: false });
        return true;
      });
    }

    // First paint: the server resolved the tab; a non-default one loads its pane now.
    apply(root.getAttribute("data-active-tab") || DEFAULT_TAB, { url: false });
  }

  if (typeof module !== "undefined" && module.exports) module.exports = logic;
  if (typeof window !== "undefined") {
    window.DashboardWorkspaceLogic = logic;
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", wire);
    } else {
      wire();
    }
  }
})();
