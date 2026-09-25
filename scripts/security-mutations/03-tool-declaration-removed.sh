# shellcheck shell=bash disable=SC2034
# Security mutation 03 — an MCP tool that declares no permission defaulted to one instead of refused.
# Record §9 row 3: remove the dispatcher's declaration check. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='an MCP tool that declares no permission defaulted to one instead of refused'
RECORD_ROW='row 3: remove the dispatcher'"'"'s declaration check'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/ScopeMatrix.kt'
FROM='                ?: return Decision.Refused('
TO='                ?: Permission.DOCS_READ.takeIf { tool.isNotEmpty() } ?: return Decision.Refused('
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.RoleMatrixTest'
EXPECT_TEST='a tool that declares no permission is refused, never defaulted'
