# Versioning: Draft, Release, Promotion

**Status:** v1.5 — 039 lifecycle loose ends: no-op writes (§5.1), working-version authoring reads (§7), authoring as a capability (§5.5A)
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Contract](pipeline-contract.md) (§13 error catalog, §17 persistence), [Templates](templates.md), [Metadata DB](metadata-db.md) (§4.4/§4.5/§4.8/§4.9 — DDL authority), [REST API](rest-api.md), [Pipeline Editor UI](pipeline-editor.md)
**Last updated:** 2026-09-01

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

- A draft **pre-allocates** `max(existing version) + 1` at creation — normally
  `current_version + 1`, but a DISCARDED row keeps its number consumed (below), so
  allocation reads the MAX rather than the pointer. The number is stable across all
  in-place writes to that draft.
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

`name`, `display_name` and `description` exist in two places, and that is deliberate — they
are not two copies of one fact:

- **`pipeline_versions.body_json` is the artifact.** The contract requires those fields
  (§3.1) and environment-portability requires the body to stand alone (§2 principle 1): an
  export bundle that did not describe its own pipeline could not be imported.
- **The `pipelines` row is an INDEX over the artifact.** `UNIQUE (name)` needs a real
  column; the list screens' search filters `PipelineRecord.name/displayName/description`
  directly; every join reads the row without parsing JSONB.

**Ratified 2026-08-31 — the row indexes the CURRENT RELEASED body.** Metadata edits ride
the release: a draft carries its metadata in its own version row, and the `pipelines` row
keeps the released values until lock. This is not a preference between two workable
options. Populating the row from a draft would make it an index over a *mixture* — some
rows describing released content, some describing unreleased edits, with nothing in the
schema marking which — and every consumer of the row (search, list, name lookup) would
silently inherit that ambiguity.

The editor shows draft metadata with a "pending release" affordance; the list shows the
released name until lock.

**Draft-write-time uniqueness check (ratified, and the reason this is safe).** Because
`UNIQUE (name)` lives on the row and the row is not updated until release, a draft rename
to a taken name would otherwise fail only at Release — after the work is done. So a draft
write that changes `name` validates it immediately:

```sql
SELECT 1 FROM pipelines
 WHERE name = :newName AND workspace_id = :workspaceId AND id <> :thisPipelineId;
```

A hit is `pipeline.validation.duplicate_name` (409 — the existing code, no catalogue
addition) at draft-write time. Uniqueness is checked AGAINST the index without being
enforced FROM it, so the early failure costs nothing structurally. The release-time
constraint remains as the backstop: it is the authority, this is the courtesy.

---

## 4. Content Hash (`body_hash`)

### 4.1 Definition

`body_hash` = **SHA-256 (hex) of the canonical body JSON**, stored on every version row at
write time. Canonicalization is **frozen** — the hash is only as good as the canonical
form — and v1.3 pins it mechanically:

- **Pipelines**: the canonical form is the database's JSONB text projection of the body —
  `encode(sha256(convert_to(body_json::text, 'UTF8')), 'hex')`, computed BY THE DATABASE in V6's
  backfill and in every repository write, one expression everywhere. The serializer's
  output remains the write FORMAT, but a JSONB column does not preserve the writer's key
  order, so hashing the serializer string in the application while the backfill hashed the
  stored projection would give two hashes for one body — exactly the "every pre-existing
  row fails its first precondition check" failure §11's backfill option exists to avoid.
  Database computation is also what makes §11's own "pgcrypto digest" backfill option
  sound.
