// #590: the same table drives ShellRenderTest and all three client path rules.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { familyForPath } from "../../main/resources/static/js/tree/sidebar-state.mjs";

const require = createRequire(import.meta.url);
const shell = require("../../main/resources/static/js/shell.js");
const rows = JSON.parse(readFileSync(new URL("../resources/shell/family-path-rule.json", import.meta.url), "utf8"));
const FLOOR = 96;
const sections = [...new Set(rows.map(row => row.section).filter(Boolean))];
const families = ["pipelines", "templates", "dashboards", "visualizations", "parameter-sets"];

// Exercise persistent-navigation's public htmx seam, without exporting or copying its regex.
// The DOM double has no page assets; every preparation fetch is fulfilled locally.
const body = new EventTarget();
const main = { querySelector: () => null, querySelectorAll: () => [] };
globalThis.window = globalThis;
globalThis.location = new URL("http://app.test/pipelines");
globalThis.document = {
  body,
  documentElement: { dataset: { dpWorkspaceId: "parity" } },
  getElementById: id => id === "app-main" ? main : null,
};
globalThis.DOMParser = class {
  parseFromString() {
    return { getElementById: id => id === "app-main" ? main : null,
      documentElement: { dataset: { dpWorkspaceId: "parity" } } };
  }
};
let fetches = 0;
globalThis.fetch = async () => {
  fetches += 1;
  return { ok: true, headers: { get: () => "text/html" }, text: async () => "fixture" };
};
await import("../../main/resources/static/js/persistent-navigation.mjs");

test("shared family path fixture has a non-vacuous coverage floor", () => {
  assert.ok(rows.length >= FLOOR, `fixture rows=${rows.length}, floor=${FLOOR}`);
  assert.ok(rows.some(row => row.section === null));
  assert.ok(rows.some(row => row.family === null));
  assert.deepEqual([...new Set(rows.map(row => row.family).filter(Boolean))].sort(), [...families].sort());
  assert.equal(new Set(rows.map(row => row.path)).size, rows.length);
  for (const row of rows) {
    assert.deepEqual(Object.keys(row).sort(), ["family", "path", "section", "ticketed"]);
    assert.equal(typeof row.path, "string");
    assert.equal(typeof row.ticketed, "boolean");
  }
});

test("shell active section agrees with the shared family path fixture", () => {
  for (const row of rows) {
    assert.deepEqual(sections.filter(section => shell.isActiveSection(section, row.path)),
      row.section === null ? [] : [row.section], `shell path=${row.path}`);
  }
});

test("sidebar family agrees with the shared family path fixture", () => {
  for (const row of rows) {
    assert.equal(familyForPath(row.path, families), row.family, `sidebar path=${row.path}`);
  }
});

test("persistent navigation tickets agree with the shared family path fixture", async () => {
  for (const row of rows) {
    let issued = 0;
    fetches = 0;
    const event = new CustomEvent("htmx:confirm", { cancelable: true, detail: {
      verb: "get", path: row.path, elt: { tagName: "A", closest: () => null },
      issueRequest() { issued += 1; },
    } });
    body.dispatchEvent(event);
    assert.equal(event.defaultPrevented, row.ticketed, `ticketed path=${row.path}`);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(fetches, Number(row.ticketed), `preparation fetch path=${row.path}`);
    assert.equal(issued, Number(row.ticketed), `issued request path=${row.path}`);
  }
});
