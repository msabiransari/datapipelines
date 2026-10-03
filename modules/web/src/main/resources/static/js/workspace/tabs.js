(function () {
  "use strict";

  /*
   * #400 — the workspace tab machine's SHARED CORE (dashboards/workspace.js, workspace/panes.js
   * and pipeline-editor/tabs.js are its clients; the editor's first adapter was reverted at the
   * #400 merge, when the pipeline workspace's browser suites went red, and #420 re-adopted it
   * with getters that read `this`). One admission and
   * transition rule, never a copy. PURE — no DOM, no Alpine, no fetch — so `node --test` owns
   * the admission and transition tables for every family that adopts it.
   *
   * What the core decides:
   *
   *  - The CLOSED tab set one page admits, with a default: an unknown name, a missing one,
   *    and an unadmitted one resolve to the default — BEFORE any read the tab would cause.
   *    The server's enum resolves the page's tab by the SAME rule; the client mirrors it so
   *    a hidden tab is inert twice over.
   *  - Admission: the spec's `admitted(name)` predicate (the pipeline editor's is "Runs
   *    needs the execution read"; the dashboard workspace's is "Keys needs the binding
   *    permission"). A caller without the right never selects the tab; the page does not
   *    even render it.
   *  - Transitions: selecting a tab moves `active`; re-selecting the active tab is inert
   *    (no lazy refetch, no history noise). There is no "off" state — one tab is always
   *    active, exactly one panel visible.
   *
   * What does NOT live here: the DOM effects, the lazy tab reads, the URL — each family's
   * glue owns those (init.js for the editor; dashboards/workspace.js for the dashboard;
   * workspace/panes.js for the visualizations workspace, #399).
   */

  /**
   * @param {Object} spec
   * @param {string[]} spec.tabs        the closed set, default first (the wire names)
   * @param {string} [spec.defaultTab]  defaults to spec.tabs[0]
   * @param {function(string): boolean} [spec.admitted]  per-tab admission; absent = all admit
   * @param {string} [spec.initial]     the server-resolved tab (unknown/unadmitted → default)
   */
  function createTabSet(spec) {
    var tabs = (spec && spec.tabs) || [];
    if (tabs.length === 0) throw new Error("a workspace tab set needs at least one tab");
    var defaultTab = spec.defaultTab || tabs[0];
    var admitted = spec.admitted || function () { return true; };
    if (tabs.indexOf(defaultTab) === -1) throw new Error("the default tab must be in the set");

    var state = {
      tabs: tabs.slice(),
      defaultTab: defaultTab,

      /** The server's own resolution, mirrored: unknown, missing and unadmitted → default. */
      active: resolve(spec ? spec.initial : undefined, tabs, defaultTab, admitted),

      /** A tab change. Unknown names and unadmitted tabs are inert (the default stays). */
      select: function (tab) {
        this.active = resolve(tab, this.tabs, this.defaultTab, admitted);
        return this.active;
      },

      /** True when [tab] is the visible one. */
      isActive: function (tab) {
        return this.active === tab;
      },

      /** The `hidden` ATTRIBUTE's value for a pane (a boolean false removes it). */
      isHidden: function (tab) {
        return this.active !== tab;
      },

      /** aria-selected needs the STRING form (the dock's rule). */
      ariaFor: function (tab) {
        return this.active === tab ? "true" : "false";
      },
    };
    return state;
  }

  /** The admission rule, shared by the server enum and this mirror: unknown/unadmitted → default. */
  function resolve(raw, tabs, defaultTab, admitted) {
    var tab = null;
    for (var i = 0; i < tabs.length; i++) {
      if (tabs[i] === raw) {
        tab = raw;
        break;
      }
    }
    if (tab !== null && admitted(tab) === false) tab = null;
    return tab || defaultTab;
  }

  var api = {
    createTabSet: createTabSet,
    resolve: resolve,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.WorkspaceTabs = api;
})();
