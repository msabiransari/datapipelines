import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import {
  browserMaximum, effectiveWidth, keyedWidth, mountBrowserResize, parsePreference,
} from "../../main/resources/static/js/browser-resize.mjs";

/* #788 — app.css is the range's ONE source, so this suite does not restate it: the `:root`
   block is parsed from the stylesheet itself and every case below is expressed relative to
   the values it holds (a plant that moves a bound re-derives the whole file). */
const here = path.dirname(fileURLToPath(import.meta.url));
const css = readFileSync(path.join(here, "../../main/resources/static/css/app.css"), "utf8");
// app.css has several `:root` blocks (and the width bands re-declare the default inside
// media rules); the shell's block is the one that declares the range itself.
const rootBlock = [...css.matchAll(/:root\s*\{([^}]*)\}/g)].map(m => m[1])
  .find(block => block.includes("--app-browser-min:"));
assert.ok(rootBlock, "app.css must declare --app-browser-min in a :root block — it is the range's one source");
const declared = name => {
  const hit = rootBlock.match(new RegExp(`${name}:\\s*(\\d+(?:\\.\\d+)?)px`));
  assert.ok(hit, `app.css's :root must declare ${name} in px — it is the range's one source`);
  return Number(hit[1]);
};
const MIN = declared("--app-browser-min");
const MAX = declared("--app-browser-max");
const BASE_DEFAULT = declared("--app-browser-default");
const RANGE = { min: MIN, max: MAX };
// The suite's steps walk from the middle out; a range narrower than two steps cannot host them.
assert.ok(MIN > 0 && MAX > MIN + 32, `the parsed range must be a usable one, read: ${MIN}–${MAX}`);
const MID = Math.round((MIN + MAX) / 2);

// What <main> keeps beside the browser — its own token (app.css's --app-main-min-width),
// a fixed input to the maximum arithmetic, not part of the browser's range.
const MAIN_MIN = 320;

test("the maximum leaves main its minimum beside the menu, within the stylesheet's range", () => {
  // At 1512 the viewport leaves 1512 - 202 - 320 = 918, more than the ceiling allows.
  assert.equal(browserMaximum(1512, 202, MAIN_MIN, RANGE), Math.min(MAX, 918));
  assert.equal(browserMaximum(900, 64, MAIN_MIN, RANGE), Math.min(MAX, 516));
  // The floor wins over 800 - 202 - 320 = 278 whenever the range's minimum is the larger.
  assert.equal(browserMaximum(800, 202, MAIN_MIN, RANGE), Math.max(MIN, 278));
  assert.equal(browserMaximum(700, 202, MAIN_MIN, RANGE), MIN);
});

test("a viewport clamp changes the effective width, never below the minimum", () => {
  // The floor wins over a 278 clamp: the column takes the minimum and main gives 2px.
  assert.equal(effectiveWidth(MAX, 278, RANGE), Math.max(MIN, 278));
  assert.equal(effectiveWidth(MID, MAX, RANGE), MID);
  assert.equal(effectiveWidth(1, MAX, RANGE), MIN);
});

test("only a whole number of pixels within the range is a preference", () => {
  assert.equal(parsePreference(String(MIN), RANGE), MIN);
  assert.equal(parsePreference(String(MID), RANGE), MID);
  assert.equal(parsePreference(`${MAX}px`, RANGE), MAX);
  assert.equal(parsePreference(` ${MID} `, RANGE), MID);
  // 256 was a preference under the old 240 floor; #784's raised floor retired it and rail.js
  // removes it on sight — kept as the named history case the issue pins.
  assert.equal(parsePreference("256", RANGE), null);
  for (const raw of [null, undefined, "", String(MIN - 1), String(MAX + 1), "2000", "300.5", "-300", "1e3", "300em", "calc(300px)", "<b>"]) {
    assert.equal(parsePreference(raw, RANGE), null, String(raw));
  }
});

test("arrows step 16 within the range; Home and End go to its ends; other keys do nothing", () => {
  assert.equal(keyedWidth("ArrowRight", MID, MAX, RANGE), MID + 16);
  assert.equal(keyedWidth("ArrowRight", MAX - 16, MAX, RANGE), MAX);
  assert.equal(keyedWidth("ArrowLeft", MID, MAX, RANGE), MID - 16);
  assert.equal(keyedWidth("ArrowLeft", MIN - 5, MAX, RANGE), MIN);
  assert.equal(keyedWidth("Home", MID, MAX, RANGE), MIN);
  // End clamps to the effective maximum (278); the stylesheet's clamp lifts it to the floor.
  assert.equal(keyedWidth("End", MID, Math.max(MIN, 278), RANGE), Math.max(MIN, 278));
  for (const key of ["Enter", " ", "ArrowUp", "Tab"]) assert.equal(keyedWidth(key, MID, MAX, RANGE), null);
});

/* A document with just the parts mountBrowserResize reads: <html>'s inline style and computed
   tokens, the two ids, the reset button, and a recording localStorage. The tokens come from
   the same parsed stylesheet values every pure-function case above uses. */
function fakeDocument({ workspace = "acme", viewport = 1512, menu = 202, browserWidth = MID, bounds = true } = {}) {
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
  const tokens = {
    "--app-menu-width": `${menu}px`,
    "--app-main-min-width": `${MAIN_MIN}px`,
    "--app-browser-default": `${BASE_DEFAULT}px`,
    ...(bounds ? { "--app-browser-min": `${MIN}px`, "--app-browser-max": `${MAX}px` } : {}),
  };
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
  assert.equal(page.attributes.get("aria-valuemin"), String(MIN));
  assert.equal(page.attributes.get("aria-valuemax"), String(Math.min(MAX, 1512 - 202 - MAIN_MIN)));
  assert.equal(page.attributes.get("aria-valuenow"), String(Math.max(MIN, Math.min(BASE_DEFAULT, MAX))));
});

