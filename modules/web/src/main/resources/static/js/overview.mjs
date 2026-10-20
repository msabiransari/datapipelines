// #556 — the family overview's interactions (templates/overview/family.html, guide-*.html):
// Copy prompt, and since #home-frame the page's one <dialog>, the starter prompt's question field
// and Reset.
//
// Copy: the prompt is the textarea's CURRENT text, edits included, and it goes to the clipboard and
// nowhere else: no request, no create call, no send (shell design §8). Success relabels the button
// "Copied" for a moment and says so in the status line; a refused or absent clipboard (a non-secure
// origin, a denied permission) selects the text instead, relabels the button "Select and copy" and
// says how to finish by hand. No path throws to the console.
//
// Dialog: every section is server-rendered inside the dialog and hidden; an opener's data-open
// names the section to show, whose data-title becomes the dialog's title (as text). showModal()
// makes the page behind inert and Escape close it natively; a click on the backdrop (outside the
// dialog's box) closes it, a click inside does not; on close, focus returns to the opener. A
// section with a [data-dialog-table] slot receives that panel's hidden table twin while open and
// gives it back on close, so the page holds one copy of the table.
//
// Question: typing fills the literal [question] of the SERVER text (the textarea's defaultValue)
// by index and slice, never innerHTML or a replacement pattern, so the reader's words land as text
// and nowhere else. The rule: the field composes the prompt only while the textarea still holds
// the last text the field composed; once the reader edits the textarea, typing in the field never
// overwrites it. Reset restores the server text and clears the question.
//
// Loaded through the page's <template data-page-assets> catalog (page-assets.mjs), once per
// document: the listeners are document-level, so they serve every overview a boosted navigation
// brings in without re-binding.

export const COPIED_MS = 1500;
export const COPIED_STATUS = "Prompt copied to the clipboard.";
export const MANUAL_STATUS = "Copy is not available here. The prompt is selected: press Ctrl+C (⌘C on a Mac) to copy it.";
export const MANUAL_LABEL = "Select and copy";
export const PLACEHOLDER = "[question]";

/** Copies [field]'s text through [clipboard]; resolves "copied" or "manual", never rejects. */
export async function copyPrompt(field, button, status, clipboard, schedule = setTimeout) {
  const label = button.getAttribute("data-copy-label") || button.textContent;
  try {
    if (!clipboard || typeof clipboard.writeText !== "function") throw new Error("clipboard unavailable");
    await clipboard.writeText(field.value);
  } catch (_) {
    field.focus();
    field.select();
    button.textContent = MANUAL_LABEL;
    status.textContent = MANUAL_STATUS;
    return "manual";
  }
  button.textContent = "Copied";
  status.textContent = COPIED_STATUS;
  schedule(() => { button.textContent = label; }, COPIED_MS);
  return "copied";
}

/** The click target's prompt parts, or null when the click is not a Copy prompt button. */
export function promptParts(target) {
  const button = target?.closest?.("[data-overview-copy]");
  const root = button?.closest("[data-overview-prompt]");
  const field = root?.querySelector("textarea");
  const status = root?.querySelector("[data-overview-copy-status]");
  return button && field && status ? { button, field, status } : null;
}

/** [template] with its first [question] replaced by [answer] (blank keeps the placeholder); as text, by index. */
export function fill(template, answer) {
  const at = template.indexOf(PLACEHOLDER);
  const words = answer.trim();
  if (at < 0 || words === "") return template;
  return template.slice(0, at) + words + template.slice(at + PLACEHOLDER.length);
}

// The text each textarea last received from its question field; absent = the server text.
const composed = new WeakMap();

/** Composes [field]'s prompt from [question]'s value unless the reader has edited it; answers whether it wrote. */
export function compose(field, question) {
  const last = composed.has(field) ? composed.get(field) : field.defaultValue;
  if (field.value !== last) return false;
  const next = fill(field.defaultValue, question.value);
  field.value = next;
  composed.set(field, next);
  return true;
}

/** Restores the server text, clears the question and the status, and relabels Copy prompt. */
export function resetPrompt(root) {
  const field = root.querySelector("textarea");
  const question = root.querySelector("[data-overview-question]");
  const status = root.querySelector("[data-overview-copy-status]");
  const copy = root.querySelector("[data-overview-copy]");
  if (!field) return;
  field.value = field.defaultValue;
  composed.delete(field);
  if (question) question.value = "";
  if (status) status.textContent = "";
  if (copy) copy.textContent = copy.getAttribute("data-copy-label") || copy.textContent;
}

