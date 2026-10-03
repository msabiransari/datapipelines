// #402 — the workspaces' own history entries (static/js/workspace/history.js): the ONE state
// shape, the canonical URL, the first push converting htmx's entry, htmx's current-path record
// kept in step, the family-filtered dispatch through ONE window listener, and the hand-off to
// htmx for an entry the live page cannot replay. The fake window below models the parts of
// the History API and htmx 2.0.10 the helper touches: entries with state + URL, popstate on
// back/forward, sessionStorage, and htmx's `window.onpopstate` (which restores {htmx:true}).

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import fs from "node:fs";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const historyPath = path.resolve(here, "../../main/resources/static/js/workspace/history.js");
const htmxPath = path.resolve(here, "../../main/resources/static/vendor/htmx/htmx.min.js");
const History = require(historyPath);

const ORIGIN = "http://app.test";

/** A window with a real-shaped history stack; back()/forward() fire popstate like a browser. */
function fakeWindow(startUrl, startState = null) {
  const listeners = { popstate: [] };
  const entries = [{ state: startState, url: new URL(startUrl, ORIGIN).href }];
  let index = 0;
  const storage = new Map();
  const clone = (v) => (v === null || v === undefined ? null : JSON.parse(JSON.stringify(v)));
  const w = {
    htmxRestores: [],
    reloads: 0,
    location: {
      get href() {
        return entries[index].url;
      },
      get pathname() {
        return new URL(entries[index].url).pathname;
      },
      get search() {
        return new URL(entries[index].url).search;
      },
      reload() {
        w.reloads++;
      },
    },
    history: {
      get state() {
        return clone(entries[index].state);
      },
      get length() {
        return entries.length;
      },
      pushState(state, _title, url) {
        entries.splice(index + 1);
        entries.push({ state: clone(state), url: new URL(url, entries[index].url).href });
        index++;
      },
      replaceState(state, _title, url) {
        entries[index] = { state: clone(state), url: url ? new URL(url, entries[index].url).href : entries[index].url };
      },
    },
    sessionStorage: {
      getItem: (k) => (storage.has(k) ? storage.get(k) : null),
      setItem: (k, v) => storage.set(k, String(v)),
    },
    addEventListener(type, fn) {
      (listeners[type] ||= []).push(fn);
    },
    htmx: {},
    // htmx 2.0.10's handler, modelled: it restores ONLY {htmx:true} entries.
    onpopstate(evt) {
      if (evt.state && evt.state.htmx) w.htmxRestores.push(w.location.pathname + w.location.search);
    },
    fire() {
      const evt = { state: clone(entries[index].state) };
      // The IDL handler was registered first (htmx's init), then the listeners.
      w.onpopstate(evt);
      listeners.popstate.forEach((fn) => fn(evt));
    },
    back() {
      index--;
      w.fire();
    },
    forward() {
      index++;
      w.fire();
    },
    listenerCount: () => listeners.popstate.length,
    entries,
    storage,
  };
  return w;
}

const KEY = "htmx-current-path-for-history";

test("an entry carries {dpWorkspace: {family, version, tab}} and NOTHING else", () => {
  const state = History.entryState("pipelines", 2, "overview");
  assert.deepEqual(Object.keys(state), ["dpWorkspace"]);
  assert.deepEqual(Object.keys(state.dpWorkspace).sort(), ["family", "tab", "version"]);
  assert.deepEqual(state, { dpWorkspace: { family: "pipelines", version: 2, tab: "overview" } });
  // A non-number version or a non-string tab is recorded as null, never carried raw.
  assert.deepEqual(History.entryState("dashboards", "2", 7).dpWorkspace, { family: "dashboards", version: null, tab: null });
});

test("a PUSHED entry's state is exactly the shape — no parameter values ride it", () => {
  const w = fakeWindow("/pipelines/p1?version=1");
  History.push("pipelines", { version: 1, tab: "flow" }, { version: 2, tab: "flow", overrides: { a: 1 } }, { defaultTab: "flow", win: w });
  for (const entry of w.entries) {
    assert.deepEqual(Object.keys(entry.state), ["dpWorkspace"]);
    assert.deepEqual(Object.keys(entry.state.dpWorkspace).sort(), ["family", "tab", "version"]);
  }
});

test("urlFor keeps every foreign parameter, sets the version and drops the DEFAULT tab", () => {
  assert.equal(History.urlFor("http://h/pipelines/p1?version=1&ok=released", 2, "flow", "flow"), "/pipelines/p1?version=2&ok=released");
  assert.equal(History.urlFor("http://h/pipelines/p1?version=1&tab=runs", 1, "flow", "flow"), "/pipelines/p1?version=1");
  assert.equal(History.urlFor("http://h/pipelines/p1", 3, "overview", "flow"), "/pipelines/p1?version=3&tab=overview");
  assert.equal(History.urlFor("/pipelines/p1?x=1#frag", 1, "flow", "flow"), "/pipelines/p1?x=1&version=1", "relative, no fragment");
});

