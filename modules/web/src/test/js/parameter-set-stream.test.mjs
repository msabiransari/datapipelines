// #383 — the parameters-only entry as the OBSERVED evaluation's client (rest-api §21.5): the stream POST to
// `/api/v1/parameter-sets/{id}/evaluations` carrying `{version, selections, evaluation_id, instance_id}` and the
// session's CSRF header, the supersede that CLOSES the prior attempt's stream, the id check that drops every
// frame of another attempt, the terminal frames' two fates, the §6.6 path for a stream that ends without a
// terminal frame, and `: revoked`. Same harness decision as the sibling tests: no packages; a hand-rolled fake
// DOM and a scripted fetch installed before the IIFE is required. The mould is `sse-*.test.mjs` +
// `dashboard-runtime.test.mjs`'s fake stream Response.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const runtimePath = path.resolve(here, "../../main/resources/static/js/datapipelines-dashboard.js");

const V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function fakeElement(tag) {
  const listeners = {};
  const attrs = {};
  const children = [];
  const el = {
    tagName: (tag || "div").toUpperCase(),
    attrs,
    children,
    listeners,
    style: {},
    className: "",
    textContent: "",
    value: "",
    checked: false,
    parentNode: null,
    firstChild: null,
    addEventListener(type, fn) {
      (listeners[type] ||= []).push(fn);
    },
    setAttribute(k, v) {
      attrs[k] = String(v);
    },
    getAttribute(k) {
      return k in attrs ? attrs[k] : null;
    },
    removeAttribute(k) {
      delete attrs[k];
    },
    appendChild(child) {
      child.parentNode = el;
      children.push(child);
      el.firstChild = children[0] || null;
      return child;
    },
    insertBefore(child, before) {
      child.parentNode = el;
      const index = children.indexOf(before);
      if (index === -1) children.push(child);
      else children.splice(index, 0, child);
      el.firstChild = children[0] || null;
      return child;
    },
    removeChild(child) {
      const index = children.indexOf(child);
      if (index !== -1) children.splice(index, 1);
      child.parentNode = null;
      el.firstChild = children[0] || null;
      return child;
    },
    querySelector() {
      return null;
    },
    querySelectorAll() {
      return [];
    },
  };
  return el;
}

function installDom(cookie) {
  const doc = {
    body: fakeElement("body"),
    head: fakeElement("head"),
    cookie: cookie || "",
    getElementById: () => null,
    querySelectorAll: () => [],
    createElement: (tag) => fakeElement(tag),
    createTextNode: (text) => ({ text: String(text), nodeType: 3 }),
  };
  globalThis.document = doc;
  globalThis.window = { document: doc };
  return doc;
}

let live = null;
function uninstallDom() {
  if (live) {
    try {
      live.dispose();
    } catch (e) {
      /* already disposed */
    }
    live = null;
  }
  delete globalThis.document;
  delete globalThis.window;
  delete globalThis.fetch;
}

/** A recording adapter: renders are counted and the form's "controls" are a settable selection map. */
function recordingAdapter() {
  const adapter = {
    rendered: [],
    selections: {},
    notifications: [],
    hostNotifications: [],
    listeners: {},
    mountLayout: () => Promise.resolve(),
    mountVisualization: () => Promise.resolve({ renderData: () => Promise.resolve("rendered") }),
    renderParameters(state) {
      adapter.rendered.push(state);
      return Promise.resolve();
    },
    readSelections: () => ({ ...adapter.selections }),
    onEdit: (cb) => (adapter.listeners.edit = cb),
    onCommit: (cb) => (adapter.listeners.commit = cb),
    onAction: () => {},
    renderData: () => Promise.resolve("rendered"),
    renderStatus: () => {},
    notify: (n) => adapter.notifications.push(n),
    resize: () => {},
    dispose: () => {},
  };
  return adapter;
}

function evaluated(values, extra) {
  return {
    id: "ps-1",
    name: "geo/filters",
    version: 2,
    valid: true,
    values,
    parameters: Object.keys(values).map((name) => ({
      name,
      label: name,
      type: "STRING",
      kind: "SELECT",
      cardinality: "SINGLE",
      dependents: [],
      state: { value: values[name], origin: "default", reset: false, hidden: false, disabled: false, options: [], errors: [] },
    })),
    ...(extra || {}),
  };
}

const encoder = new TextEncoder();
const frame = (event, payload, id) => `event: ${event}\nid: ${id}\ndata: ${JSON.stringify(payload)}\n\n`;
const comment = (text) => `:${text}\n\n`;

