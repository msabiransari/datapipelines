// 076 §B — the app shell under hx-boost (static/js/shell.js).
//
// shell.js owns three client-side halves of boosted navigation: the swap
// policy (applyBoostSwap retargets boosted requests at #app-main on
// htmx:beforeSwap, so hx-target/hx-select never have to be inherited onto
// <main> where they would hijack partial swaps), the progress bar, and the
// active-section state mirroring the server's currentPath rule. Same harness
// as the house JS tests: node --test, no packages, hand-rolled DOM doubles.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const shellPath = path.resolve(here, "../../main/resources/static/js/shell.js");

function loadShell() {
  delete require.cache[require.resolve(shellPath)];
  return require(shellPath);
}

test("the active-section rule mirrors the server: Dashboard exact, others by prefix", () => {
  const shell = loadShell();
  assert.equal(shell.isActiveSection("/dashboard", "/dashboard"), true);
  assert.equal(shell.isActiveSection("/dashboard", "/dashboard/x"), false);
  assert.equal(shell.isActiveSection("/pipelines", "/pipelines"), true);
  assert.equal(shell.isActiveSection("/pipelines", "/pipelines/abc-123"), true);
  // /admin is the section prefix even though the link's href is /admin/users.
  assert.equal(shell.isActiveSection("/admin", "/admin/users"), true);
  assert.equal(shell.isActiveSection("/pipelines", "/templates"), false);
  assert.equal(shell.isActiveSection(null, "/pipelines"), false);
  assert.equal(shell.isActiveSection("/pipelines", null), false);
});

test("syncNavActive toggles .active from data-nav-section, not from href", () => {
  const shell = loadShell();
  const mkLink = (section) => {
    const classes = new Set(["app-nav-link"]);
    return {
      getAttribute: (k) => (k === "data-nav-section" ? section : null),
      classList: { toggle: (c, on) => (on ? classes.add(c) : classes.delete(c)) },
      has: (c) => classes.has(c),
    };
  };
  const links = [mkLink("/dashboard"), mkLink("/executions"), mkLink("/admin")];
  const doc = { querySelectorAll: (sel) => (sel === ".app-nav-link[data-nav-section]" ? links : []) };

  shell.syncNavActive(doc, "/executions?offset=20".split("?")[0]);
  assert.deepEqual(links.map((l) => l.has("active")), [false, true, false]);

  shell.syncNavActive(doc, "/admin/users");
  assert.deepEqual(links.map((l) => l.has("active")), [false, false, true]);
});

test("applyBoostSwap retargets a boosted swap at #app-main with select and swap spec", () => {
  const shell = loadShell();
  const main = { id: "app-main" };
  const detail = { boosted: true, shouldSwap: true, isError: false };

  assert.equal(shell.applyBoostSwap(detail, main), true);
  assert.equal(detail.target, main);
  assert.equal(detail.selectOverride, "#app-main");
  assert.equal(detail.swapOverride, "outerHTML show:window:top");
});

test("applyBoostSwap leaves partial (non-boosted) swaps alone — the inheritance it replaces", () => {
  const shell = loadShell();
  const main = { id: "app-main" };
  const detail = { boosted: false, shouldSwap: true, isError: false, target: { id: "tpl-results" } };

  assert.equal(shell.applyBoostSwap(detail, main), false);
  assert.equal(detail.target.id, "tpl-results", "partial target untouched");
  assert.equal(detail.selectOverride, undefined);
  assert.equal(detail.swapOverride, undefined);
});

test("applyBoostSwap never touches error responses or non-swapping ones", () => {
  const shell = loadShell();
  const main = { id: "app-main" };

  const err = { boosted: true, shouldSwap: false, isError: true };
  assert.equal(shell.applyBoostSwap(err, main), false);
  assert.equal(err.target, undefined);

  const noSwap = { boosted: true, shouldSwap: false, isError: false };
  assert.equal(shell.applyBoostSwap(noSwap, main), false);

  assert.equal(shell.applyBoostSwap({ boosted: true, shouldSwap: true }, null), false,
    "no #app-main on the page — no policy");
});

test("the progress bar shows and hides by class, tolerating its absence", () => {
  const shell = loadShell();
  const classes = new Set();
  const bar = { classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) } };
  const doc = { getElementById: (id) => (id === "app-progress" ? bar : null) };

  shell.showProgress(doc);
  assert.equal(classes.has("active"), true);
  shell.hideProgress(doc);
  assert.equal(classes.has("active"), false);

  const bare = { getElementById: () => null };
  assert.doesNotThrow(() => shell.showProgress(bare));
  assert.doesNotThrow(() => shell.hideProgress(bare));
});

test("init wires the shell once — repeated loads never stack listeners", () => {
  const added = [];
  globalThis.window = { location: { pathname: "/pipelines" } };
  globalThis.document = {
    readyState: "complete",
    body: { addEventListener: (t) => added.push(t) },
    getElementById: () => null,
    querySelectorAll: () => [],
  };
  const shell = loadShell(); // init() runs at load (readyState complete)
  shell.init();
  shell.init();
  const beforeSwapCount = added.filter((t) => t === "htmx:beforeSwap").length;
  assert.equal(beforeSwapCount, 1, "one boost-policy listener across repeated init");
  delete globalThis.window;
  delete globalThis.document;
});

test("a boosted cross-origin click falls back to plain navigation (selfRequestsOnly bridge)", () => {
  const listeners = {};
  const assigned = [];
  globalThis.window = {
    location: {
      pathname: "/docs",
      assign: (href) => assigned.push(href),
    },
  };
  globalThis.document = {
    readyState: "complete",
    body: { addEventListener: (t, fn) => { listeners[t] = fn; } },
    getElementById: () => null,
    querySelectorAll: () => [],
  };
  const shell = loadShell(); // init() runs at load

  const external = { getAttribute: (k) => (k === "href" ? "https://github.com/acme/docs" : null) };
  listeners["htmx:invalidPath"]({ detail: { elt: external } });
  assert.deepEqual(assigned, ["https://github.com/acme/docs"]);

  // A same-origin invalid path (misconfiguration, not a link) is NOT navigated.
  const internal = { getAttribute: (k) => (k === "href" ? "/pipelines" : null) };
  listeners["htmx:invalidPath"]({ detail: { elt: internal } });
  assert.equal(assigned.length, 1);
  delete globalThis.window;
  delete globalThis.document;
});
