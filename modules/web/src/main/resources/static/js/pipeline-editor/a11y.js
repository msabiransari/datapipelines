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
    if (next) next.focus();
  }

  function focusPrevSibling(el) {
    var prev = el.previousElementSibling;
    if (prev) prev.focus();
  }

  function a11ySyncNode(nodeId) {
    var list = document.getElementById("pe-node-list");
    if (!list) return;

    var items = list.querySelectorAll('[role="option"]');
    for (var i = 0; i < items.length; i++) {
      if (items[i].getAttribute("data-node-id") === nodeId) {
        items[i].setAttribute("aria-selected", "true");
      } else {
        items[i].setAttribute("aria-selected", "false");
      }
    }

    if (editor && editor.cy) {
      editor.cy.elements().unselect();
      var cyNode = editor.cy.getElementById(nodeId);
      if (cyNode.length) cyNode.select();
    }
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
  window.announceStatus = announceStatus;
})();
