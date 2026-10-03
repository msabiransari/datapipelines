(function () {
  "use strict";

  /*
   * #349 — the workspace's six-tab state machine (spec §4.1, D2): Flow | Overview |
   * Parameters | Runs | Usage | Versions, Flow the default. #420: the admission and
   * transition rules are the SHARED CORE's — static/js/workspace/tabs.js, the same machine
   * the dashboards and visualizations workspaces run (one admission and transition rule per
   * workspace shape, never a copy; the core's own tests own the rule). What remains here is
   * this page's vocabulary: the closed six, the Runs admission question, and the 18 named
   * getters the CSP template binds as property paths (the Alpine build evaluates names, not
   * calls).
   *
   * The getters read `this.active`, never a captured object. Alpine's reactive proxy is the
   * receiver when a template reads `tabs.flowHidden`, and a getter registers its dependency
   * on `active` only when it reads it through that receiver; a closure over the raw object
   * (what #400's first adapter did) reads around the proxy, so no pane ever re-rendered and
   * eleven browser assertions went red. tabs.test.mjs pins the receiver rule with a Proxy.
   *
   * Admission is evaluated per transition from the LIVE `canReadExecutions`: init.js creates
   * the object before the page's permissions are read (`createTabs(false, "flow")`) and sets
   * the flag afterwards, so the core's creation-time `admitted` would stay stale.
   *
   * The server's PipelineWorkspaceTab enum resolves the page's tab by the SAME rule — the
   * two must never disagree, so this drives the mirror the client enforces on every change.
   * PURE — no DOM, no Alpine, no fetch — so `node --test` owns the tables (tabs.test.mjs).
   *
   * What does NOT live here: the lazy tab reads and their generation stamps (init.js), the
   * URL (init.js pushes a history entry through workspace/history.js, #402), and every DOM
   * effect.
   */

  // The shared core: a sibling require under node --test (the IIFE publishes there too), the
  // window global in the browser (editor.html's runtime catalog loads it first). Absent is a
  // load-order defect, refused loudly — a local copy of the rule is the duplication the core
  // exists to end.
  var core =
    (typeof module !== "undefined" && module.exports && typeof require === "function"
      ? require("../workspace/tabs.js")
      : null) ||
    (typeof window !== "undefined" ? window.WorkspaceTabs : null);
  if (!core) {
    throw new Error("pipeline-editor/tabs.js needs workspace/tabs.js loaded first (window.WorkspaceTabs)");
  }

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

  function createTabs(canReadExecutions, initial) {
    var start = resolve(initial, canReadExecutions === true);
    return {
      canReadExecutions: canReadExecutions === true,
      active: start,

      /** A tab change. Unknown names and unadmitted tabs are inert (Flow stays). */
      select: function (tab) {
        this.active = resolve(tab, this.canReadExecutions);
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

  /** The server enum's rule, mirrored by the core: unknown → Flow, and Runs without the read → Flow. */
  function resolve(raw, canReadExecutions) {
    return core.resolve(raw, TABS, FLOW, function (tab) {
      return admitted(tab, canReadExecutions);
    });
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
