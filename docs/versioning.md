# Versioning: Draft, Release, Promotion

**Status:** v1.7 — 101 version lifecycle: purge/discard/restore, the sticky `current_version` (D57–D60), §3.5 the lifecycle table
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Contract](pipeline-contract.md) (§13 error catalog, §17 persistence), [Templates](templates.md), [Metadata DB](metadata-db.md) (§4.4/§4.5/§4.8/§4.9 — DDL authority), [REST API](rest-api.md), [Pipeline Editor UI](pipeline-editor.md)
**Last updated:** 2026-09-08

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

Ratified 2026-09-08, operator ruling (implemented 099):

| # | Decision | Rationale |
|---|---|---|
| D55 | **Creation lands version 1 DRAFT**, for pipelines and templates alike, and `current_version` is NULL until a human releases. D4 holds without exception; only the non-authoring create paths (promotion import, the seeders that ride it) land RELEASED. | "Contract says, pipeline will always be DRAFT and from DRAFT to RELEASED is a human step." §3.2's old RELEASED-on-create rule was an unratified assumption whose only justification — executability — has been false since 039 made drafts executable. |
| D56 | **Execute with no version runs the LAST version — the working version:** the draft when one exists, else the latest release. On a development server that may be a draft; on a hardened (non-authoring) server no draft can exist, so it is always a release by construction. | "We always run the LAST version of the pipeline if no version is specified. On Dev, last version can be RELEASED or even DRAFT. On servers which are not development, we will always have RELEASED pipelines. No DRAFT at all." |

Ratified 2026-09-08, operator ruling (implemented 101):

| # | Decision | Rationale |
|---|---|---|
| D57 | **A pipeline always has ≥ 1 version row** (unless the entity itself is purged). Discard never removes rows — discarding the sole release is legal and lands the entity at DISCARDED with a NULL pointer. The refusal `pipeline.version.last_release` belongs to the PURGE path only: a RELEASED version is never purged, and an entity holding any non-draft version is never purged. | "Delete means it's gone … discard is per version, the entity stays." |
| D58 | **A PIPELINE node may pin only a RELEASED child version** — `pipeline.validation.pipeline_reference_not_released` at save. Template pins keep their rule (draft pins legal while the parent is a draft; release requires them released). | Composition references reviewed content; a draft child can be purged out from under a parent, which an exact pin must never allow. |
| D59 | **Names are unique forever.** A discarded pipeline/template keeps its name; restore always works; no second entity may take the name. The partial-index idea is withdrawn; `uq_pipelines_workspace_name` / `uq_templates_workspace_name` stay as they are. | A name is an identity other environments and histories reference; recycling it re-points them at a different thing. |
| D60 | **`current_version` is sticky and event-driven.** It moves only on release, discard-of-current, restore-above-current, manual switch, and purge-of-current-draft; when it moves it picks the highest-numbered ELIGIBLE live version (RELEASED always; DRAFT only under development posture), else NULL. An import never moves an existing pointer — the human switches — except the first import of an entity with no current at all. | "`current_version` helps us in fallback." The pointer is what dependents run; it must move deliberately, never as a side effect. |
| D63 | **A published endpoint serves whatever the pointer names in development, a draft included** (owner 2026-09-09: "we have to test it before the release, so we should be able to point the API to any version in dev"). Non-development holds no drafts, so released-only there by construction. | The endpoint is the thing under test; a 503 on the dev fallback made the testable path untestable. |

---

## 3. Version Lifecycle

*Rewritten 2026-09-08 (101, rulings D57–D60). This section replaces the former §3.1–§3.4
in full: the tombstone rule (an executed draft flipping to DISCARDED) and every sentence
about deletion are withdrawn. Where prose elsewhere still repeats the old rule, §3.5's
table is the ruling.*

### 3.1 Statuses and verbs

**Version statuses: `DRAFT → RELEASED → DISCARDED`.**

| Status | Meaning | Mutability |
|---|---|---|
| `DRAFT` | The working copy. At most one per entity at any time (§3.3). | Mutable in place (§5.2). |
| `RELEASED` | The locked version. | Immutable — the two status flips below touch status, the discard stamps and the pointer, nothing else. |
| `DISCARDED` | A RELEASED version that was retired. Its row stays: executions, promotion history and parents reference it. | Immutable. |

Five verbs act on versions, and they pair with the statuses exactly:

- **Purge** is the DRAFT verb (`DELETE /pipelines/{id}/versions/{v}` — drafts only). The
  row is **hard-deleted together with its executions**: development runs of a thing that
  never shipped are not history. Allocation always reads `max(version) + 1`, so a purged
  draft's number is free again exactly when nothing surviving outranks it (its executions
  went with it, so no dangling reference can exist). **Purge is not reversible.**
- **Discard** is the RELEASED verb (`POST …/versions/{v}/discard`). The row flips to
  DISCARDED (`discarded_at`, `discarded_by`); no dependent may run it, and the row stays.
- **Restore** is the DISCARDED verb (`POST …/versions/{v}/restore`): the row returns to
  RELEASED — its original `released_at`/`released_by` return, the discard stamps clear.
  **Discard is reversible; purge is not.**
- **Release** is the DRAFT→RELEASED verb (§5.3, unchanged).
- **Switch** is the pointer verb (§3.4).

A DRAFT is never discarded and a RELEASED version is never purged: the verbs refuse with
`pipeline.version.not_released` / `template.version.not_released` and
`pipeline.version.last_release` / `template.version.last_release` (§13.13/§13.9) rather
than guessing intent.

