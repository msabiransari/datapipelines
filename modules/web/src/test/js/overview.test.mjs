// #556 — static/js/overview.mjs, the family overview's Copy prompt: the textarea's CURRENT text
// (edits included) goes to the clipboard and nowhere else; a resolved write relabels the button
// "Copied" and restores it; a refused or absent clipboard selects the text, relabels the button
// "Select and copy" and says how to finish, without throwing. A minimal DOM stand-in: the module
// touches the click target's closest()/querySelector(), the field's value/focus/select and the
// button's textContent/attribute. Every network door is a recorder that must stay empty.
//
// #home-frame adds the page's one dialog (open by key, title as text, the table twin borrowed and
// given back, focus returned on close, backdrop and close-button clicks), the question field (it
// fills [question] by index, never over a reader's edit) and Reset, over a small tree stand-in
// (FakeNode) whose selectors are the module's: `[attr]` and a tag name.

import test from "node:test";
import assert from "node:assert/strict";

const requests = [];
globalThis.fetch = (...args) => { requests.push(["fetch", args]); return Promise.reject(new Error("no network")); };
globalThis.XMLHttpRequest = function XMLHttpRequest() { requests.push(["xhr"]); };
const listeners = [];
globalThis.document = { addEventListener(type, listener) { listeners.push({ type, listener }); } };

const clipboard = { writes: [], refuse: false };
clipboard.writeText = async text => {
  if (clipboard.refuse) throw new DOMException("denied", "NotAllowedError");
  clipboard.writes.push(text);
};
Object.defineProperty(globalThis, "navigator", { value: { clipboard }, configurable: true });

const overview = await import("../../main/resources/static/js/overview.mjs");
// A second import is the same module: the document listener is registered once.
await import("../../main/resources/static/js/overview.mjs");

function parts(value = "In workspace acme, build a draft pipeline") {
  const status = { textContent: "" };
  const field = { value, focused: false, selected: false, focus() { this.focused = true; }, select() { this.selected = true; } };
  const root = { querySelector: selector => (selector === "textarea" ? field : selector === "[data-overview-copy-status]" ? status : null) };
  const button = {
    textContent: "Copy prompt",
    getAttribute: name => (name === "data-copy-label" ? "Copy prompt" : null),
    closest: selector => (selector === "[data-overview-prompt]" ? root : selector === "[data-overview-copy]" ? button : null),
  };
  return { field, button, status };
}

test("a resolved write copies the current text, says Copied, then restores the label", async () => {
  const { field, button, status } = parts();
  field.value = "In workspace acme, build a draft pipeline to answer: which borough grew fastest?";
  const scheduled = [];

  const outcome = await overview.copyPrompt(field, button, status, clipboard, (run, ms) => scheduled.push({ run, ms }));

  assert.equal(outcome, "copied");
  assert.equal(clipboard.writes.at(-1), field.value);
  assert.equal(button.textContent, "Copied");
  assert.equal(status.textContent, overview.COPIED_STATUS);
  assert.equal(scheduled.length, 1);
  assert.equal(scheduled[0].ms, overview.COPIED_MS);
  scheduled[0].run();
  assert.equal(button.textContent, "Copy prompt");
  assert.equal(field.selected, false);
});

test("a refused write selects the text and says how to copy it by hand, without throwing", async () => {
  const { field, button, status } = parts();
  clipboard.refuse = true;
  try {
    const outcome = await overview.copyPrompt(field, button, status, clipboard, () => assert.fail("no restore on refusal"));
    assert.equal(outcome, "manual");
  } finally {
    clipboard.refuse = false;
  }
  assert.equal(field.focused, true);
  assert.equal(field.selected, true);
  assert.equal(button.textContent, overview.MANUAL_LABEL);
  assert.equal(status.textContent, overview.MANUAL_STATUS);
});

test("an absent clipboard (a non-secure origin) is the same manual path", async () => {
  for (const missing of [undefined, null, {}]) {
    const { field, button, status } = parts();
    assert.equal(await overview.copyPrompt(field, button, status, missing), "manual");
    assert.equal(field.selected, true);
    assert.equal(button.textContent, overview.MANUAL_LABEL);
  }
});

test("a click on Copy prompt copies through the document listener; any other click does nothing", async () => {
  const clicks = listeners.filter(entry => entry.type === "click");
  assert.equal(clicks.length, 1);
  const { field, button, status } = parts("edited by the reader");
  const before = clipboard.writes.length;

  clicks[0].listener({ target: { closest: () => null } });
  clicks[0].listener({ target: button });
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(clipboard.writes.length, before + 1);
  assert.equal(clipboard.writes.at(-1), "edited by the reader");
  assert.equal(status.textContent, overview.COPIED_STATUS);
  assert.equal(field.selected, false);
});

