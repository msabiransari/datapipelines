// 151 addendum (#144) — the execution boundaries redesigned: shapes that cannot be
// mistaken for nodes, and Start is the run trigger.
//
// Pinned here, the truth table the addendum asks for — start/end × idle/running/
// finished/failed/stopped × canExecute true/false:
//   1. START is a disc. When the viewer MAY execute it is a real button (`role="button"`,
//      focusable, `aria-label="Start execution"`); while a run is active it is the CANCEL
//      control (159/#148: `aria-label="Cancel execution"`, the word Cancel, the square
//      glyph, never `aria-disabled`); when they may not, a plain marker (`role="img"`)
//      with no affordance in every state.
//   2. END is the terminal shape: the outcome word (Finished / Failed / Stopped), the
//      elapsed time when known, never a button.
//   3. ACTIVATION calls the live component's `executePipeline` exactly once — click, Enter
//      or Space — while idle, and its `cancelExecution` exactly once while a run is active
//      (the SAME methods the toolbar calls). With the wiring removed this file is red.
//   3b. THE PRESS GUARD (159/#148): a pointer press on the disc is stopped at the canvas in
//      the CAPTURE phase so Cytoscape never `activate()`s the marker node — that
//      activation emitted `style`, the html-label re-rendered the disc a few ms later,
//      and a human's mouseup landed on a NEW element, so no click was ever dispatched.
//   3c. FOCUS survives the disc's re-render: the replacement disc is re-focused when the
//      focused one was removed, and never when focus moved elsewhere on purpose.
//   4. The markers keep their 150 contract: `kind: "boundary"`, no ports, nothing to open.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");

function loadGraph() {
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}

const marker = (side, state, extra) =>
  loadGraph().buildCardHtml(Object.assign({ id: "__execution_" + side + "__", kind: "boundary", boundary: side, state }, extra || {}));

const attr = (html, name) => {
  const m = html.match(new RegExp(name + '="([^"]*)"'));
  return m ? m[1] : null;
};

/* ----------------------------------------------------------------- start */

