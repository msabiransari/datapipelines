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
- **Postgres 16** on `localhost:5432` (DB: `datapipelines`, user: `datapipelines`, password: `datapipelines`)
- **Redis 7** on `localhost:6379` (no password)

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
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/datapipelines
SPRING_DATASOURCE_USERNAME=datapipelines
SPRING_DATASOURCE_PASSWORD=datapipelines

# Redis
DATAPIPELINES_REDIS_HOST=localhost
DATAPIPELINES_REDIS_PORT=6379

# JWT signing secret (generate: openssl rand -base64 32)
DATAPIPELINES_JWT_SECRET=your-jwt-secret-at-least-32-bytes-long

# DB encryption key (generate: openssl rand -base64 32)
DATAPIPELINES_DB_ENCRYPTION_KEY=your-encryption-key-at-least-32-bytes

# Email allowlist (optional — your email domain)
DATAPIPELINES_AUTH_ALLOWLIST_DOMAINS=yourdomain.com

# UI theme
DATAPIPELINES_UI_THEME=saas

# OIDC provider secrets — must match the providers configured in application-dev.yml
# For Google:
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret

# For Microsoft:
MICROSOFT_CLIENT_ID=your-microsoft-client-id
MICROSOFT_CLIENT_SECRET=your-microsoft-client-secret

# For any other OIDC provider, add its env vars here and configure it in application-dev.yml
# OKTA_CLIENT_ID=...
# OKTA_CLIENT_SECRET=...
```

The provider list itself (names, issuer URIs, display names) is configured in `application-dev.yml` — see [Auth spec §11.1](docs/auth.md#111-oidc-provider-configuration). Only secrets go in env vars.

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

Run this whenever the design system changes. The script records the version in `vendor-manifest.json`.

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

---

## 7. Verify

```bash
# Health check
curl http://localhost:8080/health

# Should return: {"status":"UP","components":{...}}

# Open browser
open http://localhost:8080/login
```

You should see the login page with "Sign in with Google" and "Sign in with Microsoft" buttons.

---

## 8. Create Test Data

### 8.1 Register a datasource (after first login)

```bash
# After logging in, get your session cookie from browser devtools, or use an API key
curl -X POST http://localhost:8080/api/v1/datasources \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dpk_..." \
  -d '{
    "name": "pg-local",
    "display_name": "Local Postgres",
    "dialect": "POSTGRES",
    "jdbc_url": "jdbc:postgresql://localhost:5432/testdb",
    "username": "postgres",
    "password": "postgres"
  }'
```

### 8.2 Create a template

```bash
curl -X POST http://localhost:8080/api/v1/templates \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dpk_..." \
  -d '{
    "id": "active_users.sql",
    "dialect": "POSTGRES",
    "description": "Get all active users",
    "params_schema": {},
    "body": "SELECT id, email, name, created_at FROM users WHERE is_active = true ORDER BY created_at DESC"
  }'
```

### 8.3 Create a pipeline

```bash
curl -X POST http://localhost:8080/api/v1/pipelines \
  -H "Content-Type: application/json" \
  -H "X-API-Key: dpk_..." \
  -d '{
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
      "output": {"target": "caller"},
      "depends_on": []
    }]
  }'
```

### 8.4 Execute the pipeline

Open the pipeline editor in the browser: `http://localhost:8080/pipelines/{id}/editor`

Click **Execute**. Watch the graph node turn blue → green. Result appears in the preview panel.

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

---

## 11. Project Structure

```
datapipelines/
├── DEVELOPMENT.md              ← you are here
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml   ← dependency versions (single source of truth)
├── docs/                       ← specifications (15+ docs)
├── deploy/
│   ├── docker-compose.dev.yml  ← local dev infra (Postgres + Redis)
│   └── docker-compose.yml      ← reference production compose
├── scripts/
│   └── sync-design-system.sh   ← copies design system CSS from ../design-system-starter
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
./gradlew verify    # lint + test + build

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
