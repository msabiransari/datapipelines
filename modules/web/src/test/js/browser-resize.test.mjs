import { test } from "node:test";
import assert from "node:assert/strict";
import {
  BROWSER_MAX, BROWSER_MIN, browserMaximum, effectiveWidth, keyedWidth, mountBrowserResize, parsePreference,
} from "../../main/resources/static/js/browser-resize.mjs";

test("the maximum leaves main its 320px beside the menu, within 240–480", () => {
  assert.equal(browserMaximum(1512, 202, 320), 480);
  assert.equal(browserMaximum(900, 64, 320), 480);
  assert.equal(browserMaximum(800, 202, 320), 278);
  assert.equal(browserMaximum(700, 202, 320), BROWSER_MIN);
});

test("a viewport clamp changes the effective width, never below the minimum", () => {
  assert.equal(effectiveWidth(480, 278), 278);
  assert.equal(effectiveWidth(300, 480), 300);
  assert.equal(effectiveWidth(1, 480), BROWSER_MIN);
});

test("only a whole number of pixels within 240–480 is a preference", () => {
  assert.equal(parsePreference("240"), 240);
  assert.equal(parsePreference("480px"), 480);
  assert.equal(parsePreference(" 300 "), 300);
  for (const raw of [null, undefined, "", "239", "481", "2000", "300.5", "-300", "1e3", "300em", "calc(300px)", "<b>"]) {
    assert.equal(parsePreference(raw), null, String(raw));
  }
});

test("arrows step 16 within the range; Home and End go to its ends; other keys do nothing", () => {
  assert.equal(keyedWidth("ArrowRight", 300, 480), 316);
  assert.equal(keyedWidth("ArrowRight", 470, 480), 480);
  assert.equal(keyedWidth("ArrowLeft", 300, 480), 284);
  assert.equal(keyedWidth("ArrowLeft", 250, 480), BROWSER_MIN);
  assert.equal(keyedWidth("Home", 400, 480), BROWSER_MIN);
  assert.equal(keyedWidth("End", 300, 278), 278);
  for (const key of ["Enter", " ", "ArrowUp", "Tab"]) assert.equal(keyedWidth(key, 300, 480), null);
});

/* A document with just the parts mountBrowserResize reads: <html>'s inline style and computed
   tokens, the two ids, the reset button, and a recording localStorage. */
function fakeDocument({ workspace = "acme", viewport = 1512, menu = 202, browserWidth = 300 } = {}) {
  const listeners = new Map();
  const on = target => (type, handler) => listeners.set(`${target}:${type}`, handler);
  const inline = new Map();
  const stored = new Map();
  const attributes = new Map();
  const observed = [];
  const html = {
    dataset: workspace ? { dpWorkspace: workspace } : {},
    clientWidth: viewport,
    style: {
      setProperty: (name, value) => inline.set(name, value),
      getPropertyValue: name => inline.get(name) ?? "",
      removeProperty: name => inline.delete(name),
    },
  };
  const tokens = { "--app-menu-width": `${menu}px`, "--app-main-min-width": "320px", "--app-browser-default": "300px" };
  const window = {
    getComputedStyle: () => ({ getPropertyValue: name => tokens[name] ?? "" }),
    localStorage: {
      setItem: (key, value) => stored.set(key, String(value)),
      removeItem: key => stored.delete(key),
    },
    addEventListener: on("window"),
    MutationObserver: class {
      constructor(callback) { listeners.set("html:class", callback); }
      observe(target, options) { observed.push([target, options]); }
    },
  };
  const separator = {
    setAttribute: (name, value) => attributes.set(name, value),
    addEventListener: on("separator"),
    hasPointerCapture: () => false, setPointerCapture() {}, releasePointerCapture() {},
  };
  const browser = { getBoundingClientRect: () => ({ left: menu, width: browserWidth }) };
  const reset = { addEventListener: on("reset") };
  const ids = { "app-browser": browser, "browser-boundary": separator, "browser-width-reset": reset };
  const document = {
    defaultView: window, documentElement: html,
    getElementById: id => ids[id] ?? null,
    addEventListener: on("document"),
  };
  const key = (name, init = {}) => {
    let prevented = false;
    listeners.get("separator:keydown")({ key: name, preventDefault: () => { prevented = true; }, ...init });
    return prevented;
  };
  return { document, inline, stored, attributes, listeners, key, tokens, observed, html };
}

