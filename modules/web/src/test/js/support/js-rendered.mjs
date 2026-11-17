// Shared by the chart kind's Node tests: the js-rendered engine as the page loads it (the files of the engine module's
// LOAD-ORDER.txt, from its main resources, in that order, in a fresh vm context) and a small fake browser for the
// adapter. The fake answers only what the adapter asks: computed custom properties at the chart's svg, the probe's
// computed `color` / `stroke-width` / `font-family`, CSS.supports, a canvas context, the observers and the fonts. Its
// CSS grammar is a deliberately small stand-in; the browser's own grammar is LA4's, in the browser suite.

import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
export const ENGINE_DIR = resolve(here, "../../../../../js-rendered/src/main/resources/co/datapipelines/jsrendered/engine");
export const STATIC_JS = resolve(here, "../../../main/resources/static/js");

/** The engine's file names in load order. */
export function engineFiles() {
  return readFileSync(join(ENGINE_DIR, "LOAD-ORDER.txt"), "utf8").split("\n").filter((l) => l.trim() !== "");
}

/**
 * The `JsRendered` namespace, each file run as the page runs it: one classic script after another in THIS realm, so
 * the engine's arrays and objects compare with the test's own (a second call loads it afresh over the first).
 */
export function loadEngine() {
  for (const name of engineFiles()) vm.runInThisContext(readFileSync(join(ENGINE_DIR, name), "utf8"), { filename: name });
  return vm.runInThisContext("JsRendered");
}

// ------------------------------------------------------------------------------------------- the fake browser

const NAMED = { red: [255, 0, 0], white: [255, 255, 255], black: [0, 0, 0], transparent: [0, 0, 0, 0] };

function hex(text) {
  const m = /^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$/i.exec(text);
  if (!m) return null;
  let h = m[1];
  if (h.length === 3) h = h.replace(/./g, "$&$&");
  const out = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16));
  if (h.length === 8) out.push(parseInt(h.slice(6, 8), 16) / 255);
  return out;
}

/** The fake's computed `color`: what a browser serialises for the forms the tests use; "" for anything else. */
export function computedColour(value) {
  const text = String(value).trim();
  const fromHex = hex(text);
  const named = NAMED[text.toLowerCase()];
  const rgb = fromHex || named;
  if (rgb) return rgb.length === 4 ? `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, ${rgb[3]})` : `rgb(${rgb[0]}, ${rgb[1]}, ${rgb[2]})`;
  if (/^rgba?\([^)]*\)$/i.test(text) || /^color\(srgb [^)]*\)$/i.test(text)) return text;
  return "";
}

function computedLength(value, fontPx) {
  const text = String(value).trim();
  let m;
  if ((m = /^(-?\d*\.?\d+)px$/.exec(text))) return m[1] + "px";
  if ((m = /^(-?\d*\.?\d+)rem$/.exec(text))) return Number(m[1]) * 16 + "px";
  if ((m = /^(-?\d*\.?\d+)em$/.exec(text))) return Number(m[1]) * fontPx + "px";
  if ((m = /^calc\((\d+)px \+ (\d+)px\)$/.exec(text))) return Number(m[1]) + Number(m[2]) + "px";
  return "";
}

