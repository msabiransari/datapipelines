// #399 — the visualization workspace glue's decision core (static/js/visualizations/workspace.js)
// over the shared pane logic (static/js/workspace/panes.js): the closed five-tab set with Preview
// the default (the server's VisualizationWorkspaceModel.Tab, mirrored), the URL carrying `?tab=`
// with every other parameter preserved, and the Preview's case selector read as an index that can
// only name a rendered case.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const js = (rel) => require(path.resolve(here, "../../main/resources/static/js", rel));
const panes = js("workspace/panes.js");
const viz = js("visualizations/workspace.js");
const logic = panes.createLogic(viz.tabs, viz.defaultTab);

test("the closed set is the server's five, Preview first and the default", () => {
  assert.deepEqual(viz.tabs, ["preview", "overview", "evidence", "used-by", "versions"]);
  assert.equal(viz.defaultTab, "preview");
});

test("the URL's tab reads only when the strip admits it; anything else is Preview", () => {
  const rendered = viz.tabs;
  assert.equal(logic.tabFromUrl("/visualizations/x?tab=used-by", rendered), "used-by");
  assert.equal(logic.tabFromUrl("/visualizations/x?version=2&tab=evidence", rendered), "evidence");
  assert.equal(logic.tabFromUrl("/visualizations/x?tab=keys", rendered), "preview");
  assert.equal(logic.tabFromUrl("/visualizations/x?tab=%3Cscript%3E", rendered), "preview");
  assert.equal(logic.tabFromUrl("/visualizations/x", rendered), "preview");
  // A tab the strip did not render is never selected, even when it is in the closed set.
  assert.equal(logic.tabFromUrl("/visualizations/x?tab=versions", ["preview", "overview"]), "preview");
});

test("urlWithTab replaces ONLY tab and keeps the version and the flash", () => {
  assert.equal(logic.urlWithTab("/visualizations/x?version=2&tab=overview", "versions"), "/visualizations/x?version=2&tab=versions");
  assert.equal(logic.urlWithTab("/visualizations/x", "evidence"), "/visualizations/x?tab=evidence");
  assert.equal(logic.urlWithTab("/visualizations/x?tab=versions&ok=released", "preview"), "/visualizations/x?tab=preview&ok=released");
});

test("the admitted set keeps the closed set's order, whatever order the strip rendered", () => {
  assert.deepEqual(logic.admittedTabs(["versions", "preview", "bogus"]), ["preview", "versions"]);
});

test("a case selector value names a rendered case or falls back to the first", () => {
  assert.equal(viz.caseIndexFrom("2", 3), 2);
  assert.equal(viz.caseIndexFrom(0, 3), 0);
  assert.equal(viz.caseIndexFrom("3", 3), 0, "out of range");
  assert.equal(viz.caseIndexFrom("-1", 3), 0, "negative");
  assert.equal(viz.caseIndexFrom("1.5", 3), 0, "not an integer");
  assert.equal(viz.caseIndexFrom("x", 3), 0);
  assert.equal(viz.caseIndexFrom(undefined, 3), 0);
  assert.equal(viz.caseIndexFrom("0", 0), 0);
});

// #437 — the Preview afterSwap lifecycle. The REAL scripts (workspace/history.js, tabs.js, panes.js and
// visualizations/workspace.js) are evaluated again and again over ONE fake window/document/body, a fresh
// connected root each time (a restored root, #426), and the body's REAL htmx:afterSwap registrations are
// counted — never a counter the production code reports about itself. The mount is a recording fixture:
// it returns an instance that counts its re-fits, so "the same state answers" is observable.

const historyPath = path.resolve(here, "../../main/resources/static/js/workspace/history.js");
const tabsPath = path.resolve(here, "../../main/resources/static/js/workspace/tabs.js");
const panesPath = path.resolve(here, "../../main/resources/static/js/workspace/panes.js");
const vizPath = path.resolve(here, "../../main/resources/static/js/visualizations/workspace.js");

