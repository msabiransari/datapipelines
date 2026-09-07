-- V13__datasource_credential_kind.sql
--
-- WHAT the stored credential IS, on the datasource row (datasources.md §3.4 / §7.2, round 087).
--
-- Before this, the schema could express exactly one credential: `username NOT NULL` plus
-- `password_encrypted NOT NULL`. That is the shape of "another two-level JDBC RDBMS with a
-- login", and it is wrong for every warehouse and lake connector on the roadmap — Snowflake
-- authenticates a service user with a programmatic access token or an RSA private key, BigQuery
-- with a service-account JSON document, Databricks with a PAT (`UID=token`, the token in `PWD`)
-- or OAuth M2M, and a lake read over S3 with NO stored credential at all (the instance role).
--
-- It was also already wrong for what we ship: the demo's SQLite and DuckDB datasources are FILES
-- with no authentication, and carried
--     password: "sqlite-file-datasource-has-no-authentication"
-- only because `datasource.validation.password_missing` rejected an empty one (datasources.md
-- §8A.1). That dummy is what `credential_kind = 'none'` replaces.
--
-- Three changes, all additive-then-tightening within this one migration:
--
--   1. `password_encrypted` → `credential_encrypted`, and it becomes NULLABLE. The blob is
--      kind-agnostic: a password, a token, a PEM, or a JSON document, encrypted identically
--      under the V10 versioned AES-GCM envelope with the datasource NAME as AAD (§7.1). The
--      068 key-provider and rotation path (`credential_key_version` semantics, V10's leading
--      version byte) is untouched — it never looked at what the plaintext meant.
--   2. `credential_kind TEXT NOT NULL DEFAULT 'password'`, CHECKed against the enums.md §5A set.
--      The DEFAULT is what makes the backfill trivial and TRUE: every pre-087 row was written
--      through a path that required a username and a password, so every one of them IS a
--      password. There is no heuristic here and no row to guess about.
--   3. `username` becomes NULLABLE. A private key, a service-account blob and "no credential"
--      have no username, and a placeholder in a NOT NULL column is the lie this round removes.
--
-- The CHECK that makes `password_set` derivable
-- --------------------------------------------
-- `chk_datasource_credential_present` pins `credential_kind = 'none'` ⟺ `credential_encrypted
-- IS NULL`. That equivalence is why the §3.2 response can derive `password_set` from the KIND
-- (`Datasource.credentialSet`) instead of carrying a read-side flag every constructor would have
-- to get right — the database, not a convention, is what makes the two statements the same.
--
-- `chk_datasource_credential_username` pins the other half of §3.4: a username is REQUIRED for
-- `password`, OPTIONAL for `token`, and FORBIDDEN for the other three. The application validator
-- reports these as field errors on a 400; this constraint is the backstop for a row written by
-- restore or by hand.
--
-- Idempotence is not attempted and is not needed: Flyway applies a versioned migration exactly
-- once per database.
--
-- Verification (what the round ran on a V12 database carrying rows):
--     SELECT credential_kind, count(*), count(credential_encrypted) FROM datasources GROUP BY 1;
-- Immediately after this migration every row reads ('password', n, n) — no row loses its
-- credential and no row acquires a kind it did not have.

ALTER TABLE datasources RENAME COLUMN password_encrypted TO credential_encrypted;

ALTER TABLE datasources
    ALTER COLUMN credential_encrypted DROP NOT NULL,
    ALTER COLUMN username DROP NOT NULL,
    ADD COLUMN credential_kind TEXT NOT NULL DEFAULT 'password';

ALTER TABLE datasources
    ADD CONSTRAINT chk_datasource_credential_kind CHECK (
        credential_kind IN ('password', 'token', 'private_key', 'service_account_json', 'none')
    ),
    ADD CONSTRAINT chk_datasource_credential_present CHECK (
        (credential_kind = 'none') = (credential_encrypted IS NULL)
    ),
    ADD CONSTRAINT chk_datasource_credential_username CHECK (
        CASE credential_kind
            WHEN 'password' THEN username IS NOT NULL
            WHEN 'token'    THEN TRUE
            ELSE username IS NULL
        END
    );