// Per open dialog: the opener to refocus and the borrowed table to give back.
const opened = new WeakMap();

/** Shows [dialog]'s section [key] as a modal, opened by [opener]; answers whether a section matched. */
export function openSection(dialog, key, opener) {
  let shown = null;
  for (const section of dialog.querySelectorAll("[data-dialog-section]")) {
    const match = section.getAttribute("data-dialog-section") === key;
    section.hidden = !match;
    if (match) shown = section;
  }
  if (!shown || typeof dialog.showModal !== "function") return false;
  const title = dialog.querySelector("[data-overview-dialog-title]");
  if (title) title.textContent = shown.getAttribute("data-title") || "";
  const state = { opener, borrowed: null };
  const slot = shown.querySelector("[data-dialog-table]");
  if (slot) state.borrowed = borrow(dialog, slot);
  opened.set(dialog, state);
  if (!dialog.open) dialog.showModal();
  return true;
}

/** Moves the panel table twin [slot] names into the slot; returns how to give it back, or null. */
function borrow(dialog, slot) {
  const key = slot.getAttribute("data-dialog-table");
  const root = dialog.closest?.("[data-overview]") || dialog.ownerDocument;
  const frame = Array.from(root?.querySelectorAll("[data-table-home]") || []).find(home => home.getAttribute("data-table-home") === key);
  if (!frame) return null;
  const home = { frame, parent: frame.parentNode, next: frame.nextSibling };
  slot.append(frame);
  frame.classList.remove("app-visually-hidden");
  return home;
}

/** After [dialog] closed: the borrowed table goes home hidden, and focus returns to the opener. */
export function closed(dialog) {
  const state = opened.get(dialog);
  opened.delete(dialog);
  if (!state) return;
  if (state.borrowed) {
    const { frame, parent, next } = state.borrowed;
    frame.classList.add("app-visually-hidden");
    parent.insertBefore(frame, next);
  }
  if (state.opener?.isConnected !== false) state.opener?.focus?.();
}

/** Whether [event] is a click on [dialog]'s backdrop: the dialog itself is the target and the point is outside its box. */
export function isBackdropClick(dialog, event) {
  if (event.target !== dialog || typeof dialog.getBoundingClientRect !== "function") return false;
  const box = dialog.getBoundingClientRect();
  return event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom;
}

const bound = new WeakSet();

/** The overview dialog an opener belongs to, its close listener bound once. */
function dialogFor(target) {
  const dialog = target.closest?.("[data-overview]")?.querySelector("[data-overview-dialog]");
  if (dialog && !bound.has(dialog)) {
    bound.add(dialog);
    dialog.addEventListener("close", () => closed(dialog));
  }
  return dialog;
}

/** The document's click handling: Copy prompt, Reset, close, backdrop, and any data-open opener. */
export function onClick(event) {
  const target = event.target;
  const parts = promptParts(target);
  if (parts) {
    copyPrompt(parts.field, parts.button, parts.status, globalThis.navigator?.clipboard).catch(() => {});
    return;
  }
  const reset = target?.closest?.("[data-overview-reset]");
  if (reset) {
    const root = reset.closest("[data-overview-prompt]");
    if (root) resetPrompt(root);
    return;
  }
  const close = target?.closest?.("[data-overview-dialog-close]");
  if (close) {
    close.closest("[data-overview-dialog]")?.close();
    return;
  }
  if (target?.matches?.("[data-overview-dialog]")) {
    if (isBackdropClick(target, event)) target.close();
    return;
  }
  const opener = target?.closest?.("[data-open]");
  const dialog = opener && dialogFor(opener);
  if (dialog) openSection(dialog, opener.getAttribute("data-open"), opener);
}

/** The document's input handling: a question field composes its prompt. */
export function onInput(event) {
  const question = event.target?.closest?.("[data-overview-question]");
  const field = question?.closest("[data-overview-prompt]")?.querySelector("textarea");
  if (field) compose(field, question);
}

if (globalThis.document?.addEventListener && !globalThis.DatapipelinesOverview) {
  globalThis.DatapipelinesOverview = { copyPrompt };
  document.addEventListener("click", onClick);
  document.addEventListener("input", onInput);
}
