// 065 §B — the bottom dock's transition table (pipeline-editor.md §10), one case
// per ROW plus the two rules the table states in prose: Esc is a no-op, and a
// minimised dock keeps its badge.
//
// Runs on Node's built-in runner (`node --test`, the 027b harness), same loader
// convention as result-paging / graph-card: dock.js is a browser IIFE that also
// publishes module.exports, and every assertion here is against the three fields
// the template actually binds — `state`, `tab`, `errors.length`. There are no
// derived getters to drift from what the browser renders.
//
// What this pins that the old panel could not have: there is NO close. The
// reported defect was that `resultPanel.visible = false` lost the pane with no
// way back short of re-running the pipeline; a dock whose only contraction is
// `minimized` cannot reproduce it, and "minimise from open" plus "a tab click
// restores" are the two transitions that have to hold for that to stay true.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const dockPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/dock.js");

function loadDock() {
  delete require.cache[require.resolve(dockPath)];
  return require(dockPath).createDock();
}

const failure = (code) => ({ code, message: "boom " + code, node: { id: "n", type: "DQL" } });

test("page load: hidden, on Results, with no failures — nothing has run", () => {
  const d = loadDock();
  assert.equal(d.state, "hidden");
  assert.equal(d.tab, "results");
  assert.deepEqual(d.errors, []);
  assert.equal(d.resultsStale, false);
});

test("execute started: this run's errors clear, the STATE does not move", () => {
  for (const start of ["hidden", "minimized", "open"]) {
    const d = loadDock();
    d.state = start;
    d.tab = "errors";
    d.nodeFailed("a", failure("x"));
    d.state = start; // nodeFailed may have raised it; the row under test is execute-started
    d.executeStarted();
    assert.deepEqual(d.errors, [], `errors cleared from ${start}`);
    assert.equal(d.state, start, `state unchanged from ${start}`);
    assert.equal(d.tab, "errors", `tab unchanged from ${start}`);
  }
});

test("execute started marks the Results tab 'previous run' only when a page is actually showing", () => {
  const d = loadDock();
  d.executeStarted();
  assert.equal(d.resultsStale, false, "nothing has ever run — there is no previous page to label");
  d.dataReady();
  assert.equal(d.resultsStale, false, "fresh data is not stale");
  d.executeStarted();
  assert.equal(d.resultsStale, true, "the page on screen is now from the earlier run");
  d.dataReady();
  assert.equal(d.resultsStale, false, "…and the label clears the moment new data lands");
});

test("data_ready from hidden: the dock OPENS on Results", () => {
  const d = loadDock();
  d.dataReady();
  assert.equal(d.state, "open");
  assert.equal(d.tab, "results");
});

test("data_ready from minimized / open: the state is the user's; the tab follows the data", () => {
  for (const start of ["minimized", "open"]) {
    const d = loadDock();
    d.state = start;
    d.tab = "errors";
    d.dataReady();
    assert.equal(d.state, start, `data must not re-open a ${start} dock`);
    assert.equal(d.tab, "results", "with nothing failed, the data raises its own tab");
  }
});

test("data_ready leaves the tab alone while unread failures exist", () => {
  const d = loadDock();
  d.nodeFailed("a", failure("x")); // hidden -> open/errors
  assert.equal(d.tab, "errors");
  d.dataReady();
  assert.equal(d.state, "open");
  assert.equal(d.tab, "errors", "a failure the user has not read outranks a partial result");
});

test("the FIRST node_failed of a run raises a hidden or minimised dock onto Errors", () => {
  for (const start of ["hidden", "minimized"]) {
    const d = loadDock();
    d.state = start;
    d.nodeFailed("stage_daily_trips", failure("pipeline.node.sql_error"));
    assert.equal(d.state, "open", `a failure from ${start} must surface`);
    assert.equal(d.tab, "errors");
    assert.equal(d.errors.length, 1);
    assert.equal(d.errors[0].nodeId, "stage_daily_trips");
  }
});

