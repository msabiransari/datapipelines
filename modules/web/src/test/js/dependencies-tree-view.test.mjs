// #876 G6 — static/js/dependencies/tree-view.js, the ONE adapter from G4's tree payload to the renderer's view-model.
// Every tree golden G4 ships (modules/web/src/test/resources/dependencies/goldens, the served envelopes) runs through
// it: one text status per node (colour never alone), the §5.2 version labels, the §A.2 object links built from
// entity_id/name and nothing else, the relationship labels, the constraint texts and the §13 states keyed by the
// diagnostic codes (never by complete:false alone). Names are data: a hostile name survives as text.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const view = require(path.resolve(here, "../../main/resources/static/js/dependencies/tree-view.js"));
const GOLDENS = path.resolve(here, "../resources/dependencies/goldens");

/** Every served golden that answers a tree (an envelope with `data`), by file name. */
function treeGoldens() {
  return fs
    .readdirSync(GOLDENS)
    .filter(name => name.endsWith(".json"))
    .map(name => [name, JSON.parse(fs.readFileSync(path.join(GOLDENS, name), "utf8"))])
    .filter(([, envelope]) => envelope.data)
    .map(([name, envelope]) => [name, envelope.data]);
}
const golden = name => treeGoldens().find(([file]) => file === name)[1];
const nodeNamed = (model, name) => model.nodes.find(node => node.name === name);
const clone = value => JSON.parse(JSON.stringify(value));

test("every tree golden adapts: one text status per node, a label per edge, never an empty string", () => {
  const goldens = treeGoldens();
  assert.ok(goldens.length >= 8, `non-vacuity: ${goldens.length} tree goldens read`);
  for (const [name, data] of goldens) {
    const model = view.fromTreePayload(data);
    assert.equal(model.nodes.length, data.nodes.length, name);
    assert.equal(model.edges.length, data.edges.length, name);
    for (const node of model.nodes) {
      assert.ok(node.statusText && node.statusText.length > 0, `${name} ${node.id}: a text status`);
      assert.ok(["success", "warning", "danger", "muted"].includes(node.tone), `${name} ${node.id}: tone ${node.tone}`);
      assert.ok(node.versionLabel && node.glyph && node.familyLabel && node.name, `${name} ${node.id}`);
    }
    for (const edge of model.edges) {
      assert.ok(Object.values(view.RELATIONSHIPS).includes(edge.label), `${name} ${edge.id}: label ${edge.label}`);
    }
    assert.ok(["ready", "empty", "incomplete", "cycle", "limit"].includes(model.state), `${name}: ${model.state}`);
  }
});

test("the §5.4 dashboard graph: links per family at the exact version, constraints as text, the root unlinked", () => {
  const model = view.fromTreePayload(golden("tree-dashboard.json"));
  const board = nodeNamed(model, "test/tree875/golden/board");
  assert.equal(board.isRoot, true);
  assert.equal(board.href, null, "no link to the page you are on");
  assert.equal(nodeNamed(model, "test/tree875/golden/set").href, "/parameter-sets/00000000-0000-4000-8000-000000000002?version=1");
  assert.equal(nodeNamed(model, "test/tree875/golden/source").href, "/pipelines/00000000-0000-4000-8000-000000000004?version=1");
  assert.equal(nodeNamed(model, "test/tree875/golden/chart").href, "/visualizations/00000000-0000-4000-8000-000000000006?version=1");
  assert.equal(nodeNamed(model, "test/tree875/golden/query.sql").href, "/templates/test/tree875/golden/query.sql?version=1", "a template links by name, separators kept");
  assert.equal(nodeNamed(model, "tree875_golden").href, "/datasources/tree875_golden", "a datasource links by name, unversioned");
  assert.equal(nodeNamed(model, "tree875_golden").statusText, "Registered datasource · read-write");
  assert.equal(nodeNamed(model, "tree875_golden").versionLabel, "Unversioned");
  assert.equal(nodeNamed(model, "test/tree875/golden/chart").statusText, "Draft · evidence ready");
  assert.equal(board.versionLabel, "v1");
  const constraints = model.edges.flatMap(edge => edge.constraints);
  assert.ok(constraints.includes("requires parameters region"));
  assert.ok(constraints.includes("dialect H2"));
  assert.ok(constraints.includes("needs read access"));
  assert.deepEqual(model.edges.map(edge => edge.label).sort(), ["Datasource", "Parameter set", "Selector datasource", "Selector template", "Source pipeline", "Template", "Transform template", "Visualization"]);
  assert.equal(model.state, "ready");
  const shared = model.nodes.filter(node => node.name === "tree875_golden");
  assert.equal(shared.length, 1, "the shared datasource is one node, never expanded per path");
});

