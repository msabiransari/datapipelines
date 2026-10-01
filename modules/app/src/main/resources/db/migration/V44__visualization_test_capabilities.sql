-- #352 / #10 L4a — the test-session capability persistence (metadata-db §4.32): the columns a screenshot
-- upload capability needs so the single-use replay fence survives a process restart. The preview token's
-- hash and the session deadline were V42's (`preview_token_hash`, `expires_at`); this adds the SECOND,
-- purpose-bound capability minted at successful results submission — one per run, hash-only at rest,
-- expiring no later than the session deadline, consumed atomically with the stored image.
ALTER TABLE visualization_test_runs
    ADD COLUMN upload_token_hash TEXT    NULL,
    ADD COLUMN upload_expires_at TIMESTAMPTZ NULL,
    ADD COLUMN upload_consumed_at TIMESTAMPTZ NULL;

-- The three columns are one capability: hash and deadline together, the consumption stamp only on a
-- minted one (and only before its deadline — a consume is refused past it). A NULL hash is no
-- capability: nothing else may be set. The hash's IS NOT NULL is load-bearing: NULL ~ regex is NULL,
-- and a CHECK passes on NULL — without it, a hash-less capability with a deadline would slip through.
ALTER TABLE visualization_test_runs
    ADD CONSTRAINT chk_visualization_test_runs_upload CHECK (
        (
            upload_token_hash IS NULL
            AND upload_expires_at IS NULL
            AND upload_consumed_at IS NULL
        )
        OR (
            upload_token_hash IS NOT NULL
            AND upload_token_hash ~ '^[0-9a-f]{64}$'
            AND upload_expires_at IS NOT NULL
            AND (upload_consumed_at IS NULL OR upload_consumed_at < upload_expires_at)
        )
    );
