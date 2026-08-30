# Auth & Security Specification

**Status:** v2.6 (revised — see Change Log)
**Owner:** datapipelines.co core
**Depends on:** [Type System](type-system.md)
**Last updated:** 2026-08-14

---

## 1. Purpose

This spec defines **how users authenticate** (OIDC via any provider — Google, Microsoft, Okta, Auth0, Keycloak, etc.), **how sessions work** (internal JWT after OIDC login), **how agents authenticate** (API keys), **what authenticated principals can do** (scopes), and the **Spring Security wiring** that ties it all together.

The product is self-hosted, internal-users-only. Identity is delegated to **any OIDC-compliant provider** via OpenID Connect **by default**; deployments without an IdP may additionally enable optional local password accounts (§5A), stored as Argon2id hashes. After any login, the server issues its own JWT for stateless session management.

---

## 2. Design Principles

1. **Identity is delegated by default; local accounts are the narrow exception.** The server never sees or stores OIDC user passwords — identity belongs to the external provider. The one exception (§5A): a deployment with no IdP at all would otherwise be unusable — evaluating the product required registering an OAuth client before seeing a single screen, the biggest adoption barrier the product had. For that deployment shape, optional local accounts exist: admin-created only (no self-registration), stored as Argon2id hashes (never plaintext, never in config beyond the one-time first-admin seed), forced to rotate seeded/reset credentials at first login, and off unless explicitly enabled. An OIDC-only deployment behaves exactly as before.
2. **Generic OIDC, not provider-specific.** Any OIDC-compliant provider works — Google, Microsoft, Okta, Auth0, Keycloak, Ping, AWS Cognito, etc. The deployment configures which provider(s) to enable; the code is provider-agnostic.
3. **OIDC for humans, API keys for agents.** Users log in via browser (OIDC redirect flow). Agents (Claude, GLM, Copilot) authenticate via API keys issued from the UI.
4. **Internal JWT after OIDC.** Once OIDC validates the user, the server issues its own JWT (8h TTL). The JWT is the session — any instance can validate it statelessly. OIDC tokens are NOT used for ongoing session management.
5. **API keys per agent, not global.** A user generates one key per agent. Compromised keys are individually revocable. Per-agent usage visible in audit logs.
6. **Keys hashed at rest.** Same protection as passwords would be. A database leak does not expose working credentials.
7. **Scopes are explicit and hierarchical.** Every key and session has a scope set. Default: `read`. Higher scopes require deliberate choice.
8. **Stateless server, near-live revocation.** JWT + API key validation are stateless across instances; both paths re-check the principal's liveness (user `is_active`, key revocation) through a short-TTL cache backed by Postgres (§6.3, §7.3). Deactivating a user or revoking a key takes effect within ~1 minute — never the full JWT lifetime.

---

