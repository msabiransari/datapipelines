(function () {
  "use strict";

  /*
   * 149 — the node-operation reducer: the ONE view model of what each node is doing,
   * built from the execution stream (rest-api §6.4.9 `node_progress`) and the node /
   * execution lifecycle events. The node cards, the Details pane, the Events tab and
   * 151's output connector all read it; none of them estimates a phase from a node's
   * status on its own.
   *
   * PURE (no DOM, no clock): `node --test` owns it (node-ops.test.mjs).
   *
   * Honesty rules the wire imposes, kept here:
   *  - samples apply in `sequence` order; a late, lower sequence is ignored;
   *  - the terminal sample (completed / failed / aborted) seals the operation;
   *  - a node_completed / node_failed that arrives WITHOUT a terminal sample closes
   *    the operation with `committed: null` — "not observed", never "committed";
   *  - an execution terminal event aborts every open operation with `observed: false`;
   *  - there is no percentage anywhere: no operation knows its total.
   */

  var TERMINAL = { completed: true, failed: true, aborted: true };

  function fresh(nodeId) {
    return {
      nodeId: nodeId,
      kind: null,
      destination: null,
      state: "started",
      sequence: 0,
      startedAt: null,
      observedAt: null,
      elapsedMs: null,
      timingsMs: {},
      rowsFetched: null,
      rowsWritten: null,
      batchesWritten: 0,
      committed: null,
      rolledBack: null,
      childExecutionId: null,
      terminal: false,
      /* True when the terminal state came from the operation's own terminal sample. */
      observed: false,
    };
  }

  function numberOrNull(v) {
    if (v === undefined || v === null) return null;
    var n = Number(v);
    return isFinite(n) ? n : null;
  }

  function applySample(op, p) {
    var seq = Number(p.sequence) || 0;
    if (op.terminal || seq < op.sequence) return op;
    op.sequence = seq;
    op.kind = p.operation || op.kind;
    op.destination = p.destination || op.destination;
    op.state = p.state || op.state;
    op.startedAt = p.started_at || op.startedAt;
    op.observedAt = p.observed_at || op.observedAt;
    op.elapsedMs = numberOrNull(p.elapsed_ms);
    op.timingsMs = p.timings_ms || {};
    op.rowsFetched = numberOrNull(p.rows_fetched);
    op.rowsWritten = numberOrNull(p.rows_written);
    op.batchesWritten = numberOrNull(p.batches_written) || 0;
    op.childExecutionId = p.child_execution_id || op.childExecutionId;
    if (TERMINAL[op.state]) {
      op.terminal = true;
      op.observed = true;
      op.committed = p.committed === undefined ? null : p.committed;
      op.rolledBack = p.rolled_back === undefined ? null : p.rolled_back;
    }
    return op;
  }

  function closeFromNodeEvent(op, state) {
    if (op.terminal) return op;
    op.state = state;
    op.terminal = true;
    op.observed = false;
    op.committed = null;
    return op;
  }

  function createNodeOps() {
    var ops = {};
    return {
      reduce: function (kind, payload) {
        var p = payload || {};
        switch (kind) {
          case "execution_started":
            ops = {};
            break;
          case "node_started":
            if (p.node_id && !ops[p.node_id]) ops[p.node_id] = fresh(p.node_id);
            break;
          case "node_progress":
            if (!p.node_id) break;
            ops[p.node_id] = applySample(ops[p.node_id] || fresh(p.node_id), p);
            break;
          case "node_completed":
            if (p.node_id && ops[p.node_id]) closeFromNodeEvent(ops[p.node_id], "completed");
            break;
          case "node_failed":
            if (p.node_id && ops[p.node_id]) closeFromNodeEvent(ops[p.node_id], "failed");
            break;
          case "pipeline_completed":
          case "pipeline_failed":
          case "execution_aborted":
            Object.keys(ops).forEach(function (id) {
              var op = ops[id];
              if (op.terminal) return;
              op.state = "aborted";
              op.terminal = true;
              op.observed = false;
              op.committed = false;
            });
            break;
          default:
            break;
        }
      },
      get: function (nodeId) {
        return ops[nodeId] || null;
      },
      all: function () {
        return ops;
      },
      reset: function () {
        ops = {};
      },
    };
  }

  /* ---------------------------------------------------------------- describe */

  function count(n) {
    return Number(n).toLocaleString("en-US");
  }

  function ms(v) {
    var d = Number(v);
    if (!isFinite(d) || d < 0) return null;
    if (d < 1000) return Math.round(d) + " ms";
    if (d < 60000) return (d / 1000).toFixed(1) + " s";
    return Math.floor(d / 60000) + "m " + Math.round((d % 60000) / 1000) + "s";
  }

  function destinationText(dest) {
    if (!dest) return null;
    if (dest.kind === "tempdb") return dest.table ? "tempdb." + dest.table : "tempdb";
    if (dest.kind === "datasource") return dest.table ? dest.datasource + "." + dest.table : dest.datasource || "datasource";
    if (dest.kind === "caller") return "caller";
    if (dest.kind === "parent") return "parent pipeline";
    if (dest.kind === "none") return "no output";
    return null;
  }

  /* The destination's connection noun for the waiting label: "tempdb", "pg", "result store". */
  function destinationNoun(dest) {
    if (!dest) return "output";
    if (dest.kind === "tempdb") return "tempdb";
    if (dest.kind === "datasource") return dest.datasource || "datasource";
    if (dest.kind === "caller") return "result store";
    return "output";
  }

  function stateLabel(op) {
    switch (op.state) {
      case "started": return "Running";
      case "connecting": return "Connecting to source";
      case "executing":
        if (op.kind === "ctas") return "Querying and materializing (one statement)";
        if (op.kind === "child") return "Child execution running";
        if (op.kind === "statement") return "Executing statement";
        return "Querying";
      case "fetching": return op.kind === "child" ? "Receiving child rows" : "Fetching";
      case "waiting_output": return "Waiting for " + destinationNoun(op.destination) + " connection";
      case "writing": return "Writing";
      case "finalizing": return op.kind === "writeback" ? "Committing" : "Finalizing";
      case "completed": return "Completed";
      case "failed": return "Failed";
      case "aborted": return "Aborted";
      default: return op.state;
    }
  }

  var PHASE_ORDER = ["connecting", "executing", "fetching", "waiting_output", "writing", "finalizing"];
  var PHASE_SHORT = { connecting: "connect", executing: "query", fetching: "fetch", waiting_output: "wait", writing: "write", finalizing: "finalize" };

  function phaseText(op) {
    var t = op.timingsMs || {};
    var parts = [];
    PHASE_ORDER.forEach(function (k) {
      if (t[k] === undefined || t[k] === null) return;
      var m = ms(t[k]);
      if (m !== null) parts.push(PHASE_SHORT[k] + " " + m);
    });
    return parts.length ? parts.join(" · ") : null;
  }

  function countsText(op) {
    var parts = [];
    if (op.rowsFetched !== null) parts.push(count(op.rowsFetched) + " fetched");
    if (op.rowsWritten !== null) parts.push(count(op.rowsWritten) + " written");
    return parts.length ? parts.join(" · ") : null;
  }

  function commitText(op) {
    if (!op.terminal) return null;
    if (op.committed === true) {
      return "Committed" + (op.rowsWritten !== null ? " · " + count(op.rowsWritten) + " row" + (op.rowsWritten === 1 ? "" : "s") : "");
    }
    if (op.committed === false) {
      return "Not committed" + (op.rolledBack ? " · rolled back" : "");
    }
    // committed === null: no terminal sample was observed (a closing node event), or the
    // destination has nothing to commit (DDL, a child with no output).
    if (op.destination && op.destination.kind === "none") return null;
    return op.observed ? null : "Commit not observed";
  }

  /* The card's one-line footer text while the operation is live; null when there is nothing measured. */
  function cardLine(op) {
    if (op.state === "started") return null;
    var label = stateLabel(op);
    var dest = destinationText(op.destination);
    if (op.state === "writing" && dest) label += " → " + dest;
    if (op.state === "waiting_output") return label;
    var written = op.rowsWritten !== null && op.rowsWritten > 0 ? count(op.rowsWritten) + " written" : null;
    var fetched = op.rowsFetched !== null && op.rowsFetched > 0 ? count(op.rowsFetched) + " fetched" : null;
    var tail = written || (op.state === "fetching" ? fetched : null);
    return tail ? label + " · " + tail : label;
  }

  function a11yText(op) {
    var bits = [stateLabel(op)];
    var dest = destinationText(op.destination);
    if (dest && op.state !== "started") bits[0] = bits[0] + (op.state === "writing" ? " to " + dest : "");
    if (op.kind === "child" && op.childExecutionId) bits.push("child execution " + op.childExecutionId);
    var c = countsText(op);
    if (c) bits.push(c);
    var m = op.elapsedMs !== null ? ms(op.elapsedMs) : null;
    if (m) bits.push("elapsed " + m);
    var commit = commitText(op);
    if (commit) bits.push(commit);
    return bits.join(", ");
  }

  function describe(op) {
    if (!op) return null;
    return {
      stateLabel: stateLabel(op),
      destinationText: destinationText(op.destination),
      countsText: countsText(op),
      phaseText: phaseText(op),
      commitText: commitText(op),
      elapsedText: op.elapsedMs !== null ? ms(op.elapsedMs) : null,
      cardLine: cardLine(op),
      a11yText: a11yText(op),
      terminal: op.terminal,
      state: op.state,
    };
  }

  var api = { createNodeOps: createNodeOps, describe: describe, destinationText: destinationText, stateLabel: stateLabel };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PENodeOps = api;
})();
