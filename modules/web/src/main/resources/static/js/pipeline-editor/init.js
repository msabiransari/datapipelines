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
   */
  function wireSqlCopy(editor) {
    document.addEventListener("click", function (evt) {
      var target = evt.target;
      var btn = target && target.closest ? target.closest(".pe-sql-copy") : null;
      if (!btn) return;
      var sql = btn.getAttribute("data-sql");
      if (sql === null) {
        var block = btn.closest(".pe-sql-block");
        var code = block && block.querySelector("code.pe-sql-code");
        sql = code ? code.textContent : "";
      }
      var done = function () {
        editor.announceStatus("SQL copied to clipboard");
        var label = btn.textContent;
        btn.textContent = "Copied";
        setTimeout(function () {
          btn.textContent = label;
        }, 1500);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(sql).then(done, function () {
          legacyCopy(sql, done);
        });
      } else {
        legacyCopy(sql, done);
      }
    });
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
      selectedNode: null,
      isExecuting: false,
      executionId: null,
      banner: { text: "", type: "info" },
      resultPanel: {
        visible: false,
        data: null,
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
        htmx.ajax("GET", url, {
          target: "#pe-node-sql",
          swap: "innerHTML",
          indicator: "#pe-node-sql-spinner",
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