test("without a default tab the tab is always stated (the dashboards rule), the version left as found", () => {
  assert.equal(History.urlFor("/dashboards/x?version=2&tab=overview", null, "versions"), "/dashboards/x?version=2&tab=versions");
  assert.equal(History.urlFor("/dashboards/x", null, "overview"), "/dashboards/x?tab=overview");
  assert.equal(History.urlFor("/dashboards/x?tab=board&ok=released", null, "versions"), "/dashboards/x?tab=versions&ok=released");
  assert.equal(History.urlFor("/dashboards/x", null, "board"), "/dashboards/x?tab=board");
});

test("the first push CONVERTS htmx's current entry, then pushes; htmx's path record follows", () => {
  const w = fakeWindow("/pipelines/p1", { htmx: true });
  const verb = History.push("pipelines", { version: 1, tab: "flow" }, { version: 2, tab: "flow" }, { defaultTab: "flow", win: w });
  assert.equal(verb, "push");
  assert.equal(w.entries.length, 2);
  assert.deepEqual(w.entries[0].state, History.entryState("pipelines", 1, "flow"), "Back lands on an entry WE replay");
  assert.equal(w.entries[0].url, ORIGIN + "/pipelines/p1", "the converted entry keeps its own URL");
  assert.deepEqual(w.entries[1].state, History.entryState("pipelines", 2, "flow"));
  assert.equal(w.entries[1].url, ORIGIN + "/pipelines/p1?version=2");
  assert.equal(w.storage.get(KEY), "/pipelines/p1?version=2", "htmx's next snapshot is keyed by the URL the user returns to");
});

test("a document's null entry is converted too; an entry already ours is not re-written", () => {
  const w = fakeWindow("/pipelines/p1?version=1");
  History.push("pipelines", { version: 1, tab: "flow" }, { version: 1, tab: "overview" }, { defaultTab: "flow", win: w });
  assert.deepEqual(w.entries[0].state, History.entryState("pipelines", 1, "flow"));
  const before = JSON.stringify(w.entries[1]);
  History.push("pipelines", { version: 1, tab: "WRONG" }, { version: 2, tab: "overview" }, { defaultTab: "flow", win: w });
  assert.equal(JSON.stringify(w.entries[1]), before, "an entry of ours is left as it was pushed");
  assert.equal(w.entries.length, 3);
  assert.equal(w.entries[2].url, ORIGIN + "/pipelines/p1?version=2&tab=overview");
});

test("an unchanged view only re-states the current entry — no history noise", () => {
  const w = fakeWindow("/pipelines/p1", { htmx: true });
  const verb = History.push("pipelines", { version: 1, tab: "flow" }, { version: 1, tab: "flow" }, { defaultTab: "flow", win: w });
  assert.equal(verb, "replace");
  assert.equal(w.history.length, 1);
  assert.equal(w.entries[0].url, ORIGIN + "/pipelines/p1?version=1", "the canonical URL, as replaceState wrote it before #402");
  assert.equal(w.storage.get(KEY), "/pipelines/p1?version=1");
});

test("Back/Forward over our entries replay through the family's apply; htmx restores nothing", () => {
  const w = fakeWindow("/pipelines/p1?version=1", { htmx: true });
  const applied = [];
  History.listen("pipelines", (v, t) => (applied.push([v, t]), true), { win: w });
  const opts = { defaultTab: "flow", win: w };
  History.push("pipelines", { version: 1, tab: "flow" }, { version: 2, tab: "flow" }, opts);
  History.push("pipelines", { version: 2, tab: "flow" }, { version: 2, tab: "overview" }, opts);
  const length = w.history.length;
  w.back();
  w.back();
  w.forward();
  w.forward();
  assert.deepEqual(applied, [[2, "flow"], [1, "flow"], [2, "flow"], [2, "overview"]]);
  assert.equal(w.history.length, length, "a replay never mints an entry");
  assert.deepEqual(w.htmxRestores, [], "htmx ignores entries without htmx:true");
  assert.equal(w.storage.get(KEY), "/pipelines/p1?version=2&tab=overview", "the replay keeps htmx's path record on THIS location");
  assert.equal(History.stats(w).replayed.pipelines, 4);
});

test("an htmx entry is htmx's alone — the listener neither applies nor hands it on", () => {
  const w = fakeWindow("/pipelines", { htmx: true });
  let applied = 0;
  History.listen("pipelines", () => (applied++, true), { win: w });
  w.history.pushState({ htmx: true }, "", "/pipelines/p1");
  w.back();
  assert.equal(applied, 0);
  assert.deepEqual(w.htmxRestores, ["/pipelines"], "htmx's own handler restored it, once");
  assert.equal(History.stats(w).handedToHtmx, 0);
});

test("dispatch is family-filtered: a pipelines entry never reaches the dashboards client", () => {
  const w = fakeWindow("/pipelines/p1?version=1");
  const calls = [];
  History.listen("dashboards", () => (calls.push("dashboards"), true), { win: w });
  History.listen("pipelines", () => (calls.push("pipelines"), true), { win: w });
  History.push("pipelines", { version: 1, tab: "flow" }, { version: 2, tab: "flow" }, { defaultTab: "flow", win: w });
  w.back();
  assert.deepEqual(calls, ["pipelines"]);
});