/** An element double: attributes, hidden handling and a strict selector table (an unknown selector throws). */
function element(attrs = {}) {
  const el = {
    attrs: { ...attrs },
    isConnected: true,
    getAttribute: (k) => el.attrs[k] ?? null,
    setAttribute: (k, v) => (el.attrs[k] = String(v)),
    removeAttribute: (k) => delete el.attrs[k],
  };
  return el;
}

/** One document: a body that records every real htmx:afterSwap registration, and the workspace on show. */
function makeEnv() {
  const afterSwap = [];
  const env = {
    registrations: 0,
    mounts: [],
    ajax: [],
    afterSwap,
    current: null,
    body: {
      addEventListener(type, fn) {
        if (type !== "htmx:afterSwap") return;
        env.registrations += 1;
        afterSwap.push(fn);
      },
    },
    /** A swap on [target]: every registered listener runs, as htmx's trigger would run them. */
    swap(target, detail = { target }) {
      const event = detail === undefined ? {} : { detail };
      afterSwap.slice().forEach((fn) => fn(event));
    },
  };
  env.window = {
    ajax: env.ajax,
    htmx: { ajax: (verb, url) => env.ajax.push(url) },
    location: {
      href: "http://app.test/visualizations/v1",
      pathname: "/visualizations/v1",
      search: "",
    },
    history: { state: null, pushState() {}, replaceState() {} },
    sessionStorage: { getItem: () => null, setItem() {} },
    addEventListener() {},
    DatapipelinesDashboard: { runtime: true },
    // The recording fixture mount: it answers like the capability page's mount and counts re-fits.
    DatapipelinesPreviewMount(runtime, data, testCase, section, board) {
      const instance = {
        index: data.cases.indexOf(testCase),
        owner: section.owner,
        resizes: 0,
        disposals: 0,
        resize() {
          instance.resizes += 1;
        },
        dispose() {
          instance.disposals += 1;
          board.removeAttribute("data-datapipelines-dashboard");
        },
      };
      board.setAttribute("data-datapipelines-dashboard", "live-instance");
      env.mounts.push(instance);
      return instance;
    },
  };
  env.document = {
    readyState: "complete",
    body: env.body,
    addEventListener: () => {},
    getElementById: () => null,
    // Strict: the family selector is part of the contract this harness pins.
    querySelector(sel) {
      const ws = env.current;
      if (sel === ".dp-ws-root") return ws ? ws.root : null;
      if (sel === ".viz-workspace .dp-ws-root") return ws && ws.family === "visualizations" ? ws.root : null;
      throw new Error("unexpected document selector: " + sel);
    },
  };
  return env;
}

const TAB_NAMES = ["preview", "overview", "evidence", "used-by", "versions"];

/** A Preview partial for [cases] case names: the data block, the selector and one section + board per case. */
function previewPartial(pane, cases, selected = "0") {
  const data = { visualization: "viz", cases: cases.map((name) => ({ name })) };
  const block = element();
  block.textContent = JSON.stringify(data);
  const select = element();
  select.value = selected;
  select.listeners = [];
  select.addEventListener = (type, fn) => select.listeners.push(fn);
  select.change = (value) => {
    select.value = value;
    select.listeners.forEach((fn) => fn());
  };
  const sections = cases.map((_, i) => Object.assign(element({ "data-viz-case-index": String(i) }), { owner: pane }));
  const boards = cases.map(() => Object.assign(element(), { clears: 0, replaceChildren() { this.clears += 1; } }));
  return { data, block, select, sections, boards };
}

