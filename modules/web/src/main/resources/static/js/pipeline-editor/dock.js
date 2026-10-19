(function () {
  "use strict";

  /*
   * 080 §B — the bottom dock's state machine: Details | Results | Errors | Events,
   * two states (open | collapsed), one transition table. PURE — no DOM, no Alpine,
   * no fetch — so `node --test` drives every row of that table (dock.test.mjs),
   * the same harness decision result.js's paging arithmetic gets.
   *
   * What changed from 065 §B: the inspector overlay is gone (owner ruling
   * 2026-09-05 — "move that pane in the bottom along with Result and Errors,
   * minimizable"), so Details is a TAB and the dock is always present — the old
   * `hidden` state has no page left to live on. `minimized` is renamed `collapsed`
   * (the mock's chevron), and there is still NO close: 065's standing complaint was
   * that × lost the pane with no way back short of re-running.
   *
   * What lives here: `state`, `tab`, the per-run failure list, the "these results
   * are from an earlier run" flag, the Results row count for the tab badge, and the
   * node the Details tab is showing. What does NOT live here: the result rows
   * (result.js), the events list (events.js), the failure RENDERING
   * (details.js's PEErrorDetails.build), and every focus/DOM effect (init.js).
   *
   * 195 — the editor runs Alpine's CSP build, whose expressions are pure property
   * paths (no operators, no literals, no call syntax), so the comparisons the
   * template used to inline (`tab === 'details' ? 'true' : 'false'`) moved into
   * the getters below. They stay PURE — same fields underneath, the node tests
   * assert them exactly as the browser renders them.
   */

  var OPEN = "open";
  var COLLAPSED = "collapsed";
  var DETAILS = "details";
  var RESULTS = "results";
  var ERRORS = "errors";
  var EVENTS = "events";
  var TABS = [DETAILS, RESULTS, ERRORS, EVENTS];

  /** Two failure records are the same event when node, code and message agree. */
  function failureKey(nodeId, record) {
    var r = record || {};
    return [nodeId || (r.node && r.node.id) || "", r.code || "", r.message || ""].join("|");
  }

  function createDock() {
    return {
      state: OPEN,
      tab: DETAILS,
      /* [{ key, nodeId, record }] — newest LAST, one per failed node of this run. */
      errors: [],
      /* True while the Results tab is showing a page from an EARLIER run. */
      resultsStale: false,
      /* Set once a run has delivered data_ready; drives resultsStale on re-execute. */
      hasResults: false,
      /* The Results tab badge: the caller result's row count on success. */
      resultsRows: null,
      /* The node the Details tab is showing (null → the pane's empty state). */
      detailsNodeId: null,
      /* #615: true while the dock is collapsed by the START-UP default, not by the person.
         Their first gesture on the dock makes the state theirs; a run's first results
         open a dock nobody chose to collapse. */
      autoCollapsed: false,
      /* #615: true once anything has moved the dock — a gesture, a run. The start-up default
         is a default: once the dock has moved, collapse() must not undo what came first. */
      touched: false,

      /* --- transitions (the table, and nothing else) ----------------------------
       * The template reads `state`, `tab`, `errors.length` and `resultsRows`
       * DIRECTLY where a path suffices; the derived getters below exist because
       * the CSP build cannot inline the comparisons they replace. */

      /**
       * A node was selected (card tap, list row, the card's expand button, Enter):
       * the Details tab fills with it and surfaces — the mock's select() calls
       * showPane('details'), which also un-collapses the dock.
       */
      selectNode: function (nodeId) {
        this.detailsNodeId = nodeId;
        this.tab = DETAILS;
        this.state = OPEN;
        this.autoCollapsed = false;
        this.touched = true;
        return this.state;
      },

      /** Tapping the canvas background clears the selection, not the tab. */
      clearSelection: function () {
        this.detailsNodeId = null;
        return this.state;
      },

      /** execute started: the run's failures are cleared; the state does not move. */
      executeStarted: function () {
        this.errors = [];
        this.resultsStale = this.hasResults;
        this.touched = true;
        return this.state;
      },

      /**
       * data_ready: the Results badge takes the row count and the tab follows the
       * data — unless a failure the user has not read yet owns the tab. A collapsed
       * dock stays collapsed (065: the STATE is the user's) — except one the start-up
       * default collapsed (#615): nobody chose that, so the first results open it.
       */
      dataReady: function (rowCount) {
        this.hasResults = true;
        this.resultsStale = false;
        this.resultsRows = rowCount === undefined ? null : rowCount;
        this.touched = true;
        if (this.errors.length === 0) this.tab = RESULTS;
        if (this.autoCollapsed) {
          this.state = OPEN;
          this.autoCollapsed = false;
        }
        return this.state;
      },

      /**
       * node_failed. The FIRST failure of a run raises the dock onto Errors from
       * collapsed and takes the tab; every later one appends and moves the badge,
       * leaving the state and the tab exactly where the user put them (065, kept).
       *
       * Each entry carries two accessors the template reads as paths: `label`
       * (the summary line the 057 markup rendered inline) and `view` (the
       * failure view-model, built lazily by details.js's PEErrorDetails so this
       * module stays DOM-free and renderer-free — the accessor resolves nothing
       * until the browser template, or a test with a stub, reads it).
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
            get label() {
              var r = this.record || {};
              return (this.nodeId || "—") + " · " + (r.code || "unknown");
            },
            get view() {
              if (!this.record) return null;
              return typeof window !== "undefined" && window.PEErrorDetails
                ? window.PEErrorDetails.build(this.record)
                : null;
            },
          });
        }
        if (first) {
          this.state = OPEN;
          this.tab = ERRORS;
          this.autoCollapsed = false;
        }
        this.touched = true;
        return this.state;
      },

      /** #615 — the start-up default on a short window (`defaultState` below); not a user gesture,
          so it is inert once anything has touched the dock. */
      collapse: function () {
        if (this.touched) return this.state;
        this.state = COLLAPSED;
        this.autoCollapsed = true;
        return this.state;
      },

      /** #615 — the start-up default turned back to open (the window grew before anyone touched
          the dock): undoes only a collapse the default made. */
      openDefault: function () {
        if (this.touched || !this.autoCollapsed) return this.state;
        this.state = OPEN;
        this.autoCollapsed = false;
        return this.state;
      },

      /** The chevron. One control, two directions — 065's minimise/restore pair. */
      toggleCollapse: function () {
        this.state = this.state === OPEN ? COLLAPSED : OPEN;
        this.autoCollapsed = false;
        this.touched = true;
        return this.state;
      },

      /** A tab click. From collapsed it also restores; an unknown tab is inert. */
      selectTab: function (tab) {
        if (TABS.indexOf(tab) === -1) return this.state;
        this.state = OPEN;
        this.tab = tab;
        this.autoCollapsed = false;
        this.touched = true;
        return this.state;
      },

      /* The four tab buttons: the CSP build cannot pass an argument, so each tab
         gets its own no-arg selector over the one transition above. */
      selectDetails: function () {
        return this.selectTab(DETAILS);
      },
      selectResults: function () {
        return this.selectTab(RESULTS);
      },
      selectErrors: function () {
        return this.selectTab(ERRORS);
      },
      selectEvents: function () {
        return this.selectTab(EVENTS);
      },

      /* --- derived state the template reads as paths (195; see the header) ----- */

      get dockIsOpen() {
        return this.state === OPEN;
      },
      get dockIsCollapsed() {
        return this.state === COLLAPSED;
      },
      get collapsedClass() {
        return this.state === COLLAPSED ? "pe-dock-collapsed" : "";
      },
      get detailsActive() {
        return this.tab === DETAILS;
      },
      get resultsActive() {
        return this.tab === RESULTS;
      },
      get errorsActive() {
        return this.tab === ERRORS;
      },
      get eventsActive() {
        return this.tab === EVENTS;
      },
      /* x-bind:aria-selected needs the STRING form — a boolean false would remove
         the attribute (bind()'s falsy rule), and ARIA wants it present saying "false". */
      get detailsAria() {
        return this.tab === DETAILS ? "true" : "false";
      },
      get resultsAria() {
        return this.tab === RESULTS ? "true" : "false";
      },
      get errorsAria() {
        return this.tab === ERRORS ? "true" : "false";
      },
      get eventsAria() {
        return this.tab === EVENTS ? "true" : "false";
      },
      get noErrors() {
        return this.errors.length === 0;
      },

      /**
       * Escape. Deliberately a NO-OP: with the inspector overlay gone there is no
       * surface below the modal for Esc to own, and a dock that vanished on Esc is
       * the "where did my results go" defect in a second costume (065, kept).
       * Returns false so the a11y handler knows the key was not consumed here.
       */
      handleEscape: function () {
        return false;
      },
    };
  }

  /**
   * #615 — the state the dock STARTS in (owner ruling 2026-10-19). On a short window the
   * dock starts collapsed: when opening it at its default height would leave the graph
   * canvas under `collapseBelow` px (pipeline-editor.css `--pe-dock-auto-collapse-below`),
   * the canvas keeps the height and the results wait for a tab, a run or a node. A pane
   * height the person chose (`remembered`, splitter.js's stored size) is their answer and
   * keeps the dock open. An input the page could not measure keeps the old default, open.
   * PURE: init.js measures, this decides.
   */
  function defaultState(canvasIfOpen, collapseBelow, remembered) {
    if (remembered !== null && remembered !== undefined) return OPEN;
    if (typeof canvasIfOpen !== "number" || !isFinite(canvasIfOpen)) return OPEN;
    if (typeof collapseBelow !== "number" || !isFinite(collapseBelow)) return OPEN;
    return canvasIfOpen < collapseBelow ? COLLAPSED : OPEN;
  }

  var api = {
    createDock: createDock,
    defaultState: defaultState,
    OPEN: OPEN,
    COLLAPSED: COLLAPSED,
    DETAILS: DETAILS,
    RESULTS: RESULTS,
    ERRORS: ERRORS,
    EVENTS: EVENTS,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PEDock = api;
})();
