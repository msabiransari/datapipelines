# Versioning: Draft, Release, Promotion

**Status:** v1.0 — proposed (pending review)
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Contract](pipeline-contract.md) (§13 error catalog, §17 persistence), [Templates](templates.md), [Metadata DB](metadata-db.md) (§4.4/§4.5/§4.8/§4.9 — DDL authority), [REST API](rest-api.md), [Pipeline Editor UI](pipeline-editor.md)
**Last updated:** 2026-08-31

---

## 1. Purpose and Scope

This spec defines the **version lifecycle** for pipelines and templates: how edits become
drafts, how a human releases (locks) a draft into an immutable version, and how released
versions are later **promoted** to exactly one higher environment.

It decides:

- The write rule: copy-on-write drafts living in the existing version tables — no separate
  draft tables.
- The concurrency rule: content-hash preconditions on every mutation — no last-write-wins.
- The release rule: an explicit, UI-only human action. Agents never release.
- The identity rule: version numbers are **global identities** preserved across
  environments — imports never renumber.
- The promotion rule: UI-driven, latest-released-only, guarded on both ends.

Everything here applies symmetrically to pipelines (`pipeline_versions`) and templates
(`template_versions`) unless a section says otherwise.

---

## 2. Decision Log

Ratified 2026-08-31, operator + model session:

| # | Decision | Rationale |
|---|---|---|
| D1 | Drafts are rows in the existing version tables with a `status` column. **No separate draft tables.** | One lifecycle, executions can reference drafts via the existing composite FK, and draft templates get real pre-allocated version numbers that draft pipelines can pin directly. |
| D2 | **The one write rule:** writing to a RELEASED version first copies it to a draft (copy-on-write); writing to a DRAFT overwrites it in place. | Bounded mutation — at most one mutable row per pipeline — instead of a version appended per save. Kills the version pile. |
| D3 | Every mutation carries a **content-hash precondition**; mismatch is a 409. | Two engineers (or an engineer and an agent, or two tabs) must never silently overwrite each other. No last-write-wins, ever. |
| D4 | **Release is an explicit UI action by a human.** Agents/MCP always leave drafts. | "Lock means release" is a human judgment made after testing. |
| D5 | **Version numbers are global identities.** Import preserves the source version number; renumbering is forbidden. | Cross-env renumbering breaks template pins silently (verified latent defect, §10.1) and confuses humans correlating envs. |
| D6 | Promotion pushes **only the latest RELEASED version** per pipeline. The promotion UI lists only pipelines that are released **and** have a version number greater than the target's current version. | Drafts and same-version re-pushes must never be offered; the server enforces the same rules independently (§11.3). |
| D7 | **No hotfixes.** A bug fix is a new release in the dev environment, promoted like any other. Receiver environments never author. | Local authoring on a receiver destroys number alignment (D5); the process rule keeps sequences globally coherent. |
| D8 | Promotion is a **separate, UI-driven use case** against a single configured higher environment. | Not an agent action; not automatic; not multi-hop. |

---

## 3. Version Lifecycle

### 3.1 States

| Status | Row mutability | Meaning |
|---|---|---|
| `DRAFT` | **Mutable** — in-place updates allowed (D2) | The working copy. At most one per pipeline/template at any time. |
| `RELEASED` | **Immutable** — never UPDATEd | The locked version. Executable, pinnable, promotable. |
| `DISCARDED` | Immutable | A draft that was thrown away **after it had been executed** (the execution FK blocks hard delete, §3.4). Kept as history. |

The immutability discipline of [Metadata DB §4.5](metadata-db.md) becomes: *RELEASED and
DISCARDED rows are never UPDATEd; DRAFT rows may be; only the DB predicate `status = 'DRAFT'`
permits mutation.* One bounded, checkable exception replaces "append-only forever".

State transitions:

```
            (create: v1 lands here)
                 │
                 ▼
             ┌────────┐   1st write after release    ┌────────┐
   ┌────────►│RELEASED│ ────(copy-on-write, §5.1)───►│ DRAFT  │
   │         └────────┘                              └───┬────┘
   │             ▲  ▲                            writes │ (in-place, §5.2)
   │             │  │                                   ▼
   │             │  └──────────── release (§5.3) ───────┤
   │             │                                      │
   │             │             discard, never executed ─┘ (row deleted)
   │             │
   │      ┌───────────┐  discard, was executed (FK blocks delete)
   └──────┤ DISCARDED │◄──────────────────────────────────────────
          └───────────┘
```

