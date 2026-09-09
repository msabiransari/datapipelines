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

// 079 §A: a rail link carries its section, its group and its label; the group and
// label are what shell.js re-derives the breadcrumb from after a boosted swap.
function mkLink(section, group, label) {
  const classes = new Set(["app-nav-link"]);
  const attrs = { "data-nav-section": section, "data-nav-group": group ?? "", "data-nav-label": label ?? "" };
  return {
    getAttribute: (k) => (k in attrs ? attrs[k] : null),
    setAttribute: (k, v) => { attrs[k] = v; },
    removeAttribute: (k) => { delete attrs[k]; },
    classList: { toggle: (c, on) => (on ? classes.add(c) : classes.delete(c)) },
    has: (c) => classes.has(c),
    attr: (k) => attrs[k],
  };
}

test("syncNavActive toggles .active and aria-current from data-nav-section, not from href", () => {
  const shell = loadShell();
  const links = [mkLink("/dashboard"), mkLink("/executions"), mkLink("/admin")];
  const doc = { querySelectorAll: (sel) => (sel === ".app-nav-link[data-nav-section]" ? links : []) };

  shell.syncNavActive(doc, "/executions?offset=20".split("?")[0]);
  assert.deepEqual(links.map((l) => l.has("active")), [false, true, false]);
  // The highlight is state, not just paint: aria-current rides with the class and is
  // REMOVED from the others, never merely repainted.
  assert.deepEqual(links.map((l) => l.attr("aria-current")), [undefined, "page", undefined]);

  shell.syncNavActive(doc, "/admin/users");
  assert.deepEqual(links.map((l) => l.has("active")), [false, false, true]);
  assert.deepEqual(links.map((l) => l.attr("aria-current")), [undefined, undefined, "page"]);
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
  const onWindow = [];
  globalThis.window = {
    location: { pathname: "/pipelines" },
    localStorage: { setItem() {} },
    // 103 §A: init() also listens for `pageshow` on the WINDOW — the bfcache
    // restores the DOM with `is-pending` still on the link it was left on and
    // fires no htmx event of any kind, so it is the one ending only this covers.
    addEventListener: (t) => onWindow.push(t),
  };
  globalThis.document = {
    readyState: "complete",
    body: { addEventListener: (t) => added.push(t) },
    // 079: init() also stamps the rail's aria-expanded and installs a document-level
    // keydown listener for the avatar menu, so the double grew these two members.
    addEventListener: () => {},
    documentElement: { classList: { toggle: () => {}, contains: () => false } },
    getElementById: () => null,
    querySelectorAll: () => [],
  };
  const shell = loadShell(); // init() runs at load (readyState complete)
  shell.init();
  shell.init();
  const beforeSwapCount = added.filter((t) => t === "htmx:beforeSwap").length;
  assert.equal(beforeSwapCount, 1, "one boost-policy listener across repeated init");
  assert.deepEqual(onWindow, ["pageshow"], "the bfcache clear is wired exactly once");
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
    localStorage: { setItem() {} },
    addEventListener: () => {},
  };
  globalThis.document = {
    readyState: "complete",
    body: { addEventListener: (t, fn) => { listeners[t] = fn; } },
    addEventListener: () => {},
    documentElement: { classList: { toggle: () => {}, contains: () => false } },
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

// ---------------------------------------------------------------------------
// 079 §A/§B — the rail's collapsed state, the breadcrumb mirror, the theme
// controls and the avatar menu. All four are things the SERVER computed for the
// first paint and the shell must keep true afterwards, because a boosted swap
// replaces #app-main only and never re-renders the rail or the top bar.
// ---------------------------------------------------------------------------

/** The smallest document these functions actually touch. */
function mkDoc(nodes) {
  const byId = nodes.byId || {};
  const bySelector = nodes.bySelector || {};
  const classes = new Set(nodes.rootClasses || []);
  const rootAttrs = {};
  return {
    documentElement: {
      classList: {
        toggle: (c, on) => (on ? classes.add(c) : classes.delete(c)),
        contains: (c) => classes.has(c),
      },
      setAttribute: (k, v) => { rootAttrs[k] = v; },
      getAttribute: (k) => rootAttrs[k] ?? null,
    },
    getElementById: (id) => byId[id] ?? null,
    querySelectorAll: (sel) => bySelector[sel] ?? [],
    activeElement: nodes.activeElement ?? null,
    rootHas: (c) => classes.has(c),
    rootAttr: (k) => rootAttrs[k],
  };
}

function mkEl(attrs = {}) {
  const a = { ...attrs };
  return {
    getAttribute: (k) => (k in a ? a[k] : null),
    setAttribute: (k, v) => { a[k] = v; },
    removeAttribute: (k) => { delete a[k]; },
    attr: (k) => a[k],
    hidden: !!attrs.hidden,
    textContent: attrs.textContent ?? "",
    focused: false,
    focus() { this.focused = true; },
  };
}

test("the rail's collapsed state is written to <html>, to the button and to storage", () => {
  const shell = loadShell();
  const button = mkEl({});
  const doc = mkDoc({ byId: { "rail-collapse": button } });
  const saved = {};
  const storage = { setItem: (k, v) => { saved[k] = v; } };

  shell.setRailCollapsed(doc, true, storage);
  // The class goes on <html> — the same element the layout's pre-paint inline script
  // stamps, so there is one source of truth and no flash of the wrong width.
  assert.equal(doc.rootHas(shell.COLLAPSED_CLASS), true);
  assert.equal(shell.railCollapsed(doc), true);
  assert.equal(button.attr("aria-expanded"), "false");
  assert.equal(saved[shell.RAIL_KEY], "1");

  shell.setRailCollapsed(doc, false, storage);
  assert.equal(shell.railCollapsed(doc), false);
  assert.equal(button.attr("aria-expanded"), "true");
  assert.equal(saved[shell.RAIL_KEY], "0");
});

test("a storage that throws does not stop the rail collapsing", () => {
  const shell = loadShell();
  const doc = mkDoc({});
  const hostile = { setItem() { throw new Error("SecurityError"); } };

  // Private browsing throws on write. A preference that cannot be saved is not a
  // reason to refuse the interaction.
  assert.equal(shell.setRailCollapsed(doc, true, hostile), true);
  assert.equal(shell.railCollapsed(doc), true);
});

test("syncCrumbs re-derives the breadcrumb from the ACTIVE rail link", () => {
  const shell = loadShell();
  const group = mkEl({});
  const sep = mkEl({});
  const page = mkEl({});
  const crumbs = {
    querySelector: (sel) =>
      ({ ".app-crumb-group": group, ".app-crumb-sep": sep, ".app-crumb-page": page })[sel] ?? null,
  };
  const links = [
    mkLink("/dashboard", "", "Dashboard"),
    mkLink("/pipelines", "Build", "Pipelines"),
    mkLink("/api-console", "Operate", "API"),
  ];
  const doc = mkDoc({
    byId: { "app-crumbs": crumbs },
    bySelector: { ".app-nav-link[data-nav-section]": links },
  });

  assert.deepEqual(shell.syncCrumbs(doc, "/pipelines/abc"), { group: "Build", label: "Pipelines" });
  assert.equal(page.textContent, "Pipelines");
  assert.equal(group.textContent, "Build");
  assert.equal(sep.hidden, false);

  // Dashboard has no group: the separator and the group span go away rather than
  // rendering a leading slash.
  assert.deepEqual(shell.syncCrumbs(doc, "/dashboard"), { group: "", label: "Dashboard" });
  assert.equal(group.hidden, true);
  assert.equal(sep.hidden, true);

  // Off-rail (Settings): no active link, so the server-rendered crumb is left alone
  // rather than blanked — the client has nothing better to say.
  page.textContent = "Settings";
  assert.equal(shell.syncCrumbs(doc, "/settings"), null);
  assert.equal(page.textContent, "Settings");
});

test("themeFromHref reads the theme back out of the swapped stylesheet link", () => {
  const shell = loadShell();
  assert.equal(shell.themeFromHref("/vendor/design-system/themes/dark.css"), "dark");
  assert.equal(shell.themeFromHref("/ctx/vendor/design-system/themes/jetbrains-x.css"), "jetbrains-x");
  assert.equal(shell.themeFromHref("/css/app.css"), null);
  assert.equal(shell.themeFromHref(null), null);
});

test("applyTheme flips the icon, the toggle's next value, the segment and the swatches", () => {
  const shell = loadShell();
  const sun = mkEl({ "data-mode-icon": "light" });
  const moon = mkEl({ "data-mode-icon": "dark", hidden: true });
  const toggle = mkEl({ "hx-vals": '{"theme":"dark"}' });
  const light = mkEl({ "data-mode": "light" });
  const dark = mkEl({ "data-mode": "dark" });
  const auto = mkEl({ "data-mode": "auto" });
  const ocean = mkEl({ "data-swatch": "ocean" });
  const saas = mkEl({ "data-swatch": "saas" });
  const name = mkEl({});
  const doc = mkDoc({
    byId: { "mode-toggle": toggle, "app-theme-name": name },
    bySelector: {
      "[data-mode-icon]": [sun, moon],
      "#app-appearance [data-mode]": [light, dark, auto],
      ".app-swatch[data-swatch]": [ocean, saas],
    },
  });

  shell.applyTheme(doc, "dark");
  assert.equal(doc.rootAttr("data-theme"), "dark");
  assert.equal(sun.hidden, true);
  assert.equal(moon.hidden, false);
  // The toggle always asks for the OTHER mode — after switching to dark it offers light.
  assert.equal(toggle.attr("hx-vals"), '{"theme":"light"}');
  assert.equal(toggle.attr("aria-label"), "Switch to light");
  assert.deepEqual([light, dark, auto].map((b) => b.attr("aria-pressed")), ["false", "true", "false"]);
  assert.deepEqual([ocean, saas].map((b) => b.attr("aria-pressed")), ["false", "false"]);
  assert.equal(name.textContent, "dark");

  // A palette is the same one preference: the mode segment goes fully unpressed.
  shell.applyTheme(doc, "ocean");
  assert.equal(doc.rootAttr("data-theme"), "ocean");
  assert.deepEqual([light, dark, auto].map((b) => b.attr("aria-pressed")), ["false", "false", "false"]);
  assert.deepEqual([ocean, saas].map((b) => b.attr("aria-pressed")), ["true", "false"]);
  // A palette is not dark, so the sun comes back.
  assert.equal(sun.hidden, false);
});

test("applyTheme with no theme is a no-op — a refused write must not blank the controls", () => {
  const shell = loadShell();
  const name = mkEl({ textContent: "saas" });
  const doc = mkDoc({ byId: { "app-theme-name": name } });

  assert.equal(shell.applyTheme(doc, null), null);
  assert.equal(name.textContent, "saas");
});

test("the avatar menu opens, closes and reports its state through aria-expanded", () => {
  const shell = loadShell();
  const menu = mkEl({ hidden: true });
  const avatar = mkEl({ "aria-expanded": "false" });
  const doc = mkDoc({ byId: { "app-user-menu": menu, "app-avatar": avatar } });

  assert.equal(shell.menuIsOpen(doc), false);
  shell.setMenuOpen(doc, true);
  assert.equal(menu.hidden, false);
  assert.equal(avatar.attr("aria-expanded"), "true");
  assert.equal(shell.menuIsOpen(doc), true);

  shell.setMenuOpen(doc, false);
  assert.equal(menu.hidden, true);
  assert.equal(avatar.attr("aria-expanded"), "false");
});

test("arrow keys move focus inside the menu, wrapping at both ends", () => {
  const shell = loadShell();
  const items = [mkEl({ role: "menuitem" }), mkEl({ role: "menuitem" }), mkEl({ role: "menuitem" })];
  const menu = { querySelectorAll: () => items };
  const doc = mkDoc({ byId: { "app-user-menu": menu } });

  // Nothing focused yet: ArrowDown lands on the first item.
  assert.equal(shell.moveMenuFocus(doc, 1), items[0]);

  doc.activeElement = items[0];
  assert.equal(shell.moveMenuFocus(doc, 1), items[1]);
  doc.activeElement = items[2];
  assert.equal(shell.moveMenuFocus(doc, 1), items[0], "wraps forwards");
  doc.activeElement = items[0];
  assert.equal(shell.moveMenuFocus(doc, -1), items[2], "wraps backwards");
  assert.equal(shell.moveMenuFocus(doc, "first"), items[0]);
  assert.equal(shell.moveMenuFocus(doc, "last"), items[2]);
  // Focus only — the menu's buttons are htmx triggers and must never be activated
  // by a keyboard move.
  assert.equal(items.every((i) => i.focused), true);
});

// ---------------------------------------------------------------------------
// 085 §D — the every-request busy signal: createBusyTracker counts requests in
// flight (bar shows on 0→1, hides on →0), marks the originating <button>
// aria-disabled, and arms the delayed skeleton on the swap target. Driven here
// with a fake clock and hand-rolled elements — same harness as above.
// ---------------------------------------------------------------------------

/** A fake clock: setTimeout records, fire() runs, clearTimeout drops. */
function mkClock() {
  const pending = [];
  return {
    setTimeout: (fn) => (pending.push(fn), fn),
    clearTimeout: (fn) => {
      const at = pending.indexOf(fn);
      if (at !== -1) pending.splice(at, 1);
    },
    fireNext: () => pending.shift()(),
    pendingCount: () => pending.length,
  };
}

/** An element double with the surface the tracker touches. */
function mkBusyEl(tagName) {
  const attrs = {};
  const classes = new Set();
  const el = {
    tagName,
    className: "",
    classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) },
    hasClass: (c) => classes.has(c),
    children: [],
    parentNode: null,
    getAttribute: (k) => (k in attrs ? attrs[k] : null),
    setAttribute: (k, v) => { attrs[k] = v; },
    removeAttribute: (k) => { delete attrs[k]; },
    appendChild: (child) => { el.children.push(child); child.parentNode = el; },
    removeChild: (child) => {
      const at = el.children.indexOf(child);
      if (at !== -1) el.children.splice(at, 1);
      child.parentNode = null;
    },
    attr: (k) => attrs[k],
  };
  return el;
}

