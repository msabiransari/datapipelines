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
import fs from "node:fs";
import vm from "node:vm";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const tabsPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/tabs.js");
const corePath = path.resolve(here, "../../main/resources/static/js/workspace/tabs.js");

require(tabsPath);
const { createTabs, resolve, TABS } = globalThis.window ? globalThis.window.PETabs : require(tabsPath);
const core = require(corePath);

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

// #420 — the editor RUNS the shared core (workspace/tabs.js); these guard the three ways the
// adapter can break while the admission table above stays green.

const GETTERS = TABS.flatMap((t) => [`${t}Active`, `${t}Hidden`, `${t}Aria`]);

test("every getter reads `active` through its RECEIVER — Alpine's proxy sees the dependency", () => {
  // The CSP build reads component data through a reactive proxy: a getter registers a
  // dependency only when it reads `this.active` with the proxy as receiver. A getter closed
  // over the raw object (#400's first adapter) never touches the proxy and never re-renders.
  assert.equal(GETTERS.length, 18, "the template's contract: six names × active/hidden/aria");
  for (const name of GETTERS) {
    const seen = [];
    const proxy = new Proxy(createTabs(true, "flow"), {
      get(target, key, receiver) {
        seen.push(key);
        return Reflect.get(target, key, receiver);
      },
    });
    void proxy[name];
    assert.ok(seen.includes("active"), `${name} must read active through the receiver (saw ${seen.join(",")})`);
  }
});

test("admission reads the LIVE flag: init.js creates the object, then sets canReadExecutions", () => {
  const tabs = createTabs(false, "flow");
  tabs.select("runs");
  assert.equal(tabs.active, "flow", "no execution read yet — Runs is inert");
  tabs.canReadExecutions = true; // init.js readComposition: self.tabs.canReadExecutions = …
  tabs.select("runs");
  assert.equal(tabs.active, "runs", "the flag set AFTER creation admits Runs on the next change");
  tabs.canReadExecutions = false;
  tabs.select("runs");
  assert.equal(tabs.active, "flow", "and withdrawing it makes Runs inert again");
});

test("parity: the editor's rule IS the core's — six names, unknowns, admitted and not", () => {
  const inputs = [...TABS, "RUNS", "nonsense", "", undefined, null];
  for (const canRead of [true, false]) {
    const admitted = (tab) => tab !== "runs" || canRead;
    for (const raw of inputs) {
      assert.equal(
        resolve(raw, canRead),
        core.resolve(raw, TABS, "flow", admitted),
        `resolve(${String(raw)}, ${canRead})`,
      );
      assert.equal(createTabs(canRead, raw).active, core.createTabSet({ tabs: TABS, admitted, initial: raw }).active);
    }
  }
  assert.equal(TABS[0], "flow", "the default is the first tab, as the core's createTabSet reads it");
});

// The browser path: both files are classic scripts sharing `window`; the editor's catalog loads
// the core first. A fresh context per case keeps the node `module` branch out of play.
function loadInBrowser(files) {
  const context = vm.createContext({ window: {} });
  for (const file of files) vm.runInContext(fs.readFileSync(file, "utf8"), context, { filename: file });
  return context.window;
}

test("in the browser the editor adapter resolves the core from window.WorkspaceTabs", () => {
  const win = loadInBrowser([corePath, tabsPath]);
  assert.ok(win.WorkspaceTabs, "the core published itself");
  const tabs = win.PETabs.createTabs(false, "flow");
  assert.equal(tabs.flowHidden, false);
  tabs.select("usage");
  assert.equal(tabs.active, "usage");
  tabs.select("runs");
  assert.equal(tabs.active, "flow", "Runs is refused without the execution read — it resolves to the default");
  assert.equal(win.PETabs.resolve("runs", true), "runs");
});

test("loaded WITHOUT the core the adapter refuses loudly — no silent second copy of the rule", () => {
  assert.throws(() => loadInBrowser([tabsPath]), /workspace\/tabs\.js loaded first/);
});
