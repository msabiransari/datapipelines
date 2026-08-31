/*
 * Toast notifications (ui-screens.md §5.1 Notifications): lifecycle for the
 * server-rendered .ds-toast fragments appended to the #toast stack. This script
 * never builds markup — a controller renders partials/toast, htmx appends it,
 * and this observer arms the auto-dismiss timer and the close button. Exit is
 * the design system's own animation: .exiting plays ds-toast-out, then the node
 * is removed (a fallback timer covers reduced-motion, where no animationend
 * fires).
 *
 * Testability: the module is an IIFE exporting {attach, arm, dismiss} for
 * `node --test` (modules/web/src/test/js/toast.test.mjs shims the DOM); in the
 * browser it additionally publishes window.DpToast and auto-attaches to #toast
 * on DOMContentLoaded.
 */
(function () {
  "use strict";

  var DISMISS_AFTER_MS = 6000;
  var EXIT_FALLBACK_MS = 500; // ds-toast-out is --duration-fast; slack for no-animationend

  function dismiss(toast) {
    if (!toast || toast.__dpToastRemoved) return;
    if (toast.classList.contains("exiting")) return;
    toast.classList.add("exiting");
    var remove = function () {
      if (toast.__dpToastRemoved) return;
      toast.__dpToastRemoved = true;
      if (toast.parentNode) toast.parentNode.removeChild(toast);
    };
    toast.addEventListener("animationend", remove, { once: true });
    setTimeout(remove, EXIT_FALLBACK_MS);
  }

  function arm(toast, dismissAfterMs) {
    if (!toast || toast.__dpToastArmed) return;
    toast.__dpToastArmed = true;
    var timer = setTimeout(function () {
      dismiss(toast);
    }, dismissAfterMs || DISMISS_AFTER_MS);
    var close = toast.querySelector(".ds-toast-close");
    if (close) {
      close.addEventListener("click", function () {
        clearTimeout(timer);
        dismiss(toast);
      });
    }
  }

  function armAll(root, dismissAfterMs) {
    var toasts = root.querySelectorAll(".ds-toast");
    for (var i = 0; i < toasts.length; i++) arm(toasts[i], dismissAfterMs);
  }

  function attach(stack, dismissAfterMs) {
    if (!stack) return;
    armAll(stack, dismissAfterMs);
    new MutationObserver(function (mutations) {
      mutations.forEach(function (m) {
        Array.prototype.forEach.call(m.addedNodes, function (node) {
          if (node.nodeType !== 1) return;
          if (node.classList && node.classList.contains("ds-toast")) arm(node, dismissAfterMs);
          else if (node.querySelector) armAll(node, dismissAfterMs);
        });
      });
    }).observe(stack, { childList: true });
  }

  var api = { attach: attach, arm: arm, dismiss: dismiss, DISMISS_AFTER_MS: DISMISS_AFTER_MS };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DpToast = api;
    document.addEventListener("DOMContentLoaded", function () {
      attach(document.getElementById("toast"));
    });
  }
})();
