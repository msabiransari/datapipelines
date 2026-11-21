// Brief 1049 A.2 (D2, D3, D6): the frames without axes and the shared legend placement, over the test types
// test_plain (`none`), test_polar (`polar`) and test_quiet (a frame of its own with no titles and no legend). The plot a
// frame leaves is computed HERE from the title block's and the legend's measured sizes (C.titles and C.legendLayout,
// which this lane does not change) by D3's rule, never read from the frame. G2 and G3 are falsified here too: each
// check run over a planted copy of the engine must fail, naming what it checks.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, engineWith, testTypes, testTypeKey, typeFixtures, typeInputOf } from "./engine.mjs";
import { textBox, drawn as shownOf, TOL } from "./geometry.mjs";

// These checks own G1's three types; later lanes add test types and frames of their own. The shared sweep discovers
// all of them in types.test.mjs, while this fixed context keeps registry and diagnostic assertions independent.
const NAMES = ["test_plain", "test_polar", "test_quiet"];
const R = engineWith(NAMES);
/** One frame per level (emit.mjs's first three): full, compact, minimal. */
const LEVELS = [["full", 960, 540], ["compact", 300, 180], ["minimal", 120, 40]];
const PLACES = ["top", "bottom", "left", "right"];
const PLOT_CSS = "#e8eef7";

function fixture(type, name) {
  const f = typeFixtures(type).find((x) => x.name === name);
  assert.ok(f, `fixture ${type}/${name}`);
  return f.fixture;
}

function draw(E, type, name, w, h, extra) {
  return E.render(typeInputOf(fixture(type, name), w, h, extra));
}

/** [x0, y0, x1, y1] of a set of primitives (texts by their measured box). */
function bounds(prims) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const p of prims) {
    const pts = p.k === "text" ? textBox(p) : p.k === "rect" ? [[p.x, p.y], [p.x + p.w, p.y + p.h]] : [];
    for (const [x, y] of pts) {
      x0 = Math.min(x0, x);
      y0 = Math.min(y0, y);
      x1 = Math.max(x1, x);
      y1 = Math.max(y1, y);
    }
  }
  return [x0, y0, x1, y1];
}

function disjoint(a, b) {
  return a[2] - TOL <= b[0] || b[2] - TOL <= a[0] || a[3] - TOL <= b[1] || b[3] - TOL <= a[1];
}

function inside(b, W, H) {
  return b[0] >= -TOL && b[1] >= -TOL && b[2] <= W + TOL && b[3] <= H + TOL;
}

const legendPrims = (r) => r.primitives.filter((p) => p.role === "legend" && p.k !== "group");
const legendGroups = (r) => r.primitives.filter((p) => p.role === "legend" && p.k === "group");
const titleTexts = (r) => r.primitives.filter((p) => p.k === "text" && p.role === "title" && shownOf(p) !== "");
const plotRects = (r) => r.primitives.filter((p) => p.k === "rect" && p.fill === "--chart-plot");

/**
 * The plot D3 says a frame without axes leaves, from the measured sizes: the frame less the edge, below the title block,
 * less the legend at its place (rows may take the box less the legend's gap and the minimal level's height; a column
 * all of it).
 */
function expectedPlot(E, fx, W, H) {
  const theme = E.theme.resolve(fx.theme), h = E.chrome.helpers(theme, E.measure.measurer(null)), lvl = E.chrome.level(W, H, theme);
  const e = h.em("edge"), gap = h.em("legendGap"), r = E.render(typeInputOf(fx, W, H));
  const top = E.chrome.titles(h, fx.document, lvl, W, e).bottom;
  const box = { left: e, right: W - e, top, bottom: H - e };
  const series = r.summary.series.map((s) => ({ id: s.id, title: s.title }));
  const color = fx.document.config.encoding.color;
  const place = E.chrome.legendPlace(series, fx.hidden || [], lvl, color.legend, false);
  if (place !== null) {
    const side = place === "left" || place === "right", boxH = box.bottom - box.top;
    const lay = E.chrome.legendLayout(h, series, place, box.right - box.left, side ? boxH : boxH - gap - E.tables.look("levelMinimal")[1]);
    if (lay.items.length > 0 || lay.more !== null) {
      if (place === "top") box.top += lay.h + gap;
      else if (place === "bottom" || place === "compact") box.bottom -= lay.h + gap;
      else if (place === "left") box.left += lay.w + gap;
      else box.right -= lay.w + gap;
    }
  }
  const r2 = (v) => Math.round(v * 100) / 100;
  return { x: r2(box.left), y: r2(box.top), w: r2(box.right - box.left), h: r2(box.bottom - box.top) };
}

