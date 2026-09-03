-- V9__datasource_last_test_outcome.sql
--
-- The last connection test's OUTCOME, recorded on the datasource row (datasources.md §8.1B,
-- round 061/T84). Born from the 2026-09-02 incident: `sample-trips`'s stored credential had
-- silently stopped authenticating since 2026-08-30, and the datasources screen showed it as
-- fine — because LISTING a datasource never connects to it. Every multi-node demo pipeline
-- failed at CONNECT while the only screen an operator would look at said nothing.
--
-- Three additive, nullable columns. NULL across all three = "never tested", which is exactly
-- what every pre-V9 row is; there is no truthful value to backfill and no default that would
-- not be a lie.
--
--   last_test_at      when the probe ran (TIMESTAMPTZ like every other timestamp, §2)
--   last_test_ok      whether it authenticated and answered
--   last_test_message the driver's message on failure (redaction-scrubbed at the source —
--                     DefaultDatasourceRegistry.failedProbe strips the password and any
--                     credential-bearing URL before this ever reaches a column), or the
--                     server version string on success
--
-- Written by exactly three paths, all through DatasourceRepository.recordTestOutcome:
-- `POST /api/v1/datasources/{name}/test` (§8.1), the UI's Test button, and the §8A.3 rule-3
-- bootstrap credential probe. That write deliberately does NOT touch `updated_at`: a test
-- outcome is an observation ABOUT the datasource, not a change TO it, and §8A.3 rule 1
-- promises an operator's row is left byte-untouched on every boot — a guarantee that stays
-- checkable only if the definition columns (updated_at included) do not move when a probe runs.
--
-- Deliberately NOT here: a poller. ROADMAP §2's "background datasource health checks" would
-- write these same columns on a schedule; this migration records the outcome of tests that
-- already happen, and nothing new starts running because of it.

ALTER TABLE datasources
    ADD COLUMN last_test_at      TIMESTAMPTZ,
    ADD COLUMN last_test_ok      BOOLEAN,
    ADD COLUMN last_test_message TEXT;
