// #349 — the view/run ownership gate at the unit level (spec §4.2/§4.3, A9): the run's
// facts attach to the view only when BOTH pipeline and version match; the stream is
// execution-owned and never gates. The real modules — init.js's component and sse.js's
// handler — drive every rule, so the test and the page cannot drift.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const main = (rel) => path.resolve(here, "../../main/resources/static/js/pipeline-editor/" + rel);

function freshDoc() {
  globalThis.window = {};
  globalThis.document = {
    readyState: "complete",
    addEventListener: function () {},
    removeEventListener: function () {},
    body: { addEventListener: function () {}, removeEventListener: function () {} },
    cookie: "",
    getElementById: function () {
      return null;
    },
    querySelector: function () {
      return null;
    },
    querySelectorAll: function () {
      return [];
    },
  };
  globalThis.window.PEDock = { createDock: () => ({ selectNode: () => {}, clearSelection: () => {}, errors: [] }) };
  globalThis.window.PEEvents = { createEventsLog: () => ({ count: () => 0, append: () => ({}), reset: () => {} }) };
  globalThis.ResultPanel = class {};
  globalThis.PipelineGraph = class {
    constructor() {
      this.cy = null;
    }
    render() {}
  };
  globalThis.setupA11y = () => {};
  globalThis.coerceValue = (v) => v;
  globalThis.executePipeline = () => {};
  const realSetInterval = globalThis.setInterval;
  globalThis.setInterval = () => 0;
  for (const rel of ["workspace.js", "dock.js", "events.js", "node-ops.js", "param-fields.js", "tabs.js", "init.js", "execute.js", "sse.js"]) {
    delete require.cache[require.resolve(main(rel))];
    require(main(rel));
  }
  globalThis.setInterval = realSetInterval;
}

/** A component pinned to viewing `viewedVersion`, carrying a run pinned to `runVersion`. */
function loaded({ viewedVersion = 2, runVersion = 1, pipelineId = "p1" } = {}) {
  freshDoc();
  globalThis.window.PEWorkspace = { pipelineId, viewedVersion, hasBody: true, canExecute: true };
  const editor = globalThis.window.pipelineEditor();
  editor.pipeline = { id: pipelineId, name: "p", nodes: [], parameters: {}, settings: {} };
  editor.nodes = [];
  editor.nodesById = {};
  editor.parameters = {};
  editor.paramKeys = [];
  editor.nodeStates = {};
  editor.nodeValues = {};
  editor.childExecutions = {};
  editor.contextValues = {};
  editor.canExecute = true;
  editor.executionVersion = runVersion;
  editor.runIdentity = { executionId: "e1", version: runVersion, status: "running", parameters: null, attached: true };
  editor.viewedVersionOrNull = () => viewedVersion;
  return editor;
}

const CALC = {
  id: "q",
  type: "CALCULATOR",
  kind: "fiscal_quarter",
  context_key: "run_q",
  inputs: { date: "$current_date" },
};

test("the run's resolved values attach when the run's version IS the viewed one", () => {
  const editor = loaded({ viewedVersion: 2, runVersion: 2 });
  editor.contextValues = { current_date: "2026-09-30" };
  const inputs = editor.calculatorInputs(Calc())[0];
  assert.equal(inputs.resolved, "2026-09-30", "the run's Context resolves the $reference on its own version");
});

function Calc() {
  return { ...CALC };
}

test("browsing another version does NOT wear the run's facts — the reference stays unresolved", () => {
  const editor = loaded({ viewedVersion: 2, runVersion: 1 });
  editor.contextValues = { current_date: "2026-09-30" };
  const inputs = editor.calculatorInputs(Calc())[0];
  assert.equal(inputs.resolved, null, "a v1 run's context never resolves on the v2 body (A9)");
  assert.equal(editor.calculatorValue(Calc()), null);
});

test("node states are recorded regardless; the Last run row attaches only on the match", () => {
  const editor = loaded({ viewedVersion: 2, runVersion: 1 });
  editor.nodeStates = { q: "success" };
  const node = { id: "q", type: "DQL", source: "tempdb" };
  const map = Object.fromEntries(editor.detailsMeta(node).map((r) => [r.k, r.v]));
  assert.equal(map["Last run"], undefined, "no v1 state on the v2 node");

  const matching = loaded({ viewedVersion: 1, runVersion: 1 });
  matching.nodeStates = { q: "success" };
  const mapOnMatch = Object.fromEntries(matching.detailsMeta(node).map((r) => [r.k, r.v]));
  assert.equal(mapOnMatch["Last run"], "success", "the run's own version reads its state");
});

test("sse paintsGraph: the run paints its own version, never another", () => {
  const editor = loaded({ viewedVersion: 2, runVersion: 1 });
  const handler = new globalThis.window.SseHandler(editor);
  handler.version = 1;
  assert.equal(handler.paintsGraph(), false, "a v1 run does not paint the v2 graph");

  const onVersion = loaded({ viewedVersion: 1, runVersion: 1 });
  const own = new globalThis.window.SseHandler(onVersion);
  own.version = 1;
  assert.equal(own.paintsGraph(), true, "the run's own view paints");
});

test("sse paintsGraph: a handler without a version keeps the pre-349 behavior (always paints)", () => {
  const editor = loaded({ viewedVersion: 2, runVersion: null });
  const handler = new globalThis.window.SseHandler(editor);
  assert.equal(handler.paintsGraph(), true);
});

test("the identity strip says the truth across a version switch, and offers the way back", () => {
  const editor = loaded({ viewedVersion: 2, runVersion: 1 });
  assert.equal(editor.runStripVersionText, "v1", "the strip names the RUN's version");
  assert.equal(editor.runVersionDiffers, true, "View run's version is offered");
  assert.equal(editor.runMatchesViewed(), false);

  const same = loaded({ viewedVersion: 1, runVersion: 1 });
  assert.equal(same.runVersionDiffers, false);
});

test("handleExecutionIdentity arms the strip from the wire payload, handler pin as fallback", () => {
  const editor = loaded({ viewedVersion: 2, runVersion: null });
  editor.executionVersion = null;
  editor.runIdentity = null;
  // The strip names the EFFECTIVE SUBMITTED parameters — the pipeline's declared keys —
  // never the resolved Context's platform tiers that ride the same payload.
  editor.paramKeys = ["day"];
  editor.handleExecutionIdentity(
    { execution_id: "e9", pipeline_version: 3, parameters: { day: "mon", org_timezone: "UTC" } },
    null,
  );
  assert.equal(editor.executionVersion, 3);
  assert.equal(editor.runIdentity.version, 3);
  assert.equal(editor.runStripParamsText, "day=mon", "declared parameters only — no context tiers");

  editor.handleExecutionIdentity({ execution_id: "e10" }, 2);
  assert.equal(editor.executionVersion, 2, "a payload without the version falls back to the pinned one");
});

test("the live-run record the page adopts at ANOTHER version paints nothing but shows the run", () => {
  const editor = loaded({ viewedVersion: 2, runVersion: null });
  editor.executionVersion = null;
  editor.runIdentity = null;
  editor.adoptLiveRunRecord({ executionId: "e5", version: 1 });
  assert.equal(editor.executionVersion, 1);
  assert.equal(editor.runIdentity.attached, false, "no stream attach — no paint");
  assert.equal(editor.runMatchesViewed(), false);
  assert.equal(editor.runVersionDiffers, true);
});
