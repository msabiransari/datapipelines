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
    if (upper === "INTEGER" || upper === "BIGINTEGER") {
      var n = parseInt(val, 10);
      return isNaN(n) ? val : n;
    }
    if (upper === "DECIMAL" || upper === "BIGDECIMAL") {
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
