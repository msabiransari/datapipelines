# Two-instance live verification harness (036)

Behavior that only appears with N>1 app instances, proven live against two real
app containers sharing one metadata Postgres and one Redis:

- **Drain test (M1)** — an execution mid-flight on `app1`; `docker kill -s
  SIGTERM` its container. `ExecutionDrainLifecycle` flips readiness first
  (`event=shutdown.readiness_refused`), then cancels every live JDBC statement
  (`event=shutdown.drain_cancelled`), and the executor's `finally` writes
  `ABORTED` — without `pipeline.execution.instance_lost` in `error_json` (that
  code belongs to the sweep, not the drain). Bonus evidence: the `pg_sleep`
  query visible in `pg_stat_activity` before SIGTERM is gone after the drain —
  `Statement.cancel()` reached the source database.
- **Sweep test (M2)** — an execution mid-flight on `app2`; `docker kill`
  (SIGKILL — the drain cannot run). The row stays `RUNNING`, then the
  SURVIVING instance's `StaleExecutionSweeper` flips it to `ABORTED` with
  `pipeline.execution.instance_lost` (`event=execution.swept` in the
  survivor's log). Both apps run with
  `DATAPIPELINES_EXECUTIONS_STALE_TIMEOUT_MINUTES=1`, so the wait is
  ≤ ~2.5 min (1 min staleness + 60s sweep cadence + margin).
- **Pool-invalidation test (050/R1/M3)** — a datasource PUT on `app1`
  repoints it at a second, distinguishable Postgres login; `app2`'s subscriber
  logs `event=datasource.pool_invalidated_remotely` within seconds and its
  NEXT execution serves the new login (`current_user` flips in the result
  rows) — not at `app2`'s next restart. Run separately:
  `./tests/integration-tests/multi-instance/run-pool-invalidation.sh`
  (compose project `mi050`, image `datapipelines:local-mi050` via the
  compose file's `MI_IMAGE` override; transcript
  `gate-logs/050-pool-invalidation.log`).

## Last verified

**2026-09-03 (round 061/T78): `run.sh` runs GREEN against `main` unmodified** —
`drain test: PASS`, `sweep test: PASS`, exit 0, transcript in
`gate-logs/036-two-instance.log`. The 061 brief carried a ledger claim that the
login dance was "stale against current main (email field + confirmPassword)";
that claim is **wrong**, and the measurement is what says so. The harness was
written on 2026-09-01, after every auth change it depends on (the login screen
landed 2026-08-29, `UserSettingsController`'s last change 2026-08-31), and its
`email` / `currentPassword` / `newPassword` / `confirmPassword` field names are
byte-identical to `login.html`, `settings/password.html` and
`UserSettingsController` as they stand. Nothing was changed to make it run.

One real drift DOES exist here and is deliberately left alone: the
`x-app-common` block in `docker-compose.two-instance.yml` carries ~12 of the
base service's environment keys, while `deploy/docker-compose.yml` now passes
~45 (the T32 pass-through contract). The missing keys fall back to the
`application.yml` defaults the bind-mount supplies, which is why the harness is
green — but the "any change to the base service's contract must be mirrored
here" note below is not currently true. Mirroring them is a change to a working
harness with no failing symptom, so it is reported rather than made.

## Run

```bash
./tests/integration-tests/multi-instance/run.sh
```

Prerequisites: the bootJar built (`modules/app/build/libs/datapipelines-app.jar`),
Docker running, and `deploy/.env` present (the script copies it from the main
checkout at `/Users/msabir/development/projects/datapipelines/deploy/.env` if
absent — secrets stay local; the file is git-ignored). The image
`datapipelines:local-mi036` is built on first run if missing.

The full transcript goes to `gate-logs/036-two-instance.log` (git-ignored).
The script exits non-zero unless BOTH tests pass, and always tears down with
`docker compose -p mi036 down -v` (EXIT trap — success or failure).

## How it is put together

- `docker-compose.two-instance.yml` defines two NEW services (`app1` on
  host port 18080, `app2` on 18081) layered over `deploy/docker-compose.yml`
  + `deploy/docker-compose.local.yml`; the base `datapipelines` service is
  never started. Compose concatenates `ports` across files, so overriding the
  base service's port is unreliable — new services are the robust shape. Their
  environment duplicates the base service's contract; `restart: "no"` is
  load-bearing because the harness kills these containers on purpose.
- Compose v5 resolves relative bind sources against the file that DECLARES
  them, so the `application.yml` bind uses `${MI036_REPO_ROOT}`, exported by
  `run.sh` before every compose invocation.
- Auth: the seeded local admin (`mi-admin@example.com` / one-time
  `mi036-onetime`) is logged in with the CSRF double-submit dance (GET /login
  for the `dp_csrf` cookie + hidden `_csrf`, then POST the form), the forced
  first-login password change is completed via
  `POST /partials/account/password`, and an admin-scoped API key is minted via
  `POST /api/v1/auth/api-keys` (session cookie + `DP-CSRF-Token` header). All
  later calls use the `DP-API-Key` header (CSRF-exempt).
- The slow execution is a one-node DQL pipeline whose template body is
  `SELECT pg_sleep(300) AS slept`, registered against a POSTGRES datasource
  that points at the metadata Postgres itself. The execute call streams SSE;
  the harness keeps curl alive until the container dies, because closing the
  client early triggers the 30s disconnect-grace cancellation and would
  pollute the evidence.
- Row state is read straight from the metadata DB:
  `docker exec mi036-postgres-1 psql -U datapipelines -d datapipelines -tAc
  "SELECT status, error_json FROM pipeline_executions ..."`.