- **Templates**: the canonical form is the version-owned field object
  `{engine, dialect, is_library, imports, body}` projected through
  `jsonb_build_object(…)` (which normalizes key order deterministically) and hashed with
  the same expression. `display_name`/`description` are NOT in it — see §6's asymmetry
  note.

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
       AND <the incoming body's hash expression> <> v.body_hash   -- the no-op guard (below)
       AND NOT EXISTS (SELECT 1 FROM pipeline_versions
                       WHERE pipeline_id = :id AND status = 'DRAFT')
    RETURNING pipeline_id, version
)
SELECT * FROM draft, guard            -- guard empty ⇒ 0 rows ⇒ 409
```

**The no-op guard (v1.5):** a PUT whose body is identical to the released one must not
create a draft, burn a version number, or light the pending-release badge for a change
nobody made. The predicate compares the incoming body's hash **to the released row's
stored hash, in the same statement, by the same canonical-hash expression the INSERT would
store** — never in the application, where a second implementation of the canonical form is
exactly the defect 035 found live. The statement's no-op arm returns the current RELEASED
detail in that case, so the response for a no-op **shows the current state — `status:
RELEASED`, no draft** — rather than making the caller infer it from an absence. It is not
a 4xx: the write was well-formed and the outcome is "already in that state". Consequences
the implementation pins:

- Both the draft arm and the no-op arm join the guard, so a stale precondition still
  yields zero rows ⇒ 409 — D3's no-last-write-wins outranks tidiness.
- The no-op arm requires that no draft exists: identical content written while a draft is
  open is a stale base (409 carrying the draft's state), never a "no draft" answer that
  would contradict the working state.
- **A draft edited back to match its released parent is LEFT ALONE** (written in place,
  never auto-discarded): silently deleting a draft row, its version number and its
  `updated_by` history because someone reverted would be surprising, and discard stays
  explicit.
- Templates mirror all of this (§6), with the documented asymmetry that
  `display_name`/`description` are not part of the hashed artifact — a content-identical
  save that changes only index metadata still moves the metadata, without opening a draft.

Why this matters beyond tidiness: **a draft exists if and only if the content genuinely
differs.** Draft-existence is therefore a truthful signal — which is what §7's authoring
reads rely on and what two people editing one pipeline need.

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

*(v1.3 dropped the sketch's `updated_at = NOW()` from the flip: §11's column note makes
`updated_at` draft-write metadata — a release or discard does not restamp it, so a
released row keeps the timestamp of its last draft write. The two sections now agree.)*

Preconditions, evaluated server-side before the statement runs:

1. The expected hash matches the draft (§4.2) — *you release what you tested*.
2. **Every template version pinned by the draft body is RELEASED** — templates lock first.
   A pin on a DRAFT template version fails with `pipeline.release.template_not_released`
   naming the template and version. (Pinning a DRAFT template version from a DRAFT pipeline
   is legal while iterating — §6 — and only becomes an error at pipeline release time.)
3. Full [pipeline-contract §12](pipeline-contract.md) validation re-runs on the draft body. Release is the final save-time
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

**The template metadata asymmetry (v1.3).** §3.5's metadata-rides-the-release is a
PIPELINE rule, and it works there because the pipeline body *carries* its metadata — the
draft row stages it inside `body_json`. A template's `display_name`/`description` live on
the index row `templates` only and are not part of the versioned artifact, so a template
draft stages what CAN be staged (engine/dialect/is_library/imports/body — exactly the
canonical body of §4.1) and the index metadata keeps moving at save time. Templates have
no rename (`name` is the identity), so §3.5's draft-write-time name check is pipeline-only.
Template discard is likewise simpler: nothing references a `template_versions` row by FK
(pipeline pins are numbers in JSON), so §3.4's executed-draft DISCARDED branch cannot fire
— a template draft is always hard-deleted and its number always returns to the pool.

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
| `POST /api/v1/pipelines` | Unchanged: creates v1 **RELEASED** (§3.2). Response gains `status`/`body_hash`/`current_version`. |
| `PUT /api/v1/pipelines/{id}` | **Semantics change:** always writes the draft branch (§5.1 or §5.2). Never appends a released version. Requires the hash precondition. Response carries the version's `status` and `body_hash`. A body identical to the released one is a **no-op** (§5.1): no draft, no burned number, and the response reports the current RELEASED state with no draft pointer. |
| `POST /api/v1/pipelines/{id}/release` | New. Hash-guarded (§5.3). UI-only in practice; no MCP tool is exposed for it (D4). |
| `POST /api/v1/pipelines/{id}/draft/discard` | New. Hash-guarded (§5.4). `204`, both outcomes transparent. |
| `GET /api/v1/pipelines/{id}` | Read shape gains the version's `status` and `body_hash`, `current_version`, and the `draft` pointer when one exists. **Since 039 the default body is the working version (§7.1): the DRAFT when one exists, else the current released version.** |
| Templates (`/api/v1/templates/...`) | Mirror of all the above (release = §8.9, discard = §8.10 in rest-api.md). |

### 7.1 Authoring reads return the working version (039)

The principle: **a RELEASED version is never modified, and if a draft exists it is reused,
no matter what.** Authoring reads — REST `GET /pipelines/{id}` and
`GET /templates/{id}`, the MCP `pipelines_get` / `templates_get` tools, and the editors —
therefore return the **working version**: the draft if one exists, else `current_version`.
They always state which `version` and which `status` they returned; the caller never
infers it. Before 039 the REST and MCP default was the released body with a draft pointer
beside it — an agent that read released while a draft was open would rebase its edit on
stale content and quietly discard the draft with its next write.

Three pins:

- **`current_version` is NOT repointed at the draft.** It means *latest released*, and
  execute-default, the datasource reverse-scan join and the editor's fallback all read it
  that way — repoint it and executions run unreleased code. The working version is
  DERIVED (draft-exists ? draft : current), never stored; there is no schema behind this
  section.
- **Explicit reads still win.** An explicit `version` argument (or versioned URL) returns
  exactly that version; this changes only the DEFAULT, and only for the authoring
  surfaces. Execution keeps reading `current_version`.
- The no-op rule (§5.1) is what makes this safe to act on: a draft exists **iff** the
  content genuinely differs, so "the draft is the working version" is never a phantom an
  unchanged save left behind.

**Exact spellings (v1.3, fixed in [rest-api.md](rest-api.md)):** the hash precondition
travels as the standard **`If-Match` request header** carrying the `body_hash` — on PUT,
release and discard, for pipelines and templates alike; absent/blank ⇒ `400
pipeline.execution.invalid_parameter_type` with `details.reason = "precondition_missing"`
(a caller that did not participate in the protocol at all, not a conflict). Release and
discard distinguish their refusals: no DRAFT exists ⇒ `pipeline.version.not_draft` /
`template.version.not_draft` (409); a DRAFT exists but the hash is stale ⇒
`*.version.conflict` with the current state in `details`. The executions surfaces carry
§8's marker as a `draft_run: true|false` field on each execution record.

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

- **A draft run is DERIVED, not recorded (ratified 2026-08-31).** For an execution of
  version *N*: it was a draft run when `started_at < released_at`, or when that version has
  no `released_at` (still DRAFT, or DISCARDED). No schema column. The derivation is sound
  because a version's lifecycle is one-way — RELEASED never returns to DRAFT (§3.1: a write
  after release opens a NEW version), and numbers are never reused (§3.4), so each
  `(pipeline_id, version)` has exactly one `released_at` to compare against.

  **The precondition, stated because it is not obvious.** `started_at` is
  **application-supplied**, not a database default: `ExecutionRepository` binds `:startedAt`
  from the record (its INSERT names `started_at` explicitly). So this comparison spans two
  clocks whenever the release and the execution are served by different instances. The
  failure window is sub-second and either side of a release; the consequence is a history
  LABEL, never execution behaviour, never promotion eligibility (§10.3 reads `status`, not
  this derivation). **`released_at` must therefore be set by the database (`NOW()`) at
  release**, so at most one of the two timestamps can drift. If a future requirement makes
  the draft/released distinction load-bearing rather than informational, record it instead
  of deriving it.
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

**Wire spelling (v1.3):** a payload carrying `version` must also carry `body_hash`
(absent ⇒ `hash_mismatch` with `details.reason = "body_hash_missing"`); `released_at` is
honored when present (export emits it) so §8's derivation stays truthful on the target.
The stored `released_by` is the IMPORTING actor — the source's releasing user may not
exist locally (the promotion case is §10.6's service principal, out of this round). The
template import mirrors the table entry-for-entry with `template.version.conflict`; the
template-side hash-mismatch code does not exist in §13 and this round adds no rows, so
the internally-inconsistent entry surfaces as `template.version.conflict` with
`details.reason = "hash_mismatch"` — a catalog gap raised to the operator, not papered
over.

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

### 10.6 The promotion-peer credential — a shared server key (ratified 2026-09-01)

§10.1 said "base URL + API key", which was under-specified: an API key here is workspace-pinned
and user-owned, and neither property is right for one deployment writing to another.

**Ratified shape (operator, 2026-09-01): a pre-shared server key, not a principal.** The
RECEIVER holds a long secret in configuration; the SENDER holds that same secret alongside the
target's base URL and presents it on the promotion call; the receiver validates it before
accepting any payload. No service account, no `users` row for the credential itself, no scope
matrix entry — promotion is a deployment trusting a deployment, and modelling it as a principal
was more machinery than the problem needs.

| Property | Value | Why |
|---|---|---|
| Receiver config | a long secret under the promotion key shown below | The receiver is the one that must be able to refuse. |
| Absent key | **Promotion disabled, endpoint refuses** | Fail closed. A deployment that never configured a key must not silently accept pushes. |
| Sender config | target base URL + the same secret | Held with the sender's other deployment secrets — never in a pipeline body (§2 principle 1 forbids env-specific values there). |
| Transport | A request header, TLS only, compared in constant time, never logged | It is a bearer secret; a timing-safe compare and a redacted log line are the whole discipline. |
| Scope | The promotion/import endpoint ONLY | It is not a master key. No other route consults it, and it grants no read access. |
| Direction | Receiver validates; receiver never calls the sender | A compromised receiver cannot reach back into dev. |
| Rotation | Change both sides; no user account is involved | The property a user-owned key could not give: rotation and revocation touch no human's account, and offboarding cannot break production promotion. |

The configuration shape, named here so the implementing round does not invent a second
spelling. It is **not yet in `configuration.md`** — that doc is the operator-facing authority for
SHIPPED keys, and declaring an unimplemented one there is the same defect the architecture
audit records as M11:

```yaml
# receiver (e.g. uat) — absent means promotion is refused
datapipelines:
  promotion:
    server-key: ${DATAPIPELINES_PROMOTION_SERVER_KEY:}

