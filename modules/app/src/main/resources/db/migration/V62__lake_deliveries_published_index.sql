-- =============================================================================
-- V62 — the published deliveries' index (#961, dp-lake P6; design record §11b call 46, brief 961 D4)
--
-- Lateness per feed (lake.md "Profiles") reads each feed's last `reporting.lateness_sample` published deliveries of
-- one table: a window over (feed ORDER BY published_at DESC) restricted to state = 'PUBLISHED'. V55's and V57's
-- indexes cover unsettled deliveries only, so without this index the read scans and sorts the table's whole ledger.
-- The key is the read's partition and order; the predicate is its state filter, so the index holds published rows
-- only (published_at IS NULL rows of that state sort first under DESC NULLS FIRST and the read filters them out).
-- No row is written or changed.
--
-- Down rehearsal: DROP INDEX idx_lake_deliveries_published; remove history 62.
-- =============================================================================
CREATE INDEX idx_lake_deliveries_published ON lake_deliveries (table_id, feed, published_at DESC)
    WHERE state = 'PUBLISHED';
