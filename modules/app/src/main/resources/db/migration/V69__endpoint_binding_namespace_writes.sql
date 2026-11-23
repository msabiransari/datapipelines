-- =============================================================================
-- V69 — a GET node binding may also allow namespace writes (#1111, authoring-namespace-key)
--
-- An administrator of an AUTHORING deployment binds an endpoint key once at a namespace node with
-- `"allow": true` (POST /api/v1/endpoints/bindings); while the deployment's authoring capability is on,
-- that key may then call every live POST, PUT and DELETE route beneath the node, including routes
-- published later, wherever the node is the NEAREST node carrying any GET binding on the request path
-- (auth.md §7.7). The opt-in is a property of ONE GET binding row:
--
-- 1. `allow_namespace_writes BOOLEAN NOT NULL DEFAULT FALSE`. The DEFAULT is the backfill: every existing
--    row reads false, so no grant widens at upgrade (no UPDATE is needed and none runs).
-- 2. `chk_endpoint_key_bindings_namespace_writes`: only a GET row may carry true. An exact mutation grant
--    names one route entity (V65) and never holds the marker — a property of one row, so a CHECK, the
--    house convention V65 set for the row's shape.
--
-- Nothing else moves: exact mutation grants keep their endpoint identity, cascade and primary key. The
-- marker is deployment-local — promotion's wire carries key names only, and an ordinary repeat bind
-- (`INSERT … ON CONFLICT DO NOTHING`) never rewrites an existing row, so a receiver's own choice survives.
--
-- Down path (documented, not shipped — Flyway runs forward only): docs/metadata-db.md §4.14 "Rolling back
-- V69" — drop the constraint, then the column. A binary older than V69 ignores the column and serves the
-- exact grants only, so rolling back loses nothing but the opt-ins themselves.

ALTER TABLE endpoint_key_bindings ADD COLUMN allow_namespace_writes BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE endpoint_key_bindings ADD CONSTRAINT chk_endpoint_key_bindings_namespace_writes
    CHECK (NOT allow_namespace_writes OR method = 'GET');
