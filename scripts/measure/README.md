# `scripts/measure/` — the round-108 measurements

Every number in `handbacks/108-executor-hardening.md` came from one of these. Each script
prints, in this order and on every run:

1. `uptime`'s load averages, **before** it measures anything. A measurement taken at
   1-minute load > 15 is discarded and re-run; the handback quotes the load beside the number.
2. The git SHA of the tree it ran against.
3. Its own timings, as a table.

They are re-runnable by anyone with the repository. They take no arguments and need no lane
stack: the workloads are H2 and Testcontainers Postgres, driven from the same test fixtures the
suite uses, so a reader can reproduce a row without provisioning anything.

## Why the measurements are JUnit classes and not shell

The thing being measured is JDBC behaviour inside the executor — whether a driver honours
`Statement.queryTimeout` on a `CREATE TABLE AS`, how long it takes a cancel to land, whether two
staging drains overlap. Driving that from a shell would mean reimplementing the executor's
statement handling in a script, and then measuring the reimplementation.

They are gated on `DP_MEASURE=1`, so an ordinary gate SKIPS them (they appear in the recount as
`skipped`, not as tests). A measurement is not an assertion and must never fail a build: it
reports numbers, and a human reads them.

## The scripts

| Script | Handback section | What it measures |
|---|---|---|
| `01-driver-timeouts.sh` | §1 | Per statement kind: does the driver honour `queryTimeout`, does the T202 conversion fire, and how long from deadline to failure |
| `02-staging-overlap.sh` | §2 | Three independent source nodes: per-node windows and execution wall time, plus staging throughput at several batch sizes |
| `03-pressure.sh` | §3 | Measured RSS of an execution staging at the default budget, and the DuckDB/executor CPU contention |
