// 097 §D — template-editor/lifecycle.js: the draft lifecycle, the context rail and the
// preview call, extracted from a ~135-line inline script in templates/templates/editor.html.
//
// The contract under test is the state machine the extraction had to preserve exactly:
//   release  → POST /api/v1/templates/release, hash-guarded, CSRF-headed, reload on 200,
//              button re-enabled and a DANGER TOAST on refusal (it was a window.alert);
//   discard  → opens the in-page confirmation first (it was a window.confirm) and posts
//              only when it is confirmed;
//   render   → htmx.ajax at the /partials endpoint (§2.1 gives fragments to htmx), the
//              highlight pass after the swap, and a card built from NODES on failure.
//
// The DOM is shimmed the way draft.test.mjs shims it: this suite is about the decisions,
// and the browser suite covers the page.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const staticRoot = path.resolve(here, "../../main/resources/static");

/* ---------------------------------------------------------------- the shims */

function fakeElement(id) {
  return {
    id,
    disabled: false,
    value: "",
    textContent: "",
    children: [],
    attributes: {},
    classes: new Set(),
    classList: {
      add(c) {
        this.owner.classes.add(c);
      },
      remove(c) {
        this.owner.classes.delete(c);
      },
      toggle(c, on) {
        if (on === undefined) on = !this.owner.classes.has(c);
        if (on) this.owner.classes.add(c);
        else this.owner.classes.delete(c);
        return on;
      },
    },
    getAttribute(name) {
      return this.attributes[name] === undefined ? null : this.attributes[name];
    },
    setAttribute(name, v) {
      this.attributes[name] = v;
    },
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    addEventListener() {},
    querySelectorAll() {
      return [];
    },
    remove() {
      this.removed = true;
    },
  };
}

function element(id) {
  const el = fakeElement(id);
  el.classList.owner = el;
  return el;
}

const elements = {};
function el(id) {
  if (!elements[id]) elements[id] = element(id);
  return elements[id];
}

const toasts = [];
const reloads = [];
const ajaxCalls = [];

globalThis.window = {
  DpToast: {
    show(variant, title, message) {
      toasts.push({ variant, title, message });
    },
  },
  location: {
    reload() {
      reloads.push(true);
    },
  },
};
globalThis.document = {
  cookie: "dp_csrf=tok%2Fen",
  getElementById: (id) => (elements[id] === undefined ? null : elements[id]),
  querySelector: (sel) => (sel === "[data-template-id]" ? el("te-page") : null),
  querySelectorAll: () => contextRows,
  createElement: (tag) => {
    const e = element(tag);
    e.tag = tag;
    return e;
  },
  createElementNS: (ns, tag) => {
    const e = element(tag);
    e.tag = tag;
    return e;
  },
};
let contextRows = [];

require(path.resolve(staticRoot, "js/csrf.js"));
require(path.resolve(staticRoot, "js/template-editor/lifecycle.js"));
const lifecycle = globalThis.window.TplLifecycle;

/* ---------------------------------------------------------------- the fixture */

let fetches = [];
function reset() {
  for (const key of Object.keys(elements)) delete elements[key];
  fetches = [];
  toasts.length = 0;
  reloads.length = 0;
  ajaxCalls.length = 0;
  contextRows = [];
  el("te-page").attributes["data-template-id"] = "acme/rev.sql";
  el("tpl-release-draft").attributes["data-id"] = "acme/rev.sql";
  el("tpl-release-draft").attributes["data-hash"] = "hash-v3";
  el("tpl-discard-draft").attributes["data-id"] = "acme/rev.sql";
  el("tpl-discard-draft").attributes["data-hash"] = "hash-v3";
  el("tpl-discard-confirm").classes.add("u-backdrop-hidden");
}

function stubFetch(response) {
  globalThis.fetch = (url, init) => {
    fetches.push({ url, init });
    return Promise.resolve(response);
  };
}

const ok = { ok: true, status: 200, json: () => Promise.resolve({}) };
function refusal(message) {
  return {
    ok: false,
    status: 409,
    json: () => Promise.resolve({ error: { message } }),
  };
}

/* ---------------------------------------------------------------- release */

test("release posts the hash-guarded, CSRF-headed body and reloads on success", async () => {
  reset();
  stubFetch(ok);

  await lifecycle.lifecycle("release");

  assert.equal(fetches.length, 1);
  const { url, init } = fetches[0];
  assert.equal(url, "/api/v1/templates/release");
  assert.equal(init.method, "POST");
  // §9.6: the name is in the BODY, never a path segment.
  assert.equal(init.body, JSON.stringify({ name: "acme/rev.sql" }));
  assert.equal(init.headers["If-Match"], "hash-v3");
  // The token is URL-decoded on the way into the header (the cookie holds it encoded).
  assert.equal(init.headers["DP-CSRF-Token"], "tok/en");
  assert.equal(reloads.length, 1);
});

test("a refused release re-enables the button and reports the server's message as a toast", async () => {
  reset();
  stubFetch(refusal("The draft has moved on."));

  await lifecycle.lifecycle("release");

  assert.equal(reloads.length, 0);
  assert.equal(el("tpl-release-draft").disabled, false);
  assert.deepEqual(toasts, [
    { variant: "danger", title: "Release refused", message: "The draft has moved on." },
  ]);
});

test("a refusal with no envelope still says something, and never a bare undefined", async () => {
  reset();
  stubFetch({ ok: false, status: 500, json: () => Promise.reject(new Error("not json")) });

  await lifecycle.lifecycle("release");

  assert.equal(toasts[0].message, "HTTP 500");
});

