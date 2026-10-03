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

  /* The graph zoom steps (301 #305): in by 1.25, out by its exact reciprocal, so a
     zoom-in then zoom-out returns the view and the two buttons move together. */
  var ZOOM_IN_STEP = 1.25;
  var ZOOM_OUT_STEP = 0.8;

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
      /* 195: the Parameters sidebar's field view models (param-fields.js) — the
         strings and branches the template used to compute inline. */
      paramFields: [],
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
      /* 7d (#7): `id@version` → a TRANSFORM pin resolved by graph.js (loadTransformPins) —
         { language, mode, rejects, needsReview }. The Details pane reads the mode and the
         marker here; empty until the lookups land, which the rows say ("resolving…"). */
      transformPins: {},
      selectedNode: null,
      /* 080 §B: the dock's four-tab state machine and the Events tab's log — both
         pure modules (dock.js, events.js) so `node --test` owns their transition
         tables and this file keeps only the DOM effects. The 065 inspector overlay
         is gone; Details is the dock's landing tab. */
      dock: window.PEDock.createDock(),
      eventsLog: window.PEEvents.createEventsLog(),
      /* #349 — the workspace's six-tab state (tabs.js, pure; admission re-read from the
         root's server-stamped attributes in init()). Created with the flow default so
         the template's bindings resolve even before init() runs. */
      tabs: window.PETabs ? window.PETabs.createTabs(false, "flow") : null,
      /* #349 — the composition state the page's ONE workspace block carries: the
         admitted history (the header selector, the viewed chip and the Versions tab's
         marks read it), the Overview's record-level facts and the datasource dialect
         map across every admitted body. All of it is the lens's answer, written by the
         server; the client re-reads it per version switch. */
      versionRows: [],
      pageFacts: {},
      datasourceDialects: {},
      /* The VIEWED version as a reactive field (#349): the getters that branch on it
         (the run strip, the chips, the selector) must re-evaluate when an in-page
         switch moves it — window.PEWorkspace is a plain global Alpine cannot track. */
      viewedVersion: null,
      /* The record-level draft flag (the server's hasDraft), read off the root. */
      pageHasDraft: false,
      /* #349 — the page/view state key's generation (spec §4.3): workspace + pipeline +
         viewed version + request generation. Every in-page fetch is stamped; a response
         that disagrees with the CURRENT stamp is stale and never applied — successes,
         refusals and toasts alike (A8). */
      viewGeneration: 0,
      /* #349 — run input drafts are PER VERSION within the page (spec §4.3): switching
         the viewed version saves this version's overrides and loads the other
         version's own, filtered to its schema. Never persisted anywhere. */
      overrideStore: window.PEWorkspaceLogic ? window.PEWorkspaceLogic.createOverrideStore() : null,
      /* #349 — the execution state's identity (spec §4.2/§4.3): ONE captured run with
         the submitted pipeline/version/parameter snapshot, kept while the viewed
         version moves under it. The strip and every run-fact gate read this. */
      runIdentity: null,
      executionVersion: null,
      runsLoading: false,
      runsLoadFailed: false,
      runsLoaded: false,
      usageLoading: false,
      usageLoadFailed: false,
      usageLoaded: false,
      /* The sink tokens (workspace.js tokenMatches): the record each pane read was
         issued under; a stale response's swap is cancelled before it paints. */
      sqlToken: null,
      checksToken: null,
      runsToken: null,
      usageToken: null,
      // 149: the node-operation view model (node-ops.js) — every lifecycle and
      // node_progress event reduces into it (sse.js); the cards, the Details pane and
      // the a11y list read it; 151's output connector will too.
      nodeOps: window.PENodeOps ? window.PENodeOps.createNodeOps() : null,
      // 151/#144: whether THIS viewer may execute — the same server-rendered `canExecute`
      // that renders the toolbar's Execute button, stamped on `.pe-root` as
      // `data-can-execute` and read by init(). The Start marker is a run trigger only when
      // this is true; nothing in JS derives it from roles.
      canExecute: false,
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
        displayRows: [],
        page: 1,
        totalPages: 1,
        hasPrev: false,
        hasNext: false,
        ttlSeconds: 0,
        ttlInterval: null,
        expired: false,
        cursorEndpoint: null,
        downloadJsonHref: "#",
        downloadCsvHref: "#",
        downloadArrowHref: "#",

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
          // The data script sits in <head>, so the root is looked up, not walked up to.
          self.canExecute = self.canExecuteFrom(typeof document.querySelector === "function" ? document.querySelector(".pe-root") : null);

          var overrides = {};
          self.paramKeys.forEach(function (k) {
            overrides[k] = "";
          });
          self.parameterOverrides = overrides;
          // 195: the sidebar's per-field view models — placeholder, description,
          // type, branch flags — built once here, read as paths by the template.
          // #349: the sidebar is the Parameters tab now; the view models are the same.
          self.paramFields = window.PEParamFields ? window.PEParamFields.buildParamFields(self.parameters) : [];
          // #349 — the per-version override store seeds THIS version's bag; switching
          // versions swaps bags (workspace.js createOverrideStore).
          if (self.overrideStore) self.overrideStore.save(self.viewedVersionOrNull(), self.parameterOverrides);

          self.resultPanelInstance = new ResultPanel(self);
          self.setupResultPanelMethods();
          self.sseHandler = new SseHandler(self);
          self.graph = new PipelineGraph("cy-canvas", self.nodes, self);
          self.graph.render();
          self.cy = self.graph.cy;
          self.wireGraphEventsOn();

          // #349 — the composition state: tabs admission, selector rows, Overview facts.
          self.readComposition();

          /* 140: the body's release checks — the Overview tab's verdict list lazy-loads
             from the read-only checks partial, and only when the body DECLARES any (a
             check-less pipeline pays no request). htmx.ajax fires the same CSRF-wired
             request an hx-get would; the swapped fragment carries no hx-* of its own,
             so nothing needs re-processing. #349: the read is stamped with the page's
             sink token — a version switch re-issues it and cancels the stale one. */
          var checksTarget = document.getElementById("pe-checks-latest");
          if (checksTarget && Array.isArray(data.checks) && data.checks.length > 0 && window.htmx) {
            self.checksToken = "t" + self.nextToken();
            checksTarget.setAttribute("data-pe-token", self.checksToken);
            window.htmx.ajax("GET", checksTarget.getAttribute("data-checks-url"), {
              source: document.getElementById("pe-sql-requester") || undefined,
              target: "#pe-checks-latest",
              swap: "innerHTML",
              headers: { "DP-PE-Sink-Token": self.checksToken },
            });
          }

          // The first fit ran at layoutstop, which can precede Alpine's x-show
          // flush (the banner/modal hide only then, growing the stage). One refit
          // after the flush keeps the graph centred in the canvas it really has.
          // $nextTick is Alpine's — absent under node --test.
          if (typeof self.$nextTick === "function") {
            self.$nextTick(function () {
              if (self.graph && self.graph.fitToView) self.graph.fitToView();
            });
          }

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

          // 076 §B: the boosted-swap teardown reaches the live component through
          // this handle (wireBoostLifecycle below).
          window.__peInstance = self;

          // #358 — a run this document started that is STILL RUNNING follows the
          // viewer across a boosted navigation: the old handler was disposed at
          // teardown (detached, never cancelled — see sse.js), and the component
          // this restore/arrival just bound re-attaches to the same execution's
          // event stream. The record is window-level (it survives the restore,
          // dies with the document) and clears at the run's terminal event, so a
          // fresh full load (a new window) never re-attaches and a finished run
          // never re-announces.
          // Keyed by PIPELINE as well as execution: a boosted entry into another pipeline's
          // workspace (the explorer's Open links are boosted) must not adopt this run.
          // #349 — keyed by VERSION as well: a page restored at another version than the
          // run's does not paint the run's replay onto that body (spec §4.2: node run
          // facts attach only when pipeline AND version match). It still shows the run
          // in the identity strip, with the way back to the run's version.
          var live = window.__peLiveExecution;
          var ownRun = !!(live && live.executionId && live.pipelineId && self.pipeline && live.pipelineId === self.pipeline.id);
          if (ownRun && !self.executionId && self.sseHandler && self.sseHandler.reattach) {
            var viewed = self.viewedVersionOrNull();
            if (live.version !== undefined && live.version !== null && live.version !== viewed) {
              // Another version than the run's: no stream attach, no graph paint — but
              // the strip stays truthful about the run that is out there.
              self.adoptLiveRunRecord(live);
            } else if (self.sseHandler.reattach.length >= 2) {
              self.sseHandler.reattach(live.executionId, live.version);
            } else {
              self.sseHandler.reattach(live.executionId);
            }
          }
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
        // #358: disposal detaches the stream AND disarms its timers/polls without
        // sending a cancellation — the run continues server-side (it is on
        // /executions); only our view of it goes away. The bare-abort fallback is
        // the pre-#358 contract, kept for a handler that predates dispose().
        if (self.sseHandler) {
          if (self.sseHandler.dispose) {
            self.sseHandler.dispose();
          } else if (self.sseHandler.abortController) {
            self.sseHandler.abortController.abort();
          }
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
        if (self.graph && self.graph.stopStageWatch) self.graph.stopStageWatch();
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
      selectNodeById: function (id, moveFocus) {
        var self = this;
        if (!self.selectOnly(id, moveFocus)) return;
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
        // 082 §B: both routes into this are triggers that ALREADY hold the focus —
        // the card's expand button under the pointer, and the node row a keyboard
        // user pressed Enter on. Moving focus onto the list would either reveal the
        // picker over a mouse user's canvas or take the ring off the row that opened
        // the pane; the roving tabindex still follows the selection either way.
        this.selectNodeById(id, false);
      },

      /* ==================================================== #349 — the workspace
       * composition: six tabs, in-page version switching, run identity, and the
       * generation/sink guards every in-page read is stamped with. The spec's state
       * model (§4.3): page/view state key = workspace + pipeline + viewed version +
       * generation; execution state key = workspace + execution id, kept alive while
       * the viewed version moves under it. */

      /** The viewed version as a number, or null (the choose-a-version page has none). */
      viewedVersionOrNull: function () {
        var self = this;
        var v = typeof self.viewedVersion === "number" ? self.viewedVersion : window.PEWorkspace && window.PEWorkspace.viewedVersion;
        return typeof v === "number" && isFinite(v) ? v : null;
      },

      /** The next generation counter — bumped once per issued in-page read. */
      nextToken: function () {
        this.viewGeneration = this.viewGeneration + 1;
        return this.viewGeneration;
      },

      /**
       * The composition facts, re-read from the page's ONE workspace block at init — a
       * first arrival AND a cached history restore. The component fields are the working
       * copy; an in-page version switch re-states only the block's pin fields and the
       * rows' viewed marks (#402: a snapshot taken after a switch restores with its
       * selector, Overview facts and dialect map intact).
       */
      readComposition: function () {
        var self = this;
        var root = typeof document !== "undefined" && document.querySelector ? document.querySelector(".pe-root") : null;
        var canReadExecutions = !!(root && root.getAttribute && root.getAttribute("data-can-read-executions") === "true");
        var initial = root && root.getAttribute ? root.getAttribute("data-active-tab") : "flow";
        self.pageHasDraft = !!(root && root.getAttribute && root.getAttribute("data-has-draft") === "true");
        if (self.tabs) {
          self.tabs.canReadExecutions = canReadExecutions;
          self.tabs.active = window.PETabs ? window.PETabs.resolve(initial, canReadExecutions) : "flow";
        }
        var pin = window.PEWorkspace;
        self.viewedVersion = pin && typeof pin.viewedVersion === "number" ? pin.viewedVersion : null;
        // The composition facts ride the page's ONE workspace block: the admitted
        // history, the Overview's record-level facts and the datasource dialect map.
        // Read raw here (the pin itself is workspace.js's job); absent or unparsable
        // leaves the defaults — empty rows, no facts — never a throw.
        var el = typeof document !== "undefined" && document.getElementById ? document.getElementById("pipeline-workspace") : null;
        if (el) {
          try {
            var raw = JSON.parse(el.textContent);
            if (raw && typeof raw === "object" && !Array.isArray(raw)) {
              if (Array.isArray(raw.versionRows)) self.versionRows = raw.versionRows;
              if (raw.pageFacts && typeof raw.pageFacts === "object") self.pageFacts = raw.pageFacts;
              if (raw.datasourceDialects && typeof raw.datasourceDialects === "object") {
                self.datasourceDialects = raw.datasourceDialects;
              }
            }
          } catch (e) {
            /* the pin's own refusal path (workspace.js) already recorded it */
          }
        }
      },

      /**
       * A live-run record adopted WITHOUT attaching the stream (#349): the page was
       * restored at a version other than the run's. The strip names the run and offers
       * the way back; nothing paints.
       */
      adoptLiveRunRecord: function (live) {
        this.runIdentity = {
          executionId: live.executionId,
          version: live.version,
          status: "running",
          parameters: null,
          attached: false,
        };
        this.executionVersion = live.version;
      },

      /**
       * THE in-page version switch (spec §4.3): fetch the version's body through the
       * admitted REST read, swap graph + metadata + parameter schema + source context
       * as ONE view transition, keep the execution state and stream untouched, and
       * stamp the whole page with a new generation so every stale completion — success
       * or refusal — is dropped. A late v1 response cannot overwrite v2; returning to
       * v1 is a new generation (A→B→A included).
       *
       * #402: a USER's switch pushes a history entry once the body lands (a refused or
       * superseded read pushes nothing); `options.history === false` is a Back/Forward
       * REPLAY of an entry that already holds this URL — it re-applies, never mints.
       */
      applyVersion: function (n, options) {
        var self = this;
        var version = Number(n);
        if (!self.pipeline || !self.pipeline.id) return Promise.resolve(false);
        if (!isFinite(version) || version <= 0) return Promise.resolve(false);
        var generation = self.nextToken();
        var pipelineId = self.pipeline.id;
        self.announceStatus("Loading v" + version + "…");
        return fetch("/api/v1/pipelines/" + encodeURIComponent(pipelineId) + "/versions/" + version, {
          credentials: "same-origin",
          headers: { Accept: "application/json" },
        })
          .then(function (res) {
            if (window.PEWorkspaceLogic.stale({ generation: generation, pipelineId: pipelineId }, self.currentStamp())) {
              return null; // a newer switch superseded this one mid-flight
            }
            if (!res.ok) {
              // The house refusal: absent, foreign, or (under a narrowing lens) not
              // admitted — never a silent fallback to another body.
              self.setBanner(
                "Version v" + version + " is not available for your role or does not exist. Still viewing v" +
                  (window.PEWorkspace && window.PEWorkspace.viewedVersion) + ".",
                "error",
              );
              self.announceStatus("Version v" + version + " is not available");
              return false;
            }
            return res.json().then(function (envelope) {
              var data = (envelope && envelope.data) || envelope;
              if (window.PEWorkspaceLogic.stale({ generation: generation, pipelineId: pipelineId }, self.currentStamp())) {
                return null;
              }
              self.applyBodySnapshot(version, data, options);
              return true;
            });
          })
          .catch(function () {
            if (window.PEWorkspaceLogic.stale({ generation: generation, pipelineId: pipelineId }, self.currentStamp())) return;
            self.setBanner("Version v" + version + " could not be loaded — the network refused. Still viewing the previous version.", "error");
            return false;
          });
      },

      /** The CURRENT page/view stamp, against which every in-flight read is judged. */
      currentStamp: function () {
        return {
          pipelineId: this.pipeline ? this.pipeline.id : undefined,
          version: this.viewedVersionOrNull(),
          generation: this.viewGeneration,
        };
      },

      /**
       * One fetched body becomes the viewed version: graph, metadata, parameter
       * schema, per-version overrides, checks, selector marks, URL. The execution
       * state (stream, run facts, result panel) is deliberately NOT touched — the run
       * keeps streaming while the view moves (spec §4.3); only the GRAPH PAINTING
       * gate changes, because the run's facts attach to their own version.
       */
      applyBodySnapshot: function (version, data, options) {
        var self = this;
        var previousVersion = self.viewedVersionOrNull();
        var previousTab = self.tabs ? self.tabs.active : "flow";
        // The per-version override rule FIRST (spec §4.3 — never reuse another
        // version's fields): the old bag saves under the OLD version, the new one
        // loads from the new version's own store. Only then does the reactive
        // viewed-version move, so the getters re-evaluate against the new body.
        if (self.overrideStore && previousVersion !== null) {
          self.overrideStore.save(previousVersion, self.parameterOverrides);
        }
        self.viewedVersion = version;

        self.pipeline = data;
        self.nodes = data.nodes || [];
        var byId = {};
        self.nodes.forEach(function (node) {
          byId[node.id] = node;
        });
        self.nodesById = byId;
        self.parameters = data.parameters || {};
        self.paramKeys = Object.keys(self.parameters);
        self.paramFields = window.PEParamFields ? window.PEParamFields.buildParamFields(self.parameters) : [];
        if (self.overrideStore) {
          // The schema decides which stored keys survive; the rest are dropped (an
          // incompatible field never leaks across a version change, spec §4.3).
          var stored = self.overrideStore.load(version, self.paramKeys);
          self.parameterOverrides = stored;
          self.paramFields.forEach(function (field) {
            field.override = stored[field.key] !== undefined ? stored[field.key] : "";
          });
        }
        // 7d: the transform pins were the OLD body's lookups — the new graph resolves
        // its own; the Details pane reads "resolving…" until they land.
        self.transformPins = {};

        // The workspace pin: the executed and previewed version IS the viewed one.
        // canExecute is the server-stamped role grant (the root attribute) — a new
        // body's presence is what the fetch just proved.
        if (typeof window !== "undefined") {
          window.PEWorkspace = {
            pipelineId: self.pipeline.id,
            viewedVersion: version,
            hasBody: true,
            canExecute: self.canExecute,
          };
        }
        // The page's two script-JSON blocks are kept TRUTHFUL (textContent is a DOM
        // text sink — no escaping question): the body block is the page's ONE body
        // source (init reads it; a later PEWorkspaceRead identity-checks the pair),
        // and the workspace block is the pin every execute and SQL read makes.
        if (typeof document !== "undefined") {
          // #402 security pass: written SCRIPT-SAFE, as the server writes them (ScriptSafeJson).
          // A history snapshot serialises #app-main as innerHTML, where a <script>'s text is
          // emitted RAW — a literal `</script>` in an author's description would close the
          // block on the cache-hit re-parse. `\u003c` is still valid JSON for the same value.
          var dataEl = document.getElementById("pipeline-data");
          if (dataEl) dataEl.textContent = scriptSafeJson(data);
          var wsEl = document.getElementById("pipeline-workspace");
          if (wsEl) wsEl.textContent = scriptSafeJson(self.restatedWorkspaceBlock(wsEl.textContent, version));
        }

        // The graph: rebuilt for the new nodes, its events re-armed, and the RUN's
        // states re-applied only when this IS the run's version.
        if (self.graph && self.graph.stopStageWatch) self.graph.stopStageWatch();
        if (self.cy) {
          self.cy.destroy();
          self.cy = null;
        }
        self.selectedNode = null;
        self.dock.clearSelection();
        self.graph = new PipelineGraph("cy-canvas", self.nodes, self);
        self.graph.render();
        self.cy = self.graph.cy;
        self.wireGraphEventsOn();
        setupA11y(self);
        self.replayRunOntoGraph();
        self.refreshChecks();
        self.syncViewedMarkers(version);
        self.updateViewedUrl(version, previousVersion === null ? null : { version: previousVersion, tab: previousTab }, options);
      },

      /**
       * #402 — the workspace block after an in-page switch: the four pin fields re-stated,
       * the admitted rows' viewed mark moved, and every other composition fact (the rows
       * themselves, the Overview's record-level facts, the dialect map) KEPT — the block is
       * what a cached history restore re-reads, so it must describe the page as it is.
       * An unreadable block is replaced by the pin alone (workspace.js refuses it anyway).
       */
      restatedWorkspaceBlock: function (raw, version) {
        var block = null;
        try {
          block = JSON.parse(raw);
        } catch (e) {
          block = null;
        }
        if (!block || typeof block !== "object" || Array.isArray(block)) block = {};
        var pin = window.PEWorkspace || {};
        block.pipelineId = pin.pipelineId;
        block.viewedVersion = pin.viewedVersion;
        block.hasBody = pin.hasBody;
        block.canExecute = pin.canExecute;
        if (Array.isArray(block.versionRows)) {
          block.versionRows = block.versionRows.map(function (row) {
            var copy = {};
            Object.keys(row || {}).forEach(function (k) {
              copy[k] = row[k];
            });
            copy.viewed = copy.version === version;
            return copy;
          });
        }
        return block;
      },

      /** The run's own states return to the graph when the view returns to its version. */
      replayRunOntoGraph: function () {
        var self = this;
        if (!self.runMatchesViewed()) return;
        if (!self.graph || !self.graph.cy) return;
        Object.keys(self.nodeStates || {}).forEach(function (id) {
          if (self.cy.getElementById(id).length) self.graph.setNodeState(id, self.nodeStates[id]);
        });
        var ops = self.nodeOps;
        if (ops && typeof ops.all === "function" && self.graph.setNodeOperation) {
          Object.keys(ops.all()).forEach(function (id) {
            var op = ops.get(id);
            var view = op && window.PENodeOps ? window.PENodeOps.describe(op) : null;
            self.graph.setNodeOperation(id, view);
          });
        }
      },

      /**
       * The checks read for the VIEWED version: URL re-stamped, request re-issued —
       * and the previous read's response cancelled by the sink token when it lands
       * after this one.
       */
      refreshChecks: function () {
        var self = this;
        if (typeof document === "undefined" || !window.htmx) return;
        var target = document.getElementById("pe-checks-latest");
        if (!target) return;
        if (!Array.isArray(self.pipeline.checks) || self.pipeline.checks.length === 0) {
          target.innerHTML = "";
          return;
        }
        var url =
          "/partials/pipelines/" + encodeURIComponent(self.pipeline.id) +
          "/versions/" + encodeURIComponent(self.viewedVersionOrNull()) + "/checks";
        target.setAttribute("data-checks-url", url);
        self.checksToken = "t" + self.nextToken();
        target.setAttribute("data-pe-token", self.checksToken);
        target.innerHTML = '<p class="u-secondary u-text-sm">Loading checks…</p>';
        window.htmx.ajax("GET", url, {
          source: document.getElementById("pe-sql-requester") || undefined,
          target: "#pe-checks-latest",
          swap: "innerHTML",
          headers: { "DP-PE-Sink-Token": self.checksToken },
        });
      },

      /**
       * #349 — Run checks commissions a fresh `ui` run of the VIEWED version's checks
       * into the same container (the explorer Overview's own POST). Role-gated
       * server-side; the client gates on the SAME server-stamped canExecute the
       * Execute button renders, refuses without a valid version pin, and stamps the
       * sink token so a version switch supersedes the run's fragment (stale verdicts
       * never paint). CSRF rides the body's hx-headers, the pair every htmx POST on
       * this app carries.
       */
      runChecks: function () {
        var self = this;
        if (!self.canExecute || typeof window === "undefined" || !window.htmx) return;
        var target = document.getElementById("pe-checks-latest");
        if (!target) return;
        var pin = window.PEWorkspaceLogic ? window.PEWorkspaceLogic.executeVersion(window.PEWorkspace) : null;
        if (pin == null) {
          self.announceStatus("The page could not read the pipeline's version state, so it cannot run this version's checks.");
          return;
        }
        var url =
          "/partials/pipelines/" + encodeURIComponent(self.pipeline.id) +
          "/versions/" + encodeURIComponent(pin) + "/checks/run";
        self.checksToken = "t" + self.nextToken();
        target.setAttribute("data-pe-token", self.checksToken);
        window.htmx.ajax("POST", url, {
          source: document.getElementById("pe-sql-requester") || undefined,
          target: "#pe-checks-latest",
          swap: "innerHTML",
          headers: { "DP-PE-Sink-Token": self.checksToken },
        });
      },

      /** The selector rows, the viewed marks and every [data-pe-viewed-label] chip. */
      syncViewedMarkers: function (version) {
        var self = this;
        self.versionRows = (self.versionRows || []).map(function (row) {
          var copy = {};
          Object.keys(row).forEach(function (k) {
            copy[k] = row[k];
          });
          copy.viewed = row.version === version;
          return copy;
        });
        var row = self.viewedRow();
        var label = "v" + version + (row && row.status ? " · " + String(row.status).toLowerCase() : "") +
          (row && row.current ? " · current" : "");
        if (typeof document !== "undefined" && document.querySelectorAll) {
          var chips = document.querySelectorAll("[data-pe-viewed-label]");
          for (var i = 0; i < chips.length; i++) chips[i].textContent = label;
          // The Versions tab's rows are server-rendered with the ARRIVAL version's mark;
          // an in-page switch re-marks them here (the mark is a plain element the client
          // owns — no re-render of the fragment, whose verbs stay server truth).
          var rows = document.querySelectorAll("#pe-pane-versions tr[data-version-row]");
          for (var j = 0; j < rows.length; j++) {
            var tr = rows[j];
            var trVersion = parseInt(tr.getAttribute("data-version-row"), 10);
            var mark = tr.querySelector("[data-pe-viewed-mark]");
            if (trVersion === version) {
              if (!mark) {
                mark = document.createElement("span");
                mark.className = "ds-badge ds-badge-default";
                mark.setAttribute("data-pe-viewed-mark", "");
                mark.textContent = "viewing";
                var cell = tr.querySelector("td");
                if (cell) cell.appendChild(mark);
              }
            } else if (mark) {
              mark.remove();
            }
          }
        }
      },

      /**
       * The canonical URL follows the viewed version and tab (#402): a USER's switch from
       * [from] pushes an entry of the workspace's own beside the shell's htmx entries
       * (workspace/history.js — `{dpWorkspace: {family, version, tab}}`, never a parameter
       * value), so Back/Forward return to the previous version or tab IN PAGE; an unchanged
       * view only re-states the current entry. A replay (`options.history === false`) writes
       * nothing — the entry already holds this URL — and only keeps htmx's path record on it.
       */
      updateViewedUrl: function (version, from, options) {
        var History = typeof window !== "undefined" ? window.WorkspaceHistory : null;
        if (options && options.history === false) {
          if (History) History.syncHtmxPath();
          return;
        }
        var tab = this.tabs ? this.tabs.active : "flow";
        if (History) {
          History.push("pipelines", from, { version: version, tab: tab }, { defaultTab: "flow" });
          return;
        }
        // No helper on the page (a harness without it): the pre-#402 replace, never a push.
        if (typeof history === "undefined" || !history.replaceState) return;
        var params = new URLSearchParams(window.location.search);
        params.set("version", String(version));
        if (tab && tab !== "flow") params.set("tab", tab);
        else params.delete("tab");
        var qs = params.toString();
        history.replaceState(history.state, "", window.location.pathname + (qs ? "?" + qs : ""));
      },

      /** Lazy tab reads: Runs and Usage load once, on the tab's first open. */
      ensureTabLoaded: function (tab) {
        var self = this;
        if (tab !== "runs" && tab !== "usage") return;
        var loading = tab === "runs" ? self.runsLoading : self.usageLoading;
        var loaded = tab === "runs" ? self.runsLoaded : self.usageLoaded;
        if (loaded || loading) return;
        if (!self.pipeline || !self.pipeline.id || !window.htmx) return;
        var container = document.getElementById(tab === "runs" ? "pe-runs-body" : "pe-usage-body");
        if (!container) return;
        var url = "/partials/pipelines/" + encodeURIComponent(self.pipeline.id) + "/" + tab;
        var token = "t" + self.nextToken();
        if (tab === "runs") {
          self.runsToken = token;
          self.runsLoading = true;
          self.runsLoadFailed = false;
        } else {
          self.usageToken = token;
          self.usageLoading = true;
          self.usageLoadFailed = false;
        }
        container.setAttribute("data-pe-token", token);
        window.htmx.ajax("GET", url, {
          source: document.getElementById("pe-sql-requester") || undefined,
          target: container,
          swap: "innerHTML",
          headers: { "DP-PE-Sink-Token": token },
        })
          .then(function () {
            if (tab === "runs") {
              self.runsLoading = false;
              self.runsLoaded = true;
            } else {
              self.usageLoading = false;
              self.usageLoaded = true;
            }
          })
          .catch(function () {
            if (tab === "runs") {
              self.runsLoading = false;
              self.runsLoadFailed = true;
            } else {
              self.usageLoading = false;
              self.usageLoadFailed = true;
            }
          });
      },

      /* --- the tab and run-strip actions the CSP build can spell (no-arg) --------- */

      selectFlowTab: function () {
        this.switchTab("flow");
      },
      selectOverviewTab: function () {
        this.switchTab("overview");
      },
      selectParametersTab: function () {
        this.switchTab("parameters");
      },
      selectRunsTab: function () {
        this.switchTab("runs");
      },
      selectUsageTab: function () {
        this.switchTab("usage");
      },
      selectVersionsTab: function () {
        this.switchTab("versions");
      },
      openRunParameters: function () {
        // "Run overrides ... reachable here and beside Execute" (§4.1): tab navigation
        // only — no run state is touched.
        this.switchTab("parameters");
      },
      switchTab: function (tab, options) {
        if (!this.tabs) return;
        var previous = this.tabs.active;
        var next = this.tabs.select(tab);
        // #402: the root's tab attribute is what a cached history restore re-reads
        // (readComposition) — kept on the ACTIVE tab, the resolved name only.
        var root = typeof document !== "undefined" && document.querySelector ? document.querySelector(".pe-root") : null;
        if (root && root.setAttribute) root.setAttribute("data-active-tab", next);
        var viewed = this.viewedVersionOrNull();
        if (viewed !== null) this.updateViewedUrl(viewed, { version: viewed, tab: previous }, options);
        this.ensureTabLoaded(next);
      },

      /**
       * #402 — a Back/Forward REPLAY of one of the workspace's own entries: the tab through
       * the tab machine's admission (unknown/unadmitted → Flow), the version through
       * applyVersion's numeric check and its lensed read (a refused version is the house
       * banner, never a body). Nothing is pushed. A version equal to the viewed one still
       * takes a new generation, so a switch still in flight cannot land over the entry the
       * user went back to. The stream and the run state are never touched (spec §4.3).
       */
      replayHistoryEntry: function (version, tab) {
        var replay = { history: false };
        this.switchTab(typeof tab === "string" ? tab : "flow", replay);
        var v = Number(version);
        if (version === null || !isFinite(v) || v <= 0) return;
        if (v === this.viewedVersionOrNull()) {
          this.nextToken();
          return;
        }
        this.applyVersion(v, replay);
      },
      viewRunVersion: function () {
        var v = this.executionVersion;
        if (v === null || v === undefined) return;
        this.applyVersion(v);
      },

      /**
       * The execution identity (spec §4.2): armed by the run's own execution_started,
       * which carries the submitted version and the effective parameters. sse.js calls
       * this BEFORE painting anything, so the strip is armed first.
       */
      handleExecutionIdentity: function (payload, handlerVersion) {
        this.runIdentity = {
          executionId: payload && payload.execution_id ? payload.execution_id : null,
          version: payload && payload.pipeline_version !== undefined && payload.pipeline_version !== null
            ? payload.pipeline_version
            : handlerVersion,
          status: "running",
          parameters: payload && payload.parameters ? payload.parameters : null,
          attached: true,
        };
        this.executionVersion = this.runIdentity.version;
      },
      handleRunTerminal: function (status) {
        if (this.runIdentity) this.runIdentity.status = status;
      },
      /** True when the live run's facts may paint THIS view (pipeline + version). */
      runMatchesViewed: function () {
        var self = this;
        if (self.executionVersion === null || self.executionVersion === undefined) return false;
        var viewed = self.viewedVersionOrNull();
        if (viewed === null) return false;
        var pinned = window.PEWorkspaceLogic ? window.PEWorkspaceLogic.executeVersion(window.PEWorkspace) : null;
        return pinned === self.executionVersion;
      },

      /**
       * The canvas handlers, armed per graph INSTANCE (080 §B's tap contract): a tap
       * selects and fills the dock's Node Details; the background clears; an arrow
       * announces what it means. Called at init and after every in-page version
       * switch — the rebuilt Cytoscape instance carries no handlers of its own.
       * 082 §B: a POINTER tap must not move DOM focus into the keyboard node list.
       */
      wireGraphEventsOn: function () {
        var self = this;
        if (!self.cy) return;
        self.cy.on("tap", "node", function (evt) {
          var nodeData = evt.target.data();
          self.selectNodeById(nodeData.id, false);
        });
        self.cy.on("tap", function (evt) {
          if (evt.target === self.cy) {
            self.selectedNode = null;
            self.dock.clearSelection();
          }
        });
        // 151 (#127): a tap on an ARROW says what it means — in the live region, and
        // by selecting the node that waits (its Details carry the Depends on row).
        // Boundary connectors have no authored target to select; they announce alone.
        self.cy.on("tap", "edge", function (evt) {
          var e = evt.target;
          var kind = e.data("kind");
          if (kind === "boundary") {
            var side = e.data("side") === "end" ? "end" : "start";
            announceStatus(
              side === "start"
                ? e.target().id() + " can start as soon as the execution starts — a boundary, not a transfer"
                : "the execution ends after " + e.source().id() + " — a boundary, not a transfer",
            );
            return;
          }
          announceStatus(self.edgeDescription(e.source().id(), e.target().id()));
          self.selectNodeById(e.target().id(), false);
        });
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
        // #348-b: the panel shows the SQL of the body the page is VIEWING — the version
        // travels with every request the workspace makes (workspace spec §3.3). A page that
        // cannot produce a VALID pin refuses VISIBLY and sends NOTHING: the workspace never
        // asks for the legacy working-version default (that contract stays with the callers
        // that omit the parameter — agents, old links — not with the page that displays a
        // body it cannot name).
        var versionPin = window.PEWorkspaceLogic ? window.PEWorkspaceLogic.executeVersion(window.PEWorkspace) : null;
        if (versionPin == null) {
          // The refusal needs the same x-if render tick as the successful request.
          self.$nextTick(function () {
            var pane = document.getElementById("pe-node-sql");
            if (pane) {
              pane.textContent =
                "The page could not read the pipeline's version state, so it cannot show this node's SQL for the version you are viewing. Reload the page; if it persists, re-open the pipeline.";
            }
          });
          return;
        }
        var url =
          "/partials/pipelines/" + encodeURIComponent(self.pipeline.id) +
          "/nodes/" + encodeURIComponent(self.selectedNode.id) + "/sql" +
          "?parameters=" + encodeURIComponent(JSON.stringify(wire)) +
          "&version=" + encodeURIComponent(versionPin);
        // #349 — the sink token (A8): the request stamps #pe-node-sql with the token
        // it was issued under and the component records the same; a stale response —
        // held while the user selected another node, or switched the viewed version —
        // is cancelled at beforeSwap and never paints, its failure included.
        self.sqlToken = "t" + self.nextToken();
        // #pe-node-sql lives inside <template x-if="selectedNode">, which Alpine
        // renders on the NEXT tick — issuing htmx.ajax synchronously off a
        // selection change hits htmx:targetError and the section never loads.
        self.$nextTick(function () {
          var pane = document.getElementById("pe-node-sql");
          if (!pane) return;
          pane.setAttribute("data-pe-token", self.sqlToken);
          htmx.ajax("GET", url, {
            // The reads issue from the ONE hx-sync="abort" requester: selecting B while
            // A's SQL is still loading ABORTS A's request (htmx's default would DROP
            // B's outright while A is in flight — the pane then never renders B).
            source: document.getElementById("pe-sql-requester") || undefined,
            target: "#pe-node-sql",
            swap: "innerHTML",
            indicator: "#pe-node-sql-spinner",
            headers: { "DP-PE-Sink-Token": self.sqlToken },
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
      // 188: the pane's `data-type` (app.css maps it to --type/--type-bg); it used to
      // return a style STRING, which Alpine applied as a style attribute the CSP refuses.
      detailsTypeToken: function (node) {
        return window.PEGraphUtil ? window.PEGraphUtil.typeToken(node && node.type) : "dql";
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
       * writes / value; a PIPELINE gets child / parameters / output / execution; a
       * TRANSFORM (7d) gets template / language / mode / inputs / output / rejects / strict.
       * Rows are {k, v} objects (195: the CSP build's :key and cell bindings are
       * paths — row[0]/row[1] bracket reads are not spellable).
       */
      detailsMeta: function (node) {
        if (!node) return [];
        var self = this;
        var rows = [];
        var push = function (k, v) { rows.push({ k: k, v: v }); };
        var type = String(node.type || "").toUpperCase();
        if (type === "CALCULATOR") {
          push("Kind", node.kind || "—");
          var inputs = self.calculatorInputs(node);
          push(
            "Inputs",
            inputs.length
              ? inputs.map(function (i) {
                  return i.name + " = " + i.expression + (i.resolved !== null ? " → " + i.resolved : "");
                }).join(" · ")
              : "—"
          );
          // 121: a multi-output node lists the MAPPING — each output → its key —
          // where a single node lists the one key it writes.
          push("Writes", self.calculatorWrites(node));
          var value = self.calculatorValue(node);
          push("Value", value !== null ? value : "—");
        } else if (type === "PIPELINE") {
          var child = node.pipeline || {};
          push("Child", (child.name || "—") + (child.version ? " @ v" + child.version : ""));
          var params = node.parameters ? Object.keys(node.parameters) : [];
          push(
            "Parameters",
            params.length
              ? params.map(function (k) { return k + " ← " + node.parameters[k]; }).join(" · ")
              : "—"
          );
          push("Output", self.outputText(node));
          // #349 (spec §4.2): the spawned child is a RUN fact — attached only on the
          // run's own version.
          var childExec = self.runMatchesViewed() ? self.childExecutions[node.id] : null;
          push("Execution", childExec ? childExec + " (child)" : "—");
        } else if (type === "TRANSFORM") {
          self.transformRows(node).forEach(function (row) { rows.push({ k: row[0], v: row[1] }); });
        } else {
          var source = node.source || "tempdb";
          if (node.source === "tempdb") {
            var engine =
              self.pipeline.settings && self.pipeline.settings.tempdb && self.pipeline.settings.tempdb.engine
                ? self.pipeline.settings.tempdb.engine
                : "H2";
            source = "tempdb (" + engine + ", per-execution)";
          }
          push("Source", source);
          push("Template", self.templateRefText(node));
          push("Output", self.outputText(node));
          push("Parameters", self.paramKeys.length ? self.paramKeys.join(", ") : "—");
        }
        // 108 §A — the node's own wall-clock deadline (pipeline-contract §4.11). Shown for EVERY
        // node type, including the two above, because the deadline applies to all of them and a
        // reader who cannot see it on a CALCULATOR would reasonably conclude it does not.
        // "Default" names where the number comes from when the node declares none: an author
        // debugging a `pipeline.node.timeout` needs to know whether THIS node set the budget.
        push("Timeout", self.nodeTimeoutText(node));
        // 156, #2 — the SQL statement budget, distinct from the wall-clock deadline above:
        // that one bounds the whole node (render → materialize); this one bounds one
        // `execute*` call and is the DRIVER's, not the executor's.
        var queryTimeout = self.nodeQueryTimeoutText(node);
        if (queryTimeout) push("Query Timeout", queryTimeout);
        // 151 (#127): the node's ORDERINGS in words — what it waits for (each with its
        // state) and what waits for it. These are the arrows, read without the canvas;
        // no count belongs on either row, because an arrow is never a transfer.
        var waits = self.dependencyRows(node);
        if (waits.dependsOn) push("Depends on", waits.dependsOn);
        if (waits.requiredBy) push("Required by", waits.requiredBy);
        // #349 (spec §4.2): the node's RUN facts attach only when the run's pipeline
        // AND version match the viewed body — a v1 run's state never reads as the v2
        // node's last run, however the node ids happen to coincide.
        if (self.runMatchesViewed()) {
          var state = self.nodeStates[node.id];
          if (state && state !== "idle") push("Last run", state);
          // 149: the measured operation — what the node is doing (or did), where its output
          // went, the cumulative counts, the per-state time share and whether the write
          // committed. Only what was observed: an operation without a terminal sample says
          // "Commit not observed", never "Committed"; there is no percentage to show.
          self.operationRows(node.id).forEach(function (row) {
            rows.push({ k: row[0], v: row[1] });
          });
        }
        return rows;
      },

      /** 7d: the resolved pin of a TRANSFORM node, or null while (or if) the lookup has not landed. */
      transformPinFor: function (node) {
        var util = window.PEGraphUtil;
        var key = util ? util.pinKey(node && node.template) : null;
        return key && this.transformPins ? this.transformPins[key] || null : null;
      },

      /**
       * 7d (#7, transform-nodes design §9.3) — a TRANSFORM's Details rows: the pin, the
       * language and mode read off the resolved template (the node carries neither — the mode
       * is the contract's, never the node's, §3.1), the inputs map as bound, the output, the
       * reject handling, and the needs-review marker when the pin carries it.
       */
      transformRows: function (node) {
        var pin = this.transformPinFor(node);
        var pending = node && node.template && node.template.id ? "resolving…" : "—";
        var inputs = node.inputs ? Object.keys(node.inputs) : [];
        var util = window.PEGraphUtil;
        var rows = [
          ["Template", this.templateRefText(node)],
          ["Language", pin && pin.language ? pin.language : pending],
          ["Mode", pin && pin.mode ? pin.mode : pending],
          [
            "Inputs",
            inputs.length
              ? inputs.map(function (k) { return k + " ← " + node.inputs[k]; }).join(" · ")
              : "—",
          ],
          ["Output", util ? util.transformOutputText(node) : "—"],
          ["Rejects", node.output && node.output.rejects ? "tempdb." + node.output.rejects : "—"],
          ["Strict", node.strict === true ? "yes — any reject fails the node" : "no"],
        ];
        if (pin && pin.needsReview) {
          rows.push(["Needs review", "a fact this template version cites was retired — re-verify it"]);
        }
        return rows;
      },

      /** 151/#144: the root's `data-can-execute`, and only an explicit "true" grants the trigger. */
      canExecuteFrom: function (root) {
        return !!(root && typeof root.getAttribute === "function" && root.getAttribute("data-can-execute") === "true");
      },

      /**
       * 151: the state word for a dependency's SOURCE, in the footer's vocabulary — an
       * ordering is met when its source is done, and can never be met once it failed.
       */
      dependencyStateWord: function (nodeId) {
        // #349 (spec §4.2): the states are RUN facts — attach only when the run's
        // version is the viewed one; otherwise every ordering reads as its honest
        // "pending", never another run's state.
        if (!this.runMatchesViewed()) return "pending";
        var WORDS = { idle: "pending", running: "running", success: "done", failed: "failed", aborted: "aborted" };
        return WORDS[this.nodeStates[nodeId] || "idle"] || "pending";
      },

      /** The Details pane's link rows (151): `Depends on` with states, `Required by` plain. */
      dependencyRows: function (node) {
        var self = this;
        var deps = node.depends_on || [];
        var dependsOn = deps.length
          ? deps.map(function (id) { return id + " (" + self.dependencyStateWord(id) + ")"; }).join(" · ")
          : null;
        var dependents = (self.nodes || []).filter(function (n) {
          return (n.depends_on || []).indexOf(node.id) !== -1;
        }).map(function (n) { return n.id; });
        return { dependsOn: dependsOn, requiredBy: dependents.length ? dependents.join(" · ") : null };
      },

      /**
       * 151: what a tapped arrow MEANS, for the live region — the same sentence whatever
       * the source's type (a DDL ordering reads exactly like a staged-table one, because
       * it is the same thing: the target waits for the source to finish).
       */
      edgeDescription: function (sourceId, targetId) {
        var state = this.nodeStates[sourceId] || "idle";
        var tail;
        if (state === "failed" || state === "aborted") {
          tail = sourceId + (state === "failed" ? " failed" : " was aborted") + ", so " + targetId + " cannot start";
        } else {
          tail = sourceId + " is " + this.dependencyStateWord(sourceId);
        }
        return targetId + " depends on " + sourceId + " — ordering only; " + tail;
      },

      /** The Details pane's operation rows for one node (149), from node-ops.js. */
      operationRows: function (nodeId) {
        var self = this;
        var op = self.nodeOps && self.nodeOps.get ? self.nodeOps.get(nodeId) : null;
        if (!op || !window.PENodeOps || op.state === "started") return [];
        var d = window.PENodeOps.describe(op);
        var rows = [];
        rows.push(["Operation", (op.kind || "—") + (d.destinationText ? " → " + d.destinationText : "")]);
        rows.push(["Progress", d.stateLabel + (d.elapsedText ? " · " + d.elapsedText : "")]);
        if (d.countsText) rows.push(["Rows", d.countsText]);
        if (d.phaseText) rows.push(["Time in", d.phaseText]);
        if (d.commitText) rows.push(["Commit", d.commitText]);
        if (op.childExecutionId) rows.push(["Child execution", op.childExecutionId]);
        return rows;
      },

      /**
       * The Timeout row's value: the node's own `settings.timeout_seconds` when it declared one,
       * otherwise the operator default named as a default.
       *
       * A PIPELINE node that declares none is exempt from the node deadline entirely — its work is
       * a child execution bounded one level down — so it says so rather than quoting a number that
       * will never apply to it.
       */
      nodeTimeoutText: function (node) {
        var own = node && node.settings ? node.settings.timeout_seconds : null;
        if (own) return own + "s (this node)";
        if (String(node.type || "").toUpperCase() === "PIPELINE") return "child execution's own deadline";
        return "default (datapipelines.executor.node-timeout-seconds)";
      },

      /**
       * The Query Timeout row's value (156, #2, pipeline-contract §4.11/§5.3): the SQL
       * STATEMENT budget, in author-precedence order — this node's own
       * `settings.query_timeout_seconds`, else the pipeline's, else "operator default" naming
       * every remaining tier generically.
       *
       * Absent (returns null, no row) for PIPELINE and CALCULATOR nodes: neither runs a
       * statement, so the setting cannot apply to them (pipeline-contract §12.8 refuses it
       * there for the same reason).
       *
       * Deliberately does NOT resolve or display the datasource's own `query_timeout_seconds`,
       * the operator's per-dialect default, or the flat application default AS A NUMBER: none
       * of those are in the pipeline JSON this pane already has, and fetching them would need a
       * new endpoint exposing live server config to the browser — filed as a follow-up rather
       * than guessed at here. "Operator default" names the remaining tiers generically, the same
       * pattern [nodeTimeoutText] already uses for the operator's flat default.
       */
      nodeQueryTimeoutText: function (node) {
        if (!node) return null;
        var type = String(node.type || "").toUpperCase();
        // 7d: only a statement node runs a statement (SettingsRules' STATEMENT_NODE_TYPES) — a
        // TRANSFORM reads staged rows through the executor, never a template's SQL.
        if (type !== "DQL" && type !== "DML" && type !== "DDL") return null;
        var own = node.settings ? node.settings.query_timeout_seconds : null;
        if (own) return own + "s (this node)";
        var pipelineDefault =
          this.pipeline && this.pipeline.settings ? this.pipeline.settings.query_timeout_seconds : null;
        if (pipelineDefault) return pipelineDefault + "s (pipeline default)";
        return "operator default (datasource, per-dialect, or datapipelines.executor.node-query-timeout-seconds)";
      },

      /** The sql-head's label: what the right-hand side of the pane is showing. */
      detailsSqlHead: function (node) {
        if (!node) return "";
        var type = String(node.type || "").toUpperCase();
        if (type === "CALCULATOR") return "Evaluation (inputs as resolved by the last run)";
        if (type === "PIPELINE") return "Child mapping (parameters passed down, output back)";
        if (type === "TRANSFORM") return "Definition (the pinned function, its inputs and output)";
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
          // 121: the multi shape writes the mapping the Details pane's Writes row lists;
          // calculatorValue already renders each pair's value, so it needs no re-encoding.
          var writes = node.context_keys
            ? "{" + Object.keys(node.context_keys).sort().map(function (o) { return esc(o) + " → " + esc(node.context_keys[o]); }).join(", ") + "}"
            : esc(node.context_key || "?");
          var rendered = value !== null ? (node.context_keys ? " = " + param(value) : " = " + param(JSON.stringify(value))) : "";
          lines.push(") → " + writes + rendered);
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
        if (type === "TRANSFORM") {
          // 7d: the node as a function call — escaped by construction, like the two above.
          var pin = self.transformPinFor(node);
          var t = node.template || {};
          var tfLines = [comment("-- TRANSFORM nodes have no SQL; the pinned template runs as a pure function.")];
          var about = pin ? " " + comment("-- " + [pin.language, pin.mode ? pin.mode + " mode" : null].filter(Boolean).join(", ")) : "";
          tfLines.push("template: " + esc(t.id || "?") + (t.version ? " @ v" + esc(t.version) : "") + about);
          tfLines.push("inputs:");
          var names = node.inputs ? Object.keys(node.inputs) : [];
          if (names.length) {
            names.forEach(function (k) {
              tfLines.push("  " + esc(k) + " ← " + param(String(node.inputs[k])));
            });
          } else {
            tfLines.push("  " + comment("-- none"));
          }
          var util = window.PEGraphUtil;
          tfLines.push("output: " + esc(util ? util.transformOutputText(node) : "—"));
          if (node.output && node.output.rejects) {
            tfLines.push("rejects: " + esc("tempdb." + node.output.rejects) + (node.strict === true ? "  " + comment("-- strict: any reject fails the node") : ""));
          }
          return tfLines.join("\n");
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
         ~300ms debounce the list screens use for search.
         195: @input hands the handler the event (the CSP build passes it as the
         first argument); the field is resolved from the input's data-key, and the
         value lands in parameterOverrides[key] AND the field's own `override`. */
      onParameterInput: function (evt) {
        var self = this;
        var target = evt && evt.target ? evt.target : null;
        var key = target && target.getAttribute ? target.getAttribute("data-key") : null;
        if (key && window.PEParamFields) {
          window.PEParamFields.applyParameterInput(self, key, target ? target.value : "");
        }
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
        if (node.type === "CALCULATOR") {
          if (node.context_keys) return "context keys " + Object.keys(node.context_keys).sort().map(function (o) { return node.context_keys[o]; }).join(", ");
          return "context key " + (node.context_key || "—");
        }
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
          // #349 (spec §4.2): the resolved Context is the RUN's — shown beside the
          // expression only when the run's version is the viewed one.
          if (self.runMatchesViewed() && typeof raw === "string" && raw.charAt(0) === "$") {
            var key = raw.slice(1);
            if (Object.prototype.hasOwnProperty.call(self.contextValues, key)) {
              resolved = String(self.contextValues[key]);
            }
          }
          return { name: name, expression: expression, resolved: resolved };
        });
      },

      /** What a CALCULATOR node writes — one key, or the whole output→key mapping (121). */
      calculatorWrites: function (node) {
        if (!node || node.type !== "CALCULATOR") return "—";
        if (node.context_keys) {
          // Sorted — body_json is JSONB, which does not preserve the author's key order.
          return Object.keys(node.context_keys).sort().map(function (o) {
            return o + " → " + node.context_keys[o];
          }).join(" · ");
        }
        return node.context_key || "—";
      },

      /** The value a CALCULATOR node computed in the last run, or null before one.
       * #349: run-owned — attached only when the run's version is the viewed one. */
      calculatorValue: function (node) {
        if (!node || node.type !== "CALCULATOR") return null;
        if (!this.runMatchesViewed()) return null;
        var value = this.nodeValues[node.id];
        if (value === undefined || value === null) return null;
        // 121: a multi-output node's recorded value is the whole key set it wrote.
        if (typeof value === "object") {
          return Object.keys(value).map(function (k) {
            return k + " = " + JSON.stringify(value[k]);
          }).join(" · ");
        }
        return String(value);
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
        var LABELS = { DQL: "DQL", DML: "DML", DDL: "DDL", CALCULATOR: "Calculator", PIPELINE: "Pipeline", TRANSFORM: "Transform" };
        (this.nodes || []).forEach(function (n) {
          var type = String(n.type || "").toUpperCase();
          if (!LABELS[type] || seen[type]) return;
          seen[type] = true;
          var tok = window.PEGraphUtil ? window.PEGraphUtil.typeToken(type) : "dql";
          chips.push({ label: LABELS[type], token: "--type-" + tok, type: tok });
        });
        return chips;
      },

      announceStatus: function (msg) {
        announceStatus(msg);
      },

      /* ------------------------------------------------- 195: the CSP getters
       * Alpine's CSP build evaluates every x-* value as a property path: no
       * operators, no literals, no call syntax. Every comparison, fallback and
       * concatenation the template used to inline lives in the getters and
       * no-arg methods below, named for what the template shows. Each reads its
       * inputs through `this` (the merged Alpine scope) so reactivity tracks. */

      /* --- top bar --- */
      get notExecuting() {
        return !this.isExecuting;
      },
      dismissBanner: function () {
        this.banner.text = "";
      },

      /* --- settings sidebar --- */
      get pipelineDescriptionText() {
        return this.pipeline.description || "—";
      },
      get hasCustomStagingEngine() {
        return !!(
          this.pipeline.settings &&
          this.pipeline.settings.tempdb &&
          this.pipeline.settings.tempdb.engine !== "H2"
        );
      },
      get stagingEngineText() {
        return this.pipeline.settings && this.pipeline.settings.tempdb
          ? this.pipeline.settings.tempdb.engine
          : undefined;
      },

      /* --- graph controls (the old expressions guarded with `graph &&`) ---
         The zoom steps are named (301 #305): ZOOM_IN_STEP/ZOOM_OUT_STEP — a ×1.25 in and
         its exact reciprocal out, so in-then-out lands the view where it started and a
         changed step moves both buttons together. */
      fitGraph: function () {
        if (this.graph && this.graph.fitToView) this.graph.fitToView();
      },
      resetGraph: function () {
        if (this.graph && this.graph.resetView) this.graph.resetView();
      },
      zoomIn: function () {
        if (this.graph && this.graph.zoomBy) this.graph.zoomBy(ZOOM_IN_STEP);
      },
      zoomOut: function () {
        if (this.graph && this.graph.zoomBy) this.graph.zoomBy(ZOOM_OUT_STEP);
      },

      /* --- the dock's badges (dock state + result panel, read together) --- */
      get resultsBadgeClass() {
        return this.dock.hasResults && !this.resultPanel.expired ? "pe-dock-count-ok" : "";
      },
      get resultsBadgeText() {
        return this.dock.hasResults && this.dock.resultsRows !== null ? this.dock.resultsRows : "—";
      },
      get errorsBadgeClass() {
        return this.dock.errors.length > 0 ? "pe-dock-count-err" : "";
      },

      /* The dock's and the events log's METHODS are delegated here on purpose:
         the CSP evaluator auto-calls a resolved function with `this` = the merged
         scope, so a nested object's own method (`dock.selectDetails`,
         `eventsLog.count`) would run with the wrong `this` and read undefined.
         Getters are safe (they are invoked during the property read, on their own
         object) — that is why the predicates stay on dock.js and only the
         ACTIONS are delegated. Every delegation calls through with the right
         `this`. */
      selectDetailsTab: function () {
        this.dock.selectDetails();
      },
      selectResultsTab: function () {
        this.dock.selectResults();
      },
      selectErrorsTab: function () {
        this.dock.selectErrors();
      },
      selectEventsTab: function () {
        this.dock.selectEvents();
      },
      toggleDock: function () {
        this.dock.toggleCollapse();
      },
      get eventsCount() {
        return this.eventsLog.count();
      },
      get noEvents() {
        return this.eventsLog.count() === 0;
      },

      /* --- the Details pane (the old expressions took selectedNode as an argument) --- */
      get noSelection() {
        return !this.selectedNode;
      },
      get checksPresent() {
        return ((this.pipeline.checks || []).length) > 0;
      },
      get checksCountText() {
        var n = (this.pipeline.checks || []).length;
        return n + " check" + (n === 1 ? "" : "s") + " on this version";
      },
      get selectedDetailsTypeToken() {
        return this.detailsTypeToken(this.selectedNode);
      },
      get selectedDetailsIcon() {
        return this.detailsIcon(this.selectedNode);
      },
      get selectedTypeText() {
        return String((this.selectedNode && this.selectedNode.type) || "").toLowerCase();
      },
      get selectedNeedsReview() {
        var pin = this.transformPinFor(this.selectedNode);
        return !!(pin && pin.needsReview === true);
      },
      get selectedDetailsMeta() {
        return this.detailsMeta(this.selectedNode);
      },
      get selectedSqlHead() {
        return this.detailsSqlHead(this.selectedNode);
      },
      get hasSelectedTemplate() {
        return !!(this.selectedNode && this.selectedNode.template && this.selectedNode.template.id);
      },
      get selectedTemplateHref() {
        var n = this.selectedNode;
        return "/templates/editor?name=" + encodeURIComponent(n && n.template ? n.template.id : "");
      },
      get hasSelectedChild() {
        return !!(this.selectedNode && this.selectedNode.type === "PIPELINE" && this.selectedNode.pipeline);
      },
      get selectedChildHref() {
        var n = this.selectedNode;
        return "/pipelines?q=" + encodeURIComponent(n && n.pipeline ? n.pipeline.name : "");
      },
      get selectedIsSqlNode() {
        return !!(this.selectedNode && this.isSqlNode(this.selectedNode));
      },
      get selectedIsNotSqlNode() {
        return !!(this.selectedNode && !this.isSqlNode(this.selectedNode));
      },
      get selectedDefinitionHtml() {
        return this.definitionHtml(this.selectedNode);
      },

      /* --- the Results pane --- */
      get resultsEmpty() {
        return !this.resultPanel.data && !this.resultPanel.expired;
      },
      get ttlRunning() {
        return this.resultPanel.ttlSeconds > 0;
      },
      get ttlText() {
        return "Expires in " + this.resultPanel.ttlSeconds + "s";
      },
      get resultsReady() {
        return !this.resultPanel.expired && !!this.resultPanel.data;
      },
      get noPrevPage() {
        return !this.resultPanel.hasPrev;
      },
      get noNextPage() {
        return !this.resultPanel.hasNext;
      },
      get pageInfoText() {
        return "Page " + this.resultPanel.page + " / " + this.resultPanel.totalPages;
      },

      /* --- #349: the workspace composition (selector, overview, parameters, strip) ---
         Everything below is a no-arg getter or method over the composition state: the
         CSP build's template expressions stay pure property paths. */

      /** One admitted-history row, the workspace block's shape. */
      viewedRow: function () {
        var rows = this.versionRows || [];
        for (var i = 0; i < rows.length; i++) {
          if (rows[i].viewed) return rows[i];
        }
        return null;
      },

      /** The header selector's rows: label, suffixes, canonical href, viewed mark. */
      get selectorRows() {
        var self = this;
        var id = self.pipeline && self.pipeline.id ? self.pipeline.id : "";
        var tab = self.tabs && self.tabs.active ? self.tabs.active : "flow";
        return (self.versionRows || []).map(function (row) {
          var suffix = row.current ? "· current" : "";
          if (!suffix && String(row.status).toUpperCase() === "DRAFT") suffix = "· draft";
          var params = "version=" + encodeURIComponent(row.version);
          if (tab && tab !== "flow") params += "&tab=" + encodeURIComponent(tab);
          return {
            version: row.version,
            label: "v" + row.version,
            currentSuffix: suffix,
            titleText:
              String(row.status || "").toLowerCase() + (row.current ? " · current" : ""),
            href: "/pipelines/" + encodeURIComponent(id) + "?" + params,
            ariaCurrent: row.viewed ? "page" : null,
          };
        });
      },

      /** The viewed-version chips: `v2 · draft`, or plain `v2` with no admitted status. */
      get viewedChipText() {
        var row = this.viewedRow();
        var viewed = this.viewedVersionOrNull();
        if (viewed === null) return "—";
        var label = "v" + viewed;
        if (row && row.status) label += " · " + String(row.status).toLowerCase();
        if (row && row.current) label += " · current";
        return label;
      },
      /** "released · current" — the Overview's success chip, only on the current release. */
      get viewedIsReleasedCurrent() {
        var row = this.viewedRow();
        return !!(row && row.current && String(row.status).toUpperCase() === "RELEASED");
      },

      /* --- Overview (client-rendered from the body JSON + the page facts) --------- */

      get overviewDescriptionText() {
        var d = this.pipeline ? this.pipeline.description : null;
        return d ? d : "—";
      },
      get overviewNodeCountText() {
        var n = this.nodes ? this.nodes.length : 0;
        return n + (n === 1 ? " node" : " nodes");
      },
      get overviewDatasourceCountText() {
        var n = this.overviewDatasourceNames().length;
        return n + (n === 1 ? " datasource" : " datasources");
      },
      get overviewNoDatasources() {
        return this.overviewDatasourceNames().length === 0;
      },
      get overviewNoTemplates() {
        return this.overviewTemplatePins.length === 0;
      },
      /** The datasource names THIS body touches (the registry decides what counts). */
      overviewDatasourceNames: function () {
        var self = this;
        var out = [];
        (self.nodes || []).forEach(function (n) {
          var source = n && n.source ? String(n.source) : "";
          var target = n && n.output ? n.output : null;
          if (source && source !== "tempdb" && out.indexOf(source) === -1) out.push(source);
          if (target && target.target === "datasource" && target.datasource && out.indexOf(target.datasource) === -1) {
            out.push(target.datasource);
          }
        });
        return out.sort();
      },
      /** The Overview's datasource rows: name, dialect (the pre-resolved map), href. */
      get overviewDatasources() {
        var self = this;
        var dialects = self.datasourceDialects || {};
        return self.overviewDatasourceNames().map(function (name) {
          var dialect = dialects[name];
          return {
            name: name,
            dialectText: dialect ? " (" + String(dialect).toLowerCase() + ")" : "",
            href: "/datasources/" + encodeURIComponent(name),
          };
        });
      },
      /** The Overview's template pins: `id@version`, linked into the templates screen. */
      get overviewTemplatePins() {
        var self = this;
        var seen = {};
        var rows = [];
        (self.nodes || []).forEach(function (n) {
          var t = n && n.template;
          if (!t || !t.id || seen[t.id + "@" + t.version]) return;
          seen[t.id + "@" + t.version] = true;
          rows.push({
            label: t.id + "@" + t.version,
            href: "/templates?q=" + encodeURIComponent(t.id),
          });
        });
        return rows;
      },
      get overviewCreatedText() {
        var at = this.pipeline && this.pipeline.created_at ? String(this.pipeline.created_at) : "—";
        return at.replace("T", " ").slice(0, 16);
      },
      get overviewCreatedByText() {
        return (this.pageFacts && this.pageFacts.createdBy) || "—";
      },
      get overviewCreatedViaText() {
        var via = this.pageFacts ? this.pageFacts.createdVia : null;
        if (!via || via === "session") return "";
        return via === "api_key" ? " · via API key" : " · via MCP";
      },
      get overviewNoLastRun() {
        return !(this.pageFacts && this.pageFacts.lastRun);
      },
      get overviewHasLastRun() {
        return !this.overviewNoLastRun;
      },
      get overviewLastRunHref() {
        return this.pageFacts && this.pageFacts.lastRun
          ? "/executions/" + this.pageFacts.lastRun.executionId
          : "#";
      },
      get overviewLastRunStatus() {
        return this.pageFacts && this.pageFacts.lastRun ? this.pageFacts.lastRun.status : "";
      },
      get overviewLastRunStatusWord() {
        var s = this.overviewLastRunStatus;
        return s ? s.charAt(0) + s.slice(1).toLowerCase() : "";
      },
      get overviewLastRunChipClass() {
        var s = this.overviewLastRunStatus;
        if (s === "SUCCESS") return "app-chip-ok";
        if (s === "FAILED") return "app-chip-bad";
        if (s === "RUNNING") return "app-chip-run";
        return "app-chip-warn";
      },
      get overviewLastRunDurationText() {
        var run = this.pageFacts && this.pageFacts.lastRun;
        return run && run.durationMs !== null && run.durationMs !== undefined ? run.durationMs + " ms" : "—";
      },
      get overviewLastRunRowsText() {
        var run = this.pageFacts && this.pageFacts.lastRun;
        return run && run.rowCount !== null && run.rowCount !== undefined ? run.rowCount + " rows" : "— rows";
      },
      get overviewLastRunAgoText() {
        return this.pageFacts && this.pageFacts.lastRun ? this.pageFacts.lastRun.ago : "";
      },
      get overviewLastRunAtText() {
        return this.pageFacts && this.pageFacts.lastRun ? this.pageFacts.lastRun.at : "";
      },

      /* --- Parameters tab ------------------------------------------------------- */

      get parametersCountText() {
        var n = this.paramKeys ? this.paramKeys.length : 0;
        return n + (n === 1 ? " declared" : " declared");
      },
      get hasParameters() {
        return this.paramKeys && this.paramKeys.length > 0;
      },
      get noParameters() {
        return !this.hasParameters;
      },
      /** The declaration table's rows, materialized (the CSP paths read fields). */
      get parameterRows() {
        var self = this;
        return (self.paramKeys || []).map(function (key) {
          var p = self.parameters[key] || {};
          return {
            name: key,
            type: p.type || "STRING",
            required: p.required === true,
            defaultText: p.default === null || p.default === undefined ? "—" : JSON.stringify(p.default),
            descriptionText: p.description || "—",
          };
        });
      },

      /* --- the execution identity strip ------------------------------------------ */

      get runStripVisible() {
        return !!(this.runIdentity && this.dock && (this.dock.resultsActive || this.dock.errorsActive || this.dock.eventsActive));
      },
      get runStripVersionText() {
        return this.runIdentity && this.runIdentity.version !== null && this.runIdentity.version !== undefined
          ? "v" + this.runIdentity.version
          : "—";
      },
      get runStripStatusText() {
        return this.runIdentity ? String(this.runIdentity.status) : "";
      },
      get runStripExecText() {
        var id = this.runIdentity && this.runIdentity.executionId ? String(this.runIdentity.executionId) : "";
        return id ? "execution " + id.slice(0, 8) : "";
      },
      get runStripParamsText() {
        // The EFFECTIVE SUBMITTED parameters — the pipeline's own declared keys — never
        // the whole resolved Context (the org/platform tiers ride the same payload).
        var params = this.runIdentity && this.runIdentity.parameters;
        if (!params) return "";
        var keys = (this.paramKeys || []).filter(function (k) {
          return Object.prototype.hasOwnProperty.call(params, k);
        });
        if (keys.length === 0) return "";
        return keys
          .map(function (k) {
            return k + "=" + String(params[k]);
          })
          .join(" · ");
      },
      /** The run ran another version than the one being viewed — say so, offer the way back. */
      get runVersionDiffers() {
        var viewed = this.viewedVersionOrNull();
        return (
          this.executionVersion !== null &&
          this.executionVersion !== undefined &&
          viewed !== null &&
          this.executionVersion !== viewed
        );
      },

      /* --- the error modal --- */
      hideErrorModal: function () {
        this.errorModal.hide();
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
   * per session (this script loads once per document through the runtime catalog;
   * the flag keeps the wiring singular even if that ever changes).
   *
   * htmx:beforeSwap — the outgoing swap replaces the editor's host region:
   * teardown the live component. Boosted swaps qualify outright (shell.js
   * retargets them at #app-main); so does any swap whose target IS #app-main or
   * an ancestor of it, because htmx history restores swap the cached fragment
   * WITHOUT a boosted flag. Partial swaps inside the editor (node SQL, result
   * pages) target inner nodes and never match.
   *
   * 080 §B — THE EXACTLY-ONCE TOAST FIX, superseded by the #358 runtime. The old
   * afterSettle rescue (`destroyTree` + `initTree` on the restored root) was the
   * restore path's initializer when the fragment's scripts replayed on every
   * restore — and it COMPETED with each replayed Alpine's own boot walk, which is
   * how one restore stacked components (two panes, two POSTs per click). Since
   * #358 the fragment's scripts live in the runtime's inert catalog: nothing
   * replays on a cached restore except the guarded runtime, whose `x-ignore`
   * discipline makes its activation the ONLY initializer a restored root can
   * get. This listener therefore owns TEARDOWN only; there is no rescue here to
   * compete with. (editor-teardown.test.mjs asserts that this file wires NO
   * afterSettle initializer — the ownership guard that goes red the moment a
   * rescue is re-added; a rescue planted into the served file could no longer
   * stack the restored root, the runtime's destroy-before-bind absorbs it.)
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
      // #348: the workspace version state dies with the page — the restored root's
      // component re-reads the new document's own block (the runtime re-runs
      // PEWorkspaceRead during its activation, before the component binds).
      window.PEWorkspace = null;
      window.PEWorkspaceInvalid = false;
    });
  }

  /*
   * #349 — the version selector's and the Versions tab's Open links: EVERY
   * `a[data-pe-version-link]` on the page applies its version IN PAGE instead of
   * navigating. The href stays the canonical deep link (middle-click, no-JS, the
   * explorer's full-document entry rule is untouched); a plain click here never
   * reloads the document, because a reload would detach the page from an active run
   * (spec §4.3: "Do not reload the document and lose an active run just to update the
   * dropdown"). Registered ONCE per document, reading the live component through the
   * teardown handle — the same pattern wireEventsScroll uses.
   */
  function wireVersionLinks() {
    if (typeof document === "undefined" || !document.addEventListener || document.__peVersionLinksWired) return;
    document.__peVersionLinksWired = true;
    document.addEventListener("click", function (evt) {
      var target = evt.target;
      var link = target && target.closest ? target.closest("a[data-pe-version-link]") : null;
      if (!link) return;
      var inst = window.__peInstance;
      if (!inst || typeof inst.applyVersion !== "function") return;
      var version = parseInt(link.getAttribute("data-version") || link.getAttribute("data-pe-version"), 10);
      if (!isFinite(version) || version <= 0) return;
      evt.preventDefault();
      inst.applyVersion(version);
    });
  }

  /*
   * #349 — the sink-token guard (A8): the pane reads that arrive as htmx swaps (node
   * SQL, checks, runs, usage) are stamped with the token they were issued under; a
   * response whose token no longer matches the component's current one is CANCELLED
   * before it can paint — its content AND its failure. One document-level listener,
   * registered once; every sink carries its own token pair.
   */
  function wireSinkTokenGuard() {
    if (typeof document === "undefined" || !document.body || !document.body.addEventListener || document.__peSinkGuardWired) return;
    document.__peSinkGuardWired = true;
    var SINKS = {
      "pe-node-sql": "sqlToken",
      "pe-checks-latest": "checksToken",
      "pe-runs-body": "runsToken",
      "pe-usage-body": "usageToken",
    };
    document.body.addEventListener("htmx:beforeSwap", function (evt) {
      var inst = window.__peInstance;
      var target = evt.detail && evt.detail.target;
      var key = target && target.id ? SINKS[target.id] : null;
      if (!key || !inst) return;
      // The token rides the REQUEST (htmx echoes requestConfig back to beforeSwap):
      // a newer request re-stamps the sink's data attribute, so a sink-carried token
      // could never catch the older response still in flight — the request-carried
      // one is the only record of who issued THIS response.
      var config = evt.detail.requestConfig || {};
      var headers = config.headers || {};
      var recorded = headers["DP-PE-Sink-Token"] || target.getAttribute("data-pe-token");
      var current = inst[key];
      if (window.PEWorkspaceLogic && !window.PEWorkspaceLogic.tokenMatches(recorded, current)) {
        // A newer request superseded this one: do not swap, and do not let the
        // cancelled response raise (a stale response is not an error the user owns).
        evt.detail.shouldSwap = false;
        evt.detail.isError = false;
      }
    });
  }

  /** JSON for a `<script type="application/json">` block: every `<` escaped (`\u003c`). */
  function scriptSafeJson(value) {
    return JSON.stringify(value).replace(/</g, "\\u003c");
  }

  /*
   * #402 — Back/Forward over the workspace's OWN entries (workspace/history.js): the ONE
   * window listener dispatches a `pipelines` entry here, and the LIVE component is resolved
   * at event time through the teardown handle — never the one that existed when this ran.
   * The entry is replayed in place only when the live root IS this URL's workspace (the
   * same pipeline, the root in the document); otherwise — the list is showing, another
   * pipeline's workspace is, or a history restore left a destroyed component behind — the
   * answer is false and the helper hands the entry to htmx's own restore.
   */
  function wireWorkspaceHistory() {
    if (typeof window === "undefined" || !window.WorkspaceHistory || typeof document === "undefined") return;
    window.WorkspaceHistory.listen("pipelines", function (version, tab) {
      var inst = window.__peInstance;
      if (!inst || typeof inst.replayHistoryEntry !== "function" || !inst.pipeline || !inst.pipeline.id) return false;
      var root = document.querySelector ? document.querySelector("#app-main .pe-root") : null;
      if (!root || !root._x_dataStack) return false;
      var match = /\/pipelines\/([^/]+)$/.exec(window.location.pathname);
      var id = null;
      try {
        id = match ? decodeURIComponent(match[1]) : null;
      } catch (e) {
        id = null;
      }
      if (id !== inst.pipeline.id) return false;
      inst.replayHistoryEntry(version, tab);
      return true;
    });
  }

  /*
   * 195 — the component registers itself under the CSP build's rule: `x-data`
   * may only NAME a component registered with Alpine.data (no inline object, no
   * call). alpine:init fires when the deferred alpine.min.js boots, after every
   * parser-blocking script on the page — this file included — has run, so the
   * listener below is in place in time.
   */
  if (typeof document !== "undefined" && document.addEventListener) {
    document.addEventListener("alpine:init", function () {
      if (typeof window !== "undefined" && window.Alpine && window.Alpine.data) {
        window.Alpine.data("pipelineEditor", pipelineEditor);
        // #358: the runtime's activation registers the component DIRECTLY when
        // Alpine booted without this listener (the singleton gate's planted
        // second Alpine boots before the catalog reaches init.js); the flag
        // keeps exactly one registration either way.
        window.__peComponentRegistered = true;
      }
    });
  }

  window.pipelineEditor = pipelineEditor;
  window.pipelineEditorBoost = { wireBoostLifecycle: wireBoostLifecycle };
  wireBoostLifecycle();
  wireVersionLinks();
  wireSinkTokenGuard();
  wireWorkspaceHistory();
})();
