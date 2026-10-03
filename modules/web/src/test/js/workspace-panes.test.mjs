// #426 — the shared pane glue (static/js/workspace/panes.js) wired over a fake DOM with the REAL
// history helper (workspace/history.js) and the REAL tab core (workspace/tabs.js): a tab click
// PUSHES a tab-only `visualizations` entry, Back/Forward re-select through the helper's ONE window
// listener, a root that left the document never runs (the entry goes to htmx), a second wiring keeps
// one listener, and a RESTORED root — cloned markup carrying data-dp-ws-wired="1", no expando — is
// wired again (the case the attribute guard failed). The harness is dashboard-workspace.test.mjs's.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const js = (rel) => path.resolve(here, "../../main/resources/static/js", rel);
const historyPath = js("workspace/history.js");
const tabsPath = js("workspace/tabs.js");
const panesPath = js("workspace/panes.js");

const TABS = ["preview", "overview", "evidence", "used-by", "versions"];

/**
 * A fresh window/document pair (or [win], reused), the REAL scripts loaded over it, and one rendered
 * `.dp-ws-root` wired through `WorkspacePanes.wireWorkspace`. [rootOptions] shapes the root's markup
 * (a restored root carries the wired attribute already); [spec] overrides the wire spec.
 */
function wireOver(startUrl, { win, rootOptions, spec } = {}) {
  const w = win || makeWindow(startUrl);
  const root = makeRoot(TABS, rootOptions);
  w.__docRoot = root;
  globalThis.window = w;
  globalThis.document = {
    readyState: "complete",
    querySelector: (sel) => (sel === ".dp-ws-root" ? w.__docRoot : null),
    addEventListener: () => {},
  };
  for (const p of [historyPath, tabsPath, panesPath]) delete require.cache[p];
  require(historyPath);
  require(tabsPath);
  const panes = require(panesPath);
  const wired = panes.wireWorkspace({ family: "visualizations", tabs: TABS, defaultTab: "preview", ...(spec || {}) });
  return { w, root, wired };
}

function makeWindow(startUrl) {
  const entries = [{ state: null, url: new URL(startUrl, "http://app.test").href }];
  let index = 0;
  const popstate = [];
  const clone = (v) => (v == null ? null : JSON.parse(JSON.stringify(v)));
  const w = {
    htmxRestores: [],
    ajax: [],
    htmx: { ajax: (verb, url) => w.ajax.push(url), process: () => {} },
    location: {
      get href() { return entries[index].url; },
      get pathname() { return new URL(entries[index].url).pathname; },
      get search() { return new URL(entries[index].url).search; },
    },
    history: {
      get state() { return clone(entries[index].state); },
      get length() { return entries.length; },
      pushState(s, _t, u) { entries.splice(index + 1); entries.push({ state: clone(s), url: new URL(u, entries[index].url).href }); index++; },
      replaceState(s, _t, u) { entries[index] = { state: clone(s), url: u ? new URL(u, entries[index].url).href : entries[index].url }; },
    },
    sessionStorage: { map: new Map(), getItem(k) { return this.map.get(k) ?? null; }, setItem(k, v) { this.map.set(k, String(v)); } },
    addEventListener(type, fn) { if (type === "popstate") popstate.push(fn); },
    onpopstate(evt) { if (evt.state && evt.state.htmx) w.htmxRestores.push(w.location.pathname + w.location.search); },
    go(delta) {
      index += delta;
      const evt = { state: clone(entries[index].state) };
      w.onpopstate(evt);
      popstate.forEach((fn) => fn(evt));
    },
    popstateCount: () => popstate.length,
    entries,
  };
  return w;
}

/** One workspace root: five buttons, five panes (the second with a lazy URL), the markup's own attributes. */
function makeRoot(tabs, { wiredAttribute = false, activeTab = "preview" } = {}) {
  const attrs = { "data-active-tab": activeTab };
  if (wiredAttribute) attrs["data-dp-ws-wired"] = "1";
  const buttons = tabs.map((name) => {
    const b = { attrs: { "data-dp-tab": name }, clicks: [] };
    b.getAttribute = (k) => b.attrs[k] ?? null;
    b.setAttribute = (k, v) => (b.attrs[k] = String(v));
    b.addEventListener = (t, fn) => b.clicks.push(fn);
    b.click = () => b.clicks.forEach((fn) => fn());
    return b;
  });
  const panes = Object.fromEntries(tabs.map((name) => {
    const p = { attrs: name === "overview" ? { "data-lazy-url": "/lazy/overview" } : {} };
    p.getAttribute = (k) => p.attrs[k] ?? null;
    p.setAttribute = (k, v) => (p.attrs[k] = String(v));
    p.removeAttribute = (k) => delete p.attrs[k];
    return [name, p];
  }));
  return {
    isConnected: true,
    getAttribute: (k) => attrs[k] ?? null,
    setAttribute: (k, v) => (attrs[k] = String(v)),
    querySelectorAll: () => buttons,
    querySelector: (sel) => panes[/data-dp-pane="([a-z-]+)"/.exec(sel)[1]] || null,
    button: (name) => buttons.find((b) => b.attrs["data-dp-tab"] === name),
    selected: () => buttons.filter((b) => b.attrs["aria-selected"] === "true").map((b) => b.attrs["data-dp-tab"]),
    visible: () => tabs.filter((name) => !("hidden" in panes[name].attrs)),
  };
}

