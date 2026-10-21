// #622 — delegated clicks work for the nearest card, once per document, without network.
import test from "node:test";
import assert from "node:assert/strict";

const listeners = [], writes = [], requests = [], selected = [];
globalThis.document = { addEventListener: (type, listener) => listeners.push({ type, listener }) };
Object.defineProperty(globalThis, "navigator", { configurable: true, value: { clipboard: { writeText: async text => writes.push(text) } } });
globalThis.fetch = (...args) => { requests.push(args); return Promise.resolve(); };
globalThis.XMLHttpRequest = function () { requests.push("xhr"); };
globalThis.getSelection = () => ({ selectAllChildren: pre => selected.push(pre) });
const cardModule = await import("../../main/resources/static/js/mcp-connection.mjs");
await import("../../main/resources/static/js/mcp-connection.mjs?duplicate");

function card(text) {
  const pre = { textContent: text };
  const status = { textContent: "" };
  const root = { querySelector: selector => selector === "pre" ? pre : selector === "[data-mcp-copy-status]" ? status : null };
  const button = {
    textContent: "Copy config",
    getAttribute: () => "Copy config",
    closest: selector => selector === "[data-mcp-copy]" ? button : selector === "[data-mcp-connection]" ? root : null,
  };
  const child = { closest: selector => selector === "[data-mcp-copy]" ? button : null };
  return { pre, status, button, child };
}

test("the global guard binds one delegated click listener even on a second evaluation", () => {
  assert.equal(listeners.length, 1);
  assert.equal(listeners[0].type, "click");
});

test("a nested button target copies the nearest card's pre textContent, with config wording", async () => {
  const first = card("first"), second = card('{\n "key": "dpk_…"\n}');
  await listeners[0].listener({ target: second.child });
  assert.equal(writes.at(-1), second.pre.textContent);
  assert.equal(second.button.textContent, "Copied");
  assert.equal(second.status.textContent, "Config copied to the clipboard.");
  assert.equal(first.button.textContent, "Copy config");
});

test("other clicks and incomplete cards do nothing", async () => {
  const count = writes.length;
  for (const target of [null, {}, { closest: () => null }, { closest: () => ({ closest: () => null }) }]) {
    assert.equal(cardModule.configParts(target), null);
    await listeners[0].listener({ target });
  }
  assert.equal(writes.length, count);
});

test("refused or absent clipboard selects the pre and explains manual copying", async () => {
  for (const clipboard of [undefined, { writeText: async () => { throw new Error("denied"); } }]) {
    navigator.clipboard = clipboard;
    const parts = card("placeholder config");
    await listeners[0].listener({ target: parts.button });
    assert.equal(selected.at(-1), parts.pre);
    assert.equal(parts.button.textContent, "Select and copy");
    assert.equal(parts.status.textContent, "Copy is not available here. The config is selected: press Ctrl+C (⌘C on a Mac) to copy it.");
  }
  assert.deepEqual(requests, []);
});
