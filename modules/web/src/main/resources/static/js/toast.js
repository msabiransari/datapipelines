/*
 * Toast notifications (ui-screens.md §5.1 Notifications): lifecycle for the
 * server-rendered .ds-toast fragments appended to the #toast stack. A controller
 * renders partials/toast, htmx appends it, and this observer arms the auto-dismiss
 * timer and the close button. Exit is the design system's own animation: .exiting
 * plays ds-toast-out, then the node is removed (a fallback timer covers
 * reduced-motion, where no animationend fires).
 *
 * Two exceptions to "no markup, no swaps": show() is the ONE client-side builder
 * (Shape D, for stream-borne events with no HTTP response to hang an OOB swap on),
 * and bridgeErrors() admits a 4xx/5xx that the server explicitly retargeted at
 * #toast (htmx never swaps error responses otherwise).
 *
 * Testability: the module is an IIFE exporting {attach, arm, dismiss, show,
 * bridgeErrors} for `node --test` (modules/web/src/test/js/toast.test.mjs shims
 * the DOM); in the browser it additionally publishes window.DpToast, auto-attaches
 * to #toast on DOMContentLoaded, and installs the error bridge on document.body.
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

  var VARIANTS = ["success", "danger", "warning", "info"];

  /*
   * The ONE client-side toast builder (ui-screens.md §5.1, Shape D). It exists for
   * events that arrive without an HTTP response to hang an OOB swap on — the editor's
   * SSE terminal events. Everything that DOES have a response renders partials/toast
   * server-side; this is not a shortcut past that.
   *
   * Built with createElement + textContent, never innerHTML: title and body carry
   * abort reasons, node ids and error text, none of which is trusted markup.
   * The structure mirrors partials/toast.html exactly; ToastMarkupParityTest pins it.
   */
  function show(variant, title, message, stack) {
    var target = stack || (typeof document !== "undefined" && document.getElementById("toast"));
    if (!target) return null;
    var v = VARIANTS.indexOf(variant) === -1 ? "info" : variant;
    var el = document.createElement("div");
    el.className = "ds-toast ds-toast-" + v;
    el.setAttribute("role", "status");
    var close = document.createElement("button");
    close.setAttribute("type", "button");
    close.className = "ds-toast-close";
    close.setAttribute("aria-label", "Dismiss");
    close.textContent = "×";
    var t = document.createElement("div");
    t.className = "ds-toast-title";
    t.textContent = title;
    var b = document.createElement("div");
    b.className = "ds-toast-body";
    b.textContent = message;
    el.appendChild(close);
    el.appendChild(t);
    el.appendChild(b);
    target.appendChild(el);
    // The stack's observer arms it; arm directly too, so a stack that was never
    // attach()ed (a test double, a page without the bootstrap) still auto-dismisses.
    arm(el);
    return el;
  }

  /*
   * htmx does not swap 4xx/5xx AT ALL: htmx.config.responseHandling maps [45].. to
   * {swap:false}, and HX-Retarget/HX-Reswap only set the target — the swap itself is
   * gated on shouldSwap. So a refusal that wants to be a toast has to say so and be
   * let through here. This is the same job htmx's response-targets extension does;
   * doing it in twelve lines keeps the dependency count at zero (ui-screens.md §5.1).
   *
   * Deliberately narrow: it opts in ONLY when the server asked for #toast by header,
   * so an ordinary error still behaves exactly as before. isError is left true, so
   * htmx:responseError listeners and error telemetry still fire.
   */
  function bridgeErrors(root) {
    if (!root || !root.addEventListener) return;
    root.addEventListener("htmx:beforeSwap", function (event) {
      var detail = event && event.detail;
      if (!detail || detail.shouldSwap || !detail.xhr) return;
      if (detail.xhr.getResponseHeader("HX-Retarget") !== "#toast") return;
      detail.shouldSwap = true;
    });
  }

  var api = {
    attach: attach,
    arm: arm,
    dismiss: dismiss,
    show: show,
    bridgeErrors: bridgeErrors,
    DISMISS_AFTER_MS: DISMISS_AFTER_MS,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DpToast = api;
    document.addEventListener("DOMContentLoaded", function () {
      attach(document.getElementById("toast"));
      bridgeErrors(document.body);
    });
  }
})();
