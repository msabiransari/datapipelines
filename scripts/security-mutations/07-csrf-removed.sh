# shellcheck shell=bash disable=SC2034
# Security mutation 07 — the CSRF double-submit check removed (every request exempted).
# Record §9 §8 browser state (CSRF) — no §9 row names it. Sourced by run.sh: the literal sed pair (FROM → TO, reverted TO → FROM) and the ONE test that must go red.
# Re-anchored by #470: 83f22e07 added UploadCapabilityRouteMatcher to the exemption list on the same grounds, so the current list is the anchor.
# Re-anchored by #782: the exemption call is multi-line now (LogoutSameOriginMatcher joined the list; a one-line call with four matchers breaches the 140-char detekt cap), so the anchor is the list's last element — and the TO REPLACES it with the any-request matcher rather than appending (swap() refuses a TO that contains FROM), which keeps the mutation "every request exempted".
MUTATION_NAME='the CSRF double-submit check removed (every request exempted)'
RECORD_ROW='§8 browser state (CSRF) — no §9 row names it'
TARGET='modules/auth/src/main/kotlin/co/datapipelines/auth/SecurityConfig.kt'
FROM='LogoutSameOriginMatcher(),'
TO='org.springframework.security.web.util.matcher.AnyRequestMatcher.INSTANCE,'
EXPECT_TASK=':modules:auth:test'
EXPECT_CLASS='co.datapipelines.auth.AuthHttpBoundaryTest'
EXPECT_TEST='a cookie-authenticated state change with no csrf token is 403 reason=missing'
