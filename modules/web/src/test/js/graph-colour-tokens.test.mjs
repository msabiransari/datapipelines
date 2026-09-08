// 082 addendum P1 — Cytoscape must be handed a COLOUR, never a token's declaration.
//
// `getComputedStyle(root).getPropertyValue('--edge')` returns a custom property's
// token stream VERBATIM, and four of 080's canvas tokens are `color-mix()` bridges
// (`app.css`: --brand-soft, --border-faint, --grid-dot, --edge). Cytoscape parses
// colours itself, cannot read `color-mix(in srgb, …)`, logs
// "The style property `line-color: color-mix(…)` is invalid" and falls back — which
// is why the dark theme's edges collapsed into thick grey bands on the re-render.
//
// readDesignTokens resolves every colour through a probe element. These tests drive
// exactly that seam: a fake DOM whose probe answers `rgb(…)` the way a browser does.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");

/** The four tokens app.css bridges with color-mix, plus plain ones for contrast. */
const ROOT_VARS = {
  "--pe-card-w": "236px",
  "--pe-card-h": "148px",
  "--radius-lg": "12px",
  "--brand": "#2f6bff",
  "--brand-soft": "color-mix(in srgb, var(--brand) 16%, var(--surface-default))",
  "--edge": "color-mix(in srgb, var(--text-muted) 65%, var(--surface-page))",
  "--edge-active": "#2f6bff",
  "--edge-done": "#15803d",
  "--surface-raised": "#ffffff",
  "--border-subtle": "#b2b7bf",
  "--accent-success": "#15803d",
  "--accent-danger": "#dc2626",
  "--accent-warning": "#a16207",
  "--text-muted": "#64748b",
  "--surface-page": "#f8f9fb",
};

/** What a real browser computes for each token, once it has done the mixing. */
const RESOLVED = {
  "--brand": "rgb(47, 107, 255)",
  "--brand-soft": "rgb(222, 230, 255)",
  "--edge": "rgb(126, 140, 160)",
  "--edge-active": "rgb(47, 107, 255)",
  "--edge-done": "rgb(21, 128, 61)",
  "--surface-raised": "rgb(255, 255, 255)",
  "--border-subtle": "rgb(178, 183, 191)",
  "--accent-success": "rgb(21, 128, 61)",
  "--accent-danger": "rgb(220, 38, 38)",
  "--accent-warning": "rgb(161, 98, 7)",
  "--text-muted": "rgb(100, 116, 139)",
  "--surface-page": "rgb(248, 249, 251)",
};

function withDom(rootVars, opts) {
  const options = opts || {};
  const created = [];
  const root = { __vars: rootVars };
  const body = {
    children: [],
    appendChild(el) {
      this.children.push(el);
      el.parentNode = this;
    },
    removeChild(el) {
      this.children = this.children.filter((c) => c !== el);
      el.parentNode = null;
    },
  };
  globalThis.document = {
    documentElement: root,
    body,
    createElement() {
      const el = { style: {}, parentNode: null, setAttribute() {} };
      created.push(el);
      return el;
    },
    getElementById: () => null,
    querySelectorAll: () => [],
  };
  globalThis.getComputedStyle = (el) => {
    if (el === root) {
      return { getPropertyValue: (k) => (el.__vars[k] === undefined ? "" : el.__vars[k]) };
    }
    // The probe: a browser resolves whatever `var(--x)` it was handed. `computedAs`
    // chooses the SERIALISATION, which is the whole point of these tests.
    const m = /^var\((--[a-z-]+)\)$/.exec(el.style.color || "");
    let value = m && !options.probeBlind ? (options.computedAs || RESOLVED)[m[1]] || "" : "";
    // 093's browser: a REUSED element keeps answering the first colour it was ever
    // asked for (an in-flight colour transition serialises the old value). A fresh
    // element per read is the only shape that survives this.
    if (options.stickyProbe) {
      if (el.__first === undefined) el.__first = value;
      value = el.__first;
    }
    return { color: value, getPropertyValue: () => "" };
  };
  return { body, created };
}

function loadGraph() {
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}

function styleFor(sheet, selector) {
  const entry = sheet.find((e) => e.selector === selector);
  assert.ok(entry, `no stylesheet entry for ${selector}`);
  return entry.style;
}