test("the separator names its range and the width it renders", () => {
  const page = fakeDocument();
  mountBrowserResize(page.document);
  assert.equal(page.attributes.get("aria-valuemin"), String(BROWSER_MIN));
  assert.equal(page.attributes.get("aria-valuemax"), "480");
  assert.equal(page.attributes.get("aria-valuenow"), "300");
});

test("a key writes the preference to <html> and to this workspace's key, and nothing else", () => {
  const page = fakeDocument();
  mountBrowserResize(page.document);
  assert.equal(page.key("ArrowRight"), true);
  assert.equal(page.inline.get("--app-browser-preferred"), "316px");
  assert.deepEqual([...page.stored], [["dp-browser-width:acme", "316"]]);
  assert.equal(page.attributes.get("aria-valuenow"), "316");
  assert.equal(page.key("Enter"), false);
  assert.deepEqual([...page.stored], [["dp-browser-width:acme", "316"]]);
});

test("a narrow window lowers the maximum but never rewrites the stored preference", () => {
  const page = fakeDocument({ viewport: 800 });
  mountBrowserResize(page.document);
  page.inline.set("--app-browser-preferred", "480px");
  page.listeners.get("window:resize")();
  assert.equal(page.attributes.get("aria-valuemax"), "278");
  assert.equal(page.attributes.get("aria-valuenow"), "278");
  assert.equal(page.inline.get("--app-browser-preferred"), "480px");
  assert.equal(page.stored.size, 0);
});

test("the menu's collapse moves the range, and an arrow at the clamp writes nothing", () => {
  const page = fakeDocument({ viewport: 900, menu: 64, browserWidth: 480 });
  mountBrowserResize(page.document);
  assert.deepEqual(page.observed.map(([target, options]) => [target === page.html, options.attributeFilter]), [[true, ["class"]]]);
  page.inline.set("--app-browser-preferred", "480px");
  page.tokens["--app-menu-width"] = "202px";
  page.listeners.get("html:class")();
  assert.equal(page.attributes.get("aria-valuemax"), "378");
  assert.equal(page.attributes.get("aria-valuenow"), "378");
  // The column renders at the clamp (378): ArrowRight cannot move it and must not store 378.
  page.document.getElementById("app-browser").getBoundingClientRect = () => ({ left: 202, width: 378 });
  assert.equal(page.key("ArrowRight"), true);
  assert.equal(page.inline.get("--app-browser-preferred"), "480px");
  assert.equal(page.stored.size, 0);
  assert.equal(page.key("ArrowLeft"), true);
  assert.equal(page.inline.get("--app-browser-preferred"), "362px");
});

test("Reset removes the preference and its key", () => {
  const page = fakeDocument();
  mountBrowserResize(page.document);
  page.key("End");
  assert.equal(page.stored.get("dp-browser-width:acme"), "480");
  page.listeners.get("reset:click")();
  assert.equal(page.inline.has("--app-browser-preferred"), false);
  assert.equal(page.stored.size, 0);
  assert.equal(page.attributes.get("aria-valuenow"), "300");
});

test("a drag past the maximum stops at the maximum", () => {
  const page = fakeDocument({ viewport: 800 });
  mountBrowserResize(page.document);
  const pointer = { pointerId: 7, button: 0, preventDefault() {} };
  page.listeners.get("separator:pointerdown")(pointer);
  page.listeners.get("separator:pointermove")({ ...pointer, clientX: 202 + 900 });
  assert.equal(page.inline.get("--app-browser-preferred"), "278px");
  page.listeners.get("separator:pointermove")({ ...pointer, pointerId: 8, clientX: 202 + 250 });
  assert.equal(page.inline.get("--app-browser-preferred"), "278px", "another pointer moves nothing");
});

test("without a workspace the width applies but nothing is stored", () => {
  const page = fakeDocument({ workspace: null });
  mountBrowserResize(page.document);
  page.key("Home");
  assert.equal(page.inline.get("--app-browser-preferred"), `${BROWSER_MIN}px`);
  assert.equal(page.stored.size, 0);
});

test("a page without the browser mounts nothing", () => {
  const page = fakeDocument();
  page.document.getElementById = () => null;
  assert.doesNotThrow(() => mountBrowserResize(page.document));
  assert.equal(page.listeners.size, 0);
});

test("the cap and floor are the record's", () => {
  assert.equal(BROWSER_MIN, 240);
  assert.equal(BROWSER_MAX, 480);
});
