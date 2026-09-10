/*
 * 102 — the lifecycle dialogs' client half (ui-screens §4.3d): the ONE per-screen container
 * (#px-dialog / #tx-dialog / #pe-dialog / #te-dialog), its close paths, focus, and the typed
 * confirm. Shared by four screens; it owns no verb, no fetch and no toast — the dialogs are
 * htmx partials whose POSTs answer Shape A / HX-Redirect / Shape C, and toast.js owns the
 * toasts. Everything here is chrome, in the ds-dialog lineage (094 §A/§B).
 *
 * FIVE small jobs, no framework:
 *
 * 1. CLOSE. Emptying the container is the ONLY close path (no display toggling, so a stale
 *    form can never hide in it): the dialog's own [data-lifecycle-close] buttons, and Escape.
 * 2. FOCUS. When a dialog lands, focus moves to its first control — a keyboard user opens a
 *    dialog with Enter and must not be left on the opener.
 * 3. TYPED CONFIRM. The irreversible verbs (purge, purge-entity) ship a
 *    [data-typed-confirm] button that stays disabled until [data-confirm-input] carries
 *    exactly the expected text (§5.1's convention; the server re-checks the field and
 *    answers 400 *.confirm_mismatch, so this is convenience, not the guard).
 * 4. CLOSE ON SUCCESS. A Shape A response swaps the DETAIL pane, not the dialog; the hidden
 *    [data-lifecycle-applied] marker riding with it is what closes the dialog — a refusal
 *    (Shape C, retargeted at #toast) never carries the marker, so an error cannot close
 *    over the dialog.
 * 5. MENUS. The version rows' ⋯ overflow menus close on Escape and when a dialog opens.
 */
(function () {
  'use strict';

  var CONTAINERS = ['px-dialog', 'tx-dialog', 'pe-dialog', 'te-dialog'];

  function openContainer() {
    for (var i = 0; i < CONTAINERS.length; i++) {
      var el = document.getElementById(CONTAINERS[i]);
      if (el && el.children.length > 0) return el;
    }
    return null;
  }

  function closeDialog() {
    var container = openContainer();
    if (container) container.innerHTML = '';
  }

  function firstControl(container) {
    if (!container) return null;
    var controls = container.querySelectorAll('input, select, textarea, button:not([data-lifecycle-close])');
    return controls.length > 0 ? controls[0] : container.querySelector('[data-lifecycle-close]');
  }

  /**
   * The typed-confirm rule — the pure half, exported for `node --test` (the shell.js
   * convention): a value matches when it equals the expected text exactly, whitespace
   * trimmed so a trailing space cannot hold the button hostage.
   */
  function confirmMatches(value, expected) {
    return typeof value === 'string' && typeof expected === 'string' &&
      value.trim() === expected;
  }

  function armTypedConfirm(container) {
    if (!container) return;
    var input = container.querySelector('[data-confirm-input]');
    var button = container.querySelector('[data-typed-confirm]');
    if (!input || !button) return;
    var expected = input.getAttribute('data-confirm-expect') || '';
    var evaluate = function () {
      button.disabled = !confirmMatches(input.value, expected);
    };
    input.addEventListener('input', evaluate);
    // The initial state is disabled in the MARKUP (no-JS is safe); evaluate once so a
    // browser-autofilled field cannot leave the button stale either way.
    evaluate();
  }

  function arm(container) {
    if (!container) return;
    armTypedConfirm(container);
    var focus = firstControl(container);
    if (focus) focus.focus();
  }

  // ------------------------------------------------------------------ wiring

  document.addEventListener('click', function (event) {
    if (!event.target.closest) return;
    if (event.target.closest('[data-lifecycle-close]')) {
      closeDialog();
      return;
    }
    // A click that is NOT inside the dialog card closes it (backdrop taps) — but a click
    // inside a dropdown menu of the dialog never does, and neither does a plain text
    // selection drag that ends outside.
    var container = openContainer();
    if (container && event.target === container) closeDialog();
  });

  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') return;
    var container = openContainer();
    if (container) {
      closeDialog();
      return;
    }
    closeMenus();
  });

  // A dialog landing in one of the containers arms it (htmx swaps are afterSettle-clean,
  // but afterSwap is the earliest the controls exist).
  document.body.addEventListener('htmx:afterSwap', function (event) {
    var target = event.detail && event.detail.target;
    if (!target || !target.id) return;
    // The dialog landed.
    if (CONTAINERS.indexOf(target.id) !== -1) {
      arm(target);
      closeMenus();
      return;
    }
    // A Shape A success landed in a detail pane: its marker closes the dialog.
    if ((target.id === 'pipeline-detail' || target.id === 'template-detail') &&
        target.querySelector('[data-lifecycle-applied]')) {
      closeDialog();
    }
  });

  // ------------------------------------------------------------------ 5. menus

  /** Close every open ⋯ overflow menu (the rows' `<details>` popovers). */
  function closeMenus() {
    var menus = document.querySelectorAll('details.tplx-vmenu[open]');
    Array.prototype.forEach.call(menus, function (m) { m.open = false; });
  }

  document.addEventListener('click', function (event) {
    if (!event.target.closest) return;
    if (event.target.closest('details.tplx-vmenu')) return;
    closeMenus();
  });

  var api = { confirmMatches: confirmMatches, closeDialog: closeDialog, closeMenus: closeMenus };
  if (typeof window !== 'undefined') window.lifecycleDialog = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
