# shellcheck shell=bash disable=SC2034
# Security mutation 06 — a PublicPaths glob widened to /**.
# Record §9 row 2: a handler beneath a public wildcard. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='a PublicPaths glob widened to /**'
RECORD_ROW='row 2: a handler beneath a public wildcard'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/PublicPaths.kt'
FROM='                "/docs/*",'
TO='                "/**",'
EXPECT_TASK=':tests:integration-tests:test'
EXPECT_CLASS='co.datapipelines.integration.PublicContractE2eTest'
EXPECT_TEST='every public handler is named in its glob'"'"'s row'
