// #348 — the Details pane's SQL requests carry the viewed version (workspace spec §3.3):
// the workspace ALWAYS sends `version` on the node-SQL GET, so the panel shows the SQL of
// the body the page is displaying even when the current pointer names another one. The
// legacy omission keeps the resolver's working-version default for callers that predate
// the workspace.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const initPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/init.js");

/** The teardown harness's loader, trimmed to what loadNodeSql touches. */
function loadEditor(workspace) {
  const sqlRequests = [];
  globalThis.window = {};
  globalThis.document = {
    readyState: "complete",
    addEventListener: (t, fn) => ((globalThis.__docListeners ||= {})[t] ||= []).push(fn),
    removeEventListener: () => {},
    body: { addEventListener: () => {}, removeEventListener: () => {} },
    cookie: "",
    getElementById: (id) => {
      if (id === "pipeline-data") return { textContent: JSON.stringify({ id: "p1", name: "demo", nodes: [], parameters: {} }) };
      if (id === "pe-node-sql") return { id: "pe-node-sql" };
      return null;
    },
    querySelector: () => null,
  };
  globalThis.window.PEDock = { createDock: () => ({}) };
  globalThis.window.PEEvents = { createEventsLog: () => ({}) };
  globalThis.window.PEWorkspace = workspace;
  globalThis.window.PEWorkspaceLogic = {
    executeVersion: (state) => (state && typeof state.viewedVersion === "number" && state.viewedVersion > 0 ? state.viewedVersion : null),
  };
  globalThis.window.htmx = {
    ajax: (method, url) => sqlRequests.push({ method, url }),
  };
  globalThis.htmx = globalThis.window.htmx;
  globalThis.ResultPanel = class {};
  globalThis.SseHandler = class {};
  globalThis.PipelineGraph = class {
    constructor() {
      this.cy = { on: () => {} };
    }
    render() {}
  };
  globalThis.setupA11y = () => {};
  globalThis.coerceValue = (v) => v;
  globalThis.executePipeline = () => {};

  delete require.cache[require.resolve(initPath)];
  require(initPath);
  const component = globalThis.window.pipelineEditor();
  component.$nextTick = (fn) => fn();
  return { component, sqlRequests };
}

const SQL_NODE = { id: "extract", type: "DQL" };

test("the SQL request carries the viewed version the page resolved", () => {
  const { component, sqlRequests } = loadEditor({ pipelineId: "p1", viewedVersion: 2, hasBody: true, canExecute: true });
  component.init();
  component.selectedNode = SQL_NODE;

  component.loadNodeSql();

  assert.equal(sqlRequests.length, 1);
  assert.match(sqlRequests[0].url, /\/partials\/pipelines\/p1\/nodes\/extract\/sql\?parameters=/);
  assert.match(sqlRequests[0].url, /&version=2$/, "the viewed version travels on EVERY workspace SQL request");
});

test("an explicit draft pin (v3) is sent exactly as a released pin (v1) is", () => {
  const { component, sqlRequests } = loadEditor({ pipelineId: "p1", viewedVersion: 3, hasBody: true, canExecute: true });
  component.init();
  component.selectedNode = SQL_NODE;

  component.loadNodeSql();

  assert.match(sqlRequests[0].url, /&version=3$/);
});

test("no workspace pin (a legacy caller) omits the parameter - the server default stands", () => {
  const { component, sqlRequests } = loadEditor(null);
  component.init();
  component.selectedNode = SQL_NODE;

  component.loadNodeSql();

  assert.equal(sqlRequests.length, 1);
  assert.doesNotMatch(sqlRequests[0].url, /version=/);
});
