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
/** Box I3, from rows alone: exact decimal fences, finite-value filtering, and both drawn-mark caps (#1072 D18). */
export function expectedBoxMarks(fx) {
  const {x, y} = fx.document.config.encoding;
  const Y = fx.columns[y.field], X = x ? fx.columns[x.field] : null;
  const groups = new Map();
  for (let i = 0; i < Y.length; i++) {
    if (Y[i] == null || (X && X[i] == null) || !Number.isFinite(Number(Y[i]))) continue;
    if (y.scale?.type === "log" && Number(Y[i]) <= 0) continue;
    const key = X ? JSON.stringify([typeof X[i], String(X[i])]) : "one";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(Y[i]);
  }
  if (groups.size > 50) return 0;
  if (fx.document.config.mark.outliers === "none") return groups.size;
  let drawn = 0;
  for (const values of groups.values()) {
    let outside;
    if (values.every(v => typeof v === "string")) {
      const parts = values.map(v => /^(-?)(\d+)(?:\.(\d+))?$/.exec(v));
      const scale = Math.max(...parts.map(p => (p[3] || "").length));
      const s = parts.map(p => BigInt(p[1] + p[2] + (p[3] || "").padEnd(scale, "0"))).sort((a,b) => a < b ? -1 : a > b ? 1 : 0);
      const q = k => { const h = (s.length - 1) * k, i = Math.floor(h / 4), f = BigInt(h % 4); return s[i] * (4n - f) + s[Math.min(i+1,s.length-1)] * f; };
      const a = q(1), b = q(3), low = 2n * a - 3n * (b-a), high = 2n * b + 3n * (b-a);
      outside = s.filter(v => 8n*v < low || 8n*v > high).length;
    } else {
      const s = values.map(Number).sort((a,b) => a-b);
      const q = p => { const h=(s.length-1)*p, i=Math.floor(h), f=h-i; return f ? s[i]*(1-f)+s[i+1]*f : s[i]; };
      const a=q(0.25), b=q(0.75), low=a-1.5*(b-a), high=b+1.5*(b-a);
      const span=b/1024-a/1024;
      outside = s.filter(v => (Number.isFinite(low) ? v<low : v/1024 < a/1024-1.5*span) || (Number.isFinite(high) ? v>high : v/1024 > b/1024+1.5*span)).length;
    }
    drawn += Math.min(200, outside);
  }
  return drawn > 1000 ? 0 : groups.size;
}

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
/** Exact-decimal arithmetic for heatmap I3: only the final aggregate crosses to float64. Independent of the engine. */
function heatmapExact(values, aggregate) {
  const parts = values.map((v) => {
    const m = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(v));
    if (!m) throw new Error("I3 exact decimal: " + v);
    return { sign: m[1], whole: m[2], fraction: m[3] || "" };
  });
  const scale = Math.max(...parts.map((p) => p.fraction.length));
  const integers = parts.map((p) => BigInt(p.sign + p.whole + p.fraction.padEnd(scale, "0")));
  let result;
  let places = scale;
  if (aggregate === "min" || aggregate === "max") {
    result = integers.reduce((a, b) => aggregate === "min" ? (a < b ? a : b) : (a > b ? a : b));
  } else {
    result = integers.reduce((a, b) => a + b, 0n);
    if (aggregate === "mean") {
      result = result * 10n ** 20n / BigInt(integers.length);
      places += 20;
    }
  }
  const sign = result < 0n ? "-" : "";
  const digits = (result < 0n ? -result : result).toString().padStart(places + 1, "0");
  const point = digits.length - places;
  return Number(sign + digits.slice(0, point) + (places ? "." + digits.slice(point) : ""));
}

/** I3, from delivered rows: one finite non-null aggregate per typed pair, including count's non-null-only rule. */
export function expectedHeatmapMarks(fx) {
  const e = fx.document.config.encoding, X = fx.columns[e.x.field], Y = fx.columns[e.y.field], V = fx.columns[e.color.field];
  const groups = new Map();
  for (let i = 0; i < Math.max(X.length, Y.length); i++) {
    if (X[i] == null || Y[i] == null) continue;
    const key = JSON.stringify([typeof X[i], String(X[i]), typeof Y[i], String(Y[i])]);
    if (!groups.has(key)) groups.set(key, []);
    if (V[i] != null) groups.get(key).push(V[i]);
  }
  let count = 0;
  for (const values of groups.values()) {
    if (!values.length) continue;
    const a = e.color.aggregate;
    let value;
    if (a === "count") value = values.length;
    else if (values.some((v) => typeof v === "string")) value = heatmapExact(values, a);
    else if (a === "min") value = Math.min(...values);
    else if (a === "max") value = Math.max(...values);
    else {
      value = values.reduce((x, y) => x + y, 0);
      if (a === "mean") value /= values.length;
    }
    if (Number.isFinite(value)) count++;
  }
  return count;
}

