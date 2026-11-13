-- Reader pins are additive; writer leases and dp_fence keep their existing semantics.
ALTER TABLE lake_leases DROP CONSTRAINT lake_leases_kind_check;
ALTER TABLE lake_leases ADD CONSTRAINT lake_leases_kind_check CHECK (kind IN ('table', 'namespace', 'reader'));
ALTER TABLE lake_leases ADD COLUMN pinned_metadata_location TEXT;
ALTER TABLE lake_leases ADD CONSTRAINT lake_leases_reader_location_check
    CHECK ((kind = 'reader') = (pinned_metadata_location IS NOT NULL));
CREATE INDEX idx_lake_leases_reader_location ON lake_leases (pinned_metadata_location) WHERE kind = 'reader';
