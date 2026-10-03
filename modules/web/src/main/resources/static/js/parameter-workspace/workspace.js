(function () {
  "use strict";

  /*
   * The Parameter Sets workspace's GLUE (#374, workspace spec §6.2/§6.3): reads the page's two JSON blocks, mounts the
   * static graph and the inspector, and — only for a role that may evaluate — the live form through the runtime's
   * parameters-only entry (`DatapipelinesDashboard.initParameters`). #383 — the form's evaluation is the OBSERVED
   * evaluation: the runtime hands every applied frame to `onStreamEvent`, this file drives the graph's live node
   * states, the live region's one-sentence announcements and the in-memory frame log (`window.PSWorkspace.frames`).
   * The CSP allows no inline script, so this FILE is the whole host; no server value reaches a script body.
   *
   * The contract this file keeps:
   *   - the workspace block is the ONE source of the displayed and the submitted version; a malformed block is a
   *     refusal with a visible error and ZERO requests, never a default;
   *   - a role that may not evaluate gets no control AND no request: initParameters is never called;
   *   - a parameter VALUE never leaves the form except in the evaluate POST body: no URL, no history state, no
   *     storage, no log line;
   *   - a frame whose evaluation_id is not the current attempt's never reaches this file (applied: false is logged
   *     and dropped before any state or DOM change);
   *   - mount/unmount are idempotent per root, so a history restore (runtime.js) can re-enter safely.
   */

  var mounts = new WeakMap();

  function byId(root, id) {
    return root.querySelector("#" + id);
  }

  function showError(root, code, message) {
    var region = byId(root, "ps-form-error");
    if (!region) return;
    var codeEl = byId(root, "ps-form-error-code");
    var messageEl = byId(root, "ps-form-error-message");
    if (codeEl) codeEl.textContent = code || "";
    if (messageEl) messageEl.textContent = message || "";
    region.hidden = false;
  }

  function clearError(root) {
    var region = byId(root, "ps-form-error");
    if (region) region.hidden = true;
  }

  function setStatus(root, text) {
    var status = byId(root, "ps-form-status");
    if (status) status.textContent = text;
  }

  /** One short sentence per applied frame — the live region's announcement, never the frame's JSON. */
  var ANNOUNCEMENTS = {
    parameter_waiting: function (info) {
      return (info.name || "a parameter") + " is waiting";
    },
    parameter_admitted: function (info) {
      return (info.name || "a parameter") + " was admitted";
    },
    parameter_running: function (info) {
      return (info.name || "a parameter") + " is running";
    },
    parameter_resolved: function (info) {
      return (info.name || "a parameter") + " resolved";
    },
    parameter_failed: function (info) {
      return (info.name || "a parameter") + " failed (" + (info.code || "unknown") + ")";
    },
    evaluation_started: function () {
      return "Evaluating the parameters…";
    },
    evaluation_completed: function () {
      return "The evaluation completed.";
    },
    evaluation_failed: function (info) {
      return "The evaluation failed (" + (info.code || "unknown") + ").";
    },
  };

  /**
   * #383 — the applied stream frames drive the graph and the live region. `info` is the runtime's frame witness
   * entry (`{evaluation_id, event, name?, code?, detail?, response?, applied}`); DROPPED frames (another attempt's
   * id, applied: false) are logged and change nothing. The frame log is memory only — never persisted.
   */
  function handleStreamEvent(root, state, structure, info) {
    // The log is the entry AS THE RUNTIME SENT IT (evaluation_id, event, name?, code?, detail?, response?, applied,
    // reason?) — the guards read the code, the close reason and the terminal response off it.
    window.PSWorkspace.frames.push(Object.assign({}, info));
    if (!structure) return;
    var graph = typeof structure.graph === "function" ? structure.graph() : null;
    if (!info.applied) return;
    if (info.event === "evaluation_started") {
      state.streamStates = {};
      if (graph) graph.clearStates();
    } else if (info.event === "parameter_failed") {
      state.streamStates = window.PSModel.applyFrame(state.streamStates, info);
      state.failures[info.name] = { code: info.code, detail: info.detail };
      if (graph) graph.setState(info.name, "failed");
    } else if (info.event === "evaluation_failed") {
      state.streamStates = window.PSModel.applyFrame(state.streamStates, info);
      // D3 (#375): the reducer failed every parameter without a terminal state; a parameter cancelled before its
      // first frame has no entry at all and fails here too. The graph syncs to the map.
      window.PSModel.unfinishedNames(typeof structure.names === "function" ? structure.names() : [], state.streamStates).forEach(function (name) {
        state.streamStates[name] = "failed";
      });
      Object.keys(state.streamStates).forEach(function (name) {
        if (state.streamStates[name] === "failed" && graph) graph.setState(name, "failed");
      });
    } else if (info.event === "parameter_waiting" || info.event === "parameter_admitted" || info.event === "parameter_running" || info.event === "parameter_resolved") {
      state.streamStates = window.PSModel.applyFrame(state.streamStates, info);
      if (graph) graph.setState(info.name, state.streamStates[info.name]);
    }
    // A failure the SELECTED parameter just picked up shows in the inspector at once.
    if (info.event === "parameter_failed" && typeof structure.refresh === "function") structure.refresh();
    var say = ANNOUNCEMENTS[info.event];
    var live = byId(root, "ps-graph-live");
    if (say && live) live.textContent = say(info);
  }

  function mountForm(root, workspace, state, inspect) {
    var host = byId(root, "ps-form-host");
    var runtime = window.DatapipelinesDashboard;
    if (!host || !runtime || typeof runtime.initParameters !== "function") {
      showError(root, "runtime.unavailable", "The parameter form could not load. Reload the page to try again.");
      return null;
    }
    var base = runtime.adapters(host, { provenance: true });
    // The wrapper is an adapter that DELEGATES: `this` stays the wrapper for every inherited function, so the
    // composite's own state (container, parametersRoot, selection reads) lives in ONE place.
    var adapter = Object.create(base);
    adapter.renderParameters = function (evaluated) {
      state.last = evaluated;
      state.failures = {}; // a fresh response is the truth; the stream's per-parameter failures are retired
      var rendered = base.renderParameters.call(this, evaluated);
      var graph = inspect.graph ? inspect.graph() : null;
      if (graph && typeof graph.markResets === "function") {
        graph.markResets(window.PSModel.resetNames(evaluated));
      }
      inspect.refresh();
      setStatus(
        root,
        evaluated && evaluated.valid === false
          ? "Some parameters need attention."
          : "Evaluated against version " + workspace.viewedVersion + ".",
      );
      return rendered;
    };
    var instance;
    try {
      instance = runtime.initParameters({
        server: { baseUrl: "", credentials: "session" },
        parameterSet: { id: workspace.parameterSetId, version: workspace.viewedVersion },
        container: host,
        adapter: adapter,
        options: {
          onNotification: function (n) {
            if (n.severity === "error") showError(root, n.code, n.message);
            else if (n.code === "parameters.applied") clearError(root);
          },
          onStreamEvent: function (info) {
            handleStreamEvent(root, state, inspect, info);
          },
        },
      });
    } catch (e) {
      showError(root, e && e.code ? e.code : "init.failed", e && e.message ? e.message : String(e));
      return null;
    }
    setStatus(root, "Evaluating…");
    instance.ready.then(
      function () {
        clearError(root);
      },
      function (error) {
        showError(root, error && error.code ? error.code : "bootstrap.failed", error && error.message ? error.message : String(error));
        setStatus(root, "");
      },
    );
    return instance;
  }

  function mountStructure(root, set, state) {
    var inspectorRegion = byId(root, "ps-inspector");
    var graphHost = byId(root, "ps-graph");
    var live = byId(root, "ps-graph-live");
    var inspector = window.PSInspector.mount(inspectorRegion);
    var elements = window.PSModel.graphElements(set);
    var selected = null;
    var graph = null;

    function parameterNamed(name) {
      return (set.parameters || []).filter(function (p) {
        return p.name === name;
      })[0];
    }
    function lastFor(name) {
      var parameters = (state.last && state.last.parameters) || [];
      return parameters.filter(function (p) {
        return p.name === name;
      })[0];
    }
    function failureFor(name) {
      return (state.failures && state.failures[name]) || null;
    }
    function choose(name) {
      var parameter = parameterNamed(name);
      if (!parameter) return;
      selected = name;
      if (graph) graph.select(name);
      inspector.show(parameter, set, lastFor(name), failureFor(name));
      if (live) live.textContent = "Selected " + (parameter.label || name);
    }

    if (elements.nodes.length && graphHost) {
      try {
        graph = window.PSGraph.create(graphHost, elements, choose);
      } catch (e) {
        graphHost.textContent = "The dependency graph could not load.";
      }
    }
    // Keyboard traversal of graph + inspector: the graph host is a focusable group; arrows move the selection along
    // display order (up/down) and the dependency edges (left = a parent, right = a dependent).
    if (graphHost) {
      graphHost.addEventListener("keydown", function (event) {
        var keys = ["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Home", "End"];
        if (keys.indexOf(event.key) === -1) return;
        event.preventDefault();
        var next = window.PSModel.neighbour(set, selected, event.key);
        if (next) choose(next);
      });
    }
    return {
      refresh: function () {
        if (selected) choose(selected);
      },
      graph: function () {
        return graph;
      },
      names: function () {
        return ((set && set.parameters) || [])
          .map(function (p) {
            return p && p.name;
          })
          .filter(function (n) {
            return typeof n === "string";
          });
      },
      destroy: function () {
        if (graph) graph.destroy();
        graph = null;
      },
    };
  }

  function mount(root) {
    if (!root || mounts.has(root)) return;
    // #383 — the frame log: every stream frame the page receives, for the guards to read. Memory only,
    // reset per mount, never persisted anywhere.
    window.PSWorkspace.frames = [];
    var workspace = window.PSModel.parseWorkspace((byId(root, "ps-workspace") || {}).textContent);
    if (!workspace) {
      showError(root, "workspace.invalid", "This page's workspace state is malformed; nothing was evaluated. Reload the page to try again.");
      mounts.set(root, { instance: null, structure: null });
      return;
    }
    var set = workspace.hasBody ? window.PSModel.readBlock((byId(root, "ps-data") || {}).textContent) : null;
    if (workspace.hasBody && !set) {
      showError(root, "workspace.invalid", "This page's parameter-set data is malformed; nothing was evaluated. Reload the page to try again.");
      mounts.set(root, { instance: null, structure: null });
      return;
    }
    var state = { last: null, streamStates: {}, failures: {} };
    var structure = set ? mountStructure(root, set, state) : null;
    var instance = null;
    if (set && workspace.canEvaluate) {
      instance = mountForm(root, workspace, state, structure || { refresh: function () {} });
    }
    mounts.set(root, { instance: instance, structure: structure });
    root.setAttribute("data-ps-mounted", "true");
  }

  /** Disposes the live instance and the graph, empties everything this file generated, strips generated style attributes. */
  function unmount(root) {
    var held = root && mounts.get(root);
    if (!held) return;
    if (held.instance) {
      try {
        held.instance.dispose();
      } catch (e) {
        /* the save proceeds either way */
      }
    }
    if (held.structure) held.structure.destroy();
    ["ps-graph", "ps-form-host", "ps-inspector"].forEach(function (id) {
      var node = byId(root, id);
      if (node) node.replaceChildren();
    });
    var styled = root.querySelectorAll("[style]");
    for (var i = 0; i < styled.length; i++) styled[i].removeAttribute("style");
    root.removeAttribute("data-ps-mounted");
    mounts.delete(root);
  }

  window.PSWorkspace = { mount: mount, unmount: unmount, mounted: mounts, frames: [] };
})();
