# Environments — naming them, and telling the product how careful to be

**Status:** v1.0 (normative)
**Owner:** datapipelines.co core
**Depends on:** [Configuration](configuration.md) · [Deployment](deployment.md) · [Auth](auth.md)
**Last updated:** 2026-09-06

---

This page is written for the person who deploys datapipelines.co inside an organisation and has never seen it before. It answers one question end to end: **what do I set, and where do I set it, for each of my environments?**

There are two variables. Everything else has a default that works.

---

## 1. Why two variables

Your organisation names its environments. Ours cannot.

You may have `dev` and `prod`. You may have `dev`, `qa`, `uat`, `perf`, `sandbox-eu`, `prod-us`, `prod-eu`. You may have one server called `prod` that is also the only place anyone builds anything. All of those are legitimate, and none of them tells the product how careful to be — a single-server user who honestly labels their box `prod` must not be locked out of building pipelines on the only box they have.

So the two facts are kept apart:

| | Variable | Who decides it |
|---|---|---|
| The **name** | `DATAPIPELINES_ENV` | **You.** Any label you like: `qa`, `sandbox-eu`, `prod-us`. Free text, `[a-z0-9][a-z0-9_-]{0,31}` |
| The **posture** | `DATAPIPELINES_POSTURE` | **The product.** Exactly two values, `development` and `hardened`, with the documented semantics in §2 |

**Nothing in the product branches on the name.** It is logged at boot, it identifies this deployment when it promotes content to another one, and that is all. A guard test in the build refuses any code that reads it in a condition.

You map your environments onto the two postures. A typical mapping:

```
sandbox  ->  development
dev      ->  development
qa       ->  hardened
uat      ->  hardened
prod     ->  hardened
```

Another organisation maps `dev` to `hardened` because their dev environment holds real customer data. Both are right, because the mapping is yours.

**A named environment with no posture does not start.** If `DATAPIPELINES_ENV` is anything but `local`, `DATAPIPELINES_POSTURE` is required and the app refuses to boot without it, naming both variables. That refusal is deliberate: the product cannot infer a stance from a name it is forbidden to read, and guessing `development` for something called `prod` is the failure this design exists to prevent. The one exception is the environment named `local` — a laptop — which resolves to `development`.

---

## 2. The posture table

This is normative. Everything the posture means is here.

| Rule | `development` | `hardened` |
|---|---|---|
| Authoring (`DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED`) | `true` — this deployment builds pipelines | **`false`** — a promotion receiver; explicitly settable either way |
| Demo (`DATAPIPELINES_DEMO`) | allowed | **refused** at boot |
| Local bootstrap password (`DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD[_HASH]`) | allowed | **refused** at boot. Local password accounts themselves stay allowed — a *seeded* credential is what has no place here |
| Loopback metadata database / Redis | allowed | **refused** at boot |
| Session cookie `Secure` (`DATAPIPELINES_AUTH_COOKIE_SECURE`) | derived from `DATAPIPELINES_AUTH_BASE_URL`'s scheme — an explicit `http://` drops the flag so local login works | **on** |
| OIDC | optional | **required unless `DATAPIPELINES_AUTH_ALLOW_LOCAL_ONLY=true`** — an explicit acknowledgement, which is logged |
| Boot line | `event=config.posture env=<env> posture=development authoring=on demo=nyc,trade` | same shape |

Two rows are **defaults** you can override (authoring, cookie `Secure`); four are **refusals** you cannot. A refusal names the variable and the posture in its message, so the log line tells you which line of which file to change.

The refusals exist because a deployment that asked for the hardened stance and quietly got a relaxed one is worse than a deployment that did not start.

### Turning a hardened deployment into an authoring one

A one-box organisation that wants the hardened posture *and* wants to build pipelines on that box sets the capability back on:

```
DATAPIPELINES_POSTURE=hardened
DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED=true
```

