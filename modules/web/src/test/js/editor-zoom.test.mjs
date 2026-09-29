// 301 (#305) — the graph zoom steps are named constants (ZOOM_IN_STEP / ZOOM_OUT_STEP in
// init.js), not bare literals in the component's methods: a ×1.25 in and its exact
// reciprocal out, so in-then-out returns the view and the two buttons move together.
// The editor's other suites never call zoomIn/zoomOut, so the constants had no red state;
// this file is it. Same loader convention as details-pane.test.mjs.
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
  getElementById: function () {
    return null;
  },
  querySelectorAll: function () {
    return [];
  },
};
// The run clock's interval must not pin the test process open.
const realSetInterval = globalThis.setInterval;
globalThis.setInterval = () => 0;

require(main("dock.js"));
require(main("events.js"));
require(main("graph.js"));
require(main("init.js"));
const editor = globalThis.window.pipelineEditor();
globalThis.setInterval = realSetInterval;

function graphRecording() {
  const calls = [];
  return { calls, zoomBy: (f) => calls.push(f) };
}

test("the zoom buttons step by the named constants — 1.25 in, its reciprocal out", () => {
  const graph = graphRecording();
  const self = { graph };
  editor.zoomIn.call(self);
  editor.zoomOut.call(self);
  assert.deepEqual(graph.calls, [1.25, 0.8]);
});

test("zoom in then out returns the view: the steps are exact reciprocals", () => {
  const graph = graphRecording();
  const self = { graph };
  editor.zoomIn.call(self);
  editor.zoomOut.call(self);
  assert.ok(Math.abs(graph.calls[0] * graph.calls[1] - 1) < 1e-12, `${graph.calls[0]} × ${graph.calls[1]} != 1`);
});

test("a missing graph stays quiet (the old guard, kept)", () => {
  assert.doesNotThrow(() => editor.zoomIn.call({ graph: null }));
  assert.doesNotThrow(() => editor.zoomOut.call({}));
});
