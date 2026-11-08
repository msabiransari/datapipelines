-- The settlement page's index (#844, design §8b): one table's unsettled deliveries in id order, a page at a time.
-- The key carries the page's table filter and its keyset order; the predicate carries the state filter, so a page is an
-- index range scan that stops after its rows. It replaces V55's (table_id, state) index, whose only reader was the page.
-- Down rehearsal: drop idx_lake_deliveries_unsettled_page, recreate V55's index; remove history 57.
DROP INDEX idx_lake_deliveries_unsettled;
CREATE INDEX idx_lake_deliveries_unsettled_page ON lake_deliveries (table_id, id)
    WHERE state IN ('RECEIVED', 'PUBLISHING', 'UNKNOWN');
