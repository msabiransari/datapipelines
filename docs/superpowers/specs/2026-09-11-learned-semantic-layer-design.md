# Learned Semantic Layer — design record (v0.1, 2026-09-11)

**Status:** owner-directed design, ready for a v1 lane. Settles P1 of
`docs/semantic-layer-research.md` (Part five, Decisions 1–2: no hand-authored model; the
layer is learned by agents and merged into the metadata surface). Where this record and the
research brief disagree, this record wins.

**Owner rulings this record encodes (2026-09-11):**

- The agent must LEARN a datasource before it assumes anything about it; what it learns
  should not die with the session. ("Use intelligence and mechanics to keep the semantics.")
- Retrieval-by-similarity is a *lookup* mechanism, not a store: facts are structured rows
  keyed by the object they describe, shown at the moment the agent introspects that object.
- **Database facts are not workspace-bound; business facts are.** Two scopes, one table.
- **Nothing JDBC metadata already provides is stored.** Types, nullability, keys, comments,
  the lake's declared partition column are served live from introspection, never copied.
- Facts are never edited in place: drift is **detected mechanically, marked honestly,
  superseded with evidence.**

## 1. Why

`notes/pipeline-3.txt` (2026-09-11): an agent spent ~40 tool calls learning — correctly —
that the trip timestamps are naive local time, that `observations.value` is already in the
standard unit named by `unit`, that the taxi feed is a hash sample, that `calendar.cal_date`
is ISO text, that zone 132 is JFK. Every one of those facts evaporated when the session
ended. The next agent pays the same forty calls, or guesses. On a customer's own warehouse
the facts are the same kind (units, time zones, sampling, what an enum value means, which
column is "revenue") and the cost of guessing is a wrong number in front of a customer.

The skill's new first step (2026-09-11) is *learn before you assume*. This layer is its
second half: *record what you learned, with the query that showed it* — so the learning
compounds per deployment, per datasource, across every agent and every session.

## 2. Decisions

| # | Decision | Why |
|---|---|---|
| D-S1 | **Two scopes.** `DATASOURCE` facts describe the data and are bound to the datasource (instance-level since RBAC D-R6; visible wherever the datasource is granted). `WORKSPACE` facts are an organisation's meaning and are bound to the workspace. One table, one `scope` column. | A unit or a time zone is true for everyone reading that column; "revenue = fare_amount, tips excluded" is one organisation's definition and another workspace on the same warehouse may disagree. |
| D-S2 | **Never store what introspection says.** The `kind` list (§4) is closed and contains no `type`, `nullable`, `key`, `comment`, `partition` kind. Introspection stays the source for those and is what facts are checked against. | Two copies of a type drift; one is a lie by the second week. |
| D-S3 | **Facts reference objects structurally.** `refs[]` of `(datasource, schema?, table, column?)` — never only in prose. | Drift detection is a set difference over refs, not text parsing. |
| D-S4 | **Evidence-bearing.** A fact carries the probe that established it (`evidence_sql`, run through `sql_probe` or a node execution). A fact without evidence is `asserted`; with evidence it is `observed`; a human can mark it `verified`. A fact is never higher-trust than its evidence. | "The pipeline executed" is not evidence of semantic correctness; an unverified store compounds wrong facts (owner, 2026-08-13). |
| D-S5 | **Conflicts coexist and are shown as conflicts.** Two live facts of the same `kind` on the same refs (gross vs net revenue) are both served, flagged `conflict`, with provenance. Never a silent winner. | The reader must decide; a store that picks for them is a store that lies. |
| D-S6 | **Drift: detect at read, mark, supersede — never edit, never auto-remap.** A ref that no longer resolves flips the fact to `stale` (served, marked, never as current truth); a table whose column set changed around a still-resolving ref → `needs_review`. A new fact recorded with `supersedes` links the history. No machine ever guesses that a dropped column and a new column are the same thing. | A rename and a "drop + unrelated add" are indistinguishable to a machine. |
| D-S7 | **Merged into the metadata surface.** `datasources_get`, `_get_tables`, `_get_columns` return the facts for the objects they return, inline, with scope, trust, provenance summary. No separate "query the memory" step to forget. | The fact is needed exactly where the agent is already looking (research Decision 2). |
| D-S8 | **Recording is an authoring act.** `semantics_record` needs `author` capability; DATASOURCE-scope additionally needs the datasource granted to the active workspace; WORKSPACE-scope records into the active workspace. Viewers record nothing. Keys: `author` scope, same rules. | Same bar as writing a pipeline that reads the datasource. |
| D-S9 | **Provenance crosses workspaces only as far as the reader may see.** A DATASOURCE fact recorded from workspace A is visible in B with its evidence, trust and "recorded via another workspace"; the `source_pipeline_id` link renders only where the reader can read that pipeline (D-R5 holds). | A fact about shared data is shared; a link into a private workspace is not. |
| D-S10 | **v1 has no vector index and no harvesting.** Exact lookup by object key covers the authoring moment. A similarity index over facts + pipeline descriptions ("what do we know about revenue?") and harvest-from-saved-pipelines are round 2, measured against v1's usage first. | Ship the loop, measure it on the owner's own pipeline tests (§9), then widen. |
| D-S11 | **Retention: facts are never hard-deleted by users.** `retired` is a state; superseded facts keep their row. A datasource delete cascades its DATASOURCE facts (the object is gone); a workspace deactivation hides its WORKSPACE facts (11A.3). | History is the audit; the drift guard needs the old refs. |

