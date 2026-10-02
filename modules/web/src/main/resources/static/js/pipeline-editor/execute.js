(function () {
  "use strict";

  function executePipeline(editor) {
    var pipelineId = editor.pipeline.id;
    if (!pipelineId) return;

    // #348: the version pin is the workspace state (workspace.js) — the version the
    // page is VIEWING, released or draft. A page whose block is missing or malformed,
    // or one that resolved no body, cannot choose which version to run: refuse
    // visibly and send NOTHING — never the server's execute-default, which can be a
    // different body than the one the person is looking at.
    var pin = window.PEWorkspaceLogic ? window.PEWorkspaceLogic.executeVersion(window.PEWorkspace) : null;
    if (pin == null) {
      editor.isExecuting = false;
      editor.showError(
        "The page could not read the pipeline's version state, so it cannot choose which version to run. Reload the page; if it persists, re-open the pipeline."
      );
      return;
    }

    editor.isExecuting = true;
    editor.setBanner("", "");

    if (editor.graph) editor.graph.resetAll();
    editor.nodeStates = {};
    // 080 §B: the per-run fact maps reset with the run — a stale child execution id
    // or calculator value on the Details pane would describe a run that is gone.
    if (editor.nodeValues) editor.nodeValues = {};
    if (editor.childExecutions) editor.childExecutions = {};
    if (editor.nodeErrors) editor.nodeErrors = {};
    // 065 §B "execute started": this run's Errors list empties and the dock stays
    // exactly where the user left it. A Results tab still holding the previous
    // run's page is LABELLED as such until data_ready replaces it.
    if (editor.handleExecutionStarted) editor.handleExecutionStarted();

    if (editor.sseHandler) {
      // #349: the run's OWN version — the paint gate and the identity strip read it
      // from the handler; execution_started confirms it from the wire payload.
      editor.sseHandler.version = pin;
      editor.sseHandler.connect(null, pipelineId);
    }
  }

  function collectParameters(editor) {
    var params = {};
    var keys = editor.paramKeys;
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i];
      var val = editor.parameterOverrides[k];
      if (val !== undefined && val !== null && val !== "") {
        params[k] = coerceValue(val, (editor.parameters[k] && editor.parameters[k].type) || "STRING");
      }
    }
    return params;
  }

  function coerceValue(val, type) {
    var upper = (type || "").toUpperCase();
    if (upper === "INTEGER") {
      var n = parseInt(val, 10);
      return isNaN(n) ? val : n;
    }
    // §6.3: BIGINTEGER/BIGDECIMAL are STRING-on-wire — their value space exceeds
    // the IEEE 754 safe range, so "Accepting it would silently lose precision for
    // values beyond IEEE 754 safe range" (pipeline-contract §6.3) — and the
    // server rejects a JSON number outright with 400
    // pipeline.execution.invalid_parameter_type (ParameterCoercion). Pass the
    // raw string through; the server's parse remains the authority. INTEGER and
    // DECIMAL stay JSON numbers — the split is exactly where the contract puts it.
    if (upper === "BIGINTEGER" || upper === "BIGDECIMAL") {
      return String(val);
    }
    if (upper === "DECIMAL") {
      var f = parseFloat(val);
      return isNaN(f) ? val : f;
    }
    if (upper === "BOOLEAN") {
      if (val === "true" || val === true) return true;
      if (val === "false" || val === false) return false;
      return val;
    }
    return val;
  }

  window.executePipeline = executePipeline;
  window.collectParameters = collectParameters;
  window.coerceValue = coerceValue;
})();