test("history saves only the live Preview's clean fixture shells, through one current cleanup", () => {
  const env = makeEnv();
  try {
    const old = restoreWorkspace(env, { arrived: CASES });
    old.root.isConnected = false;
    const ws = restoreWorkspace(env, { arrived: CASES });
    const partial = ws.pane.partial;
    partial.select.change("1");
    partial.sections.forEach((s) => s.setAttribute("data-dp-ready", "true"));
    partial.sections[2].setAttribute("data-dp-error", "old-error");
    evaluateScripts(env);
    const cleanups = env.window.__dpHistoryStyleCleanups;
    assert.equal(cleanups.length, 1, "one cleanup across restored roots and repeat evaluations");
    const historyRoot = { contains: (p) => p === ws.pane };
    cleanups[0]({ contains: () => false });
    assert.deepEqual(env.mounts.map((m) => m.disposals), [0, 0, 0], "no cleanup outside the history region");
    cleanups[0](historyRoot);
    assert.deepEqual(env.mounts.map((m) => m.disposals), [0, 1, 1], "only the current instances are disposed");
    assert.deepEqual(Object.keys(env.window.VisualizationWorkspacePreview.instances), []);
    assert.deepEqual(partial.boards.map((b) => b.getAttribute("data-datapipelines-dashboard")), [null, null, null]);
    assert.deepEqual(partial.boards.map((b) => b.clears), [1, 1, 1], "no generated styled chart DOM survives");
    assert.ok(partial.sections.every((s) => s.getAttribute("data-dp-ready") === null && s.getAttribute("data-dp-error") === null));
    assert.equal(partial.block.getAttribute("data-viz-wired"), "1", "the cached block still carries its marker");
    assert.equal(partial.select.value, "1", "case selection survives the cleanup");
    cleanups[0](historyRoot);
    assert.deepEqual(env.mounts.map((m) => m.disposals), [0, 1, 1], "repeated saves never dispose an instance twice");
    env.current = makeWorkspace("dashboards");
    cleanups[0](historyRoot);
    assert.deepEqual(env.mounts.map((m) => m.disposals), [0, 1, 1], "another family is untouched");
  } finally {
    restoreGlobals();
  }
});

/** One root with five tab buttons and panes; the Preview pane serves whatever partial [arrive] installed. */
function makeWorkspace(family) {
  const buttons = TAB_NAMES.map((name) => {
    const b = element({ "data-dp-tab": name });
    b.clicks = [];
    b.addEventListener = (t, fn) => b.clicks.push(fn);
    b.click = () => b.clicks.forEach((fn) => fn());
    return b;
  });
  const panes = Object.fromEntries(TAB_NAMES.map((name) => [name, element(name === "preview" ? { "data-lazy-url": "/lazy/preview" } : {})]));
  const pane = panes.preview;
  pane.partial = null;
  pane.querySelector = (sel) => {
    const p = pane.partial;
    if (!p) return null;
    if (sel === "#viz-preview-data") return p.block;
    if (sel === "[data-viz-case-select]") return p.select;
    let m = /^\[data-viz-case-index="(\d+)"\]$/.exec(sel);
    if (m) return p.sections[Number(m[1])] || null;
    m = /^\[data-viz-case-board="(\d+)"\]$/.exec(sel);
    if (m) return p.boards[Number(m[1])] || null;
    throw new Error("unexpected preview-pane selector: " + sel);
  };
  pane.querySelectorAll = (sel) => {
    if (sel !== "[data-viz-case-index]") throw new Error("unexpected preview-pane selector: " + sel);
    return pane.partial ? pane.partial.sections : [];
  };
  /** The partial lands in the pane (a lazy load, or a fast first paint before the glue ran). */
  pane.arrive = (cases, selected) => {
    pane.partial = previewPartial(pane, cases, selected);
    return pane.partial;
  };
  const root = element({ "data-active-tab": "preview" });
  root.querySelectorAll = () => buttons;
  root.querySelector = (sel) => {
    const m = /^\[data-dp-pane="([a-z-]+)"\]$/.exec(sel);
    if (!m || !panes[m[1]]) throw new Error("unexpected root selector: " + sel);
    return panes[m[1]];
  };
  root.button = (name) => buttons.find((b) => b.attrs["data-dp-tab"] === name);
  return { family, root, pane };
}

