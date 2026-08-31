// 027b B — wire coercion in execute.js matches pipeline-contract §6.3.
//
// §6.3: "BIGINTEGER and BIGDECIMAL parameters are sent as strings; INTEGER and
// DECIMAL (precision <= 15) as numbers" — and "Accepting it would silently lose
// precision for values beyond IEEE 754 safe range." The old coerceValue ran
// BIGINTEGER through parseInt and BIGDECIMAL through parseFloat, shipping JSON
// numbers the server rejects with 400 pipeline.execution.invalid_parameter_type
// (ParameterCoercion: "BIGINTEGER is string-on-wire and takes a JSON string").
// Reproduced live before the fix: {"parameters":{"supplied_amount":12345.67}} → 400.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

globalThis.window = {};
require(path.resolve(here, "../../main/resources/static/js/pipeline-editor/execute.js"));
const { coerceValue, collectParameters } = globalThis.window;

test("INTEGER coerces to a JSON number", () => {
  assert.equal(coerceValue("5", "INTEGER"), 5);
  assert.equal(coerceValue("-3", "INTEGER"), -3);
});

test("DECIMAL coerces to a JSON number", () => {
  assert.equal(coerceValue("1.5", "DECIMAL"), 1.5);
});

test("BIGINTEGER stays a string — number-on-wire is a contract violation", () => {
  const v = coerceValue("9223372036854775807", "BIGINTEGER");
  assert.equal(typeof v, "string");
  assert.equal(v, "9223372036854775807");
});

test("BIGDECIMAL stays a string — parseFloat silently loses digits past IEEE 754 safe range", () => {
  const beyondSafe = "9007199254740993"; // 2^53 + 1 — parseFloat returns ...992
  const v = coerceValue(beyondSafe, "BIGDECIMAL");
  assert.equal(typeof v, "string");
  assert.equal(v, "9007199254740993");
  assert.equal(coerceValue("12345.67", "BIGDECIMAL"), "12345.67");
});

test("unparseable values pass through raw for the server's precise rejection", () => {
  assert.equal(coerceValue("abc", "INTEGER"), "abc");
  assert.equal(coerceValue("abc", "DECIMAL"), "abc");
});

test("collectParameters sends the typed map the execute body needs", () => {
  const editor = {
    paramKeys: ["supplied_amount", "blank_one", "start_date"],
    parameters: {
      supplied_amount: { type: "BIGDECIMAL", required: true },
      blank_one: { type: "STRING" },
      start_date: { type: "DATE" },
    },
    parameterOverrides: { supplied_amount: "12345.67", blank_one: "", start_date: "2026-01-02" },
  };
  assert.deepEqual(collectParameters(editor), {
    supplied_amount: "12345.67",
    start_date: "2026-01-02",
  });
});