## 3. The store (V25)

```sql
CREATE TABLE learned_facts (
    id                  UUID PRIMARY KEY,
    scope               TEXT NOT NULL CHECK (scope IN ('DATASOURCE','WORKSPACE')),
    workspace_id        UUID REFERENCES workspaces(id),        -- NULL iff scope = DATASOURCE
    datasource_name     TEXT NOT NULL REFERENCES datasources(name) ON DELETE CASCADE,
    kind                TEXT NOT NULL,                          -- §4, closed list, CHECK
    fact                TEXT NOT NULL CHECK (length(fact) BETWEEN 8 AND 1000),
    refs_json           JSONB NOT NULL,                         -- §3.1, ≥ 1 ref
    evidence_sql        TEXT,                                   -- the probe that showed it
    evidence_summary    TEXT,                                   -- what the probe returned, ≤ 300 chars
    trust               TEXT NOT NULL CHECK (trust IN ('asserted','observed','verified','needs_review','stale','retired')),
    schema_fingerprint  TEXT NOT NULL,                          -- §6, per referenced table, at record time
    recorded_by         UUID NOT NULL REFERENCES users(id),
    recorded_via        TEXT NOT NULL,                          -- WriteSurface: mcp | session | api_key
    recorded_in         UUID NOT NULL REFERENCES workspaces(id),-- the ACTIVE workspace at record time (provenance)
    source_pipeline_id  UUID REFERENCES pipelines(id),
    source_version      INT,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verified_by         UUID REFERENCES users(id),
    verified_at         TIMESTAMPTZ,
    supersedes          UUID REFERENCES learned_facts(id),
    retired_at          TIMESTAMPTZ,
    retired_reason      TEXT,
    CONSTRAINT chk_scope_workspace CHECK ((scope = 'WORKSPACE') = (workspace_id IS NOT NULL))
);
CREATE INDEX ix_learned_facts_object ON learned_facts (datasource_name, (refs_json->0->>'table'));
CREATE INDEX ix_learned_facts_ws     ON learned_facts (workspace_id) WHERE workspace_id IS NOT NULL;
```

### 3.1 `refs_json`

An array of `{ "schema": "…"|null, "table": "…", "column": "…"|null }`, all on `datasource_name`.
A fact about a column has one ref; a join-path fact has two; a table-grain fact has a ref with
`column: null`. Refs are validated at record time against live introspection: **a ref that
does not resolve is refused** (`semantics.ref_unresolved`, 400) — the store never starts stale.

### 3.2 `schema_fingerprint`

