(function () {
  "use strict";

  /*
   * dependencies/tree-view.js (#876 G6, record §13 / §11.1) — the ADAPTER from the dependency tree payload
   * (`GET /api/v1/dependencies/<family>`, the envelope's `data`) to the renderer's neutral view-model:
   *
   *   { nodes: [{ id, family, glyph, familyLabel, name, versionLabel, statusText, tone, isRoot, href, diagnostics }],
   *     edges: [{ id, from, to, label, constraints: [text] }],
   *     notes: [text], state: "ready" | "empty" | "incomplete" | "cycle" | "limit" }
   *
   * Every rule about labels, tones, relationship names and object links lives HERE and nowhere else, so the tabs and
   * the release dialog (plan-view.js, a different server contract) share one renderer. Pure — no DOM, no fetch —
   * and exported for `node --test`. Names, messages and labels are DATA: the renderer and the list write them with
   * textContent / Cytoscape's plain `label`, never as markup.
   *
   * Display rules (record §5.2): artifacts "vN", schedule revisions "Revision N", everything unversioned
   * "Unversioned"; a datasource is "Registered", never RELEASED. Colour never carries state alone: every node has a
   * statusText the graph label and the list both show. The "incomplete" note is keyed by the LIMIT / INCOMPLETE_VIEW
   * diagnostic codes, never by `complete: false` alone (a DISCARDED root answers complete:false with only DISCARDED).
   */

  /** The lucide glyph per family — the rail's own (layouts/default.html); no new glyph. */
  var GLYPHS = {
    pipeline: "workflow",
    template: "code",
    parameter_set: "sliders-horizontal",
    visualization: "chart-line",
    dashboard: "boxes",
    api_endpoint: "activity",
    schedule: "calendar-clock",
    datasource: "database",
  };

  var FAMILY_LABELS = {
    pipeline: "Pipeline",
    template: "Template",
    parameter_set: "Parameter set",
    visualization: "Visualization",
    dashboard: "Dashboard",
    api_endpoint: "API",
    schedule: "Schedule",
    datasource: "Datasource",
  };

  /** §11.1 Relationship → its label (record §13 "relationship reasons"; IMPORT is "Imports", §23.3 item 5). */
  var RELATIONSHIPS = {
    TEMPLATE: "Template",
    CHILD_PIPELINE: "Child pipeline",
    DATASOURCE: "Datasource",
    IMPORT: "Imports",
    PARAMETER_SOURCE_TEMPLATE: "Selector template",
    PARAMETER_SOURCE_DATASOURCE: "Selector datasource",
    TRANSFORM_TEMPLATE: "Transform template",
    PARAMETER_SET: "Parameter set",
    SOURCE_PIPELINE: "Source pipeline",
    VISUALIZATION: "Visualization",
    PIPELINE_PIN: "Pipeline",
    EXECUTOR_TARGET: "Runs pipeline",
  };

  /** The object pages a node links to (brief §A.2 table): path prefix, key field, versioned. */
  var PAGES = {
    pipeline: { prefix: "/pipelines/", key: "entity_id", versioned: true },
    dashboard: { prefix: "/dashboards/", key: "entity_id", versioned: true },
    visualization: { prefix: "/visualizations/", key: "entity_id", versioned: true },
    parameter_set: { prefix: "/parameter-sets/", key: "entity_id", versioned: true },
    template: { prefix: "/templates/", key: "name", versioned: true },
    datasource: { prefix: "/datasources/", key: "name", versioned: false },
  };

  function versionLabel(version) {
    if (!version || version.kind === "unversioned") return "Unversioned";
    var n = version.number;
    if (typeof n !== "number") return "Unversioned";
    return version.kind === "revision" ? "Revision " + n : "v" + n;
  }

  /** Each `/`-separated segment percent-encoded on its own; the separators stay (a template name holds `/`). */
  function encodePath(value) {
    return String(value).split("/").map(encodeURIComponent).join("/");
  }

  /**
   * The object page of a node at its EXACT version, or null. No link for the root (the page you are on), a node
   * that did not resolve (MISSING — also how a dependency the caller cannot see arrives, erratum (8)(c)), a family
   * without a page in the table (schedule, api_endpoint only ever appear as roots), or a missing key. Never built
   * from node_id (opaque) and never pre-checked for permission: a page the caller may not open answers its own 404.
   */
  function hrefFor(node, isRoot) {
    if (isRoot || !node || node.resolution !== "resolved") return null;
    var page = PAGES[node.family];
    if (!page) return null;
    var key = node[page.key];
    if (typeof key !== "string" || key === "") return null;
    var href = page.prefix + (page.key === "name" ? encodePath(key) : encodeURIComponent(key));
    if (page.versioned) {
      var n = node.version && node.version.kind === "artifact" ? node.version.number : null;
      if (typeof n !== "number") return null;
      href += "?version=" + encodeURIComponent(String(n));
    }
    return href;
  }

  /** One text status (colour is never alone) and its tone: success | warning | danger | muted. */
  function status(node, isRoot) {
    if (node.resolution === "missing") return { text: "Missing", tone: "danger" };
    if (node.resolution === "unsupported") return { text: "Not supported here", tone: "muted" };
    var op = node.operational || null;
    if (node.family === "datasource") {
      var access = op && typeof op.readonly === "boolean" ? (op.readonly ? " · read-only" : " · read-write") : "";
      return { text: "Registered datasource" + access, tone: "muted" };
    }
    if (node.family === "schedule") {
      var condition = op && op.condition;
      if (condition === "enabled") return { text: "Enabled", tone: "success" };
      if (condition === "paused") return { text: "Paused", tone: "warning" };
      if (condition === "blocked") return { text: "Blocked", tone: "danger" };
      return { text: "Schedule", tone: "muted" };
    }
    var text;
    var tone;
    if (node.lifecycle === "RELEASED") {
      text = "Released";
      tone = "success";
    } else if (node.lifecycle === "DRAFT") {
      text = "Draft";
      tone = "warning";
    } else if (node.lifecycle === "DISCARDED") {
      // A discarded ROOT is a terminal leaf; a discarded DEPENDENCY blocks whatever pins it.
      text = isRoot ? "Discarded" : "Discarded — blocks release";
      tone = isRoot ? "muted" : "danger";
    } else {
      text = "Unversioned";
      tone = "muted";
    }
    if (op && typeof op.enabled === "boolean") text += op.enabled ? " · enabled" : " · disabled";
    if (op && typeof op.evidence_ready === "boolean") text += op.evidence_ready ? " · evidence ready" : " · evidence not ready";
    return { text: text, tone: tone };
  }

  function constraintText(c) {
    if (!c) return null;
    if (c.kind === "parameter_names" && Array.isArray(c.names)) return "requires parameters " + c.names.join(", ");
    if (c.kind === "datasource_access") return c.mode === "write" ? "needs write access" : "needs read access";
    if (c.kind === "datasource_dialect") return "dialect " + c.dialect;
    return null;
  }

  /** The diagnostics as text notes and the pane's state (cycle > limit > incomplete > empty > ready). */
  function notesOf(data, nodeCount) {
    var notes = [];
    var codes = {};
    (data.diagnostics || []).forEach(function (d) {
      codes[d.code] = true;
    });
    var state = "ready";
    if (codes.CYCLE) {
      state = "cycle";
      notes.push("Cycle: these dependencies refer back to each other, so they have no release order.");
    }
    (data.diagnostics || []).forEach(function (d) {
      if (d.code === "LIMIT") {
        notes.push("Limit reached — showing " + nodeCount + (nodeCount === 1 ? " object. " : " objects. ") + d.message);
        if (state === "ready") state = "limit";
      }
    });
    if (codes.INCOMPLETE_VIEW) {
      // The generic wording only: nothing about how many, which family or what name is hidden (§12).
      notes.push("Incomplete: some objects are not visible to you.");
      if (state === "ready") state = "incomplete";
    }
    return { notes: notes, state: state };
  }

  /** The §11.1 payload (`data`) → the renderer's view-model. Throws on a payload it cannot read. */
  function fromTreePayload(data) {
    if (!data || data.schema_version !== 1 || !Array.isArray(data.nodes) || !Array.isArray(data.edges)) {
      throw new Error("Unreadable dependency tree");
    }
    var roots = {};
    (data.roots || []).forEach(function (id) {
      roots[id] = true;
    });
    var byNode = {};
    (data.diagnostics || []).forEach(function (d) {
      if (d.node_id && d.code !== "LIMIT" && d.code !== "CYCLE") (byNode[d.node_id] = byNode[d.node_id] || []).push(d.message);
    });
    var nodes = data.nodes.map(function (n) {
      var isRoot = roots[n.node_id] === true;
      var s = status(n, isRoot);
      return {
        id: n.node_id,
        family: n.family,
        glyph: GLYPHS[n.family] || "workflow",
        familyLabel: FAMILY_LABELS[n.family] || n.family,
        name: n.name || n.display_name || "Not available",
        versionLabel: versionLabel(n.version),
        statusText: s.text,
        tone: s.tone,
        isRoot: isRoot,
        href: hrefFor(n, isRoot),
        diagnostics: byNode[n.node_id] || [],
      };
    });
    var edges = data.edges.map(function (e) {
      return {
        id: e.edge_id,
        from: e.from,
        to: e.to,
        label: RELATIONSHIPS[e.relationship] || e.relationship,
        constraints: (e.constraints || []).map(constraintText).filter(function (t) {
          return t !== null;
        }),
      };
    });
    var summary = notesOf(data, nodes.length);
    if (summary.state === "ready" && edges.length === 0) {
      // A DISCARDED root is terminal (G4: no edges, one DISCARDED diagnostic): its leaf plus that diagnostic as
      // text, not "No dependencies" — its dependencies were not expanded, which is not the same as having none.
      var rootNotes = [];
      nodes.forEach(function (n) {
        if (n.isRoot) rootNotes = rootNotes.concat(n.diagnostics);
      });
      if (rootNotes.length > 0) {
        summary.notes = summary.notes.concat(rootNotes);
      } else {
        summary.state = "empty";
        summary.notes.push("No dependencies");
      }
    }
    return { nodes: nodes, edges: edges, notes: summary.notes, state: summary.state };
  }

  var api = {
    fromTreePayload: fromTreePayload,
    hrefFor: hrefFor,
    versionLabel: versionLabel,
    RELATIONSHIPS: RELATIONSHIPS,
    GLYPHS: GLYPHS,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") {
    var ns = (window.DependencyTree = window.DependencyTree || {});
    ns.fromTreePayload = fromTreePayload;
    ns.hrefFor = hrefFor;
    ns.versionLabel = versionLabel;
  }
})();
