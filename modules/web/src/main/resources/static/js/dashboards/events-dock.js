(function () {
  "use strict";

  /*
   * #473 — the board's activity dock: every `scope: "dashboard"` event the runtime's
   * sanitized witness emits, in arrival order, with the pipeline editor's row anatomy
   * (time · severity · kind · text · id) and its auto-scroll contract (follow the tail
   * while refreshes run and the reader sits at the bottom; yield the moment they scroll
   * up, resume when they return).
   *
   * PURE: the ring buffer, the severity/kind buckets and the per-event one-line format
   * are DOM-free so `node --test` owns them (dashboard-events.test.mjs); dashboards-page.js
   * does the row rendering (textContent only) and the scrolling. The sentences are the
   * runtime's own fixed vocabulary — codes, states, stages, names, outcome words — never
   * a raw error body.
   */

  /** The timeline's compact id: the pipeline editor's `7c1e93ab` shape. */
  function shortId(id) {
    var s = String(id || "");
    return s.length > 8 ? s.slice(0, 8) : s;
  }

  /** The dock's cap: the newest 500 live events, with the evicted count kept truthful. */
  var DEFAULT_LIMIT = 500;

  /**
   * The log: a capped ring buffer. `add` returns `{ dropped }` for the event evicted to
   * make room (so the "older live events omitted" count can be derived exactly), and
   * `clear` is a VIEW action — the runtime keeps streaming regardless.
   */
  function createDashboardEventLog(options) {
    var limit = options && Number(options.limit) > 0 ? Number(options.limit) : DEFAULT_LIMIT;
    var items = [];
    var droppedTotal = 0;
    return {
      limit: limit,
      add: function (event) {
        if (!event || typeof event !== "object") return null;
        items.push(event);
        if (items.length > limit) {
          droppedTotal += 1;
          return { dropped: items.shift() };
        }
        return null;
      },
      all: function () {
        return items.slice();
      },
      size: function () {
        return items.length;
      },
      droppedTotal: function () {
        return droppedTotal;
      },
      clear: function () {
        items = [];
        droppedTotal = 0;
      },
    };
  }

  /** The severity word, for the marker and the Errors tab. A notification carries its own. */
  function severityOf(event) {
    if (event.severity === "error" || event.severity === "warning" || event.severity === "info") {
      return event.severity;
    }
    if (event.event === "source_failed" || event.event === "refresh_completed" && event.status === "failed") {
      return "error";
    }
    if (event.event === "abort_requested" || event.event === "abort_acked") {
      return "error";
    }
    if (event.event === "visualization_stale") {
      return "warning";
    }
    return "info";
  }

  /** The filter bucket: the Kind select's words. */
  function kindOf(event) {
    var e = String(event.event || "");
    if (e.indexOf("source_") === 0) return "source";
    if (e.indexOf("visualization_") === 0) return "visualization";
    if (e.indexOf("refresh_") === 0) return "refresh";
    if (e.indexOf("abort_") === 0) return "abort";
    if (e.indexOf("connection_") === 0) return "connection";
    if (e.indexOf("evaluation_") === 0) return "parameters";
    return "notification";
  }

  var STATUS_SENTENCES = {
    "in-progress": "is loading",
    ready: "loaded",
    error: "failed",
    abort: "was stopped",
    "no-data": "has no data to show",
  };

  /** One fixed sentence per event — the words the runtime itself uses. */
  function formatEvent(event) {
    var name = event.name ? String(event.name) : null;
    switch (event.event) {
      case "refresh_started":
        return "Refresh started.";
      case "source_started":
        return name ? name + " loading…" : "Source loading…";
      case "source_completed":
        return name ? name + " loaded." : "Source loaded.";
      case "source_failed":
        return (name ? name + " failed" : "Source failed") + (event.stage ? " (" + event.stage + ")." : ".");
      case "visualization_data":
        return name ? name + " received new data." : "New data received.";
      case "visualization_rendered":
        return name ? name + " rendered." : "Rendered.";
      case "visualization_stale":
        return name ? name + " is showing out-of-date data." : "Showing out-of-date data.";
      case "abort_requested":
        return "Abort requested.";
      case "abort_acked":
        return "Abort acknowledged — the refresh stopped.";
      case "notification":
        return event.message ? String(event.message) + "." : String(event.code || "Something needs attention") + ".";
      case "refresh_completed": {
        var outcomes = event.outcomes
          ? Object.keys(event.outcomes)
              .map(function (k) {
                return event.outcomes[k];
              })
              .join(" · ")
          : null;
        var base = "Refresh " + (event.status ? String(event.status) : "completed");
        return base + (outcomes ? " — " + outcomes + "." : ".");
      }
      default: {
        if (event.state && STATUS_SENTENCES[event.state]) {
          return (name ? name + " " : "") + STATUS_SENTENCES[event.state] + ".";
        }
        return String(event.event || "event") + ".";
      }
    }
  }

  /** The select filters: an empty value means all. */
  function matchesFilters(event, filters) {
    var f = filters || {};
    if (f.severity && severityOf(event) !== f.severity) return false;
    if (f.kind && kindOf(event) !== f.kind) return false;
    return true;
  }

  var api = {
    createDashboardEventLog: createDashboardEventLog,
    severityOf: severityOf,
    kindOf: kindOf,
    formatEvent: formatEvent,
    matchesFilters: matchesFilters,
    shortId: shortId,
    DEFAULT_LIMIT: DEFAULT_LIMIT,
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.DPDock = api;
})();
