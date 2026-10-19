/* #619 — progressive enhancement of the native login POST. No credential transport here. */
(function () {
  "use strict";

  function clear(field, error) {
    field.removeAttribute("aria-invalid");
    field.parentElement.classList.remove("invalid");
    error.hidden = true;
    error.textContent = "";
  }

  function reject(event, field, error, message) {
    event.preventDefault();
    error.textContent = message;
    error.hidden = false;
    field.setAttribute("aria-invalid", "true");
    field.parentElement.classList.add("invalid");
    field.focus();
  }

  /** Validate only the client-side requirements; authentication stays on the server. */
  function validate(event, doc) {
    var email = doc.getElementById("login-email");
    var password = doc.getElementById("login-password");
    var emailError = doc.getElementById("login-email-error");
    var passwordError = doc.getElementById("login-password-error");
    clear(email, emailError);
    clear(password, passwordError);
    if (!email.validity.valid) {
      reject(event, email, emailError, "Enter a valid email address.");
    } else if (!password.value) {
      reject(event, password, passwordError, "Enter your password.");
    }
  }

  /** Keep the control's accessible state, visible label and glyph in sync. */
  function toggle(doc) {
    var password = doc.getElementById("login-password");
    var button = doc.getElementById("login-password-toggle");
    var show = password.type === "password";
    password.type = show ? "text" : "password";
    button.querySelector("span").textContent = show ? "Hide" : "Show";
    button.setAttribute("aria-pressed", String(show));
    button.setAttribute("aria-label", show ? "Hide password" : "Show password");
    button.querySelector("use").setAttribute("href", button.getAttribute(show ? "data-hide-icon" : "data-show-icon"));
  }

  /** Arm once. Without this script, native required validation remains enabled. */
  function arm(doc) {
    var password = doc.getElementById("login-password");
    if (!password || password.form.dataset.loginArmed) return;
    var form = password.form;
    form.dataset.loginArmed = "true";
    var button = doc.getElementById("login-password-toggle");
    button.addEventListener("click", function () { toggle(doc); });
    form.addEventListener("submit", function (event) { validate(event, doc); });
    ["email", "password"].forEach(function (name) {
      var field = doc.getElementById("login-" + name);
      var error = doc.getElementById("login-" + name + "-error");
      field.addEventListener("input", function () { clear(field, error); });
    });
    // Native constraint validation precedes submit events. Replace it only after all
    // handlers are armed, so the inline messages can receive invalid submissions.
    form.noValidate = true;
    button.hidden = false;
  }

  if (typeof module !== "undefined" && module.exports) module.exports = { arm: arm, validate: validate, toggle: toggle };
  if (typeof document !== "undefined") {
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", function () { arm(document); });
    else arm(document);
  }
})();
