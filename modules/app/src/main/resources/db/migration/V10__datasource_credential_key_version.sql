-- V10__datasource_credential_key_version.sql
--
-- The key-version byte on every stored datasource credential (datasources.md §7.1, round 068).
--
-- The credential blob gained a leading key-version byte:
--
--     before:  nonce(12) ‖ ciphertext ‖ tag(16)
--     after:   version(1) ‖ nonce(12) ‖ ciphertext ‖ tag(16)
--
-- The version selects which data key CredentialEncryptor decrypts with, which is what makes
-- key rotation LAZY-SAFE: a row written under key 1 keeps decrypting after the current key
-- moves to 2, and is rewritten under the current key on its next password write (§7.3).
--
-- Every pre-round row was written under the single key from `datapipelines.db.encryption-key`,
-- which the `env` key provider defines as version 1 — forever. So the backfill is DETERMINISTIC,
-- not a heuristic: prefix 0x01. There is deliberately no decrypt-and-guess fallback in the
-- application; after this migration the encryptor accepts ONLY versioned blobs, and a blob still
-- in the old layout is a defect to surface, not a shape to tolerate.
--
-- Idempotence is not attempted and is not needed: Flyway applies a versioned migration exactly
-- once per database, and running this twice would prefix a second byte. What IS guarded is the
-- scope — only rows that actually carry a credential, and only rows that exist now.
--
-- Verification (the round's live proof, and the query §7.3 hands the operator):
--     SELECT get_byte(password_encrypted, 0) AS key_version, count(*)
--     FROM datasources WHERE password_encrypted IS NOT NULL GROUP BY 1 ORDER BY 1;
-- Immediately after this migration every row reads key_version = 1.

UPDATE datasources
SET password_encrypted = '\x01'::bytea || password_encrypted
WHERE password_encrypted IS NOT NULL;