function restoreGlobals() {
  delete globalThis.window;
  delete globalThis.document;
}

test("a tab click PUSHES a tab-only entry; Back and Forward re-select in page", () => {
  try {
    const { w, root } = wireOver("/visualizations/v1?version=2");
    assert.equal(w.entries.length, 1, "first paint pushes nothing");
    root.button("overview").click();
    root.button("versions").click();
    assert.equal(w.entries.length, 3);
    assert.deepEqual(w.entries[0].state, { dpWorkspace: { family: "visualizations", version: null, tab: "preview" } }, "the arrival entry, converted");
    assert.equal(w.entries[2].url, "http://app.test/visualizations/v1?version=2&tab=versions", "the version parameter rides untouched");
    assert.equal(root.getAttribute("data-active-tab"), "versions", "the root's attribute follows the active tab");
    w.go(-1);
    assert.deepEqual(root.selected(), ["overview"]);
    assert.deepEqual(root.visible(), ["overview"]);
    w.go(-1);
    assert.deepEqual(root.selected(), ["preview"]);
    w.go(1);
    w.go(1);
    assert.deepEqual(root.selected(), ["versions"]);
    assert.deepEqual(root.visible(), ["versions"]);
    assert.equal(w.entries.length, 3, "a replay never mints");
    assert.deepEqual(w.htmxRestores, []);
    assert.equal(w.WorkspaceHistory.stats().replayed.visualizations, 4);
    assert.equal(w.ajax.length, 1, "the lazy pane loaded once, on its first activation, never on a replay");
  } finally {
    restoreGlobals();
  }
});

test("re-selecting the active tab mints no entry", () => {
  try {
    const { w, root } = wireOver("/visualizations/v1");
    root.button("preview").click();
    assert.equal(w.entries.length, 1);
    assert.equal(w.entries[0].url, "http://app.test/visualizations/v1?tab=preview");
  } finally {
    restoreGlobals();
  }
});

test("a root that left the document never runs: the entry is handed to htmx", () => {
  try {
    const { w, root } = wireOver("/visualizations/v1");
    root.button("overview").click();
    root.isConnected = false; // a boosted leave swapped #app-main
    root.button("overview").attrs["aria-selected"] = "MARK";
    w.go(-1);
    assert.equal(root.button("overview").attrs["aria-selected"], "MARK", "no write to the detached root");
    assert.deepEqual(w.htmxRestores, ["/visualizations/v1"]);
    assert.equal(w.WorkspaceHistory.stats().handedToHtmx, 1);
    assert.deepEqual(w.WorkspaceHistory.stats().replayed, {});
  } finally {
    restoreGlobals();
  }
});

test("a second wiring keeps ONE window listener, and the LIVE root answers", () => {
  try {
    const { w, root: first } = wireOver("/visualizations/v1");
    first.button("overview").click();
    first.isConnected = false;
    const { root: second } = wireOver("/visualizations/v1", { win: w });
    assert.equal(w.popstateCount(), 1, "one listener per document, never per root");
    assert.equal(w.WorkspaceHistory.stats().listeners, 1);
    w.go(-1);
    assert.deepEqual(second.selected(), ["preview"], "the live root replays");
    assert.deepEqual(w.htmxRestores, []);
  } finally {
    restoreGlobals();
  }
});

test("a RESTORED root carrying data-dp-ws-wired in its markup is wired again", () => {
  try {
    const { w, root, wired } = wireOver("/visualizations/v1?tab=versions", {
      rootOptions: { wiredAttribute: true, activeTab: "versions" },
    });
    assert.ok(wired, "the attribute is a marker, never the guard: a clone of a wired root wires");
    assert.equal(root.__dpWsWired, true, "the guard is the expando");
    assert.deepEqual(root.selected(), ["versions"], "the first paint re-reads the attribute the root left with");
    root.button("overview").click();
    assert.deepEqual(root.selected(), ["overview"], "the strip answers a click");
    assert.equal(w.entries.length, 2);
    // A second pass over the SAME root stays inert.
    const again = require(panesPath).wireWorkspace({ family: "visualizations", tabs: TABS, defaultTab: "preview" });
    assert.equal(again, null, "the same root is never wired twice");
    assert.equal(w.popstateCount(), 1);
  } finally {
    restoreGlobals();
  }
});

test("with no family the glue keeps the replaceState fallback and its own per-root listener", () => {
  try {
    const { w, root } = wireOver("/visualizations/v1", { spec: { family: undefined } });
    root.button("overview").click();
    assert.equal(w.entries.length, 1, "no entry minted without a family");
    assert.equal(w.entries[0].url, "http://app.test/visualizations/v1?tab=overview");
    assert.equal(w.WorkspaceHistory.stats().listeners, 0, "the helper's listener is never installed");
    assert.equal(w.popstateCount(), 1, "the per-root listener of #399 stays on this path only");
  } finally {
    restoreGlobals();
  }
});