Per referenced table: SHA-256 over the sorted `(column, canonical type)` list as introspection
returns it, joined; several tables → the sorted concatenation. Computed at record time and
recomputed at read (§6).

## 4. Kinds (closed; the CHECK constraint and `enums.md §16`)

| kind | scope | meaning | example fact |
|---|---|---|---|
| `unit` | DATASOURCE | the unit of a numeric column | "value is in the standard unit named by `unit` (°C, mm, m/s) — not GHCN tenths" |
| `time_zone` | DATASOURCE | what a timestamp's wall-clock means | "pickup_ts is naive local (America/New_York); no UTC offset" |
| `sampling` | DATASOURCE | the population relation of a table | "1-in-16 deterministic hash sample of all yellow trips; multiply counts by 16 to estimate" |
| `grain` | DATASOURCE | one row is one what | "one row per (station, day, element)" |
| `window` | DATASOURCE | the data's coverage in time | "2023-01-01 → 2024-12-31 inclusive" |
| `enum_meaning` | DATASOURCE | what a coded value means | "location_id 132 = JFK Airport, 138 = LaGuardia Airport" |
| `join` | DATASOURCE | how two tables relate | "trips.pu_location_id → zones.location_id, many-to-one" |
| `caveat` | DATASOURCE | a trap | "cal_date is ISO-8601 TEXT — cast to DATE before joining a DATE column" |
| `format` | DATASOURCE | encoding of a text column | "holiday_name is '' (never NULL) on a non-holiday" |
| `definition` | WORKSPACE | a business measure or entity | "revenue = SUM(fare_amount); tips and tolls excluded" |
| `exclusion` | WORKSPACE | what a business question leaves out | "NYC boroughs means the five; drop EWR, Unknown, N/A" |
| `preference` | WORKSPACE | how this organisation wants a thing computed | "share comparisons within mode only — the taxi feed is a sample" |

No `type`, `nullable`, `key`, `comment`, `partition`, `row_count` kinds exist (D-S2). A
recorded fact whose text merely restates a column's type is not refused by machine (it cannot
be told apart) — the skill tells the agent not to, and human review can retire it.

## 5. Trust

`asserted` (no evidence) → `observed` (evidence present, at record time) → `verified` (a human
confirmed; UI round 2, or REST). Mechanical demotions: `needs_review` (fingerprint changed,
refs resolve), `stale` (a ref no longer resolves), `retired` (explicit, with reason). A
superseding fact starts at its own evidence level; the superseded one becomes `retired`
with reason `superseded`. Promotion by agreement (two independent pipelines computing the
same definition) is round 2.

## 6. Drift — the read-time check

Every introspection response that carries facts (D-S7) recomputes each fact's fingerprint
from the columns it just read (zero extra I/O):

- all refs resolve, fingerprint equal → served as stored;
- all refs resolve, fingerprint differs → served with `trust: needs_review` and
  `drift: "table columns changed since this was recorded"`; the row is updated (a write on
  a read path is acceptable here: it is idempotent and the alternative is serving a mark we
  computed and then forgot);
- a ref does not resolve → `trust: stale`, `drift: "column X no longer exists"`; row updated;
- `retired` facts are not served (listable via `semantics_list` with `include_retired`).

A `stale` or `needs_review` fact is shown **beside the current columns** so the agent (or a
person) can re-verify and record a superseding fact. Nothing re-maps automatically (D-S6).

## 7. Surfaces

### 7.1 MCP (three tools; tool count +3, all counts move in the same commit)

- `semantics_record` — `{scope, datasource, kind, fact, refs[], evidence_sql?, evidence_summary?, source_pipeline_id?, supersedes?}` → the stored fact. Validation: refs resolve (§3.1); `kind` in the list for the scope; `evidence_sql` must be a single read-only statement (same rule as `sql_probe`) and is **run once** at record time through the probe path — its first rows become `evidence_summary` if none was given, and a probe failure refuses the record (`semantics.evidence_failed`). Matrix: `author` / `AUTHOR`; DATASOURCE scope also requires the grant (`datasource.grant_required` otherwise, 404 per D-R5).
- `semantics_list` — `{datasource?, table?, scope?, include_retired?}` → facts with trust, drift, provenance summary. `read` / `VIEW`.
- `semantics_retire` — `{id, reason}` → `retired`. `author` / `AUTHOR`; only facts the caller's workspace may see; DATASOURCE facts recorded from another workspace need `WS_ADMIN` (D-S9's flip side: you may not silently retire what someone else established — round 1 keeps it simple and strict).

