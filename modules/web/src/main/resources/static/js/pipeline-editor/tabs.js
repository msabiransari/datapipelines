(function () {
  "use strict";

  /*
   * #349 — the pipeline workspace's six-tab state machine (spec §4.1, D2): Flow | Overview |
   * Parameters | Runs | Usage | Versions, Flow the default. #400: the admission and
   * transition rules are the SHARED CORE's — static/js/workspace/tabs.js, the same machine
   * the dashboard workspace runs (the reuse is the point: one admission and transition rule
   * per workspace shape, never a copy; the core's own tests own the rule). What remains here
   * is this page's vocabulary: the closed six, the Runs admission question, and the named
   * getters the CSP template binds as property paths (the Alpine build evaluates names, not
   * calls).
   *
   * The server's PipelineWorkspaceTab enum resolves the page's tab by the SAME rule — the
   * two must never disagree, so this drives the mirror the client enforces on every change.
   * PURE — no DOM, no Alpine, no fetch — so `node --test` owns the tables (tabs.test.mjs).
   *
   * What does NOT live here: the lazy tab reads and their generation stamps (init.js), the
   * URL (init.js replaceState), and every DOM effect.
   */

  // The shared core: a sibling require under node --test (the IIFE publishes there too), the
  // window global in the browser (loaded first — the runtime catalogs order it before this).
  var core =
    (typeof module !== "undefined" && module.exports && typeof require === "function"
      ? require("../workspace/tabs.js")
      : null) ||
    (typeof window !== "undefined" ? window.WorkspaceTabs : null);

  var FLOW = "flow";
  var OVERVIEW = "overview";
  var PARAMETERS = "parameters";
  var RUNS = "runs";
  var USAGE = "usage";
  var VERSIONS = "versions";
  var TABS = [FLOW, OVERVIEW, PARAMETERS, RUNS, USAGE, VERSIONS];

  /** This page's admission question: Runs needs the execution read; every other tab admits. */
  function admitted(tab, canReadExecutions) {
    return tab !== RUNS || canReadExecutions === true;
  }

  /** The server enum's rule, mirrored: unknown → Flow, and Runs without the read → Flow. */
  function resolve(raw, canReadExecutions) {
    if (core) {
      return core.resolve(raw, TABS, FLOW, function (tab) {
        return admitted(tab, canReadExecutions);
      });
    }
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

  function createTabs(canReadExecutions, initial) {
    var readExecutions = canReadExecutions === true;
    var start = resolve(initial, readExecutions);

    var state = {
      canReadExecutions: readExecutions,
      active: start,
      tabs: TABS.slice(),
      defaultTab: FLOW,

      /** A tab change. Unknown names and unadmitted tabs are inert (Flow stays). */
      select: function (tab) {
        this.active = resolve(tab, this.canReadExecutions);
        return this.active;
      },

      /** True when the tab's panel is the visible one. */
      isActive: function (tab) {
        return this.active === tab;
      },
    };

    /* --- this page's derived state: the named getters the CSP template reads as paths -----
       (delegating to the same active the core's rules move — one state, two vocabularies). */
    Object.defineProperties(state, {
      flowActive: { get: function () { return state.active === FLOW; } },
      overviewActive: { get: function () { return state.active === OVERVIEW; } },
      parametersActive: { get: function () { return state.active === PARAMETERS; } },
      runsActive: { get: function () { return state.active === RUNS; } },
      usageActive: { get: function () { return state.active === USAGE; } },
      versionsActive: { get: function () { return state.active === VERSIONS; } },
      flowHidden: { get: function () { return state.active !== FLOW; } },
      overviewHidden: { get: function () { return state.active !== OVERVIEW; } },
      parametersHidden: { get: function () { return state.active !== PARAMETERS; } },
      runsHidden: { get: function () { return state.active !== RUNS; } },
      usageHidden: { get: function () { return state.active !== USAGE; } },
      versionsHidden: { get: function () { return state.active !== VERSIONS; } },
      flowAria: { get: function () { return state.active === FLOW ? "true" : "false"; } },
      overviewAria: { get: function () { return state.active === OVERVIEW ? "true" : "false"; } },
      parametersAria: { get: function () { return state.active === PARAMETERS ? "true" : "false"; } },
      runsAria: { get: function () { return state.active === RUNS ? "true" : "false"; } },
      usageAria: { get: function () { return state.active === USAGE ? "true" : "false"; } },
      versionsAria: { get: function () { return state.active === VERSIONS ? "true" : "false"; } },
    });

    return state;
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
