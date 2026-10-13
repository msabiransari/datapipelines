// #465/#494 — static/js/page-assets.mjs, the ONE page-script loader every family page's
// `template[data-page-assets]` catalogs go through: the one script-URL gate, one execution per
// source per document (including sources whose global another script already set), catalogs
// executed in order with each `data-page-mount` called once per catalog node, "on-mount"
// catalogs fetched but not executed before the swap, and a failed mount remembered per catalog
// instead of retried — and re-announced — on every settle. A minimal DOM stand-in: the module
// touches only document.createElement/head.append, fetch, and main/catalog lookups.

import test from "node:test";
import assert from "node:assert/strict";

const appended = [];
const outcomes = new Map(); // source -> { fail?: true, global?: name, run?: () => void }
const executed = []; // every simulated execution, in order
const fetched = [];
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
        executed.push(element.src);
        if (outcome.global) window[outcome.global] = {};
        outcome.run?.();
        element.onload?.();
      });
    },
  },
};
globalThis.fetch = async source => { fetched.push(source); return { ok: true, arrayBuffer: async () => new ArrayBuffer(0) }; };

const assets = await import("../../main/resources/static/js/page-assets.mjs");

const MOUNT_ATTRIBUTE = "data-page-mount";
function script(src, mount = false) {
  return { getAttribute: name => (name === "src" ? src : null), hasAttribute: name => mount && name === MOUNT_ATTRIBUTE };
}
/** One inert catalog: entries are a source, or [source, true] for a mount. */
function catalogOf(entries, mode = "") {
  const scripts = entries.map(entry => (Array.isArray(entry) ? script(entry[0], entry[1]) : script(entry)));
  return {
    getAttribute: name => (name === "data-page-assets" ? mode : null),
    content: { querySelectorAll: selector => (selector === "script[src]" ? scripts : []) },
  };
}
/** A main holding the given catalogs; `prepend` records notices like the DOM would. */
function mainWith(...catalogs) {
  const main = {
    isConnected: true, notices: [], catalogs, scripts: [],
    querySelectorAll: selector => {
      if (selector === "template[data-page-assets]" || selector === "template") return main.catalogs;
      return selector === "script[src]" ? main.scripts : [];
    },
    prepend(element) { this.notices.push(element); },
  };
  return main;
}
/** The legacy single-catalog shape these cases started with. */
function singleCatalogMain(sources) {
  return mainWith(catalogOf(sources));
}
const settle = () => new Promise(resolve => setTimeout(resolve, 0));
const count = (list, source) => list.filter(entry => entry === source).length;

test("the script-URL gate admits first-party js/vendor paths only", () => {
  assert.equal(assets.assetPath("/js/lifecycle-dialog.js"), "/js/lifecycle-dialog.js");
  assert.equal(assets.assetPath("/vendor/plotly/plotly-3d.min.js"), "/vendor/plotly/plotly-3d.min.js");
  for (const hostile of ["https://evil.example/x.js", "//evil.example/x.js", "/js/../admin.js", "/js/./x.js", "/js/x.js?y=1",
    "javascript:alert(1)", "/static/x.js", "/js/x.css", "", null, 7]) {
    assert.throws(() => assets.assetPath(hostile), /Invalid page dependency/, String(hostile));
  }
});

test("a source executes once per document however often it is requested", async () => {
  const before = executed.length;
  await Promise.all([assets.loadScript("/js/once.js"), assets.loadScript("/js/once.js")]);
  await assets.loadScript("/js/once.js");
  assert.equal(executed.length - before, 1);
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
  const main = singleCatalogMain(["/js/broken-chart.js"]);
  await assets.mountPage(main); await settle();
  for (let i = 0; i < 4; i++) { await assets.mountPage(main); await settle(); }
  assert.equal(main.notices.length, 1);
  assert.equal(main.notices[0].className, "ds-error");
  assert.equal(main.notices[0].textContent, "Could not load page dependencies");
  assert.equal(appended.filter(element => element.src === "/js/broken-chart.js").length, 1, "the failed source is not refetched per settle");
});