test("a dashboard root seen from another dashboard's graph links to /dashboards/<entity_id>?version=N", () => {
  const data = clone(golden("tree-dashboard.json"));
  data.roots = ["n1"];
  const board = view.fromTreePayload(data).nodes.find(node => node.id === "n0");
  assert.equal(board.href, "/dashboards/00000000-0000-4000-8000-000000000001?version=1");
});

test("no link for a MISSING node, an entity_id-less node, or a family without a page", () => {
  const model = view.fromTreePayload(golden("missing-template-absent.json"));
  const orphan = nodeNamed(model, "test/tree875/golden/orphan.sql");
  assert.equal(orphan.href, null);
  assert.equal(orphan.statusText, "Missing");
  assert.equal(orphan.tone, "danger");
  assert.ok(orphan.diagnostics.length >= 1, "the MISSING diagnostic travels with its node as text");
  const noId = clone(golden("tree-pipeline.json"));
  noId.roots = ["n2"];
  noId.nodes[0].entity_id = null;
  assert.equal(view.fromTreePayload(noId).nodes[0].href, null, "a null entity_id builds no link");
  const schedule = clone(golden("tree-schedule-enabled.json"));
  schedule.roots = ["n1"];
  assert.equal(view.fromTreePayload(schedule).nodes[0].href, null, "a schedule has no page in the table");
  const api = clone(golden("tree-api-endpoint-enabled.json"));
  api.roots = ["n1"];
  assert.equal(view.fromTreePayload(api).nodes[0].href, null, "an API endpoint has no page in the table");
});

test("a template name is encoded per segment: separators stay, everything else is percent-encoded", () => {
  const data = clone(golden("tree-pipeline.json"));
  data.nodes[1].name = "acme/a b/q?x=1#frag.sql";
  assert.equal(view.fromTreePayload(data).nodes[1].href, "/templates/acme/a%20b/q%3Fx%3D1%23frag.sql?version=1");
});

test("the §5.2 labels: vN, Revision N, Unversioned; schedule conditions; datasource access; API enabled flag", () => {
  assert.equal(view.versionLabel({ kind: "artifact", number: 4 }), "v4");
  assert.equal(view.versionLabel({ kind: "revision", number: 2 }), "Revision 2");
  assert.equal(view.versionLabel({ kind: "unversioned", number: null }), "Unversioned");
  assert.equal(view.versionLabel(null), "Unversioned");
  const paused = view.fromTreePayload(golden("tree-schedule-paused.json")).nodes[0];
  assert.deepEqual([paused.versionLabel, paused.statusText, paused.tone], ["Revision 2", "Paused", "warning"]);
  const blocked = view.fromTreePayload(golden("tree-schedule-blocked.json")).nodes[0];
  assert.deepEqual([blocked.statusText, blocked.tone], ["Blocked", "danger"]);
  const enabled = view.fromTreePayload(golden("tree-schedule-enabled.json")).nodes[0];
  assert.deepEqual([enabled.statusText, enabled.tone], ["Enabled", "success"]);
  assert.equal(view.fromTreePayload(golden("tree-schedule-enabled.json")).edges[0].label, "Runs pipeline");
  const ro = view.fromTreePayload(golden("tree-datasource-readonly.json"));
  assert.equal(ro.nodes[0].statusText, "Registered datasource · read-only");
  assert.ok(!/released/i.test(ro.nodes[0].statusText), "a datasource is never RELEASED");
  assert.equal(ro.state, "empty");
  assert.deepEqual(ro.notes, ["No dependencies"]);
  const api = clone(golden("tree-api-endpoint-enabled.json"));
  api.nodes[0].operational = { enabled: false };
  assert.equal(view.fromTreePayload(api).nodes[0].statusText, "Draft · disabled", "what the payload carries, once #902 lands");
  const notReady = view.fromTreePayload(golden("tree-visualization-evidence-not-ready.json")).nodes[0];
  assert.equal(notReady.statusText, "Draft · evidence not ready");
});

