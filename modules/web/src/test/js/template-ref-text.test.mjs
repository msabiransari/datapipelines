// 047 — the pipeline editor's template reference is a PATH
// (template-hierarchy-design §9.4).
//
// The inspector shows the reference twice: as the link's TEXT, which truncates to one line
// in a 320px panel, and as the link's `title`, which is the only remaining copy of the full
// path. The panel must therefore render ONE value in both places — hence templateRefText,
// rather than the two separately-built expressions the markup carried before, which could
// (and, with a path, would) drift apart the moment either was edited.

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
  querySelectorAll: function () { return []; },
};
require(path.resolve(here, "../../main/resources/static/js/pipeline-editor/init.js"));
const editor = globalThis.window.pipelineEditor();

test("a deep path renders whole, with its pinned version", () => {
  assert.equal(
    editor.templateRefText({ template: { id: "acme/finance/monthly_revenue", version: 3 } }),
    "acme/finance/monthly_revenue @ v3",
  );
});

test("a flat legacy name is unchanged — §4.5 renames nothing", () => {
  assert.equal(
    editor.templateRefText({ template: { id: "fetch_orders.sql", version: 1 } }),
    "fetch_orders.sql @ v1",
  );
});

test("an unpinned reference shows the path alone, never ' @ vundefined'", () => {
  assert.equal(
    editor.templateRefText({ template: { id: "acme/finance/report" } }),
    "acme/finance/report",
  );
});

test("a node with no template shows an em dash, not 'undefined'", () => {
  assert.equal(editor.templateRefText({}), "—");
  assert.equal(editor.templateRefText(null), "—");
  assert.equal(editor.templateRefText({ template: {} }), "—");
});