test("a new catalog (navigation or history restore) gets a fresh mount attempt", async () => {
  outcomes.set("/js/recovering.js", { fail: true });
  const first = singleCatalogMain(["/js/recovering.js"]);
  await assets.mountPage(first); await settle();
  outcomes.set("/js/recovering.js", {});
  const second = singleCatalogMain(["/js/recovering.js"]);
  await assets.mountPage(second); await settle();
  assert.equal(first.notices.length, 1);
  assert.equal(second.notices.length, 0);
});

test("the singleton gate: an Alpine that already exists is not executed again, the other vendors still are", async () => {
  // Moved from runtime-activation.test.mjs (#494): the loader, not the runtime, owns this gate now. It runs before
  // any catalog case executes Alpine, so the loaded map cannot answer for the gate.
  const planted = { planted: true };
  window.Alpine = planted;
  const before = appended.length;
  await assets.loadScript("/vendor/alpinejs/alpine.min.js");
  assert.equal(appended.length, before, "no second Alpine is loaded");
  assert.equal(window.Alpine, planted);
  await assets.loadScript("/vendor/other/vendor.js");
  assert.equal(appended.at(-1).src, "/vendor/other/vendor.js", "the other vendors still load");
  delete window.Alpine;
});

// #494 — the editor's and the parameter workspace's catalogs, which their runtimes used to load themselves.

const EDITOR = [
  "/vendor/dagre/dagre.min.js",
  "/vendor/cytoscape/cytoscape.min.js",
  "/vendor/cytoscape/cytoscape-dagre.js",
  "/vendor/cytoscape/cytoscape-node-html-label.js",
  "/js/workspace/tabs.js",
  "/js/pipeline-editor/init.js",
  "/vendor/alpinejs/alpine.min.js",
  ["/js/pipeline-editor/runtime.js", true],
];
const PARAMETERS = [
  "/vendor/dagre/dagre.min.js",
  "/vendor/cytoscape/cytoscape.min.js",
  "/vendor/cytoscape/cytoscape-dagre.js",
  "/js/datapipelines-dashboard.js",
  "/js/parameter-workspace/workspace.js",
  ["/js/parameter-workspace/runtime.js", true],
];
const editorMounts = [];
const parameterMounts = [];
outcomes.set("/vendor/dagre/dagre.min.js", { global: "dagre" });
outcomes.set("/vendor/cytoscape/cytoscape.min.js", { global: "cytoscape" });
outcomes.set("/js/workspace/tabs.js", { global: "WorkspaceTabs" });
outcomes.set("/js/datapipelines-dashboard.js", { global: "DatapipelinesDashboard" });
outcomes.set("/js/pipeline-editor/runtime.js", { run: () => { window.PipelineWorkspaceMount = main => editorMounts.push({ main, executed: executed.slice() }); } });
outcomes.set("/js/parameter-workspace/runtime.js", { run: () => { window.ParameterWorkspaceMount = main => parameterMounts.push({ main, executed: executed.slice() }); } });

test("the editor's on-mount catalog is fetched, never executed, while a navigation prepares", async () => {
  const main = mainWith(catalogOf(["/js/prepared-early.js"]), catalogOf(EDITOR, "on-mount"));
  const before = executed.length;
  await assets.preparePage(main);
  assert.deepEqual(executed.slice(before), ["/js/prepared-early.js"], "only the plain catalog executes before the swap");
  for (const source of EDITOR.map(entry => (Array.isArray(entry) ? entry[0] : entry))) assert.ok(fetched.includes(source), source);
  assert.equal(editorMounts.length, 0);
});

