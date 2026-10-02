(function () {
  "use strict";

  /*
   * The Parameter Sets workspace's GLUE (#374, workspace spec §6.2/§6.3): reads the page's two JSON blocks, mounts the
   * static graph and the inspector, and — only for a role that may evaluate — the live form through the runtime's
   * parameters-only entry (`DatapipelinesDashboard.initParameters`). The CSP allows no inline script, so this FILE is
   * the whole host; no server value reaches a script body.
   *
   * The contract this file keeps:
   *   - the workspace block is the ONE source of the displayed and the submitted version; a malformed block is a
   *     refusal with a visible error and ZERO requests, never a default;
   *   - a role that may not evaluate gets no control AND no request: initParameters is never called;
   *   - a parameter VALUE never leaves the form except in the evaluate POST body: no URL, no history state, no
   *     storage, no log line;
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
      var rendered = base.renderParameters.call(this, evaluated);
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
    function choose(name) {
      var parameter = parameterNamed(name);
      if (!parameter) return;
      selected = name;
      if (graph) graph.select(name);
      inspector.show(parameter, set, lastFor(name));
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
      destroy: function () {
        if (graph) graph.destroy();
        graph = null;
      },
    };
  }

  function mount(root) {
    if (!root || mounts.has(root)) return;
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
    var state = { last: null };
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

  window.PSWorkspace = { mount: mount, unmount: unmount, mounted: mounts };
})();
