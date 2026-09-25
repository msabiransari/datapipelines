# shellcheck shell=bash disable=SC2034
# Security mutation 08 — the workspace predicate dropped from PipelineRepository.findById.
# Record §9 row 6: remove a workspace predicate. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='the workspace predicate dropped from PipelineRepository.findById'
RECORD_ROW='row 6: remove a workspace predicate'
TARGET='modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/PipelineRepository.kt'
FROM='"$SELECT_COLUMNS WHERE id = :id AND workspace_id = :workspaceId AND $ENTITY_LIVE",'
TO='"$SELECT_COLUMNS WHERE id = :id AND $ENTITY_LIVE",'
EXPECT_TASK=':tests:integration-tests:test'
EXPECT_CLASS='co.datapipelines.integration.WorkspaceIsolationSweepTest'
EXPECT_TEST='no REST route hands an ACME session a GLOBEX row'
