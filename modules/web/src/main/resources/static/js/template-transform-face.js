/*
 * 7d (#7, transform-nodes design §9.3) — the transform editor face's one behaviour: the
 * "unsaved changes" marker.
 *
 * The face's panes are plain textareas; Save draft and Run suite are htmx (the face needs no
 * script for either). What htmx cannot say is that the panes now differ from the SAVED draft
 * — and that matters, because the header's Release publishes the last saved draft, never what
 * is typed. The first keystroke in a pane reveals `#tf-dirty`; a successful save re-renders the
 * whole face, which brings the marker back hidden, so "saved" needs no code of its own.
 *
 * Delegated from the document and armed ONCE (shell.js's window-flag pattern): the face is
 * swapped in by the version select and by every save, and a per-node listener would have to
 * be re-armed after each. Under the CSP (`script-src 'self'`) this file is the page's only
 * transform-face script; it sets no style — `hidden` is an attribute.
 */
(function () {
  "use strict";

  /** True for a field whose edit makes the panes differ from the saved draft. */
  function isPaneField(el) {
    return !!(el && el.closest && el.name && el.closest("#tf-form") && el.tagName === "TEXTAREA");
  }

  /** Reveals the marker for the face containing [el]; returns whether it changed anything. */
  function markDirty(el, doc) {
    if (!isPaneField(el)) return false;
    var marker = (doc || document).getElementById("tf-dirty");
    if (!marker || !marker.hidden) return false;
    marker.hidden = false;
    return true;
  }

  var api = { isPaneField: isPaneField, markDirty: markDirty };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window === "undefined") return;
  window.TplTransformFace = api;
  if (window.__dpTransformFaceArmed) return;
  window.__dpTransformFaceArmed = true;
  document.addEventListener("input", function (evt) {
    markDirty(evt.target, document);
  });
})();