test("promptParts answers null outside a prompt", () => {
  assert.equal(overview.promptParts(null), null);
  assert.equal(overview.promptParts({}), null);
  assert.equal(overview.promptParts({ closest: () => null }), null);
});

/** A tree node whose closest/querySelector/matches understand `[attr]` and a tag name, nothing else. */
class FakeNode {
  constructor(tag, attrs = {}, children = []) {
    this.tag = tag;
    this.attrs = { ...attrs };
    this.children = [];
    this.parentNode = null;
    this.hidden = "hidden" in attrs;
    this.textContent = "";
    this.classes = new Set((attrs.class || "").split(" ").filter(Boolean));
    this.classList = { add: c => this.classes.add(c), remove: c => this.classes.delete(c), contains: c => this.classes.has(c) };
    this.listeners = {};
    children.forEach(child => this.append(child));
  }
  getAttribute(name) { return name in this.attrs ? this.attrs[name] : null; }
  matches(selector) { return selector.startsWith("[") ? selector.slice(1, -1) in this.attrs : selector === this.tag; }
  closest(selector) { return this.matches(selector) ? this : this.parentNode?.closest(selector) ?? null; }
  querySelectorAll(selector) { return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  append(child) { child.parentNode?.remove(child); child.parentNode = this; this.children.push(child); }
  remove(child) { this.children = this.children.filter(c => c !== child); child.parentNode = null; }
  insertBefore(child, next) {
    child.parentNode?.remove(child);
    child.parentNode = this;
    const at = next ? this.children.indexOf(next) : -1;
    if (at < 0) this.children.push(child); else this.children.splice(at, 0, child);
  }
  get nextSibling() { const siblings = this.parentNode?.children ?? []; return siblings[siblings.indexOf(this) + 1] ?? null; }
  get isConnected() { return this.parentNode ? this.parentNode.isConnected : this.tag === "#root"; }
  addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
  focus() { focused.node = this; }
}

const focused = { node: null };

/** The overview's dialog stand-in: showModal/close flip `open` and fire `close` like the platform's. */
class FakeDialog extends FakeNode {
  constructor(attrs, children) { super("dialog", attrs, children); this.open = false; this.box = { left: 100, right: 500, top: 100, bottom: 400 }; }
  showModal() { this.open = true; }
  close() { if (!this.open) return; this.open = false; (this.listeners.close || []).forEach(listener => listener()); }
  getBoundingClientRect() { return this.box; }
}

/** One overview page: a strip opener, the activity panel with its table twin, and the dialog with three sections. */
function overviewPage() {
  const table = new FakeNode("div", { "data-table-home": "activity", class: "dt-frame app-visually-hidden" }, [new FakeNode("table")]);
  const viewData = new FakeNode("button", { "data-open": "activity" });
  const definitions = new FakeNode("button", { "data-open": "definitions" });
  const panel = new FakeNode("section", { "data-overview-panel": "activity" }, [viewData, table]);
  const title = new FakeNode("h2", { "data-overview-dialog-title": "" });
  const close = new FakeNode("button", { "data-overview-dialog-close": "" });
  const slot = new FakeNode("div", { "data-dialog-table": "activity" });
  const sections = [
    new FakeNode("section", { "data-dialog-section": "definitions", "data-title": "How the summary is counted", hidden: "" }),
    new FakeNode("section", { "data-dialog-section": "activity", "data-title": "Development activity · 12 weeks", hidden: "" }, [slot]),
    new FakeNode("div", { "data-dialog-section": "prompt", "data-title": "<b>Start</b>", hidden: "" }),
  ];
  const dialog = new FakeDialog({ "data-overview-dialog": "" }, [title, close, ...sections]);
  const root = new FakeNode("#root", {}, [new FakeNode("div", { "data-overview": "pipelines" }, [definitions, panel, dialog])]);
  return { root, table, panel, viewData, definitions, title, close, slot, sections, dialog };
}

const click = target => listeners.find(entry => entry.type === "click").listener({ target, clientX: 300, clientY: 200 });

test("fill substitutes the first [question] by index, as text; a blank answer keeps the placeholder", () => {
  const template = "answer: [question]. Then [question] again.";
  assert.equal(overview.fill(template, "rainy days"), "answer: rainy days. Then [question] again.");
  assert.equal(overview.fill(template, "   "), template);
  assert.equal(overview.fill("no placeholder", "x"), "no placeholder");
  // A replacement pattern and markup are words, not instructions.
  assert.equal(overview.fill(template, "$& <img src=x onerror=alert(1)>"), "answer: $& <img src=x onerror=alert(1)>. Then [question] again.");
});

test("the question composes the server text until the reader edits the prompt, and Reset restores both", () => {
  const server = "build a draft pipeline to answer: [question].";
  const field = new FakeNode("textarea");
  field.defaultValue = server;
  field.value = server;
  const question = new FakeNode("input", { "data-overview-question": "" });
  const status = new FakeNode("span", { "data-overview-copy-status": "" });
  const copy = new FakeNode("button", { "data-overview-copy": "", "data-copy-label": "Copy prompt" });
  const reset = new FakeNode("button", { "data-overview-reset": "" });
  new FakeNode("div", { "data-overview-prompt": "" }, [question, field, copy, reset, status]);
  const input = listeners.find(entry => entry.type === "input").listener;

  question.value = "rainy days";
  input({ target: question });
  assert.equal(field.value, "build a draft pipeline to answer: rainy days.");
  question.value = "dry days";
  input({ target: question });
  assert.equal(field.value, "build a draft pipeline to answer: dry days.", "the field recomposes from the SERVER text, not its last fill");

  field.value = "my own words";
  question.value = "wet days";
  assert.equal(overview.compose(field, question), false);
  input({ target: question });
  assert.equal(field.value, "my own words", "an edited prompt is never overwritten by typing");

  status.textContent = overview.COPIED_STATUS;
  copy.textContent = "Copied";
  click(reset);
  assert.equal(field.value, server);
  assert.equal(question.value, "");
  assert.equal(status.textContent, "");
  assert.equal(copy.textContent, "Copy prompt");
  question.value = "snow";
  input({ target: question });
  assert.equal(field.value, "build a draft pipeline to answer: snow.", "after Reset the field composes again");
});

test("an opener shows its section by key with its title as text, and close returns focus to the opener", () => {
  const { definitions, title, sections, dialog } = overviewPage();

  click(definitions);
  assert.equal(dialog.open, true);
  assert.equal(title.textContent, "How the summary is counted");
  assert.deepEqual(sections.map(section => section.hidden), [false, true, true]);

  dialog.close(); // Escape closes a modal dialog natively; the platform then fires `close`.
  assert.equal(focused.node, definitions);

  const page = overviewPage();
  assert.equal(overview.openSection(page.dialog, "prompt", null), true);
  assert.equal(page.title.textContent, "<b>Start</b>", "the title is set as text, markup stays words");
  assert.equal(overview.openSection(page.dialog, "missing", null), false);
});

test("View data borrows the panel's table twin while open and gives it back hidden, so the page holds one table", () => {
  const { root, table, panel, viewData, slot, dialog } = overviewPage();

  click(viewData);
  assert.equal(table.parentNode, slot);
  assert.equal(table.classList.contains("app-visually-hidden"), false);
  assert.equal(root.querySelectorAll("table").length, 1);

  dialog.close();
  assert.equal(table.parentNode, panel);
  assert.equal(panel.children.indexOf(table), 1, "the table returns to its own place");
  assert.equal(table.classList.contains("app-visually-hidden"), true);
  assert.equal(root.querySelectorAll("table").length, 1);
  assert.equal(focused.node, viewData);
});

test("a backdrop click closes the dialog, a click inside its box or on its content does not, the close button does", () => {
  const { definitions, close, sections, dialog } = overviewPage();
  const listener = listeners.find(entry => entry.type === "click").listener;

  click(definitions);
  listener({ target: dialog, clientX: 300, clientY: 200 });
  assert.equal(dialog.open, true, "a click inside the box (padding) keeps it open");
  listener({ target: sections[0], clientX: 300, clientY: 200 });
  assert.equal(dialog.open, true, "a click on the content keeps it open");
  listener({ target: dialog, clientX: 20, clientY: 200 });
  assert.equal(dialog.open, false, "a click outside the box is the backdrop");

  click(definitions);
  click(close);
  assert.equal(dialog.open, false);
  assert.equal(overview.isBackdropClick(dialog, { target: sections[0], clientX: 0, clientY: 0 }), false);
});

test("the dialog's close listener is bound once however often it opens", () => {
  const { definitions, dialog } = overviewPage();
  click(definitions);
  dialog.close();
  click(definitions);
  dialog.close();
  assert.equal(dialog.listeners.close.length, 1);
});

test("no path made a request", () => {
  assert.deepEqual(requests, []);
});
