# shellcheck shell=bash disable=SC2034
# Security mutation 07 — the CSRF double-submit check removed (every request exempted).
# Record §9 §8 browser state (CSRF) — no §9 row names it. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
# Re-anchored by #470: 83f22e07 added UploadCapabilityRouteMatcher to the exemption list on the same grounds, so the current list is the anchor and the TO appends to it.
MUTATION_NAME='the CSRF double-submit check removed (every request exempted)'
RECORD_ROW='§8 browser state (CSRF) — no §9 row names it'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/SecurityConfig.kt'
FROM='csrf.ignoringRequestMatchers(ApiKeyCredentialMatcher(), PromotionRouteMatcher(), UploadCapabilityRouteMatcher())'
TO='csrf.ignoringRequestMatchers(ApiKeyCredentialMatcher(), PromotionRouteMatcher(), UploadCapabilityRouteMatcher(), org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE)'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.AuthHttpBoundaryTest'
EXPECT_TEST='a cookie-authenticated state change with no csrf token is 403 reason=missing'