/** The fake's CSS.supports: colours it can compute, lengths it can compute, any font-family without a `;`. */
export function supports(property, value) {
  const text = String(value).trim();
  if (property === "color") return computedColour(text) !== "" || /^color-mix\(/.test(text);
  if (property === "width") return computedLength(text, 14) !== "" || text === "auto" || /%$/.test(text);
  if (property === "font-family") return text !== "" && !text.includes(";");
  return false;
}

/**
 * A fake document. `declared` is the cascade at the chart: {customProperty: computedValue}; `fontPx` the svg's font
 * size (for `em`). Records every custom property read, every observer, every animation frame and every canvas font.
 */
export function fakeBrowser({ declared = {}, fontPx = 14, width = 600, height = 400 } = {}) {
  const reads = [];
  const frames = [];
  const fonts = [];
  const observers = { resize: [], mutation: [], media: [] };
  let fontsReady;
  const ready = new Promise((r) => (fontsReady = r));
  const doc = {};
  function element(tag, ns) {
    const attrs = {};
    const listeners = [];
    const el = {
      tagName: tag,
      localName: tag,
      nodeType: 1,
      namespaceURI: ns || null,
      attrs,
      listeners,
      childNodes: [],
      parentNode: null,
      ownerDocument: doc,
      _text: null,
      get firstChild() {
        return el.childNodes[0] || null;
      },
      get nextSibling() {
        if (!el.parentNode) return null;
        const siblings = el.parentNode.childNodes;
        return siblings[siblings.indexOf(el) + 1] || null;
      },
      // The interaction layer's listeners (brief 990 D7): recorded, so a test can dispatch to them and count them.
      addEventListener(type, fn) {
        listeners.push({ type, fn });
      },
      removeEventListener(type, fn) {
        const i = listeners.findIndex((l) => l.type === type && l.fn === fn);
        if (i !== -1) listeners.splice(i, 1);
      },
      /** Calls the listeners of [type] with [event] (a plain object); the test's stand-in for a browser event. */
      dispatch(type, event = {}) {
        let prevented = false;
        const e = Object.assign({ type, preventDefault: () => (prevented = true) }, event);
        listeners.filter((l) => l.type === type).forEach((l) => l.fn(e));
        return prevented;
      },
      /** The element's box on the page: its width and height attributes at the page's origin (1 px per unit). */
      getBoundingClientRect() {
        const w = Number(attrs.width || 0);
        const h = Number(attrs.height || 0);
        return { left: 0, top: 0, width: w, height: h, right: w, bottom: h };
      },
      set textContent(v) {
        el._text = String(v);
        el.childNodes = [];
      },
      get textContent() {
        return el._text !== null ? el._text : el.childNodes.map((c) => c.textContent).join("");
      },
      set innerHTML(v) {
        throw new Error("innerHTML written: " + v);
      },
      insertAdjacentHTML() {
        throw new Error("insertAdjacentHTML called");
      },
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
        if (child.parentNode) child.parentNode.removeChild(child);
        child.parentNode = el;
        el.childNodes.push(child);
        return child;
      },
      removeChild(child) {
        const i = el.childNodes.indexOf(child);
        if (i === -1) throw new Error("not a child");
        el.childNodes.splice(i, 1);
        child.parentNode = null;
        return child;
      },
    };
    return el;
  }
  const win = {
    getComputedStyle(el) {
      if (el.tagName === "svg") {
        return {
          getPropertyValue(name) {
            reads.push(name);
            return name in declared ? String(declared[name]) : "";
          },
        };
      }
      // The probe: a presentation attribute's computed value.
      return {
        color: el.attrs.color !== undefined ? computedColour(el.attrs.color) : "rgb(0, 0, 0)",
        strokeWidth: el.attrs["stroke-width"] !== undefined ? computedLength(el.attrs["stroke-width"], fontPx) : "1px",
        fontFamily: el.attrs["font-family"] !== undefined ? el.attrs["font-family"] : "serif",
      };
    },
    requestAnimationFrame(fn) {
      frames.push(fn);
      return frames.length;
    },
    ResizeObserver: class {
      constructor(fn) {
        this.fn = fn;
        this.connected = false;
        observers.resize.push(this);
      }
      observe(target) {
        this.target = target;
        this.connected = true;
      }
      disconnect() {
        this.connected = false;
      }
    },
    MutationObserver: class {
      constructor(fn) {
        this.fn = fn;
        this.connected = false;
        observers.mutation.push(this);
      }
      observe(target, options) {
        this.target = target;
        this.options = options;
        this.connected = true;
      }
      disconnect() {
        this.connected = false;
      }
    },
    matchMedia(query) {
      const media = {
        query,
        listeners: [],
        addEventListener(type, fn) {
          media.listeners.push(fn);
        },
        removeEventListener(type, fn) {
          media.listeners = media.listeners.filter((f) => f !== fn);
        },
      };
      observers.media.push(media);
      return media;
    },
  };
  Object.assign(doc, {
    defaultView: win,
    documentElement: element("html"),
    fonts: { ready },
    createElementNS: (ns, tag) => element(tag, ns),
    createElement(tag) {
      const el = element(tag);
      if (tag === "canvas") {
        const ctx = {
          font: "",
          measureText(text) {
            fonts.push(ctx.font);
            const size = Number(/(\d+(?:\.\d+)?)px/.exec(ctx.font)[1]);
            return { width: text.length * size * 0.5 };
          },
        };
        el.getContext = () => ctx;
      }
      return el;
    },
  });
  const host = element("div");
  host.clientWidth = width;
  host.clientHeight = height;
  return {
    doc,
    win,
    host,
    reads,
    frames,
    fonts,
    observers,
    fontsReady: () => fontsReady(),
    /** Runs the animation frames queued so far. */
    flushFrames() {
      frames.splice(0).forEach((fn) => fn());
    },
  };
}

/** Every element under [el] (el excluded), depth first. */
export function descendants(el, out = []) {
  for (const child of el.childNodes) {
    out.push(child);
    descendants(child, out);
  }
  return out;
}
