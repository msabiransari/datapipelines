# shellcheck shell=bash disable=SC2034
# Security mutation 02 — an unannotated handler on a governed surface admitted (the default-deny inverted).
# Record §9 row 1: an undeclared route; the protected path must refuse. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='an unannotated handler on a governed surface admitted (the default-deny inverted)'
RECORD_ROW='row 1: an undeclared route; the protected path must refuse'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/ScopeInterceptor.kt'
FROM='if (isScopeGoverned(request)) denyUnannotated(request, response, handler) else true'
TO='if (!isScopeGoverned(request)) denyUnannotated(request, response, handler) else true'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.ScopeInterceptorTest'
EXPECT_TEST='an unannotated handler under the api prefix is denied by default'
