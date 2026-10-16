// #398 — template-editor/workspace.js: the workspace's six-tab state machine (pure core)
// and the URL rewrite the DOM glue performs. The admission table mirrors the server's
// TemplateWorkspaceTab enum: unknown or missing → source; render without the render tab →
// source (BEFORE any render state — the page never renders what the role cannot use).
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const workspacePath = path.resolve(here, "../../main/resources/static/js/template-editor/workspace.js");

require(workspacePath);
const { createTabs, resolve, TABS, withTab } =
  globalThis.window && globalThis.window.TplWorkspace
    ? globalThis.window.TplWorkspace
    : require(workspacePath);

const ALL = ["source", "overview", "render", "runs", "used-by", "versions"];

test("the closed tab set is the six the brief fixes, source the default", () => {
  assert.deepEqual(TABS, ALL);
  assert.equal(resolve(undefined, true), "source");
  assert.equal(resolve(null, true), "source");
});

test("unknown names resolve to source, never an error", () => {
  assert.equal(resolve("bogus", true), "source");
  assert.equal(resolve("", true), "source");
});

test("render without the render tab resolves to source — twice over, like the server's rule", () => {
  assert.equal(resolve("render", false), "source");
  assert.equal(resolve("render", undefined), "source");
  assert.equal(resolve("render", true), "render");
});

test("every other tab is admitted whatever the render right is", () => {
  for (const tab of ["source", "overview", "runs", "used-by", "versions"]) {
    assert.equal(resolve(tab, false), tab);
  }
});

test("createTabs selects, re-selecting the active tab is inert, and admission holds on select", () => {
  const tabs = createTabs(true, "overview");
  assert.equal(tabs.active, "overview");
  assert.equal(tabs.select("versions"), "versions");
  assert.equal(tabs.isActive("versions"), true);
  assert.equal(tabs.isActive("overview"), false);
  // Inert re-select: no state change, no toggle-off.
  assert.equal(tabs.select("versions"), "versions");
  // A hidden tab is inert twice over: the client refuses too.
  assert.equal(createTabs(false, "render").active, "source");
  assert.equal(createTabs(false, "render").select("render"), "source");
  // The URL value the canonical route reads is the active tab's wire name.
  assert.equal(createTabs(true, "used-by").wire(), "used-by");
});

test("withTab rewrites only the tab parameter of a canonical URL", () => {
  assert.equal(withTab("/templates/a/b.sql", "overview"), "/templates/a/b.sql?tab=overview");
  assert.equal(withTab("/templates/a/b.sql?version=2&tab=source", "versions"), "/templates/a/b.sql?version=2&tab=versions");
  assert.equal(withTab("/templates/a/b.sql?version=3", "runs"), "/templates/a/b.sql?version=3&tab=runs");
  // An empty href is left alone — nothing to rewrite.
  assert.equal(withTab("", "source"), "");
});

// The real helper and DOM glue share an isolated browser global, including bare `history`.
function browser(start = "/templates/a.sql?version=2", helper = true) {
  const entries = [{ state: null, url: new URL(start, "http://app.test").href }];
  let index = 0;
  const listeners = [];
  const restores = [];
  const w = {
    URL, URLSearchParams,
    location: {
      get href() { return entries[index].url; },
      get pathname() { return new URL(entries[index].url).pathname; },
      get search() { return new URL(entries[index].url).search; },
    },
    history: {
      get state() { return entries[index].state; },
      pushState(state, _, url) { entries.splice(index + 1); entries.push({ state, url: new URL(url, w.location.href).href }); index++; },
      replaceState(state, _, url) { entries[index] = { state, url: new URL(url, w.location.href).href }; },
    },
    sessionStorage: { map: new Map(), setItem(key, value) { this.map.set(key, value); } },
    htmx: {},
    onpopstate(event) { if (event.state?.htmx) restores.push(w.location.pathname + w.location.search); },
    addEventListener(type, fn) { if (type === "popstate") listeners.push(fn); },
    go(delta) {
      index += delta;
      const event = { state: entries[index].state };
      w.onpopstate(event);
      listeners.forEach((fn) => fn(event));
    },
  };
  w.window = w;
  let root;
  w.document = { querySelector: () => root };
  vm.createContext(w);
  if (helper) vm.runInContext(readFileSync(path.resolve(here, "../../main/resources/static/js/workspace/history.js"), "utf8"), w);
  vm.runInContext(readFileSync(workspacePath, "utf8"), w);
  function mount(active = "source") {
    const clicks = [];
    const buttons = ALL.map((tab) => ({
      attrs: { "data-tw-tab": tab, "aria-selected": String(tab === active), "aria-controls": "pane-" + tab },
      getAttribute(key) { return this.attrs[key] ?? null; },
      setAttribute(key, value) { this.attrs[key] = value; },
      closest() { return this; },
      click() { clicks.forEach((fn) => fn({ target: this })); },
    }));
    const panes = ALL.map((tab) => ({ id: "pane-" + tab, hidden: tab !== active }));
    const link = { href: "/templates/a.sql?version=1", getAttribute() { return this.href; }, setAttribute(_, href) { this.href = href; } };
    root = {
      isConnected: true,
      getAttribute: () => "true",
      contains: (button) => buttons.includes(button),
      addEventListener: (_, fn) => clicks.push(fn),
      querySelectorAll: (selector) => selector === "[data-tw-tab]" ? buttons : selector === ".tw-pane" ? panes : [link],
      querySelector: (selector) => buttons.find((b) => selector.includes(b.attrs["aria-controls"])) ||
        buttons.find((b) => b.attrs["aria-selected"] === "true"),
      button: (tab) => buttons.find((b) => b.attrs["data-tw-tab"] === tab),
      selected: () => buttons.filter((b) => b.attrs["aria-selected"] === "true").map((b) => b.attrs["data-tw-tab"]),
      visible: () => panes.filter((p) => !p.hidden).map((p) => p.id),
      link,
      clickCount: () => clicks.length,
    };
    w.TemplateWorkspaceMount({ querySelector: () => null });
    return root;
  }
  return { w, entries, restores, mount, listenerCount: () => listeners.length };
}

