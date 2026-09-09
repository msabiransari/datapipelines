// 106 — the explorer detail's DOM-free decisions (static/js/explorer-detail.js).
//
// Same harness shape as template-explorer.test.mjs: stub the globals the script touches at
// require time, then exercise what it exposes. The pointer work, the fetch and the drawer's
// geometry are the browser suite's (`ExplorerDetailBrowserTest`); what is here is what those
// adapters delegate — which tab is on, what a refusal SAYS, and where the CSRF token comes
// from.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

globalThis.window = {};
globalThis.document = {
  cookie: "",
  addEventListener: function () {},
  querySelector: function () { return null; },
  querySelectorAll: function () { return []; },
  getElementById: function () { return null; },
};
require(path.resolve(here, "../../main/resources/static/js/explorer-detail.js"));
const detail = globalThis.window.explorerDetail;

/* ------------------------------------------------------------- the refusal */

test("a refusal body in the app's error shape reads as the server's own message", () => {
  assert.equal(
    detail.messageOf('{"error":{"code":"pipeline.version.pinned","message":"Version 1 is pinned by 2 live pipelines."}}'),
    "Version 1 is pinned by 2 live pipelines.",
  );
});

test("a body that is NOT the app's error shape is passed through, never replaced", () => {
  // A proxy's HTML or an empty body is still more information than a house "something went
  // wrong" — the point of showing it is that the user can act on it.
  assert.equal(detail.messageOf("<html>502</html>"), "<html>502</html>");
  assert.equal(detail.messageOf(""), "");
  assert.equal(detail.messageOf('{"ok":true}'), '{"ok":true}');
});

/* ---------------------------------------------------------------- the CSRF */

test("the CSRF token comes from the dp_csrf cookie, decoded, and is empty when absent", () => {
  globalThis.document.cookie = "other=1; dp_csrf=abc%2Fdef; more=2";
  assert.equal(detail.csrfToken(), "abc/def");
  globalThis.document.cookie = "dp_csrf=first";
  assert.equal(detail.csrfToken(), "first");
  globalThis.document.cookie = "unrelated=1";
  assert.equal(detail.csrfToken(), "");
});

/* ----------------------------------------------------------------- the tabs */

function tabCard(names) {
  const panels = {};
  const tabs = names.map((name) => {
    const tab = {
      _panel: name,
      classes: new Set(),
      aria: {},
      classList: {
        toggle(cls, on) { if (on) tab.classes.add(cls); else tab.classes.delete(cls); },
      },
      setAttribute(k, v) { tab.aria[k] = v; },
      getAttribute() { return name; },
    };
    panels[name] = { hidden: false };
    return tab;
  });
  const card = { querySelectorAll: () => tabs };
  tabs.forEach((t) => { t.closest = () => card; });
  globalThis.document.getElementById = (id) => panels[id] || null;
  return { tabs, panels };
}

test("selecting a tab shows exactly its panel and hides the rest", () => {
  const { tabs, panels } = tabCard(["versions", "runs", "usage"]);

  detail.selectTab(tabs[1]);

  assert.equal(panels.versions.hidden, true);
  assert.equal(panels.runs.hidden, false);
  assert.equal(panels.usage.hidden, true);
});

test("the on-tab is the one marked aria-selected, and only that one", () => {
  const { tabs } = tabCard(["versions", "runs", "usage"]);

  detail.selectTab(tabs[2]);

  assert.deepEqual(tabs.map((t) => t.aria["aria-selected"]), ["false", "false", "true"]);
  assert.deepEqual(tabs.map((t) => t.classes.has("is-on")), [false, false, true]);
});

test("hiding uses `hidden`, not a display rule — the lazy tab's swap target must stay in the DOM", () => {
  // htmx swaps INTO #pipeline-tab-runs on the tab's first click. A panel removed from the
  // document (or replaced) would have nothing to swap into and the tab would render empty.
  const { tabs, panels } = tabCard(["versions", "runs"]);
  detail.selectTab(tabs[0]);
  assert.equal(Object.prototype.hasOwnProperty.call(panels.runs, "hidden"), true);
  assert.equal(panels.runs.hidden, true);
});
