// The invariant checker for the Node suites (record §8 "Invariants": I1 to I5 and I7; brief 985 A.3, D7). Its I1, I2,
// I4 and I5 are geometry.js, a classic script the sandbox loads too (JsRenderedIdentityTest), run here in its own vm
// context over a PRISTINE engine's width table (a planted width change must not move the checker with it, G2). I3's
// expected count and D7's frames are here.
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import { engine } from "./engine.mjs";

const ctx = vm.createContext({ PRISTINE: engine() });
vm.runInContext(readFileSync(join(dirname(fileURLToPath(import.meta.url)), "geometry.js"), "utf8") + "\nthis.G = JsRenderedInvariants(PRISTINE);", ctx, { filename: "geometry.js" });
const G = ctx.G;

/** Overlap below this many px is not a meeting (rounding to two decimals moves an edge by at most 0.005). */
export const TOL = G.TOL;
export const { width, drawn, textBox, meet, markBox, arcExtremes, texts } = G;

/** The violations of I1, I2, I4 and I5 in one picture of frame W × H, with the counts checked: {violations, texts, marks}. */
export function violations(prims, W, H) {
  return G.violations(prims, W, H);
}

/**
 * I3's expected count for a bar fixture, computed from the delivered columns by this test, never by the engine. A
 * `color` of type quantitative is a shade (brief 1050 D7), known from the document alone: it makes no series, so the
 * cells are the bands and a row whose shade is null is still a row of its band.
 */
export function expectedBarMarks(fx, hiddenIds) {
  const e = fx.document.config.encoding, vertical = e.y.type === "quantitative";
  const band = vertical ? e.x : e.y, value = vertical ? e.y : e.x, given = e.color || null;
  const color = given !== null && given.type !== "quantitative" ? given : null;
  const col = (f) => (f && Object.prototype.hasOwnProperty.call(fx.columns, f) ? fx.columns[f] : []);
  const B = col(band.field), V = value.field ? col(value.field) : null, C = color ? col(color.field) : null;
  const cells = new Map();
  for (let i = 0; i < B.length; i++) {
    const b = B[i], s = C ? C[i] : null;
    if (b === null || b === undefined || (C && (s === null || s === undefined))) continue;
    const sid = C ? typeof s + ":" + String(s) : "measure";
    // Nested maps: a value may hold any character, NUL included (parity fixture bar-nul-keys), so no joined key.
    const bk = typeof b + ":" + String(b);
    if (!cells.has(bk)) cells.set(bk, new Map());
    const row = cells.get(bk);
    if (!row.has(sid)) row.set(sid, []);
    row.get(sid).push(V ? V[i] : 1);
  }
  // #1011: a stack at zero whose sum, up or down, is beyond float64 draws none of its cells; the sums are taken over
  // every series, hidden ones too, and only then are the hidden ones left out of the count.
  const stackZero = color !== null && value.stack === "zero";
  let n = 0;
  for (const row of cells.values()) {
    const drawn = [];
    for (const [sid, vals] of row) {
      if (value.aggregate === "count") {
        drawn.push([sid, vals.length]);
        continue;
      }
      const xs = vals.filter((v) => v !== null && v !== undefined).map(Number);
      if (xs.length === 0) continue;
      let r = value.aggregate === "min" || value.aggregate === "max" ? xs[0] : 0;
      for (const x of xs) r = value.aggregate === "min" ? Math.min(r, x) : value.aggregate === "max" ? Math.max(r, x) : r + x;
      if (Number.isFinite(r)) drawn.push([sid, r]);
    }
    if (stackZero) {
      let up = 0, down = 0;
      for (const [, r] of drawn) if (r < 0) down += r; else up += r;
      if (!Number.isFinite(up) || !Number.isFinite(down)) continue;
    }
    n += drawn.filter(([sid]) => !hiddenIds.includes(sid)).length;
  }
  return n;
}

/**
 * I3's expected count for a line or area fixture (brief 989 D8: mark_count counts the data's non-null points), computed
 * by this test from the delivered columns, never by the engine: rows sharing x and series are one point, aggregated; a
 * null x, a null series or a null aggregate is no point; on a log value axis (a line's) a value at or below 0 is none.
 * A temporal x counts any non-null text (the fixtures carry only valid dates); a quantitative one any finite number.
 */
export function expectedLineMarks(fx, hiddenIds) {
  const e = fx.document.config.encoding, x = e.x, y = e.y, color = e.color || null;
  const col = (f) => (f && Object.prototype.hasOwnProperty.call(fx.columns, f) ? fx.columns[f] : []);
  const X = col(x.field), C = color ? col(color.field) : null;
  const measures = Array.isArray(y.fields) ? y.fields.map((f, i) => ({ id: "field:" + f.field, V: col(f.field) })) : [{ id: "measure", V: y.field ? col(y.field) : null }];
  const log = fx.document.config.mark.type === "line" && y.scale && y.scale.type === "log";
  const cells = new Map();
  for (let i = 0; i < X.length; i++) {
    const xv = X[i];
    if (xv === null || xv === undefined) continue;
    if (x.type === "quantitative" && !Number.isFinite(Number(xv))) continue;
    if (C && (C[i] === null || C[i] === undefined)) continue;
    for (const m of measures) {
      const sid = C ? typeof C[i] + ":" + String(C[i]) : m.id;
      const key = JSON.stringify([sid, x.type === "quantitative" ? Number(xv) : String(xv)]);
      if (!cells.has(key)) cells.set(key, []);
      cells.get(key).push(m.V ? m.V[i] : 1);
    }
  }
  // #1011: a stacked area's position whose sum, up or down, is beyond float64 draws no point of any series; decided
  // over every series, hidden ones too.
  const stackZero = fx.document.config.mark.type === "area" && (C !== null || Array.isArray(y.fields)) && y.stack === "zero";
  const points = [];
  for (const [key, vals] of cells) {
    let r;
    if (y.aggregate === "count") r = vals.length;
    else {
      const xs = vals.filter((v) => v !== null && v !== undefined).map(Number);
      if (xs.length === 0) continue;
      r = y.aggregate === "min" || y.aggregate === "max" ? xs[0] : 0;
      for (const v of xs) r = y.aggregate === "min" ? Math.min(r, v) : y.aggregate === "max" ? Math.max(r, v) : r + v;
      if (y.aggregate === "mean") r /= xs.length;
    }
    if (!Number.isFinite(r) || (log && !(r > 0))) continue;
    const [sid, at] = JSON.parse(key);
    points.push({ sid, at: JSON.stringify(at), r });
  }
  const over = new Set();
  if (stackZero) {
    const sums = new Map();
    for (const p of points) {
      const t = sums.get(p.at) || { up: 0, down: 0 };
      if (p.r < 0) t.down += p.r; else t.up += p.r;
      sums.set(p.at, t);
    }
    for (const [at, t] of sums) if (!Number.isFinite(t.up) || !Number.isFinite(t.down)) over.add(at);
  }
  return points.filter((p) => !over.has(p.at) && !hiddenIds.includes(p.sid)).length;
}

