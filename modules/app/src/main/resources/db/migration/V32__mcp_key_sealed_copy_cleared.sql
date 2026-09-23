-- =============================================================================
-- V32 — Show-once MCP key (#213), D16 amended by the owner's ruling of 2026-09-23
--
-- Authority: docs/superpowers/specs/2026-09-20-roles-permissions-design.md, D16 / §3.3
-- as amended 2026-09-23: the login-minted MCP key is copyable ONCE; the first Copy
-- destroys the sealed copy in the same statement that opens it, and the key is
-- hash-only from then on.
--
-- This migration makes the FLEET hash-only immediately: every sealed copy minted
-- under the pre-amendment rule (where the top bar could open it again and again) is
-- cleared, whether or not it was ever read. The reason, from the ruling: while
-- `api_keys.secret_sealed` holds a value the key is recoverable by the server
-- (AES-256-GCM under `datapipelines.db.encryption-key`), so it is not one-way at
-- rest. A user who still needs the plaintext rotates once — delete the key, sign in
-- again, and the new key is copyable until ITS first copy.
--
-- The column STAYS: keys minted after this migration are still sealed at mint
-- (nobody was shown the plaintext in a login redirect) and cleared on first read.
-- Only the pre-amendment copies, whose read state is unknowable, go to NULL.
--
-- NO DOWN PATH: a cleared copy cannot be restored (that is the point), and re-sealing
-- is impossible — the plaintext exists nowhere once cleared. Rotating forward is the
-- only way back to a copyable key.
-- =============================================================================

DO $$
DECLARE
    cleared_count integer;
BEGIN
    UPDATE api_keys SET secret_sealed = NULL WHERE secret_sealed IS NOT NULL;
    GET DIAGNOSTICS cleared_count = ROW_COUNT;
    RAISE NOTICE 'V32: cleared % sealed MCP key copy/copies — the fleet is hash-only (show-once, #213)', cleared_count;
END $$;
