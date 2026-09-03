(function () {
  "use strict";

  /*
   * 032: the SQL copy confirmation is deliberately NOT a toast, even though
   * DpToast.show now exists. Copy is high-frequency and self-evident; a 6s
   * notification per copy trains the user to ignore the stack the SSE terminal
   * events need. The live region is the a11y-correct channel and the 1.5s label
   * swap is the visible one (ui-screens.md §5.1 keeps exactly one client-side
   * toast builder; this is not a second one).
   *
   * The SQL is read from the button's data-sql attribute, falling back to the
   * code element's textContent — NEVER from the highlighted innerHTML, which
   * carries <span> markup.
   *
   * 057: the same delegated listener serves the failure record's Copy button —
   * any .pe-copy with a data-copy attribute. One copy channel for the editor,
   * not two to keep in step.
   */
  function wireSqlCopy(editor) {
    document.addEventListener("click", function (evt) {
      var target = evt.target;
      var btn = target && target.closest ? target.closest(".pe-sql-copy") : null;
      if (btn) {
        copyFrom(btn, sqlOf(btn));
        return;
      }
      var generic = target && target.closest ? target.closest(".pe-copy") : null;
      if (generic) copyFrom(generic, generic.getAttribute("data-copy") || "");
    });
  }

  function sqlOf(btn) {
    var sql = btn.getAttribute("data-sql");
    if (sql === null) {
      var block = btn.closest(".pe-sql-block");
      var code = block && block.querySelector("code.pe-sql-code");
      sql = code ? code.textContent : "";
    }
    return sql;
  }

  function copyFrom(btn, text) {
    var what = btn.getAttribute("data-copy-label") || "SQL";
    var done = function () {
      editor.announceStatus(what + " copied to clipboard");
      var label = btn.textContent;
      btn.textContent = "Copied";
      setTimeout(function () {
        btn.textContent = label;
      }, 1500);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, function () {
        legacyCopy(text, done);
      });
    } else {
      legacyCopy(text, done);
    }
  }

  /* Non-secure-context fallback: the async clipboard API requires one. */
  function legacyCopy(text, done) {
    var ta = document.createElement("textarea");
    ta.value = text;
    ta.setAttribute("readonly", "");
    ta.style.position = "absolute";
    ta.style.left = "-9999px";
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand("copy");
      done();
    } catch (e) {
      /* no copy channel available — leave the button unchanged */
    }
    document.body.removeChild(ta);
  }

  function pipelineEditor() {
    return {
      pipeline: {},
      nodes: [],
      parameters: {},
      paramKeys: [],
      parameterOverrides: {},
      nodeStates: {},
      /* 057: node_id → the wire error object from that node's node_failed (the inspector
         renders it while the node's runtime state is "failed"). */
      nodeErrors: {},
      selectedNode: null,
      isExecuting: false,
      executionId: null,
      banner: { text: "", type: "info" },
      resultPanel: {
        visible: false,
        data: null,
        failure: null,
        columns: [],
        rows: [],
        page: 1,
        totalPages: 1,
        hasPrev: false,
        hasNext: false,
        ttlSeconds: 0,
        ttlInterval: null,
        expired: false,
        cursorEndpoint: null,

        prevPage: function () {},
        nextPage: function () {},
        downloadUrl: function () { return "#"; },
      },
      errorModal: {
        visible: false,
        message: "",

        hide: function () {
          this.visible = false;
          this.message = "";
        },
      },

      cy: null,
      graph: null,
      sseHandler: null,
      resultPanelInstance: null,
      sqlReloadTimer: null,

      init: function () {
        var self = this;
        var el = document.getElementById("pipeline-data");
        if (!el) return;
        try {
          var data = JSON.parse(el.textContent);
          self.pipeline = data;
          self.nodes = data.nodes || [];
          self.parameters = data.parameters || {};
          self.paramKeys = Object.keys(self.parameters);

          var overrides = {};
          self.paramKeys.forEach(function (k) {
            overrides[k] = "";
          });
          self.parameterOverrides = overrides;

          self.resultPanelInstance = new ResultPanel(self);
          self.setupResultPanelMethods();
          self.sseHandler = new SseHandler(self);
          self.graph = new PipelineGraph("cy-canvas", self.nodes, self);
          self.graph.render();
          self.cy = self.graph.cy;

          setupA11y(self);
          wireSqlCopy(self);

          // Highlight the SQL only after the partial has swapped in — never before:
          // the tokenizer reads the code element's textContent and replaces its
          // innerHTML with escaped, span-wrapped tokens (sql-highlight.js).
          document.body.addEventListener("htmx:afterSwap", function (evt) {
            var target = evt.detail && evt.detail.target;
            if (target && target.id === "pe-node-sql" && window.DpSqlHighlight) {
              window.DpSqlHighlight.apply(target);
            }
          });

          self.cy.on("tap", "node", function (evt) {
            var nodeData = evt.target.data();
            self.selectNodeById(nodeData.id);
          });

          self.cy.on("tap", function (evt) {
            if (evt.target === self.cy) {
              self.selectedNode = null;
            }
          });
        } catch (e) {
          console.error("Pipeline Editor init failed:", e);
        }
      },

      selectNodeById: function (id) {
        var self = this;
        var node = null;
        for (var i = 0; i < self.nodes.length; i++) {
          if (self.nodes[i].id === id) {
            node = self.nodes[i];
            break;
          }
        }
        self.selectedNode = node;
        if (self.cy) {
          self.cy.elements().unselect();
          var cyNode = self.cy.getElementById(id);
          if (cyNode.length) cyNode.select();
        }
        a11ySyncNode(id);
        self.loadNodeSql();
      },

      /*
       * The details panel's SQL section (§8). SQL does not live in pipeline nodes —
       * the server resolves the node's PINNED template and renders it against the
       * pipeline's own parameter context. The overrides travel as §6.3 wire JSON
       * built by the page's OWN coerceValue — the same function the execute path
       * uses (one coercion path for both surfaces; a second one here would
       * reintroduce the divergence 027b B existed to fix). Blank overrides are
       * unsupplied: the server's declared defaults and its sampled-parameter
       * fallback apply, exactly as on execute.
       */
      loadNodeSql: function () {
        var self = this;
        if (!self.selectedNode || !self.pipeline.id) return;
        var wire = {};
        Object.keys(self.parameterOverrides || {}).forEach(function (k) {
          var raw = self.parameterOverrides[k];
          if (raw === undefined || raw === null || raw === "") return;
          var type = (self.parameters[k] && self.parameters[k].type) || "STRING";
          wire[k] = window.coerceValue(raw, type);
        });
        var url =
          "/partials/pipelines/" + encodeURIComponent(self.pipeline.id) +
          "/nodes/" + encodeURIComponent(self.selectedNode.id) + "/sql" +
          "?parameters=" + encodeURIComponent(JSON.stringify(wire));
        // #pe-node-sql lives inside <template x-if="selectedNode">, which Alpine
        // renders on the NEXT tick — issuing htmx.ajax synchronously off a
        // selection change hits htmx:targetError and the section never loads.
        self.$nextTick(function () {
          if (!document.getElementById("pe-node-sql")) return;
          htmx.ajax("GET", url, {
            target: "#pe-node-sql",
            swap: "innerHTML",
            indicator: "#pe-node-sql-spinner",
          });
        });
      },

      /* Typing in an override box must not fire a render per keystroke — the same
         ~300ms debounce the list screens use for search. */
      onParameterInput: function () {
        var self = this;
        if (self.sqlReloadTimer) clearTimeout(self.sqlReloadTimer);
        self.sqlReloadTimer = setTimeout(function () {
          self.loadNodeSql();
        }, 300);
      },

      setupResultPanelMethods: function () {
        var self = this;
        self.resultPanel.prevPage = function () {
          if (self.resultPanelInstance && self.resultPanel.hasPrev) {
            self.resultPanelInstance.loadPage(self.resultPanel.page - 1);
          }
        };
        self.resultPanel.nextPage = function () {
          if (self.resultPanelInstance && self.resultPanel.hasNext) {
            self.resultPanelInstance.loadPage(self.resultPanel.page + 1);
          }
        };
        self.resultPanel.downloadUrl = function (format) {
          var endpoint = self.resultPanelInstance && self.resultPanelInstance.cursorEndpoint;
          if (!endpoint) return "#";
          return endpoint + "?format=" + format;
        };
      },

      executePipeline: function () {
        if (!this.pipeline.id) return;
        executePipeline(this);
      },

      cancelExecution: function () {
        if (this.sseHandler) this.sseHandler.cancel();
      },

      handleDataReady: function (payload) {
        if (this.resultPanelInstance) {
          this.resultPanelInstance.showData(payload);
        }
      },

      /* 057/T85: pipeline_failed opens the result panel's failure mode with the full
         record, plus the modal's one-line summary. Reporting "the pipeline failed"
         without the root cause is what the UI did the night of T85. */
      handlePipelineFailed: function (payload) {
        if (this.resultPanelInstance) {
          this.resultPanelInstance.showFailure(payload);
        }
        var err = payload && payload.error;
        this.showError((err && (err.user_message || err.message)) || "Pipeline execution failed");
      },

      /* The inspector/result-panel failure renderer (details.js) — plain so Alpine
         expressions stay small and node --test can drive it. */
      failureView: function (error) {
        return window.PEErrorDetails ? window.PEErrorDetails.build(error) : null;
      },

      showError: function (msg) {
        this.errorModal.visible = true;
        this.errorModal.message = msg;
      },

      setBanner: function (text, type) {
        this.banner.text = text;
        this.banner.type = type;
      },

      /*
       * §8.1's Output copy: an omitted block on a DQL node is "returns result to
       * caller (default)" (contract §9.1) — never JSON.stringify's `undefined`,
       * which is what the panel used to print for the commonest case. DML/DDL and
       * a zero-caller PIPELINE node are side effects (§4.4/§4.5/§4.9).
       */
      outputText: function (node) {
        if (!node) return "—";
        if (node.type === "DML" || node.type === "DDL") return "side effect";
        if (!node.output) {
          return node.type === "DQL" ? "returns result to caller (default)" : "side effect";
        }
        var o = node.output;
        if (o.target === "caller") return "returns result to caller";
        if (o.target === "tempdb") return "tempdb → table " + (o.table || "—");
        if (o.target === "datasource") {
          return (
            "datasource " + (o.datasource || "—") + " → " + (o.table || "—") +
            (o.mode ? " (" + o.mode + ")" : "")
          );
        }
        return JSON.stringify(o);
      },

      /*
       * §9.4's template reference: `acme/finance/monthly_revenue @ v3`.
       *
       * A template name is a PATH (template-hierarchy-design §4.1), so this string is the
       * one the inspector truncates to a single line AND the one it puts on `title` — the
       * two must be the same value, which is why the panel calls this once for each rather
       * than building the text twice. An em dash stands in for a node with no template.
       */
      templateRefText: function (node) {
        var t = node && node.template;
        if (!t || !t.id) return "—";
        return t.version ? t.id + " @ v" + t.version : t.id;
      },

      /* Execution status → the badge variant the rest of the app uses for it. */
      statusBadgeClass: function (state) {
        switch (state) {
          case "success":
            return "ds-badge-success";
          case "failed":
            return "ds-badge-danger";
          case "running":
            return "ds-badge-primary";
          case "aborted":
            return "ds-badge-warning";
          default:
            return "ds-badge-default";
        }
      },

      announceStatus: function (msg) {
        announceStatus(msg);
      },
    };
  }

  window.pipelineEditor = pipelineEditor;
})();
