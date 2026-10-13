import { test } from "node:test";
import assert from "node:assert/strict";
import { nextOpen, restoreOpen } from "../../main/resources/static/js/tree/sidebar-state.mjs";

const order = ["pipelines", "templates", "dashboards", "visualizations", "parameter-sets"];
for (const open of [null, ...order]) {
  for (const clicked of order) {
    test(`accordion ${open ?? "closed"} → ${clicked}`, () => {
      assert.equal(nextOpen(open, clicked), open === clicked ? null : clicked);
    });
  }
}

test("no remembered family opens nothing", () => {
  assert.equal(restoreOpen({ stored: null, legacyFlags: {}, current: "pipelines", order }), null);
});

test("each sole legacy open family restores", () => {
  for (const family of order) {
    assert.equal(restoreOpen({ stored: null, legacyFlags: { [family]: "1" }, current: null, order }), family);
  }
});

test("several legacy flags prefer the flagged current page", () => {
  assert.equal(restoreOpen({ stored: null, legacyFlags: { pipelines: "1", templates: "1" }, current: "templates", order }), "templates");
});

test("several legacy flags without the current page use menu order", () => {
  assert.equal(restoreOpen({ stored: null, legacyFlags: { templates: "1", pipelines: "1" }, current: "dashboards", order }), "pipelines");
});

test("every allowlisted new preference wins without reading legacy flags", () => {
  for (const stored of order) {
    assert.equal(restoreOpen({ stored, get legacyFlags() { throw new Error("must not read"); }, current: "pipelines", order }), stored);
  }
});

test("unknown or empty stored families open nothing even with legacy flags", () => {
  for (const stored of ["unknown", "", "<img src=x>", "__proto__"]) {
    assert.equal(restoreOpen({ stored, legacyFlags: { pipelines: "1" }, current: "pipelines", order }), null);
  }
});

test("unknown legacy and current families are ignored", () => {
  assert.equal(restoreOpen({ stored: null, legacyFlags: { unknown: "1", pipelines: "0" }, current: "unknown", order }), null);
});

test("legacy values must be the exact saved-open string", () => {
  for (const value of [true, 1, "true", "0", null]) {
    assert.equal(restoreOpen({ stored: null, legacyFlags: { pipelines: value }, current: "pipelines", order }), null);
  }
});

test("throwing storage reads leave everything closed", () => {
  assert.equal(restoreOpen({ get stored() { throw new Error("storage denied"); }, order }), null);
  assert.equal(restoreOpen({ stored: null, get legacyFlags() { throw new Error("storage denied"); }, order }), null);
});

test("the live menu allowlist and order govern migration", () => {
  const legacyFlags = { pipelines: "1", templates: "1" };
  assert.equal(restoreOpen({ stored: "pipelines", legacyFlags, current: "pipelines", order: ["templates"] }), null);
  assert.equal(restoreOpen({ stored: null, legacyFlags, current: "dashboards", order: ["templates", "pipelines"] }), "templates");
});
