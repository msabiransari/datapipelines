// #473 — the board's presentation cards: the card shell mountVisualization builds, the state
// treatment renderStatus drives (chip text/data-dp-state unchanged, border class + in-card
// message + the loud→quiet accent timer), heading precedence, and the parameters pane's mount
// target + label association contracts. Node's built-in runner; the fake DOM is the same shape
// the runtime and adapters tests install.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const resolveStatic = (name) => path.resolve(here, "../../main/resources/static/js/" + name);

// ---------------------------------------------------------------------------------------------- fake DOM

function fakeElement(tag) {
  const attrs = {};
  const children = [];
  const el = {
    tagName: (tag || "div").toUpperCase(),
    attrs,
    children,
    style: {},
    className: "",
    textContent: "",
    innerHTML: null, // written by NOBODY under test — the rendering rule
    scope: null,
    parentNode: null,
    firstChild: null,
    value: "",
    disabled: false,
    checked: false,
    setAttribute(k, v) {
      attrs[k] = String(v);
    },
    getAttribute(k) {
      return k in attrs ? attrs[k] : null;
    },
    removeAttribute(k) {
      delete attrs[k];
    },
    appendChild(child) {
      child.parentNode = el;
      children.push(child);
      el.firstChild = children[0] || null;
      return child;
    },
    insertBefore(child, before) {
      child.parentNode = el;
      const index = children.indexOf(before);
      if (index === -1) children.push(child);
      else children.splice(index, 0, child);
      el.firstChild = children[0] || null;
      return child;
    },
    removeChild(child) {
      const index = children.indexOf(child);
      if (index !== -1) children.splice(index, 1);
      child.parentNode = null;
      el.firstChild = children[0] || null;
      return child;
    },
    remove() {
      if (el.parentNode) el.parentNode.removeChild(el);
    },
    addEventListener(type, fn) {
      (el.listeners ||= {})[type] = (el.listeners[type] || []).concat(fn);
    },
    fire(type, event) {
      ((el.listeners || {})[type] || []).forEach((fn) => fn.call(el, event || {}));
    },
    querySelector(selector) {
      return el.querySelectorAll(selector)[0] || null;
    },
    querySelectorAll(selector) {
      const out = [];
      const classWanted = selector.startsWith(".") ? selector.slice(1) : null;
      const attrMatch = selector.startsWith("[") ? /^\[([a-z-]+)/.exec(selector) : null;
      const matches = (node) => {
        if (classWanted) return typeof node.className === "string" && node.className.split(/\s+/).includes(classWanted);
        if (attrMatch) return node.attrs && node.attrs[attrMatch[1]] !== undefined;
        return false;
      };
      const walk = (node) => {
        for (const child of node.children || []) {
          if (matches(child)) out.push(child);
          walk(child);
        }
      };
      walk(el);
      return out;
    },
  };
  return el;
}

function installDom() {
  const doc = { body: fakeElement("body"), head: fakeElement("head"), cookie: "" };
  doc.getElementById = () => null;
  doc.createElement = (tag) => fakeElement(tag);
  doc.createTextNode = (text) => ({ text: String(text), nodeType: 3 });
  doc.querySelectorAll = () => [];
  globalThis.document = doc;
  globalThis.window = {
    document: doc,
    getComputedStyle: () => ({ color: "color(srgb 0.1 0.2 0.3)" }),
  };
  return doc;
}

function uninstallDom() {
  delete globalThis.document;
  delete globalThis.window;
}

/** A stub renderer: no renderStatus of its own, so the composite's card path runs. */
function stubRenderer(runtime) {
  runtime._internal.resetRenderers();
  runtime.registerRenderer({
    kind: "plotly",
    version: "4",
    create: () => ({ renderData: () => Promise.resolve("rendered"), resize: () => {}, dispose: () => {} }),
  });
}

async function mountedAdapter(runtime, options) {
  const container = fakeElement("div");
  const adapter = runtime.adapters(container, options);
  await adapter.mountLayout({ columns: 12, grid: [{ name: "rev", x: 0, y: 0, w: 6, h: 4 }] });
  return { container, adapter };
}

const OCCURRENCE = {
  name: "rev",
  renderer: { kind: "plotly", version: "4" },
  display_name: "Revenue",
  description: "Monthly revenue by region",
  config: { label: "Old label" },
};

async function cardOf(adapter, occurrence) {
  await adapter.mountVisualization(occurrence || OCCURRENCE, { kind: "plotly", version: "4" });
  const slot = adapter.grid ? adapter.grid.rev : null;
  return slot.children[slot.children.length - 1];
}

// --------------------------------------------------------------------------------------------------- tests

test("#473: every visualization mounts inside a card shell — heading, description, body, status foot", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    stubRenderer(runtime);
    const { container, adapter } = await mountedAdapter(runtime);
    const card = await cardOf(adapter);
    assert.equal(card.tagName, "ARTICLE");
    assert.equal(card.className, "dp-dashboard-card");
    assert.equal(card.getAttribute("data-dp-viz"), "rev", "the marker moved to the card");
    // The accessible name: aria-labelledby resolves to the card's own heading.
    const heading = card.querySelector(".dp-dashboard-card-title");
    assert.equal(heading.tagName, "H3");
    assert.equal(card.getAttribute("aria-labelledby"), heading.id, "the heading names the card");
    assert.equal(heading.id.startsWith("dp-card-"), true, "a per-instance id, two boards never collide");
    assert.equal(heading.textContent, "Revenue", "the pinned display name is the heading");
    const description = card.querySelector(".dp-dashboard-card-description");
    assert.equal(description.textContent, "Monthly revenue by region");
    // The renderer's host is the card body; the foot carries chip + message.
    const body = card.querySelector(".dp-dashboard-viz");
    assert.ok(body, "the body exists");
    assert.ok(card.querySelector(".dp-dashboard-card-foot"), "the foot exists");
    const chip = card.querySelector('[role="status"]');
    assert.ok(chip, "the status element keeps role=status");
    assert.ok(card.querySelector(".dp-dashboard-card-message"), "the message element exists");
    // The conformance contract's containment relationship: the chip lives INSIDE the marked card
    // (a real browser resolves `[data-dp-viz='rev'] .dp-dashboard-status`; the fake DOM walks
    // containment directly).
    assert.equal(card.querySelector(".dp-dashboard-status"), chip);
    assert.equal(chip.parentNode.className, "dp-dashboard-card-foot");
    // The renderer was created with the BODY as its host, not the slot.
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#473: the heading falls back display_name → config.label → name; an empty description renders empty", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    stubRenderer(runtime);
    const { adapter } = await mountedAdapter(runtime);
    // presentation.title beats display_name.
    const titled = await cardOf(adapter, Object.assign({}, OCCURRENCE, { presentation: { title: "Authored title" } }));
    assert.equal(titled.querySelector(".dp-dashboard-card-title").textContent, "Authored title");
    // No display_name: the authored config label.
    const labelled = await cardOf(adapter, Object.assign({}, OCCURRENCE, { display_name: undefined }));
    assert.equal(labelled.querySelector(".dp-dashboard-card-title").textContent, "Old label");
    // Neither: the occurrence name.
    const bare = await cardOf(
      adapter,
      Object.assign({}, OCCURRENCE, { display_name: undefined, config: {}, description: "" }),
    );
    assert.equal(bare.querySelector(".dp-dashboard-card-title").textContent, "rev");
    assert.equal(bare.querySelector(".dp-dashboard-card-description").textContent, "");
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#473: loading shows the busy card and the retained-result sentence once a result exists", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    stubRenderer(runtime);
    const { adapter } = await mountedAdapter(runtime);
    const card = await cardOf(adapter);
    adapter.renderStatus(OCCURRENCE, { state: "in-progress" });
    const chip = card.querySelector('[role="status"]');
    assert.equal(chip.getAttribute("data-dp-state"), "in-progress");
    assert.equal(card.className, "dp-dashboard-card dp-dashboard-card--busy");
    assert.equal(card.getAttribute("aria-busy"), "true");
    assert.equal(card.querySelector(".dp-dashboard-card-message").textContent, "Loading…", "an empty body says only loading");
    // A body WITH a retained result says so.
    const body = card.querySelector(".dp-dashboard-viz");
    body.appendChild(fakeElement("div"));
    adapter.renderStatus(OCCURRENCE, { state: "in-progress" });
    assert.equal(
      card.querySelector(".dp-dashboard-card-message").textContent,
      "Loading — showing the previous result.",
    );
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#473: success is quiet — the accent rides the runtime's settle, nothing lingers", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    stubRenderer(runtime);
    const { adapter } = await mountedAdapter(runtime);
    const card = await cardOf(adapter);
    adapter.renderStatus(OCCURRENCE, { state: "success" });
    assert.equal(card.querySelector('[role="status"]').getAttribute("data-dp-state"), "success");
    assert.equal(card.className, "dp-dashboard-card dp-dashboard-card--success");
    assert.equal(card.getAttribute("aria-busy"), null, "loading is over");
    assert.equal(card.querySelector(".dp-dashboard-card-message").textContent, "", "no message on success");
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#473: an error is loud for ERROR_ACCENT_MS, then a quiet issue border — the message stays", async (t) => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    stubRenderer(runtime);
    t.mock.timers.enable({ apis: ["setTimeout"] });
    const { adapter } = await mountedAdapter(runtime);
    const card = await cardOf(adapter);
    adapter.renderStatus(OCCURRENCE, {
      state: "error",
      reason: { code: "source_unavailable", stage: "render" },
    });
    const chip = card.querySelector('[role="status"]');
    assert.equal(chip.getAttribute("data-dp-state"), "error");
    assert.ok(chip.textContent.includes("source_unavailable"), "the code is the chip text: " + chip.textContent);
    assert.equal(card.className, "dp-dashboard-card dp-dashboard-card--error");
    assert.equal(
      card.querySelector(".dp-dashboard-card-message").textContent,
      "A data source was unavailable (render).",
      "the dictionary sentence plus the stage",
    );
    t.mock.timers.tick(runtime._internal.ERROR_ACCENT_MS);
    assert.equal(
      card.className,
      "dp-dashboard-card dp-dashboard-card--has-issue",
      "the loud accent settles; the issue border stays",
    );
    assert.equal(
      card.querySelector(".dp-dashboard-card-message").textContent,
      "A data source was unavailable (render).",
      "the message persists",
    );
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#473: a newer status retires the pending accent timer — a settled error never repaints ready", async (t) => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    stubRenderer(runtime);
    t.mock.timers.enable({ apis: ["setTimeout"] });
    const { adapter } = await mountedAdapter(runtime);
    const card = await cardOf(adapter);
    adapter.renderStatus(OCCURRENCE, { state: "error", reason: { code: "source_unavailable" } });
    adapter.renderStatus(OCCURRENCE, { state: "ready" });
    assert.equal(card.className, "dp-dashboard-card", "ready is the quiet default");
    t.mock.timers.tick(runtime._internal.ERROR_ACCENT_MS + 1);
    assert.equal(card.className, "dp-dashboard-card", "the retired timer touched nothing");
    assert.equal(card.querySelector(".dp-dashboard-card-message").textContent, "", "ready clears the message too");
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#473: no-data, abort and ready+stale each carry word + class; stale survives the accent settle", async (t) => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    stubRenderer(runtime);
    t.mock.timers.enable({ apis: ["setTimeout"] });
    const { adapter } = await mountedAdapter(runtime);
    const card = await cardOf(adapter);

    adapter.renderStatus(OCCURRENCE, { state: "no-data" });
    assert.equal(card.querySelector('[role="status"]').getAttribute("data-dp-state"), "no-data");
    assert.equal(card.className, "dp-dashboard-card dp-dashboard-card--empty");
    assert.equal(card.querySelector(".dp-dashboard-card-message").textContent, "No data for this selection.");

    adapter.renderStatus(OCCURRENCE, { state: "abort" });
    assert.equal(card.querySelector('[role="status"]').getAttribute("data-dp-state"), "abort");
    assert.equal(card.className, "dp-dashboard-card dp-dashboard-card--aborted");
    assert.equal(card.querySelector(".dp-dashboard-card-message").textContent, "Refresh cancelled.");
    t.mock.timers.tick(runtime._internal.ERROR_ACCENT_MS);
    assert.equal(card.className, "dp-dashboard-card dp-dashboard-card--has-issue");

    adapter.renderStatus(OCCURRENCE, { state: "ready", stale: true });
    assert.equal(card.querySelector('[role="status"]').textContent, "ready (stale)");
    assert.equal(card.className, "dp-dashboard-card dp-dashboard-card--stale");
    assert.equal(card.querySelector(".dp-dashboard-card-message").textContent, "Showing out-of-date data.");
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#473: dispose retires every card timer — a disposed card never reaches back", async (t) => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    stubRenderer(runtime);
    t.mock.timers.enable({ apis: ["setTimeout"] });
    const { container, adapter } = await mountedAdapter(runtime);
    const card = await cardOf(adapter);
    adapter.renderStatus(OCCURRENCE, { state: "error", reason: { code: "source_unavailable" } });
    adapter.dispose();
    t.mock.timers.tick(runtime._internal.ERROR_ACCENT_MS + 1);
    assert.equal(card.className, "dp-dashboard-card dp-dashboard-card--error", "the timer died with the adapter");
    adapter.renderStatus(OCCURRENCE, { state: "ready" });
    assert.equal(card.className, "dp-dashboard-card dp-dashboard-card--error", "a status after dispose changes nothing");
    assert.equal(container.children.length, 0, "the composite left nothing mounted in the container");
  } finally {
    uninstallDom();
  }
});

