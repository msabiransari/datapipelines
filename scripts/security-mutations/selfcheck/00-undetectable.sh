# shellcheck shell=bash disable=SC2034
# The runner's self-check — a mutation NO test can see (a KDoc line), run by `run.sh --self-check`, which passes only when the
# runner reports "mutation 00 not detected". A runner that could report this as detected would count a green class as a guard.
MUTATION_NAME='a KDoc line reworded (no test reads it)'
RECORD_ROW='self-check of the runner'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/PublicPaths.kt'
FROM='    /** The patterns alone, in declaration order — what [SecurityConfig] feeds `requestMatchers`. */'
TO='    /** The patterns alone, in declaration order (a comment edit no test reads). */'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.PublicPathsTest'
EXPECT_TEST='the allowlist is exactly these patterns, in this order'
