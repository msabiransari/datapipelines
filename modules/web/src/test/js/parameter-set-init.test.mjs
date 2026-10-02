// The parameters-only entry (#374, workspace spec §6.3): `initParameters` points the runtime's instance machinery
// at a parameter SET — `POST /api/v1/parameter-sets/{id}/evaluate` with `{ version, selections }`, `version` ALWAYS
// present, the first render submitting `{}`, a newer attempt superseding a pending one, the server's errors shown
// verbatim. Same harness decision as the sibling tests: no packages; a hand-rolled fake DOM and a scripted fetch
// installed before the IIFE is required.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const runtimePath = path.resolve(here, "../../main/resources/static/js/datapipelines-dashboard.js");

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
  return impl;
}

function boot(options) {
  installDom(options && options.cookie);
  const fetchImpl = heldFetch();
  globalThis.fetch = fetchImpl;
  const runtime = require(runtimePath);
  const adapter = recordingAdapter();
  const container = fakeElement("div");
  const instance = runtime.initParameters({
    server: { baseUrl: "", credentials: "session" },
    parameterSet: { id: "ps-1", version: 2 },
    container,
    adapter,
    options: { onNotification: (n) => adapter.hostNotifications.push(n) },
  });
  live = instance;
  return { runtime, fetchImpl, adapter, container, instance };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));

test("initParameters refuses a missing or non-integer version and any credentials but the session", () => {
  installDom();
  try {
    const runtime = require(runtimePath);
    const base = (over) => ({
      server: { baseUrl: "", credentials: "session" },
      parameterSet: { id: "ps-1", version: 2 },
      container: fakeElement("div"),
      adapter: recordingAdapter(),
      ...over,
    });
    for (const bad of [undefined, null, 0, -1, 2.5, "2", "released", NaN]) {
      assert.throws(
        () => runtime.initParameters(base({ parameterSet: { id: "ps-1", version: bad } })),
        (e) => e.code === "init.version_unsupported",
        "version " + String(bad) + " was accepted",
      );
    }
    assert.throws(() => runtime.initParameters(base({ parameterSet: { version: 2 } })), (e) => e.code === "init.invalid");
    assert.throws(
      () => runtime.initParameters(base({ server: { credentials: { proxyBaseUrl: "/p" } } })),
      (e) => e.code === "init.invalid",
    );
    assert.equal(typeof runtime.initParameters, "function");
  } finally {
    uninstallDom();
  }
});

test("the first render submits {} with the version, to the frozen evaluate route, carrying the session's CSRF token", async () => {
  const { fetchImpl, adapter, instance } = boot({ cookie: "dp_csrf=tok%2B1" });
  try {
    await tick();
    assert.equal(fetchImpl.calls.length, 1);
    const call = fetchImpl.calls[0];
    assert.equal(call.url, "/api/v1/parameter-sets/ps-1/evaluate");
    assert.equal(call.init.method, "POST");
    assert.equal(call.init.credentials, "same-origin");
    assert.equal(call.init.headers["DP-CSRF-Token"], "tok+1");
    assert.deepEqual(call.body, { version: 2, selections: {} });
    fetchImpl.answer(call, evaluated({ country: "NL" }));
    await instance.ready;
    assert.equal(adapter.rendered.length, 1);
  } finally {
    uninstallDom();
  }
});

test("a commit submits the WHOLE selection set with the version, hidden and disabled values included", async () => {
  const { fetchImpl, adapter, instance } = boot();
  try {
    await tick();
    fetchImpl.answer(fetchImpl.calls[0], evaluated({ country: "NL", city: null, hiddenOne: "x" }));
    await instance.ready;
    adapter.selections = { country: "DE", city: null, hiddenOne: "x" };
    adapter.listeners.commit({ name: "country", type: "parameter" });
    await tick();
    assert.equal(fetchImpl.calls.length, 2);
    assert.deepEqual(fetchImpl.calls[1].body, { version: 2, selections: { country: "DE", city: null, hiddenOne: "x" } });
  } finally {
    uninstallDom();
  }
});

test("a late response of attempt n after attempt n+1 was minted touches nothing", async () => {
  const { fetchImpl, adapter, instance } = boot();
  try {
    await tick();
    fetchImpl.answer(fetchImpl.calls[0], evaluated({ country: "NL" }));
    await instance.ready;
    const rendersBefore = adapter.rendered.length;
    adapter.selections = { country: "DE" };
    adapter.listeners.commit({ name: "country", type: "parameter" }); // attempt n
    await tick();
    adapter.selections = { country: "FR" };
    adapter.listeners.commit({ name: "country", type: "parameter" }); // attempt n+1 supersedes n
    await tick();
    assert.equal(fetchImpl.calls.length, 3);
    // n+1 answers first, then the late n.
    fetchImpl.answer(fetchImpl.calls[2], evaluated({ country: "FR" }));
    await tick();
    fetchImpl.answer(fetchImpl.calls[1], evaluated({ country: "DE" }));
    await tick();
    const renders = adapter.rendered.slice(rendersBefore);
    assert.equal(renders.length, 1, "only the newest attempt's response may render");
    assert.equal(renders[0].values.country, "FR");
    assert.equal(instance._parameters.values.country, "FR", "the applied state is the newest attempt's");
  } finally {
    uninstallDom();
  }
});

