// The invariant checker (record §8 "Invariants": I1 to I5 and I7; brief 985 A.3, D7). It reads primitives only, so it
// runs without a browser. A text's box is its measured width (the PRISTINE engine's width table, never the engine under
// test: a planted width change must not move the checker with it, G2) by the font's vertical box (look fields
// textAscent and textDescent, read from the vendored Noto Sans: evidence d8), rotated about its anchor as the writer's
// transform does. Two boxes meet when they overlap by more than TOL px on every separating axis (oriented boxes).
import { engine } from "./engine.mjs";

/** Overlap below this many px is not a meeting (rounding to two decimals moves an edge by at most 0.005). */
export const TOL = 0.02;

const PRISTINE = engine();
const ASC = PRISTINE.tables.look("textAscent");
const DESC = PRISTINE.tables.look("textDescent");

/** The width of a text at a size, by the pristine table (Noto Sans, margin 1). */
export function width(text, size) {
  return PRISTINE.measure.measurer(null).fn(text, size, 400);
}

/** What a text primitive draws: its shown text when cut, else its text. */
export function drawn(t) {
  return t.shown !== null ? t.shown : t.text;
}

/** The four corners of a text's box, in frame coordinates. */
export function textBox(t) {
  const s = drawn(t), w = width(s, t.size);
  const x0 = t.anchor === "start" ? 0 : t.anchor === "middle" ? -w / 2 : -w;
  const local = [[x0, -ASC * t.size], [x0 + w, -ASC * t.size], [x0 + w, DESC * t.size], [x0, DESC * t.size]];
  const a = (t.rotate * Math.PI) / 180, c = Math.cos(a), n = Math.sin(a);
  return local.map(([x, y]) => [t.x + x * c - y * n, t.y + x * n + y * c]);
}

function axes(poly) {
  const out = [];
  for (let i = 0; i < poly.length; i++) {
    const [x1, y1] = poly[i], [x2, y2] = poly[(i + 1) % poly.length];
    const len = Math.hypot(x2 - x1, y2 - y1);
    if (len > 0) out.push([-(y2 - y1) / len, (x2 - x1) / len]);
  }
  return out;
}

function project(poly, [ax, ay]) {
  let lo = Infinity, hi = -Infinity;
  for (const [x, y] of poly) {
    const p = x * ax + y * ay;
    if (p < lo) lo = p;
    if (p > hi) hi = p;
  }
  return [lo, hi];
}

/** True when two convex polygons overlap by more than TOL on every separating axis. */
export function meet(a, b) {
  for (const ax of axes(a).concat(axes(b))) {
    const [l1, h1] = project(a, ax), [l2, h2] = project(b, ax);
    if (h1 - TOL <= l2 || h2 - TOL <= l1) return false;
  }
  return true;
}

/** The bounding box [x0, y0, x1, y1] of a mark primitive. */
export function markBox(p) {
  if (p.k === "rect") return [p.x, p.y, p.x + p.w, p.y + p.h];
  if (p.k === "circle") return [p.cx - p.r, p.cy - p.r, p.cx + p.r, p.cy + p.r];
  if (p.k === "line") return [Math.min(p.x1, p.x2), Math.min(p.y1, p.y2), Math.max(p.x1, p.x2), Math.max(p.y1, p.y2)];
  const xs = [], ys = [];
  for (let i = 0; i < p.d.length; i++) {
    const c = p.d[i], n = c === "M" || c === "L" ? 2 : c === "A" ? 3 : 0;
    if (n === 0) continue;
    xs.push(p.d[i + n - 1]);
    ys.push(p.d[i + n]);
    i += n;
  }
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
}

/** The texts a picture draws (a text cut to nothing is not drawn). */
export function texts(prims) {
  return prims.filter((p) => p.k === "text" && drawn(p) !== "");
}

