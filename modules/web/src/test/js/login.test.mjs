import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
const require = createRequire(import.meta.url);
const { arm, validate, toggle } = require("../../main/resources/static/js/login.js");

function fixture() {
  const element = () => ({
    hidden: true, textContent: "", attrs: {}, listeners: {},
    setAttribute(key, value) { this.attrs[key] = value; },
    getAttribute(key) { return this.attrs[key]; },
    removeAttribute(key) { delete this.attrs[key]; },
    addEventListener(key, callback) { this.listeners[key] = callback; },
    focus() { this.focused = true; },
    parentElement: { classList: new Set() },
  });
  const nodes = Object.fromEntries(["email", "password", "email-error", "password-error", "password-toggle"].map(id => ["login-" + id, element()]));
  for (const name of ["email", "password"]) {
    const classes = nodes["login-" + name].parentElement.classList;
    classes.remove = classes.delete.bind(classes);
  }
  const form = { dataset: {}, listeners: {}, addEventListener(key, fn) { this.listeners[key] = fn; } };
  const email = nodes["login-email"], password = nodes["login-password"], button = nodes["login-password-toggle"];
  password.form = form;
  password.type = "password";
  password.value = "secret";
  email.validity = { valid: true };
  const label = { textContent: "Show" }, use = element();
  button.querySelector = selector => selector === "span" ? label : use;
  button.attrs["data-show-icon"] = "/vendor/icons/lucide-sprite.svg#eye";
  button.attrs["data-hide-icon"] = "/vendor/icons/lucide-sprite.svg#eye-off";
  const doc = { getElementById: id => nodes[id] };
  const event = { prevented: false, preventDefault() { this.prevented = true; } };
  return { doc, nodes, form, email, password, button, label, use, event };
}

test("toggle round-trip changes type, label, accessible state and glyph", () => {
  const f = fixture();
  toggle(f.doc);
  assert.equal(f.password.type, "text");
  assert.equal(f.label.textContent, "Hide");
  assert.equal(f.button.attrs["aria-label"], "Hide password");
  assert.equal(f.button.attrs["aria-pressed"], "true");
  assert.equal(f.use.attrs.href, "/vendor/icons/lucide-sprite.svg#eye-off");
  toggle(f.doc);
  assert.equal(f.password.type, "password");
  assert.equal(f.label.textContent, "Show");
  assert.equal(f.button.attrs["aria-label"], "Show password");
  assert.equal(f.button.attrs["aria-pressed"], "false");
  assert.equal(f.use.attrs.href, "/vendor/icons/lucide-sprite.svg#eye");
});

test("invalid email blocks submission and focuses the email field", () => {
  const f = fixture(); f.email.validity.valid = false;
  validate(f.event, f.doc);
  assert.equal(f.event.prevented, true);
  assert.equal(f.email.focused, true);
  assert.equal(f.email.attrs["aria-invalid"], "true");
  assert.equal(f.nodes["login-email-error"].hidden, false);
  assert.equal(f.nodes["login-email-error"].textContent, "Enter a valid email address.");
});

test("empty password blocks submission and focuses password", () => {
  const f = fixture(); f.password.value = "";
  validate(f.event, f.doc);
  assert.equal(f.event.prevented, true);
  assert.equal(f.password.focused, true);
  assert.equal(f.nodes["login-password-error"].textContent, "Enter your password.");
});

test("valid pair permits the browser's native POST", () => {
  const f = fixture(); validate(f.event, f.doc);
  assert.equal(f.event.prevented, false);
});

test("arming exposes the toggle and typing clears only that field's error", () => {
  const f = fixture(); arm(f.doc);
  assert.equal(f.button.hidden, false);
  assert.equal(f.form.noValidate, true);
  f.password.value = ""; f.form.listeners.submit(f.event);
  f.email.validity.valid = false; validate(f.event, f.doc);
  f.nodes["login-password-error"].hidden = false;
  f.nodes["login-password-error"].textContent = "Enter your password.";
  f.email.listeners.input();
  assert.equal(f.nodes["login-email-error"].hidden, true);
  assert.equal(f.email.attrs["aria-invalid"], undefined);
  assert.equal(f.email.parentElement.classList.has("invalid"), false);
  assert.equal(f.nodes["login-password-error"].hidden, false);
  assert.equal(f.nodes["login-password-error"].textContent, "Enter your password.");
});

test("arming tolerates OIDC-only pages and does not double-bind", () => {
  arm({ getElementById: () => null });
  const f = fixture(); arm(f.doc);
  const listener = f.form.listeners.submit;
  arm(f.doc);
  assert.equal(f.form.listeners.submit, listener);
});
