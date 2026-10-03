// #400 — the dashboard workspace glue's decision core (static/js/dashboards/workspace.js):
// the rendered strip IS the admission (Keys renders only for a caller with the binding
// permission), the URL carries `?tab=` with every other parameter preserved, and an
// unknown/unadmitted tab reads as Board — the server's own resolution, mirrored.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const logicPath = path.resolve(here, "../../main/resources/static/js/dashboards/workspace.js");
const logic = require(logicPath);

test("the admitted set is the rendered strip's, in the closed set's order", () => {
  assert.deepEqual(logic.admittedTabs(["board", "overview", "refreshes", "versions", "keys"]), ["board", "overview", "refreshes", "versions", "keys"]);
  // No Keys button rendered (an author): the tab does not exist for them at all.
  assert.deepEqual(logic.admittedTabs(["board", "overview", "refreshes", "versions"]), ["board", "overview", "refreshes", "versions"]);
});

test("the URL's tab reads only when the strip admits it; anything else is Board", () => {
  const rendered = ["board", "overview", "refreshes", "versions"];
  assert.equal(logic.tabFromUrl("/dashboards/x?tab=versions", rendered), "versions");
  assert.equal(logic.tabFromUrl("/dashboards/x?version=2&tab=overview", rendered), "overview");
  assert.equal(logic.tabFromUrl("/dashboards/x?tab=keys", rendered), "board", "an unrendered tab is Board, never a hidden pane");
  assert.equal(logic.tabFromUrl("/dashboards/x?tab=nonsense", rendered), "board");
  assert.equal(logic.tabFromUrl("/dashboards/x", rendered), "board");
});

test("urlWithTab replaces ONLY tab and keeps the other parameters", () => {
  assert.equal(logic.urlWithTab("/dashboards/x?version=2&tab=overview", "versions"), "/dashboards/x?version=2&tab=versions");
  assert.equal(logic.urlWithTab("/dashboards/x", "overview"), "/dashboards/x?tab=overview");
  assert.equal(logic.urlWithTab("/dashboards/x?tab=board&ok=released", "versions"), "/dashboards/x?tab=versions&ok=released");
});

// #402 — the tab strip's history entries ride the shared helper (workspace/history.js):
// a click PUSHES a tab-only `dashboards` entry, Back/Forward re-select through the helper's ONE
// window listener, and a root that left the document never runs (the entry goes to htmx).

const historyPath = path.resolve(here, "../../main/resources/static/js/workspace/history.js");

/** A fresh window/document pair with one rendered `.dp-ws-root`, then the REAL glue wired over it. */
function wireOver(startUrl, { win } = {}) {
  const w = win || makeWindow(startUrl);
  const root = makeRoot(["board", "overview", "refreshes", "versions"]);
  w.__docRoot = root;
  globalThis.window = w;
  globalThis.document = {
    readyState: "complete",
    querySelector: (sel) => (sel === ".dp-ws-root" ? w.__docRoot : null),
    addEventListener: () => {},
  };
  for (const p of [historyPath, logicPath]) delete require.cache[p];
  require(historyPath);
  require(logicPath);
  return { w, root };
}

function makeWindow(startUrl) {
  const entries = [{ state: null, url: new URL(startUrl, "http://app.test").href }];
  let index = 0;
  const popstate = [];
  const clone = (v) => (v == null ? null : JSON.parse(JSON.stringify(v)));
  const w = {
    htmxRestores: [],
    htmx: { ajax: () => {}, process: () => {} },
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

function makeRoot(tabs) {
  const attrs = { "data-active-tab": "board" };
  const buttons = tabs.map((name) => {
    const b = { attrs: { "data-dp-tab": name }, clicks: [] };
    b.getAttribute = (k) => b.attrs[k] ?? null;
    b.setAttribute = (k, v) => (b.attrs[k] = String(v));
    b.addEventListener = (t, fn) => b.clicks.push(fn);
    b.click = () => b.clicks.forEach((fn) => fn());
    return b;
  });
  const panes = Object.fromEntries(tabs.map((name) => {
    const p = { attrs: {} };
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
    querySelector: (sel) => panes[/data-dp-pane="([a-z]+)"/.exec(sel)[1]] || null,
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
    const { w, root } = wireOver("/dashboards/d1?version=2");
    assert.equal(w.entries.length, 1, "first paint pushes nothing");
    root.button("overview").click();
    root.button("versions").click();
    assert.equal(w.entries.length, 3);
    assert.deepEqual(w.entries[0].state, { dpWorkspace: { family: "dashboards", version: null, tab: "board" } }, "the arrival entry, converted");
    assert.equal(w.entries[2].url, "http://app.test/dashboards/d1?version=2&tab=versions", "the version parameter rides untouched");
    w.go(-1);
    assert.deepEqual(root.selected(), ["overview"]);
    assert.deepEqual(root.visible(), ["overview"]);
    w.go(-1);
    assert.deepEqual(root.selected(), ["board"]);
    w.go(1);
    w.go(1);
    assert.deepEqual(root.selected(), ["versions"]);
    assert.deepEqual(root.visible(), ["versions"]);
    assert.equal(w.entries.length, 3, "a replay never mints");
    assert.deepEqual(w.htmxRestores, []);
  } finally {
    restoreGlobals();
  }
});

test("re-selecting the active tab mints no entry", () => {
  try {
    const { w, root } = wireOver("/dashboards/d1");
    root.button("board").click();
    assert.equal(w.entries.length, 1);
    assert.equal(w.entries[0].url, "http://app.test/dashboards/d1?tab=board");
  } finally {
    restoreGlobals();
  }
});

test("a root that left the document never runs: the entry is handed to htmx", () => {
  try {
    const { w, root } = wireOver("/dashboards/d1");
    root.button("overview").click();
    root.isConnected = false; // a boosted leave swapped #app-main
    root.button("overview").attrs["aria-selected"] = "MARK";
    w.go(-1);
    assert.equal(root.button("overview").attrs["aria-selected"], "MARK", "no write to the detached root");
    assert.deepEqual(w.htmxRestores, ["/dashboards/d1"]);
  } finally {
    restoreGlobals();
  }
});

test("a second wiring (a restored root) keeps ONE window listener, and the LIVE root answers", () => {
  try {
    const { w, root: first } = wireOver("/dashboards/d1");
    first.button("overview").click();
    first.isConnected = false;
    const { root: second } = wireOver("/dashboards/d1", { win: w });
    assert.equal(w.popstateCount(), 1, "one listener per document, never per root");
    assert.equal(w.WorkspaceHistory.stats().listeners, 1);
    w.go(-1);
    assert.deepEqual(second.selected(), ["board"], "the live root replays");
    assert.deepEqual(w.htmxRestores, []);
  } finally {
    restoreGlobals();
  }
});
