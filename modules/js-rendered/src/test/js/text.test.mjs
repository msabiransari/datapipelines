// A.2: numbers and dates by §4.7 (half-even on the decimal text, U+2212, the ISO code before a currency amount, compact
// at one fraction digit, exact big numbers labelled from their decimal string), the axis label rules of G9, and the
// width table of D8 (lane 977's table plus U+2212, its advance READ from the vendored Noto Sans: evidence d8).
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";

const R = engine();
const T = R.text;
const M = "−";

test("the true minus sign U+2212 on every negative number, never on zero", () => {
  assert.equal(T.MINUS, "−");
  assert.equal(T.value(-1234.5, null), M + "1,234.5");
  assert.equal(T.value(-0.004, "decimal(2)"), "0.00", "a negative that rounds to zero is zero");
  assert.equal(T.value(-0, null), "0");
  assert.equal(T.value(-7, "integer"), M + "7");
  assert.deepEqual([...T.ticks([-7.5, -5, -2.5, 0, 2.5, 5])], [M + "7.5", M + "5", M + "2.5", "0", "2.5", "5"]);
});

test("named number formats: grouping, decimals, percent, compact, currency with its ISO code", () => {
  const v = T.value;
  assert.equal(v(1234567.891, null), "1,234,567.89");
  assert.equal(v(2.5, "integer"), "2", "half to even");
  assert.equal(v(3.5, "integer"), "4", "half to even");
  assert.equal(v(1.005, "decimal(2)"), "1.00", "the shortest digits 1.005 round half to even");
  assert.equal(v(1.015, "decimal(2)"), "1.02");
  assert.equal(v(0.125, "decimal(2)"), "0.12", "half to even on the decimal text 0.125");
  assert.equal(v(0.135, "decimal(2)"), "0.14");
  assert.equal(v(0.125, "percent"), "12.5%");
  assert.equal(v(0.07, "percent"), "7%");
  assert.equal(v(14432514, "compact"), "14.4M", "at most one fraction digit");
  assert.equal(v(999999, "compact"), "1M");
  assert.equal(v(1250, "compact"), "1.2K", "half to even: 1.25 → 1.2");
  assert.equal(v(1350, "compact"), "1.4K");
  assert.equal(v(-2.5e12, "compact"), M + "2.5T");
  assert.equal(v(-1234.5, "currency(USD)"), "USD " + M + "1,234.50");
  assert.equal(v(1234.5, "currency(JPY)"), "JPY 1,234", "a currency's own digits: half to even 1234.5 → 1234");
  assert.equal(v(1.2345, "currency(KWD)"), "KWD 1.234", "three digits for KWD, half to even");
  assert.equal(v(1e21, null), "1e21", "from 10^15 up a float takes the e form (#1088 D1)");
  assert.equal(v(1.5e-7, "decimal(6)"), "0.000000");
  assert.equal(v(null, null), "");
  assert.equal(v(NaN, null), "");
  assert.throws(() => v(1, "fancy"), /not a named number format: fancy/);
});

test("exact big numbers are labelled from their decimal string, never through a float", () => {
  const v = T.value;
  assert.equal(v("9007199254740993", null), "9,007,199,254,740,993", "2^53 + 1 survives");
  assert.equal(v("-123456789012345678901234567890.125", "decimal(2)"), M + "123,456,789,012,345,678,901,234,567,890.12");
  assert.equal(v("0.135", "decimal(2)"), "0.14");
  assert.equal(v("12345678901234567890", "compact"), "12,345,678.9T", "past T the compact form keeps T, its digits grouped");
  const beyond = chartFixtures().find((f) => f.wire === "bar" && f.name === "numeric-beyond-2-53");
  assert.ok(beyond, "the fixture exists");
  const out = R.render(inputOf(beyond.fixture, 960, 540));
  const labels = out.primitives.filter((p) => p.k === "text" && p.role === "guide").map((p) => p.text);
  for (const raw of beyond.fixture.columns[beyond.fixture.document.config.encoding.y.field]) {
    if (typeof raw !== "string") continue;
    assert.ok(labels.some((l) => l.replace(/,/g, "") === raw.replace(/^\+/, "").replace(/^-/, M)), "a value label states " + raw + " exactly: " + labels.join(" | "));
  }
});

test("tick labels: the fewest fraction digits at which every label IS its tick; compact from 1,000", () => {
  const t = (xs, f) => [...T.ticks(xs, f)];
  assert.deepEqual(t([0, 2500, 5000, 7500, 10000]), ["0", "2.5K", "5K", "7.5K", "10K"]);
  assert.deepEqual(t([0, 0.25, 0.5, 0.75, 1]), ["0", "0.25", "0.5", "0.75", "1"]);
  assert.deepEqual(t([0, 0.1, 0.2, 0.30000000000000004]), ["0", "0.1", "0.2", "0.3"]);
  assert.deepEqual(t([1e7, 1.5e7, 2e7]), ["10M", "15M", "20M"]);
  assert.deepEqual(t([0, 1250, 2500]), ["0", "1.25K", "2.5K"], "the short form carries up to two digits (owner 2026-11-22, #1089)");
  assert.deepEqual(t([0, 0.25, 0.5], "percent"), ["0%", "25%", "50%"]);
  for (const xs of [[-600, -400, -200, 0, 200], [0, 0.002, 0.004], [1e15, 2e15]]) {
    const labels = T.ticks(xs, null);
    xs.forEach((x, i) => assert.equal(T.readLabel(labels[i]), x, labels[i]));
  }
});

