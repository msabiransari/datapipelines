(function () {
  "use strict";

  /*
   * 080 §B — the dock's Events tab: every SSE event in arrival order, with the
   * mock's row anatomy (+t.tttS · marker · kind · node — text · duration) and its
   * auto-scroll contract (follow the tail while running; yield the moment the user
   * scrolls up, resume when they return to the bottom).
   *
   * PURE: the log and the per-kind text formatting are DOM-free so `node --test`
   * owns them (events.test.mjs); init.js does the row rendering and the scrolling.
   * The toast announces only terminal events — EVERYTHING lands here first.
   */

  /** Compact id for the timeline: the mock's `7c1e93ab` / `a91f0c2e`. */
  function shortId(id) {
    var s = String(id || "");
    return s.length > 8 ? s.slice(0, 8) : s;
  }

  function msText(ms) {
    var d = Number(ms);
    if (!isFinite(d) || d < 0) return "";
    if (d < 1000) return Math.round(d) + " ms";
    return (d / 1000).toFixed(1) + " s";
  }

  function rowsText(rows) {
    var r = Number(rows);
    if (!isFinite(r) || r < 0) return null;
    return r.toLocaleString("en-US") + " rows";
  }

  /**
   * The `node — text` half of one event, per kind (the mock's copy). `ctx` carries
   * what the payload does not: the pipeline's name/version, the node map (for a
   * started node's type/source/template) and the output description. Defensive
   * throughout — a field the wire omits degrades the sentence, never throws.
   */
  function formatEvent(kind, payload, ctx) {
    var p = payload || {};
    var c = ctx || {};
    var node = null;
    var text = "";
    var duration = "";

    var nodeId = p.node_id || null;
    var n = nodeId && c.nodesById ? c.nodesById[nodeId] : null;
    if (nodeId) node = nodeId;

    switch (kind) {
      case "execution_started": {
        node = null;
        var label = (c.pipelineName || "pipeline") + (p.pipeline_version ? "@v" + p.pipeline_version : "");
        var bits = [label, "execution " + shortId(p.execution_id)];
        if (c.nodeCount) bits.push(c.nodeCount + " nodes");
        var bound = p.parameters ? Object.keys(p.parameters).length : 0;
        bits.push(bound + " parameters bound");
        text = bits.join(" · ");
        break;
      }
      case "node_started": {
        var type = n && n.type ? String(n.type).toUpperCase() : "";
        if (type === "PIPELINE") {
          text = "spawning child " + (n.pipeline && n.pipeline.name ? n.pipeline.name : "pipeline") +
            (n.pipeline && n.pipeline.version ? "@v" + n.pipeline.version : "");
        } else if (type === "CALCULATOR") {
          text = "evaluating " + (n.kind || "calculator");
        } else if (n) {
          var tpl = n.template && n.template.id ? n.template.id : null;
          text = tpl ? "rendered " + tpl + ", executing on " + (n.source || "tempdb") : "executing on " + (n.source || "tempdb");
        } else {
          text = "started";
        }
        break;
      }
      case "node_completed": {
        duration = msText(p.duration_ms);
        if (p.context_key) {
          text = p.context_key + " = " + JSON.stringify(p.context_value);
        } else if (p.child_execution_id) {
          text = "child " + shortId(p.child_execution_id) + " completed" + (rowsText(p.rows_out) ? " · " + rowsText(p.rows_out) : "");
        } else {
          var out = c.outputText && n ? c.outputText(n) : null;
          text = (rowsText(p.rows_out) || "done") + (out ? " → " + out : "");
        }
        break;
      }
      case "node_failed": {
        duration = msText(p.duration_ms);
        var err = p.error || {};
        text = err.code ? err.code + (err.message ? " — " + err.message : "") : err.message || "failed";
        break;
      }
      case "data_ready": {
        node = null;
        var cols = p.schema && p.schema.length ? p.schema.length : (p.schema && p.schema.columns ? p.schema.columns.length : 0);
        var rc = p.total_rows !== undefined && p.total_rows !== null ? p.total_rows : p.row_count;
        text = "caller result available — " + (rc !== undefined && rc !== null ? rc : "?") + " rows" + (cols ? " · " + cols + " columns" : "");
        break;
      }
      case "pipeline_completed": {
        node = null;
        duration = msText(p.duration_ms);
        var done = p.node_stats ? Object.keys(p.node_stats).length : 0;
        text = "execution " + shortId(p.execution_id) + " completed" + (done ? " — " + done + " nodes" : "");
        break;
      }
      case "pipeline_failed": {
        node = null;
        duration = msText(p.duration_ms);
        var ferr = p.error || {};
        text = "execution " + shortId(p.execution_id) + " failed" + (ferr.code ? " — " + ferr.code : "") +
          (p.failed_node_id ? " at " + p.failed_node_id : "");
        break;
      }
      case "execution_aborted": {
        node = null;
        text = "execution aborted" + (p.reason ? " — " + p.reason : "");
        break;
      }
      default: {
        text = kind;
        break;
      }
    }
    return { node: node, text: text, duration: duration };
  }

  /**
   * The log itself: entries in arrival order, the run's t0, the live count the tab
   * badge reads, and the auto-scroll pin. `now` is injected (ms) so the tests own
   * the clock.
   */
  function createEventsLog() {
    return {
      events: [],
      t0: null,
      /* True while the user has scrolled up — the tail stops chasing new events. */
      userPinned: false,

      /** A new run: the list and the clock reset. Returns nothing. */
      reset: function (now) {
        this.events = [];
        this.t0 = now;
        this.userPinned = false;
      },

      /**
       * Append one event. `view` is formatEvent's output. Returns the stored entry
       * ({ seq, offsetMs, kind, node, text, duration }) — init.js renders it.
       */
      append: function (kind, view, now) {
        if (this.t0 === null) this.t0 = now;
        var entry = {
          seq: this.events.length,
          offsetMs: Math.max(0, now - this.t0),
          kind: kind,
          node: view && view.node !== undefined ? view.node : null,
          text: view && view.text ? view.text : "",
          duration: view && view.duration ? view.duration : "",
        };
        this.events.push(entry);
        return entry;
      },

      /** The badge count the Events tab shows. */
      count: function () {
        return this.events.length;
      },

      /** init.js's scroll listener: pinned off the bottom, following at it. */
      setPinned: function (pinned) {
        this.userPinned = !!pinned;
      },
    };
  }

  /** The mock's `+  1.203s` timestamp column. */
  function offsetText(offsetMs) {
    var s = (Number(offsetMs) / 1000).toFixed(3);
    return "+" + s + "s";
  }

  var api = {
    createEventsLog: createEventsLog,
    formatEvent: formatEvent,
    offsetText: offsetText,
    shortId: shortId,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PEEvents = api;
})();
