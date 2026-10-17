import { test } from "node:test";
import assert from "node:assert/strict";
import { browseLabel, familyForPath, retiredTreeKeys } from "../../main/resources/static/js/tree/sidebar-state.mjs";

const order = ["pipelines", "templates", "dashboards", "visualizations", "parameter-sets"];

test("each family's catalog and artifact pages belong to that family", () => {
  for (const family of order) {
    assert.equal(familyForPath(`/${family}`, order), family);
    assert.equal(familyForPath(`/${family}/0b3c7f0e-2f7a-4e55-9d55-31f1f0a6b2c1`, order), family);
  }
});

test("pages outside the five families have no browser", () => {
  for (const path of ["/", "/datasources", "/executions", "/schedules", "/admin/members", "/keys", "/overview/pipelines"]) {
    assert.equal(familyForPath(path, order), null, path);
  }
});

test("a family the browser does not host is never answered", () => {
  assert.equal(familyForPath("/dashboards/x", ["pipelines", "templates"]), null);
  assert.equal(familyForPath("/templates/x", ["pipelines", "templates"]), "templates");
});

test("a missing or empty path has no family", () => {
  for (const path of [null, undefined, "", 42]) assert.equal(familyForPath(path, order), null);
});

test("the retired accordion keys are named per workspace and family", () => {
  assert.deepEqual(retiredTreeKeys("acme", order), [
    "dp-tree-open:acme",
    "dp-tree-panel:acme:pipelines",
    "dp-tree-panel:acme:templates",
    "dp-tree-panel:acme:dashboards",
    "dp-tree-panel:acme:visualizations",
    "dp-tree-panel:acme:parameter-sets",
  ]);
});

test("no workspace names no retired key", () => {
  for (const workspace of [null, undefined, ""]) assert.deepEqual(retiredTreeKeys(workspace, order), []);
});

test("the folder button is named for the family it shows", () => {
  assert.equal(browseLabel("Parameter Sets"), "Browse Parameter Sets");
  assert.equal(browseLabel(null), "Browse resources");
  assert.equal(browseLabel(""), "Browse resources");
});