# sender (e.g. dev) — the target and the same secret
datapipelines:
  promotion:
    target:
      base-url: ${DATAPIPELINES_PROMOTION_TARGET_URL:}
      server-key: ${DATAPIPELINES_PROMOTION_TARGET_KEY:}
```

The implementing round adds both to `configuration.md` in the commit that makes them real.

**The one gap this shape does not close, and it needs a decision before implementation.**
`pipeline_versions.created_by` and `pipeline_executions.triggered_by` are
`NOT NULL REFERENCES users(id)`. A payload authenticated by a server key carries no user, and
the source deployment's user ids are meaningless on the receiver — different `users` table. So
an imported row still needs an actor that exists locally. Three options, none free:

1. **A single reserved non-interactive `users` row** created by migration (no password, no OIDC
   identity, cannot log in), used as `created_by` for every promoted row. Cheapest; the
   receiver's history then attributes all promoted versions to "promotion", with the releasing
   human recoverable from the artifact's own `released_by`.
2. **Make the column nullable** plus a CHECK that exactly one of `created_by` /
   `promoted_from` is set. Cleaner modelling, a migration on a hot table, and every reader of
   `created_by` must handle null.
3. **Map by email** — carry the source's releasing user's email and resolve it locally. Rejected
   here: it silently creates or mis-attributes when the human has no account on the receiver.

**Recommendation: (1)**, and note it is NOT the service principal an earlier draft of this
section proposed — it is a row that exists solely to satisfy a foreign key and to name the actor
in history, with no credential attached to it at all. The machine-auth note's F10 defers to this
section; **F2's service-account question remains open there**, because an application EXECUTING a
pipeline is a different problem from a deployment PROMOTING one, and D16's own headline is that
execution is blocked by the SSE contract before auth is even reached.

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
| `updated_at` | `TIMESTAMPTZ NULL` | Written by DRAFT writes only and never restamped afterwards — a released row keeps its last draft-write timestamp (the immutability note in metadata-db §4.5/§2 is amended accordingly). |

Plus the two one-draft partial unique indexes (§3.3). **No column is added to
`pipeline_executions`** — §8 derives the draft/released distinction from `released_at`,
which is why that column is database-generated (`NOW()` in the release statement, not
`DEFAULT` — it is NULL until the first release of that version) rather than
application-supplied. The migration is `V6__version_lifecycle.sql`; metadata-db §4.5/§4.9
carry the DDL (the authority) and §5A carries the promotion classification registry the
migration test parses.

The immutability KDoc blocks on both repositories are rewritten to the §3.1 discipline.

---

## 12. The Agent-Facing Skill Must Land With the Code

`.agents/skills/datapipelines/SKILL.md` is what an agent reads before it touches this
product. It is not documentation about the system — it is the system's instructions to its
own callers, and MCP agents are the primary authoring surface here (contract §2; the
editor is a viewer/executor). **A lifecycle change that reaches the API without reaching the
skill leaves every agent operating on the old contract.**

This is not hypothetical for this spec. `SKILL.md:51` currently states:

> **Versioning** — every save creates a new version.

D2 makes that **false**. After this round a save on a released version creates a DRAFT, and
a save on a draft overwrites it in place. An agent holding the current skill will believe
its `pipelines_update` published something, and it did not.

**The rule: the skill is updated in the SAME commit as the behaviour it describes** — the
same discipline §13 applies to catalogue rows and their constants, for the same reason. A
skill that lags is worse than one that is silent, because agents act on it with confidence.

**Audited 2026-08-31 at `7f87118`: the skill is otherwise accurate, so `:51` is the only
lie this spec introduces.** All 18 `McpToolCatalog.NAMES` tools are named in it, the "18 MCP
tools" count matches, and it already discloses the REST/MCP execute asymmetry the
architecture audit records as D6 (`SKILL.md:146` — "REST execute accepts `Idempotency-Key`;
the MCP tool has no key"). **Do not update the skill for this spec before the behaviour
ships** — a skill describing an unimplemented lifecycle is the same defect in the opposite
direction, and the architecture audit's M11 exists because three such claims are live in
`deployment.md` today.

### 12.1 What this spec obliges the implementor to change

| Skill content | Why it changes |
|---|---|
| §"Core concepts" → Versioning (`:51`) | "Every save creates a new version" becomes the draft/release rule: create lands v1 RELEASED and immediately executable; every later save is draft-first (§3.2). |
| §"The golden path" | The authoring loop gains its real shape: create → iterate on the draft → **stop**. The agent does not release (D4). Say so positively — "leave the draft for a human to release" — not as an omission. |
| §"Execution semantics agents must know" | Drafts are executable, and a draft run is not a release (§8). An agent testing its own draft is the expected loop, not a workaround. |
| §"Error handling" | The new 409s an agent will actually meet: `pipeline.version.conflict` (stale content hash — re-read and rebase, never retry blindly), `pipeline.version.not_draft`, and `pipeline.validation.duplicate_name` now arriving at draft-write time (§3.5). |
| §"Best practices" | The hash precondition is a protocol, not a nuisance: read, edit, write with the hash you read. A blind retry after a 409 is how an agent silently overwrites a human's edit. |
| §"References" | Add `docs/versioning.md`. |

### 12.2 What the implementor must decide, not assume

The skill says agents author and the UI releases. **Whether `pipelines_update` returning a
draft is surfaced as a distinct tool result, or as the same result shape with a `status`
field, changes what the agent can reason about** — and the MCP tool surface is
`mcp-server.md`'s authority, guarded by `McpToolSurfaceSpecDriftTest` and the
`McpToolCatalog` binding (033). If the round adds or reshapes a tool, that spec, the
catalogue and the skill move together; if it only changes a payload, say so explicitly in
the handback so the drift guards are known to have been considered rather than missed.

**No new MCP tool for release** — D4 and §14 both settle that. The absence is deliberate and
the skill should state it, so a later reader does not read the gap as an oversight and
"fix" it.

### 12.3 The §12.2 decision, made and recorded (v1.3 / 035)

The draft-returning `pipelines_update` is a **payload change, not a surface change**: all
18 tool names stand, no tool is added or removed, and `mcp-server.md` §6.1 is untouched.
Concretely: `pipelines_update` gains the REQUIRED `expected_hash` argument (same input as
`pipelines_create` plus `id` and `expected_hash`), and both create and update results gain
`status`, `body_hash`, `current_version` and — when a draft exists — a `draft` pointer;
`pipelines_get` merges the same fields into the returned body. §6.2.5's prose and §6.2.2's
result note in `mcp-server.md` were amended to say exactly this (the tool is documented in
prose, so `McpToolSurfaceSpecDriftTest`'s schema comparison is unaffected by
construction). Making `expected_hash` required is deliberate: D3 says no last-write-wins
EVER, and an optional precondition is a protocol hole an agent will fall through — the
refusal for omitting it is the input schema itself.

### 12.4 The general rule, beyond this spec

Any round that changes what an agent can do, must do, or will be refused for, updates
`SKILL.md` in the same commit. The test is not "did the API change" but **"would an agent
holding the current skill now be wrong?"** If yes, the skill is part of the change set. The
skill has no drift guard today — nothing fails when it goes stale — so this rule is carried
by review, and a round that touched agent-visible behaviour without touching the skill
should be asked why in its handback.

---

## 13. Testing Requirements

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

## 14. Out of Scope (Deliberate)

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

## 15. Ratified Decisions and Remaining Open Items

Items 1–3 were **ratified by the operator on 2026-08-31** and are written into their
sections; they are recorded here so a reader sees what was decided and why, without
re-opening it.

| # | Decision | Where | Reasoning that settled it |
|---|---|---|---|
| 1 | Metadata rides the release, plus a draft-write-time name-uniqueness check | §3.5 | The `pipelines` row is an INDEX over the current released body, not a second copy of the metadata — so indexing a draft would make it an index over a mixture. The early check removes the only real cost (a duplicate-name rename failing at Release rather than at the write). |
| 2 | The promotion-peer credential is specified here, as a non-interactive service principal | §10.6 | §10.1's "API key" is workspace-pinned and user-owned; neither is right for one deployment writing to another. This doc is the credential's authority; the machine-auth note's F10 defers here. |
| 3 | Draft runs are derived from `released_at`, not recorded in a column | §8 | The lifecycle is one-way and numbers are never reused, so the comparison is well-defined. Its precondition — `started_at` is application-supplied, so the comparison spans two clocks — is stated in §8, along with the requirement that `released_at` be database-generated. |

**Remaining open, deliberately:**

1. **The receiver inventory API shape.** §10.2 needs the target's per-pipeline
   `(id, current_version, body_hash)`. `rest-api.md` owns the endpoint's shape at
   implementation; this doc owns only what promotion needs from it.
2. **Editor UX for the release action** — button placement, the "draft pending release"
   badge, the conflict-reload flow. `pipeline-editor.md`'s next revision, not this doc's.
3. **Whether the §10.6 service principal later serves the application-execution case** the
   machine-auth note describes. That note settles it once ratified; §10.6 constrains only
   promotion.

---

## Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-01 | v1.5 | 039 lifecycle loose ends | §5.1: the **no-op guard** — a draft-create whose body is identical to the released one is suppressed, compared hash-to-hash in the same statement by the canonical-hash expression itself; the no-op answer returns the current RELEASED state (not a 4xx, no draft pointer), both arms join the guard so a stale hash still 409s, a no-op never answers "no draft" while a draft exists, and a draft edited back to its released parent is left alone (never auto-discarded). Templates mirror it, with index metadata still moving on a content-identical save. Draft-existence becomes a truthful "content genuinely differs" signal. §7.1: authoring reads (REST GET, `pipelines_get`/`templates_get`, the editors) return the **working version** — the draft if one exists, else `current_version` — and state which version/status they returned; explicit `version` still wins, `current_version` keeps meaning latest-released (working version is derived, no schema change). |
| 2026-09-01 | v1.4 | 035 implementation | The implementer's amendments, landing with the code. §4.1: canonicalization pinned mechanically — the hash is computed BY THE DATABASE (`encode(sha256(convert_to(<jsonb>::text, 'UTF8')), 'hex')`) over the JSONB projection (pipelines) / the `jsonb_build_object` field object (templates), one expression shared by V6's backfill and every write; the serializer string is the write format, not the hash anchor. §3.4: draft allocation is `max(existing)+1` — a DISCARDED number is consumed, so the pointer alone would collide. §5.3: the sketch's `updated_at = NOW()` on the flip is dropped; §11's column note governs (draft-write metadata, never restamped). §6: template metadata asymmetry recorded (display_name/description are index-row, save-time; template discard always hard-deletes — no FK can block it). §7: exact REST spellings — `If-Match` header on PUT/release/discard, `pipeline.version.not_draft` vs `*.version.conflict` distinction, `draft_run` field name; §9.2: wire spelling (`body_hash` required with `version`, `released_at` honored, `released_by` = importing actor, template hash-mismatch gap surfaced as `template.version.conflict` + reason, raised not papered); §12.3 records the §12.2 decision (payload-only MCP change; `expected_hash` required on `pipelines_update`; mcp-server.md §6.2.2/§6.2.5 prose amended). |
| 2026-09-01 | v1.3 | operator ratification | §10.6 replaced: the promotion credential is a **pre-shared server key**, not a principal. The receiver holds a promotion server key and refuses when it is absent (fail closed); the sender holds the same secret with the target URL and presents it on the promotion call. No service account, no scope-matrix entry, no `users` row for the credential — promotion is a deployment trusting a deployment, and the earlier service-principal draft was more machinery than the problem needs. Records the one gap the shape does not close: `created_by`/`triggered_by` are NOT NULL FKs to `users`, so an imported row still needs a local actor; three options given, a single reserved non-interactive row recommended, awaiting ratification. F10 defers here; F2's service-account question stays with the machine-auth note, because an application EXECUTING a pipeline is a different problem from a deployment PROMOTING one. |
| 2026-08-31 | v1.2 | operator request | New §12: the agent-facing skill (`.agents/skills/datapipelines/SKILL.md`) is updated in the SAME commit as the behaviour it describes. Concrete for this spec — `SKILL.md:51` says "every save creates a new version", which D2 makes false, so an agent holding the current skill would believe a `pipelines_update` published something it left as a draft. Enumerates what this round obliges (versioning concept, golden path stopping short of release, draft execution, the new 409s, the hash protocol, the references list), what the implementor must DECIDE rather than assume (whether the draft result reshapes the MCP tool surface, which `mcp-server.md` and `McpToolCatalog` own), and the general rule: the test is "would an agent holding the current skill now be wrong?" Notes that the skill has no drift guard, so the rule is carried by review. Sections 12–14 renumbered to 13–15. |
| 2026-08-31 | v1.1 | operator ratification | §15's first three open items decided and written into their sections. §3.5 rewritten: the `pipelines` row is an INDEX over the current released body — the metadata is not duplicated, one side is the artifact and the other is how you find it — so metadata rides the release by definition rather than by preference, plus a draft-write-time uniqueness check reusing `pipeline.validation.duplicate_name` (no catalogue addition). New §10.6 specifies the promotion-peer credential as a non-interactive service principal, scoped to the import endpoint, backed by a real `users` row because `created_by`/`triggered_by` are NOT NULL FKs; this doc is its authority and the machine-auth note's F10 defers here. §8 drops the `ran_draft` column for derivation from `released_at`, with its cross-clock precondition stated (`started_at` is application-supplied) and `released_at` required to be database-generated. |
| 2026-08-31 | v1.0 | orchestrator review | Review pass before commit. Corrected §3.4: the executions FK is `NO ACTION` (its declaration carries no `ON DELETE` clause), not `RESTRICT` — it blocks identically here, but an implementer reading the old wording would have written the wrong DDL. §13 (Testing Requirements) now states the operational half of the drift coupling: catalogue rows and constants land in the SAME commit, because the drift test lives on `main` permanently. Also grouped the doc into `DocsCatalog` "Contracts" — 033's in-app docs index fails at init on an ungrouped doc, so the spec could not land without it. Verified against the tree: the import-renumbering defect (§9.1), `PipelineImportService.SERVER_FIELDS`, the executions composite FK, and the absence of `status`/`body_hash` today. |
| 2026-08-31 | v1.0 | versioning design session | Initial spec: draft-in-version-table lifecycle (D1/D2), content-hash preconditions (D3), UI-only release (D4), version numbers as global identities + preserved-version import (D5), latest-released-only promotion with two-sided guards (D6/D7/D8). Records the verified import-renumbering defect as D5's rationale. |