**Which posture may run which verb.** Discard, restore, purge and the entity purge are
**authoring writes** — a promotion receiver (`authoring-enabled=false`) refuses them with
`pipeline.authoring.disabled` / `template.authoring.disabled`, exactly like create,
update, release and the draft verbs (§5.5). Two verbs are deliberately NOT authoring:
**switch** (it is the promotion receiver's verb — pointing a receiver at the release it
just imported, or back at the one before it, is the human's rollout and rollback lever)
and **import** (unchanged since 039).

### 3.2 Entity status is derived; names are unique forever

An entity (`pipelines` / `templates` row) has **no stored lifecycle status**. It is
derived, never stored:

- **ACTIVE** while any of its versions is DRAFT or RELEASED;
- **DISCARDED** when every one of its versions is DISCARDED.

There is no "discard the entity" verb: discarding its last live release IS the entity
discard — the derivation lands there on its own. **Purging the entity** is allowed only
when its only version is a DRAFT; the entity row goes with it, in the same transaction,
together with the draft's executions. An entity therefore always has ≥ 1 version row
unless the entity itself is gone (D57).

**Names are unique forever (D59).** A discarded pipeline or template keeps its name;
restore always works; no second entity may ever take the name. The V1 `is_deleted`
soft-delete column on both tables is **retired (V19)**: any `is_deleted = true` row is
migrated to "every version DISCARDED" and the column dropped; every reader of
`is_deleted` reads the derived entity status instead. `pipeline.validation.pipeline_reference_deleted`
becomes the derived "the referenced entity is DISCARDED" refusal — it blocks NEW
references at save time; existing pinned references keep resolving, exactly as before.

### 3.3 One draft per entity; number allocation

A partial unique index enforces at most one DRAFT row per pipeline (and per template):

```sql
CREATE UNIQUE INDEX uq_pipeline_versions_one_draft
    ON pipeline_versions (pipeline_id) WHERE status = 'DRAFT';
```

This is what makes the write rule race-safe: two simultaneous first-writers both see
"released", both attempt the draft insert — one wins; the loser's insert violates the index
and surfaces as `pipeline.version.conflict` pointing at the new draft's hash (§4). The loser
must re-read and rebase.

**Number allocation.** A draft **pre-allocates** `max(existing version) + 1` — never
`current_version + 1`, because the pointer can be NULL (D55) and a DISCARDED row keeps its
number consumed. The number is stable across all in-place writes to that draft. Numbers
held by surviving rows are never reused; a purged draft's number is re-derived from the
MAX over what survives (§3.1) — safe precisely because purge deletes the executions with
the row.

**The draft is allocated the highest number — and an import may land above it.** Allocation
always reads the MAX (§3.3), so a draft is created above every existing version; the one
event that can place a version above a draft afterwards is an IMPORT onto an entity that
holds one (§3.5's `{D}` first-import row lands `1D 2R cur=2`): the imported release numbers
past the draft, and the pointer names it. The true invariants are the allocation rule and
"at most one draft" — the model test asserts both after every event, and NOT a
draft-dominance that the table itself overrides.

### 3.4 `current_version` is sticky and event-driven (D60)

`current_version` is the version every **pointer-following dependent** runs — published
endpoints, promotion, schedules (092), dashboards later. It is **sticky**: it no longer
means "the latest RELEASED version" as a derived fact. It moves ONLY on these events, and
when it moves it picks the **highest-numbered ELIGIBLE live version**:

| Event | Pointer rule |
|---|---|
| release(v) | current = v |
| discard(x) | x ≠ current: unchanged. x = current: highest eligible live version, else NULL |
| restore(x) | current = x only if x > current or current is NULL; otherwise unchanged |
| switch(v) — manual | current = v; v must be live and eligible. The promotion receiver's verb (§3.1) |
| purge(draft v) | as discard: only moves if v = current (a development fallback), then highest eligible live version, else NULL |
| import | **never moves an existing pointer** — the human switches. Exception: the FIRST import of an entity that has no current at all sets it — nothing exists to break |

**Eligible** = RELEASED, plus DRAFT under `posture=development` (`authoring-enabled=true`).
Under any other posture only RELEASED is eligible — there are no drafts there by
construction (§5.5), but the rule is stated, not assumed. A draft's mere EXISTENCE never
moves the pointer; a draft is chosen only when the pointer must move and the draft is the
highest live version (the discard/purge fallback above).

**Dependents read the pointer; authors read "last" (D56, unchanged).** Manual execute
with no version is NOT a pointer read: it runs the working version — the draft when one
exists, else the latest release — resolved in one place (`PipelineService.workingVersion`).
The published surface serves the pointer when it names a RELEASED version, which under
hardened postures is the only thing it can name.

**NULL pointer.** A published endpoint answers `endpoint.pipeline_not_released` at serve
time — the endpoint row is not deleted, and an entity whose status is DISCARDED answers
the same refusal rather than a 404; a schedule (092) records a refused run; promotion has
nothing to push.

**A draft pointer (D63, 2026-09-09).** Under the development posture the pointer may name a
DRAFT (the D60 fallback, or a manual switch), and a published endpoint **serves it** — the
endpoint has to be testable before the release, so in development the API can be pointed at
any live version. Outside development no draft exists, so a release is served by
construction; the serve path checks the same eligibility rule the pointer uses
(`PipelineVersionStatus.eligibleForPointer`), never a status of its own.

### 3.5 The lifecycle table

**The tree.** Pipelines and templates are two folder trees; every leaf has an ordered
version list. Dependencies are **edges**: exact-version pins (a pipeline node's template
pin, a PIPELINE node's child pin) and pointer edges (endpoint→current, schedule→current).
Three graph rules cover every refusal:

1. A version with an **inbound exact pin from a LIVE (non-discarded) version** cannot be
   discarded or purged — the refusal names the pinning entities. Codes:
   `pipeline.version.pinned` for child pipelines; for templates the existing
   `template.in_use` IS the in-use refusal — no `template.version.pinned` is added.
2. A pointer edge follows the pointer and refuses on NULL (§3.4).
3. An entity can be purged only when it has **no inbound edges of any kind** and its only
   version is a DRAFT. For pipelines this reduces to the draft-only check — a
   never-released pipeline cannot be pinned (D58) and cannot be published — and to the
   template side it is the `template.in_use` pin check, which is reachable.

**Composition (D58).** A PIPELINE node may pin only a RELEASED child version —
`pipeline.validation.pipeline_reference_not_released` at save. Template pins keep their
rule: a DRAFT pipeline may pin a DRAFT or RELEASED template version while iterating, and
release requires every pinned template version RELEASED (§5.3). Pinning a DISCARDED
template version is refused at save (`pipeline.validation.template_version_not_found`) —
which is what makes "no live version pins a DISCARDED version" an invariant rather than a
hope.

**The sole version (D57).** Discarding the only RELEASED version is legal — the entity
becomes DISCARDED and the pointer goes NULL; restore brings it back. The refusal
`pipeline.version.last_release` / `template.version.last_release` belongs to the PURGE
path only: *"discard is per version, the entity stays; restore or release something
first."*

**Purging a draft pipeline offers its exclusive draft templates.** The purge call accepts
`include_exclusive_draft_templates: true`: the service computes the set of DRAFT template
versions the draft body pins that no OTHER live pipeline version pins, and either purges
them with the pipeline (true) or merely returns them in the response (false, the default)
— either way the caller sees the set, so the UI (102) can offer the cleanup.

#### 3.5.1 Notation and blanket rules

Rows below use: `1R 2X 3D cur=1` — version `number` with status letter (R released,
X discarded, D draft), `cur` the pointer (`∅` = NULL). The **Shape** column groups rows
into the ten families `{D} {R} {R,D} {R,R} {R,R,D} {R,X} {X,D} {X,X} {X,X,D} {R,X,D}`;
within a family the order of statuses matters for pointer outcomes, so every row states
its full `Before` map. Codes shown are the pipeline spelling; the template twins
substitute `template.version.last_release` / `not_released` / `not_discarded` /
`not_eligible` and `template.in_use` (for `pinned`).

- **Posture blanket rule.** Discard, restore, purge and purge(entity) are authoring
  writes: under **hardened** posture every such row — whatever its shape — is refused
  `pipeline.authoring.disabled` / `template.authoring.disabled` before any other check.
  The rows below give the **development** outcome for those verbs, and carry a posture
  cell only where a non-authoring verb (switch, import) differs by posture:
  `dev` = development; `hard` = hardened; `both` = identical under either.
  Draft-holding shapes cannot arise under hardened (§5.5); their rows state the rule.
- **Missing versions.** An event naming a version the entity does not hold is
  `404 pipeline.execution.not_found` (`template.not_found` for templates) — uniformly,
  not per-row.
- **The pointer-followers.** After every allowed row, `cur` names a live eligible version
  or is NULL; dependents behave per §3.4. This is the invariant the model test asserts
  after every event — the table never restates it per row.

#### 3.5.2 The table

| Shape | Before | Event | Posture | Inbound edge | Outcome | After | Entity |
|---|---|---|---|---|---|---|---|
| {D} | `1D cur=∅` | release | dev | — | allowed | `1R cur=1` | ACTIVE |
| {D} | `1D cur=∅` | purge(v1) | dev | — | allowed — row + executions deleted; sole version ⇒ entity purge | entity gone | gone |
| {D} | `1D cur=∅` | purge(entity) | dev | — | allowed — same outcome as purge(v1) | entity gone | gone |
| {D} | `1D cur=∅` | purge(entity) | dev | draft template pinned by a live pipeline version (template twin) | refused `template.in_use` | `1D cur=∅` | ACTIVE |
| {D} | `1D cur=∅` | discard(v1) | dev | — | refused `pipeline.version.not_released` (drafts are purged, never discarded) | `1D cur=∅` | ACTIVE |
| {D} | `1D cur=∅` | switch(v1) | dev | — | allowed — a draft is eligible in development | `1D cur=1` | ACTIVE |
| {D} | `1D cur=∅` | switch(v1) | hard | — | refused `pipeline.version.not_eligible` | `1D cur=∅` | ACTIVE |
| {D} | `1D cur=∅` | import (first — no current) | both | — | allowed — lands RELEASED at `max+1` (version-less path) or the payload's exact number, and sets the pointer | `1D 2R cur=2` | ACTIVE |
| {R} | `1R cur=1` | release | dev | — | refused `pipeline.version.not_draft` | `1R cur=1` | ACTIVE |
| {R} | `1R cur=1` | discard(v1) | dev | — | allowed — no eligible live remains ⇒ pointer NULL | `1X cur=∅` | DISCARDED |
| {R} | `1R cur=1` | discard(v1) | dev | live parent version pins v1 | refused `pipeline.version.pinned` | `1R cur=1` | ACTIVE |
| {R} | `1R cur=1` | purge(v1) | dev | — | refused `pipeline.version.last_release` (a release is never purged) | `1R cur=1` | ACTIVE |
| {R} | `1R cur=1` | purge(entity) | dev | — | refused `pipeline.version.last_release` | `1R cur=1` | ACTIVE |
| {R} | `1R cur=1` | restore(v1) | dev | — | refused `pipeline.version.not_discarded` | `1R cur=1` | ACTIVE |
| {R} | `1R cur=1` | switch(v1) | both | — | allowed (no-op: v is live and eligible) | `1R cur=1` | ACTIVE |
| {R} | `1R cur=1` | import (subsequent) | both | — | allowed — lands RELEASED; the pointer does NOT move (D60) | `1R 2R cur=1` | ACTIVE |
| {R,D} | `1R 2D cur=1` | release | dev | — | allowed | `1R 2R cur=2` | ACTIVE |
| {R,D} | `1R 2D cur=1` | release | dev | draft pins a DRAFT template version | refused `pipeline.release.template_not_released` (§5.3's rule, restated) | `1R 2D cur=1` | ACTIVE |
| {R,D} | `1R 2D cur=1` | purge(v2) | dev | — | allowed — draft + its executions deleted | `1R cur=1` | ACTIVE |
| {R,D} | `1R 2D cur=1` | purge(v2) | dev | draft template v2 pinned by another live pipeline version (template twin) | refused `template.in_use` | `1R 2D cur=1` | ACTIVE |
| {R,D} | `1R 2D cur=1` | discard(v1) | dev | — | allowed — fallback: the draft is the highest eligible live version | `1X 2D cur=2` | ACTIVE |
| {R,D} | `1R 2D cur=1` | switch(v2) | dev | — | allowed | `1R 2D cur=2` | ACTIVE |
| {R,D} | `1R 2D cur=1` | switch(v2) | hard | — | refused `pipeline.version.not_eligible` | `1R 2D cur=1` | ACTIVE |
| {R,D} | `1R 2D cur=1` | import (subsequent) | both | — | allowed — v₃ RELEASED; pointer stays | `1R 2D 3R cur=1` | ACTIVE |
| {R,R} | `1R 2R cur=2` | release | dev | — | refused `pipeline.version.not_draft` | `1R 2R cur=2` | ACTIVE |
| {R,R} | `1R 2R cur=2` | discard(v2 = current) | dev | — | allowed — fallback to v1 | `1R 2X cur=1` | ACTIVE |
| {R,R} | `1R 2R cur=2` | discard(v1 ≠ current) | dev | — | allowed — pointer untouched | `1X 2R cur=2` | ACTIVE |
| {R,R} | `1R 2R cur=2` | discard(v2) | dev | live parent version pins v2 | refused `pipeline.version.pinned` | `1R 2R cur=2` | ACTIVE |
| {R,R} | `1R 2R cur=2` | purge(v1) / purge(v2) | dev | — | refused `pipeline.version.last_release` | `1R 2R cur=2` | ACTIVE |
| {R,R} | `1R 2R cur=2` | purge(entity) | dev | — | refused `pipeline.version.last_release` | `1R 2R cur=2` | ACTIVE |
| {R,R} | `1R 2R cur=2` | switch(v1) | both | — | allowed — switching DOWN to an older release is legal (the receiver's rollback lever) | `1R 2R cur=1` | ACTIVE |
| {R,R} | `1R 2R cur=2` | import (subsequent) | both | — | allowed — v₃ RELEASED; pointer stays; the human switches | `1R 2R 3R cur=2` | ACTIVE |
| {R,R,D} | `1R 2R 3D cur=2` | release | dev | — | allowed | `1R 2R 3R cur=3` | ACTIVE |
| {R,R,D} | `1R 2R 3D cur=2` | discard(v2 = current) | dev | — | allowed — fallback catches the draft | `1R 2X 3D cur=3` | ACTIVE |
| {R,R,D} | `1R 2R 3D cur=2` | discard(v1) | dev | — | allowed — pointer untouched | `1X 2R 3D cur=2` | ACTIVE |
| {R,R,D} | `1R 2R 3D cur=2` | purge(v3) | dev | — | allowed | `1R 2R cur=2` | ACTIVE |
| {R,R,D} | `1R 2R 3D cur=2` | switch(v3) | dev | — | allowed | `1R 2R 3D cur=3` | ACTIVE |
| {R,R,D} | `1R 2R 3D cur=2` | switch(v3) | hard | — | refused `pipeline.version.not_eligible` | `1R 2R 3D cur=2` | ACTIVE |
| {R,X} | `1R 2X cur=1` | restore(v2) | dev | — | allowed — v2 > current ⇒ pointer moves | `1R 2R cur=2` | ACTIVE |
| {R,X} | `1R 2X cur=1` | discard(v1 = current) | dev | — | allowed — no eligible live remains ⇒ NULL | `1X 2X cur=∅` | DISCARDED |
| {R,X} | `1R 2X cur=1` | switch(v2) | both | — | refused `pipeline.version.not_eligible` (discarded is not live) | `1R 2X cur=1` | ACTIVE |
| {R,X} | `1R 2X cur=1` | import (subsequent) | both | — | allowed — v₃ RELEASED; pointer stays | `1R 2X 3R cur=1` | ACTIVE |
| {R,X} | `1R 2X cur=1` | purge(v1) / purge(entity) | dev | — | refused `pipeline.version.last_release` | `1R 2X cur=1` | ACTIVE |
| {R,X} | `1X 2R cur=2` | restore(v1) | dev | — | allowed — v1 < current ⇒ pointer untouched | `1R 2R cur=2` | ACTIVE |
| {R,X} | `1X 2R cur=2` | discard(v2 = current) | dev | — | allowed — no eligible live remains ⇒ NULL | `1X 2X cur=∅` | DISCARDED |
| {X,D} | `1X 2D cur=2` | release | dev | — | allowed | `1X 2R cur=2` | ACTIVE |
| {X,D} | `1X 2D cur=2` | purge(v2 = current) | dev | — | allowed — purge-of-current falls back; nothing eligible remains ⇒ NULL | `1X cur=∅` | DISCARDED |
| {X,D} | `1X 2D cur=2` | restore(v1) | dev | — | allowed — v1 < current ⇒ pointer untouched | `1R 2D cur=2` | ACTIVE |
| {X,D} | `1X 2D cur=2` | purge(entity) | dev | — | refused `pipeline.version.last_release` (holds a non-draft row) | `1X 2D cur=2` | ACTIVE |
| {X,D} | `1X 2D cur=2` | switch(v1) | dev | — | refused `pipeline.version.not_eligible` (switch(v2) is the allowed no-op) | `1X 2D cur=2` | ACTIVE |
| {X,X} | `1X 2X cur=∅` | restore(v2) | dev | — | allowed — current NULL ⇒ pointer set | `1X 2R cur=2` | ACTIVE |
| {X,X} | `1X 2X cur=∅` | restore(v1) | dev | — | allowed — current NULL ⇒ pointer set | `1R 2X cur=1` | ACTIVE |
| {X,X} | `1X 2R cur=2` (after restore(v2)) | restore(v1) | dev | — | allowed — v1 < current ⇒ pointer untouched | `1R 2R cur=2` | ACTIVE |
| {X,X} | `1X 2X cur=∅` | purge(entity) | dev | — | refused `pipeline.version.last_release` | `1X 2X cur=∅` | DISCARDED |
| {X,X} | `1X 2X cur=∅` | release / switch | dev | — | refused `pipeline.version.not_draft` / `pipeline.version.not_eligible` — nothing live (hardened: release is the blanket authoring refusal) | `1X 2X cur=∅` | DISCARDED |
| {X,X} | `1X 2X cur=∅` | import (first — no current) | both | — | allowed — v₃ RELEASED; pointer set; the entity is ACTIVE again | `1X 2X 3R cur=3` | ACTIVE |
| {X,X,D} | `1X 2X 3D cur=3` | release | dev | — | allowed | `1X 2X 3R cur=3` | ACTIVE |
| {X,X,D} | `1X 2X 3D cur=3` | purge(v3 = current) | dev | — | allowed — fallback; nothing eligible remains ⇒ NULL | `1X 2X cur=∅` | DISCARDED |
| {X,X,D} | `1X 2X 3D cur=3` | restore(v2) | dev | — | allowed — v2 < current ⇒ pointer untouched | `1X 2R 3D cur=3` | ACTIVE |
| {X,X,D} | `1X 2X 3D cur=3` | purge(entity) | dev | — | refused `pipeline.version.last_release` | `1X 2X 3D cur=3` | ACTIVE |
| {R,X,D} | `1R 2X 3D cur=1` | release | dev | — | allowed | `1R 2X 3R cur=3` | ACTIVE |
| {R,X,D} | `1R 2X 3D cur=1` | discard(v1 = current) | dev | — | allowed — fallback catches the draft | `1X 2X 3D cur=3` | ACTIVE |
| {R,X,D} | `1R 2X 3D cur=1` | purge(v3) | dev | — | allowed — v3 ≠ current ⇒ pointer untouched | `1R 2X cur=1` | ACTIVE |
| {R,X,D} | `1R 2X 3D cur=1` | restore(v2) | dev | — | allowed — v2 > current ⇒ pointer moves | `1R 2R 3D cur=2` | ACTIVE |
| {R,X,D} | `1R 2X 3D cur=3` | purge(v3 = current) | dev | — | allowed — fallback to v1 | `1R 2X cur=1` | ACTIVE |
| {R,X,D} | `1R 2X 3D cur=3` | discard(v1 ≠ current) | dev | — | allowed — pointer untouched | `1X 2X 3D cur=3` | ACTIVE |
| {R,X,D} | `1R 2X 3D cur=3` | restore(v2) | dev | — | allowed — v2 < current ⇒ pointer untouched | `1R 2R 3D cur=3` | ACTIVE |

**`VersioningSpecDriftTest` parses this table** (the `ScopeMatrixSpecDriftTest` shape), so
the model test in §13 runs the DOC's rows, not a copy. Editing a row is editing the rule;
the parser fails the build on a row it cannot read.

### 3.6 The one write rule (copy-on-write)

> **When we write, we make sure it is not a released version. If it is released, we first
> create a draft out of the released version, then write. Otherwise we write — no problem.**

Mechanically, `PUT /pipelines/{id}` resolves the current state of the pipeline and takes
exactly one of two branches (§5.1/§5.2). There is no third branch: a PUT never appends a
released version, and a PUT never touches a RELEASED or DISCARDED row.

**Creation is draft-first too, without exception (D55).** There is nothing to copy from, so
there is no copy-on-write step — but `POST /pipelines`, `pipelines_create`, `POST /templates`
and `templates_create` all land version 1 as **DRAFT**, and the index row's `current_version`
stays NULL until a human releases it. DRAFT → RELEASED is a human step; D4 holds with no
exception, on creation as on every subsequent change.

The pipeline is executable the moment it is created all the same: drafts have been executable
since 039 (§8), and execute with no version runs the **working** version (§7.2). That is what
retired the previous rule. Until 2026-09-08 this section said creation "lands version 1 directly
as RELEASED, so an MCP-authored pipeline is executable the moment it is created" — an assumption
written on 2026-08-31, never ratified, whose only justification stopped being true when drafts
became executable. Its cost was real: an agent that created `demo/top_carrier_by_borough`
followed it to the letter and produced a RELEASED pipeline no human had looked at.

The two create paths that are **not** authoring keep landing RELEASED, and that is the whole
exception: a promotion import (§9.2) and the seeders that ride the same import services
(`ExampleContentSeeder`, `LakeBootstrapSeeder`). The content they write was reviewed and released
where it came from, and the system actor is not an agent asking for a review. The distinction is
a required argument on both repositories' `create` (`CreateLifecycle`), so a new create path has
to say which of the two it is and a missed one is a compile error.

### 3.7 What rides the draft vs. the release

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

### 5.4 Purge the draft (was "discard", 101)

The draft verb is **purge** (§3.1): the row is hard-deleted **together with its
executions** — development runs of a thing that never shipped are not history, and the
executions FK must never force a tombstone. The service deletes the execution rows in the
same transaction (the constraint stays `NO ACTION`; the delete is explicit, auditable and
testable — metadata-db.md documents the choice), then the version row; when it was the
sole version the entity row goes too (the entity purge, §3.2).

```sql
DELETE FROM pipeline_executions
 WHERE (pipeline_id, version) = (:id, :draftVersion);

DELETE FROM pipeline_versions
 WHERE pipeline_id = :id AND version = :draftVersion
   AND status = 'DRAFT' AND body_hash = :expectedHash
```

Redis result keys are not deleted — they expire on their own TTL, and a purge leaves zero
orphan execution ROWS (asserted by test); an expired key is not a reference. If the purged
draft had become `current_version` (the development fallback of §3.4), the same
transaction recomputes the pointer. 0 rows on the version delete ⇒ 409, as before.

### 5.5 Drafts are a deployment capability (039)

D7 says *"receiver environments never author"* — nothing enforced it. Now something does,
mechanically, as one block of deployment-role settings
([configuration.md §3.19](configuration.md#319-deployment); the codebase groups config
one-per-concern — `auth`, `executor`, `staging` — so `deployment` is the house shape):

```yaml
datapipelines:
  deployment:
    name: dev                      # LABEL ONLY — nothing branches on it (below)
    authoring-enabled: true        # the capability this section enforces
    promotion:                     # RESERVED — not implemented as of this round (§10.6)
      server-key: ...              # INBOUND: what I accept (receiver role)
      target:                      # OUTBOUND: where I push (sender role)
        base-url: ...
        server-key: ...
```

- **`deployment.authoring-enabled`, default `true`** — named for the CAPABILITY, not the
  environment: someone runs this on ONE box, authors there and runs there, and that server
  is "production" in every ordinary sense, so a key that gated authoring on an environment
  NAME would lock them out of the only server they have. A promotion receiver turns it
  off; everyone else is on by default.
- **`deployment.name` is a LABEL, and nothing may branch on it.** This is the rule that
  makes the bundling safe rather than a regression — putting the label next to the
  capability re-creates exactly the temptation the capability naming exists to avoid. Its
  one consumer is the startup posture log line (name + authoring state); it is not on
  `/info` (permitAll), and a guard test pins that no production code reads it in a
  conditional — stronger, that no production source names the key at all beyond its log
  consumer.
- **Enforced at the write path, fail-closed** (C3), with the catalogued refusals
  `pipeline.authoring.disabled` / `template.authoring.disabled` (§13.13/§13.9, 403)
  naming the reason. Every authoring write refuses on a receiver: pipeline/template
  create, update/draft, release, discard and delete — REST and MCP alike, at the shared
  services. Reads, execution and **import are unaffected**.
- **Draft creation hangs off the WRITE path only — never off "a released row was
  written".** This is the trap this section exists for: promotion imports RELEASED
  versions into a receiver, and any draft-creation logic attached to the released-row path
  would fire on every import and create drafts on exactly the deployments this rule
  forbids them on. The import paths (§9.2) insert RELEASED rows directly and never open
  drafts; the round's tests assert it.
- **Boot checks** ([configuration.md §7](configuration.md#7-config-validation)): a
  deployment with a promotion `server-key` configured — meaning it RECEIVES — AND
  authoring enabled WARNs loudly (D7's violation stated in config) but does not fail (a
  one-box deployment may legitimately be both). **This check is currently one-sided**: the
  `promotion` sub-block is reserved for the round that implements it (§10.6's fenced
  sample), so the authoring half ships now and the promotion half slots into the check's
  seam without rework. Authoring disabled while DRAFT rows exist REFUSES startup, naming
  them: someone authored on a receiver and version alignment may already be broken (§9.3)
  — better found at boot than at the next promotion's 409.

---

## 6. Templates: Same Lifecycle, Plus the Pin Rule

`template_versions` gains the identical statuses, hash column, one-draft index, and four
write paths (mirrored codes `template.version.conflict`, `template.version.not_draft`).

**The template metadata asymmetry (v1.3).** §3.7's metadata-rides-the-release is a
PIPELINE rule, and it works there because the pipeline body *carries* its metadata — the
draft row stages it inside `body_json`. A template's `display_name`/`description` live on
the index row `templates` only and are not part of the versioned artifact, so a template
draft stages what CAN be staged (engine/dialect/is_library/imports/body — exactly the
canonical body of §4.1) and the index metadata keeps moving at save time. Templates have
no rename (`name` is the identity), so §3.7's draft-write-time name check is pipeline-only.
Template draft purge is likewise simpler: nothing references a `template_versions` row by FK
(pipeline pins are numbers in JSON), so a template draft is always hard-deleted with no
execution cleanup to do. A RELEASED template version is discarded/restored exactly like a
pipeline release (§3.5's table, template code spellings), and `template.in_use` — the pin
guard that already guarded entity delete — is the code for a pinned version's
discard/purge too.

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
| `POST /api/v1/pipelines` | Creates v1 **DRAFT** (§3.6, D55). Response carries `status: "DRAFT"`, `version: 1`, `body_hash`, `current_version: null` and the `draft` pointer. Releasing it is `POST …/release` like any other draft. |
| `PUT /api/v1/pipelines/{id}` | **Semantics change:** always writes the draft branch (§5.1 or §5.2). Never appends a released version. Requires the hash precondition. Response carries the version's `status` and `body_hash`. A body identical to the released one is a **no-op** (§5.1): no draft, no burned number, and the response reports the current RELEASED state with no draft pointer. |
| `POST /api/v1/pipelines/{id}/release` | New. Hash-guarded (§5.3). UI-only in practice; no MCP tool is exposed for it (D4). |
| `POST /api/v1/pipelines/{id}/draft/discard` | The editor's draft verb — **now a purge** (§5.4): the draft row and its executions are deleted, no tombstone. Still hash-guarded, still `204`. |
| `POST /api/v1/pipelines/{id}/versions/{v}/discard` | New (101). Discards RELEASED version v (§3.1): row flips to DISCARDED, pointer per §3.4. Refusals: `not_released`, `pinned`, authoring. |
| `POST /api/v1/pipelines/{id}/versions/{v}/restore` | New (101). Returns a DISCARDED version to RELEASED; pointer moves only if v > current or current is NULL. |
| `DELETE /api/v1/pipelines/{id}/versions/{v}` | New (101). **Purge, drafts only** — the row and its executions go; sole draft ⇒ the entity goes with it. Refusals: `last_release`, `pinned`. |
| `DELETE /api/v1/pipelines/{id}` | **Semantics change (101):** the entity purge — allowed only when the only version is a DRAFT (`last_release` otherwise), with `include_exclusive_draft_templates` (§3.5). Replaces the V1 soft delete, which V19 retires. |
| `POST /api/v1/pipelines/{id}/current` | New (101). **Manual switch** — body `{"version": v}`; v must be live and eligible (`not_eligible`). Not authoring-gated: the promotion receiver's verb. |
| `POST /api/v1/pipelines/{id}/execute` | With no `version`, runs the **working version** (§7.2, D56) — the draft when one exists, else the latest release. An explicit `version` is still exact and never clamped. The execution record pins the version that ran, and the executions screen shows `DRAFT` beside a draft run. |
| `GET /api/v1/pipelines/{id}` | Read shape gains the version's `status` and `body_hash`, `current_version`, and the `draft` pointer when one exists. **Since 039 the default body is the working version (§7.1): the DRAFT when one exists, else the current released version.** |
| `GET /api/v1/pipelines` (and `?prefix=`) | Each row's `version` is the **working** version and a new `status` field says `DRAFT` or `RELEASED` — since D55 a listing that reported `current_version` alone would show nothing for every freshly authored pipeline. |
| `GET /api/v1/pipelines/{id}/export` | A pipeline with no RELEASED version is refused with `pipeline.promotion.not_released` (409, "release it from the UI first"): an export feeds a promotion import, which lands RELEASED content. |
| `POST /api/v1/endpoints` | Publishing over a pipeline whose `current_version` is null is refused with `endpoint.pipeline_not_released` — the code and the check already existed; since D55 it is an ordinary state rather than an unreachable one. |
| Templates (`/api/v1/templates/...`) | Mirror of all the above (release = §8.9, discard = §8.10 in rest-api.md) — addressed by name in query/body since rest-api v2.0 (§9.6: the name never travels in a path segment). The 101 verbs are twins by name: `POST /api/v1/templates/version/discard|restore|current` (name + version in the body) and `DELETE /api/v1/templates` (the entity purge). |

**The 101 verbs are human verbs (D4 family).** All carry
`MUTATE_PIPELINES_TEMPLATES` and are **session-only** — an API key, however scoped, is
refused (`auth.session.required`); there is deliberately no MCP tool for any of them
(§12.2's rule, applied to purge/discard/restore/switch exactly as to release). The
one non-session exception is `POST …/current` for an operator rolling a receiver forward
or back — still session-only on REST; receivers are driven from the UI. Every verb
emits its audit event (§3.5 of enums.md §15): `pipeline.version.discarded` /
`restored` / `purged`, `pipeline.purged`, `pipeline.current_switched`, and the template
twins.

### 7.1 Authoring reads return the working version (039)

The principle: **a RELEASED version is never modified, and if a draft exists it is reused,
no matter what.** Authoring reads — REST `GET /pipelines/{id}` and
`GET /templates?name=...`, the MCP `pipelines_get` / `templates_get` tools, and the editors —
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
  surfaces. ~~Execution keeps reading `current_version`.~~ **Superseded by D56 (§7.2):
  execution's default is the working version too.**
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

### 7.2 Execute with no version runs the working version (D56, 099)

**The default everywhere is the working version: the draft when one exists, else the latest
release.** It is the rule §7.1 already applied to authoring reads, now applied to running as
well, and it is what "we always run the LAST version" means:

| Surface | Default with no `version` |
|---|---|
| `POST /api/v1/pipelines/{id}/execute` | working version |
| `pipelines_execute` (MCP) | working version |
| `datapipelines://pipelines/{id}` (MCP resource, no `/versions/{n}`) | working version |
| `pipelines_get`, `GET /pipelines/{id}`, the editor | working version (§7.1, unchanged) |
| A published endpoint (`GET /api/x/…`) | **released only** — a draft is never served (§5.1 of published-endpoints); unchanged |
| Promotion candidates and the promotion push | **released only** (D6); unchanged |

One place resolves it: `PipelineService.workingVersion(workspaceId, record)`. It was three
inline copies (the REST execute controller, `PipelineExecuteTool`, `pipelines_get`) before 099,
which is exactly the drift the D6 refactor removed from the LOOKUP and left in the DEFAULT.

**Posture.** Under `authoring-enabled=false` (the hardened default, configuration.md §244) no
draft can be created at all — create, update, release and discard are all refused with
`pipeline.authoring.disabled` (§5.5) — so on a non-development server the working version is a
RELEASED version by construction, and no configuration says otherwise. That is the ruling's
second half ("on servers which are not development, we will always have RELEASED pipelines, no
DRAFT at all") held up by a mechanism rather than by a promise.

Two consequences worth stating:

- **A draft run is marked.** The execution record already pins the version that ran; the
  executions list and detail render `DRAFT` beside a draft run (§8, ui-screens §4.8) so a
  reader is never guessing whether a result came from reviewed content.
- **There is one "nothing to run" case at the DEFAULT**, and it is not a fresh pipeline: creation always writes
  version 1, so the only way to a version-less pipeline is purging the sole draft of a
  never-released one (§3.2 — the purge deletes the entity with it). Execute answers `404 pipeline.execution.not_found` there — the same
  refusal an out-of-range explicit version gets, with `details.pipeline_version` naming it.
  A pipeline whose every release is DISCARDED (entity DISCARDED, pointer NULL) answers the
  same refusal at execute-default, and an explicit execution of a DISCARDED version is
  refused the same way — a retired version is not executable; restore it first (§3.1).

---

## 8. Executing Drafts

Drafts are executable — the editor's test loop depends on it (single-node run, full-draft
run). Because a draft **is** a version row, the existing composite FK records the run
against the real draft version number; nothing about the execution schema weakens.

- **A draft run is DERIVED, not recorded (ratified 2026-08-31).** For an execution of
  version *N*: it was a draft run when `started_at < released_at`, or when that version has
  no `released_at` (still DRAFT). No schema column. The derivation is sound
  because a version's lifecycle is one-way — RELEASED never returns to DRAFT (§3.1: a write
  after release opens a NEW version), numbers are never reused (§3.3), and 101's restore
  returns a DISCARDED version to RELEASED **keeping its original `released_at`** — so each
  `(pipeline_id, version)` has exactly one `released_at` to compare against, forever.

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
a dev pipeline pins `acme/lib/foo.sql@2`, the bundled template lands on a fresh target as
`acme/lib/foo.sql@1`, and the pipeline's pin fails `template_version_not_found`. The export/import
pair only round-trips when both environments' numbers happen to be in lockstep — i.e.,
never after any drift. Pins are version numbers; cross-env renumbering is a
correctness-breaking hazard, not a cosmetic one. D5 fixes this by construction.

### 9.2 Import with preserved versions

The import payload may carry `version` (promotion always sends it). When present it is
**honored**; when absent, today's allocate-next-local behavior applies. Target-side rules:

| Target state for that (id, version) | Hash vs target | Result |
|---|---|---|
| Absent | — | Insert as RELEASED at that exact version (`released_at` from source), with source's `body_hash`. **The pointer does not move (D60)** — except when the entity has no current at all, when this first import sets it. |
| Present, RELEASED | **Same** | Idempotent no-op (200). Re-importing an old export is safe. |
| Present, RELEASED | **Different** | `409 pipeline.import.version_conflict`, both hashes in details. Never overwrite. |
| Present, DRAFT (local draft) | any | `409 pipeline.import.version_conflict` — a local engineer's draft is never clobbered. |
| Present, DISCARDED | any | `409 pipeline.import.version_conflict` — discarded numbers are never reused. |

**The pointer rule on import (D60, 101).** An import **never moves an existing pointer**:
promoting v5 onto a receiver whose pointer names v3 inserts the version and leaves the
pointer at v3 — the human switches when they are ready (`POST …/current`, §7). The single
exception is the FIRST import of an entity that has no current at all (a never-released
entity, or one whose every release was discarded): nothing exists to break, so the
pointer is set to the imported version. Index metadata (`name`/`display_name`/
`description`) rides the pointer, not the number: it adopts the imported body's values
exactly when the import moved the pointer. Version-less imports (no `version` in the
payload) allocate `max(version) + 1` — never `current_version + 1` (the pointer can be
NULL or stale) — and follow the same pointer rule. Gaps in the number sequence are
expected and harmless.

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

Against exactly **one configured higher environment** (base URL + the pre-shared server key of
§10.6; the keys themselves are defined in [configuration.md §3.19](configuration.md#319-deployment),
not restated here, per that doc's sole-authority rule). Shipped by 055; the receiver's two
endpoints are specified in [REST API §18](rest-api.md#18-promotion-endpoints-receiver).

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
target's per-pipeline and per-template `(name, current_version, body_hash)`
([REST API §18.1](rest-api.md#181-promotion-inventory)) and compares. **By name, not by id**
(the shipped correction to this paragraph's first draft): workspace and template names are a
global namespace and pipeline ids are globally unique but locally opaque, so a name is the one
identifier that means the same thing on both deployments. The comparison is:

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

**Ratified shape (operator, 2026-09-01): a pre-shared server key, not a principal.** Shipped
2026-09-02 (055). The
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

The configuration shape. It lives inside the `datapipelines.deployment` block (§5.5's
amendment — the deployment-role settings are grouped, and the `promotion` sub-block mirrors
the inbound/outbound split this section draws). It is now **shipped and documented in
[configuration.md §3.19](configuration.md#319-deployment)**, which is the operator-facing
authority; the sample below is retained as this section's own illustration:

```yaml
# receiver (e.g. uat) — absent means promotion is refused
datapipelines:
  deployment:
    name: uat
    authoring-enabled: false           # receivers never author (D7, §5.5)
    promotion:
      server-key: ${DATAPIPELINES_DEPLOYMENT_PROMOTION_SERVER_KEY:}

# sender (e.g. dev) — the target and the same secret
datapipelines:
  deployment:
    name: dev
    promotion:
      target:
        base-url: ${DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_URL:}
        server-key: ${DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_KEY:}
```

The implementing round adds the block to `configuration.md` in the commit that makes it
real.

**The gap this shape did not close, and how it was closed (R7, ratified 2026-09-02).**
`pipeline_versions.created_by` and `pipeline_executions.triggered_by` are
`NOT NULL REFERENCES users(id)`. A payload authenticated by a server key carries no user, and
the source deployment's user ids are meaningless on the receiver — different `users` table. So
an imported row still needs an actor that exists locally.

Three options were weighed: a reserved non-interactive `users` row; making the column nullable
with a CHECK; and mapping by email (rejected outright — it silently creates or mis-attributes
when the human has no account on the receiver).

**The ruling took the first option's shape but not its mechanism:** a **system service account
provisioned at first boot**, not created by a migration, and used for promotion AND for every
other non-user-bound write the system makes now or later — the retention job, the
stale-execution sweeper, anything automated that comes after. It is provisioned through the
same `createUser` path the bootstrap admin uses, with the same create-if-absent and
insert-race semantics, and read back through ONE well-known lookup so nobody mints a second
actor. Login is disabled by construction: its provider name is reserved at startup validation,
its address is under RFC 2606's unresolvable `.invalid` TLD, and the local-password paths
refuse it. The full contract is [Auth §4.5](auth.md#45-the-system-service-account-r7).

The FKs therefore stay `NOT NULL` and no schema change was needed. Every promoted row is
stamped with that actor, and the receiver's audit event carries the provenance the row itself
cannot: `source_env` (the sender's `deployment.name`) and a truncated fingerprint of the key
that authorised the push — never the key. The releasing human stays recoverable from the
artifact's own `released_by` on the source deployment.

The machine-auth note's F10 defers to this section; **F2's service-account question is
answered only for the ACTOR half** — an application EXECUTING a pipeline is a different
problem from a deployment PROMOTING one, and D16's own headline is that execution is blocked
by the SSE contract before auth is even reached (R9).

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
  catches a doctored payload; the D60 pointer rule (subsequent import never moves the
  pointer; first import onto a current-less entity sets it).
- **Promotion guards:** draft selected ⇒ `pipeline.promotion.not_released`; same-version ⇒
  `pipeline.promotion.not_newer`; push order respects the closure (child pipeline absent on
  target ⇒ parent not pushed); datasource pre-validation fails batch atomically.
- **The lifecycle model test (101's acceptance gate).** `VersionLifecycleModelTest`
  generates seeded random event sequences (length 1–25) over real Postgres and the real
  services, in both postures, and after EVERY event asserts the invariants — ≥ 1 version
  row; ≤ 1 DRAFT; the DRAFT is the highest number; numbers strictly increase; the pointer
  is live-or-NULL and eligible for the posture; after a pointer move it is the highest
  eligible; no exact pin from a live version points at a DISCARDED/purged version; the
  entity status equals the derivation; names unique; a purged version leaves zero
  execution rows — AND that the event's outcome equals §3.5's row for that shape. The
  sequences replay §3.5's own rows (parsed, never copied) before the random phase. The
  gate runs 2,000 sequences per posture with a bounded, measured runtime; the seed prints
  on failure with a shrunk counterexample. **Falsify the model itself** — comment out one
  service rule (e.g. eligibility under hardened) and the property must fail within the
  run; a model test that cannot go red is a rubber stamp.
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
  — "not in the agent" means no first-class tool surface. The 101 verbs (purge, discard,
  restore, entity purge, switch) extend the same ruling: no MCP tools, and REST carries
  them session-only.
- **UI for the lifecycle verbs** — 102 puts buttons on them; this round ships services,
  REST, the spec and the proof.
- **Bulk "release all drafts"** — per-pipeline release only for now; a bulk action can be
  added to the promotion screen later without schema change.
- **Multi-hop promotion, promotion scheduling, promotion history UI** — the single-target,
  human-triggered flow ships first.
- **Merge/rebase tooling for conflicting drafts** — the conflict response gives the current
  hash; rebase is manual by design at this scale.
- **Discarded-release retention jobs** — DISCARDED release rows accumulate only when a
  human discards one; a retention job is deferred until measured. (The executed-draft
  tombstones this used to describe are gone — drafts are purged with their executions,
  §3.1.)

## 15. Ratified Decisions and Remaining Open Items

Items 1–3 were **ratified by the operator on 2026-08-31** and are written into their
sections; they are recorded here so a reader sees what was decided and why, without
re-opening it.

| # | Decision | Where | Reasoning that settled it |
|---|---|---|---|
| 1 | Metadata rides the release, plus a draft-write-time name-uniqueness check | §3.7 | The `pipelines` row is an INDEX over the current released body, not a second copy of the metadata — so indexing a draft would make it an index over a mixture. The early check removes the only real cost (a duplicate-name rename failing at Release rather than at the write). |
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
| 2026-09-08 | v1.7 | 101 version lifecycle (D57–D60) | **§3 rewritten** as a table-first model: version statuses `DRAFT → RELEASED → DISCARDED` with the verb pairing **purge = the draft verb** (row hard-deleted WITH its executions — the §3.4 tombstone rule and every sentence about the executed-draft DISCARDED flip are withdrawn), **discard = the release verb** (reversible via **restore**), entity status **derived** (ACTIVE while any version is live; DISCARDED when all are), **names unique forever** (D59 — `is_deleted` soft delete retired in V19, every reader moves to the derived status, `pipeline_reference_deleted` becomes the derived entity-DISCARDED refusal), and **`current_version` sticky and event-driven** (D60 — moves only on release / discard-of-current / restore-above-current / manual switch / purge-of-current-draft; picks the highest ELIGIBLE live version; DRAFT eligible only under development posture; import never moves an existing pointer except the first import onto a current-less entity). **New §3.5 "The lifecycle table"**: ~67 rows over ten version-set shapes × events × postures with an inbound-edge column — the ruling, parsed by `VersioningSpecDriftTest` so the model test runs the DOC's rows. New decisions **D57** (≥ 1 version row; `last_release` refuses only the purge path on a release), **D58** (PIPELINE nodes pin RELEASED children only — `pipeline.validation.pipeline_reference_not_released` at save), **D59**, **D60**. §5.4 is now the draft purge (executions deleted in-transaction, Redis keys expire on TTL); §7's REST table gains the 101 verbs (all MUTATE_PIPELINES_TEMPLATES, all **session-only**, all audited — `pipeline.version.discarded/restored/purged`, `pipeline.purged`, `pipeline.current_switched`, template twins); §9.2's import table drops "bump if greater" for the D60 pointer rule, and index metadata now rides the pointer, not the number. §13 gains the model-test acceptance gate. Old §3.2/§3.5 became §3.6/§3.7. |
| 2026-09-08 | v1.6 | 099 draft-first (D55/D56) | **§3.2 rewritten.** Creation lands version 1 as a **DRAFT** for pipelines and templates alike, and `pipelines.current_version` / `templates.current_version` are NULL until a human releases (`V18` makes them nullable and drops `DEFAULT 0`; the two import readers that did arithmetic on the pointer read it through `COALESCE(…, 0)`). The old sentence — "creation is not a modification … lands version 1 directly as RELEASED, so an MCP-authored pipeline is executable the moment it is created" — was an ASSUMPTION written 2026-08-31, never ratified, contradicting D4, and its only justification has been false since 039 made drafts executable. **New §7.2:** execute with no version runs the WORKING version (draft if one exists, else the latest release), resolved in ONE place (`PipelineService.workingVersion`) for REST execute, `pipelines_execute` and the MCP body resource; released-only surfaces (promotion candidates and inventory, published endpoints, the export bundle) are unchanged and now say so. §3.4 records the nullable pointer, the working-version reads and the one "no version at all" state (discard of the sole draft of a never-released pipeline). §7's REST rows updated; new decisions **D55** (create lands DRAFT, D4 without exception) and **D56** (execute runs the last version; hardened deployments hold no drafts by construction). The non-authoring create paths — promotion import and the seeders that ride it — still land RELEASED, now as a required `CreateLifecycle` argument rather than an implicit default. |
| 2026-09-01 | v1.5 | 039 lifecycle loose ends | §5.1: the **no-op guard** — a draft-create whose body is identical to the released one is suppressed, compared hash-to-hash in the same statement by the canonical-hash expression itself; the no-op answer returns the current RELEASED state (not a 4xx, no draft pointer), both arms join the guard so a stale hash still 409s, a no-op never answers "no draft" while a draft exists, and a draft edited back to its released parent is left alone (never auto-discarded). Templates mirror it, with index metadata still moving on a content-identical save. Draft-existence becomes a truthful "content genuinely differs" signal. §7.1: authoring reads (REST GET, `pipelines_get`/`templates_get`, the editors) return the **working version** — the draft if one exists, else `current_version` — and state which version/status they returned; explicit `version` still wins, `current_version` keeps meaning latest-released (working version is derived, no schema change). §5.5: authoring is a **deployment capability** — the `datapipelines.deployment` block (`name` a LABEL nothing branches on, pinned by a guard test; `authoring-enabled` default true; the `promotion` sub-block reserved via §10.6's fenced sample) — enforced fail-closed at the write path with `pipeline.authoring.disabled` / `template.authoring.disabled` (§13.13/§13.9); import never creates drafts and is never refused; boot logs the deployment posture, WARNs on the receiver-also-authors combination (currently one-sided — the promotion half slots in when promotion ships) and refuses to start when a disabled-authoring deployment holds existing drafts. |
| 2026-09-01 | v1.4 | 035 implementation | The implementer's amendments, landing with the code. §4.1: canonicalization pinned mechanically — the hash is computed BY THE DATABASE (`encode(sha256(convert_to(<jsonb>::text, 'UTF8')), 'hex')`) over the JSONB projection (pipelines) / the `jsonb_build_object` field object (templates), one expression shared by V6's backfill and every write; the serializer string is the write format, not the hash anchor. §3.4: draft allocation is `max(existing)+1` — a DISCARDED number is consumed, so the pointer alone would collide. §5.3: the sketch's `updated_at = NOW()` on the flip is dropped; §11's column note governs (draft-write metadata, never restamped). §6: template metadata asymmetry recorded (display_name/description are index-row, save-time; template discard always hard-deletes — no FK can block it). §7: exact REST spellings — `If-Match` header on PUT/release/discard, `pipeline.version.not_draft` vs `*.version.conflict` distinction, `draft_run` field name; §9.2: wire spelling (`body_hash` required with `version`, `released_at` honored, `released_by` = importing actor, template hash-mismatch gap surfaced as `template.version.conflict` + reason, raised not papered); §12.3 records the §12.2 decision (payload-only MCP change; `expected_hash` required on `pipelines_update`; mcp-server.md §6.2.2/§6.2.5 prose amended). |
| 2026-09-01 | v1.3 | operator ratification | §10.6 replaced: the promotion credential is a **pre-shared server key**, not a principal. The receiver holds a promotion server key and refuses when it is absent (fail closed); the sender holds the same secret with the target URL and presents it on the promotion call. No service account, no scope-matrix entry, no `users` row for the credential — promotion is a deployment trusting a deployment, and the earlier service-principal draft was more machinery than the problem needs. Records the one gap the shape does not close: `created_by`/`triggered_by` are NOT NULL FKs to `users`, so an imported row still needs a local actor; three options given, a single reserved non-interactive row recommended, awaiting ratification. F10 defers here; F2's service-account question stays with the machine-auth note, because an application EXECUTING a pipeline is a different problem from a deployment PROMOTING one. |
| 2026-08-31 | v1.2 | operator request | New §12: the agent-facing skill (`.agents/skills/datapipelines/SKILL.md`) is updated in the SAME commit as the behaviour it describes. Concrete for this spec — `SKILL.md:51` says "every save creates a new version", which D2 makes false, so an agent holding the current skill would believe a `pipelines_update` published something it left as a draft. Enumerates what this round obliges (versioning concept, golden path stopping short of release, draft execution, the new 409s, the hash protocol, the references list), what the implementor must DECIDE rather than assume (whether the draft result reshapes the MCP tool surface, which `mcp-server.md` and `McpToolCatalog` own), and the general rule: the test is "would an agent holding the current skill now be wrong?" Notes that the skill has no drift guard, so the rule is carried by review. Sections 12–14 renumbered to 13–15. |
| 2026-08-31 | v1.1 | operator ratification | §15's first three open items decided and written into their sections. §3.5 rewritten: the `pipelines` row is an INDEX over the current released body — the metadata is not duplicated, one side is the artifact and the other is how you find it — so metadata rides the release by definition rather than by preference, plus a draft-write-time uniqueness check reusing `pipeline.validation.duplicate_name` (no catalogue addition). New §10.6 specifies the promotion-peer credential as a non-interactive service principal, scoped to the import endpoint, backed by a real `users` row because `created_by`/`triggered_by` are NOT NULL FKs; this doc is its authority and the machine-auth note's F10 defers here. §8 drops the `ran_draft` column for derivation from `released_at`, with its cross-clock precondition stated (`started_at` is application-supplied) and `released_at` required to be database-generated. |
| 2026-08-31 | v1.0 | orchestrator review | Review pass before commit. Corrected §3.4: the executions FK is `NO ACTION` (its declaration carries no `ON DELETE` clause), not `RESTRICT` — it blocks identically here, but an implementer reading the old wording would have written the wrong DDL. §13 (Testing Requirements) now states the operational half of the drift coupling: catalogue rows and constants land in the SAME commit, because the drift test lives on `main` permanently. Also grouped the doc into `DocsCatalog` "Contracts" — 033's in-app docs index fails at init on an ungrouped doc, so the spec could not land without it. Verified against the tree: the import-renumbering defect (§9.1), `PipelineImportService.SERVER_FIELDS`, the executions composite FK, and the absence of `status`/`body_hash` today. |
| 2026-08-31 | v1.0 | versioning design session | Initial spec: draft-in-version-table lifecycle (D1/D2), content-hash preconditions (D3), UI-only release (D4), version numbers as global identities + preserved-version import (D5), latest-released-only promotion with two-sided guards (D6/D7/D8). Records the verified import-renumbering defect as D5's rationale. |
