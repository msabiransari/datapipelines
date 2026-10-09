// #465 — static/js/page-assets.mjs, the persistent navigation's script registry: the one
// script-URL gate, one execution per source per document (including sources another loader
// already ran, recognised by the global they set), and a failed mount that is remembered per
// catalog instead of retried — and re-announced — on every settle. A minimal DOM stand-in:
// the module touches only document.createElement/head.append and main/catalog lookups.

import test from "node:test";
import assert from "node:assert/strict";

const appended = [];
const outcomes = new Map(); // source -> { fail?: true, global?: name }
let executions = 0;
globalThis.window = globalThis;
globalThis.document = {
  createElement(tag) {
    const attributes = new Map();
    return {
      tagName: tag.toUpperCase(), removed: false,
      setAttribute(name, value) { attributes.set(name, String(value)); },
      remove() { this.removed = true; },
    };
  },
  getElementById() { return null; },
  head: {
    append(element) {
      appended.push(element);
      const outcome = outcomes.get(element.src) || {};
      queueMicrotask(() => {
        if (outcome.fail) { element.onerror?.(); return; }
        executions += 1;
        if (outcome.global) window[outcome.global] = {};
        element.onload?.();
      });
    },
  },
};

const assets = await import("../../main/resources/static/js/page-assets.mjs");

function script(src, mount = false) {
  return { getAttribute: name => (name === "src" ? src : null), hasAttribute: name => mount && name === "data-page-mount" };
}
/** A main holding one inert catalog; `prepend` records notices like the DOM would. */
function mainWith(sources) {
  const catalog = { content: { querySelectorAll: selector => (selector === "script[src]" ? sources.map(source => script(source)) : []) } };
  const main = {
    isConnected: true, notices: [],
    querySelector: selector => (selector === "template[data-chart-assets]" ? catalog : null),
    prepend(element) { this.notices.push(element); },
  };
  return { main, catalog };
}
const settle = () => new Promise(resolve => setTimeout(resolve, 0));

test("the script-URL gate admits first-party js/vendor paths only", () => {
  assert.equal(assets.assetPath("/js/lifecycle-dialog.js"), "/js/lifecycle-dialog.js");
  assert.equal(assets.assetPath("/vendor/plotly/plotly-3d.min.js"), "/vendor/plotly/plotly-3d.min.js");
  for (const hostile of ["https://evil.example/x.js", "//evil.example/x.js", "/js/../admin.js", "/js/./x.js", "/js/x.js?y=1",
    "javascript:alert(1)", "/static/x.js", "/js/x.css", "", null, 7]) {
    assert.throws(() => assets.assetPath(hostile), /Invalid page dependency/, String(hostile));
  }
});

test("a source executes once per document however often it is requested", async () => {
  const before = executions;
  await Promise.all([assets.loadScript("/js/once.js"), assets.loadScript("/js/once.js")]);
  await assets.loadScript("/js/once.js");
  assert.equal(executions - before, 1);
});

test("lifecycle-dialog.js is not executed again when another loader already set its global", async () => {
  window.lifecycleDialog = { planted: true };
  const before = appended.length;
  await assets.loadScript("/js/lifecycle-dialog.js");
  assert.equal(appended.length, before, "no second <script> for a source whose global exists");
  delete window.lifecycleDialog;
});

test("a failed load rejects, removes its tag and is retried by a later request", async () => {
  outcomes.set("/js/flaky.js", { fail: true });
  await assert.rejects(assets.loadScript("/js/flaky.js"), /Could not load page dependencies/);
  assert.equal(appended.at(-1).removed, true);
  outcomes.set("/js/flaky.js", {});
  await assets.loadScript("/js/flaky.js");
  assert.equal(appended.filter(element => element.src === "/js/flaky.js").length, 2);
});

test("a failed mount shows one notice and is not retried by later settles of the same catalog", async () => {
  outcomes.set("/js/broken-chart.js", { fail: true });
  const { main } = mainWith(["/js/broken-chart.js"]);
  await assets.mountCharts(main); await settle();
  for (let i = 0; i < 4; i++) { await assets.mountCharts(main); await settle(); }
  assert.equal(main.notices.length, 1);
  assert.equal(main.notices[0].className, "ds-error");
  assert.equal(main.notices[0].textContent, "Could not load page dependencies");
  assert.equal(appended.filter(element => element.src === "/js/broken-chart.js").length, 1, "the failed source is not refetched per settle");
});

test("a new catalog (navigation or history restore) gets a fresh mount attempt", async () => {
  outcomes.set("/js/recovering.js", { fail: true });
  const first = mainWith(["/js/recovering.js"]);
  await assets.mountCharts(first.main); await settle();
  outcomes.set("/js/recovering.js", {});
  const second = mainWith(["/js/recovering.js"]);
  await assets.mountCharts(second.main); await settle();
  assert.equal(first.main.notices.length, 1);
  assert.equal(second.main.notices.length, 0);
});