test("dates: UTC from the wire string's digits, label text per granularity (G9)", () => {
  assert.equal(T.temporalFull("2024-01-05", null), "2024-01-05");
  assert.equal(T.temporalFull("2024-01-05T14:00:00Z", null), "2024-01-05 14:00 UTC");
  assert.equal(T.temporalFull("2024-01-05T14:00:00Z", "month"), "2024-01");
  assert.equal(T.temporalFull("2024-01-05", "year"), "2024");
  const ax = (raw, f) => JSON.parse(JSON.stringify(T.temporalAxis(raw, f)));
  assert.deepEqual(ax("2024-03-01", "year"), { text: "2024", under: null });
  assert.deepEqual(ax("2024-03-01", "month"), { text: "Mar", under: "2024" });
  assert.deepEqual(ax("2024-03-05", null), { text: "Mar 5", under: "2024" });
  assert.deepEqual(ax("2024-03-05T09:30:00Z", null), { text: "09:30", under: "Mar 5, 2024" });
  assert.deepEqual(ax("not a date", null), { text: "not a date", under: null });
  assert.deepEqual(ax("2024-13-01", null), { text: "2024-13-01", under: null }, "no month 13");
  assert.equal(T.temporalKey("2024-01-05T14:00:00.5Z"), "2024-01-05T14:00:00.500000000");
  assert.equal(T.temporalKey("2024-01-05"), "2024-01-05T00:00:00.000000000");
});

test("a temporal band axis writes the year under the first label and wherever it changes", () => {
  const months = ["2023-11-01", "2023-12-01", "2024-01-01", "2024-02-01"];
  const doc = { display_name: "m", config: { mark: { type: "bar" }, encoding: { x: { field: "m", type: "temporal", format: "month", title: null }, y: { field: "v", type: "quantitative", aggregate: "sum", title: null } } } };
  const out = R.render({ type: "bar", document: doc, columns: { m: months, v: [1, 2, 3, 4] }, types: { m: "DATE", v: "DOUBLE" }, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] });
  const ticks = out.primitives.filter((p) => p.k === "text" && p.role === "tick").map((p) => p.text);
  assert.deepEqual([...ticks.filter((t) => /^[A-Z]|^\d{4}$/.test(t))], ["Nov", "2023", "Dec", "Jan", "2024", "Feb"]);
});

test("D8: the width table is lane 977's plus U+2212, read from the font (572/1000 em)", () => {
  const table = R.widthTables["noto-sans-regular"];
  assert.equal(table[0x2212], 572, "U+2212's advance, read by evidence d8-read-advance");
  assert.equal(table[0x2d], 322, "the hyphen-minus stays as lane 977 read it");
  const m = R.measure.measurer({ table: "noto-sans-regular", margin: 1 });
  assert.equal(m.fn("2024", 10), 0.572 * 4 * 10);
  assert.equal(m.fn(M, 10), 5.72);
  assert.equal(m.missing, 0);
  m.fn("中文", 10);
  assert.equal(m.missing, 2, "a code point the table lacks is counted");
  assert.throws(() => R.measure.measurer({ table: "noto-sans-regular", margin: 0.9 }), /margin outside 1 to 2/);
  assert.throws(() => R.measure.measurer({ table: "nope" }), /unknown width table: nope/);
});

test("D8: a missing code point takes the average width and the render says so", () => {
  const doc = { display_name: "w", config: { mark: { type: "bar" }, encoding: { x: { field: "k", type: "nominal", title: null }, y: { field: "v", type: "quantitative", aggregate: "sum", title: null } } } };
  const input = (k) => ({ type: "bar", document: doc, columns: { k: [k], v: [1] }, types: {}, frame: { w: 960, h: 540 }, theme: "light", measure: null, hidden: [] });
  assert.deepEqual(JSON.parse(JSON.stringify(R.render(input("北京")).summary.warnings)), [{ code: "chart.text.width-missing" }]);
  assert.deepEqual(JSON.parse(JSON.stringify(R.render(input("Paris")).summary.warnings)), []);
  const injected = R.render(Object.assign(input("北京"), { measure: (s, size) => s.length * size * 0.5 }));
  assert.deepEqual(JSON.parse(JSON.stringify(injected.summary.warnings)), [], "an injected measure is never 'missing'");
});

test("fit cuts by code point with an ellipsis and drops a trailing space", () => {
  const SIX = (text) => 6 * [...text].length;
  const fit = (t, max) => R.measure.fit(SIX, t, 11, 400, max);
  assert.equal(fit("abcdef", 36), "abcdef");
  assert.equal(fit("abcdefgh", 30), "abcd…");
  assert.equal(fit("abcdefgh", 6), "…");
  assert.equal(fit("abcdefgh", 3), "", "not even the ellipsis fits: nothing");
  assert.equal(fit("ab 😀😀😀", 24), "ab…", "never inside a surrogate pair, the trailing space dropped");
});
