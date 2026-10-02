(function () {
  "use strict";

  /*
   * dashboards/workspace.js (#400) — the dashboard WORKSPACE's tab state: the client half of
   * the page's closed tab set (Board | Overview | Refreshes | Versions | Keys, Board the
   * default; the server's DashboardWorkspaceTab enum resolves the page's tab by the SAME
   * rule). The admission/transition machine is the SHARED CORE (static/js/workspace/tabs.js —
   * pipeline-editor/tabs.js runs it too; the reuse is the point, never a copy); what this
   * glue owns is the DOM effects the core deliberately does not:
   *
   *  - the tab strip: aria-selected moves, panes hide through the `hidden` ATTRIBUTE (never
   *    an inline style), re-selecting the active tab is inert;
   *  - the LAZY panes: Overview/Refreshes/Versions/Keys load their partial ONCE, on the
   *    tab's first activation (a hidden tab causes no fetch — the pipeline editor's rule);
   *  - the URL: a tab change replaceStates `?tab=` onto the current history entry, and a
   *    popstate re-selects from the URL — Back/Forward across IN-PAGE tab switches works as
   *    #350 left it for the pipeline editor;
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
    if (!root || root.getAttribute("data-dp-ws-wired") === "1") return;
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

    function apply(tab, options) {
      var next = tabs ? tabs.select(tab) : (rendered.indexOf(tab) !== -1 ? tab : DEFAULT_TAB);
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
        var url = logic.urlWithTab(window.location.href, next);
        window.history.replaceState({ dpTab: next }, "", url);
      }
    }

    buttons.forEach(function (button) {
      button.addEventListener("click", function () {
        apply(button.getAttribute("data-dp-tab"));
      });
    });

    window.addEventListener("popstate", function () {
      apply(logic.tabFromUrl(window.location.href, rendered), { url: false });
    });

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
