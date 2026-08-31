// 032 — sql-highlight.js: a zero-dependency, single-pass SQL tokenizer.
//
// The security-critical decision is the ESCAPING ORDER: tokenize the RAW SQL,
// then escape each token's text as it is emitted into a <span>. Escaping first
// and tokenizing the escaped string would tokenize `&lt;` as three tokens and
// corrupt any `&` inside a string literal. highlight() returns HTML assigned
// with innerHTML, so every path that puts text in that string escapes & < >.
//
// The reconstructibility test is the one that catches a tokenizer silently
// eating whitespace or an unrecognized character — a bug class that looks fine
// until a real query loses a newline.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

function loadHighlight() {
  globalThis.window = {};
  return require(
    path.resolve(here, "../../main/resources/static/js/pipeline-editor/sql-highlight.js")
  );
}

test("keywords, strings, comments, numbers and parameters are classified", () => {
  const { tokenize } = loadHighlight();
  const kinds = (sql) => tokenize(sql).map((t) => t.kind);

  assert.ok(kinds("SELECT * FROM t").includes("keyword"));
  assert.ok(kinds("WHERE a = 'it''s'").includes("string")); // doubled quote escape
  assert.ok(kinds("-- a comment\nSELECT 1").includes("comment"));
  assert.ok(kinds("/* block */ SELECT 1").includes("comment"));
  assert.ok(kinds("LIMIT 100").includes("number"));
  assert.ok(kinds("WHERE d >= ${start_date}").includes("parameter"));
  assert.ok(kinds("WHERE d >= :start_date").includes("parameter"));
});

test("output is escaped — markup in the SQL never survives as markup", () => {
  const html = loadHighlight().highlight("SELECT '<script>alert(1)</script>' AS x");
  assert.ok(!html.includes("<script"));
  assert.ok(html.includes("&lt;script&gt;"));
});

test("an ampersand inside a string literal is escaped, not corrupted", () => {
  const html = loadHighlight().highlight("SELECT 'a & b'");
  assert.ok(html.includes("a &amp; b"));
  assert.ok(!html.includes("& b"));
});

test("an unterminated string terminates the token stream instead of hanging", () => {
  const sql = "SELECT '" + "x".repeat(10000);
  const started = Date.now();
  const tokens = loadHighlight().tokenize(sql);
  assert.ok(Date.now() - started < 500, "the tokenizer must be single-pass, not backtracking");
  assert.equal(tokens[tokens.length - 1].kind, "string"); // consumed to EOF, no throw
});

test("every input is reconstructible from its tokens — nothing is dropped", () => {
  // The backtick is deliberately NOT in any recognized class: an input without an
  // unrecognized character cannot catch a tokenizer whose catch-all branch silently
  // eats one (found by falsification — dropping the catch-all left this test green).
  const sql = "SELECT `a`, /* c */ b\nFROM t -- tail\nWHERE x = 'y' AND n = 12";
  const tokens = loadHighlight().tokenize(sql);
  assert.equal(tokens.map((t) => t.text).join(""), sql);
});

test("highlight(tokenize(x)) round-trips the text content", () => {
  const { highlight } = loadHighlight();
  const sql = "SELECT a FROM t WHERE x = 'y' AND n < 12";
  const html = highlight(sql);
  // Strip the spans; the remaining text is the escaped SQL.
  const text = html
    .replace(/<span class="pe-sql-tok-[a-z]+">/g, "")
    .replace(/<\/span>/g, "");
  assert.ok(text.includes("n &lt; 12"));
  assert.ok(!text.includes("< 12"));
});
