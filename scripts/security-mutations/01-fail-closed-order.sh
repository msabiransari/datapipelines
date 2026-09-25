# shellcheck shell=bash disable=SC2034
# Security mutation 01 — the interceptor's fail-closed order inverted — kind confinement judged only where no permission is declared.
# Record §9 row 3: bypass the interceptor's wiring. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='the interceptor'"'"'s fail-closed order inverted — kind confinement judged only where no permission is declared'
RECORD_ROW='row 3: bypass the interceptor'"'"'s wiring'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/ScopeInterceptor.kt'
FROM='if (principal != null && kind != null && !reachableBy(kind, request.appPath())) {'
TO='if (principal != null && kind != null && !reachableBy(kind, request.appPath()) && declaredPermission(handler) == null) {'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.ScopeInterceptorTest'
EXPECT_TEST='an MCP key off mcp is refused with the confinement code before its role is judged'
