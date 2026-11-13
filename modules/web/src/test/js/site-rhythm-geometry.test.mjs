// Exercise the exact browser spacing sweep with painted box fragments; no browser is started.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import vm from "node:vm";

const here = path.dirname(fileURLToPath(import.meta.url));
const source = readFileSync(path.resolve(here,
  "../../../../../tests/browser-tests/src/test/kotlin/co/datapipelines/browser/SiteRhythmBrowserTest.kt"), "utf8");
const sweep = source.match(/const val TIGHT_PAIRS_JS\s*=\s*"""([\s\S]*?)"""/)[1];
const rect = (left, top, right, bottom) => ({ left, top, right, bottom, width: right - left, height: bottom - top });
function block(fragments) {
  return { tagName: "P", className: "", textContent: "sample", children: [], style: { display: "block", position: "static" },
    getClientRects: () => fragments,
    getBoundingClientRect: () => rect(Math.min(...fragments.map(r => r.left)), Math.min(...fragments.map(r => r.top)),
      Math.max(...fragments.map(r => r.right)), Math.max(...fragments.map(r => r.bottom))) };
}
function measure(...children) {
  const parent = { ...block([rect(0, 0, 540, 300)]), tagName: "DIV", className: "prose", children };
  const main = { querySelectorAll: () => [parent, ...children] };
  return vm.runInNewContext(`(${sweep})()`, { document: { querySelector: () => main }, getComputedStyle: el => el.style });
}

test("separate columns have a horizontal gap despite overlapping vertical ranges", () => {
  const result = measure(block([rect(0, 0, 250, 200)]), block([rect(290, 0, 540, 200)]));
  assert.equal(result.tight.length, 0);
  assert.ok(result.pairs > 0);
});
test("a split paragraph uses its painted fragments instead of its union bounding box", () => {
  assert.equal(measure(block([rect(0, 0, 250, 200), rect(290, 0, 540, 40)]),
    block([rect(290, 56, 540, 120)])).tight.length, 0);
});
for (const gap of [0, 4, 7, 8, 16]) {
  test(`same-column gap ${gap}px retains the 8px floor`, () => {
    assert.equal(measure(block([rect(0, 0, 250, 40)]), block([rect(0, 40 + gap, 250, 90 + gap)])).tight.length,
      gap < 8 ? 1 : 0);
  });
}
test("actual overlap remains an offender", () => {
  assert.equal(measure(block([rect(0, 0, 250, 60)]), block([rect(0, 50, 250, 100)])).tight.length, 1);
});
test("two columns separated by less than the floor remain an offender", () => {
  assert.equal(measure(block([rect(0, 0, 250, 200)]), block([rect(254, 0, 504, 200)])).tight.length, 1);
});
