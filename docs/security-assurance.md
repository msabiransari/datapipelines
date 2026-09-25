# Security assurance — the OWASP ASVS 5.0.0 Level 2 index

**Status:** an evidence index, not a compliance claim. Created by #217 slice A (2026-09-24).
**Target:** [OWASP ASVS 5.0.0](https://github.com/OWASP/ASVS/tree/v5.0.0_release) (released 2025-05-30), Level 2 — the owner's decision P3 in the [security-assurance record](superpowers/specs/2026-09-24-security-assurance-design.md) §13. Every section ID below was read from the `v5.0.0_release` tag, not recalled, and none is from the moving development branch.
**Authority:** status lives in GitHub ([#217](https://github.com/msabiransari/datapipelines/issues/217) and the issues linked below). This page records where the evidence is, and does not track the work.
**Distribution:** contributor material; not packaged into the product jar (the docs include list in `modules/web/build.gradle.kts` leaves it out on purpose). Its status column changes with every assurance slice, and the product docs describe the running version.

## How to read it

One row per ASVS 5.0.0 **section**, meaning a family of requirements (`V8.2` is the whole of "General Authorization Design"). A section's individual requirements carry L1/L2/L3 levels in the standard. This index answers, per family and for this product, one question: *what automated guard or manual procedure stands behind the Level 1 and Level 2 requirements here?*

| Status | Meaning |
|---|---|
| **covered** | Named automated guards exercise the family's L1/L2 requirements as this product applies them. A future slice may still deepen them. |
| **partial** | Guards exist for part of the family; the rest is named in the row and is outstanding. |
| **manual** | Needs a human assessment that no test can replace (the P3 rows below). Outstanding until one is recorded. |
| **outstanding** | Nothing stands behind it yet. Tracked in the row's issue, or in #217 when none is named. |
| **N/A** | Does not apply to this product, for the technical reason given. An outstanding control is never N/A. |

"Record" is the section of the [security-assurance record](superpowers/specs/2026-09-24-security-assurance-design.md) that owns the family's future work. The mutation numbers refer to [`scripts/security-mutations/`](../scripts/security-mutations/run.sh) (DEVELOPMENT.md §10.2). A guard is listed only if it exists in the tree today.

## The manual assessments (P3)

The boundaries with this product's real blast radius (record §8, ordered by B7) are decisions and configurations more than code paths. They need a named human assessment, and each is **outstanding** until one is recorded here, with its date and reviewer:

| # | Boundary | Why no test settles it | ASVS families it bears on | Status |
|---|---|---|---|---|
| M1 | **SQL containment by database privileges.** User-authored SQL runs against customer datasources as the configured user. | The product executes the SQL it is given, by design; the boundary is the privileges of the account a datasource is registered with, which the product does not control. | V1.2, V8.2, V13.2 | manual — outstanding |
| M2 | **In-process engines** (H2 staging, DuckDB lake, the JSONata/Freemarker evaluators). | Their privilege model is the engine's configuration and the JVM's, not a route; the transform record's breach suite is the precedent to extend. | V1.2, V5.3, V15.2 | manual — outstanding |
| M3 | **Datasource egress to private address ranges.** | Customer databases are often private, so a public-address-only rule would break the product (record §8). The policy is a deployment decision per instance. | V12.3, V13.2 | manual — outstanding |
| M4 | **File roots** (lake and file datasources). | Traversal, canonicalisation and symlink handling at pool build; the open follow-ups are in [#204](https://github.com/msabiransari/datapipelines/issues/204). | V5.1, V5.3 | manual — outstanding |

## The index

| Section | Family | Status | Guard or evidence | Record |
|---|---|---|---|---|
| V1.1 | Encoding and sanitization architecture | outstanding | Not assessed. | §8 |
| V1.2 | Injection prevention | manual | M1, M2. Metadata SQL is parameterised (JDBC `NamedParameterJdbcTemplate` throughout); user-authored SQL is the intended capability. | §8 |
| V1.3 | Sanitization | outstanding | Not assessed. The CSP work in [#195](https://github.com/msabiransari/datapipelines/issues/195) and [#197](https://github.com/msabiransari/datapipelines/issues/197) bears on it. | §8 |
| V1.4 | Memory, string and unmanaged code | N/A | JVM only; no native code of the product's own. | — |
| V1.5 | Safe deserialization | outstanding | Not assessed. | §8 |
| V2.1 | Validation and business-logic documentation | partial | The pipeline contract and error catalogs are documented and drift-tested (`AuthErrorSpecDriftTest`, `ApiErrorCatalogSpecDriftTest`). Business-logic limits are not assessed. | §8 |
| V2.2 | Input validation | outstanding | Not assessed as a family. | §8 |
| V2.3 | Business logic security | outstanding | Not assessed. | §7.4 |
| V2.4 | Anti-automation | covered | `LoginRateLimitFilterTest` (per-client login damper), `EndpointKeyBudgetFilterTest` (per-key serve budget), the §12 per-user limiter (`RateLimitFilter`). | §8 |
| V3.1 | Web frontend security documentation | partial | auth.md §8.1 (headers), §8.3 and §8.6 (public contract). | §8 |
| V3.2 | Unintended content interpretation | outstanding | `SecurityHeadersTest` pins `nosniff`; the CSP gaps are [#195](https://github.com/msabiransari/datapipelines/issues/195) and [#197](https://github.com/msabiransari/datapipelines/issues/197). | §8 |
| V3.3 | Cookie setup | covered | `AuthHttpBoundaryTest` (session and CSRF cookie attributes, SameSite, lifetime); `PublicContractE2eTest` (no public route sets a session cookie). | §8 |
| V3.4 | Browser security mechanism headers | partial | `SecurityHeadersTest`; outstanding: [#195](https://github.com/msabiransari/datapipelines/issues/195), [#197](https://github.com/msabiransari/datapipelines/issues/197). HSTS is the edge's (deployment.md). | §8 |
| V3.5 | Browser origin separation | covered | The CSRF double-submit (`AuthHttpBoundaryTest`, mutation 07), `StaticJsCsrfAuditTest`, one configured CORS origin (auth.md §8.2). | §8 |
| V3.6 | External resource integrity | manual | Third-party assets are vendored under `/vendor/**`; no CDN at runtime. No test asserts the absence of external script origins. | §8 |
| V3.7 | Other browser security considerations | outstanding | Not assessed. | §8 |
| V4.1 | Generic web service security | partial | `PublicContractE2eTest` (anonymous answers, no stack traces), the unified error envelope (`AuthErrorWriterTest`). | §4, §8 |
| V4.2 | HTTP message structure validation | outstanding | Not assessed (request smuggling and header limits sit at Tomcat and the edge). | §8 |
| V4.3 | GraphQL | N/A | No GraphQL endpoint. `EntryInventoryE2eTest` would fail on a new handler mapping. | — |
| V4.4 | WebSocket | N/A | No WebSocket endpoint. Tomcat's `WsFilter` is registered and inventoried (auth.md §8.6); an endpoint would be a new entry family. | §4 |
| V5.1 | File handling documentation | manual | M4. | §8 |
| V5.2 | File upload and content | outstanding | Not assessed (the product accepts no file uploads through the UI today — to be confirmed by the assessment). | §8 |
| V5.3 | File storage | manual | M2, M4. | §8 |
| V5.4 | File download | outstanding | Not assessed (result exports). | §8 |
| V6.1 | Authentication documentation | covered | auth.md §3–§6, §5A. | §8 |
| V6.2 | Password security | partial | `LocalPasswordServiceTest`, `LocalAuthServiceTest` (policy, lockout — auth.md §5A.3, §5A.5). Not assessed: the L2 password requirements as a whole. | §8 |
| V6.3 | General authentication security | partial | `LoginRateLimitFilterTest`, `AuthHttpBoundaryTest`, `NonHumanRowRefusalTest` (no service identity signs in). Multi-factor authentication is not offered for local accounts — an owner decision. | §8 |
| V6.4 | Authentication factor lifecycle and recovery | partial | `SuperAdminRecoveryE2eTest`, `WorkspaceInvitationLocalE2eTest`. Reset and invitation replay are not assessed (record §8). | §7.5, §8 |
| V6.5 | General multi-factor authentication | outstanding | Local accounts are single-factor; OIDC delegates factors to the identity provider (V6.8). | §8 |
| V6.6 | Out-of-band authentication | N/A | None offered. | — |
| V6.7 | Cryptographic authentication mechanism | N/A | None offered (no client certificates, no WebAuthn). | — |
| V6.8 | Authentication with an identity provider | partial | `OidcLoginIntegrationTest`, `PkceAuthorizationRequestTest`, `UserServiceIdentityLinkingTest`. | §8 |
| V7.1 | Session management documentation | covered | auth.md §6. | §8 |
| V7.2 | Fundamental session management security | covered | `JwtServiceTest`, `JwtFilterLivenessTest` (liveness re-checked per request through the 60 s cache). | §7.5 |
| V7.3 | Session timeout | partial | The 8 h JWT lifetime (auth.md §6.4); an idle timeout is not assessed. | §7.5 |
| V7.4 | Session termination | partial | `AuditLogoutHandlerTest`, `PrincipalLivenessTest`, `McpKeyRoleFreshnessTest` (a change takes effect within the 60 s auth cache TTL, P2). Open SSE streams are not cut on revocation yet: [#230](https://github.com/msabiransari/datapipelines/issues/230) (P4). | §7.5 |
| V7.5 | Defenses against session abuse | covered | The CSRF double-submit (`AuthHttpBoundaryTest`, mutation 07), `CookieAuthorizationRequestRepositoryTest`. | §8 |
| V7.6 | Federated re-authentication | outstanding | Not assessed. | §8 |
| V8.1 | Authorization documentation | covered | auth.md §7.6 (the permission catalog) and §8.6 (entries, public contract); `ScopeMatrixSpecDriftTest`, `MatrixRowReachabilityTest`, `EntryInventoryE2eTest`. | §5, §4 |
| V8.2 | General authorization design | covered | Default-deny at the interceptor (`ScopeInterceptorTest`, `RequiredScopeCoverageTest`, `ReadFloorTest`, `MutatingHandlerScopeFloorTest`); every route and tool against the catalog (`RoleWalkE2eTest`); one permission at a time (`PermissionSeamE2eTest`); key confinement (`EndpointKeyConfinementTest`, `ServerKeyConfinementTest`); the seam packaged once (`PackagedResolverTest`); mutations 01–05, 09, 10. | §6, §7.1, §7.2 |
| V8.3 | Operation level authorization | partial | `WorkspaceIsolationSweepTest` (mutation 08), `PromoterLensSweepTest`, `ExecutionsVisibilityTest`, `DeactivationSweepTest`. Outstanding: effect witnesses for denied writes (record §7.4, slice B). | §7.3, §7.4 |
| V8.4 | Other authorization considerations | partial | `RevocationTtlTest`, `McpKeyRoleFreshnessTest`; the public contract (`PublicContractE2eTest`, mutation 06). Outstanding: [#230](https://github.com/msabiransari/datapipelines/issues/230). | §7.5 |
| V9.1 | Token source and integrity | covered | `JwtServiceTest` (HS256 signature and algorithm pinned, auth.md §6.3). | §8 |
| V9.2 | Token content | covered | `JwtServiceTest` (issuer, expiry, claims). | §8 |
| V10.1 | Generic OAuth and OIDC security | partial | `PkceAuthorizationRequestTest`, `CookieAuthorizationRequestRepositoryTest`. | §8 |
| V10.2 | OAuth client | partial | As V10.1, and `OidcLoginIntegrationTest`. | §8 |
| V10.3 | OAuth resource server | N/A | The product accepts no OAuth access tokens: its credentials are its own session JWT and API keys. | — |
| V10.4 | OAuth authorization server | N/A | Not an authorization server. | — |
| V10.5 | OIDC client | partial | `OidcLoginIntegrationTest`, `UserServiceIdentityLinkingTest`. | §8 |
| V10.6 | OpenID provider | N/A | Not an OpenID provider. | — |
| V10.7 | Consent management | N/A | No third-party consent flow of its own. | — |
| V11.1 | Cryptographic inventory and documentation | outstanding | Not assessed. | §8 |
| V11.2 | Secure cryptography implementation | outstanding | Not assessed. | §8 |
| V11.3 | Encryption algorithms | outstanding | Datasource credentials are sealed at rest (`SecretSealer`); no test exercises it directly. | §8 |
| V11.4 | Hashing and hash-based functions | covered | `BoundedSecretHasherTest` (Argon2id for keys and passwords, bounded). | §8 |
| V11.5 | Random values | outstanding | Not assessed. | §8 |
| V11.6 | Public key cryptography | N/A | None of the product's own (TLS is the edge's). | — |
| V11.7 | In-use data cryptography | N/A | Not in scope for this product. | — |
| V12.1 | General TLS security guidance | manual | TLS terminates at the edge (deployment.md); the app does not configure it. | §8 |
| V12.2 | HTTPS with external-facing services | manual | As V12.1. | §8 |
| V12.3 | Service-to-service communication security | manual | M3; datasource connection TLS is per datasource. | §8 |
| V13.1 | Configuration documentation | covered | configuration.md, drift-tested by the config-key spec-drift tests; `ConfigValidatorTest`. | §8 |
| V13.2 | Backend communication configuration | partial | The management port binds loopback and its one endpoint refuses anonymous callers (`EntryInventoryE2eTest`, auth.md §8.6); M1, M3. | §4, §8 |
| V13.3 | Secret management | partial | `scripts/secret-scan.sh` (staged-change hook); "no credential through an agent" (auth.md, changelog v2.13). Not assessed as a whole. | §8 |
| V13.4 | Unintended information leakage | partial | No stack trace on any public route (`PublicContractE2eTest`); the error envelope (`AuthErrorWriterTest`); nothing under `/actuator` on the application port (`EntryInventoryE2eTest`). | §8 |
| V14.1 | Data protection documentation | outstanding | Not assessed. | §8 |
| V14.2 | General data protection | outstanding | Not assessed. | §8 |
| V14.3 | Client-side data protection | partial | Authenticated responses are `private` by default (`SecurityHeadersTest`). | §8 |
| V15.1 | Secure coding and architecture documentation | covered | module-structure.md; the security-assurance record. | §6 |
| V15.2 | Security architecture and dependencies | partial | `verifyModuleDependencies`, `ArchitectureGuardTest` (layering; transports and jobs reach approved entry points), `scripts/vuln-scan.sh` (OSV), `scripts/container-scan.sh`; M2. | §6, §8 |
| V15.3 | Defensive coding | outstanding | Not assessed. | §8 |
| V15.4 | Safe concurrency | outstanding | Not assessed. | §8 |
| V16.1 | Security logging documentation | covered | auth.md §10 (audit events and shape). | §8 |
| V16.2 | General logging | partial | Structured logs with correlation ids (observability.md §3.3). | §8 |
| V16.3 | Security events | partial | The audit events of auth.md §10.1 (`ApiKeyRejectionAuditTest` among others). Not assessed as a family. | §8 |
| V16.4 | Log protection | outstanding | Not assessed. | §8 |
| V16.5 | Error handling | covered | The unified error envelope (`AuthErrorWriterTest`, `AuthErrorSpecDriftTest`, `ApiErrorCatalogSpecDriftTest`); no stack trace on public routes (`PublicContractE2eTest`). | §8 |
| V17.1–V17.3 | WebRTC | N/A | No WebRTC. | — |

## What changes this page

A slice that adds a guard adds its name to the row it serves, in the same commit. A status moves to **covered** only when the guard exists in the tree and has been shown able to fail (DEVELOPMENT.md §10.2). A manual assessment row names its date and reviewer when it is done. Nothing here counts as an ASVS compliance claim: the standard is the target (P3), and this page records how far the evidence reaches.
