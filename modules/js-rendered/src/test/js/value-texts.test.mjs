// Lane 1088: a number a chart writes outside an axis (tooltip, value label, legend end, stack total) follows the axis
// rule (owner 2026-11-22, "same rule as axis"; design record 2026-11-14-js-rendered-charts §4.7 and its note of
// 2026-11-22 "Numbers outside an axis, the short form with two digits, a stack beyond the number range"): from 10^15 up
// a float takes lane 1059's e form at every format (D1); an exact text (BIGINT, DECIMAL) never does and is never read
// through a float (D2); below 10^-5 the formats that name no digits take the e form (D3); between the two such a
// format shows the value's own digits instead of 0 (D4); a format that names its digits keeps them (D5); a value that
// is not finite is the empty text (D6); zero is 0 (D7). A.7's probes carry the same texts into the server's sandbox.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { engine, engineWith } from "./engine.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const T = engine().text;
const M = "−";
const FORMATS = [null, "compact", "percent", "integer", "decimal(2)", "currency(USD)"];
const nameOf = (f) => (f === null ? "no format" : f);
/** A text in the e form: a digit, then e (a currency's code and a percent's sign are not part of it). */
const isSci = (t) => /[0-9]e/.test(t);

// ---------------------------------------------------------------- A.1 the ruling, by its texts

/** The brief's first table, as the rule writes it: [value, no format, compact, percent, integer, decimal(2), currency(USD)]. */
const TABLE = [
  [1e18, "1e18", "1e18", "1e20%", "1e18", "1e18", "USD 1e18"],
  [1234560000000000000, "1.23e18", "1.23e18", "1.23e20%", "1.23e18", "1.23e18", "USD 1.23e18"],
  ["25000000000000000", "25,000,000,000,000,000", "25,000T", "2,500,000,000,000,000,000%", "25,000,000,000,000,000", "25,000,000,000,000,000.00", "USD 25,000,000,000,000,000.00"],
  [1234.5, "1,234.5", "1.2K", "123,450%", "1,234", "1,234.50", "USD 1,234.50"],
  [0.051, "0.051", "0.05", "5.1%", "0", "0.05", "USD 0.05"],
  [0.05, "0.05", "0.05", "5%", "0", "0.05", "USD 0.05"],
  [0.5, "0.5", "0.5", "50%", "0", "0.50", "USD 0.50"],
  [0.0149, "0.015", "0.01", "1.5%", "0", "0.01", "USD 0.01"],
  [0.004, "0.004", "0.004", "0.4%", "0", "0.00", "USD 0.00"],
  [0.0046, "0.0046", "0.005", "0.5%", "0", "0.00", "USD 0.00"],
  [0.00001, "0.00001", "0.00001", "0.001%", "0", "0.00", "USD 0.00"],
  [0.0000099, "9.9e−6", "9.9e−6", "0.001%", "0", "0.00", "USD 0.00"],
  [2.5e-9, "2.5e−9", "2.5e−9", "2.5e−7%", "0", "0.00", "USD 0.00"],
  [-250000000000000000, "−2.5e17", "−2.5e17", "−2.5e19%", "−2.5e17", "−2.5e17", "USD −2.5e17"],
  [0, "0", "0", "0%", "0", "0.00", "USD 0.00"],
  [999999999999999, "999,999,999,999,999", "1,000T", "1e17%", "999,999,999,999,999", "999,999,999,999,999.00", "USD 999,999,999,999,999.00"],
  [1e15, "1e15", "1e15", "1e17%", "1e15", "1e15", "USD 1e15"],
];

/** The other sign of three rows: a small value, one below 10^-5, one at 10^15. */
const OTHER_SIGN = [
  [-0.004, "−0.004", "−0.004", "−0.4%", "0", "0.00", "USD 0.00"],
  [-2.5e-9, "−2.5e−9", "−2.5e−9", "−2.5e−7%", "0", "0.00", "USD 0.00"],
  [-1e15, "−1e15", "−1e15", "−1e17%", "−1e15", "−1e15", "USD −1e15"],
];

/** Every cell of the rows asserted; a red names each cell that differs (its text, and what the rule writes). */
function assertRows(rows) {
  let n = 0;
  const wrong = [];
  for (const [v, ...texts] of rows) {
    FORMATS.forEach((f, i) => {
      const t = T.value(v, f);
      if (t !== texts[i]) wrong.push(`${typeof v === "string" ? JSON.stringify(v) : v} as ${nameOf(f)}: ${t} (wants ${texts[i]})`);
      n++;
    });
  }
  assert.deepEqual(wrong, [], "cells that differ: " + wrong.join("; "));
  return n;
}