/**
 * I3's expected count for a scatter fixture (brief 1062 D3, D11), computed by this test from the delivered columns,
 * never by the engine: one mark per row, no aggregation. A row is no mark when its series is null; when its x or y is
 * null (a time x: not a date text "YYYY-MM-DD…"), or its size is null where the chart has one; when x, y or the size is
 * not a finite number (an exact column's text read by Number); on a log axis, when its value is 0 or less. A shade's
 * value never removes a row. The rows of a hidden series are left out.
 */
export function expectedScatterMarks(fx, hiddenIds) {
  const e = fx.document.config.encoding, x = e.x, y = e.y, given = e.color || null, size = e.size || null;
  const color = given !== null && given.type !== "quantitative" ? given : null;
  const col = (f) => (f && Object.prototype.hasOwnProperty.call(fx.columns, f) ? fx.columns[f] : []);
  const X = col(x.field), Y = col(y.field), C = color ? col(color.field) : null, Z = size ? col(size.field) : null;
  const absent = (v) => v === null || v === undefined;
  const logX = x.type !== "temporal" && x.scale && x.scale.type === "log", logY = y.scale && y.scale.type === "log";
  let n = 0;
  for (let i = 0; i < Y.length; i++) {
    if (C && absent(C[i])) continue;
    if (absent(X[i]) || absent(Y[i]) || (Z && absent(Z[i]))) continue;
    if (x.type === "temporal" && !/^[0-9]{4}-[0-9]{2}-[0-9]{2}/.test(String(X[i]))) continue;
    const xn = x.type === "temporal" ? 1 : Number(X[i]), yn = Number(Y[i]), zn = Z ? Number(Z[i]) : 0;
    if (!Number.isFinite(xn) || !Number.isFinite(yn) || !Number.isFinite(zn)) continue;
    if ((logX && !(xn > 0)) || (logY && !(yn > 0))) continue;
    const sid = C ? typeof C[i] + ":" + String(C[i]) : "measure";
    if (!hiddenIds.includes(sid)) n++;
  }
  return n;
}

/**
 * D7's widths and heights: every level is met. The record's three heights (100, 200, 480) and, since #985 R1, the
 * compact and full level heights themselves (140, 240), where the refusal found texts meeting and leaving the frame.
 */
export const WIDTHS = Array.from({ length: (1600 - 240) / 40 + 1 }, (_, i) => 240 + 40 * i);
export const HEIGHTS = [100, 140, 200, 240, 480];

/** Pie I3, from the fixture rows: group, aggregate, fold smallest first, then hide and omit unrepresentable angles. */
export function expectedPieMarks(fixture, hiddenIds) {
  const {theta, color} = fixture.document.config.encoding;
  const groups = new Map();
  fixture.columns[color.field].forEach((name, i) => {
    if (name == null) return;
    const id = typeof name + ":" + name;
    if (!groups.has(id)) groups.set(id, []);
    if (theta.aggregate === "count") groups.get(id).push(1);
    else if (fixture.columns[theta.field][i] != null) groups.get(id).push(Number(fixture.columns[theta.field][i]));
  });
  let slices = [...groups].filter(([, v]) => v.length).map(([id, v]) => ({id, n: theta.aggregate === "count" ? v.length : theta.aggregate === "min" ? Math.min(...v) : theta.aggregate === "max" ? Math.max(...v) : v.reduce((a, b) => a + b, 0) / (theta.aggregate === "mean" ? v.length : 1)}));
  slices = slices.filter(s => Number.isFinite(s.n));
  if (slices.some(s => s.n < 0)) return 0;
  const limit = fixture.document.config.mark.max_slices ?? 8;
  if (slices.length > limit) {
    const ranked = slices.map((s, i) => ({...s, i})).sort((a, b) => b.n - a.n || a.i - b.i);
    const keep = new Set(ranked.slice(0, limit - 1).map(s => s.i));
    const other = slices.filter((s, i) => !keep.has(i)).reduce((a, s) => a + s.n, 0);
    slices = slices.filter((s, i) => keep.has(i));
    if (Number.isFinite(other)) slices.push({id: "other", n: other});
  }
  if (!Number.isFinite(slices.reduce((a, s) => a + s.n, 0))) return 0;
  const shown = slices.filter(s => !hiddenIds.includes(s.id) && s.n > 0);
  const total = shown.reduce((a, s) => a + s.n, 0);
  return shown.filter(s => s.n / total * 360 >= 0.02).length;
}
