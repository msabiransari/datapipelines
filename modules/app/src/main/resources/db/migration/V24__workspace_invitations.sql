-- =============================================================================
-- V24 — Workspace invitations (113): add a person BEFORE they have ever signed in
--
-- Authority: 113 dispatch (owner scenario 2026-09-10) + auth.md §4.6; recorded as
-- DDL in metadata-db.md §4.17.
--
-- Today `POST /workspaces/{name}/members` requires the `users` row to exist, and
-- an SSO user's row is created only at their FIRST login — so a workspace admin
-- cannot add `bob@company.com` before Bob has signed in. This table is the
-- bridge: an invitation keyed by email that the login path materialises.
--
-- Separate from `workspace_members` ON PURPOSE: the members table's `user_id`
-- stays NOT NULL and nothing pretends a person exists before they do. No expiry
-- column in v1 (owner: an invitation is a membership waiting for its user;
-- revocable like any other membership).
-- =============================================================================

CREATE TABLE workspace_invitations (
    workspace_id UUID        NOT NULL REFERENCES workspaces(id),
    email        TEXT        NOT NULL,                       -- normalized lowercase, the §4.2 rule
    author       BOOLEAN     NOT NULL DEFAULT FALSE,
    promoter     BOOLEAN     NOT NULL DEFAULT FALSE,
    admin        BOOLEAN     NOT NULL DEFAULT FALSE,
    invited_by   UUID        NOT NULL REFERENCES users(id),
    invited_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (workspace_id, email),
    CONSTRAINT chk_workspace_invitation_admin_authors CHECK (NOT admin OR author),
    CONSTRAINT chk_workspace_invitation_email_lower  CHECK (email = lower(email))
);

-- The login path materialises by EMAIL (the PK is (workspace_id, email), so a
-- workspace-scoped listing uses its prefix, but the materialise lookup cannot).
-- Same reasoning as V23's idx_datasource_workspaces_workspace: one index for the
-- one lookup that does not walk the primary key.
CREATE INDEX idx_workspace_invitations_email ON workspace_invitations(email);
