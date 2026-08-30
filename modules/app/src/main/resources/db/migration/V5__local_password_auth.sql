-- V5__local_password_auth.sql
--
-- Optional local username/password accounts alongside OIDC (auth.md §5A,
-- metadata-db.md §4.1 — the DDL authority this file is generated from).
--
--   * password_hash TEXT NULL — Argon2id encoded hash (SecretHasher, auth.md §7.2).
--     NULL = OIDC-only account: it can never authenticate locally. Existing rows
--     backfill NULL and behave exactly as before.
--   * password_changed_at TIMESTAMPTZ NULL — when the current hash was set;
--     NULL while the account has never had a local password.
--   * must_change_password BOOLEAN NOT NULL DEFAULT FALSE — the forced-change
--     gate (auth.md §5A.4): seeded and admin-reset credentials set TRUE, and every
--     authenticated route redirects to the change-password screen until cleared.
--   * failed_login_count INTEGER NOT NULL DEFAULT 0 and locked_until TIMESTAMPTZ
--     NULL — the per-account lockout (auth.md §5A.3): N consecutive failures lock
--     the account for a configured duration; a successful login or an admin reset
--     clears both.
--
-- No new indexes: the local login lookup keys off the existing UNIQUE email.
-- No CHECK constraints: lockout bounds live in the repository's atomic UPDATE,
-- matching the module's application-maintained discipline (metadata-db §2).
ALTER TABLE users
    ADD COLUMN password_hash TEXT,
    ADD COLUMN password_changed_at TIMESTAMPTZ,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until TIMESTAMPTZ;