// The I3 oracle for a waterfall, review round 1 (R1-1): computed from the ROWS and the DOCUMENT, never from the engine. The first
// form (../oracle-expectedWaterfall.mjs) walked the categories in input order whatever x.sort said; the order decides where the
// running level first passes float64, so it decides the count. That form is kept as the plant.
// The order is derived here, with no call into the engine: null or absent = first appearance; a list = the listed values first,
// in the list's order (matched by their text), then the rest by first appearance; ascending / descending = numbers by value,
// plain dates (YYYY-MM-DD) by their text, other texts by CODE POINT (never UTF-16 units), descending the reverse of ascending.
// A band it cannot order on its own (a temporal value that is not a plain date, an exact-number column as the band) THROWS.
const waterfallCodePoints = (s) => Array.from(String(s), (c) => c.codePointAt(0));
const waterfallByCodePoint = (a, b) => { const x = waterfallCodePoints(a), y = waterfallCodePoints(b); for (let i = 0; i < Math.min(x.length, y.length); i++) if (x[i] !== y[i]) return x[i] - y[i]; return x.length - y.length; };
const WATERFALL_PLAIN_DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/;
function waterfallOrdered(items, enc, type) {
  const sort = enc.sort;
  if (sort === null || sort === undefined) return items;
  if (Array.isArray(sort)) {
    const pos = new Map(sort.map((v, i) => [String(v), i]));
    const listed = items.filter((it) => pos.has(String(it.raw))).sort((a, b) => pos.get(String(a.raw)) - pos.get(String(b.raw)));
    return listed.concat(items.filter((it) => !pos.has(String(it.raw))));
  }
  if (sort !== "ascending" && sort !== "descending") throw new Error("expectedWaterfallMarks: a waterfall's x.sort is null, ascending, descending or a list, not " + JSON.stringify(sort));
  if (type === "BIGINTEGER" || type === "BIGDECIMAL") throw new Error("expectedWaterfallMarks: no own order for an exact-number band");
  if (enc.type === "temporal") for (const it of items) if (!WATERFALL_PLAIN_DATE.test(String(it.raw))) throw new Error("expectedWaterfallMarks: no own order for the temporal value " + String(it.raw));
  const up = items.slice().sort((a, b) => (typeof a.raw === "number" && typeof b.raw === "number" ? (a.raw < b.raw ? -1 : a.raw > b.raw ? 1 : 0) : waterfallByCodePoint(a.raw, b.raw)));
  return sort === "descending" ? up.reverse() : up;
}
export function expectedWaterfallMarks(f) {
  const e = f.document.config.encoding, mark = f.document.config.mark;
  const B = f.columns[e.x.field] || [], V = e.y.field && f.columns[e.y.field] ? f.columns[e.y.field] : null;
  const cells = new Map();
  for (let i = 0; i < B.length; i++) { if (B[i] === null || B[i] === undefined) continue; const k = typeof B[i] + ":" + String(B[i]); if (!cells.has(k)) cells.set(k, { raw: B[i], vals: [] }); cells.get(k).vals.push(V ? V[i] : 1); }
  const steps = [];
  for (const { vals } of waterfallOrdered(Array.from(cells.values()), e.x, (f.types || {})[e.x.field])) {
    let r;
    if (e.y.aggregate === "count") r = vals.length;
    else { const xs = vals.filter((v) => v !== null && v !== undefined).map(Number); if (xs.length === 0) continue; r = e.y.aggregate === "min" || e.y.aggregate === "max" ? xs[0] : 0; for (const x of xs) r = e.y.aggregate === "min" ? Math.min(r, x) : e.y.aggregate === "max" ? Math.max(r, x) : r + x; if (e.y.aggregate === "mean") r /= xs.length; }
    if (Number.isFinite(r)) steps.push(r);
  }
  let level = 0, drawn = 0, cut = false;
  for (const x of steps) { level += x; if (!Number.isFinite(level)) { cut = true; break; } drawn++; }
  const total = mark.total === undefined ? "end" : mark.total;
  return drawn + (total === "end" && drawn > 0 && !cut ? 1 : 0);
}
