-- V3__execution_lineage.sql
--
-- Composition lineage (design doc 2026-08-13-pipeline-node-type, §5): a PIPELINE node
-- spawns a real child execution; these columns link the family.
--   root_execution_id: top ancestor; equals execution_id for roots. Backfilled = own id,
--   NOT NULL from here on — family queries and cancellation never special-case NULL.
--   parent_execution_id / parent_node_id: NULL for roots.
-- Also extends chk_triggered_via: child executions record 'PIPELINE'.

ALTER TABLE pipeline_executions ADD COLUMN parent_execution_id UUID REFERENCES pipeline_executions(execution_id);
ALTER TABLE pipeline_executions ADD COLUMN parent_node_id TEXT;
ALTER TABLE pipeline_executions ADD COLUMN root_execution_id UUID;

UPDATE pipeline_executions SET root_execution_id = execution_id;
ALTER TABLE pipeline_executions ALTER COLUMN root_execution_id SET NOT NULL;

CREATE INDEX idx_executions_root ON pipeline_executions(root_execution_id);

ALTER TABLE pipeline_executions DROP CONSTRAINT chk_triggered_via;
ALTER TABLE pipeline_executions ADD CONSTRAINT chk_triggered_via
    CHECK (triggered_via IN ('UI', 'REST', 'MCP', 'PIPELINE'));
