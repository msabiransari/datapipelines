// Control response order against the real explorer; the small DOM fixture records rendered rows and errors.
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const source = readFileSync(new URL("../../main/resources/static/js/schedules/explorer.js", import.meta.url), "utf8");

function fixture() {
  const pending = [];
  function element(template) {
    return {
      template,
      children: [],
      setAttribute() {},
      removeAttribute() {},
      appendChild(child) { this.children.push(child); },
      querySelector: () => element(),
      querySelectorAll: () => [],
    };
  }
  const root = element();
  const schedules = {
    state: { root, all: null, query: "", condition: "", selectedId: null },
    live: () => true,
    remember() {},
    url: id => `/schedules?id=${id}`,
    clear(node) { node.children = []; },
    clone: element,
    text(node, slot, value) { node[slot] = value; return node; },
    slot: () => null,
    show() {},
  };
  const window = {
    DpSchedules: schedules,
    DpSchedulesModel: {
      conditionFilter: word => ["enabled", "paused", "blocked"].includes(word) ? word : "",
      filteredEmptyTitle: word => `No ${word} schedules.`,
    },
    DpSchedulesApi: {
      listAll(prefix, condition) {
        return new Promise((resolve, reject) => pending.push({ prefix, condition, resolve, reject }));
      },
    },
  };
  vm.runInNewContext(source, { window, document: { getElementById: () => root, createElement: element } });
  return { schedules, pending, root };
}

test("an older filter response cannot overwrite the newest condition's list", async () => {
  const { schedules, pending, root } = fixture();
  const older = schedules.explorer.setCondition("blocked");
  const newer = schedules.explorer.setCondition("enabled");
  assert.deepEqual(pending.map(request => request.condition), ["blocked", "enabled"]);
  const newestRows = [{ id: "enabled-id", name: "enabled/name", condition: "enabled" }];
  pending[1].resolve(newestRows);
  await newer;
  assert.deepEqual(schedules.state.all.map(row => row.id), ["enabled-id"]);
  pending[0].resolve([{ id: "blocked-id", name: "blocked/name", condition: "blocked" }]);
  await older;
  assert.deepEqual(schedules.state.all.map(row => row.id), ["enabled-id"], "the stale Blocked response replaced the Enabled results");
  assert.equal(root.children[0].children[0].path, "enabled/name");
});

test("an older filter error cannot replace the newest successful list", async () => {
  const { schedules, pending, root } = fixture();
  const older = schedules.explorer.setCondition("blocked");
  const newer = schedules.explorer.setCondition("enabled");
  pending[1].resolve([]);
  await newer;
  const rendered = root.children[0];
  assert.equal(rendered.title, "No enabled schedules.");
  pending[0].reject(new Error("old request failed"));
  await older;
  assert.equal(root.children[0].template, rendered.template, "an old request replaced the current list with an error");
  assert.equal(root.children[0].title, "No enabled schedules.");
});

test("a response belonging to an old page cannot write into a replacement page state", async () => {
  const { schedules, pending } = fixture();
  const oldLoad = schedules.explorer.setCondition("blocked");
  const currentRows = [{ id: "current-page", name: "enabled/name", condition: "enabled" }];
  schedules.state = { ...schedules.state, condition: "enabled", all: currentRows };
  pending[0].resolve([{ id: "previous-page", name: "blocked/name", condition: "blocked" }]);
  await oldLoad;
  assert.deepEqual(schedules.state.all.map(row => row.id), ["current-page"], "the previous page's response wrote into its replacement");
});


test("a current request failure still renders its error", async () => {
  const { schedules, pending, root } = fixture();
  const load = schedules.explorer.setCondition("blocked");
  pending[0].reject(new Error("current request failed"));
  await load;
  assert.equal(root.children[0].template, "sch-tpl-error");
  assert.equal(root.children[0].message, "current request failed");
});