/* ---------------------------------------------------------------- discard */

test("discard asks first — it opens the in-page confirmation and posts nothing", async () => {
  reset();
  stubFetch(ok);

  const result = lifecycle.lifecycle("discard");

  assert.equal(result, null);
  assert.equal(fetches.length, 0);
  assert.equal(el("tpl-discard-confirm").classes.has("u-backdrop-hidden"), false);
});

test("confirming the discard closes the dialog and posts to the discard endpoint", async () => {
  reset();
  stubFetch(ok);
  lifecycle.lifecycle("discard");

  await lifecycle.confirmDiscard();

  assert.equal(el("tpl-discard-confirm").classes.has("u-backdrop-hidden"), true);
  assert.equal(fetches.length, 1);
  assert.equal(fetches[0].url, "/api/v1/templates/draft/discard");
  assert.equal(fetches[0].init.headers["If-Match"], "hash-v3");
  assert.equal(reloads.length, 1);
});

test("keeping the draft closes the dialog and never posts", async () => {
  reset();
  stubFetch(ok);
  lifecycle.lifecycle("discard");

  lifecycle.closeConfirm();

  assert.equal(el("tpl-discard-confirm").classes.has("u-backdrop-hidden"), true);
  assert.equal(fetches.length, 0);
});

/* ---------------------------------------------------------------- the context rail */

test("the tab state is classes, never styles written from script", () => {
  reset();
  el("context-kv");
  el("context-json");
  el("tab-kv-btn");
  el("tab-json-btn");

  lifecycle.switchTab("json");

  assert.equal(el("context-kv").classes.has("u-hidden"), true);
  assert.equal(el("context-json").classes.has("u-hidden"), false);
  assert.equal(el("tab-json-btn").classes.has("app-tab-active"), true);
  assert.equal(el("tab-kv-btn").classes.has("app-tab-idle"), true);
  // Nothing set an inline style on any of them.
  for (const id of ["context-kv", "context-json", "tab-kv-btn", "tab-json-btn"]) {
    assert.equal(el(id).style, undefined);
  }
});

test("a context row is built from nodes, not from an innerHTML string", () => {
  reset();
  const rows = el("context-rows");

  const row = lifecycle.addContextRow();

  assert.equal(rows.children.length, 1);
  assert.equal(row.innerHTML, undefined);
  assert.equal(row.className, "context-row");
  assert.deepEqual(
    row.children.map((c) => c.tag),
    ["input", "input", "button"],
  );
});

test("the key/value tab sends typed values — a bare number is a number", () => {
  reset();
  lifecycle.switchTab("kv");
  contextRows = [
    { querySelectorAll: () => [{ value: " region " }, { value: " emea " }] },
    { querySelectorAll: () => [{ value: "limit" }, { value: "42" }] },
    { querySelectorAll: () => [{ value: "rate" }, { value: "1.5" }] },
    // A row with no key contributes nothing rather than an empty-string key.
    { querySelectorAll: () => [{ value: "  " }, { value: "ignored" }] },
  ];

  assert.equal(lifecycle.buildContextJson(), JSON.stringify({ region: "emea", limit: 42, rate: 1.5 }));
});

test("the JSON tab sends the textarea verbatim", () => {
  reset();
  el("contextJsonTextarea").value = '{"x": 1}';
  lifecycle.switchTab("json");

  assert.equal(lifecycle.buildContextJson(), '{"x": 1}');
  lifecycle.switchTab("kv");
});

/* ---------------------------------------------------------------- the preview */

test("the preview goes through htmx at the partials URL, and highlights after the swap", async () => {
  reset();
  let highlighted = null;
  globalThis.window.TplPreviewHighlight = { highlightPreview: (pane) => (highlighted = pane) };
  globalThis.htmx = {
    ajax: (method, url, opts) => {
      ajaxCalls.push({ method, url, opts });
      return Promise.resolve();
    },
  };
  el("previewPane");
  el("previewBtn");
  el("previewSpinner").classes.add("u-hidden");
  el("versionSelect").value = "3";
  el("templateBody").value = "SELECT 1";

  await lifecycle.renderPreview();

  assert.equal(ajaxCalls.length, 1);
  const call = ajaxCalls[0];
  assert.equal(call.method, "POST");
  assert.equal(call.url, "/partials/templates/render?name=acme%2Frev.sql&version=3");
  assert.equal(call.opts.target, "#previewPane");
  assert.equal(call.opts.values.body, "SELECT 1");
  assert.equal(call.opts.values.context, "{}");
  assert.equal(highlighted, el("previewPane"));
  // The button and the spinner return to rest whatever happened.
  assert.equal(el("previewBtn").disabled, false);
  assert.equal(el("previewSpinner").classes.has("u-hidden"), true);
});

test("a failed preview builds its card from nodes and leaves the pane's markup alone", async () => {
  reset();
  globalThis.window.TplPreviewHighlight = null;
  globalThis.htmx = { ajax: () => Promise.reject(new Error("network down")) };
  const pane = el("previewPane");
  el("previewBtn");
  el("previewSpinner");

  await lifecycle.renderPreview();

  assert.equal(pane.innerHTML, undefined);
  assert.equal(pane.children.length, 1);
  const card = pane.children[0];
  assert.equal(card.className, "ds-card app-error-card u-p-md");
  assert.equal(card.children[0].textContent, "network down");
  assert.equal(el("previewBtn").disabled, false);
});