/** A doc double: the progress bar by id plus createElement for the skeleton. */
function mkBusyDoc() {
  const classes = new Set();
  const bar = { classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) } };
  return {
    barActive: () => classes.has("active"),
    getElementById: (id) => (id === "app-progress" ? bar : null),
    createElement: (tag) => mkBusyEl(tag.toUpperCase()),
  };
}

test("the bar shows on the first request and hides only when the LAST one ends", () => {
  const shell = loadShell();
  const clock = mkClock();
  const doc = mkBusyDoc();
  const tracker = shell.createBusyTracker(clock, shell.SKELETON_DELAY_MS);

  // Interleaved: a tree expand (A) and a detail load (B) overlap; A ending
  // first must not hide the bar B is still holding.
  tracker.begin(doc, mkBusyEl("SUMMARY"), mkBusyEl("DIV"));
  assert.equal(doc.barActive(), true);
  assert.equal(tracker.inFlight(), 1);
  tracker.begin(doc, mkBusyEl("BUTTON"), mkBusyEl("DIV"));
  assert.equal(tracker.inFlight(), 2);

  tracker.end(doc, mkBusyEl("SUMMARY"), mkBusyEl("DIV"));
  assert.equal(doc.barActive(), true, "B still in flight — the bar stays");
  tracker.end(doc, mkBusyEl("BUTTON"), mkBusyEl("DIV"));
  assert.equal(doc.barActive(), false);
  assert.equal(tracker.inFlight(), 0);

  // A stray end (a terminal event with no matching begin) clamps at zero and
  // never errors — the one guard against a bar stuck on by bookkeeping drift.
  assert.doesNotThrow(() => tracker.end(doc, mkBusyEl("BUTTON"), mkBusyEl("DIV")));
  assert.equal(tracker.inFlight(), 0);
  assert.equal(doc.barActive(), false);
});