function near(a, b) {
  return Math.abs(a - b) <= 0.011;
}

function samePlot(a, b) {
  return near(a.x, b.x) && near(a.y, b.y) && near(a.w, b.w) && near(a.h, b.h);
}

// ---------------------------------------------------------------------------------------------------- registration

test("the frame registry holds cartesian, polar, none, and the test types' own frame after test_quiet loads", () => {
  const product = engine();
  for (const name of ["cartesian", "polar", "none"]) assert.ok(product.frames.names().includes(name), name + " is a product frame");
  assert.deepEqual([...R.frames.names()], [...product.frames.names(), "test_quiet"]);
  assert.deepEqual([...R.types()].map((t) => t.name), [...product.types()].map((t) => t.name).concat(NAMES));
  for (const name of NAMES) assert.ok(testTypes().includes(name), name + " has a test type file");
});

test("D2: a type registered with an unknown axes throws naming it", () => {
  const def = { channels: [], model: () => null, marks: () => [], axes: "spiral" };
  assert.throws(() => R.type("test_spiral", def), {
    message: "type: test_spiral: axes spiral is not a frame (" + R.frames.names().join(", ") + ")",
  });
  assert.throws(() => R.type("test_none", Object.assign({}, def, { axes: undefined })), /axes undefined is not a frame/);
});

test("D2: frames.add checks its definition and names the member; a second add of one name throws", () => {
  const lay = () => null;
  assert.throws(() => R.frames.add("test_noroom", { titles: true, legend: true, lay }), /test_noroom: room is a function \(the frame has a legend\)/);
  assert.throws(() => R.frames.add("test_t", { titles: "yes", legend: false, lay }), /test_t: titles is true or false/);
  assert.throws(() => R.frames.add("test_l", { titles: false, legend: 1, lay }), /test_l: legend is true or false/);
  assert.throws(() => R.frames.add("test_y", { titles: false, legend: false, lay: 3 }), /test_y: lay is a function/);
  assert.throws(() => R.frames.add("test_u", { titles: false, legend: false, lay, axes: 1 }), /test_u: unknown member axes/);
  assert.throws(() => R.frames.add("none", { titles: false, legend: false, lay }), /none is already added/);
  assert.throws(() => R.frames.add("Bad", { titles: false, legend: false, lay }), /lower-case/);
  assert.throws(() => R.frames.get("absent"), /no frame absent/);
  // Three members with no legend are enough, as test_quiet's own registration shows (it loaded above).
  const E = engineWith(["test_quiet"]);
  E.frames.add("test_three", { titles: false, legend: false, lay });
  assert.equal(E.frames.get("test_three").room, null);
});

// ------------------------------------------------------------------------------------- levels, legend and the plot