### 3.2 The one write rule (copy-on-write)

> **When we write, we make sure it is not a released version. If it is released, we first
> create a draft out of the released version, then write. Otherwise we write — no problem.**

Mechanically, `PUT /pipelines/{id}` resolves the current state of the pipeline and takes
exactly one of two branches (§5.1/§5.2). There is no third branch: a PUT never appends a
released version, and a PUT never touches a RELEASED or DISCARDED row.

**Creation is not a modification** — there is nothing to copy from. `POST /pipelines`
(still) lands version 1 directly as RELEASED, so an MCP-authored pipeline is executable the
moment it is created. Every subsequent change is draft-first.

### 3.3 One draft per entity

A partial unique index enforces at most one DRAFT row per pipeline (and per template):

```sql
CREATE UNIQUE INDEX uq_pipeline_versions_one_draft
    ON pipeline_versions (pipeline_id) WHERE status = 'DRAFT';
```

This is what makes the write rule race-safe: two simultaneous first-writers both see
"released", both attempt the draft insert — one wins; the loser's insert violates the index
and surfaces as `pipeline.version.conflict` pointing at the new draft's hash (§4). The loser
must re-read and rebase.

### 3.4 Version number allocation and discard

- A draft **pre-allocates** `current_version + 1` at creation. The number is stable across
  all in-place writes to that draft.
- `pipelines.current_version` keeps its existing meaning — **the latest RELEASED version**.
  It does not move while a draft exists. Every existing reader (execute-default, editor
  load, datasource joins, MCP get) keeps its semantics unchanged.
- **Discarding a never-executed draft hard-deletes the row; the number returns to the pool.**
- **Discarding an executed draft is impossible** — the `pipeline_executions` composite FK
  blocks the delete. (The constraint is `fk_executions_pipeline_version` in
  `V1__initial_schema.sql:130`; it declares no `ON DELETE` clause, so it is `NO ACTION` — which
  blocks exactly as `RESTRICT` does here. Do not "fix" it to `RESTRICT` on the strength of this
  spec: the difference is only deferrability, and nothing defers it.) Such drafts flip to `DISCARDED` and the number stays
  consumed. History shows "v4 draft discarded".
- Version numbers are therefore monotonic per pipeline and **never reused** once any
  execution has referenced them.

### 3.5 What rides the draft vs. the release

Pipeline-row metadata (`name`, `display_name`, `description` on `pipelines`) **rides the
release**: a draft carries its own metadata in its row, the RELEASED row and the
`pipelines` row keep the released values until lock. The editor shows draft metadata with a
"pending release" affordance. (Flagged for reviewer confirmation — see §14.)

---

## 4. Content Hash (`body_hash`)

### 4.1 Definition

`body_hash` = **SHA-256 of the canonical body JSON**, stored on every version row at write
time. For pipelines the canonical form is the one `PipelineSerializer` already emits (the
portable seven-field body, pipeline-contract §3 note); templates gain the equivalent
canonical serialization as part of this work. Canonicalization is **frozen**: key order is
pinned by the serializer and must never drift between app versions — the hash is only as
good as the canonical form.

The stored hash doubles as:

1. The precondition token for every mutation (§4.2).
2. **Cross-server content identity** for promotion delta detection (§11.2) — same content
   hash on two servers ⇒ same body, regardless of environment.
3. A cheap stored-integrity check (recompute vs. stored, e.g. in audits).

### 4.2 The precondition protocol

Every mutation — draft create, draft write, release, discard — carries the hash of the
version the caller based its change on (an `If-Match`-style request header or an explicit
`expected_hash` field; the REST contract fixes the exact spelling at implementation). The
server evaluates the precondition inside the mutating statement's `WHERE` clause:

- **Draft write / release / discard:** expected hash must equal the DRAFT row's stored
  `body_hash`.
- **Draft create (first write after release):** expected hash must equal the RELEASED
  row's stored `body_hash`.

Zero rows affected ⇒ the base is stale ⇒ `409 pipeline.version.conflict` (or
`template.version.conflict`) with the current state in `details`:

```json
{
  "code": "pipeline.version.conflict",
  "message": "Pipeline was modified by someone else after you loaded it.",
  "details": {
    "current_body_hash": "…",
    "current_status": "DRAFT",
    "updated_by": "user-id",
    "updated_at": "2026-08-31T14:03:11Z"
  }
}
```

The client's recovery path is explicit: reload, diff against its own edit, re-apply. The
server never merges and never overwrites. This protects every pairing — two engineers,
engineer + MCP agent, two tabs.

### 4.3 What the hash does NOT do

It is not a diff format, not a merge token, and not a security signature (it is not
keyed). Its only job is equality: *is the thing I am about to modify the thing I looked
at?*

---

## 5. Write Paths

All four paths stay single-statement data-modifying CTEs — the repository house pattern
([PipelineRepository](metadata-db.md#46-pipeline_executions) KDoc) — so each remains atomic
without an enclosing transaction. Sketches; the implementation owns final SQL.

### 5.1 Draft create (copy-on-write, first write after release)

```sql
WITH guard AS (                       -- precondition: caller's base is the released body
    SELECT 1 FROM pipeline_versions
     WHERE pipeline_id = :id AND version = :currentVersion
       AND status = 'RELEASED' AND body_hash = :expectedHash
), draft AS (
    INSERT INTO pipeline_versions
        (pipeline_id, version, body_json, body_hash, status, created_by, updated_by, updated_at)
    SELECT pipeline_id, version + 1, CAST(:bodyJson AS jsonb), :bodyHash, 'DRAFT', :actor, :actor, NOW()
      FROM pipeline_versions
     WHERE pipeline_id = :id AND version = :currentVersion AND status = 'RELEASED'
       AND NOT EXISTS (SELECT 1 FROM pipeline_versions
                        WHERE pipeline_id = :id AND status = 'DRAFT')
    RETURNING pipeline_id, version
)
SELECT * FROM draft, guard            -- guard empty ⇒ 0 rows ⇒ 409
```

### 5.2 Draft write (in-place)

```sql
UPDATE pipeline_versions
   SET body_json = CAST(:bodyJson AS jsonb), body_hash = :bodyHash,
       updated_by = :actor, updated_at = NOW()
 WHERE pipeline_id = :id AND status = 'DRAFT' AND body_hash = :expectedHash
RETURNING version                     -- 0 rows ⇒ 409 (stale base or no draft)
```

A PUT arriving while no draft exists takes §5.1; a PUT arriving while a draft exists takes
§5.2. The branch is decided by the draft's existence, and both branches carry the same
precondition semantics.

### 5.3 Release (lock)

One statement, three effects — validate happens in the service layer immediately before:

```sql
WITH locked AS (
    UPDATE pipeline_versions
       SET status = 'RELEASED', released_at = NOW(), released_by = :actor,
           updated_at = NOW()
     WHERE pipeline_id = :id AND status = 'DRAFT' AND body_hash = :expectedHash
    RETURNING version
), bumped AS (
    UPDATE pipelines
       SET current_version = (SELECT version FROM locked),
           name = :name, display_name = :displayName, description = :description,
           updated_at = NOW()
     WHERE id = :id
    RETURNING current_version
)
SELECT * FROM locked, bumped          -- 0 rows ⇒ 409
```

Preconditions, evaluated server-side before the statement runs:

1. The expected hash matches the draft (§4.2) — *you release what you tested*.
2. **Every template version pinned by the draft body is RELEASED** — templates lock first.
   A pin on a DRAFT template version fails with `pipeline.release.template_not_released`
   naming the template and version. (Pinning a DRAFT template version from a DRAFT pipeline
   is legal while iterating — §6 — and only becomes an error at pipeline release time.)
3. Full §12 pipeline validation re-runs on the draft body. Release is the final save-time
   gate; nothing is released that the validator would refuse.

### 5.4 Discard

```sql
DELETE FROM pipeline_versions
 WHERE pipeline_id = :id AND status = 'DRAFT' AND body_hash = :expectedHash
```

0 rows ⇒ 409. FK violation (the draft was executed) ⇒ retry as the DISCARDED status flip
(§3.1). Both outcomes are transparent to the caller: "discarded".

---

## 6. Templates: Same Lifecycle, Plus the Pin Rule

`template_versions` gains the identical statuses, hash column, one-draft index, and four
write paths (mirrored codes `template.version.conflict`, `template.version.not_draft`).

Because drafts pre-allocate real version numbers, **a DRAFT pipeline can pin a DRAFT
template version** by its number — this is the intended editor loop (edit SQL → run the
node → tweak → release), and it is why drafts live in the version tables (D1). The rule
that makes it safe:

> A pipeline may be **released** only when every template version its body pins is
> **RELEASED**. Draft pins are legal while the pipeline itself is a draft.

Release ordering is therefore always templates-first. Pins are immutable references, so no
cycle is possible.

---

## 7. REST Surface Changes

Additive; existing routes keep their shapes. Exact wire contracts land in
[rest-api.md](rest-api.md) at implementation.

| Route | Change |
|---|---|
| `POST /api/v1/pipelines` | Unchanged: creates v1 **RELEASED** (§3.2). |
| `PUT /api/v1/pipelines/{id}` | **Semantics change:** always writes the draft branch (§5.1 or §5.2). Never appends a released version. Requires the hash precondition. Response carries the version's `status` and `body_hash`. |
| `POST /api/v1/pipelines/{id}/release` | New. Hash-guarded (§5.3). UI-only in practice; no MCP tool is exposed for it (D4). |
| `POST /api/v1/pipelines/{id}/draft/discard` | New. Hash-guarded (§5.4). |
| `GET /api/v1/pipelines/{id}` | Read shape gains `current_version_status` and the draft pointer when one exists. Default body remains the **released** version. |
| Templates (`/api/v1/templates/...`) | Mirror of all the above. |

MCP authoring (`pipelines_update` tool) calls the same PUT and therefore lands its work as
drafts on existing pipelines. An agent iterating produces **one** draft row that it keeps
overwriting — the pile the old PUT-per-save semantics created is gone. A human releases
from the UI when satisfied (D4). The pipelines list gains a "drafts pending release" badge
so unreviewed agent work is visible.

---

## 8. Executing Drafts

Drafts are executable — the editor's test loop depends on it (single-node run, full-draft
run). Because a draft **is** a version row, the existing composite FK records the run
against the real draft version number; nothing about the execution schema weakens.

- `pipeline_executions` gains a `ran_draft BOOLEAN NOT NULL DEFAULT FALSE` snapshot, set at
  execution time, so history distinguishes draft runs from released runs after the version
  is later released (otherwise the distinction is lost when `status` flips).
- Draft executions appear in history with a draft marker; they are never promotable and
  never count as validation for release (the human decides that).

---

## 9. Cross-Environment Identity and Import

### 9.1 Why version numbers must be global identities (verified defect)

Both import paths renumber today: pipeline import **strips** the `version` field
(`PipelineImportService` `SERVER_FIELDS`) and allocates `current_version + 1`; template
import calls the same bump-and-append. That breaks the export bundle round-trip silently:
a dev pipeline pins `foo.sql@2`, the bundled template lands on a fresh target as
`foo.sql@1`, and the pipeline's pin fails `template_version_not_found`. The export/import
pair only round-trips when both environments' numbers happen to be in lockstep — i.e.,
never after any drift. Pins are version numbers; cross-env renumbering is a
correctness-breaking hazard, not a cosmetic one. D5 fixes this by construction.

### 9.2 Import with preserved versions

The import payload may carry `version` (promotion always sends it). When present it is
**honored**; when absent, today's allocate-next-local behavior applies. Target-side rules:

| Target state for that (id, version) | Hash vs target | Result |
|---|---|---|
| Absent | — | Insert as RELEASED at that exact version (`released_at` from source), with source's `body_hash`. Bump `current_version` if greater. |
| Present, RELEASED | **Same** | Idempotent no-op (200). Re-importing an old export is safe. |
| Present, RELEASED | **Different** | `409 pipeline.import.version_conflict`, both hashes in details. Never overwrite. |
| Present, DRAFT (local draft) | any | `409 pipeline.import.version_conflict` — a local engineer's draft is never clobbered. |
| Present, DISCARDED | any | `409 pipeline.import.version_conflict` — consumed numbers are never reused (§3.4). |

Gaps below `current_version` (a target that jumped straight to v6) are expected and
harmless: `current_version` is "max released present".

**Hash recompute guard:** the target recomputes the body hash from the payload body and
refuses with `pipeline.import.hash_mismatch` if it differs from the declared hash — this
catches transfer corruption and canonicalization drift between app versions in one place.

### 9.3 The price of number preservation, accepted

If a receiver ever authors locally (a process violation, D7), its local version numbers
collide with future dev releases. The outcome is a loud 409, resolved procedurally
(re-do the change in dev, release, promote). The design fails safe — it never silently
diverges, and with receivers that never author, the price is never paid.

---

## 10. Promotion (UI-Driven, Separate Use Case)

Against exactly **one configured higher environment** (base URL + API key; config keys
defined in [configuration.md](configuration.md) at implementation — not restated here, per
that doc's sole-authority rule).

### 10.1 Policy

- **No hotfixes** (D7): a bug fix is a new release in dev, promoted like any change.
  Receivers never author; the sole writer of a receiver is promotion.
- Promotion is triggered by a human from the UI (D8). No MCP tool. No schedule, no
  auto-promotion.

### 10.2 The listing rule (what the UI shows)

> The promotion screen lists only pipelines that are **RELEASED** and whose current version
> number is **greater than** the target's current version for that pipeline (a pipeline the
> target does not have counts as target version 0). Drafts are never listed. Same-version
> entries are never listed.

The delta is computed from the target's inventory: the promotion orchestrator reads the
target's per-pipeline `(id, current_version, body_hash)` (a read API addition on the
receiver, specified in rest-api.md at implementation) and compares:

- Same `body_hash` ⇒ nothing to push (version for humans, hash for machines).
- Different hash or absent ⇒ the pipeline is promotable; **only its latest RELEASED body is
  pushed** (D6) — intermediate released versions are not transferred; the target executes
  `current_version` only and its own history references its own numbers.

### 10.3 Server-side guards (independent of the UI)

The UI rule is convenience; the server enforces the same constraints regardless of caller:

1. The promotion orchestrator refuses to push a pipeline whose candidate version is not
   RELEASED → `pipeline.promotion.not_released`.
2. It refuses to push a version not greater than the target's current version for that
   pipeline → `pipeline.promotion.not_newer`. Same-version pushes are a bug, not a no-op
   to swallow.
3. The receiver enforces §9.2's import table — conflict, idempotency, hash recompute.

Both ends guard; a client bug cannot smuggle a draft or a stale version through.

### 10.4 Push order (dependency closure)

Per promotion batch, push in topological order:

1. **Template versions** referenced by any pushed pipeline (direct node refs plus the
   transitive `imports_json` closure — the export bundle already computes this set).
2. **Child pipelines** referenced by PIPELINE nodes (recursively — the export bundle does
   NOT include these today; promotion computes the closure itself).
3. The **pipelines**, children before parents.

Pins are immutable and cycle-free, so the order always exists. Templates/pipelines already
present at the same version and hash are skipped (idempotent).