/** Every number of every primitive, for I5. */
function numbers(p, out) {
  for (const k of Object.keys(p)) {
    const v = p[k];
    if (typeof v === "number") out.push([k, v]);
    else if (Array.isArray(v)) for (const e of v) if (typeof e === "number") out.push([k, e]);
  }
  return out;
}

/**
 * The cut texts of a picture in groups: a title-role text drawn on more than one line (a wrapped subtitle, record §4.4
 * G5) is one group of its lines in order; every other cut text is a group of one.
 */
function cutGroups(ts) {
  const groups = new Map();
  for (const t of ts) {
    if (t.shown === null) continue;
    const key = t.role === "title" ? JSON.stringify(["title", t.text, t.size, t.weight]) : Symbol();
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(t);
  }
  return [...groups.values()];
}

/**
 * I4 over one group, or null when it holds: the lines, each without its ellipsis, joined by one space are a prefix of
 * the full text; only the last line may end with the ellipsis; a group ending without one shows the whole text and is
 * more than one line (one line without an ellipsis is a silent cut); a cut text is never its full text.
 */
function i4(lines) {
  const text = lines[0].text, heads = [];
  for (let i = 0; i < lines.length; i++) {
    const shown = lines[i].shown, cut = shown.endsWith("…");
    if (cut && i < lines.length - 1) return "an ellipsis before the last line";
    heads.push(cut ? shown.slice(0, -1).trimEnd() : shown);
  }
  const joined = heads.join(" "), last = lines[lines.length - 1].shown;
  if (lines.length === 1 && last === text) return "a cut text equal to its full text";
  if (!text.startsWith(joined)) return "not a prefix of the full text";
  if (!last.endsWith("…") && (lines.length === 1 || joined !== text)) return "cut without an ellipsis";
  return null;
}

/**
 * The violations of I1, I2, I4 and I5 in one picture of frame W × H: [{inv, what}]. Also returns the counts it checked,
 * so a caller can floor them (a checker that saw nothing is not a pass).
 */
export function violations(prims, W, H) {
  const out = [], ts = texts(prims), boxes = ts.map(textBox);
  for (let i = 0; i < ts.length; i++) {
    for (let j = i + 1; j < ts.length; j++) {
      if (meet(boxes[i], boxes[j])) out.push({ inv: "I1", what: JSON.stringify(drawn(ts[i])) + " meets " + JSON.stringify(drawn(ts[j])) });
    }
    for (const [x, y] of boxes[i]) {
      if (x < -TOL || y < -TOL || x > W + TOL || y > H + TOL) {
        out.push({ inv: "I2", what: "text " + JSON.stringify(drawn(ts[i])) + " leaves the frame at " + x.toFixed(2) + "," + y.toFixed(2) });
        break;
      }
    }
  }
  for (const lines of cutGroups(ts)) {
    const t = lines[0], why = i4(lines);
    if (why !== null) out.push({ inv: "I4", what: why + ": " + JSON.stringify(lines.map((l) => l.shown)) + " of " + JSON.stringify(t.text) });
  }
  let marks = 0;
  for (const p of prims) {
    if (p.role === "mark" && p.k !== "group" && p.k !== "text") {
      marks++;
      const [x0, y0, x1, y1] = markBox(p);
      if (x0 < -TOL || y0 < -TOL || x1 > W + TOL || y1 > H + TOL) out.push({ inv: "I2", what: "mark of " + p.series + " leaves the frame: " + [x0, y0, x1, y1].join(",") });
    }
    for (const [k, v] of numbers(p, [])) if (!Number.isFinite(v)) out.push({ inv: "I5", what: p.k + "." + k + " = " + v });
  }
  return { violations: out, texts: ts.length, marks };
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

/** D7's widths and heights: every level is met. */
export const WIDTHS = Array.from({ length: (1600 - 240) / 40 + 1 }, (_, i) => 240 + 40 * i);
export const HEIGHTS = [100, 200, 480];
