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
    cellGapMaxShare: f(0.25, "render", "brief 1070 D7: gap takes at most a quarter of each cell slot, leaving positive sides at small frames"),
    heatmapLabelsDefault: f("none", "render", "look contract §4.3: heatmap labels are absent unless the author asks for values"),
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
    labelMinEm: f(6, "render", "em: on a row whose labels may be cut, thinning keeps every k-th label so each has at least min(its width, this many em) of room (lane 977's rule); a row of whole labels (a position axis) asks for the widest label's whole width instead (lane 1087)"),
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
    labelGap: f(0.67, "render", "em: the least room between two neighbouring tick labels (category labels; the value axis's labels and the plot's least height, #985 R1)"),
    cornerClear: f(0.08, "render", "em: the corner rule's room around the lowest value tick label (#981); a browser rounds a font's ascent and descent to whole pixels (D9)"),
    axisTitleGap: f(0.33, "render", "em: between an axis title and its tick labels"),
    legendKey: f(0.83, "render", "em: a legend key's side"),
    legendKeyGap: f(0.42, "render", "em: between a legend key and its name"),
    legendEntryGap: f(1, "render", "em: between two legend entries"),
    legendGap: f(0.67, "render", "em: between the legend and the plot"),
    legendStepRatio: f(1.6, "render", "§3.3 and the first look study (16 × 10 px): a step of a colour scale's legend is legendKey high and this many times as wide in a row, the other way round in a column"),
    valueLabelGap: f(0.25, "render", "em of --chart-text-small: between a bar's end and its value label"),
    // the radial axis (brief 1055 D8; record §9 G3, look contract §2.1 radar "grid: straight", §4.7 radar)
    radialRings: f("polygon", "render", "look contract §2.1 radar (L9, grid: straight): a radial axis's rings are polygons through the spokes (\"circle\": one circle per ring, reached by the table's test only)"),
    radialTickSpan: f(2, "render", "× the radius: the axis length the radial axis's tick rule is given (its scale is read across the whole circle)"),
    radialNameShare: f(0.25, "render", "the widest a radial axis's name may be, as a share of the plot's width"),
    radialRadiusFloor: f(0.5, "render", "a radial axis writes its names only while the radius left after their room is at least this share of the polar frame's"),
    // Pie (brief 1068 D13): rendered defaults, label fit, hole and hover reserve.
    pieInnerDefault: f(0.6, "render", "brief 1068 D13: pieInnerDefault"),
    pieLabelsDefault: f("percent", "render", "brief 1068 D13: pieLabelsDefault"),
    holeTotalMin: f(34, "render", "brief 1068 D13: holeTotalMin"),
    sliceLabelPad: f(4, "render", "brief 1068 D13: sliceLabelPad"),
    holeUnitGap: f(4, "render", "brief 1068 D13: holeUnitGap"),
    pieLabelRadius: f(0.66, "render", "brief 1068 D13: pieLabelRadius"),
    pieOtherText: f("Other", "render", "brief 1068 D13: pieOtherText"),
    pieMoveAllowance: f(5, "render", "brief 1068 D13: pieMoveAllowance"),
    boxShare: f(0.5, "render", "look contract §2.1, §3.8 box; brief 1072 D7, D14: boxShare"),
    boxMaxWidth: f(56, "render", "look contract §2.1, §3.8 box; brief 1072 D7, D14: boxMaxWidth"),
    boxCapShare: f(0.44, "render", "look contract §2.1, §3.8 box; brief 1072 D7, D14: boxCapShare"),
    boxMinLines: f(3, "render", "look contract §2.1, §3.8 box; brief 1072 D7, D14: boxMinLines"),
    boxOutsideLess: f(1, "render", "look contract §2.1, §3.8 box; brief 1072 D7, D14: boxOutsideLess"),
    boxOutsideOpacity: f(0.75, "render", "look contract §2.1, §3.8 box; brief 1072 D7, D14: boxOutsideOpacity"),
    boxOutsideRing: f(0.75, "render", "look contract §2.1, §3.8 box; brief 1072 D7, D14: boxOutsideRing"),
    boxOutliersDefault: f("points", "render", "look contract §2.1, §3.8 box; brief 1072 D7, D14: boxOutliersDefault"),
    // Waterfall defaults and labels (look contract §4.3 and §4.7; brief 1073 D10).
    waterfallTotalDefault: f("end", "render", "brief 1073 D10: waterfallTotalDefault"),
    waterfallLabelSlack: f(6, "render", "brief 1073 D10: waterfallLabelSlack"),
    waterfallHitMin: f(8, "render", "brief 1073 D10: waterfallHitMin"),
    waterfallRiseText: f("Increase", "render", "brief 1073 D10: waterfallRiseText"),
    waterfallFallText: f("Decrease", "render", "brief 1073 D10: waterfallFallText"),
    waterfallOpeningText: f("Opening", "render", "brief 1073 D10: waterfallOpeningText"),
    waterfallTotalText: f("Total", "render", "brief 1073 D10: waterfallTotalText"),
    waterfallKeyTotalText: f("Opening and total", "render", "brief 1073 D10: waterfallKeyTotalText"),
    waterfallRunningText: f("Running total", "render", "brief 1073 D10: waterfallRunningText"),
    funnelLeast: f(6, "render", "brief 1074 D13: funnelLeast"),
    funnelFit: f(12, "render", "brief 1074 D13: funnelFit"),
    funnelShareMaxEm: f(6, "render", "brief 1074 D13: funnelShareMaxEm"),
    funnelNameShare: f(0.5, "render", "brief 1074 D13: funnelNameShare"),
    funnelGapShare: f(0.5, "render", "brief 1074 D13: funnelGapShare"),
    funnelLabelsDefault: f("percent_of_first", "render", "brief 1074 D13: funnelLabelsDefault"),
    funnelFirstText: f("Of the first step", "render", "brief 1074 D13: funnelFirstText"),
    funnelPreviousText: f("Of the step before", "render", "brief 1074 D13: funnelPreviousText"),
    funnelTinyText: f("<1%", "render", "brief 1074 D13: funnelTinyText"),
    // words
    // the line family (brief 989 D6, D7, D10a; look contract §2 line, weight, points, area, legend; §4.2; §4.3; §4.5)
    curveDefault: f("smooth", "render", "§2 line and the record's §4.5: a line's curve is a monotone cubic unless the author wrote another"),
    pointsDefault: f("end", "render", "§2 points and the record's §4.5: one dot on each series' last point unless the author wrote another"),
    lineLabelsDefault: f("none", "render", "§4.3: a line carries no value label unless the author wrote labels: last"),
    lastLabelFormat: f("compact", "render", "§4.5: a last value without a format of its own is written in the short form (\"Revenue 3.7K\")"),
    lineRound: f(true, "render", "§2 weight: a line's joins and ends are round"),
    areaFade: f(true, "render", "§2 area: the fill fades from --chart-area-top at the plot's top to --chart-area-bottom at the baseline (false: one flat opacity, the top's)"),
    legendKeyLine: f("line", "render", "§4.2: a line's legend key is a short line"),
    legendKeyArea: f("square", "render", "§4.2: an area's legend key is a square"),
    legendKeyPoint: f("dot", "render", "§4.2: a point's legend key is a dot"),
    directMin: f(2, "render", "§4.2 line 4 (L5): the names are on the lines from this many series in the data"),
    directMax: f(4, "render", "§4.2 line 4 (L5): the names are on the lines up to this many series in the data"),
    directGap: f(0.5, "render", "em: between the plot's right edge and a name or a last value written after the lines"),
    emptyText: f("No data", "render", "record §4.3: the empty state's one text, until the look lane words it"),
    morePrefix: f("+", "render", "record §4.6: the compact legend's '+n' entry"),
    // The interaction layer's (record §4.8, §4.10; look contract §3.8, §4.4, L8; brief 990 D2a, D4a): read on the page
    // by datapipelines-chart-interactions.js, never by render. The card's five measures are × --chart-text (s); with
    // the default tokens 2 × tipPad + tipLine = 2 × 3.96 + 15.6 = 23.52 ≤ 24, so the record's smallest frame (80 × 24)
    // holds the one-row card (LA13).
    tipDistance: f(14, "interaction", "contract §3.8 (C5): the tooltip card stands this many px from its anchor, flipped at the frame's edges"),
    tipLine: f(1.3, "interaction", "× s: the card's line height h (contract §4.4, L8)"),
    tipPad: f(0.33, "interaction", "× s: the card's padding p, the same on all four sides, its border inside it (contract §4.4, L8)"),
    tipKey: f(0.67, "interaction", "× s: the width of a card row's key, a square of the series colour (contract §4.4, L8)"),
    tipGap: f(0.5, "interaction", "× s: the gap between the parts of a card row: key, name, value (contract §4.4, L8)"),
    tipMax: f(36, "interaction", "× s: the card's greatest width G (contract §4.4, L8)"),
    tipTotalJoin: f(" · ", "interaction", "contract §4.4: a stacked bar's card heads with the category, this text, then the stack's total (index.totals); shown whole or the category alone (L8)"),
    tipValueWeight: f(600, "interaction", "contract §4.4: a card row's value is bold, and the hovered series' name in a bar's group"),
    crosshairDot: f(0.5, "interaction", "contract §3.4: a crosshair dot's radius is --chart-point plus this many px"),
    focusGap: f(0.25, "interaction", "em: the keyboard's focus ring stands this far outside its target's box"),
    markReach: f(24, "interaction", "contract §3.8 (C5), §4.4: a point that asks for it is found within this many px of the pointer"),
    markMove: f(5, "interaction", "contract §3.8 (C5), §4.4: a hovered slice moves this many px outward"),
    markRing: f(0.25, "interaction", "em: the hover ring stands this far outside its mark"),
  };

  var CAPS = {
    rows: { kind: "row", limit: 20000, basis: "measured", unit: "rows", note: "record §4.9, every type: lane 985 D12 measured bar by S-CAP (re-taken in round 2 on the corrected engine); 20,000 passes aggregating to 60 marks and on the refusal path (every row its own group), which fails at 50,000 (longest drag frame 83.4 ms over 3.5 × D = 58.45 ms); F5 measures the line" },
    series: { kind: "semantic", limit: 8, basis: "fixed", unit: "series", note: "O-15: 8 series on any type with a legend" },
    "bar.marks": { kind: "mark", limit: 1000, basis: "measured", unit: "non-null aggregated cells", note: "lane 977 S-CAP: bar 200 × 5 passed, 1,000 × 5 failed" },
    "line.points": { kind: "mark", limit: 2000, basis: "measured", unit: "points per series drawn in full, then thinned", note: "record §4.9: lane 989 D9 (d) by S-CAP (round 3), the smooth line in full at 2 series, two runs: 2,000 passes (longest drag frame 33.4 ms both runs), 5,000 fails (66.7 ms both runs, over 3.5 × D = 58.45 ms)" },
    "line.rows": { kind: "row", limit: 10000, basis: "measured", unit: "rows", note: "record §4.9: the line family's row cap, lane 989 D9 (a) by S-CAP (round 3), the thinned line at 2 series under line.points, two runs: 10,000 passes (redraw p95 33.1 and 49.0 ms, longest drag frame 33.4 ms both), 20,000 fails (drag 66.6 and 66.7 ms, over 3.5 × D = 58.45 ms)" },
    "area.points": { kind: "mark", limit: 1000, basis: "measured", unit: "points per series drawn in full, then thinned", note: "record §4.9: lane 989 D9 (b) by S-CAP (round 3), a stacked area of 2 series in full from line's cap down, two runs: 1,000 passes (longest drag frame 33.4 ms both runs); 2,000 fails one of its two runs (66.6 ms, over 3.5 × D = 58.45 ms; 50.1 ms in the other)" },
    "area.rows": { kind: "row", limit: 10000, basis: "inherited", unit: "rows", note: "record §4.9: the line family's row cap, inherited from line (lane 989 D9 (a), round 3)" },
    "scatter.marks": { kind: "mark", limit: 1000, basis: "measured", unit: "rows drawn", note: "record §4.9 Draft 13 (the numbers that stand): scatter's 1,000 is lane 977's browser table, as bar's 1,000 is (lane 977 S-CAP: scatter 1,000 passed, 2,000 failed by one frame of 50.1 ms); one mark per row drawn, never thinned" },
    "pie.marks": { kind: "semantic", limit: 8, basis: "fixed", unit: "slices", note: "Record §4.5 pie: eight slices, not measured (R19); marks are series, so the series cap refuses first." },
    "heatmap.marks": { kind: "mark", limit: 1000, basis: "inherited", unit: "non-null cells", note: "record §4.9 heatmap row: min(2,500, 1,000) = 1,000 cells, inherited and not measured here (R19)" },
    "box.marks": { kind: "mark", limit: 50, basis: "inherited", unit: "boxes", note: "record §4.5 box cap and §4.9 box row: 50 boxes; inherited, not measured for box (R19)" },
    "box.outliers": { kind: "mark", limit: 1000, basis: "inherited", unit: "outside points drawn, over all boxes", note: "record §4.9 box row: 1,000 outlier points; inherited, not measured for box (R19)" },
    "box.outliers_each": { kind: "semantic", limit: 200, basis: "fixed", unit: "outside points drawn per box", note: "record §4.9 semantic limits box row: 200 outlier points per box; fixed, not measured for box (R19)" },
    "waterfall.marks": { kind: "mark", limit: 50, basis: "inherited", unit: "bars", note: "Record §4.5 waterfall and §4.9: 50 bars including the total; inherited, not based on the lane 1073 prototype's 20,000-row timings (R19)." },
    "funnel.marks": { kind: "semantic", limit: 12, basis: "fixed", unit: "stages", note: "record §4.5 and §4.9: at most 12 stages; the thirteenth refuses with chart.too_many_marks. A semantic limit, not measured (R19)." },
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
