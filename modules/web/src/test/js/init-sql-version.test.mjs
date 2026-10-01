// #348/#348-b — the Details pane's SQL requests carry the viewed version (workspace spec
// §3.3), and a page without a VALID pin refuses VISIBLY with ZERO requests: the workspace
// never asks for the legacy working-version default (that contract belongs to the callers
// that omit the parameter — agents, old links). Both suites load the ACTUAL shared
// workspace.js module — its read path and its pure rule are the code under test.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const initPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/init.js");
const workspacePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/workspace.js");

/** The teardown harness's loader, trimmed to what loadNodeSql touches. */
function loadEditor(workspaceBlock, dataBlock) {
  const sqlRequests = [];
  const paneWrites = [];
  globalThis.window = {};
  globalThis.document = {
    readyState: "complete",
    addEventListener: (t, fn) => ((globalThis.__docListeners ||= {})[t] ||= []).push(fn),
    removeEventListener: () => {},
    body: { addEventListener: () => {}, removeEventListener: () => {} },
    cookie: "",
    getElementById: (id) => {
      if (id === "pipeline-data") {
        return dataBlock === null ? null : { textContent: dataBlock };
      }
      if (id === "pipeline-workspace") {
        return workspaceBlock === null ? null : { textContent: workspaceBlock };
      }
      if (id === "pe-node-sql") {
        return {
          set textContent(v) {
            paneWrites.push(v);
          },
          get textContent() {
            return paneWrites[paneWrites.length - 1] ?? "";
          },
        };
      }
      return null;
    },
    querySelector: () => null,
  };
  globalThis.window.PEDock = { createDock: () => ({}) };
  globalThis.window.PEEvents = { createEventsLog: () => ({}) };
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

  // The ACTUAL shared module initializes the context from the document stubs.
  delete require.cache[require.resolve(workspacePath)];
  delete require.cache[require.resolve(initPath)];
  require(workspacePath);
  require(initPath);
  const component = globalThis.window.pipelineEditor();
  component.$nextTick = (fn) => fn();
  return { component, sqlRequests, paneWrites };
}

const SQL_NODE = { id: "extract", type: "DQL" };
const DATA = JSON.stringify({ id: "p1", name: "p", nodes: [], parameters: {} });
const PIN_2 = JSON.stringify({ pipelineId: "p1", viewedVersion: 2, hasBody: true, canExecute: true });
const PIN_3 = JSON.stringify({ pipelineId: "p1", viewedVersion: 3, hasBody: true, canExecute: true });

test("the SQL request carries the viewed version the page resolved", () => {
  const { component, sqlRequests, paneWrites } = loadEditor(PIN_2, DATA);
  component.init();
  component.selectedNode = SQL_NODE;

  component.loadNodeSql();

  assert.equal(sqlRequests.length, 1);
  assert.match(sqlRequests[0].url, /\/partials\/pipelines\/p1\/nodes\/extract\/sql\?parameters=/);
  assert.match(sqlRequests[0].url, /&version=2$/, "the viewed version travels on EVERY workspace SQL request");
  assert.equal(paneWrites.length, 0, "a valid pin renders no refusal");
});

test("an explicit draft pin (v3) is sent exactly as a released pin (v1) is", () => {
  const { component, sqlRequests } = loadEditor(PIN_3, DATA);
  component.init();
  component.selectedNode = SQL_NODE;

  component.loadNodeSql();

  assert.match(sqlRequests[0].url, /&version=3$/);
});

test("a missing pin refuses VISIBLY and sends NOTHING - never the working-version default", () => {
  const { component, sqlRequests, paneWrites } = loadEditor(null, DATA);
  component.init();
  component.selectedNode = SQL_NODE;

  component.loadNodeSql();

  assert.equal(sqlRequests.length, 0, "zero SQL requests without a valid pin");
  assert.equal(paneWrites.length, 1, "the refusal is visible in the pane");
  assert.match(paneWrites[0], /version state/);
});

test("an identity mismatch refuses too - the block and the body are from different documents", () => {
  const otherData = JSON.stringify({ id: "other-pipeline", name: "p", nodes: [], parameters: {} });
  const { component, sqlRequests, paneWrites } = loadEditor(PIN_2, otherData);
  component.init();
  component.selectedNode = SQL_NODE;

  component.loadNodeSql();

  assert.equal(globalThis.window.PEWorkspaceInvalid, true);
  assert.equal(sqlRequests.length, 0);
  assert.equal(paneWrites.length, 1);
});

test("a fractional pin cannot come from the page - the validator refuses before the URL is built", () => {
  // The workspace block is server-built and integer; the validation that refuses
  // non-integers is proven against the real module in workspace.test.mjs. Here: the
  // page-side consequence — a state the validator refuses produces zero requests.
  const fractional = JSON.stringify({ pipelineId: "p1", viewedVersion: 2.5, hasBody: true, canExecute: true });
  const { component, sqlRequests, paneWrites } = loadEditor(fractional, DATA);
  component.init();
  component.selectedNode = SQL_NODE;

  component.loadNodeSql();

  assert.equal(sqlRequests.length, 0);
  assert.equal(paneWrites.length, 1);
});

test("first selection displays the refusal after Alpine creates the SQL pane", () => {
  const { component, sqlRequests, paneWrites } = loadEditor(null, DATA);
  component.init();
  component.selectedNode = SQL_NODE;
  const ticks = [];
  component.$nextTick = (callback) => ticks.push(callback);
  const find = document.getElementById;
  let rendered = false;
  document.getElementById = (id) => id === "pe-node-sql" && !rendered ? null : find(id);
  component.loadNodeSql();
  rendered = true;
  ticks.forEach((callback) => callback());
  assert.equal(sqlRequests.length, 0);
  assert.equal(paneWrites.length, 1, "the first-selection refusal must reach the newly rendered pane");
  assert.match(paneWrites[0], /version state/);
});
