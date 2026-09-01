// Keyboard-navigation coverage for static/js/pipeline-editor/a11y.js (034 F1):
// Home/End on the node list (the WAI listbox pattern) and the Escape close order —
// §14.1 documented both for two rounds while the code implemented neither.
//
// Runs on Node's BUILT-IN runner (`node --test`), wired into Gradle by
// modules/web's `editorJsTest` task — same harness decision as the other editor
// tests: no packages, no config. a11y.js is an IIFE publishing window.*, so the
// tests install a hand-rolled fake `window`/`document` before requiring it.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const a11yPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/a11y.js");

function fakeElement() {
  const listeners = {};
  const attrs = {};
  const el = {
    attrs,
    focused: false,
    parentNode: null,
    nextElementSibling: null,
    previousElementSibling: null,
    className: "",
    textContent: "",
    setAttribute: (k, v) => {
      attrs[k] = String(v);
    },
    getAttribute: (k) => (k in attrs ? attrs[k] : null),
    addEventListener: (type, fn) => (listeners[type] ||= []).push(fn),
    fire: (type, event) => (listeners[type] || []).forEach((fn) => fn.call(el, event)),
    focus: () => {
      el.focused = true;
    },
  };
  return el;
}

function fakeDom() {
  const docListeners = {};
  const children = [];
  const relink = () =>
    children.forEach((c, i) => {
      c.parentNode = list;
      c.nextElementSibling = children[i + 1] || null;
      c.previousElementSibling = children[i - 1] || null;
    });
  const list = {
    children,
    set innerHTML(_v) {
      children.length = 0;
    },
    appendChild(c) {
      children.push(c);
      relink();
    },
    querySelectorAll: (sel) => (sel === '[role="option"]' ? [...children] : []),
  };
  const documentShim = {
    createElement: () => fakeElement(),
    getElementById: (id) => (id === "pe-node-list" ? list : null),
    addEventListener: (type, fn) => (docListeners[type] ||= []).push(fn),
    fire: (type, event) => (docListeners[type] || []).forEach((fn) => fn(event)),
  };
  return { list, children, documentShim };
}

function key(k) {
  return {
    key: k,
    prevented: false,
    preventDefault() {
      this.prevented = true;
    },
  };
}

function loadA11y() {
  const dom = fakeDom();
  delete require.cache[require.resolve(a11yPath)];
  globalThis.window = {};
  globalThis.document = dom.documentShim;
  require(a11yPath);
  return { window: globalThis.window, dom };
}

function editorWith(nodes, over = {}) {
  return {
    nodes: nodes.map((id) => ({ id })),
    errorModal: { visible: false, message: "" },
    resultPanel: { visible: false },
    resultPanelInstance: null,
    selectedNode: null,
    ...over,
  };
}

test("Home and End move focus and the roving tabindex to the first / last option", () => {
  const { window, dom } = loadA11y();
  window.setupA11y(editorWith(["a", "b", "c"]));
  const [first, , last] = dom.children;

  first.fire("keydown", key("End"));
  assert.equal(last.focused, true);
  assert.equal(last.getAttribute("tabindex"), "0");
  assert.equal(first.getAttribute("tabindex"), "-1");

  last.fire("keydown", key("Home"));
  assert.equal(first.focused, true);
  assert.equal(first.getAttribute("tabindex"), "0");
  assert.equal(last.getAttribute("tabindex"), "-1");
});

test("the arrows still move one option at a time", () => {
  const { window, dom } = loadA11y();
  window.setupA11y(editorWith(["a", "b", "c"]));
  const [first, second] = dom.children;

  first.fire("keydown", key("ArrowDown"));
  assert.equal(second.focused, true);
  assert.equal(first.getAttribute("tabindex"), "-1");
});

test("Escape closes topmost-first: modal, then result panel, then details, then nothing", () => {
  const { window, dom } = loadA11y();
  let resultHides = 0;
  const editor = editorWith(["a"], {
    errorModal: { visible: true, message: "boom" },
    resultPanel: { visible: true },
    resultPanelInstance: {
      hide() {
        resultHides += 1;
        editor.resultPanel.visible = false;
      },
    },
    selectedNode: { id: "a" },
  });
  window.setupA11y(editor);

  const esc = () => {
    const e = key("Escape");
    dom.documentShim.fire("keydown", e);
    return e;
  };

  esc();
  assert.equal(editor.errorModal.visible, false, "the modal is topmost");
  assert.equal(resultHides, 0, "one surface per press");

  esc();
  assert.equal(resultHides, 1);
  assert.notEqual(editor.selectedNode, null, "details still open");

  esc();
  assert.equal(editor.selectedNode, null);

  const fourth = esc();
  assert.equal(fourth.prevented, false, "with nothing open, Escape is not consumed");
});
