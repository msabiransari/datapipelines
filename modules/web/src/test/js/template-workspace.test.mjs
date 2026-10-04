// #398 — template-editor/workspace.js: the workspace's six-tab state machine (pure core)
// and the URL rewrite the DOM glue performs. The admission table mirrors the server's
// TemplateWorkspaceTab enum: unknown or missing → source; render without the render tab →
// source (BEFORE any render state — the page never renders what the role cannot use).
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const workspacePath = path.resolve(here, "../../main/resources/static/js/template-editor/workspace.js");

require(workspacePath);
const { createTabs, resolve, TABS, withTab } =
  globalThis.window && globalThis.window.TplWorkspace
    ? globalThis.window.TplWorkspace
    : require(workspacePath);

const ALL = ["source", "overview", "render", "runs", "used-by", "versions"];

test("the closed tab set is the six the brief fixes, source the default", () => {
  assert.deepEqual(TABS, ALL);
  assert.equal(resolve(undefined, true), "source");
  assert.equal(resolve(null, true), "source");
});

test("unknown names resolve to source, never an error", () => {
  assert.equal(resolve("bogus", true), "source");
  assert.equal(resolve("", true), "source");
});

test("render without the render tab resolves to source — twice over, like the server's rule", () => {
  assert.equal(resolve("render", false), "source");
  assert.equal(resolve("render", undefined), "source");
  assert.equal(resolve("render", true), "render");
});

test("every other tab is admitted whatever the render right is", () => {
  for (const tab of ["source", "overview", "runs", "used-by", "versions"]) {
    assert.equal(resolve(tab, false), tab);
  }
});

test("createTabs selects, re-selecting the active tab is inert, and admission holds on select", () => {
  const tabs = createTabs(true, "overview");
  assert.equal(tabs.active, "overview");
  assert.equal(tabs.select("versions"), "versions");
  assert.equal(tabs.isActive("versions"), true);
  assert.equal(tabs.isActive("overview"), false);
  // Inert re-select: no state change, no toggle-off.
  assert.equal(tabs.select("versions"), "versions");
  // A hidden tab is inert twice over: the client refuses too.
  assert.equal(createTabs(false, "render").active, "source");
  assert.equal(createTabs(false, "render").select("render"), "source");
  // The URL value the canonical route reads is the active tab's wire name.
  assert.equal(createTabs(true, "used-by").wire(), "used-by");
});

test("withTab rewrites only the tab parameter of a canonical URL", () => {
  assert.equal(withTab("/templates/a/b.sql", "overview"), "/templates/a/b.sql?tab=overview");
  assert.equal(withTab("/templates/a/b.sql?version=2&tab=source", "versions"), "/templates/a/b.sql?version=2&tab=versions");
  assert.equal(withTab("/templates/a/b.sql?version=3", "runs"), "/templates/a/b.sql?version=3&tab=runs");
  // An empty href is left alone — nothing to rewrite.
  assert.equal(withTab("", "source"), "");
});
