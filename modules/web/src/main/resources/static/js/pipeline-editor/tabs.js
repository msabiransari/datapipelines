(function () {
  "use strict";

  /*
   * #349 — the workspace's six-tab state machine (spec §4.1, D2): Flow | Overview |
   * Parameters | Runs | Usage | Versions, Flow the default. PURE — no DOM, no Alpine,
   * no fetch — so `node --test` owns the admission and transition tables (tabs.test.mjs),
   * the same harness decision dock.js and events.js get.
   *
   * What the module decides:
   *
   *  - The closed tab set and its wire names — the mirror of the server's
   *    PipelineWorkspaceTab enum. The server already resolves the page's tab (unknown →
   *    flow, runs without the execution read → flow BEFORE any runs read); this module
   *    enforces the same rule on every CLIENT-side tab change, so a hidden tab is inert
   *    twice over.
   *  - Admission: Runs is execution-owned (spec §4.2's promoter rule — "a promoter
   *    retains Node Details without execution tabs", and §4.3 — hidden tabs cause no
   *    fetch). A caller without the execution read never selects it; the pane is not
   *    even rendered for them.
   *  - Transitions: selecting a tab moves `active`; re-selecting the active tab is
   *    inert (no lazy refetch, no history noise). There is no "off" state — one tab is
   *    always active, exactly one panel visible.
   *
   * What does NOT live here: the lazy tab reads and their generation stamps (init.js),
   * the URL (init.js pushes a history entry through workspace/history.js, #402), and
   * every DOM effect.
   */

  var FLOW = "flow";
  var OVERVIEW = "overview";
  var PARAMETERS = "parameters";
  var RUNS = "runs";
  var USAGE = "usage";
  var VERSIONS = "versions";
  var TABS = [FLOW, OVERVIEW, PARAMETERS, RUNS, USAGE, VERSIONS];

  function createTabs(canReadExecutions, initial) {
    var start = resolve(initial, canReadExecutions === true);
    return {
      canReadExecutions: canReadExecutions === true,
      active: start,

      /** A tab change. Unknown names and unadmitted tabs are inert (Flow stays). */
      select: function (tab) {
        var next = resolve(tab, this.canReadExecutions);
        this.active = next;
        return this.active;
      },

      /** True when the tab's panel is the visible one. */
      isActive: function (tab) {
        return this.active === tab;
      },

      /* --- derived state the template reads as paths (the dock.js convention) ----- */

      get flowActive() {
        return this.active === FLOW;
      },
      get overviewActive() {
        return this.active === OVERVIEW;
      },
      get parametersActive() {
        return this.active === PARAMETERS;
      },
      get runsActive() {
        return this.active === RUNS;
      },
      get usageActive() {
        return this.active === USAGE;
      },
      get versionsActive() {
        return this.active === VERSIONS;
      },

      /* The panels hide through the `hidden` ATTRIBUTE — a boolean false removes it
         (bind()'s falsy rule), so exactly one panel is in the DOM's visible set. No
         inline style is ever written. */
      get flowHidden() {
        return this.active !== FLOW;
      },
      get overviewHidden() {
        return this.active !== OVERVIEW;
      },
      get parametersHidden() {
        return this.active !== PARAMETERS;
      },
      get runsHidden() {
        return this.active !== RUNS;
      },
      get usageHidden() {
        return this.active !== USAGE;
      },
      get versionsHidden() {
        return this.active !== VERSIONS;
      },

      /* aria-selected needs the STRING form (the dock's rule, kept). */
      get flowAria() {
        return this.active === FLOW ? "true" : "false";
      },
      get overviewAria() {
        return this.active === OVERVIEW ? "true" : "false";
      },
      get parametersAria() {
        return this.active === PARAMETERS ? "true" : "false";
      },
      get runsAria() {
        return this.active === RUNS ? "true" : "false";
      },
      get usageAria() {
        return this.active === USAGE ? "true" : "false";
      },
      get versionsAria() {
        return this.active === VERSIONS ? "true" : "false";
      },
    };
  }

  /** The admission rule, shared by the server enum and this mirror: unknown → flow. */
  function resolve(raw, canReadExecutions) {
    var tab = null;
    for (var i = 0; i < TABS.length; i++) {
      if (TABS[i] === raw) {
        tab = raw;
        break;
      }
    }
    if (tab === RUNS && canReadExecutions !== true) return FLOW;
    return tab || FLOW;
  }

  var api = {
    createTabs: createTabs,
    resolve: resolve,
    FLOW: FLOW,
    OVERVIEW: OVERVIEW,
    PARAMETERS: PARAMETERS,
    RUNS: RUNS,
    USAGE: USAGE,
    VERSIONS: VERSIONS,
    TABS: TABS,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PETabs = api;
})();
