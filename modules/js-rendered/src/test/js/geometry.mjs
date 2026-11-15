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
export const { width, drawn, textBox, meet, markBox, texts } = G;

/** The violations of I1, I2, I4 and I5 in one picture of frame W × H, with the counts checked: {violations, texts, marks}. */
export function violations(prims, W, H) {
  return G.violations(prims, W, H);
}

/** I3's expected count for a bar fixture, computed from the delivered columns by this test, never by the engine. */
export function expectedBarMarks(fx, hiddenIds) {
  const e = fx.document.config.encoding, vertical = e.y.type === "quantitative";
  const band = vertical ? e.x : e.y, value = vertical ? e.y : e.x, color = e.color || null;
  const col = (f) => (f && Object.prototype.hasOwnProperty.call(fx.columns, f) ? fx.columns[f] : []);
  const B = col(band.field), V = value.field ? col(value.field) : null, C = color ? col(color.field) : null;
  const cells = new Map();
  for (let i = 0; i < B.length; i++) {
    const b = B[i], s = C ? C[i] : null;
    if (b === null || b === undefined || (C && (s === null || s === undefined))) continue;
    const sid = C ? typeof s + ":" + String(s) : "measure";
    if (hiddenIds.includes(sid)) continue;
    // Nested maps: a value may hold any character, NUL included (parity fixture bar-nul-keys), so no joined key.
    const bk = typeof b + ":" + String(b);
    if (!cells.has(bk)) cells.set(bk, new Map());
    const row = cells.get(bk);
    if (!row.has(sid)) row.set(sid, []);
    row.get(sid).push(V ? V[i] : 1);
  }
  let n = 0;
  for (const vals of [...cells.values()].flatMap((m) => [...m.values()])) {
    if (value.aggregate === "count") {
      n++;
      continue;
    }
    const xs = vals.filter((v) => v !== null && v !== undefined).map(Number);
    if (xs.length === 0) continue;
    let r = value.aggregate === "min" || value.aggregate === "max" ? xs[0] : 0;
    for (const x of xs) r = value.aggregate === "min" ? Math.min(r, x) : value.aggregate === "max" ? Math.max(r, x) : r + x;
    if (Number.isFinite(r)) n++;
  }
  return n;
}

/**
 * D7's widths and heights: every level is met. The record's three heights (100, 200, 480) and, since #985 R1, the
 * compact and full level heights themselves (140, 240), where the refusal found texts meeting and leaving the frame.
 */
export const WIDTHS = Array.from({ length: (1600 - 240) / 40 + 1 }, (_, i) => 240 + 40 * i);
export const HEIGHTS = [100, 140, 200, 240, 480];