for (const type of ["test_plain", "test_polar"]) {
  test(`${type}: at each level the title block, the legend and the plot do not overlap and lie inside the frame`, () => {
    let checked = 0;
    for (const name of ["eight-long-names", "title-subtitle", ...PLACES.map((p) => "legend-" + p)]) {
      for (const [lvl, W, H] of LEVELS) {
        const r = draw(R, type, name, W, H), where = `${type}/${name} ${W}x${H}`;
        assert.equal(r.state, "rendered", where);
        assert.equal(r.level, lvl, where);
        const plot = r.index.plot, parts = [["plot", [plot.x, plot.y, plot.x + plot.w, plot.y + plot.h]]];
        if (titleTexts(r).length > 0) parts.push(["titles", bounds(titleTexts(r))]);
        if (legendPrims(r).length > 0) parts.push(["legend", bounds(legendPrims(r))]);
        for (const [what, b] of parts) assert.ok(inside(b, W, H), `${where}: the ${what} leaves the frame: ${b}`);
        for (let i = 0; i < parts.length; i++) {
          for (let j = i + 1; j < parts.length; j++) assert.ok(disjoint(parts[i][1], parts[j][1]), `${where}: the ${parts[i][0]} meets the ${parts[j][0]}`);
        }
        if (lvl !== "minimal") assert.ok(parts.length >= 2, `${where}: a title or a legend was drawn`);
        checked += parts.length;
      }
    }
    assert.ok(checked >= 40, "parts checked: " + checked);
  });

  test(`${type}: the plot is the box left by the title block and the legend (computed from their measured sizes), at every fixture and level`, () => {
    let n = 0;
    for (const f of typeFixtures(type)) {
      for (const [, W, H] of LEVELS) {
        const r = R.render(typeInputOf(f.fixture, W, H));
        if (r.state !== "rendered") continue;
        const want = expectedPlot(R, f.fixture, W, H);
        assert.ok(samePlot(r.index.plot, want), `${type}/${f.name} ${W}x${H}: plot ${JSON.stringify(r.index.plot)}, the sizes give ${JSON.stringify(want)}`);
        n++;
      }
    }
    assert.ok(n >= 27, "plots checked: " + n);
  });

  test(`${type}: the legend at each written place at full, one row with "+n" at compact, absent at minimal`, () => {
    for (const place of PLACES) {
      const r = draw(R, type, "legend-" + place, 960, 540), p = r.index.plot, b = bounds(legendPrims(r));
      assert.ok(legendGroups(r).length >= 4, `${place}: the legend's entries`);
      const where = { top: b[3] <= p.y + TOL, bottom: b[1] >= p.y + p.h - TOL, left: b[2] <= p.x + TOL, right: b[0] >= p.x + p.w - TOL };
      assert.ok(where[place], `legend-${place}: the legend ${b} is not ${place} of the plot ${JSON.stringify(p)}`);
    }
    const full = draw(R, type, "eight-long-names", 960, 540);
    const rowsAt = (r) => new Set(r.primitives.filter((q) => q.k === "rect" && q.role === "legend").map((q) => q.y));
    assert.ok(rowsAt(full).size >= 2, "eight long names wrap at full: rows " + [...rowsAt(full)]);
    const compact = draw(R, type, "eight-long-names", 300, 180);
    assert.equal(rowsAt(compact).size, 1, "one row at compact");
    const more = compact.primitives.filter((q) => q.k === "text" && q.role === "legend" && q.text.startsWith(R.tables.look("morePrefix")));
    assert.equal(more.length, 1, 'a "+n" at compact');
    assert.equal(legendGroups(compact).length + Number(more[0].text.slice(R.tables.look("morePrefix").length)), 8, "the drawn entries and n are the eight series");
    for (const name of ["eight-long-names", ...PLACES.map((p) => "legend-" + p)]) assert.equal(legendGroups(draw(R, type, name, 120, 40)).length, 0, `${name}: no legend at minimal`);
  });

  test(`${type}: no legend under legend: null nor with one series, at any level`, () => {
    for (const name of ["legend-null", "one-series"]) {
      for (const [, W, H] of LEVELS) {
        const r = draw(R, type, name, W, H);
        assert.equal(legendPrims(r).length + legendGroups(r).length, 0, `${name} ${W}x${H}`);
      }
    }
  });

  test(`${type}: the empty fixture is the no-data state`, () => {
    for (const [, W, H] of LEVELS) assert.equal(draw(R, type, "empty", W, H).state, "no-data");
  });
}

/** Two-colour issues of one type in engine E: exactly one --chart-plot rectangle over the plot at full and compact, none at minimal; no line, tick or axis at any level. */
function twoColourIssues(E, type) {
  const bad = [];
  for (const [lvl, W, H] of LEVELS) {
    const r = draw(E, type, "two-colours", W, H), rects = plotRects(r);
    const canvas = r.primitives.find((p) => p.fill === "--chart-canvas");
    if (canvas.color === PLOT_CSS) bad.push("the canvas took the plot's colour");
    if (lvl === "minimal") {
      if (rects.length !== 0) bad.push(`${lvl}: ${rects.length} plot rectangles`);
    } else if (rects.length !== 1) bad.push(`${lvl}: ${rects.length} plot rectangles (expected 1)`);
    else {
      const q = rects[0], p = r.index.plot;
      if (q.color !== PLOT_CSS || q.role !== "grid") bad.push(`${lvl}: the plot rectangle is ${q.role} ${q.color}`);
      if (!samePlot({ x: q.x, y: q.y, w: q.w, h: q.h }, p)) bad.push(`${lvl}: the plot rectangle is not over the plot`);
    }
    for (const p of r.primitives) if (p.k === "line" || p.role === "axis" || p.role === "tick") bad.push(`${lvl}: a ${p.k} of role ${p.role}`);
  }
  return bad;
}