test("a color-mix token reaches Cytoscape as rgb(), never as its declaration", () => {
  withDom(ROOT_VARS);
  const { readDesignTokens, buildStylesheet } = loadGraph();

  const tokens = readDesignTokens();
  assert.equal(tokens.edgeIdle, "rgb(126, 140, 160)");
  assert.equal(tokens.brandSoft, "rgb(222, 230, 255)");

  // The whole point: nothing Cytoscape is handed may still say color-mix.
  const sheet = buildStylesheet(tokens);
  const serialised = JSON.stringify(sheet);
  assert.ok(!/color-mix/.test(serialised), "a color-mix expression reached the Cytoscape stylesheet");
  assert.equal(styleFor(sheet, "edge")["line-color"], "rgb(126, 140, 160)");
  assert.equal(styleFor(sheet, "node:selected")["underlay-color"], "rgb(222, 230, 255)");
});

test("every colour token is resolved, not just the mixed ones", () => {
  withDom(ROOT_VARS);
  const { readDesignTokens } = loadGraph();
  const tokens = readDesignTokens();

  // A plain hex resolves to rgb() through the same path — one rule, no per-token list
  // of "which ones are expressions" to keep in step with app.css.
  [
    ["brand", "--brand"],
    ["edgeActive", "--edge-active"],
    ["edgeDone", "--edge-done"],
    ["nodeSurface", "--surface-raised"],
    ["nodeBorder", "--border-subtle"],
    ["nodeSuccess", "--accent-success"],
    ["nodeFailed", "--accent-danger"],
    ["nodeAborted", "--accent-warning"],
    ["edgeLabelText", "--text-muted"],
    ["edgeLabelBg", "--surface-page"],
  ].forEach(([key, name]) => {
    assert.equal(tokens[key], RESOLVED[name], `${key} (${name}) was not resolved`);
  });
});

/**
 * What Chrome ACTUALLY returned, measured against the live editor on 2026-09-07: a
 * `color-mix(in srgb, …)` token computes to CSS Color 4's `color(srgb …)` form, not to
 * legacy `rgb()`. The first cut of this fix accepted only strings starting with "rgb",
 * so it silently fell back to the raw token and Cytoscape kept logging
 * "The style property `line-color: color-mix(…)` is invalid" — the defect, unfixed,
 * with a green unit test. This table is that measurement, kept.
 */
const CHROME_COLOR4 = {
  "--brand": "color(srgb 0.184314 0.419608 1)",
  "--brand-soft": "color(srgb 0.870588 0.901961 1)",
  "--edge": "color(srgb 0.412745 0.436078 0.480392)",
  "--edge-active": "color(srgb 0.184314 0.419608 1)",
  "--edge-done": "color(srgb 0.082353 0.501961 0.239216)",
  "--surface-raised": "color(srgb 1 1 1)",
  "--border-subtle": "color(srgb 0.698039 0.717647 0.749020)",
  "--accent-success": "color(srgb 0.082353 0.501961 0.239216)",
  "--accent-danger": "color(srgb 0.862745 0.149020 0.149020)",
  "--accent-warning": "color(srgb 0.631373 0.384314 0.027451)",
  "--text-muted": "color(srgb 0.392157 0.454902 0.545098)",
  "--surface-page": "color(srgb 0.972549 0.976471 0.984314)",
};