test("subsequent node_failed: append and move the badge; state and tab stay put", () => {
  const d = loadDock();
  d.nodeFailed("a", failure("c1"));
  d.selectTab("results"); // the user has deliberately gone back to the data
  d.nodeFailed("b", failure("c2"));
  d.nodeFailed("c", failure("c3"));
  assert.equal(d.errors.length, 3, "the badge counts every failed node");
  assert.equal(d.state, "open", "state unchanged");
  assert.equal(d.tab, "results", "the user's tab choice is not overridden by a later failure");
  assert.deepEqual(d.errors.map((e) => e.nodeId), ["a", "b", "c"], "newest LAST");
});

test("the same failure arriving twice lists once — node_failed then the pipeline_failed it caused", () => {
  const d = loadDock();
  const rec = failure("pipeline.node.sql_error");
  d.nodeFailed("stage", rec);
  d.nodeFailed("stage", rec);
  assert.equal(d.errors.length, 1, "one entry per failed node, not one per event");
});

test("minimise: only an OPEN dock has anything to minimise", () => {
  const open = loadDock();
  open.dataReady();
  assert.equal(open.minimise(), "minimized");

  const hidden = loadDock();
  assert.equal(hidden.minimise(), "hidden", "there is nothing on screen to contract");

  const min = loadDock();
  min.state = "minimized";
  assert.equal(min.minimise(), "minimized", "idempotent");
});

test("a tab click on a MINIMISED dock restores it onto that tab", () => {
  const d = loadDock();
  d.dataReady();
  d.minimise();
  d.selectTab("errors");
  assert.equal(d.state, "open");
  assert.equal(d.tab, "errors");
});

test("a tab click on an OPEN dock switches the tab and leaves the state", () => {
  const d = loadDock();
  d.dataReady();
  d.selectTab("errors");
  assert.equal(d.state, "open");
  assert.equal(d.tab, "errors");
  d.selectTab("results");
  assert.equal(d.tab, "results");
  d.selectTab("nonsense");
  assert.equal(d.tab, "results", "an unknown tab name changes nothing");
});

test("a tab click on a HIDDEN dock is inert — there are no tabs to click yet", () => {
  const d = loadDock();
  d.selectTab("errors");
  assert.equal(d.state, "hidden");
  assert.equal(d.tab, "results");
});

test("Esc is a NO-OP on the dock — the key belongs to the inspector", () => {
  const d = loadDock();
  d.dataReady();
  d.nodeFailed("a", failure("x"));
  const before = { state: d.state, tab: d.tab, n: d.errors.length };
  assert.equal(d.handleEscape(), false, "the dock does not consume Escape");
  assert.deepEqual({ state: d.state, tab: d.tab, n: d.errors.length }, before);

  d.minimise();
  assert.equal(d.handleEscape(), false);
  assert.equal(d.state, "minimized", "and it certainly does not close");
});

test("a minimised dock KEEPS its badge — the header strip is what stays on screen", () => {
  const d = loadDock();
  d.nodeFailed("a", failure("c1"));
  d.nodeFailed("b", failure("c2"));
  d.minimise();
  assert.equal(d.state, "minimized");
  assert.equal(d.errors.length, 2, "minimising hides the body, never the count");
  d.nodeFailed("c", failure("c3"));
  assert.equal(d.errors.length, 3, "and the count keeps moving while minimised");
  assert.equal(d.state, "minimized", "a later failure does not re-open what the user minimised");
});

test("there is NO close: nothing in the module can take the dock back to hidden", () => {
  const d = loadDock();
  d.dataReady();
  const api = Object.keys(d).filter((k) => typeof d[k] === "function");
  assert.ok(!api.some((k) => /close|hide|dismiss/i.test(k)), `no close-shaped transition: ${api.join(", ")}`);
  api.forEach((k) => {
    d[k]("results");
    assert.notEqual(d.state, "hidden", `${k}() must never return the dock to hidden`);
  });
});