test("the originating button is busy-classed and aria-disabled for the flight; summaries and inputs get the class only", () => {
  const shell = loadShell();
  const clock = mkClock();
  const doc = mkBusyDoc();
  const tracker = shell.createBusyTracker(clock, shell.SKELETON_DELAY_MS);
  const button = mkBusyEl("BUTTON");
  const summary = mkBusyEl("SUMMARY");
  const input = mkBusyEl("INPUT");

  tracker.begin(doc, button, null);
  assert.equal(button.hasClass(shell.BUSY_CLASS), true);
  assert.equal(button.attr("aria-disabled"), "true");
  tracker.end(doc, button, null);
  assert.equal(button.hasClass(shell.BUSY_CLASS), false);
  assert.equal(button.attr("aria-disabled"), undefined);

  // The marker is the SHELL's own, not htmx's .htmx-request — htmx 2.0.10 puts
  // its class on the hx-indicator target instead when the element carries
  // hx-indicator, so the control itself would go unmarked. Every requesting
  // element gets the class (the chevron spin keys off it on summaries); only
  // buttons get aria-disabled — a folder's summary must stay operable while
  // its level loads (the §C hammer pins collapse/re-expand mid-fetch), and a
  // search input must keep accepting keystrokes.
  tracker.begin(doc, summary, null);
  tracker.begin(doc, input, null);
  assert.equal(summary.hasClass(shell.BUSY_CLASS), true);
  assert.equal(input.hasClass(shell.BUSY_CLASS), true);
  assert.equal(summary.attr("aria-disabled"), undefined);
  assert.equal(input.attr("aria-disabled"), undefined);
  tracker.end(doc, summary, null);
  tracker.end(doc, input, null);
  assert.equal(summary.hasClass(shell.BUSY_CLASS), false);
  assert.equal(input.hasClass(shell.BUSY_CLASS), false);

  // A button that was ALREADY aria-disabled is not one we marked — end must
  // not strip a state it did not set.
  const preset = mkBusyEl("BUTTON");
  preset.setAttribute("aria-disabled", "true");
  tracker.begin(doc, preset, null);
  tracker.end(doc, preset, null);
  assert.equal(preset.attr("aria-disabled"), "true");
});

