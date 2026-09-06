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
  /* The delegated listeners are module-level so teardown() can remove them (076
     §B): init() runs on every visit to the editor, and anonymous handlers would
     stack one per visit. */
  var sqlCopyHandler = null;
  var sqlHighlightHandler = null;
  var fitKeyHandler = null;

  function wireSqlCopy(editor) {
    if (sqlCopyHandler) document.removeEventListener("click", sqlCopyHandler);
    sqlCopyHandler = function (evt) {
      var target = evt.target;
      var btn = target && target.closest ? target.closest(".pe-sql-copy") : null;
      if (btn) {
        copyFrom(btn, sqlOf(btn));
        return;
      }
      var generic = target && target.closest ? target.closest(".pe-copy") : null;
      if (generic) copyFrom(generic, generic.getAttribute("data-copy") || "");
    };
    document.addEventListener("click", sqlCopyHandler);
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
      nodesById: {},
      parameters: {},
      paramKeys: [],
      parameterOverrides: {},
      nodeStates: {},
      /* 057: node_id → the wire error object from that node's node_failed. */
      nodeErrors: {},
      /* 072: the resolved execution Context — seeded from execution_started (org config,
         platform keys, parameters) and extended by each CALCULATOR node's completion.
         The Details pane resolves a `$reference` through it; empty before the first run,
         which is why every read below falls back to showing the reference itself. */
      contextValues: {},
      /* 072: node_id → the value a CALCULATOR node computed, as text. */
      nodeValues: {},
      /* 080 §B: node_id → the child execution a PIPELINE node spawned (from
         node_completed's child_execution_id), for the Details pane's Execution row. */
      childExecutions: {},
      selectedNode: null,
      /* 080 §B: the dock's four-tab state machine and the Events tab's log — both
         pure modules (dock.js, events.js) so `node --test` owns their transition
         tables and this file keeps only the DOM effects. The 065 inspector overlay
         is gone; Details is the dock's landing tab. */
      dock: window.PEDock.createDock(),
      eventsLog: window.PEEvents.createEventsLog(),
      isExecuting: false,
      executionId: null,
      /* 080 §D: the top bar's run status — dot + elapsed while running, the
         terminal summary after. The clock lives here (a DOM effect); sse.js's
         event handlers call start/stop. */
      runStatus: { phase: "idle", text: "Idle", startedAt: 0, timer: null, rows: null },
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
          var byId = {};
          self.nodes.forEach(function (n) { byId[n.id] = n; });
          self.nodesById = byId;
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
          wireFitKey(self);
          wireEventsScroll(self);

          // Highlight the SQL only after the partial has swapped in — never before:
          // the tokenizer reads the code element's textContent and replaces its
          // innerHTML with escaped, span-wrapped tokens (sql-highlight.js).
          if (sqlHighlightHandler) {
            document.body.removeEventListener("htmx:afterSwap", sqlHighlightHandler);
          }
          sqlHighlightHandler = function (evt) {
            var target = evt.detail && evt.detail.target;
            if (target && target.id === "pe-node-sql" && window.DpSqlHighlight) {
              window.DpSqlHighlight.apply(target);
            }
          };
          document.body.addEventListener("htmx:afterSwap", sqlHighlightHandler);

          // 080 §B: a tap SELECTS and fills the dock's Details tab — the pane the
          // 065 inspector overlay became. The card's expand button and Enter on a
          // focused row land here too (openNodeDetails delegates).
          self.cy.on("tap", "node", function (evt) {
            var nodeData = evt.target.data();
            self.selectNodeById(nodeData.id);
          });

          self.cy.on("tap", function (evt) {
            if (evt.target === self.cy) {
              self.selectedNode = null;
              self.dock.clearSelection();
            }
          });

          // 076 §B: the boosted-swap teardown reaches the live component through
          // this handle (wireBoostLifecycle below).
          window.__peInstance = self;
        } catch (e) {
          console.error("Pipeline Editor init failed:", e);
        }
      },

      /*
       * 076 §B — teardown before a swap replaces #app-main. The one real bug
       * boost can introduce is an execution stream living past the section
       * change, so the stream's reader is ABORTED here — never cancel(): the
       * run continues server-side (it is on /executions); only our view of it
       * goes away. Cytoscape is destroyed (its container is leaving the DOM),
       * timers are cleared, and the document-level listeners init() installed
       * come off so a later visit starts clean.
       */
      teardown: function () {
        var self = this;
        if (self.sseHandler && self.sseHandler.abortController) {
          self.sseHandler.abortController.abort();
        }
        if (self.sqlReloadTimer) {
          clearTimeout(self.sqlReloadTimer);
          self.sqlReloadTimer = null;
        }
        if (self.resultPanel && self.resultPanel.ttlInterval) {
          clearInterval(self.resultPanel.ttlInterval);
          self.resultPanel.ttlInterval = null;
        }
        self.stopRunClock("idle");
        if (self.cy) {
          self.cy.destroy();
          self.cy = null;
        }
        self.graph = null;
        if (sqlCopyHandler) {
          document.removeEventListener("click", sqlCopyHandler);
          sqlCopyHandler = null;
        }
        if (sqlHighlightHandler) {
          document.body.removeEventListener("htmx:afterSwap", sqlHighlightHandler);
          sqlHighlightHandler = null;
        }
        if (fitKeyHandler) {
          document.removeEventListener("keydown", fitKeyHandler);
          fitKeyHandler = null;
        }
      },

      /**
       * Selection (080 §B): the card highlight, `selectedNode`, the canvas's
       * `:selected` pseudo-state, the a11y list's `aria-selected` — and the dock's
       * Details tab, which fills with the node and surfaces (the mock's select()
       * calls showPane('details')). The 065 split between select-only and open
       * died with the overlay: there is no second pane to keep closed.
       */
      selectNodeById: function (id) {
        var self = this;
        if (!self.selectOnly(id)) return;
        self.dock.selectNode(id);
        self.loadNodeSql();
      },

      /**
       * The selection half, shared by the canvas tap and the list (no recursion).
       * `moveFocus` is false on the open path — see a11ySyncNode's contract.
       */
      selectOnly: function (id, moveFocus) {
        var self = this;
        var node = self.nodesById ? self.nodesById[id] : null;
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
       * 080 §B: the card's expand button and Enter/Space on a focused row — both
       * routes into Details. With the overlay gone this IS selectNodeById; the
       * separate name stays so the two call sites (and their tests) read as the
       * affordance they are.
       */
      openNodeDetails: function (id) {
        this.selectNodeById(id);
      },

      /*
       * The Details pane's SQL section (§8). SQL does not live in pipeline nodes —
       * the server resolves the node's PINNED template and renders it against the
       * pipeline's own parameter context. The overrides travel as §6.3 wire JSON
       * built by the page's OWN coerceValue — the same function the execute path
       * uses. Blank overrides are unsupplied: the server's declared defaults and
       * its sampled-parameter fallback apply, exactly as on execute.
       *
       * CALCULATOR and PIPELINE nodes have no SQL — their pane content is the
       * evaluation / child mapping, built client-side (definitionHtml), so the
       * fetch is skipped for them.
       */
      loadNodeSql: function () {
        var self = this;
        if (!self.selectedNode || !self.pipeline.id) return;
        if (!self.isSqlNode(self.selectedNode)) return;
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

      /** SQL-backed node types fetch the rendered statement; the others build it. */
      isSqlNode: function (node) {
        var t = node && String(node.type || "").toUpperCase();
        return t === "DQL" || t === "DML" || t === "DDL";
      },

      /* -------------------------------------------------- the Details pane */

      /** The pane's `--type`/`--type-bg` pair (the tile and the eyebrow read it). */
      detailsTypeStyle: function (node) {
        var tok = window.PEGraphUtil ? window.PEGraphUtil.typeToken(node && node.type) : "dql";
        return "--type:var(--type-" + tok + ");--type-bg:var(--type-" + tok + "-bg)";
      },

      detailsIcon: function (node) {
        var util = window.PEGraphUtil;
        var id = util ? util.iconForType(node && node.type) : "db";
        return (
          '<svg class="ds-icon ds-icon-sm" aria-hidden="true" focusable="false">' +
          '<use href="/vendor/icons/lucide-sprite.svg#' + id + '"></use></svg>'
        );
      },

      /**
       * The key/value facts, per node type (080 §B — the mock's Details meta):
       * SQL types get source / template / output / parameters; a CALCULATOR gets
       * kind / inputs (each expression beside what the last run resolved it to) /
       * writes / value; a PIPELINE gets child / parameters / output / execution.
       */
      detailsMeta: function (node) {
        if (!node) return [];
        var self = this;
        var rows = [];
        var type = String(node.type || "").toUpperCase();
        if (type === "CALCULATOR") {
          rows.push(["Kind", node.kind || "—"]);
          var inputs = self.calculatorInputs(node);
          rows.push([
            "Inputs",
            inputs.length
              ? inputs.map(function (i) {
                  return i.name + " = " + i.expression + (i.resolved !== null ? " → " + i.resolved : "");
                }).join(" · ")
              : "—",
          ]);
          rows.push(["Writes", node.context_key || "—"]);
          var value = self.calculatorValue(node);
          rows.push(["Value", value !== null ? value : "—"]);
        } else if (type === "PIPELINE") {
          var child = node.pipeline || {};
          rows.push(["Child", (child.name || "—") + (child.version ? " @ v" + child.version : "")]);
          var params = node.parameters ? Object.keys(node.parameters) : [];
          rows.push([
            "Parameters",
            params.length
              ? params.map(function (k) { return k + " ← " + node.parameters[k]; }).join(" · ")
              : "—",
          ]);
          rows.push(["Output", self.outputText(node)]);
          var childExec = self.childExecutions[node.id];
          rows.push(["Execution", childExec ? childExec + " (child)" : "—"]);
        } else {
          var source = node.source || "tempdb";
          if (node.source === "tempdb") {
            var engine =
              self.pipeline.settings && self.pipeline.settings.tempdb && self.pipeline.settings.tempdb.engine
                ? self.pipeline.settings.tempdb.engine
                : "H2";
            source = "tempdb (" + engine + ", per-execution)";
          }
          rows.push(["Source", source]);
          rows.push(["Template", self.templateRefText(node)]);
          rows.push(["Output", self.outputText(node)]);
          rows.push(["Parameters", self.paramKeys.length ? self.paramKeys.join(", ") : "—"]);
        }
        var state = self.nodeStates[node.id];
        if (state && state !== "idle") rows.push(["Last run", state]);
        return rows;
      },

      /** The sql-head's label: what the right-hand side of the pane is showing. */
      detailsSqlHead: function (node) {
        if (!node) return "";
        var type = String(node.type || "").toUpperCase();
        if (type === "CALCULATOR") return "Evaluation (inputs as resolved by the last run)";
        if (type === "PIPELINE") return "Child mapping (parameters passed down, output back)";
        return "Rendered SQL (template body, parameters as binds)";
      },

      /**
       * The non-SQL pane content (080 §B): a CALCULATOR's evaluation or a PIPELINE
       * node's child mapping, as escaped text with the mock's `-- comment` lines in
       * the muted token. Escaped by construction — every dynamic value goes through
       * the same escaper the cards use.
       */
      definitionHtml: function (node) {
        if (!node) return "";
        var esc = window.PEGraphUtil ? window.PEGraphUtil.escapeHtml : function (s) { return String(s); };
        var comment = function (s) { return '<span class="pe-sql-tok-comment">' + esc(s) + "</span>"; };
        var param = function (s) { return '<span class="pe-sql-tok-parameter">' + esc(s) + "</span>"; };
        var type = String(node.type || "").toUpperCase();
        var self = this;
        if (type === "CALCULATOR") {
          var lines = [comment("-- CALCULATOR nodes have no SQL; this pane shows the evaluation.")];
          lines.push(esc(node.kind || "?") + "(");
          self.calculatorInputs(node).forEach(function (i) {
            lines.push(
              "  " + esc(i.name) + " = " + esc(i.expression) +
                (i.resolved !== null ? "  " + comment("-- " + i.resolved) : "")
            );
          });
          var value = self.calculatorValue(node);
          lines.push(") → " + esc(node.context_key || "?") + (value !== null ? " = " + param(JSON.stringify(value)) : ""));
          return lines.join("\n");
        }
        if (type === "PIPELINE") {
          var child = node.pipeline || {};
          var out = [comment("-- PIPELINE node: runs " + (child.name || "?") + (child.version ? "@v" + child.version : "") + " as a child execution")];
          out.push("parameters:");
          var params = node.parameters ? Object.keys(node.parameters) : [];
          if (params.length) {
            params.forEach(function (k) {
              out.push("  " + esc(k) + ": " + param(String(node.parameters[k])));
            });
          } else {
            out.push("  " + comment("-- none passed down"));
          }
          out.push("output: " + esc(self.outputText(node)));
          var childExec = self.childExecutions[node.id];
          if (childExec) out.push(comment("-- last child execution: " + childExec));
          return out.join("\n");
        }
        return "";
      },

      /* ------------------------------------------------------ the run clock */

      /** execution_started: the status dot goes brand and the elapsed text ticks. */
      startRunClock: function () {
        var self = this;
        self.stopRunClock("running");
        self.runStatus.phase = "running";
        self.runStatus.startedAt = Date.now();
        self.runStatus.rows = null;
        self.runStatus.text = "Running · 0.0 s";
        self.runStatus.timer = setInterval(function () {
          var s = (Date.now() - self.runStatus.startedAt) / 1000;
          self.runStatus.text = "Running · " + s.toFixed(1) + " s";
        }, 100);
      },

      /**
       * A terminal event (or teardown): the clock stops and the status takes its
       * final text. `phase` is done | failed | aborted | idle; the elapsed comes
       * from the clock when it ran.
       */
      stopRunClock: function (phase) {
        var self = this;
        if (self.runStatus.timer) {
          clearInterval(self.runStatus.timer);
          self.runStatus.timer = null;
        }
        var elapsed = self.runStatus.startedAt ? ((Date.now() - self.runStatus.startedAt) / 1000).toFixed(1) : null;
        self.runStatus.phase = phase;
        if (phase === "done") {
          self.runStatus.text = "Completed" + (elapsed ? " · " + elapsed + " s" : "") +
            (self.runStatus.rows !== null && self.runStatus.rows !== undefined ? " · " + self.runStatus.rows + " rows" : "");
        } else if (phase === "failed") {
          self.runStatus.text = "Failed" + (elapsed ? " · " + elapsed + " s" : "");
        } else if (phase === "aborted") {
          self.runStatus.text = "Aborted" + (elapsed ? " · " + elapsed + " s" : "");
        } else if (phase === "running") {
          self.runStatus.text = "Running · 0.0 s";
        } else {
          self.runStatus.text = "Idle";
          self.runStatus.startedAt = 0;
        }
      },

      statusClass: function () {
        var p = this.runStatus.phase;
        if (p === "running") return "pe-status-running";
        if (p === "done") return "pe-status-done";
        if (p === "failed" || p === "aborted") return "pe-status-failed";
        return "";
      },

      /* ------------------------------------------------------ the Events tab */

      /**
       * 080 §B: EVERY SSE event lands in the Events tab, in arrival order — the
       * toast only ever announces the terminal one. sse.js's dispatch calls this
       * first for every kind. execution_started also resets the log (a new run's
       * timeline) and starts the top bar's clock.
       */
      logEvent: function (kind, payload) {
        var self = this;
        if (kind === "execution_started") {
          self.eventsLog.reset(Date.now());
          self.clearEventRows();
          self.startRunClock();
        }
        var view = window.PEEvents.formatEvent(kind, payload, {
          pipelineName: self.pipeline.name,
          nodeCount: self.nodes.length,
          nodesById: self.nodesById,
          outputText: function (n) { return self.outputText(n); },
        });
        var entry = self.eventsLog.append(kind, view, Date.now());
        self.appendEventRow(entry);
      },

      /** One timeline row (the mock's anatomy) — escaped by construction. */
      appendEventRow: function (entry) {
        var list = document.getElementById("pe-event-list");
        if (!list) return;
        var row = document.createElement("div");
        row.className = "pe-ev pe-ev-" + entry.kind + (entry.seq === 0 ? " pe-ev-start" : "");

        var t = document.createElement("span");
        t.className = "pe-ev-t";
        t.textContent = window.PEEvents.offsetText(entry.offsetMs);
        row.appendChild(t);

        var m = document.createElement("span");
        m.className = "pe-ev-m";
        m.appendChild(document.createElement("i"));
        row.appendChild(m);

        var k = document.createElement("span");
        k.className = "pe-ev-k";
        k.textContent = entry.kind;
        row.appendChild(k);

        var n = document.createElement("span");
        n.className = "pe-ev-n";
        if (entry.node) {
          var b = document.createElement("b");
          b.textContent = entry.node;
          n.appendChild(b);
          n.appendChild(document.createTextNode(" — "));
        }
        n.appendChild(document.createTextNode(entry.text));
        row.appendChild(n);

        var d = document.createElement("span");
        d.className = "pe-ev-d";
        d.textContent = entry.duration || "";
        row.appendChild(d);

        list.appendChild(row);

        // Auto-scroll: follow the tail until the user scrolls up (the scroll
        // listener in wireEventsScroll owns the pin).
        if (!self_eventsPinned(this)) {
          var pane = document.getElementById("pe-pane-events");
          if (pane) pane.scrollTop = pane.scrollHeight;
        }
      },

      clearEventRows: function () {
        var list = document.getElementById("pe-event-list");
        if (list) list.innerHTML = "";
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
        var rows = payload && payload.total_rows !== undefined && payload.total_rows !== null
          ? payload.total_rows
          : payload && payload.row_count;
        this.runStatus.rows = rows === undefined ? null : rows;
        this.dock.dataReady(rows === undefined ? null : rows);
      },

      /* 080 §B: a run starts — this run's Errors list empties, the dock's state is
         the user's and does not move, and a Results tab still showing the previous
         run's page says so until data_ready replaces it (065, kept). */
      handleExecutionStarted: function () {
        this.dock.executeStarted();
      },

      /**
       * 065 §B: one failure record joins the Errors tab. Called for `node_failed`
       * (the per-node record) AND for `pipeline_failed` — the execution-level
       * record. Same-node/code/message records dedupe, so a node failure followed
       * by the pipeline failure it caused lists once.
       */
      recordFailure: function (nodeId, error) {
        if (!error) return;
        this.dock.nodeFailed(nodeId || null, error);
        var n = this.dock.errors.length;
        this.announceStatus("Errors (" + n + ")");
      },

      /* 057/T85: pipeline_failed records the full record in the Errors tab plus
         the modal's one-line summary. */
      handlePipelineFailed: function (payload) {
        if (this.resultPanelInstance) {
          this.resultPanelInstance.showFailure(payload);
        }
        var err = payload && payload.error;
        this.recordFailure((err && err.node && err.node.id) || null, err);
        this.showError((err && (err.user_message || err.message)) || "Pipeline execution failed");
      },

      /* The failure renderer (details.js) — plain so Alpine expressions stay small
         and node --test can drive it. */
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
       * caller (default)" (contract §9.1) — never JSON.stringify's `undefined`.
       * DML/DDL and a zero-caller PIPELINE node are side effects (§4.4/§4.5/§4.9).
       */
      outputText: function (node) {
        if (!node) return "—";
        if (node.type === "CALCULATOR") return "context key " + (node.context_key || "—");
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
       * 072 §0.6 — the read-only view of a CALCULATOR node's inputs: each entry is
       * `{name, expression, resolved}` — what the BODY says beside what the last run
       * actually used. Before any run there is no Context, so `resolved` is null and
       * the pane shows the expression alone.
       */
      calculatorInputs: function (node) {
        if (!node || node.type !== "CALCULATOR" || !node.inputs) return [];
        var self = this;
        return Object.keys(node.inputs).map(function (name) {
          var raw = node.inputs[name];
          var expression = typeof raw === "string" ? raw : JSON.stringify(raw);
          var resolved = null;
          if (typeof raw === "string" && raw.charAt(0) === "$") {
            var key = raw.slice(1);
            if (Object.prototype.hasOwnProperty.call(self.contextValues, key)) {
              resolved = String(self.contextValues[key]);
            }
          }
          return { name: name, expression: expression, resolved: resolved };
        });
      },

      /** The value a CALCULATOR node computed in the last run, or null before one. */
      calculatorValue: function (node) {
        if (!node || node.type !== "CALCULATOR") return null;
        var value = this.nodeValues[node.id];
        return value === undefined || value === null ? null : String(value);
      },

      /*
       * §9.4's template reference: `acme/finance/monthly_revenue @ v3` — the same
       * string for the line and for its `title`.
       */
      templateRefText: function (node) {
        var t = node && node.template;
        if (!t || !t.id) return "—";
        return t.version ? t.id + " @ v" + t.version : t.id;
      },

      /* ------------------------------------------------- top bar + legend */

      /** The crumb's folder half: `nyc/mobility/` for `nyc/mobility/mobility_briefing`. */
      crumbPath: function () {
        var name = this.pipeline.name || "";
        var idx = name.lastIndexOf("/");
        return idx === -1 ? "" : name.slice(0, idx + 1);
      },

      /** The crumb's leaf: the pipeline's own name, bold in the mock. */
      crumbName: function () {
        var name = this.pipeline.name || this.pipeline.display_name || "";
        var idx = name.lastIndexOf("/");
        return idx === -1 ? name : name.slice(idx + 1);
      },

      /** One legend chip per node type present on the canvas (080 §A). */
      legendChips: function () {
        var seen = {};
        var chips = [];
        var LABELS = { DQL: "DQL", DML: "DML", DDL: "DDL", CALCULATOR: "Calculator", PIPELINE: "Pipeline" };
        (this.nodes || []).forEach(function (n) {
          var type = String(n.type || "").toUpperCase();
          if (!LABELS[type] || seen[type]) return;
          seen[type] = true;
          var tok = window.PEGraphUtil ? window.PEGraphUtil.typeToken(type) : "dql";
          chips.push({ label: LABELS[type], token: "--type-" + tok });
        });
        return chips;
      },

      announceStatus: function (msg) {
        announceStatus(msg);
      },
    };
  }

  /** The events log's pin, read tolerantly (Alpine proxies wrap the plain module). */
  function self_eventsPinned(editor) {
    return !!(editor.eventsLog && editor.eventsLog.userPinned);
  }

  /**
   * 080 §B: the Events pane's auto-scroll contract — follow the tail while the run
   * streams, yield the moment the user scrolls up, resume when they return to the
   * bottom. Installed per init on the pane itself (the element is stable markup).
   */
  function wireEventsScroll(editor) {
    var pane = document.getElementById("pe-pane-events");
    if (!pane || pane.__peEventsScrollWired) return;
    pane.__peEventsScrollWired = true;
    pane.addEventListener("scroll", function () {
      // Read the LIVE component — a history-restored pane keeps this listener
      // while the component that wired it was destroyed and re-bound (080 §B).
      var ed = (typeof window !== "undefined" && window.__peInstance) || editor;
      var atBottom = pane.scrollTop + pane.clientHeight >= pane.scrollHeight - 20;
      if (ed && ed.eventsLog && ed.eventsLog.setPinned) ed.eventsLog.setPinned(!atBottom);
    });
  }

  /**
   * 080 §A: `F` fits the graph (the hint pill advertises it). Guarded like every
   * shortcut that shares a page with inputs: never while typing, never with a
   * modifier. Module-level so teardown() can remove it.
   */
  function wireFitKey(editor) {
    if (fitKeyHandler) document.removeEventListener("keydown", fitKeyHandler);
    fitKeyHandler = function (e) {
      if (e.key !== "f" && e.key !== "F") return;
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      var t = e.target;
      var tag = t && t.tagName ? t.tagName.toLowerCase() : "";
      if (tag === "input" || tag === "textarea" || tag === "select" || (t && t.isContentEditable)) return;
      if (editor.graph && editor.graph.fitToView) {
        e.preventDefault();
        editor.graph.fitToView();
      }
    };
    document.addEventListener("keydown", fitKeyHandler);
  }

  /*
   * 076 §B — the editor's boost lifecycle, one document-level pair installed ONCE
   * per session (this script re-executes on every boosted visit to the editor;
   * the flag keeps the wiring singular).
   *
   * htmx:beforeSwap — the outgoing swap replaces the editor's host region:
   * teardown the live component. Boosted swaps qualify outright (shell.js
   * retargets them at #app-main); so does any swap whose target IS #app-main or
   * an ancestor of it, because htmx history restores swap the cached fragment
   * WITHOUT a boosted flag. Partial swaps inside the editor (node SQL, result
   * pages) target inner nodes and never match.
   *
   * htmx:afterSettle — the rescue half: a history restore brings the editor's
   * DOM back WITHOUT re-executing its scripts, so no component is bound.
   *
   * 080 §B — THE EXACTLY-ONCE TOAST FIX. The cached DOM comes back with the
   * PREVIOUS component's Alpine state (`_x_dataStack`) and its @click listeners
   * still attached (teardown kills the stream and the canvas; it cannot unbind
   * Alpine). The old rescue ran a bare `Alpine.initTree(root)`, and Alpine's
   * x-data guard (`data-has-alpine-state`) is only set by Alpine.clone — not by
   * a history restore — so initTree stacked a SECOND component on the same root:
   * every @click registered twice, one Execute click fired executePipeline()
   * once per stacked component, and N executions meant N success toasts on
   * completion — the owner's report. Another restore stacked a third. The fix
   * destroys the stale tree before re-binding: one root, one component, one
   * stream, one toast. The falsifying test is editor-toast-once.test.mjs.
   */
  function wireBoostLifecycle() {
    if (window.__peBoostWired) return;
    if (typeof document === "undefined" || !document.addEventListener) return; // node --test
    window.__peBoostWired = true;

    document.addEventListener("htmx:beforeSwap", function (evt) {
      var inst = window.__peInstance;
      if (!inst) return;
      var detail = evt.detail || {};
      var target = detail.target;
      var main = document.getElementById("app-main");
      var replacesMain =
        detail.boosted ||
        target === main ||
        target === document.body ||
        (target && target.contains && main && target.contains(main));
      if (!replacesMain) return;
      inst.teardown();
      window.__peInstance = null;
      window.PEDraft = null;
    });

    document.addEventListener("htmx:afterSettle", function () {
      var main = document.getElementById("app-main");
      var root = main && main.querySelector ? main.querySelector(".pe-root") : null;
      if (!root || window.__peInstance || !window.Alpine || !window.Alpine.initTree) return;
      // See the block comment above: destroy the stale tree BEFORE re-binding, or
      // the restored root stacks components and one click runs N executions.
      if (root._x_dataStack && window.Alpine.destroyTree) {
        window.Alpine.destroyTree(root);
      }
      window.Alpine.initTree(root);
    });
  }

  window.pipelineEditor = pipelineEditor;
  window.pipelineEditorBoost = { wireBoostLifecycle: wireBoostLifecycle };
  wireBoostLifecycle();
})();
