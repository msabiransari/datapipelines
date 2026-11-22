-- =============================================================================
-- V67 — published mutation reservations (#1039, published-mutation-apis design record §2, §4, §6)
--
-- One row per ADMITTED published POST, PUT or DELETE. The row is two things at once:
--
-- 1. The durable Idempotency-Key reservation. It is written BEFORE the execution starts — before any
--    business SQL — in the metadata database, so it survives cache loss, an application restart and the loss
--    of the replica that admitted it, and the UNIQUE scope digest makes two replicas racing one key agree on
--    ONE execution id. `scope_digest` is a keyed digest (HMAC-SHA256 under a subkey of the deployment secret)
--    over (workspace, credential, route entity, method, concrete business path, client key): the raw
--    Idempotency-Key is never stored. `request_fingerprint` is a keyed digest over (method, concrete path,
--    query parameters, canonical JSON body): the body is never stored either. Both are NULL once the
--    reservation is RETIRED (its replay window elapsed and the same scope was reserved again); NULLs are
--    distinct under UNIQUE, so any number of retired rows coexist with the one live reservation.
--
-- 2. The execution's ORIGIN: which route entity (and API version, and exact pipeline pin), which method, which
--    concrete business path and which credential admitted it. Polling under the business path checks these
--    against the caller, so a GET grant at another route cannot substitute for the original mutation grant.
--    The origin outlives the replay window (a retired row keeps it), exactly as the execution row does.
--
-- `owner_token` is the admitting replica's claim on STARTING the run; a retry that finds the reservation with
-- no execution row after the start window takes it over by a compare-and-set on the token. The execution row's
-- primary key is the start fence (the run records fail-closed), so a slow owner and a taking-over retry can
-- never both run business SQL.
--
-- No foreign key to pipeline_executions: the reservation precedes the execution row by design. No foreign key
-- to published_endpoints: deleting a route must not erase the origin of the runs it admitted (polling then
-- answers the opaque 404 because the route is not live). The credential cascades: a purged key's runs cannot be
-- polled by anyone, so its origin rows go with it; a workspace cascades likewise.
--
-- Down path (documented, not shipped — Flyway runs forward only): docs/metadata-db.md §7.6. A binary older
-- than V67 ignores this table and refuses every mutation with `503 endpoint.mutations_disabled` (it predates
-- mutation serving), so it can neither reinterpret a reservation nor serve a non-GET route; no barrier needed.

CREATE TABLE published_mutation_reservations (
    execution_id         UUID        PRIMARY KEY,
    workspace_id         UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    api_key_id           TEXT        NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    endpoint_id          UUID        NOT NULL,
    api_version          INT         NOT NULL,
    pipeline_id          UUID        NOT NULL,
    pipeline_version     INT         NOT NULL,
    has_caller           BOOLEAN     NOT NULL,
    method               TEXT        NOT NULL,
    business_path        TEXT        NOT NULL,
    scope_digest         TEXT        NULL,
    request_fingerprint  TEXT        NULL,
    body_bytes           BIGINT      NULL,
    owner_token          UUID        NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    retired_at           TIMESTAMPTZ NULL,
    CONSTRAINT uq_published_mutation_reservations_scope UNIQUE (scope_digest),
    CONSTRAINT chk_published_mutation_reservations_method CHECK (method IN ('POST', 'PUT', 'DELETE')),
    CONSTRAINT chk_published_mutation_reservations_live CHECK (
        (retired_at IS NULL AND scope_digest IS NOT NULL AND request_fingerprint IS NOT NULL)
        OR (retired_at IS NOT NULL AND scope_digest IS NULL AND request_fingerprint IS NULL)
    )
);

-- The credential cascade's lookup: purging a key deletes its origin rows without a sequential scan.
CREATE INDEX idx_published_mutation_reservations_key ON published_mutation_reservations (api_key_id);
