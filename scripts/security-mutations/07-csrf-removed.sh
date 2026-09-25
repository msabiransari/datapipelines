# shellcheck shell=bash disable=SC2034
# Security mutation 07 — the CSRF double-submit check removed (every request exempted).
# Record §9 §8 browser state (CSRF) — no §9 row names it. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
MUTATION_NAME='the CSRF double-submit check removed (every request exempted)'
RECORD_ROW='§8 browser state (CSRF) — no §9 row names it'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/SecurityConfig.kt'
FROM='csrf.ignoringRequestMatchers(ApiKeyCredentialMatcher(), PromotionRouteMatcher())'
TO='csrf.ignoringRequestMatchers(ApiKeyCredentialMatcher(), PromotionRouteMatcher(), org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE)'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.AuthHttpBoundaryTest'
EXPECT_TEST='a cookie-authenticated state change with no csrf token is 403 reason=missing'
