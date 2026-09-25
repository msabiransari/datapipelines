-- =============================================================================
-- V37 — Keys v2: every key is a robot member of one workspace (#233)
--
-- Authority: docs/superpowers/specs/2026-09-23-permissions-and-keys-design.md (RATIFIED
-- 2026-09-23; amended 2026-09-24, A1–A12; amended 2026-09-25, A13–A19) §3, §10; recorded as
-- DDL in metadata-db.md §4.2. One transaction, four facts:
--
-- 1. The `user` kind is renamed `mcp` (A19) — kind is the transport and the word says which.
--    The column's DEFAULT follows; the code's enum (ApiKeyKind.MCP) spells the same wire value.
--
-- 2. Every live `user`-kind key is CONVERTED (A15, B4) — the login-minted rows AND the
-- pre-R3 on-demand ones (V31's own KDoc: the pre-existing rows' `minted_at_login` is FALSE,
-- "which is the truth about them", so the flag cannot decide who converts; every live
-- `user` row must end identity-backed with a role, because the final CHECK admits a
-- NULL role only on a revoked row). For every live key, its owner's role in the key's
-- workspace decides —
--      author | promoter | workspace_admin → the key keeps living, now IDENTITY-BACKED: a
--        `service` identity is created for it (exactly as V34 built endpoint/server identities:
--        provider 'key', subject = the key id, email '<key id>@keys.invalid', no password),
--        `user_id` moves to the identity, and `created_by` keeps naming the owner — the person
--        who may revoke it (A14's revoke-own) and whose removal now revokes it (A17);
--      viewer — or an owner with NO membership — → REVOKED, never upgraded and never guessed
--        (a viewer is never a key role, A15; a missing membership is not a role to invent).
--    An owner's SUPER-ADMIN flag grants nothing here: only the explicit membership's role
--    counts, because the key's role must be one the owner HOLDS in that workspace (the subset
--    rule, A14) and the owner's membership IS what they hold.
--    A20 (owner ruling 2026-09-25): the conversion CTE may STAND under the creator-liveness
--    rule — it reads the owner's MEMBERSHIP, never `users.is_active`, so a key whose owner
--    is deactivated converts exactly as this header describes and then stays DEAD at request
--    time (`auth.principal_deactivated` from ApiKeyService.liveActor) until the owner is
--    reactivated. Liveness is judged at validation, never migrated.
--
-- 3. `minted_at_login` and its one-live-key unique index are DROPPED (A15): no key is minted
--    at sign-in any more; the Keys page is the one creation path. Revoked pre-v2 login keys
--    keep `role IS NULL` — the CHECK admits it for exactly those rows.
--
-- 4. `chk_api_keys_role` is REPLACED (A13/A14/A19) and a live-name uniqueness (A18) is added:
--      mcp      → one of the member roles ('author' | 'promoter' | 'workspace_admin'),
--                 `role IS NULL` tolerated only on a REVOKED pre-v2 row;
--      endpoint → 'api_caller';
--      server   → 'promotion_receiver'.
--    A unique index on live `(workspace_id, name)` (A18) — pre-existing duplicate live names
--    are disambiguated (all but the newest gain the key id's tail), so the migration cannot
--    fail on old data and no key row is lost.
--
-- Executions and audit rows are not touched: past runs keep their attribution (PK9).
--
-- DOWN PATH (manual, lossy by design): the pre-v2 shapes are not recoverable — a converted
-- login key's owner-linkage (user_id = the member) and the dropped column cannot be rebuilt
-- from what V37 leaves. Rolling the CODE back requires restoring the database from backup.
-- =============================================================================

-- 1. The OLD constraints come off FIRST: the conversion below moves rows THROUGH states
-- neither CHECK admits (a `user`-kind row gaining a role; an `mcp` row still role-less).
-- Postgres has no ALTER for a CHECK expression, so both are dropped and re-created at the
-- end — where they validate the FINAL world, which is what makes the migration self-checking.
ALTER TABLE api_keys DROP CONSTRAINT chk_api_keys_kind;
ALTER TABLE api_keys DROP CONSTRAINT chk_api_keys_role;
ALTER TABLE api_keys ALTER COLUMN kind SET DEFAULT 'mcp';

-- 2. Every live `user`-kind key converts or revokes (B4, one DO block = one statement
--    group inside Flyway's transaction).
DO $$
DECLARE
    converted_count integer;
    revoked_count integer;
    identities_created integer;
BEGIN
    -- 2a. CONVERT: the owner's membership role decides (author | promoter | workspace_admin).
    --     The identity is built exactly like V34's; created_by keeps the owner. Every live
    --     `user`-kind row is in scope — the pre-R3 on-demand keys included (see above).
    WITH convertible AS (
        SELECT k.id AS key_id, k.name, m.role
          FROM api_keys k
          JOIN workspace_members m
            ON m.user_id = k.created_by
           AND m.workspace_id = k.workspace_id
         WHERE k.kind = 'user'
           AND k.is_revoked = FALSE
           AND m.role IN ('author', 'promoter', 'workspace_admin')
    ),
    minted AS (
        INSERT INTO users (email, display_name, provider, provider_subject, is_active, is_admin, kind)
        SELECT lower(c.key_id) || '@keys.invalid', c.name, 'key', c.key_id, TRUE, FALSE, 'service'
          FROM convertible c
        RETURNING id, provider_subject
    )
    UPDATE api_keys k
       SET user_id = minted.id,
           role = convertible.role
      FROM minted
      JOIN convertible ON convertible.key_id = minted.provider_subject
     WHERE k.id = convertible.key_id;
    GET DIAGNOSTICS converted_count = ROW_COUNT;

    -- 2b. REVOKE: a viewer's key, an owner with no membership, or a membership in an
    --     unexpected state — revoked, never guessed (B4). A NOTICE carries the count.
    UPDATE api_keys k
       SET is_revoked = TRUE
     WHERE k.kind = 'user'
       AND k.is_revoked = FALSE
       AND k.role IS NULL;
    GET DIAGNOSTICS revoked_count = ROW_COUNT;
    RAISE NOTICE 'V37: % user-kind key(s) converted to identity-backed keys with their owner''s role; % revoked (viewer or no membership)',
        converted_count, revoked_count;
END $$;

-- 3. kind `user` → `mcp` (A19), now that every live row carries a role.
UPDATE api_keys SET kind = 'mcp' WHERE kind = 'user';
ALTER TABLE api_keys ADD CONSTRAINT chk_api_keys_kind CHECK (kind IN ('mcp', 'endpoint', 'server'));

-- 3b. The login mint is gone (A15).
DROP INDEX IF EXISTS api_keys_one_live_user_key;
ALTER TABLE api_keys DROP COLUMN minted_at_login;

-- A19 on the execution side: `pipeline_executions.executed_by_key_kind` carries the KEY KIND's
-- wire value, so new `mcp`-key runs write 'mcp'. The CHECK widens to admit it and keeps 'user'
-- for every row the old world wrote — history is not rewritten.
ALTER TABLE pipeline_executions DROP CONSTRAINT chk_executions_executed_by_key_kind;
ALTER TABLE pipeline_executions
    ADD CONSTRAINT chk_executions_executed_by_key_kind
        CHECK (executed_by_key_kind IS NULL OR executed_by_key_kind IN ('user', 'mcp', 'endpoint', 'server'));

-- 4. The CHECK (A13/A14/A19) and the live-name uniqueness (A18). The old CHECK was already
-- dropped in step 1 (before the conversion moved rows through in-between states).
-- `role IS NOT NULL` on EVERY role-bearing arm is NOT redundant (V34's own lesson, which the
-- first draft of this migration re-tripped on the mcp arm): under SQL's three-valued logic
-- `role = 'api_caller'` is UNKNOWN for a NULL role, a CHECK passes on UNKNOWN, and without the
-- explicit `IS NOT NULL` a LIVE `mcp` row with a NULL role evaluated to NULL (arm 1 UNKNOWN,
-- arm 2 FALSE) and was ADMITTED — measured on 2026-09-25 by
-- FlywayMigrationIntegrationTest's `roleAccepted("mcp", null) shouldBe false` probe.
ALTER TABLE api_keys ADD CONSTRAINT chk_api_keys_role CHECK (
    (kind = 'mcp' AND role IS NOT NULL AND role IN ('author', 'promoter', 'workspace_admin'))
    OR (kind = 'mcp' AND role IS NULL AND is_revoked)
    OR (kind = 'endpoint' AND role IS NOT NULL AND role = 'api_caller')
    OR (kind = 'server' AND role IS NOT NULL AND role = 'promotion_receiver')
);

-- A18: disambiguate pre-existing duplicate LIVE names in one workspace (all but the newest
-- gain the key id's tail; the id makes the result unique by construction), then the index.
DO $$
DECLARE
    renamed_count integer;
BEGIN
    WITH ranked AS (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY workspace_id, name
                   ORDER BY created_at DESC, id DESC
               ) AS rn
        FROM api_keys
        WHERE is_revoked = FALSE
    )
    UPDATE api_keys k
       SET name = k.name || ' (' || lower(right(k.id, 4)) || ')'
      FROM ranked r
     WHERE k.id = r.id AND r.rn > 1;
    GET DIAGNOSTICS renamed_count = ROW_COUNT;
    RAISE NOTICE 'V37: renamed % duplicate live key name(s) — (workspace_id, name) is unique for live keys (A18)', renamed_count;
END $$;

CREATE UNIQUE INDEX uq_api_keys_live_workspace_name
    ON api_keys (workspace_id, name)
    WHERE is_revoked = FALSE;
