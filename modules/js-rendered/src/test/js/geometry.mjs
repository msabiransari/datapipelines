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
 * D7's widths and heights: every level is met. The record's three heights (100, 200, 480) and, since #985 R1, the
 * compact and full level heights themselves (140, 240), where the refusal found texts meeting and leaving the frame.
 */
export const WIDTHS = Array.from({ length: (1600 - 240) / 40 + 1 }, (_, i) => 240 + 40 * i);
export const HEIGHTS = [100, 140, 200, 240, 480];
