// #526 — the dashboard runtime's missing-marker fallback (`_ensureStyleElement`) must create a
// CSP-COMPLIANT opt-out marker. Chromium refuses EVERY script-created `<style>` element at
// insertion as an inline style under `style-src 'self'` (with a text child, with an empty
// comment, or childless — each refused with the empty-text hash), so the fallback creates the
// marker as a TEMPLATE — the same shape every first-party host pre-places (#456). Plotly's check
// is the id and the class alone (`getElementById` + `matches(".no-inline-styles")`), never the
// tag or a sheet. The fallback only serves third-party hosts (the embed/proxy case). Same
// harness decision as dashboard-runtime.test.mjs: no packages, no config — a hand-rolled
// document double and a scripted fetch before requiring the IIFE (which publishes
// window.DatapipelinesDashboard and nothing else).

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const runtimePath = path.resolve(here, "../../main/resources/static/js/datapipelines-dashboard.js");

// ---------------------------------------------------------------------------------------------- fake DOM

const STYLE_ID = "plotly.js-style-global";

function fakeElement(tag) {
  const attrs = {};
  const children = [];
  const el = {
    tagName: (tag || "div").toUpperCase(),
    attrs,
    children,
    className: "",
    parentNode: null,
    setAttribute(k, v) {
      attrs[k] = String(v);
    },
    getAttribute(k) {
      return k in attrs ? attrs[k] : null;
    },
    appendChild(child) {
      child.parentNode = el;
      children.push(child);
      return child;
    },
  };
  return el;
}

function installDom() {
  const byId = {};
  const doc = {
    head: fakeElement("head"),
    getElementById: (id) => byId[id] || null,
    createElement: (tag) => fakeElement(tag),
    createTextNode: (text) => ({ nodeType: 3, text: String(text) }),
  };
  // Register an appended child under its id attribute, exactly as the live DOM would.
  doc.head.appendChild = (child) => {
    child.parentNode = doc.head;
    if (child.getAttribute("id")) byId[child.getAttribute("id")] = child;
    return child;
  };
  globalThis.document = doc;
  globalThis.window = { document: doc };
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

/** The configuration read and the three no-op reads bootstrap needs; no refresh is started. */
function fakeFetch() {
  const json = (payload) => ({
    ok: true,
    status: 200,
    text: async () => JSON.stringify(payload),
  });
  const impl = (url) => {
    if (url.includes("/runtime/config")) {
      return Promise.resolve(
        json({
          data: {
            configuration_id: "cfg-1",
            renderer: { bundle: "2d" },
            dashboard: { id: "d1", name: "dbr/board", version: 1, status: "RELEASED" },
            layout: { columns: 12, grid: [] },
            parameter_set: null,
            visualizations: [],
            groups: [],
            actions: [],
            action_controls: [],
            parameter_scopes: {},
            parameter_state: null,
            timeouts: { refresh_seconds: 300, parameter_lock_seconds: 30, render_seconds: 20 },
            budgets: { max_bytes_per_source: 1, max_bytes_per_refresh: 2 },
          },
        }),
      );
    }
    if (url.includes("/runtime/parameters")) return Promise.resolve(json({ data: null }));
    if (url.includes("/runtime/visualizations")) return Promise.resolve(json({ data: [] }));
    return Promise.reject(new Error("no scripted handler for " + url));
  };
  return impl;
}

// ----------------------------------------------------------------------------------------------- the adapter

/** The twelve functions §10.3 requires; boot judges their presence before anything runs. */
function scriptedAdapter() {
  return {
    mountLayout: () => Promise.resolve(),
    mountVisualization: () =>
      Promise.resolve({ renderData: () => Promise.resolve("rendered") }),
    renderParameters: () => Promise.resolve(),
    readSelections: () => ({}),
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

async function boot() {
  const doc = installDom();
  globalThis.fetch = fakeFetch();
  const runtime = require(runtimePath);
  const instance = runtime.init({
    server: { baseUrl: "", credentials: "session" },
    dashboard: { id: "d1", version: "released" },
    container: fakeElement("div"),
    adapter: scriptedAdapter(),
    options: { renderTimeoutMs: 200 },
  });
  await instance.ready;
  return { instance, doc };
}

// --------------------------------------------------------------------------------------------------- tests

test("the missing-marker fallback creates a CSP-compliant marker", async () => {
  const { instance, doc } = await boot();

  try {
    // Bootstrap step 1 ran with NO host marker: the fallback created the marker itself.
    const created = doc.getElementById(STYLE_ID);
    assert.ok(created, "the fallback created the opt-out marker");
    assert.equal(created.tagName, "TEMPLATE", "the marker is a template — Chromium refuses every " +
      "script-created <style> element at insertion, whatever its children");
    assert.match(created.className, /(^| )no-inline-styles( |$)/, "Plotly's check matches the class");
    assert.equal(
      created.childNodes ? created.childNodes.length : created.children.length,
      0,
      "the marker carries NO child nodes",
    );

    // Idempotence: an existing element without the class gains it exactly once.
    created.className = "";
    instance._ensureStyleElement();
    const occurrences = (created.className.match(/no-inline-styles/g) || []).length;
    assert.equal(occurrences, 1, "the class is gained exactly once");

    // A second call is a no-op — no duplicated class, still no children.
    instance._ensureStyleElement();
    assert.equal((created.className.match(/no-inline-styles/g) || []).length, 1);
    assert.equal(created.childNodes ? created.childNodes.length : created.children.length, 0);
  } finally {
    instance.dispose();
    uninstallDom();
  }
});
