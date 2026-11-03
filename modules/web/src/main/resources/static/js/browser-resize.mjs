/*
 * #554 — the resource browser's width (record §4 "Browser resizing"), on the browser/content
 * boundary (`#browser-boundary`, a separator). Replaces 465's rail resize: the menu has fixed
 * expanded/compact widths now, and the old `dp-rail-width:<ws>` measured a different
 * composition, so it is removed on sight (rail.js), never reinterpreted.
 *
 * Two widths, kept apart: the PREFERENCE (`--app-browser-preferred` on <html>, persisted as
 * `dp-browser-width:<ws>`, always within the user's range) and the EFFECTIVE width the
 * stylesheet derives from it (`--app-browser-width`: clamped to what leaves main its own
 * minimum). A narrow window changes the effective width only; the preference survives for
 * the wider screen.
 *
 * #788 — the RANGE is the stylesheet's, not ours: app.css's `:root` declares
 * `--app-browser-min` and `--app-browser-max` (the clamp's own operands — that sheet is the
 * range's one source). They are read as computed styles at mount and handed to every
 * function below; a sheet that does not resolve to a positive range leaves the control
 * unmounted, loudly, rather than guessing at a literal here.
 */
export const KEY_STEP = 16;

/** The widest the browser may be at this viewport: its own cap, less what main and the menu need. */
export function browserMaximum(viewport, menu, minMain, range) {
  return Math.max(range.min, Math.min(range.max, viewport - menu - minMain));
}

/** The width a preference renders at — the stylesheet's clamp(), where the minimum wins. */
export function effectiveWidth(preferred, maximum, range) {
  return Math.max(range.min, Math.min(preferred, maximum));
}

/** A stored or written preference, or null when it is not one (anything outside the range). */
export function parsePreference(raw, range) {
  if (raw === null || raw === undefined) return null;
  const text = String(raw).trim().replace(/px$/, "");
  if (!/^\d+$/.test(text)) return null;
  const width = Number(text);
  return width >= range.min && width <= range.max ? width : null;
}

/** The next preference for a key on the separator, from the width the reader sees; null for any other key. */
export function keyedWidth(key, current, maximum, range) {
  if (key === "ArrowRight") return Math.min(maximum, current + KEY_STEP);
  if (key === "ArrowLeft") return Math.max(range.min, current - KEY_STEP);
  if (key === "Home") return range.min;
  if (key === "End") return maximum;
  return null;
}

export function mountBrowserResize(document) {
  const window = document.defaultView;
  const html = document.documentElement;
  const browser = document.getElementById("app-browser");
  const separator = document.getElementById("browser-boundary");
  if (!browser || !separator) return;
  const px = name => Number.parseFloat(window.getComputedStyle(html).getPropertyValue(name)) || 0;
  const range = { min: px("--app-browser-min"), max: px("--app-browser-max") };
  if (range.min <= 0 || range.max <= 0 || range.min > range.max) {
    console.error(
      "browser-resize: app.css's --app-browser-min/--app-browser-max did not resolve to a " +
        "positive range; the width control stays unmounted rather than guess at a literal.",
    );
    return;
  }
  const workspace = html.dataset.dpWorkspace;
  const key = workspace ? `dp-browser-width:${workspace}` : null;
  let pointer = null;

  const maximum = () => browserMaximum(html.clientWidth, px("--app-menu-width"), px("--app-main-min-width"), range);
  const preferred = () =>
    parsePreference(html.style.getPropertyValue("--app-browser-preferred"), range) ?? px("--app-browser-default");

  function sync() {
    const max = maximum();
    separator.setAttribute("aria-valuemin", String(range.min));
    separator.setAttribute("aria-valuemax", String(max));
    separator.setAttribute("aria-valuenow", String(Math.round(effectiveWidth(preferred(), max, range))));
  }
  function apply(width) {
    const next = Math.round(Math.max(range.min, Math.min(range.max, width)));
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
    const current = Math.round(browser.getBoundingClientRect().width);
    const next = keyedWidth(event.key, current, maximum(), range);
    if (next === null) return;
    // A press that cannot move the column (an arrow at the clamp) writes nothing: the clamp
    // must never overwrite the preference a wider screen will honour again.
    event.preventDefault(); if (next !== current) apply(next);
  });
  document.getElementById("browser-width-reset")?.addEventListener("click", reset);
  // A viewport change moves the effective width and the range, never the preference.
  window.addEventListener("resize", sync);
  document.addEventListener("dp:browser-visibility", sync);
  // The menu's collapse (a class on <html>) moves the range too.
  new window.MutationObserver(sync).observe(html, { attributes: true, attributeFilter: ["class"] });
  sync();
}
if (typeof document !== "undefined") mountBrowserResize(document);
