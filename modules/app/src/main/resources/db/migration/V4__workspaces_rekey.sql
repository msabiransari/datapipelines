-- V4__workspaces_rekey.sql
--
-- Workspaces, slice 1: the persistence re-key (design doc
-- 2026-08-16-workspaces-design §3/§4, D2/D9; re-base resolutions R1/R2 as
-- recorded in metadata-db.md — the DDL authority this file is generated from).
--
--   * workspaces + workspace_members tables. The `default` workspace is seeded
--     with the well-known constant UUID defa0000-0000-0000-0000-000000000001
--     (R2: deterministic across deployments and greppable — a boot-time DB
--     lookup or a config key was considered and rejected) and created_by NULL
--     (R1: NULL = system-provisioned; the spec's NOT NULL gave the seed no
--     user to reference on a fresh install).
--   * pipelines and api_keys gain workspace_id NOT NULL, backfilled to
--     `default`. pipelines.name uniqueness moves from global to per-workspace —
--     the same mechanism (a plain UNIQUE constraint, soft-deleted rows
--     included), re-keyed (workspace_id, name).
--   * templates re-keys onto a surrogate UUID PK: today's TEXT id becomes
--     `name`, UNIQUE per workspace; template_versions.template_id re-keys onto
--     the surrogate. Pipeline-JSON and imports_json {id, version} refs keep
--     meaning the human id (now `name`) — stored payloads are immutable and
--     are not rewritten.
--   * datasources gains workspace_id (NULL = global — existing rows backfill
--     NULL, preserving shared behavior, D9) and is_readonly. Columns only; the
--     datasources module does not change in this slice.
--
-- No column DEFAULT pins workspace_id anywhere: slice-1 pins live in repository
-- code, greppable by the constant, because slice 2 must find them all.

-- ---------------------------------------------------------------------------
-- §4.11 workspaces
-- ---------------------------------------------------------------------------
CREATE TABLE workspaces (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name         TEXT        NOT NULL UNIQUE,          -- [a-z0-9_-]+, 1–63, immutable (referenced in config/UX)
    display_name TEXT        NOT NULL,
    is_personal  BOOLEAN     NOT NULL DEFAULT FALSE,   -- TRUE only for auto-per-user provisioned workspaces
    created_by   UUID        REFERENCES users(id),     -- R1: NULL = system-provisioned
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- §4.12 workspace_members
-- ---------------------------------------------------------------------------
CREATE TABLE workspace_members (
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    user_id      UUID        NOT NULL REFERENCES users(id),
    role         TEXT        NOT NULL DEFAULT 'member',
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT chk_workspace_member_role CHECK (role IN ('owner', 'member'))
);

-- D9: the pre-workspaces world was one shared space, so every pre-existing row
-- moves into `default` and every existing user joins it. Existing users enter
-- as 'owner': before workspaces every active user had full capability over the
-- shared content, and the backfill preserves that (global is_admin still
-- bypasses membership either way; role semantics arrive with slice 2).
INSERT INTO workspaces (id, name, display_name, is_personal, created_by)
VALUES ('defa0000-0000-0000-0000-000000000001', 'default', 'Default', FALSE, NULL);

INSERT INTO workspace_members (workspace_id, user_id, role)
SELECT 'defa0000-0000-0000-0000-000000000001', id, 'owner' FROM users;

-- ---------------------------------------------------------------------------
-- §4.4 pipelines: workspace ownership + per-workspace name uniqueness
-- ---------------------------------------------------------------------------
ALTER TABLE pipelines ADD COLUMN workspace_id UUID REFERENCES workspaces(id);
UPDATE pipelines SET workspace_id = 'defa0000-0000-0000-0000-000000000001';
ALTER TABLE pipelines ALTER COLUMN workspace_id SET NOT NULL;

-- Same mechanism as the global rule it replaces: a plain UNIQUE constraint —
-- not a partial index — so a soft-deleted pipeline's name stays taken within
-- its workspace until the row is hard-deleted (execution history references
-- the name, §4.4).
ALTER TABLE pipelines DROP CONSTRAINT pipelines_name_key;
ALTER TABLE pipelines ADD CONSTRAINT uq_pipelines_workspace_name UNIQUE (workspace_id, name);

-- ---------------------------------------------------------------------------
-- §4.8/§4.9 templates: surrogate UUID PK, TEXT id becomes `name`
-- ---------------------------------------------------------------------------
ALTER TABLE templates ADD COLUMN workspace_id UUID REFERENCES workspaces(id);
UPDATE templates SET workspace_id = 'defa0000-0000-0000-0000-000000000001';
ALTER TABLE templates ALTER COLUMN workspace_id SET NOT NULL;

-- gen_random_uuid() is volatile: the ADD COLUMN backfills a distinct surrogate
-- per existing row, and the DEFAULT stays for the post-rename PK column.
ALTER TABLE templates ADD COLUMN new_id UUID DEFAULT gen_random_uuid();
ALTER TABLE templates ALTER COLUMN new_id SET NOT NULL;

ALTER TABLE template_versions ADD COLUMN new_template_id UUID;
UPDATE template_versions tv
   SET new_template_id = t.new_id
  FROM templates t
 WHERE tv.template_id = t.id;
ALTER TABLE template_versions ALTER COLUMN new_template_id SET NOT NULL;

ALTER TABLE template_versions DROP CONSTRAINT template_versions_template_id_fkey;
ALTER TABLE template_versions DROP CONSTRAINT template_versions_pkey;
ALTER TABLE templates DROP CONSTRAINT templates_pkey;

-- idx_templates_active follows the rename and now serves the `name` listing path.
ALTER TABLE templates RENAME COLUMN id TO name;
ALTER TABLE templates RENAME COLUMN new_id TO id;
ALTER TABLE templates ADD PRIMARY KEY (id);
ALTER TABLE templates ADD CONSTRAINT uq_templates_workspace_name UNIQUE (workspace_id, name);

ALTER TABLE template_versions DROP COLUMN template_id;
ALTER TABLE template_versions RENAME COLUMN new_template_id TO template_id;
ALTER TABLE template_versions ADD PRIMARY KEY (template_id, version);
ALTER TABLE template_versions ADD CONSTRAINT template_versions_template_id_fkey
    FOREIGN KEY (template_id) REFERENCES templates(id) ON DELETE CASCADE;

-- ---------------------------------------------------------------------------
-- §4.10 datasources: workspace binding (NULL = global) + readonly flag
-- ---------------------------------------------------------------------------
ALTER TABLE datasources ADD COLUMN workspace_id UUID REFERENCES workspaces(id);
ALTER TABLE datasources ADD COLUMN is_readonly BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------
-- §4.2 api_keys: workspace pinning (D3)
-- ---------------------------------------------------------------------------
ALTER TABLE api_keys ADD COLUMN workspace_id UUID REFERENCES workspaces(id);
UPDATE api_keys SET workspace_id = 'defa0000-0000-0000-0000-000000000001';
ALTER TABLE api_keys ALTER COLUMN workspace_id SET NOT NULL;
