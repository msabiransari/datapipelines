// The dashboard client runtime's state machine (#10, the implementation spec's §10.2/§10.3; the
// design record's §5.3–§5.6) on Node's built-in runner — same harness decision as the editor
// tests: no packages, no config; the runtime is an IIFE publishing window.*, so the tests
// install hand-rolled fake window/document and a scripted global fetch before requiring it.
//
// The record's acceptance scenarios at unit level where they live in the runtime:
//   1  two instances, isolated (their own ownership, callbacks, DOM)
//   3  freshness — an old refresh finishing after a newer one cannot overwrite the new view
//   4  distinct failure states (no-data, error, abort) — never an unexplained spinner
//  11  bootstrap order config → placeholders → parameters → initial action
//  13  the lock — deadline, late responses, the gate
//  14  notifications — deduplicated, structured, with recovery intents
//  17  connection loss — retain content, report pending, offer Retry
//  18  abort — local invalidation immediate, ack not required for the state

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const runtimePath = path.resolve(here, "../../main/resources/static/js/datapipelines-dashboard.js");

// ---------------------------------------------------------------------------------------------- fake DOM

function fakeElement(tag) {
  const listeners = {};
  const attrs = {};
  const children = [];
  const el = {
    tagName: (tag || "div").toUpperCase(),
    attrs,
    children,
    style: {},
    className: "",
    textContent: "",
    parentNode: null,
    firstChild: null,
    addEventListener(type, fn) {
      (listeners[type] ||= []).push(fn);
    },
    fire(type, event) {
      (listeners[type] || []).forEach((fn) => fn.call(el, event || {}));
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
    remove() {
      if (el.parentNode) el.parentNode.removeChild(el);
    },
    querySelector(selector) {
      return el.querySelectorAll(selector)[0] || null;
    },
    querySelectorAll(selector) {
      const out = [];
      const classWanted = selector.startsWith(".") ? selector.slice(1) : null;
      const attrMatch = selector.startsWith("[") ? /^\[([a-z-]+)(?:=)?/.exec(selector) : null;
      const matches = (node) => {
        if (classWanted) return typeof node.className === "string" && node.className.split(/\s+/).includes(classWanted);
        if (attrMatch) return node.attrs && node.attrs[attrMatch[1]] !== undefined;
        return false;
      };
      const walk = (node) => {
        for (const child of node.children || []) {
          if (matches(child)) out.push(child);
          walk(child);
        }
      };
      walk(el);
      return out;
    },
  };
  return el;
}

function installDom(options) {
  const doc = {
    body: fakeElement("body"),
    head: fakeElement("head"),
    cookie: (options && options.cookie) || "",
  };
  const byId = {};
  doc.getElementById = (id) => byId[id] || null;
  doc.querySelectorAll = (selector) => {
    if (selector === "script[data-dp-plotly-bundle]") return (options && options.plotlyScripts) || [];
    return [];
  };
  doc.createElement = (tag) => {
    const el = fakeElement(tag);
    const originalSetAttribute = el.setAttribute;
    el.setAttribute = (k, v) => {
      originalSetAttribute(k, v);
      if (k === "id") byId[v] = el;
    };
    const originalAppendChild = el.appendChild;
    el.appendChild = (child) => {
      originalAppendChild(child);
      if (child.attrs && child.attrs.id) byId[child.attrs.id] = child;
      return child;
    };
    return el;
  };
  doc.createTextNode = (text) => ({ text: String(text), nodeType: 3 });
  globalThis.document = doc;
  globalThis.window = { document: doc, getComputedStyle: () => ({ color: "rgb(10, 20, 30)" }) };
  globalThis.location = { reload: () => {} };
  return doc;
}

function uninstallDom() {
  delete globalThis.document;
  delete globalThis.window;
  delete globalThis.location;
  delete globalThis.fetch;
}

// -------------------------------------------------------------------------------------------- fake fetch

/** A scripted fetch: `on(match, answer)` registers handlers by URL substring; `stream` emits frames. */
function fakeFetch() {
  const calls = [];
  const handlers = [];
  const encoder = new TextEncoder();
  const impl = (url, init) => {
    calls.push({ url, init: init || {}, at: calls.length });
    const handler = handlers.find((candidate) => url.includes(candidate.match));
    if (!handler) return Promise.reject(new Error("no scripted handler for " + url));
    return Promise.resolve(handler.answer(url, init || {}));
  };
  impl.calls = calls;
  // `on` registers at the FRONT (a test's specific answer wins); `otherwise` appends a fallback.
  impl.on = (match, answer) => {
    handlers.unshift({ match, answer });
    return impl;
  };
  impl.otherwise = (match, answer) => {
    handlers.push({ match, answer });
    return impl;
  };
  impl.json = (payload, status) => ({
    ok: (status || 200) < 400,
    status: status || 200,
    text: async () => JSON.stringify(payload),
  });
  impl.envelope = (data, status) => impl.json({ data }, status);
  impl.error = (code, status) => impl.json({ error: { code, message: code } }, status);
  impl.frame = (event, payload, id) => `event: ${event}\nid: ${id}\ndata: ${JSON.stringify(payload)}\n\n`;
  impl.stream = (frames, onClosed) => ({
    ok: true,
    status: 200,
    text: async () => "",
    body: {
      getReader() {
        let index = 0;
        return {
          read: async () => {
            if (index < frames.length) {
              const value = frames[index];
              index += 1;
              return { done: false, value: encoder.encode(value) };
            }
            return { done: true, value: undefined };
          },
          cancel: async () => {
            if (onClosed) onClosed();
          },
        };
      },
    },
  });
  /** A stream whose first read returns `first`; the release resolver's argument becomes the second chunk. */
  impl.heldStream = (first, holder) => ({
    ok: true,
    status: 200,
    text: async () => "",
    body: {
      getReader() {
        let stage = 0;
        return {
          read: async () => {
            if (stage === 0) {
              stage = 1;
              if (first) return { done: false, value: encoder.encode(first) };
              return { done: false, value: encoder.encode("") };
            }
            if (stage === 1) {
              stage = 2;
              const text = await new Promise((resolve) => holder.push(resolve));
              if (!text) return { done: true, value: undefined };
              return { done: false, value: encoder.encode(text) };
            }
            return { done: true, value: undefined };
          },
          cancel: async () => {},
        };
      },
    },
  });
  return impl;
}

// ---------------------------------------------------------------------------------------------- fixtures

function scriptedAdapter(overrides) {
  const log = [];
  const adapter = {
    log,
    commitCb: null,
    editCb: null,
    actionCb: null,
    mountLayout: () => {
      log.push("mountLayout");
      return Promise.resolve();
    },
    mountVisualization: (occurrence, renderer) => {
      log.push("mountVisualization:" + occurrence.name + ":" + renderer.kind);
      return Promise.resolve({
        renderData: (ref) => {
          log.push("renderData:" + ref.name);
          return Promise.resolve("rendered");
        },
      });
    },
    renderParameters: (state) => {
      log.push("renderParameters:" + (state ? state.parameters.length : "null"));
      return Promise.resolve();
    },
    readSelections: () => {
      log.push("readSelections");
      return {};
    },
    onEdit: (callback) => {
      adapter.editCb = callback;
    },
    onCommit: (callback) => {
      adapter.commitCb = callback;
    },
    onAction: (callback) => {
      adapter.actionCb = callback;
    },
    renderData: () => Promise.resolve("rendered"),
    renderStatus: (occurrence, status) => {
      log.push("status:" + occurrence.name + ":" + status.state + (status.stale ? ":stale" : ""));
    },
    notify: (notification) => {
      log.push("notify:" + notification.code);
    },
    resize: () => {},
    dispose: () => {
      log.push("dispose");
    },
  };
  return Object.assign(adapter, overrides || {});
}

function configPayload(overrides) {
  return Object.assign(
    {
      configuration_id: "cfg-1",
      renderer: { bundle: "2d" },
      dashboard: { id: "d1", name: "dbr/board", version: 1, status: "RELEASED" },
      layout: { columns: 12, grid: [] },
      parameter_set: null,
      visualizations: [
        { name: "chart", renderer: { kind: "plotly", version: "4" }, config: {}, bindings: {}, presentation: null, timeout_seconds: 120 },
        { name: "cells", renderer: { kind: "table", version: "1" }, config: {}, bindings: {}, presentation: null, timeout_seconds: 120 },
      ],
      groups: [],
      actions: [],
      action_controls: [],
      parameter_scopes: {},
      parameter_state: null,
      timeouts: { refresh_seconds: 300, parameter_lock_seconds: 30, render_seconds: 20 },
      budgets: { max_bytes_per_source: 1, max_bytes_per_refresh: 2 },
    },
    overrides || {},
  );
}

function evaluatedState(overrides) {
  return Object.assign(
    {
      parameters: [
        {
          definition: { name: "year", label: "Year" },
          value: 2026,
          state: { hidden: false, disabled: false },
          options: [{ value: 2026, display_value: "2026" }],
        },
      ],
      parents: [],
      parameter_revision: 3,
    },
    overrides || {},
  );
}

async function boot(options) {
  const doc = installDom(options && options.dom);
  const fetchImpl = options && options.fetch ? options.fetch : fakeFetch();
  globalThis.fetch = fetchImpl;
  fetchImpl.otherwise("/runtime/visualizations", () => fetchImpl.stream([]));
  fetchImpl.otherwise("/abort", () => fetchImpl.envelope({ refresh_id: "x", status: "abort_requested" }));
  fetchImpl.otherwise("/runtime/parameters", () => fetchImpl.envelope((options && options.parameters) || evaluatedState()));
  fetchImpl.otherwise("/runtime/config", () => fetchImpl.envelope((options && options.config) || configPayload()));
  const adapter = (options && options.adapter) || scriptedAdapter();
  const runtime = require(runtimePath);
  // The browser gets its renderers from the adapter files' load-time registration; the tests
  // register stand-ins for the same kinds and majors when the real files are not loaded.
  const registered = runtime._internal.renderers();
  if (!registered.plotly) runtime.registerRenderer({ kind: "plotly", version: "4", create: () => ({ renderData: () => Promise.resolve("rendered") }) });
  if (!registered.table) runtime.registerRenderer({ kind: "table", version: "1", create: () => ({ renderData: () => Promise.resolve("rendered") }) });
  const container = doc.createElement("div");
  const instance = runtime.init({
    server: { baseUrl: (options && options.baseUrl) || "", credentials: (options && options.credentials) || "session" },
    dashboard: { id: "d1", version: "released" },
    container,
    adapter,
    options: Object.assign({ renderTimeoutMs: 200 }, options && options.initOptions),
  });
  await instance.ready;
  return { instance, adapter, fetch: fetchImpl, doc, container, runtime };
}

// ------------------------------------------------------------------------------------------------- tests

test("init refuses an adapter missing any required function, naming it", () => {
  installDom();
  try {
    const runtime = require(runtimePath);
    for (const name of runtime._internal.REQUIRED_ADAPTER_FUNCTIONS) {
      const adapter = scriptedAdapter();
      delete adapter[name];
      assert.throws(
        () =>
          runtime.init({
            server: { baseUrl: "", credentials: "session" },
            dashboard: { id: "d", version: "released" },
            container: fakeElement("div"),
            adapter,
          }),
        (error) => error.code === "adapter.missing_function" && error.details.missing === name,
        "missing " + name + " was accepted",
      );
    }
  } finally {
    uninstallDom();
  }
});

test("init refuses a numeric version, bad credentials and a twice-mounted container", () => {
  installDom();
  try {
    const runtime = require(runtimePath);
    const container = fakeElement("div");
    const base = () => ({
      server: { baseUrl: "", credentials: "session" },
      dashboard: { id: "d", version: "released" },
      container,
      adapter: scriptedAdapter(),
    });
    assert.throws(() => runtime.init({ ...base(), dashboard: { id: "d", version: 3 } }), (e) => e.code === "init.version_unsupported");
    assert.throws(() => runtime.init({ ...base(), server: { baseUrl: "", credentials: "bearer" } }), (e) => e.code === "init.invalid");
    const instance = runtime.init(base());
    assert.throws(() => runtime.init(base()), (e) => e.name === "DashboardAlreadyMounted");
    instance.dispose();
    // After disposal the container is unmarked: a fresh init is legitimate.
    assert.doesNotThrow(() => runtime.init(base()));
  } finally {
    uninstallDom();
  }
});

test("bootstrap runs in the barrier's order, and a parameter-free board skips the parameters call", async () => {
  const { adapter, fetch } = await boot({
    config: configPayload({
      parameter_set: { name: "dbr/sets/reporting", version: 1 },
    }),
  });
  const order = (marker) => adapter.log.indexOf(marker);
  assert.ok(order("mountLayout") !== -1 && order("renderParameters:1") !== -1, adapter.log.join("|"));
  assert.ok(
    order("mountLayout") < order("renderParameters:1") && order("renderParameters:1") < order("mountVisualization:chart:plotly"),
    "layout → parameters → placeholders, in that order: " + adapter.log.join("|"),
  );
  assert.deepEqual(
    fetch.calls.map((call) => call.url.replace(/^.*\/api/, "/api")).slice(0, 2),
    ["/api/v1/dashboards/d1/runtime/config", "/api/v1/dashboards/d1/runtime/parameters"],
  );

  const parameterFree = fakeFetch();
  const { adapter: freeAdapter } = await boot({ fetch: parameterFree });
  assert.ok(!freeAdapter.log.some((line) => line.startsWith("renderParameters")), "a parameter-free board rendered parameters");
  assert.ok(!parameterFree.calls.some((call) => call.url.includes("/runtime/parameters")), "a parameter-free board called the parameters route");
});

test("the initial action fires once ready, with the action's own scope and targets", async () => {
  const fetchImpl = fakeFetch();
  fetchImpl.on("/runtime/visualizations", () => fetchImpl.stream([]));
  const config = configPayload({
    parameter_set: { name: "dbr/sets/reporting", version: 1 },
    actions: [{ name: "refresh_cells", type: "refresh", scope: "targets", targets: ["cells"], initial: true }],
  });
  const { instance } = await boot({ fetch: fetchImpl, config });
  await new Promise((resolve) => setTimeout(resolve, 10));
  const streamCall = fetchImpl.calls.find((call) => call.url.includes("/runtime/visualizations"));
  assert.ok(streamCall, "the initial action opened a stream");
  const body = JSON.parse(streamCall.init.body);
  assert.equal(body.scope, "targets");
  assert.deepEqual(body.targets, ["cells"]);
  assert.match(body.refresh_id, /^[0-9a-f-]{36}$/);
  assert.equal(body.parameter_revision, 3);
  assert.equal(body.configuration_id, "cfg-1");
  assert.ok(instance);
});

test("freshness: an old refresh finishing after a newer one owns only the targets nothing newer claimed", async () => {
  const fetchImpl = fakeFetch();
  const holders = [];
  fetchImpl.on("/runtime/visualizations", () => {
    const holder = [];
    holders.push(holder);
    return fetchImpl.heldStream(null, holder);
  });
  const { instance, adapter } = await boot({ fetch: fetchImpl });
  const r1 = await instance.refresh({ scope: "targets", targets: ["chart", "cells"] });
  const r2 = await instance.refresh({ scope: "targets", targets: ["cells"] });
  await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(holders.length, 2, "two streams opened");
  // Stream 1 (R1) releases: data for chart (still owned), data for cells (superseded by R2), completion.
  holders[0][0](
    fetchImpl.frame("visualization_data", { refresh_id: r1, name: "chart", type: "visualization", bindings: {}, rows: 1, bytes: 4 }) +
      fetchImpl.frame("visualization_data", { refresh_id: r1, name: "cells", type: "visualization", bindings: {}, rows: 1, bytes: 4 }) +
      fetchImpl.frame("refresh_completed", {
        refresh_id: r1,
        status: "COMPLETED",
        targets: { chart: { outcome: "rendered" }, cells: { outcome: "rendered" } },
      }),
  );
  await new Promise((resolve) => setTimeout(resolve, 40));
  holders[1][0](
    fetchImpl.frame("refresh_completed", { refresh_id: r2, status: "COMPLETED", targets: { cells: { outcome: "rendered" } } }),
  );
  await new Promise((resolve) => setTimeout(resolve, 40));

  const renders = adapter.log.filter((line) => line.startsWith("renderData:"));
  assert.deepEqual(renders, ["renderData:chart"], "R1's data for cells (owned by R2) must not render: " + adapter.log.join("|"));
  assert.ok(adapter.log.includes("status:chart:success"), adapter.log.join("|"));
  assert.ok(!adapter.log.includes("status:cells:success"), "R1's completion must not publish cells: " + adapter.log.join("|"));
});

test("a no-data status frame sets no-data and sends no renderData", async () => {
  const fetchImpl = fakeFetch();
  fetchImpl.on("/runtime/visualizations", (url, init) => {
    const refreshId = JSON.parse(init.body).refresh_id;
    return fetchImpl.stream([fetchImpl.frame("visualization_status", { refresh_id: refreshId, name: "chart", type: "visualization", state: "no-data" })]);
  });
  const { instance, adapter } = await boot({ fetch: fetchImpl });
  await instance.refresh({ scope: "targets", targets: ["chart"] });
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.ok(adapter.log.includes("status:chart:no-data"), adapter.log.join("|"));
  assert.ok(!adapter.log.some((line) => line.startsWith("renderData:")), "no data frame, no render: " + adapter.log.join("|"));
});

test("a data frame renders, shows success, then settles to ready without a new status event", async () => {
  const fetchImpl = fakeFetch();
  fetchImpl.on("/runtime/visualizations", (url, init) => {
    const refreshId = JSON.parse(init.body).refresh_id;
    return fetchImpl.stream([
      fetchImpl.frame("visualization_data", { refresh_id: refreshId, name: "chart", type: "visualization", bindings: { "data[0].x": [1, 2] }, rows: 2, bytes: 16 }),
    ]);
  });
  const { instance, adapter } = await boot({ fetch: fetchImpl });
  await instance.refresh({ scope: "targets", targets: ["chart"] });
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.ok(adapter.log.includes("renderData:chart"), adapter.log.join("|"));
  assert.ok(adapter.log.includes("status:chart:success"), adapter.log.join("|"));
  await new Promise((resolve) => setTimeout(resolve, 1300));
  assert.ok(adapter.log.includes("status:chart:ready"), "success settles to ready: " + adapter.log.join("|"));
});

test("a hung renderer hits the render deadline and reports error with the render code", async () => {
  const hungAdapter = scriptedAdapter({
    mountVisualization: (occurrence) =>
      Promise.resolve({
        renderData: () => new Promise(() => {}), // never settles — the deadline must
      }),
  });
  hungAdapter.renderStatus = (occurrence, status) =>
    hungAdapter.log.push("status:" + occurrence.name + ":" + status.state + (status.reason ? ":" + status.reason.code : ""));
  const fetchImpl = fakeFetch();
  fetchImpl.on("/runtime/visualizations", (url, init) => {
    const refreshId = JSON.parse(init.body).refresh_id;
    return fetchImpl.stream([fetchImpl.frame("visualization_data", { refresh_id: refreshId, name: "chart", type: "visualization", bindings: { x: [1] }, rows: 1, bytes: 4 })]);
  });
  const { instance } = await boot({ fetch: fetchImpl, adapter: hungAdapter, initOptions: { renderTimeoutMs: 30 } });
  await instance.refresh({ scope: "targets", targets: ["chart"] });
  await new Promise((resolve) => setTimeout(resolve, 80));
  assert.ok(hungAdapter.log.includes("status:chart:error:render.timeout"), hungAdapter.log.join("|"));
  assert.ok(hungAdapter.log.includes("notify:render.failed"));
});

test("the parameter lock refuses actions while pending, releases on the deadline, and a late response is superseded", async () => {
  const fetchImpl = fakeFetch();
  fetchImpl.on("/runtime/parameters", (url, init) => {
    if (JSON.parse(init.body).intent === "bootstrap") return fetchImpl.envelope(evaluatedState());
    return new Promise(() => {}); // the attempt under test never answers — its deadline must
  });
  const { instance, adapter } = await boot({ fetch: fetchImpl });
  instance._config.timeouts.parameter_lock_seconds = 0.05;
  const attempt = instance._evaluateParameters("parent_change");
  attempt.catch(() => {});
  await new Promise((resolve) => setTimeout(resolve, 5));
  await assert.rejects(() => instance.refresh({ scope: "all" }), (error) => error.code === "actions.locked");
  await assert.rejects(() => instance.reset(), (error) => error.code === "actions.locked");
  await new Promise((resolve) => setTimeout(resolve, 90));
  assert.ok(adapter.log.includes("notify:parameters.lock_timeout"), adapter.log.join("|"));
  assert.ok(!instance._lock, "the deadline released the lock");
  const rendersBefore = adapter.log.filter((line) => line.startsWith("renderParameters")).length;
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(
    adapter.log.filter((line) => line.startsWith("renderParameters")).length,
    rendersBefore,
    "a superseded attempt renders nothing (the response never even arrived here, and none is awaited)",
  );
});

test("a response arriving after its attempt expired changes nothing", async () => {
  const fetchImpl = fakeFetch();
  let answerLate;
  fetchImpl.on("/runtime/parameters", (url, init) => {
    if (JSON.parse(init.body).intent === "bootstrap") return fetchImpl.envelope(evaluatedState());
    return new Promise((resolve) => {
      answerLate = () => resolve(fetchImpl.envelope(evaluatedState({ parameter_revision: 77 })));
    });
  });
  const { instance, adapter } = await boot({
    fetch: fetchImpl,
    config: configPayload({ parameter_set: { name: "dbr/sets/reporting", version: 1 } }),
  });
  instance._config.timeouts.parameter_lock_seconds = 0.05;
  const doomed = instance._evaluateParameters("retry");
  doomed.catch(() => {});
  await new Promise((resolve) => setTimeout(resolve, 90)); // the deadline fires
  const rendersBefore = adapter.log.filter((line) => line.startsWith("renderParameters")).length;
  answerLate();
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.equal(adapter.log.filter((line) => line.startsWith("renderParameters")).length, rendersBefore, "the late response was superseded");
  await assert.rejects(() => doomed, (error) => error.code === "parameters.superseded");
  assert.equal(instance._parameters.parameter_revision, 3, "the applied state is unchanged");
});

test("abort invalidates locally at once, posts with the instance id, and a 404 is idempotent", async () => {
  const fetchImpl = fakeFetch();
  fetchImpl.on("/runtime/visualizations", () => fetchImpl.stream([]));
  const { instance, adapter } = await boot({ fetch: fetchImpl });
  const refreshId = await instance.refresh({ scope: "all" });
  const outcome = await instance.abort(refreshId);
  assert.deepEqual(outcome, { abort_requested: true });
  assert.ok(adapter.log.includes("status:chart:abort"), adapter.log.join("|"));
  assert.ok(adapter.log.includes("status:cells:abort"));
  const abortCall = fetchImpl.calls.find((call) => call.url.includes("/abort"));
  assert.equal(JSON.parse(abortCall.init.body).instance_id, instance._instanceId);
  // A second abort (the refresh already ended locally) is a no-op that sends nothing.
  const callsBefore = fetchImpl.calls.length;
  await instance.abort(refreshId);
  assert.equal(fetchImpl.calls.length, callsBefore);
  // A 404 from the server (already finished server-side) publishes nothing.
  fetchImpl.on("/abort", () => fetchImpl.error("dashboard.refresh.not_found", 404));
  const second = await instance.refresh({ scope: "all" });
  const notificationsBefore = adapter.log.filter((line) => line.startsWith("notify:")).length;
  await instance.abort(second);
  assert.equal(adapter.log.filter((line) => line.startsWith("notify:")).length, notificationsBefore, "a 404 abort is the finished-refresh idempotence");
});

test("connection loss retains content, marks pending stale, offers Retry, and the retry mints a NEW refresh id", async () => {
  const fetchImpl = fakeFetch();
  fetchImpl.on("/runtime/visualizations", (url, init) => {
    const refreshId = JSON.parse(init.body).refresh_id;
    return fetchImpl.stream([
      fetchImpl.frame("visualization_data", { refresh_id: refreshId, name: "chart", type: "visualization", bindings: { x: [1] }, rows: 1, bytes: 4 }),
    ]);
  });
  const { instance, adapter } = await boot({ fetch: fetchImpl });
  const first = await instance.refresh({ scope: "all" });
  await new Promise((resolve) => setTimeout(resolve, 40));
  assert.ok(adapter.log.find((line) => line.startsWith("notify:transport.disconnected")), adapter.log.join("|"));
  assert.ok(adapter.log.includes("status:cells:ready:stale"), "pending targets are stale, content retained: " + adapter.log.join("|"));
  assert.ok(adapter.log.includes("status:chart:success"), "completed targets stay completed");
  const retry = await instance.recover("retry");
  assert.notEqual(retry, first, "the retry mints a fresh refresh id");
  assert.ok(retry, "recover(retry) started a refresh");
});

test("dispose closes the stream, aborts a running refresh, unmarks the container, and late frames change nothing", async () => {
  const fetchImpl = fakeFetch();
  let cancelled = false;
  fetchImpl.on("/runtime/visualizations", (url, init) => {
    init.signal.onabort = () => {
      cancelled = true;
    };
    return {
      ok: true,
      status: 200,
      text: async () => "",
      body: {
        getReader() {
          return {
            read: () => new Promise(() => {}),
            cancel: async () => {
              cancelled = true;
            },
          };
        },
      },
    };
  });
  const { instance, adapter, container, runtime } = await boot({ fetch: fetchImpl });
  await instance.refresh({ scope: "all" });
  const before = adapter.log.filter((line) => line.startsWith("status:")).length;
  instance.dispose();
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.ok(cancelled, "the stream read was cancelled");
  assert.ok(fetchImpl.calls.some((call) => call.url.includes("/abort")), "a running refresh got its best-effort abort");
  assert.ok(adapter.log.includes("dispose"), "the adapter was disposed");
  assert.equal(container.getAttribute(runtime._internal.MOUNTED_ATTRIBUTE), null, "the container was unmarked");
  assert.equal(adapter.log.filter((line) => line.startsWith("status:")).length, before, "no status changed after disposal");
  instance._onFrame("visualization_data", { refresh_id: "zz", name: "chart", type: "visualization", bindings: {}, rows: 1 });
  assert.equal(adapter.log.filter((line) => line.startsWith("status:")).length, before);
});

test("two instances of one dashboard are isolated: ownership, callbacks, notifications, disposal", async () => {
  const doc = installDom();
  const runtime = require(runtimePath);
  const fetchImpl = fakeFetch();
  globalThis.fetch = fetchImpl;
  fetchImpl.on("/runtime/visualizations", () => fetchImpl.stream([]));
  fetchImpl.on("/abort", () => fetchImpl.envelope({ status: "abort_requested" }));
  fetchImpl.on("/runtime/parameters", () => fetchImpl.envelope(evaluatedState()));
  fetchImpl.on("/runtime/config", () => fetchImpl.envelope(configPayload()));
  const firstAdapter = scriptedAdapter();
  const secondAdapter = scriptedAdapter();
  const first = runtime.init({ server: { baseUrl: "", credentials: "session" }, dashboard: { id: "d1", version: "released" }, container: doc.createElement("div"), adapter: firstAdapter, options: { renderTimeoutMs: 200 } });
  const second = runtime.init({ server: { baseUrl: "", credentials: "session" }, dashboard: { id: "d1", version: "released" }, container: doc.createElement("div"), adapter: secondAdapter, options: { renderTimeoutMs: 200 } });
  await first.ready;
  await second.ready;
  assert.notEqual(first._instanceId, second._instanceId);
  const firstRefresh = await first.refresh({ scope: "targets", targets: ["chart"] });
  // A frame for the FIRST instance's refresh reaches the second instance's runtime through no path:
  // it ignores refresh ids it did not mint.
  second._onFrame("visualization_data", { refresh_id: firstRefresh, name: "chart", type: "visualization", bindings: {}, rows: 1, bytes: 2 });
  assert.ok(!secondAdapter.log.some((line) => line.startsWith("renderData:")), "another instance's frame touched nothing here");
  first.dispose();
  await new Promise((resolve) => setTimeout(resolve, 20));
  assert.ok(firstAdapter.log.includes("dispose"));
  assert.ok(!secondAdapter.log.includes("dispose"), "disposing one instance left the other mounted");
  await second.refresh({ scope: "all" });
  assert.ok(secondAdapter.log.some((line) => line.startsWith("status:")), "the surviving instance still refreshes");
  uninstallDom();
});

test("session mode sends the csrf header on POSTs; proxy mode omits cookie and header and re-bases", async () => {
  const doc = installDom({ cookie: "dp_csrf=tok%20123" });
  const runtime = require(runtimePath);
  const sessionFetch = fakeFetch();
  globalThis.fetch = sessionFetch;
  sessionFetch.on("/runtime/visualizations", () => sessionFetch.stream([]));
  sessionFetch.on("/runtime/parameters", () => sessionFetch.envelope(evaluatedState()));
  sessionFetch.on("/runtime/config", () => sessionFetch.envelope(parameterised));
  const parameterised = configPayload({ parameter_set: { name: "dbr/sets/reporting", version: 1 } });
  const session = runtime.init({ server: { baseUrl: "", credentials: "session" }, dashboard: { id: "d1", version: "released" }, container: doc.createElement("div"), adapter: scriptedAdapter(), options: { renderTimeoutMs: 200 } });
  await session.ready;
  await session.refresh({ scope: "all" });
  const posted = sessionFetch.calls.filter((call) => call.init.method === "POST");
  assert.ok(posted.length >= 2, "the parameters and stream POSTs happened");
  for (const call of posted) {
    assert.equal(call.init.credentials, "same-origin");
    assert.equal(call.init.headers["DP-CSRF-Token"], "tok 123", "the cookie's value, URL-decoded");
  }

  const proxyFetch = fakeFetch();
  globalThis.fetch = proxyFetch;
  proxyFetch.on("/runtime/visualizations", () => proxyFetch.stream([]));
  proxyFetch.on("/runtime/parameters", () => proxyFetch.envelope(evaluatedState()));
  proxyFetch.on("/runtime/config", () => proxyFetch.envelope(parameterised));
  const proxy = runtime.init({ server: { baseUrl: "", credentials: { proxyBaseUrl: "https://proxy.example/pipeline" } }, dashboard: { id: "d1", version: "released" }, container: doc.createElement("div"), adapter: scriptedAdapter(), options: { renderTimeoutMs: 200 } });
  await proxy.ready;
  await proxy.refresh({ scope: "all" });
  for (const call of proxyFetch.calls) {
    assert.ok(call.url.startsWith("https://proxy.example/pipeline/api/v1/dashboards/"), call.url);
    assert.equal(call.init.credentials, "omit", "no cookie to the proxy");
    assert.equal(call.init.headers["DP-CSRF-Token"], undefined, "no csrf header in proxy mode");
  }
  uninstallDom();
});

test("a stale configuration (409) publishes the notification with recovery reload and disposes", async () => {
  const notifications = [];
  const fetchImpl = fakeFetch();
  fetchImpl.on("/runtime/config", () => fetchImpl.error("dashboard.runtime.configuration_stale", 409));
  const doc = installDom();
  const runtime = require(runtimePath);
  globalThis.fetch = fetchImpl;
  const adapter = scriptedAdapter();
  const container = doc.createElement("div");
  const instance = runtime.init({
    server: { baseUrl: "", credentials: "session" },
    dashboard: { id: "d1", version: "released" },
    container,
    adapter,
    options: { renderTimeoutMs: 200, onNotification: (n) => notifications.push(n) },
  });
  await assert.rejects(() => instance.ready, (error) => error.code === "dashboard.runtime.configuration_stale");
  await new Promise((resolve) => setTimeout(resolve, 20));
  const stale = notifications.find((n) => n.code === "dashboard.runtime.configuration_stale");
  assert.ok(stale, "the stale outcome was published");
  assert.ok(stale.configurationStale, "it carries the reload instruction");
  assert.ok(adapter.log.includes("dispose"), "the instance disposed itself");
  uninstallDom();
});

test("callbacks carry the identity quartet, and a commit on a parent re-evaluates the set", async () => {
  const fetchImpl = fakeFetch();
  fetchImpl.on("/runtime/parameters", (url, init) => {
    const intent = JSON.parse(init.body).intent;
    return fetchImpl.envelope(evaluatedState(intent === "bootstrap" ? { parents: ["year"] } : { parameter_revision: 9, parents: ["year"] }));
  });
  fetchImpl.on("/runtime/visualizations", () => fetchImpl.stream([]));
  const { instance, adapter } = await boot({
    fetch: fetchImpl,
    config: configPayload({ parameter_set: { name: "dbr/sets/reporting", version: 1 } }),
  });
  const seen = [];
  instance.on("commit", (enriched) => seen.push(enriched));
  // Fire the wrapper the way the composite adapter's commit gesture would.
  assert.equal(typeof adapter.commitCb, "function", "the runtime registered its commit wrapper on the adapter");
  adapter.commitCb({ name: "year", type: "parameter" });
  await new Promise((resolve) => setTimeout(resolve, 30));
  assert.deepEqual(seen[0], { instanceId: instance._instanceId, name: "year", type: "parameter", refreshId: null });
  const revisionCall = fetchImpl.calls.filter((call) => call.url.includes("/runtime/parameters")).pop();
  assert.equal(JSON.parse(revisionCall.init.body).intent, "parent_change", "a parent commit re-evaluates the set");
  assert.equal(instance._parameters.parameter_revision, 9, "the new state was applied");
});

test("the renderer judgement fails before execution: unknown kind, version mismatch, two bundles, 2d-declared for a 3d board", async () => {
  const cases = [
    { config: configPayload({ visualizations: [{ name: "x", renderer: { kind: "svg", version: "1" }, config: {} }] }), code: "renderer.unsupported" },
    { config: configPayload({ visualizations: [{ name: "x", renderer: { kind: "plotly", version: "5" }, config: {} }] }), code: "renderer.version_mismatch" },
    { config: configPayload({ renderer: { bundle: "3d" } }), dom: { plotlyScripts: [{ getAttribute: () => "2d" }] }, code: "renderer.bundle_insufficient" },
    { config: configPayload(), dom: { plotlyScripts: [{ getAttribute: () => "2d" }, { getAttribute: () => "3d" }] }, code: "renderer.two_bundles" },
  ];
  for (const testCase of cases) {
    const fetchImpl = fakeFetch();
    let streamCalled = false;
    fetchImpl.on("/runtime/config", () => fetchImpl.envelope(testCase.config));
    fetchImpl.on("/runtime/visualizations", () => {
      streamCalled = true;
      return fetchImpl.stream([]);
    });
    const doc = installDom(testCase.dom);
    globalThis.fetch = fetchImpl;
    const runtime = require(runtimePath);
    runtime._internal.resetRenderers();
    runtime.registerRenderer({ kind: "plotly", version: "4", create: () => ({ renderData: () => Promise.resolve("rendered") }) });
    runtime.registerRenderer({ kind: "table", version: "1", create: () => ({ renderData: () => Promise.resolve("rendered") }) });
    const adapter = scriptedAdapter();
    const instance = runtime.init({ server: { baseUrl: "", credentials: "session" }, dashboard: { id: "d1", version: "released" }, container: doc.createElement("div"), adapter, options: { renderTimeoutMs: 200 } });
    await assert.rejects(() => instance.ready, (error) => error.code === testCase.code, "expected " + testCase.code);
    assert.ok(!streamCalled, "nothing executed");
    uninstallDom();
  }
});

test("reset restores the applied baseline without executing anything", async () => {
  const fetchImpl = fakeFetch();
  const { instance, adapter } = await boot({
    fetch: fetchImpl,
    config: configPayload({ parameter_set: { name: "dbr/sets/reporting", version: 1 } }),
  });
  const callsBefore = fetchImpl.calls.length;
  await instance.reset();
  assert.equal(fetchImpl.calls.length, callsBefore, "reset executed nothing");
  assert.ok(adapter.log.some((line) => line.startsWith("renderParameters")), "the baseline was re-rendered");
  assert.equal(instance._parameters.parameter_revision, 3, "the revision is the applied one");
});
