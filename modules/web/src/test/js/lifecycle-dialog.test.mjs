// 102 — the lifecycle dialogs' DOM-free decisions (static/js/lifecycle-dialog.js).
//
// The one exported decision is the typed-confirm rule (ui-screens §5.1): the irreversible
// buttons stay disabled until the field carries EXACTLY the expected text. The server
// re-checks the same field as 400 *.confirm_mismatch before the service runs, so this test
// pins the CONVENIENCE half; the guard half is the controller suite's.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

globalThis.window = { addEventListener: function () {} };
globalThis.document = {
  addEventListener: function () {},
  querySelector: function () { return null; },
  querySelectorAll: function () { return []; },
  getElementById: function () { return null; },
};
globalThis.document.body = { addEventListener: function () {} };
require(path.resolve(here, "../../main/resources/static/js/lifecycle-dialog.js"));
const dialogs = globalThis.window.lifecycleDialog;

test("the typed confirm matches only the exact expected text, trimmed", () => {
  assert.equal(dialogs.confirmMatches("v4", "v4"), true);
  assert.equal(dialogs.confirmMatches(" v4 ", "v4"), true); // trailing space never holds the button hostage
  assert.equal(dialogs.confirmMatches("v3", "v4"), false);
  assert.equal(dialogs.confirmMatches("V4", "v4"), false); // the version is typed, not shouted
  assert.equal(dialogs.confirmMatches("4", "v4"), false); // the v IS the point — it names the row
  assert.equal(dialogs.confirmMatches("", "v4"), false);
  assert.equal(dialogs.confirmMatches("test/my_pipeline", "test/my_pipeline"), true);
  assert.equal(dialogs.confirmMatches(null, "v4"), false);
  assert.equal(dialogs.confirmMatches("v4", null), false);
});

// 140 — the release override's min-chars arm (the typed confirm's sibling): "Release anyway"
// stays disabled until the reason carries at least N trimmed characters.
test("the min-chars arm counts trimmed characters against the floor", () => {
  assert.equal(dialogs.minCharsMet("0123456789", 10), true); // exactly at the floor
  assert.equal(dialogs.minCharsMet("012345678", 10), false);
  assert.equal(dialogs.minCharsMet("  0123456789  ", 10), true); // padding never counts
  assert.equal(dialogs.minCharsMet("          ", 10), false); // all whitespace is empty
  assert.equal(dialogs.minCharsMet("", 10), false);
  assert.equal(dialogs.minCharsMet(null, 10), false);
});

// 142 — the release cascade's consent arm: every consent box present must be checked; a
// dialog with no box (no draft pin) has nothing to withhold.
test("the consent rule needs every box checked, and an absent box is consent", () => {
  assert.equal(dialogs.consentMet([]), true);
  assert.equal(dialogs.consentMet([{ checked: true }]), true);
  assert.equal(dialogs.consentMet([{ checked: false }]), false);
  assert.equal(dialogs.consentMet([{ checked: true }, { checked: false }]), false);
});

// The ⋯ menu's placement (owner 2026-09-12: the menu fell into the tab panel's scrollable
// overflow and never showed). The list lives in the top layer now, so WHERE it goes is this
// pure rule's decision: under the ⋯, right-aligned; above it when the viewport has no room
// below; clamped inside the viewport when neither side fits.
const vp = { width: 1280, height: 900 };
const size = { width: 192, height: 120 };

test("the menu opens right-aligned under its ⋯ when there is room below", () => {
  const at = dialogs.menuPlacement({ top: 300, bottom: 328, left: 1000, right: 1028 }, size, vp, 4);
  assert.deepEqual(at, { top: 332, left: 836, above: false });
});

test("the menu opens above its ⋯ when the viewport has no room below", () => {
  const at = dialogs.menuPlacement({ top: 850, bottom: 878, left: 1000, right: 1028 }, size, vp, 4);
  assert.deepEqual(at, { top: 726, left: 836, above: true });
});

test("a menu that fits on neither side takes the roomier side, inside the viewport", () => {
  const tiny = { width: 400, height: 100 };
  // 30px above, 70px below: below wins, and the list is pinned to the viewport's top so
  // its bottom edge lands on the viewport's bottom (100 - 120 clamps to 0).
  assert.deepEqual(dialogs.menuPlacement({ top: 30, bottom: 30, left: 300, right: 328 }, size, tiny, 0),
    { top: 0, left: 136, above: true });
  // 70px above, 30px below: above wins — the top clamps to 0 the same way.
  assert.deepEqual(dialogs.menuPlacement({ top: 70, bottom: 70, left: 300, right: 328 }, size, tiny, 0),
    { top: 0, left: 136, above: true });
});

test("the menu's left edge never leaves the viewport", () => {
  const at = dialogs.menuPlacement({ top: 10, bottom: 38, left: 20, right: 48 }, size, vp, 4);
  assert.equal(at.left, 0);
  assert.equal(at.top, 42);
});