test("a late response arriving BEFORE the newer one is answered changes nothing either", async () => {
  const { fetchImpl, adapter, instance } = boot();
  try {
    await tick();
    fetchImpl.answer(fetchImpl.calls[0], evaluated({ country: "NL" }));
    await instance.ready;
    const rendersBefore = adapter.rendered.length;
    adapter.selections = { country: "DE" };
    adapter.listeners.commit({ name: "country", type: "parameter" });
    await tick();
    adapter.selections = { country: "FR" };
    adapter.listeners.commit({ name: "country", type: "parameter" });
    await tick();
    fetchImpl.answer(fetchImpl.calls[1], evaluated({ country: "DE" })); // the superseded one, first
    await tick();
    assert.equal(adapter.rendered.length, rendersBefore, "a superseded response rendered");
    fetchImpl.answer(fetchImpl.calls[2], evaluated({ country: "FR" }));
    await tick();
    assert.equal(adapter.rendered.length, rendersBefore + 1);
  } finally {
    uninstallDom();
  }
});

test("a server error reaches the form verbatim — the server's code and message, not a sentence of the renderer's", async () => {
  const { fetchImpl, adapter, instance } = boot();
  try {
    await tick();
    fetchImpl.answer(fetchImpl.calls[0], evaluated({ country: "NL" }));
    await instance.ready;
    adapter.selections = { country: "DE" };
    adapter.listeners.commit({ name: "country", type: "parameter" });
    await tick();
    const verbatim = "Selector template 'geo/cities' could not run: connection refused (datasource 'warehouse')";
    fetchImpl.answer(fetchImpl.calls[1], { error: { code: "parameter_set.selector_failed", message: verbatim } }, 502);
    await tick();
    const failure = adapter.notifications.find((n) => n.severity === "error" && n.scope === "parameters");
    assert.ok(failure, "no failure notification reached the host");
    assert.equal(failure.code, "parameter_set.selector_failed");
    assert.equal(failure.message, verbatim);
  } finally {
    uninstallDom();
  }
});

test("the same failure twice with a success between is shown twice", async () => {
  const { fetchImpl, adapter, instance } = boot();
  try {
    await tick();
    fetchImpl.answer(fetchImpl.calls[0], evaluated({ country: "NL" }));
    await instance.ready;
    const fail = async (index) => {
      adapter.selections = { country: "DE" };
      adapter.listeners.commit({ name: "country", type: "parameter" });
      await tick();
      fetchImpl.answer(fetchImpl.calls[index], { error: { code: "x.y", message: "boom" } }, 500);
      await tick();
    };
    await fail(1);
    adapter.selections = { country: "NL" };
    adapter.listeners.commit({ name: "country", type: "parameter" });
    await tick();
    fetchImpl.answer(fetchImpl.calls[2], evaluated({ country: "NL" }));
    await tick();
    await fail(3);
    assert.equal(adapter.notifications.filter((n) => n.code === "x.y").length, 2);
  } finally {
    uninstallDom();
  }
});

test("a failed FIRST evaluation rejects ready with the server's code and message and disposes the instance", async () => {
  const { fetchImpl, adapter, instance } = boot();
  try {
    await tick();
    fetchImpl.answer(fetchImpl.calls[0], { error: { code: "parameter_set.not_found", message: "no such parameter set version" } }, 404);
    await assert.rejects(instance.ready, (e) => e.code === "parameter_set.not_found" && e.message === "no such parameter set version");
    await tick();
    const published = adapter.notifications.find((n) => n.scope === "bootstrap");
    assert.equal(published.message, "no such parameter set version");
  } finally {
    uninstallDom();
  }
});

test("the composite adapter renders origin and reset marks ONLY when provenance is requested", async () => {
  installDom();
  try {
    const runtime = require(runtimePath);
    const state = evaluated({ country: "NL" });
    state.parameters[0].state.origin = "first";
    state.parameters[0].state.reset = true;
    const marksOf = async (adapterOptions) => {
      const container = fakeElement("div");
      const adapter = runtime.adapters(container, adapterOptions);
      await adapter.mountLayout({});
      await adapter.renderParameters(state);
      const out = [];
      const walk = (node) => {
        if (node.className && /dp-dashboard-parameter-(origin|reset)/.test(node.className)) out.push(node);
        (node.children || []).forEach(walk);
      };
      walk(container);
      return out;
    };
    assert.equal((await marksOf(undefined)).length, 0, "the board's rows changed");
    const marks = await marksOf({ provenance: true });
    assert.equal(marks.length, 2);
    const origin = marks.find((m) => m.attrs["data-dp-origin"]);
    assert.equal(origin.attrs["data-dp-origin"], "first");
    assert.equal(origin.textContent, "first option");
    assert.ok(marks.find((m) => m.attrs["data-dp-reset"] === "true"));
  } finally {
    uninstallDom();
  }
});
