// #358 — a page that navigates away mid-run detaches WITHOUT cancelling (C):
//
//   1. dispose() stops every consumer: a recovery poll's late result, a stream
//      chunk, the cancel fallback timer — nothing arrives, nothing fires, and
//      disposal itself sends NO cancellation request (cancel is a verb with its
//      own permission; navigation is not it).
//   2. The restored page re-attaches through the §10.3 replay stream: the
//      terminal event in the log is dispatched EXACTLY once, the live-run record
//      clears with it, and a replay that ends before the terminal re-arms within
//      its budget instead of walking the live connection-loss path.
//
// Real sse.js in a vm context; fetch and the timers are the fixture's.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(
  new URL("../../main/resources/static/js/pipeline-editor/sse.js", import.meta.url),
  "utf8",
);
const tick = () => new Promise((resolve) => setImmediate(resolve));

const ENCODED = (frame) => new TextEncoder().encode(frame);
const frame = (event, data) => ENCODED(`event:${event}\ndata:${JSON.stringify(data ?? {})}\n\n`);

function sseBody(frames, readerState) {
  let i = 0;
  return {
    getReader() {
      return {
        read() {
          readerState.reads++;
          if (i < frames.length) return Promise.resolve({ done: false, value: frames[i++] });
          return Promise.resolve({ done: true });
        },
      };
    },
  };
}

function fixture() {
  const writes = [];
  const requests = [];
  const timers = new Map();
  const readerState = { reads: 0 };
  let respond;
  const win = { DpToast: { show: (...args) => writes.push(["toast", ...args]) } };
  const context = vm.createContext({
    window: win,
    document: { cookie: "dp_csrf=t" },
    AbortController,
    TextDecoder,
    TextEncoder,
    console,
    fetch: (url, options) => {
      requests.push({ url, options, method: (options && options.method) || "GET" });
      return new Promise((resolve) => {
        respond = (response) => resolve(response);
      });
    },
    setTimeout: (callback) => {
      const id = timers.size + 1;
      timers.set(id, callback);
      return id;
    },
    clearTimeout: (id) => timers.delete(id),
  });
  vm.runInContext(source, context);
  const editor = {
    isExecuting: true,
    setBanner: (...args) => writes.push(["banner", ...args]),
    announceStatus: (...args) => writes.push(["announce", ...args]),
    showError: (...args) => writes.push(["error", ...args]),
    stopRunClock: () => {},
  };
  const handler = new context.window.SseHandler(editor);
  handler.settlePolledOutcome = (outcome) => writes.push(["settled", outcome]);
  return {
    handler,
    editor,
    win,
    writes,
    requests,
    timers,
    readerState,
    respond: (response) => respond(response),
    json: (body) => ({ ok: true, json: async () => body }),
    fireTimers: async () => {
      const armed = [...timers.values()];
      timers.clear();
      armed.forEach((callback) => callback());
      await tick();
    },
  };
}

test("a recovery result arriving after page disposal changes nothing and never cancels the run", async () => {
  const f = fixture();
  f.handler.executionId = "execution-a";
  f.handler.pollExecution();
  f.handler.dispose();
  f.respond(f.json({ status: "ABORTED" }));
  await tick();
  assert.deepEqual(f.writes, [], "no late banner, sweep, announcement or toast");
  assert.equal(f.requests.length, 1, "disposing the page sends no cancellation request");
  assert.equal(f.requests[0].method, "GET", "the one request is the recovery poll, not a DELETE");
});

test("disposal clears an armed recovery timer and aborts the in-flight read", async () => {
  const f = fixture();
  f.handler.executionId = "execution-a";
  f.handler.pollExecution();
  f.respond(f.json({ status: "RUNNING" }));
  await tick();
  assert.equal(f.timers.size, 1, "the recovery timer really was armed");
  const signal = f.requests[0].options.signal;
  f.handler.dispose();
  assert.equal(f.timers.size, 0, "no recovery timer survives page disposal");
  assert.equal(signal.aborted, true, "the in-flight poll read is aborted");
});

test("disposal clears the cancel fallback timer — an aborted page never aborts a stream late", async () => {
  const f = fixture();
  f.handler.executionId = "execution-a";
  f.handler.cancel();
  f.respond({ ok: true, json: async () => ({}) });
  await tick();
  assert.equal(f.timers.size, 1, "the accepted cancel armed its 5s fallback");
  f.handler.dispose();
  assert.equal(f.timers.size, 0, "the fallback dies with the page");
  assert.equal(f.requests.filter((r) => r.method === "DELETE").length, 1, "a LIVE cancel still cancels");
});

test("a live recovery still reports its terminal outcome (positive control)", async () => {
  const f = fixture();
  f.handler.executionId = "execution-a";
  f.handler.pollExecution();
  f.respond(f.json({ status: "ABORTED" }));
  await tick();
  assert.equal(f.writes.filter((w) => w[0] === "settled").length, 1, "the live path settles");
});

test("the live-run record names the pipeline the run belongs to", async () => {
  const f = fixture();
  f.editor.pipeline = { id: "p-live" };
  f.handler.executionId = "e9";
  f.handler.dispatch("execution_started", JSON.stringify({ execution_id: "e9", parameters: {} }));
  // Field assertions: the record is built inside the vm context, so deepEqual's prototype check
  // would compare two Object realms, not the fields.
  assert.equal(f.win.__peLiveExecution.executionId, "e9");
  assert.equal(f.win.__peLiveExecution.pipelineId, "p-live");
});

