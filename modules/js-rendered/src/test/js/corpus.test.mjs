// A.6, S-5 (record §10.1; brief 985 A.6, G8): the engine's bar `model` equals the product chart core's, frozen once
// over the parity corpus (fixtures/_corpus/core-model.json, written by the lane's evidence script freeze-corpus.mjs with
// the existing emitter emit-js-model.mjs; the core's sha256 is in the file). ChartModelParityTest (modules/visualization)
// compares exactly these fields between ChartModel and the core: categories and series (key, first, full), every cell
// (num, exact, aggregate) and markCount; the emitter emits all of them and nothing that test does not read but its
// IEEE text oracle. They are compared here WHOLE, text included: `full` (a category's text, a temporal band's date
// text by §4.7) and `aggregate` (the cell's exact text) are equal on every case, so no field is excepted. The engine's
// formatted value text (`cells[].text`) and axis text (`layout.bands`) follow §4.7 and have no counterpart in the
// frozen model; the number and date suites own them.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { engine, FIXTURES } from "./engine.mjs";

const FROZEN = JSON.parse(readFileSync(join(FIXTURES, "_corpus", "core-model.json"), "utf8"));
const CORE_SHA256 = "4b56c48b95f28d0ebfddc753afbdcd527745edcd0b6c4b76500984905c6bf42c";

/** The bar template's `model`, captured at registration in this test's own engine (no engine file exposes it). */
function barModel(edit) {
  const hook = "(function () { var t = JsRendered.type; JsRendered.__models = {}; JsRendered.type = function (n, d) { JsRendered.__models[n] = d.model; return t(n, d); }; })();\n";
  const R = engine((s) => {
    if (edit) edit(s);
    s["types/bar.js"] = hook + s["types/bar.js"];
  });
  return R.__models.bar;
}

/** Every field where the engine's model differs from the core's: the fields ChartModelParityTest compares. */
export function differences(name, m, k) {
  const out = [];
  const d = (path, a, b) => {
    if (JSON.stringify(a) !== JSON.stringify(b)) out.push(`${name} ${path}: engine=${JSON.stringify(a)} core=${JSON.stringify(b)}`);
  };
  for (const label of ["categories", "series"]) {
    d(label + ".size", m[label].length, k[label].length);
    k[label].forEach((ki, i) => {
      const mi = m[label][i] || {};
      for (const f of ["key", "first", "full"]) d(`${label}[${i}].${f}`, mi[f], ki[f]);
    });
  }
  d("cells.size", m.cells.length, k.cells.length);
  k.cells.forEach((row, s) => {
    d(`cells[${s}].size`, (m.cells[s] || []).length, row.length);
    row.forEach((kc, c) => {
      const mc = (m.cells[s] || [])[c] || {};
      for (const f of ["num", "exact", "aggregate"]) d(`cells[${s}][${c}].${f}`, mc[f], kc[f]);
    });
  });
  d("markCount", m.markCount, k.markCount);
  return out;
}

test("the frozen corpus is the core lane 977 measured, every case modelled, above the floors", () => {
  assert.equal(FROZEN.core.sha256, CORE_SHA256);
  const cases = Object.values(FROZEN.cases);
  assert.equal(cases.length, 30, "17 fixtures, 5 examples and 8 numeric cases (ChartModelParityTest's corpus)");
  let cells = 0;
  for (const c of cases) for (const row of c.core.cells) cells += row.length;
  assert.ok(cells >= 150, `cells compared: ${cells}`);
  assert.ok(cases.filter((c) => c.core.markCount > 0).length >= 15, "non-empty cases");
});

const model = barModel();
for (const [name, c] of Object.entries(FROZEN.cases)) {
  test(`the engine's bar model equals the core's: ${name}`, () => {
    assert.deepEqual(differences(name, model(c.document, c.columns, c.types), c.core), []);
  });
}