/**
 * Puts a fresh [family] workspace on show and evaluates the REAL scripts over the one window — what a
 * restored (cloned) root's re-run does. [arrived] = the cases of a Preview partial already in the pane
 * when the glue runs (the fast first paint); omit for a pane still waiting for its partial.
 */
function restoreWorkspace(env, { family = "visualizations", arrived = null } = {}) {
  const ws = makeWorkspace(family);
  if (arrived) ws.pane.arrive(arrived);
  env.current = ws;
  evaluateScripts(env);
  return ws;
}

/** The scripts evaluated once more over whatever workspace is on show (a second pass over the SAME root too). */
function evaluateScripts(env) {
  globalThis.window = env.window;
  globalThis.document = env.document;
  for (const p of [historyPath, tabsPath, panesPath, vizPath]) delete require.cache[p];
  for (const p of [historyPath, tabsPath, panesPath, vizPath]) require(p);
}

function restoreGlobals() {
  delete globalThis.window;
  delete globalThis.document;
}

const CASES = ["first", "second", "third"];

test("N restored-root wirings leave ONE document afterSwap listener, never one per wiring", () => {
  const env = makeEnv();
  try {
    for (let i = 0; i < 4; i++) restoreWorkspace(env);
    assert.equal(env.registrations, 1, "htmx:afterSwap registrations on the body after 4 wirings");
    assert.equal(env.afterSwap.length, 1, "live htmx:afterSwap listeners on the body after 4 wirings");
  } finally {
    restoreGlobals();
  }
});

test("a swap of the LATEST pane mounts its fixture once and publishes the CURRENT glue's state", () => {
  const env = makeEnv();
  try {
    restoreWorkspace(env);
    const second = restoreWorkspace(env);
    second.pane.arrive(CASES);
    env.swap(second.pane);
    const earlier = env.window.VisualizationWorkspacePreview;
    assert.deepEqual(env.mounts.map((m) => m.index), [0], "the selected case mounted once");
    assert.equal(env.mounts[0].owner, second.pane);

    const third = restoreWorkspace(env);
    third.pane.arrive(CASES, "1");
    env.swap(third.pane);
    const current = env.window.VisualizationWorkspacePreview;
    assert.notEqual(current, earlier, "the published state is the third evaluation's, not the second's");
    assert.deepEqual(env.mounts.map((m) => [m.index, m.owner === third.pane]), [[0, false], [1, true]], "one mount for the latest pane");
    assert.deepEqual(Object.keys(current.instances), ["1"]);
    assert.equal(current.instances[1], env.mounts[1]);
    assert.equal(env.afterSwap.length, 1);
  } finally {
    restoreGlobals();
  }
});

test("case selection and reveal read the SAME state the swap published", () => {
  const env = makeEnv();
  try {
    restoreWorkspace(env);
    restoreWorkspace(env);
    const ws = restoreWorkspace(env);
    const partial = ws.pane.arrive(CASES);
    env.swap(ws.pane);
    const state = env.window.VisualizationWorkspacePreview;
    assert.equal(state.data.cases.length, 3);

    partial.select.change("2");
    assert.deepEqual(env.mounts.map((m) => m.index), [0, 2], "the selector mounts the chosen case lazily");
    assert.deepEqual(Object.keys(state.instances), ["0", "2"], "the mount landed in the published state");
    assert.equal(partial.sections[2].attrs.hidden, undefined, "the chosen case is shown");
    assert.equal(partial.sections[0].attrs.hidden, "hidden");

    ws.root.button("overview").click();
    assert.equal(env.mounts[1].resizes, 0);
    ws.root.button("preview").click();
    assert.equal(env.mounts[1].resizes, 1, "a revealed Preview re-fits the visible case from the same state");
    assert.equal(env.mounts.length, 2, "a re-fit is not a second mount");

    partial.select.change("2");
    assert.equal(env.mounts[1].resizes, 2, "a re-selected case re-fits");
    partial.select.change("x");
    assert.equal(partial.sections[0].attrs.hidden, undefined, "a value naming no case falls back to the first");
    assert.equal(env.mounts[0].resizes, 1, "the fallback case re-fits its existing instance");
    assert.equal(env.mounts.length, 2);
  } finally {
    restoreGlobals();
  }
});

