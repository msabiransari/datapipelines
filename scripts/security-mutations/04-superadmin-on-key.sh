# shellcheck shell=bash disable=SC2034
# Security mutation 04 — a key principal made a super admin (B1 dropped).
# Record §9 row 7: a credential fence removed — no key is a super admin. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
# Re-anchored by #470: keys v2 A13 retired the author cap, so the defect this expresses is the B1 flag itself — the test that must go red asserts a validated key's principal is never a super admin.
MUTATION_NAME='a key principal made a super admin (B1 dropped)'
RECORD_ROW='row 7: a credential fence removed — no key is a super admin'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/ApiKeyService.kt'
FROM='            superAdmin = false,'
TO='            superAdmin = true,'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.ApiKeyServiceTest'
EXPECT_TEST='an mcp key validates against its OWN role - its owner'"'"'s role is never consulted'
