// #383 — the observed evaluation's node-state machine: `PSModel.applyFrame`/`reduceFrames` (pure frames → states),
// the D3 rule (`evaluation_failed` fails every parameter without a terminal state, the set's complement included),
// the terminal response's reset names, and the inspector's stream-failure row. The model is required directly —
// no DOM, no network (the sibling `parameter-workspace-model.test.mjs` mould).

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const model = require(path.resolve(here, "../../main/resources/static/js/parameter-workspace/model.js"));

const wait = (name, parents) => ({ event: "parameter_waiting", name, waiting_on: parents || [] });
const admitted = (name) => ({ event: "parameter_admitted", name });
const running = (name) => ({ event: "parameter_running", name });
const resolved = (name) => ({ event: "parameter_resolved", name, origin: "source", reset: false });
const failed = (name, code, detail) => ({ event: "parameter_failed", name, code, detail });

test("scenario 3's full cascade order reduces to every parameter resolved", () => {
  // country changed → state waits on country, city waits on state; state's frames run first, then city's.
  const frames = [
    { event: "evaluation_started" },
    wait("state", ["country"]),
    wait("city", ["state"]),
    admitted("state"),
    running("state"),
    resolved("state"),
    admitted("city"),
    running("city"),
    resolved("city"),
    { event: "evaluation_completed" },
  ];
  assert.deepEqual(model.reduceFrames(frames), { state: "resolved", city: "resolved" });
});

test("each parameter walks its own forward path; a backwards frame changes nothing", () => {
  const states = model.reduceFrames([wait("city", ["state"]), admitted("city"), running("city")]);
  assert.deepEqual(states, { city: "running" });
  // The server never rewinds a parameter: a waiting frame after running is ignored.
  assert.deepEqual(model.applyFrame(states, wait("city")), { city: "running" });
  // A terminal state is sticky within one evaluation.
  const done = model.reduceFrames([running("city"), resolved("city")]);
  assert.deepEqual(model.applyFrame(done, running("city")), { city: "resolved" });
});

test("D3: evaluation_failed fails every parameter without a terminal state — and the set's untouched names too", () => {
  const states = model.reduceFrames([
    running("state"),
    resolved("state"),
    running("city"),
    { event: "evaluation_failed", code: "parameter.evaluate.timeout" },
  ]);
  assert.deepEqual(states, { state: "resolved", city: "failed" });
  // The complement: `country` never emitted a frame (cancelled before its first) and still counts as unfinished.
  assert.deepEqual(model.unfinishedNames(["country", "state", "city"], states), ["country"]);
  // A per-parameter failure is terminal for that parameter alone; the D3 sweep keeps it failed.
  const withFailure = model.reduceFrames([running("city"), failed("city", "parameter.evaluate.selectors_saturated", "saturated"), { event: "evaluation_failed", code: "x" }]);
  assert.deepEqual(withFailure, { city: "failed" });
});

test("the machine ignores unknown events, nameless frames and never mutates its input", () => {
  const states = { city: "running" };
  const frozen = { ...states };
  assert.deepEqual(model.applyFrame(states, { event: "evaluation_started" }), states);
  assert.deepEqual(model.applyFrame(states, { event: "parameter_running" }), states);
  assert.deepEqual(model.applyFrame(states, { event: "parameter_resolved", name: "" }), states);
  assert.deepEqual(model.applyFrame(states, null), states);
  const out = model.applyFrame(states, resolved("city"));
  assert.deepEqual(states, frozen, "the input map is never mutated");
  assert.deepEqual(out, { city: "resolved" });
});

test("resetNames reads the terminal response's per-parameter reset marks", () => {
  const response = {
    parameters: [
      { name: "country", state: { reset: false } },
      { name: "state", state: { reset: true } },
      { name: "city", state: { reset: true } },
      { name: "ghost" },
    ],
  };
  assert.deepEqual(model.resetNames(response), ["state", "city"]);
  assert.deepEqual(model.resetNames(null), []);
  assert.deepEqual(model.resetNames({}), []);
});

test("the inspector carries a failed parameter's stream code and detail verbatim, as text rows", () => {
  const parameter = { name: "city", label: "City", type: "STRING", kind: "SELECT", cardinality: "SINGLE" };
  const rows = model.inspect(parameter, { parameters: [parameter] }, null, {
    code: "parameter.evaluate.selectors_saturated",
    detail: "saturated",
  });
  const stream = rows.find((row) => row.label === "Evaluation stream");
  assert.ok(stream, "the stream outcome row exists");
  assert.equal(stream.kind, "code");
  assert.equal(stream.value, "failed: parameter.evaluate.selectors_saturated — saturated");
  const withoutDetail = model.inspect(parameter, { parameters: [parameter] }, null, { code: "parameter.evaluate.timeout" });
  assert.equal(withoutDetail.find((row) => row.label === "Evaluation stream").value, "failed: parameter.evaluate.timeout");
  assert.equal(
    model.inspect(parameter, { parameters: [parameter] }, null, null).some((row) => row.label === "Evaluation stream"),
    false,
    "no stream row without a stream failure",
  );
});
