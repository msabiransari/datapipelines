-- =============================================================================
-- V23 — RBAC round 1: capability moves into the workspace membership
--
-- Authority: docs/superpowers/specs/2026-09-10-rbac-design.md v1.0 (RATIFIED),
-- recorded as DDL in metadata-db.md §4.11 / §4.12 / §4.10 / §4.16.
--
-- Four changes, in dependency order:
--   1. workspace_members gains the three additive capability flags and loses `role`
--      (D-R1, D-R2, D-R14: owner → admin+author, member → author).
--   2. workspaces gains deactivation columns (D-R10) and the `demo` seed (D-R11).
--   3. datasource_workspaces replaces datasources.workspace_id — visibility is a
--      GRANT, and "global" is gone (D-R7, D-R14).
--   4. users.scopes is NOT dropped: it never existed. See the note at §4 below.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. §4.12 workspace_members — additive capability flags (D-R1, D-R2)
-- ---------------------------------------------------------------------------
-- "Viewer" is the row with all three FALSE. `admin` implies `author` (design §1):
-- a workspace admin can author, and the flag stays explicit so the matrix reads
-- directly off the row rather than deriving one flag from another at every check.
ALTER TABLE workspace_members
    ADD COLUMN author   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN promoter BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN admin    BOOLEAN NOT NULL DEFAULT FALSE;

-- D-R14: owner → workspace admin (and therefore author); member → author.
-- Before this migration a `member` had the whole authoring surface (session scopes
-- were `author` for every non-admin user — JwtService.scopesFor), so `member → author`
-- preserves exactly what worked yesterday rather than demoting anyone.
UPDATE workspace_members SET admin = TRUE, author = TRUE WHERE role = 'owner';
UPDATE workspace_members SET author = TRUE                WHERE role = 'member';

ALTER TABLE workspace_members
    ADD CONSTRAINT chk_workspace_member_admin_authors CHECK (NOT admin OR author);

ALTER TABLE workspace_members DROP CONSTRAINT chk_workspace_member_role;
ALTER TABLE workspace_members DROP COLUMN role;

-- The last-admin rule (design §1) is enforced in the service, not by a constraint:
-- "at least one admin per workspace" is a cross-row invariant a CHECK cannot express,
-- and a trigger's refusal would carry no catalogued error code (§13.12 workspace.last_admin).
-- This index makes the count that enforces it cheap.
CREATE INDEX idx_workspace_members_admins ON workspace_members(workspace_id) WHERE admin;

-- ---------------------------------------------------------------------------
-- 2. §4.11 workspaces — deactivation (D-R10) and the `demo` seed (D-R11)
-- ---------------------------------------------------------------------------
-- Deactivate, never delete. `is_deleted` (V4) stays what it is — the soft delete the
-- CRUD surface used — and deactivation is the new, reversible, never-purging state.
ALTER TABLE workspaces
    ADD COLUMN deactivated_at TIMESTAMPTZ NULL,
    ADD COLUMN deactivated_by UUID NULL REFERENCES users(id);

CREATE INDEX idx_workspaces_active ON workspaces(name) WHERE is_deleted = FALSE AND deactivated_at IS NULL;

-- D-R11: `demo` is the workspace the product ships, like the bootstrap admin. Seeded
-- here rather than by a boot-time seeder ONLY for the fresh-database case; the runtime
-- seeder (DemoWorkspaceSeeder) is what keeps it idempotent across restarts and what
-- honours O-3 — a DEACTIVATED `demo` is never recreated. `created_by` is NULL: R1's
-- "system-provisioned" convention (V4 §4.11), and no user row is guaranteed to exist here.
INSERT INTO workspaces (id, name, display_name, is_personal, created_by)
VALUES ('de000000-0000-0000-0000-000000000001', 'demo', 'Demo', FALSE, NULL)
ON CONFLICT (name) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. §4.16 datasource_workspaces — visibility is a grant (D-R7, D-R14)
-- ---------------------------------------------------------------------------
-- NOTE ON THE KEY: the design record writes `datasource_id UUID REFERENCES
-- datasources(id)`. There is no such column — `datasources` is keyed by `name TEXT`
-- (V1 §4.10) and always has been. The grant table therefore keys on the real primary
-- key. Recorded as a design-record correction in the 112 handback.
CREATE TABLE datasource_workspaces (
    datasource_name TEXT        NOT NULL REFERENCES datasources(name) ON DELETE CASCADE,
    workspace_id    UUID        NOT NULL REFERENCES workspaces(id),
    granted_by      UUID        NOT NULL REFERENCES users(id),
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (datasource_name, workspace_id)
);

