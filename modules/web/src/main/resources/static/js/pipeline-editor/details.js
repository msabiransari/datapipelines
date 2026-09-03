(function () {
  "use strict";

  function DetailsPanel(editor) {
    this.editor = editor;
    this.visible = false;
    this.nodeData = null;
  }

  DetailsPanel.prototype.show = function (nodeData) {
    this.nodeData = nodeData;
    this.visible = true;
    this.editor.selectedNode = nodeData;
  };

  DetailsPanel.prototype.hide = function () {
    this.visible = false;
    this.nodeData = null;
    this.editor.selectedNode = null;
  };

  /*
   * 057/T85 — the failure view-model: the one function that turns a wire `error` object
   * (node_failed / pipeline_failed / error_json) into what the inspector and the result
   * panel render. Pure on purpose: Alpine renders it in the browser and `node --test`
   * asserts it here (sse-node-failure.test.mjs) — no DOM in either path.
   *
   * The wire's `caused_by` is outermost-first, root cause LAST (the orientation Java's
   * getCause walks). Humans read the opposite way, so `chain` is REVERSED for display:
   * chain[0] is the ROOT cause. `rootCause` is its one-line "class: message" form.
   *
   * Under error-detail=structured the exception and sql sections are simply absent —
   * build() returns nulls for them and the templates hide on null. It never fabricates a
   * "details unavailable" apology; it shows what it has.
   */
  var PEErrorDetails = {
    build: function (error) {
      if (!error) return null;
      var chain = null;
      var rootCause = null;
      if (error.exception) {
        var levels = [{ cls: error.exception.class, message: error.exception.message, frames: error.exception.frames || [] }].concat(
          (error.exception.caused_by || []).map(function (l) {
            return { cls: l.class, message: l.message, frames: l.frames || [] };
          })
        );
        // Root-cause FIRST for humans (the wire is outermost-first).
        chain = levels.slice().reverse();
        var root = chain[0];
        rootCause = root.cls + (root.message ? ": " + root.message : "");
      }
      return {
        code: error.code || "unknown",
        message: error.message || "",
        userMessage: error.user_message || null,
        correlationId: error.correlation_id || null,
        node: error.node || null,
        nodeLine: error.node ? this.nodeLine(error.node) : null,
        sql: error.sql || null,
        rootCause: rootCause,
        chain: chain,
        detailsText: error.details ? JSON.stringify(error.details, null, 2) : null,
        copyText: this.copyText(error, chain, rootCause),
      };
    },

    /* "stage_daily_trips · DQL · sample-trips (POSTGRES) · sample_trips_daily.sql @ v1" */
    nodeLine: function (node) {
      var parts = [node.id, node.type];
      if (node.datasource) parts.push(node.datasource + (node.dialect ? " (" + node.dialect + ")" : ""));
      if (node.template) parts.push(node.template + (node.template_version ? " @ v" + node.template_version : ""));
      return parts.join(" · ");
    },

    /* The whole failure, greppable: everything the inspector shows, one clipboard payload. */
    copyText: function (error, chain, rootCause) {
      var lines = [];
      lines.push("code: " + (error.code || "unknown"));
      lines.push("message: " + (error.message || ""));
      if (error.user_message) lines.push("user_message: " + error.user_message);
      if (error.correlation_id) lines.push("correlation_id: " + error.correlation_id);
      if (error.node) lines.push("node: " + this.nodeLine(error.node));
      if (error.details) lines.push("details: " + JSON.stringify(error.details));
      if (error.sql) lines.push("sql: " + error.sql);
      if (error.exception) {
        lines.push("exception: " + error.exception.class + (error.exception.message ? ": " + error.exception.message : ""));
        (chain || []).forEach(function (level) {
          lines.push("caused by: " + level.cls + (level.message ? ": " + level.message : ""));
          (level.frames || []).forEach(function (f) {
            lines.push("    at " + f);
          });
        });
      }
      return lines.join("\n");
    },
  };

  window.DetailsPanel = DetailsPanel;
  window.PEErrorDetails = PEErrorDetails;

  // node --test (027b harness): load without a browser.
  if (typeof module !== "undefined" && module.exports) {
    module.exports = PEErrorDetails;
  }
})();
