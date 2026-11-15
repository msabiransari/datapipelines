// 06-tables.js : the two closed tables of the engine (record §4.9 and §4.10). Neither is reachable from a chart
// document, a page option or a token; both are the same file in the browser and on the server, so I6 holds without
// either adapter passing them.
//
// LOOK — every value the look contract fixes and does not make a token (its §3.8): one value per field, read by field
// name, each field naming its one consumer (`render` in this lane: the shared layout or a template). No template and
// no layout file holds such a value as a number or a word of its own (A14, LA5). A spacing the contract does not value
// is a multiple of the text size (`em`: × --chart-text unless the field says otherwise), so it follows the host's text.
//
// CAPS — the semantic limits, the mark caps and the row cap (§4.9), each marked with its basis: `measured` (a lane's
// browser and sandbox numbers), `inherited` (from a measured family), `provisional` (awaiting its measurement) or
// `fixed` (a semantic limit: what a type means; no measurement changes it).
(function (R) {
  "use strict";
  function f(value, consumer, note) {
    return { value: value, consumer: consumer, note: note };
  }
  var LOOK = {
    // §2 grid, axis, corners, bars: structure
    gridValue: f(true, "render", "§2 grid: a line at every value tick"),
    gridCategory: f(true, "render", "§2 grid: a line at every drawn category label"),
    frame: f(true, "render", "§2 axis: a rectangle around the plot"),
    zeroLine: f(true, "render", "§2 axis: the zero line when zero is inside the range"),
    barShare: f(0.74, "render", "§2 bars: a group of bars fills 74% of its band"),
    stackShare: f(0.7, "render", "§2 bars: a stacked bar fills 70% of its band"),
    radiusMaxShare: f(0.5, "render", "§2 corners: the rounded end is never more than half the bar's width"),
    roundOuterOnly: f(true, "render", "§2 corners: in a stack only the outermost segment is rounded"),
    legendKeyBar: f("square", "render", "§4.2: a bar's legend key is a square (the shared layout also draws \"line\", a stroke across the key)"),
    labelsDefault: f("values", "render", "§4.3 and the record's §4.5: bar value labels are on by default"),
    stackTotalFormat: f("compact", "render", "§4.3: a stack's total is written in the short form"),
    // the record's §4.6: levels, ticks, rotation
    levelFull: f([360, 240], "render", "record §4.6: the full level from this frame (provisional)"),
    levelCompact: f([200, 140], "render", "record §4.6: the compact level from this frame (provisional)"),
    levelMinimal: f([80, 24], "render", "record §4.6: the minimal level from this frame (provisional); below it nothing is drawn"),
    tickSpacing: f(48, "render", "record §4.6: at most one value tick per this many px of axis"),
    tickSpacingCompact: f(72, "render", "record §4.6: fewer ticks at the compact level: at most one per this many px"),
    rotateAngle: f(45, "render", "record §4.6: the angle of a rotated category label, degrees"),
    rotateHiddenShare: f(0.5, "render", "record §4.6: rotate only when thinning would hide more than this share of the labels"),
    labelMinEm: f(6, "render", "em: thinning keeps every k-th label so each has at least min(its width, this many em) of room (lane 977's rule)"),
    rotateMaxEm: f(10, "render", "em: a rotated category label is cut to this length"),
    bandLabelMaxEm: f(12, "render", "em: a horizontal bar's category names are cut to this width"),
    legendSideMaxEm: f(10, "render", "em: a legend at the left or right is at most this wide"),
    // the font's vertical box (read from the vendored Noto Sans, lane 985 evidence d8): where a text's box lies
    textAscent: f(1.069, "render", "a text's box rises this many × its size above its baseline"),
    textDescent: f(0.293, "render", "a text's box falls this many × its size below its baseline"),
    // spacings the contract does not value: multiples of the text size (§3.8, last row)
    edge: f(0.5, "render", "em: the inset between the frame's edge and anything drawn"),
    lineHeight: f(1.4, "render", "× a text's size: the distance between two lines of text"),
    titleGap: f(0.5, "render", "em: below the title block"),
    tickGap: f(0.5, "render", "em: between a tick label and the plot"),
    labelGap: f(0.67, "render", "em: the least room between two neighbouring category labels"),
    cornerClear: f(0.08, "render", "em: the corner rule's room around the lowest value tick label (#981); a browser rounds a font's ascent and descent to whole pixels (D9)"),
    axisTitleGap: f(0.33, "render", "em: between an axis title and its tick labels"),
    legendKey: f(0.83, "render", "em: a legend key's side"),
    legendKeyGap: f(0.42, "render", "em: between a legend key and its name"),
    legendEntryGap: f(1, "render", "em: between two legend entries"),
    legendGap: f(0.67, "render", "em: between the legend and the plot"),
    valueLabelGap: f(0.25, "render", "em of --chart-text-small: between a bar's end and its value label"),
    // words
    emptyText: f("No data", "render", "record §4.3: the empty state's one text, until the look lane words it"),
    morePrefix: f("+", "render", "record §4.6: the compact legend's '+n' entry"),
  };

  var CAPS = {
    rows: { kind: "row", limit: 100000, basis: "provisional", unit: "rows", note: "record §4.9: every type; F1 measures it for bar (D12)" },
    series: { kind: "semantic", limit: 8, basis: "fixed", unit: "series", note: "O-15: 8 series on any type with a legend" },
    "bar.marks": { kind: "mark", limit: 1000, basis: "measured", unit: "non-null aggregated cells", note: "lane 977 S-CAP: bar 200 × 5 passed, 1,000 × 5 failed" },
  };

  function look(name) {
    if (!Object.prototype.hasOwnProperty.call(LOOK, name)) throw new Error("no look-table field " + name);
    return LOOK[name].value;
  }

  function cap(name) {
    if (!Object.prototype.hasOwnProperty.call(CAPS, name)) throw new Error("no cap-table entry " + name);
    return CAPS[name].limit;
  }

  R.tables = { LOOK: LOOK, CAPS: CAPS, look: look, cap: cap };
})(JsRendered);
