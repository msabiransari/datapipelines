# shellcheck shell=bash disable=SC2034
# Security mutation 09 — the promotion route's credential admits a key of any kind.
# Record §9 row 7: remove promotion credential confinement. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='the promotion route'"'"'s credential admits a key of any kind'
RECORD_ROW='row 7: remove promotion credential confinement'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/ApiKeyService.kt'
FROM='if (!record.isServerKey) throw ApiKeyInvalidException()'
TO='if (!record.isServerKey && record.kind == ApiKeyKind.SERVER) throw ApiKeyInvalidException()'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.ApiKeyServiceTest'
EXPECT_TEST='validateServerKey returns the key and the identity it acts as, and refuses every other kind with the SAME answer'
