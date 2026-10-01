// #348 — the workspace version state (workspace.js / workspace spec §3.4).
//
// The rule: a run targets the version the page is VIEWING — released or draft, always
// sent. A state the page could not read (block missing or unparsable) or one that
// resolved no body is a REFUSAL, never a default: the server's execute-default (the
// working version) can be a different body than the one the person is looking at, and
// "some version ran" is the wrong-body failure this module exists to prevent.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const workspacePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/workspace.js");

function freshWindow() {
  const loaded = globalThis.window;
  globalThis.window = {};
  globalThis.document = {
    readyState: "complete",
    addEventListener: function () {},
    cookie: "",
    getElementById: function () {
      return null;
    },
  };
  return loaded;
}

function loadWith(block) {
  globalThis.document.getElementById = (id) =>
    id === "pipeline-workspace" ? (block === undefined ? null : { textContent: block }) : null;
  delete require.cache[require.resolve(workspacePath)];
  require(workspacePath);
}

test("a valid state publishes the viewed version and the execute rule returns it", () => {
  freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":3,"hasBody":true,"canExecute":true}');
  assert.deepEqual(globalThis.window.PEWorkspace, { pipelineId: "p1", viewedVersion: 3, hasBody: true, canExecute: true });
  assert.equal(globalThis.window.PEWorkspaceInvalid, undefined);
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), 3);
});

test("a released view pins its own version exactly as a draft view does", () => {
  freshWindow();
  loadWith('{"viewedVersion":1,"hasBody":true}');
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), 1);
});

test("the choose-a-version state resolves NO version to run", () => {
  freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":null,"hasBody":false,"canExecute":false}');
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), null);
});

test("a malformed workspace block records the refusal, never a silent default", () => {
  const restored = freshWindow();
  loadWith('{"viewedVersion": 2, ');
  assert.equal(globalThis.window.PEWorkspaceInvalid, true);
  assert.equal(globalThis.window.PEWorkspace, null);
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), null);
  globalThis.window = restored;
});

test("a missing workspace block records the refusal too - a page with no pin cannot run", () => {
  const restored = freshWindow();
  loadWith(undefined);
  assert.equal(globalThis.window.PEWorkspaceInvalid, true);
  assert.equal(globalThis.window.PEWorkspace, null);
  globalThis.window = restored;
});

test("non-numeric or non-positive viewed versions are refused by the pure rule", () => {
  freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":2,"hasBody":true}');
  const rule = globalThis.window.PEWorkspaceLogic.executeVersion;
  assert.equal(rule({ viewedVersion: "2" }), null);
  assert.equal(rule({ viewedVersion: 0 }), null);
  assert.equal(rule({ viewedVersion: -1 }), null);
  assert.equal(rule(null), null);
  assert.equal(rule(undefined), null);
});
