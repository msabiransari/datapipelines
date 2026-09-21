-- =============================================================================
-- V31 — Roles R3 (#179), D16: the login-minted MCP key
--
-- Authority: docs/superpowers/specs/2026-09-20-roles-permissions-design.md (RATIFIED),
-- D16 / §3.3; recorded as DDL in metadata-db.md §4.2.
--
-- Two columns and one rule:
--
--   secret_sealed BYTEA NULL — the key's full plaintext (`dpk_<id>.<secret>`), sealed
--   with the deployment's credential-encryption key (the same AES-256-GCM key that seals
--   datasource passwords — datasources.md §7.1, docs/key-providers.md), AAD = the key id.
--   The Argon2id `key_hash` stays the authentication half (§7.2); the sealed copy is what
--   lets the top bar offer a COPY button for a key minted at login, when nobody was shown
--   the plaintext. NULL for every row minted before R3: those keys show their prefix
--   WITHOUT the copy button, and the bar says "delete and sign in again" to rotate into a
--   copyable one.
--
--   minted_at_login BOOLEAN NOT NULL DEFAULT FALSE — marks the rows the login/switch hook
--   (WorkspaceService.workspaceForLogin) created, so an audit reader can tell a system
--   mint from the pre-R3 on-demand ones. The DEFAULT keeps every pre-existing row FALSE,
--   which is the truth about them.
--
-- The rule, as a partial unique index: at most ONE live (not revoked) `user`-kind key per
-- (user_id, workspace_id). `endpoint` and `server` keys are excluded — an admin may create
-- as many API keys as the workspace needs, and uniqueness there would be a rule about
-- nothing. Revoked user keys are excluded too: rotation IS delete-then-relogin, so the
-- revoked rows of past rotations must not block the next mint.
--
-- The index can only be created after the data already obeys it: the UPDATE below revokes
-- every live user key but the newest per (user_id, workspace_id) and reports how many it
-- revoked. Revocation, not deletion — `audit_log.key_id` keeps resolving (§4.2's note),
-- and a revoked key is exactly what "you rotated" looks like everywhere else.
--
-- DOWN PATH (manual): DROP INDEX api_keys_one_live_user_key; ALTER TABLE api_keys
--   DROP COLUMN secret_sealed, DROP COLUMN minted_at_login;
-- Lossless for the schema; the ROWS the UPDATE revoked stay revoked on the way down (a
-- down migration must not un-revoke a credential), and secrets sealed under the current
-- key become unreadable-but-harmless once the column is gone.
-- =============================================================================

ALTER TABLE api_keys ADD COLUMN secret_sealed BYTEA NULL;
ALTER TABLE api_keys ADD COLUMN minted_at_login BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
DECLARE
    revoked_count integer;
BEGIN
    WITH ranked AS (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY user_id, workspace_id
                   ORDER BY created_at DESC, id DESC
               ) AS rn
        FROM api_keys
        WHERE kind = 'user' AND is_revoked = FALSE
    )
    UPDATE api_keys k
       SET is_revoked = TRUE
      FROM ranked r
     WHERE k.id = r.id AND r.rn > 1;
    GET DIAGNOSTICS revoked_count = ROW_COUNT;
    RAISE NOTICE 'V31: revoked % duplicate live user key(s) — one MCP key per user per workspace (D16)', revoked_count;
END $$;

CREATE UNIQUE INDEX api_keys_one_live_user_key
    ON api_keys (user_id, workspace_id)
    WHERE kind = 'user' AND is_revoked = FALSE;