test("the editor's catalog executes in order, Alpine before the runtime, and mounts once per catalog node", async () => {
  const catalog = catalogOf(EDITOR, "on-mount");
  const main = mainWith(catalog);
  await assets.mountPage(main); await settle();
  for (let i = 0; i < 3; i++) { await assets.mountPage(main); await settle(); } // the page's own partial settles
  assert.equal(editorMounts.length, 1, "one mount per catalog node");
  assert.equal(editorMounts[0].main, main);
  const order = EDITOR.map(entry => (Array.isArray(entry) ? entry[0] : entry));
  const seen = editorMounts[0].executed.filter(source => order.includes(source));
  assert.deepEqual(seen, order, "every entry executed, in catalog order, before the mount ran");
  // A cached history restore brings a NEW catalog node: a new mount, and nothing executes again.
  const before = executed.length;
  await assets.mountPage(mainWith(catalogOf(EDITOR, "on-mount"))); await settle();
  assert.equal(editorMounts.length, 2);
  assert.equal(executed.length, before, "a restore re-mounts without re-executing a single source");
  assert.equal(main.notices.length, 0);
});

test("crossing editor -> parameter workspace executes each shared vendor and plugin once", async () => {
  await assets.mountPage(mainWith(catalogOf(PARAMETERS, "on-mount"))); await settle();
  for (const source of ["/vendor/dagre/dagre.min.js", "/vendor/cytoscape/cytoscape.min.js", "/vendor/cytoscape/cytoscape-dagre.js"]) {
    assert.equal(count(executed, source), 1, source);
  }
  assert.equal(parameterMounts.length, 1);
  const own = ["/js/datapipelines-dashboard.js", "/js/parameter-workspace/workspace.js", "/js/parameter-workspace/runtime.js"];
  assert.deepEqual(parameterMounts[0].executed.filter(source => own.includes(source)), own);
});

test("a source shared with an earlier page's catalog (tabs.js after a dashboard) is not executed again", async () => {
  const before = count(executed, "/js/workspace/tabs.js");
  const main = mainWith(catalogOf(["/js/workspace/tabs.js"]));
  await assets.mountPage(main); await settle();
  assert.equal(main.notices.length, 0);
  assert.equal(count(executed, "/js/workspace/tabs.js"), before);
});

test("a catalog entry outside the allow-list is refused: nothing executes, the mount never runs, one notice", async () => {
  for (const hostile of ["https://evil.example/alpine.min.js", "/static/runtime.js", "/js/../admin.js"]) {
    const before = appended.length;
    const mountsBefore = editorMounts.length;
    const main = mainWith(catalogOf([hostile, ["/js/pipeline-editor/runtime.js", true]], "on-mount"));
    await assert.rejects(assets.preparePage(main), /Invalid page dependency/, hostile);
    await assets.mountPage(main); await settle();
    assert.equal(appended.length, before, `no <script> for ${hostile}`);
    assert.equal(editorMounts.length, mountsBefore, "the activation never runs behind a refused entry");
    assert.equal(main.notices.length, 1);
    assert.equal(main.notices[0].textContent, "Invalid page dependency");
  }
});

test("catalogs in one main mount independently: a failed one does not block the other", async () => {
  outcomes.set("/js/broken-sibling.js", { fail: true });
  const main = mainWith(catalogOf(["/js/broken-sibling.js"]), catalogOf(["/js/sibling-ok.js"]));
  await assets.mountPage(main); await settle();
  assert.equal(main.notices.length, 1);
  assert.equal(count(executed, "/js/sibling-ok.js"), 1);
});

test("a mount waits for the page's own swapped-in scripts: a restore's replayed tag runs before the activation", async () => {
  // A cached restore re-creates main's bare tags to execute AFTER htmx:historyRestore; activating first let a replayed
  // second Alpine's start walk bind the live root twice (PipelineWorkspaceHistoryBrowserTest's planted case).
  const listeners = {};
  const replayed = { type: "", noModule: false, addEventListener: (name, handler) => { listeners[name] = handler; } };
  const before = editorMounts.length;
  const main = mainWith(catalogOf(EDITOR, "on-mount"));
  main.scripts = [replayed];
  const mounting = assets.mountPage(main);
  await settle();
  assert.equal(editorMounts.length, before, "no activation while the page's own script is pending");
  listeners.load();
  await mounting;
  assert.equal(editorMounts.length, before + 1, "the mount runs once the replayed script has executed");
});
