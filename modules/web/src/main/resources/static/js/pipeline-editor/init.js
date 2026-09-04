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
      /* 065 §B/§C: the two panes' state machines, both pure modules (dock.js,
         inspector.js) so `node --test` owns their transition tables and this file
         keeps only the DOM effects — the SQL load, the focus moves. */
      dock: window.PEDock.createDock(),
      inspector: window.PEInspector.createInspector(),
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

          // 065 §C: a tap SELECTS and nothing else — the inspector opens from the
          // card's own button (or Enter/Space on the selected node). Sliding a
          // 320px drawer in on every click through the graph is what made the
          // drawer the only place SQL could live, and it fired a partial request
          // per click on the way.
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

      /**
       * Selection, and ONLY selection (065 §C): the card highlight, `selectedNode`,
       * the canvas's `:selected` pseudo-state and the a11y list's `aria-selected`.
       * It does not open the inspector and does not fetch SQL — those cost a
       * request and a pane per click through the graph, which is what made the
       * pane small enough to ignore. If the inspector is ALREADY up, it re-targets
       * in place, because a selection change with a stale panel beside it is worse
       * than either behaviour on its own.
       */
      selectNodeById: function (id) {
        var self = this;
        self.selectOnly(id);
        if (self.inspector.open) self.openNodeDetails(id, null);
      },

      /**
       * The selection half, shared by select-only and open (no recursion).
       * `moveFocus` is false on the open path — see a11ySyncNode's contract.
       */
      selectOnly: function (id, moveFocus) {
        var self = this;
        var node = null;
        for (var i = 0; i < self.nodes.length; i++) {
          if (self.nodes[i].id === id) {
            node = self.nodes[i];
            break;
          }
        }
        if (!node) return null;
        self.selectedNode = node;
        if (self.cy) {
          self.cy.elements().unselect();
          var cyNode = self.cy.getElementById(id);
          if (cyNode.length) cyNode.select();
        }
        a11ySyncNode(id, moveFocus);
        return node;
      },

      /**
       * 065 §C — the only route into the inspector: the card's open button, the
       * node list's Enter/Space, or a re-target while the panel is already up.
       * `trigger` is the control focus returns to on close — a hint, not a promise:
       * a card button does not survive the html-label's next re-render, which is
       * why `restoreFocusTo` re-finds it by node id when the handle goes stale.
       */
      openNodeDetails: function (id, trigger) {
        var self = this;
        // moveFocus=false: the list mirror must NOT take focus here — the panel is
        // about to, and the two calls race (measured live: the hidden <li> won at
        // 2 of 3 zoom levels).
        if (!self.selectOnly(id, false)) return;
        var how = self.inspector.openFrom(id, trigger);
        self.loadNodeSql();
        // Focus moves INTO the panel on a fresh open. A replace leaves it where it
        // is — already inside the panel, or on the card just clicked; moving it
        // again on every re-target IS the flicker.
        if (how === "open") self.focusInspector(0);
      },

      /**
       * Focus the panel's close button once Alpine has actually rendered AND SHOWN
       * it. `$nextTick` alone is not enough, and neither is "the element exists":
       * the panel's body is behind an `x-if` that can land a frame after the
       * `x-show`, and `focus()` on an element whose ancestor still carries
       * `display: none` is a silent NO-OP — measured live on the demo stack
       * (2026-09-04), where the button was in the DOM and `document.activeElement`
       * was still `<body>` at every zoom level. So the loop retries until focus
       * DEMONSTRABLY landed, then stops; the bound gives up rather than spinning.
       */
      focusInspector: function (attempt) {
        var self = this;
        if (!self.inspector.open || attempt > 8) return;
        var again = function () {
          if (typeof requestAnimationFrame === "function") {
            requestAnimationFrame(function () {
              self.focusInspector(attempt + 1);
            });
          }
        };
        self.$nextTick(function () {
          var close = document.getElementById("pe-details-close");
          if (!close || !close.focus) return again();
          close.focus();
          if (document.activeElement !== close) again();
        });
      },

      /** Close the inspector and hand focus back to the control that opened it. */
      closeNodeDetails: function () {
        var nodeId = this.inspector.nodeId;
        var back = this.inspector.close();
        this.restoreFocusTo(back, nodeId);
      },

      /**
       * Put focus back on the control that opened the inspector.
       *
       * The stored handle is usually enough — a node-list row is a stable element.
       * A CARD button is not: `cytoscape-node-html-label` re-renders its template on
       * every `data`/`style` event, and selecting the node is itself a style event,
       * so by close time the captured button is typically DETACHED. `focus()` on a
       * detached element is a silent no-op, and the tell is only visible to a
       * keyboard user (measured live on the demo stack, 2026-09-04: focus landed on
       * `<body>` after every close-from-card).
       *
       * So: try the handle, and if it is gone or refuses focus, re-find the LIVE
       * button for that node by its `data-node-open` attribute. Scanning the
       * attribute beats a selector because node ids come from user-authored pipeline
       * JSON and would need escaping.
       */
      restoreFocusTo: function (el, nodeId) {
        var connected = el && (el.isConnected === undefined || el.isConnected);
        if (el && el.focus && connected) {
          el.focus();
          if (document.activeElement === el) return;
        }
        if (!nodeId) return;
        var buttons = document.querySelectorAll(".pe-card-open");
        for (var i = 0; i < buttons.length; i++) {
          if (buttons[i].getAttribute("data-node-open") === nodeId) {
            buttons[i].focus();
            return;
          }
        }
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
        this.dock.dataReady();
      },

      /* 065 §B: a run starts — this run's Errors list empties, the dock's state is
         the user's and does not move, and a Results tab still showing the previous
         run's page says so until data_ready replaces it. */
      handleExecutionStarted: function () {
        this.dock.executeStarted();
      },

      /**
       * 065 §B: one failure record joins the Errors tab. Called for `node_failed`
       * (the per-node record) AND for `pipeline_failed` — the execution-level
       * record used to render inside the results panel, and moving that block out
       * of Results without giving it the Errors tab would have deleted 057's whole
       * point from the page. Same-node/code/message records dedupe, so a node
       * failure followed by the pipeline failure it caused lists once.
       */
      recordFailure: function (nodeId, error) {
        if (!error) return;
        this.dock.nodeFailed(nodeId || null, error);
        var n = this.dock.errors.length;
        this.announceStatus("Errors (" + n + ")");
      },

      /* 057/T85: pipeline_failed opens the result panel's failure mode with the full
         record, plus the modal's one-line summary. Reporting "the pipeline failed"
         without the root cause is what the UI did the night of T85. */
      handlePipelineFailed: function (payload) {
        if (this.resultPanelInstance) {
          this.resultPanelInstance.showFailure(payload);
        }
        var err = payload && payload.error;
        this.recordFailure((err && err.node && err.node.id) || null, err);
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
