// 035 — the draft lifecycle's execute pin (draft.js / versioning §8).
//
// The rule: when the editor is showing a DRAFT, Execute pins the run to the draft's
// version number (running a draft is the expected review loop — the composite FK records
// the run against the real draft number); when no draft exists, NO version is sent and the
// server's execute-default (latest RELEASED, versioning §3.4) applies. Sending the
// released number explicitly in the draft case would run the WRONG body — the bug this
// guard exists to catch.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

globalThis.window = {};
globalThis.document = {
  readyState: "complete",
  addEventListener: function () {},
  cookie: "",
  getElementById: function () { return null; },
};
require(path.resolve(here, "../../main/resources/static/js/pipeline-editor/draft.js"));
const { executeVersion } = globalThis.window.PEDraftLogic;

test("a draft pins the run to the draft's version", () => {
  assert.equal(executeVersion({ hasDraft: true, draftVersion: 3 }), 3);
});

test("no draft means no version — the server's execute-default applies", () => {
  assert.equal(executeVersion({ hasDraft: false, draftVersion: null }), null);
});

test("a missing lifecycle block is treated as no draft", () => {
  assert.equal(executeVersion(null), null);
  assert.equal(executeVersion(undefined), null);
});
