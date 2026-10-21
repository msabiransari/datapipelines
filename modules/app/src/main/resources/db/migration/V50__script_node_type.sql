-- V50 — the TRANSFORM→SCRIPT node-type rename (owner ruling 2026-10-20, script-node-rename lane)
--
-- The wire's node type renames TRANSFORM→SCRIPT with no alias and no deprecation window.
-- Stored pipeline versions are the only place the old name survives on disk, so V50 rewrites
-- every stored body's node `type` values and re-stamps body_hash for exactly the rows it
-- touched — the hash pins the body text, so a rewrite without a re-stamp would fail every
-- release precondition and integrity read.
--
-- What is deliberately NOT rewritten: prose that merely mentions the word — node ids,
-- descriptions, any free-text value. The migration rewrites the typed field, never text.
-- Templates carry no node type; their V33 constraint renames (the file itself stays
-- byte-identical — shipped migrations are immutable).

-- 1. Node `type` values in stored pipeline bodies: an order-preserving rebuild of the
--    `nodes` array, hash re-stamped in the same statement (all SET expressions read the
--    rewritten body from the CTE, so hash and body can never disagree).
WITH rewritten AS (
    SELECT pipeline_id,
           version,
           jsonb_set(
               body_json,
               '{nodes}',
               (
                   SELECT jsonb_agg(
                              CASE WHEN n->>'type' = 'TRANSFORM'
                                       THEN jsonb_set(n, '{type}', '"SCRIPT"'::jsonb)
                                  ELSE n
                              END
                              ORDER BY ord
                          )
                     FROM jsonb_array_elements(body_json->'nodes') WITH ORDINALITY AS t(n, ord)
               )
           ) AS new_body
      FROM pipeline_versions
     WHERE jsonb_typeof(body_json->'nodes') = 'array'
       AND EXISTS (
               SELECT 1
                 FROM jsonb_array_elements(body_json->'nodes') n
                WHERE n->>'type' = 'TRANSFORM'
           )
)
UPDATE pipeline_versions v
   SET body_json = r.new_body,
       body_hash = encode(sha256(convert_to(r.new_body::text, 'UTF8')), 'hex')
  FROM rewritten r
 WHERE v.pipeline_id = r.pipeline_id
   AND v.version = r.version;

-- 2. The template-version blocks constraint, renamed to the new vocabulary. V33 created it
--    as chk_transform_blocks; the rename is metadata-only (no row is rewritten, no recheck).
ALTER TABLE template_versions
    RENAME CONSTRAINT chk_transform_blocks TO chk_script_blocks;
