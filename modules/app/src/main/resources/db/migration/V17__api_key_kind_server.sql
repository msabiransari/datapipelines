-- V17__api_key_kind_server.sql
--
-- `server` joins the API-key kind set (auth.md §7.7, enums.md §8A, round 091).
--
-- The promotion receiver's credential (versioning §10.6) has until now been a pre-shared
-- CONFIG value — `datapipelines.deployment.promotion.server-key` — which no operator can
-- mint, list, expire or revoke without editing a file and restarting the deployment. A
-- `server` key is that same credential stored like every other key: an Argon2id hash, a
-- `dpk_` prefix, an owner, an expiry and a revocation flag. It carries NO scopes; its
-- authority is the route family it opens (`/api/v1/promotion/**`) and nothing else, the same
-- way an `endpoint` key's authority is its bindings.
--
-- V11 named `chk_api_keys_kind` "so a later kind widens it the way V3 widened
-- chk_triggered_via" — this is that later kind. Postgres has no ALTER for a CHECK's
-- expression, so the constraint is dropped and recreated; additive in effect, since every
-- value it admitted before it admits now.
--
-- No data changes: no existing row can be a `server` key (the value did not exist), so there
-- is nothing to backfill.

ALTER TABLE api_keys DROP CONSTRAINT chk_api_keys_kind;

ALTER TABLE api_keys
    ADD CONSTRAINT chk_api_keys_kind CHECK (kind IN ('user', 'endpoint', 'server'));