test("A.1 the ruling by its texts: 17 values at six formats, the cells that change and the ones that keep their bytes", () => {
  assert.equal(TABLE.length, 17);
  assert.equal(assertRows(TABLE), 102);
});

test("A.1 the other sign: the true minus before the digits, before the e form's mantissa and in its power; a named digit count rounds a small negative to zero", () => {
  assert.equal(assertRows(OTHER_SIGN), 18);
});

test("A.1 zero is 0 at every format, never an e form (D7), negative zero too", () => {
  const zero = ["0", "0", "0%", "0", "0.00", "USD 0.00"];
  for (const z of [0, -0, "0", "0.000", "-0"]) FORMATS.forEach((f, i) => assert.equal(T.value(z, f), zero[i], `${String(z)} as ${nameOf(f)}`));
});

test("A.1 a value that is not finite, null or undefined is the empty text at every format (D6): never In,fin,ity", () => {
  for (const v of [Infinity, -Infinity, NaN, null, undefined]) for (const f of FORMATS) assert.equal(T.value(v, f), "", `${String(v)} as ${nameOf(f)}`);
  // The other currency digits and decimal(n) widths take the same path.
  for (const f of ["currency(JPY)", "currency(KWD)", "decimal(0)", "decimal(6)"]) assert.equal(T.value(Infinity, f), "", f);
});

// ---------------------------------------------------------------- A.2 the start points from both sides, every format

test("A.2 the start points from both sides: one below 10^15 keeps its digits, 10^15 is 1e15; 0.00001 keeps its digits, 0.0000099 is 9.9e−6; a percent reads both from the percentage", () => {
  const v = T.value;
  assert.equal(v(999999999999999, null), "999,999,999,999,999");
  assert.equal(v(999999999999999, "integer"), "999,999,999,999,999");
  assert.equal(v(1e15, null), "1e15");
  assert.equal(v(1e15, "integer"), "1e15");
  assert.equal(v(1e15, "currency(JPY)"), "JPY 1e15", "a currency of no fraction digits takes the e form too");
  assert.equal(v(0.00001, null), "0.00001");
  assert.equal(v(0.00001, "compact"), "0.00001");
  assert.equal(v(0.0000099, null), "9.9e" + M + "6");
  assert.equal(v(0.0000099, "compact"), "9.9e" + M + "6");
  // A percent: the percentage 99,999,999,999,999 % (a value of 9.9999999999999e11, one below the start point) keeps
  // its digits; the percentage 10^15 % (a value of 10^13) is 1e15%.
  assert.equal(v(999999999999.99, "percent"), "99,999,999,999,999%");
  assert.equal(v(1e13, "percent"), "1e15%");
  assert.equal(v(9.99e12, "percent"), "999,000,000,000,000%");
  // Below: the percentage 0.00001 % (a value of 10^-7) keeps its digits, 0.0000099 % is 9.9e−6%.
  assert.equal(v(1e-7, "percent"), "0.00001%");
  assert.equal(v(9.9e-8, "percent"), "9.9e" + M + "6%");
  // A format that names its digits never takes the e form below (D5).
  assert.equal(v(9.9e-8, "decimal(6)"), "0.000000");
  assert.equal(v(2.5e-9, "currency(KWD)"), "KWD 0.000");
});

// A decimal as {n, e}: the value n × 10^e, n a BigInt. The test's own reader (exact; never the engine's).
function fromString(s) {
  const m = /^(-?)([0-9]+)(?:\.([0-9]+))?(?:e([+-]?[0-9]+))?$/.exec(s);
  assert.ok(m, "a decimal text: " + s);
  const frac = m[3] || "";
  return { n: BigInt((m[1] ? "-" : "") + m[2] + frac), e: (m[4] ? Number(m[4]) : 0) - frac.length };
}

