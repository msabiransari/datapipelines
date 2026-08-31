(function () {
  "use strict";

  function executePipeline(editor) {
    var pipelineId = editor.pipeline.id;
    if (!pipelineId) return;

    editor.isExecuting = true;
    editor.setBanner("", "");

    if (editor.graph) editor.graph.resetAll();
    editor.nodeStates = {};

    if (editor.sseHandler) {
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