test("a user click pushes one tab-only templates entry and syncs htmx; Back/Forward only replay", () => {
  const { w, entries, mount, restores } = browser();
  const root = mount();
  const arrivalUrl = entries[0].url;
  root.button("overview").click();
  assert.equal(entries.length, 2, "one user switch pushes one entry");
  assert.equal(arrivalUrl, "http://app.test/templates/a.sql?version=2", "arrival paints without a URL write");
  assert.equal(JSON.stringify(entries[1].state), JSON.stringify({ dpWorkspace: { family: "templates", version: null, tab: "overview" } }));
  assert.equal(w.sessionStorage.map.get("htmx-current-path-for-history"), "/templates/a.sql?version=2&tab=overview");
  root.button("overview").click();
  assert.equal(entries.length, 2, "reselect never mints");
  root.button("versions").click();
  for (const [delta, tab] of [[-1, "overview"], [-1, "source"], [1, "overview"], [1, "versions"]]) {
    w.go(delta);
    assert.deepEqual(root.selected(), [tab]);
    assert.deepEqual(root.visible(), ["pane-" + tab]);
    assert.equal(root.link.href, "/templates/a.sql?version=1&tab=" + tab, "version links follow replay too");
    assert.equal(entries.length, 3, "replay never mints");
    assert.equal(w.sessionStorage.map.get("htmx-current-path-for-history"), w.location.pathname + w.location.search);
  }
  assert.equal(w.WorkspaceHistory.stats().replayed.templates, 4);
  assert.deepEqual(restores, []);
});

test("a detached root or a different pathname hands the entry to htmx without painting", () => {
  for (const detached of [true, false]) {
    const { w, mount, restores } = browser();
    const root = mount();
    root.button("overview").click();
    if (detached) root.isConnected = false;
    else w.history.replaceState(w.history.state, "", "/templates/other.sql?tab=overview");
    w.history.pushState(w.history.state, "", w.location.href);
    w.go(-1);
    assert.deepEqual(root.selected(), ["overview"]);
    assert.equal(w.WorkspaceHistory.stats().handedToHtmx, 1);
    assert.equal(restores.length, 1);
    assert.equal(w.WorkspaceHistory.stats().replayed.templates, undefined);
  }
});

test("same-root mounts are inert, a restored root owns replay, and foreign entries skip templates", () => {
  const { w, mount, entries, listenerCount } = browser();
  const first = mount();
  first.button("overview").click();
  w.TemplateWorkspaceMount({ querySelector: () => null });
  assert.equal(first.clickCount(), 1);
  first.isConnected = false;
  const restored = mount("overview");
  w.go(-1);
  assert.deepEqual(restored.selected(), ["source"]);
  assert.deepEqual(first.selected(), ["overview"]);
  assert.equal(listenerCount(), 1);
  assert.equal(w.WorkspaceHistory.stats().listeners, 1);
  let foreign = 0;
  w.WorkspaceHistory.listen("pipelines", () => { foreign++; return true; });
  w.WorkspaceHistory.push("pipelines", { version: 1, tab: "flow" }, { version: 1, tab: "overview" });
  w.go(-1);
  w.go(1);
  assert.equal(foreign, 2);
  assert.equal(w.WorkspaceHistory.stats().replayed.templates, 1, "foreign replay never touches templates");
  assert.deepEqual(restored.selected(), ["source"]);
  assert.equal(entries.length, 2);
});

test("without the helper a user switch keeps replaceState, and arrival writes no URL", () => {
  const { mount, entries } = browser("/templates/a.sql", false);
  const root = mount();
  assert.equal(entries[0].url, "http://app.test/templates/a.sql");
  root.button("overview").click();
  assert.equal(entries.length, 1);
  assert.equal(entries[0].url, "http://app.test/templates/a.sql?tab=overview");
});