test("a recovery poll that finds the run FINISHED clears the live-run record — no re-attach, no second toast later", async () => {
  // The streamed terminal clears window.__peLiveExecution (dispatch); the polled terminal
  // must too, or a boosted leave + restore re-attaches to a finished run and replays its
  // terminal event. Red with the clearing removed from the poll's terminal branch.
  for (const status of ["SUCCESS", "FAILED", "ABORTED"]) {
    const f = fixture();
    f.win.__peLiveExecution = { executionId: "execution-a" };
    f.handler.executionId = "execution-a";
    f.handler.pollExecution();
    f.respond(f.json({ status }));
    await tick();
    assert.equal(f.win.__peLiveExecution, null, `${status} found by the poll clears the record`);
    assert.equal(f.writes.filter((w) => w[0] === "settled").length, 1, `${status} still settles`);
  }
  const running = fixture();
  running.win.__peLiveExecution = { executionId: "execution-a" };
  running.handler.executionId = "execution-a";
  running.handler.pollExecution();
  running.respond(running.json({ status: "RUNNING" }));
  await tick();
  assert.deepEqual(running.win.__peLiveExecution, { executionId: "execution-a" }, "a RUNNING answer keeps the record");
});

test("a disposed handler drops a stream chunk that was already in flight", async () => {
  const f = fixture();
  f.handler.reattach("execution-live");
  await tick();
  // A read that is PENDING when the page goes away — the chunk arrives after dispose.
  let release;
  const chunk = new Promise((resolve) => {
    release = () => resolve({ done: false, value: frame("pipeline_completed", { execution_id: "execution-live" }) });
  });
  let served = false;
  f.respond({
    ok: true,
    body: {
      getReader() {
        return {
          read() {
            if (served) return Promise.resolve({ done: true });
            served = true;
            return chunk;
          },
        };
      },
    },
  });
  await tick();
  f.handler.dispose();
  release();
  await tick();
  await tick();
  assert.equal(
    f.writes.filter((w) => w[0] === "toast").length,
    0,
    "the terminal event of a disposed page is nobody's toast",
  );
  assert.equal(f.win.__peLiveExecution === undefined || f.win.__peLiveExecution === null, true);
});

test("reattach replays the log: the terminal arrives EXACTLY once and clears the live-run record", async () => {
  const f = fixture();
  f.handler.reattach("execution-live");
  await tick();
  assert.match(f.requests[0].url, /\/api\/v1\/executions\/execution-live\/events$/, "the §10.3 replay stream");
  f.respond({
    ok: true,
    body: sseBody(
      [
        frame("execution_started", { execution_id: "execution-live", parameters: {} }),
        frame("node_started", { execution_id: "execution-live", node_id: "a" }),
        frame("pipeline_completed", { execution_id: "execution-live" }),
      ],
      f.readerState,
    ),
  });
  await tick();
  await tick();
  const toasts = f.writes.filter((w) => w[0] === "toast");
  assert.equal(toasts.length, 1, "one replay, one terminal, ONE toast");
  assert.equal(f.editor.isExecuting, false);
  assert.equal(f.timers.size, 0, "no replay re-armed after the terminal");
  assert.equal(f.requests.filter((r) => r.method === "DELETE").length, 0, "re-attaching never cancels");
});

test("a replay that ends before the terminal re-arms; disposal stops the loop", async () => {
  const f = fixture();
  f.handler.reattach("execution-still-running");
  await tick();
  f.respond({ ok: true, body: sseBody([frame("node_started", { node_id: "a" })], f.readerState) });
  await tick();
  await tick();
  assert.equal(f.timers.size, 1, "a silent replay re-arms (the run is still going)");
  assert.deepEqual(
    f.writes.filter((w) => w[0] === "banner"),
    [],
    "the still-running run is not announced as connection loss",
  );
  f.handler.dispose();
  assert.equal(f.timers.size, 0, "the re-arm dies with the page");
});

test("the re-attach budget's end says what a lost connection says (and never cancels)", async () => {
  const f = fixture();
  f.handler.maxReplays = 2;
  f.handler.reattach("execution-forever");
  await tick();
  for (let i = 0; i <= f.handler.maxReplays; i++) {
    f.respond({ ok: true, body: sseBody([], f.readerState) });
    await tick();
    await tick();
    if (i < f.handler.maxReplays) await f.fireTimers();
  }
  assert.deepEqual(
    f.writes.filter((w) => w[0] === "banner").at(-1),
    ["banner", "Connection lost — refresh to check status", "connection-lost"],
  );
  assert.equal(f.requests.filter((r) => r.method === "DELETE").length, 0);
});

test("the live-run record: armed at execution_started, cleared at the terminal", () => {
  const f = fixture();
  f.handler.dispatch("execution_started", JSON.stringify({ execution_id: "e9", parameters: {} }));
  assert.equal(f.win.__peLiveExecution && f.win.__peLiveExecution.executionId, "e9", "a restored page follows this record");

  f.handler.dispatch("node_started", JSON.stringify({ execution_id: "e9", node_id: "a" }));
  assert.equal(f.win.__peLiveExecution && f.win.__peLiveExecution.executionId, "e9", "mid-run events leave the record alone");

  f.handler.dispatch("pipeline_completed", JSON.stringify({ execution_id: "e9" }));
  assert.equal(f.win.__peLiveExecution, null, "a finished run is never followed again");

  f.handler.dispatch("execution_started", JSON.stringify({ execution_id: "e10", parameters: {} }));
  f.handler.dispatch("execution_aborted", JSON.stringify({ execution_id: "e10" }));
  assert.equal(f.win.__peLiveExecution, null, "every terminal case clears the record");
});