### 7.2 Introspection enrichment (D-S7)

`datasources_get` gains `facts[]` (table-less facts: `window`, `sampling` on the datasource
as a whole); `datasources_get_tables` gains per-table `facts[]` (`grain`, table-level
`caveat`, `join`); `datasources_get_columns` gains per-column `facts[]`. Each fact renders as
`{id, scope, kind, fact, trust, drift?, evidence_summary?, recorded_via, recorded_at,
from_this_workspace: bool, source_pipeline?: {id, name} | null}` — `source_pipeline` present
only when the reader can read it (D-S9). Facts of the same kind on the same refs → each
carries `conflict: true` (D-S5). The REST twins (`GET /datasources/{name}/schemas|tables|columns`)
carry the same block; `rest-api.md §9` documents it.

### 7.3 UI (round 1: read-only)

The datasource detail page (both the lake detail and — after 114 — the list's row expansion)
shows the facts per table with trust badges. Recording and verifying from the UI is round 2.

## 8. The skill

The 2026-09-11 skill rewrite's first step *Learn before you assume* gains its second half,
verbatim in `SKILL.md`:

> **Record what you learned, with the query that showed it.** After you have established a
> fact about the data that introspection could not tell you — a unit, a time zone, a sample
> rate, a grain, what a coded value means, a join that holds — call `semantics_record` with
> the probe you ran. Read the facts already attached to a table before probing it: a fact
> marked `observed` or `verified` with evidence saves you the probe; a fact marked `stale`
> or `needs_review` is a warning, not a truth — re-verify and record the superseding fact.
> Never record what introspection already returns (types, keys, comments).

`references/tools.md` gets the three tools. Nothing demo-specific anywhere (owner rule).

## 9. Effectiveness — measured on the owner's own pipeline tests

The v1 acceptance is not a unit test. It is the owner building pipelines with his examples:

1. **Session 1 (fresh store):** the agent learns and records. Count: facts recorded per
   session; the audit log carries `semantics.recorded` per fact.
2. **Session 2 (same datasources, a different question):** count the facts the agent READ
   from introspection before its first probe, and the probes it did NOT run because a fact
   answered the question (the transcript shows both). Target: the second session makes
   fewer schema probes than the first on the same tables, and its pipeline description
   cites recorded facts.
3. **Session 3 (drift injected — rename one column in a demo table):** the fact goes
   `stale` at the next introspection, is shown beside the current columns, and the agent
   records a superseding fact. Target: no pipeline written against the dropped column.

Recorded in `notes/` per session; the numbers decide round 2's shape (harvesting, index,
promotion rules).

## 10. Tests (the gate)

1. Migration + inventory (`FlywayMigrationIntegrationTest`).
2. `SemanticsRecordToolTest`: each kind × scope; ref validation refuses an unknown column;
   evidence runs and is summarised; a failing probe refuses the record; scope rules through
   the real matrix (viewer refused; author without the grant → 404).
3. Enrichment: `datasources_get_columns` on a table with facts carries them; conflict
   flagging; provenance visibility across two workspaces (the 112 sweep's two-tenant
   fixture) — `source_pipeline` present for the owner workspace, absent for the other.
4. Drift: record → rename the column in the test container → next `get_columns` serves
   `stale` with the message and the row is updated; add a column → `needs_review`;
   supersede → the old fact `retired(superseded)`.
5. `WorkspaceIsolationSweepTest` still green — the three tools and the REST block are
   workspace-bound.
6. Catalogue/matrix/drift pins (tool count, `auth.md §7.6` rows, `enums.md §16` kinds via a
   spec-drift test on the CHECK list).
