// 027b A — automated coverage for the SSE frame parser in sse.js.
//
// There is no JS test harness in this repo; these run on Node's BUILT-IN
// runner (`node --test`, Node >= 18 — no packages, no config), wired into
// Gradle by modules/web's `editorJsTest` task. sse.js is a browser IIFE that
// publishes on `window`, so each test shims `globalThis.window` and requires
// the file directly; only the frame parser (readStream's line loop) is a pure
// function over a byte stream, which is exactly what these drive — no DOM is
// touched before dispatch().
//
// The load-bearing cases are the CHUNK-BOUNDARY splits: `data_ready` carries
// the inline first page (page-size-rows rows) as ONE `data:` line — tens of
// rows already exceed Tomcat's default 8KB response buffer — so a real result
// frame spans many network reads. Measured live (027b before-evidence): a
// 1000-row data_ready frame is 25,598 bytes delivered across 6+ chunks.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");

function loadSseHandler() {
  globalThis.window = {};
  delete require.cache[require.resolve(ssePath)];
  require(ssePath);
  return globalThis.window.SseHandler;
}

function fakeEditor() {
  return {
    isExecuting: false,
    nodeStates: {},
    graph: null,
    setBanner() {},
    showError() {},
    announceStatus() {},
    handleDataReady: null,
  };
}

/** A reader that serves `wire` split into fixed-size chunks (the last short). */
function chunkedReader(wire, chunkSize) {
  const bytes = new TextEncoder().encode(wire);
  let i = 0;
  const reader = {
    exhausted: false,
    read() {
      if (i >= bytes.length) {
        reader.exhausted = true;
        return Promise.resolve({ done: true, value: undefined });
      }
      const slice = bytes.slice(i, i + chunkSize);
      i += chunkSize;
      return Promise.resolve({ done: false, value: slice });
    },
  };
  return reader;
}

/** A reader that serves `wire` as chunks at explicit byte offsets [a, b, ...]. */
function splitReader(wire, splits) {
  const bytes = new TextEncoder().encode(wire);
  const points = [0, ...splits, bytes.length];
  let i = 0;
  const reader = {
    exhausted: false,
    read() {
      if (i >= points.length - 1) {
        reader.exhausted = true;
        return Promise.resolve({ done: true, value: undefined });
      }
      const slice = bytes.slice(points[i], points[i + 1]);
      i += 1;
      return Promise.resolve({ done: false, value: slice });
    },
  };
  return reader;
}

async function collectDispatches(reader) {
  const SseHandler = loadSseHandler();
  const handler = new SseHandler(fakeEditor());
  const events = [];
  handler.dispatch = (type, data) => events.push({ type, data });
  handler.readStream({ body: { getReader: () => reader } });
  // readStream is fire-and-forget promise chaining. `exhausted` flips when the
  // final read() is SERVED; one more macrotask lets the done branch run first.
  for (let i = 0; i < 1000 && !reader.exhausted; i++) {
    await new Promise((r) => setImmediate(r));
  }
  assert.equal(reader.exhausted, true, "stream did not drain within the poll budget");
  await new Promise((r) => setImmediate(r));
  return events;
}

// The wire shape the app's emitter writes (Spring SseEmitter): id, event, data,
// blank-line terminator, CRLF-free.
function frame(name, data) {
  return `id:1\nevent:${name}\ndata:${data}\n\n`;
}

test("a stream delivered as one chunk dispatches every frame exactly once, in order", async () => {
  const wire = frame("execution_started", '{"execution_id":"e1"}') + frame("node_started", '{"node_id":"n"}') + frame("pipeline_completed", "{}");
  const events = await collectDispatches(chunkedReader(wire, 4096));
  assert.deepEqual(
    events.map((e) => e.type),
    ["execution_started", "node_started", "pipeline_completed"],
  );
  assert.equal(events[0].data, '{"execution_id":"e1"}');
});

test("THE bug: a frame split at a chunk boundary still dispatches, at EVERY split point", async () => {
  const wire = frame("execution_started", '{"execution_id":"e1"}') + frame("pipeline_completed", "{}");
  for (let split = 1; split < wire.length; split++) {
    const events = await collectDispatches(splitReader(wire, [split]));
    assert.deepEqual(
      events.map((e) => e.type),
      ["execution_started", "pipeline_completed"],
      `frame state was lost at chunk boundary ${split}`,
    );
    assert.equal(events[0].data, '{"execution_id":"e1"}', `data corrupted at split ${split}`);
  }
});

test("a data line arriving across THREE chunks dispatches once with the complete payload", async () => {
  const wire = frame("data_ready", '{"row_count":2}');
  // event line + head of data | middle of data | tail of data + terminator
  const events = await collectDispatches(splitReader(wire, [30, 42]));
  assert.equal(events.length, 1);
  assert.equal(events[0].type, "data_ready");
  assert.equal(events[0].data, '{"row_count":2}');
});

test("a data_ready frame larger than the 8KB Tomcat buffer arrives intact", async () => {
  const rows = Array.from({ length: 1000 }, (_, i) => [i + 1, `row-${i + 1}`, (i + 1) * 1.5]);
  const payload = JSON.stringify({ execution_id: "e1", schema: [], rows, row_count: rows.length, total_rows: 2500, has_more: true });
  assert.ok(payload.length > 8192, "fixture must exceed the response buffer to model the real stream");
  const wire = frame("pipeline_completed", "{}") + frame("data_ready", payload);
  // ~8KB network chunks model Tomcat's default buffer — the measured live run
  // split a 25.5KB frame across chunks of 2890/5296/4336/1448/1448/10155 bytes.
  const events = await collectDispatches(chunkedReader(wire, 8192));
  assert.deepEqual(
    events.map((e) => e.type),
    ["pipeline_completed", "data_ready"],
  );
  const parsed = JSON.parse(events[1].data);
  assert.equal(parsed.row_count, 1000);
  assert.equal(parsed.rows.length, 1000);
  assert.deepEqual(parsed.rows[999], [1000, "row-1000", 1500]);
});

test("a frame with EMPTY data is legitimate and dispatches", async () => {
  const wire = "event:data_ready\ndata:\n\n";
  const events = await collectDispatches(chunkedReader(wire, 4096));
  assert.equal(events.length, 1);
  assert.equal(events[0].type, "data_ready");
  assert.equal(events[0].data, "");
});

test("a frame never terminated by a blank line is NOT dispatched — only the terminator completes a frame", async () => {
  // WHATWG SSE: dispatch fires on the blank line; a stream that ends mid-frame
  // discards it. The server (Spring SseEmitter) always writes \n\n, so no real
  // frame exercises this — but it pins WHY the end-of-buffer dispatch was
  // deleted: it fired on exactly this incomplete state.
  const wire = "event:data_ready\ndata:{\"row_count\":1}";
  const events = await collectDispatches(chunkedReader(wire, 4096));
  assert.equal(events.length, 0);
});