## 3. Authentication Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      Browser (Human User)                      │
│                                                                │
│  1. User visits /login                                         │
│  2. Sees buttons for each configured OIDC provider             │
│  3. Clicks one (e.g., "Sign in with Google", "Sign in with     │
│     Okta", "Sign in with Company SSO")                         │
│  4. Redirected to OIDC provider                                │
│  5. Authenticates at provider                                  │
│  6. Provider redirects back with authorization code           │
│  7. Server exchanges code for OIDC tokens                     │
│  8. Server validates ID token, extracts user identity         │
│  9. Server creates/updates user record in Postgres            │
│ 10. Server issues internal JWT (8h TTL)                       │
│ 11. Server sets HttpOnly cookie: dp_session=<JWT>             │
│ 12. Redirect to /                                               │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                    Agent (Claude / GLM / Copilot)              │
│                                                                │
│ 1. User has previously logged in and generated an API key     │
│ 2. Agent sends: DP-API-Key: dpk_<id>.<secret>                 │
│    (or, on /mcp: Authorization: Bearer dpk_<id>.<secret>)     │
│ 3. Server validates key hash (Argon2id) against Postgres      │
│ 4. Server resolves user + scopes from key record              │
│ 5. Request authenticated                                       │
└──────────────────────────────────────────────────────────────┘
```

Both paths resolve to the same internal principal:
```kotlin
data class AuthenticatedPrincipal(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val scopes: Set<Scope>,
    val authMethod: AuthMethod,       // OIDC or API_KEY
    val keyId: String?                // present when authMethod = API_KEY
)
```

---

## 4. User Identity

### 4.1 User entity

```json
{
  "id": "uuid",
  "email": "alice@company.com",
  "display_name": "Alice Wang",
  "profile_picture_url": "https://lh3.googleusercontent.com/...",
  "provider": "google",
  "provider_subject": "108214793245678901234",
  "is_active": true,
  "is_admin": false,
  "created_at": "2026-08-01T10:00:00Z",
  "last_login_at": "2026-08-05T14:30:00Z"
}
```

The `provider` field stores the **OIDC registration name** as configured by the deployment (e.g., `google`, `microsoft`, `okta`, `company-sso`). It's free text — any provider name the deployment configures. Not constrained to a fixed enum.

### 4.2 User provisioning

**First login:** When a user logs in via OIDC for the first time:
1. Server extracts `email`, `name`, `sub`, `picture` from the OIDC ID token. The email is **normalized to lowercase** before every lookup and store — provider case differences must not fork one human into two rows (or mint a second bootstrap admin, §4.4). If the ID token carries `email_verified: false`, the login is **rejected** (`auth.login.oidc_error`, audited) — an unverified self-registered account at a provider must never reach the email-keyed linking step below, or it takes over the existing account with that email. A provider omitting the claim is treated as vouching for the address.
2. Checks if a user with that `email` already exists.
   - **Yes:** Updates `provider`, `provider_subject`, `last_login_at`, `profile_picture_url` — and `display_name`, which refreshes from the ID token's `name` claim on **every** login (owner-ratified 2026-08-28: there is no profile-edit feature, so a stored name has no user-chosen referent to protect, and freezing it would leave an IdP rename unrepresentable; revisit if a profile surface ever ships). The §4.4 bootstrap-completion case needs no special handling — the `provider = 'bootstrap'` placeholder name is replaced by this same refresh at the first real sign-in.
   Grant-wise this step changes nothing: `is_admin` is decided at row creation and nowhere else (§4.4).
   - **No:** Creates a new user record. Default `is_active: true`, `is_admin: false`.
3. Checks the email domain allowlist (if configured — see §4.3).
   - Domain not allowlisted: reject login with `auth.login.domain_not_allowed`.
4. **Workspace resolution (design §5.1/§7):** determines the active workspace the JWT stamps
   — the user's **last-used** workspace when it is still a live membership, else their
   **first membership**, else — under `auto-per-user` provisioning only — the **freshly
   provisioned personal workspace** created at this step (`is_personal = TRUE`, name from the
   lowercased email local-part sanitized to `[a-z0-9_-]+`, collision-suffixed, creator enters
   as `owner`). A user with zero memberships (possible under `self-serve` before first
   creation, and under `closed`) stamps nothing: they authenticate fine and every
   workspace-scoped operation answers `403 workspace.membership_required` (§5.6).
5. Issues internal JWT (stamping `active_workspace` when step 4 resolved one), sets cookie, redirects to `/`.

**Subsequent logins:** Same flow — user record updated, JWT reissued.

**Account deactivation:** Admin marks user `is_active: false` via UI. Subsequent OIDC logins are rejected with `auth.login.user_inactive`. Existing sessions and API keys stop working within the liveness-cache TTL (~60s): every authenticated request re-checks `is_active` through the cache (§6.3, §7.3), so a deactivated user's JWT and keys are dead within a minute, not at the 8h JWT expiry. API keys additionally remain individually revocable.

### 4.3 Email domain allowlist

Configurable per deployment:
- `DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS=company.com,subsidiary.com`
- If set, only users with emails from these domains can log in.
- If empty (default), any Google/Microsoft user can log in (open provisioning).

For internal-only deployments, **always set the allowlist** to prevent random Google accounts from accessing the instance.

### 4.4 Bootstrap admin

A fresh deployment has zero admins (`users.is_admin DEFAULT FALSE`, no seed rows) — so without a bootstrap path, nobody can ever grant `admin`. The mechanism (added 2026-08-07, security review LOW-11; extended 2026-08-29 to seed the first admin's local credential, §5A.2):

- The operator sets `datapipelines.auth.bootstrap-admin-email` ([Configuration §3.4](configuration.md#34-auth)) before first start.
- When a user row is **created** (first provisioning, §4.2) and its lowercase-normalized email matches the configured value (compared case-insensitively), `is_admin` is set true. The grant fires **only at row creation**: a later login changes nothing, the flag is never *revoked* by this path, and after an admin deliberately revokes admin (`auth.user.admin_revoked`, §10.1) this path never re-grants it — re-instating admin is an explicit §16.3 operation.
- Audit-logged as `auth.user.admin_granted` with actor `bootstrap` (§10.1).
- If the key is unset, no bootstrap occurs — the deployment simply has no admin until the key is set and that user logs in.

**Pre-provisioning (design 2026-08-16-sample-data §6.1).** When [`datapipelines.bootstrap.datasources-file`](configuration.md#318-bootstrap) is set, startup needs this user to exist *before* anyone has logged in: `datasources.created_by` is `NOT NULL REFERENCES users(id)`, and bootstrap datasource registration runs before the server accepts traffic. So, when no row exists for the configured address (lowercase-normalized, §4.2), one is created with placeholder identity fields — `provider = 'bootstrap'`, `provider_subject` = the email (together satisfying the NOT NULL columns and the `(provider, provider_subject)` uniqueness), `display_name` from the email local-part, `is_active = TRUE`.

This is **not a second grant path.** It is the rule above firing earlier in time: the row is created, so the grant fires, once, with the `auth.user.admin_granted` / actor `bootstrap` audit event. Everything the rule already promised still holds, and each clause is load-bearing here:

- A **restart** finds the row and touches nothing on it — no re-grant, no identity rewrite, not even `updated_at`. An admin who deliberately revoked admin stays revoked.
- The admin's **first real OIDC login** completes the placeholder through §4.2's linking step: `provider`, `provider_subject`, `profile_picture_url` and — this case only — `display_name` from the `name` claim. It grants nothing.
- If that admin logs in **before** registration ever runs, §4.2 creates the row and grants there instead; the later pre-provisioning finds it and adds nothing. Either order, exactly one grant and exactly one audit event.

Startup refuses when `datasources-file` is set and this key is not ([Configuration §3.18](configuration.md#318-bootstrap)) — a missing actor would otherwise surface as a foreign-key error mid-startup naming neither key.

**Explicitly rejected: "first user to log in becomes admin."** Combined with the open-provisioning default (§4.3), that rule is a land-grab race on any reachable instance — whoever hits `/login` first owns every datasource credential. Do not reintroduce it as a convenience.

---

## 5. OIDC Login Flow

### 5.1 Provider configuration (generic)

OIDC providers are configured as a **list** in the deployment config. Each provider requires only three values — `client-id`, `client-secret`, and `issuer-uri`. The issuer URI triggers OIDC discovery (`/.well-known/openid-configuration`), which auto-configures all authorization, token, userinfo, and JWKS endpoints.

```yaml
datapipelines:
  auth:
    oidc:
      providers:
        - name: google               # registration ID (used in URLs, stored in users.provider)
          client-id: ${GOOGLE_CLIENT_ID}
          client-secret: ${GOOGLE_CLIENT_SECRET}
          issuer-uri: https://accounts.google.com
          display-name: "Sign in with Google"   # shown on login button

        - name: microsoft
          client-id: ${MS_CLIENT_ID}
          client-secret: ${MS_CLIENT_SECRET}
          issuer-uri: https://login.microsoftonline.com/common/v2.0
          display-name: "Sign in with Microsoft"

        # Any other OIDC provider:
        # - name: okta
        #   client-id: ${OKTA_CLIENT_ID}
        #   client-secret: ${OKTA_CLIENT_SECRET}
        #   issuer-uri: https://company.okta.com
        #   display-name: "Sign in with Okta"
        #
        # - name: keycloak
        #   client-id: ${KEYCLOAK_CLIENT_ID}
        #   client-secret: ${KEYCLOAK_CLIENT_SECRET}
        #   issuer-uri: https://sso.company.com/realms/main
        #   display-name: "Company SSO"
```

Scopes requested for every provider: `openid`, `profile`, `email`. These are the standard OIDC scopes that give us the user's identity.

### 5.2 ClientRegistration bean (built at startup)

The provider list is converted into Spring Security `ClientRegistration` objects at startup. OIDC discovery fetches each provider's `.well-known/openid-configuration` to auto-detect all endpoints.

```kotlin
@Configuration
class OidcConfig {

    @Bean
    fun clientRegistrationRepository(
        authConfig: AuthConfig
    ): ClientRegistrationRepository {
        val registrations = authConfig.oidc.providers.map { p ->
            ClientRegistration.withRegistrationId(p.name)
                .clientId(p.clientId)
                .clientSecret(p.clientSecret)
                .scope("openid", "profile", "email")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                // ABSOLUTE, from datapipelines.auth.base-url — NEVER the request-derived
                // {baseUrl} template: an attacker-controlled Host / X-Forwarded-Host would
                // otherwise choose the redirect_uri sent to the IdP, and the only backstop
                // is the IdP's own allowlist, a control this deployment does not own.
                .redirectUri("${authConfig.baseUrl}/login/oauth2/code/${p.name}")
                .issuerUri(p.issuerUri)        // triggers OIDC discovery
                .clientName(p.displayName)
                .build()
        }

        if (registrations.isEmpty()) {
            error("No OIDC providers configured. Set datapipelines.auth.oidc.providers in config.")
        }

        return InMemoryClientRegistrationRepository(registrations)
    }
}
```

The redirect URI is built from `datapipelines.auth.base-url` ([Configuration §3.4](configuration.md#34-auth)) — the deployment's exact external origin (e.g. `https://dp.example.com`, no trailing slash). Startup fails when it is unset while any OIDC provider is configured. This is what §13's "OIDC redirect URI locked to the deployment's exact domain" means mechanically; the earlier `{baseUrl}` placeholder resolved from the incoming request and let a hostile `Host`/`X-Forwarded-Host` header pick the redirect target (v2.4).

### 5.3 Login page (dynamic — renders buttons for each configured provider)

```kotlin
@Controller
class LoginController(
    private val clientRegistrationRepository: ClientRegistrationRepository
) {
    @GetMapping("/login")
    fun login(model: Model): String {
        val providers = clientRegistrationRepository.toList().map { reg ->
            mapOf(
                "name" to reg.registrationId,
                "displayName" to reg.clientName
            )
        }
        model.addAttribute("providers", providers)
        return "login"
    }

    private fun ClientRegistrationRepository.toList(): List<ClientRegistration> {
        // Spring's Iterable → Kotlin List
        return this.asIterable().toList()
    }
}
```

```html
<!-- login.html (Thymeleaf) — renders one button per configured provider -->
<div class="ds-card login-card">
    <h1 class="ds-h2">Sign in to datapipelines.co</h1>
    <div th:each="p : ${providers}" class="login-buttons">
        <a th:href="@{'/oauth2/authorization/' + ${p.name}}"
           class="ds-button ds-button--secondary"
           th:text="${p.displayName}">
            Sign in
        </a>
    </div>
</div>
```

The login page shows **only the providers the deployment configured** — one button, two buttons, or five buttons. No hardcoded provider names.

When local accounts are enabled (§5A), the page gains a username/password form **before** the provider buttons, separated by a simple divider — one form, then the divider, then the buttons, never tabs (tabs hide half the methods behind a click and add state to a page that should have none). Only the methods actually enabled render: an OIDC-only deployment shows exactly the page above (no form, no divider), a local-only deployment shows the form with no divider and no buttons. Failure states arrive as `?error=` banners (`credentials`, `locked`, `inactive` — same idiom as the OIDC banners).

### 5.4 OIDC redirect flow (Spring Security handles automatically)

```
1. User clicks a provider button (e.g., "Sign in with Okta")
   → GET /oauth2/authorization/okta
   → Spring Security redirects to Okta's authorization endpoint

2. User authenticates at Okta
   → Okta redirects to: GET /login/oauth2/code/okta?code={auth_code}&state={state}

3. Spring Security exchanges code for OIDC tokens (server-side)
   → Calls Okta's token endpoint with client_id + client_secret + code
   → Receives: id_token, access_token, refresh_token

4. Spring Security validates ID token (signature, audience, issuer, expiry)
   → Extracts claims: sub, email, name, picture

5. OidcSuccessHandler (our custom code):
   → user = userService.findOrCreateByEmail(claims, registrationId)
   → jwt = jwtService.issue(user)
   → response.setCookie("dp_session", jwt, httpOnly=true, secure=base-url-scheme, sameSite="Lax")
     (T33: `secure` is keyed off datapipelines.auth.base-url's scheme — https (or unset) keeps
     the flag; an explicit http:// base-url drops it so local login works. Production MUST run
     https — see §8.4. The same rule applies to the dp_csrf and dp_oauth2_authz cookies.)
   → response.sendRedirect("/")
```

### 5.5 OIDC success handler

```kotlin
@Component
class OidcSuccessHandler(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val auditLogger: AuditLogger,
    private val authConfig: AuthConfig
) : SimpleUrlAuthenticationSuccessHandler() {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val oidcUser = authentication.principal as OidcUser
        val claims = oidcUser.idToken.claims
        val clientRegistration = (authentication as OAuth2AuthenticationToken)
            .authorizedClientRegistrationId     // "google", "okta", "company-sso", etc.

        val email = claims["email"] as String
        val displayName = claims["name"] as String? ?: email
        val pictureUrl = claims["picture"] as String?
        val providerSubject = claims["sub"] as String

        // Check allowlist
        if (!authConfig.isDomainAllowed(email)) {
            auditLogger.log("auth.login.domain_not_allowed", email = email)
            redirectStrategy.sendRedirect(request, response, "/login?error=domain_not_allowed")
            return
        }

        // Find or create user
        val user = userService.findOrCreateByEmail(
            email = email,
            displayName = displayName,
            pictureUrl = pictureUrl,
            provider = clientRegistration,        // whatever registration name was configured
            providerSubject = providerSubject
        )

        if (!user.isActive) {
            auditLogger.log("auth.login.user_inactive", userId = user.id)
            redirectStrategy.sendRedirect(request, response, "/login?error=inactive")
            return
        }

        // Issue internal JWT
        val jwt = jwtService.issue(user)
        val cookie = Cookie("dp_session", jwt).apply {
            isHttpOnly = true
            secure = true
            // Lax, not Strict: Strict withholds the cookie on the cross-site redirect
            // chain back from the IdP (and on reloads of that landing), so every login
            // would end on a 401. CSRF protection covers state-changing requests.
            setAttribute("SameSite", "Lax")
            maxAge = authConfig.jwt.ttlHours * 3600
            path = "/"
        }
        response.addCookie(cookie)

        userService.updateLastLogin(user.id)
        auditLogger.log("auth.login.success", userId = user.id,
            details = mapOf("provider" to clientRegistration))

        redirectStrategy.sendRedirect(request, response, "/")
    }
}
```

The success handler is **fully provider-agnostic.** It reads `authorizedClientRegistrationId` from the authentication token — that's whatever provider name the deployment configured. It doesn't know or care whether it's Google, Okta, or Keycloak.

---

### 5.6 Workspace resolution & the `DP-Workspace` header

Every authenticated request resolves exactly **one** active workspace (design §5); all
authored-content operations (pipelines, templates, executions) are scoped to it.

- **Session (JWT) principals:** the active workspace comes from the JWT's
  `active_workspace` claim (stamped at login, §4.2 step 4), membership re-checked per
  request through the same 60s liveness cache as `users.is_active` (§6.3) — workspace
  revocation takes effect within the identical ~1-minute window. A claim whose membership
  is gone falls back to the first live membership.
- **`DP-Workspace: <workspace name>`** switches the active workspace for that request.
  A name the principal is not a member of is refused `403 workspace.membership_required` —
  indistinguishable from an unknown name, so the header cannot probe workspace existence.
  A successful switch is remembered as the user's last-used workspace (drives the next
  login's stamp). Global `is_admin` bypasses the membership check (design D4).
- **API-key / MCP principals:** the key's **pinned** `workspace_id` (chosen at issuance,
  §7.4) IS the context — no override exists. `DP-Workspace` on an API-key request is
  **rejected** (`400 workspace.header_forbidden`), never silently ignored: a header-
  switchable agent key would make every leaked key a skeleton key across the user's
  workspaces (design D3), and a quietly dropped header would train agents on a lie.
