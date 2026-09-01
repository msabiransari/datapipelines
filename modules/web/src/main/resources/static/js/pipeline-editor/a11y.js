(function () {
  "use strict";

  var editor = null;

  function setupA11y(context) {
    editor = context;
    buildNodeList();
    installEscapeHandler();
  }

  // §14.1: Escape closes the TOPMOST open surface — error modal, then result panel,
  // then the details panel — one per press, matching the close paths the UI already
  // has (init.js's canvas-tap, ResultPanel.hide, the modal dismiss). Attached once:
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
      } else if (editor.resultPanel && editor.resultPanel.visible && editor.resultPanelInstance) {
        editor.resultPanelInstance.hide();
      } else if (editor.selectedNode) {
        editor.selectedNode = null;
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
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          var id = this.getAttribute("data-node-id");
          editor.selectNodeById(id);
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

  function a11ySyncNode(nodeId) {
    var list = document.getElementById("pe-node-list");
    if (!list) return;

    var items = list.querySelectorAll('[role="option"]');
    for (var i = 0; i < items.length; i++) {
      var selected = items[i].getAttribute("data-node-id") === nodeId;
      items[i].setAttribute("aria-selected", selected ? "true" : "false");
      // Roving tabindex follows the selection, and focus follows the tabindex so a
      // keyboard user sees the ring where the canvas ring is.
      items[i].setAttribute("tabindex", selected ? "0" : "-1");
      if (selected) items[i].focus();
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