test("D3: with canvas and plot in two colours, exactly one --chart-plot rectangle over the plot at full and compact, none at minimal, and no line, tick or axis", () => {
  for (const type of ["test_plain", "test_polar"]) assert.deepEqual(twoColourIssues(R, type), [], type);
});

test("D3: polar hands the template the plot, its centre and half its smaller side; none hands the plot", () => {
  const probe = (type, anchor) =>
    engineWith(NAMES, (s) => {
      const k = testTypeKey(type), before = s[k];
      assert.equal(before.split(anchor).length, 2, `the probe's anchor occurs once in ${type}`);
      s[k] = before.replace(anchor, "R.probeHand = hand;\n    " + anchor);
    });
  const P = probe("test_polar", "var band = hand.radius");
  for (const [, W, H] of [...LEVELS, ["full", 480, 360], ["full", 360, 240], ["compact", 200, 140]]) {
    for (const name of ["legend-left", "legend-top", "title-subtitle", "one-series"]) {
      const fx = fixture("test_polar", name), r = P.render(typeInputOf(fx, W, H)), hand = P.probeHand, p = expectedPlot(R, fx, W, H);
      const r2 = (v) => Math.round(v * 100) / 100;
      assert.deepEqual(Object.keys(hand), ["plot", "centre", "radius", "visible", "level"]);
      assert.ok(samePlot(hand.plot, p), `${name} ${W}x${H}: plot`);
      assert.ok(near(hand.centre.x, r2(p.x + p.w / 2)) && near(hand.centre.y, r2(p.y + p.h / 2)), `${name} ${W}x${H}: centre ${JSON.stringify(hand.centre)}`);
      assert.ok(near(hand.radius, r2(Math.min(p.w, p.h) / 2)), `${name} ${W}x${H}: radius ${hand.radius}`);
      assert.equal(hand.level, r.level);
    }
  }
  const N = probe("test_plain", "var plot = hand.plot");
  N.render(typeInputOf(fixture("test_plain", "title-subtitle"), 960, 540));
  assert.deepEqual(Object.keys(N.probeHand), ["plot", "visible", "level"]);
});

// --------------------------------------------------------------------------------------- test_quiet: the booleans

/** test_quiet's issues in engine E: a title text, a legend group or a hidden series in its rendered or no-data state; fewer than its three series drawn. */
function quietIssues(E) {
  const bad = [];
  for (const name of ["titled-three-series", "titled-no-rows"]) {
    for (const [, W, H] of [...LEVELS, ["full", 480, 360]]) {
      const r = draw(E, "test_quiet", name, W, H), where = `${name} ${W}x${H} (${r.state})`;
      if (r.primitives.some((p) => p.role === "title")) bad.push(where + ": a title text");
      if (r.primitives.some((p) => p.role === "legend")) bad.push(where + ": a legend");
      if (r.summary.series.some((s) => s.hidden)) bad.push(where + ": a hidden series");
      if (name === "titled-three-series") {
        const series = new Set(r.primitives.filter((p) => p.role === "mark").map((p) => p.series));
        if (series.size !== 3) bad.push(where + ": " + series.size + " series drawn");
      } else if (r.state !== "no-data") bad.push(where + ": not the no-data state");
    }
  }
  return bad;
}

test("D2/D6: test_quiet draws no title, no legend and no hidden series, rendered or empty, and draws all three series though one is switched off", () => {
  assert.deepEqual(fixture("test_quiet", "titled-three-series").hidden, ["string:South"]);
  assert.ok(fixture("test_quiet", "titled-three-series").document.config.title.subtitle, "the document has a title and a subtitle");
  assert.deepEqual(quietIssues(R), []);
});

// ---------------------------------------------------------------------------------- the shared placement (D2, G3)