test("a second evaluation over a root already wired leaves the first evaluation's state in charge", () => {
  const env = makeEnv();
  try {
    const ws = restoreWorkspace(env);
    evaluateScripts(env); // wireWorkspace answers null: the root is wired, so this pass owns nothing
    assert.equal(env.afterSwap.length, 1);
    const partial = ws.pane.arrive(CASES);
    env.swap(ws.pane);
    assert.deepEqual(env.mounts.map((m) => m.index), [0]);
    partial.select.change("1");
    ws.root.button("overview").click();
    ws.root.button("preview").click();
    assert.deepEqual(env.mounts.map((m) => m.index), [0, 1]);
    assert.equal(env.mounts[1].resizes, 1, "the reveal re-fits the case the selector mounted: swap, selector and reveal read one state");
  } finally {
    restoreGlobals();
  }
});

test("a swap landing on a pane that left the document, or any other target, does nothing", () => {
  const env = makeEnv();
  try {
    const old = restoreWorkspace(env);
    old.pane.arrive(CASES);
    old.root.isConnected = false; // a boosted leave swapped #app-main; its lazy load is still in flight
    const live = restoreWorkspace(env);
    env.swap(old.pane);
    assert.deepEqual(env.mounts, [], "no mount from a pane that left the document");
    assert.equal(old.pane.partial.block.getAttribute("data-viz-wired"), null, "the detached pane's block was never read");
    assert.equal(env.window.VisualizationWorkspacePreview, undefined, "no state published by a detached pane");

    // A well-formed Preview pane that is NOT the one on show (never attached to this document).
    const stray = makeWorkspace("visualizations");
    stray.pane.arrive(CASES);
    env.swap(stray.pane);
    assert.deepEqual(env.mounts, [], "no mount from a foreign Preview pane");
    assert.equal(stray.pane.partial.block.getAttribute("data-viz-wired"), null, "the foreign pane's block was never read");

    // The shell's own swaps: a region with no Preview inside, a missing target, a missing detail.
    const region = element();
    region.querySelector = () => null;
    region.querySelectorAll = () => [];
    env.swap(region);
    env.swap(undefined, undefined);
    env.swap(undefined, {});
    env.swap(null, { target: null });
    assert.deepEqual(env.mounts, []);
    assert.equal(env.window.VisualizationWorkspacePreview, undefined);

    // The live pane still answers.
    live.pane.arrive(CASES);
    env.swap(live.pane);
    assert.deepEqual(env.mounts.map((m) => m.index), [0]);
    assert.equal(env.mounts[0].owner, live.pane);
  } finally {
    restoreGlobals();
  }
});

test("a swap of the live pane with no Preview block does nothing, and a later partial still wires", () => {
  const env = makeEnv();
  try {
    const ws = restoreWorkspace(env);
    env.swap(ws.pane); // the lazy load's "Loading preview…" placeholder: no partial yet
    assert.deepEqual(env.mounts, []);
    assert.equal(env.window.VisualizationWorkspacePreview, undefined);
    ws.pane.arrive(CASES);
    env.swap(ws.pane);
    assert.deepEqual(env.mounts.map((m) => m.index), [0]);
  } finally {
    restoreGlobals();
  }
});

test("a non-visualization workspace on show is never answered, though its pane carries the same marker", () => {
  const env = makeEnv();
  try {
    restoreWorkspace(env);
    const dashboards = makeWorkspace("dashboards"); // a boosted arrival of another family
    dashboards.pane.arrive(CASES);
    env.current = dashboards;
    env.swap(dashboards.pane);
    assert.deepEqual(env.mounts, [], "the dashboards pane is not the visualization workspace's Preview");
    assert.equal(env.window.VisualizationWorkspacePreview, undefined);
    assert.equal(dashboards.pane.partial.block.getAttribute("data-viz-wired"), null);
  } finally {
    restoreGlobals();
  }
});

