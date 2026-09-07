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
  globalThis.window = { location: { pathname: "/pipelines" }, localStorage: { setItem() {} } };
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