test("the skeleton appears only after the delay, and a fast swap never flashes it", () => {
  const shell = loadShell();
  const clock = mkClock();
  const doc = mkBusyDoc();
  const tracker = shell.createBusyTracker(clock, shell.SKELETON_DELAY_MS);
  const target = mkBusyEl("DIV");

  // Fast: the request ends before the timer fires — timer cancelled, no
  // aria-busy, no skeleton, nothing left behind.
  tracker.begin(doc, mkBusyEl("BUTTON"), target);
  assert.equal(clock.pendingCount(), 1);
  tracker.end(doc, mkBusyEl("BUTTON"), target);
  assert.equal(clock.pendingCount(), 0);
  assert.equal(target.attr("aria-busy"), undefined);
  assert.equal(target.children.length, 0);

  // Slow: the timer fires in flight — aria-busy plus ONE design-system
  // skeleton row; the request's end removes both.
  tracker.begin(doc, mkBusyEl("BUTTON"), target);
  clock.fireNext();
  assert.equal(target.attr("aria-busy"), "true");
  assert.equal(target.children.length, 1);
  assert.equal(
    target.children[0].className,
    "ds-skeleton ds-skeleton-table-row " + shell.SKELETON_CLASS,
  );
  assert.equal(target.children[0].attr("aria-hidden"), "true");
  tracker.end(doc, mkBusyEl("BUTTON"), target);
  assert.equal(target.attr("aria-busy"), undefined);
  assert.equal(target.children.length, 0);
});

