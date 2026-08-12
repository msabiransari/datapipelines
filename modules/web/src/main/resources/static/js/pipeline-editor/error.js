(function () {
  "use strict";

  function ErrorModal(editor) {
    this.editor = editor;
    this.visible = false;
    this.message = "";
  }

  ErrorModal.prototype.show = function (message) {
    this.message = message;
    this.visible = true;
    if (this.editor.errorModal) {
      this.editor.errorModal.visible = true;
      this.editor.errorModal.message = message;
    }
  };

  ErrorModal.prototype.hide = function () {
    this.visible = false;
    this.message = "";
    if (this.editor.errorModal) {
      this.editor.errorModal.visible = false;
      this.editor.errorModal.message = "";
    }
  };

  window.ErrorModal = ErrorModal;
})();
