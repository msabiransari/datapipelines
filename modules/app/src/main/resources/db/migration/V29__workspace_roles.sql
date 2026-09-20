-- =============================================================================
-- V29 — Roles R1 (#177): ONE role per membership and per invitation
--
-- Authority: docs/superpowers/specs/2026-09-20-roles-permissions-design.md (RATIFIED),
-- D1 / D20 / D21; recorded as DDL in metadata-db.md §4.12 / §4.17.
--
-- V23 replaced the v1 `role` column with three additive flags so that "an author who
-- also releases" could be one row. The 2026-09-20 rulings took release away from the
-- promoter and made it an ops role that authors nothing, so the combination the flags
-- existed to express no longer exists. This migration folds them back into ONE value,
-- `role TEXT` with a CHECK, on both tables — the members and the invitations that
-- materialise into them (V24 mirrored the flags; it mirrors the role).
--
-- Precedence for existing rows (the record's §5, R1):
--   admin → 'workspace_admin', else promoter → 'promoter', else author → 'author',
--   else 'viewer'.
-- An author+promoter row becomes a PROMOTER: under the new matrix that person keeps the
-- promote verb they had and loses authoring; a workspace admin who wants them authoring
-- instead changes the role on the members page. The choice is recorded here so it is
-- a decision, not an accident of column order.
--
-- DOWN PATH (manual — Flyway runs forward only):
--   ALTER TABLE workspace_members ADD COLUMN author BOOLEAN NOT NULL DEFAULT FALSE,
--       ADD COLUMN promoter BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN admin BOOLEAN NOT NULL DEFAULT FALSE;
--   UPDATE workspace_members SET admin = TRUE, author = TRUE WHERE role = 'workspace_admin';
--   UPDATE workspace_members SET promoter = TRUE                WHERE role = 'promoter';
--   UPDATE workspace_members SET author = TRUE                  WHERE role = 'author';
--   ALTER TABLE workspace_members ADD CONSTRAINT chk_workspace_member_admin_authors CHECK (NOT admin OR author);
--   DROP INDEX idx_workspace_members_admins;
--   CREATE INDEX idx_workspace_members_admins ON workspace_members(workspace_id) WHERE admin;
--   ALTER TABLE workspace_members DROP CONSTRAINT chk_workspace_member_role, DROP COLUMN role;
--   -- and the same four statements for workspace_invitations (constraint
--   -- chk_workspace_invitation_admin_authors; no index).
--   The down path is lossless for every row this migration produced: each of the four role
--   values maps back to exactly one flag triple. It cannot recover an author+promoter row
--   (which is the point of the change).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1. §4.12 workspace_members — the role column
-- ---------------------------------------------------------------------------
ALTER TABLE workspace_members ADD COLUMN role TEXT;

UPDATE workspace_members
   SET role = CASE
                WHEN admin    THEN 'workspace_admin'
                WHEN promoter THEN 'promoter'
                WHEN author   THEN 'author'
                ELSE               'viewer'
              END;

ALTER TABLE workspace_members
    ALTER COLUMN role SET NOT NULL,
    ALTER COLUMN role SET DEFAULT 'viewer',
    ADD CONSTRAINT chk_workspace_member_role
        CHECK (role IN ('viewer', 'author', 'promoter', 'workspace_admin'));

-- The last-admin rule's counter (design §1) follows the column it counts. Same index
-- name, so FlywayMigrationIntegrationTest's index inventory does not move.
DROP INDEX idx_workspace_members_admins;
CREATE INDEX idx_workspace_members_admins ON workspace_members(workspace_id) WHERE role = 'workspace_admin';

ALTER TABLE workspace_members DROP CONSTRAINT chk_workspace_member_admin_authors;
ALTER TABLE workspace_members
    DROP COLUMN author,
    DROP COLUMN promoter,
    DROP COLUMN admin;

-- ---------------------------------------------------------------------------
-- 2. §4.17 workspace_invitations — the same shape (an invitation carries ONE role, D20)
-- ---------------------------------------------------------------------------
ALTER TABLE workspace_invitations ADD COLUMN role TEXT;

UPDATE workspace_invitations
   SET role = CASE
                WHEN admin    THEN 'workspace_admin'
                WHEN promoter THEN 'promoter'
                WHEN author   THEN 'author'
                ELSE               'viewer'
              END;

ALTER TABLE workspace_invitations
    ALTER COLUMN role SET NOT NULL,
    ALTER COLUMN role SET DEFAULT 'viewer',
    ADD CONSTRAINT chk_workspace_invitation_role
        CHECK (role IN ('viewer', 'author', 'promoter', 'workspace_admin'));

ALTER TABLE workspace_invitations DROP CONSTRAINT chk_workspace_invitation_admin_authors;
ALTER TABLE workspace_invitations
    DROP COLUMN author,
    DROP COLUMN promoter,
    DROP COLUMN admin;
