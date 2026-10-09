/** The preferred width is never overwritten by a temporary viewport clamp. */
export function effectiveWidth(preferred, available, minimum) { return Math.min(available, Math.max(minimum, preferred)); }

/** Host-owned global rail control; width survives navigation, collapse and viewport changes. */
export function mountRailResize(document) {
  const html = document.documentElement; const rail = document.getElementById("app-rail");
  const boundary = document.getElementById("rail-boundary"); const separator = document.getElementById("rail-resize");
  if (!rail || !separator || !boundary) return;
  const key = `dp-rail-width:${html.dataset.dpWorkspace}`;
  let preferred = Number.parseFloat(html.style.getPropertyValue("--app-rail-preferred")) || 232;
  let pointer = null;
  function available() { return html.clientWidth; }
  function apply(width, persist = true) {
    preferred = Math.max(232, Math.min(100000, width));
    html.style.setProperty("--app-rail-preferred", `${preferred}px`);
    separator.setAttribute("aria-valuenow", String(Math.round(effectiveWidth(preferred, available(), 232))));
    separator.setAttribute("aria-valuemax", String(available()));
    if (persist) { try { localStorage.setItem(key, String(Math.round(preferred))); } catch (_) { /* preference only */ } }
  }
  const move = event => { if (pointer === event.pointerId) apply(Math.min(available(), event.clientX)); };
  const finish = event => { if (pointer !== event.pointerId) return; if (separator.hasPointerCapture(pointer)) separator.releasePointerCapture(pointer); pointer = null; };
  separator.addEventListener("pointerdown", event => {
    if (event.button !== 0) return; event.preventDefault(); pointer = event.pointerId; separator.setPointerCapture(pointer);
    html.classList.remove("rail-collapsed"); html.classList.add("rail-expanded");
  });
  separator.addEventListener("pointermove", move);
  separator.addEventListener("pointerup", finish); separator.addEventListener("pointercancel", finish);
  separator.addEventListener("lostpointercapture", () => { pointer = null; });
  separator.addEventListener("keydown", event => {
    const step = event.shiftKey ? 50 : 10;
    let width = rail.getBoundingClientRect().width;
    if (event.key === "ArrowRight") width += step;
    else if (event.key === "ArrowLeft") width -= step;
    else if (event.key === "Home") width = 232;
    else if (event.key === "End") width = available();
    else return;
    event.preventDefault(); html.classList.remove("rail-collapsed"); html.classList.add("rail-expanded"); apply(Math.min(available(), width));
  });
  document.getElementById("rail-width-reset")?.addEventListener("click", () => apply(232));
  document.getElementById("rail-boundary-collapse")?.addEventListener("click", () => {
    const collapsed = html.classList.toggle("rail-collapsed"); html.classList.toggle("rail-expanded", !collapsed);
    try { localStorage.setItem("dp-rail", collapsed ? "1" : "0"); } catch (_) { /* preference only */ }
  });
  window.addEventListener("resize", () => apply(preferred, false)); apply(preferred, false);
}
if (typeof document !== "undefined") mountRailResize(document);