CREATE INDEX idx_datasource_workspaces_workspace ON datasource_workspaces(workspace_id);

-- `owner_workspace_id`: set when a workspace admin registered the datasource (it is
-- granted to that workspace and they cannot grant it elsewhere); NULL when a super
-- admin registered it as an instance datasource (design §4).
ALTER TABLE datasources ADD COLUMN owner_workspace_id UUID NULL REFERENCES workspaces(id);

-- D-R14, the workspace-bound half: granted to its own workspace, owned by it.
-- `granted_by` is the datasource's CREATOR — the design record says "the bootstrap
-- admin or the system user", but `datasources.created_by` is NOT NULL and is the
-- honest actor: whoever registered it is who the grant descends from. Using it also
-- keeps this migration from having to mint a users row.
INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by, granted_at)
SELECT d.name, d.workspace_id, d.created_by, NOW()
  FROM datasources d
 WHERE d.workspace_id IS NOT NULL;

UPDATE datasources SET owner_workspace_id = workspace_id WHERE workspace_id IS NOT NULL;

-- D-R14, the global half: a `global` datasource (workspace_id IS NULL) is granted to
-- EVERY existing workspace, so nothing that worked yesterday stops today. Owner stays
-- NULL — an instance datasource belongs to no workspace.
INSERT INTO datasource_workspaces (datasource_name, workspace_id, granted_by, granted_at)
SELECT d.name, w.id, d.created_by, NOW()
  FROM datasources d
 CROSS JOIN workspaces w
 WHERE d.workspace_id IS NULL
   AND w.is_deleted = FALSE
ON CONFLICT DO NOTHING;

ALTER TABLE datasources DROP COLUMN workspace_id;

-- ---------------------------------------------------------------------------
-- 4. api_keys: `admin` leaves the key wire (D-R12, O-2)
-- ---------------------------------------------------------------------------
-- `admin` was the only scope that ever bought a key an INSTANCE verb, and instance
-- verbs — creating workspaces, managing members, releasing, promoting — are human.
-- Stripped here so the stored rows agree with the rule; ApiKeyService ALSO filters it
-- at validation, so the rule does not rest on this statement having run. A key left
-- with no scopes at all keeps `read`, the §7.5 floor: emptying a credential silently
-- would break an integration in a way nothing explains.
UPDATE api_keys SET scopes = array_remove(scopes, 'admin') WHERE 'admin' = ANY(scopes);
UPDATE api_keys SET scopes = ARRAY['read'] WHERE cardinality(scopes) = 0 AND kind = 'user';

-- ---------------------------------------------------------------------------
-- 5. users.scopes — the drop the design record asked for, and why it is absent
-- ---------------------------------------------------------------------------
-- D-R1 says "users.scopes goes away". There is no `users.scopes` column and there
-- never was: V1 §4.1 has `is_admin` and no scope array, and the only `scopes TEXT[]`
-- in the schema is `api_keys.scopes` (V1 §4.2), which STAYS — keys keep their scopes
-- (D-R12). What actually carried global session capability is `JwtService.scopesFor`,
-- which derived `admin`-or-`author` from `users.is_admin` in code; that derivation is
-- what round 1 removes, in Kotlin, not in SQL. `users.is_admin` stays and means SUPER
-- ADMIN. Recorded as a design-record correction in the 112 handback.
