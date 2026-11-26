// types/gauge.js — one aggregate on a 240° open arc (§4.5; #1075). The polar frame owns chrome.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, T = R.text, TR = R.trig, r2 = R.scales.r2, look = R.tables.look;
  var CHANNELS = [{ key: "value", required: true, node: { node: "shape", name: "value" } }];
  var TONES = { good: "--chart-good", warn: "--chart-warn", bad: "--chart-bad" };
  function word(tone) { return look(tone === "good" ? "gaugeWordGood" : tone === "warn" ? "gaugeWordWarn" : "gaugeWordBad"); }
  function part(a) { return a.exact !== null ? a.exact : a.num; }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
  function finite(v) { return typeof v === "number" && v - v === 0; }
  function model(doc, columns) {
    var e = doc.config.encoding.value, mark = doc.config.mark, domain = e.scale.domain;
    var n = D.rowCount(columns), vals = [], col = e.aggregate === "count" ? null : D.column(columns, e.field);
    for (var i = 0; i < n; i++) vals.push(col === null ? 1 : col[i]);
    var a = n === 0 ? { num: null, exact: null } : D.aggregate(vals, e.aggregate, e.field);
    var span = domain[1] - domain[0], shown = a.num !== null, warnings = [];
    if (shown && (!finite(a.num) || !finite(span) || span <= 0)) {
      shown = false; warnings.push({ code: "chart.rule.non-finite" });
    }
    var bands = mark.bands || [], target = typeof mark.target === "number" ? mark.target : null;
    var keys = [], seen = {};
    bands.forEach(function (b) {
      if (seen[b.tone] !== true) {
        seen[b.tone] = true;
        keys.push({ id: b.tone, title: word(b.tone), key: "legendKeyBar", colour: TONES[b.tone] });
      }
    });
    if (target !== null) keys.push({ id: "target", title: look("gaugeWordTarget"), key: "legendKeyLine", colour: "--chart-ink" });
    var title = e.aggregate === "count" ? "Count" : e.aggregate.charAt(0).toUpperCase() + e.aggregate.slice(1) + " of " + e.field;
    var m = {
      series: [{ id: "measure", title: title, colour: R.theme.SERIES[0] }], markCount: shown ? 1 : 0, warnings: warnings,
      value: shown ? a : null, lo: domain[0], hi: domain[1], bands: bands, target: target, format: e.format, unit: e.unit,
      title: doc.config.title && doc.config.title.text ? doc.config.title.text : null,
    };
    if (keys.length > 0) { m.keys = keys; m.keysAlone = true; }
    return m;
  }
  function thickness(ra) {
    return Math.min(clamp(ra * look("gaugeThickShare"), look("gaugeThickMin"), look("gaugeThickMax")), ra * look("gaugeThickCap"));
  }
  /** Largest radius on the two-decimal grid satisfying an increasing extent. */
  function solve(extent, room, slope) {
    var lo = 0, hi = room / slope;
    for (var i = 0; i < 48; i++) { var mid = (lo + hi) / 2; if (extent(mid) <= room) lo = mid; else hi = mid; }
    return Math.floor(lo * 100) / 100;
  }
  /** Fit the entire picture, including butt-stroke corners at every possible target angle. */
  function fit(plot, half, endDrop, banded, targeted, wide) {
    var down = -TR.cos(half), side = TR.sin(half), reach = look("gaugeTargetReach");
    function extra(ra) {
      var edge = ra + thickness(ra) / 2, e = banded ? look("gaugeRingOffset") + look("gaugeRingWidth") / 2 : 0;
      if (targeted) { var far = edge + reach; e = Math.max(e, Math.sqrt(far * far + wide * wide / 4) - edge); }
      return e;
    }
    function below(ra) {
      var t = thickness(ra), b = down * ra + t / 2 + endDrop;
      if (banded) b = Math.max(b, down * (ra + t / 2 + look("gaugeRingOffset")) + look("gaugeRingWidth") / 2);
      if (targeted) b = Math.max(b, down * (ra + t / 2 + reach) + side * wide / 2);
      return b;
    }
    var rw = solve(function (r) { return r + thickness(r) / 2 + extra(r); }, plot.w / 2, 1);
    var rh = solve(function (r) { return r + thickness(r) / 2 + extra(r) + below(r); }, plot.h, 1 + down);
    var ra = Math.min(rw, rh);
    return { ra: ra, extra: extra(ra), below: below(ra) };
  }
  function inside(cx, cy, x, y, w, h, radius) {
    for (var i = 0; i < 2; i++) for (var j = 0; j < 2; j++) {
      var dx = x + i * w - cx, dy = y + j * h - cy;
      if (Math.sqrt(dx * dx + dy * dy) > radius) return false;
    }
    return true;
  }
  function zone(m) {
    var v = clamp(m.value.num, m.lo, m.hi);
    for (var i = 0; i < m.bands.length; i++) if (v <= m.bands[i].to) return i;
    return -1;
  }
  /** Empty and out-of-order intervals do not consume a ring piece; borders belong to both neighbours. */
  function intervals(m) {
    var out = [], from = m.lo;
    m.bands.forEach(function (b) {
      var to = Math.min(b.to, m.hi);
      if (to > from) out.push({ from: from, to: to, tone: b.tone });
      from = Math.max(from, b.to);
    });
    return out;
  }
  function labels(m, h, g) {
    var out = [], cx = g.cx, cy = g.cy, ra = g.ra, thick = g.thick;
    var radius = r2(ra - thick / 2 - look("gaugePad")), least = look("gaugeNumberMin") * h.text;
    var size = clamp(look("gaugeNumberShare") * ra, least, look("gaugeNumberMax") * h.text);
    var plain = T.value(part(m.value), m.format), full = R.lines.withUnit(plain, m.unit);
    var tries = [[full, size], [plain, size], [full, least], [plain, least]], chosen = null;
    for (var i = 0; i < tries.length && chosen === null; i++) {
      var t = tries[i], w = h.width(t[0], t[1], h.titleWeight), bh = h.boxH(t[1]);
      if (inside(cx, cy, cx - w / 2, cy - bh / 2, w, bh, radius)) chosen = { text: t[0], size: t[1], bh: bh };
    }
    if (chosen !== null) {
      out.push(h.label({ role: "mark", x: cx, y: h.middle(cy, chosen.size), text: chosen.text, anchor: "middle", size: chosen.size, weight: h.titleWeight, ink: "--chart-ink" }));
      var z = zone(m);
      if (z >= 0) {
        var name = word(m.bands[z].tone), ww = h.width(name, h.text, 400), top = cy + chosen.bh / 2 + look("gaugeWordGap");
        if (inside(cx, cy, cx - ww / 2, top, ww, h.boxH(h.text), radius)) {
          out.push(h.label({ role: "mark", x: cx, y: top + h.ascent(h.text), text: name, anchor: "middle", size: h.text, ink: "--chart-ink-2" }));
        }
      }
    }
    var ends = [], plot = g.plot;
    [[m.lo, -g.half], [m.hi, g.half]].forEach(function (e) {
      var name = T.value(e[0], m.format), w = h.width(name, h.small, 400), p = TR.point(cx, cy, ra, e[1]);
      var x = clamp(p.x, plot.x + w / 2, plot.x + plot.w - w / 2);
      ends.push({ x: x, w: w, text: name, y: p.y + thick / 2 + look("gaugeEndGap") + h.ascent(h.small) });
    });
    if (ends[0].x + ends[0].w / 2 + 2 <= ends[1].x - ends[1].w / 2) ends.forEach(function (e) {
      out.push(h.label({ role: "tick", x: r2(e.x), y: r2(e.y), text: e.text, anchor: "middle", size: h.small, ink: "--chart-muted" }));
    });
    return out;
  }
  function marks(m, hand, h) {
    if (hand.visible.measure !== true || m.value === null) return { primitives: [], lookup: [], count: 0 };
    var theme = h.theme, plot = hand.plot, half = look("gaugeSweep") / 2, text = hand.level !== "minimal";
    var banded = m.bands.length > 0, targeted = m.target !== null;
    var endDrop = text ? look("gaugeEndGap") + h.boxH(h.small) : 0;
    var wide = Math.max(look("gaugeTargetBack"), theme.n("--chart-line"));
    var f = fit(plot, half, endDrop, banded, targeted, wide), decor = true;
    if ((banded || targeted) && f.ra < look("gaugeDecorMin")) { decor = false; f = fit(plot, half, endDrop, false, false, wide); }
    if (!(f.ra > 0)) return { primitives: [], lookup: [], count: 0 };
    var ra = f.ra, thick = r2(thickness(ra)), outer = r2(ra + thick / 2 + f.extra), used = outer + f.below;
    var cx = r2(plot.x + plot.w / 2), top = plot.y + (plot.h - used) / 2, cy = r2(top + outer);
    // Take the fraction first: multiplying the offset by 240 can overflow on finite domains.
    function angle(v) { return r2(-half + look("gaugeSweep") * ((clamp(v, m.lo, m.hi) - m.lo) / (m.hi - m.lo))); }
    function arc(rad, from, to) { var p = TR.point(cx, cy, rad, from); return ["M", p.x, p.y].concat(TR.arc(cx, cy, rad, from, to)); }
    var out = [];
    out.push(P.path({ role: "grid", d: arc(ra, -half, half), stroke: "--chart-grid", strokeColor: theme.css("--chart-grid"), strokeWidth: thick, round: true }));
    if (decor && banded) {
      var pieces = intervals(m), rr = r2(ra + thick / 2 + look("gaugeRingOffset"));
      pieces.forEach(function (p, i) {
        var from = angle(p.from) + (i === 0 ? 0 : look("gaugeRingBreak"));
        var to = angle(p.to) - (i === pieces.length - 1 ? 0 : look("gaugeRingBreak"));
        if (to > from) out.push(P.path({ role: "guide", d: arc(rr, from, to), stroke: TONES[p.tone], strokeColor: theme.css(TONES[p.tone]), strokeWidth: look("gaugeRingWidth"), round: true }));
      });
    }
    var to = Math.max(angle(m.value.num), r2(-half + look("gaugeMinSweep")));
    P.group(out, { role: "mark", series: "measure", label: m.series[0].title }, [P.path({ role: "mark", series: "measure", datum: 0, d: arc(ra, -half, to), stroke: "--chart-series-1", strokeColor: theme.css("--chart-series-1"), strokeWidth: thick, round: true })]);
    if (decor && targeted) {
      var a = angle(m.target), p0 = TR.point(cx, cy, ra - thick / 2 - look("gaugeTargetReach"), a), p1 = TR.point(cx, cy, ra + thick / 2 + look("gaugeTargetReach"), a);
      [["--chart-canvas", look("gaugeTargetBack")], ["--chart-ink", theme.n("--chart-line")]].forEach(function (s) {
        out.push(P.line({ role: "guide", x1: p0.x, y1: p0.y, x2: p1.x, y2: p1.y, stroke: s[0], strokeColor: theme.css(s[0]), strokeWidth: s[1] }));
      });
    }
    if (text) out = out.concat(labels(m, h, { cx: cx, cy: cy, ra: ra, thick: thick, plot: plot, half: half }));
    function fmt(v) { return R.lines.withUnit(T.value(v, m.format), m.unit); }
    var rows = [{ name: look("gaugeWordNow"), value: fmt(part(m.value)), colour: "--chart-series-1", color: theme.css("--chart-series-1") }];
    if (targeted) rows.push({ name: look("gaugeWordTarget"), value: fmt(m.target), colour: null, color: null });
    m.bands.forEach(function (b) { rows.push({ name: word(b.tone), value: look("gaugeWordTo") + " " + fmt(b.to), colour: TONES[b.tone], color: theme.css(TONES[b.tone]) }); });
    var box = { kind: "box", x: r2(cx - outer), y: r2(top), w: r2(2 * outer), h: r2(used) };
    var left = box.x, right = r2(box.x + box.w), upper = box.y, bottom = r2(box.y + box.h);
    // The drawn end labels can overhang the arc. Union their written bounds without moving any ink (#1075 r2).
    out.forEach(function (p) {
      if (p.k !== "text" || p.role !== "tick") return;
      var w = h.width(p.text, p.size, p.weight);
      left = Math.min(left, r2(p.x - w / 2)); right = Math.max(right, r2(p.x + w / 2));
      upper = Math.min(upper, r2(p.y - h.ascent(p.size))); bottom = Math.max(bottom, r2(p.y + h.descent(p.size)));
    });
    box.x = r2(Math.max(plot.x, left)); box.y = r2(Math.max(plot.y, upper));
    box.w = r2(Math.min(plot.x + plot.w, right) - box.x); box.h = r2(Math.min(plot.y + plot.h, bottom) - box.y);
    return { primitives: out, lookup: [{ series: "measure", datum: 0, row: 0,
      shape: box, emphasis: null,
      card: { heading: m.title, rows: rows, keep: 0 } }], count: 1 };
  }
  R.type("gauge", { channels: CHANNELS, model: model, marks: marks, axes: "polar" });
})(JsRendered);
