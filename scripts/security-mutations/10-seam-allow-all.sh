# shellcheck shell=bash disable=SC2034
# Security mutation 10 — the seam's production resolver replaced by an allow-all.
# Record §9 row 4: the permission decision itself. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='the seam'"'"'s production resolver replaced by an allow-all'
RECORD_ROW='row 4: the permission decision itself'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/PermissionResolver.kt'
FROM='): Boolean = (superAdmin && permission in RolePermissions.SUPER_ADMIN) || (role != null && permission in RolePermissions.of(role))'
TO='): Boolean = permission.wire.isNotEmpty() || (superAdmin && permission in RolePermissions.SUPER_ADMIN) || (role != null && permission in RolePermissions.of(role))'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.RoleMatrixTest'
EXPECT_TEST='each workspace role is admitted exactly its column of the catalog - every refusal on the role axis'
