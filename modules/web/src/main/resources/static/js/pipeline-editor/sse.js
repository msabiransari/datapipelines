(function () {
  "use strict";

  function SseHandler(editor) {
    this.editor = editor;
    this.abortController = null;
    this.isConnected = false;
    this.connectionLost = false;
    this.pollCount = 0;
    this.maxPolls = 2;
    this.executionId = null;
  }

  SseHandler.prototype.connect = function (executionId, pipelineId) {
    var self = this;
    self.executionId = executionId;
    self.connectionLost = false;
    self.pollCount = 0;
    self.isConnected = true;
    self.abortController = new AbortController();

    var url = "/api/v1/pipelines/" + pipelineId + "/execute";
    var body = JSON.stringify({
      parameters: self.editor.parameterOverrides,
    });

    fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
      },
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
        self.handleConnectionLoss();
      });
  };

  SseHandler.prototype.readStream = function (response) {
    var self = this;
    var reader = response.body.getReader();
    var decoder = new TextDecoder();
    var buffer = "";

    function pump() {
      reader
        .read()
        .then(function (result) {
          if (result.done) {
            self.isConnected = false;
            if (!self.connectionLost) {
              self.handleConnectionLoss();
            }
            return;
          }

          buffer += decoder.decode(result.value, { stream: true });
          var lines = buffer.split("\n");
          buffer = lines.pop() || "";

          var eventType = null;
          var eventData = "";

          for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (line.startsWith("event: ")) {
              eventType = line.substring(7).trim();
            } else if (line.startsWith("data: ")) {
              eventData = line.substring(6);
            } else if (line === "" && eventType) {
              self.dispatch(eventType, eventData);
              eventType = null;
              eventData = "";
            }
          }

          // Handle last event if there is one at end of buffer
          if (eventType && eventData) {
            self.dispatch(eventType, eventData);
          }

          pump();
        })
        .catch(function (err) {
          if (err.name === "AbortError") return;
          self.isConnected = false;
          self.handleConnectionLoss();
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
        editor.isExecuting = false;
        editor.setBanner("Pipeline completed successfully", "success");
        break;

      case "pipeline_failed":
        editor.isExecuting = false;
        editor.showError(payload.message || "Pipeline execution failed");
        break;

      case "data_ready":
        if (editor.handleDataReady) {
          editor.handleDataReady(payload);
        }
        break;

      case "execution_aborted":
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
    fetch("/api/v1/executions/" + self.executionId, { method: "DELETE" })
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
        var status = data.status || (data.data && data.data.status);
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
