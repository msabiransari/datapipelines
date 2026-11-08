// The JS half of the chart model parity (lane 860 A.2b, design record 2026-11-06 section 5.1).
//
//   node emit-js-model.mjs <chart.js> <parity-root> <examples-dir> <out.json>
//
// Runs the runtime core's `model` (the product's CURRENT datapipelines-dashboard-chart.js, passed by path) over every
// case of <parity-root>/corpus.json and writes {categories, series, cells, markCount} per case to <out.json>, which the
// Kotlin ChartModelParityTest (modules/visualization) compares field by field with ChartModel. The Gradle task
// `chartParityEmit` runs this before every visualization test run, never up to date, so the comparison always reads a
// fresh evaluation of the current core. Any failure exits non-zero, and no partial file is left behind.
import { readFileSync, writeFileSync, mkdirSync, rmSync, renameSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join, resolve } from "node:path";

const [chartPath, root, examplesDir, out] = process.argv.slice(2);
if (!chartPath || !root || !examplesDir || !out) {
  console.error("usage: node emit-js-model.mjs <chart.js> <parity-root> <examples-dir> <out.json>");
  process.exit(2);
}

const require = createRequire(import.meta.url);
const chart = require(resolve(chartPath));
const read = (path) => JSON.parse(readFileSync(path, "utf8"));

/** The product's spec as the runtime receives it: the document's config, columns flattened to {field: LogicalType}. */
function productSpec(doc) {
  const columns = {};
  Object.values(doc.inputs || {}).forEach((input) => {
    (input.columns || []).forEach((c) => {
      columns[c.name] = c.type;
    });
  });
  return { display_name: doc.display_name, description: doc.description, config: doc.config, columns };
}

/** A float64 as JSON: finite numbers as numbers (JSON.stringify writes the shortest round-trip digits). */
function num(v) {
  if (v === null) return null;
  if (typeof v !== "number" || !Number.isFinite(v)) throw new Error("a non-finite cell value: " + String(v));
  return v;
}

function item(i) {
  return { key: i.key, first: i.first, full: i.full };
}

function modelOf(doc, bindings) {
  const m = chart.core.model(productSpec(doc), bindings, chart._internal.DEFAULT_TOKENS);
  return {
    categories: m.categories.map(item),
    series: m.series.map(item),
    cells: m.cells.map((row) => row.map((c) => ({ num: num(c.num), exact: c.exact, aggregate: c.aggregate }))),
    markCount: m.markCount,
  };
}

const corpus = read(join(root, "corpus.json"));
const cases = {};
for (const name of corpus.fixtures) {
  const f = read(join(root, "fixtures", name + ".json"));
  cases["fixture/" + name] = modelOf(f.spec, f.bindings);
}
for (const name of corpus.examples) {
  const doc = read(join(examplesDir, name + ".json"));
  cases["example/" + name] = modelOf(doc, read(join(root, "fixtures", name + ".json")).bindings);
}
for (const name of corpus.numeric) {
  const f = read(join(root, "numeric", name + ".json"));
  cases["numeric/" + name] = modelOf(f.spec, f.bindings);
}

mkdirSync(dirname(resolve(out)), { recursive: true });
rmSync(out, { force: true });
const tmp = out + ".tmp";
writeFileSync(tmp, JSON.stringify({ chart: resolve(chartPath), cases }, null, 1) + "\n");
renameSync(tmp, out);
console.log("CHART_PARITY_CASES=" + Object.keys(cases).length);
