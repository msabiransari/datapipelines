(function () {
  "use strict";

  var editor = null;

  function setupA11y(context) {
    editor = context;
    buildNodeList();
    installEscapeHandler();
  }

  // §14.1: Escape closes the TOPMOST open surface — the error modal, then the node
  // inspector — one per press. 065 §B removed the middle rung: the dock has no
  // close and Esc is a NO-OP on it, so a key press aimed at the panel above can no
  // longer take the results away. Closing the inspector hands focus back to the
  // card button that opened it (inspector.js owns the reference). Attached once:
  // setupA11y re-runs would otherwise stack duplicate listeners.
  var escapeInstalled = false;
  function installEscapeHandler() {
    if (escapeInstalled || typeof document.addEventListener !== "function") return;
    escapeInstalled = true;
    document.addEventListener("keydown", function (e) {
      if (e.key !== "Escape" || !editor) return;
      if (editor.errorModal && editor.errorModal.visible) {
        editor.errorModal.visible = false;
        editor.errorModal.message = "";
      } else if (editor.inspector && editor.inspector.open) {
        editor.closeNodeDetails();
      } else {
        return;
      }
      e.preventDefault();
    });
  }

  function buildNodeList() {
    if (!editor || !editor.nodes) return;
    var list = document.getElementById("pe-node-list");
    if (!list) return;

    list.innerHTML = "";
    for (var i = 0; i < editor.nodes.length; i++) {
      var node = editor.nodes[i];
      var li = document.createElement("li");
      li.className = "pe-node-list-item";
      li.setAttribute("role", "option");
      li.setAttribute("aria-selected", "false");
      // Roving tabindex: exactly one item is tabbable, arrows move it (and focus).
      // Without ANY tabindex an <li> cannot take focus and the keydown handlers
      // below never fired — the keyboard path was dead (031 finding).
      li.setAttribute("tabindex", i === 0 ? "0" : "-1");
      li.setAttribute("data-state", "idle");
      li.setAttribute("data-node-id", node.id);
      li.textContent = (node.display_name || node.name || node.id);
      li.addEventListener("click", function () {
        var id = this.getAttribute("data-node-id");
        editor.selectNodeById(id);
      });
      li.addEventListener("keydown", function (e) {
        // 065 §C keyboard parity: a click on the row SELECTS (what tapping a card
        // does); Enter/Space on the selected row OPENS the inspector (what the
        // card's button does), and this row is the element focus returns to.
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          var id = this.getAttribute("data-node-id");
          if (editor.openNodeDetails) editor.openNodeDetails(id, this);
          else editor.selectNodeById(id);
        }
        if (e.key === "ArrowDown") {
          e.preventDefault();
          focusNextSibling(this);
        }
        if (e.key === "ArrowUp") {
          e.preventDefault();
          focusPrevSibling(this);
        }
        if (e.key === "Home") {
          e.preventDefault();
          focusEdgeItem(this, true);
        }
        if (e.key === "End") {
          e.preventDefault();
          focusEdgeItem(this, false);
        }
      });
      list.appendChild(li);
    }
  }

  // §14.1: Home/End jump to the first/last option (the WAI listbox pattern), moving
  // the roving tabindex with the focus exactly like the arrows do.
  function focusEdgeItem(el, first) {
    var list = el.parentNode;
    if (!list) return;
    var items = list.querySelectorAll('[role="option"]');
    if (!items.length) return;
    var target = first ? items[0] : items[items.length - 1];
    if (target === el) return;
    el.setAttribute("tabindex", "-1");
    target.setAttribute("tabindex", "0");
    target.focus();
  }

  function focusNextSibling(el) {
    var next = el.nextElementSibling;
    if (next) {
      el.setAttribute("tabindex", "-1");
      next.setAttribute("tabindex", "0");
      next.focus();
    }
  }

  function focusPrevSibling(el) {
    var prev = el.previousElementSibling;
    if (prev) {
      el.setAttribute("tabindex", "-1");
      prev.setAttribute("tabindex", "0");
      prev.focus();
    }
  }

  /**
   * Mirror the canvas selection onto the DOM list.
   *
   * `moveFocus` defaults to TRUE — the roving tabindex normally carries focus, so a
   * keyboard user sees the ring where the canvas ring is. It is passed FALSE when
   * the selection is a side effect of OPENING the inspector (065 §C): that path
   * moves focus into the panel, and two focus calls in one turn is a race the panel
   * loses roughly half the time (measured live on the demo stack, 2026-09-04:
   * `document.activeElement` was the hidden `<li>`, not the close button, at 2 of 3
   * zoom levels). The tabindex still moves — only the focus() is withheld.
   */
  function a11ySyncNode(nodeId, moveFocus) {
    var list = document.getElementById("pe-node-list");
    if (!list) return;

    var takeFocus = moveFocus !== false;
    var items = list.querySelectorAll('[role="option"]');
    for (var i = 0; i < items.length; i++) {
      var selected = items[i].getAttribute("data-node-id") === nodeId;
      items[i].setAttribute("aria-selected", selected ? "true" : "false");
      items[i].setAttribute("tabindex", selected ? "0" : "-1");
      if (selected && takeFocus) items[i].focus();
    }

    if (editor && editor.cy) {
      editor.cy.elements().unselect();
      var cyNode = editor.cy.getElementById(nodeId);
      if (cyNode.length) cyNode.select();
    }
  }

  // Execution-state mirror: graph.js setNodeState/resetAll call this so a keyboard
  // user sees running/success/failed/aborted without the canvas (styled via the
  // same accent tokens as the graph border — pipeline-editor.css).
  function a11yNodeState(nodeId, state) {
    var list = document.getElementById("pe-node-list");
    if (!list) return;
    var item = list.querySelector('[role="option"][data-node-id="' + nodeId + '"]');
    if (item) item.setAttribute("data-state", state);
  }

  function announceStatus(message) {
    var region = document.getElementById("pe-live-region");
    if (!region) return;
    region.textContent = "";
    setTimeout(function () {
      region.textContent = message;
    }, 100);
  }

  window.setupA11y = setupA11y;
  window.buildNodeList = buildNodeList;
  window.a11ySyncNode = a11ySyncNode;
  window.a11yNodeState = a11yNodeState;
  window.announceStatus = announceStatus;
})();
