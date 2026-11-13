// #876 §F.2 — static/js/dependencies/plan-view.js, the release dialog's adapter: the server-rendered RELEASE PLAN rows
// → the renderer's view-model, with NO request (record §12). The worked example is the brief's board: three draft
// dependencies (sales/clean shared by two pipelines), two released ones and one datasource prerequisite — six nodes,
// six edges, clean once, and the prerequisite never a node. Names and paths are data: a hostile one survives as text.

import test from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const planView = require(path.resolve(here, "../../main/resources/static/js/dependencies/plan-view.js"));
const treeView = require(path.resolve(here, "../../main/resources/static/js/dependencies/tree-view.js"));
const TABLES = { glyphs: treeView.GLYPHS, labels: treeView.FAMILY_LABELS };

const ROWS = [
  { nodeId: "n0", family: "dashboard", name: "sales/board", version: "3", status: "DRAFT", kind: "root" },
  { nodeId: "n1", family: "pipeline", name: "sales/daily", version: "5", status: "DRAFT", kind: "draft" },
  { nodeId: "n2", family: "pipeline", name: "sales/clean", version: "4", status: "DRAFT", kind: "draft" },
  { nodeId: "n3", family: "template", name: "sales/query", version: "1", status: "RELEASED", kind: "released" },
  { nodeId: "n4", family: "pipeline", name: "sales/weekly", version: "2", status: "DRAFT", kind: "draft" },
  { nodeId: "n5", family: "parameter_set", name: "sales/filters", version: "1", status: "RELEASED", kind: "released" },
];
const EDGES = [
  { from: "n0", to: "n1", path: "sources/0/pipeline" },
  { from: "n0", to: "n4", path: "sources/1/pipeline" },
  { from: "n0", to: "n5", path: "parameter_set" },
  { from: "n1", to: "n2", path: "nodes/0/pipeline" },
  { from: "n4", to: "n2", path: "nodes/0/pipeline" },
  { from: "n2", to: "n3", path: "nodes/0/template" },
];

/** A stand-in for the dialog's `[data-release-plan]`: plan rows, one prerequisite row, the hidden edge list. */
function dialogRoot(rows, edges) {
  const el = attrs => ({ getAttribute: name => (name in attrs ? attrs[name] : null) });
  const rowEls = rows.map(r =>
    el({
      "data-release-row": r.kind,
      "data-node-id": r.nodeId,
      "data-family": r.family,
      "data-name": r.name,
      "data-version": r.version,
      "data-status": r.status,
    }),
  );
  const edgeEls = edges.map(e => el({ "data-from": e.from, "data-to": e.to, "data-path": e.path }));
  const asked = [];
  return {
    asked,
    querySelectorAll(selector) {
      asked.push(selector);
      if (selector === "[data-release-row][data-node-id]") return rowEls;
      if (selector === "[data-release-edges] > li") return edgeEls;
      throw new Error(`unexpected selector ${selector}`);
    },
  };
}

test("the worked example: six nodes, six edges, the shared draft once, the root flagged, no links", () => {
  const model = planView.fromRows(ROWS, EDGES, TABLES);

  assert.equal(model.nodes.length, 6);
  assert.equal(model.edges.length, 6);
  assert.equal(model.nodes.filter(n => n.name === "sales/clean").length, 1, "a shared draft is one node");
  assert.equal(model.edges.filter(e => e.to === "n2").length, 2, "reached twice: the shape is in the edges");
  assert.deepEqual(model.nodes.filter(n => n.isRoot).map(n => n.id), ["n0"]);
  assert.equal(model.state, "ready");
  for (const node of model.nodes) {
    assert.equal(node.href, null, `${node.id}: the dialog is a consent surface, no link`);
    assert.ok(node.statusText.length > 0 && node.glyph && node.familyLabel, node.id);
  }
  assert.equal(model.nodes[0].statusText, "Draft — released by this action");
  assert.equal(model.nodes[1].statusText, "Draft — released with this");
  assert.equal(model.nodes[3].statusText, "Released");
  assert.equal(model.nodes[5].familyLabel, treeView.FAMILY_LABELS.parameter_set);
  assert.deepEqual(model.edges.map(e => e.label), EDGES.map(e => e.path), "the plan's path as given");
});

test("a blocking node reads its status in words with the danger tone - never colour alone", () => {
  const rows = [ROWS[0], { nodeId: "n9", family: "template", name: "gone/q", version: "9", status: "MISSING", kind: "blocking" }];
  const model = planView.fromRows(rows, [{ from: "n0", to: "n9", path: "nodes/0/template" }], TABLES);

  assert.equal(model.nodes[1].statusText, "Missing — blocks release");
  assert.equal(model.nodes[1].tone, "danger");
});

test("an edge naming an unknown node is dropped, and a root alone is the empty state", () => {
  const model = planView.fromRows([ROWS[0]], [{ from: "n0", to: "n-ghost", path: "x" }], TABLES);

  assert.equal(model.edges.length, 0);
  assert.equal(model.state, "empty");
  assert.deepEqual(model.notes, ["No dependencies"]);
});

test("names and paths are data: a hostile value survives verbatim as text", () => {
  const hostile = '<img src=x onerror=alert(1)>"/q';
  const rows = [ROWS[0], { nodeId: "n1", family: "template", name: hostile, version: "1", status: "RELEASED", kind: "released" }];
  const model = planView.fromRows(rows, [{ from: "n0", to: "n1", path: hostile }], TABLES);

  assert.equal(model.nodes[1].name, hostile);
  assert.equal(model.edges[0].label, hostile);
});

test("fromReleaseRows reads only the plan rows and the hidden edge list - never a prerequisite, never a request", () => {
  const root = dialogRoot(ROWS, EDGES);

  const model = planView.fromReleaseRows(root);

  assert.equal(model.nodes.length, 6);
  assert.equal(model.edges.length, 6);
  // Only node rows (data-node-id) are read: the prerequisite row carries none, so it can never become a node.
  assert.deepEqual(root.asked, ["[data-release-row][data-node-id]", "[data-release-edges] > li"]);
});

test("fromReleaseRows refuses a missing or empty plan, so the mount shows its failure state", () => {
  assert.throws(() => planView.fromReleaseRows(null), /No release plan/);
  assert.throws(() => planView.fromReleaseRows(dialogRoot([], [])), /No release plan/);
});