/** A text the engine wrote, read back with the unit of its last written digit: {n, e} where 10^e is that unit. */
function readText(text) {
  let t = text.replace(/^[A-Z]{3} /, ""), shift = 0;
  assert.ok(!t.includes("-"), "no hyphen for a minus: " + text);
  t = t.split(M).join("-");
  if (t.endsWith("%")) {
    shift = -2;
    t = t.slice(0, -1);
  } else {
    const u = "KMBT".indexOf(t.charAt(t.length - 1));
    if (u >= 0) {
      shift = 3 * (u + 1);
      t = t.slice(0, -1);
    }
  }
  assert.ok(/^-?[0-9]{1,3}(,[0-9]{3})*(\.[0-9]+)?(e-?[0-9]+)?$/.test(t), "the shape of a written number: " + text);
  const d = fromString(t.replace(/,/g, ""));
  return { n: d.n, e: d.e + shift };
}

const abs = (n) => (n < 0n ? -n : n);
/** |a − b| ≤ half of 10^unit, exactly. */
function withinHalf(a, b, unit) {
  const e = Math.min(a.e, b.e, unit - 1);
  const scale = (x) => x.n * 10n ** BigInt(x.e - e);
  return abs(scale(a) - scale(b)) * 2n <= 10n ** BigInt(unit - e);
}

test("A.2 the sweep: 1, 2.5 and 9.99 times every power of ten a double holds, both signs, six formats; each text reads back within half its last digit; no e form longer than 13 characters, no text longer than 27", () => {
  const by = {}, longest = { all: "", sci: "" };
  let texts = 0;
  for (let k = -323; k <= 308; k++) for (const m of ["1", "2.5", "9.99"]) for (const sg of [1, -1]) {
    const x = sg * Number(m + "e" + k);
    if (!Number.isFinite(x) || x === 0) continue;
    const ref = fromString(String(x));
    for (const f of FORMATS) {
      const t = T.value(x, f), back = readText(t), sci = isSci(t);
      assert.ok(withinHalf(back, ref, back.e), `${x} as ${nameOf(f)}: ${t} is within half its last digit of ${String(x)}`);
      const b = (by[nameOf(f)] = by[nameOf(f)] || { texts: 0, sci: 0 });
      b.texts++;
      texts++;
      if (sci) {
        b.sci++;
        if (t.length > longest.sci.length) longest.sci = t;
      }
      if (t.length > longest.all.length) longest.all = t;
    }
  }
  console.log(`A.2 sweep: ${texts} texts; by format ${JSON.stringify(by)}; longest e form ${longest.sci.length} ${JSON.stringify(longest.sci)}; longest ${longest.all.length} ${JSON.stringify(longest.all)}`);
  assert.ok(texts >= 20000, "texts " + texts);
  for (const f of ["no format", "compact", "percent"]) assert.ok(by[f].sci >= 3000, f + " " + by[f].sci);
  for (const f of ["integer", "decimal(2)", "currency(USD)"]) assert.ok(by[f].sci >= 1500, f + " " + by[f].sci);
  assert.ok(longest.sci.length <= 13, "the longest text in the e form: " + longest.sci);
  assert.ok(longest.all.length <= 27, "the longest text: " + longest.all);
  assert.equal(T.value(-9.99e100, "currency(USD)"), "USD " + M + "9.99e100");
  assert.equal(T.value(-1e14, "currency(USD)"), "USD " + M + "100,000,000,000,000.00");
  // The two long texts D1 leaves as they are (below the start point, a named digit count).
  assert.equal(T.value(999999999999999, "currency(USD)"), "USD 999,999,999,999,999.00");
  assert.equal(T.value(-9.99e14, "currency(USD)"), "USD " + M + "999,000,000,000,000.00");
});

// ---------------------------------------------------------------- A.3 exact values (D2)

