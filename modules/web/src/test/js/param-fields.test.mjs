// 195 — the Parameters sidebar's per-field view model (param-fields.js).
//
// The editor runs Alpine's CSP build, whose expressions are pure property
// paths: the placeholder branches, the description fallback and the type badge
// the template used to compute inline are materialized here. Every assertion is
// a string or flag the template binds by name.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

globalThis.window = {};
require(path.resolve(here, "../../main/resources/static/js/pipeline-editor/param-fields.js"));
const { buildParamFields, applyParameterInput } = globalThis.window.PEParamFields;

test("one field per key, Object.keys order, the override pre-seeded empty", () => {
  const fields = buildParamFields({
    window_start: { type: "STRING", description: "Window start" },
    limit: { type: "INTEGER" },
  });
  assert.deepEqual(
    fields.map((f) => f.key),
    ["window_start", "limit"],
  );
  assert.deepEqual(fields[0], {
    key: "window_start",
    type: "STRING",
    descriptionText: "Window start",
    placeholderText: "Optional",
    hasOverride: true,
    hasNoOverride: false,
    override: "",
  });
});

test("placeholder branches: default (JSON-encoded), required, optional — the template's truthiness", () => {
  const fields = buildParamFields({
    a: { type: "STRING", default: { start: "2026-04-01" } },
    b: { type: "INTEGER", required: true },
    c: { type: "STRING" },
    d: { type: "INTEGER", default: 0, required: true }, // falsy default: falls through, exactly as `p.default ?` did
  });
  assert.equal(fields[0].placeholderText, 'Default: {"start":"2026-04-01"}');
  assert.equal(fields[1].placeholderText, "Required");
  assert.equal(fields[2].placeholderText, "Optional");
  assert.equal(fields[3].placeholderText, "Required", "a falsy default never wins the ternary");
});

test("description falls back to the em-dash; a missing parameter object reads as empty", () => {
  const fields = buildParamFields({ a: { type: "STRING" }, b: null });
  assert.equal(fields[0].descriptionText, "—");
  assert.equal(fields[1].descriptionText, "—");
  assert.equal(fields[1].type, undefined);
  assert.deepEqual(buildParamFields(undefined), []);
  assert.deepEqual(buildParamFields(null), []);
});

test("applyParameterInput lands in BOTH places: parameterOverrides and the field's override", () => {
  const editor = {
    parameterOverrides: { region: "" },
    paramFields: buildParamFields({ region: { type: "STRING" } }),
  };
  assert.equal(applyParameterInput(editor, "region", "nyc"), true);
  assert.equal(editor.parameterOverrides.region, "nyc", "the execute and SQL-render paths read this map");
  assert.equal(editor.paramFields[0].override, "nyc", "the template's value binding reads this");
});

test("an unknown key is ignored, never invented", () => {
  const editor = {
    parameterOverrides: { region: "" },
    paramFields: buildParamFields({ region: { type: "STRING" } }),
  };
  assert.equal(applyParameterInput(editor, "bogus", "x"), false);
  assert.deepEqual(editor.parameterOverrides, { region: "" });
  assert.equal(applyParameterInput(null, "region", "x"), false);
  assert.equal(applyParameterInput(editor, null, "x"), false);
});
