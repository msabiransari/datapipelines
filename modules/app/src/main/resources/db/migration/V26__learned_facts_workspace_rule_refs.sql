-- =============================================================================
-- V26 — A WORKSPACE rule may carry no refs (136 §B / T278)
--
-- Authority: 136 dispatch (the 2026-09-14 five-pipeline acceptance run); recorded
-- in metadata-db.md §4.18.
--
-- 118's v1 bound every learned fact to at least one table of ONE datasource
-- (`chk_learned_facts_refs`: ≥ 1 ref). A workspace RULE can span datasources —
-- "busiest day = taxi + rideshare COMBINED" names two — and the recorder
-- validates every ref against the fact's single datasource, so the rule was
-- refused twice and stored against one side with the other in prose. The minimal
-- change: for the three WORKSPACE-scope kinds (`definition`, `exclusion`,
-- `preference`) `refs_json` may be the empty array — the fact stays bound to a
-- datasource (its `datasource_name`, for visibility and for the listing) but
-- names no table. DATASOURCE-scope kinds keep requiring a ref: a unit without a
-- column is meaningless. The array-typed half of the CHECK is unchanged.
-- =============================================================================

ALTER TABLE learned_facts DROP CONSTRAINT chk_learned_facts_refs;

ALTER TABLE learned_facts ADD CONSTRAINT chk_learned_facts_refs CHECK (
    jsonb_typeof(refs_json) = 'array'
    AND (scope = 'WORKSPACE' OR jsonb_array_length(refs_json) >= 1)
);
