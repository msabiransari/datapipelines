# DEVELOPMENT

Developer setup guide for datapipelines.co. Follow these steps to get the app running locally.

---

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| **JDK** | 21+ (LTS) | Runtime + compile target |
| **Docker** | 24+ | Local Postgres + Redis via Compose |
| **Git** | 2.x | Clone the repo |
| **curl** or **httpie** | any | API testing |

Optional (for frontend work):
- Node.js 20+ — only needed if modifying the design system (`../design-system-starter`)

---

## 1. Clone

```bash
git clone <repo-url> datapipelines
cd datapipelines
```

---

## 2. Start Local Infrastructure

```bash
docker compose -f deploy/docker-compose.dev.yml up -d
```

This starts:
- **Postgres 16** on `localhost:5434` (DB: `datapipelines`, user: `datapipelines`, password: `datapipelines`)
- **Redis 7** on `localhost:6381` (no password)

The host ports are deliberately NOT the defaults (5432/6379) — those collide with
other local stacks on most developer machines. Keep them in sync with
`application-dev.yml` and configuration.md §6 if you ever change them.

Verify:
```bash
docker compose -f deploy/docker-compose.dev.yml ps
```

---

## 3. OIDC Provider Setup

The app uses **generic OIDC** — any OIDC-compliant provider works (Google, Microsoft, Okta, Auth0, Keycloak, etc.). For local development, set up one or two providers.

### 3.1 Google

