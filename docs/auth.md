# Auth & Security Specification

**Status:** v3.9 (revised — see Change Log)
**Owner:** datapipelines.co core
**Depends on:** [Type System](type-system.md)
**Last updated:** 2026-09-25

---

## 1. Purpose

This spec defines **how users authenticate** (OIDC via any provider — Google, Microsoft, Okta, Auth0, Keycloak, etc.), **how sessions work** (internal JWT after OIDC login), **how agents authenticate** (API keys), **what authenticated principals can do** (roles — a member's, or a key's own, §7.5), and the **Spring Security wiring** that ties it all together.

The product is self-hosted, internal-users-only. Identity is delegated to **any OIDC-compliant provider** via OpenID Connect **by default**; deployments without an IdP may additionally enable optional local password accounts (§5A), stored as Argon2id hashes. After any login, the server issues its own JWT for stateless session management.

---

## 2. Design Principles

1. **Identity is delegated by default; local accounts are the narrow exception.** The server never sees or stores OIDC user passwords — identity belongs to the external provider. The one exception (§5A): a deployment with no IdP at all would otherwise be unusable — evaluating the product required registering an OAuth client before seeing a single screen, the biggest adoption barrier the product had. For that deployment shape, optional local accounts exist: admin-created only (no self-registration), stored as Argon2id hashes (never plaintext, never in config beyond the one-time first-admin seed), forced to rotate seeded/reset credentials at first login, and off unless explicitly enabled. An OIDC-only deployment behaves exactly as before.
2. **Generic OIDC, not provider-specific.** Any OIDC-compliant provider works — Google, Microsoft, Okta, Auth0, Keycloak, Ping, AWS Cognito, etc. The deployment configures which provider(s) to enable; the code is provider-agnostic.
3. **OIDC for humans, API keys for agents.** Users log in via browser (OIDC redirect flow). Agents (Claude, GLM, Copilot) authenticate via API keys issued from the UI.
4. **Internal JWT after OIDC.** Once OIDC validates the user, the server issues its own JWT (8h TTL). The JWT is the session — any instance can validate it statelessly. OIDC tokens are NOT used for ongoing session management.
5. **API keys per agent, not global.** A user generates one key per agent. Compromised keys are individually revocable. Per-agent usage visible in audit logs.
6. **Keys hashed at rest.** Same protection as passwords would be. A database leak does not expose working credentials.
7. **Roles decide, and every credential has exactly one.** A session holds its membership's role; every key its OWN role (§7.5) — an `mcp` key the member role chosen at creation under the subset rule, an API or server key its transport role. There are no scopes (#215 slice (b)).
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
│ 4. Server resolves the user the key acts as + its role        │
│ 5. Request authenticated                                       │
└──────────────────────────────────────────────────────────────┘
```

Both paths resolve to the same internal principal:
```kotlin
data class AuthenticatedPrincipal(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val authMethod: AuthMethod,       // OIDC, API_KEY or PROMOTION
    val keyId: String?,               // present when a key authenticated
    val workspace: WorkspaceContext?, // the resolved role in the active / pinned workspace
    val keyRole: KeyRole?,            // every key's own role (keys v2 A13; §7.5)
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
1. Server extracts `email`, `name`, `sub`, `picture` from the OIDC ID token. The email is **normalized to lowercase** before every lookup and store — provider case differences must not fork one human into two rows (or mint a second bootstrap admin, §4.4). If the ID token carries `email_verified: false`, the login is **rejected** (`auth.login.oidc_error`, audited) — an unverified self-registered account at a provider must never reach the email-keyed linking step below, or it takes over the existing account with that email. A provider **omitting** the claim is now also treated as NOT vouching for the address (2026-09-21, #187 — fail closed): the login is rejected the same way. An IdP that never emits the claim must be trusted explicitly, per provider, with `datapipelines.auth.oidc.providers[].trust-email-without-verified-claim: true` (§5.1); each acceptance under the knob is audited (`auth.login.email_verified_assumed`). A claim that is present and `false` is refused whatever the knob says.
2. Checks if a user with that `email` already exists.
   - **Yes:** the stored identity is compared with the incoming one (2026-09-21, #187 — an identity is linked ONCE, never silently re-linked):
     - the row is the `provider = 'bootstrap'` placeholder — this login **completes** it (the §4.4 pre-provisioned admin's first sign-in); `provider`, `provider_subject`, `last_login_at`, `profile_picture_url` and `display_name` update (the `display_name` refresh is owner-ratified 2026-08-28: there is no profile-edit feature, so a stored name has no user-chosen referent to protect, and freezing it would leave an IdP rename unrepresentable; revisit if a profile surface ever ships);
     - the stored `(provider, provider_subject)` equals the incoming pair — the same person signing in again; the same refresh runs;
     - anything else — a different provider, a different subject under the same provider, or a `local`/`system` row — the login is **refused**: nothing is updated, the event is `auth.login.identity_mismatch` (naming the two providers, never the subjects), and the redirect is `/login?error=identity_mismatch` ("This email is already linked to a different sign-in identity. Ask an administrator to reset it."). Re-linking an account is the super admin's explicit **identity reset** on the admin users screen (`user.identity_reset`, §7.6, audited `auth.user.identity_reset`), which returns the row to the bootstrap placeholder so the NEXT sign-in with that email claims it.
     Grant-wise this step changes nothing: `is_admin` is decided at row creation and nowhere else (§4.4).
   - **No:** Creates a new user record. Default `is_active: true`, `is_admin: false`.
   2a. **Materialises every pending invitation for that email** (§4.6) into real
       memberships, with their roles, in the same act — and DELETES the invitations. The
       materialise runs in `WorkspaceService.workspaceForLogin` (step 4), the one resolution
       both credential paths reach, so it happens before any workspace is stamped: the
       first login lands an invited user in the invited workspace with the invited role,
       and the `demo` join below never fires for them (D-R11 — the invited workspace
       REPLACES the default, it is not added beside it). Each materialisation is audited
       (`workspace.invitation_materialised`) with the inviter.
3. Checks the email domain allowlist (if configured — see §4.3).
   - Domain not allowlisted: reject login with `auth.login.domain_not_allowed`.
4. **Workspace resolution (§5.1, §12):** determines the active workspace the JWT stamps
   — the user's **last-used** workspace when it still resolves to a live, ACTIVE membership,
   else their **first active membership**, else — this is the D-R11 rule — a fresh **viewer
   membership of `demo`**, the one workspace the product ships (created by `DemoWorkspaceSeeder`
   at first boot, with the example content, and never recreated once deactivated — O-3). The demo join fires only for a
   user with NO membership at all, so somebody removed from `demo` on purpose is not re-added
   by their next login. It lives in `WorkspaceService.workspaceForLogin`, shared by both
   credential paths: the owner's rule is about logging in, not about which provider did it.

   A user still with nothing selectable — `demo` deactivated, or never seeded — stamps
   nothing. They authenticate fine and every workspace-scoped operation is refused; round 2
   draws the "no workspace" page.

   **The provisioning modes are gone** (D-R11). `auto-per-user` (a personal workspace per
   first login), `self-serve` (anyone creates) and `closed` (admin only) were removed with
   `open-join` in RBAC round 1, and both config keys are refused BY NAME at startup
   ([Configuration §3.17](configuration.md#317-workspaces)). Workspaces are created by super
   admins. Existing personal workspaces stay as ordinary workspaces, with their sole member as
   the workspace admin (D-R14).
5. Issues internal JWT (stamping `active_workspace` when step 4 resolved one), sets cookie, redirects to `/`.

**Subsequent logins:** Same flow — user record updated, JWT reissued.

**Account deactivation:** Admin marks user `is_active: false` via UI. Subsequent OIDC logins are rejected with `auth.login.user_inactive`. Existing sessions and API keys stop working within the liveness-cache TTL (~60s): every authenticated request re-checks `is_active` through the cache (`PrincipalLiveness`, [§11A.3](#11a3-deactivation)), so a deactivated user's JWT and keys are dead within a minute, not at the 8h JWT expiry — refused with `auth.principal_deactivated` (401; a page navigation lands on `/login?error=inactive`). Nothing is revoked: reactivation restores them. API keys additionally remain individually revocable.

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

### 4.5 The system service account (R7)

`pipeline_versions.created_by` and `pipeline_executions.triggered_by` are `NOT NULL REFERENCES users(id)`. Some writes the system makes belong to **no human**: a version promoted from another deployment (authenticated by a shared server key, not a principal — [Versioning §10.6](versioning.md#106-the-promotion-peer-credential--a-shared-server-key-ratified-2026-09-01)), a retention sweep, a stale-execution reap. Those writes still need an actor row.

One row serves all of them, provisioned at first boot through the same `createUser` path §4.2 and §4.4 use, and read back through the **one** well-known lookup `UserService.systemActor()` — so promotion, scheduled jobs and any future automated write stamp the SAME actor and nobody mints a second.

| Column | Value | Why |
|---|---|---|
| `provider` | `system` | Reserved at startup validation ([Configuration §7](configuration.md#7-config-validation)) exactly as `bootstrap` and `local` are: an OIDC provider named `system` could link an external identity onto this row through §4.2's linking step |
| `provider_subject` | `system` | A fixed sentinel, never a real subject claim |
| `email` | `system@system.invalid` | RFC 2606 reserves the `.invalid` TLD as permanently unresolvable — no mail-based flow can reach it. Deliberately not under a `datapipelines.` domain: that would read as a configuration key to the docs audit, and an address is not one |
| `display_name` | `System` | What history and the audit trail render |
| `is_admin` | `FALSE` | It is an actor, not an authority. Every path that stamps it already knows the workspace it is writing into; nothing here needs a membership bypass |

**Login is disabled by construction, not by a flag.** Three independent facts each make it impossible: no OIDC provider may be named `system`, so no external identity can link to the row; the address cannot resolve; and the local-password paths refuse it — `createLocalUser` at the reserved address answers `EmailTaken` (the row already holds it), and `resetPassword` on a non-human row answers "no such user". Since #215 its row also carries `users.kind = 'system'`, and every login, linking and user-administration path refuses a non-human row by that ONE predicate (§4.7). There is nothing to disable because there is no credential to hold.

**Idempotent.** A restart returns the existing row untouched — no re-grant, no identity rewrite, no `updated_at` bump, the same contract §4.4's pre-provisioning states. Two replicas racing a fresh database settle it by catch-and-reread, exactly as the bootstrap actor does (ARCH-AUDIT M5).

It is deliberately **not** a credential. It holds no API key and no password, and nothing authenticates *as* it; it is the name history writes down when the writer was the system itself. The one exception is the promotion receiver's DEPRECATED config-value credential ([Versioning §10.6](versioning.md#106-the-promotion-peer-credential--a-shared-server-key-ratified-2026-09-01)): a shared secret with no row to attach an identity to, so a push it opens acts as this account (#215 B6). A STORED server key acts as its own identity instead (§4.7, §7.7).

**It is the identity schedules fire under (#9, R2).** A scheduled launch is built IN-PROCESS as an `AuthenticatedPrincipal` for this row — `AuthMethod.SYSTEM`, a workspace context for the schedule's workspace marked as the system actor's (`SystemActorPrincipals.forWorkspace`) — never by a filter, and no request can carry it (`WorkspaceResolutionFilter` refuses one that did). Its permission questions go to `PermissionResolver`'s **system arm** (`holdsAsSystemActor`), which answers a FIXED set in any workspace — `pipeline.execute`, `pipeline.read`, `execution.read` (the launch, the pinned version's read, the reconciler's execution read) — and nothing else: no instance permission, no role, no key. The set is code (`RolePermissions.SYSTEM_ACTOR`), not a §7.6 column, so the role walk never sees it; `SystemActorPrincipalTest` pins it against a literal and proves a widened resolver is caught. A scheduled execution records `executed_by` = this row and `triggered_via = SCHEDULE`; the schedule and a Run now's person are on the scheduler's own `schedule_runs` row ([scheduler design revision](superpowers/specs/2026-09-22-scheduler-design-revision.md) §4).

### 4.6 Invitations

The owner's scenario (2026-09-10): "Admin created a workspace and wants to add employees to
that… They will have two options: 1. create user/password, 2. company SSO." Today a member is
added through `POST /workspaces/{name}/members` (§17.7), which requires the `users` row to
exist — and an SSO user's row is created only at their FIRST login (§4.2). So a workspace
admin could not add `bob@company.com` before Bob had ever signed in. The bridge is an
**invitation keyed by email** (metadata-db §4.17) that the login path materialises.

The rules, each load-bearing:

1. **An invitation exists only while no `users` row holds that email.** Inviting an email
   that has a row makes the person a member AT ONCE (the ordinary member-added path). There
   is no invitation "pending for an existing user", and nothing pretends a person exists
   before they do: the invitation references an EMAIL; `workspace_members.user_id` stays
   `NOT NULL REFERENCES users(id)`.
2. **Invite-time refusals.** The email is normalized lowercase before every lookup and store
   (the §4.2 rule — `Bob@Company.com` and `bob@company.com` are one invitee). An email the
   §4.3 domain allowlist would refuse AT LOGIN is refused at INVITE time with the SAME code
   the login would use (`auth.login.domain_not_allowed`, at a 400 here — the caller's request
   is the defect): an invitation that could never be honoured is a trap. Inviting into a
   DEACTIVATED workspace is refused (`workspace.inactive`, 404) — a super admin can see the
   greyed workspace, and this is the verb telling them the same thing every other member verb
   would.
3. **The latest admin decision wins.** Re-inviting the same email UPSERTS the row: the role
   (and inviter, and date) is replaced, and the upsert is audited (`workspace.member_invited`)
   every time it fires — the row keeps no history; the audit trail does. The role is one of
   the four [`WorkspaceRole`](enums.md#8c-workspacerole--the-one-role-a-membership-holds) values,
   enforced by the same kind of database CHECK the members table states (D20).
4. **Materialisation at login.** When a user row comes into existence (§4.2's first login, or
   the admin's local-account creation — the two creation paths), every pending invitation for
   that email becomes a membership with its invited role, and the invitations are deleted,
   in one atomic statement. The `demo`-viewer default (§4.2 step 4) is applied ONLY when no
   invitation materialised — the invited workspace replaces the demo default, it is not added
   beside it (D-R11) — and `active_workspace` stamps the FIRST-INVITED workspace: the
   materialised memberships enter the membership ordering at their invitation dates
   (`joined_at = invited_at`), so "first membership" is the admin's first decision, not an
   alphabetical accident of same-transaction timestamps.
5. **Deactivated workspaces wait.** An invitation into a deactivated workspace does not
   materialise at login — the materialise predicate requires the workspace to be active —
   and is not lost either: reactivation makes it live at the next login. The row can be
   revoked meanwhile by a super admin, which is exactly the tidying a super admin inside a
   greyed workspace is for.
6. **Revocation and visibility.** `DELETE /workspaces/{name}/invitations/{email}` revokes
   (workspace admin or super admin, `workspace.members.manage`); a missing invitation is
   `workspace.invitation.not_found` (404). The members listing (§17.6) returns pending
   invitations in a SEPARATE `invitations[]` array, never mixed into `members[]` — a client
   that counts members must not count ghosts.

**No expiry in v1, and no email is ever sent** — the product has no SMTP (§5A); an invitation
is a row, not a message, and it is revocable like any other membership. There is no
self-service join: invitations are created by a workspace admin or a super admin, through the
same permission gate as every other member verb (`workspace.members.manage`).

**Local accounts need no invitation** (§5A.1): the Admin → Users form's optional "also add to
workspace, with a role" writes the user row and the membership in one act, because the row exists
by the time the membership is written — rule 1 doing the work directly.

---


### 4.7 Key identities

Since #215 (the [permissions and keys record](superpowers/specs/2026-09-23-permissions-and-keys-design.md) §3.3, PK5, ratified 2026-09-23) an `endpoint` key and a `server` key each act as **their own non-login identity** — a `users` row with `kind = 'service'` — never as the person who created them. The MCP (`user`) key is the one exception: it acts as its member (§7.5).

`users.kind` is `human` | `service` | `system` (V34; [`UserKind`](enums.md#8-userkind--what-a-users-row-is)). V34 set `system` on the System row (§4.5) and `human` on every other; one identity per existing endpoint/server key was created in the same migration (PK9).

The identity is built exactly like the System account, so login is impossible by construction:

| Column | Value | Why |
|---|---|---|
| `kind` | `service` | The ONE predicate every login, linking and administration path refuses (below) |
| `provider` | `key` | Reserved at startup ([Configuration §7](configuration.md#7-config-validation)) exactly as `system`, `local` and `bootstrap` are: an OIDC provider named `key` could otherwise link an external identity onto the row |
| `provider_subject` | the key id | A key's identity is its key's, one-to-one |
| `email` | `<key id>@keys.invalid` | RFC 2606's unresolvable `.invalid` — no mail-based flow can reach it, and `createLocalUser` refuses the domain |
| `display_name` | the key's name | Keys have no rename (record A6), so the name history shows is the name the key was created with |
| `is_admin` | `FALSE` | No key is ever a super admin (B1, §7.5) |
| password | none | Hashless, and the local-credential read returns PEOPLE only |

- **Created with its key, in one transaction** (B4): `ApiKeyService.issue` provisions the identity and inserts the key under `@Transactional("metadataTransactionManager")` — a half-created pair cannot exist. The key row's `user_id` is the identity; `api_keys.created_by` records the person who created it (the Keys page's "Created by").
- **Revoking the key deactivates the identity** (`is_active = false`, in the same transaction). History keeps the row, so runs and received versions still name it: history renders "<key name> (API key)".
- **Lifecycle is derived, never cascaded** (record A2). Deactivating or deleting a workspace revokes no key and deactivates no identity; the ONE liveness predicate (§11A.3) reads the identity, the key and the pinned workspace on every request, so reactivating the workspace restores exactly what was live before.
- **Managed only through its key** (record A3). The user-administration routes — REST (`/api/v1/auth/users/**`) and the admin screen's list, row actions, identity reset, password reset and mail status — look the row up BEFORE any mutation and answer an unknown-user 404 for a non-human row; the admin users page, members lists and invitations show `human` rows only; adding a non-human row to a workspace is refused as "no such person".
- **Attribution** (record C5, C4): `pipeline_executions.executed_by`, `audit_log.user_id` and every `created_by` written on the key's behalf name the identity. The per-user concurrency limit (`ExecutionSlots`, keyed on `executed_by`) is therefore a per-KEY limit. Runs a key served before V34 keep their original attribution (the key's creator); the serve audit still pairs those with the key (§7.7).

**One predicate: a person is `kind = 'human'`.** It guards OIDC linking (a `service` or `system` row is never claimable, whatever its provider says — the kind is judged before the provider), the identity reset (which refuses a non-human row, so the `bootstrap` flip can never make one claimable — this closed a pre-existing hole for the System row), the local-credential read, the password reset, membership and invitation materialisation, the session filter (a token naming a non-human row is `auth.session.invalid`) and every user-administration route.

## 5. OIDC Login Flow

### 5.1 Provider configuration (generic)

OIDC providers are configured as a **list** in the deployment config. Each provider requires only three values — `client-id`, `client-secret`, and `issuer-uri`. The issuer URI triggers OIDC discovery (`/.well-known/openid-configuration`), which auto-configures all authorization, token, userinfo, and JWKS endpoints.

Each provider takes one optional trust knob — `trust-email-without-verified-claim` (default `false`, #187): set it `true` ONLY for an IdP that never emits the `email_verified` claim, so a missing claim is accepted as verified (audited `auth.login.email_verified_assumed`). A claim that is present and `false` is refused regardless.

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
          # trust-email-without-verified-claim: true   # ONLY for an IdP that never emits the claim (§4.2, #187)

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

**`SameSite=Lax`, and it stays Lax (T33, 096 §F).** Strict is the instinctive choice here and
it does not work: the post-login landing arrives over the cross-site redirect chain from the
identity provider, where a Strict cookie is withheld — and a browser RELOAD of that landing
re-uses the cross-site initiator, so the user never becomes logged in and the failure looks
like "login is broken", not like a cookie attribute. Observed live 2026-08-28. Lax still
withholds the cookie on cross-site POSTs; the control for state change is the `dp_csrf`
double-submit token (§8.4), which does not depend on SameSite at all. This paragraph exists
because the KDoc on `OidcSuccessHandler` and §8.4 both said Strict for three rounds while the
code said Lax, and a text that leans on a defence the code does not provide invites someone
to "fix" the code to match it.

**The create branch tells sys-ops (137, §5A.8).** `UserService.findOrCreateByEmail` answers `Provisioned(user, created)`, and when `created` is true — this callback's insert won the row — the handler hands a "New user" notice to `MailNotices` after the workspace resolution (so the notice can name where the account landed): `created by: self-service via <registration id>`, never a password. A returning user's login sends nothing; the loser of a two-replica first-login race reports `created = false` and sends nothing either — the winner's callback already did. With mail unconfigured the notice is one INFO line.

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

**Admin operations** on the user-administration screen (§7.6 `user.manage`): **create a local user** (a random one-time password, `must_change_password = TRUE`, audited `auth.user.created` — **emailed to the user when mail is configured, shown to the admin exactly once when it is not** (§5A.8); there is no self-service "forgot password" flow, so an admin-driven reset is the whole account-recovery story), **reset a password** (new one-time credential, forces the change, clears the lockout — the unlock path for a sprayed-out account; mailed the same way), **disable local access** (the hash is cleared and the account is OIDC-only again), and **unlock** (clears the lockout without touching the credential).

### 5A.2 Seeding the first admin

A fresh local-accounts deployment has the chicken-and-egg §4.4 solves for OIDC: no admin exists to create the first account. Config seeds the **first admin only, never ordinary users** — passwords are not a config medium (config is plaintext in `docker compose config`, `docker inspect`, image layers and pasted issue reports, and offers no rotation, no revocation and no self-service). Every other account's credential lives hashed in the database, created by an admin from the user-administration screen.

- `datapipelines.auth.local.bootstrap-password-hash` — the preferred form: a **pre-computed Argon2id hash**, produced by `./gradlew :modules:auth:hashPassword` (prompts, or reads `DATAPIPELINES_SEED_PASSWORD`; never take the password as a shell argument — it would leak into shell history and the process table). The plaintext never has to sit in a config file.
- `datapipelines.auth.local.bootstrap-password` — the plaintext alternative, accepted because a zero-setup demo should be one command. It is never logged and never audited.

Both forms land on the `bootstrap-admin-email` account (created through the single §4.4 creation path, so the admin grant and its audit event fire exactly as for OIDC) and **always** seed with `must_change_password = TRUE`: the app refuses to proceed to any other screen until the seeded credential is replaced (§5A.4). Seeding is **create-if-absent and idempotent** — a restart never resets a changed password, and an account that already exists is left untouched. And the seeded credential cannot survive silently: every startup where the bootstrap-admin account still has `must_change_password = TRUE` logs a WARN (`event=auth.local.one_time_credential_pending`). Startup refuses the ambiguous shapes ([Configuration §7](configuration.md#7-config-validation)): both forms set, a seed without `local.enabled`, or a seed without `bootstrap-admin-email` — each naming both keys.

**The seed fires ONCE, at row creation, so `bootstrap-admin-email` is a before-first-boot decision.** Changing it afterwards creates no second administrator and moves no credential — the account that exists is the account that works. Every subsequent startup says so, as a WARN, and changes nothing:

```
event=auth.local.bootstrap_mismatch configured=you@example.com seeded=admin@local.test
```

Sign in as the seeded address and add or reset the other from **Admin → Users**, or start again from an empty database. `./app.sh --scaffold` exists for exactly this: it writes `deploy/secrets.env` and stops, so the address can be set before the first `--start`. Both `./app.sh --start` and `./app.sh --status` read the seeded row out of the database and print the login that exists rather than the one the file names.

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

**Only a password-opened session is gated.** The session JWT carries an `amr` claim (`pwd` \| `oidc`, RFC 8176) saying which credential opened it, and `ForcedPasswordChangeInterceptor` applies the gate to `pwd` sessions only: a user whose row was seeded with a local one-time password but who signs in through an identity provider presented no password and is not asked to change one. A session token minted before the claim existed is treated as `pwd` until it expires. (2026-09-06: the owner's Google sign-in was bounced to the change screen by the seeded local credential.)

On a successful change **through the gate** (the user's `must_change_password` was TRUE), the response carries `HX-Redirect: /dashboard` so the browser leaves the change screen; a voluntary change from Settings stays on the page with a toast (fixed 2026-09-05 — the forced flow used to leave the form on screen with a toast saying "you can continue"). The change itself (self-service, `POST /partials/account/password`) verifies the **current** password first — a hijacked session must not be able to rotate the credential — enforces the §5A.5 floor, clears `must_change_password`, and audits `auth.password.changed`.

### 5A.5 Enumeration resistance and the password policy

Unknown email, an OIDC-only account, and a wrong password are the **same outcome**: the same `Invalid email or password.` banner, the same redirect, the same HTTP status, and — just as important — the **same cost**. The no-row path still runs one Argon2 verification against a dummy hash, so response timing cannot tell "no such account" from "wrong password". Failure audit events carry no credential material.

Passwords are compared only through `SecretHasher.verify` (constant-time-ish at the hash layer). The policy floor for user-chosen passwords (self-service change) is **12 characters minimum** and 128 maximum: length is the factor that resists offline cracking (NIST 800-63B), composition rules push users to predictable patterns, and the maximum bounds Argon2 input cost. Seeded/admin-generated one-time passwords are random and always force a change at first login, so the floor does not apply to them.

### 5A.6 The login flow

`POST /login` (form fields `email`, `password`, plus the `_csrf` double-submit token — Spring's field name; the COOKIE is `dp_csrf` and the header `DP-CSRF-Token`, §8.4) is public (§8.3) and metered by the same per-IP `LoginRateLimitFilter` as the OIDC paths (`/login` prefix). It is deliberately **not** under a scope-governed path: it is the authentication ceremony itself, so no principal exists for the ScopeInterceptor to check yet — exactly like the OIDC callback it mirrors.

On success the flow converges with OIDC: the same `JwtService.issue` mints the same `dp_session` cookie, the same §4.2-step-4 workspace resolution runs (`workspaceForLogin`: last-used, else first active membership, else the D-R11 `demo` join — the pre-113 `auto-per-user` clause no longer exists), the same `auth.login.success` audit event is written (with `provider: "local"` in the details), and `is_active` is re-checked exactly as on the OIDC path (a deactivated account with the correct password gets the `inactive` banner — safe to reveal because the caller just proved the password). The `must_change_password` flag does not block the login; it engages the §5A.4 gate after it.

### 5A.7 Credential minting is session-only

Any operation that **mints or rotates a usable interactive credential** refuses an API-key principal with `auth.session.required` (§13.7), regardless of role. The set is exactly:

| Operation | Surface |
|---|---|
| Create local user | `POST /partials/admin/users` |
| Reset a user's password | `PATCH /partials/admin/users/{id}/reset-password` |
| Disable local access | `PATCH /partials/admin/users/{id}/disable-local` |
| Unlock an account | `PATCH /partials/admin/users/{id}/unlock` |
| Change own password | `POST /partials/account/password` |

**Why the role matrix cannot express this.** A permission test sees a role, not a credential, so it sees a `dpk_` key and a browser session with the same role as one principal. (Since #215 B2 no key reaches these partials at all — the MCP key is confined to `/mcp` — so this rule is now the second line.) `ApiKeyFilter` applies no path test and `ApiKeyCredentialMatcher` makes key requests CSRF-exempt, so an admin-scoped key reaches these partials with a single header. It could then create a local admin, read the one-time password out of the response body, and sign in — trading a revocable, workspace-pinned, non-interactive credential for a `dp_session` that is **not** pinned and that **outlives revocation of the key that created it**. That defeats the ~60s revocation contract in §8, which is the entire answer to a leaked agent key (§2, principle 5).

The self-service change is the same shape one floor down: `profile.password` is every role's, so without this rule a leaked key could guess its owner's password and, on a hit, rotate it into a full takeover.

**Deliberately excluded:** `activate`, `deactivate`, `promote`, `demote`. These administer users without ever emitting a credential, and are already ratified for keys through the documented `/api/v1/auth/users` REST surface (§7.6 `user.manage`). The line this draws is credential-minting, not privilege.

**Second layer.** Because a hijacked *session* is still a threat, `LocalPasswordService.changeOwn` also consults the §5A.3 lockout before any Argon2 work, counts failures on the same counter as `POST /login`, and audits them (`auth.password.change_failed`, `auth.password.change_locked`). Before this it had none of the three, making it an unmetered, unaudited guessing oracle beside a fully-defended login path.

### 5A.8 Mail: the welcome mail and the new-user notice

Two notices, owned here because they are outbound adapters of this module's own flows (137, the owner's 2026-09-14 rulings; keys in [Configuration §3.27](configuration.md#327-mail)):

| Kind | To | When | Carries |
|---|---|---|---|
| `welcome` | the user | an admin creates a local account (§5A.1) | subject *Your datapipelines account*: the login URL (built from `datapipelines.auth.base-url`, never a request's origin — §5.2), the login (the email), the **one-time password**, one sentence that the first login asks for a new password (§5A.4), and the reply-to as the human to write to |
| `password_reset` | the user | an admin resets a password (§5A.1) | the same family, subject *Your datapipelines password was reset* — an admin resetting a password has the same "must message the user" problem as creating one, so it is mailed the same way |
| `new_user` | `datapipelines.mail.ops-to` (the sys-ops sink, a comma list) | a local account is created, OR a social login happens for the first time (the OIDC callback's create branch, §5.5) | subject *New user: \<email\> (\<provider\>)*: email, display name if one was given, provider (`local` or the registration id), who created it (the admin's email, or `self-service via <provider>`), the workspace it landed in (the one the admin asked for, or the one the first login resolved), the time, a link to the admin users screen. **Never a password** |

**Mail is enabled exactly when it is configured** — `host` and `from` both set; there is no flag ([Configuration §3.27](configuration.md#327-mail)). Any SMTP server (Postmark's SMTP endpoint is one); STARTTLS is required-not-opportunistic and the `hardened` posture refuses it off; the Postmark stream header is optional and rides every message when set. With mail off, every notice is one INFO line (`event=mail.skipped`, kind and recipient domain only — [Observability §3.4B](observability.md#34b-the-mail-events-137)) and nothing is claimed or audited.

**The mechanics** (`MailNotifier`, `MailSender`, `MailSendRepository` — `mail_sends`, [Metadata DB §4.19](metadata-db.md#419-mail_sends)):

1. **Rendered once**, Thymeleaf text + html parts from `mail-templates/`; the password is a template variable and nothing else in the product ever sees it again. It exists in exactly one place: the message body handed to the transport. Not in the audit row, not in a log line, not in the claim row — pinned by `MailNotifierIntegrationTest` (a positive control proves the password DID reach the transport, then audit, log capture and `row_to_json(mail_sends)` are grepped for it: zero hits).
2. **Claimed before it is sent**, on the request thread: one `mail_sends` row per message identity `(user, kind, act)` — `act` is the user's own id for `welcome` and `new_user` (once per user, ever) and a fresh id per `password_reset` (two resets are two credentials and two mails). `ON CONFLICT DO NOTHING`: a retry, a double-submit or a second instance finds the row and does not send. **The welcome mail carrying a password never goes twice.** The claim rides the caller's transaction when there is one, so a rolled-back creation claims nothing; it exists — `PENDING` — by the time the admin screen renders. The row proves an ATTEMPT, not a delivery, and is never cleaned up.
3. **Sent after commit, off the request thread**: registered as an `afterCommit` synchronization when a metadata transaction is open (today neither hook point runs in one — the create paths catch-and-reread a duplicate insert, which an aborted Postgres transaction would forbid), else handed to a bounded pool at once (two threads, a queue of 100, caller-runs on overflow — not discard, because a discarded welcome mail is a password nobody has). A failed send NEVER fails the request. The transport's timeouts are bounded (connect 10 s, read/write 30 s). A **connect** failure — the one class that proves nothing was delivered — is retried in place, bounded (3 attempts, 250 ms / 1 s backoff, each retry logged `mail.send_retry`; 158, #121); anything past connect (a read timeout after DATA, a 5xx, an authentication refusal) is terminal at once, because the server may hold the message and a password mail never risks a second copy.
4. **Recorded**: `sent_at` + the `Message-ID` on the claim row and `mail.sent` in the audit log; or `error` (the exception's class and message) on the row and `mail.failed` — `kind`, `to`, `act_id` and the id or the error in `details`, never the body ([Enums §15](enums.md#15-authauditevent--auth-audit-log-events)). A row still `PENDING` long after its claim is a send the process never got to (it died between commit and send): the admin screen shows it, and the admin's answer is a reset — never a silent retry of a password mail.

**What the admin screen shows** follows from the derivation ([UI §4.12](ui-screens.md#412-admin-user-management-admin-scope-only)): with mail on, create and reset show "Emailed to \<address\>" with the claim's outcome — sending (polled until terminal), sent, or failed with the error — and **never the password**; an admin should not have to message anyone, and two copies of a credential are one too many. With mail off they show the password as before.

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
    "active_workspace": "acme",
    "iat": 1691234567,
    "exp": 1691263367,
    "iss": "datapipelines"
  }
  ```
  `active_workspace` is present only when login resolved one (§4.2 step 4); it is the
  workspace *name*, the same value `DP-Workspace` carries (§5.6).
- **Signing secret:** `DATAPIPELINES_JWT_SECRET` env var (≥ 32 bytes random, base64). Required at startup.

**No authority rides the token.** What a session may do is its membership's role in the active workspace, resolved per request (§11A), plus the user row's `is_admin` re-read through the §11.4 cache — never a claim, so a demotion or a revoked super admin takes effect within one TTL rather than at token expiry. A `scopes` claim a pre-round-1 token may still carry is ignored (scopes were removed entirely in #215).

A browser session is therefore **the broadest credential in the product** — switchable across
every workspace the user belongs to (§5.6), where a key's workspace is pinned at issue and
immutable. There is no read-only session: the §7.6 matrix cannot express
"this principal is a browser" or "this principal is a key", so the four gates that need that
distinction are written outside it, by hand — `requireSessionAdmin` on the credential-minting
admin actions (§5A.7), `changeOwnPassword` refusing keys (§5A.4), `POST /workspace/switch`
(§5.6), and the published-endpoint surface refusing sessions (§7.7). Anything that must distinguish the two is a
fifth one, written outside the matrix too. (Since #215 B2 the MCP key reaches none of the four
surfaces, so each is also a second line behind the kind confinement, §7.7.)

### 6.2 Why not use OIDC tokens directly?

- **Decoupled TTL.** Our JWT has its own TTL (8h). OIDC access tokens have provider-specific TTLs (Google = 1h). Using OIDC tokens would require refreshing mid-session.
- **Our own authorization model.** What a session may do is our roles (§11A), resolved per request. OIDC tokens carry provider scopes which don't map to it.
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

                // Liveness re-check through the ONE predicate (§11A.3): cached (60s TTL,
                // same cache infra as §7.3) lookup of users.is_active. Deactivation kills
                // the session within ~1 min — `auth.principal_deactivated`.
                principalLiveness.require(userId, pin = null)

                val principal = AuthenticatedPrincipal(
                    userId = userId,
                    email = claims["email"] as String,
                    displayName = claims["name"] as String,
                    authMethod = AuthMethod.OIDC,
                    keyId = null
                )
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(principal, null, emptyList())
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
8. PrincipalLiveness (§11A.3), cached, 60s TTL: the users.is_active of the
    user the key ACTS AS — its own identity (§4.7) for every kind — and,
    for an MCP key only, of its CREATOR besides (A20: a deactivated
    person's MCP keys are refused until reactivation; endpoint and server
    keys stay creator-independent, PK2)
    → inactive is 401 auth.principal_deactivated; the pinned workspace's
    liveness → deactivated is 404 auth.key_workspace_inactive. A server key
    on the promotion route folds both into 401 auth.promotion.key_invalid.
9. Update last_used_at, last_used_ip (async).
10. Build the principal: userId = the user the key acts as — its own
    identity (§4.7) for every kind. Every key carries its KEY ROLE (§7.5):
    the MCP key the role CHOSEN at creation (A13), an endpoint or server
    key its transport role (`api_caller` / `promotion_receiver`). Nothing is
    read from any membership. No key principal is ever a super admin (B1).
```

In-memory cache (`datapipelines.auth.api-keys.cache-ttl-seconds`, default 60s) for recently-validated keys, user liveness and memberships, invalidated on revocation/deactivation/role change on the local instance and by TTL elsewhere — so a role change, a member removal, a key revocation or a deactivation takes effect **within the auth cache TTL, 60 s by default** (#215 B5, record amendment A9).

### 7.4 Issuance

Keys v2 (#233, A13–A15) — **every key is a robot member of one workspace, and the Keys page is the ONE creation path for every kind.** No key is minted at sign-in any more: the login mint (D16), the top-bar chip and `minted_at_login` are retired (V37). What each kind is and who may create it:

- **An `mcp` key is created by anyone holding `mcp_key.create`** (author, promoter, workspace admin; a super admin) — on the Keys page (`POST /partials/api-keys`) or over REST (`POST /api/v1/auth/api-keys`), naming the ROLE it should carry. The subset rule (§7.5, A14) decides which roles the creator may offer; a request for a role outside that set is refused with `auth.role_required`, `details.required` = the role, `details.held` = the creator's. `viewer` is never a key role (A15) and `super_admin` is not a value `api_keys.role` accepts (B1). A first login creates NO key (A15).
- **An `endpoint` key (shown in the UI as an "API key", D17) is created by a workspace admin or super admin** (`api_key.create`), with its endpoint associations in the same request or added later from the page. It acts as its own identity (§4.7) with the `api_caller` role.
- **A `server` key is minted — and revoked — by a super admin** (`server_key.create`, D18; `server_key.revoke` for the revocation). It acts as its own identity with the `promotion_receiver` role.

**Revocation** (A14/A17): a creator may revoke a key THEY created (`mcp_key.revoke_own`, author and above) — `DELETE /api/v1/auth/api-keys/{keyId}` or the page's Delete; a caller holding `api_key.revoke` revokes any key of the workspace through the same routes, a `server` key additionally needing `server_key.revoke`. Removing a member revokes EVERY live key they created in that workspace, of every kind, each audited with reason `member_removed` (A17/B6 — the created-by generalisation of #200); the members-row lever (reason `admin_revoked`) does the same without removing them. One edge, stated: a key whose creator no longer holds ANY membership of the key's workspace — an ex-super-admin's key, say — has no creator revoke-own to answer to (their permission is judged where they are a member); in that workspace it is revocable by the workspace admin's `api_key.revoke` alone. Revocation is the end state — **keys and identities are never hard-deleted by the application**; the retention sweep's purge (§11C) deletes a revoked key and its identity only once nothing references them (A17/B5).

**Names are unique per workspace among live keys** (A18): `(workspace_id, name)` is a partial unique index, so a replacement key needs a fresh name until the old one is revoked.

The show-once copy survives only as the V37-migrated login keys' sealed plaintext (§4.7): a key created on the page returns its plaintext in the create response exactly once (§7.4's original rule) and stores nothing copyable; a migrated key's unread copy is opened ONCE by its creator from the Keys page (`GET /partials/mcp-key/secret` — the open and the clear are ONE creator-scoped statement, `ApiKeyRepository.openAndClearSealedSecret`), and the fetch-metadata guard still refuses anything a browser marks cross-site or as a navigation (the §7.4 `Sec-Fetch` rule of #213). No NEW key is ever minted with a sealed copy (A15).

For every key created on demand, the guards run in the service, before anything is written: the creator must hold the KIND's create permission in the pinned workspace (`mcp_key.create` for `mcp` — the route's floor, since it is the LOWEST of the three; `api_key.create` for `endpoint`; `server_key.create` for `server`; gate 7); and **the subset rule** (A14, record O3 ruled) — a key's role must not hold a permission its creator lacks in that workspace. The identity and the key are then created in ONE transaction (§4.7). A request that still sends `scopes` is refused by name (scopes were removed, PK8); one that names NO kind is refused with `auth.key_kind_not_mintable` (keys v2: there is no default kind to fall back to). The workspace pin is unchanged: a key is **pinned to a workspace** (design §5.2) and the pin is the key's request context for its entire lifetime (§5.6). The HTTP surface for key management is defined in [REST API §16](rest-api.md#16-auth--user-admin-endpoints).

### 7.5 Key roles

**Scopes are gone** (#215 slice (b), record PK8), and since keys v2 (#233, A13) **every key carries a role of its own** — chosen at creation, held by its identity, judged by one column of §7.6. Nothing is derived from a membership at request time: no cap (PK4 retired), no freshness rule (C2 retired) — the member's later role changes do not flow to the key, and the key's authority changes only when someone creates a new key. What freshness remains is revocation's (§7.4): a creator's revoke-own (`mcp_key.revoke_own`), the workspace admin's `api_key.revoke`, member removal, and the members-row lever.

| Credential | Its role | Where it is judged |
|---|---|---|
| Session | Its membership's role in the active workspace, or a super admin's authority (§11A) | The §7.6 member columns |
| `mcp` key | A MEMBER role **chosen at creation**: `author`, `promoter` or `workspace_admin` — never `viewer` (A15), never a super-admin role (B1). The key's identity holds that role in the key's workspace exactly as a member does (A13); a `promoter`-role key is lensed like the member it mirrors (§11A.1) | The §7.6 `mcp:<role>` columns (the member columns), answered through the same `PermissionResolver` seam a session's decisions ask (#239) — the production seam reads the member column, so no answer changed |
| `endpoint` key | **`api_caller`** — `endpoint.serve` on the paths bound to it, and the result paging (§7.7, A16) of the executions it started | The §7.6 `api_caller` column |
| `server` key (stored, or the deprecated config value) | **`promotion_receiver`** — `promotion.inventory.read`, `promotion.push`, for any workspace (B6) | The §7.6 `promotion_receiver` column |

**The subset rule** (A14, record O3 ruled): a creator may give a key any role whose permission set — the one `RolePermissions` table, read off the §7.6 columns — is a subset of the creator's own permissions in that workspace. No ordinal ladder exists: promoter and author are not comparable and neither may mint the other; a workspace admin's column contains both, so an admin may mint any of the three; a super admin holds every permission, so any role. `RolePermissions.offerable` is the one predicate, consulted by the Keys page dialog, the REST route and the service.

Key roles are `snake_case` everywhere — storage, wire, the CHECK — and the UI shows a human label ("api caller"). A key's role is stored on its row (`api_keys.role`), and a database CHECK (V37) makes the kind/role families a fact: `mcp` ⇒ one of the three member roles (`role IS NULL` tolerated only on a revoked pre-v2 row), `endpoint` ⇒ `api_caller`, `server` ⇒ `promotion_receiver` — the transport arms spelled with `role IS NOT NULL`, because under SQL's three-valued logic the bare comparison would admit a NULL role.

**No key is ever a super admin** (B1). `superAdmin` is false on every key principal, and `super_admin` is not a value `api_keys.role` accepts, so no key resolves an instance permission, whoever minted it.

### 7.6 Operation Matrix — the permission catalog (authoritative)

This matrix is the ONLY place authorization requirements are defined. [REST API](rest-api.md), [MCP Server](mcp-server.md), and [UI Screens](ui-screens.md) reference it; they never assert anything locally. Since #215 slice (a) it is the **permission catalog** of the [permissions and keys record](superpowers/specs/2026-09-23-permissions-and-keys-design.md) §2 (ratified 2026-09-23, amended 2026-09-24): one row per `<functionality>.<permission>` — 65 on this base — and every REST handler, UI route and MCP tool declares exactly ONE of them (`@RequiredScope(Permission.X)` on a handler; the tool's catalog entry, `McpToolCatalog.Entry.permission`, since slice (b)). The thirty coarse operations of v2 (`READ_RESOURCES` … `MANAGE_DATASOURCE_GRANTS`) are retired; each maps onto the permissions its surfaces were spread over.

Since slice (b) there is **one** axis, the ROLE ([§7.5](#75-key-roles), [§11A](#11a-roles)):

- **The member roles** — the five columns `viewer` … `super_admin`; in code the ONE role table `RolePermissions`. For a session that is its own membership's role. For the MCP key it is the key's OWN role — chosen at creation, held by its identity (A13): no member's CURRENT role flows to it, so no demotion reaches it — nothing is re-read (PK4 and C2 are retired). A key whose role must change is revoked and replaced, and the freshness levers are all revocation's (§7.4): revoke-own, `api_key.revoke`, member removal, the members-row lever. Super admin is a property of the USER, held in every workspace (D7): ✓ on every row except the two **fenced** ones — and never on a key (B1).
- **The key roles** — the two columns `api_caller` and `promotion_receiver` (record §3.2): an `endpoint` or `server` key is judged by its key role's column and nothing else.

**Cell alphabet.** ✓ = the role holds the permission; ✗ = refused with `auth.role_required` (the MCP key is judged as its OWN key role — `details.held` names it; `auth.key_issuer_role_lost` remains only on the context-judged path, §9); **own** = held, and the read or cancel path additionally limits the caller to their OWN runs (D11: `executed_by = self`) unless they also hold `execution.read_all` / `execution.cancel_all`; **all** = held, every row of the workspace; **lens** = held, and the promoter LENS (178, §11A.1) narrows WHAT is returned to released objects newer than the promotion target's — a hidden object answers exactly as an absent one, and an unreadable target answers NOTHING (fail closed); **fenced** = no MEMBER role holds it, super admin included — only the `promotion_receiver` key role does, through the promotion server-key route family (§7.7). In the two key-role columns, **own** = the executions the key started (its identity's runs, §4.7) and **bound** = the published paths bound to the key (§7.7). Every ✓ / own / all / lens / bound cell is *allowed* to the role walk; ✗ and fenced are *refused*.

`ScopeMatrix.allowed(principal, permission, workspace)` answers it and is the only function `ScopeInterceptor` and the MCP dispatcher call; a handler or tool that declares no permission is refused with `auth.permission.undeclared`. A refusal's details name the catalog permission (`required`) and the ROLE it was judged as (`held`: `viewer` … `workspace_admin`, `super_admin`, a key role — informative only). Each row's Surfaces cell names every route in code font as the handler maps it (`VERB /path`, a path variable's regex dropped) and, after `MCP:`, every tool by wire name — the drift test, the reachability gate and the role walk read the placement from here, so a route or tool the table does not place, or places on a row other than the one it declares, fails the build. Four rows have no surface of their own — `execution.read_all`, `execution.cancel_all`, `server_key.create`, `server_key.revoke` — because a service asks for them inside another row's route (the own-only execution filters, the cancel paths, the key issuance and revocation services); the reachability gate counts that service check as the row's claim.

**The catalog — permissions and roles:** (record §2, #215; keys v2 #233 — 67 permissions; the scheduler #9 — 73)

| Permission | Surfaces | viewer | author | promoter | ws_admin | super_admin | api_caller | promotion_receiver | mcp:author | mcp:promoter | mcp:ws_admin |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `pipeline.read` | Read pipelines, their versions, exports and checks; the pipeline pages and partials; the dashboard and the search partial (every role's landing reads). REST/UI: `GET /api/v1/pipelines`, `GET /api/v1/pipelines/{id}`, `GET /api/v1/pipelines/{id}/export`, `GET /api/v1/pipelines/{id}/versions`, `GET /api/v1/pipelines/{id}/versions/{version}`, `GET /api/v1/pipelines/{id}/versions/{version}/checks`, `GET /dashboard`, `GET /partials/dashboard-stats`, `GET /partials/pipelines`, `GET /partials/pipelines/detail`, `GET /partials/pipelines/{id}/nodes/{nodeId}/sql`, `GET /partials/pipelines/{id}/runs`, `GET /partials/pipelines/{id}/usage`, `GET /partials/pipelines/{id}/versions/{version}/checks`, `GET /partials/search`, `GET /pipelines`. MCP: `pipelines_list`, `pipelines_get` | ✓ | ✓ | lens | ✓ | ✓ | ✗ | ✗ | ✓ | lens | ✓ |
| `pipeline.create` | Create a pipeline. REST/UI: `POST /api/v1/pipelines`. MCP: `pipelines_create` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `pipeline.update` | Write a pipeline's draft. REST/UI: `PUT /api/v1/pipelines/{id}`. MCP: `pipelines_update` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `pipeline.version.manage` | Discard a draft, discard / restore / purge a version, and the lifecycle dialogs for them. REST/UI: `POST /api/v1/pipelines/{id}/draft/discard`, `DELETE /api/v1/pipelines/{id}/versions/{version}`, `POST /api/v1/pipelines/{id}/versions/{version}/discard`, `POST /api/v1/pipelines/{id}/versions/{version}/restore`, `GET /partials/pipelines/{id}/lifecycle/discard`, `POST /partials/pipelines/{id}/lifecycle/discard`, `GET /partials/pipelines/{id}/lifecycle/purge`, `POST /partials/pipelines/{id}/lifecycle/purge`, `GET /partials/pipelines/{id}/lifecycle/restore`, `POST /partials/pipelines/{id}/lifecycle/restore` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `pipeline.delete` | Delete a pipeline (the editor's purge-entity dialog). REST/UI: `DELETE /api/v1/pipelines/{id}`, `GET /partials/pipelines/{id}/lifecycle/purge-entity`, `POST /partials/pipelines/{id}/lifecycle/purge-entity` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `pipeline.import` | Import a pipeline. REST/UI: `POST /api/v1/pipelines/import` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `pipeline.release` | Release a version (the release dialog; since 7e the response's `warnings` and the dialog's needs-review rows — a field on this release, no new route). REST/UI: `POST /api/v1/pipelines/{id}/release`, `GET /partials/pipelines/{id}/lifecycle/release`, `POST /partials/pipelines/{id}/lifecycle/release` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `pipeline.switch_version` | Switch the served version — the rollback lever. REST/UI: `POST /api/v1/pipelines/{id}/current`, `GET /partials/pipelines/{id}/lifecycle/switch`, `POST /partials/pipelines/{id}/lifecycle/switch` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `pipeline.execute` | Execute a pipeline; the pipeline editor page floors here (122). REST/UI: `POST /api/v1/pipelines/{id}/execute`, `GET /pipelines/{id}/editor`. MCP: `pipelines_execute` | ✓ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `pipeline.run_checks` | Run a version's release checks. REST/UI: `POST /api/v1/pipelines/{id}/versions/{version}/checks/run`, `POST /partials/pipelines/{id}/versions/{version}/checks/run`. MCP: `pipelines_run_checks` | ✓ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `pipeline.execute_node` | Execute one node against live data (returns rows). MCP: `pipelines_execute_node` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `template.read` | Read templates, their versions and runs; the template pages, the editor's source (since 7e: `needs_review` on every read and the list's `implements` filter — fields on these reads). REST/UI: `GET /api/v1/templates`, `GET /api/v1/templates/versions`, `GET /partials/templates`, `GET /partials/templates/editor/source`, `GET /partials/templates/runs`, `GET /partials/templates/transform-face`, `GET /partials/templates/versions`, `GET /templates`, `GET /templates/editor`. MCP: `templates_list`, `templates_get`, `templates_used_by` | ✓ | ✓ | lens | ✓ | ✓ | ✗ | ✗ | ✓ | lens | ✓ |
| `template.create` | Create a template (the create modal; a transform's `implements` since 7e — a field). REST/UI: `POST /api/v1/templates`, `POST /partials/templates`. MCP: `templates_create` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `template.update` | Write a template's draft (the editor's Edit; the transform face's Save draft, 7d; a transform's `implements`, on a draft or a released version, 7e — a field). REST/UI: `PUT /api/v1/templates`, `POST /partials/templates/editor/edit`, `POST /partials/templates/transform-face/save`. MCP: `templates_update` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `template.version.manage` | Discard a draft, discard / restore / purge a version, the lifecycle dialogs; purge a never-released draft over MCP. REST/UI: `POST /api/v1/templates/draft/discard`, `DELETE /api/v1/templates/version`, `POST /api/v1/templates/version/discard`, `POST /api/v1/templates/version/restore`, `GET /partials/templates/lifecycle/discard`, `POST /partials/templates/lifecycle/discard`, `GET /partials/templates/lifecycle/purge`, `POST /partials/templates/lifecycle/purge`, `GET /partials/templates/lifecycle/restore`, `POST /partials/templates/lifecycle/restore`. MCP: `templates_purge_draft` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `template.delete` | Delete a template (the purge-entity dialog). REST/UI: `DELETE /api/v1/templates`, `GET /partials/templates/lifecycle/purge-entity`, `POST /partials/templates/lifecycle/purge-entity` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `template.import` | Import a template. REST/UI: `POST /api/v1/templates/import` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `template.render` | Render a template (the editor preview). REST/UI: `POST /api/v1/templates/render`, `POST /partials/templates/render`. MCP: `templates_render` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `template.evaluate` | Evaluate a transform template over a caller-supplied input (7b, the render row's twin); the transform face's Run suite over its unsaved panes (7d). REST/UI: `POST /api/v1/templates/evaluate`, `POST /partials/templates/transform-face/run-suite`. MCP: `templates_evaluate` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `template.release` | Release a template version (the release dialog). REST/UI: `POST /api/v1/templates/release`, `GET /partials/templates/lifecycle/release`, `POST /partials/templates/lifecycle/release` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `template.switch_version` | Switch a template's served version. REST/UI: `POST /api/v1/templates/current` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `execution.read` | Execution metadata, the executions screens and the SSE replay, and — since #9 — the durable event record as JSON (`?format=json` on the events route) — own runs unless `execution.read_all` (D11), and every SCHEDULED run of the workspace (#9 R3: a scheduled run is its schedule's, visible to every role that reaches this row; the REST listing and the single-run reads, not yet the UI lists or MCP). REST/UI: `GET /api/v1/executions`, `GET /api/v1/executions/{id}`, `GET /api/v1/executions/{id}/events`, `GET /executions`, `GET /executions/{id}`, `GET /partials/executions`, `GET /partials/recent-executions`. MCP: `executions_list`, `executions_get` | own | own | ✗ | ✓ | ✓ | own | ✗ | own | ✗ | ✓ |
| `execution.result.read` | The result cursor — the same own-or-all filter. REST/UI: `GET /api/v1/executions/{id}/result`, `GET /partials/executions/{id}/result`. MCP: `executions_get_result` | own | own | ✗ | ✓ | ✓ | own | ✗ | own | ✗ | ✓ |
| `execution.read_all` | Lifts "own" on the two rows above — no surface of its own; the read paths ask for it | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `execution.cancel` | Cancel a running execution — own runs unless `execution.cancel_all`. REST/UI: `DELETE /api/v1/executions/{id}`, `DELETE /partials/executions/{id}/cancel`. MCP: `executions_cancel` | own | own | ✗ | ✓ | ✓ | ✗ | ✗ | own | ✗ | ✓ |
| `execution.cancel_all` | Lifts "own" on cancel — no surface of its own; the cancel paths ask for it | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `schedule.read` | Read schedules (#9, R8): the list, one schedule, a pattern's preview, a schedule's upcoming occurrences, its runs, one run with its trail. A promoter reads through the LENS — the schedules whose target pipeline it admits. REST/UI: `GET /api/v1/schedules`, `GET /api/v1/schedules/preview`, `GET /api/v1/schedules/{id}`, `GET /api/v1/schedules/{id}/runs`, `GET /api/v1/schedules/{id}/runs/{runId}`, `GET /api/v1/schedules/{id}/upcoming` | ✓ | ✓ | lens | ✓ | ✓ | ✗ | ✗ | ✓ | lens | ✓ |
| `schedule.create` | Create a schedule (#9, R8). REST/UI: `POST /api/v1/schedules` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `schedule.update` | Edit a schedule — timing, job, parameters, rename (#9, R8). REST/UI: `PUT /api/v1/schedules/{id}` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `schedule.pause` | Pause, resume and unblock a schedule — the operational controls (#9, R8). REST/UI: `POST /api/v1/schedules/{id}/pause`, `POST /api/v1/schedules/{id}/resume`, `POST /api/v1/schedules/{id}/unblock` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `schedule.delete` | Delete a schedule — soft; its runs stay (#9, R8). REST/UI: `DELETE /api/v1/schedules/{id}` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `schedule.run` | Run now — a manual run fired by the system identity, the person recorded (#9, L2). REST/UI: `POST /api/v1/schedules/{id}/run` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `datasource.read` | Datasource metadata, the datasource pages, the registered lake tables, the browse partials, the register form's pool fields. REST/UI: `GET /api/v1/datasources`, `GET /api/v1/datasources/{name}`, `GET /api/v1/datasources/{name}/lake-tables`, `GET /datasources`, `GET /datasources/{name}`, `GET /partials/datasources`, `GET /partials/datasources/pool-fields`, `GET /partials/datasources/{name}/lake-tables`, `GET /partials/datasources/{name}/tables`, `GET /partials/datasources/{name}/tables/{table}/columns`. MCP: `datasources_list`, `datasources_get`, `datasources_get_table_stats` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `datasource.introspect` | Schema introspection over a live connection — introspection is reading (ratified). REST/UI: `GET /api/v1/datasources/{name}/schemas`, `GET /api/v1/datasources/{name}/tables`, `GET /api/v1/datasources/{name}/tables/{table}/columns`. MCP: `datasources_get_schemas`, `datasources_get_tables`, `datasources_get_columns` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `datasource.test` | Test a datasource connection — follows execute (ratified). REST/UI: `POST /api/v1/datasources/{name}/test`, `POST /partials/datasources/{name}/test`. MCP: `datasources_test` | ✓ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `datasource.preview_rows` | Preview a table's rows (row data — author and above). MCP: `datasources_preview_rows` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `datasource.sql_probe` | Run a bounded read-only SQL probe (row data — author and above). MCP: `sql_probe` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `datasource.manage` | Register / update / delete datasources and their dialogs. The `member-datasources-enabled` gate, and the super-admin rule for an INSTANCE or in-process datasource (#186), stay in the service (`DatasourceWorkspaceRules`). REST/UI: `POST /api/v1/datasources`, `PUT /api/v1/datasources/{name}`, `DELETE /api/v1/datasources/{name}`, `POST /partials/datasources`, `POST /partials/datasources/{name}`, `GET /partials/datasources/{name}/delete`, `POST /partials/datasources/{name}/delete`, `GET /partials/datasources/{name}/edit` | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `datasource.grant` | Grant / revoke a datasource to a workspace, and read who holds it (D-R7). REST/UI: `GET /api/v1/datasources/{name}/grants`, `POST /api/v1/datasources/{name}/grants/{workspace}`, `DELETE /api/v1/datasources/{name}/grants/{workspace}`, `GET /partials/datasources/{name}/grants`, `POST /partials/datasources/{name}/grants`, `POST /partials/datasources/{name}/grants/{workspace}/remove` | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `lake_table.manage` | Register / import / unregister lake tables (the dp-lake catalog). REST/UI: `POST /api/v1/datasources/{name}/tables`, `POST /api/v1/datasources/{name}/tables/import`, `DELETE /api/v1/datasources/{name}/tables/{namespace}/{table}`. MCP: `lake_tables_register`, `lake_tables_import`, `lake_tables_unregister` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `endpoint.read` | Read published endpoints; the API console page. REST/UI: `GET /api-console`, `GET /api/v1/endpoints`. MCP: `endpoints_list`, `endpoints_get` | ✓ | ✓ | lens | ✓ | ✓ | ✗ | ✗ | ✓ | lens | ✓ |
| `endpoint.publish` | Publish a released pipeline at a URL. REST/UI: `POST /api/v1/endpoints`. MCP: `endpoints_create` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `endpoint.unpublish` | Unpublish. REST/UI: `DELETE /api/v1/endpoints`. MCP: `endpoints_delete` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `endpoint.serve` | Serve a published endpoint — the floor only; the path binding is the gate (§7.7), and an `endpoint`-kind key bypasses both axes. REST/UI: `GET /api/{category}/**`, `POST /api/{category}/**`, `PUT /api/{category}/**`, `PATCH /api/{category}/**`, `DELETE /api/{category}/**` | ✓ | ✓ | ✓ | ✓ | ✓ | bound | ✗ | ✓ | ✓ | ✓ |
| `semantic.read` | Read learned facts; the datasource facts dialog (since 7e the read carries each WORKSPACE fact's `implemented_by`, narrowed by the reader's `template.read` lens — a field on `semantics_list` and the datasource listings; the dialog does not render it). REST/UI: `GET /partials/datasources/{name}/facts`. MCP: `semantics_list` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `semantic.record` | Record a learned fact (D-S8); a DATASOURCE-scope record also needs the datasource granted here. MCP: `semantics_record` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `semantic.retire` | Retire a learned fact; retiring a DATASOURCE fact another workspace established additionally needs `datasource.manage`, asked in the service. MCP: `semantics_retire` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ |
| `calculator.read` | The calculator catalog — a property of the build. MCP: `calculators_list`, `calculators_get` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `docs.read` | The shipped skill docs — a property of the build. MCP: `docs_list`, `docs_get` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `promotion.read` | The promotion page (owner rule 13); the promote verb on it renders by `promotion.promote`. REST/UI: `GET /promotion` | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `promotion.promote` | Promote to the higher environment — the sending side (D5). REST/UI: `POST /promotion/promote` | ✗ | ✗ | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ | ✓ | ✓ |
| `promotion.inventory.read` | The RECEIVING side's inventory — the promotion server-key route family only (§7.7). REST/UI: `GET /api/v1/promotion/inventory` | fenced | fenced | fenced | fenced | fenced | ✗ | ✓ | ✗ | ✗ | ✗ |
| `promotion.push` | The RECEIVING side's push — the promotion server-key route family only (§7.7). REST/UI: `POST /api/v1/promotion/push` | fenced | fenced | fenced | fenced | fenced | ✗ | ✓ | ✗ | ✗ | ✗ |
| `mcp_key.own` | See the keys YOU created and read one's sealed copy (once, #213 — the V37-migrated login keys); the Keys page and the key settings page (keys v2 A15: the login mint is retired, creation is `mcp_key.create`). REST/UI: `GET /api/v1/auth/api-keys`, `GET /api-keys`, `GET /partials/mcp-key/secret`, `GET /settings/api-keys` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `mcp_key.create` | Create an `mcp` key — a robot member with the role the SUBSET RULE allows the creator (keys v2 A13/A14); the Keys page dialog and REST, one creation path for every kind (A15). REST/UI: `POST /api/v1/auth/api-keys`, `POST /partials/api-keys` | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `mcp_key.revoke_own` | Revoke a key YOU created — the service checks `created_by`; any key of the workspace additionally needs `api_key.revoke`, a `server` key `server_key.revoke` (keys v2 A14). REST/UI: `DELETE /api/v1/auth/api-keys/{keyId}`, `DELETE /partials/api-keys/{keyId}` | ✗ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `api_key.read` | The Keys page's WHOLE workspace table — endpoint, server and every `mcp` key — beside `mcp_key.own`'s personal view; asked by the page controller (keys v2). No route of its own | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `api_key.create` | Create an `endpoint` key — asked by the issuance service on `mcp_key.create`'s routes, whose floor is the LOWEST create permission (keys v2 A14); a `server` key needs `server_key.create` instead. No route of its own | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `api_key.revoke` | Revoke ANY key of the workspace — asked by the revocation service on `mcp_key.revoke_own`'s routes; a `server` key additionally needs `server_key.revoke` (keys v2 A14). No route of its own | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `api_key.bind` | Associate an `endpoint` key with published paths (D17). REST/UI: `POST /api/v1/endpoints/bindings`, `DELETE /api/v1/endpoints/bindings`, `POST /partials/api-keys/{keyId}/bindings` | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `server_key.create` | Mint a `server` key (D18) — asked by the issuance service on `api_key.create`'s routes | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `server_key.revoke` | Revoke a `server` key — asked by the revocation service on `api_key.revoke`'s route | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `workspace.switch` | Switch the active workspace, and list your own memberships (D13, D14). REST/UI: `GET /api/v1/workspaces`, `POST /workspace/switch` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `workspace.read` | The workspaces page, one workspace, its members (D13). A SESSION with no reachable workspace may still call it (§11A.1). REST/UI: `GET /api/v1/workspaces/{name}`, `GET /api/v1/workspaces/{name}/members`, `GET /workspaces` | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `workspace.update` | Rename a workspace's display name. REST/UI: `PUT /api/v1/workspaces/{name}`, `POST /workspaces/{name}/display-name` | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `workspace.members.manage` | Add / remove members, set their role, invite, revoke a member's MCP key (#200) — never your own membership (#208); the last admin stays (`workspace.last_admin`). REST/UI: `DELETE /api/v1/workspaces/{name}/invitations/{email}`, `POST /api/v1/workspaces/{name}/members`, `PUT /api/v1/workspaces/{name}/members/{userId}`, `DELETE /api/v1/workspaces/{name}/members/{userId}`, `DELETE /api/v1/workspaces/{name}/members/{userId}/key`, `POST /partials/workspaces/{name}/members/{userId}/role`, `POST /workspaces/{name}/invitations/revoke`, `POST /workspaces/{name}/members`, `POST /workspaces/{name}/members/{userId}/key/revoke`, `POST /workspaces/{name}/members/{userId}/remove` | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |
| `workspace.create` | Create a workspace (D7). REST/UI: `POST /api/v1/workspaces`, `POST /workspaces/create` | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `workspace.lifecycle` | Deactivate, reactivate, delete a workspace (D-R10). REST/UI: `DELETE /api/v1/workspaces/{name}`, `POST /api/v1/workspaces/{name}/deactivate`, `POST /api/v1/workspaces/{name}/reactivate`, `POST /workspaces/{name}/deactivate`, `POST /workspaces/{name}/delete`, `POST /workspaces/{name}/reactivate` | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `user.manage` | User administration: list, activate, deactivate, grant / revoke super admin, local accounts. REST/UI: `GET /admin/users`, `GET /api/v1/auth/users`, `GET /api/v1/auth/users/{userId}`, `POST /api/v1/auth/users/{userId}/activate`, `POST /api/v1/auth/users/{userId}/deactivate`, `POST /api/v1/auth/users/{userId}/grant-admin`, `POST /api/v1/auth/users/{userId}/revoke-admin`, `GET /partials/admin/users`, `POST /partials/admin/users`, `GET /partials/admin/users/{userId}/mail/{kind}`, `PATCH /partials/admin/users/{userId}/{action}` | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `user.identity_reset` | Release a user's linked sign-in identity (#187). REST/UI: `PATCH /partials/admin/users/{userId}/identity-reset` | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ |
| `profile.read` | The current principal; the settings page; the avatar proxy — the signed-in principal's OWN picture, fetched server-side from an allowlisted host (#197, §11.1). REST/UI: `GET /api/v1/auth/me`, `GET /settings`, `GET /avatar` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `profile.preference` | Your own theme preference. REST/UI: `PATCH /partials/profile/theme` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| `profile.password` | Change your own password (current password verified in-handler); the password page. REST/UI: `POST /partials/account/password`, `GET /settings/password` | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ | ✓ | ✓ | ✓ |
| Read the audit log — **reserved** (D12) | no surface yet: `audit_log` has no page and no endpoint (verified 2026-09-20 — the only reads are the serve/MCP existence checks and the tool learnings, none caller-facing). When one lands it adds a permission on this row | ✗ | ✗ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✓ |

(**There is no datasource WRITE on the MCP surface at all** (094): registering one means handing over a live database credential, and no credential travels through an agent — creating, editing and deleting a datasource are UI/REST-only. Nor is there a workspace, membership, release or promote tool: those are human verbs (D-R2, O-2), which is the same reason no key is ever a super admin (B1). 38 of the 42 tools operate inside the API key's pinned workspace; the four exceptions are `calculators_list` / `calculators_get` (072) and `docs_list` / `docs_get` (120), and only because they touch no workspace data at all — the calculator catalog and the shipped skill docs are properties of the BUILD, identical for every caller.)

**UI screens** reference the same permissions as the routes they call; per-screen minimums are listed in [UI Screens](ui-screens.md) and MUST match this matrix. Since round 2 (114) they also RENDER by it: a verb this matrix would refuse is not drawn at all, and the screen-by-screen inventory — every verb, the role boolean that renders it, and the permission it answers to — is [UI Screens §4.3e](ui-screens.md#43e-role-visibility--every-verb-and-the-role-boolean-that-renders-it-114-normative). There is deliberately no "UI" column here: the rendering rule is derived from the role columns, and a second copy of it in this table would be a second thing to keep true. The htmx partials (`/partials/**`) and the workspace screen actions declare their REST twin's permission with the same `@RequiredScope` mechanism, and the ScopeInterceptor governs every non-public route with the same default-deny: an unannotated handler is refused (`auth.permission.undeclared`), and a mutating partial enforces its twin's permission. Three rail items follow a row since 2026-09-20 and are drawn only for the roles that hold it: Executions (`execution.read`), Promotion (`promotion.read`), Workspaces (`workspace.read`; a principal with no workspace keeps the link because the no-workspace page is the one screen that explains their state).

One PAGE route floors above `read` without being a mutation: the pipeline editor (`GET /pipelines/{id}/editor`) declares `pipeline.execute` (122) — the permission the screen exists to exercise for its LOWEST role (D3: viewers execute what they can read), so a viewer's session reaches the page (no key renders a page — the MCP key is confined to `/mcp`, §7.7). The AUTHORING state the page renders is read, never written — the authoring verbs on it are role-hidden (114) and every mutating call it makes is verb-guarded or viewer-level. The template editor (`GET /templates/editor`) floors at `template.read` since 143 (T315, owner ruling) for the same reason: the explorer's Open links render for every reader, the page renders read state (a draft body is read, never written, by a GET), and every write it can make — Edit, Preview, the lifecycle dialogs — is its own route on its own permission, hidden by role in the markup ([UI Screens §4.7](ui-screens.md#47-template-editor)). 096 §C's authoring floor stays on those writes. **Every GET declares the LOWEST permission whose row admits it** (`ReadFloorTest`): a read of executions is `execution.read`, not `pipeline.read`, because their role sets differ; a read of the workspaces page is `workspace.read`; a dialog fetched for a verb floors at that verb's permission; every other read a signed-in person may make is one of the every-role reads (`pipeline.read`, `template.read`, `datasource.read`, `endpoint.read`, `semantic.read`, the self rows).

### 7.7 Key kinds and published-endpoint bindings

Round 074 gives every API key a **kind** ([`ApiKeyKind`](enums.md#8a-apikeykind--what-an-api-key-is), `api_keys.kind`, default `user`); round 091 adds the third. A kind answers WHERE a credential may be presented; its role (§7.5) answers what it may do there.

| Kind | Authenticates where | Authority | Created by |
|---|---|---|---|
| `mcp` (the MCP key; `user` until keys v2 V37, A19) | `DP-API-Key` / `Authorization: Bearer` on **`/mcp` and nowhere else** (#215 B2, owner ruling 2026-09-24: "MCP key should be only MCP"). On every other route — REST, `/partials/**`, every page, the published tree — the REST key filter refuses it with `403 endpoint.key_kind_refused` and `details.reason = "mcp_key_off_surface"`, before any permission is judged; the interceptor states the same confinement again | Its OWN identity (§4.7) with the member role chosen at creation — `author`, `promoter` or `workspace_admin` under the subset rule (A13/A14, §7.5); never a super-admin role (B1) | **Anyone holding `mcp_key.create` — author, promoter, workspace admin — on the Keys page or over REST** (keys v2 A13/A14); never at sign-in (A15) |
| `endpoint` | `DP-API-Key` on the published tree — a `GET` under `/api/` whose first segment is not a reserved category (`v[0-9]+` or `api`, R-EP5) — **including the result-paging routes** `…/executions/{id}` and `…/executions/{id}/result` for executions **it** started (A16, §19.3). The framework's own `GET /api/v1/executions/…` reads are OFF its surface (A16): those are session-only, and an `api` key on them is refused by kind | Its own identity (§4.7) with the `api_caller` role; its rows in `endpoint_key_bindings` decide WHICH published paths it serves | **Workspace admins and super admins, on the Keys page** (`api_key.create`, D17) — the UI calls this kind an "API key" |
| `server` | `DP-Promotion-Key` on `/api/v1/promotion/**`, presented by a SENDING deployment | Its own identity with the `promotion_receiver` role — inventory and push, for any workspace (B6) | Super admins only (`server_key.create`, D18) |

Each kind carries exactly one role (the database CHECK, §7.5), so a request that names a role its kind cannot hold is refused rather than quietly corrected (`endpoint.key_kind_refused`) — a caller who writes `{"kind": "endpoint", "role": "promotion_receiver"}` holds a mental model this surface has to correct out loud. A request that still sends `scopes` is refused by name.

**The confinement is central, not per-handler.** Every key kind is refused on every route outside its own family, so a new route cannot become reachable to one by someone forgetting a check — the same default-deny reasoning as the unannotated-handler rule in §7.6. The MCP key's family is `/mcp`; `ApiKeyFilter` refuses it everywhere else FIRST (the chain stops there — a session cookie on the same request does not rescue it), and `ScopeInterceptor` states the same reach table for every MVC handler (`ScopeInterceptor.reachableBy`). Two properties of the interceptor's check are load-bearing:

- It is decided **before** the handler's `@RequiredScope` is read. A UI page carries no annotation and lives outside the governed prefixes, so an annotation-first order would let every screen in the product answer a credential that authorises none of them (091: a scopeless key could render `/settings/api-keys` and read its owner's key list).
- On the routes a confined kind may reach, what it may DO is its key role (§7.5). Keys v2 A16 narrows the `endpoint` family to the published tree alone — the paging routes ride it, and the framework's execution routes are simply off the surface, so the old "reached by path, refused by role" case on `DELETE /api/v1/executions/{id}` is gone.

`/mcp` is a **servlet**, not an MVC handler, so the interceptor never sees it; `McpAuthFilter` refuses both identity-acting kinds (`endpoint`, `server`) there. Without that refusal such a key could not CALL a tool (its key role holds no tool's permission) but could still read the whole catalogue through `tools/list`.

#### Hierarchical bindings (ruling R-EP2)

A binding names a **node of the endpoint tree**, not a pattern: `/lending` authorises every endpoint beneath it. Per request, the ancestors of the request path are walked from the most specific (`/lending/{borough}/home` → `/lending/{borough}` → `/lending` → `/`), and the **first node carrying any binding decides**. The presenting key must be among that node's bound keys, or the request is `403 endpoint.key_not_bound`.

**A binding stays inside its workspace** (2026-09-21, #191). At bind time, a prefix is refused unless it is the root `/` or a node at or above a path the CALLER'S workspace publishes — `endpoint.path_invalid`, with a message that names only the caller's own tree, because whether another workspace publishes at the prefix is exactly what the refusal must not reveal (§11A.1). At serve time, a binding decides only for an endpoint of ITS OWN workspace: the ancestor query filters on the endpoint's `workspace_id`, the authorizer re-checks the surviving rows in memory (fail closed, either layer alone suffices), and a foreign binding at a nearer node neither authorises nor shadows — the walk continues past it as if the node carried nothing. A root binding is therefore still safe: it exercises only bindings of the serving key's own workspace.

A deeper binding therefore **replaces** an inherited one rather than adding to it: a binding at `/lending/private` hides the one at `/lending` for that subtree, and the key bound at `/lending` stops working there. Bind both keys at the deeper node when both should work.

**The demo workspace's public key (#224) is an ordinary `endpoint` key** — an `api_caller` minted at bootstrap from `datapipelines.bootstrap.demo-api-key`, named `demo-public-key`, bound to the demo family's published paths and shown on the site's demo-data page; nothing about it is a fourth kind, and its budget is the serve path's per-key budget (rest-api §19.8).

That is the more conservative of the two readings, and it is chosen deliberately. An operator who binds a narrow key deep in the tree is drawing a boundary; an additive model would silently keep the broad key working across it. The cost of "replace" is a binding an operator can see is missing and add; the cost of "add" is a boundary that was never real.

A bound node decides for every credential that reaches it — since B2 that is only ever an `endpoint` key.

#### The unbound case

An endpoint with no binding on any ancestor is not public and not open to any key: **it is unservable until a key is bound to it** (#215 B3). Serving takes a key (a session is refused with `auth.session.required`), and since B2 the MCP key never reaches a published path — so the only credential that can arrive is an `endpoint` key, refused with `403 endpoint.key_kind_refused`. Until slice (b) a `user` key of the endpoint's own workspace holding `execute` was admitted here; that branch went with the scopes.

That is the security property: **an unbound path authorises nothing.** If an unbound path fell through to "any endpoint key may call it", publishing a new endpoint would silently widen every existing endpoint key's reach at the moment of publication.

#### Reading results

An endpoint key may read the metadata and the cursor of executions it started (`api_caller` holds `execution.read` and `execution.result.read`, both *own*). Since #215 its runs carry its own identity in `executed_by` (§4.7), so ownership is the ordinary own-runs rule — `executed_by` = the principal — and the key's creator does NOT see the key's run as their own (only `execution.read_all` does). Runs served BEFORE V34 carry the creator instead; for those, ownership is still proved by the `endpoint.served` audit row that pairs the **key id** with the **execution id**. A different endpoint key asking for the same execution is refused either way.

#### Issuance

`POST /api/v1/auth/api-keys` takes `kind` and `bindings` in the same request, and the bindings are validated **before** the key is minted. They are part of issuance rather than a second call because the plaintext key is returned exactly once: a failure between mint and bind would leave an operator holding a secret they can neither use nor re-read. Since 179 the route is `api_key.create` (workspace admins and super admins), `kind` is `endpoint` or `server` only — `user` is refused with `auth.key_kind_not_mintable`, and an absent `kind` means `user`, so a pre-179 client meets the refusal rather than silently minting a different credential — and the bindings may name the key by `api_key_id` (the `/api-keys` page's shape) or, kept for REST compatibility, by the caller's own key's `api_key_name`. The request may name the key's `role` (only the kind's own); the response carries `role`, the `identity` it acts as (`{id, display_name}`) and `created_by`. Everything else about an endpoint key is an ordinary key — `dpk_` prefix, Argon2id, expiring, revocable, rate-limited on its identity's budget (§11A).

Binding and unbinding are audited (`endpoint.key_bound` / `endpoint.key_unbound`, [Enums §15](enums.md#15-authauditevent--auth-audit-log-events)), as is every serve (`endpoint.served`).

#### The `server` kind (091)

Promotion is one deployment writing RELEASED content into another ([Versioning §10.6](versioning.md#106-the-promotion-peer-credential--a-shared-server-key-ratified-2026-09-01)). Its credential is a **server key**, and since #215 a stored one acts as its own `service` identity (§4.7) with the `promotion_receiver` role — no human it can be traced to, and no longer the System account's authority (record C4). 091 changed where the key LIVES.

Until 091 it was a pre-shared configuration value — `datapipelines.deployment.promotion.server-key` — which nobody can mint, list, expire or revoke without editing a file and restarting the deployment. A `server` key is the same credential stored like every other key: Argon2id hash, `dpk_` prefix, an owner, an expiry, a revocation flag and a last-used stamp. The configured value is accepted for **one release** and WARNs at boot ([Configuration §3.19](configuration.md#319-deployment)).

- **Minting is `server_key.create` — a super admin's** (D18): whoever holds this credential can write pipelines, templates and datasource references into the receiving deployment.
- **Role: `promotion_receiver`** — `promotion.inventory.read` and `promotion.push`, and nothing else. The confinement is the route family, asserted the same way an endpoint key's is. **Intake is instance-wide** (#215 B6, owner ruling 2026-09-24; record amendment A11): a batch names its target workspace and the receiver resolves it by name, for ANY workspace — the key's `workspace_id` is where it is listed and administered (the Keys page), not a confinement.
- **Presented as `DP-Promotion-Key`, by a deployment.** `PromotionServerKeyFilter` compares the configured value first (constant time — a deployment that has not migrated pays no database read), then validates the header against the key store. Fail-closed is unchanged: no configured value AND no live `server` key ⇒ every push refused. Missing header, malformed header, wrong key, **wrong kind**, revoked, expired, a deactivated identity or pinned workspace and "nothing configured here" all answer the same `auth.promotion.key_invalid`, so a caller cannot classify a credential by asking. The creator's liveness is not consulted (PK2).
- **The actor is the key's own identity** (record C4), never the admin who minted it: received versions and the `auth.promotion.accepted` row name it. The deprecated config value — a bootstrap credential with no row to attach an identity to — stays the System account (§4.5, B6), with the same `promotion_receiver` role. The key's id rides on the principal so the audit trail can name WHICH key across a rotation, and `last_used_at` is stamped like any other key's.
- **Presented as an ordinary `DP-API-Key` it authenticates a principal that is refused everywhere** — `/api/**`, `/partials/**`, `/mcp` and every UI page — with `403 endpoint.key_kind_refused` and `details.reason = "server_key_off_surface"`.

Rotation, which the config value never had: mint a second `server` key, set it on the sender, revoke the first. No restart, on either side.

---

## 8. Spring Security Configuration

### 8.1 Filter chain

Security headers are written before the downstream filter chain starts. An SSE worker may write the response while the original servlet thread unwinds; deferred security-header inspection at that point would race the container's mutable headers. `HeaderWriterFilter` therefore uses eager writing. The default security and private-cache headers remain enabled; an explicit application `Cache-Control` value (including public page/asset TTLs) overrides that header afterward. This changes header timing, not authentication, authorization or CSRF rules.

The sketch below is the shape of `SecurityConfig.securityFilterChain` as it is actually
assembled, not a simplification: the three `addFilterBefore` calls read in the REVERSE of
the order they produce, which is why the resulting order is spelled out under §8.2 rather
than inferred from the source. The `permitAll` list is **not** written inline — it is
`PublicPaths.ENTRIES` (§8.3), so every pattern carries a reason and is reachable to the
tests that guard it.

```kotlin
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val filters: AuthFilters,                 // apiKey, jwt, loginRateLimit, promotionServerKey, workspaceResolution, oidcSignedInBounce
    private val oidcSuccessHandler: OidcSuccessHandler,
    private val scopeInterceptor: ScopeInterceptor,
    private val forcedPasswordChangeInterceptor: ForcedPasswordChangeInterceptor,
    // …entry point, access-denied handler, logout handler, OAuth2 authorization-request
    // repository + resolver, AuthProperties
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf ->
                // Cookie: dp_csrf (readable by JS), header: DP-CSRF-Token; the plain
                // (non-XOR) request handler keeps cookie value == header value.
                csrf.csrfTokenRepository(cookieCsrfRepository())
                // Exemption follows the CREDENTIAL, not the path (§8.4): DP-API-Key (or
                // Bearer dpk_ on /mcp), plus the promotion route on the same grounds (§10.6).
                csrf.ignoringRequestMatchers(ApiKeyCredentialMatcher(), PromotionRouteMatcher())
                // Double-submit needs a STABLE cookie; this chain has no server session to
                // protect, so Spring's rotate-or-delete strategy is neutered (027).
                csrf.sessionAuthenticationStrategy(NullAuthenticatedSessionStrategy())
            }
            .authorizeHttpRequests { auth ->
                auth
                    // A re-dispatch of an already-authorized request, not a path rule (§8.3).
                    .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                    // THE allowlist — one row per pattern, each with its reason (§8.3).
                    .requestMatchers(*PublicPaths.PATTERNS.toTypedArray()).permitAll()
                    .anyRequest().authenticated()
            }

        // OIDC login is wired ONLY when providers are configured (§5A): a local-accounts-only
        // deployment has no ClientRegistrations and no /oauth2 endpoints.
        if (oidcConfigured) configureOidcLogin(http)   // + OidcSignedInBounceFilter (090)

        http
            .addFilterBefore(filters.jwt, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(filters.apiKey, JwtAuthenticationFilter::class.java)
            .addFilterBefore(filters.loginRateLimit, ApiKeyFilter::class.java)
            .addFilterBefore(filters.promotionServerKey, LoginRateLimitFilter::class.java)
            .addFilterAfter(filters.workspaceResolution, JwtAuthenticationFilter::class.java)
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(authEntryPoint)      // §8.2 — splits by Accept
                it.accessDeniedHandler(authAccessDeniedHandler)
            }
            .logout { logout ->
                logout.logoutUrl("/logout")
                       .addLogoutHandler(auditLogoutHandler)
                       .deleteCookies("dp_session")
                       .logoutSuccessUrl("/login")
            }

        val chain = http.build()
        chain.filters.filterIsInstance<HeaderWriterFilter>().single().setShouldWriteHeadersEagerly(true)
        return chain
    }

    // Two MVC interceptors, registered as WebMvcConfigurer beans: the scope interceptor on
    // every handler (§7.6), and the forced-password-change gate on every handler except
    // ForcedPasswordChangeInterceptor.EXCLUDE_PATTERNS (§5A.4).
}
```

### 8.2 Filter order (per request)

```
 1. CORS filter                     — a container filter ahead of the security chain (one origin = auth.base-url; unset ⇒ no CORS)
 2. CSRF filter                     — validates dp_csrf on state-changing requests; exemption follows the CREDENTIAL (§8.4), never the path
 3. PromotionServerKeyFilter        — /api/v1/promotion/** only: the DP-Promotion-Key gate (§10.6). Inert everywhere else
 4. LoginRateLimitFilter            — /login and /oauth2/ only: per-client-IP damper, 429 rate_limit.exceeded (§11.5)
 5. ApiKeyFilter                    — checks DP-API-Key header (or Bearer dpk_ on /mcp); if present, validates and sets SecurityContext
 6. JwtAuthenticationFilter         — checks dp_session cookie; DECLINES when ApiKeyFilter stashed a rejection (§8.4); skips /mcp entirely
 7. WorkspaceResolutionFilter       — resolves the active workspace onto the principal (§5.6): DP-Workspace switch or claim/pin, membership-checked
 8. OAuth2LoginAuthenticationFilter — handles /oauth2/** and /login/oauth2/code/** redirects (wired only when a provider is configured)
 9. AuthorizationFilter             — checks the §8.3 allowlist, then authenticated() for everything else
10. ForcedPasswordChangeInterceptor — §5A.4, every handler except EXCLUDE_PATTERNS
11. ScopeInterceptor (MVC)          — kind confinement (§7.7), then @RequiredScope; default-deny for unannotated handlers outside the §8.3 allowlist
12. Controller                      — handles the request
```

If neither API key nor JWT is present (and the path requires auth), the AuthorizationFilter routes to the entry point, which splits by client shape (T31): a request whose `Accept` includes `text/html` — a browser navigating a UI route — gets a `302` to `/login` (the `Location` is a relative header, never `sendRedirect`'s Host-derived absolute URL); `/api/**` and `/mcp` NEVER redirect (their 401 JSON envelope is contract, byte-pinned), and non-HTML clients (`curl`'s `Accept: */*`, JSON API callers) keep the exact current envelope whatever path they hit. If authenticated but scope insufficient, the ScopeInterceptor returns `403`.

### 8.3 Public endpoints (no auth required)

The table below is **generated from `PublicPaths.ENTRIES`** (`modules/auth`), which is the
single source of the `permitAll` list the chain in §8.1 is built from. `PublicPathsTest`
parses this section and demands equality pattern-for-pattern, reason-for-reason and
round-for-round, so neither side can move without the other; `PublicRouteWalkerTest`
(`modules/web`) then walks every request mapping the application registers against these
patterns and freezes the resulting public handler set by name, so a new controller mapped
under one of the globs fails the build instead of shipping public. Everything not matched
here falls to `anyRequest().authenticated()`.

Two things are deliberately **not** rows in this table:

- The `DispatcherType.ASYNC` / `ERROR` permit (§8.1). It is not a path rule: it says that a
  re-dispatch of a request the REQUEST dispatch already authenticated and authorized is not
  re-authorized from an empty `SecurityContext`. Without it every completed SSE stream dies
  as Access Denied on its completion dispatch.
- Anything reached only with a credential. Being on this list means *anonymous* access, not
  "unscoped": an annotated handler on a public path is still enforced by `ScopeInterceptor`.

| Path pattern | Why public | Since |
|---|---|---|
| `/` | The marketing home page: constant content, no datastore, no principal — public by design (033/D4). | 033 |
| `/site/**` | The marketing site's own css/js/img assets; the design system it references rides /vendor/** below. | 033 |
| `/mcp-server-for-sql-databases` | Intent-cluster page: GET-only constant content whose only live fact is the compile-time MCP tool count. | 073 |
| `/mcp-server/*` | Per-engine intent-cluster pages, one segment deep and enumerated by the page registry, not globbed open. | 073 |
| `/add-mcp-server-to-claude-code` | Intent-cluster page: GET-only constant content, no datastore and no principal on the request. | 073 |
| `/ai-data-pipeline` | Intent-cluster page: GET-only constant content, no datastore and no principal on the request. | 073 |
| `/text-to-sql-agent` | Intent-cluster page: GET-only constant content, no datastore and no principal on the request. | 073 |
| `/compare/*` | The comparison pages (airflow, dbt): GET-only constant content, one segment deep, no datastore. | 073 |
| `/federated-query` | Intent-cluster page: GET-only constant content, no datastore and no principal on the request. | 073 |
| `/dp-lake` | The dp-lake product page: same shape and same reasoning as the 073 intent-cluster pages. | 089 |
| `/faq` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | site-v2 |
| `/roadmap` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | site-v2 |
| `/security` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | site-v2 |
| `/published-api` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | site-v2 |
| `/mcp-tools` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | site-v2 |
| `/tableau` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | site-v2 |
| `/tableau/*` | Site v2 intent pages under the Tableau hub: GET-only constant content, no datastore and no principal on the request. | site-v2 |
| `/for/*` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | 111 |
| `/how-it-works` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | 115 |
| `/demo-data` | Demo-data page: GET-only, renders the vendored manifests parsed at startup; no datastore, no principal. | 116 |
| `/pricing` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | 119 |
| `/semantic-layer` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | 119 |
| `/use-cases` | Site v3 hub page: GET-only constant content, no datastore and no principal on the request. | 145 |
| `/explore` | Site v3 directory, generated from the page registry and the packaged docs catalog; no datastore, no principal. | 145 |
| `/docs` | The in-product spec index: packaged Markdown, no principal, no datastore, already public in the AGPL repo. | 073 |
| `/docs/*` | One packaged spec per slug, rendered from the jar or raw at its .md twin (173); the same text is public on GitHub. | 073 |
| `/skill.md` | The agent skill's core, raw: it is the MANUAL, so requiring a key would gate learning how to use the key. | 095 |
| `/skill/*` | The skill's reference files, packaged in the jar and identical to the public AGPL repository's text. | 095 |
| `/robots.txt` | Crawler infrastructure: a document that is meaningless unless it is readable without a login. | 073 |
| `/sitemap.xml` | Generated from the page registry and packaged doc slugs; a sitemap behind auth indexes nothing. | 073 |
| `/llms.txt` | The llms.txt index, generated from the page registry and the docs catalog; an agent index behind auth indexes nothing. | 173 |
| `/llms-full.txt` | The full llms.txt: every packaged doc's Markdown, already public at its own .md route and on GitHub. | 173 |
| `/health` | Liveness probe for orchestrators, which present no credential; flattened UP/DOWN plus version only. | P3d |
| `/ready` | Readiness probe for orchestrators, which present no credential; flattened UP/DOWN plus version only. | P3d |
| `/info` | Build info (version, build time, commit) — the deployment's own identity, no principal data. | P3d |
| `/login` | The login page and the local password POST: the surface a caller uses BEFORE it has a credential. | P3d |
| `/login/**` | Login error redirects and the OIDC callback subtree, reached before any session exists. | P3d |
| `/oauth2/**` | The OIDC authorization redirect and callback endpoints, by definition pre-authentication. | P3d |
| `/vendor/**` | Vendored static assets (design system, fonts, icons, Alpine, Cytoscape, htmx) served to the login page too. | P3d |
| `/css/**` | The application stylesheets, which the unauthenticated login and error pages already render. | P3d |
| `/js/**` | The application scripts, which the unauthenticated login and error pages already render. | P3d |
| `/favicon.ico` | The site icon, requested by the browser on the login page before any credential exists. | P3d |
| `/error` | Boot's default error page with no stack trace or message; an anonymous error must not loop through login. | P8 |


### 8.4 API endpoints (auth via API key OR JWT)

All `/api/v1/**` endpoints accept either:
- `DP-API-Key: dpk_...` header (agents, programmatic clients).
- `Cookie: dp_session=<jwt>` (browser UI calling REST directly).

The filter chain tries API key first, then JWT. **If both are present the API key decides the
request — including when it decides to refuse it** (096 §E, review finding F9). A presented
`DP-API-Key` that fails validation ends the request with its own code
(`auth.api_key.invalid` / `auth.api_key.expired`); `JwtAuthenticationFilter` declines to
authenticate the cookie behind it, and `AuthEntryPoint` answers with the key's stashed error.

Until 096 the rejection was stashed and the chain continued, so a revoked or expired key
presented alongside a live `dp_session` was served at SESSION privilege — at least `author`
(§6.1) — by a credential the server had just refused, and revoking a key produced no visible
signal to a client holding both. Not browser-exploitable (a custom header forces a preflight
this deployment does not grant), but it meant revocation did not end the session beside it.

CSRF exemption is scoped by **credential type, never by path**: a request is exempt only when it carries an API key (`DP-API-Key` header, or `Authorization: Bearer dpk_` on `/mcp`) — a credential a hostile browser context cannot forge, with no cookie involved. A state-changing request authenticated by the `dp_session` cookie requires the `dp_csrf` double-submit token (`DP-CSRF-Token` header) **wherever it occurs**: `/partials/**` ([UI Screens §3](ui-screens.md#3-common-layout)), `POST /logout`, and cookie-authenticated calls to `/api/v1/**` alike. `/mcp` accepts no cookies at all (§8.5), so CSRF never arises there. `dp_session`'s `SameSite=Lax` (§5.5 — Strict withholds the cookie on the IdP's cross-site redirect chain and strands every login) is defense-in-depth, not the control: it defends against neither a same-site subdomain attacker nor a cross-site top-level GET. In the §8.1 chain this is a `RequestMatcher` over the credential carrier, not a path glob. A CSRF failure returns 403 `auth.csrf.invalid` with `details.reason`: `missing` | `mismatch` (§9). All three cookies this module mints (`dp_session`, `dp_csrf`, `dp_oauth2_authz`) carry the `Secure` flag keyed off `datapipelines.auth.base-url`'s scheme (T33): `https://` — or no base-url at all — keeps `Secure`; an explicit `http://` base-url drops it so local development login works over plain HTTP. **Production MUST be https** — the wrong default would silently drop sessions there, so absent configuration fails secure. The `dp_csrf` cookie is **stable for the life of the browsing session**: it is minted by the first response that renders a CSRF token (the login page) and is never rotated or deleted afterwards — the chain's CSRF config pins `NullAuthenticatedSessionStrategy`, because Spring's default `CsrfAuthenticationStrategy` rotates-or-deletes the cookie on every security-context change, which (with per-request JWT authentication, §4.2) is every authenticated request and would strand each rendered page's `hx-headers` token against a rotated cookie (found 027). (History: v2.2's prose and sketch contradicted each other; v2.3 briefly resolved toward path-based exemption + `SameSite=Strict`; v2.4 supersedes both after two Gate C seats independently flagged the subdomain gap — exemption follows the credential, not the path.)

### 8.5 MCP endpoint (`/mcp`)

`POST /mcp` and `GET /mcp` ([MCP Server §3](mcp-server.md#3-transport)) are API-key-only — session cookies are **not** accepted there. Two equivalent credential carriers:

- `DP-API-Key: dpk_<id>.<secret>` — same as REST.
- `Authorization: Bearer dpk_<id>.<secret>` — for MCP clients that can only set the standard Authorization header. The `ApiKeyFilter` recognizes the `dpk_` prefix in a Bearer token and routes it through the identical validation path (§7.3).

`/mcp` is CSRF-exempt (no cookie auth) and enforces the same permission catalog (§7.6) per tool.

The filter chain authenticates on the servlet thread and the MCP layer carries the principal forward in the transport context (`McpToolContext`), because the MCP SDK runs each tool handler on its own scheduler thread — `SecurityContextHolder` is **empty** there. Code reachable from a tool therefore takes the principal and the workspace as arguments and never reads the thread-local; a `@Bean` adapter that did (the save-time datasource lookup, until 134) silently validated every MCP save as "no principal" ([MCP Server §4.1](mcp-server.md#41-auth-model)). Filters, interceptors and MVC controllers run on the request thread and may keep reading it.

### 8.6 Entry inventory and public contract

Every way into the running application, as a reviewed table the build compares with what the application actually registers — in both directions, by name (#217, [security-assurance record](superpowers/specs/2026-09-24-security-assurance-design.md) §4, ratified B3/B5). A registration with no row fails: somebody added a filter, a servlet, a scheduled job or a listener that nobody reviewed. A row with no registration also fails: the doc promises an entry, or a guard, that is not there. Adding one of these is a two-line change — the code and its row — in the same commit, the rule AGENTS.md already applies to handlers and §7.6.

Handler methods are not listed here: §7.6 is their reviewed table (B3 — one table, never two). `EntryInventoryE2eTest` places every handler the application registers, from every module, on the §7.6 row of the permission it declares or in the public contract below, and every route §7.6 names must be registered.

**The entry inventory:** one row per registration, read at runtime by `EntryInventoryE2eTest`. Servlets and container filters come from the servlet container, with each filter's order from Spring Boot's sorted initializers (`container` when the container registered it itself). The security chain comes from `FilterChainProxy`, in the order it runs. Handler mappings are every `HandlerMapping` bean, and a mapping of a kind the test cannot read fails until it has an adapter. Scheduled jobs are every `@Scheduled` task. Ports and actuator endpoints come from the PACKAGED jar booted as its own process: its LISTEN sockets are read from `/proc/<pid>/fd` and `/proc/<pid>/net/tcp{,6}`, and its management port's actuator index is read with a session, each endpoint with the status an anonymous caller gets. "Reachable from a request" is `no` for every scheduled job (B5 — a job is inventoried, unreachable from any transport and bounded, never an entry point), and the test also fails if a job's bean is a request handler. Since #9 the scheduler's db-scheduler tasks are a family of their own (`scheduler-task`), read off every `Task` bean — a new task is a new row, and the same B5 rules hold for it.

| Family | Name | Type | Pattern / schedule | Purpose | Reachable from a request | Bounded work |
|---|---|---|---|---|---|---|
| servlet | `dispatcherServlet` | `DispatcherServlet` | `/` | Spring MVC's front controller: every handler method (§7.6, §8.6.2), the static resources and the error page. | yes | Per request; the handlers' own bounds. |
| servlet | `mcpTransport` | `HttpServletStatelessServerTransport` | `/mcp` | The MCP JSON-RPC transport ([MCP Server §3](mcp-server.md#3-transport)); an API key only (§8.5), each tool gated by its catalog row. | yes | Per request; the tools' own bounds and the §12 limiter. |
| filter | `characterEncodingFilter` | `OrderedCharacterEncodingFilter` | `/* · order -2147483648` | Spring Boot: UTF-8 request and response encoding. | yes | Constant per request. |
| filter | `webMvcObservationFilter` | `ServerHttpObservationFilter` | `/* · order -2147483647` | Micrometer: the HTTP server observation (metrics) for every request. | yes | Constant per request. |
| filter | `correlationIdFilterRegistration` | `CorrelationIdFilter` | `/* · order -2147483638` | Adopts or mints the correlation id, into the MDC and the response header ([Observability §3.3](observability.md#33-correlation-id-propagation)). | yes | Constant per request. |
| filter | `corsFilterRegistration` | `CorsFilter` | `/* · order -2147483637` | CORS for the one configured origin, `auth.base-url` (§8.2 step 1); no origin configured, no CORS. | yes | Constant per request. |
| filter | `formContentFilter` | `OrderedFormContentFilter` | `/* · order -9900` | Spring Boot: form bodies on PUT, PATCH and DELETE. | yes | The body the container already bounds. |
| filter | `requestContextFilter` | `OrderedRequestContextFilter` | `/* · order -105` | Spring Boot: binds the request to its thread for request-scoped lookups. | yes | Constant per request. |
| filter | `springSecurityFilterChain` | `DelegatingFilterProxyRegistrationBean$1` | `/* · order -100` | Delegates to Spring Security's filter chain — the `security-chain` rows below. | yes | The chain's. |
| filter | `rateLimitFilterRegistration` | `RateLimitFilter` | `/* · order -99` | The per-user request limiter over `/api/v1` and `/mcp` ([REST API §12](rest-api.md#12-rate-limiting)). | yes | Constant per request; the window lives in Redis. |
| filter | `endpointKeyBudgetFilterRegistration` | `EndpointKeyBudgetFilter` | `/* · order -98` | The per-key request budget of every `api_caller` key on the serve path ([Configuration §3.22](configuration.md#322-published-endpoints)). | yes | Constant per request; the window lives in Redis. |
| filter | `mcpAuthFilterRegistration` | `McpAuthFilter` | `/mcp · order -90` | Turns the key the chain validated into the MCP calling context, or refuses (§8.5). | yes | Constant per request. |
| filter | `Tomcat WebSocket (JSR356) Filter` | `WsFilter` | `/* · order container` | Registered by Tomcat's WebSocket initializer (the `tomcat-embed-websocket` jar is on the classpath). No WebSocket endpoint is deployed, so it passes every request on; an endpoint would be a new entry family and needs its own row and adapter first (security-assurance record §4). | yes | Passes through: no endpoint to upgrade to. |
| security-chain | `chain 1 #01` | `DisableEncodeUrlFilter` | `any request` | Never writes a session id into a URL. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #02` | `WebAsyncManagerIntegrationFilter` | `any request` | Carries the security context into async (SSE) processing. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #03` | `SecurityContextHolderFilter` | `any request` | Loads the (empty — stateless) security context per request. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #04` | `HeaderWriterFilter` | `any request` | The security headers, written eagerly (§8.1, `SecurityHeaders`). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #05` | `CorsFilter` | `any request` | Spring Security's CORS hook — the same single-origin configuration. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #06` | `CsrfFilter` | `any request` | The `dp_csrf` double-submit on state-changing cookie requests (§8.4); exempt by credential, never by path. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #07` | `LogoutFilter` | `any request` | `POST /logout`: clears the session cookie and audits (§6.5). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #08` | `OidcSignedInBounceFilter` | `any request` | A signed-in visitor never starts a new authorization ceremony at `/oauth2/authorization/*`. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #09` | `OAuth2AuthorizationRequestRedirectFilter` | `any request` | `/oauth2/authorization/{provider}`: the redirect to the identity provider (§5.4). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #10` | `OAuth2LoginAuthenticationFilter` | `any request` | `/login/oauth2/code/{provider}`: the OIDC callback (§5.4–5.5). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #11` | `PromotionServerKeyFilter` | `any request` | `/api/v1/promotion/**` only: the promotion credential gate (§7.7, §10.6); inert elsewhere. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #12` | `LoginRateLimitFilter` | `any request` | `/login` and `/oauth2/` only: the per-client-IP damper (§11.5). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #13` | `ApiKeyFilter` | `any request` | `DP-API-Key` (or `Bearer dpk_` on `/mcp`): validates the key and sets the principal (§7.3). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #14` | `JwtAuthenticationFilter` | `any request` | The `dp_session` cookie: validates the session and sets the principal (§6.3). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #15` | `WorkspaceResolutionFilter` | `any request` | Resolves the active workspace onto the principal (§5.6). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #16` | `RequestCacheAwareFilter` | `any request` | Spring Security's saved-request hook (no session: nothing is saved). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #17` | `SecurityContextHolderAwareRequestFilter` | `any request` | Exposes the principal through the servlet request API. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #18` | `AnonymousAuthenticationFilter` | `any request` | Marks a request with no credential anonymous, so the §8.3 allowlist can admit it. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #19` | `SessionManagementFilter` | `any request` | Session policy: stateless, no servlet session is created. | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #20` | `ExceptionTranslationFilter` | `any request` | Turns an access refusal into the entry point's 401/302 or the 403 handler (§8.2). | yes | Constant per request; key and session checks read the §11.4 cache. |
| security-chain | `chain 1 #21` | `AuthorizationFilter` | `any request` | The §8.3 allowlist, then `authenticated()` for everything else. | yes | Constant per request; key and session checks read the §11.4 cache. |
| handler-mapping | `requestMappingHandlerMapping` | `RequestMappingHandlerMapping` | `handler methods — auth.md §7.6 and §8.6.2` | Every `@RequestMapping` handler. Its routes are placed on §7.6's rows (governed) or in the public contract below. | yes | The handlers'. |
| handler-mapping | `resourceHandlerMapping` | `SimpleUrlHandlerMapping` | `/**, /site/**, /webjars/**` | Static resources from the packaged classpath locations. Only the §8.3 globs (`/site/**`, `/vendor/**`, `/css/**`, `/js/**`, `/favicon.ico`, `/robots.txt`) are anonymous; every other static path needs a credential. | yes | A file read; the container bounds the response. |
| handler-mapping | `welcomePageHandlerMapping` | `WelcomePageHandlerMapping` | `no URL` | Spring Boot's welcome page: no `index.html` is packaged, so it maps nothing. | no | Maps nothing. |
| handler-mapping | `welcomePageNotAcceptableHandlerMapping` | `WelcomePageNotAcceptableHandlerMapping` | `no URL` | The welcome page's 406 companion; maps nothing for the same reason. | no | Maps nothing. |
| handler-mapping | `beanNameHandlerMapping` | `BeanNameUrlHandlerMapping` | `no URL` | Maps a bean named like a URL; no bean is. | no | Maps nothing. |
| handler-mapping | `routerFunctionMapping` | `RouterFunctionMapping` | `no route function` | Functional routes: none. A `RouterFunction` bean would be a new entry mechanism, and this row changes with it. | no | Maps nothing. |
| handler-mapping | `healthEndpointWebMvcHandlerMapping` | `AdditionalHealthEndpointPathsWebMvcHandlerMapping` | `no mapping` | Actuator's additional health-group paths on the application port; no group sets one. | no | Maps nothing. |
| scheduled | `StaleExecutionSweepScheduler#sweep` | `fixedDelay` | `every PT15S` | Marks a RUNNING execution whose heartbeat stopped as failed — the instance holding it died (deployment.md §6.2). | no | One idempotent `UPDATE` per tick; single scheduler thread, ticks never overlap. |
| scheduled | `DatasourcePoolReaperScheduler#reap` | `fixedDelay` | `every PT5S` | Closes retired datasource pools once drained or past their ceiling ([Datasources §5.2](datasources.md#52-pool-lifecycle)). | no | Walks one in-memory queue, usually empty. |
| scheduled | `ExecutionEventRetentionScheduler#retain` | `fixedDelay` | `every PT1H` | Deletes `execution_events` rows older than the retention window ([Metadata DB §8.1](metadata-db.md#81-execution-event-cleanup)). | no | One `DELETE` per tick, cutoff `now − retention`. |
| scheduler-task | `schedule-dispatcher` | `recurring` | `every PT10S` | #9's ONE dispatcher ([Scheduler](scheduler.md)): records due occurrences and enqueues their run task in one transaction. A db-scheduler task, not an `@Scheduled` job — one instance cluster-wide, only on an instance with `datapipelines.scheduler.enabled`. | no | At most 100 due schedules per tick, `FOR UPDATE SKIP LOCKED`; one run and one missed summary per schedule however long the outage. |
| scheduler-task | `schedule-reconciler` | `recurring` | `every PT10S` | Maps each watched run's execution outcome onto the run (R6) — the executor's standardized outcome, never a pipeline table. | no | At most 500 watched runs per tick; one conditional UPDATE per changed run. |
| scheduler-task | `schedule-run` | `one-time` | `one instance per recorded run` | Admits one run: capacity, preparation, the start claim, the launch through the executor — as the system identity, never a request's principal. | no | One run per delivery; returns once the execution's record exists (it never holds a thread for the run). |
| port | `server.port` | `listening socket` | `bound to *` | The application port: every handler, `/mcp`, the site and the published endpoints. | yes | Tomcat's thread and connection limits. |
| port | `management.server.port` | `listening socket` | `bound to 127.0.0.1` | Actuator only, on loopback by default ([Configuration §3.14](configuration.md#314-framework-wiring-keys)); nothing under `/actuator` is routable on the application port. | yes | Tomcat's limits; one endpoint exposed. |
| actuator | `health` | `management endpoint` | `/actuator/health · anonymous 401` | The one exposed actuator endpoint (`management.endpoints.web.exposure.include: health`). The application's security chain guards the management context too, so an anonymous caller is refused; orchestrator probes use `/health` and `/ready` on the application port (§8.3). | yes | The health indicators' own timeouts. |

**The public contract:** one row per [§8.3](#83-public-endpoints-no-auth-required) glob, its reason exactly as `PublicPaths` states it. `PublicContractE2eTest` holds the running application to it. Every handler under a public glob is named in the row of the FIRST glob, in declaration order, that covers it. A row names only handlers that are registered: `resources` for the static-resource handler, `filter:<Class>` for a security-chain endpoint. The rows and `PublicPaths` are the same set of globs, reason for reason. An anonymous GET of the probe answers the row's status, never with a stack trace and never with a session cookie. An anonymous POST, PUT and DELETE on the probe (with a matching CSRF pair, so the refusal measured is the route's) are `refused` (403, 404 or 405). The exceptions: the row names the suites that own those verbs (`own E2E:` — the login and OIDC flows are tested where they live, and the test checks that those suites exist), or states that every verb gets the GET's answer (`same answer:`). The "principal · write · content" cell is the reviewed claim; no test measures datastore writes here (the record's slice B effect witnesses will).

| Glob | Reason (`PublicPaths`) | Handlers | Probe | Answer | Principal · write · content | Other verbs |
|---|---|---|---|---|---|---|
| `/` | The marketing home page: constant content, no datastore, no principal — public by design (033/D4). | `GET / SiteController#home` | `/` | 200 | no principal · no write · constant | refused |
| `/site/**` | The marketing site's own css/js/img assets; the design system it references rides /vendor/** below. | `resources` | `/site/css/site.css` | 200 | no principal · no write · static asset | refused |
| `/mcp-server-for-sql-databases` | Intent-cluster page: GET-only constant content whose only live fact is the compile-time MCP tool count. | `GET /mcp-server-for-sql-databases SitePagesController#pillar` | `/mcp-server-for-sql-databases` | 200 | no principal · no write · constant | refused |
| `/mcp-server/*` | Per-engine intent-cluster pages, one segment deep and enumerated by the page registry, not globbed open. | `GET /mcp-server/{engine} SitePagesController#engine` | `/mcp-server/postgres` | 200 | no principal · no write · constant | refused |
| `/add-mcp-server-to-claude-code` | Intent-cluster page: GET-only constant content, no datastore and no principal on the request. | `GET /add-mcp-server-to-claude-code SitePagesController#addToClaudeCode` | `/add-mcp-server-to-claude-code` | 200 | no principal · no write · constant | refused |
| `/ai-data-pipeline` | Intent-cluster page: GET-only constant content, no datastore and no principal on the request. | `GET /ai-data-pipeline SitePagesController#aiDataPipeline` | `/ai-data-pipeline` | 200 | no principal · no write · constant | refused |
| `/text-to-sql-agent` | Intent-cluster page: GET-only constant content, no datastore and no principal on the request. | `GET /text-to-sql-agent SitePagesController#textToSqlAgent` | `/text-to-sql-agent` | 200 | no principal · no write · constant | refused |
| `/compare/*` | The comparison pages (airflow, dbt): GET-only constant content, one segment deep, no datastore. | `GET /compare/airflow SitePagesController#compareAirflow` `GET /compare/dbt SitePagesController#compareDbt` `GET /compare/dagster-vs-airflow SiteV2Batch2Controller#compareDagsterAirflow` `GET /compare/fivetran-airbyte SiteV2Batch2Controller#compareFivetranAirbyte` `GET /compare/postgres-only SiteV2Batch2Controller#comparePostgresOnly` | `/compare/dbt` | 200 | no principal · no write · constant | refused |
| `/federated-query` | Intent-cluster page: GET-only constant content, no datastore and no principal on the request. | `GET /federated-query SitePagesController#federatedQuery` | `/federated-query` | 200 | no principal · no write · constant | refused |
| `/dp-lake` | The dp-lake product page: same shape and same reasoning as the 073 intent-cluster pages. | `GET /dp-lake SitePagesController#dpLake` | `/dp-lake` | 200 | no principal · no write · constant | refused |
| `/faq` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /faq SitePagesController#faq` | `/faq` | 200 | no principal · no write · constant | refused |
| `/roadmap` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /roadmap SitePagesController#roadmap` | `/roadmap` | 200 | no principal · no write · constant | refused |
| `/security` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /security SitePagesController#security` | `/security` | 200 | no principal · no write · constant | refused |
| `/published-api` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /published-api SitePagesController#publishedApi` | `/published-api` | 200 | no principal · no write · constant | refused |
| `/mcp-tools` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /mcp-tools SitePagesController#mcpTools` | `/mcp-tools` | 200 | no principal · no write · constant | refused |
| `/tableau` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /tableau SitePagesController#tableau` | `/tableau` | 200 | no principal · no write · constant | refused |
| `/tableau/*` | Site v2 intent pages under the Tableau hub: GET-only constant content, no datastore and no principal on the request. | `GET /tableau/governed-dataset SitePagesController#tableauGovernedDataset` `GET /tableau/extracts-alerts-dashboards SiteV2Batch2Controller#tableauRoadmap` `GET /tableau/prep-vs-pipelines-as-code SiteV2Batch2Controller#tableauPrep` | `/tableau/governed-dataset` | 200 | no principal · no write · constant | refused |
| `/for/*` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /for/analysts SiteV2Batch2Controller#forAnalysts` `GET /for/agencies SiteV2Batch2Controller#forAgencies` `GET /for/saas-teams SiteV2Batch2Controller#forSaasTeams` | `/for/analysts` | 200 | no principal · no write · constant | refused |
| `/how-it-works` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /how-it-works SitePagesController#howItWorks` | `/how-it-works` | 200 | no principal · no write · constant | refused |
| `/demo-data` | Demo-data page: GET-only, renders the vendored manifests parsed at startup; no datastore, no principal. | `GET /demo-data SitePagesController#demoData` | `/demo-data` | 200 | no principal · no write · vendored manifests read at startup | refused |
| `/pricing` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /pricing SiteV2Batch2Controller#pricing` | `/pricing` | 200 | no principal · no write · constant | refused |
| `/semantic-layer` | Site v2 intent page: GET-only constant content, no datastore and no principal on the request. | `GET /semantic-layer SiteV2Batch2Controller#semanticLayer` | `/semantic-layer` | 200 | no principal · no write · constant | refused |
| `/use-cases` | Site v3 hub page: GET-only constant content, no datastore and no principal on the request. | `GET /use-cases SiteHubController#useCases` | `/use-cases` | 200 | no principal · no write · constant | refused |
| `/explore` | Site v3 directory, generated from the page registry and the packaged docs catalog; no datastore, no principal. | `GET /explore SiteHubController#explore` | `/explore` | 200 | no principal · no write · generated from the page registry and the docs catalog | refused |
| `/docs` | The in-product spec index: packaged Markdown, no principal, no datastore, already public in the AGPL repo. | `GET /docs DocsController#index` | `/docs` | 200 | no principal · no write · packaged Markdown | refused |
| `/docs/*` | One packaged spec per slug, rendered from the jar or raw at its .md twin (173); the same text is public on GitHub. | `GET /docs/{slug} DocsController#doc` `GET /docs/{slug} DocsController#markdown` `GET /docs/{slug}.md DocsController#markdown` | `/docs/auth` | 200 | no principal · no write · packaged Markdown | refused |
| `/skill.md` | The agent skill's core, raw: it is the MANUAL, so requiring a key would gate learning how to use the key. | `GET /skill.md SkillController#skill` | `/skill.md` | 200 | no principal · no write · the packaged skill | refused |
| `/skill/*` | The skill's reference files, packaged in the jar and identical to the public AGPL repository's text. | `GET /skill/{name}.md SkillController#reference` | `/skill/endpoints.md` | 200 | no principal · no write · the packaged skill | refused |
| `/robots.txt` | Crawler infrastructure: a document that is meaningless unless it is readable without a login. | `resources` | `/robots.txt` | 200 | no principal · no write · static file | refused |
| `/sitemap.xml` | Generated from the page registry and packaged doc slugs; a sitemap behind auth indexes nothing. | `GET /sitemap.xml SitemapController#sitemap` | `/sitemap.xml` | 200 | no principal · no write · generated from the registries | refused |
| `/llms.txt` | The llms.txt index, generated from the page registry and the docs catalog; an agent index behind auth indexes nothing. | `GET /llms.txt LlmsTxtController#index` | `/llms.txt` | 200 | no principal · no write · generated from the registries | refused |
| `/llms-full.txt` | The full llms.txt: every packaged doc's Markdown, already public at its own .md route and on GitHub. | `GET /llms-full.txt LlmsTxtController#full` | `/llms-full.txt` | 200 | no principal · no write · packaged Markdown | refused |
| `/health` | Liveness probe for orchestrators, which present no credential; flattened UP/DOWN plus version only. | `GET /health HealthController#health` | `/health` | 200 | no principal · no write · probe state and version | refused |
| `/ready` | Readiness probe for orchestrators, which present no credential; flattened UP/DOWN plus version only. | `GET /ready HealthController#ready` | `/ready` | 200 | no principal · no write · probe state and version | refused |
| `/info` | Build info (version, build time, commit) — the deployment's own identity, no principal data. | `GET /info HealthController#info` | `/info` | 200 | no principal · no write · build info | refused |
| `/login` | The login page and the local password POST: the surface a caller uses BEFORE it has a credential. | `GET /login UiController#login` `POST /login LocalLoginController#login` | `/login` | 200 | GET: no principal · no write · the login page. POST: a login attempt — the lockout counter and the audit row (§5A.3) | own E2E: LocalLoginE2eTest, LocalAdminSeedE2eTest |
| `/login/**` | Login error redirects and the OIDC callback subtree, reached before any session exists. | `filter:OAuth2LoginAuthenticationFilter` | `/login/oauth2/code/google` | 302 | the OIDC callback: a session only on a completed ceremony (§5.5) | own E2E: OidcLoginIntegrationTest, OidcOnlyRegressionE2eTest |
| `/oauth2/**` | The OIDC authorization redirect and callback endpoints, by definition pre-authentication. | `filter:OAuth2AuthorizationRequestRedirectFilter` | `/oauth2/authorization/google` | 302 | the redirect to the identity provider: the authorization-request cookie only | own E2E: OidcLoginIntegrationTest, PkceAuthorizationRequestTest |
| `/vendor/**` | Vendored static assets (design system, fonts, icons, Alpine, Cytoscape, htmx) served to the login page too. | `resources` | `/vendor/htmx/htmx.min.js` | 200 | no principal · no write · static asset | refused |
| `/css/**` | The application stylesheets, which the unauthenticated login and error pages already render. | `resources` | `/css/app.css` | 200 | no principal · no write · static asset | refused |
| `/js/**` | The application scripts, which the unauthenticated login and error pages already render. | `resources` | `/js/csrf.js` | 200 | no principal · no write · static asset | refused |
| `/favicon.ico` | The site icon, requested by the browser on the login page before any credential exists. | `resources` | `/favicon.ico` | 200 | no principal · no write · static asset | refused |
| `/error` | Boot's default error page with no stack trace or message; an anonymous error must not loop through login. | `GET /error BasicErrorController#errorHtml` `GET /error BasicErrorController#error` | `/error` | 500 | no principal · no write · Boot's error page, no stack or message | same answer: 500 |

---

## 9. Auth Errors

Codes follow the `{domain}.{entity}.{failure}` convention; the registry of record is [Pipeline Contract §13.7](pipeline-contract.md#137-authentication--authorization).

| Code | HTTP | Description |
|---|---|---|
| `auth.api_key.expiry_invalid` | 400 | Key issuance named an unusable expiry — unknown preset, unparseable date, a date in the past, or `custom` with no date (§7.4). `details.reason` names which |
| `auth.login.domain_not_allowed` | 403 | Email domain not in allowlist (OIDC) |
| `auth.login.user_inactive` | 403 | User account deactivated (OIDC or local) |
| `auth.login.bad_credentials` | 401 | Local login rejected: email unknown or password incorrect — deliberately identical (§5A.5) |
| `auth.login.locked` | 403 | Local account locked after consecutive failures (§5A.3) |
| `auth.password.change_required` | 403 | Session principal must change password before any other operation (§5A.4) |
| `auth.login.oidc_error` | 500 | OIDC provider returned an error during login |
| `auth.session.expired` | 401 | JWT expired |
| `auth.session.invalid` | 401 | JWT signature invalid or malformed |
| `auth.api_key.missing` | 401 | No `DP-API-Key` header, no Bearer `dpk_` token, no `dp_session` cookie |
| `auth.api_key.invalid` | 401 | Key id not found, revoked, hash mismatch, or the row of the user it acts as gone — a deactivated member or identity is `auth.principal_deactivated` |
| `auth.api_key.expired` | 401 | Key's `expires_at` is in the past |
| `auth.permission.undeclared` | 403 | The route or tool declares no catalog permission, so nothing can judge it (§7.6) — a build defect the coverage guards fail, kept as the runtime defence. `details.route` or `details.tool` names the surface. Replaced `auth.scope.insufficient` (#215 PK8) |
| `auth.role_required` | 403 | Principal's role lacks the required PERMISSION in the active workspace — the role axis of the §7.6 matrix (§11A). `details.required` is the catalog permission (`pipeline.update`); `details.held` the ROLE it was judged as (`viewer` … `workspace_admin`, `super_admin`, a key role) — informative only, never compared (#215). The one authorization refusal for every principal since slice (b); the MCP ownership rules ride it too (`details.reason`: `not_creator`, `started_outside_mcp`, `different_credential`) |
| `auth.key_issuer_role_lost` | 403 | A key principal judged through a workspace CONTEXT (the promotion config-value peer) lacks the permission (§7.5). Since keys v2 every key carries its own role and refuses with `auth.role_required`; this code remains for the context-judged path. Retrying with the same credential will not help |
| `auth.key_kind_not_mintable` | 400 | Keys v2 (§7.4): issuance named NO kind — there is no default kind since the login mint retired (A15); name `mcp`, `endpoint` or `server`. A request naming the retired `user` wire word is an unknown kind (`endpoint.key_kind_refused`) |
| `auth.key_workspace_inactive` | 404 | The key's pinned workspace is deactivated (§11A); reactivating it restores the key |
| `auth.key_name_taken` | 409 | Keys v2 A18 (§7.4): the create path named a key that already exists LIVE in the workspace — the unique index's clean refusal ahead of the constraint's raw failure. `details.reason` is `key_name_taken`; revoking the existing key frees the name |
| `auth.principal_deactivated` | 401 | The principal's user is deactivated (§11A.3) — a session's or MCP key's member, or an `endpoint`/`server` key's own identity (§4.7); judged by `PrincipalLiveness` where the credential becomes a principal, within the §11.4 window. A session's cookie is cleared and an HTML navigation lands on `/login?error=inactive`. Never on `/api/v1/promotion/**`, where every refusal is `auth.promotion.key_invalid` |
| `auth.csrf.invalid` | 403 | CSRF token missing or mismatched on a state-changing UI request (`details.reason`: `missing` \| `mismatch`) |
| `auth.promotion.key_invalid` | 401 | The promotion peer's pre-shared server key was absent, malformed, or did not match — and the same code when the receiver has no key configured, so promotion-disabled is indistinguishable from wrong-key ([Versioning §10.6](versioning.md#106-the-promotion-peer-credential--a-shared-server-key-ratified-2026-09-01)) |

Rate limiting uses the single system-wide `rate_limit.exceeded` code ([Pipeline Contract §13.11](pipeline-contract.md#1311-rate-limiting--idempotency)) — there is no separate auth-layer rate-limit code. The two limiters answer with DIFFERENT user messages (#232): the login damper says "Too many sign-in attempts. Wait a minute and try again." (what it counts — sign-in attempts), while the per-user API limiter and every API surface answer with the error catalog's request-volume sentence, "You're sending requests faster than we allow. Wait a moment and try again." (§13.11 carries both). The login damper is **in-process** (per instance, no Redis), so it never raises the companion `rate_limit.unavailable`: that code belongs to the shared per-user limiter, whose counters live in Redis and which fails closed when they cannot be read ([REST API §12.3](rest-api.md#123-when-the-limiter-itself-is-unavailable)). The login rate limit is `datapipelines.auth.rate-limit.login-per-minute` ([Configuration §3.4](configuration.md#34-auth)), and **the address it buckets by — like every audit `source_ip` — is resolved through `datapipelines.auth.trusted-proxies`** ([Deployment §6.2](deployment.md#62-multi-instance-horizontal-scaling-production)): behind a load balancer, without that list every request shares the LB's address and the per-IP limiter becomes a global one. Empty (the default) means `X-Forwarded-For` is ignored entirely and the direct peer is the client, so a bare deployment is unaffected.

Workspace resolution failures (§5.6) use the `workspace.*` codes — catalogued in [Pipeline Contract §13.12](pipeline-contract.md#1312-workspace-resolution). **The 404 rule governs them**: an addressed workspace the caller cannot reach — unknown, non-member, or deactivated — is one answer, `workspace.not_found` (404), never a 403. `workspace.membership_required` (403) survives only where NO workspace was addressed (a principal with zero memberships), because there is no name there whose existence a 403 could leak. `workspace.creation_forbidden` was retired with the provisioning modes (§4.2).

---

## 10. Audit Log

### 10.1 Events

| Event | Trigger |
|---|---|
| `auth.login.success` | Login succeeded, JWT issued (OIDC or local — the details' `provider` names the method) |
| `auth.login.domain_not_allowed` | User's email domain not in allowlist |
| `auth.super_admin_acting` | A super admin acted in a workspace they hold no explicit membership in (§11A). Emitted by `ScopeInterceptor` at the one choke point every governed handler passes — **reads included**: the 404 rule's promise is that a workspace is invisible from outside, and the one principal exempt from that promise is the one whose reads most need to be on the record. `details`: the operation, workspace, path, method, and `acting_via: super_admin` |
| `workspace.member_added` | A member was added, with their role (`details.role`). The first-login demo join (§4.2) carries `reason: first_login_demo_viewer` |
| `workspace.member_invited` | An invitation was created (§4.6) — or an existing one's role REPLACED by a re-invite; the upsert is the latest admin decision winning, so it is audited every time it fires. `details`: workspace, email, role |
| `workspace.invitation_revoked` | A pending invitation was revoked (§4.6). `details`: workspace, email |
| `workspace.invitation_materialised` | A pending invitation became a real membership at login (§4.2 step 2a / §4.6). `details`: workspace, email, role, and `inviter` — the actor whose decision the login is executing |
| `workspace.member_removed` | A member was removed |
| `workspace.member_role_changed` | A member's role was replaced (D1; was `workspace.member_flags_changed`) — `details.from` and `details.to` carry both roles, because a membership row keeps no history of its own |
| `workspace.deactivated` | A workspace was deactivated (§11A). Nothing it owns is purged |
| `workspace.reactivated` | A deactivated workspace was restored |
| `datasource.granted` | A datasource was granted to a workspace — the verb that decides who can reach a live database's data. `details.already_granted` separates a new grant from an idempotent re-grant |
| `datasource.revoked` | A datasource's grant to a workspace was removed; the datasource itself is untouched |
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
| `auth.api_key.revoked_by_admin` | A member's login-minted key was revoked by a workspace admin or super admin (#200; roles record §3.7) — because their membership ended (`details.reason: member_removed`, the same act that removes them) or as an explicit act that keeps the member (`reason: admin_revoked`). `details` carry workspace, `target_user_id` and reason; `key_id` is the revoked key |
| `auth.api_key.used` | API key validated (sampled 1/100) |
| `auth.api_key.rejected` | API key validation failed |
| `auth.scope.denied` | Request rejected for insufficient scope |
| `auth.user.deactivated` | Admin deactivated a user |
| `auth.user.activated` | Admin reactivated a user |
| `auth.user.admin_granted` | Admin granted admin scope to user |
| `auth.user.admin_revoked` | Admin revoked admin scope from user |
| `auth.workspace.created` | Workspace created through the service path |
| `auth.workspace.header_rejected` | `DP-Workspace` presented on an API-key request (§5.6) |

The same `audit_log` table also carries the **promotion events** — `auth.promotion.rejected` when the peer-credential gate refuses a request, `auth.promotion.accepted` when a batch is applied ([Versioning §10](versioning.md#10-promotion-ui-driven-separate-use-case)) — the **datasource decryption events** ([Datasources §7.4](datasources.md#74-decryption-points-and-audit-log)), the **MCP tool events** — `mcp.tool.called` for every tool call, `mcp.tool.write` for every mutating one — emitted by the MCP dispatcher through the same sink ([MCP §14](mcp-server.md#14-audit)), and the **mail events** — `mail.sent` / `mail.failed` for every notice §5A.8 sends, kind and recipients in `details` and never a body (all registered in [Enums §15](enums.md#15-authauditevent--auth-audit-log-events)).

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
          # picture-hosts: lh3.googleusercontent.com   # #197 — see below; empty = no avatar fetch
          # trust-email-without-verified-claim: true   # default false (§4.2/#187) — only for
          #                                            # an IdP that never emits the claim

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
```

**Per provider, three values are required:**
- `client-id` — from the OIDC provider's app registration.
- `client-secret` — from the provider's app registration.
- `issuer-uri` — the provider's OIDC issuer URL. Triggers auto-discovery of all endpoints.

**Two further values:**
- `name` — **required.** Registration ID used in URLs (`/oauth2/authorization/{name}`) and stored in `users.provider`. Lowercase `[a-z0-9-]+`. (The §5.2 bean uses it directly; there is no derivation fallback.)
- `display-name` — optional. Text shown on the login button; defaults to `name`.

**One further value (#197):**
- `picture-hosts` — optional, comma-separated bare hostnames, **default empty**. The allowlist the avatar proxy (`GET /avatar`, §7.6 `profile.read`) may fetch the stored `users.profile_picture_url` from, so the CSP's `img-src` can stay `'self' data:` and the browser never loads an image from an identity provider's host. The host is NOT derivable from `issuer-uri` (Google issues from `accounts.google.com`, serves pictures from `lh3.googleusercontent.com`; Entra's pictures live on Microsoft Graph; a self-hosted Keycloak's sit wherever its operator mapped them), so the operator names the hosts the deployment trusts. Empty (the default) = the proxy fetches nothing and every avatar falls back to initials — exactly the rendering a login without a `picture` claim already gets. A non-hostname entry (scheme, port, path, userinfo or wildcard) refuses startup. The fetch itself is fenced: the caller's OWN row only, http/https, no userinfo component, redirects never followed, an image content type required, a 1 MiB cap on the body, and a small private TTL cache.

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

Validated API keys, user liveness and memberships are cached in-memory per instance for `datapipelines.auth.api-keys.cache-ttl-seconds` (default 60). Revocation, deactivation and a role change invalidate the local cache immediately and take effect elsewhere **within the TTL — 60 s by default** (#215 B5, record amendment A9: this is the freshness contract; no eviction bus, no Redis stamp).

**Work already in flight (security-assurance record P4, owner ruling 2026-09-24).** The bound above governs every NEW request. An execution that is already running when its caller's authority is revoked keeps that authority to completion, under the executor's own drain and cancel rules; revocation never cancels it. An open SSE stream is to be cut at its next event after revocation. **That half is not built yet:** today a stream is authorized once, when it opens, and runs to its execution's end ([#230](https://github.com/msabiransari/datapipelines/issues/230)).

### 11.5 Other auth configuration keys

All auth config keys (allowlist domains, JWT TTL, cache TTL, login rate limit) are defined in [Configuration §3.4](configuration.md#34-auth) — the single config authority. This spec does not restate names or defaults.

**The login limiter's window is per INSTANCE (096 §F, review finding F8).** It is an in-memory
fixed one-minute window per client address, held in each replica's own heap — so N replicas
behind a load balancer give a client that lands on all of them an effective budget of
**N × `login-per-minute`**. Deliberate: this is a brute-force damper, not a distributed quota,
and a shared store on the login path costs more than it buys.

It also **fails OPEN** at its ceiling: the table is bounded at 10,000 tracked clients, and once
it is full (after sweeping rolled-over windows) a new client is admitted UNMETERED rather than
the map being grown, so a spoofed-source-IP flood can never exhaust the heap. That is the
owner's posture and it is unchanged; what 096 added is that each such admission increments
`datapipelines.auth.login_rate_limit.saturated` ([Observability §4.1](observability.md#41-metric-naming)),
so saturation is a rate to alert on rather than a WARN line lost inside the flood that caused
it. The per-user API/MCP limiter ([Configuration §3.7](configuration.md#37-rate-limiting)) is
a different budget with a different key and fails CLOSED — the two are not interchangeable.

---

## 11A. Roles

**Permission lives in the workspace membership, not on the user** (RBAC design D-R1, ratified 2026-09-10; roles design D1, ratified 2026-09-20). A person is a viewer in one workspace and an author in another; there is no global "author" any more. The one global authority left is `users.is_admin`, which means **super admin** — the instance.

A membership row (`workspace_members`, [metadata-db §4.12](metadata-db.md#412-workspace_members)) carries **exactly one role** — `role`, one of `viewer`, `author`, `promoter`, `workspace_admin` (V29; [`WorkspaceRole`](enums.md#8c-workspacerole--the-one-role-a-membership-holds)). V23 had split the role into three additive booleans so that "an author who also releases" could be one row; the 2026-09-20 rulings took release away from the promoter and made it an ops role that authors nothing, so the combination the booleans existed for no longer exists and the row is one value again. The migration's precedence for old rows: admin → `workspace_admin`, else promoter → `promoter`, else author → `author`, else `viewer`. An invitation ([§4.6](#46-invitations)) carries the same one role (D20).

**Vocabulary (D21).** A **role** is what a member holds. A **permission** is an action a role may perform — a ROW of the §7.6 matrix (`Permission` in code: the 65 `<functionality>.<permission>` catalog rows, `pipeline.read` …, each held by the roles its §7.6 row admits). "The author role has the permission to release" is a sentence about a row; a permission is never a free string, and nothing in the product grants or stores one.

The five roles, as the record's §2 ratifies them (the row-by-row table is §7.6; this is the shape):

| Role | Holds | Does NOT hold |
|---|---|---|
| **viewer** (D3) | reads everything in the workspace; executes pipelines, cancels and reads its OWN runs and results; tests a datasource connection; introspects schemas | authoring, release, promotion, members |
| **author** (D4, D8, D9) | the viewer's rows + create/edit/discard/restore/purge pipelines and templates, **release**, switch the served version, publish endpoints, register lake tables, record/retire learned facts, read the promotion page | promote, members, workspace datasources |
| **promoter** (D5) | reads datasources; reads pipelines, templates and endpoints through the **lens** (§11A.1: released, and newer than the promotion target's — what is promotable, and nothing else); introspects schemas; reads the promotion page; **promotes** | execute, cancel, execution and result reads, connection test, authoring, release, switch, members. Promoters are ops people. |
| **workspace admin** (D6) | everything in the workspace: every row above plus members, roles and invitations, workspace-bound datasources, the workspaces page, the audit trail (D12 — no surface yet), and promotion | the instance verbs |
| **super admin** (D7) | everything everywhere: every workspace's rows without a membership (audited `acting_via=super_admin`), global datasources and grants, workspaces, users | — |

Reads are cumulative — viewer ⊂ author ⊂ workspace admin ⊂ super admin — and the promoter is NOT on that chain: it reads datasources as a viewer does, reads pipelines, templates and endpoints through the lens (§11A.1), reads no executions, and adds the promote verb.

Two rows changed hands on 2026-09-20 and are worth naming: **release** is the author's (it was the promoter's — "the author releases, the promoter promotes", D8), and the **workspaces page** is the workspace admin's (D13) while the switcher in the chrome stays every member's. **Executions** became own-or-admin on every surface (D11): a member's list is their own runs, a workspace admin's is the workspace's, a promoter's does not exist; an endpoint-key run is nobody's own and lists for admins only (`executed_by_key_kind`, [metadata-db §4.6](metadata-db.md#46-pipeline_executions)).

### 11A.1 The 404 rule

**A workspace you cannot reach does not exist.** Non-member, unknown name, and deactivated are ONE answer — `404 workspace.not_found`, the same body a genuinely missing row produces — on every surface: REST, MCP, UI pages and htmx partials. **Never 403 for a foreign id.** The same rule covers every entity inside a workspace: an id or name from another workspace is not-found, not forbidden, because a 403 would turn a flat namespace into an enumeration oracle one request at a time.

The rule is mechanical, not a convention: every repository read that a caller-supplied id or name can reach carries the workspace predicate IN ITS SQL, and `WorkspaceIsolationSweepTest` walks every REST route and every MCP tool with a foreign workspace's identifiers and asserts the not-found answer on every one.

**The lens clause (178, roles design §3.1).** For a principal whose role in the active workspace is **promoter** (and not a super admin), the same rule covers every pipeline and template the promotion rule does not list: an object is VISIBLE iff it has a current RELEASED version and that version is newer than the promotion target's inventory entry for the same name — [Versioning §10.2](versioning.md#102-the-listing-rule-what-the-ui-shows)'s rule, the target's absence counting as version 0, the same content hash counting as "nothing to push" — and an endpoint is visible iff its pipeline is. Everything else — a draft, an already-promoted object, one the target serves at a higher version — is ABSENT for that principal: lists, levels, counts, search, the rail badges, the resource catalogue and `used_by` leave it out, and a get by id or name answers exactly the not-found an id from another workspace gets. A visible object's pending DRAFT is invisible too ("it should hide draft"): the working version a promoter reads is the current RELEASE, the draft pointer and the list badge are absent, DRAFT rows leave the version list, and an explicit DRAFT version number is not-found. The rule covers VERSION CONTENT, not only names (178b): the node-SQL partial renders the release's node with the pinned template read through the lens (a hidden or DRAFT template version is "template not found"), the checks reads refuse a DRAFT version exactly as an absent one (`PipelineService.findExecutable` under the lens), and `used_by` / the used-by pane list RELEASED pins of RELEASED template versions only — a draft's number or status never travels. The rule is applied ONCE per request (`PromotableView`, the same computation the promotion page lists from) and passed as a value into every read (`PipelineService`, `TemplateService`, `EndpointPublishService`) — never a thread-local, because an MCP tool runs where no security context exists. The higher environment is read through a per-workspace cache (`inventory-cache-ttl-seconds`, [Configuration §3.19](configuration.md#319-deployment)); when it cannot be read the lens FAILS CLOSED — the promoter sees nothing, the list screens say why in the promotion page's words, a read is never a 502 — and `pipeline.promotion.lens_unavailable` is logged once per window. Both credential kinds are lensed alike: a promoter's API key is a promoter. `PromoterLensSweepTest` walks every promoter-reachable GET route and read tool with a hidden and an absent identifier and demands identical answers, exact lists, a leak-free body, a viewer control that never touches the target, and the fail-closed walk.

`workspace.membership_required` (403) survives in exactly one place: a principal with ZERO memberships, which addressed no workspace at all and so has no name to protect. Two permissions are judged WITHOUT a workspace context for a session: `workspace.read` (the page) and `workspace.switch` (the REST list-own, §17.1) — "which workspaces do I belong to" is meaningful when the answer is none, and it is how a zero-membership person reaches the no-workspace page ([UI §4.13](ui-screens.md#413-workspaces-workspaces-design-9-members-and-deactivation-rewritten-by-114)) instead of a JSON 404. A key never gets that exception: a key with no context is a key whose workspace is gone, and it stays the 404.

A second null-context exception stands beside it (#113): a **super admin session** keeps the INSTANCE verbs — the operations whose permission is `super_admin` (create / deactivate / reactivate / delete a workspace, user administration, instance datasources and their grants) — when NO workspace is reachable at all. Those operations are instance-level by construction: none of them reads or writes a workspace's content, and refusing them stranded the one principal who could repair an empty deployment (deactivate the last active workspace, and even the reactivate verb answered `workspace.not_found`). The evidence is the user row's `is_admin`, re-read per request through the auth cache — not a context the request does not have. Every workspace-scoped operation stays the 404 for that principal, and a key never gets this exception either: no key is ever a super admin (B1, §7.5).

### 11A.2 Super admins

A super admin is an **implicit member of every workspace** (D-R8) and resolves any workspace through the SAME path everyone else does — no bypass branch, so there is no second code path to keep correct. Every action they take in a workspace where they hold no explicit membership is audited as `auth.super_admin_acting` with `acting_via=super_admin`, reads included (§10.1 says why).

A deactivated workspace is not selectable by a super admin either: [§11A.3](#11a3-deactivation)'s first effect has no exception, and a super admin who needs to act inside one reactivates it first — an audited, reversible step.

### 11A.3 Deactivation

**Deactivate, never delete** (D-R10, D15). Deactivation — of a USER (`users.is_active`, §4.2) or of a WORKSPACE (`workspaces.deactivated_at`) — purges nothing and revokes nothing; it is reversible, and reactivation restores every credential. What it does is make the principal or the workspace **served nothing**, through ONE predicate.

**The predicate.** `PrincipalLiveness` answers "user active ∧ (pinned workspace active, when the credential pins one)", both reads through the §11.4 liveness cache (so a valid request costs zero extra queries, and a deactivation takes effect on the instance that performed it at once and everywhere else within one TTL, ~60 s by default). It is judged where each credential BECOMES a principal — the earliest point a refusal can happen, and the one every surface passes through because the security chain is global, so no boundary downstream restates it and no new surface can forget it:

| where | credential | deactivated USER | deactivated WORKSPACE |
|---|---|---|---|
| `JwtAuthenticationFilter` | session | `auth.principal_deactivated` 401 on the API; a page navigation is sent to `/login?error=inactive`, cookie cleared either way | a session never resolves one: the stamped claim falls through to another active membership (§5), `DP-Workspace` naming it is `workspace.not_found` 404 |
| `ApiKeyService.validate` — `/mcp`, published endpoints | MCP key, `endpoint` key | `auth.principal_deactivated` 401 — the key's own identity for every kind, and for an `mcp` key its CREATOR besides (A20: a deactivated person's `mcp` keys are refused until reactivation; `endpoint`/`server` keys stay creator-independent, PK2) | `auth.key_workspace_inactive` 404 (the pin) |
| `ApiKeyService.validateServerKey` — `/api/v1/promotion/**` | `server` key | `auth.promotion.key_invalid` 401 — the peer's ONE answer, whatever failed | `auth.promotion.key_invalid` 401 (the pin, closed in 180) |
| `PublishedEndpointServeService` | any key, the ENDPOINT's own workspace | — | the unknown-path 404, byte-identical, before the pipeline is read |

The USER case has one code on every surface but the promotion peer. The WORKSPACE case keeps the older 404 rule ([§11A.1](#11a1-the-404-rule)): a deactivated workspace is indistinguishable from one that does not exist — `auth.key_workspace_inactive` stays distinct only because a key's holder is a member by construction and already knows the pin.

**A deactivated workspace's five effects**, all consequences of readers consulting `Workspace.isActive` (or its cached form, the predicate):

1. it cannot be selected — the switcher hides it, and `DP-Workspace` naming it answers 404 like a non-membership;
2. its published endpoints are unknown paths — to every caller, not only to keys pinned there;
3. API keys pinned to it are refused with `auth.key_workspace_inactive` (404), server keys with the promotion peer's one answer;
4. `WorkspaceLiveness` answers false for it — the question a scheduler will ask (no consumer exists yet; the interface is the hook);
5. a super admin's listing shows it greyed with the date.

To a MEMBER a deactivated workspace is indistinguishable from one that never existed, so deactivation is not a signal anybody can read. Reactivation is a super-admin verb and is audited. `DeactivationSweepTest` proves the whole table on the live server: every route and every tool, for a deactivated session and for keys with a deactivated identity, pin or — `mcp` keys only (A20) — deactivated creator, plus the differential (deactivated vs unknown workspace: same body on every route) and the promotion peer's one body.

There is deliberately NO last-active-workspace guard: decommissioning the final workspace is a legitimate operator act, and it is recoverable — with zero active workspaces the super admin's session resolves no workspace at all, and §11A.1's second null-context exception (#113) keeps the instance verbs, reactivation included, available to exactly that principal.

### 11A.4 Keys

Keys v2 (#233, A13–A19; the record §3 as amended): **a key is a robot member of one workspace** — kind, workspace, identity, role, created by. The `mcp` key carries the member role CHOSEN AT CREATION under the subset rule and acts as its own identity (§4.7, §7.5) — nothing is derived from a membership any more, so a member's role change does not affect their existing keys at all (C2 retired with the derivation). `endpoint` (`api_caller`) and `server` (`promotion_receiver`) keys are unchanged in shape (PK2). No key is ever a super admin (B1). On every request, inside the validation-cache TTL (60 s by default): the key is live, its identity is active, and its pinned workspace is active.

**Lifecycle** (A15/A17): nothing is minted at sign-in; creation is the Keys page (or the REST twin) alone; revocation is the end state — the application never hard-deletes a key or an identity. The retention sweep's purge (§11C) deletes a revoked, unbound key and its deactivated identity only once no FK column references them (B5); removing a member revokes every key they created in that workspace (B6).

### 11C. The keys purge (keys v2 A17/B5)

**Revocation is the end state; the purge is the only delete.** The application never hard-deletes a key or an identity. As the retention sweep's LAST step (the hourly job that also retains execution events, `RetentionSchedulingConfiguration`), `KeyRetentionPurge` deletes:

- a REVOKED key that no `endpoint_key_bindings` row names, **and** whose identity nothing references — the two are one unit: an execution's `executed_by`, an audit row's `user_id`, a membership, an invitation, or a surviving key row keeps the pair alive (B5's falsification: a revoked key with one live execution STAYS; one with none goes);
- then the deactivated `service` identities that nothing references at all.

The "nothing references" predicate is DERIVED from the live schema (`information_schema`, every FK referencing `users(id)`), so a migration that adds a new FK automatically blocks the purge — the purge never disables a constraint and never guesses. A test enumerates the FKs independently and asserts the purge respects exactly that list; `KeysV2MigrationTest` and the retention tests prove both directions.

---

## 12. Implementation Notes

### 12.1 Where this lives

`auth` Gradle module:
- `co.datapipelines.auth.User` data class
- `co.datapipelines.auth.ApiKey` data class
- `co.datapipelines.auth.Permission`, `RolePermissions`, `KeyRole`, `UserKind` — the catalog, the one role table, the key roles, what a `users` row is
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
- [ ] Session cookies `HttpOnly`, `Secure`, `SameSite=Lax` (§5.5 — Strict is unusable with an IdP redirect chain; CSRF, not SameSite, is the control).
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
- **Group/role sync from provider** — map OIDC groups to internal roles automatically.
- **Per-datasource ACLs** — fine-grained access beyond roles.
- **Service accounts** — non-human principals for CI/CD.
- **MFA** — if provider enforces it (Google/Microsoft MFA is provider-side, transparent to us).

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-26 | v3.9 | scheduler lane 1 (#9) — numbered after origin/main's v3.6 (197), v3.7 (232), v3.8 (239) | **§4.5: the system account is the identity schedules fire under (R2)** — built in-process (`AuthMethod.SYSTEM`, `SystemActorPrincipals`), never by a filter (`WorkspaceResolutionFilter` refuses one that arrived on a request); `PermissionResolver`'s new system arm answers a FIXED set in any workspace — `pipeline.execute`, `pipeline.read`, `execution.read` — and nothing else (`RolePermissions.SYSTEM_ACTOR`, pinned by `SystemActorPrincipalTest`). **§7.6: 73 permissions** — `schedule.read` (every member; the promoter through the lens), `schedule.create`, `schedule.update`, `schedule.pause` (pause, resume, unblock), `schedule.delete`, `schedule.run` (Run now, L2) — author, workspace admin, super admin (R8); no key role holds any of them (slice 1 is session-only). `execution.read` covers the durable event record (`?format=json`) and every SCHEDULED run of the workspace (R3) — visibility, not ownership. **§8.6**: a new `scheduler-task` family — `schedule-dispatcher`, `schedule-reconciler`, `schedule-run`, read off every db-scheduler `Task` bean by `EntryInventoryE2eTest`, unreachable from any transport (B5). |
| 2026-09-26 | v3.8 | 239 (#239) the key through the seam | §7.5, mechanism only, no route/tool/cell changed: an `mcp` key's member-role admission asks the same `PermissionResolver` a session's decisions ask (security-assurance record §7.1/B4) — the production resolver reads the member column, so no observable answer changed, and the isolated-permission witness (`PermissionSeamE2eTest`) covers the MCP tool surface again (red-first on the direct read). The two transport key roles (`api_caller`, `promotion_receiver`) are still read from the table at admission. |
| 2026-09-25 | v3.7 | 232 (#232) the limiter's own sentence | §9: the two rate limiters answer with DIFFERENT user messages — the login damper keeps its sign-in sentence, the per-user API limiter answers with the catalog's request-volume sentence (§13.11 carries the split). The shared `RateLimitExceededException`'s message became a parameter defaulting to the login sentence; `RateLimitFilter` overrides with the catalog's. Code, status and `details` unchanged. |
| 2026-09-25 | v3.6 | 197 (#197) the avatar proxy | `img-src` narrowed to `'self' data:` — the standing `https:` grant is gone. The OIDC picture (`users.profile_picture_url`) is served by `GET /avatar` (§7.6 `profile.read` row gains the route): the signed-in principal's OWN row only, fetched server-side from the per-provider `picture-hosts` allowlist (§11.1 — NOT derivable from `issuer-uri`: Google issues from `accounts.google.com`, serves pictures from `lh3.googleusercontent.com`), redirects never followed, `image/*` only, 1 MiB cap, private TTL cache. The page's `<img>` points at the proxy; the provider URL never reaches the HTML. Deployment §6.2's CSP row and Configuration §3.4 carry the same story. |
| 2026-09-25 | v3.5 | keys v2 (#233) | **Every key is a robot member of one workspace** ([record](superpowers/specs/2026-09-23-permissions-and-keys-design.md) §10, A13–A19). **§7.4 issuance rewritten**: no key is minted at sign-in (A15 — D16, the top-bar chip and `minted_at_login` retire); the Keys page is the ONE creation path; an `mcp` key's role is CHOSEN at creation under the **subset rule** (A14): a creator may give a key any role whose §7.6 column is a subset of their own — `mcp_key.create` (author, promoter, ws admin) and `mcp_key.revoke_own` (author+) join the catalog; `api_key.create`/`api_key.revoke` become service-checked floors on the same routes. **§7.5**: the PK4 cap, the freshness rule (C2) and the admin cap (C3) are gone — the key's identity holds its chosen member role (`author` \| `promoter` \| `workspace_admin`; never viewer, A15) exactly as a member does, and a `promoter`-role key is lensed. **§7.6**: 67 permissions; the catalog gains the three `mcp:<role>` key-role columns; `POST /api/v1/auth/api-keys` + `POST /partials/api-keys` move to `mcp_key.create`, both delete routes to `mcp_key.revoke_own`, `GET /api-keys` to `mcp_key.own`; `GET /api/v1/auth/api-keys/mine`, `DELETE /partials/mcp-key` and `GET /partials/mcp-key/chip` are gone. **§7.7**: the `user` kind is renamed `mcp` (A19); the `endpoint` kind's reach is the published tree INCLUDING its result-paging routes and EXCLUDING the framework's `GET /api/v1/executions/…` (A16 — session-only now, with `ExecutionsController` refusing keys as the second line). **§9**: `auth.key_kind_not_mintable` reworded (no kind named); `auth.key_issuer_role_lost` narrowed to the context-judged path. **§11A.4/§11C**: keys and identities are never hard-deleted — revocation is the end state, the retention sweep's purge deletes only what nothing references (A17/B5), member removal revokes created keys (B6), and names are unique per workspace among live keys (A18). Migration: V37 (every live `user`-kind key — login-minted and the pre-R3 on-demand ones — converted or revoked, B4; the CHECK spells `role IS NOT NULL` on every role-bearing arm, because the three-valued-logic trap admits a NULL-role row otherwise). |
| 2026-09-25 | v3.4 | 7e (#7) the semantic link | §7.6: no route, tool or cell changed — the semantic link is fields on existing rows (transform-nodes design §9.4): `implements` on `template.create` / `template.update` (a released version too), `needs_review` and the `implements` list filter on `template.read`, `implemented_by` on `semantic.read` (lensed by the reader's `template.read`), the release response's `warnings` on `pipeline.release`. The row texts say so. |
| 2026-09-25 | v3.3 | 7d (#7) the transform editor face | §7.6: three UI routes join existing rows, no cell changed — `GET /partials/templates/transform-face` (the four panes, read-only for a non-author) on `template.read`; `POST /partials/templates/transform-face/save` (Save draft, 7b's write path and save gate) on `template.update`; `POST /partials/templates/transform-face/run-suite` (Run suite over the unsaved panes — nothing written) on `template.evaluate`, the row 7b's evaluate tool and route sit on (transform-nodes design §9.4). |
| 2026-09-24 | v3.2 | 217a (#217) entry inventory | **§8.6 (new): the entry inventory and the public contract** ([security-assurance record](superpowers/specs/2026-09-24-security-assurance-design.md) §4, ratified B3/B5). The inventory has one row per registration the running application holds that is not a handler method: servlets, container filters in order, the security chain's filters in order, handler mappings, scheduled jobs (each unreachable from a request, B5), and the packaged jar's listening sockets and actuator endpoints. `EntryInventoryE2eTest` compares it with the runtime both ways and places every handler route on §7.6 or §8.6.2. The public contract has one row per §8.3 glob: its reason, the handlers it covers, a probe and its anonymous answer, what it touches, and the other verbs; `PublicContractE2eTest` holds the running application to it. **§11.4**: work already in flight (P4): a running execution keeps its authority to completion, and an open stream is cut at its next event after revocation — not built yet (#230). The member permission decisions now ask one `PermissionResolver` (record §7.1, B4) — no answer changed. The ASVS 5.0.0 Level 2 index is [security-assurance.md](security-assurance.md). |
| 2026-09-24 | v3.1 | 215b (#215) key identities, key roles | **Scopes removed; keys carry roles** ([permissions and keys record](superpowers/specs/2026-09-23-permissions-and-keys-design.md) §3–§5, PK4–PK9). **§4.7 key identities** (new): an `endpoint`/`server` key acts as its own `users` row (`kind = 'service'`, provider `key`, `<key id>@keys.invalid`), created with the key in one transaction, deactivated by its revocation, managed only through its key; one predicate (`kind = 'human'`) guards every login, linking and user-admin path — closing the pre-existing hole where the identity reset could make the System row claimable. **§7.5 key roles** replaces scopes: `api_caller`, `promotion_receiver`, and the MCP key as its member capped at author (PK4); no key is ever a super admin (B1). **§7.6** gains the two key-role columns; the two key-scope tables are gone; the heading drops "two axes". **§7.7**: the MCP key is confined to `/mcp` (B2, owner ruling 2026-09-24); an unbound published path serves no one (B3); promotion intake is instance-wide and a stored server key acts as its identity (C4, B6). **§9**: `auth.permission.undeclared` replaces `auth.scope.insufficient`; `auth.key_scope_unavailable` retired. **§7.3/§11.4**: authority freshness is the 60 s auth cache TTL (B5, A9). Migration: V34 (PK9). |
| 2026-09-24 | v3.0 | 215a (#215) the permission catalog | **§7.6 is the permission catalog** ([permissions and keys record](superpowers/specs/2026-09-23-permissions-and-keys-design.md) §2, ratified 2026-09-23, amended 2026-09-24): 65 `<functionality>.<permission>` rows replace the thirty coarse operations and the separate MCP role table. Each row's Surfaces cell places every route (`VERB /path`) and tool, so the drift test, the reachability gate and the role walk read the placement from the doc; the cell alphabet gains **fenced** (the two promotion-receiving rows — no role holds them, super admin included). Handlers declare `@RequiredScope(Permission.X)`; tools their entry in `ScopeMatrix.MCP_TOOL_PERMISSION`; the one role table is `RolePermissions`. Every route keeps its role set and scope floor (the A4 shim: the permission key-scope table replaces the REST key-scope table); the MCP role axis moves on five cells — viewer × `datasources_preview_rows`, `sql_probe`, `pipelines_execute_node` and promoter × the two probes — which the record puts at author-and-above (keys minted at login were already refused them by scope; a member demoted from author keeps an author-scoped key, and that key is now refused them). Two named changes by owner ruling (2026-09-24): revoking a SERVER key is a super admin's (`server_key.revoke`; §7.4 — #191 had given it to the page's workspace admin), and retiring another workspace's DATASOURCE fact asks `datasource.manage` (the workspace admin, as before). Refusal details: `required` is the catalog permission, `held` the role (§9). Drifts fixed on the way: `GET /api/v1/endpoints` sits on `endpoint.read` (v2 said the publish row; the handler declared the read row); `DELETE /api/v1/workspaces/{name}` on `workspace.lifecycle` (v2 listed it on the update row too); the pinned-workspace tool count reads 38 of 42. Slice (b) removes scopes and this shim. |
| 2026-09-23 | v2.37 | 7b (#7) templates_evaluate | §7.6 MCP table 41 → **42** tools: `templates_evaluate` joins the `author`/`author` row — the `templates_render` row (R6: evaluating untrusted code on the server is the same authoring act as rendering; viewer ✗, promoter ✗). The REST twin `POST /api/v1/templates/evaluate` declares the existing `MUTATE_PIPELINES_TEMPLATES` row (no new RestOperation); its row now names the route. Both `ScopeMatrixSpecDriftTest` counts moved 41 → 42 in the same commit. |
| 2026-09-23 | v2.36 | 213 (#213) show-once MCP key | **§7.4: the login-minted key is copyable ONCE** (D16 amended 2026-09-23): the sealed copy exists until its first read and is destroyed by it — the open and the clear are one owner-scoped statement (`ApiKeyRepository.openAndClearSealedSecret`), a second copy in any tab answers 404, and the key is hash-only from then on; nothing the server holds can reveal a key that has been read. V32 cleared every pre-amendment sealed copy fleet-wide (rotation — delete + sign in — is the path back to a copyable key). §7.6: the `VIEW_OWN_MCP_KEY` row gains `GET /partials/mcp-key/chip` (the post-copy chip re-render); no role cell changed, the secret endpoint's authorization is unchanged — its semantics went one-shot. Merge review: the secret route carries a **fetch-metadata guard** — a request whose `Sec-Fetch-Site` is not `same-origin`, or whose `Sec-Fetch-Mode` is `navigate`, is refused 403 before the open, because `dp_session` is `SameSite=Lax` and would otherwise ride a cross-site top-level navigation that destroys the one copy (never readable by the attacker, but a forced rotation). |
| 2026-09-22 | v2.35 | #210 mint honours the sign-in method | §7.4: the login/switch mint skips only a PASSWORD session whose local password must still be changed; an OIDC session, which the forced-change gate lets through, mints its key. Before this a Google user with a stale bootstrap flag was keyless on every sign-in, silently. |
| 2026-09-22 | v2.34 | #208 self-membership | §7.6's `MANAGE_WORKSPACE_MEMBERS` row (unchanged cells): the three member verbs are never available on the caller's OWN membership — `409 workspace.self_membership`, refused at the service on every surface; a super admin's members-list row reads "super admin" (owner ruling 2026-09-22). |
| 2026-09-22 | v2.33 | 186 review M1 (#186) — LAKE in the in-process gate | §7.6's `MUTATE_WORKSPACE_DATASOURCES` row (unchanged cells): `LAKE` joins the in-process engines whose registration and re-pointing are super-admin-only — its embedded DuckDB runs with external access on and no local-filesystem lock ([Datasources §9](datasources.md#9-validation-rules), v2.44). |
| 2026-09-21 | v2.32 | 200 (#200) membership-bound keys | **A `user` key now ends with the membership it is pinned to, and a workspace admin can revoke it** (roles record §3.7, owner rulings 2026-09-21). §7.4 rewritten on the three rulings: removing a member revokes their `user` key in the same act (one transaction; reason `member_removed`); a workspace admin can revoke a member's key WITHOUT removing them — NEW `DELETE /api/v1/workspaces/{name}/members/{user_id}/key` and the members-row verb, on the existing `MANAGE_WORKSPACE_MEMBERS` row (§7.6), idempotent `204`, reason `admin_revoked`, the member's SESSION untouched and the next login minting fresh; NO automatic rotation on password or identity events (a login that stops working is not a security signal — recovery is an admin act). Before this round a removed member's key kept authenticating through the issuer check's viewer fallback, which admitted EXECUTE. NEW audit event `auth.api_key.revoked_by_admin` (§10.1, [Enums §15](enums.md#15-authauditevent--auth-audit-log-events)). |
| 2026-09-21 | v2.31 | 186b (#186) H2 URL form case-sensitivity | §7.6's `MUTATE_WORKSPACE_DATASOURCES` row (unchanged cells, extended description): the in-process H2 prefixes are matched **case-sensitively, exactly as the driver reads them** — a mixed-case `TCP:`/`MEM:`/`FILE:` is an unknown URL form the driver would open as a literal file, and is refused for every role. No operation, route or role cell changed; the super-admin rule of v2.30 is unchanged — this closes the spelling bypass of it. |
| 2026-09-21 | v2.30 | 186 (#186) in-process datasource containment | §7.6's `MUTATE_WORKSPACE_DATASOURCES` row (unchanged cells, extended description): registering or re-pointing an IN-PROCESS or file-backed datasource (H2 `mem:`/`file:`, DuckDB, SQLite) is **super-admin-only** even where `member-datasources-enabled` admits workspace admins — decided in `DatasourceWorkspaceRules` on the same route, like the instance-datasource rule. No operation or role cell changed; the row's ws_admin ✓ now reads as "server-form datasources". The runtime halves (file roots, the de-privileged H2 pool and probe scratch) are [Datasources §3/§4.2A/§9](datasources.md#9-validation-rules). |
| 2026-09-21 | v2.29 | #202 csrf cookie lifetime | §8.4: `dp_csrf` now carries the session's lifetime (`Max-Age` = `jwt.ttl-hours`) and an explicit `SameSite=Lax`. It was a browser-session cookie while `dp_session` persisted for 8 h, so a tab restored after a browser restart failed every POST (`missing`, then `mismatch` on the retry — the logout / workspace-switch report) until reloaded. Pinned by `AuthHttpBoundaryTest`. |
| 2026-09-21 | v2.28 | 191 (#191, #192, #187) — auth/serve security | **Three security fixes, one round.** §4.2: a MISSING `email_verified` claim is now UNVERIFIED — fail closed; the per-provider knob `trust-email-without-verified-claim` (§5.1, §11.1, default `false`) admits an IdP that never emits the claim, audited `auth.login.email_verified_assumed`; a claim present-and-false is refused regardless. §4.2/§7.6: an identity is linked ONCE — a login whose `(provider, provider_subject)` does not match the stored row (a different provider, a different subject, a `local`/`system` row) refuses with `auth.login.identity_mismatch` → `/login?error=identity_mismatch`, nothing updated; NEW row **`USER_IDENTITY_RESET`** (super admin, §7.6) — `PATCH /partials/admin/users/{userId}/identity-reset` returns the row to the bootstrap placeholder, audited `auth.user.identity_reset`, so the next sign-in with that email claims it. §7.7: endpoint-key bindings are workspace-confined — bind time refuses a prefix that is not at or above a path the caller's own workspace publishes (`endpoint.path_invalid`, naming only the caller's own tree); serve time considers only bindings of the ENDPOINT's workspace (SQL predicate + in-memory backstop; a foreign binding neither authorises nor shadows). The serve audit (R4) records the OBSERVED outcome: `started` pre-answer, then a terminal row `completed`/`failed`/`accepted` (#192). |
| 2026-09-21 | v2.27 | 180 (#180) roles R4 — deactivation | **D15, the liveness round.** §11A.3 rewritten around ONE predicate (`PrincipalLiveness`: user active ∧ pinned workspace active, through the §11.4 cache) judged where each credential becomes a principal — `JwtAuthenticationFilter`, `ApiKeyService.validate`, `validateServerKey` — with the serve path refusing a deactivated workspace's endpoint as an unknown path to every caller (before, only keys pinned there were refused). NEW code `auth.principal_deactivated` (401, §9, §13.7's registry) for a deactivated USER on every surface but the promotion peer; `auth.api_key.invalid` no longer means "owner deactivated"; a deactivated session's page navigation lands on `/login?error=inactive`. The WORKSPACE case keeps the 404 rule (`auth.key_workspace_inactive`, `workspace.not_found`). Gap closed: a `server` key pinned to a deactivated workspace authenticated on `/api/v1/promotion/**`. Gate 7 (`DeactivationSweepTest`) proves every route and tool. §4.2, §6.3, §7.3 steps updated; §5A.6's form field corrected to `_csrf` and its dead `auto-per-user` clause removed (#182). |
| 2026-09-21 | v2.26 | 179 (#179) roles R3 — keys | **D16/D17, the keys round.** §7.4 rewritten: a `user` key is minted by the login/switch hook, one per user per workspace (V31's partial unique index), scopes derived from the role, the plaintext SEALED (`secret_sealed`, the credential-encryption key, AAD = the key id) so the top bar can serve a copy; rotation is delete + sign in again; a user who owes a password change is minted nothing until after it. On-demand `user` minting is refused on every surface — `auth.key_kind_not_mintable` (400, §9, §13.7's registry). §7.6: `MANAGE_OWN_API_KEYS` is RENAMED **`VIEW_OWN_MCP_KEY`** (the self surface: list, `/mine`, the top bar's copy and delete-to-rotate) and creation left it; NEW row **`MANAGE_API_KEYS`** (ws_admin + super admin, `author` scope) takes `POST /api/v1/auth/api-keys`, the `/api-keys` page and its partials, and the endpoint-bindings routes — which moved OUT of `MANAGE_ENDPOINTS` (association is the admin's verb; the by-name request shape is kept, and `api_key_id` joins it). §7.7's kind table restates who mints each kind. §11A.4 rewritten. The console (`/api-console`) is read-only again: the keys card moved to `/api-keys`; the MCP connection card stays, and the top bar's chip links to it. |
| 2026-09-21 | v2.25 | 178 (#178) roles R2 | **The promoter lens lands** (roles design §3.1, D5): the `lens` cell in §7.6 now has behaviour — a promoter reads pipelines, templates and endpoints through `PromotableView`, [Versioning §10.2](versioning.md#102-the-listing-rule-what-the-ui-shows)'s rule applied on every read surface (REST, UI pages and partials, the rail counts, search, MCP tools and resources); §11A.1 gains the **lens clause** (a hidden object is an absent one; fail closed when the target cannot be read; the rule travels as a value, never a thread-local). The cell alphabet and the §11A role table say so in the present tense. New guard `PromoterLensSweepTest`; new knob `datapipelines.deployment.promotion.inventory-cache-ttl-seconds` ([Configuration §3.19](configuration.md#319-deployment)); new WARN `pipeline.promotion.lens_unavailable` and counter `datapipelines.promotion.lens.inventory` ([Observability §3.4D](observability.md#34d-the-promoter-lens-event-178)). No matrix row changed. |
| 2026-09-20 | v2.24 | 177 (#177) roles R1 | **§7.6 rewritten role-first** from the ratified [roles design](superpowers/specs/2026-09-20-roles-permissions-design.md) §2: five role columns per row (viewer \| author \| promoter \| ws_admin \| super_admin), every REST row naming its `RestOperation` constant, the key-scope axis as its own smaller table per surface; `ScopeMatrixSpecDriftTest` parses the new shape (a cell alphabet of ✓ ✗ own all lens, a reserved-row list). Rows that changed: `RELEASE_VERSION` and `SWITCH_SERVED_VERSION` promoter → **author** (D8); `EXECUTE_PIPELINE`, `CANCEL_EXECUTION`, the execution reads and `TEST_DATASOURCE` lose the **promoter** (D5; the connection test follows execute — ratified); `TEST_DATASOURCE` ws_admin → execute; `INTROSPECT_DATASOURCE` author → every role ("introspection is reading" — ratified); `WORKSPACES_READ` every member → **ws_admin** (D13). New rows: **`READ_EXECUTIONS`** (D11: own unless workspace admin, promoter none — the execution reads leave `READ_RESOURCES`), **`WORKSPACE_SWITCH`** (the switcher stays every member's), **`PROMOTION_READ`** (owner rule 13: the page is the author's too); a **reserved** audit-log row (D12 — no surface exists). `MUTATE_DATASOURCES` REMOVED: no handler ever declared it (the instance-datasource rule is `DatasourceWorkspaceRules`' on the same route) — the reachability gate's first finding. §11A rewritten to the five roles: ONE role per membership (V29, `WorkspaceRole`), `Capability` → `Permission` (D21), the vocabulary rule; §4.6 and §10.1 say role, not flags (`workspace.member_flags_changed` → `workspace.member_role_changed`). §4.4/§7.4 keys unchanged (R3). |
| 2026-09-19 | v2.23 | 173 (#173) the agent surface | §8.3: `/llms.txt` and `/llms-full.txt` join the allowlist (generated from the page registry and the docs catalog, no datastore, no principal — meaningless behind a login, like the sitemap); the `/docs/*` reason names the raw `.md` twin the same glob already covers. `PublicPathsTest` 41 → 43 rows. |
| 2026-09-19 | v2.22 | 172 (#172) endpoint URL shape | §7.6/§7.7: the published-endpoint surface is `/api/<category>/<version>/<path…>` (R-EP5) — the `endpoint`-kind confinement follows the FIRST segment of the path (reserved: `v[0-9]+`, `api`), never a literal prefix. No scope, role or kind rule changed; the route family an endpoint key reaches is the same set of URLs under a new shape. |
| 2026-09-17 | v2.21 | 158 (#121) mail connect retry | §5A.8 step 3: a **connect** failure (the connection never opened — the one class that cannot have delivered) is retried in place, bounded (3 attempts, 250 ms / 1 s backoff, `mail.send_retry` logged per retry); anything past connect stays terminal at once — the never-twice rule for password mails is unchanged. The claim row and audit reflect the final outcome only. |
| 2026-09-17 | v2.20 | SSE response headers (#131) | §8.1: configure eager security headers before asynchronous response handoff; retain security defaults and explicit application caching. Guarded at the configured filter boundary and by real HTTP/OIDC tests. |
| 2026-09-14 | v2.19 | 140 release checks over MCP | §7.6 MCP table: `pipelines_run_checks` joins the `execute`/`execute` row (40 → 41 tools) — the D-R3 verb, the same floor as the `EXECUTE_PIPELINE` REST twin (`POST …/checks/run`): a viewer runs what they can read, and a check run returns no row data beyond the one observed cell per check. Both `ScopeMatrixSpecDriftTest` counts moved 40 → 41 in the same commit. The status line had drifted a version behind the rows again (the v2.16 note's pattern); now current. |
| 2026-09-14 | v2.18 | 137 mail notices | New **§5A.8 Mail**: the welcome mail (login URL + one-time password) at local-account creation, the reset mail at an admin reset, and the sys-ops "New user" notice (no password) at every creation — local or the OIDC callback's create branch (§5.5: `findOrCreateByEmail` answers `Provisioned(user, created)`). Enabled exactly when configured ([Configuration §3.27](configuration.md#327-mail)); claimed before sent (`mail_sends`, [Metadata DB §4.19](metadata-db.md#419-mail_sends)) so a password mail never goes twice; sent after commit off the request thread; `mail.sent` / `mail.failed` audited without a body. §5A.1's "no email flow — the product has no SMTP" is gone: there is still no self-service reset, but the credential an admin mints is mailed, and the admin screen shows where it went instead of what it was ([UI §4.12](ui-screens.md#412-admin-user-management-admin-scope-only)). |
| 2026-09-14 | v2.17 | 134 MCP save sees workspace datasources | §8.5: the principal reaches an MCP tool through the transport context, not the thread — the SDK's scheduler thread has an empty `SecurityContextHolder`, so anything reachable from a tool takes principal and workspace as arguments (the save-time datasource port did not, and every MCP save saw owner-less datasources only). No matrix change. |
| 2026-09-13 | v2.16 | 122 viewer executes | §7.6: no matrix row changed — the round re-floored a PAGE route. The pipeline editor (`GET /pipelines/{id}/editor`) declares `EXECUTE_PIPELINE` instead of `MUTATE_PIPELINES_TEMPLATES`: D-R3 says viewers execute, the §4.3e table already gave the viewer's editor an Execute verb, and the author floor refused the viewer before any of it was reachable (the owner's 2026-09-12 report). The template editor keeps the author floor. New paragraph after the UI-screens one states the page-route reasoning; the read key's "everything except the editors" property is unchanged (read < execute). Status line had drifted a version behind the rows (119-style); now current. |
| 2026-09-11 | v2.14 | 117 templates_update | §7.6 MCP table: `templates_update` joins the `author`/`author` row (34 → 35 tools) — the template mirror of `pipelines_update`, the draft write REST `PUT /templates` already gates. Both `ScopeMatrixSpecDriftTest` counts (scope and capability) moved 34 → 35 in the same commit. |
| 2026-09-11 | v2.15 | 118 learned semantic layer | §7.6 MCP table 35 → **38** tools: `semantics_list` joins the `read`/`view` row; a new `semantics_record` / `semantics_retire` row on `author`/`author` (recording is an authoring act, D-S8; the DATASOURCE-scope grant requirement is the §5.3 gate — not-found otherwise; the cross-workspace retire rule needs the workspace-admin role, enforced in the service). §10's event registry (enums.md §15) gains `semantics.recorded` / `semantics.retired`. |
| 2026-09-07 | v2.13 | 094 the agent boundary | §7.6 MCP table: `datasources_create` LEAVES the matrix (28 → 27 tools). The standing rule it becomes: **no credential travels through an agent** — a password passed through a tool call transits the agent's context, its transcript and whatever the client logs, which 068 documented as an accepted trade and 094 rejected. Datasource create, update and delete are UI/REST-only; the read and probe tools (`datasources_list`/`_get`/`_test`, the three introspection tools, `datasources_preview_rows`) are unchanged, and none of them accepts a credential. |
| 2026-09-04 | v2.12 | 068 datasources_create | §7.6 MCP table: `datasources_create` joins the `author` row (21 → 22 tools) — the same floor `datasources_test` sits on, since registration opens a real pool against a production database at save time. `global: true` still requires admin, but as a workspaces D8 rule inside the shared create service, not a scope floor, so it does not appear in this matrix. |
| 2026-09-02 | v2.11 | 040 template used-by | §7.6 MCP table: `templates_used_by` joins the `read` row (20 → 21 tools) — it returns which pipelines reference which template version, reference structure a workspace reader may already see by reading the pipelines themselves; no customer row data (040 D7). |
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
| 2026-09-02 | v2.8 | MCP audit (052) | §10.1: cross-link added — the shared `audit_log` also carries the MCP tool events (`mcp.tool.called` per call, `mcp.tool.write` per mutating call, node runs included), authored by MCP §14 and registered in Enums §15. No auth events changed. |
| 2026-09-05 | v2.5 | 074 key kinds | New **§7.7**: every API key gains a KIND (`user` \| `endpoint`). An `endpoint` key carries no scopes — its authority is its rows in `endpoint_key_bindings`, walked from the most specific ancestor of the request path, where the first node carrying any binding decides and a deeper binding REPLACES a shallower one (R-EP2). An endpoint key with no binding on any ancestor authorises nothing; it reaches published endpoints and the cursor of executions it started, and is refused everywhere else — centrally in `ScopeInterceptor`, and again in `McpAuthFilter` because `/mcp` is a servlet the interceptor never sees. §7.6 gains two REST rows and four MCP tools. |
| 2026-09-07 | v2.13 | 089 dp-lake registry | §7.6 MCP table: `lake_tables_register`, `lake_tables_import` and `lake_tables_unregister` join the `author` row (28 → 31 tools) — the datasource-mutation floor, and mutating a GLOBAL datasource's registry is admin-only as a workspaces D8 rule inside the shared registry service, not a scope. §7.6 REST table gains the lake-table writes row at `author`; the registry listing `GET /api/v1/datasources/{name}/lake-tables` is covered by the existing metadata-read row. |
