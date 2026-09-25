// 7d (#7) — the transform face's "unsaved changes" marker (template-transform-face.js).
//
// Runs on Node's built-in runner (`node --test`, the 027b harness). The file is a browser IIFE
// that also publishes on module.exports; the pure pair is driven with fake elements. Pinned:
//   1. an edit to a PANE (a textarea inside #tf-form) reveals #tf-dirty — once;
//   2. anything else (a hidden input, a field outside the face, no marker) changes nothing.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const facePath = path.resolve(here, "../../main/resources/static/js/template-transform-face.js");

function load() {
  delete require.cache[require.resolve(facePath)];
  return require(facePath);
}

function field({ tag = "TEXTAREA", name = "body", inForm = true } = {}) {
  return {
    tagName: tag,
    name,
    closest: (selector) => (selector === "#tf-form" && inForm ? { id: "tf-form" } : null),
  };
}

function docWith(marker) {
  return { getElementById: (id) => (id === "tf-dirty" ? marker : null) };
}

test("a keystroke in a pane reveals the unsaved-changes marker, once", () => {
  const face = load();
  const marker = { hidden: true };
  assert.equal(face.markDirty(field(), docWith(marker)), true);
  assert.equal(marker.hidden, false, "the marker is revealed");
  assert.equal(face.markDirty(field({ name: "tests" }), docWith(marker)), false, "already revealed: nothing to change");
});

test("fields that are not panes leave the marker alone", () => {
  const face = load();
  const marker = { hidden: true };
  assert.equal(face.markDirty(field({ tag: "INPUT", name: "bodyHash" }), docWith(marker)), false, "the hash is not a pane");
  assert.equal(face.markDirty(field({ inForm: false }), docWith(marker)), false, "a textarea outside the face");
  assert.equal(face.markDirty(field({ name: "" }), docWith(marker)), false, "an unnamed control posts nothing");
  assert.equal(face.markDirty(null, docWith(marker)), false);
  assert.equal(marker.hidden, true);
  // A viewer's face renders no marker at all — the edit (impossible there anyway) is a no-op.
  assert.equal(face.markDirty(field(), docWith(null)), false);
});

test("a result swapped into #tf-result is scrolled into view; any other swap is left alone", () => {
  const face = load();
  const calls = [];
  const target = (id) => ({ id, scrollIntoView: (opts) => calls.push([id, opts]) });
  assert.equal(face.revealResult(target("tf-result")), true);
  assert.deepEqual(calls, [["tf-result", { block: "nearest" }]], "instant, the nearest edge — no smooth motion to suppress");
  assert.equal(face.revealResult(target("template-source")), false, "a whole-face swap (a save) is not scrolled");
  assert.equal(face.revealResult(null), false);
  assert.equal(calls.length, 1);
});