test("states are keyed by the diagnostic codes: cycle, limit, the DISCARDED leaf, write access, imports", () => {
  const cycle = view.fromTreePayload(golden("tree-acyclic-false.json"));
  assert.equal(cycle.state, "cycle");
  assert.ok(cycle.notes.some(note => note.startsWith("Cycle:")));
  assert.ok(!cycle.notes.some(note => /order/i.test(note) && !/no release order/i.test(note)), "no execution-order hint");
  for (const name of ["tree-limit-depth.json", "tree-limit-edges.json", "tree-limit-nodes.json", "tree-limit-wire-bytes.json", "tree-limit-diagnostics.json"]) {
    const limit = view.fromTreePayload(golden(name));
    assert.equal(limit.state, "limit", name);
    assert.ok(limit.notes.some(note => note.startsWith("Limit reached — showing ")), name);
  }
  const discarded = view.fromTreePayload(golden("tree-pipeline-discarded.json"));
  assert.equal(discarded.state, "ready", "complete:false with only DISCARDED is NOT the incomplete state");
  assert.equal(discarded.nodes[0].statusText, "Discarded");
  assert.equal(discarded.nodes[0].tone, "muted");
  assert.ok(discarded.notes.length === 1 && !discarded.notes.includes("No dependencies"), "the DISCARDED diagnostic as text");
  const blocker = clone(golden("tree-pipeline.json"));
  blocker.nodes[1].lifecycle = "DISCARDED";
  const blocking = view.fromTreePayload(blocker).nodes[1];
  assert.deepEqual([blocking.statusText, blocking.tone], ["Discarded — blocks release", "danger"]);
  const writer = view.fromTreePayload(golden("tree-pipeline-writer.json"));
  assert.ok(writer.edges.some(edge => edge.constraints.includes("needs write access")));
  assert.equal(view.fromTreePayload(golden("tree-template-import.json")).edges[0].label, "Imports");
  const incomplete = clone(golden("tree-pipeline.json"));
  incomplete.diagnostics = [{ code: "INCOMPLETE_VIEW", node_id: null, edge_id: null, message: "hidden: acme/secret" }];
  const shown = view.fromTreePayload(incomplete);
  assert.equal(shown.state, "incomplete");
  assert.deepEqual(shown.notes, ["Incomplete: some objects are not visible to you."], "generic wording, nothing about what is hidden");
});

// #908 — erratum (11): a parameter_names constraint's optional unmet_names (the bound names the reached set version
// does not declare) is a SECOND constraint text after the met one; it is never a diagnostic, a note or a state.
const UNMET = "constraint-parameter-names-unmet.json";
const setEdge = model => model.edges.find(edge => edge.label === "Parameter set");

test("#908 the unmet golden: the set edge shows the bound names and then the unmet ones, nothing else changes", () => {
  const model = view.fromTreePayload(golden(UNMET));
  assert.deepEqual(setEdge(model).constraints, ["requires parameters region, since", "unmet parameters since"]);
  const others = model.edges.filter(edge => edge.label !== "Parameter set").map(edge => [edge.id, edge.constraints]);
  assert.deepEqual(others, [["e1", []], ["e2", []], ["e3", ["needs read access"]], ["e4", []], ["e5", []]]);
  assert.equal(model.state, "ready", "an unmet name is not a diagnostic");
  assert.deepEqual(model.notes, []);
});

test("#908 the control: the same payload without unmet_names renders the met text alone", () => {
  const data = clone(golden(UNMET));
  delete data.edges[0].constraints[0].unmet_names;
  assert.deepEqual(setEdge(view.fromTreePayload(data)).constraints, ["requires parameters region, since"]);
});

test("#908 unmet names keep payload order; an empty or non-array unmet_names adds nothing", () => {
  const all = clone(golden(UNMET));
  all.edges[0].constraints[0].unmet_names = ["region", "since"];
  assert.deepEqual(setEdge(view.fromTreePayload(all)).constraints, ["requires parameters region, since", "unmet parameters region, since"]);
  for (const odd of [[], null, "since", { 0: "since" }]) {
    const data = clone(golden(UNMET));
    data.edges[0].constraints[0].unmet_names = odd;
    assert.deepEqual(setEdge(view.fromTreePayload(data)).constraints, ["requires parameters region, since"], JSON.stringify(odd));
  }
});