test("concurrent requests into the same target share one skeleton; different targets get their own", () => {
  const shell = loadShell();
  const clock = mkClock();
  const doc = mkBusyDoc();
  const tracker = shell.createBusyTracker(clock, shell.SKELETON_DELAY_MS);
  const pane = mkBusyEl("DIV");
  const other = mkBusyEl("DIV");

  // Two selections into #template-detail (the hx-sync abort pair): one timer,
  // one skeleton; the FIRST request's end leaves both alone because the second
  // is still in flight.
  tracker.begin(doc, mkBusyEl("BUTTON"), pane);
  tracker.begin(doc, mkBusyEl("BUTTON"), pane);
  assert.equal(clock.pendingCount(), 1, "one timer per target, not per request");
  clock.fireNext();
  assert.equal(pane.children.length, 1);
  tracker.end(doc, mkBusyEl("BUTTON"), pane);
  assert.equal(pane.attr("aria-busy"), "true", "the second request still holds the pane");
  assert.equal(pane.children.length, 1);
  tracker.end(doc, mkBusyEl("BUTTON"), pane);
  assert.equal(pane.attr("aria-busy"), undefined);
  assert.equal(pane.children.length, 0);

  // Different panes are independent: clearing one never touches the other.
  tracker.begin(doc, mkBusyEl("BUTTON"), pane);
  tracker.begin(doc, mkBusyEl("BUTTON"), other);
  assert.equal(clock.pendingCount(), 2);
  clock.fireNext(); // pane's timer
  clock.fireNext(); // other's timer
  tracker.end(doc, mkBusyEl("BUTTON"), pane);
  assert.equal(pane.children.length, 0);
  assert.equal(other.attr("aria-busy"), "true");
  assert.equal(other.children.length, 1);
  tracker.end(doc, mkBusyEl("BUTTON"), other);
  assert.equal(other.children.length, 0);
});

