// 106/398 — the explorers' tree drawer (static/js/explorer-detail.js).
//
// Same harness shape as template-explorer.test.mjs: stub the globals the script touches at
// require time, then exercise what it exposes. The pointer work and the drawer's geometry are
// the browser suite's (`ExplorerPaneGeometryBrowserTest`, on the schedules page — the one
// explorer left); what is here is the decision the adapters delegate: which state the drawer's
// four moving parts (the class, the openers' aria-expanded, the backdrop, the focus) are in.
//
// The tab and tree-badge tests this file carried were deleted with the halves of the module
// they pinned: #401 and #398 retired the two panes those served, and nothing emits the
// `lifecycle-changed` payload any more.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

globalThis.window = {};
const listeners = {};
globalThis.document = {
  cookie: "",
  addEventListener: function (type, fn) { listeners[type] = fn; },
  querySelector: function () { return null; },
  querySelectorAll: function () { return []; },
};
require(path.resolve(here, "../../main/resources/static/js/explorer-detail.js"));
const detail = globalThis.window.explorerDetail;

function drawerDom({ withFocusable }) {
  const classes = new Set();
  const focused = [];
  const opener = { aria: {}, setAttribute(k, v) { opener.aria[k] = v; } };
  const backdrop = { hidden: true };
  const focusable = { focus() { focused.push("first"); } };
  const body = {
    classList: {
      toggle(cls, on) { if (on) classes.add(cls); else classes.delete(cls); },
      contains(cls) { return classes.has(cls); },
    },
    querySelector() { return withFocusable ? focusable : null; },
  };
  globalThis.document.querySelector = (sel) => {
    if (sel === ".tplx-body") return body;
    if (sel === "[data-explorer-drawer-backdrop]") return backdrop;
    return null;
  };
  globalThis.document.querySelectorAll = (sel) => (sel === "[data-explorer-drawer-open]" ? [opener] : []);
  return { classes, focused, opener, backdrop };
}

test("opening the drawer sets the class, aria-expanded, shows the backdrop and focuses the tree", () => {
  const dom = drawerDom({ withFocusable: true });
  detail.setDrawer(true);
  assert.ok(dom.classes.has("is-drawer-open"));
  assert.equal(dom.opener.aria["aria-expanded"], "true");
  assert.equal(dom.backdrop.hidden, false);
  assert.deepEqual(dom.focused, ["first"]);
});

test("closing the drawer undoes all four and moves no focus", () => {
  const dom = drawerDom({ withFocusable: true });
  detail.setDrawer(true);
  dom.focused.length = 0;
  detail.setDrawer(false);
  assert.ok(!dom.classes.has("is-drawer-open"));
  assert.equal(dom.opener.aria["aria-expanded"], "false");
  assert.equal(dom.backdrop.hidden, true);
  assert.deepEqual(dom.focused, []);
});

test("a page with no explorer body is left alone — no throw, no state", () => {
  globalThis.document.querySelector = () => null;
  globalThis.document.querySelectorAll = () => [];
  assert.doesNotThrow(() => detail.setDrawer(true));
});

test("Escape closes the drawer", () => {
  const dom = drawerDom({ withFocusable: false });
  detail.setDrawer(true);
  listeners.keydown({ key: "Escape" });
  assert.ok(!dom.classes.has("is-drawer-open"));
});
