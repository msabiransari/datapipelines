// #876 G6 — static/js/dependencies/mount.js, the Graph/List choice (brief §A.3: "persists per page in localStorage
// … defaults to the list when prefers-reduced-motion is set"). The real file runs in a VM with a stand-in window —
// its location, a Map-backed localStorage and matchMedia — and its per-page view functions are read off
// window.DependenciesMount._views. Round 1 kept ONE global key, so one page's choice became every unvisited page's
// default (verdict 876-r1 R2); these cases pin the page scope, the bound on stored pages, validation on read and a
// refused storage.

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const MOUNT = fs.readFileSync(path.resolve(here, "../../main/resources/static/js/dependencies/mount.js"), "utf8");

function storage({ refuse = false } = {}) {
  const map = new Map();
  return {
    map,
    getItem(key) {
      if (refuse) throw new Error("SecurityError: storage refused");
      return map.has(key) ? map.get(key) : null;
    },
    setItem(key, value) {
      if (refuse) throw new Error("SecurityError: storage refused");
      map.set(key, String(value));
    },
  };
}

/** Loads mount.js into a fresh VM; `go(path)` moves the stand-in location (a navigation to another page). */
function load({ reduced = false, localStorage = storage() } = {}) {
  const location = { pathname: "/" };
  const window = {
    location,
    localStorage,
    matchMedia: query => ({ matches: reduced && query === "(prefers-reduced-motion: reduce)" }),
  };
  vm.runInNewContext(MOUNT, { window, WeakSet, JSON, Array, setTimeout, clearTimeout });
  const views = window.DependenciesMount._views;
  return {
    views,
    localStorage,
    go(pathname) {
      location.pathname = pathname;
    },
  };
}

test("a page never chosen on: Graph by default, List under prefers-reduced-motion", () => {
  const plain = load();
  plain.go("/pipelines/one");
  assert.equal(plain.views.preferred(), "graph");
  const reduced = load({ reduced: true });
  reduced.go("/pipelines/one");
  assert.equal(reduced.views.preferred(), "list");
});

test("two pages keep independent choices; one page's choice is never another page's default", () => {
  const page = load();
  page.go("/pipelines/one");
  page.views.remember("list");
  page.go("/templates/test/other.sql");
  assert.equal(page.views.preferred(), "graph", "an unvisited page uses its own default");
  page.views.remember("graph");
  page.go("/pipelines/one");
  assert.equal(page.views.preferred(), "list");
  page.go("/templates/test/other.sql");
  assert.equal(page.views.preferred(), "graph");
  assert.deepEqual([...page.localStorage.map.keys()], [page.views.key], "one key, however many pages");
});

test("the identity is the path: a tab or version query change on the same page keeps its choice", () => {
  const page = load();
  page.go("/dashboards/7");
  page.views.remember("list");
  // location.pathname never carries the query; the same path under ?tab=board or ?version=2 is the same page.
  page.go("/dashboards/7");
  assert.equal(page.views.preferred(), "list");
  page.go("/dashboards/8");
  assert.equal(page.views.preferred(), "graph");
});

test("the key is bounded: past the cap the oldest page is dropped and the newest kept", () => {
  const page = load();
  const max = page.views.max;
  assert.ok(max >= 10, `a usable cap (${max})`);
  for (let i = 0; i <= max; i++) {
    page.go(`/pipelines/p${i}`);
    page.views.remember("list");
  }
  const stored = JSON.parse(page.localStorage.map.get(page.views.key));
  assert.equal(stored.length, max);
  assert.deepEqual(stored[0], ["/pipelines/p1", "list"], "p0, the oldest, is gone");
  assert.deepEqual(stored[max - 1], [`/pipelines/p${max}`, "list"]);
  page.go("/pipelines/p0");
  assert.equal(page.views.preferred(), "graph", "a dropped page is back to its default");
  // Choosing again on a kept page moves it to the newest end, so it is the last to be dropped.
  page.go("/pipelines/p1");
  page.views.remember("graph");
  const moved = JSON.parse(page.localStorage.map.get(page.views.key));
  assert.equal(moved.length, max);
  assert.deepEqual(moved[max - 1], ["/pipelines/p1", "graph"]);
});

test("validated on read: a malformed key, a foreign word or a bad pair falls back to the page's default", () => {
  for (const raw of ["list", "{\"a\":1}", "not json", "[[\"/pipelines/one\",\"<b>x</b>\"]]", "[[\"/pipelines/one\"]]", "[[7,\"list\"]]"]) {
    const store = storage();
    store.map.set("dp.deps.views", raw);
    const page = load({ localStorage: store });
    page.go("/pipelines/one");
    assert.equal(page.views.preferred(), "graph", `stored ${raw}`);
  }
  const store = storage();
  store.map.set("dp.deps.views", JSON.stringify([["/pipelines/one", "bogus"], ["/pipelines/two", "list"]]));
  const page = load({ localStorage: store });
  page.go("/pipelines/two");
  assert.equal(page.views.preferred(), "list", "the valid pair beside a bad one still reads");
  page.views.remember("<script>");
  assert.equal(store.map.get("dp.deps.views"), JSON.stringify([["/pipelines/one", "bogus"], ["/pipelines/two", "list"]]), "only graph or list is ever written");
});

test("a refused storage: the default stands and remembering never throws", () => {
  const page = load({ reduced: true, localStorage: storage({ refuse: true }) });
  page.go("/pipelines/one");
  assert.equal(page.views.preferred(), "list");
  assert.doesNotThrow(() => page.views.remember("graph"));
  assert.equal(page.views.preferred(), "list");
});