/** The shared placement's issues in engine E with a legend object of this test's own: its two boxes in the output, the box left below them. */
function sharedPlacementIssues(E) {
  const h = E.chrome.helpers(E.theme.resolve("light"), E.measure.measurer(null)), gap = h.em("legendGap");
  const lg = {
    place: "top",
    layout: (hh, boxW, boxH) => ({ w: 40, h: 10, drawn: { own: true }, empty: false, boxW, boxH }),
    prims: (hh, lay, ox, oy, out) => {
      out.push(E.prim.rect({ role: "legend", x: ox, y: oy, w: 15, h: 10 }));
      out.push(E.prim.rect({ role: "legend", x: ox + 25, y: oy, w: 15, h: 10 }));
      return out;
    },
  };
  const out = [], bad = [];
  let left;
  try {
    left = E.frames.legend(h, { left: 6, right: 206, top: 20, bottom: 120 }, lg, (place, boxH) => boxH - 1, out);
  } catch (err) {
    return ["the placement threw: " + err.message];
  }
  const boxes = out.map((p) => [p.x, p.y, p.w, p.h].join(","));
  if (boxes.join(" ") !== "6,20,15,10 31,20,15,10") bad.push("the legend's own boxes are not in the primitives: " + boxes.join(" "));
  if (left.top !== 20 + 10 + gap || left.left !== 6 || left.bottom !== 120) bad.push("the box left: " + JSON.stringify(left));
  if (lg.drawn.own !== true) bad.push("lg.drawn is not the layout's");
  return bad;
}

test("D2: the shared placement places a legend object of the test's own through lg.layout and lg.prims", () => {
  assert.deepEqual(sharedPlacementIssues(R), []);
});

// ------------------------------------------------------------------------------------------------ G2, G3 falsified

/** A copy of the engine with one file's anchor replaced; the anchor must occur exactly once. */
function planted(file, anchor, by) {
  return engineWith(NAMES, (s) => {
    assert.equal(s[file].split(anchor).length, 2, `the plant's anchor occurs once in ${file}`);
    s[file] = s[file].replace(anchor, by);
  });
}

const FRAME_LINE = 'var frame = R.frames.get(reg.axes), e = h.em("edge");';

test("G2: with the old throw restored, a frame without axes is refused, naming axes", () => {
  const E = planted("20-render.js", FRAME_LINE, FRAME_LINE + '\n    if (reg.axes !== "cartesian") throw new Error("render: axes " + reg.axes + " are not drawn by this engine version");');
  assert.throws(() => draw(E, "test_plain", "title-subtitle", 960, 540), /axes none are not drawn/);
  assert.throws(() => draw(E, "test_polar", "title-subtitle", 960, 540), /axes polar are not drawn/);
});

test("G2: a render that calls C.titles whatever the frame says fails test_quiet's check on a title text in the no-data state", () => {
  const E = planted("20-render.js", "var t = frame.titles ? C.titles(h, doc, lvl, W, e) : { texts: [], bottom: 0 };", "var t = C.titles(h, doc, lvl, W, e);");
  const bad = quietIssues(E);
  assert.ok(bad.some((b) => /titled-no-rows .*\(no-data\): a title text/.test(b)), "the plant passes: " + bad.join("; "));
});

test("G2: the plot rectangle left out of the none frame fails the two-colour check", () => {
  const E = planted("18-frames.js", 'if (lvl !== "minimal") under.push(', 'if (false) under.push(');
  assert.ok(twoColourIssues(E, "test_plain").some((b) => /full: 0 plot rectangles/.test(b)));
});

test("G3: a placement that calls C.legendPrims instead of lg.prims fails the shared placement's check", () => {
  const E = engineWith(NAMES, (s) => {
    const n = s["18-frames.js"].split("lg.prims(h, lay, box.").length - 1;
    assert.equal(n, 4, "the four calls of the placement");
    s["18-frames.js"] = s["18-frames.js"].replaceAll("lg.prims(h, lay, box.", "R.chrome.legendPrims(h, lay, box.").replaceAll(", out);\n      b.", ", lg.hiddenOf, lg.colourOf, out, lg.key);\n      b.");
  });
  const bad = sharedPlacementIssues(E);
  assert.ok(bad.length > 0 && /placement threw|own boxes/.test(bad[0]), "the plant passes: " + bad.join("; "));
});