test("Start, may execute, idle: a real button — role, focus, label, no disabled", () => {
  const html = marker("start", "idle", { canExecute: true });
  assert.match(html, /class="pe-marker pe-marker-start pe-marker-run"/);
  assert.equal(attr(html, "role"), "button");
  assert.equal(attr(html, "tabindex"), "0");
  assert.equal(attr(html, "aria-label"), "Start execution");
  assert.ok(!html.includes("aria-disabled"), "enabled while idle");
  assert.match(html, /pe-marker-word">Start</);
  assert.match(html, /lucide-sprite\.svg#play/);
  assert.ok(!html.includes("pe-card-port") && !html.includes("pe-card-open") && !html.includes("pe-port "), "a marker is not a node");
});

test("Start, may execute, running: the Cancel control — a button, never aria-disabled, reads Cancel", () => {
  const html = marker("start", "running", { canExecute: true });
  assert.match(html, /class="pe-marker pe-marker-start pe-marker-run pe-marker-cancel"/);
  assert.equal(attr(html, "role"), "button");
  assert.equal(attr(html, "tabindex"), "0");
  assert.ok(!html.includes("aria-disabled"), "Cancel is live, not disabled");
  assert.equal(attr(html, "aria-label"), "Cancel execution");
  assert.match(html, /pe-marker-word">Cancel</);
  assert.match(html, /lucide-sprite\.svg#square/, "the toolbar's Cancel glyph, not the play triangle");
  assert.ok(!html.includes("lucide-sprite.svg#play"));
  assert.match(html, /pe-card-running/);
  assert.match(html, /class="pe-card pe-card-boundary pe-card-boundary-start pe-card-running pe-card-cancel"/, "the card root carries the cancel state for the stylesheet");
});

test("idle → running → idle: the disc goes Start → Cancel → Start, and only the running state carries the cancel class", () => {
  const idle = marker("start", "idle", { canExecute: true });
  const running = marker("start", "running", { canExecute: true });
  assert.match(idle, /pe-marker-word">Start</);
  assert.ok(!idle.includes("pe-marker-cancel"));
  assert.equal(attr(idle, "aria-label"), "Start execution");
  assert.match(running, /pe-marker-word">Cancel</);
  for (const terminal of ["success", "failed", "aborted"]) {
    const back = marker("start", terminal, { canExecute: true });
    assert.match(back, /pe-marker-word">Start</, terminal);
    assert.ok(!back.includes("pe-marker-cancel") && !back.includes("pe-card-cancel"), terminal + ": the cancel classes are gone");
    assert.equal(attr(back, "aria-label"), "Start execution", terminal);
    assert.match(back, /lucide-sprite\.svg#play/, terminal);
  }
});

for (const state of ["success", "failed", "aborted"]) {
  test(`Start, may execute, after ${state}: the disc is armed again and reads Start`, () => {
    const html = marker("start", state, { canExecute: true });
    assert.equal(attr(html, "role"), "button");
    assert.ok(!html.includes("aria-disabled"));
    assert.match(html, /pe-marker-word">Start</);
  });
}

test("Start, may NOT execute: a plain marker — role img, no tabindex, no run class, same shape", () => {
  for (const state of ["idle", "running", "success", "failed", "aborted"]) {
    const html = marker("start", state, { canExecute: false });
    assert.equal(attr(html, "role"), "img", state);
    assert.equal(attr(html, "tabindex"), null, state);
    assert.ok(!html.includes("pe-marker-run"), state + ": no affordance class");
    assert.equal(attr(html, "aria-label"), state === "running" ? "Execution start — running" : "Execution start", state);
    assert.match(html, /pe-marker-word">(Start|Running…)</);
  }
  const running = marker("start", "running", { canExecute: false });
  assert.match(running, /pe-marker-word">Running…</);
  assert.ok(!running.includes("pe-marker-cancel") && !running.includes("pe-card-cancel") && !running.includes("Cancel"), "no cancel affordance for a viewer who may not execute");
});

/* ------------------------------------------------------------------- end */

test("End: the outcome word and the elapsed time when known; never a button", () => {
  const table = [
    ["idle", "End", "Execution end"],
    ["running", "End", "Execution end — running"],
    ["success", "Finished", "Execution end — finished"],
    ["failed", "Failed", "Execution end — failed"],
    ["aborted", "Stopped", "Execution end — stopped"],
  ];
  for (const [state, word, aria] of table) {
    for (const canExecute of [true, false]) {
      const html = marker("end", state, { canExecute });
      assert.equal(attr(html, "role"), "img", `${state}/${canExecute}`);
      assert.ok(!html.includes("pe-marker-run"), `${state}: End is never a trigger`);
      assert.match(html, new RegExp('pe-marker-word">' + word + "<"), state);
      assert.equal(attr(html, "aria-label"), aria, state);
      assert.match(html, /class="pe-marker pe-marker-end"/);
      assert.match(html, /lucide-sprite\.svg#square/);
    }
  }
  const timed = marker("end", "success", { canExecute: true, elapsed: "2m 14s" });
  assert.match(timed, /pe-marker-elapsed">2m 14s</);
  assert.equal(attr(timed, "aria-label"), "Execution end — finished in 2m 14s");
  assert.ok(!marker("end", "idle", { elapsed: "2m 14s" }).includes("pe-marker-elapsed"), "an idle End has nothing to time");
});

test("MARKER_LABELS: Start says Running… while the execution runs", () => {
  assert.equal(loadGraph().MARKER_LABELS.start.running, "Running…");
  assert.equal(loadGraph().MARKER_LABELS.start.idle, "Start");
});

/* ------------------------------------------------------------ activation */

/** A container fake that records its delegated listeners — bubble ones by type, capture ones apart. */
function fakeContainer() {
  const listeners = {};
  const capture = {};
  return {
    listeners,
    capture,
    addEventListener(type, fn, useCapture) { (useCapture === true ? capture : listeners)[type] = fn; },
  };
}

function fakeEvent(type, targetMatches, extra) {
  return Object.assign({
    type,
    target: { closest: (sel) => (sel === ".pe-marker-run" && targetMatches ? { getAttribute: () => "__execution_start__" } : null) },
    defaultPrevented: false,
    stopped: false,
    preventDefault() { this.defaultPrevented = true; },
    stopPropagation() { this.stopped = true; },
  }, extra || {});
}

test("click on the Start disc calls the live component's executePipeline exactly once", () => {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const container = fakeContainer();
  let calls = 0;
  globalThis.window = { __peInstance: { isExecuting: false, executePipeline() { calls++; } } };
  try {
    g.editor = null;
    g.wireMarkerActivation(container);
    assert.equal(typeof container.listeners.click, "function", "a delegated click listener is installed");
    const evt = fakeEvent("click", true);
    container.listeners.click(evt);
    assert.equal(calls, 1, "one activation, one call — the same executePipeline the toolbar calls");
    assert.ok(evt.defaultPrevented && evt.stopped, "the canvas's own tap does not also fire");
    container.listeners.click(fakeEvent("click", false));
    assert.equal(calls, 1, "a click elsewhere on the canvas is not an activation");
  } finally {
    delete globalThis.window;
  }
});

test("activation while a run is active calls the live component's cancelExecution exactly once — never executePipeline", () => {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const container = fakeContainer();
  let runs = 0;
  let cancels = 0;
  globalThis.window = { __peInstance: { isExecuting: true, executePipeline() { runs++; }, cancelExecution() { cancels++; } } };
  try {
    g.wireMarkerActivation(container);
    const evt = fakeEvent("click", true);
    container.listeners.click(evt);
    assert.equal(cancels, 1, "one activation, one cancel — the same cancelExecution the toolbar's Cancel calls");
    assert.equal(runs, 0, "a running pipeline is never started again from the disc");
    assert.ok(evt.defaultPrevented && evt.stopped);
    container.listeners.keydown(fakeEvent("keydown", true, { key: "Enter" }));
    container.listeners.keydown(fakeEvent("keydown", true, { key: " " }));
    assert.equal(cancels, 3, "Enter and Space cancel too");
    const before = container.listeners.click;
    g.wireMarkerActivation(container);
    assert.equal(container.listeners.click, before, "wiring is idempotent (history-restored containers keep one listener)");
  } finally {
    delete globalThis.window;
  }
});

test("the activation guard's exits: no disc under the event, no live component, no executePipeline while idle, no cancelExecution while running", () => {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const container = fakeContainer();
  g.editor = null;
  // 1. The event did not come from a disc: nothing is resolved, nothing is called.
  let touched = 0;
  globalThis.window = { get __peInstance() { touched++; return { isExecuting: false, executePipeline() { touched += 100; } }; } };
  try {
    g.wireMarkerActivation(container);
    container.listeners.click(fakeEvent("click", false));
    assert.equal(touched, 0, "a click elsewhere never even resolves the component");
    // 2. No live component and no graph editor: a silent no-op, not a throw.
    globalThis.window = {};
    assert.doesNotThrow(() => container.listeners.click(fakeEvent("click", true)));
    // 3. Idle, but the component has no executePipeline: nothing is called, nothing throws.
    let cancels = 0;
    globalThis.window = { __peInstance: { isExecuting: false, cancelExecution() { cancels++; } } };
    assert.doesNotThrow(() => container.listeners.click(fakeEvent("click", true)));
    assert.equal(cancels, 0, "idle never routes to cancel");
    // 4. Running, but the component has no cancelExecution: nothing is called, never executePipeline.
    let runs = 0;
    globalThis.window = { __peInstance: { isExecuting: true, executePipeline() { runs++; } } };
    assert.doesNotThrow(() => container.listeners.click(fakeEvent("click", true)));
    assert.equal(runs, 0, "running never routes to execute");
  } finally {
    delete globalThis.window;
  }
});

test("the press guard: mousedown, pointerdown and touchstart on the disc are stopped in the CAPTURE phase before Cytoscape sees them — and only on the disc", () => {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const container = fakeContainer();
  globalThis.window = { __peInstance: { isExecuting: false, executePipeline() {} } };
  try {
    g.wireMarkerActivation(container);
    for (const type of ["mousedown", "pointerdown", "touchstart"]) {
      assert.equal(typeof container.capture[type], "function", type + ": a capture-phase listener is installed");
      assert.equal(container.listeners[type], undefined, type + ": not a bubble listener — Cytoscape's own binding on the same element would run first");
      const onDisc = fakeEvent(type, true);
      container.capture[type](onDisc);
      assert.ok(onDisc.stopped, type + " on the disc is stopped");
      assert.ok(!onDisc.defaultPrevented, type + ": the default (focus lands on the disc) is kept");
      const elsewhere = fakeEvent(type, false);
      container.capture[type](elsewhere);
      assert.ok(!elsewhere.stopped, type + " elsewhere reaches Cytoscape — pan, drag and tap are untouched");
    }
  } finally {
    delete globalThis.window;
  }
});

test("Enter and Space on the focused disc activate; other keys do not", () => {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const container = fakeContainer();
  let calls = 0;
  globalThis.window = { __peInstance: { isExecuting: false, executePipeline() { calls++; } } };
  try {
    g.wireMarkerActivation(container);
    container.listeners.keydown(fakeEvent("keydown", true, { key: "Enter" }));
    container.listeners.keydown(fakeEvent("keydown", true, { key: " " }));
    container.listeners.keydown(fakeEvent("keydown", true, { key: "Tab" }));
    container.listeners.keydown(fakeEvent("keydown", false, { key: "Enter" }));
    assert.equal(calls, 2);
  } finally {
    delete globalThis.window;
  }
});

test("without a live component the graph's own editor is the fallback — and a viewer's marker never reaches it", () => {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const container = fakeContainer();
  let calls = 0;
  g.editor = { isExecuting: false, executePipeline() { calls++; } };
  globalThis.window = {};
  try {
    g.wireMarkerActivation(container);
    container.listeners.click(fakeEvent("click", true));
    assert.equal(calls, 1);
  } finally {
    delete globalThis.window;
  }
  // A viewer's marker has no `.pe-marker-run` element at all (the role/img table above),
  // so `closest` never matches and the listener has nothing to activate.
  assert.ok(!marker("start", "idle", { canExecute: false }).includes("pe-marker-run"));
});

/* ------------------------------------------------------------- focus */

/**
 * A DOM small enough to script the keeper's three moments: the disc gains focus, the
 * html-label REPLACES it (focusout with the old disc still connected, then the swap,
 * then the observer's callback), or focus moves elsewhere on purpose.
 */
function focusHarness() {
  const listeners = {};
  const capture = {};
  const container = {
    listeners,
    capture,
    addEventListener(type, fn, useCapture) { (useCapture === true ? capture : listeners)[type] = fn; },
    discs: [],
    querySelectorAll(sel) { return sel === "[data-marker-run]" ? container.discs.filter((d) => d.isConnected) : []; },
  };
  const doc = { body: { tag: "body" }, activeElement: null };
  doc.activeElement = doc.body;
  const disc = (id) => {
    const d = {
      isConnected: true,
      focused: 0,
      getAttribute: (n) => (n === "data-marker-run" ? id : null),
      closest: (sel) => (sel === "[data-marker-run]" ? d : null),
      focus() { d.focused++; doc.activeElement = d; listeners.focusin && listeners.focusin({ target: d }); },
    };
    container.discs.push(d);
    return d;
  };
  let observed = null;
  let callback = null;
  const observers = { observeCalls: 0, disconnectCalls: 0 };
  globalThis.MutationObserver = class {
    constructor(fn) { callback = fn; }
    observe(target, opts) { observers.observeCalls++; observed = { target, opts }; }
    disconnect() { observers.disconnectCalls++; observed = null; }
  };
  globalThis.document = doc;
  const timers = [];
  const realSetTimeout = globalThis.setTimeout;
  globalThis.setTimeout = (fn) => { timers.push(fn); return timers.length; };
  const flushTimers = () => { while (timers.length) timers.shift()(); };
  const mutate = () => callback && callback([]);
  const restore = () => { delete globalThis.MutationObserver; delete globalThis.document; globalThis.setTimeout = realSetTimeout; };
  return { container, doc, disc, observers, observed: () => observed, flushTimers, mutate, restore };
}

test("focus survives the disc's re-render: the replacement disc is focused when the focused one was removed by the html-label", () => {
  const h = focusHarness();
  try {
    const g = Object.create(loadGraph().PipelineGraph.prototype);
    g.wireMarkerActivation(h.container);
    assert.equal(typeof h.container.listeners.focusin, "function", "the keeper listens for focus entering a disc");
    assert.equal(typeof h.container.listeners.focusout, "function", "and for focus leaving it");
    const old = h.disc("__execution_start__");
    old.focus();
    assert.equal(h.observers.observeCalls, 1, "watching the canvas for the re-render once a disc has focus");
    assert.deepEqual(h.observed().opts, { childList: true, subtree: true });
    // The re-render: focusout fires with the old disc STILL connected (measured on Chrome
    // 2026-09-17), then the swap detaches it, focus falls to <body>, the observer fires —
    // and its restore is DEFERRED behind the focusout's own classification (below).
    h.container.listeners.focusout({ target: old, relatedTarget: null });
    old.isConnected = false;
    h.doc.activeElement = h.doc.body;
    const fresh = h.disc("__execution_start__");
    h.mutate();
    assert.equal(fresh.focused, 0, "nothing synchronous: the restore waits one tick for the focusout to be classified");
    h.flushTimers();
    assert.equal(fresh.focused, 1, "the replacement disc took the focus back");
    assert.equal(h.doc.activeElement, fresh);
    assert.equal(h.observers.disconnectCalls, 0, "the deferred focusout check saw a removed disc and kept watching");
    // A later re-render while focus is still on the disc: nothing to do.
    h.mutate();
    h.flushTimers();
    assert.equal(fresh.focused, 1, "focus already on the disc — not re-focused");
  } finally {
    h.restore();
  }
});

test("focus that moved on purpose is respected: a genuine blur stops the keeper, a later re-render steals nothing", () => {
  const h = focusHarness();
  try {
    const g = Object.create(loadGraph().PipelineGraph.prototype);
    g.wireMarkerActivation(h.container);
    const old = h.disc("__execution_start__");
    old.focus();
    // Tab away: focusout, the disc stays connected, focus is on another element.
    const elsewhere = { tag: "button" };
    h.container.listeners.focusout({ target: old, relatedTarget: elsewhere });
    h.doc.activeElement = elsewhere;
    h.flushTimers();
    assert.equal(h.observers.disconnectCalls, 1, "a connected disc that lost focus is a real blur — the keeper stops");
    // Then the run ends and the disc is re-rendered: focus must stay where the user put it.
    old.isConnected = false;
    const fresh = h.disc("__execution_start__");
    h.mutate();
    h.flushTimers();
    assert.equal(fresh.focused, 0, "no focus theft after a deliberate move");
    assert.equal(h.doc.activeElement, elsewhere);
    // A mouse click on a CARD while the disc has focus — the order the browser runs it in:
    // the mousedown listeners first (Cytoscape activates the card, the html-label QUEUES that
    // card's re-render), then the focus change (focus on <body>, the disc's focusout QUEUES
    // its classification), then the re-render timer runs and its mutation reaches the
    // observer — with the classification still pending. A synchronous restore here would
    // pull focus back to the disc; the deferred one lands behind the classification.
    const again = h.disc("__execution_start__");
    again.focus();
    h.doc.activeElement = h.doc.body;
    h.container.listeners.focusout({ target: again, relatedTarget: null }); // classification queued
    h.mutate(); // the other card's re-render: the observer fires now, focus is on <body>
    assert.equal(h.doc.activeElement, h.doc.body, "nothing synchronous in the observer");
    h.flushTimers(); // the classification runs first (queued first), then the restore finds nothing to do
    assert.equal(again.focused, 1, "no focus theft: the click on the canvas blurred a still-connected disc");
    assert.equal(fresh.focused, 0);
    assert.equal(h.doc.activeElement, h.doc.body);
    assert.equal(h.observers.disconnectCalls, 2, "the keeper stopped");
    // And the plain canvas-click case, classified before any re-render: still nothing.
    const third = h.disc("__execution_start__");
    third.focus();
    h.container.listeners.focusout({ target: third, relatedTarget: null });
    h.doc.activeElement = h.doc.body;
    h.flushTimers();
    third.isConnected = false;
    const newer = h.disc("__execution_start__");
    h.mutate();
    h.flushTimers();
    assert.equal(newer.focused, 0, "the canvas click blurred the disc while it was still connected — no restore");
  } finally {
    h.restore();
  }
});

test("without a MutationObserver (node --test, an old browser) the keeper is inert and the activation wiring still installs", () => {
  const g = Object.create(loadGraph().PipelineGraph.prototype);
  const container = fakeContainer();
  assert.equal(typeof globalThis.MutationObserver, "undefined");
  assert.doesNotThrow(() => g.wireMarkerActivation(container));
  assert.equal(typeof container.listeners.click, "function");
  assert.equal(container.listeners.focusin, undefined, "no keeper without an observer");
});

/* ------------------------------------------------------- data plumbing */

test("buildElements hands canExecute to both markers; setMarkerState carries the elapsed text", () => {
  const { buildElements, PipelineGraph } = loadGraph();
  const els = buildElements([{ id: "solo", type: "DQL", source: "pg" }], null, { canExecute: true });
  const markers = els.filter((e) => e.group === "nodes" && e.data.kind === "boundary");
  assert.deepEqual(markers.map((m) => m.data.canExecute), [true, true]);
  const viewer = buildElements([{ id: "solo", type: "DQL", source: "pg" }], null, { canExecute: false });
  assert.deepEqual(viewer.filter((e) => e.data.kind === "boundary" && e.group === "nodes").map((m) => m.data.canExecute), [false, false]);

  const g = Object.create(PipelineGraph.prototype);
  const data = { id: "__execution_end__", kind: "boundary", boundary: "end", state: "idle" };
  const node = { length: 1, removeClass() {}, addClass() {}, data(k, v) { if (v === undefined) return data[k]; data[k] = v; } };
  g.cy = { getElementById: () => node };
  g._boundaryIds = { start: "__execution_start__", end: "__execution_end__" };
  g._mm = null;
  g.setMarkerState("end", "success", { elapsed: "2m 14s" });
  assert.equal(data.label, "Finished");
  assert.equal(data.elapsed, "2m 14s");
  g.setMarkerState("end", "idle");
  assert.equal(data.elapsed, null, "a reset End forgets the previous run's time");
});

test("durationText: the marker's elapsed vocabulary is the footer's", () => {
  const { durationText } = loadGraph();
  assert.equal(durationText(842), "842 ms");
  assert.equal(durationText(5301), "5.3 s");
  assert.equal(durationText(134000), "2m 14s");
  assert.equal(durationText(-1), null);
});

/* ------------------------------------------------------ the run clock → End */

test("pipeline_completed hands End the run clock's elapsed time; a fresh run clears it", () => {
  const opsPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/node-ops.js");
  const ssePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/sse.js");
  globalThis.window = {};
  for (const p of [opsPath, graphPath, ssePath]) delete require.cache[require.resolve(p)];
  require(opsPath); require(graphPath); require(ssePath);
  const win = globalThis.window;
  try {
    const endData = { id: "__execution_end__", kind: "boundary", boundary: "end", state: "idle" };
    const startData = { id: "__execution_start__", kind: "boundary", boundary: "start", state: "idle" };
    const node = (d) => ({ id: () => d.id, length: 1, removeClass() {}, addClass() {}, classes: () => [], data(k, v) { if (v === undefined) return d[k]; d[k] = v; }, incomers: () => [], outgoers: () => [] });
    const byId = { __execution_end__: node(endData), __execution_start__: node(startData) };
    const graph = Object.create(win.PipelineGraph.prototype);
    graph.cy = { getElementById: (id) => byId[id] || { length: 0 }, nodes: () => Object.values(byId), edges: () => [] };
    graph._boundaryIds = { start: "__execution_start__", end: "__execution_end__" };
    graph._mm = null;
    const editor = {
      isExecuting: true, nodeStates: {}, nodeOps: win.PENodeOps.createNodeOps(), graph,
      runStatus: { startedAt: Date.now() - 134_000 },
      setBanner() {}, showError() {}, announceStatus() {}, stopRunClock() {}, handlePipelineFailed() {},
    };
    graph.editor = editor;
    const handler = new win.SseHandler(editor);
    handler.dispatch("pipeline_completed", JSON.stringify({ execution_id: "e1" }));
    assert.match(endData.elapsed, /^2m 1[3-5]s$/, "the End marker knows how long the run took");
    handler.dispatch("execution_started", JSON.stringify({ execution_id: "e2", parameters: {} }));
    assert.equal(endData.elapsed, null);
    editor.runStatus = { startedAt: 0 };
    handler.dispatch("pipeline_failed", JSON.stringify({ execution_id: "e2", error: { message: "x" } }));
    assert.equal(endData.elapsed, null, "no clock, no claim");
    assert.equal(endData.label, "Failed");
  } finally {
    delete globalThis.window;
  }
});

/* ------------------------------------------------------------- minimap */

test("the minimap paints the boundaries as a disc and a square of their own box, cards as bars", () => {
  const { PipelineGraph } = loadGraph();
  const g = Object.create(PipelineGraph.prototype);
  g.tokens = { cardW: 236, cardH: 148 };
  const mk = (id, x, kind, boundary, w, h) => ({
    id: () => id,
    position: () => ({ x, y: 0 }),
    width: () => w,
    data: (k) => ({ kind, boundary, cardH: h, state: "idle" })[k],
  });
  const nodes = [mk("__execution_start__", 0, "boundary", "start", 72, 80), mk("card", 400, undefined, undefined, 236, 148), mk("__execution_end__", 800, "boundary", "end", 72, 80)];
  const appended = [];
  const el = {
    clientWidth: 160, clientHeight: 96, innerHTML: "",
    appendChild(child) { appended.push(child); },
    querySelector: () => null, querySelectorAll: () => [],
  };
  globalThis.document = {
    getElementById: (id) => (id === "pe-minimap" ? el : null),
    createElement: (tag) => ({ tag, style: {}, className: "", attrs: {}, setAttribute(k, v) { this.attrs[k] = v; } }),
  };
  g.cy = { nodes: () => nodes, extent: () => ({ x1: 0, y1: 0, x2: 1, y2: 1 }), on() {} };
  try {
    g.renderMinimap();
  } finally {
    delete globalThis.document;
  }
  const bars = appended.filter((c) => c.className.startsWith("pe-mm-node"));
  assert.equal(bars.length, 3);
  const start = bars.find((b) => b.attrs["data-id"] === "__execution_start__");
  const end = bars.find((b) => b.attrs["data-id"] === "__execution_end__");
  const card = bars.find((b) => b.attrs["data-id"] === "card");
  assert.match(start.className, /pe-mm-boundary pe-mm-boundary-start/);
  assert.match(end.className, /pe-mm-boundary pe-mm-boundary-end/);
  assert.equal(start.style.width, start.style.height, "a disc is square");
  assert.equal(end.style.width, end.style.height, "so is the stop shape");
  assert.ok(parseFloat(card.style.width) > parseFloat(start.style.width) * 2, "the card is the wide bar, the marker the compact shape");
  assert.ok(!card.className.includes("boundary"));
});