// ---------------------------------------------------------------- 103 §A / §B
//
// The click's acknowledgement and the entrance, driven on a fake clock. The
// state machine is what these pin: the 150ms arm exists so a fast swap never
// flashes the pill, and the clear must be total across three different kinds of
// ending (settle, htmx error/abort, bfcache restore) — only their union covers
// every path, and a link left dimmed forever is worse than no feedback at all.

/** An element double with `closest`, `classList.contains` and attributes. */
function mkFeelEl(tagName, attrs = {}, parents = []) {
  const classes = new Set();
  const own = { ...attrs };
  const el = {
    tagName,
    classList: {
      add: (c) => classes.add(c),
      remove: (c) => classes.delete(c),
      contains: (c) => classes.has(c),
    },
    getAttribute: (k) => (k in own ? own[k] : null),
    setAttribute: (k, v) => { own[k] = v; },
    removeAttribute: (k) => { delete own[k]; },
    // The chain the real `closest` walks, described as [selector, element] pairs.
    closest: (sel) => {
      if (matches(el, sel)) return el;
      const hit = parents.find(([s]) => s === sel);
      return hit ? hit[1] : null;
    },
    listeners: {},
    addEventListener: (type, fn) => { el.listeners[type] = fn; },
    fire: (type) => el.listeners[type] && el.listeners[type](),
    has: (c) => classes.has(c),
    attr: (k) => own[k],
  };
  return el;
}

/** The two selectors boostedNavScope asks the ELEMENT ITSELF about. */
function matches(el, sel) {
  if (sel === "a[href]") return el.tagName === "A" && el.getAttribute("href") !== null;
  if (sel === "[hx-boost]") return el.getAttribute("hx-boost") !== null;
  return false;
}

/** A doc double carrying just the status pill. */
function mkFeelDoc(pill) {
  return { getElementById: (id) => (id === "app-status-pill" ? pill : null) };
}

function mkPill() {
  return mkFeelEl("DIV", { hidden: "hidden" });
}

