(function (root, factory) {
  "use strict";
  var api = factory();
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") window.PSModel = api;
})(this, function () {
  "use strict";

  /*
   * The Parameter Sets workspace's PURE model (#374, workspace spec §6.2): no DOM, no network — everything the
   * graph, the inspector and the glue decide from the two server-rendered JSON blocks lives here so `node --test`
   * owns it. Nothing in this file builds a selector or an HTML string from a parameter's name: names stay DATA
   * (element ids, object keys, text content), never markup.
   */

  var OBJECT = "[object Object]";

  function isObject(value) {
    return Object.prototype.toString.call(value) === OBJECT;
  }

  /** A JSON block's text → its value, or null. A malformed block is a REFUSAL for the caller, never a default. */
  function readBlock(text) {
    if (typeof text !== "string" || text.trim() === "") return null;
    try {
      var value = JSON.parse(text);
      return isObject(value) ? value : null;
    } catch (e) {
      return null;
    }
  }

  /**
   * The workspace block (`#ps-workspace`): the ONE source of the displayed AND the submitted version. Returns the
   * validated state, or null when any field the page acts on is missing or mistyped — the glue then refuses with a
   * visible error and issues zero requests.
   */
  function parseWorkspace(text) {
    var block = readBlock(text);
    if (!block) return null;
    if (typeof block.parameterSetId !== "string" || block.parameterSetId === "") return null;
    if (typeof block.hasBody !== "boolean" || typeof block.canEvaluate !== "boolean") return null;
    if (block.hasBody) {
      var v = block.viewedVersion;
      if (typeof v !== "number" || !isFinite(v) || v < 1 || v % 1 !== 0) return null;
    } else if (block.canEvaluate) {
      return null; // nothing to evaluate: a body-less page that claims evaluate is the server's defect
    }
    return block;
  }

  /** The badge of a parameter's SOURCE kind (spec §6.2): constants, template, or a plain input. */
  function sourceKind(parameter) {
    var source = parameter && parameter.source;
    if (source && Array.isArray(source.constants)) return "constants";
    if (source && source.template) return "template";
    return parameter && parameter.kind === "INPUT" ? "input" : "none";
  }

  /**
   * The static graph's elements: one node per parameter (display order kept), one edge per `depends_on` entry
   * whose parent exists in the set — a dangling name is skipped, never invented as a node. Edges run
   * parent → child, the direction a selection flows.
   */
  function graphElements(set) {
    var parameters = (set && Array.isArray(set.parameters) && set.parameters) || [];
    var known = {};
    parameters.forEach(function (p) {
      if (p && typeof p.name === "string") known[p.name] = true;
    });
    var nodes = [];
    var edges = [];
    parameters.forEach(function (p, index) {
      if (!p || typeof p.name !== "string") return;
      nodes.push({ id: p.name, label: p.label || p.name, badge: sourceKind(p), kind: p.kind, order: index });
      (Array.isArray(p.depends_on) ? p.depends_on : []).forEach(function (parent) {
        if (known[parent] === true) edges.push({ id: parent + "→" + p.name, source: parent, target: p.name });
      });
    });
    return { nodes: nodes, edges: edges };
  }

  /** Direct and transitive dependents of [name], in display order — over `depends_on`, cycle-safe. */
  function dependentsOf(set, name) {
    var parameters = (set && set.parameters) || [];
    var direct = [];
    var reach = {};
    function children(of) {
      var out = [];
      parameters.forEach(function (p) {
        if (p && Array.isArray(p.depends_on) && p.depends_on.indexOf(of) !== -1) out.push(p.name);
      });
      return out;
    }
    direct = children(name);
    var queue = direct.slice();
    while (queue.length) {
      var next = queue.shift();
      if (reach[next] || next === name) continue;
      reach[next] = true;
      queue = queue.concat(children(next));
    }
    var transitive = parameters
      .map(function (p) {
        return p && p.name;
      })
      .filter(function (n) {
        return reach[n] === true;
      });
    return { direct: direct, transitive: transitive };
  }

  function json(value) {
    return JSON.stringify(value === undefined ? null : value);
  }

  function pretty(value) {
    return JSON.stringify(value === undefined ? null : value, null, 2);
  }

  /**
   * One parameter's inspector, as plain data the DOM layer renders with `textContent` only. Rows are
   * `{label, kind: "text"|"code"|"table"|"links", ...}` in the spec's order: identity, type/kind/cardinality,
   * required, default, constraints, presentation, the source (constants table, or the template pin and
   * datasource name — as LINKS to their own routes, never inlined), the dependency lines, the two stored
   * expression ASTs (labelled as stored), and the last outcome once an evaluation ran.
   */
  function inspect(parameter, set, last) {
    var rows = [];
    function text(label, value) {
      rows.push({ label: label, kind: "text", value: String(value) });
    }
    function code(label, value) {
      rows.push({ label: label, kind: "code", value: value });
    }
    text("Label", parameter.label || parameter.name);
    text("Name", parameter.name);
    if (parameter.description) text("Description", parameter.description);
    var type = String(parameter.type);
    if (parameter.precision !== undefined && parameter.precision !== null) {
      type += "(" + parameter.precision + (parameter.scale !== undefined && parameter.scale !== null ? "," + parameter.scale : "") + ")";
    }
    text("Type", type);
    text("Kind", parameter.kind);
    text("Cardinality", parameter.cardinality || "SINGLE");
    text("Required", parameter.required === true ? "yes" : "no");
    text("Default", parameter.default_value === undefined || parameter.default_value === null ? "none" : json(parameter.default_value));
    if (isObject(parameter.constraints) && Object.keys(parameter.constraints).length) {
      code(
        "Constraints",
        Object.keys(parameter.constraints)
          .map(function (key) {
            return key + ": " + json(parameter.constraints[key]);
          })
          .join("\n"),
      );
    }
    if (isObject(parameter.presentation)) {
      var shown = [];
      if (parameter.presentation.control) shown.push("control: " + parameter.presentation.control);
      if (isObject(parameter.presentation.format)) shown.push("format: " + json(parameter.presentation.format));
      if (shown.length) code("Presentation", shown.join("\n"));
    }
    var source = parameter.source;
    if (source && Array.isArray(source.constants)) {
      rows.push({
        label: "Options (constants)",
        kind: "table",
        headers: ["Value", "Shown as", "Default"],
        body: source.constants.map(function (c) {
          return [json(c.value), String(c.display_value), c.is_default === true ? "yes" : ""];
        }),
      });
    } else if (source && source.template) {
      var links = [];
      links.push({
        text: "template " + source.template.id + "@" + source.template.version,
        href: "/templates/editor?name=" + encodeURIComponent(source.template.id) + "&version=" + encodeURIComponent(source.template.version),
      });
      if (source.datasource) {
        links.push({ text: "datasource " + source.datasource, href: "/datasources/" + encodeURIComponent(source.datasource) });
      }
      rows.push({ label: "Source (template pin)", kind: "links", links: links });
    }
    var dependsOn = Array.isArray(parameter.depends_on) ? parameter.depends_on : [];
    text("Depends on", dependsOn.length ? dependsOn.join(", ") : "nothing");
    var dependents = dependentsOf(set, parameter.name);
    text("Dependents", dependents.direct.length ? dependents.direct.join(", ") : "none");
    if (dependents.transitive.length > dependents.direct.length) text("All downstream", dependents.transitive.join(", "));
    code("Hidden when (stored expression)", parameter.hidden_expression ? pretty(parameter.hidden_expression) : "never — no expression stored");
    code("Disabled when (stored expression)", parameter.disabled_expression ? pretty(parameter.disabled_expression) : "never — no expression stored");
    var state = last && last.state;
    if (state) {
      var outcome = ["value: " + json(state.value), "origin: " + state.origin];
      if (state.reset === true) outcome.push("reset: the previous selection was dropped");
      if (state.hidden === true) outcome.push("hidden: yes");
      if (state.disabled === true) outcome.push("disabled: yes");
      (state.errors || []).forEach(function (e) {
        outcome.push("error " + e.code + ": " + e.message);
      });
      code("Last evaluation", outcome.join("\n"));
    }
    return rows;
  }

  /** The keyboard graph traversal's one rule: which parameter an arrow key lands on from the selected one. */
  function neighbour(set, current, key) {
    var order = ((set && set.parameters) || []).map(function (p) {
      return p.name;
    });
    if (!order.length) return null;
    var index = order.indexOf(current);
    if (key === "Home") return order[0];
    if (key === "End") return order[order.length - 1];
    if (index === -1) return order[0];
    if (key === "ArrowDown") return order[Math.min(order.length - 1, index + 1)];
    if (key === "ArrowUp") return order[Math.max(0, index - 1)];
    var parameter = set.parameters[index];
    if (key === "ArrowRight") return dependentsOf(set, current).direct[0] || current;
    if (key === "ArrowLeft") return (Array.isArray(parameter.depends_on) && parameter.depends_on.filter(function (n) {
      return order.indexOf(n) !== -1;
    })[0]) || current;
    return current;
  }

  return {
    readBlock: readBlock,
    parseWorkspace: parseWorkspace,
    sourceKind: sourceKind,
    graphElements: graphElements,
    dependentsOf: dependentsOf,
    inspect: inspect,
    neighbour: neighbour,
  };
});