That is supported and expected. The default is `false` because the common hardened deployment is a **receiver** — content is built in a lower environment and promoted up (§7).

**What authoring off means for versions.** Every draft-creating write — create, update, release,
discard, for pipelines and templates — is refused with `pipeline.authoring.disabled` /
`template.authoring.disabled` where authoring is off ([versioning §5.5](versioning.md#55-drafts-are-a-deployment-capability-039)),
so **a hardened receiver cannot hold a DRAFT at all**: everything on it arrived RELEASED through
promotion. That is what makes the execute default safe to state in one sentence everywhere — running
a pipeline with no version given runs the *working* version (versioning §7.2, D56), which on a
development deployment may be a draft and on a hardened one is a release by construction, not by
convention.

---

## 3. The variable contract

**Everything the product needs is an environment variable.** Docker Compose, Kubernetes, systemd, Nomad, ECS and a bare `java -jar` are all *loaders* of the same dotenv-shaped settings. Nothing the product needs lives only in a compose file, and nothing requires a YAML file you have to author.

There are **two files**, and the second beats the first:

```
deploy/
  env/
    defaults.env              TRACKED. Every non-secret variable, with the value this
                              deployment defaults to. One comment each.
  secrets.env.example         TRACKED. The template for the file below, and the list of
                              every variable name you may set.
  secrets.env                 NEVER TRACKED. Your credentials, and every override that
                              belongs to this deployment.
  compose.yml                 the production-shape stack
  compose.local-build.yml     override: build the image from this checkout
  compose.laptop-infra.yml    Postgres + Redis only, for running the app from a laptop
```

`defaults.env` is the reference: every `DATAPIPELINES_*` variable this build binds, one
line each with the value it ships and a one-line comment naming its
[Configuration](configuration.md) section. You do not copy it or edit it — every loader
reads it as it stands, and you put your differences in `secrets.env`.

`secrets.env.example` is what you copy (or let `./app.sh --scaffold` generate). It has
three parts: the secrets, with empty values; the two or three values only you can supply
(this deployment's external URL, its first administrator); and then **every other
variable, commented out, showing the default `defaults.env` gives it**. Uncomment a line
to change it here.

**One authority per variable.** Each variable is *declared* in exactly one of the two
files — never both — and a build check (`scripts/compose-env-audit.sh`) fails if that
stops being true, if a `defaults.env` value drifts from the application's own default
without a declared reason, or if the commented list stops matching. That is what makes it
safe to say "the repository is the source of truth for settings" without anyone having to
remember which file wins.

### The two exceptions, and why they exist

**Secrets are not in a tracked file.** Ever, for any reason (§6).

**The posture's own defaults are in neither file.** `DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED`
and `DATAPIPELINES_AUTH_COOKIE_SECURE` are the two rows of the posture table that are
*defaults* rather than refusals, and their default **is** the posture — carried by the
profile `DATAPIPELINES_POSTURE` selects. A tracked settings file is loaded for every
posture, so a value there would be one posture's answer imposed on both; that is precisely
how a `hardened` stack once booted with `authoring=on`. `deploy/compose.yml` therefore
passes those two in Compose's valueless form (`DATAPIPELINES_AUTH_COOKIE_SECURE:`, nothing
after the colon), the only form that leaves a variable *unset* rather than empty — an
empty variable outranks a profile just as a set one does. You can still override either:
set it in `secrets.env`, your Secret, or your shell, and it wins.

### If you would rather use a YAML file

You can. Mount your own and point Spring at it:

```
SPRING_CONFIG_ADDITIONAL_LOCATION=/etc/datapipelines/application.yml
```

Its keys are the same ones `defaults.env` names, in their dotted form (`datapipelines.auth.base-url` for `DATAPIPELINES_AUTH_BASE_URL`). This is the supported way to configure **more than one OIDC provider**, since a list of providers is awkward as environment variables. Everything else is easier as a variable.

### You do not set `SPRING_PROFILES_ACTIVE`

The posture selects the profile that carries its defaults. Setting the profile yourself is unnecessary, and setting it to a *different* posture refuses startup rather than loading one posture's defaults while every posture rule judges the other.

---

## 4. Loaders

Two files, in one order — `defaults.env`, then `secrets.env` — under every loader below. Compose is one consumer of them, not the contract.

### Docker Compose

```bash
docker compose -f deploy/compose.yml \
  --env-file deploy/env/defaults.env \
  --env-file deploy/secrets.env \
  up -d
```

Later `--env-file`s win, so secrets go last and an operator's value always beats a tracked default. `DATAPIPELINES_ENV` and `DATAPIPELINES_POSTURE` are yours to set in `secrets.env` (both are commented out in the template with `defaults.env`'s value beside them).

### A bare JAR

```bash
set -a
. deploy/env/defaults.env
. deploy/secrets.env
set +a
java -jar datapipelines-app.jar
```

`set -a` exports every variable the files assign; `set +a` stops. That is the whole integration — and the profile carrying the posture's defaults is selected by `DATAPIPELINES_POSTURE` alone, with no `SPRING_PROFILES_ACTIVE` in sight.

**No inversions.** Before 2026-09-06 a laptop had to load a third file *after* the secrets, to undo a database password and a Redis `requirepass` that belonged to a different machine. `deploy/compose.laptop-infra.yml` now starts its Postgres and Redis from the same two variables the application binds, so there is one value per credential and the order never has to be argued with.

### systemd

```ini
[Service]
EnvironmentFile=/etc/datapipelines/defaults.env
EnvironmentFile=/etc/datapipelines/secrets.env
ExecStart=/usr/bin/java -jar /opt/datapipelines/app.jar
```

Two lines, and `systemd` applies them in order — the same precedence as everything else here.

### Kubernetes

```bash
kubectl create configmap dp-defaults --from-env-file=deploy/env/defaults.env
kubectl create secret generic dp-secrets --from-env-file=deploy/secrets.env
```

then, in the pod spec:

```yaml
envFrom:
  - configMapRef: { name: dp-defaults }
  - secretRef:    { name: dp-secrets }
env:
  - name: DATAPIPELINES_ENV
    value: prod
  - name: DATAPIPELINES_POSTURE
    value: hardened
```

Set `MANAGEMENT_SERVER_ADDRESS=0.0.0.0` if you scrape metrics from outside the pod, and pair it with a NetworkPolicy limiting that port to your monitoring namespace ([Deployment §9](deployment.md#9-security-hardening-checklist-deployment)).

### ECS and Nomad

The same file, in their environment block: ECS task definitions take `environment` pairs (or `secrets` from Secrets Manager / Parameter Store); Nomad jobs take a `template` stanza with `env = true`. Neither needs anything from this product that the file does not already carry.

---

## 5. Demo

Demo is a **flag**, not an environment. It is how someone evaluates the product out of the box, inside a `development` environment.

```bash
./app.sh --start --demo nyc          # the NYC mobility family
./app.sh --start --demo nyc,trade    # both families
./app.sh --start --demo nyc,trade,lake   # ... plus the dp-lake family
```

or, on any loader, `DATAPIPELINES_DEMO=nyc,trade`.

What it loads: two independent families of published sample data, each downloaded and checksum-verified from object storage, restored into their own databases, registered as **read-only** datasources, and accompanied by a set of example pipelines seeded into the personal workspace of whoever logs in. The versions are pinned in `deploy/env/defaults.env`, which is tracked — a data change is a new version directory and a commit, so what a deployment loads is visible in the repository rather than in someone's local file. [Deployment Appendix B](deployment.md#appendix-b-demo-quickstart--the-published-sample-data) is the full quickstart.

The third family, `lake`, is the exception to "downloaded": it has **no loader** — nothing is fetched or restored at demo start, because dp-lake reads the published Parquet/Iceberg objects **in place over HTTPS** at query time. A demo with `lake` therefore needs egress to S3 (or to a mirror you have configured in its place) not just at load time but whenever a lake query runs.

**The `hardened` posture refuses a non-empty `DATAPIPELINES_DEMO` at boot.** Demo registers datasources and seeds content; that is evaluation, and it does not belong in an environment you have declared hardened.

---

## 6. Secrets

A secret is anything that would let someone else act as this deployment, or read what it has stored:

- `DATAPIPELINES_JWT_SECRET` — forges any session if leaked.
- `DATAPIPELINES_DB_ENCRYPTION_KEY` — decrypts every stored datasource credential. **Back it up before you need it:** losing it makes those credentials unrecoverable.
- `SPRING_DATASOURCE_PASSWORD`, `DATAPIPELINES_REDIS_PASSWORD` — the infrastructure.
- `GOOGLE_CLIENT_SECRET` (or your own provider's).
- `DATAPIPELINES_DEPLOYMENT_PROMOTION_SERVER_KEY` and `..._TARGET_KEY` — bearer credentials between two deployments.
- `DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD` — a one-time admin credential, and refused under `hardened` for that reason.

They live in **`deploy/secrets.env`**, which is git-ignored and never tracked. `deploy/secrets.env.example` is the template, and `./app.sh --scaffold` generates a real one *from it* with the secrets filled in — one derivation, so the two cannot drift. Scaffold before you start: the first administrator's address is seeded once, at the first boot, and editing the file afterwards does not move it (§8). Under Kubernetes it becomes a `Secret`; under systemd an `EnvironmentFile` with mode `0600`; under ECS it becomes `secrets` entries resolved from your secret store.

Settings, by contrast, are tracked in the repository. That split is deliberate: it lets the repository be the source of truth for what a deployment *does* without ever holding a credential.

### If your organisation runs a KMS

The datasource-credential encryption key can come from a cloud key store instead of `DATAPIPELINES_DB_ENCRYPTION_KEY`. `DATAPIPELINES_DB_KEY_PROVIDER` selects the provider, and [Key Providers](key-providers.md) is the procedure for implementing one against AWS KMS, GCP KMS, Azure Key Vault or Vault. The `env` provider — the key as a variable — is the default and needs no decision from you.

---

## 7. Promotion between environments

Content is built in one environment and pushed to exactly one higher environment: a sender holds the target's URL and a pre-shared key; the receiver holds the same key and, by default under `hardened`, has authoring switched off. The label you gave each deployment is what a receiver records as the source of what it received, which is why the names chain the way your environments do.

The credential is a **server key, not a user**: no account, no scope, no API key, and rotating it is "set the new value on both, restart both". [Deployment §6.3A](deployment.md#63a-promotion-a-receiver-deployment-055) is the receiver's configuration, and [Versioning §10](versioning.md#10-promotion-ui-driven-separate-use-case) is what promotion moves and what it refuses.

---

## 8. First login

The first administrator is created **once**, at the first boot that finds no account for `DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL`.

- With OIDC configured, that account is created when the person with that email address first signs in, and it is granted admin at that moment.
- With local password accounts (`DATAPIPELINES_AUTH_LOCAL_ENABLED=true`), a one-time password from `DATAPIPELINES_AUTH_LOCAL_BOOTSTRAP_PASSWORD` (or a pre-computed hash) seeds that account at boot, and the application forces a new password at first sign-in.

**Seeding does not repeat.** Changing `DATAPIPELINES_AUTH_BOOTSTRAP_ADMIN_EMAIL` afterwards does not create a second administrator and does not move the credential: the account that exists is the account that works. When the two disagree the application says so at every boot —

```
event=auth.local.bootstrap_mismatch configured=you@example.com seeded=admin@local.test
```

— and changes nothing. Sign in as the seeded address, then add or reset the other from **Admin → Users**. If you have lost the one-time password, reset it there too, or start again from an empty database. `./app.sh --start` reads the account out of the database and prints the login that actually exists, rather than the one your file names.

---

## 9. A first deployment, in ten lines

### With Docker Compose

```bash
./app.sh --scaffold                                        # 1  writes deploy/secrets.env
                                                           #    with every secret generated
$EDITOR deploy/secrets.env                                 # 2  your admin address, your
                                                           #    AUTH_BASE_URL, your OIDC client,
                                                           #    and uncomment:
                                                           #      DATAPIPELINES_ENV=prod
                                                           #      DATAPIPELINES_POSTURE=hardened
docker compose -f deploy/compose.yml \
  --env-file deploy/env/defaults.env \
  --env-file deploy/secrets.env up -d                      # 3
curl -fsS http://localhost:8080/health                     # 4
docker compose -f deploy/compose.yml logs datapipelines | grep config.posture   # 5
```

Five lines, because the file you would otherwise have hand-assembled is generated. Without
`app.sh` it is `cp deploy/secrets.env.example deploy/secrets.env`, `chmod 600`, and four
`openssl rand -base64 32 | 24` values pasted into the four keys section 1 names.

Line 5 prints what the deployment thinks it is:

```
event=config.posture env=prod posture=hardened authoring=off demo=(none)
```

### With a bare JAR

```bash
cp deploy/secrets.env.example /etc/datapipelines/secrets.env       # 1
chmod 600 /etc/datapipelines/secrets.env                           # 2
$EDITOR /etc/datapipelines/secrets.env                             # 3  the secrets, plus
                                                                   #    DATAPIPELINES_ENV,
                                                                   #    DATAPIPELINES_POSTURE,
                                                                   #    SPRING_DATASOURCE_URL,
                                                                   #    DATAPIPELINES_REDIS_HOST
set -a                                                             # 4
. deploy/env/defaults.env                                          # 5
. /etc/datapipelines/secrets.env                                   # 6
set +a                                                             # 7
java -jar datapipelines-app.jar                                    # 8
```

Everything that is not the product's to choose — the environment's name, the posture, a
real database endpoint — is a line you uncomment in `secrets.env`, which the template
already lists with `defaults.env`'s value beside it.

To evaluate the product instead, on a laptop, the whole thing is one line — `./app.sh --start --demo nyc` — which is `env=local`, `posture=development`, and a stack with sample data in it.

---

## Appendix: Change Log

| Date | Version | Change |
|---|---|---|
| 2026-09-08 | v1.2 | §2: what authoring OFF means for versions — every draft-creating write is refused, so a hardened receiver holds no drafts at all and the execute default (versioning §7.2, D56) is a RELEASED version by construction rather than by convention. |
| 2026-09-07 | v1.2 | **The `lake` demo family.** §5: a third family with no loader — nothing downloads; dp-lake reads the published objects in place over HTTPS at query time, so it needs egress to S3 (or a configured mirror) whenever a lake query runs. The hardened refusal is unchanged. |
| 2026-09-06 | v1.1 | **One settings file.** `deploy/env/defaults.env` (tracked) and `deploy/secrets.env` (git-ignored) are the whole loader story, in that order, everywhere; `deploy/secrets.env.example` is the template and the list of every variable name. The five files 075 shipped (`laptop.env`, `demo.env`, `example.env`, `posture/development.env`, `posture/hardened.env`) are deleted, and with them the laptop's load-order inversion. The posture's two default rows now live ONLY in the profile ymls, with compose passing them in the valueless form. §3, §4 and §9 rewritten. |
| 2026-09-05 | v1.0 | First version. The `DATAPIPELINES_ENV` / `DATAPIPELINES_POSTURE` pair, the normative posture table, the environment-variable contract and `deploy/env/example.env`, loader recipes for Compose / bare JAR / systemd / Kubernetes / ECS / Nomad, demo as a flag, the secrets split, promotion, and first login. |