test("the fast first paint wires an already-arrived partial at once, and a repeated swap never mounts it twice", () => {
  const env = makeEnv();
  try {
    const ws = restoreWorkspace(env, { arrived: CASES });
    assert.deepEqual(env.mounts.map((m) => m.index), [0], "no swap needed: the arrived partial mounted at wiring");
    assert.equal(env.window.VisualizationWorkspacePreview.data.cases.length, 3);
    env.swap(ws.pane);
    env.swap(ws.pane);
    assert.equal(env.mounts.length, 1, "an already-wired block is not mounted again");
    assert.equal(env.registrations, 1);

    // A fresh partial landing in the same pane (a new swap) is a new block: it wires once.
    ws.pane.arrive(["only"]);
    env.swap(ws.pane);
    env.swap(ws.pane);
    assert.equal(env.mounts.length, 2);
    assert.equal(env.window.VisualizationWorkspacePreview.data.cases.length, 1);
  } finally {
    restoreGlobals();
  }
});

test("a selector value outside the rendered cases mounts the first case on wiring", () => {
  const env = makeEnv();
  try {
    const ws = restoreWorkspace(env);
    ws.pane.arrive(CASES, "99");
    env.swap(ws.pane);
    assert.deepEqual(env.mounts.map((m) => m.index), [0]);
  } finally {
    restoreGlobals();
  }
});

for (const selected of ["0", "1"]) {
  test(`a cached block carrying the wired marker mounts selected case ${selected} and keeps one live wiring`, () => {
    const env = makeEnv();
    try {
      restoreWorkspace(env, { arrived: CASES });
      const earlier = env.window.VisualizationWorkspacePreview;
      const ws = makeWorkspace("visualizations");
      const partial = ws.pane.arrive(CASES, selected);
      // htmx restores innerHTML: attributes survive; node expandos and listeners do not.
      partial.block.setAttribute("data-viz-wired", "1");
      env.current = ws;
      evaluateScripts(env);
      assert.equal(env.mounts.length, 2, "the cached block must mount its selected case afresh");
      const state = env.window.VisualizationWorkspacePreview;
      const initial = Number(selected);
      const other = initial === 0 ? 1 : 0;
      assert.notEqual(state, earlier, "the restored evaluation publishes current state");
      assert.equal(state.instances[initial], env.mounts[1]);
      assert.equal(env.mounts[1].owner, ws.pane);
      assert.equal(env.mounts[1].index, initial);
      assert.equal(partial.select.listeners.length, 1, "one selector registration");
      assert.equal(partial.block.getAttribute("data-viz-wired"), "1", "visible marker retained");

      env.swap(ws.pane);
      env.swap(ws.pane);
      evaluateScripts(env);
      assert.equal(env.mounts.length, 2, "repeated swaps and evaluation never mount the live block again");
      assert.equal(partial.select.listeners.length, 1, "repeated wiring never adds a selector listener");
      assert.equal(env.registrations, 1, "one document swap listener across evaluations");
      assert.equal(env.window.VisualizationWorkspacePreview, state);

      partial.select.change(String(other));
      assert.equal(env.mounts.length, 3, "the second case mounts only on selection");
      assert.equal(state.instances[other], env.mounts[2]);
      assert.equal(partial.sections[other].getAttribute("hidden"), null);
      assert.equal(partial.sections[initial].getAttribute("hidden"), "hidden");
      partial.select.change(selected);
      partial.select.change(String(other));
      assert.equal(env.mounts[2].resizes, 1, "revisiting a mounted case re-fits it");
      ws.root.button("overview").click();
      ws.root.button("preview").click();
      assert.equal(env.mounts[2].resizes, 2, "revealing Preview re-fits the current instance");
      assert.equal(env.mounts.length, 3);
      assert.equal(env.window.VisualizationWorkspacePreview, state);
    } finally {
      restoreGlobals();
    }
  });
}
