(function () {
  "use strict";

  /*
   * 065 §B — the bottom dock's state machine: Results | Errors, three states,
   * one transition table (pipeline-editor.md §10). PURE — no DOM, no Alpine, no
   * fetch — so `node --test` drives every row of that table (dock.test.mjs), the
   * same harness decision result.js's paging arithmetic gets.
   *
   * What lives here: `state` (hidden | minimized | open), `tab` (results |
   * errors), the per-run failure list, and the "these results are from an
   * earlier run" flag. What does NOT live here: the result rows themselves
   * (result.js owns the 027b-frozen cursor arithmetic and hands the dock
   * nothing), the failure RENDERING (details.js's PEErrorDetails.build), and
   * every focus/DOM effect (init.js).
   *
   * There is no `close`. The owner's complaint was that the results panel's ×
   * lost the pane with no way back short of re-running; `minimized` is the
   * whole of what × used to do, and it keeps its header strip, its tabs and its
   * badge on the screen.
   */

  var HIDDEN = "hidden";
  var MINIMIZED = "minimized";
  var OPEN = "open";
  var RESULTS = "results";
  var ERRORS = "errors";

  /** Two failure records are the same event when node, code and message agree. */
  function failureKey(nodeId, record) {
    var r = record || {};
    return [nodeId || (r.node && r.node.id) || "", r.code || "", r.message || ""].join("|");
  }

  function createDock() {
    return {
      state: HIDDEN,
      tab: RESULTS,
      /* [{ nodeId, record }] — newest LAST, one per failed node of this run. */
      errors: [],
      /* True while the Results tab is showing a page from an EARLIER run. */
      resultsStale: false,
      /* Set once a run has delivered data_ready; drives resultsStale on re-execute. */
      hasResults: false,

      /* --- transitions (the §10 table, and nothing else) --------------------
       * The template reads `state`, `tab` and `errors.length` DIRECTLY — no
       * derived getters here, so an Alpine proxy has nothing to preserve and the
       * node tests assert the same three fields the browser renders. */

      /** execute started: the run's failures are cleared; the state does not move. */
      executeStarted: function () {
        this.errors = [];
        this.resultsStale = this.hasResults;
        return this.state;
      },

      /** data_ready: hidden opens on Results; an already-visible dock stays put. */
      dataReady: function () {
        this.hasResults = true;
        this.resultsStale = false;
        if (this.state === HIDDEN) {
          this.state = OPEN;
          this.tab = RESULTS;
          return this.state;
        }
        // minimized / open: the STATE is the user's; the tab follows the data
        // only while nothing failed — a failure the user has not read yet wins.
        if (this.errors.length === 0) this.tab = RESULTS;
        return this.state;
      },

      /**
       * node_failed. The FIRST failure of a run raises the dock onto Errors from
       * hidden or minimized; every later one appends and moves the badge, leaving
       * the state and the tab exactly where the user put them.
       */
      nodeFailed: function (nodeId, record) {
        var first = this.errors.length === 0;
        var key = failureKey(nodeId, record);
        var seen = false;
        for (var i = 0; i < this.errors.length; i++) {
          if (this.errors[i].key === key) {
            seen = true;
            break;
          }
        }
        if (!seen) {
          this.errors.push({
            key: key,
            nodeId: nodeId || (record && record.node && record.node.id) || null,
            record: record || null,
          });
        }
        if (first && (this.state === HIDDEN || this.state === MINIMIZED)) {
          this.state = OPEN;
          this.tab = ERRORS;
        }
        return this.state;
      },

      /** The minimise button. Only an OPEN dock has anything to minimise. */
      minimise: function () {
        if (this.state === OPEN) this.state = MINIMIZED;
        return this.state;
      },

      /** The restore button — the same control, flipped, on a minimised dock. */
      restore: function () {
        if (this.state === MINIMIZED) this.state = OPEN;
        return this.state;
      },

      /** A tab click. From minimized it also restores; from hidden it is inert. */
      selectTab: function (tab) {
        if (tab !== RESULTS && tab !== ERRORS) return this.state;
        if (this.state === HIDDEN) return this.state;
        if (this.state === MINIMIZED) this.state = OPEN;
        this.tab = tab;
        return this.state;
      },

      /**
       * Escape. Deliberately a NO-OP: Esc belongs to the node inspector (§C), and
       * a dock that vanished on the key that closes the panel above it is the
       * "where did my results go" defect in a second costume. Returns false so the
       * a11y handler knows the key was not consumed here.
       */
      handleEscape: function () {
        return false;
      },
    };
  }

  var api = { createDock: createDock, HIDDEN: HIDDEN, MINIMIZED: MINIMIZED, OPEN: OPEN, RESULTS: RESULTS, ERRORS: ERRORS };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PEDock = api;
})();
