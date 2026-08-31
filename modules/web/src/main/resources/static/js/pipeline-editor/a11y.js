(function () {
  "use strict";

  var editor = null;

  function setupA11y(context) {
    editor = context;
    buildNodeList();
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
      });
      list.appendChild(li);
    }
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
