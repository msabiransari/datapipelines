import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import vm from "node:vm";

/* #788 — rail.js is the pre-paint reader: it re-derives the browser's width range from
   app.css's `:root` (`--app-browser-min` / `--app-browser-max`) at run time, so this suite
   parses the SAME block from the stylesheet and expresses every case against it. A plant
   that moves a bound re-derives the file; re-hardcoding a literal into rail.js goes red. */
const here = path.dirname(fileURLToPath(import.meta.url));
const source = readFileSync(path.join(here, "../../main/resources/static/js/rail.js"), "utf8");
const css = readFileSync(path.join(here, "../../main/resources/static/css/app.css"), "utf8");
// The shell's `:root` block is the one that declares the range (app.css has several, and
// the width bands re-declare the default inside media rules).
const rootBlock = [...css.matchAll(/:root\s*\{([^}]*)\}/g)].map(m => m[1])
  .find(block => block.includes("--app-browser-min:"));
assert.ok(rootBlock, "app.css must declare --app-browser-min in a :root block — it is the range's one source");
const declared = name => {
  const hit = rootBlock.match(new RegExp(`${name}:\\s*(\\d+(?:\\.\\d+)?)px`));
  assert.ok(hit, `app.css's :root must declare ${name} in px — it is the range's one source`);
  return Number(hit[1]);
};
const MIN = declared("--app-browser-min");
const MAX = declared("--app-browser-max");
const BOUNDS = { "--app-browser-min": `${MIN}px`, "--app-browser-max": `${MAX}px` };

/* A minimal page: <html> with the workspace attribute, a recording localStorage and
   computed styles whose tokens the caller controls. Runs rail.js in a vm context — the
   same parser-blocking classic script the browser runs, no module loader involved. */
function runRail({ stored = {}, tokens = BOUNDS } = {}) {
  const applied = [];
  const classes = [];
  const removed = [];
  const store = { ...stored };
  const document = {
    documentElement: {
      getAttribute: name => (name === "data-dp-workspace" ? "acme" : null),
      classList: { add: cls => classes.push(cls) },
      style: { setProperty: (name, value) => applied.push([name, value]) },
    },
  };
  const localStorage = {
    getItem: key => (key in store ? store[key] : null),
    setItem: (key, value) => { store[key] = String(value); },
    removeItem: key => { delete store[key]; removed.push(key); },
  };
  const context = {
    document,
    localStorage,
    getComputedStyle: () => ({ getPropertyValue: name => tokens[name] ?? "" }),
  };
  vm.createContext(context);
  vm.runInContext(source, context, { filename: "rail.js" });
  return { applied, classes, removed, store };
}

test("a stored width within the range is stamped on <html> before the first paint", () => {
  const page = runRail({ stored: { "dp-browser-width:acme": String(MIN) } });
  assert.deepEqual(page.applied, [["--app-browser-preferred", `${MIN}px`]]);
  // The retired rail-width key still goes on sight; the width key survives.
  assert.deepEqual(page.removed, ["dp-rail-width:acme"]);
  assert.ok("dp-browser-width:acme" in page.store);
});

test("a stored width one under the floor is removed before the first paint, not applied", () => {
  const page = runRail({ stored: { "dp-browser-width:acme": String(MIN - 1) } });
  assert.deepEqual(page.applied, []);
  assert.deepEqual(page.removed, ["dp-rail-width:acme", "dp-browser-width:acme"]);
});

test("a stored width one over the ceiling is removed before the first paint, not applied", () => {
  const page = runRail({ stored: { "dp-browser-width:acme": String(MAX + 1) } });
  assert.deepEqual(page.applied, []);
  assert.deepEqual(page.removed, ["dp-rail-width:acme", "dp-browser-width:acme"]);
});

test("a non-numeric stored value is removed, not applied", () => {
  const page = runRail({ stored: { "dp-browser-width:acme": "300em" } });
  assert.deepEqual(page.applied, []);
  assert.ok(page.removed.includes("dp-browser-width:acme"));
});

test("bounds the stylesheet does not resolve leave the stored key untouched", () => {
  // A missing stylesheet must not erase a preference it could not judge (#788).
  const page = runRail({ stored: { "dp-browser-width:acme": String(MIN - 1) }, tokens: {} });
  assert.deepEqual(page.applied, []);
  assert.deepEqual(page.removed, ["dp-rail-width:acme"], "only the retired key goes");
  assert.equal(page.store["dp-browser-width:acme"], String(MIN - 1), "the width key survives");
});