### 10.5 Datasource pre-validation

Pipelines reference datasources by name. Before pushing anything, the orchestrator
collects every datasource name the batch references and verifies each exists on the
target; a missing name fails the whole batch with one consolidated error (mirroring the
import service's combined `missing_datasources` report) rather than failing mid-batch.

---

## 11. Schema Changes (Amendment for Implementation)

[metadata-db.md](metadata-db.md) remains the sole DDL authority (its rule D4); these land
there at implementation time, in the same commit as the repositories:

`pipeline_versions` and `template_versions` gain:

| Column | Type | Notes |
|---|---|---|
| `status` | `TEXT NOT NULL DEFAULT 'RELEASED'` + `CHECK (status IN ('DRAFT','RELEASED','DISCARDED'))` | Existing rows backfill as RELEASED — zero data migration semantics. |
| `body_hash` | `TEXT NOT NULL` | Backfilled for existing rows (pgcrypto digest or a startup task). |
| `released_at` | `TIMESTAMPTZ NULL` | NULL for drafts; set at release. |
| `released_by` | `UUID NULL` | Actor of the release. |
| `updated_by` | `UUID NULL` | Last draft writer — powers the 409 details. |
| `updated_at` | `TIMESTAMPTZ NULL` | Draft rows only; RELEASED/DISCARDED stay NULL (the immutability note in metadata-db §4.5 is amended accordingly). |

Plus: the two one-draft partial unique indexes (§3.3), and
`pipeline_executions.ran_draft BOOLEAN NOT NULL DEFAULT FALSE` (§8).

The immutability KDoc blocks on both repositories are rewritten to the §3.1 discipline.

---

## 12. Testing Requirements

House discipline: every guard below must be **falsifiable** — revert the production change
and the test goes red (a guard that cannot fail is not a guard).

- **Write-path unit/integration:** draft create (copy-on-write honored, released row
  untouched), draft write (in-place, no new row), release (pointer bumps, statuses flip),
  discard (delete vs DISCARDED-by-FK). Two concurrent draft creates ⇒ exactly one winner,
  one conflict.
- **Hash precondition:** every mutation with a stale hash ⇒ 409 with current hash/author
  details; with the correct hash ⇒ succeeds. Falsification: remove the `WHERE body_hash`
  predicate and watch these go red.
- **One-draft index:** second draft insert violates the partial unique index.
- **Release pin rule:** pipeline release with a DRAFT template pin ⇒
  `pipeline.release.template_not_released`; with all pins released ⇒ succeeds.
- **Import table (§9.2):** each row of the conflict table as a case; hash recompute guard
  catches a doctored payload.
- **Promotion guards:** draft selected ⇒ `pipeline.promotion.not_released`; same-version ⇒
  `pipeline.promotion.not_newer`; push order respects the closure (child pipeline absent on
  target ⇒ parent not pushed); datasource pre-validation fails batch atomically.
- **Drift:** new §13.13 codes ↔ `PipelineErrorCodes` constants. `PipelineErrorCodesSpecDriftTest`
  parses the catalogue generically and asserts BOTH directions, with a per-domain non-vacuity
  guard — so this is enforced automatically. **The operational consequence is that the §13.13
  rows and the constants must land in the SAME commit**: a catalogue row without its constant
  turns `main` red immediately, and the drift test lives there permanently. The same applies to
  the mirrored `template.*` codes and `TemplateErrorCodesSpecDriftTest`.

---

## 13. Out of Scope (Deliberate)

- **MCP release tool** — decided against (D4). The REST release endpoint exists for the UI;
  an agent holding a raw MUTATE-scoped API key could call it directly, which is acceptable
  — "not in the agent" means no first-class tool surface.
- **Bulk "release all drafts"** — per-pipeline release only for now; a bulk action can be
  added to the promotion screen later without schema change.
- **Multi-hop promotion, promotion scheduling, promotion history UI** — the single-target,
  human-triggered flow ships first.
- **Merge/rebase tooling for conflicting drafts** — the conflict response gives the current
  hash; rebase is manual by design at this scale.
- **Draft retention jobs** — DISCARDED rows accumulate slowly (only executed-then-discarded
  drafts); a retention job is deferred until measured.

## 14. Open Items for Review

1. §3.5 — pipeline-row metadata rides the release (draft shows its own metadata in the
   editor; the `pipelines` row updates at lock). Alternative: metadata updates land
   immediately on draft create. Recommend as written.
2. §8 — `ran_draft` snapshot column vs. deriving from `released_at` comparison at read
   time. Recommend the column (derivation breaks if a version is released, then a later
   draft reuses… nothing — numbers are never reused — but the column is still the cheaper,
   explicit record).
3. §10.2 — the receiver inventory API shape (list with `(id, current_version, body_hash)`)
   is named but not specified; rest-api.md owns it at implementation.
4. Editor UX for the release action (button placement, "draft pending release" badge,
   conflict reload flow) is pipeline-editor.md's next revision, not this doc's.

---

## Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-08-31 | v1.0 | orchestrator review | Review pass before commit. Corrected §3.4: the executions FK is `NO ACTION` (its declaration carries no `ON DELETE` clause), not `RESTRICT` — it blocks identically here, but an implementer reading the old wording would have written the wrong DDL. §12 now states the operational half of the drift coupling: catalogue rows and constants land in the SAME commit, because the drift test lives on `main` permanently. Also grouped the doc into `DocsCatalog` "Contracts" — 033's in-app docs index fails at init on an ungrouped doc, so the spec could not land without it. Verified against the tree: the import-renumbering defect (§9.1), `PipelineImportService.SERVER_FIELDS`, the executions composite FK, and the absence of `status`/`body_hash` today. |
| 2026-08-31 | v1.0 | versioning design session | Initial spec: draft-in-version-table lifecycle (D1/D2), content-hash preconditions (D3), UI-only release (D4), version numbers as global identities + preserved-version import (D5), latest-released-only promotion with two-sided guards (D6/D7/D8). Records the verified import-renumbering defect as D5's rationale. |