7. Skill artifacts regenerated (`SkillDistributionTest`).
8. One E2E through the real MCP loop: `sql_probe` → `semantics_record` → `get_columns`
   shows it → a second key in another workspace with the grant sees the fact but not the
   pipeline link.

## 11. Rounds

- **Round 1 (v1, one lane, ~a day for an Opus-class model):** V25, the three tools, the
  enrichment on MCP + REST, drift at read, the skill sentences, the read-only UI list, the
  tests above. No harvesting, no index, no UI recording.
- **Round 2 (after §9's measurements):** harvest candidate `definition` facts from saved
  pipeline descriptions (proposed, `asserted`, never auto-observed); a similarity index over
  facts + descriptions for question-level retrieval; UI record/verify/retire; promotion by
  agreement; export as OSI (the research brief's "door left open").

## 12. Open items

- O-1: should `evidence_sql` be re-runnable from the UI ("re-verify" button) in round 1? (Cheap; the probe path exists. Lean yes if it costs < an hour.)
- O-2: the `enum_meaning` kind will collect many rows on lookup tables (one per value) — allow a compact `values: {code: meaning}` payload inside `fact`, or one row per value? Lean: one row per value; the reader gets exactly the value it filters on.
- O-3: DATASOURCE facts on a datasource later un-granted from the recording workspace: still visible wherever it is granted (the fact is the datasource's). Confirm with the owner.
- O-4: rate limit on `semantics_record` per key (an agent in a loop recording the same fact): refuse an exact duplicate `(scope, datasource, kind, refs, fact)` as `semantics.duplicate` (409) rather than rate-limit.

## 13. As shipped (118, round 1)

Everything above shipped as written unless listed here. Each deviation names the thing the code contradicted and what was built instead.

Public page: the marketing site's **/semantic-layer** (119 §B) renders this design for buyers and engineers — the loop, the scopes, the trust ladder, drift, the today/next split of §11, and the §19 kinds table (verbatim, drift-guarded) — with 118's real `facts[]` wire example as the artifact.

| Where | The record says | What shipped, and why |
|---|---|---|
| §3 index | `ix_learned_facts_object ON (datasource_name, (refs_json->0->>'table'))` + `ix_learned_facts_ws` | `idx_learned_facts_datasource (datasource_name)` + `idx_learned_facts_workspace (workspace_id) WHERE NOT NULL`. The expression index would index only the FIRST ref of a multi-ref (join) fact and serve none of the reads that ship — every read is per datasource, then narrowed to a table in the reader over a set that is hundreds of rows at most. House `idx_` prefix. |
| §3 CHECKs | `scope`, `fact` length, `trust` | Also `chk_learned_facts_kind` (the §4 list — enums.md §19 and the Kotlin enum are drift-tested against it), `chk_learned_facts_kind_scope` (each kind's scope stated once, in the database), `chk_learned_facts_refs` (a JSON array, ≥ 1), `chk_learned_facts_summary_length` (≤ 300), `chk_learned_facts_via` (the V20 write-surface set), `chk_learned_facts_retired` (`retired` ⇔ `retired_at`). `source_pipeline_id` is `ON DELETE SET NULL` — a purged pipeline detaches; the fact outlives it. |
| §3.2 fingerprint | "several tables → the sorted concatenation" hashed | Stored as sorted `tableKey=sha256` entries joined by `;` — each table's digest stays ADDRESSABLE, because the read-time check recomputes only the table whose columns it just read; hashing the concatenation once more would make a two-table join fact un-checkable from either listing alone. |
| §4 kinds / §7.2 `datasources_get` | "table-less facts: `window`, `sampling` on the datasource as a whole" | §3.1 requires ≥ 1 ref with a table, so there is no table-less fact. `datasources_get.facts[]` carries every visible fact of kind `window` or `sampling` (table-grain refs), served as stored — this read opens no connection and has nothing to recompute drift against. |
| §6 "shown beside the current columns" | a `stale` fact beside the columns | `datasources_get_columns` returns a bare ARRAY of columns; a fact whose column is gone has no column entry to ride on. It is marked on the row at that read, and it is served on the table's entry in `datasources_get_tables` (beside the listing an agent reads before asking for columns) and by `semantics_list` / the UI. A `needs_review` fact still resolves and rides on its column. |
| §6 tables listing | "every introspection response that carries facts recomputes each fact's fingerprint" | A tables listing has no columns in hand: it can only decide "the table is no longer listed" — and only when the listing is COMPLETE (no `namespace`/`schema` filter, not truncated); a filtered listing proves nothing about what it did not list and marks nothing. |
| §7.1 `semantics_record` matrix | `author` / `AUTHOR`, DATASOURCE scope needs the grant (`datasource.grant_required`, 404) | As specified; the grant check IS the §5.3 visibility gate (`requireVisible`), so an ungranted datasource is `datasource.not_found` (the same 404, the D-R5 answer) BEFORE anything runs — `datasource.grant_required` is not emitted separately. |
| §7.1 `semantics_list` | `{datasource?, table?, scope?, include_retired?}` | `datasource` is REQUIRED (the visibility gate is per datasource; a cross-datasource listing is round 2's index question) and `since` was added — the §9 "what was recorded since <time>" question. Served as stored (no columns in hand). |
| §7.1 `semantics_retire` cross-workspace rule | `WS_ADMIN` | As specified, raised as `auth.role_required` (403, `details.required: ws_admin`) — the existing capability refusal, not a new code. |
| §7.2 fact shape | as listed | Plus `conflict: true` (D-S5), omitted when false; `semantics_record`'s result and `semantics_list`'s rows are the FULL shape (the summary plus `datasource`, `refs`, `evidence_sql`, `recorded_by`, `source_version`, `supersedes`, `retired_at`/`retired_reason`). |
| §7.3 UI | "the list's row expansion (after 114)" | No row expansion exists. A read-only **Facts** dialog on every datasources-list row (every member; `data-read`, not a verb) plus the same table inline on the LAKE detail page — one fragment, one model. |
| Error codes | `semantics.ref_unresolved`, `semantics.evidence_failed`, `semantics.duplicate` | Plus `semantics.kind_invalid`, `semantics.fact_invalid`, `semantics.evidence_refused` (the classifier / a `:parameter` — refused before any connection), `semantics.not_found` (pipeline-contract §13.15, seven rows). |
| Module fence | "`modules/datasources` (the fact repository + drift check); `modules/mcp-server` (three tools + enrichment)" | `datasources` may depend on `typesystem` only (module-structure §5.4), so the principal-aware half — capability rules, the audit rows, the D-S9 pipeline link — lives in `modules/application` (`SemanticsService`, `LearnedFactsEnricher`), the `LakeTableRegistryService` precedent for one validated path behind two surfaces; the beans are declared by `web`'s `SemanticsConfiguration` (the `LakeConfiguration` precedent). The repository, recorder, fingerprint and drift detector are in `datasources` as specified. |
| §8 skill | "the *Learn before you assume* step gains its second half" | That step does not exist yet (the skill rewrite had not landed); the §8 paragraph sits verbatim under golden-path step 1 with a "read the `facts` that arrive" lead-in — the orchestrator merges the two. |
| §12 O-1 | re-verify button in round 1 | Not built: the UI is read-only in round 1 (§7.3); a re-verify is a trust write. |
| §12 O-2 | one row per value | One row per value (nothing compact); the 1000-character `fact` window admits a short list where a reader wants one. |
| §12 O-3 | confirm with the owner | Shipped as the record says: a DATASOURCE fact stays visible wherever the datasource is granted, after the recording workspace loses its grant included — the fact is the datasource's. `recorded_in` and `from_this_workspace` keep the provenance honest. Owner to confirm. |
| §12 O-4 | refuse an exact duplicate as `semantics.duplicate` (409) | Shipped: `(scope, workspace_id, datasource, kind, refs, fact)` over LIVE rows, refs normalised so order does not matter; `details.existing_id`. A retired fact is not a duplicate (re-recording is how a retirement is corrected). No rate limit. |
