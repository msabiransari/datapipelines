// A.4: the caps (record §4.9, A10's unit half for bar). One table holds the semantic limits, the mark caps and the row
// cap, each marked with its basis. A bar of exactly the row cap aggregating to 60 marks draws; one row more is refused
// with chart.too_many_rows BEFORE the model is entered (a counting stub in a copy of the template proves it); 5,000
// groups all null is no-data; one non-null cell over the mark cap is refused; a ninth series is refused.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine } from "./engine.mjs";

const R = engine();
const ROWS = R.tables.cap("rows");
const MARKS = R.tables.cap("bar.marks");
const doc = (color) => ({ display_name: "c", config: { mark: { type: "bar" }, encoding: Object.assign({ x: { field: "k", type: "nominal", title: null }, y: { field: "v", type: "quantitative", aggregate: "sum", title: null } }, color ? { color: { field: "c", type: "nominal" } } : {}) } });
const input = (columns, color) => ({ type: "bar", document: doc(color), columns, types: { k: "STRING", v: "DOUBLE", c: "STRING" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] });

/** n rows aggregating to `groups` categories. */
function rows(n, groups) {
  const k = new Array(n), v = new Array(n);
  for (let i = 0; i < n; i++) {
    k[i] = "g" + (i % groups);
    v[i] = 1 + (i % 7);
  }
  return { k, v };
}

/** A copy of the engine whose bar model counts its calls on JsRendered.modelCalls. */
function counted(edit) {
  return engine((s) => {
    const a = s["types/bar.js"];
    s["types/bar.js"] = a.replace("function model(doc, columns, types) {", "function model(doc, columns, types) {\n    R.modelCalls = (R.modelCalls || 0) + 1;");
    if (a === s["types/bar.js"]) throw new Error("stub anchor missing");
    if (edit) edit(s);
  });
}

test("the cap table: every entry has a kind, a limit, a basis and a note; the bar and line-family needs are there", () => {
  const caps = R.tables.CAPS;
  assert.deepEqual(Object.keys(caps).sort(), ["area.points", "area.rows", "bar.marks", "box.marks", "box.outliers", "box.outliers_each", "combo.layers", "combo.rows", "funnel.marks", "gauge.marks", "heatmap.marks", "histogram.marks", "line.points", "line.rows", "pie.marks", "radar.marks", "rows", "scatter.marks", "series", "sparkline.points", "sparkline.rows", "treemap.marks", "waterfall.marks"]);
  for (const [name, c] of Object.entries(caps)) {
    assert.ok(["row", "mark", "semantic"].includes(c.kind), name);
    assert.ok(Number.isInteger(c.limit) && c.limit > 0, name);
    assert.ok(["measured", "inherited", "provisional", "fixed"].includes(c.basis), name);
    assert.ok(typeof c.note === "string" && c.note.length > 10, name);
  }
  assert.equal(caps.series.limit, 8);
  assert.equal(caps["bar.marks"].limit, 1000);
  // The line family's caps as lane 989 round 3 measured them (D9 (d), (a), (b)); area's rows are line's, inherited.
  assert.deepEqual(["line.points", "line.rows", "area.points", "area.rows"].map((k) => [k, caps[k].limit, caps[k].basis]), [
    ["line.points", 2000, "measured"],
    ["line.rows", 10000, "measured"],
    ["area.points", 1000, "measured"],
    ["area.rows", 10000, "inherited"],
  ]);
  assert.throws(() => R.tables.cap("not_a_type.marks"), /no cap-table entry not_a_type\.marks/);
});

test("exactly the row cap, aggregating to 60 marks, draws", () => {
  const out = R.render(input(rows(ROWS, 60)));
  assert.equal(out.state, "rendered");
  assert.equal(out.summary.marks, 60);
});

test("one row over the cap is refused with chart.too_many_rows, before the model is entered", () => {
  const C = counted();
  const out = C.render(input(rows(ROWS + 1, 60)));
  assert.equal(out.state, "refused");
  assert.deepEqual(JSON.parse(JSON.stringify(out.summary.refusal)), { code: "chart.too_many_rows", details: { rows: ROWS + 1, limit: ROWS } });
  assert.equal(C.modelCalls, undefined, "the model was never entered");
  assert.deepEqual([...out.primitives], []);
  C.render(input(rows(10, 2)));
  assert.equal(C.modelCalls, 1, "the stub counts (non-vacuity): a drawable input enters the model once");
});

