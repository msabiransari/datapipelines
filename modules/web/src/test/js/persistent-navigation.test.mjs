// #465 — static/js/persistent-navigation.mjs, the prepared in-shell navigation: which clicks it
// takes over, the busy signal it raises for the shell while the destination prepares (the bar
// is otherwise driven only by htmx's own request pair, so a slow preparation looked like a dead
// click), that every start is paired with an end on success, failure and supersession, and that
// the event names match the ones shell.js listens for. A minimal DOM stand-in: one EventTarget
// body, a parser stub and a scripted fetch.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const shell = require(path.resolve(here, "../../main/resources/static/js/shell.js"));

const body = new EventTarget();
const elements = new Map();
const main = { prepend(element) { elements.set(element.id, element); }, querySelector: () => null, querySelectorAll: () => [] };
elements.set("app-main", main);
globalThis.window = globalThis;
globalThis.location = new URL("http://app.test/pipelines");
globalThis.document = {
  body,
  documentElement: { dataset: { dpWorkspaceId: "ws-1" } },
  getElementById: id => elements.get(id) || null,
  createElement: () => ({ setAttribute() {}, remove() { elements.delete(this.id); } }),
};
globalThis.DOMParser = class {
  parseFromString(text) {
    const destination = { querySelector: () => null, querySelectorAll: () => [] };
    return { getElementById: id => (id === "app-main" ? destination : null), documentElement: { dataset: { dpWorkspaceId: text } } };
  }
};
const replies = [];
globalThis.fetch = (url, options) => new Promise((resolve, reject) => {
  const reply = { url, resolve, reject, signal: options.signal };
  options.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
  replies.push(reply);
});
const html = (workspace = "ws-1", status = 200) =>
  ({ ok: status < 400, status, headers: { get: () => "text/html;charset=UTF-8" }, text: async () => workspace });

const log = [];
body.addEventListener(shell.NAVIGATION_PREPARE_START, event => log.push(["start", event.detail?.elt?.id]));
body.addEventListener(shell.NAVIGATION_PREPARE_END, event => log.push(["end", event.detail?.elt?.id]));

await import("../../main/resources/static/js/persistent-navigation.mjs");

function anchor(id, boostOff = false) {
  return { id, tagName: "A", closest: selector => (boostOff && selector === '[hx-boost="false"]' ? {} : null) };
}
function confirm(elt, pathname, verb = "get") {
  const event = new CustomEvent("htmx:confirm", { cancelable: true,
    detail: { verb, elt, path: pathname, issueRequest() { log.push(["issue", elt.id]); } } });
  body.dispatchEvent(event);
  return event;
}
const flush = () => new Promise(resolve => setTimeout(resolve, 0));

test("the event names are the ones shell.js listens for", () => {
  assert.equal(shell.NAVIGATION_PREPARE_START, "dp:navigation-prepare-start");
  assert.equal(shell.NAVIGATION_PREPARE_END, "dp:navigation-prepare-end");
});

test("a held preparation raises the shell's busy signal at once and ends it only after the final request is issued", async () => {
  log.length = 0; replies.length = 0;
  const event = confirm(anchor("leaf"), "/pipelines/42");
  assert.equal(event.defaultPrevented, true);
  assert.deepEqual(log, [["start", undefined]], "busy before the preparation GET answers");
  replies[0].resolve(html()); await flush();
  assert.deepEqual(log, [["start", undefined], ["issue", "leaf"], ["end", undefined]],
    "the end follows issueRequest, so htmx's own request holds the bar without a gap");
});

test("a failed preparation ends the busy signal and leaves a notice instead of issuing the request", async () => {
  log.length = 0; replies.length = 0;
  confirm(anchor("leaf"), "/dashboards/7");
  replies[0].resolve(html("ws-1", 503)); await flush();
  assert.deepEqual(log, [["start", undefined], ["end", undefined]]);
  assert.equal(elements.get("dp-navigation-notice")?.textContent, "Could not open destination");
});

test("a superseded preparation ends its own busy signal and never issues", async () => {
  log.length = 0; replies.length = 0;
  confirm(anchor("first"), "/visualizations/1");
  confirm(anchor("second"), "/visualizations/2");
  await flush();
  assert.equal(replies[0].signal.aborted, true);
  replies[1].resolve(html()); await flush();
  assert.deepEqual(log.filter(entry => entry[0] === "issue"), [["issue", "second"]]);
  assert.equal(log.filter(entry => entry[0] === "start").length, 2);
  assert.equal(log.filter(entry => entry[0] === "end").length, 2, "every start is paired");
});

test("a workspace change refuses the destination and still ends the busy signal", async () => {
  log.length = 0; replies.length = 0;
  confirm(anchor("leaf"), "/templates/t");
  replies[0].resolve(html("ws-2")); await flush();
  assert.deepEqual(log, [["start", undefined], ["end", undefined]]);
  assert.equal(elements.get("dp-navigation-notice")?.textContent, "Session or workspace changed. Reload to continue.");
});

test("non-family, non-GET, foreign and unboosted links pass through untouched", () => {
  log.length = 0; replies.length = 0;
  for (const [elt, pathname, verb] of [[anchor("a"), "/settings", "get"], [anchor("b"), "/pipelines/1", "post"],
    [anchor("c"), "https://elsewhere.test/pipelines/1", "get"], [anchor("d", true), "/pipelines/1", "get"],
    [{ id: "e", tagName: "BUTTON", closest: () => null }, "/pipelines/1", "get"]]) {
    assert.equal(confirm(elt, pathname, verb).defaultPrevented, false, `${elt.id} ${pathname}`);
  }
  assert.deepEqual(log, []); assert.equal(replies.length, 0);
});