1. Go to [Google Cloud Console](https://console.cloud.google.com/).
2. Create a project (or use an existing one).
3. **APIs & Services → Credentials → Create Credentials → OAuth2 Client ID**.
4. Application type: **Web application**.
5. Authorized redirect URIs: `http://localhost:8080/login/oauth2/code/google`
6. Copy the **Client ID** and **Client Secret**.

### 3.2 Microsoft (Entra ID / Azure AD)

1. Go to [Microsoft Entra admin center](https://entra.microsoft.com/).
2. **App registrations → New registration**.
3. Supported account types: **Accounts in any organizational directory (Multitenant)**.
4. Redirect URI (Web): `http://localhost:8080/login/oauth2/code/microsoft`
5. Copy the **Application (client) ID**.
6. **Certificates & secrets → New client secret** → copy the **Value**.

### 3.3 Any other OIDC provider (Okta, Auth0, Keycloak, etc.)

1. Register an OAuth2/OIDC client in your provider's admin console.
2. Set the redirect URI to: `http://localhost:8080/login/oauth2/code/{provider-name}`
   where `{provider-name}` is whatever you'll use in the config (e.g., `okta`, `keycloak`).
3. Copy the **client ID**, **client secret**, and the provider's **issuer URI**.

Common issuer URIs:

| Provider | Issuer URI |
|---|---|
| Google | `https://accounts.google.com` |
| Microsoft (multi-tenant) | `https://login.microsoftonline.com/common/v2.0` |
| Okta | `https://{your-org}.okta.com` |
| Auth0 | `https://{your-tenant}.auth0.com` |
| Keycloak | `https://{host}/realms/{realm}` |

---

## 4. Configure Environment

Create `.env.local` in the project root (git-ignored — never commit secrets):

```bash
# Metadata DB
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5434/datapipelines
SPRING_DATASOURCE_USERNAME=datapipelines
SPRING_DATASOURCE_PASSWORD=datapipelines

# Redis
DATAPIPELINES_REDIS_HOST=localhost
DATAPIPELINES_REDIS_PORT=6381

# JWT signing secret — MUST be real base64 of ≥32 random bytes; a placeholder here
# fails startup validation (configuration.md §7) by design
DATAPIPELINES_JWT_SECRET=<paste output of: openssl rand -base64 32>

# DB encryption key — MUST decode to exactly 32 bytes
DATAPIPELINES_DB_ENCRYPTION_KEY=<paste output of: openssl rand -base64 32>

# Email allowlist (optional — your email domain)
DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS=yourdomain.com

# Auth base URL — REQUIRED whenever OIDC providers are configured (auth §5.2/§11.3).
# Startup fails without it; OIDC redirect URIs are built from it, never from request headers.
DATAPIPELINES_AUTH_BASE_URL=http://localhost:8080

# Bootstrap admin (optional — makes your OIDC login an admin on first sign-in, auth §4.4)
DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL=you@yourdomain.com

# UI theme
DATAPIPELINES_UI_THEME=saas

# OIDC provider secrets — must match the providers configured in application.yml
# For Google:
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret

# For Microsoft: application.yml declares BOTH providers, so both placeholders must
# resolve even if you only use Google. Any non-empty value works.
MICROSOFT_CLIENT_ID=unused
MICROSOFT_CLIENT_SECRET=unused

# For any other OIDC provider, add its env vars here and configure it in application-dev.yml
# OKTA_CLIENT_ID=...
# OKTA_CLIENT_SECRET=...
```

The provider list itself (names, issuer URIs, display names) is configured in `application.yml` — see [Auth spec §11.1](docs/auth.md#111-oidc-provider-configuration). Only secrets go in env vars.

Generate secrets:
```bash
openssl rand -base64 32    # for JWT_SECRET
openssl rand -base64 32    # for DB_ENCRYPTION_KEY
```

---

## 5. Sync the Design System

The design system lives in a sibling project:

```bash
cd ../design-system-starter
npm install
npm run build                # produces dist/
cd ../datapipelines
./scripts/sync-design-system.sh   # copies dist/ → modules/web/src/main/resources/static/vendor/design-system/
```

Run this whenever the design system changes. The script records the version and SHA-256 of every vendored asset in `modules/web/src/main/resources/static/vendor/design-system/vendor-manifest.json` — one manifest for all vendored assets (design system CSS, Cytoscape, dagre, Alpine). See [Pipeline Editor §12.1](docs/pipeline-editor.md#121-file-structure). `./scripts/sync-design-system.sh --check` verifies the committed assets and the manifest's hashes against `dist/` without writing anything (exit 1 on any drift).

---

## 6. Build and Run

```bash
# Load env vars
export $(grep -v '^#' .env.local | xargs)

# Build (skip tests for speed during dev)
./gradlew build -x test

# Run with dev profile
./gradlew :modules:app:bootRun --args='--spring.profiles.active=dev'
```

Or run from your IDE: main class `co.datapipelines.DatapipelinesApplicationKt`, active profile `dev`, env vars from `.env.local`.

The app starts on `http://localhost:8080`.

### 6.1 Run from IntelliJ IDEA

1. **Run → Edit Configurations → + → Application.**
2. **Main class:** `co.datapipelines.DatapipelinesApplicationKt`
3. **Module classpath:** the `app` Gradle module's `main` source set (in the module picker this reads as the app project's main source set)
4. **Program arguments:** `--spring.profiles.active=dev`
5. **Environment variables:** load them from `.env.local`. In the run configuration, the *Environment variables* field has a file-picker icon on the right — select your project-root `.env.local`. IntelliJ exports it into the launched process, same as the `export $(grep -v '^#' .env.local | xargs)` line above. (Do not paste the secrets into the stored run config.)
6. **Working directory:** the project root (default).

Debug works as usual — set a breakpoint and use the bug icon instead of run. Live reload is not configured; re-run after code changes.

Verify with:

```bash
curl http://localhost:8080/health
```

If startup fails with `datapipelines.auth.base-url must be set…`, your env file is missing `DATAPIPELINES_AUTH_BASE_URL` (§4). If it fails on a `MICROSOFT_*` placeholder, both provider placeholder sets must resolve (§4).

### 6.2 Dependency lockfiles

Every module resolves its dependencies against its committed `gradle.lockfile`
(module-structure.md §7.6). Locking is STRICT: if resolution drifts from the
lock — a version changed in `gradle/libs.versions.toml`, a dependency added or
removed in a module build file, a transitive shifting — the build FAILS with a
lock-state error. That failure is the guard working; do not try to bypass it.

**Updating the locks** — the only correct response to a *deliberate* dependency
change:

```bash
./gradlew resolveAndLockAll --write-locks
```

This resolves every resolvable configuration in every module and rewrites all
`gradle.lockfile` files (plus `buildSrc/gradle.lockfile`). Read the resulting
diff like any dependency review — it shows exactly which versions moved — and
commit it in the same commit as the catalog/build-file change that caused it.
A lockfile diff with no corresponding dependency change means something drifted
that you did not intend; investigate, don't commit.

- To move a single module (say, bump only SLF4J): change the version, then
  `./gradlew resolveAndLockAll --write-locks --update-locks org.slf4j:slf4j-api`.
- Never edit a `gradle.lockfile` by hand (its own header says why).
- The `-Poracle` / `-Pmysql` drivers are deliberately excluded from lock
  validation — one lockfile cannot validate both flag states; the reason is
  commented in `modules/datasources/build.gradle.kts`. They are the only
  exclusions.
- STRICT mode also fails when a configuration has no recorded lock state, so
  adding a new resolvable configuration (e.g. a new plugin) requires a
  `--write-locks` run before the build goes green again.

### 6.3 Dependency verification (checksums)

Every artifact the build downloads — dependencies AND plugins, for every module
and buildSrc — is verified against a committed SHA-256 checksum in
`gradle/verification-metadata.xml` (Gradle's dependency verification, strict
mode). A download whose checksum differs from the committed one fails the
build naming the artifact: this is the supply-chain twin of the lockfiles
(locks pin WHICH version; verification pins WHAT BYTES).

PGP/signature verification is deliberately NOT enabled (deferred — key
management for ~500 artifacts is its own project; checksums already close the
"artifact changed upstream / MITM mirror" hole against a trusted first
download).

**Updating the metadata** — required whenever a dependency change is
deliberate, in the same commit as the lockfile update:

```bash
./gradlew --write-verification-metadata sha256 --write-locks resolveAndLockAll
```

This resolves every configuration (the point of `resolveAndLockAll`) and
records each artifact's checksum. Review the diff like the lockfile diff:
new entries must be exactly the artifacts your change introduced.

---

## 7. Verify

```bash
# Health check (root-level, no auth — not under /api/v1)
curl http://localhost:8080/health

# Should return:
# {"status":"UP","version":"...","components":{"database":"UP","redis":"UP","h2_factory":"UP"}}

# Open browser
open http://localhost:8080/login
```

The health payload shape is specified in [REST API §11.1](docs/rest-api.md#111-health-check).

You should see the login page with one "Sign in with …" button per provider configured in `application-dev.yml` (Google and Microsoft if you set up both) — the buttons are rendered from the provider registry, not hard-coded ([Auth §5.3](docs/auth.md#53-login-page-dynamic--renders-buttons-for-each-configured-provider)).

---

## 8. Create Test Data

### 8.1 Register a datasource (after first login)

```bash
# After logging in, mint an API key from the UI (or POST /api/v1/auth/api-keys — the
# secret is returned exactly once). All custom headers use the DP- prefix.
curl -X POST http://localhost:8080/api/v1/datasources \
  -H "Content-Type: application/json" \
  -H "DP-API-Key: dpk_..." \
  -d '{
    "name": "pg-local",
    "display_name": "Local Postgres",
    "dialect": "POSTGRES",
    "jdbc_url": "jdbc:postgresql://localhost:5434/testdb",
    "username": "postgres",
    "password": "postgres"
  }'
```

### 8.2 Create a template

```bash
curl -X POST http://localhost:8080/api/v1/templates \
  -H "Content-Type: application/json" \
  -H "DP-API-Key: dpk_..." \
  -d '{
    "id": "active_users.sql",
    "dialect": "POSTGRES",
    "display_name": "Active Users",
    "description": "Get all active users. Declares no parameters.",
    "imports": [],
    "body": "SELECT id, email, name, created_at FROM users WHERE is_active = true ORDER BY created_at DESC"
  }'
```

Templates declare no parameter schema — the variables a body may reference are exactly the keys of the calling pipeline's `parameters` map, validated by dry-render when the *pipeline* is saved ([Templates §3.2](docs/templates.md#32-field-reference)). `imports` binds library templates to namespace aliases; the body never contains `<#import>` ([Templates §6](docs/templates.md#6-library-templates)).

### 8.3 Create a pipeline

```bash
curl -X POST http://localhost:8080/api/v1/pipelines \
  -H "Content-Type: application/json" \
  -H "DP-API-Key: dpk_..." \
  -d '{
    "schema_version": 1,
    "name": "active_users",
    "display_name": "Active Users",
    "description": "List all active users from local PG",
    "parameters": {},
    "nodes": [{
      "id": "fetch_active_users",
      "description": "Fetch active users",
      "type": "DQL",
      "source": "pg-local",
      "template": {"id": "active_users.sql", "version": 1},
      "depends_on": []
    }]
  }'
```

No `output` block: an omitted `output` on a DQL node defaults to `target: caller`, so this single node **is** the caller node — the one whose rows come back to you. At most one node per pipeline may resolve to `caller`; zero is also legal (a pure write-back pipeline just returns stats). See [Pipeline Contract §16.1](docs/pipeline-contract.md#161-minimal-pipeline-single-source-read).

### 8.4 Execute the pipeline

Open the pipeline editor in the browser: `http://localhost:8080/pipelines/{id}/editor`

Click **Execute**. Watch the graph node turn blue → green. Result appears in the preview panel.

### 8.5 Connect an Agent (MCP)

Agents (Claude, CoPilot, OpenCode, Kimi, Cursor, …) talk to the running app through its MCP server — a Streamable HTTP endpoint at `POST /mcp` ([MCP spec §3](docs/mcp-server.md#3-transport--protocol)). The transport is **API-key-only**: no browser cookies, no OIDC session. Mint an API key first (Settings → API Keys in the UI, or `POST /api/v1/auth/api-keys`; the secret is returned exactly once) and grant it the scope your agent needs — `admin` covers everything (`admin ⊃ author ⊃ execute ⊃ read`, [Auth §7.5](docs/auth.md#75-scopes)).

Example client configuration (OpenCode — `~/.config/opencode/opencode.jsonc`, or a project-level `opencode.json`):

```jsonc
{
  "mcp": {
    "datapipelines": {
      "type": "remote",
      "url": "http://localhost:8080/mcp",
      "headers": { "DP-API-Key": "dpk_<id>.<secret>" }
    }
  }
}
```

For clients that can only set the standard Authorization header, `"Authorization": "Bearer dpk_<id>.<secret>"` is equivalent ([MCP §3.2](docs/mcp-server.md#32-auth-headers)). Keep the key out of version control — global config or an untracked file.

Restart your agent client after adding the config (MCP servers are discovered at startup). Once connected, the agent sees 18 tools (`pipelines_create`, `pipelines_execute`, `templates_render`, `datasources_get_schemas`, `datasources_get_tables`, `executions_get_result`, …) plus 3 prompts (`analyze_pipeline`, `create_pipeline_for_question`, `debug_failed_execution`) — the full surface is in [MCP spec §6](docs/mcp-server.md#6-tools).

Notes for agent users:

- `pipelines_execute` is a **single blocking call** — it returns when the execution reaches a terminal state or times out (default 600s). Progress arrives as the `node_stats` array in the final result, not as streamed notifications ([MCP §6.2.3](docs/mcp-server.md#623-pipelines_execute)).
- Results page through `executions_get_result` within the TTL; `has_more: true` means there are more pages.
- To cancel a stuck execution: `DELETE /api/v1/executions/{id}` from REST (no MCP cancel tool in v1).
- `/mcp` shares the per-user rate limiter with `/api/v1` ([REST §12](docs/rest-api.md#12-rate-limiting)).

---

## 9. Run Tests

```bash
# Unit tests (fast)
./gradlew test

# Integration tests (slower — starts Testcontainers)
./gradlew integrationTest

# One module's tests
./gradlew :modules:typesystem:test

# With coverage
./gradlew test jacocoTestReport
```

Integration tests use **Testcontainers** to spin up real Postgres, Redis, and source databases (PG, MySQL, MSSQL) in Docker containers. Docker must be running.

---

## 10. Linting and Formatting

```bash
# Kotlin linting (ktlint)
./gradlew ktlintCheck

# Static analysis (detekt)
./gradlew detekt

# Auto-fix ktlint issues
./gradlew ktlintFormat
```

CI runs all three. No code merges with violations.

### 10.1 Documentation audit

Any change under `docs/` (or to this file) must pass the documentation audit:

```bash
./scripts/docs-audit.sh    # must exit 0
```

It mechanically checks cross-document links and anchors, error codes against the
[Pipeline Contract §13 catalog](docs/pipeline-contract.md#13-error-code-catalog), configuration keys
against [configuration.md](docs/configuration.md), and forbidden legacy spellings (superseded header
names, removed entity fields). CI runs it alongside the Kotlin checks — **exit 0 is required before
merging**. See [Validation Discipline](docs/enums.md#validation-discipline) in enums.md.

### 10.2 Quality tooling

Guards beyond lint/static analysis. Each tool below is pinned exactly, and every
guard has been proven able to fail (see the run handbacks in the private
orchestration store — a sibling repo outside this one, per the 2026-08-15
convention that keeps orchestration material out of the product repo; the
buildSrc guard tests in-tree are the live descendants of those proofs).

#### Coverage (Kover)

```bash
./gradlew koverHtmlReport   # per-module reports + aggregated root report
./gradlew koverXmlReport    # XML (Cobertura) equivalents, for tooling
```

Kover is applied to every module by `CommonConventionsPlugin`; the root project
additionally aggregates all modules into one report
(`build/reports/kover/index.html`). `check` depends on `koverVerify`, so a
coverage regression fails the build.

Each module has a minimum **line coverage** floor — the module's measured
baseline from the first Kover run (2026-08-15) minus 2 points, rounded down.
The floors live in `COVERAGE_FLOORS` in
`buildSrc/src/main/kotlin/CommonConventionsPlugin.kt`. They exist to catch
regressions, not to force new tests: raise a floor only when a module's
coverage has genuinely improved, and never lower one to make a build pass.
`tests/integration-tests` has no floor (no main sources of its own).

The floors guard itself is tested (`buildSrc/src/test`, Gradle TestKit — a
project missing from the maps fails configuration; `-Pkover.off` drops the
floor rule). A main build only builds buildSrc through its jar, so its tests
never run automatically: `./gradlew -p buildSrc test` runs them, and
`scripts/gate.sh` runs that as a stage — forced to execute (`cleanTest`;
the catalog file's content is a declared test input, so a
`gradle/libs.versions.toml` edit re-runs the guards even without the gate),
and skipped fail-soft with the cause named when the network preflight says
offline (the TestKit probes resolve their compile/test dependencies from
Maven Central when their cache is cold).

#### Dependency vulnerabilities (OSV-Scanner)

```bash
./scripts/vuln-scan.sh    # scans every committed gradle.lockfile
```

Exit codes — the contract callers script from (the scanner's own codes are
remapped INSIDE the script, never propagated raw; the offline sentinel is
shared with `gate.sh` via `scripts/lib/scan-tools.sh`):

- `0` — scan ran, no findings
- `1` — scan ran, vulnerabilities found
- `2` — scan did NOT reach a verdict, cause named: scanner error while
  online; preflight classified the environment as broken (curl missing,
  TLS/CA failure); install-side failure of the pinned scanner (download
  failure, missing checksum entry, **SHA256 mismatch — a tampered binary is
  a supply-chain failure, never a scan result**); unsupported platform; or
  no committed lockfiles. (These paths must never exit 1, which means
  "findings".)
- `200` — skipped: offline (fail-soft; connection-level failures only —
  connection refused, DNS — and a curl *timeout* only after one retry at a
  longer budget, so a slow-but-online box cannot silently skip the scan)

osv-scanner (pinned in the script, downloaded into the git-ignored `.tools/` —
outside `build/`, so `gradlew clean` does not force a re-download — and
SHA256-verified against the release manifest) checks the
resolved dependency set — the lockfiles, direct and transitive — against
the OSV database. Ignores live in `osv-scanner.toml`; every entry needs a reason +
date comment and an `ignoreUntil`. `scripts/gate.sh` runs the scan as its final
stage; exit 200 warns and does NOT fail the gate (the scan is meaningless
without osv.dev — the skip is printed, never silent); exits 1 and 2 fail it.

The shared install helpers in `scripts/lib/scan-tools.sh` (download, SHA256
verify) exit `2` on failure for **all three** scanner scripts —
secret-scan (`1` = leaks found) and container-scan (`1` = trivy finding)
publish the same distinction between a findings verdict and a tooling
failure, so an install-side breakage can never masquerade as a scan result
in any consumer's branching.

#### Secret scanning (gitleaks)

```bash
./scripts/secret-scan.sh            # full-history scan
./scripts/secret-scan.sh --staged   # staged changes only (what the hook runs)
./scripts/install-hooks.sh          # one-time: point git at .githooks/
```

gitleaks (pinned in the script, same verified-download pattern) scans the full
history or just staged changes. `install-hooks.sh` sets plain git
`core.hooksPath` to the committed `.githooks/` directory — no hooks framework —
so the pre-commit hook blocks staged secrets. The allowlist is `.gitleaks.toml`;
entries are SECRET-targeted and path-scoped with `condition = "AND"`
(suppression requires the triaged file AND the extracted secret, so a real
secret co-located with an allowlisted token still fires), each with a reason +
date comment, and the config EXTENDS the gitleaks default ruleset (a bare
custom config silently replaces it — that mistake was made and caught here).

#### Container scanning (trivy)

```bash
./scripts/container-scan.sh                  # Dockerfile config scan + production image scan
./scripts/container-scan.sh --config-only    # Dockerfile config scan only
```

trivy (pinned in the script, same verified-download pattern) scans the
Dockerfile for misconfigurations and the locally-built production image for
package CVEs. Baseline exceptions live in `.trivyignore` with a reason + date
comment AND an `exp:` expiry per entry — an expired entry fails the scan
again, so baselines get re-triaged instead of rotting (same discipline as
`osv-scanner.toml`'s `ignoreUntil`). Anything NOT ignored exits 1, so new
findings fail. We do not chase base-image CVE zero — record the count, fix
what a base-image bump or an obvious Dockerfile change resolves cheaply.
trivy 0.74 has no docker-compose scanner, so `deploy/*.yml` is not covered.
First run needs network (vulnerability DB download); the image scan needs a
Docker daemon.

#### Architecture guards (Konsist)

Layering rules as ordinary unit tests (module-structure.md §7.8):
`RequiredScopeKonsistTest` in `modules/web` and `ArchitectureGuardTest` in
`tests/integration-tests` (no field injection; `@Transactional` only on
`@Service`-stereotyped types, interfaces included). They run with the normal
`test` task — no separate command.

---

## 11. Project Structure

```
datapipelines/
├── DEVELOPMENT.md              ← you are here
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml   ← dependency versions (single source of truth)
├── docs/                       ← specifications — see docs/README.md for the index
│   ├── README.md               ← spec index (start here)
│   └── SPEC-REVIEW-2026-08.md  ← ratified cross-doc decisions D1–D15 (permanent record)
├── deploy/
│   ├── docker-compose.dev.yml  ← local dev infra (Postgres + Redis)
│   └── docker-compose.yml      ← reference production compose
├── scripts/
│   ├── sync-design-system.sh   ← copies design system CSS from ../design-system-starter (§5)
│   ├── docs-audit.sh           ← mechanical doc consistency check (§10.1); must exit 0
│   ├── gate.sh                 ← Gate A: clean/build/build cycles + buildSrc guard tests + vuln-scan
│   ├── install-hooks.sh        ← one-time: point git's core.hooksPath at .githooks/
│   ├── secret-scan.sh          ← gitleaks secret scan (full history / --staged for the hook)
│   ├── vuln-scan.sh            ← OSV-Scanner over the committed lockfiles (§10.2)
│   ├── container-scan.sh       ← trivy config + image scan (§10.2)
│   └── lib/
│       └── scan-tools.sh       ← shared pinned-scanner install/verify machinery (sourced)
├── modules/
│   ├── typesystem/             ← canonical types, per-dialect mappers
│   ├── pipeline-contract/      ← pipeline model, validation
│   ├── templates/              ← Freemarker integration
│   ├── datasources/            ← connection registry, dialect adapters
│   ├── staging/                ← H2 per-request lifecycle
│   ├── dag/                    ← DAG data structure + executor
│   ├── auth/                   ← OIDC, JWT, API keys, scopes
│   ├── mcp-server/             ← MCP transport + tools
│   ├── web/                    ← REST controllers, Thymeleaf UI, SSE
│   └── app/                    ← Spring Boot main, config, Flyway migrations
└── tests/
    └── integration-tests/      ← cross-module integration tests
```

---

## 12. Common Issues

### "Port 8080 already in use"

Another process is using port 8080. Either kill it or change the app port:
```bash
./gradlew bootRun --args='--server.port=8090 --spring.profiles.active=dev'
```

### "Flyway migration failed"

The metadata DB has a dirty migration state. Drop and recreate:
```bash
docker compose -f deploy/docker-compose.dev.yml down -v
docker compose -f deploy/docker-compose.dev.yml up -d
```

### "OIDC login redirect_uri mismatch"

The redirect URI in your Google/Microsoft app registration must exactly match:
- Google: `http://localhost:8080/login/oauth2/code/google`
- Microsoft: `http://localhost:8080/login/oauth2/code/microsoft`

### "Design system CSS not found"

You haven't synced the design system:
```bash
cd ../design-system-starter && npm install && npm run build && cd -
./scripts/sync-design-system.sh
```

### "Testcontainers tests fail"

Docker must be running. Testcontainers spins up containers for each integration test:
```bash
docker info    # should return server info, not "Cannot connect to the Docker daemon"
```

---

## 13. Git Workflow

```bash
# Create a feature branch
git checkout -b feature/add-duckdb-staging

# Make changes, commit
git add .
git commit -m "Add DuckDB as staging engine option"

# Run full verification before pushing
./gradlew verify         # lint + test + build
./scripts/docs-audit.sh  # required if the change touches docs/ or DEVELOPMENT.md (§10.1)

# Push and create PR
git push -u origin feature/add-duckdb-staging
gh pr create --title "Add DuckDB staging" --body "..."
```

**No AI attribution in commits** (per project rules). Commit messages follow conventional format or the existing repo convention.

---

## Appendix: Quick Start (One-Liner Setup)

For someone who already has OIDC credentials and Docker running:

```bash
git clone <repo-url> datapipelines && cd datapipelines && \
docker compose -f deploy/docker-compose.dev.yml up -d && \
cp .env.example .env.local && \
nano .env.local && \                           # fill in OIDC + secrets
cd ../design-system-starter && npm install && npm run build && cd - && \
./scripts/sync-design-system.sh && \
export $(grep -v '^#' .env.local | xargs) && \
./gradlew :modules:app:bootRun --args='--spring.profiles.active=dev'
```

Then open `http://localhost:8080/login`.
