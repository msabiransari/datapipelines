// #400 — the workspace tab machine's SHARED CORE (static/js/workspace/tabs.js): the closed
// set, the default, the admission predicate and the transition table — the rule
// pipeline-editor/tabs.js and dashboards/workspace.js both run. The family clients own
// their own vocabulary (their tests cover it); this file owns the RULE.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const tabsPath = path.resolve(here, "../../main/resources/static/js/workspace/tabs.js");
const core = require(tabsPath);

test("the core admits a closed set with a default first", () => {
  const tabs = core.createTabSet({ tabs: ["board", "overview"], defaultTab: "board" });
  assert.equal(tabs.active, "board");
  assert.deepEqual(tabs.tabs, ["board", "overview"]);
});

test("unknown, missing and unadmitted names resolve to the default — at creation AND at select", () => {
  const tabs = core.createTabSet({
    tabs: ["board", "overview", "keys"],
    defaultTab: "board",
    admitted: (tab) => tab !== "keys",
  });
  assert.equal(tabs.active, "board");
  assert.equal(core.createTabSet({ tabs: ["board", "keys"], defaultTab: "board", admitted: (t) => t !== "keys", initial: "keys" }).active, "board");
  tabs.select("keys");
  assert.equal(tabs.active, "board", "a hidden tab is inert twice over — no pane, no fetch");
  tabs.select("nonsense");
  assert.equal(tabs.active, "board");
  tabs.select("overview");
  assert.equal(tabs.active, "overview");
});

test("re-selecting the active tab is inert; exactly one tab is active", () => {
  const tabs = core.createTabSet({ tabs: ["a", "b", "c"] });
  tabs.select("b");
  tabs.select("b");
  assert.equal(tabs.active, "b");
  assert.equal(tabs.isHidden("b"), false);
  assert.equal(tabs.isHidden("a"), true);
  assert.equal(tabs.ariaFor("b"), "true");
  assert.equal(tabs.ariaFor("a"), "false");
});

test("an absent admission predicate admits everything", () => {
  const tabs = core.createTabSet({ tabs: ["a", "b"] });
  tabs.select("b");
  assert.equal(tabs.active, "b");
});

test("a default outside the set is refused loudly", () => {
  assert.throws(() => core.createTabSet({ tabs: ["a", "b"], defaultTab: "z" }));
  assert.throws(() => core.createTabSet({ tabs: [] }));
});