/** A stream Response whose chunks are delivered in order; `closed` observes a reader cancel. */
function streamResponse(chunks, seen) {
  return {
    ok: true,
    status: 200,
    text: async () => "",
    body: {
      getReader() {
        let index = 0;
        return {
          read: async () => {
            if (index < chunks.length) {
              const value = chunks[index];
              index += 1;
              return { done: false, value: encoder.encode(value) };
            }
            return { done: true, value: undefined };
          },
          cancel: async () => {
            if (seen) seen.closed = true;
          },
        };
      },
    },
  };
}

/** A fetch whose every answer is held until the test releases it, so ordering is the test's. */
function heldFetch() {
  const calls = [];
  const impl = (url, init) =>
    new Promise((resolve) => {
      calls.push({ url, init, body: init.body ? JSON.parse(init.body) : null, resolve });
    });
  impl.calls = calls;
  impl.answer = (call, data, status) =>
    call.resolve({
      ok: (status || 200) < 400,
      status: status || 200,
      text: async () => JSON.stringify(status >= 400 ? data : { data }),
    });
  impl.answerStream = (call, chunks, seen) => call.resolve(streamResponse(chunks, seen));
  return impl;
}

/** The one success stream for a call: started, then the terminal frame carrying the response verbatim. */
function successStream(call, payload) {
  const id = call.body.evaluation_id;
  return [
    frame("evaluation_started", { evaluation_id: id, parameter_set_id: "ps-1", version: 2, deadline_at: "2026-10-02T22:00:00Z" }, "1"),
    frame("evaluation_completed", { evaluation_id: id, response: payload }, "2"),
  ];
}

function boot(options) {
  installDom(options && options.cookie);
  const fetchImpl = heldFetch();
  globalThis.fetch = fetchImpl;
  const runtime = require(runtimePath);
  const adapter = recordingAdapter();
  const streamEvents = [];
  const container = fakeElement("div");
  const instance = runtime.initParameters({
    server: { baseUrl: "", credentials: "session" },
    parameterSet: { id: "ps-1", version: 2 },
    container,
    adapter,
    options: {
      onNotification: (n) => adapter.hostNotifications.push(n),
      onStreamEvent: (info) => streamEvents.push(info),
      ...(options && options.lockSeconds ? { parameterLockSeconds: options.lockSeconds } : {}),
    },
  });
  live = instance;
  return { runtime, fetchImpl, adapter, container, instance, streamEvents };
}

/** Boots, answers the bootstrap stream, and leaves the instance ready. */
async function readyBoot(options) {
  const ctx = boot(options);
  await tick();
  ctx.fetchImpl.answerStream(ctx.fetchImpl.calls[0], successStream(ctx.fetchImpl.calls[0], evaluated({ country: "NL" })));
  await ctx.instance.ready;
  return ctx;
}

const tick = () => new Promise((resolve) => setImmediate(resolve));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

test("the observed evaluation's POST: the evaluations route, the SSE accept, the CSRF header, a v4 id fresh per attempt", async () => {
  const { fetchImpl, adapter, instance } = boot({ cookie: "dp_csrf=tok%2B1" });
  try {
    await tick();
    assert.equal(fetchImpl.calls.length, 1);
    const first = fetchImpl.calls[0];
    assert.equal(first.url, "/api/v1/parameter-sets/ps-1/evaluations");
    assert.equal(first.init.method, "POST");
    assert.equal(first.init.credentials, "same-origin");
    assert.equal(first.init.headers["Accept"], "text/event-stream");
    assert.equal(first.init.headers["Content-Type"], "application/json");
    assert.equal(first.init.headers["DP-CSRF-Token"], "tok+1");
    assert.equal(first.body.version, 2);
    assert.deepEqual(first.body.selections, {});
    assert.match(first.body.evaluation_id, V4, "evaluation_id is a canonical v4 UUID");
    assert.match(first.body.instance_id, V4, "instance_id is a canonical v4 UUID");
    fetchImpl.answerStream(first, successStream(first, evaluated({ country: "NL" })));
    await instance.ready;
    assert.equal(adapter.rendered.length, 1);

    // The second attempt mints a FRESH id — never a reuse of the first's.
    adapter.selections = { country: "DE" };
    adapter.listeners.commit({ name: "country", type: "parameter" });
    await tick();
    assert.equal(fetchImpl.calls.length, 2);
    const second = fetchImpl.calls[1];
    assert.equal(second.url, "/api/v1/parameter-sets/ps-1/evaluations");
    assert.equal(second.body.version, 2);
    assert.deepEqual(second.body.selections, { country: "DE" });
    assert.match(second.body.evaluation_id, V4);
    assert.notEqual(second.body.evaluation_id, first.body.evaluation_id);
    assert.equal(second.body.instance_id, first.body.instance_id);
  } finally {
    uninstallDom();
  }
});

