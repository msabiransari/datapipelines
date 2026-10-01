(function () {
  "use strict";

  // The dp_csrf cookie is JS-readable by design (CookieCsrfTokenRepository
  // .withHttpOnlyFalse(), auth.md §8.4) — the double-submit pair for every
  // cookie-authenticated state-changing fetch (pipeline-editor.md §7.2). Without
  // the header the editor's Execute and Cancel were both rejected 403
  // auth.csrf.invalid (024 T41, fixed 027).
  function readCookie(name) {
    var match = document.cookie.match(new RegExp("(?:^|;\\s*)" + name + "=([^;]*)"));
    return match ? decodeURIComponent(match[1]) : null;
  }

  function SseHandler(editor) {
    this.editor = editor;
    this.abortController = null;
    this.isConnected = false;
    this.connectionLost = false;
    this.terminalSeen = false;
    this.pollCount = 0;
    this.maxPolls = 2;
    this.executionId = null;
    // #358: disposal marks a handler whose PAGE went away (boosted navigation,
    // history save). A disposed handler consumes nothing further and owns no
    // timers; disposal NEVER sends a cancellation — the run keeps going.
    this.disposed = false;
    this.pollController = null;
    this.pollTimer = null;
    this.cancelTimer = null;
    // #358: the re-attach loop (a restored page following a run that outlived
    // the navigation) replays the execution's event stream through the §10.3
    // GET endpoint until the terminal arrives or the budget runs out.
    this.replayCount = 0;
    this.maxReplays = 15;
    this.reattachTimer = null;
  }

  /**
   * Detach this page from the run. Aborts the open reader and every armed
   * timer, and refuses everything that arrives afterwards — a recovery poll's
   * late result, a stream chunk, a cancel fallback — without sending a
   * cancellation request of its own (cancel is a verb with its own permission;
   * navigation is not it). Idempotent: teardown and the history-save cleanup
   * may both reach for it.
   */
  SseHandler.prototype.dispose = function () {
    if (this.disposed) return;
    this.disposed = true;
    this.isConnected = false;
    if (this.abortController) this.abortController.abort();
    if (this.pollController) this.pollController.abort();
    if (this.pollTimer !== null) clearTimeout(this.pollTimer);
    if (this.cancelTimer !== null) clearTimeout(this.cancelTimer);
    if (this.reattachTimer !== null) clearTimeout(this.reattachTimer);
    this.pollTimer = null;
    this.cancelTimer = null;
    this.reattachTimer = null;
  };

  /** The recovery poll's next tick — armed through a handle dispose() can clear. */
  SseHandler.prototype.schedulePoll = function () {
    var self = this;
    if (self.disposed) return;
    self.pollTimer = setTimeout(function () {
      self.pollTimer = null;
      self.pollExecution();
    }, 2000);
  };

  /**
   * #358 — follow a run that outlived this document's previous page view. The
   * stream is the §10.3 replay (GET /api/v1/executions/{id}/events): it re-sends
   * the event log as it stands, so the restored page rebuilds the run's timeline,
   * and — once the terminal event is IN the log — delivers it exactly once. A
   * replay that ends before the terminal (the run is still going) is re-armed
   * within [maxReplays]; a budget that runs out says what a lost connection says.
   * Never sends a cancellation.
   */
  SseHandler.prototype.reattach = function (executionId) {
    var self = this;
    if (self.disposed || !executionId || self.executionId) return;
    self.executionId = executionId;
    self.isConnected = true;
    // The re-attach mode flag: a stream that ends without a terminal re-plays
    // instead of walking the live connection-loss path (see readStream).
    self.detached = true;
    self.editor.isExecuting = true;
    self.replayStream();
  };

  SseHandler.prototype.replayStream = function () {
    var self = this;
    if (self.disposed) return;
    self.abortController = new AbortController();
    fetch("/api/v1/executions/" + self.executionId + "/events", {
      headers: { Accept: "text/event-stream" },
      credentials: "same-origin",
      signal: self.abortController.signal,
    })
      .then(function (response) {
        if (self.disposed) return;
        if (!response.ok) {
          // 404 (unknown/foreign) or 410 (log past retention): nothing to follow.
          self.editor.isExecuting = false;
          self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
          return;
        }
        return self.readStream(response);
      })
      .catch(function (err) {
        if (self.disposed || err.name === "AbortError") return;
        self.scheduleReplay();
      });
  };

  /** The re-attach loop's next replay — armed through a handle dispose() can clear. */
  SseHandler.prototype.scheduleReplay = function () {
    var self = this;
    if (self.disposed || self.terminalSeen) return;
    if (self.replayCount >= self.maxReplays) {
      self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
      self.editor.isExecuting = false;
      return;
    }
    self.replayCount++;
    self.reattachTimer = setTimeout(function () {
      self.reattachTimer = null;
      self.replayStream();
    }, 2000);
  };

  function pipelineLabel(editor) {
    var p = editor.pipeline || {};
    return p.display_name || p.name || "Pipeline";
  }

  SseHandler.prototype.connect = function (executionId, pipelineId) {
    var self = this;
    if (self.disposed) return;
    self.executionId = executionId;
    self.connectionLost = false;
    self.terminalSeen = false;
    self.pollCount = 0;
    self.detached = false;
    self.isConnected = true;
    self.abortController = new AbortController();

    var url = "/api/v1/pipelines/" + pipelineId + "/execute";
    // §7.2: typed JSON via collectParameters(), never the raw overrides — the
    // parameter panel seeds every declared key with "" (init.js), and sending the
    // blanks as-is 400s pipeline.execution.invalid_parameter_type on any pipeline
    // whose defaulted parameters were left untouched (observed on the seeded
    // revenue_by_borough, 027). collectParameters skips blanks and coerces wire
    // types, so the server's declared defaults apply.
    var parameters = window.collectParameters
      ? window.collectParameters(self.editor)
      : self.editor.parameterOverrides;
    var body = { parameters: parameters };
    // #348 (workspace spec §3.4): the run pins the version the page is VIEWING —
    // released or draft, ALWAYS sent. workspace.js read it from #pipeline-workspace;
    // a page whose block is missing or malformed never reaches this line (execute.js
    // refuses before connecting), and this second gate keeps a versionless POST —
    // which would let the server pick a body the person is not looking at — from
    // ever firing. There is no fallback to a default.
    var pin = window.PEWorkspaceLogic ? window.PEWorkspaceLogic.executeVersion(window.PEWorkspace) : null;
    if (pin == null) {
      self.editor.isExecuting = false;
      self.editor.showError(
        "The page could not read the pipeline's version state, so it cannot choose which version to run. Reload the page; if it persists, re-open the pipeline."
      );
      return;
    }
    body.version = pin;

    fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        "DP-CSRF-Token": readCookie("dp_csrf"),
      },
      credentials: "same-origin",
      body: JSON.stringify(body),
      signal: self.abortController.signal,
    })
      .then(function (response) {
        if (self.disposed) return;
        if (!response.ok) {
          return response.json().then(function (err) {
            if (self.disposed) return;
            self.editor.showError(
              (err && err.error && err.error.message) || "Execution request failed: " + response.status
            );
            self.editor.isExecuting = false;
          });
        }
        return self.readStream(response);
      })
      .catch(function (err) {
        if (self.disposed) return;
        if (err.name === "AbortError") return;
        self.isConnected = false;
        // A teardown error AFTER a terminal event is noise, not loss — the
        // async SSE context can error on completion; §7.1.7 applies here too.
        if (!self.terminalSeen) self.handleConnectionLoss();
      });
  };

  SseHandler.prototype.readStream = function (response) {
    var self = this;
    if (self.disposed) return;
    var reader = response.body.getReader();
    var decoder = new TextDecoder();
    var buffer = "";
    // Frame state lives HERE, beside `buffer` — a frame's `event:` line and its
    // complete `data:` line routinely arrive in different chunks. `data_ready`
    // carries the inline first page (page-size-rows rows) as one `data:` line;
    // tens of rows already exceed Tomcat's default 8KB response buffer, so the
    // frame spans many reads. With per-chunk state the event type was lost at
    // the first boundary, the completed frame never dispatched, and the run
    // bannered success while the result panel never opened (027b A).
    var eventType = null;
    var eventData = "";

    function pump() {
      reader
        .read()
        .then(function (result) {
          if (self.disposed) return;
          if (result.done) {
            self.isConnected = false;
            // A stream that ends AFTER a terminal event completed normally — §7.1.7:
            // only a stream that ends WITHOUT one is connection loss. Treating every
            // end as loss overwrote the success banner with "Connection lost" (027).
            // The re-attach loop re-plays instead: a replay that ends before the
            // terminal means the run is still going, and the next replay carries it.
            if (!self.connectionLost && !self.terminalSeen) {
              if (self.detached) self.scheduleReplay();
              else self.handleConnectionLoss();
            }
            return;
          }

          buffer += decoder.decode(result.value, { stream: true });
          var lines = buffer.split("\n");
          buffer = lines.pop() || "";

          for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            // SSE field values may carry ONE optional leading space after the colon
            // (WHATWG spec) — the app's emitter writes `event:name` without it, so
            // matching only "event: " never dispatched a single event and every
            // execution ended as "Connection lost" (027). Accept both forms.
            if (line.indexOf("event:") === 0) {
              eventType = line.substring(6).trim();
            } else if (line.indexOf("data:") === 0) {
              eventData = line.substring(5).trim();
            } else if (line === "" && eventType !== null) {
              // A frame is dispatched ONLY at its blank-line terminator — the
              // server (Spring SseEmitter) always terminates frames with \n\n.
              // `eventType !== null`, not truthiness: a frame with EMPTY data is
              // legitimate and must still dispatch.
              self.dispatch(eventType, eventData);
              eventType = null;
              eventData = "";
            }
          }

          pump();
        })
        .catch(function (err) {
          if (self.disposed) return;
          if (err.name === "AbortError") return;
          self.isConnected = false;
          // Same guard as the stream-end branch: a reader error after a
          // terminal event must not resurrect the loss path (027); the
          // re-attach loop retries within its budget instead.
          if (!self.terminalSeen) {
            if (self.detached) self.scheduleReplay();
            else self.handleConnectionLoss();
          }
        });
    }

    pump();
  };

  SseHandler.prototype.dispatch = function (eventType, data) {
    var self = this;
    if (self.disposed) return;
    var editor = self.editor;
    var payload = null;
    try {
      payload = JSON.parse(data);
    } catch (e) {
      payload = data;
    }

    // #358 — the record a restored page follows (init.js's activation re-attaches
    // through it): armed at the run's start, cleared at its terminal event, so a
    // finished run is never re-announced and a fresh document never re-attaches.
    if (typeof window !== "undefined") {
      if (eventType === "execution_started") {
        var runId = (payload && payload.execution_id) || self.executionId;
        // The record names its PIPELINE too: a boosted entry into ANOTHER pipeline's workspace
        // (the explorer's Open links are boosted) must not adopt this run (merge follow-up).
        var runPipeline = (self.editor.pipeline && self.editor.pipeline.id) || null;
        if (runId) window.__peLiveExecution = { executionId: runId, pipelineId: runPipeline };
      } else if (
        eventType === "pipeline_completed" ||
        eventType === "pipeline_failed" ||
        eventType === "execution_aborted"
      ) {
        window.__peLiveExecution = null;
      }
    }

    // 080 §B: EVERY event lands in the dock's Events tab, in arrival order — the
    // toast only ever announces the terminal one. execution_started also resets
    // the log and starts the top bar's clock (init.js owns both DOM effects).
    if (editor.logEvent) editor.logEvent(eventType, payload);

    // 149: EVERY lifecycle event also reduces into the node-operation view model
    // (node-ops.js) — the one place the cards, the Details pane and 151's output
    // connector read what a node is doing. Reduced BEFORE the switch so a node's
    // terminal event closes its operation before the card re-renders for it.
    self.reduceOperation(eventType, payload);

    switch (eventType) {
      case "execution_started":
        if (payload.execution_id) self.executionId = payload.execution_id;
        if (editor.graph) editor.graph.resetAll();
        // 072: the frame carries the RESOLVED Context at execution start — org config,
        // the platform keys and the parameters (pipeline-contract §7.2 tiers 1-4). The
        // inspector uses it to show a CALCULATOR node's `$org_fiscal_start_date` as
        // `$org_fiscal_start_date → 09-15`, which is the difference between reading the
        // body and knowing what the run actually did. Calculator OUTPUTS (tier 5) arrive
        // later, one per node_completed, and are merged in there.
        if (editor.contextValues) editor.contextValues = payload.parameters || {};
        editor.isExecuting = true;
        editor.setBanner("", "");
        // 150: the entry boundary is armed; End was reset to neutral by resetAll above.
        self.setMarker("start", "running");
        break;

      case "node_started":
        // 080 §A: setNodeState owns the edge transition now — the curves INTO a
        // running node flow (active), no separate edge call here.
        if (editor.graph) {
          editor.graph.setNodeState(payload.node_id, "running");
        }
        if (editor.nodeStates) editor.nodeStates[payload.node_id] = "running";
        editor.announceStatus("Node " + payload.node_id + " started");
        break;

      case "node_progress":
        // 135: a terminal sample (state "aborted"/"failed") is the ONLY node-level
        // terminal signal a CANCELLED node gets — PipelineExecutor emits no node
        // event for a cancellation ("Cancellation is not a node failure"), so the
        // #125 operation sample is all that arrives before pipeline_failed. Map it
        // onto the graph exactly when THIS sample is what sealed the operation (the
        // reducer already ran, above): a node-level event keeps precedence, so a
        // sample replayed after node_failed — or a lower-sequence straggler the
        // reducer ignored — changes nothing, and a mid-run sample (writing, …) is
        // not terminal and never moves the graph. setNodeState stops the pulse,
        // clears the incoming flow and mirrors minimap + a11y.
        if (
          payload && payload.node_id && (payload.state === "aborted" || payload.state === "failed") &&
          editor.graph && editor.nodeOps && typeof editor.nodeOps.get === "function"
        ) {
          var sealed = editor.nodeOps.get(payload.node_id);
          if (sealed && sealed.terminal && sealed.state === payload.state) {
            editor.graph.setNodeState(payload.node_id, payload.state);
          }
        }
        break;

      case "node_completed":
        // 080 §A: success turns the incoming edges --edge-done inside setNodeState.
        if (editor.graph) {
          editor.graph.setNodeState(payload.node_id, "success");
        }
        // 059 §A line 5: the event carries the node's stats FLAT (SseEventProjection:
        // duration_ms / rows_out / bytes_out — not a nested stats object) and the
        // completion branch used to drop them. One hand-off populates the card's
        // run line ("5 rows · 37 ms"); rows_out is NOT_MEASURED (-1) on a no-row
        // node, which formatRunLine renders as the elapsed time alone. 080 §A: the
        // CALCULATOR's context_value rides along for the footer, and setNodeStats
        // labels the OUTGOING edges with the count flowing out of this node.
        if (editor.graph && editor.graph.setNodeStats) {
          editor.graph.setNodeStats(payload.node_id, {
            duration_ms: payload.duration_ms,
            rows_out: payload.rows_out,
            context_value: payload.context_key ? payload.context_value : undefined,
            context_values: payload.context_values !== undefined ? payload.context_values : undefined,
          });
        }
        // 072: a CALCULATOR node's whole output is one value. Recorded per node for the
        // Details pane's Calculator rows, and merged into the Context so a LATER node's
        // `$reference` to it resolves on screen the way it resolved in the run.
        if (payload.context_key) {
          if (editor.nodeValues) editor.nodeValues[payload.node_id] = payload.context_value;
          if (editor.contextValues) editor.contextValues[payload.context_key] = payload.context_value;
        }
        // 121: a multi-output node wrote a SET — recorded whole for the Details pane, and
        // every key merged into the Context for the same on-screen resolution as the run.
        if (payload.context_values) {
          if (editor.nodeValues) editor.nodeValues[payload.node_id] = payload.context_values;
          if (editor.contextValues) {
            Object.keys(payload.context_values).forEach(function (k) {
              editor.contextValues[k] = payload.context_values[k];
            });
          }
        }
        // 080 §B: a PIPELINE node's completion links to the child it spawned — the
        // Details pane's Execution row reads it.
        if (payload.child_execution_id && editor.childExecutions) {
          editor.childExecutions[payload.node_id] = payload.child_execution_id;
        }
        if (editor.nodeStates) editor.nodeStates[payload.node_id] = "success";
        editor.announceStatus("Node " + payload.node_id + " completed");
        break;

      case "node_failed":
        if (editor.graph) {
          editor.graph.setNodeState(payload.node_id, "failed");
        }
        if (editor.nodeStates) editor.nodeStates[payload.node_id] = "failed";
        // 057/T85: the error object is the failure record — code, message, node context,
        // rendered SQL, exception chain. Dropping it (the reported defect) left the
        // inspector with nothing but a red node; the owner opened the database to learn why.
        if (payload.error && editor.nodeErrors) editor.nodeErrors[payload.node_id] = payload.error;
        // 065 §B: the same record also joins the dock's Errors tab — the PER-RUN
        // view beside the inspector's per-node one. One record, two homes, both
        // read-only; PEErrorDetails.build renders both.
        if (payload.error && editor.recordFailure) editor.recordFailure(payload.node_id, payload.error);
        if (payload.dependents && editor.graph) {
          payload.dependents.forEach(function (depId) {
            editor.graph.setNodeState(depId, "aborted");
            if (editor.nodeStates) editor.nodeStates[depId] = "aborted";
          });
        }
        editor.announceStatus("Node " + payload.node_id + " failed");
        break;

      case "pipeline_completed":
        self.terminalSeen = true;
        editor.isExecuting = false;
        // 150: the boundaries read the authoritative outcome — never a node event.
        self.setMarker("start", "idle");
        self.setMarker("end", "success");
        // 080 §D: the top bar's status takes its terminal text (elapsed from the
        // clock, the row count data_ready left on runStatus.rows).
        if (editor.stopRunClock) editor.stopRunClock("done");
        // Shape D (ui-screens.md §5.1): a stream-borne event has no HTTP response
        // to hang an OOB swap on, so the ONE client-side builder reports it. The
        // terminal events also ANNOUNCE now — they previously did not (only
        // node-level events did); this is an addition, not a preservation.
        if (window.DpToast && window.DpToast.show) {
          window.DpToast.show("success", "Pipeline completed", pipelineLabel(editor) + " finished");
        }
        editor.announceStatus("Pipeline completed successfully");
        break;

      case "pipeline_failed":
        self.terminalSeen = true;
        editor.isExecuting = false;
        if (editor.stopRunClock) editor.stopRunClock("failed");
        // 135: the executor cancels running siblings and never starts queued ones
        // when a node fails, emitting no node event for either — what it records in
        // node_stats is ABORTED. Sweep them here so the on-screen states equal
        // node_stats at the end of the run, exactly as execution_aborted already did.
        self.abortUnfinishedNodes();
        // 150: End reads the authoritative outcome — a failure is Failed, and a
        // root that never started stays aborted beside it, not rewritten.
        self.setMarker("start", "idle");
        self.setMarker("end", "failed");
        // 057: the FULL payload goes to the result panel's failure mode — the code, the
        // message, the correlation id, the rendered SQL and the exception chain, on the
        // screen the engineer is already looking at. The modal keeps a one-line summary
        // (a failure detail is not a 6s notification, §9 — but the DETAIL lives in the panel).
        if (editor.handlePipelineFailed) {
          editor.handlePipelineFailed(payload);
        } else {
          editor.showError((payload.error && payload.error.message) || payload.message || "Pipeline execution failed");
        }
        editor.announceStatus("Pipeline execution failed");
        break;

      case "data_ready":
        if (editor.handleDataReady) {
          editor.handleDataReady(payload);
        }
        break;

      case "execution_aborted":
        self.terminalSeen = true;
        editor.isExecuting = false;
        if (editor.stopRunClock) editor.stopRunClock("aborted");
        self.abortUnfinishedNodes();
        // 150: the owner's cancel is Stopped at the boundary — the marker's word,
        // not a control (End is never a button; since 151/#144 Start is the run
        // trigger, and Cancel stays the toolbar's).
        self.setMarker("start", "idle");
        self.setMarker("end", "aborted");
        var abortReason = payload && payload.reason ? String(payload.reason) : null;
        if (window.DpToast && window.DpToast.show) {
          window.DpToast.show("warning", "Execution aborted", abortReason || "The execution was aborted");
        }
        editor.announceStatus(abortReason ? "Execution aborted (" + abortReason + ")" : "Execution aborted");
        break;

      default:
        break;
    }
  };

  /**
   * 135: every node still idle or running when the execution ends aborted or
   * failed is, by the server's own bookkeeping, ABORTED — the executor cancels
   * running siblings and never starts queued ones, and it emits no node event
   * for either. setNodeState carries the whole transition: the pulse stops, the
   * incoming flow clears, and the minimap/a11y mirrors follow. Nodes that
   * reached a real terminal state (success/failed) are not touched.
   */
  SseHandler.prototype.abortUnfinishedNodes = function () {
    var editor = this.editor;
    if (!editor.graph || !editor.graph.cy) return;
    editor.graph.cy.nodes().forEach(function (node) {
      // 150: the Start/End markers are not nodes — nothing about them ever ran,
      // and End's one terminal word is the execution outcome, set by its own case.
      if (node.data && node.data("kind") === "boundary") return;
      var state = (editor.nodeStates && editor.nodeStates[node.id()]) || node.classes().join("");
      if (!state || state === "idle" || state === "running") {
        editor.graph.setNodeState(node.id(), "aborted");
      }
    });
  };

  /**
   * 150: the boundary markers' lifecycle, from the same authoritative events
   * every other state reads. Start arms on execution_started and goes neutral at
   * the execution's terminal event; End moves ONLY here — the authoritative
   * execution outcome — never on a node event (a finished branch is not a
   * finished execution). Guarded: the marker API is graph-internal and optional.
   */
  SseHandler.prototype.setMarker = function (side, state) {
    var editor = this.editor;
    if (editor.graph && typeof editor.graph.setMarkerState === "function") {
      // 151/#144: a terminal End carries the run clock's elapsed time when the clock ran
      // this session (init.js runStatus.startedAt); no clock, no claim.
      var terminal = side === "end" && (state === "success" || state === "failed" || state === "aborted");
      editor.graph.setMarkerState(side, state, terminal ? { elapsed: this.runElapsedText() } : null);
    }
  };

  /** `2m 14s` since the run clock started, or null when this session has no clock for the run. */
  SseHandler.prototype.runElapsedText = function () {
    var rs = this.editor.runStatus;
    var startedAt = rs && Number(rs.startedAt);
    if (!startedAt || !isFinite(startedAt) || startedAt <= 0) return null;
    var fmt = window.PEGraphUtil && window.PEGraphUtil.durationText;
    return fmt ? fmt(Date.now() - startedAt) : null;
  };

  /**
   * 149: feed the reducer and re-paint the node's operation line. Tolerant of a page
   * without the reducer (the module is optional to this handler) and of a payload
   * without a node id (execution-level events reduce for their side effect only).
   */
  SseHandler.prototype.reduceOperation = function (eventType, payload) {
    var editor = this.editor;
    var ops = editor.nodeOps;
    if (!ops || typeof ops.reduce !== "function" || !payload || typeof payload !== "object") return;
    ops.reduce(eventType, payload);
    var describe = window.PENodeOps && window.PENodeOps.describe;
    var paint = function (nodeId) {
      var op = ops.get(nodeId);
      var view = op && describe ? describe(op) : null;
      if (editor.graph && editor.graph.setNodeOperation) editor.graph.setNodeOperation(nodeId, view);
      if (window.a11yNodeOperation) window.a11yNodeOperation(nodeId, view ? view.a11yText : null);
    };
    if (payload.node_id) {
      paint(payload.node_id);
    } else {
      // An execution-level event may have closed every open operation.
      Object.keys(ops.all()).forEach(paint);
    }
  };

  SseHandler.prototype.cancel = function () {
    var self = this;
    if (self.disposed) return;
    if (!self.executionId) return;
    // §6.3/§15.2: the DELETE makes the server emit execution_aborted ON the
    // still-open stream. The old order aborted the reader FIRST, so the client
    // never consumed its own terminal event and the aborted end state (banner,
    // node sweep, and now the toast) could never render from the UI's own Cancel
    // button (031 F5). Send the DELETE with the reader open; abort only as the
    // fallback when no terminal event arrives.
    //
    // #336 D8: the outcome is stated. An ACCEPTED cancel arms the 5 s fallback —
    // the no-terminal-event case is the only one it exists for. A 409 is §15.2's
    // documented quiet case (the execution already reached its terminal state and
    // that is what renders). Any other refusal or a network failure toasts the
    // server's catalogued message (or fixed copy when there is no body) and
    // leaves the stream open: the run is never presented as cancelled.
    fetch("/api/v1/executions/" + self.executionId, {
      method: "DELETE",
      // Cookie-authenticated DELETE — same double-submit pair as execute (§7.2/§15.2).
      headers: { "DP-CSRF-Token": readCookie("dp_csrf") },
      credentials: "same-origin",
    })
      .then(function (res) {
        if (self.disposed) return;
        if (res.ok) {
          self.cancelTimer = setTimeout(function () {
            if (self.disposed) return;
            if (!self.terminalSeen && self.abortController) {
              self.abortController.abort();
              self.isConnected = false;
            }
          }, 5000);
          return;
        }
        if (res.status === 409) return;
        res
          .json()
          .then(function (err) {
            if (self.disposed) return;
            var message =
              (err && err.error && (err.error.user_message || err.error.message)) ||
              "The execution could not be cancelled (HTTP " + res.status + "). It is still running.";
            if (window.DpToast && window.DpToast.show) window.DpToast.show("danger", "Cancel failed", message);
          })
          .catch(function () {
            if (self.disposed) return;
            if (window.DpToast && window.DpToast.show) {
              window.DpToast.show("danger", "Cancel failed", "The execution could not be cancelled. It is still running.");
            }
          });
      })
      .catch(function () {
        if (self.disposed) return;
        if (window.DpToast && window.DpToast.show) {
          window.DpToast.show("danger", "Cancel failed", "The server could not be reached. The execution is still running.");
        }
      });
  };

  SseHandler.prototype.handleConnectionLoss = function () {
    var self = this;
    if (self.disposed) return;
    if (self.connectionLost) return;
    self.connectionLost = true;
    // 151: nothing about the run is observed from here on — freeze every moving
    // indicator (pulses, the port's write flow, consumer-running edges) without
    // rewriting a single state; the recovery poll below may learn the OUTCOME.
    if (self.editor.graph && typeof self.editor.graph.markStreamLost === "function") {
      self.editor.graph.markStreamLost();
    }
    self.editor.setBanner(
      "Connection lost — attempting to recover",
      "connection-lost"
    );
    self.pollExecution();
  };

  /**
   * 151: the recovery poll learned how the execution ENDED. Settle the graph the way
   * the live terminal event would have — the End marker takes the polled outcome, Start
   * rests, every open operation closes UNOBSERVED through the reducer (149's rule: the
   * client never saw it end, so its commit is "not observed", never "committed"), and a
   * failed/aborted outcome sweeps the unfinished nodes exactly as the live
   * pipeline_failed / execution_aborted do (135). A polled SUCCESS paints no node Done:
   * no node_completed was observed, and a card is not repainted from an inference.
   */
  SseHandler.prototype.settlePolledOutcome = function (outcome) {
    var self = this;
    var kind = outcome === "success" ? "pipeline_completed" : outcome === "failed" ? "pipeline_failed" : "execution_aborted";
    self.reduceOperation(kind, {});
    if (outcome !== "success") self.abortUnfinishedNodes();
    self.setMarker("start", "idle");
    self.setMarker("end", outcome);
  };

  SseHandler.prototype.pollExecution = function () {
    var self = this;
    if (self.disposed) return;
    if (self.pollCount >= self.maxPolls || !self.executionId) {
      self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
      self.editor.isExecuting = false;
      return;
    }
    self.pollCount++;
    self.pollController = self.pollController || new AbortController();
    fetch("/api/v1/executions/" + self.executionId, { signal: self.pollController.signal })
      .then(function (res) {
        if (self.disposed) return;
        if (!res.ok) {
          if (self.pollCount < self.maxPolls) {
            self.schedulePoll();
          } else {
            self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
            self.editor.isExecuting = false;
          }
          return;
        }
        return res.json();
      })
      .then(function (data) {
        if (self.disposed) return;
        if (!data) return;
        // The executions API reports UPPER-CASE statuses (SUCCESS/FAILED/RUNNING);
        // the editor compared them case-sensitively against lowercase words, so
        // even a successful recovery poll fell through to "Connection lost" (027).
        var status = (data.status || (data.data && data.data.status) || "").toLowerCase();
        // #358 (merge follow-up): a run the recovery poll finds FINISHED is as terminal as a
        // streamed terminal event — the live-run record clears here too, or a later restore
        // would re-attach to the finished run and replay its terminal (one more toast).
        if (status === "completed" || status === "success" || status === "failed" || status === "aborted") {
          if (typeof window !== "undefined") window.__peLiveExecution = null;
        }
        if (status === "completed" || status === "success") {
          self.editor.isExecuting = false;
          if (self.editor.stopRunClock) self.editor.stopRunClock("done");
          self.settlePolledOutcome("success");
          self.editor.setBanner("Pipeline completed", "success");
        } else if (status === "failed") {
          self.editor.isExecuting = false;
          if (self.editor.stopRunClock) self.editor.stopRunClock("failed");
          self.settlePolledOutcome("failed");
          // 057: even the degraded recovery path names the CODE — a bare "Pipeline failed"
          // was the exact screen the owner reported (T85). The full record is a click away
          // on the execution page; this banner at least says what failed.
          var errCode =
            (data.error && data.error.code) || (data.data && data.data.error && data.data.error.code) || null;
          self.editor.setBanner(errCode ? "Pipeline failed — " + errCode : "Pipeline failed", "error");
        } else if (status === "aborted") {
          // The recovery poll previously had no aborted branch (031 F5): a cancel
          // that raced a dropped stream fell through to "Connection lost".
          self.editor.isExecuting = false;
          if (self.editor.stopRunClock) self.editor.stopRunClock("aborted");
          self.settlePolledOutcome("aborted");
          if (window.DpToast && window.DpToast.show) {
            window.DpToast.show("warning", "Execution aborted", "The execution was aborted");
          }
          self.editor.announceStatus("Execution aborted");
        } else if (status === "running") {
          if (self.pollCount <= self.maxPolls) {
            self.schedulePoll();
          } else {
            self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
            self.editor.isExecuting = false;
          }
        } else {
          self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
          self.editor.isExecuting = false;
        }
      })
      .catch(function () {
        if (self.disposed) return;
        if (self.pollCount < self.maxPolls) {
          self.schedulePoll();
        } else {
          self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
          self.editor.isExecuting = false;
        }
      });
  };

  window.SseHandler = SseHandler;
})();