test("#908 a hostile unmet name is data: kept verbatim inside the text", () => {
  const hostile = "<img src=x onerror=alert(1)>";
  const data = clone(golden(UNMET));
  data.edges[0].constraints[0].names = ["region", hostile];
  data.edges[0].constraints[0].unmet_names = [hostile];
  assert.deepEqual(setEdge(view.fromTreePayload(data)).constraints, ["requires parameters region, " + hostile, "unmet parameters " + hostile]);
});

test("a leaf template with no imports is the empty state", () => {
  const leaf = view.fromTreePayload(golden("tree-template.json"));
  assert.equal(leaf.state, "empty");
  assert.deepEqual(leaf.notes, ["No dependencies"]);
});

test("an unreadable payload is refused: schema_version 2, missing arrays, nothing", () => {
  const bumped = clone(golden("tree-pipeline.json"));
  bumped.schema_version = 2;
  assert.throws(() => view.fromTreePayload(bumped), /Unreadable dependency tree/);
  assert.throws(() => view.fromTreePayload({ schema_version: 1, nodes: [] }), /Unreadable/);
  assert.throws(() => view.fromTreePayload(null), /Unreadable/);
});

test("a hostile name is data: kept verbatim as text, never altered into markup", () => {
  const data = clone(golden("tree-pipeline.json"));
  data.nodes[1].name = "<img src=x onerror=alert(1)>";
  const node = view.fromTreePayload(data).nodes[1];
  assert.equal(node.name, "<img src=x onerror=alert(1)>");
  assert.equal(node.href, "/templates/%3Cimg%20src%3Dx%20onerror%3Dalert(1)%3E?version=1");
});

// The graph label is the renderer's half of "colour never alone": every drawn node carries its status as text.
const renderer = require(path.resolve(here, "../../main/resources/static/js/dependencies/renderer.js"));

test("every graph label over every golden carries the node's family, version label and status text", () => {
  let labels = 0;
  for (const [name, data] of treeGoldens()) {
    for (const node of view.fromTreePayload(data).nodes) {
      const label = renderer.graphLabel(node);
      assert.ok(label.endsWith("\n" + node.statusText), `${name} ${node.id}: ${label}`);
      assert.ok(label.startsWith(node.familyLabel + " · "), `${name} ${node.id}`);
      assert.ok(label.includes(" " + node.versionLabel + "\n"), `${name} ${node.id}`);
      labels++;
    }
  }
  assert.ok(labels >= 40, `non-vacuity: ${labels} labels`);
  const long = renderer.graphLabel({ familyLabel: "Template", name: "a/".repeat(40), versionLabel: "v1", statusText: "Draft" });
  const visible = long.split("\n")[0].replaceAll("\u200b", "");
  assert.ok(visible.length <= "Template · ".length + 48 + " v1".length && long.includes("…"), "a long name is ellipsised in the graph only");
});

test("a graph label wraps between a name's segments, and no segment outruns a line", () => {
  // Cytoscape wraps a label only at whitespace and U+200B: a folder path gets a U+200B after each "/" (#876 R1 — the
  // unbroken path ran over the node's family glyph).
  const label = renderer.graphLabel({ familyLabel: "Template", name: "test/depsabcd/formatter.jsonata", versionLabel: "v1", statusText: "Draft" });
  assert.equal(label, "Template · test/\u200bdepsabcd/\u200bformatter.jsonata v1\nDraft");
  const long = renderer.graphLabel({ familyLabel: "Template", name: "test/" + "x".repeat(40), versionLabel: "v1", statusText: "Draft" });
  const segments = long.split("\n")[0].split(/[\s\u200b]+/);
  assert.ok(segments.every(s => s.length <= 22), `every wrap-free run fits a line: ${JSON.stringify(segments)}`);
  assert.ok(long.includes("x".repeat(21) + "…"), "the long segment keeps its start and says it was cut");
  assert.equal(renderer.graphLabel({ familyLabel: "Datasource", name: "warehouse", versionLabel: "Unversioned", statusText: "Registered" }),
    "Datasource · warehouse Unversioned\nRegistered", "a name with no folder is drawn as it is");
});