test("the row count is the longest column, whatever its field", () => {
  const cols = rows(10, 2);
  cols.extra = new Array(ROWS + 1).fill(null);
  assert.equal(R.render(input(cols)).summary.refusal.code, "chart.too_many_rows");
});

test("5,000 groups all null is no-data, not a refusal", () => {
  const k = Array.from({ length: 5000 }, (_, i) => "g" + i), v = new Array(5000).fill(null);
  const out = R.render(input({ k, v }));
  assert.equal(out.state, "no-data");
  assert.equal(out.summary.marks, 0);
});

test("the mark cap: exactly the cap draws; one non-null cell over it is refused with chart.too_many_marks", () => {
  const at = R.render(input(rows(MARKS, MARKS)));
  assert.equal(at.state, "rendered");
  assert.equal(at.summary.marks, MARKS);
  const k = Array.from({ length: MARKS + 1 }, (_, i) => "g" + i), v = Array.from({ length: MARKS + 1 }, () => 1);
  const over = R.render(input({ k, v }));
  assert.equal(over.state, "refused");
  assert.deepEqual(JSON.parse(JSON.stringify(over.summary.refusal)), { code: "chart.too_many_marks", details: { marks: MARKS + 1, limit: MARKS } });
  const nulls = R.render(input({ k: k.concat(["extra"]), v: v.slice(0, MARKS).concat([null, null]) }));
  assert.equal(nulls.state, "rendered", "a null cell is not a mark: 1,000 non-null of 1,002 groups draws");
});

test("a ninth series is refused with chart.too_many_series; eight draw", () => {
  const make = (n) => ({ k: Array.from({ length: n }, () => "a"), c: Array.from({ length: n }, (_, i) => "s" + i), v: Array.from({ length: n }, () => 1) });
  assert.equal(R.render(input(make(8), true)).state, "rendered");
  const out = R.render(input(make(9), true));
  assert.deepEqual(JSON.parse(JSON.stringify(out.summary.refusal)), { code: "chart.too_many_series", details: { series: 9, limit: 8 } });
});

test("the refusal order: rows before series before marks", () => {
  const n = ROWS + 1, many = { k: new Array(n), c: new Array(n), v: new Array(n) };
  for (let i = 0; i < n; i++) {
    many.k[i] = "g" + i;
    many.c[i] = "s" + (i % 20);
    many.v[i] = 1;
  }
  assert.equal(R.render(input(many, true)).summary.refusal.code, "chart.too_many_rows");
  const seriesAndMarks = { k: Array.from({ length: 2000 }, (_, i) => "g" + i), c: Array.from({ length: 2000 }, (_, i) => "s" + (i % 9)), v: new Array(2000).fill(1) };
  assert.equal(R.render(input(seriesAndMarks, true)).summary.refusal.code, "chart.too_many_series");
});

test("radar's 72-point cap is semantic, fixed, not measured, and enforced on a 73-mark stand-in", () => {
  const cap = R.tables.CAPS["radar.marks"];
  assert.deepEqual([cap.kind, cap.limit, cap.basis, cap.unit], ["semantic", 72, "fixed", "points"]);
  assert.match(cap.note, /not measured/); assert.match(cap.note, /§4\.5 row radar/);
  const E = engine(s => {
    const anchor = "function model(doc, columns, types) {";
    assert.equal(s["types/radar.js"].split(anchor).length, 2);
    s["types/radar.js"] = s["types/radar.js"].replace(anchor, anchor + ' return {series: [], markCount: 73, warnings: []};');
  });
  const i = input({ k: [], v: [] }); i.type = "radar"; i.document.config.mark.type = "radar";
  assert.deepEqual(JSON.parse(JSON.stringify(E.render(i).summary.refusal)), { code: "chart.too_many_marks", details: { marks: 73, limit: 72 } });
});
