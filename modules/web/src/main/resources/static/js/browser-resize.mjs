/*
 * #554 — the resource browser's width (record §4 "Browser resizing"), on the browser/content
 * boundary (`#browser-boundary`, a separator). Replaces 465's rail resize: the menu has fixed
 * expanded/compact widths now, and the old `dp-rail-width:<ws>` measured a different
 * composition, so it is removed on sight (rail.js), never reinterpreted.
 *
 * Two numbers, kept apart: the PREFERENCE (`--app-browser-preferred` on <html>, persisted as
 * `dp-browser-width:<ws>`, always within 240–480) and the EFFECTIVE width the stylesheet
 * derives from it (`--app-browser-width`: clamped to what leaves main its 320px). A narrow
 * window changes the effective width only; the preference survives for the wider screen.
 */
export const BROWSER_MIN = 240;
export const BROWSER_MAX = 480;
export const KEY_STEP = 16;

/** The widest the browser may be at this viewport: its own cap, less what main and the menu need. */
export function browserMaximum(viewport, menu, minMain) {
  return Math.max(BROWSER_MIN, Math.min(BROWSER_MAX, viewport - menu - minMain));
}

/** The width a preference renders at — the stylesheet's clamp(), where the minimum wins. */
export function effectiveWidth(preferred, maximum) {
  return Math.max(BROWSER_MIN, Math.min(preferred, maximum));
}

/** A stored or written preference, or null when it is not one (anything outside 240–480). */
export function parsePreference(raw) {
  if (raw === null || raw === undefined) return null;
  const text = String(raw).trim().replace(/px$/, "");
  if (!/^\d+$/.test(text)) return null;
  const width = Number(text);
  return width >= BROWSER_MIN && width <= BROWSER_MAX ? width : null;
}

/** The next preference for a key on the separator, from the width the reader sees; null for any other key. */
export function keyedWidth(key, current, maximum) {
  if (key === "ArrowRight") return Math.min(maximum, current + KEY_STEP);
  if (key === "ArrowLeft") return Math.max(BROWSER_MIN, current - KEY_STEP);
  if (key === "Home") return BROWSER_MIN;
  if (key === "End") return maximum;
  return null;
}

export function mountBrowserResize(document) {
  const window = document.defaultView;
  const html = document.documentElement;
  const browser = document.getElementById("app-browser");
  const separator = document.getElementById("browser-boundary");
  if (!browser || !separator) return;
  const workspace = html.dataset.dpWorkspace;
  const key = workspace ? `dp-browser-width:${workspace}` : null;
  let pointer = null;

  const px = name => Number.parseFloat(window.getComputedStyle(html).getPropertyValue(name)) || 0;
  const maximum = () => browserMaximum(html.clientWidth, px("--app-menu-width"), px("--app-main-min-width"));
  const preferred = () => parsePreference(html.style.getPropertyValue("--app-browser-preferred")) ?? px("--app-browser-default");

  function sync() {
    const max = maximum();
    separator.setAttribute("aria-valuemin", String(BROWSER_MIN));
    separator.setAttribute("aria-valuemax", String(max));
    separator.setAttribute("aria-valuenow", String(Math.round(effectiveWidth(preferred(), max))));
  }
  function apply(width) {
    const next = Math.round(Math.max(BROWSER_MIN, Math.min(BROWSER_MAX, width)));
    html.style.setProperty("--app-browser-preferred", `${next}px`);
    try { if (key) window.localStorage.setItem(key, String(next)); } catch (_) { /* preference only */ }
    sync();
  }
  function reset() {
    html.style.removeProperty("--app-browser-preferred");
    try { if (key) window.localStorage.removeItem(key); } catch (_) { /* preference only */ }
    sync();
  }

  const move = event => {
    if (pointer !== event.pointerId) return;
    apply(Math.min(maximum(), event.clientX - browser.getBoundingClientRect().left));
  };
  const finish = event => {
    if (pointer !== event.pointerId) return;
    if (separator.hasPointerCapture(pointer)) separator.releasePointerCapture(pointer);
    pointer = null;
  };
  separator.addEventListener("pointerdown", event => {
    if (event.button !== 0) return;
    event.preventDefault(); pointer = event.pointerId; separator.setPointerCapture(pointer);
  });
  separator.addEventListener("pointermove", move);
  separator.addEventListener("pointerup", finish); separator.addEventListener("pointercancel", finish);
  separator.addEventListener("lostpointercapture", () => { pointer = null; });
  separator.addEventListener("keydown", event => {
    const next = keyedWidth(event.key, browser.getBoundingClientRect().width, maximum());
    if (next === null) return;
    event.preventDefault(); apply(next);
  });
  document.getElementById("browser-width-reset")?.addEventListener("click", reset);
  // A viewport change moves the effective width and the range, never the preference.
  window.addEventListener("resize", sync);
  document.addEventListener("dp:browser-visibility", sync);
  sync();
}
if (typeof document !== "undefined") mountBrowserResize(document);