/** The brief's second table: an exact text at six formats, today's bytes but for the two small ones (D3, D4). */
const EXACT = [
  ["12345678901234567", "12,345,678,901,234,567", "12,345.7T", "1,234,567,890,123,456,700%", "12,345,678,901,234,567", "12,345,678,901,234,567.00", "USD 12,345,678,901,234,567.00"],
  ["1234567890123456789", "1,234,567,890,123,456,789", "1,234,567.9T", "123,456,789,012,345,678,900%", "1,234,567,890,123,456,789", "1,234,567,890,123,456,789.00", "USD 1,234,567,890,123,456,789.00"],
  ["123456789012345678901234567891", "123,456,789,012,345,678,901,234,567,891", "123,456,789,012,345,678.9T", "12,345,678,901,234,567,890,123,456,789,100%", "123,456,789,012,345,678,901,234,567,891", "123,456,789,012,345,678,901,234,567,891.00", "USD 123,456,789,012,345,678,901,234,567,891.00"],
  ["-1234567890123456789", "−1,234,567,890,123,456,789", "−1,234,567.9T", "−123,456,789,012,345,678,900%", "−1,234,567,890,123,456,789", "−1,234,567,890,123,456,789.00", "USD −1,234,567,890,123,456,789.00"],
  ["12345678901234567890.1234", "12,345,678,901,234,567,890.12", "12,345,678.9T", "1,234,567,890,123,456,789,012.3%", "12,345,678,901,234,567,890", "12,345,678,901,234,567,890.12", "USD 12,345,678,901,234,567,890.12"],
  ["999999999999999.999", "1,000,000,000,000,000", "1,000T", "99,999,999,999,999,999.9%", "1,000,000,000,000,000", "1,000,000,000,000,000.00", "USD 1,000,000,000,000,000.00"],
  ["0.0000001234567890123456789", "1.23e−7", "1.23e−7", "0.00001%", "0", "0.00", "USD 0.00"],
  ["0.004999", "0.005", "0.005", "0.5%", "0", "0.00", "USD 0.00"],
];

/** A whole number's text built from the input's own characters: every digit, grouped in threes, the true minus. */
function grouped(s) {
  const neg = s.startsWith("-"), d = neg ? s.slice(1) : s;
  let out = "";
  for (let i = 0; i < d.length; i++) out += (i > 0 && (d.length - i) % 3 === 0 ? "," : "") + d.charAt(i);
  return (neg ? M : "") + out;
}

test("A.3 an exact value never takes the e form from 10^15 up and keeps every digit before the point (D2); a small one follows D3 and D4 from its own digits", () => {
  assert.equal(EXACT.length, 8);
  assert.equal(assertRows(EXACT), 48);
  const wholes = EXACT.map((r) => r[0]).filter((s) => /^-?[0-9]+$/.test(s));
  assert.equal(wholes.length, 4);
  for (const s of wholes) for (const f of [null, "integer"]) {
    const t = T.value(s, f);
    assert.equal(t, grouped(s), `${s} as ${nameOf(f)}: every digit, from the input's own characters`);
    assert.equal(t.replace(/,/g, "").replace(M, "-"), s, "nothing dropped: " + t);
    assert.ok(!isSci(t), t);
  }
});

// ---------------------------------------------------------------- A.7 the probes, Node's half

test("A.7 the value-text probes, Node's half: value-text-probes.js evaluated in Node is value-text-probes.json byte for byte (the sandbox's half is JsRenderedValueTextsTest); and the comparison goes red on one text changed in one run alone", () => {
  const script = readFileSync(path.join(HERE, "value-text-probes.js"), "utf8"), expected = readFileSync(path.join(HERE, "value-text-probes.json"), "utf8");
  // The engine without the test types: the sandbox evaluates the product's program alone.
  const plain = engineWith([]), text = new Function("R", script + "\nreturn valueTextProbes(R);")(plain);
  assert.equal(text + "\n", expected);
  const probes = JSON.parse(text), sci = probes.filter((p) => isSci(p.text)).length, empty = probes.filter((p) => p.text === "").length;
  console.log(`A.7 probes: ${probes.length}, ${text.length} characters, ${sci} in the e form, ${empty} empty`);
  assert.ok(probes.length >= 264, "probes " + probes.length);
  assert.ok(sci >= 100, "texts in the e form " + sci);
  assert.equal(empty, 18, "Infinity, −Infinity and NaN at the six formats (D6)");
  assert.equal(new Set(probes.map((p) => p.name)).size, probes.length, "every probe has its own name");
  // The control (valueTextProbesPlanted, in the probe file so both runs evaluate the same code): one text of one probe
  // written with a hyphen for its true minus. Its text is the snapshot but for that one character.
  const planted = new Function("R", script + "\nreturn valueTextProbesPlanted(R);")(plain), WAS = '"2.5e-9"', IS = '"2.5e−9"';
  assert.notEqual(planted, text);
  assert.equal(planted.split(WAS).length - 1, 1);
  assert.equal(text.split(WAS).length - 1, 0);
  assert.equal(planted.replace(WAS, IS), text);
  const other = JSON.parse(planted);
  assert.deepEqual(probes.filter((p, i) => JSON.stringify(p) !== JSON.stringify(other[i])).map((p) => p.name), ["2.5 billionths as no format"]);
});
