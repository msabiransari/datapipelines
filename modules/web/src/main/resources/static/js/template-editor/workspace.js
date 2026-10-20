(function () {
  "use strict";

  /*
   * #398 — the template workspace's six-tab state machine: Source (the default) |
   * Overview | Render | Runs | Used by | Versions. PURE at the core (no DOM, no fetch), so
   * `node --test` owns the admission and transition tables — the same harness decision
   * pipeline-editor/tabs.js got — with a thin DOM glue (a delegated click listener and the
   * URL rewrite) in the browser half.
   *
   * What the module decides:
   *
   *  - The closed tab set and its wire names — the mirror of the server's
   *    TemplateWorkspaceTab enum. The server already resolves the page's tab (unknown or
   *    missing → source; render without the author capability, or for a script, → source
   *    BEFORE any render state is read); this module enforces the same rule on every
   *    CLIENT-side tab change, so a hidden tab is inert twice over.
   *  - Admission: Render is the render-context + preview tab — its POST is MUTATE
   *    (`template.render`) and it exists to feed Preview, so a caller without the author
   *    capability never selects it (143's rule); the pane is not even rendered for them.
   *  - Transitions: selecting a tab moves `active`; re-selecting the active tab is inert.
   *    There is no "off" state — one tab is always active, exactly one panel visible.
   *
   * What does NOT live here: the Runs tab's one lazy load (the button's own
   * hx-trigger="click once") and every fetch. Tab changes are NAVIGATION ONLY — no fetch is
   * cancelled and no in-flight state exists to lose (a template does not execute).
   *
   * URL contract (#528): a USER switch pushes a tab-only `templates` entry through
   * WorkspaceHistory, with `?tab=` always stated. First paint keeps the arrival URL;
   * Back/Forward re-select in page without a URL write. The helper keeps htmx's cache path
   * current, so Back after a boosted leave is a cache hit. Detached roots hand off to htmx.
   * The version selector's rows carry the CURRENT tab (`&tab=`), so a version switch — a
   * full navigation, there is no active run to protect — lands on the tab the reader was
   * reading; after every in-page switch the glue re-stamps those hrefs.
   */

  var SOURCE = "source";
  var OVERVIEW = "overview";
  var RENDER = "render";
  var RUNS = "runs";
  var USED_BY = "used-by";
  var VERSIONS = "versions";
  var TABS = [SOURCE, OVERVIEW, RENDER, RUNS, USED_BY, VERSIONS];

  /** The admission rule, shared by the server enum and this mirror: unknown → source. */
  function resolve(raw, canRender) {
    var tab = null;
    for (var i = 0; i < TABS.length; i++) {
      if (TABS[i] === raw) {
        tab = raw;
        break;
      }
    }
    if (tab === RENDER && canRender !== true) return SOURCE;
    return tab || SOURCE;
  }

  function createTabs(canRender, initial) {
    var start = resolve(initial, canRender === true);
    return {
      canRender: canRender === true,
      active: start,

      /** A tab change. Unknown names and unadmitted tabs are inert (Source stays). */
      select: function (tab) {
        this.active = resolve(tab, this.canRender);
        return this.active;
      },

      /** True when the tab's panel is the visible one. */
      isActive: function (tab) {
        return this.active === tab;
      },

      /** The URL query value the canonical route reads for this tab. */
      wire: function () {
        return this.active;
      },
    };
  }

  // ------------------------------------------------------------------ DOM glue

  function doc() {
    return typeof document === "undefined" ? null : document;
  }

  /** The `tab=` query parameter of [href] rewritten to [tab]; other parameters untouched. */
  function withTab(href, tab) {
    if (!href) return href;
    var base = href.split("?")[0];
    var params = new URLSearchParams(href.split("?")[1] || "");
    params.set("tab", tab);
    var query = params.toString();
    return query ? base + "?" + query : base;
  }

  function arm() {
    var d = doc();
    var root = d && d.querySelector(".tw-root");
    if (!d || !root || root.__twWired) return;
    root.__twWired = true;
    var canRender = root.getAttribute("data-can-render") === "true";
    var tabs = createTabs(canRender, readActiveTab(root));
    var helper = window.WorkspaceHistory || null;

    function readActiveTab(scope) {
      var active = scope.querySelector('[data-tw-tab][aria-selected="true"]');
      return active ? active.getAttribute("data-tw-tab") : null;
    }

    function paint(options, previous) {
      Array.prototype.forEach.call(root.querySelectorAll("[data-tw-tab]"), function (button) {
        var on = tabs.isActive(button.getAttribute("data-tw-tab"));
        button.setAttribute("aria-selected", on ? "true" : "false");
      });
      Array.prototype.forEach.call(root.querySelectorAll(".tw-pane"), function (pane) {
        // Which pane a tab button controls — the pane whose id the button's aria-controls names.
        var owner = root.querySelector('[aria-controls="' + pane.id + '"]');
        pane.hidden = !owner || !tabs.isActive(owner.getAttribute("data-tw-tab"));
      });
      // The version selector's rows carry the CURRENT tab, so a version switch — a full
      // navigation — lands where the reader is.
      Array.prototype.forEach.call(root.querySelectorAll(".tw-version-link"), function (link) {
        link.setAttribute("href", withTab(link.getAttribute("href"), tabs.wire()));
      });
      if (!options || options.url !== false) {
        if (helper) {
          helper.push("templates", { version: null, tab: previous }, { version: null, tab: tabs.wire() });
        } else if (typeof URL === "function" && typeof history !== "undefined" && history.replaceState) {
          var url = withTab(window.location.pathname + window.location.search, tabs.wire());
          history.replaceState(history.state, "", url);
        }
      }
    }

    root.addEventListener("click", function (evt) {
      var button = evt.target.closest && evt.target.closest("[data-tw-tab]");
      if (!button || !root.contains(button)) return;
      var previous = tabs.active;
      var next = tabs.select(button.getAttribute("data-tw-tab"));
      if (next === button.getAttribute("data-tw-tab")) paint(null, previous);
    });
    if (helper) {
      var wiredPath = window.location.pathname;
      helper.listen("templates", function (version, tab) {
        if (!root.isConnected || window.location.pathname !== wiredPath) return false;
        tabs.select(typeof tab === "string" ? tab : SOURCE);
        paint({ url: false });
        return true;
      });
    }
    paint({ url: false });
  }

  // #514: execution defines the API once; the page-script loader mounts each arrival and
  // cached restore with its page in place. The root's own flag keeps repeated mounts inert.
  if (typeof window !== "undefined") {
    window.TplWorkspace = { createTabs: createTabs, resolve: resolve, TABS: TABS, withTab: withTab };
    window.TemplateWorkspaceMount = function (main) {
      arm();
      // lifecycle.js now survives navigation too: its closure must follow this page's
      // visible context mode (fresh Key/Value, or the mode serialized in a history entry).
      var context = main.querySelector("#context-json");
      if (context) window.TplLifecycle.switchTab(context.classList.contains("u-hidden") ? "kv" : "json");
    };
  }

  var api = { createTabs: createTabs, resolve: resolve, TABS: TABS, withTab: withTab, arm: arm };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})();
