// 7d (#7, transform-nodes design §9.3) — the dock's Details tab for a TRANSFORM node
// (init.js): the meta rows, the definition text, the head label, the legend chip.
//
// details-pane.test.mjs's loader convention: the state literal builds the dock and events
// machines from their modules' globals, so those load first; graph.js publishes the pure
// helpers (PEGraphUtil) the pane reads.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const main = (rel) => path.resolve(here, "../../main/resources/static/js/pipeline-editor/" + rel);

globalThis.window = {};
globalThis.document = {
  readyState: "complete",
  addEventListener: function () {},
  cookie: "",
  getElementById: function () { return null; },
  querySelectorAll: function () { return []; },
};
const realSetInterval = globalThis.setInterval;
globalThis.setInterval = () => 0;

require(main("dock.js"));
require(main("events.js"));
require(main("graph.js"));
require(main("init.js"));
const editor = globalThis.window.pipelineEditor();
globalThis.setInterval = realSetInterval;

const ROW = {
  id: "shape_orders",
  type: "TRANSFORM",
  template: { id: "acme/shape/order_lines.jsonata", version: 3 },
  inputs: { orders: "stg_orders", tz: "$org_timezone" },
  output: { target: "tempdb", table: "order_lines", rejects: "order_lines_rejected" },
  strict: false,
};

function fresh() {
  editor.pipeline = { settings: {} };
  editor.nodeStates = {};
  editor.nodes = [ROW];
  editor.transformPins = {};
}

test("a TRANSFORM's Details before its pin resolves: the node's own facts, and 'resolving…' for the template's", () => {
  fresh();
  const map = Object.fromEntries(editor.detailsMeta(ROW));
  assert.equal(map.Template, "acme/shape/order_lines.jsonata @ v3");
  assert.equal(map.Language, "resolving…");
  assert.equal(map.Mode, "resolving…", "the mode is the contract's — never the node's (§3.1)");
  assert.equal(map.Inputs, "orders ← stg_orders · tz ← $org_timezone");
  assert.equal(map.Output, "tempdb.order_lines");
  assert.equal(map.Rejects, "tempdb.order_lines_rejected");
  assert.equal(map.Strict, "no");
  assert.equal(map["Query Timeout"], undefined, "a TRANSFORM runs no statement (SettingsRules' statement types)");
  assert.ok(map.Timeout, "the node deadline applies to every node type");
  assert.equal(map["Needs review"], undefined);
});

test("with the pin resolved: the language and the mode, and the needs-review row when flagged", () => {
  fresh();
  editor.transformPins["acme/shape/order_lines.jsonata@3"] = { language: "jsonata", mode: "row", rejects: true, needsReview: true };
  const map = Object.fromEntries(editor.detailsMeta(Object.assign({}, ROW, { strict: true })));
  assert.equal(map.Language, "jsonata");
  assert.equal(map.Mode, "row");
  assert.equal(map.Strict, "yes — any reject fails the node");
  assert.match(map["Needs review"], /retired/);
});

test("the definition pane: the node as a function call, escaped by construction", () => {
  fresh();
  editor.transformPins["acme/shape/order_lines.jsonata@3"] = { language: "jsonata", mode: "row", rejects: true, needsReview: false };
  const hostile = Object.assign({}, ROW, { inputs: { orders: "<script>alert(1)</script>" } });
  const html = editor.definitionHtml(hostile);
  assert.match(html, /-- TRANSFORM nodes have no SQL/);
  assert.match(html, /template: acme\/shape\/order_lines\.jsonata @ v3/);
  assert.match(html, /-- jsonata, row mode/);
  assert.match(html, /output: tempdb\.order_lines/);
  assert.match(html, /rejects: tempdb\.order_lines_rejected/);
  assert.equal(html.includes("<script>"), false);
  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.equal(editor.detailsSqlHead(ROW), "Definition (the pinned function, its inputs and output)");
  assert.equal(editor.isSqlNode(ROW), false, "no rendered-SQL fetch for a TRANSFORM");
});

test("the legend names the Transform type with its own token", () => {
  fresh();
  const chips = editor.legendChips();
  assert.deepEqual(chips, [{ label: "Transform", token: "--type-transform", type: "transform" }]);
});
