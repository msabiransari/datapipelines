// 065 §C — the node inspector's open/close and focus contract.
//
// Harness: the fake-element shape a11y.test.mjs uses for its DOM-touching cases —
// an object with `focus()` that records it was called. inspector.js treats the
// trigger as an opaque handle (it only ever hands it back), which is what lets
// this run on `node --test` with no DOM at all.
//
// The two rules pinned here are the ones that fail SILENTLY in a browser:
//
//   1. Focus RETURNS to the control that opened the panel. The opener is a button
//      drawn inside a Cytoscape html-label, re-created on every data/style event —
//      so the element REFERENCE is the only durable handle; a selector would find
//      a different object (or none) by the time the panel closes.
//   2. Opening from a second card REPLACES in place. A close-then-open would flash
//      the scrim and, worse, hand focus back to the FIRST card in between. The
//      `changes` log is what makes "no intermediate closed state" falsifiable —
//      asserting `open === true` after the fact cannot see a close that already
//      happened and was undone.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";
import fs from "node:fs";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const inspectorPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/inspector.js");

function loadInspector() {
  delete require.cache[require.resolve(inspectorPath)];
  return require(inspectorPath).createInspector();
}

/** The a11y suite's fake element, trimmed to what a focus-return target needs. */
function fakeButton(name) {
  return {
    name,
    focused: 0,
    focus() {
      this.focused += 1;
    },
  };
}

test("open records the node and remembers the control to hand focus back to", () => {
  const i = loadInspector();
  const btn = fakeButton("card-a");
  assert.equal(i.open, false, "the panel does not exist before a card asks for it");
  assert.equal(i.openFrom("stage_daily_trips", btn), "open");
  assert.equal(i.open, true);
  assert.equal(i.nodeId, "stage_daily_trips");
  assert.equal(i.returnTo, btn, "the ELEMENT, not a selector — the card button is re-created on every graph event");
});

test("close returns the opener so focus goes back to the card button", () => {
  const i = loadInspector();
  const btn = fakeButton("card-a");
  i.openFrom("a", btn);
  const back = i.close();
  assert.equal(back, btn);
  back.focus();
  assert.equal(btn.focused, 1);
  assert.equal(i.open, false);
  assert.equal(i.nodeId, null);
});

test("a second close cannot steal focus back from wherever the user has gone", () => {
  const i = loadInspector();
  const btn = fakeButton("card-a");
  i.openFrom("a", btn);
  i.close();
  assert.equal(i.close(), null, "closing a closed panel hands back nothing");
  assert.equal(i.returnTo, null);
});

test("opening from a SECOND card replaces in place — no intermediate closed state", () => {
  const i = loadInspector();
  const a = fakeButton("card-a");
  const b = fakeButton("card-b");
  i.openFrom("a", a);
  assert.equal(i.openFrom("b", b), "replace", "the caller is told to skip the entry transition");
  assert.equal(i.open, true);
  assert.equal(i.nodeId, "b");
  assert.deepEqual(i.changes, ["open", "replace"], "no close between the two opens");
  assert.ok(!i.changes.includes("close"), "a close-then-open would flash the scrim and misdirect focus");
  assert.equal(a.focused, 0, "focus never went back to the first card");
});

test("after a replace, close returns to the LAST card that opened the panel", () => {
  const i = loadInspector();
  const a = fakeButton("card-a");
  const b = fakeButton("card-b");
  i.openFrom("a", a);
  i.openFrom("b", b);
  assert.equal(i.close(), b, "Esc belongs to the card the user last acted on");
  assert.equal(a.focused, 0);
});

test("a re-target with no trigger (selection change, keyboard) keeps the existing return point", () => {
  const i = loadInspector();
  const a = fakeButton("card-a");
  i.openFrom("a", a);
  i.openFrom("b", null);
  assert.equal(i.nodeId, "b");
  assert.equal(i.returnTo, a, "an opener-less re-target must not orphan the focus return");
});

test("Esc closes the inspector and yields the element to focus", () => {
  const i = loadInspector();
  const btn = fakeButton("card-a");
  i.openFrom("a", btn);
  const r = i.handleEscape();
  assert.deepEqual({ closed: r.closed, focus: r.focus }, { closed: true, focus: btn });
  assert.equal(i.open, false);
});

test("Esc on a closed inspector is not consumed — the key falls through", () => {
  const i = loadInspector();
  assert.equal(i.handleEscape(), null);
  assert.deepEqual(i.changes, [], "nothing happened, so nothing is recorded");
});

/**
 * The SQL box's floor is a STYLESHEET fact, so it is pinned where it lives —
 * against the shipped file, the same way graph-card.test.mjs pins the card
 * title's two-line clamp.
 *
 * The number that matters is the shrink factor. `flex: 1 1 40%` (the CSS
 * default shrink) reads as "40% basis, grows" and silently is not a floor at
 * all: measured on the demo stack (2026-09-04), a node whose other sections
 * were tall squeezed the SQL section from 411px to **17px** with a 28-line
 * statement still inside it — this round's own defect, reintroduced by one
 * digit. `flex: 1 0 40%` held 334px in the same test. A regression here would
 * look completely reasonable in a diff, which is why it gets an assertion.
 */
test("the SQL section may grow but must NEVER shrink — the 40% floor is the shrink factor", () => {
  const css = fs.readFileSync(
    path.resolve(here, "../../main/resources/static/css/pipeline-editor.css"),
    "utf8",
  );
  const at = css.indexOf(".pe-details-body > .pe-details-section-sql");
  assert.ok(at >= 0, "the SQL section rule must exist — it is what gives the statement its box");
  const rule = css.slice(at, css.indexOf("}", at));
  const flex = /flex:\s*([^;]+);/.exec(rule);
  assert.ok(flex, `the rule must set flex: ${rule}`);
  const [grow, shrink, basis] = flex[1].trim().split(/\s+/);
  assert.equal(grow, "1", "it takes spare space");
  assert.equal(shrink, "0", "…and gives none back: a shrink factor of 1 collapses the box to 17px under pressure");
  assert.match(basis, /^40%$/, "the basis is the documented 40% floor (pipeline-editor.md §8.3)");
});

test("the statement does not wrap and scrolls in its own box", () => {
  const css = fs.readFileSync(
    path.resolve(here, "../../main/resources/static/css/pipeline-editor.css"),
    "utf8",
  );
  const at = css.indexOf(".pe-details-section-sql .pe-sql {");
  assert.ok(at >= 0, "the statement's own rule must exist");
  const rule = css.slice(at, css.indexOf("}", at));
  assert.match(rule, /white-space:\s*pre\b/, "wrapping destroys the indentation that carries the query's shape");
  assert.match(rule, /overflow:\s*auto/, "a long line scrolls inside the box, never widens the panel");
  assert.match(rule, /max-height:\s*none/, "no inherited ceiling — the box IS the panel's growing section");
});
