(function () {
  "use strict";

  /*
   * 104 §A — ONE splitter, two panes.
   *
   * The owner's report was about the editor's bottom dock ("it has a fixed height … I want
   * to drag the height"), but the explorers' tree pane had already produced the same request
   * three times (too narrow at 480px, too wide at 908px, then the divider). Both are the same
   * mechanism — a pane whose size is a stylesheet's guess — so there is one module, not two:
   * a PURE core (`node --test` owns it, splitter.test.mjs, the decision dock.js's state table
   * got) plus a DOM adapter that binds a `role="separator"` handle.
   *
   * ## The one thing it writes
   *
   * A size lands as ONE CSS custom property, and the stylesheets size from it:
   *
   *     --pe-dock-pane-h   pipeline-editor.css  .pe-dock's second grid row
   *     --tplx-tree-w      template-tree.css    .tplx-tree's width
   *
   * Both are declared with the shipped default as the `var()` fallback, so an unset property
   * IS the default — nothing to seed, nothing to reset to but "remove it".
   *
   * The property is written on `document.documentElement`, not on the pane's own container,
   * for two reasons that only showed up when this was tried the other way:
   *
   *  - the pane element does not exist yet when this module runs (the module is a
   *    parser-blocking script ABOVE the markup, which is what makes the remembered size
   *    apply BEFORE first paint — 090's stylesheet lesson, one layer up: a size applied
   *    after the pane paints is a layout shift, and no inline script is allowed here);
   *  - `#app-main` is htmx's boosted-swap target, so a property on the pane dies with every
   *    navigation while a property on the root survives them all.
   *
   * `InlineWidthAuditTest` bans a static `style="…"` attribute in a TEMPLATE; this is a
   * script setting a custom property on the root at runtime, which is the shape that audit's
   * KDoc names as allowed (and the one a style CSP permits).
   *
   * ## What is pure and what is not
   *
   * Pure: the clamp (`sizeFor`), the keyboard steps (`stepFor`), the stored-value guard
   * (`parseStored`). Everything a decision. Not pure: pointer capture, focus, localStorage,
   * and the per-pane bounds — those are measured off a live layout and the browser suites
   * own them.
   */

  var STEP = 16;
  var SHIFT_STEP = 64;

  /** Per-pane localStorage keys. Both explorers share ONE key: the width follows the user. */
  var KEYS = {
    editorDock: "dp.pane.editor-dock",
    explorerTree: "dp.pane.explorer-tree",
  };

  /** A stored size this large is not a pane, it is corruption. */
  var MAX_STORED = 100000;

  function num(v) {
    return typeof v === "number" && isFinite(v);
  }

  /**
   * The floor wins over the ceiling. `max` for the dock is `stage height − 160px`, which goes
   * NEGATIVE on a very short window; a naive `min(max(v, min), max)` would answer that
   * negative number and collapse the pane. The floor is the contract.
   */
  function clamp(value, min, max) {
    var lo = num(min) ? min : 0;
    var hi = num(max) ? max : Infinity;
    if (hi < lo) hi = lo;
    if (!num(value)) return lo;
    return Math.min(Math.max(value, lo), hi);
  }

  /**
   * The size a drag has reached. `start` is the pane's size when the pointer went down;
   * `dx`/`dy` are the pointer's travel since. `invert` is for a handle on the pane's leading
   * edge — the dock's handle is on its TOP, so the pointer moving UP (negative dy) makes the
   * dock TALLER.
   *
   * Rounded to whole pixels: a fractional grid row blurs the 1px border it draws against.
   */
  function sizeFor(spec) {
    var s = spec || {};
    var delta = s.axis === "x" ? s.dx : s.dy;
    if (!num(delta)) delta = 0;
    if (s.invert) delta = -delta;
    var start = num(s.start) ? s.start : NaN;
    return Math.round(clamp(start + delta, s.min, s.max));
  }

  /**
   * A key press on the separator. Arrows step 16px along the handle's OWN axis (Shift 64px),
   * Home/End park on the floor/ceiling, and every other key — including the perpendicular
   * arrows, which belong to the page — returns null so the handler leaves the event alone.
   */
  function stepFor(spec) {
    var s = spec || {};
    var step = s.shiftKey ? SHIFT_STEP : STEP;
    var current = num(s.current) ? s.current : NaN;
    var grow = s.axis === "x" ? "ArrowRight" : "ArrowUp";
    var shrink = s.axis === "x" ? "ArrowLeft" : "ArrowDown";
    if (!s.invert && s.axis === "y") {
      grow = "ArrowDown";
      shrink = "ArrowUp";
    }
    if (s.invert && s.axis === "x") {
      grow = "ArrowLeft";
      shrink = "ArrowRight";
    }
    if (s.key === grow) return Math.round(clamp(current + step, s.min, s.max));
    if (s.key === shrink) return Math.round(clamp(current - step, s.min, s.max));
    if (s.key === "Home") return Math.round(clamp(s.min, s.min, s.max));
    if (s.key === "End") return Math.round(clamp(s.max, s.min, s.max));
    return null;
  }

  /** A remembered size, or null. Rubbish, zero, negatives and absurd values are refused. */
  function parseStored(raw) {
    if (raw === null || raw === undefined || raw === "") return null;
    var n = Number(raw);
    if (!isFinite(n) || n <= 0 || n > MAX_STORED) return null;
    return Math.round(n);
  }

  /* ------------------------------------------------------------ storage (guarded) */

  function readStored(key) {
    try {
      return parseStored(window.localStorage.getItem(key));
    } catch (e) {
      /* Private mode, blocked storage: the pane keeps its stylesheet default. */
      return null;
    }
  }

  function writeStored(key, value) {
    try {
      if (value === null) window.localStorage.removeItem(key);
      else window.localStorage.setItem(key, String(value));
    } catch (e) {
      /* Nothing to do: the size is applied, it just will not be remembered. */
    }
  }

  /* ------------------------------------------------------------------ DOM adapter */

  function root() {
    return document.documentElement;
  }

  function applyProp(prop, value) {
    if (value === null) root().style.removeProperty(prop);
    else root().style.setProperty(prop, value + "px");
  }

  /**
   * Restore a remembered size onto the root. Called at MODULE LOAD, before the pane's markup
   * is parsed — that is the whole point of the parser-blocking script tag.
   */
  function restore(prop, key) {
    var stored = readStored(key);
    if (stored !== null) applyProp(prop, stored);
    return stored;
  }

  /**
   * The measured size of a pane RIGHT NOW, whatever produced it — a remembered value, the
   * stylesheet's clamp, or a drag in flight. Read off the live element so a reset ("what is
   * the default?") never has to re-derive a `clamp()` in JS.
   */
  function measure(el, axis) {
    if (!el) return NaN;
    var box = el.getBoundingClientRect();
    return axis === "x" ? box.width : box.height;
  }

  /**
   * Bind one splitter handle. The shared contract is the `data-splitter="<pane key>"`
   * attribute, not a class: `app.css` belongs to another lane this round, so each pane's own
   * stylesheet paints its own handle (`.pe-dock-splitter`, `.tplx-splitter`) and the marker is
   * what this module finds them by.
   *
   * `opts`: { handle, pane, axis, invert, prop, storageKey, container, label,
   *           bounds: () => ({min, max}), onChange: (size) => void }
   *
   * `container` is the element that carries `is-dragging` — the CSS uses it to kill the
   * dock's `transition: grid-template-rows` for the duration of a drag (§D: a transition
   * during a drag is a pane that lags the pointer, and it is exactly what
   * `prefers-reduced-motion` users must never see).
   */
  function bind(opts) {
    var handle = opts.handle;
    if (!handle || handle.__dpSplitterBound) return null;
    handle.__dpSplitterBound = true;

    var axis = opts.axis;
    var pane = opts.pane;
    var state = { start: 0, pointerId: null, min: 0, max: 0 };

    /* The pane's size RIGHT NOW. The tree is one element and measures itself; the dock's
       pane is a grid ROW holding four tab panels of which three are `x-show`-hidden, so
       measuring any one of them would read 0 — `opts.measure` is how the dock hands over
       `dock height − tabs height` instead of a wrong element. */
    function current() {
      return opts.measure ? opts.measure() : measure(pane, axis);
    }

    handle.setAttribute("role", "separator");
    handle.setAttribute("aria-orientation", axis === "x" ? "vertical" : "horizontal");
    handle.setAttribute("tabindex", "0");
    if (opts.label) handle.setAttribute("aria-label", opts.label);

    function bounds() {
      var b = opts.bounds ? opts.bounds() : { min: 0, max: Infinity };
      return { min: num(b.min) ? b.min : 0, max: num(b.max) ? b.max : Infinity };
    }

    /** aria-valuemin/max/now, every time the size or the window moves. */
    function publish(size) {
      var b = bounds();
      handle.setAttribute("aria-valuemin", String(Math.round(b.min)));
      handle.setAttribute("aria-valuemax", String(Math.round(isFinite(b.max) ? b.max : b.min)));
      handle.setAttribute("aria-valuenow", String(Math.round(num(size) ? size : current())));
    }

    function commit(size) {
      withoutTransition(function () {
        applyProp(opts.prop, size);
      });
      writeStored(opts.storageKey, size);
      publish(size);
      if (opts.onChange) opts.onChange(size);
    }

    /** Double-click: forget the size and fall back to the stylesheet's own default. */
    function reset() {
      withoutTransition(function () {
        applyProp(opts.prop, null);
      });
      writeStored(opts.storageKey, null);
      if (opts.onChange) opts.onChange(current());
      publish(current());
    }

    /**
     * `is-dragging` kills the container's size transition (§D). It is NOT only for pointer
     * drags: the dock's `transition: grid-template-rows 0.2s` exists for the COLLAPSE, and it
     * would otherwise animate a keyboard step and a double-click reset too — a pane that eases
     * toward a size the user has already chosen, and motion `prefers-reduced-motion` asked not
     * to have. Every commit therefore suppresses it, a drag for its whole duration and a
     * discrete change for one frame.
     */
    function suppress(on) {
      if (opts.container) opts.container.classList.toggle("is-dragging", !!on);
    }

    /**
     * Run `fn` (the property write) with the transition off, and — this is the part that is
     * easy to get wrong — FLUSH the layout before turning it back on. `requestAnimationFrame`
     * fires BEFORE the frame's layout and paint, so re-enabling the transition there would
     * re-enable it in time for the very change being made, and the pane would ease to the new
     * size after all. Reading `offsetHeight` while the class is still on commits the new size
     * synchronously; only then is it safe to release.
     */
    function withoutTransition(fn) {
      var midDrag = state.pointerId !== null;
      if (!midDrag) suppress(true);
      fn();
      if (midDrag) return;
      if (opts.container) void opts.container.offsetHeight;
      if (typeof requestAnimationFrame === "function") {
        requestAnimationFrame(function () {
          suppress(false);
        });
      } else {
        suppress(false);
      }
    }

    /** The resize cursor and the selection lock, while a captured pointer crosses the page. */
    function cursorLock(on) {
      root().classList.toggle("dp-splitting-" + axis, !!on);
    }

    handle.addEventListener("pointerdown", function (ev) {
      if (ev.button !== undefined && ev.button !== 0) return;
      var b = bounds();
      state.min = b.min;
      state.max = b.max;
      state.start = current();
      state.pointerId = ev.pointerId;
      state.x = ev.clientX;
      state.y = ev.clientY;
      suppress(true);
      cursorLock(true);
      /* setPointerCapture: the drag survives the pointer leaving the 12px handle, which is
         what every "the divider stops following my mouse" report is. */
      if (handle.setPointerCapture && ev.pointerId !== undefined) handle.setPointerCapture(ev.pointerId);
      /* NOT `ev.preventDefault()` here, deliberately: preventing the default of `pointerdown`
         suppresses the compatibility mouse events it would otherwise produce — including
         `click`, and therefore `dblclick`, which is the reset gesture. Selection is stopped by
         the `user-select: none` the drag class puts on the document instead. */
    });

    handle.addEventListener("pointermove", function (ev) {
      if (state.pointerId === null) return;
      commit(
        sizeFor({
          axis: axis,
          invert: opts.invert,
          min: state.min,
          max: state.max,
          start: state.start,
          dx: ev.clientX - state.x,
          dy: ev.clientY - state.y,
        }),
      );
      ev.preventDefault();
    });

    function endDrag(ev) {
      if (state.pointerId === null) return;
      if (handle.releasePointerCapture && ev && ev.pointerId !== undefined) {
        try {
          handle.releasePointerCapture(ev.pointerId);
        } catch (e) {
          /* already released */
        }
      }
      state.pointerId = null;
      suppress(false);
      cursorLock(false);
    }

    handle.addEventListener("pointerup", endDrag);
    handle.addEventListener("pointercancel", endDrag);
    handle.addEventListener("lostpointercapture", endDrag);

    handle.addEventListener("keydown", function (ev) {
      var b = bounds();
      var next = stepFor({
        axis: axis,
        invert: opts.invert,
        min: b.min,
        max: b.max,
        current: current(),
        key: ev.key,
        shiftKey: ev.shiftKey,
      });
      if (next === null) return;
      commit(next);
      ev.preventDefault();
    });

    handle.addEventListener("dblclick", function (ev) {
      reset();
      ev.preventDefault();
    });

    /* A handle is a control, not content: a native drag-image drag would fight the resize. */
    handle.addEventListener("dragstart", function (ev) {
      ev.preventDefault();
    });

    publish(current());
    return { publish: publish, reset: reset, commit: commit, current: current };
  }

  /* ---------------------------------------------------------------- the two panes */

  /** `fraction` of the viewport's width, in px — the JS side of a `vw` ceiling. */
  function vw(fraction) {
    return (window.innerWidth || 0) * fraction;
  }

  /* The four bounds, each also written in the stylesheet that draws the pane:
     `--pe-dock-min-h` / `--pe-dock-canvas-floor` in pipeline-editor.css, and
     `.tplx-tree`'s `min-width` / `max-width` in template-tree.css. Two of them are
     measured back by the browser suites, so a change on one side that is not made on the
     other goes red rather than quiet. */
  var DOCK_MIN = 120;
  var DOCK_CANVAS_FLOOR = 160;
  var TREE_MIN = 260;
  var TREE_MAX_SHARE = 0.4;

  function wireEditorDock() {
    var dock = document.querySelector(".pe-dock");
    var handle = document.querySelector('[data-splitter="editor-dock"]');
    var body = document.querySelector(".pe-body");
    var tabs = dock && dock.querySelector(".pe-dock-tabs");
    if (!dock || !handle || !body || !tabs) return null;
    /*
     * The pane row's height, read from the RESOLVED custom property rather than from the
     * dock's box. `dockH − tabsH` looked right and was wrong by exactly the dock's 1px
     * `border-top`: every keyboard step then started 1px above where the last one landed and
     * the drift compounded (measured: a 16px ArrowUp moved the property 232 → 249). The
     * property is also the only honest answer while the dock is COLLAPSED — the row is 0px
     * then, but the height the user chose, and that expand must restore, is still this one.
     * The rendered box is the fallback for a browser that cannot resolve it.
     */
    var paneHeight = function () {
      var raw = window.getComputedStyle(root()).getPropertyValue("--pe-dock-pane-h");
      var px = parseFloat(raw);
      if (isFinite(px) && px >= 0) return px;
      return Math.max(0, measure(dock, "y") - measure(tabs, "y"));
    };
    return bind({
      handle: handle,
      pane: dock,
      measure: paneHeight,
      container: dock,
      axis: "y",
      invert: true,
      prop: "--pe-dock-pane-h",
      storageKey: KEYS.editorDock,
      label: "Resize results panel",
      bounds: function () {
        /* Everything the two of them share, less the strip the canvas keeps: `.pe-body` is
           `flex: 1`, so whatever the dock stops using the stage reclaims and the sum is
           constant through a drag. */
        var total = measure(body, "y") + paneHeight();
        return { min: DOCK_MIN, max: total - DOCK_CANVAS_FLOOR };
      },
      /* No `onChange` for the canvas, deliberately. `.pe-body` is `flex: 1`, so a taller
         dock IS a shorter `.pe-stage`, and graph.js already watches the stage with a
         ResizeObserver — routing that observer through `handleStageResize` (104 §B) makes
         the canvas follow a dock drag, a rail collapse and a window resize by one path
         instead of three. Calling the graph from here as well would fit twice per frame. */
    });
  }

  function wireExplorerTree() {
    var handle = document.querySelector('[data-splitter="explorer-tree"]');
    var tree = document.querySelector(".tplx-tree");
    var body = document.querySelector(".tplx-body");
    if (!handle || !tree || !body) return null;
    return bind({
      handle: handle,
      pane: tree,
      container: body,
      axis: "x",
      prop: "--tplx-tree-w",
      storageKey: KEYS.explorerTree,
      label: "Resize navigation tree",
      bounds: function () {
        return { min: TREE_MIN, max: vw(TREE_MAX_SHARE) };
      },
    });
  }

  /* The restore runs NOW, at parse time, above the markup — see the header. The wiring waits
     for the elements. htmx boosts swap `#app-main`, so the wiring re-runs after every swap;
     `bind` is idempotent per handle. */
  if (typeof window !== "undefined" && typeof document !== "undefined") {
    restore("--pe-dock-pane-h", KEYS.editorDock);
    restore("--tplx-tree-w", KEYS.explorerTree);

    var wire = function () {
      wireEditorDock();
      wireExplorerTree();
    };
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire);
    else wire();
    document.addEventListener("htmx:afterSwap", wire);
    window.addEventListener("resize", function () {
      /* The ceiling is viewport-relative; a window that shrank past a remembered size has to
         pull it back in, or the tree owns 90% of a narrow window. */
      var tree = document.querySelector(".tplx-tree");
      if (!tree) return;
      var stored = readStored(KEYS.explorerTree);
      if (stored === null) return;
      var capped = Math.round(clamp(stored, TREE_MIN, vw(TREE_MAX_SHARE)));
      applyProp("--tplx-tree-w", capped);
    });
  }

  var api = {
    sizeFor: sizeFor,
    stepFor: stepFor,
    parseStored: parseStored,
    clamp: clamp,
    bind: bind,
    restore: restore,
    KEYS: KEYS,
    STEP: STEP,
    SHIFT_STEP: SHIFT_STEP,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.DpSplitter = api;
})();
