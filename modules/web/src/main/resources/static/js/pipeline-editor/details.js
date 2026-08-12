(function () {
  "use strict";

  function DetailsPanel(editor) {
    this.editor = editor;
    this.visible = false;
    this.nodeData = null;
  }

  DetailsPanel.prototype.show = function (nodeData) {
    this.nodeData = nodeData;
    this.visible = true;
    this.editor.selectedNode = nodeData;
  };

  DetailsPanel.prototype.hide = function () {
    this.visible = false;
    this.nodeData = null;
    this.editor.selectedNode = null;
  };

  window.DetailsPanel = DetailsPanel;
})();
