// #336 D8 — the editor's Cancel is honest about its outcome.
//
// The defect: `.catch(function () {})` on the cancel DELETE and no `response.ok`
// check — a refused or failed cancel armed the 5 s abort fallback anyway and the
// run could be presented as cancelled when nothing of the sort happened. Now:
// an ACCEPTED cancel arms the fallback (the no-terminal-event case only); a 409
// is §15.2's documented quiet case (the terminal state is already rendering);
// any other refusal or a network failure toasts a fixed (or the server's
// catalogued) message and leaves the stream open.
//
// Red on the pre-336 sse.js by construction: the timer armed on every ending,
// and no toast ever fired.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");

/**
 * Loads sse.js fresh, with the window/document it binds to and the witnesses it
 * must reach: `toasts` records every DpToast.show, `timers` every setTimeout
 * (the abort fallback is the 5 s one), and the replaced setTimeout never FIRES —
 * the timer's effect (aborting the reader) is the fallback's own behaviour, out
 * of scope for an outcome test.
 */
function loadSse(toasts) {
  globalThis.window = {
    DpToast: {
      show: (variant, title, message) => toasts.push({ variant, title, message }),
    },
  };
  globalThis.document = {
    readyState: "complete",
    cookie: "dp_csrf=token123",
    addEventListener: function () {},
    getElementById: () => null,
  };
  delete require.cache[require.resolve(ssePath)];
  require(ssePath);
  return globalThis.window.SseHandler;
}

/** The timer witness, installed for the test's duration and restored after. */
function withTimers(timers, block) {
  const realSetTimeout = globalThis.setTimeout;
  globalThis.setTimeout = (fn, ms) => {
    timers.push(ms);
    const t = realSetTimeout(() => {}, 10_000_000); // never fires: the fallback's effect is out of scope here
    if (t.unref) t.unref(); // never-firing: must not hold the test runner's event loop open
    return t;
  };
  return Promise.resolve()
    .then(block)
    .finally(() => {
      globalThis.setTimeout = realSetTimeout;
    });
}

function handlerWith(
  Handler,
  fetchBehavior,
) {
  const aborts = [];
  globalThis.fetch = fetchBehavior;
  const editor = { showError: () => {}, setBanner: () => {}, pipeline: { name: "p" }, isExecuting: true };
  const handler = new Handler(editor);
  handler.executionId = "exec-1";
  handler.abortController = { abort: () => aborts.push(true) };
  handler.terminalSeen = false;
  handler.isConnected = true;
  return { handler, aborts };
}

async function settle() {
  await new Promise((r) => setImmediate(r));
  await new Promise((r) => setImmediate(r));
}

test("an accepted cancel arms the 5 s fallback and toasts nothing", async () => {
  const toasts = [];
  const timers = [];
  const Handler = loadSse(toasts, timers);
  const { handler, aborts } = handlerWith(Handler, () => Promise.resolve({ ok: true, status: 204, json: async () => ({}) }));

  await withTimers(timers, async () => {
    handler.cancel();
    await settle();
  });

  assert.deepEqual(toasts, []);
  assert.deepEqual(timers, [5000]);
  assert.deepEqual(aborts, []); // the timer's EFFECT (abort when no terminal event) is the fallback's own
});

test("a refused cancel toasts the server's catalogued message and never arms the fallback", async () => {
  const toasts = [];
  const timers = [];
  const Handler = loadSse(toasts, timers);
  const { handler, aborts } = handlerWith(
    Handler,
    () =>
      Promise.resolve({
        ok: false,
        status: 500,
        json: async () => ({ error: { code: "internal", message: "the store refused" } }),
      }),
  );

  handler.cancel();
  await settle();

  assert.equal(toasts.length, 1);
  assert.equal(toasts[0].variant, "danger");
  assert.equal(toasts[0].message, "the store refused"); // the catalogued message
  assert.deepEqual(timers, []); // nothing was cancelled — no abort fallback
  assert.deepEqual(aborts, []);
});

test("a 409 is the documented quiet case - the terminal state is already rendering", async () => {
  const toasts = [];
  const timers = [];
  const Handler = loadSse(toasts, timers);
  const { handler } = handlerWith(Handler, () => Promise.resolve({ ok: false, status: 409, json: async () => ({}) }));

  handler.cancel();
  await settle();

  assert.deepEqual(toasts, []);
  assert.deepEqual(timers, []);
});

test("a network failure toasts and never arms the fallback", async () => {
  const toasts = [];
  const timers = [];
  const Handler = loadSse(toasts, timers);
  const { handler } = handlerWith(Handler, () => Promise.reject(new TypeError("fetch failed")));

  handler.cancel();
  await settle();

  assert.equal(toasts.length, 1);
  assert.equal(toasts[0].variant, "danger");
  assert.deepEqual(timers, []);
});
