/*
 * #9 slice 2 — the Schedules page's small DOM toolkit and its per-visit state.
 *
 * The markup is the SERVER's: every piece the page draws is a <template> skeleton rendered by
 * Thymeleaf (templates/schedules/*.html), so role-gated verbs are decided server-side and
 * absent from a reader's skeleton. This file only clones those skeletons and fills their
 * `data-slot` elements — with textContent, never innerHTML: names, parameter values, reasons
 * and trail text are user data.
 *
 * `window.DpSchedules` survives a boosted navigation away and back; its `state` does not —
 * page.js replaces it on every visit, and every timer checks `state.root.isConnected`.
 */
(function () {
  "use strict";

  var S = (window.DpSchedules = window.DpSchedules || {});

  /** A fresh copy of a skeleton's single root element, or null if the role left it out. */
  S.clone = function (templateId) {
    var t = document.getElementById(templateId);
    if (!t || !t.content || !t.content.firstElementChild) return null;
    return t.content.firstElementChild.cloneNode(true);
  };

  S.slot = function (root, name) {
    return root ? root.querySelector('[data-slot="' + name + '"]') : null;
  };

  /** Sets a slot's text (null/undefined → empty) and returns the element, if the slot exists. */
  S.text = function (root, name, value) {
    var el = S.slot(root, name);
    if (el) el.textContent = value === null || value === undefined ? "" : String(value);
    return el;
  };

  S.show = function (el, visible) {
    if (el) el.hidden = !visible;
  };

  S.clear = function (el) {
    while (el && el.firstChild) el.removeChild(el.firstChild);
  };

  /** A <time> slot: the reader's words in the text, the exact instant on hover and in `datetime`. */
  S.time = function (root, name, iso, words) {
    var el = S.slot(root, name);
    if (!el) return null;
    el.textContent = iso ? words : "—";
    if (iso) {
      el.setAttribute("datetime", iso);
      el.setAttribute("title", iso);
    } else {
      el.removeAttribute("datetime");
      el.removeAttribute("title");
    }
    return el;
  };

  /**
   * A §20 refusal as a toast (ui-screens §5.1: the user message as the headline, the code and
   * the correlation id in small text). toast.js's `show` is the ONE client-side toast builder
   * — this page's outcomes arrive as REST JSON, which carries no server fragment to splice
   * (Shape D; the page's REST-only contract is the record's §6).
   */
  S.toastError = function (err, fallbackTitle) {
    if (!window.DpToast) return;
    var ref = [err && err.code, err && err.correlationId ? "ref " + err.correlationId : ""].filter(Boolean).join(" · ");
    window.DpToast.show("danger", (err && err.userMessage) || fallbackTitle || "That did not work", ref);
  };

  S.toast = function (variant, title, message) {
    if (window.DpToast) window.DpToast.show(variant, title, message || "");
  };

  /** A new idempotency key (model.uuidFrom over getRandomValues — crypto.randomUUID needs a secure context). */
  S.newKey = function () {
    var bytes = new Uint8Array(16);
    window.crypto.getRandomValues(bytes);
    return window.DpSchedulesModel.uuidFrom(bytes);
  };

  /**
   * The page's one dialog container (#sch-dialog, the §4.3d idiom): a dialog is cloned in and
   * removed on close — nothing is display-toggled, so a stale form can never hide there. Focus
   * lands on the first control and returns to the opener on close.
   */
  S.openDialog = function (el, onClose) {
    var host = document.getElementById("sch-dialog");
    if (!host || !el) return null;
    S.closeDialog(true);
    S.state.dialogOpener = document.activeElement;
    S.state.onDialogClose = onClose || null;
    host.appendChild(el);
    var first = el.querySelector(
      "input:not([type=hidden]):not([disabled]):not([readonly]), select:not([disabled]), textarea, .app-modal button:not(.app-modal-close)",
    );
    if (first) first.focus();
    return el;
  };

  S.closeDialog = function (silent) {
    var host = document.getElementById("sch-dialog");
    if (!host || !host.firstChild) return;
    S.clear(host);
    var after = S.state.onDialogClose;
    S.state.onDialogClose = null;
    if (after) after();
    var opener = S.state.dialogOpener;
    S.state.dialogOpener = null;
    if (!silent && opener && opener.isConnected) opener.focus();
  };

  S.dialog = function () {
    var host = document.getElementById("sch-dialog");
    return host ? host.firstElementChild : null;
  };

  /** True while the page this state belongs to is still the one on screen. */
  S.live = function () {
    return !!(S.state && S.state.root && S.state.root.isConnected);
  };

  /** The page's deep link for a schedule (and a run), kept in the address bar as the reader moves. */
  S.url = function (scheduleId, runId) {
    var parts = [];
    if (scheduleId) parts.push("id=" + encodeURIComponent(scheduleId));
    if (runId) parts.push("run=" + encodeURIComponent(runId));
    return "/schedules" + (parts.length ? "?" + parts.join("&") : "");
  };

  S.remember = function (scheduleId, runId) {
    try {
      if (window.history && window.history.replaceState) window.history.replaceState(window.history.state, "", S.url(scheduleId, runId));
    } catch (e) {
      /* A refused history write costs the deep link only. */
    }
  };
})();