test("a key writes the preference to <html> and to this workspace's key, and nothing else", () => {
  const page = fakeDocument();
  mountBrowserResize(page.document);
  assert.equal(page.key("ArrowRight"), true);
  assert.equal(page.inline.get("--app-browser-preferred"), `${MID + 16}px`);
  assert.deepEqual([...page.stored], [[`dp-browser-width:acme`, String(MID + 16)]]);
  assert.equal(page.attributes.get("aria-valuenow"), String(MID + 16));
  assert.equal(page.key("Enter"), false);
  assert.deepEqual([...page.stored], [[`dp-browser-width:acme`, String(MID + 16)]]);
});

test("a narrow window lowers the maximum but never rewrites the stored preference", () => {
  const page = fakeDocument({ viewport: 800 });
  mountBrowserResize(page.document);
  page.inline.set("--app-browser-preferred", `${MAX}px`);
  page.listeners.get("window:resize")();
  const narrowMax = Math.max(MIN, 800 - 202 - MAIN_MIN);
  assert.equal(page.attributes.get("aria-valuemax"), String(narrowMax));
  assert.equal(page.attributes.get("aria-valuenow"), String(narrowMax));
  assert.equal(page.inline.get("--app-browser-preferred"), `${MAX}px`);
  assert.equal(page.stored.size, 0);
});

test("the menu's collapse moves the range, and an arrow at the clamp writes nothing", () => {
  const page = fakeDocument({ viewport: 900, menu: 64, browserWidth: MAX });
  mountBrowserResize(page.document);
  assert.deepEqual(page.observed.map(([target, options]) => [target === page.html, options.attributeFilter]), [[true, ["class"]]]);
  page.inline.set("--app-browser-preferred", `${MAX}px`);
  page.tokens["--app-menu-width"] = "202px";
  page.listeners.get("html:class")();
  const collapsedMax = Math.max(MIN, 900 - 202 - MAIN_MIN);
  assert.equal(page.attributes.get("aria-valuemax"), String(collapsedMax));
  assert.equal(page.attributes.get("aria-valuenow"), String(collapsedMax));
  // The column renders at the clamp: ArrowRight cannot move it and must not store the clamp.
  page.document.getElementById("app-browser").getBoundingClientRect = () => ({ left: 202, width: collapsedMax });
  assert.equal(page.key("ArrowRight"), true);
  assert.equal(page.inline.get("--app-browser-preferred"), `${MAX}px`);
  assert.equal(page.stored.size, 0);
  assert.equal(page.key("ArrowLeft"), true);
  assert.equal(page.inline.get("--app-browser-preferred"), `${collapsedMax - 16}px`);
});

test("Reset removes the preference and its key", () => {
  const page = fakeDocument();
  mountBrowserResize(page.document);
  page.key("End");
  assert.equal(page.stored.get("dp-browser-width:acme"), String(Math.min(MAX, 1512 - 202 - MAIN_MIN)));
  page.listeners.get("reset:click")();
  assert.equal(page.inline.has("--app-browser-preferred"), false);
  assert.equal(page.stored.size, 0);
  assert.equal(page.attributes.get("aria-valuenow"), String(Math.max(MIN, Math.min(BASE_DEFAULT, MAX))));
});

test("a drag past the maximum stops at the maximum", () => {
  const page = fakeDocument({ viewport: 800 });
  mountBrowserResize(page.document);
  const pointer = { pointerId: 7, button: 0, preventDefault() {} };
  page.listeners.get("separator:pointerdown")(pointer);
  page.listeners.get("separator:pointermove")({ ...pointer, clientX: 202 + 900 });
  assert.equal(page.inline.get("--app-browser-preferred"), `${Math.max(MIN, 800 - 202 - MAIN_MIN)}px`);
  page.listeners.get("separator:pointermove")({ ...pointer, pointerId: 8, clientX: 202 + 250 });
  assert.equal(page.inline.get("--app-browser-preferred"), `${Math.max(MIN, 800 - 202 - MAIN_MIN)}px`, "another pointer moves nothing");
});

test("without a workspace the width applies but nothing is stored", () => {
  const page = fakeDocument({ workspace: null });
  mountBrowserResize(page.document);
  page.key("Home");
  assert.equal(page.inline.get("--app-browser-preferred"), `${MIN}px`);
  assert.equal(page.stored.size, 0);
});

test("a page without the browser mounts nothing", () => {
  const page = fakeDocument();
  page.document.getElementById = () => null;
  assert.doesNotThrow(() => mountBrowserResize(page.document));
  assert.equal(page.listeners.size, 0);
});

test("a sheet that does not resolve the range leaves the control unmounted, loudly", () => {
  const errors = [];
  const page = fakeDocument({ bounds: false });
  const original = console.error;
  console.error = message => errors.push(String(message));
  try {
    mountBrowserResize(page.document);
  } finally {
    console.error = original;
  }
  assert.equal(page.listeners.size, 0, "no control is wired without the stylesheet's bounds");
  assert.equal(errors.filter(message => message.includes("--app-browser-min")).length, 1, "the refusal is visible in the console");
});

test("the range is the stylesheet's, read positive and ordered", () => {
  assert.equal(MIN > 0, true, `--app-browser-min read ${MIN}`);
  assert.equal(MAX > MIN, true, `--app-browser-max read ${MAX}, min ${MIN}`);
  assert.equal(BASE_DEFAULT > 0, true, `--app-browser-default read ${BASE_DEFAULT}`);
});