- **The pin also governs workspace management.** The workspace-management endpoints
  (§7.6 "Update a workspace / manage its members") address their target by path NAME, so
  for a key principal the service refuses any target other than the key's pinned
  workspace — the same no-oracle `403 workspace.membership_required` a non-member gets,
  so "pinned elsewhere" and "not a member" stay indistinguishable. Without this, a key
  pinned to workspace A could rename, delete, or edit the membership of workspace B
  whenever its owner belongs to both — the D3 skeleton key by another door. Sessions are
  untouched (their active workspace is switchable by design). Two exemptions, both
  because no EXISTING workspace is overreached: **create** (there is no target workspace
  yet, creation grants the caller ownership of a NEW workspace only, and the `author`
  floor plus the per-mode provisioning refusal are its gates) and the **`open-join`
  self-join** (adding the caller's OWN email to a workspace the deployment declared open
  touches only the caller's own membership — the joiner enters as `member`, and the
  key's active workspace stays pinned).
- **Zero memberships** (possible under `closed` provisioning): the request proceeds with
  no workspace; every workspace-scoped operation then answers
  `403 workspace.membership_required`.

## 5A. Local password accounts (optional)

Local username/password authentication is the **optional second sign-in method** for deployments without an IdP (§2 Principle 1). It is disabled by default (`datapipelines.auth.local.enabled`, [Configuration §3.4](configuration.md#34-auth)); a deployment with only OIDC configured behaves exactly as before this section existed. With no OIDC providers and local accounts enabled, the deployment starts and serves the whole product with zero external setup — the zero-setup path.

### 5A.1 Accounts

A local account is an ordinary `users` row whose `password_hash` holds an **Argon2id** encoded hash (metadata-db §4.1) produced by the same `SecretHasher` API keys use (§7.2) — no second hashing library, no hand-rolled parameters. `password_hash IS NULL` means OIDC-only: that account can **never** authenticate locally. There is **no self-registration** — a local account exists because an admin created it on the user-administration screen, or because it is the config-seeded bootstrap admin (§5A.2). The email domain allowlist (§4.3) governs OIDC exactly as today; admin creation is itself the gate for local accounts, so the allowlist does not re-apply there.

A local account's `provider` is the placeholder `'local'` (like `'bootstrap'`, §4.4 — a value with meaning, not an OIDC registration name): it marks "no OIDC identity is linked". If the person later signs in via OIDC with the same email, §4.2's linking step replaces the placeholder exactly as it replaces `'bootstrap'`; the local password stays valid alongside the OIDC identity unless an admin disables local access.

**Admin operations** on the user-administration screen (§7.6 `USER_ADMINISTRATION`): **create a local user** (a random one-time password is shown to the admin exactly once, `must_change_password = TRUE`, audited `auth.user.created` — there is no email flow; the product has no SMTP, so an admin-driven reset is the whole account-recovery story), **reset a password** (new one-time credential, forces the change, clears the lockout — the unlock path for a sprayed-out account), **disable local access** (the hash is cleared and the account is OIDC-only again), and **unlock** (clears the lockout without touching the credential).

### 5A.2 Seeding the first admin

A fresh local-accounts deployment has the chicken-and-egg §4.4 solves for OIDC: no admin exists to create the first account. Config seeds the **first admin only, never ordinary users** — passwords are not a config medium (config is plaintext in `docker compose config`, `docker inspect`, image layers and pasted issue reports, and offers no rotation, no revocation and no self-service). Every other account's credential lives hashed in the database, created by an admin from the user-administration screen.

- `datapipelines.auth.local.bootstrap-password-hash` — the preferred form: a **pre-computed Argon2id hash**, produced by `./gradlew :modules:auth:hashPassword` (prompts, or reads `DATAPIPELINES_SEED_PASSWORD`; never take the password as a shell argument — it would leak into shell history and the process table). The plaintext never has to sit in a config file.
- `datapipelines.auth.local.bootstrap-password` — the plaintext alternative, accepted because a zero-setup demo should be one command. It is never logged and never audited.

Both forms land on the `bootstrap-admin-email` account (created through the single §4.4 creation path, so the admin grant and its audit event fire exactly as for OIDC) and **always** seed with `must_change_password = TRUE`: the app refuses to proceed to any other screen until the seeded credential is replaced (§5A.4). Seeding is **create-if-absent and idempotent** — a restart never resets a changed password, and an account that already exists is left untouched. And the seeded credential cannot survive silently: every startup where the bootstrap-admin account still has `must_change_password = TRUE` logs a WARN (`event=auth.local.one_time_credential_pending`). Startup refuses the ambiguous shapes ([Configuration §7](configuration.md#7-config-validation)): both forms set, a seed without `local.enabled`, or a seed without `bootstrap-admin-email` — each naming both keys.

### 5A.3 Lockout

Distinct from the per-IP login rate limit (§9, `rate_limit.exceeded`): that damper is IP/route-scoped and cannot stop a **slow spray against one account** from many addresses. After `datapipelines.auth.local.lockout.max-failures` (default 5) **consecutive** failed local logins, the account locks for `datapipelines.auth.local.lockout.duration-minutes` (default 15):

- While locked, an attempt does **no Argon2 work** and does not touch the failure count — a spray cannot keep the lock warm or spend the server's native hashing memory. Even the correct password is refused until the lock expires.
- An expired lock starts a **fresh** failure budget (the count resets on the next failure rather than re-locking instantly forever).
- A successful login clears the counters. The lock engagement is audited as `auth.login.locked` (§10.1), and an admin can clear it early (unlock or password reset on the user-administration screen).

### 5A.4 Forced password change

Every seeded (§5A.2) and admin-reset credential is one-time: `users.must_change_password` is TRUE, and **the app refuses to proceed to any other screen until it is changed**. The mechanism is an MVC interceptor (`ForcedPasswordChangeInterceptor`) registered once for all paths — a filter/interceptor concern precisely so a future controller cannot forget it (a test adds a brand-new route and proves it is still gated). While the flag is set:

- Browsers are redirected `302` to `/settings/password`; htmx requests get `HX-Redirect` (a 302 would swap a full page into a fragment target); API and MCP paths get the `403 auth.password.change_required` envelope (§9) — a redirect is meaningless to a JSON client.
- The allowlist is exactly: the change-password page and its endpoint, `/logout`, `/health`, `/ready`, and the public/static paths the change page needs to render.
- **API-key principals are not gated** — an API key is a separate credential the user created deliberately; the forced change is about the human proving control of the interactive account.
- The flag is read through the same ~60s liveness cache (D13); every password mutation evicts it immediately.

The change itself (self-service, `POST /partials/account/password`) verifies the **current** password first — a hijacked session must not be able to rotate the credential — enforces the §5A.5 floor, clears `must_change_password`, and audits `auth.password.changed`.

### 5A.5 Enumeration resistance and the password policy

Unknown email, an OIDC-only account, and a wrong password are the **same outcome**: the same `Invalid email or password.` banner, the same redirect, the same HTTP status, and — just as important — the **same cost**. The no-row path still runs one Argon2 verification against a dummy hash, so response timing cannot tell "no such account" from "wrong password". Failure audit events carry no credential material.

Passwords are compared only through `SecretHasher.verify` (constant-time-ish at the hash layer). The policy floor for user-chosen passwords (self-service change) is **12 characters minimum** and 128 maximum: length is the factor that resists offline cracking (NIST 800-63B), composition rules push users to predictable patterns, and the maximum bounds Argon2 input cost. Seeded/admin-generated one-time passwords are random and always force a change at first login, so the floor does not apply to them.

### 5A.6 The login flow

`POST /login` (form fields `email`, `password`, plus the `dp_csrf` double-submit token — §8.4) is public (§8.3) and metered by the same per-IP `LoginRateLimitFilter` as the OIDC paths (`/login` prefix). It is deliberately **not** under a scope-governed path: it is the authentication ceremony itself, so no principal exists for the ScopeInterceptor to check yet — exactly like the OIDC callback it mirrors.

On success the flow converges with OIDC: the same `JwtService.issue` mints the same `dp_session` cookie, the same §4.2-step-4 workspace resolution runs (`auto-per-user` provisioning included), the same `auth.login.success` audit event is written (with `provider: "local"` in the details), and `is_active` is re-checked exactly as on the OIDC path (a deactivated account with the correct password gets the `inactive` banner — safe to reveal because the caller just proved the password). The `must_change_password` flag does not block the login; it engages the §5A.4 gate after it.

## 6. Session Tokens (Internal JWT)

### 6.1 JWT format

After OIDC login, the server issues its own JWT:

- **Algorithm:** HS256 (HMAC-SHA256, symmetric).
- **TTL:** 8 hours (configurable via `DATAPIPELINES_AUTH_JWT_TTL_HOURS`).
- **Claims:**
  ```json
  {
    "sub": "user-uuid",
    "email": "alice@company.com",
    "name": "Alice Wang",
    "scopes": ["read", "execute", "author"],
    "active_workspace": "acme",
    "iat": 1691234567,
    "exp": 1691263367,
    "iss": "datapipelines"
  }
  ```
  `active_workspace` is present only when login resolved one (§4.2 step 4); it is the
  workspace *name*, the same value `DP-Workspace` carries (§5.6).
- **Signing secret:** `DATAPIPELINES_JWT_SECRET` env var (≥ 32 bytes random, base64). Required at startup.

**Scope derivation at token issue (v1 rule):** the `scopes` claim is derived from the user record at login: `is_admin = true` → `["read", "execute", "author", "admin"]`; every other active user → `["read", "execute", "author"]`. Finer per-user scope assignment and IdP group sync are future work (§15). API-key scopes are chosen at key creation (§7.4) and are independent of this rule, bounded by the creator's scopes.

### 6.2 Why not use OIDC tokens directly?

- **Decoupled TTL.** Our JWT has its own TTL (8h). OIDC access tokens have provider-specific TTLs (Google = 1h). Using OIDC tokens would require refreshing mid-session.
- **Scope management.** Our JWT carries our own scopes (`read`, `execute`, `author`, `admin`). OIDC tokens carry provider scopes which don't map to our authorization model.
- **Stateless validation.** Our JWT is validated with a local HMAC secret. OIDC token validation requires fetching the provider's JWKS (network call).
- **No vendor lock-in.** If we add a third provider (GitHub, Okta), the internal JWT is identical regardless of which provider authenticated the user.

### 6.3 JWT validation (on every request)

```kotlin
class JwtAuthenticationFilter(
    private val jwtService: JwtService
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        // Extract JWT from cookie
        val cookie = request.cookies?.firstOrNull { it.name == "dp_session" }
        val jwt = cookie?.value

        if (jwt != null) {
            try {
                val claims = jwtService.validate(jwt)
                val userId = UUID.fromString(claims.subject)

                // Liveness re-check: cached (60s TTL, same cache infra as §7.3) Postgres
                // lookup of users.is_active. Deactivation kills the session within ~1 min.
                if (!userLivenessCache.isActive(userId)) {
                    throw DeactivatedUserException(userId)
                }

                val principal = AuthenticatedPrincipal(
                    userId = userId,
                    email = claims["email"] as String,
                    displayName = claims["name"] as String,
                    scopes = (claims["scopes"] as List<*>).map { Scope.valueOf(it as String) }.toSet(),
                    authMethod = AuthMethod.OIDC,
                    keyId = null
                )
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(
                        principal, null,
                        principal.scopes.map { SimpleGrantedAuthority("SCOPE_${it.name}") }
                    )
            } catch (e: Exception) {
                // Invalid/expired JWT or deactivated user — clear cookie, proceed unauthenticated
                response.addCookie(Cookie("dp_session", "").apply { maxAge = 0 })
            }
        }

        filterChain.doFilter(request, response)
    }
}
```

### 6.4 Session expiration

JWT expires after 8h (configurable). On expiry:
- API calls return `401 auth.session.expired`.
- UI redirects to `/login`.
- No refresh token flow. User re-authenticates via OIDC.

### 6.5 Logout

```
POST /logout
Cookie: dp_session=<jwt>
```

Server clears `dp_session` cookie. The JWT itself is stateless and not revoked server-side — but with the cookie cleared and the JWT gone from the client, the session is effectively dead. The OIDC provider's session is NOT terminated (user may need to sign out separately at Google/Microsoft if they want full logout).

---

## 7. API Keys

Unchanged from the previous auth spec — API keys are independent of the OIDC login mechanism. A user logs in via OIDC, generates API keys from the UI, and those keys are used by agents.

### 7.1 Anatomy

```
dpk_<key_id>.<random_secret>
```

- `dpk_` — literal prefix (secret-scanner detectable).
- `key_id` — 12-char base32, unique per key.
- `random_secret` — 48-char base32.
- Total length: ~64 chars.

### 7.2 Storage

Argon2id hash (same as before). Schema in [Metadata DB spec](metadata-db.md).

### 7.3 Validation flow

```
1. Client sends DP-API-Key: dpk_<id>.<secret>
   (on /mcp, Authorization: Bearer dpk_<id>.<secret> is equivalent)
2. Parse id and secret.
3. Look up api_keys WHERE id = 'dpk_<id>' AND is_revoked = false.
4. Not found → 401 auth.api_key.invalid.
5. expires_at < now → 401 auth.api_key.expired.
6. Argon2id.verify(key_hash, full_key).
7. Verify fails → 401 auth.api_key.invalid.
8. Check the key owner's users.is_active (cached, 60s TTL).
   Inactive → 401 auth.api_key.invalid.
9. Update last_used_at, last_used_ip (async).
10. Build principal with userId + scopes from key record.
```

In-memory cache (`datapipelines.auth.api-keys.cache-ttl-seconds`, default 60s) for recently-validated keys and owner liveness, invalidated on revocation/deactivation on the local instance and by TTL elsewhere.

### 7.4 Issuance

User logs in via OIDC → navigates to API Keys page → clicks "Generate new key" → names it, selects scopes, optionally sets expiration → server generates key, hashes it, stores hash, returns plaintext **once**.

A key's scopes MUST be a subset of its creator's scopes at issue time — a `read`-scoped session cannot mint an `author` key (privilege escalation guard). The same guard extends to workspaces: a key is **pinned to a workspace its creator is a member of** (design §5.2) — v1 pins the creator's active workspace at issuance, and the pin is the key's request context for its entire lifetime (§5.6). The HTTP surface for key management is defined in [REST API §16](rest-api.md#16-auth--user-admin-endpoints).

### 7.5 Scopes

Same hierarchical scope system:

| Scope | Includes | Description |
|---|---|---|
| `read` | — | Read pipelines, templates, datasources, executions |
| `execute` | `read` | Execute pipelines; retrieve results |
| `author` | `execute`, `read` | Create/modify pipelines and templates |
| `admin` | all | Manage datasources, users, system config |

Default scope on key creation: `read`. Higher scopes require explicit selection.

### 7.6 Scope ↔ Operation Matrix (authoritative)

This matrix is the ONLY place operation-level scope requirements are defined. [REST API](rest-api.md), [MCP Server](mcp-server.md), and [UI Screens](ui-screens.md) reference it; they never assert scopes locally. Scopes are hierarchical (§7.5) — the listed scope is the minimum.

**REST endpoints:**

| Operation | Endpoints | Min scope |
|---|---|---|
| Read pipelines / templates / datasources (metadata) / executions | all `GET` under `/api/v1/pipelines`, `/api/v1/templates`, `/api/v1/datasources`, `/api/v1/executions` (incl. `/export`, `/versions`) | `read` |
| Retrieve execution results (cursor) | `GET /api/v1/executions/{id}/result` (+ ownership check) | `read` |
| Execute a pipeline | `POST /api/v1/pipelines/{id}/execute` | `execute` |
| Cancel an execution | `DELETE /api/v1/executions/{id}` (+ ownership check; `admin` may cancel any) | `execute` |
| Create / update / delete pipelines & templates, import | `POST`/`PUT`/`DELETE` on `/api/v1/pipelines`, `/api/v1/templates`, `POST /api/v1/pipelines/import` | `author` |
| Test a datasource connection | `POST /api/v1/datasources/{name}/test` | `author` |
| Introspect a datasource schema | `GET /api/v1/datasources/{name}/schemas`, `GET /api/v1/datasources/{name}/tables`, `GET /api/v1/datasources/{name}/tables/{t}/columns` | `author` |
| Create / update / delete workspace-bound datasources | `POST`/`PUT`/`DELETE` on `/api/v1/datasources` (workspaces D8: author is the floor; the `member-datasources-enabled` gate, membership and binding checks are enforced in-handler) | `author` |
| Create / update / delete global datasources | `POST`/`PUT`/`DELETE` on `/api/v1/datasources` for `global` datasources and the `global`/`readonly`-on-global flag writes (workspaces D8) | `admin` |
| Manage own API keys | `/api/v1/auth/api-keys` (key scopes ⊆ own scopes, §7.4) | any authenticated |
| Get current principal | `GET /api/v1/auth/me` ([REST API §16.2](rest-api.md#162-current-principal)) | any authenticated |
| Set own theme preference | `PATCH /partials/profile/theme` (writes the caller's own user row only — no payload-chosen target) | any authenticated |
| User administration | `/api/v1/auth/users/**` (activate, deactivate, grant/revoke admin) | `admin` |
| List / read own workspaces & members | `GET /api/v1/workspaces`, `GET /api/v1/workspaces/{name}`, `GET /api/v1/workspaces/{name}/members` | `read` |
| Create a workspace (per provisioning mode) | `POST /api/v1/workspaces` — `closed` mode refuses non-admins in-handler (`workspace.creation_forbidden`) | `author` |
| Update a workspace / manage its members | `PUT /api/v1/workspaces/{name}`, `POST /api/v1/workspaces/{name}/members`, `DELETE /api/v1/workspaces/{name}/members/{user_id}`, `DELETE /api/v1/workspaces/{name}` — workspace `owner` or `admin` enforced in-handler; an API key manages only its pinned workspace (§5.6) | `author` |
| Change own password | `POST /partials/account/password` (§5A.4 — the current password is verified in-handler; own account only) | any authenticated |

**MCP tools** (all 18 — [MCP Server §6.2](mcp-server.md#62-tool-definitions)):

| Tool | Min scope |
|---|---|
| `pipelines_list`, `pipelines_get`, `templates_list`, `templates_get`, `datasources_list`, `datasources_get`, `executions_list`, `executions_get`, `executions_get_result` | `read` |
| `pipelines_execute` | `execute` |
| `pipelines_create`, `pipelines_update`, `templates_create`, `templates_render` | `author` |
| `datasources_test`, `datasources_get_schemas`, `datasources_get_tables`, `datasources_get_columns` | `author` |

(MCP has no datasource-management tools in v1 — creating/editing datasources is UI/REST-only: workspace-bound datasource CUD is `author` + the workspaces D8 gates, global datasource CUD is `admin`. All 18 tools operate inside the API key's pinned workspace, design §9.)

**UI screens** reference the same REST operations they call; per-screen minimums are listed in [UI Screens](ui-screens.md) and MUST match this matrix. The htmx partials (`/partials/**`) and the workspace screen actions declare their REST twin's operation with the same `@RequiredScope` mechanism, and the ScopeInterceptor governs `/partials/**` with the same default-deny as `/api/**` and `/mcp`: an unannotated partial is refused, and a mutating partial enforces its twin's floor (a `read` key cannot register a datasource through `POST /partials/datasources`).

---

## 8. Spring Security Configuration

### 8.1 Filter chain

```kotlin
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val apiKeyFilter: ApiKeyFilter,
    private val oidcSuccessHandler: OidcSuccessHandler,
    private val scopeInterceptor: ScopeInterceptor
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf ->
                // Cookie: dp_csrf (readable by JS), header: DP-CSRF-Token
                csrf.csrfTokenRepository(cookieCsrfRepository())
                // Exemption follows the CREDENTIAL, not the path (§8.4): only requests
                // carrying DP-API-Key (or Bearer dpk_ on /mcp) skip CSRF.
                csrf.ignoringRequestMatchers(apiKeyCarrierMatcher)
            }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/health", "/ready", "/info",
                        "/login", "/login/**",
                        "/oauth2/**",
                        "/vendor/**", "/css/**", "/js/**", "/favicon.ico"
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2Login { oauth ->
                oauth.successHandler(oidcSuccessHandler)
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(apiKeyFilter, JwtAuthenticationFilter::class.java)
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .logout { logout ->
                logout.logoutUrl("/logout")
                       .deleteCookies("dp_session")
                       .logoutSuccessUrl("/login")
            }

        return http.build()
    }

    @Bean
    fun mvcInterceptor(): WebMvcConfigurer {
        return object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(scopeInterceptor)
            }
        }
    }
}
```

### 8.2 Filter order (per request)

```
1. CORS filter                     — adds CORS headers
2. CSRF filter                     — validates CSRF token on state-changing requests (UI only; API paths excluded)
3. ApiKeyFilter                    — checks DP-API-Key header (or Bearer dpk_ on /mcp); if present, validates and sets SecurityContext
4. JwtAuthenticationFilter         — checks dp_session cookie; if present, validates and sets SecurityContext
5. WorkspaceResolutionFilter       — resolves the active workspace onto the principal (§5.6): DP-Workspace switch or claim/pin, membership-checked
6. OAuth2LoginAuthenticationFilter — handles /oauth2/** and /login/oauth2/code/** redirects
7. AuthorizationFilter             — checks authenticated() for protected paths
8. ScopeInterceptor (MVC)          — checks @RequiredScope annotation on controller methods; default-deny for unannotated handlers under /api/**, /partials/** and /mcp
9. Controller                      — handles the request
```

If neither API key nor JWT is present (and the path requires auth), the AuthorizationFilter routes to the entry point, which splits by client shape (T31): a request whose `Accept` includes `text/html` — a browser navigating a UI route — gets a `302` to `/login` (the `Location` is a relative header, never `sendRedirect`'s Host-derived absolute URL); `/api/**` and `/mcp` NEVER redirect (their 401 JSON envelope is contract, byte-pinned), and non-HTML clients (`curl`'s `Accept: */*`, JSON API callers) keep the exact current envelope whatever path they hit. If authenticated but scope insufficient, the ScopeInterceptor returns `403`.

### 8.3 Public endpoints (no auth required)

| Path pattern | Why public |
|---|---|
| `/health`, `/ready` | Health checks for orchestrators |
| `/info` | Build info |
| `/login`, `/login/**` | Login page + error redirects + the local password POST (§5A) |
| `/oauth2/**` | OIDC authorization + callback |
| `/vendor/**`, `/css/**`, `/js/**` | Static assets (design system, Cytoscape, Alpine, app CSS/JS) |

### 8.4 API endpoints (auth via API key OR JWT)

All `/api/v1/**` endpoints accept either:
- `DP-API-Key: dpk_...` header (agents, programmatic clients).
- `Cookie: dp_session=<jwt>` (browser UI calling REST directly).

The filter chain tries API key first, then JWT. If both present, API key wins.

CSRF exemption is scoped by **credential type, never by path**: a request is exempt only when it carries an API key (`DP-API-Key` header, or `Authorization: Bearer dpk_` on `/mcp`) — a credential a hostile browser context cannot forge, with no cookie involved. A state-changing request authenticated by the `dp_session` cookie requires the `dp_csrf` double-submit token (`DP-CSRF-Token` header) **wherever it occurs**: `/partials/**` ([UI Screens §3](ui-screens.md#3-common-layout)), `POST /logout`, and cookie-authenticated calls to `/api/v1/**` alike. `/mcp` accepts no cookies at all (§8.5), so CSRF never arises there. `dp_session`'s `SameSite=Strict` (§5.5) is defense-in-depth, not the control — it does not defend against a same-site subdomain attacker. In the §8.1 chain this is a `RequestMatcher` over the credential carrier, not a path glob. A CSRF failure returns 403 `auth.csrf.invalid` with `details.reason`: `missing` | `mismatch` (§9). All three cookies this module mints (`dp_session`, `dp_csrf`, `dp_oauth2_authz`) carry the `Secure` flag keyed off `datapipelines.auth.base-url`'s scheme (T33): `https://` — or no base-url at all — keeps `Secure`; an explicit `http://` base-url drops it so local development login works over plain HTTP. **Production MUST be https** — the wrong default would silently drop sessions there, so absent configuration fails secure. (History: v2.2's prose and sketch contradicted each other; v2.3 briefly resolved toward path-based exemption + `SameSite=Strict`; v2.4 supersedes both after two Gate C seats independently flagged the subdomain gap — exemption follows the credential, not the path.)

### 8.5 MCP endpoint (`/mcp`)

`POST /mcp` and `GET /mcp` ([MCP Server §3](mcp-server.md#3-transport)) are API-key-only — session cookies are **not** accepted there. Two equivalent credential carriers:

- `DP-API-Key: dpk_<id>.<secret>` — same as REST.
- `Authorization: Bearer dpk_<id>.<secret>` — for MCP clients that can only set the standard Authorization header. The `ApiKeyFilter` recognizes the `dpk_` prefix in a Bearer token and routes it through the identical validation path (§7.3).

`/mcp` is CSRF-exempt (no cookie auth) and enforces the same scope matrix (§7.6) per tool.

---

## 9. Auth Errors

Codes follow the `{domain}.{entity}.{failure}` convention; the registry of record is [Pipeline Contract §13.7](pipeline-contract.md#137-authentication--authorization).

| Code | HTTP | Description |
|---|---|---|
| `auth.login.domain_not_allowed` | 403 | Email domain not in allowlist (OIDC) |
| `auth.login.user_inactive` | 403 | User account deactivated (OIDC or local) |
| `auth.login.bad_credentials` | 401 | Local login rejected: email unknown or password incorrect — deliberately identical (§5A.5) |
| `auth.login.locked` | 403 | Local account locked after consecutive failures (§5A.3) |
| `auth.password.change_required` | 403 | Session principal must change password before any other operation (§5A.4) |
| `auth.login.oidc_error` | 500 | OIDC provider returned an error during login |
| `auth.session.expired` | 401 | JWT expired |
| `auth.session.invalid` | 401 | JWT signature invalid or malformed |
| `auth.api_key.missing` | 401 | No `DP-API-Key` header, no Bearer `dpk_` token, no `dp_session` cookie |
| `auth.api_key.invalid` | 401 | Key id not found, revoked, hash mismatch, or owner deactivated |
| `auth.api_key.expired` | 401 | Key's `expires_at` is in the past |
| `auth.scope.insufficient` | 403 | Principal lacks required scope (§7.6 matrix) |
| `auth.csrf.invalid` | 403 | CSRF token missing or mismatched on a state-changing UI request (`details.reason`: `missing` \| `mismatch`) |

Rate limiting uses the single system-wide `rate_limit.exceeded` code ([Pipeline Contract §13.11](pipeline-contract.md#1311-rate-limiting--idempotency)) — there is no separate auth-layer rate-limit code. The login rate limit is `datapipelines.auth.rate-limit.login-per-minute` ([Configuration §3.4](configuration.md#34-auth)).

Workspace resolution failures (§5.6) use the `workspace.*` codes — `workspace.membership_required` (403), `workspace.header_forbidden` (400), `workspace.creation_forbidden` (403) — catalogued in [Pipeline Contract §13.12](pipeline-contract.md#1312-workspace-resolution).

---

## 10. Audit Log

### 10.1 Events

| Event | Trigger |
|---|---|
| `auth.login.success` | Login succeeded, JWT issued (OIDC or local — the details' `provider` names the method) |
| `auth.login.domain_not_allowed` | User's email domain not in allowlist |
| `auth.login.user_inactive` | User account is deactivated (OIDC or local — same event) |
| `auth.login.oidc_error` | OIDC provider returned an error |
| `auth.login.bad_credentials` | Local login failed: unknown email, OIDC-only account, or wrong password — deliberately indistinguishable (§5A.5) |
| `auth.login.locked` | Local account locked after `lockout.max-failures` consecutive failures (§5A.3) |
| `auth.password.seeded` | Config seeded the bootstrap admin's one-time local credential (§5A.2) |
| `auth.password.changed` | User changed their own password (self-service or forced, §5A.4) |
| `auth.password.reset` | Admin reset a user's password — new one-time credential (§5A.1) |
| `auth.password.disabled` | Admin disabled a user's local access — account is OIDC-only (§5A.1) |
| `auth.user.created` | Admin created a local account (§5A.1; details carry the acting admin) |
| `auth.user.unlocked` | Admin cleared a local account's lockout (§5A.3) |
| `auth.logout` | User logged out (cookie cleared) |
| `auth.api_key.created` | New API key issued |
| `auth.api_key.revoked` | API key revoked |
| `auth.api_key.used` | API key validated (sampled 1/100) |
| `auth.api_key.rejected` | API key validation failed |
| `auth.scope.denied` | Request rejected for insufficient scope |
| `auth.user.deactivated` | Admin deactivated a user |
| `auth.user.activated` | Admin reactivated a user |
| `auth.user.admin_granted` | Admin granted admin scope to user |
| `auth.user.admin_revoked` | Admin revoked admin scope from user |
| `auth.workspace.provisioned` | Personal workspace auto-created on first login (`auto-per-user` mode) |
| `auth.workspace.created` | Workspace created through the service path |
| `auth.workspace.header_rejected` | `DP-Workspace` presented on an API-key request (§5.6) |

### 10.2 Log shape

```json
{
  "timestamp": "2026-08-05T14:30:00.123Z",
  "event": "auth.login.success",
  "user_id": "uuid",
  "provider": "google",
  "source_ip": "10.0.0.42",
  "user_agent": "Mozilla/5.0...",
  "details": {
    "email": "alice@company.com"
  }
}
```

---

## 11. Configuration

OIDC providers are configured as a **generic list**. The deployment chooses which provider(s) to enable — any OIDC-compliant provider works.

### 11.1 OIDC provider configuration

Providers are defined in `application.yml` (structural config) with secrets referenced from env vars:

```yaml
datapipelines:
  auth:
    oidc:
      providers:
        - name: google               # registration ID
          client-id: ${GOOGLE_CLIENT_ID}
          client-secret: ${GOOGLE_CLIENT_SECRET}
          issuer-uri: https://accounts.google.com
          display-name: "Sign in with Google"

        # Microsoft (Entra ID) — SINGLE-TENANT only in v1: the issuer-uri MUST name
        # your tenant. Multi-tenant `/common` is NOT supported: its discovery metadata
        # carries the `{tenantid}` issuer template, which the OIDC discovery client
        # (Nimbus, via Spring's ClientRegistrations.fromIssuerLocation) refuses, so a
        # `/common` issuer fails startup — no request flow can ever succeed behind it.
        # - name: microsoft
        #   client-id: ${MICROSOFT_CLIENT_ID}
        #   client-secret: ${MICROSOFT_CLIENT_SECRET}
        #   issuer-uri: https://login.microsoftonline.com/{tenant-id}/v2.0
        #   display-name: "Sign in with Microsoft"

        # Add any OIDC provider the company uses:
        # - name: okta
        #   client-id: ${OKTA_CLIENT_ID}
        #   client-secret: ${OKTA_CLIENT_SECRET}
        #   issuer-uri: https://company.okta.com
        #   display-name: "Sign in with Okta"
        #
        # - name: keycloak
        #   client-id: ${KEYCLOAK_CLIENT_ID}
        #   client-secret: ${KEYCLOAK_CLIENT_SECRET}
        #   issuer-uri: https://sso.company.com/realms/main
        #   display-name: "Company SSO"

    jwt:
      secret: ${DATAPIPELINES_JWT_SECRET}
      ttl-hours: 8
      algorithm: HS256

    allowlist:
      domains: ${DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS:}    # comma-separated, empty = open

    api-keys:
      cache-ttl-seconds: 60
      default-scopes: [read]
```

**Per provider, three values are required:**
- `client-id` — from the OIDC provider's app registration.
- `client-secret` — from the provider's app registration.
- `issuer-uri` — the provider's OIDC issuer URL. Triggers auto-discovery of all endpoints.

**Two further values:**
- `name` — **required.** Registration ID used in URLs (`/oauth2/authorization/{name}`) and stored in `users.provider`. Lowercase `[a-z0-9-]+`. (The §5.2 bean uses it directly; there is no derivation fallback.)
- `display-name` — optional. Text shown on the login button; defaults to `name`.

### 11.2 Common provider issuer URIs (reference)

| Provider | issuer-uri |
|---|---|
| Google | `https://accounts.google.com` |
| Microsoft (single tenant) | `https://login.microsoftonline.com/{tenant-id}/v2.0` |
| ~~Microsoft (multi-tenant)~~ | `/common` — **unsupported in v1** (§11.1: the `{tenantid}` discovery metadata is refused at startup) |
| Okta | `https://{your-org}.okta.com` |
| Auth0 | `https://{your-tenant}.auth0.com` |
| Keycloak | `https://{host}/realms/{realm}` |
| AWS Cognito | `https://cognito-idp.{region}.amazonaws.com/{user-pool-id}` |
| Ping Identity | `https://{host}/as` |

### 11.3 Required environment variables

The env vars depend on which providers the deployment configures. The structural config (which providers, issuer URIs) is in `application.yml`; secrets are in env vars:

| Variable | Required? | Description |
|---|---|---|
| `DATAPIPELINES_JWT_SECRET` | **yes** | Internal JWT signing secret (≥ 32 bytes random, base64) |
| `DATAPIPELINES_AUTH_BASE_URL` | **yes (when any OIDC provider is configured)** | The deployment's exact external origin, e.g. `https://dp.example.com` — the absolute OIDC redirect URI is built from it (§5.2). **Application startup fails if unset while providers are configured** ([Configuration §3.4](configuration.md#34-auth) `datapipelines.auth.base-url`). |
| `DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL` | no | Bootstrap the first admin (§4.4). Optional; when set, the OIDC user with this exact (lowercased) email is granted admin at row creation. |
| Provider-specific client-id/secret env vars | **yes** | One pair per configured provider (names defined in `application.yml`) |

For example, a deployment using Google + Okta would set:
```bash
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
OKTA_CLIENT_ID=...
OKTA_CLIENT_SECRET=...
DATAPIPELINES_JWT_SECRET=...
```

### 11.4 API key validation cache

Validated API keys and owner-liveness results are cached in-memory per instance for `datapipelines.auth.api-keys.cache-ttl-seconds` (default 60). Revocation/deactivation invalidates the local cache immediately and takes effect elsewhere at TTL expiry.

### 11.5 Other auth configuration keys

All auth config keys (allowlist domains, JWT TTL, cache TTL, default key scopes, login rate limit) are defined in [Configuration §3.4](configuration.md#34-auth) — the single config authority. This spec does not restate names or defaults.

---

## 12. Implementation Notes

### 12.1 Where this lives

`auth` Gradle module:
- `co.datapipelines.auth.User` data class
- `co.datapipelines.auth.ApiKey` data class
- `co.datapipelines.auth.Scope` enum
- `co.datapipelines.auth.AuthenticatedPrincipal` data class
- `co.datapipelines.auth.JwtService` — issue + validate internal JWTs
- `co.datapipelines.auth.ApiKeyService` — issue + validate + revoke API keys
- `co.datapipelines.auth.UserService` — find-or-create by OIDC identity
- `co.datapipelines.auth.OidcSuccessHandler` — OIDC login callback
- `co.datapipelines.auth.JwtAuthenticationFilter` — cookie → JWT → principal
- `co.datapipelines.auth.ApiKeyFilter` — header → key → principal
- `co.datapipelines.auth.ScopeInterceptor` — `@RequiredScope` enforcement
- `co.datapipelines.auth.AuditLogger`
- `co.datapipelines.auth.SecurityConfig` — Spring Security wiring

### 12.2 Dependencies

- Spring Security (OAuth2 client, CSRF, filter chain).
- Spring Security OAuth2Jose (JWT validation for OIDC ID tokens).
- `de.mkammerer:argon2-jvm` — API key hashing.
- `io.jsonwebtoken:jjwt` — internal JWT issue/validate.

### 12.3 Persistence (JDBC)

All auth tables accessed via `JdbcTemplate` + `RowMapper`. No JPA. See [Metadata DB spec](metadata-db.md) for table definitions.

---

## 13. Security Checklist

- [ ] OIDC client secrets stored in env vars or secret manager, not in source.
- [ ] `DATAPIPELINES_JWT_SECRET` is high-entropy (≥ 32 bytes random).
- [ ] Email domain allowlist configured for internal-only deployments.
- [ ] API keys hashed with Argon2id, never stored plaintext.
- [ ] Session cookies `HttpOnly`, `Secure`, `SameSite=Strict`.
- [ ] CSRF protection on all state-changing UI endpoints.
- [ ] No `/actuator/*` path reachable without auth on the application port; `/actuator/prometheus` served only on the separate management port ([Observability §4.2](observability.md#42-exposure)). Root `/health`, `/ready`, `/info` are the only public probes.
- [ ] All auth events audited.
- [ ] All traffic over TLS.
- [ ] OIDC redirect URI locked to the deployment's exact domain (not `localhost` in production).

---

## 14. What Changed from v1.0

| v1.0 (local auth) | v2.0 (OIDC) |
|---|---|
| Local username + password | Google/Microsoft OIDC |
| `users.password_hash` (Argon2id) | Removed — no passwords stored |
| `POST /auth/login` (JSON body) | `GET /oauth2/authorization/google` (browser redirect) |
| No identity provider | Google + Microsoft OIDC providers |
| `users.provider`, `users.provider_subject` | New fields for OIDC identity |
| Email domain allowlist | New — restricts who can log in |
| JWT, API keys, scopes, audit log | **Unchanged** |

*Postscript (v2.10):* optional local accounts returned in §5A — narrower than v1.0's: admin-created only (no self-registration), Argon2id via the API-key `SecretHasher`, per-account lockout, forced rotation of seeded/reset credentials, off by default.

---

## 15. Open Questions / Future

- **Additional OIDC providers** (GitHub, Okta, Auth0) — easy to add; just another Spring Security registration.
- **SAML** — for enterprises that require SAML instead of OIDC. Spring Security SAML extension.
- **Group/role sync from provider** — map OIDC groups to internal scopes automatically.
- **Per-datasource ACLs** — fine-grained access beyond scopes.
- **Service accounts** — non-human principals for CI/CD.
- **MFA** — if provider enforces it (Google/Microsoft MFA is provider-side, transparent to us).

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-30 | v2.10 | local password auth | **§5A (new): optional local password accounts** — admin-created only (no self-registration), Argon2id via the API-key `SecretHasher`, `provider = 'local'` placeholder, per-account lockout (§5A.3), config-seeded first admin with a forced first-login change (§5A.2/§5A.4), enumeration-resistant failures with timing equalization (§5A.5), `POST /login` converging on the OIDC session (§5A.6). §1/§2 Principle 1 amended honestly: identity delegated by default, the narrow local exception and WHY. §5.3 login page: form + divider + buttons, only enabled methods. §8.3: `/login` covers the local POST. §10.1 gains `auth.login.bad_credentials`, `auth.login.locked`, `auth.password.{seeded,changed,reset,disabled}`, `auth.user.{created,unlocked}`; `auth.login.success`/`user_inactive` are shared with OIDC. The local-auth error codes join §9 and the §13.7 registry (see the catalog commit). §14 postscript. |
| 2026-08-28 | v2.9 | sample data, slice A | **§4.4 pre-provisioning:** when `datapipelines.bootstrap.datasources-file` is set, startup creates the configured bootstrap admin's row before serving traffic (placeholder `provider = 'bootstrap'` / `provider_subject` = the email, `display_name` from the local-part) so bootstrap-registered datasources have a real `created_by`. Grant-at-creation semantics are unchanged — this is the same single path firing earlier, one audit event across provision → restart → login. **§4.2 linking:** the update no longer rewrites `display_name` on every sign-in; it is set at row creation, and refreshed from the ID token's `name` claim only when the login completes a `provider = 'bootstrap'` placeholder |
| 2026-08-26 | v2.8 | workspaces slice 2 | **Workspace resolution (design §5/§7):** §4.2 step 4 — login stamps `active_workspace` (last-used, else first membership, else freshly provisioned personal workspace under `auto-per-user`); §5.6 — `DP-Workspace` per-request switch, membership-checked (`403 workspace.membership_required`), API-key requests pin the key's workspace and refuse the header (`400 workspace.header_forbidden`); §7.4 — issuance restricted to the creator's workspaces; `workspace.*` codes catalogued in pipeline-contract §13.12. **§11.1:** stock config ships Google only; Microsoft becomes a commented single-tenant example — multi-tenant `/common` documented as unsupported in v1 (Nimbus refuses its `{tenantid}` discovery metadata at startup). |
| 2026-08-05 | v1.0 | initial draft | Local username/password auth, JWT sessions, API keys, scopes, audit log |
| 2026-08-05 | v2.0 | OIDC migration | Replaced local auth with Google/Microsoft OIDC. No passwords stored. Internal JWT issued after OIDC login. Added email domain allowlist. Added Spring Security filter chain configuration. Added OIDC success handler. Users provisioned automatically on first login. |
| 2026-08-05 | v2.1 | generic OIDC | Replaced hardcoded Google/Microsoft with **generic OIDC provider model**. Any OIDC-compliant provider works (Google, Microsoft, Okta, Auth0, Keycloak, AWS Cognito, Ping, etc.). Deployment configures a provider list in `application.yml`; login page renders buttons dynamically. `provider` column in users table is free text (not constrained to GOOGLE/MICROSOFT). OIDC discovery auto-configures all endpoints from `issuer-uri`. |
| 2026-08-07 | v2.2 | consistency campaign | **D10:** `X-API-Key` → `DP-API-Key`; CSRF = `dp_csrf` cookie + `DP-CSRF-Token` header. **D11:** `/mcp` in the chain (§8.5): API-key-only, CSRF-exempt, Bearer `dpk_` accepted. **D13:** liveness re-check on every request via 60s cache — deactivation effective ≤ ~1 min (§4.2, §6.3, §7.3). **D14:** scope derivation at login (§6.1). **D15:** authoritative scope↔operation matrix (§7.6); key scopes ⊆ creator's scopes (§7.4). **D5:** error codes normalized to 3-segment (`auth.api_key.missing` etc.), `auth.csrf.*` collapsed to `auth.csrf.invalid`, `auth.rate_limit.exceeded` removed in favor of `rate_limit.exceeded`. `name` required per provider (§11.1); audit example provider lowercase; config tables replaced by pointers to configuration.md (D8). See [SPEC-REVIEW-2026-08](SPEC-REVIEW-2026-08.md) |
| 2026-08-09 | v2.3 | P3 build (Gate C testing review) | §8.4 CSRF prose corrected to match the §8.1 chain (which is the ratified D10 design): `/api/**` and `/mcp` are CSRF-exempt — API keys carry no cookie, and cookie-authenticated API calls are defended by `dp_session` `SameSite=Strict` (§5.5); the `dp_csrf`/`DP-CSRF-Token` double-submit guards the cookie-native UI surfaces (`/partials/**`, `POST /logout`), failing 403 `auth.csrf.invalid` with `details.reason`. The v2.2 sentence requiring the token on cookie-authenticated `/api` calls contradicted the sketch and is withdrawn. |
| 2026-08-10 | v2.5 | P3 build (auth re-review) | §7.6: added the `GET /api/v1/auth/me` row (any authenticated) — default-deny (§8.3 ScopeInterceptor) turns a missing matrix row into a hard block for that endpoint, and §7.6 is the sole authority. §11.3: `DATAPIPELINES_AUTH_BASE_URL` listed as required-with-providers (startup fails without it — the v2.4 §5.2 change added the requirement but not the env-list entry) + `DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL` optional. |
| 2026-08-09 | v2.4 | P3 build (Gate C security + API reviews) | **CSRF re-ruled (supersedes v2.3):** exemption follows the CREDENTIAL, not the path — only API-key-carrying requests skip CSRF; cookie-authenticated state-changing requests require the `dp_csrf` double-submit everywhere, `SameSite=Strict` demoted to defense-in-depth (same-site subdomain gap, flagged independently by two Gate C seats); §8.1 sketch updated. **§5.2:** OIDC redirect URI built absolutely from new `datapipelines.auth.base-url` (Configuration §3.4), never request-derived (`Host`/`X-Forwarded-Host` attack); startup fails when unset with providers configured. **§4.2:** emails lowercase-normalized at every lookup/store; login rejected when `email_verified: false` (unverified-account takeover via email-keyed linking). **§4.4:** bootstrap admin grant fires only at row creation — never re-grants after a deliberate revoke. |
| 2026-08-14 | v2.6 | v1.1 introspection build | §7.6 REST table: new "Introspect a datasource schema" row (`GET /api/v1/datasources/{name}/schema`, `/tables`, `/tables/{t}/columns`) at `author` — the §8.1 connection-test precedent (live connection against a production datasource; consumer is authoring). Sourced from datasources §7A. MCP rows follow with the mcp-server amendment. |
| 2026-08-15 | v2.7 | surface restructure (part 1) | §7.6: `datasources_get_schema` row removed from the MCP table and `GET /api/v1/datasources/{name}/schema` from the introspection REST row (the bundled whole-schema snapshot is gone — table listings stay lightweight); MCP count 18 → 17 pending the schemas listing. |
