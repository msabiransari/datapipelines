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
    inspector: { open: false },
    closeNodeDetails() {},
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

// 065 §B/§C rewrote the Escape ladder: the dock has NO close and Escape is a
// no-op on it, so the order is modal → inspector → nothing. The middle rung
// (ResultPanel.hide) is gone on purpose — losing the results to the key that
// closes the panel above them is the defect this round exists to remove.
test("Escape closes topmost-first: modal, then the inspector, then nothing — never the dock", () => {
  const { window, dom } = loadA11y();
  let dockTouched = 0;
  let closes = 0;
  const editor = editorWith(["a"], {
    errorModal: { visible: true, message: "boom" },
    inspector: { open: true },
    dock: {
      state: "open",
      minimise() {
        dockTouched += 1;
      },
    },
    closeNodeDetails() {
      closes += 1;
      editor.inspector.open = false;
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
  assert.equal(closes, 0, "one surface per press");

  esc();
  assert.equal(closes, 1, "the inspector is the next rung");
  assert.equal(editor.inspector.open, false);

  const third = esc();
  assert.equal(third.prevented, false, "with nothing open, Escape is not consumed");
  assert.equal(editor.dock.state, "open", "Escape never touches the dock");
  assert.equal(dockTouched, 0, "…and never calls minimise on it");
});

// 065 §C — the focus race the live DOM check found (2026-09-04, demo stack): the
// list mirror focuses the selected row, the inspector focuses its close button, and
// with both firing in one turn the hidden <li> won at 2 of 3 zoom levels. The open
// path passes moveFocus=false; the tabindex still moves, only focus() is withheld.
// A keyboard user is the only one who meets this, which is why it needs a test and
// not a comment.
test("a11ySyncNode withholds focus when asked, but still moves the roving tabindex", () => {
  const { window, dom } = loadA11y();
  window.setupA11y(editorWith(["a", "b"]));
  const [first, second] = dom.children;

  window.a11ySyncNode("b", false);
  assert.equal(second.getAttribute("aria-selected"), "true");
  assert.equal(second.getAttribute("tabindex"), "0", "the roving tabindex still follows the selection");
  assert.equal(first.getAttribute("tabindex"), "-1");
  assert.equal(second.focused, false, "…but focus stays where the caller put it — the panel is taking it");

  // The default is unchanged: a plain selection still carries focus to the row.
  window.a11ySyncNode("a");
  assert.equal(first.focused, true);
});

// 065 §C keyboard parity: the node list's Enter/Space is the card button's twin —
// it OPENS the inspector, and hands it the row as the focus-return element. A
// plain click on the row still only selects.
test("Enter on a node row opens the inspector and offers the row as the focus return point", () => {
  const { window, dom } = loadA11y();
  const opened = [];
  const editor = editorWith(["a", "b"], {
    openNodeDetails(id, trigger) {
      opened.push([id, trigger]);
    },
    selectNodeById() {
      throw new Error("Enter must OPEN, not merely select");
    },
  });
  window.setupA11y(editor);
  const [first] = dom.children;
  first.fire("keydown", key("Enter"));
  assert.equal(opened.length, 1);
  assert.equal(opened[0][0], "a");
  assert.equal(opened[0][1], first, "the row itself is the focus return element");
});
