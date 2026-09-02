// 041 — template-editor/preview.js: the preview pane's SQL highlighting pass.
//
// The contract under test: after the render endpoint's HTML lands in
// #previewPane, every <pre> in the pane is re-emitted through the shared
// tokenizer (032 sql-highlight.js) — keywords/strings/numbers get their token
// spans, text stays escaped, and shapes without a <pre> (the error card) are
// left untouched. The editable textarea is deliberately not highlighted (D5).

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const staticRoot = path.resolve(here, "../../main/resources/static");

globalThis.window = {};
require(path.resolve(staticRoot, "js/pipeline-editor/sql-highlight.js"));
require(path.resolve(staticRoot, "js/template-editor/preview.js"));
const { highlightPreview } = globalThis.window.TplPreviewHighlight;

/* A minimal stand-in for the swapped-in <pre>: textContent is the decoded SQL,
   innerHTML is what highlightPreview writes. */
function fakePre(text) {
  return { textContent: text, innerHTML: "" };
}

function fakePane(...pres) {
  return {
    pres,
    querySelectorAll(sel) {
      assert.equal(sel, "pre");
      return this.pres;
    },
  };
}

test("rendered SQL gains token spans", () => {
  const pre = fakePre("SELECT COUNT(*) FROM t WHERE x = 42");
  highlightPreview(fakePane(pre));
  assert.match(pre.innerHTML, /<span class="pe-sql-tok-keyword">SELECT<\/span>/);
  assert.match(pre.innerHTML, /<span class="pe-sql-tok-number">42<\/span>/);
  // reconstructibility through the round trip: strip spans, get the SQL back
  assert.equal(
    pre.innerHTML.replace(/<span class="pe-sql-tok-[a-z]+">/g, "").replace(/<\/span>/g, ""),
    "SELECT COUNT(*) FROM t WHERE x = 42",
  );
});

test("rendered output stays escaped — no raw <, > or & reaches innerHTML", () => {
  const pre = fakePre("SELECT a < b AND c > d -- x & y");
  highlightPreview(fakePane(pre));
  assert.ok(!/<(?!\/?span)/.test(pre.innerHTML));
  assert.ok(!/(?<!&lt;)&(?![a-z]+;)/.test(pre.innerHTML));
});

test("string literals highlight whole — quotes win over ${} inside them", () => {
  const pre = fakePre("WHERE day BETWEEN '${start_date}' AND '${end_date}'");
  highlightPreview(fakePane(pre));
  // The tokenizer's string-literal branch consumes the interpolated look-alike:
  // inside SQL quotes, ${...} is string content, not a freemarker parameter.
  assert.match(pre.innerHTML, /pe-sql-tok-string">'\$\{start_date\}'<\/span>/);
  assert.match(pre.innerHTML, /pe-sql-tok-string">'\$\{end_date\}'<\/span>/);
});

test("the error card shape (no pre) is untouched", () => {
  const pane = fakePane();
  highlightPreview(pane); // must not throw
  assert.equal(pane.pres.length, 0);
});

test("a null-ish pane is a no-op, not a throw", () => {
  assert.equal(highlightPreview(null), null);
});

test("without DpSqlHighlight loaded the pane is returned unchanged", () => {
  const saved = globalThis.window.DpSqlHighlight;
  delete globalThis.window.DpSqlHighlight;
  try {
    const pre = fakePre("SELECT 1");
    highlightPreview(fakePane(pre));
    assert.equal(pre.innerHTML, ""); // nothing written
  } finally {
    globalThis.window.DpSqlHighlight = saved;
  }
});
