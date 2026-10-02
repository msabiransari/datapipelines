// #349 — the workspace's six-tab state machine (tabs.js, pure): the closed set, the
// admission rule (Runs needs the execution read; everything else is a pipeline-read
// surface), the transition table and the derived strings the CSP template binds. The
// server's PipelineWorkspaceTab enum resolves the page's tab by the SAME rule — the two
// must never disagree, so this drives the mirror the client enforces on every change.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const tabsPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/tabs.js");

require(tabsPath);
const { createTabs, resolve, TABS } = globalThis.window ? globalThis.window.PETabs : require(tabsPath);

test("the tab set is the closed six, Flow first", () => {
  assert.deepEqual(TABS, ["flow", "overview", "parameters", "runs", "usage", "versions"]);
});

test("createTabs resolves the server's tab; unknown and missing land on Flow", () => {
  assert.equal(createTabs(true, "flow").active, "flow");
  assert.equal(createTabs(true, "overview").active, "overview");
  assert.equal(createTabs(true, "nonsense").active, "flow");
  assert.equal(createTabs(true, undefined).active, "flow");
  assert.equal(createTabs(true, null).active, "flow");
});

test("a tab change moves active; re-selecting the active tab is inert", () => {
  const tabs = createTabs(true, "flow");
  tabs.select("parameters");
  assert.equal(tabs.active, "parameters");
  tabs.select("versions");
  assert.equal(tabs.active, "versions");
  tabs.select("versions");
  assert.equal(tabs.active, "versions");
});

test("Runs resolves to Flow without the execution read — at creation AND at select", () => {
  const tabs = createTabs(false, "runs");
  assert.equal(tabs.active, "flow", "the server's own resolution, mirrored");
  tabs.select("runs");
  assert.equal(tabs.active, "flow", "a hidden tab is inert twice over — no pane, no fetch");
  tabs.select("usage");
  assert.equal(tabs.active, "usage", "Usage is a pipeline-read surface, admitted without the execution read");
});

test("an execution reader selects Runs", () => {
  const tabs = createTabs(true, "flow");
  tabs.select("runs");
  assert.equal(tabs.active, "runs");
});

test("exactly one panel is visible: the active tab's hidden getter is false", () => {
  const tabs = createTabs(true, "flow");
  tabs.select("overview");
  const hidden = {
    flow: tabs.flowHidden,
    overview: tabs.overviewHidden,
    parameters: tabs.parametersHidden,
    runs: tabs.runsHidden,
    usage: tabs.usageHidden,
    versions: tabs.versionsHidden,
  };
  const visible = Object.entries(hidden).filter(([, v]) => v === false);
  assert.deepEqual(
    visible.map(([k]) => k),
    ["overview"],
    "exactly one panel visible",
  );
});

test("aria-selected is the STRING form, true only on the active tab", () => {
  const tabs = createTabs(true, "runs");
  assert.equal(tabs.flowAria, "false");
  assert.equal(tabs.runsAria, "true");
  assert.equal(tabs.versionsAria, "false");
});

test("resolve mirrors the server enum's wire names exactly", () => {
  for (const wire of TABS) {
    assert.equal(resolve(wire, true), wire);
  }
  assert.equal(resolve("RUNS", true), "flow", "the wire is lowercase; uppercase is unknown");
});
