// types/funnel.js — centred stage bars (§4.5; #1074). The none frame owns chrome.
(function (R) {
  "use strict";
  var P = R.prim, D = R.data, T = R.text, L = R.lines, r2 = R.scales.r2, look = R.tables.look;
  var CHANNELS = [
    { key: "stage", required: true, node: { node: "shape", name: "band" } },
    { key: "value", required: true, node: { node: "shape", name: "value" } },
  ];
  function finite(n) { return typeof n === "number" && n - n === 0; }
  function partOf(s) { return s.exact !== null ? s.exact : s.num; }

  /** A missing denominator, an overflowing quotient and an underflowing positive quotient are distinct. */
  function share(num, den) {
    if (!(den > 0)) return { kind: "none" };
    var s = num / den;
    if (!finite(s)) return { kind: "over" };
    if (s === 0 && num > 0) return { kind: "tiny" };
    return { kind: "is", v: s };
  }
  function shareLabel(sh) {
    if (sh.kind === "none" || sh.kind === "over") return null;
    if (sh.kind === "tiny") return look("funnelTinyText");
    var c = sh.v * 100;
    if (sh.v > 0 && Math.round(c) === 0) return look("funnelTinyText");
    // From 2^53 hundredths onward the share is already whole at this precision; multiplying can overflow.
    return T.value(c < 9007199254740992 ? Math.round(c) / 100 : sh.v, "percent");
  }
  function shareCard(sh) {
    return sh.kind === "is" ? T.value(sh.v, "percent") : sh.kind === "tiny" ? look("funnelTinyText") : null;
  }

  function model(doc, columns, types) {
    var e = doc.config.encoding, st = e.stage, va = e.value, groups = new Map(), warnings = [], seen = {};
    function warn(code) { if (seen[code] !== true) { seen[code] = true; warnings.push({ code: code }); } }
    var B = D.column(columns, st.field), V = va.aggregate === "count" ? null : D.column(columns, va.field);
    for (var i = 0; i < B.length; i++) {
      var b = B[i];
      if (D.isNull(b)) { warn("chart.rule.null-category"); continue; }
      var k = D.keyOf(b);
      if (!groups.has(k)) groups.set(k, { key: k, raw: b, first: groups.size, full: String(b), vals: [] });
      groups.get(k).vals.push(V ? V[i] : 1);
    }
    var ordered = D.order(Array.from(groups.values()), st.sort, function () { return null; }, (types || {})[st.field], false);
    var stages = [];
    for (var j = 0; j < ordered.length; j++) {
      var a = D.aggregate(ordered[j].vals, va.aggregate, va.field || "count");
      if (a.num === null) continue;
      if (!finite(a.num)) { warn("chart.rule.non-finite"); continue; }
      if (a.num < 0) return { rule: { code: "chart.rule.negative-part", details: {} } };
      stages.push({ name: ordered[j].full, num: a.num, exact: a.exact });
    }
    var shares = [];
    for (var z = 0; z < stages.length; z++) {
      var first = share(stages[z].num, stages[0].num), prev = z === 0 ? { kind: "none" } : share(stages[z].num, stages[z - 1].num);
      if (first.kind === "over" || prev.kind === "over") warn("chart.rule.non-finite");
      shares.push({ first: first, prev: prev });
    }
    var dflt = va.field ? va.aggregate.charAt(0).toUpperCase() + va.aggregate.slice(1) + " of " + va.field : "Count";
    var vtitle = va.title === null ? null : va.title === undefined ? dflt : va.title;
    if (vtitle !== null && typeof va.unit === "string" && va.unit !== "") vtitle += " (" + va.unit + ")";
    return {
      series: [{ id: "measure", title: vtitle === null ? "" : vtitle, colour: R.theme.SERIES[0] }],
      stages: stages, shares: shares, markCount: stages.length, warnings: warnings, legend: undefined,
      labels: doc.config.mark.labels === undefined ? look("funnelLabelsDefault") : doc.config.mark.labels,
      value: va, vtitle: vtitle,
    };
  }

  function marks(m, hand, h) {
    var plot = hand.plot, theme = h.theme, n = m.stages.length, text = h.text, small = h.small, tw = h.titleWeight;
    var token = m.series[0].colour, css = theme.css(token), rowH = plot.h / n;
    var gap = Math.min(theme.n("--chart-gap"), look("funnelGapShare") * rowH), barH = rowH - gap;
    var cg = h.em("tickGap"), canText = hand.level !== "minimal" && rowH >= h.boxH(text);
    var canValue = hand.level !== "minimal" && rowH >= h.boxH(small), vmax = 0;
    var shareText = [], cardShare = [], nameShown = [], shareW = 0, nameW = 0;
    var cap = Math.min(h.em("bandLabelMaxEm"), look("funnelNameShare") * plot.w);
    for (var a = 0; a < n; a++) {
      vmax = Math.max(vmax, m.stages[a].num);
      var sh = m.shares[a];
      cardShare.push({ first: shareCard(sh.first), prev: shareCard(sh.prev) });
      var pick = m.labels === "percent_of_first" ? sh.first : m.labels === "percent_of_previous" ? sh.prev : null;
      var t = pick === null ? null : shareLabel(pick);
      if (t !== null && h.width(t, text, 400) > look("funnelShareMaxEm") * text) t = null;
      shareText.push(t);
      if (canText && t !== null) shareW = Math.max(shareW, h.width(t, text, 400));
      var shown = canText ? h.fit(m.stages[a].name, text, 400, cap) : "";
      nameShown.push(shown);
      if (shown !== "") nameW = Math.max(nameW, h.width(shown, text, 400));
    }
    var right = plot.x + plot.w, barsLeft = plot.x + (nameW > 0 ? nameW + cg : 0);
    var barsRight = right - (shareW > 0 ? shareW + cg : 0), full = Math.max(0, barsRight - barsLeft);
    var least = Math.min(look("funnelLeast"), full), cx = barsLeft + full / 2;
    var rects = [], texts = [], lookup = [];
    for (var i = 0; i < n; i++) {
      var s = m.stages[i];
      // Divide first: the ratio is bounded by one even when the values approach the end of the number range.
      var w = vmax > 0 ? Math.max(least, full * (s.num / vmax)) : least;
      var y0 = plot.y + i * rowH, cy = y0 + rowH / 2, by = y0 + (rowH - barH) / 2;
      var rx = r2(Math.min(theme.n("--chart-bar-radius"), look("radiusMaxShare") * Math.min(w, barH)));
      rects.push(P.rect({ role: "mark", x: cx - w / 2, y: by, w: w, h: barH, fill: token, color: css, rx: rx, series: "measure", datum: i }));
      if (nameShown[i] !== "") texts.push(h.label({ role: "tick", x: barsLeft - cg, y: h.middle(cy, text), text: s.name, shown: nameShown[i], anchor: "end", size: text, ink: "--chart-ink-2" }));
      var vt = L.withUnit(T.value(partOf(s), m.value.format), m.value.unit), wv = h.width(vt, small, tw);
      if (canValue && barH >= h.boxH(small) && w >= wv + look("funnelFit")) {
        var ink = R.theme.onFill(theme.colour(token), theme.colour("--chart-on-fill-light"), theme.colour("--chart-on-fill-dark"));
        texts.push(h.label({ role: "guide", x: cx, y: h.middle(cy, small), text: vt, anchor: "middle", size: small, weight: tw, ink: ink }));
      } else if (canValue && cx + w / 2 + look("valueLabelGap") * small + wv <= barsRight + 1e-9) {
        texts.push(h.label({ role: "guide", x: cx + w / 2 + look("valueLabelGap") * small, y: h.middle(cy, small), text: vt, anchor: "start", size: small, ink: "--chart-ink-2" }));
      }
      if (canText && shareText[i] !== null) texts.push(h.label({ role: "guide", x: right, y: h.middle(cy, text), text: shareText[i], anchor: "end", size: text, ink: "--chart-muted" }));
      var rows = [{ name: m.vtitle === null ? "" : m.vtitle, value: vt, colour: token, color: css }], cs = cardShare[i];
      if (cs.first !== null) rows.push({ name: look("funnelFirstText"), value: cs.first });
      if (i > 0 && cs.prev !== null) rows.push({ name: look("funnelPreviousText"), value: cs.prev });
      var top = r2(plot.y + i * rowH), bot = r2(plot.y + (i + 1) * rowH);
      lookup.push({ series: "measure", datum: i, shape: { kind: "box", x: plot.x, y: top, w: plot.w, h: r2(bot - top) },
        row: i, emphasis: null, card: { heading: s.name, rows: rows, keep: 0 } });
    }
    var out = [];
    P.group(out, { role: "mark", series: "measure", label: m.vtitle === null ? "" : m.vtitle }, rects);
    return { primitives: out.concat(texts), lookup: lookup, count: n };
  }
  R.type("funnel", { channels: CHANNELS, model: model, marks: marks, axes: "none" });
})(JsRendered);
