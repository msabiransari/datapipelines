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

globalThis.window = {};
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
