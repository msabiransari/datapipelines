// #556 — static/js/overview.mjs, the family overview's Copy prompt: the textarea's CURRENT text
// (edits included) goes to the clipboard and nowhere else; a resolved write relabels the button
// "Copied" and restores it; a refused or absent clipboard selects the text, relabels the button
// "Select and copy" and says how to finish, without throwing. A minimal DOM stand-in: the module
// touches the click target's closest()/querySelector(), the field's value/focus/select and the
// button's textContent/attribute. Every network door is a recorder that must stay empty.

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

test("no path made a request", () => {
  assert.deepEqual(requests, []);
});