test("Chrome's color(srgb …) answer is normalised to rgb() — the form Cytoscape parses", () => {
  withDom(ROOT_VARS, { computedAs: CHROME_COLOR4 });
  const { readDesignTokens, buildStylesheet } = loadGraph();

  const tokens = readDesignTokens();
  assert.equal(tokens.edgeIdle, "rgb(105, 111, 122)");
  assert.equal(tokens.brandSoft, "rgb(222, 230, 255)");

  const serialised = JSON.stringify(buildStylesheet(tokens));
  assert.ok(!/color-mix/.test(serialised), "a color-mix expression reached the stylesheet");
  assert.ok(!/color\(srgb/.test(serialised), "a color() expression reached the stylesheet — Cytoscape cannot parse it either");
});

test("toLegacyRgb converts what it understands and refuses what it does not", () => {
  const { toLegacyRgb } = loadGraph();

  assert.equal(toLegacyRgb("color(srgb 0.412745 0.436078 0.480392)"), "rgb(105, 111, 122)");
  assert.equal(toLegacyRgb("color(srgb 0.5 0.5 0.5 / 0.5)"), "rgba(128, 128, 128, 0.5)");
  assert.equal(toLegacyRgb("rgb(1, 2, 3)"), "rgb(1, 2, 3)", "already legacy — passed through");
  assert.equal(toLegacyRgb("#abc123"), "#abc123", "a hex is a colour Cytoscape parses");
  // Refusing is the point: a guess here would paint a WRONG colour with no signal.
  // The canvas sampler behind it is the general answer for these.
  assert.equal(toLegacyRgb("oklch(0.5 0.1 200)"), null);
  assert.equal(toLegacyRgb(""), null);
  assert.equal(toLegacyRgb(null), null);
});

test("the probe is removed again — the page keeps no measuring element", () => {
  const dom = withDom(ROOT_VARS);
  loadGraph().readDesignTokens();

  // 093: one probe per TOKEN read now (a reused one answered its first colour for all
  // twelve on the site3 stack) — and every one of them is taken back out.
  assert.ok(dom.created.length >= 6, "one probe per token read");
  assert.equal(dom.body.children.length, 0, "…and every one is taken back out");
});

test("an UNDECLARED token still falls back to the mock's hex", () => {
  // A stale theme file must degrade to the 080 light values, never blank the graph.
  const bare = { "--pe-card-w": "236px", "--pe-card-h": "148px" };
  withDom(bare);
  const tokens = loadGraph().readDesignTokens();

  assert.equal(tokens.edgeIdle, "#94a3b8");
  assert.equal(tokens.brandSoft, "#dbeafe");
});

test("a probe that cannot answer leaves the raw token — the pre-082 behaviour, not a blank", () => {
  withDom(ROOT_VARS, { probeBlind: true });
  const tokens = loadGraph().readDesignTokens();

  assert.equal(tokens.edgeActive, "#2f6bff", "a plain hex is still usable as declared");
  assert.ok(/color-mix/.test(tokens.edgeIdle), "…and the mixed one degrades to what 080 shipped");
});

test("Cytoscape is initialised with the DEFAULT wheel sensitivity", () => {
  // 080 set `wheelSensitivity: 0.3`; Cytoscape warns on every init that a custom value
  // is unsupported, and the owner reads that warning on every editor open. The ± controls
  // are the supported way to change the feel.
  const source = require("node:fs").readFileSync(graphPath, "utf8");
  const config = source
    .slice(source.indexOf("this.cy = cytoscape({"), source.indexOf("// The HTML card overlay"))
    .split("\n")
    .filter((line) => !/^\s*\/\//.test(line)) // the comment explaining the removal names it
    .join("\n");
  assert.ok(!/wheelSensitivity\s*:/.test(config), "wheelSensitivity is back in the cytoscape config");
});

test("093: twelve tokens read through a browser that answers a reused probe with its first colour are still twelve DISTINCT colours", () => {
  // The site3 capture: every card, edge and label in --brand. One span, twelve inline
  // writes, every getComputedStyle after the first returned the FIRST value.
  const { created } = withDom(ROOT_VARS, { stickyProbe: true });
  const { readDesignTokens } = loadGraph();
  const tokens = readDesignTokens();

  assert.equal(tokens.nodeSurface, "rgb(255, 255, 255)", "the node surface is the brand colour again");
  assert.equal(tokens.brand, "rgb(47, 107, 255)");
  assert.notEqual(tokens.edgeIdle, tokens.brand);
  const colours = Object.values(tokens).filter((v) => typeof v === "string" && /^rgb/.test(v));
  assert.ok(new Set(colours).size >= 6, `expected distinct colours, got ${JSON.stringify(tokens)}`);
  // …because every read got its own element, none of which outlived the call.
  assert.ok(created.filter((el) => el.style.color).length >= 6, "one probe element per read");
  assert.equal(created.filter((el) => el.parentNode).length, 0, "no probe left in the document");
});
