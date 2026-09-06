// 080 §B — the bottom dock's transition table, one case per ROW plus the rules the
// table states in prose: Esc is a no-op, a collapsed dock keeps its badge, and
// there is NO close.
//
// Runs on Node's built-in runner (`node --test`, the 027b harness), same loader
// convention as result-paging / graph-card: dock.js is a browser IIFE that also
// publishes module.exports, and every assertion here is against the fields the
// template actually binds — `state`, `tab`, `errors.length`, `resultsRows`,
// `detailsNodeId`. There are no derived getters to drift from what the browser
// renders.
//
// What changed from 065: the inspector overlay is gone, Details is a TAB, and the
// dock is always present — the `hidden` state has no page left to live on, and
// `minimized` is renamed `collapsed` (the mock's chevron).

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

test("page load: OPEN on Details, nothing selected, no failures — the dock is always present", () => {
  const d = loadDock();
  assert.equal(d.state, "open");
  assert.equal(d.tab, "details");
  assert.equal(d.detailsNodeId, null);
  assert.deepEqual(d.errors, []);
  assert.equal(d.resultsStale, false);
  assert.equal(d.resultsRows, null);
});

test("selectNode fills Details and surfaces the tab — from ANY resting state", () => {
  for (const start of ["open", "collapsed"]) {
    const d = loadDock();
    d.state = start;
    d.tab = "events";
    d.selectNode("trips_by_borough");
    assert.equal(d.detailsNodeId, "trips_by_borough");
    assert.equal(d.tab, "details", `Details surfaces from ${start}`);
    assert.equal(d.state, "open", `a ${start} dock opens to show the node`);
  }
});

test("clearSelection empties the pane but keeps the tab — canvas background taps are not tab changes", () => {
  const d = loadDock();
  d.selectNode("a");
  d.clearSelection();
  assert.equal(d.detailsNodeId, null);
  assert.equal(d.tab, "details");
  assert.equal(d.state, "open");
});

test("execute started: this run's errors clear, the STATE does not move", () => {
  for (const start of ["open", "collapsed"]) {
    const d = loadDock();
    d.nodeFailed("a", failure("x"));
    d.state = start; // nodeFailed may have raised it; the row under test is execute-started
    d.tab = "errors";
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
  d.dataReady(5);
  assert.equal(d.resultsStale, false, "fresh data is not stale");
  d.executeStarted();
  assert.equal(d.resultsStale, true, "the page on screen is now from the earlier run");
  d.dataReady(7);
  assert.equal(d.resultsStale, false, "…and the label clears the moment new data lands");
});

test("data_ready: the badge takes the row count and the tab follows the data", () => {
  for (const start of ["open", "collapsed"]) {
    const d = loadDock();
    d.state = start;
    d.tab = "details";
    d.dataReady(5);
    assert.equal(d.state, start, `data must not re-open a ${start} dock`);
    assert.equal(d.tab, "results", "with nothing failed, the data raises its own tab");
    assert.equal(d.resultsRows, 5, "the Results badge shows the row count on success");
  }
});

test("data_ready leaves the tab alone while unread failures exist", () => {
  const d = loadDock();
  d.nodeFailed("a", failure("x"));
  assert.equal(d.tab, "errors");
  d.dataReady(3);
  assert.equal(d.state, "open");
  assert.equal(d.tab, "errors", "a failure the user has not read outranks a partial result");
});

test("the FIRST node_failed of a run raises a collapsed dock onto Errors", () => {
  const d = loadDock();
  d.toggleCollapse();
  d.nodeFailed("stage_daily_trips", failure("pipeline.node.sql_error"));
  assert.equal(d.state, "open", "a failure must surface");
  assert.equal(d.tab, "errors");
  assert.equal(d.errors.length, 1);
  assert.equal(d.errors[0].nodeId, "stage_daily_trips");
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

test("the chevron toggles open <-> collapsed; there is still NO close", () => {
  const d = loadDock();
  assert.equal(d.toggleCollapse(), "collapsed");
  assert.equal(d.toggleCollapse(), "open");
  assert.equal(d.toggleCollapse(), "collapsed", "idempotent per call, never hidden");
  const api = Object.keys(d).filter((k) => typeof d[k] === "function");
  assert.ok(!api.some((k) => /close|hide|dismiss/i.test(k)), `no close-shaped transition: ${api.join(", ")}`);
});

test("a tab click on a COLLAPSED dock restores it onto that tab", () => {
  const d = loadDock();
  d.toggleCollapse();
  d.selectTab("events");
  assert.equal(d.state, "open");
  assert.equal(d.tab, "events");
});

test("a tab click switches among all four tabs; an unknown name is inert", () => {
  const d = loadDock();
  for (const tab of ["details", "results", "errors", "events"]) {
    d.selectTab(tab);
    assert.equal(d.tab, tab);
    assert.equal(d.state, "open");
  }
  d.selectTab("nonsense");
  assert.equal(d.tab, "events", "an unknown tab name changes nothing");
});

test("Esc is a NO-OP on the dock — a tab has nothing to close", () => {
  const d = loadDock();
  d.nodeFailed("a", failure("x"));
  const before = { state: d.state, tab: d.tab, n: d.errors.length };
  assert.equal(d.handleEscape(), false, "the dock does not consume Escape");
  assert.deepEqual({ state: d.state, tab: d.tab, n: d.errors.length }, before);

  d.toggleCollapse();
  assert.equal(d.handleEscape(), false);
  assert.equal(d.state, "collapsed", "and it certainly does not close");
});

test("a collapsed dock KEEPS its badge — the header strip is what stays on screen", () => {
  const d = loadDock();
  d.nodeFailed("a", failure("c1"));
  d.nodeFailed("b", failure("c2"));
  d.toggleCollapse();
  assert.equal(d.state, "collapsed");
  assert.equal(d.errors.length, 2, "collapsing hides the body, never the count");
  d.nodeFailed("c", failure("c3"));
  assert.equal(d.errors.length, 3, "and the count keeps moving while collapsed");
});