test("a boosted nav click pends the link, arms the pill at 150ms, and settles clean", () => {
  const shell = loadShell();
  const clock = mkClock();
  const pill = mkPill();
  const doc = mkFeelDoc(pill);
  const tracker = shell.createPendingTracker(clock, shell.PENDING_DELAY_MS);

  const nav = mkFeelEl("NAV", { "hx-boost": "true" });
  const link = mkFeelEl("A", { href: "/pipelines" }, [[".app-nav", nav], ["[hx-boost]", nav]]);

  assert.equal(shell.boostedNavScope(link), nav);
  assert.equal(tracker.begin(doc, link, nav), true);

  // The dim lands in the SAME frame as the press — before any request exists.
  assert.equal(link.has(shell.PENDING_CLASS), true);
  assert.equal(link.attr("aria-disabled"), "true");
  assert.equal(nav.has(shell.PENDING_SCOPE_CLASS), true);
  // …and the pill is only ARMED, never shown yet.
  assert.equal(tracker.armed(), true);
  assert.equal(pill.attr("hidden"), "hidden");

  clock.fireNext();
  assert.equal(pill.attr("hidden"), undefined);
  assert.equal(pill.has(shell.PILL_ON_CLASS), true);

  tracker.clear(doc);
  assert.equal(link.has(shell.PENDING_CLASS), false);
  assert.equal(link.attr("aria-disabled"), undefined);
  assert.equal(nav.has(shell.PENDING_SCOPE_CLASS), false);
  assert.equal(pill.has(shell.PILL_ON_CLASS), false);
  assert.equal(pill.attr("hidden"), "hidden");
  assert.equal(tracker.pendingCount(), 0);
});

test("a 40ms swap never flashes the pill — the arm is cancelled, not fired", () => {
  const shell = loadShell();
  const clock = mkClock();
  const pill = mkPill();
  const doc = mkFeelDoc(pill);
  const tracker = shell.createPendingTracker(clock, shell.PENDING_DELAY_MS);
  const nav = mkFeelEl("NAV", { "hx-boost": "true" });
  const link = mkFeelEl("A", { href: "/templates" }, [[".app-nav", nav], ["[hx-boost]", nav]]);

  tracker.begin(doc, link, nav);
  assert.equal(clock.pendingCount(), 1);
  // The swap lands before the arm — the whole point of the 150ms delay.
  tracker.clear(doc);
  assert.equal(clock.pendingCount(), 0, "the timer is cancelled, not left to fire into a settled page");
  assert.equal(pill.attr("hidden"), "hidden");
  assert.equal(pill.has(shell.PILL_ON_CLASS), false);
});

test("an htmx error clears the pending state — afterSettle never fires for one", () => {
  const shell = loadShell();
  const clock = mkClock();
  const pill = mkPill();
  const doc = mkFeelDoc(pill);
  const tracker = shell.createPendingTracker(clock, shell.PENDING_DELAY_MS);
  const nav = mkFeelEl("NAV", { "hx-boost": "true" });
  const link = mkFeelEl("A", { href: "/executions" }, [[".app-nav", nav], ["[hx-boost]", nav]]);

  tracker.begin(doc, link, nav);
  clock.fireNext(); // the request is slow; the pill is up
  assert.equal(pill.has(shell.PILL_ON_CLASS), true);

  // htmx:responseError / sendError / timeout / swapError / sendAbort all route
  // here, because NONE of them is followed by an afterSettle.
  tracker.clear(doc);
  assert.equal(link.has(shell.PENDING_CLASS), false);
  assert.equal(pill.has(shell.PILL_ON_CLASS), false);
});

test("pending never unsets an aria-disabled it did not set, and never double-marks", () => {
  const shell = loadShell();
  const clock = mkClock();
  const doc = mkFeelDoc(mkPill());
  const tracker = shell.createPendingTracker(clock, shell.PENDING_DELAY_MS);
  // A control that was ALREADY aria-disabled (085 §D's busy marking, or a
  // server-rendered disabled action) must come back exactly as it was.
  const link = mkFeelEl("A", { href: "/promotion", "aria-disabled": "true" });

  tracker.begin(doc, link, null);
  assert.equal(tracker.begin(doc, link, null), false, "a second click on a pending link is a no-op");
  assert.equal(clock.pendingCount(), 1, "one arm per navigation, not one per click");
  tracker.clear(doc);
  assert.equal(link.attr("aria-disabled"), "true", "restored to what it was, not stripped");
});