test("a pipelines entry pops with no dashboards root: the dashboards client never runs", () => {
  const w = fakeWindow("/dashboards/d1?tab=board");
  let ranOnDetached = 0;
  // The dashboards client's apply refuses when its root is gone; registered, never called here.
  History.listen("dashboards", () => (ranOnDetached++, false), { win: w });
  w.history.replaceState(History.entryState("pipelines", 1, "flow"), "", "/pipelines/p1?version=1");
  w.history.pushState({ htmx: true }, "", "/pipelines");
  w.back();
  assert.equal(ranOnDetached, 0);
});

test("an entry the live page cannot replay is handed to htmx's own restore", () => {
  const w = fakeWindow("/pipelines/p1?version=1");
  // The live page is not this workspace any more (Forward from the list onto our entry).
  History.listen("pipelines", () => false, { win: w });
  History.push("pipelines", { version: 1, tab: "flow" }, { version: 2, tab: "flow" }, { defaultTab: "flow", win: w });
  w.back();
  assert.deepEqual(w.htmxRestores, ["/pipelines/p1?version=1"], "htmx restores the entry from its cache (or the server)");
  assert.equal(History.stats(w).handedToHtmx, 1);
  assert.equal(History.stats(w).replayed.pipelines || 0, 0);
});

test("a family nobody registered, and an apply that throws, hand to htmx — never a dead entry", () => {
  const w = fakeWindow("/templates/t1");
  w.history.replaceState(History.entryState("templates", 1, "flow"), "", "/templates/t1?version=1");
  w.history.pushState({ htmx: true }, "", "/templates");
  History.listen("pipelines", () => {
    throw new Error("boom");
  }, { win: w });
  w.back();
  assert.deepEqual(w.htmxRestores, ["/templates/t1?version=1"]);
  w.history.replaceState(History.entryState("pipelines", 1, "flow"), "", "/pipelines/p1?version=1");
  w.history.pushState({ htmx: true }, "", "/pipelines");
  w.back();
  assert.equal(History.stats(w).handedToHtmx, 2);
});

test("without htmx the hand-off is a reload, never a silent no-op", () => {
  const w = fakeWindow("/pipelines/p1?version=1");
  delete w.htmx;
  History.listen("pipelines", () => false, { win: w });
  History.push("pipelines", { version: 1, tab: "flow" }, { version: 2, tab: "flow" }, { defaultTab: "flow", win: w });
  w.back();
  assert.equal(w.reloads, 1);
});

test("ONE window listener per document; re-registering replaces the family's apply", () => {
  const w = fakeWindow("/dashboards/d1?tab=board");
  const calls = [];
  History.listen("dashboards", () => (calls.push("first"), true), { win: w });
  History.listen("dashboards", () => (calls.push("second"), true), { win: w });
  History.listen("pipelines", () => true, { win: w });
  assert.equal(w.listenerCount(), 1);
  assert.equal(History.stats(w).listeners, 1);
  History.push("dashboards", { version: null, tab: "board" }, { version: null, tab: "overview" }, { win: w });
  w.back();
  assert.deepEqual(calls, ["second"], "the LIVE root's apply answers, resolved at event time");
});

test("htmx's current-path record is written from THIS location, never from state", () => {
  const w = fakeWindow("/pipelines/p1?version=1");
  History.listen("pipelines", () => true, { win: w });
  History.push("pipelines", { version: 1, tab: "flow" }, { version: 2, tab: "flow" }, { defaultTab: "flow", win: w });
  // A forged entry: its state names a different place than its URL.
  w.history.replaceState({ dpWorkspace: { family: "pipelines", version: 9, tab: "/evil" } }, "", "/pipelines/p1?version=2");
  w.history.pushState(History.entryState("pipelines", 3, "flow"), "", "/pipelines/p1?version=3");
  w.back();
  assert.equal(w.storage.get(KEY), "/pipelines/p1?version=2");
});

test("storage that refuses never breaks a push", () => {
  const w = fakeWindow("/pipelines/p1?version=1");
  w.sessionStorage.setItem = () => {
    throw new Error("QuotaExceededError");
  };
  assert.equal(History.push("pipelines", { version: 1, tab: "flow" }, { version: 2, tab: "flow" }, { defaultTab: "flow", win: w }), "push");
});

test("drift pin: htmx 2.0.10's current-path key and its popstate rule are still in the vendored build", () => {
  const source = fs.readFileSync(htmxPath, "utf8");
  const count = (needle) => source.split(needle).length - 1;
  assert.equal(History.HTMX_CURRENT_PATH_KEY, "htmx-current-path-for-history");
  // $t writes it, Gt reads it — an upgrade that renames the record turns this red.
  assert.equal(count('"' + History.HTMX_CURRENT_PATH_KEY + '"'), 2);
  // htmx restores ONLY {htmx:true} entries and hands the rest to the previous handler.
  assert.equal(count("window.onpopstate=function(e){if(e.state&&e.state.htmx){"), 1);
  // Its snapshot save re-states the CURRENT entry as htmx's own.
  assert.equal(count("history.replaceState({htmx:true},te().title,location.href)"), 1);
});
