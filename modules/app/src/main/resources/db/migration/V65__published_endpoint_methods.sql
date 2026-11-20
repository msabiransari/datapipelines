-- =============================================================================
-- V65 — published endpoints get METHOD identity; bindings get exact mutation grants
-- (#1037, published-mutation-apis design record §§2–4)
--
-- 1. published_endpoints gains `method` (GET | POST | PUT | DELETE, default 'GET'): half of a route's
--    identity, as immutable as the path. A route is one (method, path) pair serving one pinned pipeline
--    version; separate entities within a workspace may share a path ACROSS methods, so the deployment-wide
--    UNIQUE(path_pattern) narrows to UNIQUE(path_pattern, method) — a URL is still global, but per method.
--    The §4.1 ambiguity refusal widens with it, in code: within a workspace, overlapping patterns conflict
--    for the SAME method only (GET and POST may overlap); across workspaces they conflict for EVERY method
--    (V11's "a URL has exactly one meaning" holds per method, and a cross-workspace conflict answers with a
--    REDACTED 409 — no other workspace, method or pattern in the body).
--
-- 2. endpoint_key_bindings gains `method` (same set, default 'GET') and `endpoint_id` (NULL for GET rows):
--    a GET binding stays the tree-node grant it always was (path_prefix is a NODE; the ancestor walk is
--    unchanged and reads GET rows only); a mutation grant names exactly ONE route ENTITY — path_prefix is
--    that entity's full pattern, endpoint_id its identity, so a recreated route never inherits a predecessor's
--    grant, and ON DELETE CASCADE retires the grant with its entity. The PK widens to (path_prefix,
--    api_key_id, method): one key may hold the GET node grant and every mutation grant at the same pattern.
--
-- Backfill: every existing row of both tables is GET ("migration labels every existing binding GET at its
-- current node" — no row moves, no row's meaning changes). DEFAULT 'GET' is the backfill; no UPDATE needed.
--
-- The CHECK is the house convention for an enum column (V11's chk_api_keys_kind, chk_triggered_via): the
-- database refuses a value the application's enum cannot name. The bindings' shape rule — a GET row has no
-- entity, a mutation row must — is a CHECK for the same reason: it is a property of ONE row, and a
-- binding-grant written without its entity (or a GET row half-describing a mutation) is a state no code
-- path produces and none should ever read.
--
-- Down path (documented, not shipped — Flyway runs forward only): docs/metadata-db.md §7.4 "Rolling back V65"
-- is the ONLY text; PublishedMethodDowngradeBarrierIntegrationTest extracts and executes it. It deletes every
-- non-GET route (versions and grants cascade), restores V11's constraints and removes V65's and the
-- barrier's flyway_schema_history rows. A binary older than V65 does NOT refuse to start on this schema by
-- itself (Flyway ignores a future versioned migration) and would serve non-GET metadata as GET — the
-- repeatable db/downgrade-barrier/R__published_endpoint_method_downgrade_barrier.sql is what makes it refuse.

ALTER TABLE published_endpoints ADD COLUMN method TEXT NOT NULL DEFAULT 'GET';
ALTER TABLE published_endpoints ADD CONSTRAINT chk_published_endpoints_method
    CHECK (method IN ('GET', 'POST', 'PUT', 'DELETE'));

-- Replace the deployment-wide path unique with the per-method one (V11's note still holds per method).
-- (V11 declared the original table-level, so Postgres named it with the FULL column name —
--  published_endpoints_path_pattern_key.)
ALTER TABLE published_endpoints DROP CONSTRAINT published_endpoints_path_pattern_key;
ALTER TABLE published_endpoints
    ADD CONSTRAINT uq_published_endpoints_path_method UNIQUE (path_pattern, method);

ALTER TABLE endpoint_key_bindings ADD COLUMN method TEXT NOT NULL DEFAULT 'GET';
ALTER TABLE endpoint_key_bindings ADD CONSTRAINT chk_endpoint_key_bindings_method
    CHECK (method IN ('GET', 'POST', 'PUT', 'DELETE'));
ALTER TABLE endpoint_key_bindings ADD COLUMN endpoint_id UUID
    REFERENCES published_endpoints(id) ON DELETE CASCADE;
ALTER TABLE endpoint_key_bindings ADD CONSTRAINT chk_endpoint_key_bindings_shape
    CHECK ((method = 'GET' AND endpoint_id IS NULL) OR (method <> 'GET' AND endpoint_id IS NOT NULL));

-- One key holds each (node, method) grant once; several keys may hold the same grant.
ALTER TABLE endpoint_key_bindings DROP CONSTRAINT endpoint_key_bindings_pkey;
ALTER TABLE endpoint_key_bindings
    ADD CONSTRAINT pk_endpoint_key_bindings PRIMARY KEY (path_prefix, api_key_id, method);

-- The mutation grant's entity lookup: "which keys may call THIS route entity".
CREATE INDEX idx_endpoint_key_bindings_endpoint ON endpoint_key_bindings(endpoint_id)
    WHERE endpoint_id IS NOT NULL;
