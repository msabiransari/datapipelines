// D9 and D7 (brief 985; #981): the shared label row over the `_layout` fixtures — inputs of the x label row that are
// not chart documents (the frame, the plot, the lowest value tick's label and its box, the x labels with their centres),
// recorded by the lane's D9 harness. Counted on their own, never with the chart fixtures. For each: the texts the label
// row returns meet neither each other nor the lowest tick's label, the rule had work to do (the first label met the
// corner before it), and the lowest tick's number is never what gives way (the row takes no part of it).
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { engine, FIXTURES } from "./engine.mjs";
import { textBox, meet, width } from "./geometry.mjs";

const R = engine();
const DIR = join(FIXTURES, "_layout");
const LAYOUT = readdirSync(DIR).filter((f) => f.endsWith(".json")).sort().map((f) => ({ name: f.slice(0, -5), fx: JSON.parse(readFileSync(join(DIR, f), "utf8")) }));

const box = (c) => [[c.x0, c.y0], [c.x1, c.y0], [c.x1, c.y1], [c.x0, c.y1]];

test("the label-row fixtures exist (floor 1, counted apart from the chart fixtures)", () => {
  assert.ok(LAYOUT.length >= 1, `label-row fixtures: ${LAYOUT.length}`);
});

for (const { name, fx } of LAYOUT) {
  test(`the label row keeps the corner clear and its labels apart: ${name}`, () => {
    const h = R.chrome.helpers(R.theme.resolve(fx.theme), R.measure.measurer(fx.measure));
    const o = fx.input, res = R.axes.labelRow(h, o), corner = box(o.corner);
    assert.ok(res.texts.length >= 2, `texts drawn: ${res.texts.length}`);
    // the rule had work to do: the first label, where the row would put it without the rule, meets the corner
    const f = o.labels[0], fw = width(f.text, h.text), top = o.plot.y + o.plot.h + h.em("tickGap");
    assert.ok(meet(box({ x0: f.x - fw / 2, y0: top, x1: f.x + fw / 2, y1: top + h.boxH(h.text) }), corner), "the fixture no longer exercises the corner");
    assert.notEqual(res.corner, "clear");
    const polys = res.texts.map(textBox), met = [];
    polys.forEach((p, i) => {
      if (meet(p, corner)) met.push(`${res.texts[i].text} meets the lowest tick ${fx.lowestTick}`);
      for (let j = i + 1; j < polys.length; j++) if (meet(p, polys[j])) met.push(`${res.texts[i].text} meets ${res.texts[j].text}`);
    });
    assert.deepEqual(met, []);
    // the lowest tick's number is drawn whole: its box is the input's, the row never shrinks or drops it
    assert.ok(fx.lowestTick.length > 0);
    assert.ok(Math.abs(o.corner.x1 - o.corner.x0 - width(fx.lowestTick, h.text)) < 0.01, "the corner box is the lowest tick's label");
  });
}