// ---- the parameters pane: mount target and label association (#473 work item B) ---------------

test("#473: with parametersContainer the pane mounts in the host's panel, never in the grid container", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const panel = fakeElement("aside");
    const { container, adapter } = await mountedAdapter(runtime, { parametersContainer: panel });
    await adapter.renderParameters(compositeState());
    const root = panel.children[0];
    assert.equal(root.className, "dp-dashboard-parameters");
    assert.deepEqual(
      container.children.map((c) => c.className),
      ["dp-dashboard"],
      "the container holds only the grid",
    );
    adapter.dispose();
    assert.equal(panel.children.length, 0, "dispose removes the pane from the panel too");
  } finally {
    uninstallDom();
  }
});

test("#473: every single control has a labelled caption — label[for] to a stable per-instance id", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const { container, adapter } = await mountedAdapter(runtime);
    await adapter.renderParameters(compositeState());
    const row = container.querySelector("[data-dp-parameter]");
    assert.equal(row.tagName, "DIV", "a single control is not a fieldset");
    const label = row.children[0];
    assert.equal(label.tagName, "LABEL");
    const select = row.children[1];
    assert.equal(select.tagName, "SELECT");
    assert.equal(label.getAttribute("for"), select.id, "the caption names ITS control");
    assert.ok(select.id.startsWith("dp-param-"), "a per-instance id: " + select.id);
    assert.equal(select.className, "ds-input", "the design system's field treatment");
    // The boolean tri-state and the free INPUT carry the same association.
    await adapter.renderParameters(booleanInputState(null));
    const boolRow = container.querySelector("[data-dp-parameter]");
    assert.equal(boolRow.children[0].getAttribute("for"), boolRow.children[1].id);
    assert.equal(boolRow.children[1].className, "ds-input");
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#473: a group is a fieldset with a legend; every option's text is a label[for] over its own input", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const { container, adapter } = await mountedAdapter(runtime);
    const state = compositeState();
    const multi = JSON.parse(JSON.stringify(state.parameters[0]));
    multi.name = "regions";
    multi.label = "Regions";
    multi.cardinality = "MULTI";
    multi.presentation = { control: "checkboxes" };
    multi.state.value = ["EU"];
    multi.state.options = [
      { value: "EU", display_value: "Europe", is_default: true },
      { value: "US", display_value: "United States", is_default: false },
    ];
    state.parameters = [multi];
    await adapter.renderParameters(state);
    const row = container.querySelector("[data-dp-parameter]");
    assert.equal(row.tagName, "FIELDSET", "a group row is a fieldset");
    const legend = row.children[0];
    assert.equal(legend.tagName, "LEGEND");
    assert.equal(legend.textContent, "Regions", "the legend is the caption");
    const inputs = row.children.filter((c) => c.tagName === "INPUT");
    assert.equal(inputs.length, 2);
    const labels = row.children.filter((c) => c.tagName === "LABEL");
    assert.equal(labels.length, 2, "each option's text is a label");
    for (let i = 0; i < 2; i++) {
      assert.equal(labels[i].getAttribute("for"), inputs[i].id, "option label " + i + " names its box");
      assert.equal(labels[i].textContent, i === 0 ? "Europe" : "United States");
    }
    // The radio group behaves the same, name scoped per adapter.
    await adapter.renderParameters(radioState());
    const radioRow = container.querySelector("[data-dp-parameter]");
    assert.equal(radioRow.tagName, "FIELDSET");
    const radios = radioRow.children.filter((c) => c.tagName === "INPUT");
    const radioLabels = radioRow.children.filter((c) => c.tagName === "LABEL");
    assert.equal(radios.length, radioLabels.length);
    for (let r = 0; r < radios.length; r++) {
      assert.equal(radioLabels[r].getAttribute("for"), radios[r].id);
    }
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

// -------------------------------------------------------------------------------------------- fixtures

/** One SELECT SINGLE (dropdown) parameter exactly as the writer sends it. */
function compositeState(overrides) {
  return Object.assign(
    {
      valid: true,
      values: { year: 2026 },
      parameters: [
        {
          name: "year",
          label: "Year",
          type: "INTEGER",
          kind: "SELECT",
          cardinality: "SINGLE",
          required: false,
          depends_on: [],
          presentation: { control: "dropdown" },
          dependents: [],
          state: {
            value: 2026,
            origin: "default",
            computed_default: 2026,
            reset: false,
            hidden: false,
            disabled: false,
            options: [
              { value: 2025, display_value: "2025", is_default: false },
              { value: 2026, display_value: "2026", is_default: true },
            ],
            errors: [],
          },
        },
      ],
      overrides_applied: {},
      parents: ["year"],
      parameter_revision: 3,
    },
    overrides || {},
  );
}

/** The composite state with its one parameter reshaped as a BOOLEAN INPUT at [value]. */
function booleanInputState(value) {
  const state = compositeState();
  Object.assign(state.parameters[0], {
    name: "enabled",
    label: "Enabled",
    type: "BOOLEAN",
    kind: "INPUT",
    cardinality: "SINGLE",
    presentation: null,
  });
  state.parameters[0].state.value = value;
  state.parameters[0].state.options = null;
  state.values = { enabled: value };
  return state;
}

/** The composite state with its one parameter presented as a radio group. */
function radioState() {
  const state = compositeState();
  state.parameters[0].presentation = { control: "radio" };
  return state;
}
