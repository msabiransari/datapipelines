// 106/102 — the explorer detail's DOM-free decisions (static/js/explorer-detail.js).
//
// Same harness shape as template-explorer.test.mjs: stub the globals the script touches at
// require time, then exercise what it exposes. The pointer work, the dialogs and the drawer's
// geometry are the browser suite's (`ExplorerDetailBrowserTest`); what is here is what those
// adapters delegate — which tab is on, and (since 102) what the tree badge shows after a
// lifecycle verb: the POST's OWN facts, never a client-side guess.
//
// The 106 refusal-message tests (messageOf) were deleted with the fetch path they pinned:
// 102 replaced the plain-confirm verbs with §4.3d dialogs whose refusals arrive as §5.1
// Shape C toasts (toast.js's bridgeErrors), so there is no client-side refusal parsing left.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

globalThis.window = {};
globalThis.document = {
  cookie: "",
  addEventListener: function () {},
  querySelector: function () { return null; },
  querySelectorAll: function () { return []; },
  getElementById: function () { return null; },
};
require(path.resolve(here, "../../main/resources/static/js/explorer-detail.js"));
const detail = globalThis.window.explorerDetail;

/* ----------------------------------------------------------------- the tabs */

function tabCard(names) {
  const panels = {};
  const tabs = names.map((name) => {
    const tab = {
      _panel: name,
      classes: new Set(),
      aria: {},
      classList: {
        toggle(cls, on) { if (on) tab.classes.add(cls); else tab.classes.delete(cls); },
      },
      setAttribute(k, v) { tab.aria[k] = v; },
      getAttribute() { return name; },
    };
    panels[name] = { hidden: false };
    return tab;
  });
  const card = { querySelectorAll: () => tabs };
  tabs.forEach((t) => { t.closest = () => card; });
  globalThis.document.getElementById = (id) => panels[id] || null;
  return { tabs, panels };
}

test("selecting a tab shows exactly its panel and hides the rest", () => {
  const { tabs, panels } = tabCard(["versions", "runs", "usage"]);

  detail.selectTab(tabs[1]);

  assert.equal(panels.versions.hidden, true);
  assert.equal(panels.runs.hidden, false);
  assert.equal(panels.usage.hidden, true);
});

test("the on-tab is the one marked aria-selected, and only that one", () => {
  const { tabs } = tabCard(["versions", "runs", "usage"]);

  detail.selectTab(tabs[2]);

  assert.deepEqual(tabs.map((t) => t.aria["aria-selected"]), ["false", "false", "true"]);
  assert.deepEqual(tabs.map((t) => t.classes.has("is-on")), [false, false, true]);
});

test("hiding uses `hidden`, not a display rule — the lazy tab's swap target must stay in the DOM", () => {
  // htmx swaps INTO #pipeline-tab-runs on the tab's first click. A panel removed from the
  // document (or replaced) would have nothing to swap into and the tab would render empty.
  const { tabs, panels } = tabCard(["versions", "runs"]);
  detail.selectTab(tabs[0]);
  assert.equal(Object.prototype.hasOwnProperty.call(panels.runs, "hidden"), true);
  assert.equal(panels.runs.hidden, true);
});

/* ------------------------------------------- 102 — the tree badge refresh */

test("the badge facts come verbatim from the POST payload — a number, or nothing", () => {
  assert.deepEqual(detail.badgeFacts(null, { workingVersion: 3, hasDraft: false }), { version: "v3", hasDraft: false });
  assert.deepEqual(detail.badgeFacts(null, { workingVersion: 2, hasDraft: true }), { version: "v2", hasDraft: true });
  // null working version = no live version: the badge goes away entirely.
  assert.deepEqual(detail.badgeFacts(null, { workingVersion: null, hasDraft: false }), { version: null, hasDraft: false });
  // A payload that carries nothing changes nothing.
  assert.deepEqual(detail.badgeFacts(null, null), { version: null, hasDraft: false });
});

function leafWithBadges(versionText, withDraft) {
  const leaf = {
    _children: {},
    querySelector(sel) { return this._children[sel] || null; },
  };
  if (versionText !== null) {
    leaf._children[".tpl-leaf-version"] = { textContent: versionText, remove() { leaf._children[".tpl-leaf-version"] = null; } };
  }
  if (withDraft) {
    leaf._children[".tpl-leaf-draft"] = { remove() { leaf._children[".tpl-leaf-draft"] = null; } };
  }
  return leaf;
}

test("a release rewrites the version badge and drops the draft badge", () => {
  const leaf = leafWithBadges("v3", true);
  detail.applyBadge(leaf, { workingVersion: 3, hasDraft: false });
  assert.equal(leaf._children[".tpl-leaf-version"].textContent, "v3");
  assert.equal(leaf._children[".tpl-leaf-draft"], null);
});

test("a draft purge moves the badge back to the surviving release", () => {
  const leaf = leafWithBadges("v3", true);
  detail.applyBadge(leaf, { workingVersion: 2, hasDraft: false });
  assert.equal(leaf._children[".tpl-leaf-version"].textContent, "v2");
  assert.equal(leaf._children[".tpl-leaf-draft"], null);
});

test("a working version of null removes the version badge — no live version remains", () => {
  const leaf = leafWithBadges("v1", false);
  detail.applyBadge(leaf, { workingVersion: null, hasDraft: false });
  assert.equal(leaf._children[".tpl-leaf-version"], null);
});
