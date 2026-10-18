# Release dependencies — research and implementation specification

**Status:** RATIFIED — the owner ruled on every §17 row on 2026-10-18 00:55 UTC ([#397, comment 6243954207](https://github.com/msabiransari/datapipelines/issues/397#issuecomment-6243954207)): rows 1 and 3–12 as recommended; row 2 the alternative, extended (family-prefixed codes AND the old codes replaced — §14 amended 2026-10-18). Implementation is authorized slice by slice through §16's issues (#517–#523), each on its own brief; one sub-question (§14, the deprecation window) is still the owner's and is answered before #517 is briefed. Amendment of 2026-10-18 by the orchestrator: §14, §17 row 2, every `release.*` code name, and the S09/S35 citations re-anchored to main `354e196b3` (§19). **Written:** 2026-10-13. **Verified base:** `origin/main` at `3de6212304997007373efa11bf5a91aaf49b224b`. Existing behavior below was read at that tree; proposed names, bounds and behavior are explicitly recommendations. “FROZEN” identifies a proposed build contract once ratified, not a claim that it already ships.

**Authority:** #397's ruling allows draft pins throughout development and requires a dependency-aware, consented parent release. [#459](https://github.com/msabiransari/datapipelines/issues/459) subsequently answered the held runtime question: explicitly selected draft dashboards work with draft dependencies. It is CLOSED and its landed implementation is on this base. The blank OPEN INPUT and in-flight #369 wording in the dispatch brief are stale; they do not require another owner answer. #369's explicit-version, session/key and lens restrictions remain. §18 records corrections without editing any older record.

**Mould:** [dashboard implementation spec](2026-09-28-dashboard-implementation-spec.md) and [parameter-set workspace spec](2026-10-02-parameter-set-workspace-spec.md). Product packaging is an explicit top-level doc allowlist (`modules/web/build.gradle.kts:118`); this contributor specification is outside it. The source register in §19 identifies exact base-tree file:lines. References to S-numbers resolve there. Acceptance scenarios are future work, not evidence that this design has run.

## 0. What ships, and what does not

**FROZEN:** one bounded, workspace-scoped dependency graph, one release plan, explicit selection of every reachable DRAFT dependency, one metadata transaction, native validation for every released version, dependencies before their consumers, and one durable audit row per version actually released. Published runtime and promotion remain RELEASED-only. A dependency does not acquire a new version number when released.

**Already shipped:** draft source/child admission and draft preview (#459); pipeline draft-purge guards (#462); templates, visualizations and dashboards workspaces (#398/#399/#400); root dialog hash guards (#416/#453). Do not build these again. The remaining work is the generic closure, dependency hash consent, cross-family release, library-import lifecycle protection, the shared dialog/read projection, and complete promotion closure.

**Out of scope:** new authoring UI, a release MCP tool, automatic promotion or deployment, datasource creation/release, arbitrary runtime dependencies discovered from SQL, changing pipeline-check override policy, waiving visualization evidence, re-numbering bodies, or cascading purge/discard/restore. No production code, migration, test, other product document or generated asset changes in this research lane.

**Build choices:** class decomposition, storage query batching and visual indentation may vary within the frozen contracts. No lane may choose a different consent default, error envelope, authorization policy or partial route shape.

## 1. Modules and the graph — question 1

**Verified:** validators consume references through lower-layer ports; `web` assembles services. `TemplateReleaser` is declared in `pipeline-contract` because `templates` depends on that module, not the reverse (S08). No generic release-tree coordinator exists in the current five release services. The existing cascades stop at direct template pins or direct visualizations (S07, S09–S11).

### 1.1 Complete pin inventory

Each arrow is dependent → dependency. Multiplicity counts field occurrences; the release deduplicates exact versions, while retaining every incoming field path for explanations.

| Dependent → dependency | Pin field / cardinality | Validator/source at the base | Current admission and release |
|---|---|---|---|
| Pipeline → SQL or transform template | `nodes[].template.{id,version}`; zero to many eligible nodes | S01:168–205; S02:111–114; S07:291–295 | DRAFT/RELEASED at save; direct template cascade with explicit consent at release. PIPELINE/CALCULATOR nodes have no such pin. |
| Pipeline → child pipeline | PIPELINE `nodes[].pipeline.{name,version}`; zero to many | S03:75–112, 120–151 | DRAFT/RELEASED at authoring; RELEASED at release/import unless the new plan releases it first. |
| Pipeline → parameter set | **No supported pin** | S04:39–65; S02:95–114 | `parameters` is the pipeline's own declarations. No parameter-set dependency is inferred from equal names or dashboard bindings. |
| Parameter set → selector template | `parameters[].source.template.{id,version}`; zero to many | S05:36–92; S06:140–148 | DRAFT/RELEASED at save; direct template cascade exists at release. Inline/constants have no template pin. |
| Visualization → transform template | `transform.template.{name,version}`; zero or one | S12:142–186 | Live draft accepted; direct template cascade; visualization's own evidence gate remains mandatory. |
| Dashboard → parameter set | `parameter_set.{name,version}`; zero or one | S13:74–82 | Live draft accepted; currently no set cascade, release requires RELEASED. |
| Dashboard → pipeline | `sources[].pipeline.{name,version}`; zero to many | S13:95–123 | Live draft accepted at save since #459. Read-only and bindings still checked. Release currently rejects a draft source. |
| Dashboard → visualization | `visualizations[].visualization.{name,version}`; zero to many | S13:216–245 | Live draft accepted; direct visualization cascade, through the visualization's own gate. Its draft transform is not implicitly consented. |
| Template → imported library template | `imports[].{id,version,alias}`; zero to many, transitively | S14:19–25; S15:165–188; S16:53–61 | Exact version; library type, alias safety, cycles and depth validated. Registry lookup is not a RELEASED-only lookup. No import-release cascade or template-import inbound arm is present in the inspected lifecycle guards. |

Datasources (`source.datasource`, pipeline node source) are named capabilities/configuration, not versioned release nodes. Calculator kinds, learned-fact citations, visualization fixtures, dashboard groups/actions and selector `depends_on` graphs are not artifact pins. Endpoints and schedules follow pointers, not version pins (S27:412). Keep these as validation prerequisites; never invent a checkbox to “release” them.

The pipeline → set negative was checked against the bound Pipeline fields, validator dispatch and serialized pin shapes, not a phrase search alone. The template-import gap was checked against `LibraryResolver`, registry lookup, `TemplateReleaseService` and the three-arm `TemplateUsage.Pins` type (S15–S17, S36). A transitive import counts even when reached through an already RELEASED template; current releases cannot be assumed to have the new closure invariant.

### 1.2 Closure and cycles

**FROZEN:** graph identity is `(workspace_id, family, resolved_entity_id, exact_version)`. References resolve by the stored name/id and exact integer version; retain that resolution's entity UUID in the plan to detect purge/recreation under the same name. Root plus all reachable version pins is the closure, including imports, child pipelines and edges under RELEASED nodes. No “latest,” “current” or unnumbered draft pin enters a saved body.

Cross-family arrows cannot return to a dashboard, visualization or parameter set. Pipeline composition and template imports are same-family graphs. **Cycles are not ruled out solely by construction:** draft pipeline mutations can create them; current CompositionRules bounds them with `composition_too_deep` (S03:37–42). Imports have an explicit exact-key path-cycle rule (S15:165–166); alias duplicates and two versions of one library are distinct cases.

The generic walk uses an explicit stack, active-path identities for cycle detection, and global memoization for distinct-node loads. Reaching an expanded node again adds an edge; it never releases the node twice. Depth is the longest path, propagated over the DAG, not the first observed path. A cycle refuses with proposed `<family>.release.dependency_cycle`; no arbitrary topological tie-break makes a cycle releasable. Preserve existing save-time self-reference, DAG and family depth checks; the release walk is a second guard against later edits and historical bodies.

**Recommended bounds:** 32 edges of total closure depth, 1,024 distinct versions including root, 4,096 pin occurrences, 100 reported blockers, 1 MiB plan request/response. Family import/composition/document bounds remain independently stricter when applicable. Count before loading/enqueuing beyond the budget; never materialize every repeated path. All are configuration with catalog rows, not literals scattered among families. A complete closure over the limit refuses; a truncated tree cannot authorize release. §17.4 owns ratification.

## 2. Data model, identities and hashes — question 6

**FROZEN:** no new persisted release-plan table or body schema. A preview is advisory and stateless. An entity has one numbered mutable DRAFT; release flips that same version and follows the existing pointer rule. Every selected version's body hash and content remain unchanged. Do not copy a dependency, create a new version, or rewrite parent pins.

The future coordinator needs a cross-process metadata write mutex scoped by workspace. **Recommendation:** lock the existing workspace row in the metadata database before pin-affecting mutations; read/import/release adapters share a `ReleaseMutationGuard` port. No Redis lock or JVM-only lock can prove this invariant. A build lane may choose a dedicated FK-backed lock row instead if a bounded experiment demonstrates that locking the workspace row creates an unacceptable interaction; the behavioral contract and proof remain frozen. Acquire the workspace guard before version/evidence locks, always in that order.

### 2.1 Release-plan wire — proposed, FROZEN after ratification

```json
{
  "root": "n0",
  "nodes": [
    {"node_id":"n0","family":"dashboard","name":"test/releases/board","version":3,"status":"DRAFT","body_hash":"hD","selectable":false},
    {"node_id":"n1","family":"visualization","name":"test/releases/chart","version":2,"status":"DRAFT","body_hash":"hV","selectable":true},
    {"node_id":"n2","family":"template","name":"test/releases/chart.jsonata","version":4,"status":"DRAFT","body_hash":"hT","selectable":true}
  ],
  "edges":[
    {"from":"n0","to":"n1","path":"visualizations[0].visualization"},
    {"from":"n1","to":"n2","path":"transform.template"}
  ],
  "complete":true,
  "blockers":[],
  "tree_hash":"opaque-server-sha256"
}
```

`node_id` is a deterministic opaque locator in one snapshot, not an entity credential. The root is implicitly selected by the Release action. A RELEASED node is shown but not selectable. A missing reference is `MISSING` in the plan even though the brief calls it ABSENT; DISCARDED retains its status. An unresolved visible pin has no body hash. The server alone determines topology, statuses, visibility and selectability.

`tree_hash` covers a versioned canonical representation of root identity, every resolved entity identity, exact version, status, body hash, current pointer, pin occurrence path, and edge. Sorting and UTF-8 serialization are shared by all adapters and pinned by a golden contract test; distinguish null from missing and include a hash-format version. Do not hash display text, user selections or warning prose. It is a concurrency token, not authorization. Permission, lens, posture and current evidence are rechecked separately on submit.

A lensed read uses the projection in §5: it returns `complete:false`, `tree_hash:null`, `can_release:false` whenever any reachable pin is hidden. A viewer with a complete read view may receive `complete:true` but has `tree_hash:null` and `can_release:false` because they cannot release. Visible nodes still carry only their admitted versions' metadata. It must not expose the unprojected digest as a hidden-object change oracle. A complete session plan for an authorized releaser carries `can_release:true` only when its known metadata blockers are empty; fresh native gates still run on submit.

### 2.2 Worked version example

| Version/pin | Before | After successful cascade |
|---|---|---|
| Dashboard `test/releases/board@3` | DRAFT, `hD`; pins chart@2, set@5 and source@7 | RELEASED, `hD`; pins exactly the same numbers; current=3 |
| Visualization `test/releases/chart@2` | DRAFT, `hV`; pins transform@4 | RELEASED, `hV`; transform pin remains @4; current=2 |
| Transform `test/releases/chart.jsonata@4` | DRAFT, `hT` | RELEASED, `hT`; current=4 |
| Parameter set @5 / source pipeline @7 | DRAFT at their own hashes | RELEASED @5 / @7 at those same hashes; their pointers follow their own native release rule |

“The draft” is a working-version selection convention on a read/UI, not a pin syntax. Resolve it to a number before saving; reject a wildcard/string/missing version through the existing reader. No migration of stored bodies or hash recalculation is needed. Old released bodies with unreleased imports must be repaired bottom-up by releasing/restoring the exact dependency, never editing an immutable parent.

Runtime `configuration_id` is distinct from `tree_hash`. At this base, draft configuration identity includes nested pipelines/templates/imports, while RELEASED identity includes direct dependencies (S18:141–170). Releasing the same versions can change the draft runtime's configuration identity because its hashing mode changes; old clients re-fetch after a stale configuration response. Do not set a runtime ID to a release-plan digest. §8 preserves and tests this separation.

## 3. Documents and save-time rules — question 2

**FROZEN:** all inventory edges accept an existing, workspace-visible DRAFT or RELEASED exact version for authoring. Missing/DISCARDED versions are invalid new pins. Allowing draft lifecycle status does not waive any body/type/binding rule. Imports gain an explicit live-status check; the current library resolver's existence/type test alone does not enforce that proposed rule.

| Edge | Checks still required on the draft's current content | Existing refusal fate |
|---|---|---|
| Pipeline → template | Existence, type/dialect, dry render, transform contract, interpolation/bind rules | Keep `pipeline.validation.template_version_not_found` for missing/discarded; no new meaning for a draft. |
| Pipeline → child | Exact version/workspace; self-reference, composition depth, declared parameters and caller output | Keep `pipeline.validation.pipeline_reference_not_released` for DISCARDED authoring pins and strict legacy release/import validation; it already admits DRAFT at authoring. New plan runs lifecycle-strict validation after child flips. |
| Set → template/datasource | SQL template; visible datasource; matching dialect; bound inputs and namespaces; no parent interpolation; selector cardinality/type and probes | Keep `parameter.validation.template_version_not_found` and all current selector failures. Datasource readiness is a precondition, not a release node. |
| Visualization → template | Live transform version, contract inputs/outputs, bindings, renderer/config/test-body validity | Keep `visualization.validation.transform_binding_invalid` with its reasons. Admission of a draft does not confer GREEN evidence. |
| Dashboard → source | D38 transitively over current source/child bodies; caller output/column contract when available, required source parameters, literals/set bindings, execution-count caps | Keep `dashboard.validation.source_not_released` for legacy release/import/strict validation only. The new plan releases the source first, then strict dashboard validation must pass. Keep `source_not_read_only` unchanged. |
| Dashboard → set/visualization | Live versions, parameter namespace/scopes, required inputs, source mappings, layout and type compatibility | Keep `dashboard.validation.dependency_not_found` for missing/discarded; release-specific blocking goes to §14's shared code. |
| Template → imported library | Exact pin, library flag, alias safety/uniqueness, closure cycles/depth, live status | Keep `template.validation.import_not_found` for a missing/discarded import with safe positional details; keep import-cycle/depth/type codes. Release status checking is new. |

Caller-output uncertainty stays as currently documented: pipeline release may record `not_observed`; the generic cascade must not invent an execution-evidence requirement for that case (S07:139–147; S27:704–709). A child with no caller node is still refused where the parent requires one. D38 is a declarative node/source rule, not a guarantee that a datasource connection is physically read-only (S20); #463 owns that separate hardening.

**Migration:** existing numbered pins and hashes remain valid. #459 already relaxed source/child authoring checks, so no repeat migration is needed. New release logic revalidates historical draft graphs and reports drift at their field paths. Existing RELEASED nodes are not silently re-released or relabeled to repair invalid closure. A missing or discarded import blocks the whole release plan until explicitly repaired.

**Build choice:** reuse validators through immutable snapshot/overlay ports; do not make `allowDraft` an implicit default in a strict release/import path. Preserve DashboardValidator's explicit `requireReleasedSources` choice (S13:40–48).

## 4. Generic release and atomicity — questions 3 and 4

### 4.1 One algorithm

**FROZEN:** all reachable pinned drafts are offered, including shared drafts and imports beneath RELEASED nodes. Exclusivity governs destructive cleanup (§12), never release: a shared draft is still the exact dependency this parent needs. Show admitted other-pinner information as context; never hide a required draft merely because another parent pins it. Draft dependencies are checked by default, matching #142's dialog. The REST/service default remains **no consent**.

1. Authenticate, check root release permission, posture and root visibility; identify its exact DRAFT and validate root `If-Match`. Read the bounded closure and authorization projection. Preview performs no release, selector probe, pipeline check execution or evidence mutation.
2. On submit, validate the plan shape, require `tree_hash` for any consented cascade, reject unknown/duplicate/RELEASED/root selection IDs, and require every reachable DRAFT dependency in `include_dependencies`. A deselected ancestor does not remove its descendants from the worklist. Missing/discarded dependencies are blockers, never checkboxes.
3. Re-read and compare the submitted digest to the server closure before any external validation work. Recheck family release permissions for every version that will flip. An unticked draft or missing/discarded pin refuses `<family>.release.dependency_not_released`; the common details shape is §14. No version flips.
4. **Prepare outside metadata transactions:** run native structural/source validation and every selected pipeline's fresh release checks against the exact snapshot, using candidate templates/children through a read-only overlay. Selector probes and customer connections also stay outside. Collect per-node gate results; never let a root override reason waive a child's failed check. Proposed `check_overrides` is an array of `{node_id,reason}`, each reason subject to the existing minimum and native policy. Diagnostic check-run records may persist on refusal; they are not released-version or pointer changes.
5. Open ONE `TransactionTemplate(metadataTransactionManager)` transaction. Acquire the workspace mutation guard, then all version/evidence locks in deterministic family/entity/version order. Re-resolve the full graph, verify identities/statuses/body hashes/pointers/edges against the submitted digest, and recheck principal/context, permissions, posture and evidence freshness. Any changed dependency or pointer is `<family>.release.plan_conflict`, even if someone else independently released it. Do not silently accept a new draft or new closure.
6. In a deterministic dependency-first topological order, invoke each selected dependency's native prepared-release port once, then root last. Strict metadata validation sees children already RELEASED in the same transaction. For a RELEASED node, verify its dependency legality without invoking its release/evidence gate again. Every newly released visualization executes its evidence/row-lock/mechanical gate in this transaction. Every CAS guards the node's exact version and consented body hash. No customer connection is opened here.
7. Write one strict audit row per actual flip, in the same transaction and release order. Commit once. Return the root's existing family response plus `dependencies_released` in release order and its native warnings/gate summaries. The dialog redirects only after commit. Any failure through step 7 rolls back all flips, pointers and release audit rows.

Shared nodes are released once even through diamonds. Different versions of one entity are distinct nodes; only its actual one DRAFT can be selected. If the root or a node fails native validation/evidence, preserve its existing catalogued refusal and add a safe `node_id`/visible path context. Never turn a visualization `tests_red` into a generic unchecked-pin error or automatically create GREEN evidence.

### 4.2 What is transactional today

| Family | Actual base boundary and call port | Proposed adapter contract |
|---|---|---|
| Pipeline | S07:208–228 uses injected TransactionOperations; S21:107 wires metadata manager's template; check gate outside | `PipelineReleaseParticipant.prepare/commitPrepared` splits the native path; child pipeline and template ports use exact version/hash. |
| Template | S17:62–101 has no release annotation; `releaseDraft` is one JDBC statement (S35:1488–1503). `TemplateReleaser`/`releasePinned` joins caller transaction, reads current draft hash (S08; S17:121–143) | `TemplateReleaseParticipant` keeps native validation/flip and adds import closure; replace current-hash pickup with consented hash at commit. |
| Parameter set | S09:181–189 programmatic transaction; source revalidation outside; S22:99 wires manager template | `ParameterSetReleaseParticipant` uses native revalidation outside and exact flip inside. |
| Visualization | S10:103–110 programmatic transaction around evidence/cascade/flip; S23:142 wiring | `VisualizationReleaseParticipant` retains gate inside; no bypass through repository-only flip. |
| Dashboard | S11:94–103 validates strict sources before its current visualization transaction; S23:161 wiring | `DashboardReleaseParticipant` prevalidates snapshot and runs strict validation after dependencies flip inside the coordinator transaction. |

All participant/guard/coordinator names in this table are **proposed ports**, not existing classes. Declare family-neutral contracts in `pipeline-contract`, assemble adapters/coordinator in `web`, leave family-owned validation in its current module. No inverse module dependency is needed. The production manager is `metadataTransactionManager`, a `DataSourceTransactionManager` over the metadata DataSource (S24:49–55). Never use customer, staging or a newly created manager; never use REQUIRES_NEW, async writes or a directly constructed no-transaction default in production.

The app's metadata database is PostgreSQL (S47:12–16). H2/PostgreSQL customer-datasource fixtures exercise outside-transaction preparation; they are not two metadata stores to coordinate. Rollback proof must use real PostgreSQL and production wiring; an in-memory H2 or direct-transaction service double cannot prove that join. Proposed proof classes, one per root: `PipelineDependencyReleaseRollbackE2eTest`, `TemplateDependencyReleaseRollbackE2eTest`, `ParameterSetDependencyReleaseRollbackE2eTest`, `VisualizationDependencyReleaseRollbackE2eTest`, `DashboardDependencyReleaseRollbackE2eTest`. Their non-vacuity assertion observes a dependency already flipped before planting the root failure, then verifies committed state after rollback.

Merely wrapping today's DashboardService.release is insufficient: it refuses draft sources before opening its transaction; it calls visualization release without transform consent; releasePinned reads a fresh hash; native pipeline checks would open customer connections if run within an outer dashboard transaction. These require explicit prepare/commit integration, not nested calls plus a larger Boolean.

### 4.3 Concurrency and rollback proof

**FROZEN:** pin saves/updates, create/name reuse, purge, discard, restore, pointer switch, import/promotion receive and releases must participate in the same workspace metadata mutation guard. Visualization evidence start/completion writes that can change the latest-run verdict also obey guard-before-candidate-lock ordering; release rechecks that current verdict, not only a stored GREEN Boolean. Locking only the versions being released does not serialize an inbound reference created during a purge, and holding a repeatable-read snapshot does not prevent a concurrent discard. Recheck inverse pin guards while holding the guard. Customer probes are never inside it. Import's preparation remains outside, as with #302.

Without this writer cooperation the documented preflight race remains (S27:442–448); a build may not label that design concurrency-proof. Other workspaces remain independent. Bound lock acquisition to a recommended 5 seconds and return proposed `<family>.release.busy` on exhaustion, without retrying customer checks invisibly.

Proofs (future): native #205 trigger-plant mould (S31:33–51), after a dependency has actually flipped, force the root flip to throw; assert every status, body hash, current pointer and audit row equals before. Remove the coordinator transaction and require RED on those committed-row assertions, then restore GREEN. Cover template-root/import, pipeline-root/child/template, set-root/template, visualization-root/template and dashboard-root/all families. S30:266–283 and S32:23–81 are existing pipeline/visualization witnesses, not proofs of the new generic system. Add deterministic two-connection races: draft edit, dependency discard, inbound pin versus purge, two overlapping releases, and evidence completion versus release. The loser conflicts/refuses; no successful parent can pin an ineligible dependency. Assert a different workspace can proceed.

## 5. Permissions and the promoter lens — question 5

**FROZEN:** a cascade requires the caller's native `*.release` permission for the root and **each version actually released**. Parent release is not delegated authority to mutate other families. RELEASED dependencies need read admission for the interactive plan, not release permission. Existing family authoring/posture guards run as well. Today the five release rows have identical role cells, so this grants no new capability; check per family anyway to prevent future drift. Dashboard execution's D50 delegation is a separate policy and is not a precedent for release.

### 5.1 Existing release rows, quoted cells

The cells below are the complete auth §7.6 header and five rows at S25:873/881/894/902/911/919. Permission identifiers live in S26:31/44/57/75/83. A promoter never releases; the legacy prose calling the releaser “promoter” describes an author-side action incorrectly.

| Permission | viewer | author | promoter | ws_admin | super_admin | api_caller | promotion_receiver | mcp:author | mcp:promoter | mcp:ws_admin | dashboard_viewer |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `pipeline.release` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ |
| `template.release` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ |
| `parameter_set.release` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ |
| `visualization.release` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ |
| `dashboard.release` | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✓ | ✗ | ✓ | ✗ |

Every graph arrow's cascade intersects its parent row with every selected dependency row above. Thus each proposed pipeline→template/child, template→library, set→template, visualization→template and dashboard→set/source/visualization/transitive cascade has cells **✗ / ✓ / ✗ / ✓ / ✓ / ✗ / ✗ / ✓ / ✗ / ✓ / ✗**. No permission strings are minted. MCP keys' permission cells do not mean their credential may call session-only dialog routes or that a release tool exists.

### 5.2 New surfaces and their guards

All five families get their own routes, avoiding a dynamic family path whose annotation cannot name one static permission. `F` is one of `pipelines`, `parameter-sets`, `visualizations`, `dashboards`; templates use names in query/body, never slash-containing names in a path segment.

| Proposed surface | viewer | author | promoter | ws_admin | super_admin | api_caller | promotion_receiver | mcp:author | mcp:promoter | mcp:ws_admin | dashboard_viewer | Permission/transport |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `GET /api/v1/F/{id}/dependencies?version=N`; template twin `GET /api/v1/templates/dependencies?name=&version=N` | ✓ | ✓ | lens | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | Family READ; session-only. MCP gets the same data through its read tool. |
| `GET` and `POST /partials/F/{id}/lifecycle/release`; template twin `/partials/templates/lifecycle/release?name=` (name on POST body) | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | Family RELEASE; session-only. Four pairs exist for pipeline/template/visualization/dashboard; add parameter-set pair. |
| Existing REST release routes, with plan payload | ✗ | ✓ | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | Existing family RELEASE and session-only human lifecycle contract; preserve key-kind confinement. |
| Optional tree on each existing MCP `*_get` | n/a | n/a | n/a | n/a | n/a | ✗ | ✗ | ✓ | lens | ✓ | ✗ | Existing family READ on `/mcp`, key-role and per-node lens applied. No release act. |

Surface transport restrictions specialize the permission table. The `dashboard.read` permission has `dashboard_viewer: bound` at S25:913, but that credential is confined to runtime/refresh routes; it cannot call the proposed authoring dependency read or MCP. A viewer may inspect the graph, with `can_release:false`; they cannot open a release dialog or submit it.

**Guard for every row:** actual `@RequiredScope(Permission.FAMILY_READ/RELEASE)` plus `LifecycleVerbs.requireSession` for session routes; MCP `McpToolCatalog.Entry.permission` remains the read row. `RoleWalkE2eTest` walks all member/key columns; `ScopeMatrixSpecDriftTest` compares cells/permissions; `MatrixRowReachabilityTest` binds documented routes to handlers; `ReadFloorTest` verifies the read chooses the lowest row; `WorkspaceIsolationSweepTest` and `PromoterLensSweepTest` verify absence. Land handler, Surfaces cell and walk expectation together. AGENTS.md's RestOperation/MCP_TOOL_MIN_* names are obsolete at this base; use the actual Permission/catalog seam, as the dashboard spec itself records.

### 5.3 Projection and refusal

Apply a separately admitted READ lens to **each family/version** before reading its metadata into the public tree. A hidden root is the existing family 404. A visible root with an unreadable dependency returns visible nodes only, no hidden edge/name/path/version/hash/status/count or number of hidden blockers; report one generic `incomplete:true` indication via `complete:false`. `tree_hash:null`, `can_release:false`; no selective digest over hidden content. An unreadable promotion target fails closed as the existing lens does.

A promoter who submits a release is refused `auth.role_required` before dependency lookup; the refusal identifies required root permission, never a source. On an incomplete dependency read, the message is “The complete dependency graph is unavailable in this view.” Do not label a hidden node MISSING or list which family is hidden. Authorized authors with the whole view see and may fix the named blocker.

#344 explicitly exempts **export/promotion bundles** from dependency lenses (S25:1690). Preserve that existing, root-lensed transfer rule; an interactive release tree/read-tool projection is not an export bundle. §12 applies that exemption only to transferring an admitted RELEASED root. §18 records that existing dashboards_get's direct dependency projection is not the new tree's privacy oracle (S34:120–148).

## 6. REST and partial contracts

**FROZEN:** keep existing release URLs: pipeline `POST /api/v1/pipelines/{id}/release`, template `POST /api/v1/templates/release`, and `POST /api/v1/{parameter-sets|visualizations|dashboards}/{id}/release`. Root `If-Match` keeps its current family parser and required-hash error behavior. New JSON fields are `tree_hash`, `include_dependencies` (node IDs), and optional `check_overrides` (§4). For template release merge these fields into its existing name-bearing request; do not replace the name with a UUID path.

```json
{"tree_hash":"preview-digest","include_dependencies":["n1","n2"],"check_overrides":[]}
```

Existing query aliases `release_pinned_templates` and `release_pinned_visualizations` remain scoped to their **old direct edges**, never mean “release all families.” False/absent consents to nothing. True selects the corresponding direct pinned drafts in a supplied plan; every additional reachable draft still needs selection. Supplying both a true alias and explicit selections is `<family>.release.plan_invalid` to avoid two authorities. Aliases true without a preview `tree_hash` refuse proposed `<family>.release.plan_required` (428). This is a deliberate tightening for old opt-in clients, requiring owner ratification (§17.6); absence of consent retains the old pipeline default refusal bytes (§14).

For a non-cascading old request with no plan, only root may flip; require the complete closure already RELEASED, and validate it under the same writer guard. Dependency lookup failures keep native validation order in this compatibility path. Direct-template-only pipeline fixtures retain their original code/message/details/order. New generic requests get the shared dependency refusal. No unauthenticated route or promotion-receiver release consent is added.

Partials render one shared `release-dependencies` fragment/model inside each family's existing lifecycle shell. Existing field names `bodyHash` (pipeline/template) and `body_hash` (visualization/dashboard) are preserved at their controllers; generic fragment uses `tree_hash` and repeated `include_dependencies`. Parameter-set pair uses `body_hash`. Submit adapters normalize to the one plan contract. Session POSTs require the normal CSRF token; no new exemption. Refusals render within the dialog with the current selections preserved; do not redirect onto a success page after a conflict.

**Build choice:** bind a bounded typed JSON request or equivalent form adapter; never accept client-supplied bodies, statuses, arbitrary family names, entity URLs or topological order. Successful family response keeps its existing fields, warning/caller_output semantics, cascade arrays and redirects; append the generic result list. A stale plan is not retried automatically. Reopen/reload explicitly refreshes its tree and checkboxes.

## 7. MCP and the authoring manual — question 9

**FROZEN:** no release/switch/promotion tool. S33's catalog contains **61 entries** and zero names ending `_release` or `_switch`; its surface drift test derives its tool count from the catalog. The server instruction “no tool releases or promotes anything” remains (S37:5).

**Recommendation:** add optional `include_dependencies:boolean=false` to existing `pipelines_get`, `templates_get`, `parameter_sets_get`, `visualizations_get`, `dashboards_get`. With false/absent, their existing response is byte-equivalent; with true, append `release_dependencies`, the same graph projection as §2/§5 for the tool's **returned exact version**. Do not add a sixth tool, execute checks, evaluate selectors, issue a release credential or expose a submit capability. The returned graph reports lifecycle prerequisites, not a claim that fresh pipeline checks or visualization evidence will pass.

MCP author/admin keys read admitted draft/released graphs, but `can_release:false` in this transport. Promoter keys get the released working root and projected admitted versions only; no pending draft metadata. `tree_hash:null` for all MCP responses: a person obtains fresh consent through their session dialog. MCP can explain visible draft nodes and say the human release includes them. The fixed 61 tool names remain; tool schemas, descriptions, response goldens and served manual are updated deliberately and drift-tested.

The build lane's manual sentence: **“Drafts may pin live draft dependencies while you develop. A person's release dialog shows the complete dependency graph and offers its pinned drafts; every required draft must be included. No tool releases or promotes anything.”** Update the rendered manual's source, export and golden together, not a generated copy. This research lane adds no skill resource.

## 8. Runtime — question 8, held input resolved

**FROZEN by #459, not an open choice:** draft dashboards run draft/live dependencies only when the caller is a session user reading an explicitly selected DRAFT through the whole-view lens. Ordinary published/current dashboards remain RELEASED-only; narrowing lenses do not admit DRAFT versions; keys naming an explicit version are refused. No MCP tool/schedule/import path gains the runtime draft mode.

The brief's four unconditional NOT_RELEASED checks have already changed: the base resolver computes `allowDraft` at S18:94, checks visualization/transform/set through `admitted` at 104/108/117 and source at 131; `admitted` at 173–176 accepts RELEASED or a draft only in that mode. Source read-only admission remains at 132. Draft dependency hashes include nested pipelines/templates/imports (158–165). Keep the shared runtime, never a second renderer or a workspace-mode config switch.

New release changes no runtime admission flag. After release, refresh obtains the served RELEASED graph; missing/discarded references still fail closed with `dashboard.runtime.dependency_missing`, and stale configuration with `dashboard.runtime.configuration_stale`. Preserve stream-time nested read-only admission from #459. Tests must include a draft child changed to a business write after configuration read and a template/import edit at the same version. C1 in §18 records the superseded R1 and distinguishes already-landed behavior from proposed release work.

## 9. The UI — question 10

**FROZEN:** one generic release content fragment using the existing lifecycle container, focus/close behavior and `lifecycle-dialog.js` delegated consent evaluator (S19:103–159). Family release handlers remain statically permission-gated. One DRAFT dependency → one selection, checked by default. RELEASED rows have status chips and no checkbox; MISSING/DISCARDED rows show a blocking explanation. The root appears as the version being released, without its own include checkbox.

Render folder-path names and `@version`, family/status chips, incoming pin paths and a bounded tree/DAG view. Shared nodes get one selectable row with references from other branches; no duplicated checkbox can disagree about one version. Collapsing a branch changes presentation only, never consent. A selected DRAFT ancestor's reachable drafts remain required. Hidden dependency branches are omitted per §5, without counted placeholders. Use escaped server text and design tokens; no HTML from names, SQL, template bodies or diagnostic messages.

For the pipeline's old direct-template-only case, keep the exact checked-by-default “Also release this draft template with the pipeline” / “Also release these N draft templates with the pipeline” group label, shared-pinner wording, RELEASED-pin rows, needs-review rows, checks/override disclosure and release result/flash. Back that group by the per-node selections; its master checkbox toggles all of its eligible template rows. Per-node include checkboxes are the ruling's intentional visible addition when multiple drafts appear; the brief's “no visible change” cannot literally hold for the old single-group layout and the new per-node requirement. No other pipeline behavior changes (§18 C8).

Keep `data-consent-input`, `data-consent-submit` and the single evaluator that composes consent and override minimum length. Any unticked draft disables the button; a forged POST still reaches the server refusal. Needs-review markers remain nonblocking warnings; they never count as missing release evidence. Root and dependency hash tokens are fixed to the preview; replacing a checks footer must not replace them with current hashes. For child pipeline overrides show the particular node's gate/reason, not a single override-all control.

Every family's Versions tab opens this content for its exact working draft. Tabs remain navigation only; viewing a version never switches the pointer or releases it. Pipeline/template/visualization/dashboard shells are migrated, not duplicated; parameter sets gain the missing human release shell. #398/#399/#400 are already CLOSED at this base, so no held workspace lane is blocked retroactively. Their current workspaces are consumers of this future shared content.

Browser acceptance (future, both themes; narrow and desktop viewports): nested all-draft graph; shared diamond with one checkbox; untick/retick and master toggle; forged POST refusal; stale same-number child body; pipeline checks footer OOB replacement; needs-review nonblocking; child gate refusal and exact override; missing/discarded imports; promoter hidden-vs-absent parity; viewer controls; close/reopen without stacked listeners; accepted submit redirects once to the exact released version's Versions tab, stale submit stays in place. Reuse the existing ReleaseCascadeBrowserTest as a baseline fixture; extend behavior through the real browser, not only the JS evaluator.

## 10. Visualization evidence and family gates

**FROZEN:** release consent is not test evidence. Existing visualization gate locks the exact DRAFT, requires the latest run GREEN for the exact body hash, honors expiry/open/stale/red failures and re-runs the mechanical check (S38). A prior GREEN run followed by a RED run refuses. Releasing a transform does not itself change its body hash; an edit at the same version still conflicts with the plan and must also be judged by native mechanical validation.

Pipeline root and child checks run fresh outside metadata transactions against the planned graph, independently per node. Save-time permissiveness is used only to validate a prepared candidate graph; final metadata validation requires every exact child RELEASED after its predecessor commits in the shared transaction. Set source validation/probes use the same prepare discipline. Gate errors preserve their catalog and path; generic topological traversal cannot skip them or report success after swallowing a failed native gate.

**Build choice:** prepared result representation; **frozen validity:** prepared result binds full node/body/closure snapshot and override decision. Re-read when acquiring the transaction guard. If a probe's external datasource data changes after it runs, existing checks remain point-in-time evidence; do not promise distributed atomicity with customer databases. No new arbitrary expiry number for pipeline checks is proposed here.

## 11. Audit and diagnostics

**FROZEN:** exactly one existing family `*.version.released` event for each version actually flipped, dependencies first, root last, with the originating actor/workspace and existing `via` word (`session`; no `mcp` release via). No row for an already RELEASED dependency. Details contain family identity/name/version, `scope:version`, `release_id`, root provenance and safe immediate-parent locators; no body, row data, selector value or SQL. `release_id` correlates the one transaction, not a durable authorization token.

Keep existing direct cascade keys (`templates_released`, `visualizations_released`, `cascade_from_pipeline_id`, `cascade_from_parameter_set_id`, `cascade_from_visualization_id`, `cascade_from_dashboard_id`, `cascade_from_version`) as appropriate; add generic `dependencies_released` on the root and `cascade_root:{family,id,version}` on every cascaded row. A shared node still has one event even if several parents reach it. Preserve pipeline `caller_output` and native check-override facts. `scope:version` on release is a proposed additive detail; #372's existing scope word applies to purges and must not be claimed as already present on releases.

**Verified limitation:** current LifecycleVerbs emits after service return (S28:94–98), and AuditLogger's direct insert catches DataAccessException (S39:96–109). Those paths do not prove “every successful cascade has every row” during an audit outage. **Recommendation:** a strict `ReleaseAuditSink` adapter uses the existing audit row shape and awaited JDBC insert in the metadata transaction; insertion failure aborts release. Do not silently change best-effort behavior of unrelated audit events or have both participant and controller emit the same event. The generic coordinator owns these rows; compatibility controllers stop their duplicate release emission when using it.

Audit-read projection filters provenance/summary entries through the reader's per-family/version lenses; never embed a hidden dependency's name or count in a row the caller can read. Internal full audit storage is access-controlled, not a public tree oracle. A permission denial or plan conflict writes no successful-release event. Diagnostic check runs may remain (§4), and redacted operational logs may record an error class/SQLState without serializing refused content. Prove audit failure by a planted insert refusal and count zero committed release rows/flips.

## 12. Twins, promotion and exclusive cleanup — question 7

**FROZEN:** release is the only newly cascading lifecycle verb. Purge/discard/restore/switch remain explicit native acts; do not interpret `include_dependencies` as cleanup permission.

### 12.1 Lifecycle table extended over all inventory edges

Every row below applies to each version-pinning edge in §1, including template imports. Its template/pipeline examples generalize the exact base versioning rows (S27). Family error spelling is chosen by the **target** version, not by the root that pins it.

| Event / before | Generic result | Code and base row generalized |
|---|---|---|
| Release `1D`, all dependencies RELEASED | `1R`, current=1; same version/hash/pins | Native not-draft/hash failures retained; base :322, :340. |
| Release with any required draft not consented | No flip/pointer/audit | `<family>.release.dependency_not_released`; the old template-only default (`pipeline.release.template_not_released`, base :341) is replaced by `pipeline.release.dependency_not_released` (§14 map, ruling row 2). |
| Release with all drafts selected and native gates passing | Exact dependency versions then root in one transaction | Native gate codes / plan conflict on failure; base :342 expanded to the whole closure. |
| Purge draft version, other versions remain | Refuse live exact inbound pins; otherwise delete only named draft | Pipeline `pipeline.version.pinned`; template `template.in_use`; set `parameter.in_use`; visualization `visualization.version.pinned`; dashboard has no incoming artifact pin. Base :344–345 and arrows :407–410. |
| Purge sole draft/entity | Refuse protected stored inbound references; otherwise entity gone; never cascade deletion | Same target pin codes; `*.version.last_release` if a RELEASED version exists. Base :325–327/:335–336 and :414–428. |
| Discard RELEASED version | Refuse live exact inbound pins, otherwise status X and native pointer fallback | Same target pin codes; `*.version.not_released` for draft. Base :328/:334/:346. |
| Restore DISCARDED | Require its outbound closure RELEASED before restoring; no automatic restore/release of dependencies | Family existing dependency validation codes; new neutral `<family>.release.dependency_not_released` is **not** a restore code. Pipeline strict composition/template validation, set selector/template validation, visualization transform binding validation, dashboard dependency/source validation; base :430–434 generalized. |
| Switch current version | Native eligibility/posture rule; outgoing exact pins unchanged; no dependency switches | Native `*.version.not_eligible`; base :329–330/:338/:347–348. Dashboard served runtime continues to require RELEASED. |
| Exclusive-draft cleanup during pipeline entity purge | Keep `include_exclusive_draft_templates`, false default; only eligible exclusive draft-only templates; recheck all pinners under writer guard | `template.in_use` on concurrent pin; base :279–292; unrelated to release inclusion. Add importing-template arm to exclusivity evidence. |
| Promote/export admitted RELEASED parent | Carry every exact reachable RELEASED version; no release on sender/receiver | Root native not-released and proposed `<family>.release.dependency_not_released` for an invalid closure; base spec §12 / versioning §10.4, expanded deliberately. |
| Audit | One existing family event for each actual release, original via; scope word per §11 | S29:464–473; #372 scope provenance clarified, not a new event code. |

The current twins are not perfectly symmetric: S27:420–428 states parent pipeline entity protection stays live/exact, template/set entity guards use historical versions, and visualization entity guard counts LIVE dashboards only. **Recommendation:** standardize entity deletion protection to every stored pinning version of a dependent that still has a live version, plus restore-time outbound revalidation; nonsole draft purge and discard stay live/exact. An entirely discarded dependent must still fail clearly when restoring after an explicitly allowed target removal; it cannot resurrect a new entity under a reused name. All inverse arms, especially template imports, must be covered. This tightening is a ratification choice (§17.7), not an assertion about today's scans.

### 12.2 Promotion bundles

**Verified:** dashboard export contains visualization envelopes and set/source references rather than set/pipeline bodies (S40:74–102). TemplateBundleAdapter walks template imports and deduplicates, but an absent lookup is skipped by `visit` (S41:64–72); do not reuse that silent skip as the new closure validator. The old dashboard spec explicitly assumes sets/pipelines promoted first (§12).

**Recommendation:** version the transfer envelope capability to include the whole §1 closure in family arrays (`templates`, `parameter_sets`, `pipelines`, `visualizations`, root dashboard). Exact numbers/hashes preserved; a root's saved body unchanged. Compute closure at sender from exact pins, under #344's root-only lens rule; validate every node RELEASED. A released parent with a draft/missing/discarded import fails, not an auto-release or skipped dependency. A DRAFT root remains its native promotion not-released refusal. Same-version/same-hash target entries can be omitted from transmission, but are explicit manifest requirements and rechecked at receive.

Receiver validates the complete shape, hashes, required datasource capabilities and closure; stages source/selector probes outside the metadata transaction through the existing overlay discipline. It applies dependency-first in one metadata transaction under the writer guard, with the existing import/receiver authority, ID-conflict and pointer rules. Existing newer/different target content follows native conflict rules; never renumber to make a dependency fit. No fallback to a legacy receiver that cannot carry set/pipeline closure: negotiate the envelope capability and refuse with proposed `<family>.release.bundle_unsupported` before sending any bodies. Interactive release consent does not travel as promotion authority. A migration compatibility test imports an existing legacy envelope according to its existing rules; the new full-closure envelope must be explicitly recognized, not silently ignored.

## 13. Security

**FROZEN:** no new network egress, customer credentials, remote URL dereferencing or live deployment. Resolve only typed local workspace pins. Enforce budget while enumerating; no per-path exponential expansion, recursive stack growth or unchecked client IDs. Cache a dependency once by exact identity, never solely by name; do not cache draft bodies across requests.

Enumeration prevention applies to tree, common refusal details, shared-pinner summaries, audit projections and MCP responses. Name-hidden and absent cases compare byte-for-byte for the same caller; no hidden counts, digest, status or timing-dependent additional metadata query beyond the permitted bounded lookup. Root foreign-workspace responses keep existing 404 behavior. The #344 transfer exception is explicitly separate (§5.3), with tests on both sides.

Session dialog/REST POSTs use `dp_csrf` and `DP-CSRF-Token`/normal form token, as S42:75–88 configures; a missing/invalid token refuses before mutation. Plan GET/MCP reads are nonmutating. Existing key-kind route confinement remains even when the permission matrix would admit a key's role. UI uses `th:text`/escaped attributes, one shared consent evaluator and design tokens. `If-Match` protects root; the full recomputed `tree_hash` protects **every** dependency body/version/status/entity resolution/pointer and topology under the metadata guard. The consent does not drift to a newer body or a name-reused entity.

Depth, node/edge count, details count, byte and lock-wait bounds are §1.2/§4.3. Bound the browser DOM too; canonical nodes plus bounded branch references, never a fully expanded path tree. A dashboard with N visualization occurrences and M templates may share nodes; work is bounded by vertices+pin edges and longest-path propagation, not N×all recursive paths. Proposed limits need catalog/config/reader tests and 1-at-limit / 1-over tests. Fail-closed budget errors do not show a truncated but submittable plan.

Release audit writes are strict and transaction-bound, without body content; unavailable audit storage refuses release. No new best-effort writer or asynchronous audit task. Native gate preparation outside metadata transactions admits no external write beyond what the existing authorized check/probe path already permits; the design claims atomic metadata, not customer-datasource rollback.

## 14. Error codes — five family-prefixed code sets, one shared shape (amended 2026-10-18, ruling §17 row 2)

**FROZEN by the ruling (replaces the neutral-domain proposal kept below the line):** there is no neutral `release` domain and no new §13 subsection for it. Every release-plan refusal is a FAMILY code `<family>.release.<reason>`, where `<family>` is the ROOT of the release request — `pipeline`, `template`, `parameter`, `visualization` or `dashboard` — and lives in that family's existing §13 subsection of pipeline-contract (the one holding its `<family>.release.*` / `<family>.version.*` rows today; a family with no release row yet gets its rows added to its own subsection, never to a shared one). A cross-family refusal carries the ROOT's prefix: releasing a dashboard blocked by an unreleased template refuses `dashboard.release.dependency_not_released`, and the blocker's family is in `blockers[].family`, never in the code. The DETAILS shape is one, shared by the five sets (the table). Constants, catalog HTTP mappings and spec-drift coverage land in the same commit as the code that emits them (S1 for the pipeline set and the shared shape; S2–S5 for their families).

| Reason (`<family>.release.<reason>`) | HTTP | Trigger / details |
|---|---|---|
| `dependency_not_released` | 409 | At least one visible MISSING/DISCARDED pin or unticked required draft. `{root, blockers:[{node_id,family,name,version,status,reason,paths}], blockers_truncated}`; reason `not_included`, `missing` or `discarded`. Root is typed identity metadata, never a body. At most 100 blockers; incomplete-lens reads instead use §5's generic projection, no hidden blocker rows. |
| `plan_conflict` | 409 | Any consented snapshot fact changed; `{reason:"snapshot_changed", node_id?, path?}` only when visible. Reopen the preview. Native root hash mismatch keeps native family version conflict. |
| `plan_required` | 428 | Cascade consent without `tree_hash`; no dependency names. Obtain a fresh session dependency preview. |
| `plan_invalid` | 400 | Malformed digest/selection, duplicate or unknown IDs, unsupported selection or incompatible aliases; bounded field locator, no reflected arbitrary values. |
| `dependency_cycle` | 409 | Active-path repeated exact node; visible bounded node/path trail, never every expanded path. |
| `dependency_limit` | 422 | Complete graph exceeds a configured depth/node/edge/byte bound; `{limit,maximum}` without hidden totals. |
| `busy` | 409 | Workspace guard deadline exceeded; retry requires a current plan, no partial mutation. |
| `bundle_unsupported` | 409 | Promotion peer lacks full-closure envelope capability; no bodies transmitted. The family is the promoted root's. |

The visible unticked-template response example is a 409 with `error.code=pipeline.release.dependency_not_released` (a pipeline root), one blocker `{node_id:"n2",family:"template",name:"test/releases/chart.jsonata",version:4,status:"DRAFT",reason:"not_included",paths:["visualizations[0].visualization/transform.template"]}`. An author sees the node and can retick it; a hidden dependency is never emitted by this shape. Paths are bounded locators on visible edges.

**The old codes are REPLACED, on the old paths too (owner, 2026-10-18, confirming follow-up):** there is no byte-for-byte compatibility path. The owner was told, and accepted, that this breaks existing clients and every test and doc pinning an old code; those change in the same slice as the code. The map:

| Old code (main `354e196b3`) | Becomes | Note |
|---|---|---|
| `pipeline.release.template_not_released` | `pipeline.release.dependency_not_released` | the native message `Template '<id>' version <v> is not released; release the template first.` and the `template_id`, `template_version`, `template_status`, `pins_not_released` details (S07:319–341) are replaced by the shared shape; the earlier "prove by capturing the base response" instruction is withdrawn |
| `parameter.release.template_not_released` | `parameter.release.dependency_not_released` | shared shape |
| `visualization.release.dependency_not_released` | unchanged spelling | its details become the shared shape |
| `dashboard.release.dependency_not_released` | unchanged spelling | its details become the shared shape |

Unchanged, outside the ruling: native `visualization.release.tests_missing/tests_stale/tests_red/mechanical_failed`, `pipeline.release.template_needs_review` (a release WARNING, not a refusal — `PipelineReleaseService.kt:133`), `pipeline.check.failed`, root `*.version.conflict`, body/parameter/type validation, auth/CSRF, posture and native not-draft failures. §3 narrows source/child status validation to strict paths; it does not rename constants.

**Inventory before the switch (S1's brief carries it, by script, for EVERY old code):** `git grep -l -F <code>` over the tree for each of the four old strings, docs included. For one code, `template_not_released`, at `354e196b3` (lander, 2026-10-18 00:59 UTC): docs/pipeline-contract.md, docs/rest-api.md, docs/versioning.md, the parameter-engine and release-dependencies records; `ParameterErrorCodes.kt`, `PipelineErrorCodes.kt`, `PipelineReleaseService.kt`, `TemplateReleaser.kt`, `PipelinesController.kt`, `PipelineLifecycleDialogController.kt`, `partials/pipeline-lifecycle-release.html`; tests `VersionLifecycleModelTest`, `ReleaseCascadeBrowserTest`, `ReleaseCascadeE2eTest`.

**OPEN — the owner's, not asked on 2026-10-18:** whether the replaced codes get a deprecation window (for example one release in which both spellings are answered, the old one marked deprecated in §13) or switch at once. The answer is recorded here, with its date, before #517 is briefed; S1's brief quotes it.

**Guards:** the five families' spec-drift tests cover the new rows; `ApiErrorCatalogSpecDriftTest`, docs-audit check C, HTTP-mapping tests, common failure-shape tests across the five roots, native gate passthrough cases, hidden/absent response equality; and, once the switch lands, a whole-tree guard that the four old strings are absent (`git grep -c` = 0 outside this record's history). Adding rows to a family requires auditing the scanner/catalog family discovery, not assuming a regex recognizes them.

---

*Superseded proposal (kept for the record, not normative): a neutral `release.*` domain in one new pipeline-contract §13 subsection after §13.23, with the old family codes retained byte for byte on compatibility paths. Declined by the owner in §17 row 2.*

## 15. Documents and guards this creates

Each implementation owns its matching code/doc/test amendment together. Every Status/change-log version is **next**, chosen at integration; do not copy today's versions into future briefs. The shared authoritative files are serialized by S1/S6/S7 (§16), not edited concurrently by family lanes.

| Document | Row/section to amend | Status row text / guard |
|---|---|---|
| `versioning.md` | §3 twins/inverse arrows including imports; §5.3 generic release and legacy default; §10.4 closure/capability | **next — #397 dependency closure, consented hashes and metadata transaction; lifecycle edge invariants.** VersionLifecycleModelTest and real rollback/race suites. |
| `dashboards.md` | §3.2 dashboard release; §3.1 visualization gate; §5 runtime mode remains #459; §7 shared dialogs | **next — #397 shared release over sets, pipelines, visualizations and transitive templates.** Validator/gate/runtime mode regression suites. No nonexistent §11 is invented. |
| `rest-api.md` | §5.10, §8.9, §21/§22/§23 release/GET dependency plan; §10 transfer after S7; form adapters in UI docs | **next — #397 plan payload, selection, hash conflict, family compatibility and release projection.** Controller/E2E response goldens, request bounds. §13 is CORS, not the error registry. |
| `auth.md` | §7.6 Surfaces cells for new five READ routes/parameter-set partials; cascade permission intersection; §10 audit; §11A.1 interactive tree vs transfer exemption | **next — #397 dependency reads and native release authorization on every selected family.** RoleWalkE2eTest, ScopeMatrixSpecDriftTest, MatrixRowReachabilityTest, ReadFloorTest, kind/lens/isolation sweeps. |
| `enums.md` | §15 release events: generic provenance/one row, scope word; retained family arrays | **next — #397 transaction-bound cascade release provenance.** Lifecycle audit source/spec drift and committed-row counts; no new event name. |
| `ui-screens.md` | §4.4 pipeline, §4.6 templates, §4.21 dashboards, §4.23 parameter sets, §4.24 visualizations; recheck headings at build base | **next — #397 one release-content contract for every Versions tab.** Browser scenarios in §9/§20, shared consent unit guard. §4.22 on this base is the session-less visualization test preview, not its workspace. |
| `pipeline-contract.md` | §13 next neutral release subsection + retained §13.13/§13.20/§13.22/§13.23 aliases; §12 strict-vs-authoring child rule | **next — #397 neutral release-plan refusals and unchanged legacy defaults.** Constants/API catalog drift in same commit. |
| `configuration.md` | Dependency release depth/nodes/edges/detail bytes/lock timeout keys under one proposed area | **next — #397 bounded release planning and lock acquisition.** Config-key drift, defaults/bounds/readers. Exact dotted keys chosen centrally by S1 and ratified with the contract before other lanes depend on them. |
| `mcp-server.md` and served manual source | Five optional read-tool parameters/response projection, no new tool; §7's manual sentence | **next — #397 read-only dependency explanation; human-only release.** Tool schema/list drift (61 names at research base), manual goldens, docsExport + docs-audit. |

**Audit coverage caveat:** the base `scripts/docs-audit.sh:118` scans `docs/*.md`, DEVELOPMENT and exported manual, **not recursively `docs/superpowers/specs/`**. Run it as required, but do not claim it parsed this design. S43 proves the scan set. This lane separately verifies this document's headings, local links, cited base-tree lines and section/scenario coverage, and invokes the audit's own Python parser with this nested specification added to its scan set (§19 evidence procedure). The parser adaptation is private evidence, not an edit to the product script or another product document; the product fence remains one new spec file.

## 16. Lane cut and tracked implementation work

**FROZEN:** ratify §17 first. S1 lands alone and freezes the graph/plan/ports/serialization/compatibility contract. S2–S5 may then run in parallel on disjoint family-owned participant files and tests. S6 integrates dialogs/read routes/tools and authoritative shared doc rows after the family handbacks. S7 promotion is a separate serial wire change. Research alone neither dispatches nor closes those implementation issues.

| Slice / tracked issue | Fence (implementation proposal) | Base | Acceptance, falsified both ways | Consumer |
|---|---|---|---|---|
| S1 generic algorithm + pipeline migration — [#517](https://github.com/msabiransari/datapipelines/issues/517) | New family-neutral graph/plan/participant/guard ports; web coordinator and pipeline read/prepare/commit adapters; metadata writer guard participation across existing writers; strict release audit adapter; generic constants/config/central contract docs; their tests. No dialog or tool changes. | Latest integration base after ratification; current snapshot is research provenance only | Base legacy direct-template response equality; exact child/template hash mismatch; cycle/diamond/budget limits; trigger-planted rollback and audit refusal; deterministic writer races RED with transaction/guard removed, GREEN restored. | All release participants and S6. Reuses #109/#142 behavior, not another template-only implementation. |
| S2 template/import closure + inverse guards — [#518](https://github.com/msabiransari/datapipelines/issues/518) | Template-owned participant/read adapter files and tests, LibraryResolver/live-status and TemplateUsage import arm, importing-template guard/restore integration; no core coordinator changes | S1 merged | DRAFT library selected releases; unticked/discarded library refuses; importing live draft protects target purge/discard; a stale/reused name conflicts. Remove import edge/status/inverse arm and require RED, restore GREEN. | Every family reaching templates, template workspace already shipped #398. |
| S3 parameter-set participant — [#519](https://github.com/msabiransari/datapipelines/issues/519) | Parameter-set-owned participant/read adapter files and tests, explicit preparation/commit seam | S1 merged; use frozen template participant contract | Selector probes are outside metadata transaction; own template selection and exact hash guard; trigger-plant flips rollback. Remove prep/commit separation or guard and require targeted RED/GREEN. | Parameter workspace #357/#374 consumes S6 later; no reimplementation of existing set→template cascade. |
| S4 visualization participant — [#520](https://github.com/msabiransari/datapipelines/issues/520) | Visualization-owned participant/read adapter files and evidence/native release integration/tests | S1 merged | Latest GREEN only; stale/red/open/expired run refused; mechanical gate rerun and transform hash/rollback proof. Bypass evidence or omit a consented transform and require RED, restore GREEN. | Existing workspace #399, dashboard S5. |
| S5 dashboard participant — [#521](https://github.com/msabiransari/datapipelines/issues/521) | Dashboard-owned participant/read adapter files and tests; strict validation after deps commit; no shared visualization participant file | S1 merged, on frozen S2–S4 interfaces; acceptance waits for those actual integrations | Draft set/source/child/viz/template closure releases atomically; each native gate can refuse all; read-only source child violation refuses. Remove any edge or gate and require RED, restore GREEN. | Existing #400/#459 workspace/runtime. |
| S6 shared dialog + read projection + MCP + document synchronization — [#522](https://github.com/msabiransari/datapipelines/issues/522) | Existing family lifecycle/read controllers and five READ routes, one shared partial/model, lifecycle JS adaptation, family Versions links, five MCP read schemas, served manual; all matching auth/REST/UI/enums doc rows and route guards. Shared authoritative docs edited here serially after S2–S5. No native participant edits. | S1–S5 merged | §20 scenarios in real browser/wire; root and dependency stale preview, hidden/absent equality, CSRF, forged selection, check-footer replacement; 61 tool names unchanged and absent-option response goldens. Revert selected model/hash/role/projection rules independently for RED/GREEN. | All five workspaces; none of #398/#399/#400 is still a blocked lane at this base. |
| S7 full-closure promotion — [#523](https://github.com/msabiransari/datapipelines/issues/523) | Promotion/transfer wire, sender/receiver closure adapters and guards/tests, §12 transfer document rows; no dialog/participant edits | S1–S6 merged; explicit transfer capability ratified | Fresh target receives entire RELEASED dashboard closure, exact versions/hashes; incompatible receiver and draft dependency refuse before send; target write failure rolls every import back. Remove one family/closure check/outer txn and require RED, restore GREEN. | Promotion page/wire; no release or deployment. |

S1 writer-guard changes span existing modules **before** parallelism; inventory every mutation caller and name that fence in its brief. S2–S5 own distinct family adapter/service files and distinct test suites; predeclare their filenames at dispatch. They do not edit shared coordinator/catalog/doc files. Changes requiring a shared file return to the serial integration slice rather than overlapping lanes. S2 and S1 may touch the same existing files at different stages; that is a dependency, not a disjoint parallel fence. Family docs are synchronized before delivery of S6; do not claim their new behaviors normative before the adapters are present. Any defect discovered during implementation is a tracked fixing issue, not an unrecorded exception.

## 17. Numbers and choices to confirm

Every row has a recommendation; already decided draft support/no MCP release is not reopened. **Ratified 2026-10-18 00:55 UTC** ([#397 comment 6243954207](https://github.com/msabiransari/datapipelines/issues/397#issuecomment-6243954207)): rows 1 and 3–12 as recommended (row 4's bounds are validated against real content and at/over-limit fixtures before they freeze, per the ruling's wording); row 2 the alternative, extended, as the row now says. The issue links in §16 are the approved slices, briefed one at a time after the §14 open question is answered.

| # | Choice | Recommendation | Alternative and consequence |
|---|---|---|---|
| 1 | Checkbox default and shared drafts | Every reachable pinned DRAFT selected by default, shared included. Matches #142's default and guarantees a complete graph. | Default unchecked adds deliberate work; exclusive-only offer leaves a required shared pin unreleasable through its parent. |
| 2 | Refusal/legacy transition | **RULED 2026-10-18: the alternative, extended** — family-prefixed `<family>.release.*` codes for new plan requests (the root's family), one shared details shape, AND the existing family codes replaced on the old paths too (§14's map). Consequences the owner accepted: more constants and per-family drift rows; existing clients, tests and docs pinning an old code break and change in the same slice. OPEN: a deprecation window for the old codes (§14). | The record's recommendation — a neutral `release.*` domain with old codes retained byte for byte — was declined. |
| 3 | Cascade authority | Native release permission on each newly flipped version; no parent delegation. | Parent permission alone becomes new cross-family delegated mutation authority and needs an explicit matrix/security ruling. |
| 4 | Bounds | Depth 32, 1,024 versions, 4,096 edges, 100 blockers, 1 MiB wire, 5 s lock wait, all configurable; retain stricter native caps. | Lower bounds can reject an otherwise legal large dashboard; higher bounds increase read/DOM/lock cost. Validate with real content and at/over-limit fixtures before freezing. |
| 5 | Snapshot protection | One complete canonical `tree_hash` plus existing root If-Match; include body/status/entity/version/pointer/edges; no hash on MCP/lensed incomplete trees. | Per-node ETags expose a larger request and require exact-node set validation too; root-only hashes miss mutable dependencies. |
| 6 | Legacy cascade alias without a plan | Keep false/default bytes; true alias requires a fresh tree hash (428), and selects only its old direct edge family. | Accepting true without dependency hash retains the preview/submit edit hole; silently expanding it to the full graph changes consent. |
| 7 | Inverse lifecycle/cleanup | Protect every §1 edge; normalize entity guard historical protection where a dependent still has a live version; restore revalidates outbound closure; release includes shared drafts, cleanup remains exclusive. | Keep inconsistent historical guards and rely solely on restore refusals; smaller change, weaker deletion/name-reuse protection. |
| 8 | Promotion closure | Versioned full-family closure envelope, exact RELEASED pins, negotiated receiver capability; no automatic release. | Keep manual set/pipeline pre-promotion and document that the generic release does not make transfer self-contained. |
| 9 | MCP read contract | Optional `include_dependencies=false` on five existing gets, no tool count change; append lensed read-only projection when true. | New read tool increases surface/catalog/walk count; always appending the graph changes all response shapes/costs. |
| 10 | Write serialization | Database workspace mutation guard honored by every pin-affecting writer, guard before version/evidence locks. | Row-only dependency locks need a complete predicate/reference locking design and proof for inbound pin insertions; JVM/Redis-only mutex insufficient. |
| 11 | Release audit guarantee | Strict awaited same-transaction audit insertion; rollback on audit failure; current best-effort behavior remains for unrelated events. | Keep post-service best-effort audit and explicitly weaken “one row per released version” to an availability-qualified promise. |
| 12 | Pipeline visual compatibility | Keep labels/checks/warnings/flash and master checkbox; add per-node controls required by ruling. | Keep only the old group checkbox, violating per-draft include controls; a totally new pipeline layout adds unrelated visible change. |

## 18. Corrections and disagreements

These C-rows amend interpretations here; they do not edit other design records. Distinguish a stale brief from a product defect.

| # | Earlier statement | Verified correction or proposed replacement |
|---|---|---|
| C1 | #397 brief OPEN INPUT blank; #369 in flight on released-only R1 | #459 explicitly supersedes R1 and is landed. §8 applies the shipped draft mode at S18:94–176; no new owner answer needed on runtime. |
| C2 | Draft dashboard sources and child pipelines refused at SAVE | #459 changed authoring; S13:103 is conditional on explicit strict mode; PipelineService passes draft admission at S44:179. They are still strict at release/import. |
| C3 | No dashboard/visualization release dialogs | #399/#400 shipped their lifecycle controllers/partials; auth rows S25:911/919 list them. Parameter sets remain REST-only for release. Genericize existing dialogs, add only missing parameter-set pair. |
| C4 | Cycles ruled out by released-only composition | Current CompositionRules explicitly admits mutable drafts and bounds cyclic walks (S03:37–42). §1 adds explicit generic cycle detection/refusal; imports have their own path guard. |
| C5 | Existing hash handshake identifies exact tested template content by version alone | Draft is edited in place without changing version; releasePinned takes today's hash (S17:127–143). Root preview hash was fixed by #416/#453; dependency snapshot guards are still needed. |
| C6 | Dashboard spec §11.4: dashboard consent doesn't consent its visualization's templates | Kept as current D61 legacy behavior, superseded **for the ratified generic plan** by separately selected transitive nodes; no evidence bypass. |
| C7 | Dashboard spec §12: sets/pipelines promoted first; parameter-set workspace spec says lifecycle remains REST/MCP and has no proposed release shell | §12 recommends negotiated whole-family closure; §6/§9 adds missing set session release shell under the existing row. C-rows are recommendations until ratified. |
| C8 | Generic per-draft checkboxes and pipeline migration with literally “no visible change” | Old pipeline has one group checkbox (S45:78–99). Per-node controls intentionally add visible selection granularity while preserving labels/checks/warnings/results. Owner choice §17.12. |
| C9 | Research gate's docs-audit “parses headings” of nested spec | At this base S43:118 is top-level only. Run standard audit and a separate scan-set-extended parser invocation plus citation/coverage verification; report the limit. |
| C10 | AGENTS.md requires RestOperation/MCP_TOOL_MIN_*; brief refers to services with no @Transactional as uncertain boundaries | Actual Permission/catalog seam is recorded in the dashboard spec §5; all three programmatic transaction templates are wired to the metadata manager (S22–S24), template release is one statement/caller-joined. |
| C11 | Every dependency edge listed in brief excludes template imports; twins spans every aggregate | LibraryResolver/TemplateImport and TemplateUsage's three-arm type show a same-family import edge without that inbound guard arm. Include imports/statuses/guards and track S2 rather than declare the inventory complete at seven arrows. |
| C12 | Existing per-family release audits already prove durable one-row-per-version | Controller emits after service, logger swallows database failure. §11 recommends strict transaction-bound release audit; #372 scope is a purge fact, not already a release field. |
| C13 | #344 permits whole-view dependencies, so the tree can reuse export or current dashboards_get projection | #344 explicitly governs transfer. Interactive tree must apply its own per-family lens; current dashboards_get can echo a referenced name while status is null (S34:129–147). New projection suppresses that metadata without claiming to rewrite all existing document reads. |
| C14 | Branch work starts on brief's 2026-10-02 tree and named in-flight chain | Instructions say latest origin/main. This research base is 3de621230; all file/line references were rederived here. Foreign dirty main/store work is outside the lane. No gate/merge/push/VPS action is part of this delivery. |

**Added beyond the brief:** import closure/inverse lifecycle gap; explicit cycle handling; guard participation by every writer; name-reuse identity protection; child-specific check overrides and preparation outside metadata transactions; strict audit-outage behavior; lensed digest suppression; capability-negotiated promotion; audit's nested-spec blind spot; native caller_output/evidence invariants and the literal pipeline UI compatibility conflict.

## 19. Source register and research checks

Each S-reference below is a base-tree file:line range, not a citation to the dispatch prompt. Re-anchored 2026-10-18: S09 and S35 drifted with the landings since the base and now cite main `354e196b3` (the ranges hold the same code); S44 is unchanged; every other row is still its base-tree range and is re-read by the slice that cites it. These files/lines were read during research and re-read mechanically against `git show 3de6212304997007373efa11bf5a91aaf49b224b:<path>` for the private evidence ledger. Proposed port names, code names and fixtures elsewhere have no claim to exist at this base.

| Source | Base file:lines |
|---|---|
| S01 | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/ReferenceRules.kt:168–205` |
| S02 | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/PipelineValidator.kt:88–124` |
| S03 | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/CompositionRules.kt:13–151` |
| S04 | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/Pipeline.kt:35–65` |
| S05 | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/PinRules.kt:13–95` |
| S06 | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParameterSetModel.kt:140–148` |
| S07 | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/PipelineReleaseService.kt:39–241,277–344` |
| S08 | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/TemplateReleaser.kt:5–31` |
| S09 | `modules/parameters/src/main/kotlin/co/datapipelines/parameters/ParameterSetService.kt:162–191` (re-anchored 2026-10-18 to main `354e196b3`; was `:161–190` at the base) |
| S10 | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/VisualizationService.kt:85–112` |
| S11 | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/DashboardService.kt:75–105,375–427` |
| S12 | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/VisualizationValidator.kt:140–188` |
| S13 | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/DashboardValidator.kt:37–123,176–247` |
| S14 | `modules/templates/src/main/kotlin/co/datapipelines/templates/TemplateImport.kt:6–28` |
| S15 | `modules/templates/src/main/kotlin/co/datapipelines/templates/LibraryResolver.kt:5–42,165–188` |
| S16 | `modules/templates/src/main/kotlin/co/datapipelines/templates/RepositoryTemplateRegistry.kt:6–61` |
| S17 | `modules/web/src/main/kotlin/co/datapipelines/web/templates/TemplateReleaseService.kt:54–143,278–299` |
| S18 | `modules/web/src/main/kotlin/co/datapipelines/web/dashboards/runtime/DashboardRuntimeResolver.kt:48–190` |
| S19 | `modules/web/src/main/resources/static/js/lifecycle-dialog.js:64–168` |
| S20 | `modules/application/src/main/kotlin/co/datapipelines/application/endpoints/ReadOnlyPipelineRule.kt:15–98` |
| S21 | `modules/web/src/main/kotlin/co/datapipelines/web/pipelines/PipelineLifecycleConfiguration.kt:57–74,87–109,118–145` |
| S22 | `modules/web/src/main/kotlin/co/datapipelines/web/config/ParametersConfiguration.kt:78–100` |
| S23 | `modules/web/src/main/kotlin/co/datapipelines/web/config/VisualizationConfiguration.kt:122–162` |
| S24 | `modules/app/src/main/kotlin/co/datapipelines/config/TransactionConfiguration.kt:36–55` |
| S25 | `docs/auth.md:873–919,1684–1690` |
| S26 | `modules/auth/src/main/kotlin/co/datapipelines/auth/Permission.kt:20–88` |
| S27 | `docs/versioning.md:255–350,399–448,669–755` |
| S28 | `modules/web/src/main/kotlin/co/datapipelines/web/ui/PipelineLifecycleDialogController.kt:51–108` |
| S29 | `docs/enums.md:464–473` |
| S30 | `tests/integration-tests/src/test/kotlin/co/datapipelines/integration/ReleaseCascadeE2eTest.kt:264–283` |
| S31 | `modules/app/src/test/kotlin/co/datapipelines/config/RemoveMemberRollbackIntegrationTest.kt:33–51` |
| S32 | `tests/integration-tests/src/test/kotlin/co/datapipelines/integration/VisualizationReleaseCascadeRollbackE2eTest.kt:15–83` |
| S33 | `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/McpToolCatalog.kt:34–175` |
| S34 | `modules/mcp-server/src/main/kotlin/co/datapipelines/mcp/DashboardsTools.kt:69–150` |
| S35 | `modules/templates/src/main/kotlin/co/datapipelines/templates/TemplateRepository.kt:186–201,1487–1503,1641–1663` (re-anchored 2026-10-18 to main `354e196b3`; was `:174–189,1469–1485,1623–1645` at the base) |
| S36 | `modules/application/src/main/kotlin/co/datapipelines/application/templates/TemplateUsage.kt:30–80` |
| S37 | `modules/mcp-server/src/main/resources/mcp/server-instructions.txt:1–7` |
| S38 | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/VisualizationReleaseEvidence.kt:5–100` |
| S39 | `modules/auth/src/main/kotlin/co/datapipelines/auth/AuditLogger.kt:62–109` |
| S40 | `modules/visualization/src/main/kotlin/co/datapipelines/visualization/ArtifactTransferService.kt:29–104` |
| S41 | `modules/web/src/main/kotlin/co/datapipelines/web/visualizations/TemplateBundleAdapter.kt:13–72` |
| S42 | `modules/auth/src/main/kotlin/co/datapipelines/auth/SecurityConfig.kt:40–88` |
| S43 | `scripts/docs-audit.sh:115–126` |
| S44 | `modules/pipeline-contract/src/main/kotlin/co/datapipelines/pipeline/PipelineService.kt:169–182` |
| S45 | `modules/web/src/main/resources/templates/partials/pipeline-lifecycle-release.html:60–138` |
| S46 | `modules/web/build.gradle.kts:118–149` |
| S47 | `docs/metadata-db.md:12–16` |

Additional source checks: existing specs' §5/§11.4/§12/§17/§18 and parameter workspace §5/§14; five REST release controller mappings; lifecycle controller/partial inventory; root docs packaging allowlist; current doc headings; actual roles/drift/isolation guard filenames; sample-content JSON keys/names. The private evidence records these mechanical inventories and source lines with the base SHA. Github issue bodies/comments were read for #397, #369, #459, #205, #372, #396 and #462, plus duplicate searches for the follow-ups. No session transcripts or other lanes' progress were polled.

Research verification: run the served manual exporter, standard docs-audit and a scan-set-extended parser invocation; check source-register ranges exist and reread them from the base; check local links/heading uniqueness/sections/scenarios/roles; `git diff --check`; one-file diff; commit trailers zero; clean lane porcelain. These prove document consistency/provenance, **not implementation behavior, browser acceptance or owner ratification**. No gate.sh, live VPS, app mutation, merge, rebase or push.

## 20. Acceptance scenarios and demo prerequisites

**Verified demo inventory:** `scripts/sample-data/content/examples.json` contains 20 templates, six pipelines and one parameter set, including `nyc/lib/metrics.sql`, `nyc/mobility/mobility_briefing` → `nyc/mobility/borough_od_matrix`, and `nyc/parameters/geo_filters`. `examples-lake.json` contains templates/pipelines/datasource requirements. Neither JSON has a visualization or dashboard collection. Existing demo content is not evidence of an all-draft release flow.

**Fixture recommendation:** create isolated `test/releases/*` exact-version drafts over the existing sample shapes, with a local H2 datasource, a simple read-only SQL source with declared or observed columns, a child pipeline, SQL selector template/set, JSONata transform and a visualization with genuine current GREEN test evidence. Create a dashboard that binds those columns/parameters correctly. Clone names/content, not live RELEASED rows; set lifecycle through actual create/update APIs. Use the sample imported library shape on a SQL template to exercise imports. Bodies, sample-data edits and new acceptance fixtures belong to implementation lanes, not this record.

1. **Entire draft graph.** Authorized author opens dashboard@3 release dialog. Its set@5, source@7→child@1→SQL template→library, visualization@2→transform@4 appear, all drafts checked by default. A shared library/template appears once with multiple incoming edges. RELEASED versions appear without checkboxes. One submit passes native source/check/evidence gates and releases the full set dependency-first then dashboard; versions, bodies and pins stay exact, pointers move natively, audit row count equals distinct DRAFT nodes including root. Read committed rows and run the served board. Countercase: remove a graph edge or bypass one native gate and the acceptance must fail.
2. **Unticked draft.** Untick transform@4: button disabled; retain names/status/selection and show that dependency is required. Forge the same POST anyway: 409 `<family>.release.dependency_not_released` names visible n2 with `not_included`; nothing flips and no release audit row commits. Retick, submit current plan, all release. Removing client disable alone must not make the server acceptance green.
3. **Dependency moved after preview.** Hold tree/hash, update transform@4's body **without changing number**, then submit. 409 `<family>.release.plan_conflict`, unchanged statuses/pointers/audit, message in dialog. Repeat for child pipeline, import library, pointer switch and purge/recreation under identical name@1. Root-only stale hash instead keeps native family version-conflict. Reopen current plan and show success on a matching snapshot; a fixture that changed version only is insufficient.
4. **Promoter/hidden source.** Read admitted RELEASED root containing a dependency outside this principal's family lens; compare missing and hidden dependency fixtures. Project visible graph only, `complete:false`, `tree_hash:null`, `can_release:false`; no hidden name/version/path/status/count. Release GET/POST refuses root role before any pin metadata read. Viewer graph readable but release controls absent. Export the admitted RELEASED root separately to prove #344 still carries its pinned transfer closure.
5. **Pipeline baseline.** Run the current direct-template-only release dialog fixture including two templates, a shared pinner, released pins, needs-review warning and failing checks with a native explicit override. Compare labels/results/check behavior/legacy no-consent response to the observed base, allow only the intentional per-node controls and new hidden snapshot fields. True consent releases templates then pipeline; no selected dependency, hash or gate is taken from a newer read.
6. **Twins and transfer.** A second live pipeline/set/viz/importing-template pins a candidate draft: release offers it as shared; exclusive cleanup does not offer deletion. Purge/discard refuse the inverse pin; restore refuses missing/discarded outbound dependency; switch changes only the target pointer. Promote DRAFT root → native not-released; invalid RELEASED closure with a DRAFT import → dependency refusal, no send. Promote valid full closure to fresh target → all exact versions present. Incompatible receiver and late target insert failure leave nothing partially imported.
7. **MCP explanation.** Author key calls existing dashboard get with `include_dependencies:true`: sees all admitted exact draft nodes as a read-only explanation and tells person release requires those pins. Absent option matches existing output. Promoter key gets only admitted RELEASED projection; missing/hidden parity holds, no digest for either. `tools/list` stays 61 names and no release/switch tool; an attempted release tool lookup remains unavailable.
8. **Rollback and audit outage.** For each root family plant a metadata trigger that throws only after a dependency has flipped. Committed statuses/hashes/pointers/release audits all equal before. Then plant audit insert failure and get the same all-or-nothing result. Remove transaction/strict audit handling to prove the tests RED at those persisted-state assertions; restore and prove GREEN. Drop each plant in finally and verify zero remaining fixture triggers.
9. **Bounds/cycle and concurrency.** Real graph replays plus exact budget boundaries: repeated shared imports have one load/node/flip; a pipeline cycle refuses explicitly; a longest path reached second cannot escape depth cap. A blocked second connection edits/discards/saves a pin during release/purge; read final committed state and enforce closure/inverse invariant, not an allowed-either-outcome test. Different-workspace write proceeds. No incomplete plan can be submitted.
10. **Runtime separation.** Explicit session DRAFT preview runs all drafts, parameter option dependencies and transforms, and changes configuration ID on a same-version nested edit; key explicit-version and promoter draft requests retain refusal. After cascade the current board runs only RELEASED versions. A source child becomes non-read-only between configuration and launch: source launch refuses, never executes business writes. Release-tree digest never becomes configuration ID.