test("the evaluation_id and instance_id never touch storage, the URL or the DOM — the body is their only ride", async () => {
  const { fetchImpl, instance } = await readyBoot();
  try {
    assert.equal(instance._init.container.getAttribute("data-evaluation-id"), null);
    assert.ok(!String(instance._init.container.getAttribute("data-dp-instance")).includes(instance._instanceId));
    assert.equal(globalThis.document.cookie.indexOf("evaluation"), -1);
    assert.ok(fetchImpl.calls[0].url.indexOf("evaluation_id") === -1);
  } finally {
    uninstallDom();
  }
});

test("a supersede closes the prior attempt's stream — the abort signal fires, the server's grace does the rest", async () => {
  const { fetchImpl, adapter, instance } = await readyBoot();
  try {
    adapter.selections = { country: "DE" };
    adapter.listeners.commit({ name: "country", type: "parameter" }); // attempt n
    await tick();
    const first = fetchImpl.calls[1];
    assert.equal(first.init.signal.aborted, false, "the in-flight attempt's stream is open");
    adapter.selections = { country: "FR" };
    adapter.listeners.commit({ name: "country", type: "parameter" }); // attempt n+1 supersedes n
    await tick();
    assert.equal(first.init.signal.aborted, true, "the supersede closed the prior attempt's stream");
    fetchImpl.answerStream(fetchImpl.calls[2], successStream(fetchImpl.calls[2], evaluated({ country: "FR" })));
    await tick();
    assert.equal(instance._parameters.values.country, "FR");
    assert.equal(adapter.rendered.length, 2);
  } finally {
    uninstallDom();
  }
});

test("a planted late evaluation_completed of the previous id changes nothing — the id check drops it before any state", async () => {
  const { fetchImpl, adapter, instance, streamEvents } = await readyBoot();
  try {
    const rendersBefore = adapter.rendered.length;
    adapter.selections = { country: "DE" };
    adapter.listeners.commit({ name: "country", type: "parameter" }); // attempt n (superseded below)
    await tick();
    const staleId = fetchImpl.calls[1].body.evaluation_id;
    adapter.selections = { country: "FR" };
    adapter.listeners.commit({ name: "country", type: "parameter" }); // attempt n+1, the current one
    await tick();
    const currentId = fetchImpl.calls[2].body.evaluation_id;
    // The hostile chunk: the PREVIOUS attempt's terminal frame, planted on the CURRENT stream ahead of the
    // current attempt's own terminal frame.
    fetchImpl.answerStream(fetchImpl.calls[2], [
      frame("evaluation_completed", { evaluation_id: staleId, response: evaluated({ country: "DE" }) }, "9"),
      frame("evaluation_completed", { evaluation_id: currentId, response: evaluated({ country: "FR" }) }, "10"),
    ]);
    await tick();
    const renders = adapter.rendered.slice(rendersBefore);
    assert.equal(renders.length, 1, "exactly the current attempt's response may render");
    assert.equal(renders[0].values.country, "FR");
    assert.equal(instance._parameters.values.country, "FR");
    const dropped = streamEvents.filter((e) => e.event === "evaluation_completed" && e.applied === false);
    assert.equal(dropped.length, 1, "the planted frame is logged as dropped, not silently eaten");
    assert.equal(dropped[0].evaluation_id, staleId);
  } finally {
    uninstallDom();
  }
});

test("evaluation_failed ends the attempt with the catalogued code", async () => {
  const { fetchImpl, adapter, instance } = await readyBoot();
  try {
    adapter.selections = { country: "DE" };
    adapter.listeners.commit({ name: "country", type: "parameter" });
    await tick();
    const id = fetchImpl.calls[1].body.evaluation_id;
    fetchImpl.answerStream(fetchImpl.calls[1], [
      frame("evaluation_started", { evaluation_id: id, parameter_set_id: "ps-1", version: 2, deadline_at: "2026-10-02T22:00:00Z" }, "1"),
      frame("evaluation_failed", { evaluation_id: id, code: "parameter.evaluate.timeout" }, "2"),
    ]);
    await tick();
    const failure = adapter.notifications.find((n) => n.severity === "error" && n.code === "parameter.evaluate.timeout");
    assert.ok(failure, "the catalogued code reached the host verbatim");
    assert.notEqual(instance._lock, null, "a failed attempt holds the lock until its deadline, as a refused plain evaluate does");
    assert.equal(adapter.rendered.length, 1, "no render for a failed attempt");
  } finally {
    uninstallDom();
  }
});

