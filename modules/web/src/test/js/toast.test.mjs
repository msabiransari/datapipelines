// Toast lifecycle coverage for static/js/toast.js (028: datasources SPA table +
// toast notifications).
//
// Runs on Node's BUILT-IN runner (`node --test`), wired into Gradle by
// modules/web's `editorJsTest` task — same harness decision as the 027b editor
// tests: no packages, no config. toast.js is an IIFE that exports
// {attach, arm, dismiss} via module.exports when `window` is absent, which is
// exactly the node case; these tests drive the api with a hand-rolled fake DOM
// (classList, listeners, parent/child removal) and a fake MutationObserver —
// the two browser surfaces the module touches.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const toastPath = path.resolve(here, "../../main/resources/static/js/toast.js");

function loadToast() {
  delete require.cache[require.resolve(toastPath)];
  return require(toastPath);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Minimal element double: the four DOM surfaces toast.js uses. */
function fakeEl({ classes = ["ds-toast"], toasts = [], withClose = true } = {}) {
  const listeners = {};
  const closeListeners = [];
  const el = {
    nodeType: 1,
    children: [],
    parentNode: null,
    _classes: new Set(classes),
    _toasts: toasts,
    classList: {
      add: (c) => el._classes.add(c),
      contains: (c) => el._classes.has(c),
    },
    addEventListener: (type, fn) => (listeners[type] ||= []).push(fn),
    fire: (type) => (listeners[type] || []).forEach((fn) => fn()),
    querySelector: (sel) =>
      sel === ".ds-toast-close" && withClose
        ? { addEventListener: (t, fn) => closeListeners.push(fn) }
        : null,
    querySelectorAll: (sel) => (sel === ".ds-toast" ? el._toasts : []),
    removeChild(child) {
      child.parentNode = null;
      el.children = el.children.filter((c) => c !== child);
    },
    closeClick: () => closeListeners.forEach((fn) => fn()),
    closeListenerCount: () => closeListeners.length,
  };
  return el;
}

function adopt(stack, toast) {
  stack.children.push(toast);
  toast.parentNode = stack;
}

test("dismiss marks exiting and removes the node on animationend", () => {
  const api = loadToast();
  const stack = fakeEl({ classes: ["ds-toast-stack"] });
  const toast = fakeEl();
  adopt(stack, toast);

  api.dismiss(toast);

  assert.equal(toast._classes.has("exiting"), true);
  assert.equal(toast.parentNode, stack, "still present until the exit animation ends");
  toast.fire("animationend");
  assert.equal(toast.parentNode, null, "removed on animationend");
  assert.equal(stack.children.length, 0);
});

test("arm auto-dismisses after the configured delay", async () => {
  const api = loadToast();
  const stack = fakeEl({ classes: ["ds-toast-stack"] });
  const toast = fakeEl();
  adopt(stack, toast);

  api.arm(toast, 30);
  await sleep(90);

  assert.equal(toast._classes.has("exiting"), true, "exit started on the timer");
  toast.fire("animationend");
  assert.equal(toast.parentNode, null);
});

test("the close button dismisses immediately, ahead of the auto-dismiss timer", () => {
  const api = loadToast();
  const toast = fakeEl();

  api.arm(toast, 60_000); // would fire long after the test process exits
  toast.closeClick();

  assert.equal(toast._classes.has("exiting"), true, "dismissed by the click, not the timer");
});

test("arm is idempotent — one close listener, one timer, no double-arming", async () => {
  const api = loadToast();
  const stack = fakeEl({ classes: ["ds-toast-stack"] });
  const toast = fakeEl();
  adopt(stack, toast);

  api.arm(toast, 30);
  api.arm(toast, 30);

  assert.equal(toast.closeListenerCount(), 1);
  await sleep(90);
  assert.equal(toast._classes.has("exiting"), true, "the single timer still fires");
});

test("attach arms existing toasts and toasts added later through the observer", async () => {
  let observerCallback = null;
  globalThis.MutationObserver = class {
    constructor(cb) {
      observerCallback = cb;
    }
    observe() {}
  };
  const api = loadToast();
  const existing = fakeEl();
  const stack = fakeEl({ classes: ["ds-toast-stack"], toasts: [existing] });
  adopt(stack, existing);

  api.attach(stack, 30);

  // A later htmx beforeend swap: the observer sees the added .ds-toast node.
  const added = fakeEl();
  adopt(stack, added);
  observerCallback([{ addedNodes: [added] }]);

  await sleep(90);
  assert.equal(existing._classes.has("exiting"), true, "pre-existing toast armed at attach");
  assert.equal(added._classes.has("exiting"), true, "observer-armed toast dismissed too");
  delete globalThis.MutationObserver;
});

test("the observer ignores non-toast and non-element additions", async () => {
  let observerCallback = null;
  globalThis.MutationObserver = class {
    constructor(cb) {
      observerCallback = cb;
    }
    observe() {}
  };
  const api = loadToast();
  const stack = fakeEl({ classes: ["ds-toast-stack"] });
  api.attach(stack, 30);

  // A text node and a wrapper div carrying a toast inside (htmx may swap either).
  const wrapper = fakeEl({ classes: ["x"], toasts: [fakeEl()] });
  assert.doesNotThrow(() =>
    observerCallback([{ addedNodes: [{ nodeType: 3 }, wrapper] }]),
  );
  delete globalThis.MutationObserver;
});

test("bridgeErrors swaps an error response that retargets the toast stack", () => {
  const toast = loadToast();
  const listeners = {};
  const root = { addEventListener: (name, fn) => { listeners[name] = fn; } };
  toast.bridgeErrors(root);

  const detail = {
    shouldSwap: false,
    xhr: { status: 409, getResponseHeader: (h) => (h === "HX-Retarget" ? "#toast" : null) },
  };
  listeners["htmx:beforeSwap"]({ detail });
  assert.equal(detail.shouldSwap, true);
});

test("bridgeErrors leaves an error that does NOT retarget the stack alone", () => {
  const toast = loadToast();
  const listeners = {};
  toast.bridgeErrors({ addEventListener: (n, f) => { listeners[n] = f; } });

  const detail = { shouldSwap: false, xhr: { status: 500, getResponseHeader: () => null } };
  listeners["htmx:beforeSwap"]({ detail });
  assert.equal(detail.shouldSwap, false);
});

test("bridgeErrors never downgrades a successful swap", () => {
  const toast = loadToast();
  const listeners = {};
  toast.bridgeErrors({ addEventListener: (n, f) => { listeners[n] = f; } });

  const detail = { shouldSwap: true, xhr: { status: 200, getResponseHeader: () => "#toast" } };
  listeners["htmx:beforeSwap"]({ detail });
  assert.equal(detail.shouldSwap, true);
});

/** Element double built by the fake document: the surfaces show() touches. */
function fakeDocument() {
  return {
    createElement(tag) {
      const el = {
        tagName: tag.toUpperCase(),
        nodeType: 1,
        children: [],
        parentNode: null,
        textContent: "",
        _attrs: {},
        _classes: [],
        setAttribute(k, v) { el._attrs[k] = String(v); },
        getAttribute(k) { return k in el._attrs ? el._attrs[k] : null; },
        appendChild(child) { el.children.push(child); child.parentNode = el; return child; },
        removeChild(child) {
          child.parentNode = null;
          el.children = el.children.filter((c) => c !== child);
        },
        addEventListener() {},
        querySelector() { return null; },
        classList: {
          add(c) { if (!el._classes.includes(c)) el._classes.push(c); },
          contains(c) { return el._classes.includes(c); },
          get value() { return el._classes.join(" "); },
        },
      };
      Object.defineProperty(el, "className", {
        get() { return el._classes.join(" "); },
        set(v) { el._classes = v.split(" ").filter(Boolean); },
      });
      return el;
    },
    getElementById() { return null; },
  };
}

/** Stack double: fakeEl plus the appendChild show() delivers through. */
function fakeStack() {
  const stack = fakeEl({ classes: ["ds-toast-stack"] });
  stack.appendChild = (child) => {
    stack.children.push(child);
    child.parentNode = stack;
  };
  return stack;
}

test("show builds the server fragment's shape and appends it to the stack", () => {
  globalThis.document = fakeDocument();
  const toast = loadToast();
  const stack = fakeStack();
  toast.show("success", "Pipeline completed", "graph_fixture finished in 1.2s", stack);

  const el = stack.children[0];
  assert.deepEqual(el.classList.value.split(" "), ["ds-toast", "ds-toast-success"]);
  assert.equal(el.getAttribute("role"), "status");
  // Child ORDER matters — it is the server fragment's order (partials/toast.html:9-11).
  assert.deepEqual(el.children.map((c) => c.className),
    ["ds-toast-close", "ds-toast-title", "ds-toast-body"]);
  assert.equal(el.children[1].textContent, "Pipeline completed");
  delete globalThis.document;
});

test("show escapes by construction — title and body are never parsed as markup", () => {
  globalThis.document = fakeDocument();
  const toast = loadToast();
  const stack = fakeStack();
  toast.show("danger", "<img src=x onerror=alert(1)>", "<b>no</b>", stack);

  // textContent, never innerHTML: the values carry abort reasons and node ids.
  assert.equal(stack.children[0].children[1].textContent, "<img src=x onerror=alert(1)>");
  assert.equal(stack.children[0].children[1].children.length, 0);
  delete globalThis.document;
});

test("show is inert when the stack is absent", () => {
  delete globalThis.document;
  assert.doesNotThrow(() => loadToast().show("info", "t", "m", null));
});

test("an unknown variant falls back to info rather than emitting a dead class", () => {
  globalThis.document = fakeDocument();
  const toast = loadToast();
  const stack = fakeStack();
  toast.show("purple", "t", "m", stack);
  assert.ok(stack.children[0].classList.value.includes("ds-toast-info"));
  delete globalThis.document;
});
