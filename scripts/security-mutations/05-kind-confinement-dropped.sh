# shellcheck shell=bash disable=SC2034
# Security mutation 05 — an endpoint key's route-family confinement dropped.
# Record §9 row 7: remove endpoint binding / key-kind confinement. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='an endpoint key'"'"'s route-family confinement dropped'
RECORD_ROW='row 7: remove endpoint binding / key-kind confinement'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/ScopeInterceptor.kt'
FROM='ApiKeyKind.ENDPOINT -> isPublishedEndpointPath(uri) || EXECUTION_READ.matches(uri)'
TO='ApiKeyKind.ENDPOINT -> uri.isNotEmpty() || isPublishedEndpointPath(uri) || EXECUTION_READ.matches(uri)'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.EndpointKeyConfinementTest'
EXPECT_TEST='an endpoint key is refused everywhere else'
