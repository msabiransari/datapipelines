-- =============================================================================
-- V34 — Key identities and key roles (#215 slice (b))
--
-- Authority: docs/superpowers/specs/2026-09-23-permissions-and-keys-design.md (RATIFIED
-- 2026-09-23, amended 2026-09-24) §3 (keys, identities), §4 (scopes removed), PK5, PK6, PK8,
-- PK9; recorded as DDL in metadata-db.md §4.1 / §4.2.
--
-- 1. users.kind — 'human' | 'service' | 'system' (PK5). Every existing row is a person except
--    the System actor (auth.md §4.5), which is found by its reserved provider AND email. On a
--    fresh database the System row does not exist yet; its seeder writes kind = 'system' itself.
--
-- 2. api_keys.created_by — who created the key (B4). Until now user_id was owner AND creator;
--    it is backfilled from user_id on every row BEFORE user_id moves (step 3), so the Keys page's
--    "Created by" keeps naming the person who created each key.
--
-- 3. One 'service' identity per existing endpoint/server key (PK9), built exactly like the
--    System actor so login is impossible by construction: provider 'key' (reserved at startup,
--    configuration.md §7), provider_subject = the key id, email '<key id>@keys.invalid' (RFC 2606),
--    no password, is_admin FALSE, display_name = the key's name. A revoked key's identity is
--    created INACTIVE — revoking a key deactivates its identity (§3.3). The key's user_id then
--    points at its identity; the MCP (`user`) key keeps user_id = created_by = the member.
--    A COUNT CHECK refuses the migration unless identities created = endpoint + server keys.
--
-- 4. api_keys.role — the key's role (PK6, A1, A5): 'api_caller' on every endpoint key,
--    'promotion_receiver' on every server key, NULL on the MCP key (its role is its member's,
--    capped at author, read per request — PK4). A CHECK makes PK3/PK6 database facts — with
--    `role IS NOT NULL` on the two non-user arms, which the record's spelling lacks (see below).
--
-- 5. api_keys.scopes is DROPPED (PK8): the key's role replaces it.
--
-- Executions and audit rows are not touched: past runs keep their attribution (PK9).
--
-- DOWN PATH (manual, lossy by design): ALTER TABLE api_keys ADD COLUMN scopes TEXT[] NOT NULL
--   DEFAULT '{read}'; UPDATE api_keys SET scopes = '{author,execute,read}' WHERE kind = 'user';
--   UPDATE api_keys SET scopes = '{}' WHERE kind <> 'user'; UPDATE api_keys k SET user_id =
--   k.created_by WHERE k.kind <> 'user'; ALTER TABLE api_keys DROP CONSTRAINT chk_api_keys_role,
--   DROP COLUMN role, DROP COLUMN created_by; DELETE FROM users WHERE kind = 'service';
--   ALTER TABLE users DROP CONSTRAINT chk_users_kind, DROP COLUMN kind;
-- The MCP keys' original scopes are not recoverable (they were the role's reach at mint time);
-- re-granting `author` on the way down is the permissive choice and is only for a rollback that
-- also rolls the code back.
-- =============================================================================

ALTER TABLE users ADD COLUMN kind TEXT NOT NULL DEFAULT 'human';
ALTER TABLE users ADD CONSTRAINT chk_users_kind CHECK (kind IN ('human', 'service', 'system'));
UPDATE users SET kind = 'system' WHERE provider = 'system' AND email = 'system@system.invalid';

ALTER TABLE api_keys ADD COLUMN created_by UUID NULL REFERENCES users(id);
UPDATE api_keys SET created_by = user_id;
ALTER TABLE api_keys ALTER COLUMN created_by SET NOT NULL;

ALTER TABLE api_keys ADD COLUMN role TEXT NULL;

DO $$
DECLARE
    keys_to_migrate integer;
    identities_created integer;
    keys_repointed integer;
BEGIN
    SELECT COUNT(*) INTO keys_to_migrate FROM api_keys WHERE kind IN ('endpoint', 'server');

    INSERT INTO users (email, display_name, provider, provider_subject, is_active, is_admin, kind)
    SELECT lower(k.id) || '@keys.invalid', k.name, 'key', k.id, NOT k.is_revoked, FALSE, 'service'
      FROM api_keys k
     WHERE k.kind IN ('endpoint', 'server');
    GET DIAGNOSTICS identities_created = ROW_COUNT;

    UPDATE api_keys k
       SET user_id = u.id
      FROM users u
     WHERE u.provider = 'key' AND u.provider_subject = k.id AND k.kind IN ('endpoint', 'server');
    GET DIAGNOSTICS keys_repointed = ROW_COUNT;

    IF identities_created <> keys_to_migrate OR keys_repointed <> keys_to_migrate THEN
        RAISE EXCEPTION 'V34: % endpoint/server key(s), but % identit(ies) created and % key(s) repointed — refusing',
            keys_to_migrate, identities_created, keys_repointed;
    END IF;
    RAISE NOTICE 'V34: % endpoint/server key(s) now act as their own identity (PK9)', identities_created;
END $$;

UPDATE api_keys SET role = 'api_caller' WHERE kind = 'endpoint';
UPDATE api_keys SET role = 'promotion_receiver' WHERE kind = 'server';

-- `role IS NOT NULL` is not redundant: under SQL's three-valued logic `role = 'api_caller'` is
-- UNKNOWN for a NULL role, the whole OR is then UNKNOWN, and a CHECK passes on UNKNOWN — so the
-- record's §3.2 spelling would admit an `endpoint` or `server` key with no role at all.
ALTER TABLE api_keys ADD CONSTRAINT chk_api_keys_role CHECK (
    (kind = 'user' AND role IS NULL)
    OR (kind = 'endpoint' AND role IS NOT NULL AND role = 'api_caller')
    OR (kind = 'server' AND role IS NOT NULL AND role = 'promotion_receiver')
);

ALTER TABLE api_keys DROP COLUMN scopes;

CREATE INDEX idx_api_keys_created_by ON api_keys(created_by);
