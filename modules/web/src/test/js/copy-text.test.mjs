// #622 — the shared copier's clipboard and manual paths, with deterministic timers.
import test from "node:test";
import assert from "node:assert/strict";
import { copyText, COPIED_MS } from "../../main/resources/static/js/copy-text.mjs";

function options() {
  const state = { selected: 0 };
  return {
    button: { textContent: "Copy config", getAttribute: () => "Copy config" },
    status: { textContent: "" },
    select: () => { state.selected++; },
    copiedStatus: "Config copied to the clipboard.",
    manualStatus: "Select the config and copy it manually.",
    manualLabel: "Select and copy",
    state,
  };
}

test("resolved write copies the exact text and restores the original label after COPIED_MS", async () => {
  const opts = options();
  const writes = [], scheduled = [];
  const text = '{\n "key": "dpk_…", "literal": "<b>&"\n}';
  assert.equal(await copyText(text, opts, { writeText: async value => writes.push(value) }, (run, ms) => scheduled.push({ run, ms })), "copied");
  assert.deepEqual(writes, [text]);
  assert.equal(opts.button.textContent, "Copied");
  assert.equal(opts.status.textContent, opts.copiedStatus);
  assert.equal(opts.state.selected, 0);
  assert.equal(scheduled.length, 1);
  assert.equal(scheduled[0].ms, COPIED_MS);
  scheduled[0].run();
  assert.equal(opts.button.textContent, "Copy config");
});

for (const [name, clipboard] of [
  ["refused", { writeText: async () => { throw new Error("denied"); } }],
  ["absent", undefined],
  ["missing writeText", {}],
]) {
  test(`${name} clipboard selects the source and resolves manual without scheduling`, async () => {
    const opts = options();
    assert.equal(await copyText("text", opts, clipboard, () => assert.fail("must not schedule")), "manual");
    assert.equal(opts.state.selected, 1);
    assert.equal(opts.button.textContent, opts.manualLabel);
    assert.equal(opts.status.textContent, opts.manualStatus);
  });
}

test("the current button label is the fallback when data-copy-label is absent", async () => {
  const opts = options();
  opts.button.getAttribute = () => null;
  let restore;
  await copyText("", opts, { writeText: async () => {} }, run => { restore = run; });
  restore();
  assert.equal(opts.button.textContent, "Copy config");
});
