(function () {
  "use strict";

  /*
   * 065 §C — the node inspector's OPEN/CLOSE state and its focus contract.
   *
   * Pinned here rather than in init.js because the two rules that break silently
   * are both state rules, not rendering rules:
   *
   *   1. Focus RETURNS to the control that opened the panel. This module REMEMBERS
   *      the opener; it does not decide how to REACH it. That split is load-bearing:
   *      a node-list row is a stable element, but a card button is drawn inside a
   *      Cytoscape html-label that re-renders its template on every `data`/`style`
   *      event — and selecting the node IS a style event, so the captured button is
   *      reliably DETACHED by the time the panel closes (measured on the demo stack,
   *      2026-09-04: `isConnected === false` after every open, at 0.5, 1.0 and 2.0
   *      zoom), and `focus()` on a detached element is a silent no-op. init.js's
   *      `restoreFocusTo` therefore treats the handle as a HINT and falls back to
   *      re-finding the live button by its node id — which is why `nodeId` is kept
   *      beside the handle and must be read BEFORE close() clears both.
   *   2. Opening from a SECOND card replaces the content IN PLACE. A close-then-open
   *      would flash the scrim, re-run the entry transition and (worse) hand focus
   *      back to the first card between the two. `changes` records what actually
   *      happened — a test can assert the sequence never contains a "close".
   *
   * Pure: elements are opaque handles here (only `.focus()` is ever called on the
   * one the caller hands back), so `node --test` drives it with the a11y suite's
   * fake elements.
   */
  function createInspector() {
    return {
      open: false,
      nodeId: null,
      /* The element focus goes back to on close — the card button that opened it. */
      returnTo: null,
      /* "open" | "replace" | "close", in order. The no-flicker guard reads this. */
      changes: [],

      /**
       * Open (or re-target) the panel for `nodeId`, remembering `trigger` as the
       * focus return point. Returns "open" on a fresh open and "replace" when the
       * panel was already up — the caller skips the entry transition on a replace.
       */
      openFrom: function (nodeId, trigger) {
        var replaced = this.open;
        this.nodeId = nodeId;
        // The LAST opener is the return point: after A → B, Esc belongs to B's card.
        if (trigger !== undefined && trigger !== null) this.returnTo = trigger;
        this.open = true;
        this.changes.push(replaced ? "replace" : "open");
        return replaced ? "replace" : "open";
      },

      /**
       * Close. Returns the element the caller must focus (or null) and forgets it,
       * so a second close cannot steal focus back from wherever the user has gone.
       */
      close: function () {
        if (!this.open) return null;
        var back = this.returnTo;
        this.open = false;
        this.nodeId = null;
        this.returnTo = null;
        this.changes.push("close");
        return back;
      },

      /** Esc closes the inspector — and is the ONLY surface Esc owns below the modal. */
      handleEscape: function () {
        if (!this.open) return null;
        return { closed: true, focus: this.close() };
      },
    };
  }

  var api = { createInspector: createInspector };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.PEInspector = api;
})();
