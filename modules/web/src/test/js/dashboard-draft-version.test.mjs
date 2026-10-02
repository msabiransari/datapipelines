// The draft preview's version on the wire (#369, dashboards.md §5.2/§6.1): the runtime's init
// admits `"released"` or a positive integer version, and an integer rides `?version=N` on EVERY
// runtime path the instance builds — config, parameters, the stream and abort. Same harness
// decision as the sibling tests: no packages, no config; a hand-rolled fake DOM and a scripted
// fetch before the IIFE is required.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const runtimePath = path.resolve(here, "../../main/resources/static/js/datapipelines-dashboard.js");

// The fake DOM/fetch/adapter are the runtime tests' exact shapes, copied per file (the js tests
// share no module on purpose — each stands alone under `node --test`).
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
    querySelector() {
      return null;
    },
    querySelectorAll() {
      return [];
    },
  };
  return el;
}

function installDom() {
  const doc = {
    body: fakeElement("body"),
    head: fakeElement("head"),
    cookie: "",
    getElementById: () => null,
    querySelectorAll: () => [],
    createElement: (tag) => fakeElement(tag),
    createTextNode: (text) => ({ text: String(text), nodeType: 3 }),
  };
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

function scriptedAdapter() {
  return {
    mountLayout: () => Promise.resolve(),
    mountVisualization: () => Promise.resolve({ renderData: () => Promise.resolve("rendered") }),
    renderParameters: () => Promise.resolve(),
    readSelections: () => {},
    onEdit: () => {},
    onCommit: () => {},
    onAction: () => {},
    renderData: () => Promise.resolve("rendered"),
    renderStatus: () => {},
    notify: () => {},
    resize: () => {},
    dispose: () => {},
  };
}

function configPayload() {
  return {
    configuration_id: "cfg-draft",
    renderer: { bundle: "2d" },
    dashboard: { id: "d1", name: "dbr/board", version: 3, status: "DRAFT" },
    layout: { columns: 12, grid: [] },
    parameter_set: null,
    visualizations: [
      { name: "chart", renderer: { kind: "plotly", version: "4" }, config: {}, bindings: {}, presentation: null, timeout_seconds: 120 },
    ],
    groups: [],
    actions: [],
    action_controls: [],
    parameter_scopes: {},
    parameter_state: null,
    timeouts: { refresh_seconds: 300, parameter_lock_seconds: 30, render_seconds: 20 },
    budgets: { max_bytes_per_source: 1, max_bytes_per_refresh: 2 },
  };
}

function fakeFetch() {
  const calls = [];
  const handlers = [];
  const impl = (url) => {
    calls.push(url);
    const handler = handlers.find((candidate) => url.includes(candidate.match));
    if (!handler) return Promise.reject(new Error("no scripted handler for " + url));
    return Promise.resolve(handler.answer(url));
  };
  impl.calls = calls;
  impl.on = (match, answer) => {
    handlers.unshift({ match, answer });
    return impl;
  };
  impl.json = (payload, status) => ({
    ok: (status || 200) < 400,
    status: status || 200,
    text: async () => JSON.stringify(payload),
  });
  impl.envelope = (data) => impl.json({ data });
  return impl;
}

async function boot(version) {
  installDom();
  const fetchImpl = fakeFetch();
  globalThis.fetch = fetchImpl;
  fetchImpl.on("/runtime/config", () => fetchImpl.envelope(configPayload()));
  fetchImpl.on("/runtime/parameters", () => fetchImpl.envelope({ valid: true, values: {}, parameters: [], overrides_applied: {}, parents: [], parameter_revision: 0 }));
  const runtime = require(runtimePath);
  const registered = runtime._internal.renderers();
  if (!registered.plotly) runtime.registerRenderer({ kind: "plotly", version: "4", create: () => ({ renderData: () => Promise.resolve("rendered") }) });
  const container = fakeElement("div");
  const instance = runtime.init({
    server: { baseUrl: "", credentials: "session" },
    dashboard: { id: "d1", version },
    container,
    adapter: scriptedAdapter(),
  });
  await instance.ready;
  return { instance, fetch: fetchImpl, container };
}

test("init admits the released view and a positive integer version, and refuses everything else", async () => {
  installDom();
  try {
    const runtime = require(runtimePath);
    const base = (version) => ({
      server: { baseUrl: "", credentials: "session" },
      dashboard: { id: "d", version },
      container: fakeElement("div"),
      adapter: scriptedAdapter(),
    });
    // A refused version is refused BEFORE any fetch: no handler is registered, so a call would reject loudly.
    for (const bad of [0, -1, 2.5, "3", "", null, NaN]) {
      assert.throws(
        () => runtime.init(base(bad)),
        (e) => e.code === "init.version_unsupported",
        "version " + String(bad) + " was accepted",
      );
    }
    for (const good of ["released", 1, 3, 99]) {
      const instance = runtime.init(base(good));
      await instance.ready.catch(() => {});
      instance.dispose();
    }
  } finally {
    uninstallDom();
  }
});

test("an integer version rides ?version=N on every runtime path; released rides no query", async () => {
  const { instance } = await boot(3);
  try {
    assert.equal(instance._configPath(), "/api/v1/dashboards/d1/runtime/config?version=3");
    assert.equal(instance._parametersPath(), "/api/v1/dashboards/d1/runtime/parameters?version=3");
    assert.equal(instance._streamPath(), "/api/v1/dashboards/d1/runtime/visualizations?version=3");
    assert.equal(
      instance._abortPath("11111111-1111-4111-8111-111111111111"),
      "/api/v1/dashboards/d1/runtime/refreshes/11111111-1111-4111-8111-111111111111/abort?version=3",
    );
  } finally {
    instance.dispose();
    uninstallDom();
  }
});

test("the released view's paths carry no query, exactly the pre-#369 bytes", async () => {
  const { instance } = await boot("released");
  try {
    assert.equal(instance._configPath(), "/api/v1/dashboards/d1/runtime/config");
    assert.equal(instance._parametersPath(), "/api/v1/dashboards/d1/runtime/parameters");
    assert.equal(instance._streamPath(), "/api/v1/dashboards/d1/runtime/visualizations");
    assert.equal(
      instance._abortPath("11111111-1111-4111-8111-111111111111"),
      "/api/v1/dashboards/d1/runtime/refreshes/11111111-1111-4111-8111-111111111111/abort",
    );
  } finally {
    instance.dispose();
    uninstallDom();
  }
});

test("a draft instance actually fetches the versioned config, once per bootstrap", async () => {
  const { instance, fetch } = await boot(3);
  try {
    const configCalls = fetch.calls.filter((url) => url.includes("/runtime/config"));
    assert.equal(configCalls.length, 1);
    assert.equal(configCalls[0], "/api/v1/dashboards/d1/runtime/config?version=3");
  } finally {
    instance.dispose();
    uninstallDom();
  }
});
