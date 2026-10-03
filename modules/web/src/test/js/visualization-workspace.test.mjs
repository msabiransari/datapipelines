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
