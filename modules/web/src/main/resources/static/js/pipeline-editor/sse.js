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
  }

  SseHandler.prototype.connect = function (executionId, pipelineId) {
    var self = this;
    self.executionId = executionId;
    self.connectionLost = false;
    self.terminalSeen = false;
    self.pollCount = 0;
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
    var body = JSON.stringify({
      parameters: parameters,
    });

    fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        "DP-CSRF-Token": readCookie("dp_csrf"),
      },
      credentials: "same-origin",
      body: body,
      signal: self.abortController.signal,
    })
      .then(function (response) {
        if (!response.ok) {
          return response.json().then(function (err) {
            self.editor.showError(
              (err && err.error && err.error.message) || "Execution request failed: " + response.status
            );
            self.editor.isExecuting = false;
          });
        }
        return self.readStream(response);
      })
      .catch(function (err) {
        if (err.name === "AbortError") return;
        self.isConnected = false;
        // A teardown error AFTER a terminal event is noise, not loss — the
        // async SSE context can error on completion; §7.1.7 applies here too.
        if (!self.terminalSeen) self.handleConnectionLoss();
      });
  };

  SseHandler.prototype.readStream = function (response) {
    var self = this;
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
          if (result.done) {
            self.isConnected = false;
            // A stream that ends AFTER a terminal event completed normally — §7.1.7:
            // only a stream that ends WITHOUT one is connection loss. Treating every
            // end as loss overwrote the success banner with "Connection lost" (027).
            if (!self.connectionLost && !self.terminalSeen) {
              self.handleConnectionLoss();
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
          if (err.name === "AbortError") return;
          self.isConnected = false;
          // Same guard as the stream-end branch: a reader error after a
          // terminal event must not resurrect the loss path (027).
          if (!self.terminalSeen) self.handleConnectionLoss();
        });
    }

    pump();
  };

  SseHandler.prototype.dispatch = function (eventType, data) {
    var self = this;
    var editor = self.editor;
    var payload = null;
    try {
      payload = JSON.parse(data);
    } catch (e) {
      payload = data;
    }

    switch (eventType) {
      case "execution_started":
        if (payload.execution_id) self.executionId = payload.execution_id;
        if (editor.graph) editor.graph.resetAll();
        editor.isExecuting = true;
        editor.setBanner("", "");
        break;

      case "node_started":
        if (editor.graph) {
          editor.graph.setNodeState(payload.node_id, "running");
          editor.graph.setEdgesToNodeActive(payload.node_id, true);
        }
        if (editor.nodeStates) editor.nodeStates[payload.node_id] = "running";
        editor.announceStatus("Node " + payload.node_id + " started");
        break;

      case "node_completed":
        if (editor.graph) {
          editor.graph.setNodeState(payload.node_id, "success");
          editor.graph.setEdgesToNodeActive(payload.node_id, true);
          editor.graph.setEdgesFromNodeActive(payload.node_id, true);
        }
        if (editor.nodeStates) editor.nodeStates[payload.node_id] = "success";
        editor.announceStatus("Node " + payload.node_id + " completed");
        break;

      case "node_failed":
        if (editor.graph) {
          editor.graph.setNodeState(payload.node_id, "failed");
          editor.graph.setEdgesToNodeActive(payload.node_id, true);
        }
        if (editor.nodeStates) editor.nodeStates[payload.node_id] = "failed";
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
        editor.setBanner("Pipeline completed successfully", "success");
        break;

      case "pipeline_failed":
        self.terminalSeen = true;
        editor.isExecuting = false;
        editor.showError(payload.message || "Pipeline execution failed");
        break;

      case "data_ready":
        if (editor.handleDataReady) {
          editor.handleDataReady(payload);
        }
        break;

      case "execution_aborted":
        self.terminalSeen = true;
        editor.isExecuting = false;
        if (editor.graph) {
          editor.graph.cy.nodes().forEach(function (node) {
            var state = (editor.nodeStates && editor.nodeStates[node.id()]) || node.classes().join("");
            if (!state || state === "idle" || state === "running") {
              editor.graph.setNodeState(node.id(), "aborted");
              if (editor.nodeStates) editor.nodeStates[node.id()] = "aborted";
            }
          });
        }
        editor.setBanner("Execution aborted", "aborted");
        break;

      default:
        break;
    }
  };

  SseHandler.prototype.cancel = function () {
    var self = this;
    if (!self.executionId) return;
    if (self.abortController) {
      self.abortController.abort();
    }
    self.isConnected = false;
    // Cookie-authenticated DELETE — same double-submit pair as execute (§7.2/§15.2).
    fetch("/api/v1/executions/" + self.executionId, {
      method: "DELETE",
      headers: { "DP-CSRF-Token": readCookie("dp_csrf") },
      credentials: "same-origin",
    })
      .catch(function () {});
  };

  SseHandler.prototype.handleConnectionLoss = function () {
    var self = this;
    if (self.connectionLost) return;
    self.connectionLost = true;
    self.editor.setBanner(
      "Connection lost — attempting to recover",
      "connection-lost"
    );
    self.pollExecution();
  };

  SseHandler.prototype.pollExecution = function () {
    var self = this;
    if (self.pollCount >= self.maxPolls || !self.executionId) {
      self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
      self.editor.isExecuting = false;
      return;
    }
    self.pollCount++;
    fetch("/api/v1/executions/" + self.executionId)
      .then(function (res) {
        if (!res.ok) {
          if (self.pollCount < self.maxPolls) {
            setTimeout(function () { self.pollExecution(); }, 2000);
          } else {
            self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
            self.editor.isExecuting = false;
          }
          return;
        }
        return res.json();
      })
      .then(function (data) {
        if (!data) return;
        // The executions API reports UPPER-CASE statuses (SUCCESS/FAILED/RUNNING);
        // the editor compared them case-sensitively against lowercase words, so
        // even a successful recovery poll fell through to "Connection lost" (027).
        var status = (data.status || (data.data && data.data.status) || "").toLowerCase();
        if (status === "completed" || status === "success") {
          self.editor.isExecuting = false;
          self.editor.setBanner("Pipeline completed", "success");
        } else if (status === "failed") {
          self.editor.isExecuting = false;
          self.editor.setBanner("Pipeline failed", "error");
        } else if (status === "running") {
          if (self.pollCount <= self.maxPolls) {
            setTimeout(function () { self.pollExecution(); }, 2000);
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
        if (self.pollCount < self.maxPolls) {
          setTimeout(function () { self.pollExecution(); }, 2000);
        } else {
          self.editor.setBanner("Connection lost — refresh to check status", "connection-lost");
          self.editor.isExecuting = false;
        }
      });
  };

  window.SseHandler = SseHandler;
})();
