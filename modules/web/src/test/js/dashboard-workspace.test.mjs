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
