// 027b C+D — result-panel paging speaks rest-api §6.4.7/§7.3 exactly.
//
// The wire payloads: data_ready (§6.4.7) carries schema + positional rows +
// row_count + total_rows + has_more + result_url + ttl — and NO `limit`, NO
// `page`, NO `total_pages`. Cursor pages (§7.3) add offset + limit. The old
// panel derived pageSize from rows.length (a lower bound — the short LAST page
// rescaled every subsequent offset) and guessed totalPages (frozen at 2,
// rendering "Page 3 / 2").
//
// The load-bearing sequence is the one 027's E2E never ran: forward to the
// SHORT LAST page, then back. 2500 rows at limit 1000 → pages 1000/1000/500.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));

globalThis.window = {};
require(path.resolve(here, "../../main/resources/static/js/pipeline-editor/result.js"));
const { ResultPanel } = globalThis.window;

/** Page fixture: `n` positional rows, §7.3 envelope fields. */
function pagePayload(offset, limit, totalRows, n) {
  const rows = Array.from({ length: n }, (_, i) => [offset + i + 1, `row-${offset + i + 1}`, 1.5]);
  return {
    execution_id: "e1",
    schema: [
      { name: "n", type: "INTEGER" },
      { name: "label", type: "STRING" },
      { name: "amount", type: "DECIMAL" },
    ],
    rows,
    row_count: n,
    offset,
    limit,
    total_rows: totalRows,
    has_more: offset + n < totalRows,
    expires_at: null,
  };
}

function setup() {
  const fetched = [];
  globalThis.fetch = (url) => {
    fetched.push(String(url));
    const params = new URLSearchParams(String(url).split("?")[1]);
    const offset = Number(params.get("offset"));
    const limit = Number(params.get("limit"));
    const rows = Math.min(limit, 2500 - offset);
    const body = { schema_version: 1, data: pagePayload(offset, limit, 2500, rows) };
    return Promise.resolve({ ok: true, json: () => Promise.resolve(body) });
  };
  const editor = { resultPanel: {} };
  const panel = new ResultPanel(editor);
  return { panel, fetched };
}

test("data_ready first page: pageSize from the full inline page, totalPages from total_rows", () => {
  const { panel } = setup();
  panel.showData({ ...pagePayload(0, undefined, 2500, 1000), result_url: "/api/v1/executions/e1/result", ttl_seconds: undefined, expires_at: undefined });
  // data_ready has no `limit`: the inline page is one FULL page (has_more), so
  // its row count is the provisional size; total_rows is the real denominator.
  assert.equal(panel.pageSize, 1000);
  assert.equal(panel.totalPages, 3);
  assert.equal(panel.page, 1);
  assert.equal(panel.hasPrev, false);
  assert.equal(panel.hasNext, true);
  assert.equal(panel.data.rows.length, 1000);
});

test("the short-last-page round trip: forward to page 3, then Prev back", async () => {
  const { panel, fetched } = setup();
  panel.showData({ ...pagePayload(0, undefined, 2500, 1000), result_url: "/api/v1/executions/e1/result" });

  panel.loadPage(2);
  await new Promise((r) => setImmediate(r));
  assert.ok(fetched[0].includes("offset=1000"), `page 2 must fetch offset=1000, got ${fetched[0]}`);
  assert.ok(fetched[0].includes("limit=1000"));
  assert.equal(panel.page, 2);
  assert.equal(panel.totalPages, 3);

  panel.loadPage(3); // short last page: 500 rows
  await new Promise((r) => setImmediate(r));
  assert.ok(fetched[1].includes("offset=2000"), `page 3 must fetch offset=2000, got ${fetched[1]}`);
  assert.equal(panel.rows.length, 500);
  // THE 027b C fix: a short page reports limit=1000 and MUST NOT rescale
  // pageSize to its own row count.
  assert.equal(panel.pageSize, 1000, "short last page corrupted the page size");
  assert.equal(panel.page, 3);
  assert.equal(panel.totalPages, 3);
  assert.equal(panel.hasNext, false);

  // THE round trip the E2E never ran: Prev off the short last page.
  panel.loadPage(2);
  await new Promise((r) => setImmediate(r));
  assert.ok(fetched[2].includes("offset=1000"), `Prev off page 3 must fetch offset=1000, got ${fetched[2]}`);
  assert.ok(fetched[2].includes("limit=1000"));
  assert.equal(panel.page, 2);
  assert.equal(panel.rows.length, 1000);
  assert.equal(panel.rows[0].n, 1001, "page 2 must start at row 1001, not overlap page 1");
  assert.equal(panel.rows[999].n, 2000);
  assert.equal(panel.totalPages, 3);
});

test("single-page result: has_more false, totalPages 1, no pager drift", () => {
  const { panel } = setup();
  panel.showData({ ...pagePayload(0, undefined, 50, 50), result_url: "/api/v1/executions/e1/result" });
  assert.equal(panel.totalPages, 1);
  assert.equal(panel.hasNext, false);
  assert.equal(panel.pageSize, 50);
});
