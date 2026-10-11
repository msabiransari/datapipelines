# shellcheck shell=bash disable=SC2034
# Security mutation 05 — an endpoint key's route-family confinement dropped.
# Record §9 row 7: remove endpoint binding / key-kind confinement. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
# Re-anchored by #470: 6d7b18f0 moved result paging under the served path, so the arm no longer ORs EXECUTION_READ in — the current arm is the bare published-path check.
MUTATION_NAME='an endpoint key'"'"'s route-family confinement dropped'
RECORD_ROW='row 7: remove endpoint binding / key-kind confinement'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/ScopeInterceptor.kt'
FROM='ApiKeyKind.ENDPOINT -> isPublishedEndpointPath(uri)'
TO='ApiKeyKind.ENDPOINT -> true'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.EndpointKeyConfinementTest'
EXPECT_TEST='an endpoint key is refused everywhere else'
