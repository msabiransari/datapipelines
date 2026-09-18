(function () {
  "use strict";

  /*
   * 149 — the node-operation reducer: the ONE view model of what each node is doing,
   * built from the execution stream (rest-api §6.4.9 `node_progress`) and the node /
   * execution lifecycle events. The node cards, the Details pane, the Events tab and
   * 151's output port (`describe().port`) all read it; none of them estimates a phase
   * from a node's status on its own.
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
            // The execution ended around an operation that never got its terminal sample: it
            // is aborted from the CLIENT's point of view, and nothing was observed about its
            // commit — so committed stays UNKNOWN (null → "Commit not observed"), exactly as
            // the node-terminal fallback above does. "false" would be a claim (R149-2).
            Object.keys(ops).forEach(function (id) {
              var op = ops[id];
              if (op.terminal) return;
              op.state = "aborted";
              op.terminal = true;
              op.observed = false;
              op.committed = null;
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
    // committed === null: the destination has nothing to commit (DDL, a child with no
    // output) — or the outcome is UNKNOWN: no terminal sample was observed (a closing
    // node event), or the terminal sample carried no commit evidence (rest-api §6.4.9: a
    // driver that never confirmed, a failure thrown by commit() itself). 151 aligned the
    // second case with the wire's own instruction — "clients must show it as such
    // ('commit not observed'), never as 'not committed'" — the pre-151 view stayed silent.
    if (op.destination && op.destination.kind === "none") return null;
    return "Commit not observed";
  }

  /* ------------------------------------------------------------ the port (151) */

  /* The operation kind in the reader's words. `ctas` keeps the combined label because the
   * write IS the statement — there is no separate write phase to animate. */
  var KIND_WORDS = { stage: "stage", ctas: "one statement", materialize: "result", writeback: "write-back", statement: "statement", child: "child result" };

  /*
   * The output port's state — a subset of the reducer's states, in the port's own terms:
   * a port is about the DESTINATION, so a query or a fetch is `pending` (nothing has
   * reached the destination yet, whatever the source is doing), a wait for the output
   * connection is `waiting` (still — nothing moves), and only a measured `writing` sample
   * earns the flow. CTAS is `combined`: the write is inside the statement.
   */
  function portState(op) {
    if (op.terminal) return op.state === "completed" ? "done" : op.state;
    switch (op.state) {
      case "waiting_output": return "waiting";
      case "writing": return "writing";
      case "finalizing": return "finalizing";
      case "executing": return op.kind === "ctas" ? "combined" : "pending";
      default: return "pending";
    }
  }

  function writtenText(op) {
    return op.rowsWritten !== null ? count(op.rowsWritten) + " written" : null;
  }

  /* The terminal line: what is KNOWN about the write's durability, and only that. */
  function portTerminalText(op, word) {
    var parts = word ? [word] : [];
    var w = writtenText(op);
    if (op.committed === true) {
      parts.push("committed" + (op.rowsWritten !== null ? " · " + count(op.rowsWritten) + " row" + (op.rowsWritten === 1 ? "" : "s") : ""));
    } else if (op.committed === false) {
      parts.push(op.rolledBack ? "rolled back" : "not committed");
    } else {
      if (w) parts.push(w);
      parts.push("commit not observed");
    }
    return parts.join(" · ");
  }

  function portText(op) {
    var w = writtenText(op);
    switch (portState(op)) {
      case "combined": return "one statement";
      case "waiting": return "waiting for " + destinationNoun(op.destination) + " connection";
      case "writing": return w ? "writing · " + w : "writing";
      case "finalizing": return (op.kind === "writeback" ? "committing" : "finalizing") + (w ? " · " + w : "");
      case "done": return portTerminalText(op, null);
      case "failed": return portTerminalText(op, "failed");
      case "aborted": return portTerminalText(op, "aborted");
      default: return w || "";
    }
  }

  /* The port's accessible name: destination first, then the state in words, no colour. */
  function portA11y(op) {
    var dest = destinationText(op.destination) || "output";
    var state = portState(op);
    var text;
    switch (state) {
      case "pending": text = writtenText(op) || "not writing yet"; break;
      case "combined": text = "querying and materializing in one statement"; break;
      default: text = portText(op).replace(/ · /g, ", ");
    }
    return "Output to " + dest + ": " + text;
  }

  function portView(op) {
    return {
      state: portState(op),
      text: portText(op),
      kindLabel: KIND_WORDS[op.kind] || op.kind || "",
      a11y: portA11y(op),
    };
  }

  /*
   * The card's footer while the operation is live — laid out for ONE line (T251): the state
   * word on the left (`cardLine`), the cumulative count on the right (`cardCounts`). The
   * destination is the card's own fact line already, so it is not repeated here; the Details
   * pane and the a11y description carry the long form.
   */
  function cardLine(op) {
    if (op.state === "started") return null;
    if (op.state === "waiting_output") return "Waiting for " + destinationNoun(op.destination);
    return stateLabel(op);
  }

  function cardCounts(op) {
    if (op.state === "started" || op.terminal) return null;
    if (op.rowsWritten !== null && op.rowsWritten > 0) return count(op.rowsWritten) + " written";
    if (op.rowsFetched !== null && op.rowsFetched > 0) return count(op.rowsFetched) + " fetched";
    return null;
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
      cardCounts: cardCounts(op),
      a11yText: a11yText(op),
      terminal: op.terminal,
      state: op.state,
      // 151: the output port's view — graph.js writes it onto the card's data.
      port: portView(op),
    };
  }

  var api = { createNodeOps: createNodeOps, describe: describe, destinationText: destinationText, stateLabel: stateLabel };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PENodeOps = api;
})();