test("boostedNavScope refuses everything htmx itself would not boost", () => {
  const shell = loadShell();
  const nav = mkFeelEl("NAV", { "hx-boost": "true" });
  const main = mkFeelEl("MAIN", { "hx-boost": "true" });
  const chain = [[".app-nav", nav], ["[hx-boost]", nav]];

  // In-content links scope to whatever carries the boost (there is no rail group).
  const inMain = mkFeelEl("A", { href: "/pipelines/abc" }, [["[hx-boost]", main]]);
  assert.equal(shell.boostedNavScope(inMain), main);

  // A fragment link, a new-tab link and a download are navigations htmx leaves alone.
  assert.equal(shell.boostedNavScope(mkFeelEl("A", { href: "#top" }, chain)), null);
  assert.equal(shell.boostedNavScope(mkFeelEl("A", { href: "/x", target: "_blank" }, chain)), null);
  assert.equal(shell.boostedNavScope(mkFeelEl("A", { href: "/x", download: "" }, chain)), null);
  assert.equal(shell.boostedNavScope(mkFeelEl("A", {}, chain)), null);
  // hx-boost="false" on the link itself: `closest("[hx-boost]")` finds the LINK,
  // exactly as htmx's own lookup does, and the answer is no.
  assert.equal(shell.boostedNavScope(mkFeelEl("A", { href: "/logout", "hx-boost": "false" }, chain)), null);
  // Outside any boosting ancestor (the anonymous shell's brand link).
  assert.equal(shell.boostedNavScope(mkFeelEl("A", { href: "/" }, [])), null);
});

test("a form's pending rides on its submit button, which htmx never names", () => {
  const shell = loadShell();
  const clock = mkClock();
  const pill = mkPill();
  const doc = mkFeelDoc(pill);
  const tracker = shell.createPendingTracker(clock, shell.PENDING_DELAY_MS);

  const button = mkFeelEl("BUTTON", { type: "submit" });
  const form = mkFeelEl("FORM");
  form.querySelector = (sel) => (sel.includes("submit") ? button : null);

  assert.equal(shell.submitControl(form), button);
  tracker.begin(doc, shell.submitControl(form), form);
  assert.equal(button.has(shell.PENDING_CLASS), true);
  assert.equal(button.attr("aria-disabled"), "true");
  assert.equal(form.has(shell.PENDING_SCOPE_CLASS), true);
  clock.fireNext();
  assert.equal(pill.has(shell.PILL_ON_CLASS), true);
  tracker.clear(doc);
  assert.equal(button.has(shell.PENDING_CLASS), false);

  // A form with no submit control is not a crash and not a pend.
  const bare = mkFeelEl("FORM");
  bare.querySelector = () => null;
  assert.equal(shell.submitControl(bare), null);
  assert.equal(shell.submitControl(null), null);
});

test("the entrance class comes off on animationend, and off anyway if none ever runs", () => {
  const shell = loadShell();
  const clock = mkClock();
  const main = mkFeelEl("MAIN", { id: "app-main" });

  assert.equal(shell.markEntrance(clock, null), false, "no #app-main — nothing to animate");
  shell.markEntrance(clock, main);
  assert.equal(main.has(shell.ENTER_CLASS), true);
  main.fire("animationend");
  assert.equal(main.has(shell.ENTER_CLASS), false, "the normal path: the animation ended");

  // prefers-reduced-motion sets `animation: none` (app.css), so animationend
  // NEVER fires — the fallback timer is the only thing that takes the class off,
  // and without it the class would be permanent state on a reduced-motion machine.
  const quiet = mkFeelEl("MAIN", { id: "app-main" });
  quiet.addEventListener = () => {};
  const quietClock = mkClock(); // its own clock: the first entrance's fallback is still armed
  shell.markEntrance(quietClock, quiet);
  assert.equal(quiet.has(shell.ENTER_CLASS), true);
  quietClock.fireNext();
  assert.equal(quiet.has(shell.ENTER_CLASS), false);
});
