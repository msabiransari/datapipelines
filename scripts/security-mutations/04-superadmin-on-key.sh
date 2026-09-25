# shellcheck shell=bash disable=SC2034
# Security mutation 04 — a key principal made a super admin when its user is one (B1 dropped).
# Record §9 row 7: a credential fence removed — no key is a super admin. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='a key principal made a super admin when its user is one (B1 dropped)'
RECORD_ROW='row 7: a credential fence removed — no key is a super admin'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/ApiKeyService.kt'
FROM='            superAdmin = false,'
TO='            superAdmin = actor.isAdmin,'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.ApiKeyServiceTest'
EXPECT_TEST='the MCP key acts as its member'"'"'s current role capped at author - and is never a super admin'