test("a stream that ends WITHOUT a terminal frame: content kept, the lock released by the deadline and never earlier", async () => {
  const { fetchImpl, adapter, instance } = await readyBoot({ lockSeconds: 0.2 });
  try {
    const rendersBefore = adapter.rendered.length;
    adapter.selections = { country: "DE" };
    adapter.listeners.commit({ name: "country", type: "parameter" });
    await tick();
    const id = fetchImpl.calls[1].body.evaluation_id;
    fetchImpl.answerStream(fetchImpl.calls[1], [
      frame("evaluation_started", { evaluation_id: id, parameter_set_id: "ps-1", version: 2, deadline_at: "2026-10-02T22:00:00Z" }, "1"),
      frame("parameter_running", { evaluation_id: id, parameter: "country", datasource: "d", template: { id: "t", version: 1 } }, "2"),
      // ...and the body just ends: no evaluation_completed, no evaluation_failed (§6.6's cut).
    ]);
    await tick();
    assert.equal(adapter.rendered.length, rendersBefore, "nothing rendered without a terminal frame");
    const disconnected = adapter.notifications.find((n) => n.code === "transport.disconnected");
    assert.ok(disconnected, "the transport-failure notice is published");
    assert.notEqual(instance._lock, null, "the lock is NOT released by the stream's end");
    const timeouts = adapter.notifications.filter((n) => n.code === "parameters.lock_timeout");
    assert.equal(timeouts.length, 0, "the deadline has not run yet — released by it, never earlier");
    await sleep(300);
    assert.equal(instance._lock, null, "the deadline released the lock");
    assert.equal(adapter.notifications.filter((n) => n.code === "parameters.lock_timeout").length, 1);
  } finally {
    uninstallDom();
  }
});

test(": revoked ends the read with the runtime's revoked notice and holds the lock for the deadline", async () => {
  const { fetchImpl, adapter, instance } = await readyBoot({ lockSeconds: 0.2 });
  try {
    adapter.selections = { country: "DE" };
    adapter.listeners.commit({ name: "country", type: "parameter" });
    await tick();
    const id = fetchImpl.calls[1].body.evaluation_id;
    fetchImpl.answerStream(fetchImpl.calls[1], [
      frame("evaluation_started", { evaluation_id: id, parameter_set_id: "ps-1", version: 2, deadline_at: "2026-10-02T22:00:00Z" }, "1"),
      comment(" revoked"),
    ]);
    await tick();
    const revoked = adapter.notifications.find((n) => n.code === "stream.revoked");
    assert.ok(revoked, "the revoked notice is published");
    assert.notEqual(instance._lock, null, "a revoked read does not release the lock");
    await sleep(300);
    assert.equal(instance._lock, null);
    assert.equal(adapter.notifications.filter((n) => n.code === "parameters.lock_timeout").length, 1);
  } finally {
    uninstallDom();
  }
});

test("the frame witness sees every frame — applied entries carry the name, drops carry applied: false", async () => {
  const { fetchImpl, streamEvents } = await readyBoot();
  try {
    const applied = streamEvents.filter((e) => e.applied);
    assert.ok(applied.length >= 2);
    assert.equal(applied[0].event, "evaluation_started");
    assert.equal(applied[0].evaluation_id, streamEvents[0].evaluation_id);
    const closed = streamEvents.find((e) => e.event === "stream_closed");
    assert.ok(closed, "the terminal close is announced");
    assert.equal(closed.reason, "terminal");
  } finally {
    uninstallDom();
  }
});

test("the bootstrap's failure still rejects ready with the server's code and message (the POST's refusal, not a frame)", async () => {
  const { fetchImpl, adapter, instance } = boot();
  try {
    await tick();
    fetchImpl.answer(fetchImpl.calls[0], { error: { code: "parameter_set.not_found", message: "no such parameter set version" } }, 404);
    await assert.rejects(
      instance.ready,
      (e) => e.code === "parameter_set.not_found" && e.message === "no such parameter set version",
    );
  } finally {
    uninstallDom();
  }
});

test("_internal exports the one parser; both revoked spellings yield the same comment", () => {
  installDom();
  try {
    const runtime = require(runtimePath);
    assert.equal(typeof runtime._internal.SseParser, "function", "SseParser is exported for the unit tests only");
    const comments = [];
    const frames = [];
    const parser = runtime._internal.SseParser(
      (event, payload) => frames.push([event, payload]),
      (text) => comments.push(text),
    );
    parser.push(":revoked\n\n");
    parser.push(": revoked\n\n");
    parser.push(': heartbeat\n\n');
    parser.push('event: parameter_running\ndata: {"evaluation_id":"x","parameter":"city"}\n\n');
    assert.deepEqual(comments, ["revoked", "revoked", "heartbeat"]);
    assert.deepEqual(frames, [["parameter_running", { evaluation_id: "x", parameter: "city" }]]);
  } finally {
    uninstallDom();
  }
});
