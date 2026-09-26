# Security assurance: entry-point contracts, behavioral tests and delivery gates

**Status:** RATIFIED 2026-09-24 with the amendments of [§13](#13-ratification-amendments-2026-09-24)
(the pre-ratification review's corrections B1–B8 and the owner's decisions P1–P5). Draft 1 was
prepared for review; its text below is kept as written, and every place an amendment changes it
carries an **Amended (…, §13)** marker. Where the draft and §13 disagree, §13 wins.
**Date:** 2026-09-24.
**Owner intent:** make it difficult to introduce an unguarded entry point or weaken an existing
security boundary without an actionable build failure.
**Tracking:** [GitHub issue #217](https://github.com/msabiransari/datapipelines/issues/217);
GitHub remains the delivery tracker.
**Distribution:** contributor design material, not packaged product documentation.
**Source baseline:** `1aac294e9ca21b692e32904465ad1307b4b3efa2` on main. Source inspection only;
this document does not report a test run, a complete security audit or deployed protection.

## 1. Outcome and limits

Deliver a reusable security assurance system around the application's real trust boundaries.
Every supported entry mechanism must be discoverable; every discovered entry must have a reviewed
contract; every applicable contract must have executed evidence. Missing, unknown, skipped or
unreachable cases must never look like a passing security gate.

The system combines runtime refusal, architectural constraints, generated test combinations,
feature-specific behavioral assertions, deliberate guard falsification and mandatory CI checks.
It must protect against mistakes in both directions: unauthorized access and accidental denial
of legitimate use. A system that refuses everything is not correct.

No finite test suite makes security holes impossible. The enforceable objective is to close known
classes of omission and bypass, expose the remaining assumptions and make changes reviewable.
Host compromise, malicious repository administrators and unknown dependency vulnerabilities remain
outside that guarantee. Periodic independent review remains necessary.

This proposal extends the existing architecture; it does not replace it with a new policy engine,
introduce editable roles, grant new authority or silently change an access decision.

## 2. Authority and OWASP baseline

The following project authorities continue to decide product behavior:

- [auth.md](../../auth.md): permissions, roles, credential confinement, public routes and refusal semantics.
- [Permissions and keys design](2026-09-23-permissions-and-keys-design.md): #215, including its
  2026-09-24 amendments. The catalog slice is present on the inspected main; do not assume the key
  identity, scope-removal or dialog slices have landed.
- [Roles design](2026-09-20-roles-permissions-design.md): resource visibility, promoter filtering and lifecycle rules, as amended by #215.
- [Module structure](../../module-structure.md), [development workflow](../../../DEVELOPMENT.md)
  and [repository agreements](../../../AGENTS.md): layering and delivery.
- [Security policy](../../../SECURITY.md): vulnerability disclosure. Newly discovered exploitable
  findings go through private advisories, not public implementation issues or this design record.

Use **OWASP ASVS 5.0.0, applicable Level 1 and Level 2 requirements**, as the proposed verification
baseline. Selected higher-assurance requirements may be added for credential issuance, tenant
isolation and execution containment after review. This is a target, not a claim of ASVS compliance.
ASVS supplies verifiable requirements; the Top 10 alone is not a verification plan.
**Amended (P3, §13):** the target is **OWASP ASVS 5.0.0 Level 2**, with no compliance claim; the
rows needing manual assessment (SQL containment by database privileges, in-process engines,
egress to private ranges, file roots) are listed in the map as outstanding.
Source: [OWASP ASVS repository and versioned materials](https://github.com/OWASP/ASVS).

Matrix-driven integration testing and continuing authorization regression checks are supported by
[OWASP Authorization Testing Automation](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Testing_Automation_Cheat_Sheet.html)
and [OWASP Authorization Regression Testing](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Regression_Testing_Cheat_Sheet.html).
Default refusal and server-side enforcement follow the
[OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html).
The concrete contracts, architecture and acceptance criteria below are this project's proposal.

During implementation, create a versioned verification map in the existing contributor docs home:
ASVS requirement ID including version, applicability, threat/boundary, enforcing component,
automated test IDs or manual procedure, evidence, and linked delivery issue. Verify IDs against
the pinned release; do not invent numbers or track a moving development version. Every applicable
requirement is covered or explicitly outstanding. An N/A needs a technical reason and reviewer;
an outstanding control is not N/A. GitHub owns status; the map is an evidence index, not a second roadmap.

### 2.1 Compatibility contract with the permission catalog work

The permissions-and-keys record is a prerequisite, not material to redesign in this project.
Its PK1–PK9, O1–O3 and A1–A8 rulings must be linked to the following test families:

| Existing ruling | Required assurance behavior |
|---|---|
| PK1 and granularity/O2 | Permission checks independent of role names; actions that share a permission still have distinct scenarios; co-granted permissions are tested separately |
| PK2, PK5 | Service-key authority belongs to the key; its non-login identity has no membership; creator privilege changes do not become usage-time authority; attribution uses that identity |
| PK3, PK4 | No admin authority on keys; automatic MCP key uses current membership with the specified cap, including super-admin membership/no-membership cases |
| PK6, A1, A5 | API role is `api_caller`, server role is `promotion_receiver`; serving keys are path-bound; scheduler role remains undecided; stored/wire role names use `snake_case` |
| PK7, O3 | Type-specific creation permissions and the creator's permission ceiling are checked independently from usage-time authorization |
| PK8, A4 | Transitional permission-to-scope shim is tested only while it exists; target tests require scopes to be removed with slice (b), not retained as a second policy |
| PK9 | Existing keys migrate to correct identities/roles; historical attribution and execution/audit counts survive |
| A2 | Workspace liveness participates in permission resolution; deactivate/delete does not cascade revocation or identity deactivation; reactivation preserves manual deactivation |
| A3, A6 | Identities are managed through keys only; human administration refuses non-human targets with not-found semantics; no invented key-rename behavior |
| C1–C5 | Accepted introspection/test access changes, next-request MCP role changes, admin MCP own-execution visibility, narrowed promotion authority and per-key attribution/concurrency get explicit before/after scenarios |
| A7, A8 | Re-inventory on the implementation base; do not freeze historical catalog/tool counts or reorder the owner's existing implementation lanes |

Treat A1's ban on offering member roles to service keys together with PK4's explicit automatic
MCP-key rule, which still derives viewer/author/promoter authority from membership. Do not read
A1's shorthand as removing PK4. Fable must flag any remaining ambiguity against the owner's
ratified intent before implementation. Likewise verify the creation ceiling against the special
promotion receiver fence; do not invent a super-admin exception to make a fixture pass.

## 3. Inspected foundation and coverage boundaries

These components exist in the inspected tree. Existence is not evidence that their tests passed:

| Component | Current responsibility | What this proposal adds |
|---|---|---|
| `SecurityConfig`, `AuthFilters`, `ScopeInterceptor` in `modules/auth` | Credential processing, CSRF, public-path policy, authenticated default and MVC permission gate | Complete entry inventory, bypass probes, valid requests and effects |
| `Permission`, `RolePermissions`, `ScopeMatrix` | Granular catalog and authorization decision; transitional key-scope mapping | Independent expected policy and isolated permission combinations |
| `PublicPaths`, `PublicPathsTest`, `PublicRouteWalkerTest` | Public-path declarations, documentation parity and public handler inventory | Every registered mechanism and method; new handlers under public globs cannot inherit exposure silently |
| `RequiredScopeCoverageTest`, `MatrixRowReachabilityTest`, `ReadFloorTest`, `MutatingHandlerScopeFloorTest` | Declaration, reachability and permission-floor guards | Exact surface-to-permission contract and interior permission coverage |
| `RoleWalkE2eTest` | Runtime REST discovery and REST/MCP admission checks against auth.md | Successful feature scenarios, data disclosure assertions and denied-effect assertions |
| `WorkspaceIsolationSweepTest`, `PromoterLensSweepTest`, `DeactivationSweepTest` | Existing reusable security sweeps | Contract-linked completeness and lifecycle/context variants |
| `McpServerAutoConfiguration`, `McpAuthFilter`, `McpToolDispatcher` | Separate MCP servlet, credential gate and per-tool enforcement | Protocol-wide inventory and real wire tests beyond MVC |
| `PublishedEndpointServeService`, `EndpointAuthorizer`, `PromotionServerKeyFilter` | Published-path binding and promotion credential fences | Valid bound/unbound/foreign-key scenarios and non-effects |
| `ArchitectureGuardTest`, `verifyModuleDependencies` | Source and module boundaries | Security boundary ownership and forbidden alternative entry mechanisms |
| `scripts/gate.sh`, `.github/workflows/ci.yml`, `.githooks/pre-commit` | Build gates, CI jobs and staged secret scan | Required security verdict, coverage evidence and security-specific contributor feedback |

`RoleWalkE2eTest` deliberately treats reaching a handler with missing entities/invalid bodies as
admission evidence. Retain that cheap broad check, but never label it successful action evidence.
The CI file separates `gate`, `integration` and `cold-verify`; its comments leave requiring the
latter checks to the owner and omit scanners from CI. Repository settings were not inspected.
The committed hook inspected here is a pre-commit secret scan; do not assume a security pre-push
hook or branch protection is installed because documentation mentions one.

## 4. Trust boundaries and entry families

An entry point includes anything that introduces authority, untrusted input or executable work;
it is broader than a controller method. Maintain these families as separate inventory adapters:

| Family | Discovery and enforcement boundary | Mandatory evidence |
|---|---|---|
| REST, UI pages, forms, htmx partials | Running Spring handler mappings; security chain and MVC interceptor | Authentication, permission, CSRF where applicable, resource filtering, exact response/effect |
| MCP transport and tools | Servlet/filter registrations, protocol capabilities, live tool registry and dispatcher | Credential type, tool permission, valid arguments, protocol error semantics; enumerate prompts/resources and other methods actually exposed |
| Published endpoints | Generic serve route plus published-path registry, key bindings and serve service | Workspace, nearest binding semantics, key confinement, read-only execution rule and own-result access |
| Promotion receivers and senders | Receiver route family/filter and promotion application services | Dedicated credential fence, workspace/batch integrity, attribution, no outbound send on denial |
| Login, OIDC callbacks, reset, invitations, logout | Public handlers plus framework-owned security endpoints/filters | Protocol-specific protections; public does not mean unrestricted or exempt from tests |
| Static resources, docs, health, errors | Resource handlers, servlet context, public-path declarations, management endpoints actually enabled | Explicit exposure, no sensitive state, safe rendering, cache and error behavior |
| SSE, async work and result cursors | Stream/result routes and executor handoff points | Authorized subscription/read, workspace-bound cursors, context propagation, redispatch safety and documented revocation semantics |
| Background jobs and future user schedules | Scheduled-method/registration inventory and application service entry | Explicit system-maintenance capability or current delegated authority; no implicit administrator fallback. **Amended (B5, §13):** a maintenance job is inventoried, unreachable from any transport and bounded — not a system capability |
| Outbound connections and local execution | Datasource/HTTP/file/expression adapter registrations and approved factories | Destination/file containment, execution limits, secret handling and no bypass via alternate adapter |

Current maintenance schedulers include retention, stale-execution sweeping and pool reaping.
They need narrowly described system capabilities, not a fabricated user or unrestricted super-admin
principal. **Amended (B5, §13):** they need no capability at all — each `@Scheduled` method is
inventoried with its purpose and bounded-work limit, and none is reachable from a request. The future user scheduler (#9) must declare its key contract when introduced; this
spec does not decide its role.

Inventory servlet registrations, all active security chains and their matchers/order, filters,
handler mappings (including framework handlers), resource handlers, scheduled entry methods and
protocol registries. Include actual HTTP methods, path constraints and relevant media/header
conditions in surface identity. Model framework-generated HEAD/OPTIONS behavior explicitly.

Discover runtime registrations independently of the contract registry. Compare both directions:
an unclassified registration fails, and a contract with no real registration fails. Conditional
features have a declared profile/configuration activation matrix: each supported security-relevant
combination must be exercised or rejected by startup validation. Baselines come from the running
application, not guessed counts. Required families have presence assertions as well as set equality.

Runtime discovery is supplemented by source/bytecode and dependency checks for new registration
mechanisms: servlet/filter/security-chain beans, functional routes, WebSockets, listeners,
schedulers and new server dependencies. Unsupported mechanisms fail review/build until their
inventory adapter and enforcement contract land. Exact sanctioned registration sites are reviewed;
changes inside those sites still produce a runtime inventory diff. A regex sweep alone cannot
prove the absence of arbitrary reflective registration or a new listening socket.

## 5. Security contracts and independent expectations

Use the existing Kotlin/JUnit infrastructure and typed test fixtures. Start without a new policy
language, generic workflow interpreter or external authorization service.

There are three related artifacts with distinct jobs:

1. **Product policy:** auth.md remains the reviewed role-to-permission and credential authority;
   production catalogs implement it, with drift tests in both directions.
2. **Surface contract:** an explicit reviewed mapping from discovered surface to intended action,
   admission permission, credential restrictions, resource predicates and scenario IDs.
   **Amended (B3, §13):** the surface contract IS auth.md §7.6's row; the test-owned artifact adds
   only what the doc lacks, keyed by permission + surface, and a parity test proves the two are one set.
3. **Scenario implementation:** fixture setup, a valid invocation, observable success and refusal
   assertions, cleanup and declared side effects. The runner expands the security dimensions.

The surface contract is test-owned (**amended (B3, §13):** only its additions to auth.md §7.6 are). It must not infer its expected permission from the annotation
or dispatcher entry being tested, nor call `ScopeMatrix.allowed` to obtain its expected verdict.
Likewise the role expectation must not be generated from `RolePermissions`. Sharing identifiers
and request schemas is fine; sharing the implementation's decision as the oracle is not.

Avoid a competing editable role table in the test fixtures: parse the reviewed auth.md policy.
Retain separately reviewed critical invariants and behavior tests so changing documentation and
code together does not silently make an unsafe policy correct. Policy changes require an explicit
access-difference review, including additions and removals for every principal kind.

Each contract contains:

- Stable action ID; surface IDs; activation conditions; security boundary owner.
- Exposure: protected, explicitly public, credential-fenced, or named internal maintenance.
- One admission permission for protected user actions, following #215; additional permissions
  used inside the action are separately named conditional requirements, not an implicit union.
- Credential classes accepted/refused, identity kinds, workspace requirements and key bindings.
- Resource rules, including the intentional absence of an ownership restriction where applicable.
- Valid fixture/invocation and exact transport-specific success/refusal assertions.
- Effects to observe, sensitive fields to exclude, audit requirements and bounded-work limits.
- Applicable reusable probes; explicit reason for each inapplicable probe; no blanket skip flag.

Interior permissions such as `execution.read_all` and `execution.cancel_all` need consumer/scenario
coverage even though no route declares them as its admission permission. Do not force fake routes
to satisfy reachability. Public and maintenance contracts have no invented user permission.

## 6. Enforcement architecture

Retain central credential resolution and central transport admission. Unknown permission, missing
principal, invalid context or undeclared protected action refuses before useful work. Missing
security metadata must fail startup where registrations can be validated, with runtime refusal
remaining as defense against dynamic paths. Production validation reads production declarations;
it must not depend on test fixtures being packaged.

Place resource-sensitive enforcement at the application service boundary shared by its callers,
with workspace predicates at the data access layer. A transport's permission annotation alone
cannot protect an invocation from a scheduler, another service or a new transport. Direct calls
to externally invocable application operations must have behavioral refusal tests too.

For application operations, pass explicit immutable principal/workspace context. If authorization
produces an internal validated context, bind it to actor, workspace, action and resource/version;
keep construction inside the enforcing module. Do not serialize it into an enduring grant or
treat it as a replacement for liveness checks. Test-only principal construction never reaches the
production artifact. This does not mandate authorization wrappers on every private helper.

Architecture guards constrain transports and jobs to approved application entry services and
constrain sensitive adapters to approved factories. Existing intentional direct repository reads
must be inventoried and retain explicit scoping; migration must not assume the current tree
already conforms. New exceptions require a reviewed contract and tests, not a new broad package
allowlist. Keep business code permission-based; role names belong only in policy, identity/role
administration, presentation and migrations, with those exceptions narrowly checked.

Database constraints enforce identity/key-kind/role validity from #215. Workspace-scoped APIs
require workspace context rather than optional filters; global access uses a separately named,
explicitly authorized path. Query, update and delete predicates must enforce scope atomically.
Do not promise database row-level security without a separate design and migration decision.

## 7. Generic test runner

### 7.1 Permission precision

For each action, test its intended admission permission present, absent and replaced with an
unrelated permission. For compound requirements, remove each required permission independently;
for conditional widening, prove both the narrower result and the legitimate broader result.

Use synthetic permission sets through a test-only grant-resolution seam, leaving the production
permission evaluator, transport interceptor/dispatcher and resource enforcement in place. Never
mock an authorization decision to return allow. The seam exists only in test sources, has no
request header/configuration switch, and is checked absent from the packaged production artifact.
**Amended (B4, §13):** the seam's production shape is one interface in `auth` main and ONE
production implementation; the synthetic implementation lives in test sources; a jar-scan test
proves no second implementation is packaged; a Spring profile or property never selects it.
Separate wire tests use actual persisted roles and real credential resolution without this seam.
**Amended (#239, 2026-09-25):** an `mcp` key's member-role admission is decided through this seam
too — `ScopeMatrix.allowed` asks the installed resolver with the key's pinned workspace and member
role, the production implementation answering the member column, so no observable answer changed —
and the isolated-permission witness covers the MCP tool surface again (`PermissionSeamE2eTest`'s
tool leg, red on the pre-#239 matrix). The two transport key-role columns (`api_caller`,
`promotion_receiver`) are NOT behind it: the matrix reads them from the role table at admission,
and the sentence above — separate wire tests use actual persisted roles — stays true for them.

This catches a delete route incorrectly requiring create even when today's roles hold both.
Roles are not ordered privilege levels: author and promoter are incomparable. No numeric rank,
ordinal comparison or assumption that every role inherits the preceding one is permitted.

### 7.2 Real role and credential walk

Expand every active protected surface across all current roles and permitted credential classes.
Derive the role inventory from the actual catalog and assert equality with documented roles and
the fixture registry. Adding a role with no fixture or expectation is red, never silently omitted.
Include anonymous, malformed, expired, revoked, deactivated, wrong-kind and wrong-workspace cases
where applicable. Test mixed credentials and precedence, including an invalid key plus a valid
session: no silent fallback may bypass the documented credential or CSRF policy.

The logical action may have REST, UI and MCP adapters sharing data setup and effect assertions.
Each adapter must be invoked through its real framework, with valid request/argument shapes.
UI visibility checks supplement server enforcement; hidden controls cannot substitute for it.

### 7.3 Resource and data probes

Seed two workspaces, multiple same-role users, distinct service identities and deliberate ID/name
collisions. Probe individual reads, lists, pagination, searches, counts, exports, versions,
results/cursors, nested references and bulk operations. Validate returned bodies and metadata,
not just statuses. A permitted list may legitimately return 200 with a filtered or empty set.

Cross-workspace foreign-resource and nonexistent-resource responses must follow auth.md's
non-disclosure contract, including normalized body and relevant headers. Normalization excludes
only documented volatile fields; it must not erase leaked IDs/messages. Do not assert constant
timing from ordinary integration tests; use targeted analysis for credible timing channels.

Apply same-user/other-user restrictions only where policy requires them. Pipelines are not made
owner-only by this proposal. Execution ownership, promoter visibility and endpoint-key bindings
have their own predicates. Body/query/route workspace IDs and nested resource IDs cannot override
the authenticated context. Mass assignment of role, identity kind, admin state or workspace fields
must be refused or ignored according to an explicit contract.

### 7.4 Valid success and no unauthorized effects

An allowed action must return its expected response and produce its expected persisted/result
effect. A 400, 404, 500 or missing tool is never a successful action scenario. The older admission
walk reports a separate coverage category and cannot satisfy this requirement.

A denied valid mutation must return its documented refusal and leave protected state unchanged:
metadata, customer data, files, queued jobs, execution records, artifacts and outbound calls as
applicable. Expected audit records, denial counters and abuse-control updates are explicit allowed
effects. Assert before/after state and recording sinks; a strict mock that only throws if called
cannot by itself demonstrate that a required call occurred.

Restore isolated fixtures between destructive cases. Await work deterministically, then assert no
late effects after denial/cancellation. Observe production effect boundaries with isolated real
databases/files and controlled network sinks; do not replace the authorization path with mocks.

### 7.5 Credential lifecycle and asynchronous authority

Cover key creation constraints, migration, attribution, expiry, revocation, identity deactivation,
membership removal, role changes, workspace deactivation/deletion/reactivation and cache eviction.
Verify manual identity deactivation is not undone by workspace reactivation. A service identity
cannot use password, OIDC, invitation, reset or identity-administration paths to become human.
Non-MCP service keys use their own authority under #215, not their creator's current role.
Automatic MCP keys follow the member role and cap defined there. Neither receives admin authority.

For every lifecycle change, record the authoritative freshness/TTL rule and test just before and
after its bound with a controlled clock or event synchronization. No arbitrary sleeps. Revocation
must not fall back to cached grants beyond that bound, and authority-resolution failures must not
grant access. Declare how authentication outages are surfaced separately from policy denial.
Specifically, #215 C2 and §3.4 require MCP membership-role changes on the **next request**. A cache
TTL is not permission to weaken that requirement; test warmed caches before the change and the
first request afterward. Where the existing product authority requires immediate invalidation,
the test must require it rather than invent a grace period.
**Amended (B2/P2, §13):** the ratified bound is **within the auth cache TTL (60 s)** — #215's C2 as
amended (record A9) and auth.md §7.3/§11.4; the test warms the cache, changes the role and asserts
the bound just before and after it (215b pinned it: `McpKeyRoleFreshnessTest`).

Before queued delegated work begins or is retried, re-resolve its identity, workspace and current
authority; never replay a stale serialized allow decision. Already-running execution, cancellation
and open-stream behavior need explicit contracts consistent with the executor design. This draft
does not silently promise immediate cancellation of all in-flight work on revocation.
**Amended (P4, §13):** running executions keep their authority to completion; open SSE streams are
cut at the next event after revocation; auth.md states both. The stream half is not built on the
ratification base (a stream is authorized once, when it opens) — tracked in
[#230](https://github.com/msabiransari/datapipelines/issues/230). New result
reads always undergo current authorization. Test context isolation across threads/coroutines and
concurrent users, plus ASYNC/ERROR redispatch without allowing a fresh unauthorized operation.

### 7.6 Practical coverage without combinatorial explosion

Exhaust role-by-surface admission decisions and each declared credential fence. Exercise each
required permission independently and each resource predicate at its boundary. Pairwise sampling
may supplement secondary combinations; it cannot replace a named security invariant. New roles
reuse action scenarios automatically; new actions supply fixtures and business-specific assertions.
An adapter claiming equivalence to another adapter still needs wire evidence for its own boundary.

## 8. Security beyond authorization

Each family attaches the following applicable probes and existing tests to the verification map.
These are required assessment/delivery areas, not claims of existing protection.
**Amended (B7, §13):** the rows are ordered by this product's real blast radius — user-authored
SQL against customer datasources, in-process engines, datasource egress to private addresses,
file roots, key issuance and tenant isolation first; slice D takes them in that order, and the
rest of the ASVS L2 map is "outstanding, tracked", not slice-D scope. The rows below are reordered accordingly
(the draft listed authentication first); tenant isolation is §7.3's resource probes:

| Boundary | Required classes of checks |
|---|---|
| Files, SQL and expression execution | Traversal/canonicalization/symlinks, file roots, JDBC URL and driver restrictions, in-process engine privileges, bounded CPU/memory/concurrency, cancellation and cleanup; user-authored SQL is an intentional capability whose configured database privileges must remain the boundary |
| Outbound connections | Configured destination policy at use time, redirects/DNS changes, metadata and loopback access, TLS verification, timeouts and bounded responses; customer databases may be private, so do not impose a public-address-only rule that breaks the product |
| Authentication and browser state | JWT signature/algorithm/issuer/audience/lifetime, OIDC state/nonce/PKCE and account linking, reset/invitation replay, session rotation, secure cookie behavior, CSRF, credential precedence and login abuse limits |
| Untrusted input and rendering | Schema/size/depth limits, stored/reflected/DOM XSS including JSON embedded in HTML, template evaluation containment, unsafe deserialization, SQL/command injection and mass assignment |
| Responses, caches and audit | No secrets/raw sensitive payloads in errors/logs/audit, workspace/identity-aware cache keys, no authenticated response cached publicly, stable attribution, safe log fields and documented audit-failure behavior |
| Proxy, protocol and deployment | Trusted forwarded headers, HTTPS redirects, CSP/CORS, allowed methods/content types, cookie behavior behind the real proxy shape, management-port exposure, debug settings and secure startup validation |
| Supply chain and delivery | Secret/dependency/container scanning, pinned tools/actions, migration constraints, least-privileged CI, no untrusted PR execution with repository/deployment secrets |

Use browser tests for browser-enforced behavior and an isolated HTTPS reverse-proxy fixture for
origin/redirect/cookie/header behavior. API status codes alone cannot prove a browser flow works.
Keep existing scanners and execution containment checks; map and extend them before adding tools.
Version, maintenance, licensing and compatibility must be reviewed before adding a dependency.

## 9. Proving that the guards detect regressions

Every new guard ships with a controlled negative fixture or reversible mutation that fails at the
intended security assertion, and a clean run that passes. A compilation error, missing fixture,
unrelated failure or tooling crash is not a successful falsification.

The initial required mutation set is:

| Deliberate defect | Required failure |
|---|---|
| Add an undeclared MVC route, MCP tool or servlet registration | Inventory/contract mismatch; protected runtime path refuses |
| Add a handler beneath an existing public wildcard | Public exposure inventory changes and requires a named contract |
| Remove the permission declaration or bypass interceptor/dispatcher wiring | Startup/runtime refusal or valid negative action scenario fails |
| Replace delete permission with create permission | Independent surface mapping and isolated-permission scenario fail |
| Introduce a role/permission without fixtures, including an interior permission | Exact registry/consumer coverage mismatch |
| Remove workspace or execution-owner predicate | Foreign/other-user data or effects assertion fails |
| Remove endpoint binding or promotion credential confinement | Wrong-key valid request fails the expected refusal assertion |
| Serve stale authority past the documented invalidation bound | Lifecycle scenario fails |
| Move authorization after an effect | Denied-effect witness detects the write/enqueue/send |
| Disable an inventory adapter, omit a suite or exclude it from the task graph | Family presence or expected-versus-executed evidence gate fails |

Run guard fixture tests on every PR. Run relevant production mutations when a boundary changes
and a complete curated mutation set before the assurance system is accepted and on its scheduled
verification job. **Amended (B6, §13):** the initial set is pinned to the ten rows, run weekly and on
changes under `modules/auth`, `ScopeInterceptor`, the dispatcher and `PublicPaths` — never per PR —
and each mutation names the ONE test class expected red. Each boundary gets at least one real wiring falsification; testing only the
inventory parser is insufficient. In redundant defenses, an isolated test proves each layer and
a combined bypass mutation proves the whole-path behavioral test can fail. Record any surviving
mutation with its reason and independent defense; never count it silently as detected.

Mutations run in a disposable checkout and isolated stack. Never mutate the shared main checkout,
production, shared databases or live credentials. Retain mutation ID, base SHA, patch hash,
targeted test, failure assertion and clean-run evidence.

## 10. Development and CI gates

### 10.1 Contributor contract

A new action lands in the same commit with its permission declaration, policy/doc row, surface
contract, valid scenario, applicable probes and falsification evidence. A new entry mechanism also
lands its discovery adapter and default-refusal integration. Role changes land with their policy
diff and automatically expanded tests. Permission additions identify both surface and interior
consumers. No unimplemented catalog row passes as coverage.

During implementation, extend AGENTS.md, DEVELOPMENT.md and the PR template with this rule.
Update the existing private lane prompt/handback Security sections through their owner; do not
copy private dispatch or raw evidence into the public repository. The PR asks for changed trust
boundaries, allowed roles/credentials, resource restrictions, test IDs and remaining acceptance
work. These prompts help authors; the build must detect an omission without trusting a checkbox.

Extend the existing hook installation flow with fast local contract/architecture feedback and
document how to invoke it directly. Avoid network-dependent or full Docker suites on every
commit. Local hooks are bypassable and cannot be the merge enforcement point.

### 10.2 Mandatory checks

Introduce a stable aggregate required check, provisionally `security-assurance`, backed by:
**Amended (B1/P1, §13):** the delivery model is **direct push** — the local merge gate is the
enforcement point, the aggregate is a stage of `scripts/gate.sh`, CI re-runs it cold, and the
admin bypass of the branch rules is documented; under a future PR model the aggregate becomes the
required status check.

1. Fast catalog, inventory, contract, architecture, public-exposure and harness fixture tests.
2. Real application authorization/credential/resource/lifecycle/effect integration scenarios.
3. Relevant browser and proxy scenarios, plus evidence that all required scenarios executed.
4. Security scanners with recorded tool/database freshness and reviewed, bounded suppressions.

Reuse existing CI jobs/tasks where they already execute the evidence; the aggregate must verify
all required dependencies completed successfully on the candidate commit. Always publish the
aggregate result, including when a dependency fails, is skipped, cancelled or times out. Such
outcomes are non-passing, not neutral success. No path-only exclusion may suppress the aggregate. **Amended (B8, §13):** path filters may pick
which HEAVY tier runs, never whether the aggregate reports; the fast tier always runs, and a skipped
heavy tier needs evidence inside its freshness budget.
Optional features still need their declared configuration-matrix evidence.

Reports contain discovered/classified surfaces, expected/executed/passed/failed/skipped scenario
IDs, role/credential/profile coverage, invariant/mutation results, base and tested commit SHAs,
artifact digest, tool versions and unresolved verification items. Missing reports, duplicate IDs,
zero-case families, stale SHA evidence, disabled tests and unexpected skips fail the aggregate.
Generate expected scenarios independently before execution; do not derive the expected set from
the tests that happened to run. A numeric code-coverage percentage is not a security verdict.

Network-dependent scans that cannot run may give an explicitly incomplete local result, but cannot
satisfy the required merge/release evidence. Define freshness budgets in reviewed configuration;
do not silently reuse an old successful scan. Security-relevant migrations and packaged-artifact
tests run on clean outputs, with the actual test task execution visible in the evidence.

Require this check in repository rules for the target branch, including relevant merge-queue
events if used. Require fresh review of policy, exemptions, security wiring, CI and test-harness
changes; use protected ownership rules where the repository can enforce them. Verify branch/rule
settings through the hosting API during rollout and retain evidence. Workflow YAML alone does
not prove enforcement. Owner/admin bypass remains a documented trust boundary. **Amended (B1/P1, §13):** the rules exist on
`main` and every push bypasses them as an admin; that is the documented boundary under direct push.

Release/deployment must reference the tested artifact and SHA, not rebuild an unverified variant.
Run safe smoke checks against the deployed proxy/configuration when deployment is separately
authorized. Integration acceptance and deployment acceptance remain distinct.

### 10.3 Exceptions and test safety

No exemption for an unknown entry point, missing declaration, disabled required suite or observed
authorization bypass. An intentional public route is a tested contract, not an exemption.
Other suppressions require exact scope, rationale, owner approval, linked issue, compensating
control and expiry; expired or broadened suppressions fail. Existing exceptions must be assessed
on their current facts, not automatically grandfathered.

Use per-run workspace/database/volume namespaces and synthetic secrets. Capture outbound mail,
HTTP, object-store and promotion effects in explicit sinks. Refuse unconfigured egress. All
destructive and load/abuse tests run only in isolated environments. Redact evidence before
publishing artifacts; reports contain identifiers needed for diagnosis, not credentials or
customer results. Cleanup verifies the resources it owns and never uses default live stack names.

## 11. Delivery slices and acceptance

This draft authorizes no implementation by itself. After review and ratification, search/update
GitHub issues for each bounded slice. Existing security follow-ups remain authoritative; link
their verification into this program rather than duplicate or mark them completed here.

| Slice | Deliverable | Exit evidence |
|---|---|---|
| A — inventory and policy | Pin implementation baseline; reconcile #215 state; inventory all families/configurations; contracts and ASVS applicability map | Every discovered entry classified; every missing behavior tracked; guards reject a new unknown entry; reviewed policy differences |
| B — reusable runner | Typed scenarios, independent surface expectations, synthetic grant seam, real credential adapters and effect witnesses | One complete vertical slice for pipeline mutation, execution ownership, published key binding and promotion; clean plus falsified runs |
| C — migrate coverage | Extend existing walks/sweeps to every active action, interior permission and supported credential; public/internal contracts | Exact expected/executed parity, valid success and refusal/effect evidence, key/lifecycle gates on completed #215 behavior |
| D — wider boundaries | Browser/proxy, outbound/file/execution/secret controls and mapped scanner evidence | Applicable ASVS rows have evidence or explicitly tracked gaps; each accepted boundary tested at its real effect point |
| E — enforce delivery | Local feedback, contributor rules, mandatory CI aggregate and verified repository settings | A PR adding an unguarded route and a PR skipping the suite cannot merge; same proof for a new public handler and a wrong permission |

**Amended (P5, §13):** slice A now; B after the scheduler (#9) ships; C–E as the owner's calendar
allows. Slice D takes §8's rows in B7's order. Slice E's "cannot merge" proof is read under P1's
direct-push model: the local gate refuses, and CI's cold re-run reports.

Slice A may begin while #215 is in progress. Full key-role acceptance waits for its relevant
implementation; transitional scopes are named explicitly in interim evidence and removed from
this harness with their production removal. Do not encode old key behavior as the target model.

Completion requires all of the following:

- All active entry families, surfaces, interior permission consumers and security-relevant
  configurations have complete contract/evidence coverage with no unexplained skips.
- Every protected action has valid allowed and denied evidence; applicable resource, credential,
  lifecycle and side-effect predicates are exercised through their real boundary.
- The curated mutation set detects the intended regressions and clean runs pass.
- New role, action, public exposure, interior permission and transport demonstrations each prove
  the framework either expands coverage or fails clearly until the missing contract is supplied.
- The applicable ASVS baseline has no unacknowledged gap. No ASVS completion claim while an
  applicable requirement remains unresolved; any narrower accepted milestone names its limits.
- Required CI enforcement is verified on the target repository/branch; production acceptance is
  separately recorded against its artifact, proxy configuration and authorized deployment.

Do not defer an unclassified active entry family or an untested authorization bypass to make a
milestone appear complete. Future protocols/features need no speculative implementation today;
their registration mechanisms must trigger the extension rule when introduced. General fuzzing,
formal verification and database row-level security can be considered separately after the
contract runner proves useful; none is a substitute for the required behavioral evidence above.

## 12. Fable review brief

Review this as a design, not as a claim that the proposed protections exist. Read the current
implementation and #215 amendments before assessing compatibility. Return findings with severity,
the exact section, a concrete failure/omission scenario and the smallest durable correction.
Separate design defects, unresolved product decisions and implementation details. Report useful
omissions beyond this list as well.

Challenge these points in particular:

1. Can a new route, protocol operation, servlet, job, listener or outbound adapter avoid discovery?
2. Can the expected policy and implementation share the same error and still pass? Can co-granted
   permissions hide an incorrect check? Can interior permissions escape coverage?
3. Can an alternate caller bypass the application boundary? Is context propagated safely without
   introducing a reusable stale authorization token or a production test override?
4. Do contracts preserve #215 key identity/role rules, special credential fences, promoter
   filtering, execution visibility and existing recovery flows without inventing new privileges?
5. Can invalid input, a 404, a 500, a disabled profile or an empty inventory masquerade as coverage?
6. Can denial happen after a write, enqueue, file access or outbound effect? Can a race or retry
   reintroduce authority after revocation? Which in-flight semantics need an owner decision?
7. Are public login/protocol endpoints, static/management routes and ASYNC/ERROR dispatch covered
   without breaking legitimate browser, MCP and streaming flows?
8. Can a PR weaken its own harness, skip an aggregate dependency or use a stale artifact and still
   merge? Which hosting settings and reviewer controls require explicit rollout verification?
9. Is the workload affordable on the existing CI runner? Propose safe factoring and measured
   budgets, not removal of invariants or blanket pairwise sampling.
10. Is the ASVS scope adequate for a server intentionally executing SQL and accessing customer
    datasources? Identify controls needing manual assessment or a separate containment decision.

**Recommendations awaiting review/ratification** (**amended, §13:** all decided on 2026-09-24 — P1–P5): the ASVS Level 2 target; the test-only grant
resolution seam; requiring the aggregate security check and fresh policy review; scan freshness
and exception-expiry budgets; and explicit semantics for already-running work after revocation.
Where existing product authority already settles a decision, reuse it rather than reopening it.

## 13. Ratification amendments (2026-09-24)

The pre-ratification review (the owner's private record of 2026-09-24, sections B and G) found
eight design defects and put five product decisions to the owner, who took all five the same day.
They are reproduced below verbatim from that review — the defect, then its correction; then the
owner's rulings — and each is marked in the text above where it changes it. Where the draft text
and this section disagree, this section wins. Slice A (#217, lane 217a) is the first delivery
under the ratified record.

### 13.1 The review's corrections (B1–B8)

*Marked in: §10.2 (three markers), §11 (slice E, under P5's marker).*

**B1 — HIGH — §10.2/§11 E assume PR-based merge enforcement that this repository does not
practise.** `main` carries four rules (`pull_request`, `required_status_checks`, `deletion`,
`non_fast_forward`) and EVERY push in this project bypasses them as an admin
("Bypassed rule violations for refs/heads/main" on each push, 2026-09-23/24). The enforcement point
today is the orchestrator's local gate on the merge SHA plus CI read after the push. A "required
aggregate check" is therefore advisory until the owner decides the delivery model. Correction: §10.2
names the decision (product decision P1 below) and defines the gate for BOTH models: under direct
push, the aggregate runs inside `scripts/gate.sh` (the gate that already precedes every push) and CI
re-runs it cold; under PR merge, the aggregate is the required status check. "Verify branch/rule
settings through the hosting API" is right either way — record that the rules exist and are bypassed.

*Marked in: §7.5 (with P2).*

**B2 — HIGH — §7.5 requires MCP role changes "on the next request" while `AuthCache` holds key
records and memberships for `cacheTtlSeconds = 60` per JVM** (`AuthProperties.kt:106`,
`AuthCache.kt:45`, `application.yml:184`). The spec forbids a grace period; the code has one. Either
C2 means "within the cache TTL" (then the record and this spec say 60 s and test just before/after
the bound), or role changes must evict the cache (event-driven invalidation in the member-role
update path, cross-instance via Redis or a version stamp). Correction: name it as product decision
P2 and, whichever way, make §7.5's test "warmed cache, then the role change, then the first request"
assert the ratified bound. Today the honest bound is 60 s.

*Marked in: §5 (item 2 and the test-owned paragraph).*

**B3 — MEDIUM — §5's "surface contract" is a second hand-kept table of 187+ rows.** auth.md §7.6 v3
(215a) is already the reviewed surface→permission mapping (the Surfaces column), and the reachability
gate already reads the doc's PLACEMENT (every route on its permission's row) in both directions. A
test-owned duplicate will drift from it. Correction: the surface contract IS auth.md §7.6's row;
the test-owned artifact adds only what the doc does not carry (scenario IDs, credential classes,
resource predicates, effects), keyed by permission + surface, and the parity test proves the two are
the same set. One reviewed table, extended — never two.

*Marked in: §7.1.*

**B4 — MEDIUM — §7.1's "test-only grant-resolution seam" needs its production shape stated.**
"Exists only in test sources" cannot be literally true: the production evaluator must expose an
injection point (an interface such as `PermissionResolver` behind `WorkspaceContext.permits`) for a
test implementation to replace. Correction: say so — one interface in `auth` main, ONE production
implementation in main, the synthetic one in test sources, and a jar-scan test proving no second
implementation is packaged. Also forbid the seam from being a Spring profile or property (a profile
name in an env var is a request-free switch nobody would notice).

*Marked in: §4 (the background-jobs row and the maintenance-scheduler paragraph).*

**B5 — MEDIUM — §4's "background jobs need narrowly described system capabilities" is over-scoped.**
The three schedulers act on instance state (retention, sweep, reaper) with no principal and no
per-workspace decision; authorising them adds a permission model to code that has no caller. The
useful invariant is different: an inventory that every `@Scheduled` method is listed with its
purpose and its bounded-work limit, and that none of them can be reached from a request. Correction:
replace "system capability" with "inventoried, unreachable from any transport, bounded" — the
scheduler (#9) keeps its own key contract as the spec already says.

*Marked in: §9.*

**B6 — MEDIUM — §9's mutation programme is under-specified in cost.** Ten curated mutations × a
disposable checkout × the suites that must fail = many full builds. Correction: pin the initial set
to the ten rows, run them as a scheduled job (weekly) and on changes under `modules/auth`,
`ScopeInterceptor`, the dispatcher and `PublicPaths`, never per PR; each mutation names the ONE
test class expected red so the run is minutes, not gates. The "every guard ships with its own
falsification" rule is already the store's protocol — cite it rather than restate.

*Marked in: §8 (rows reordered) and §11 (slice D, under P5's marker).*

**B7 — LOW — §8's table is a checklist without priority.** For THIS product the boundaries with real
blast radius are: user-authored SQL against customer datasources (the configured DB privileges are
the boundary, as §8 notes), in-process engines (H2/DuckDB/JSONata — the transform record's §4.5
breach suite is the precedent), datasource egress to private addresses, file roots, key issuance and
tenant isolation. Correction: §8 orders its rows by that risk and §11's slice D takes them in that
order; the rest of the ASVS L2 map is "outstanding, tracked", not slice-D scope.

*Marked in: §10.2.*

**B8 — LOW — §10.2 "No path-only exclusion may suppress the aggregate" conflicts with §12 Q9's
affordability.** Correction: path filters may decide which HEAVY tier runs (browser/proxy, the full
runner), never whether the aggregate reports; the aggregate always runs the fast tier and requires
the heavy tiers' evidence to be within the freshness budget (a timestamped artifact from the last
run on the same base), so a skipped heavy tier with stale evidence fails.

### 13.2 The owner's decisions (P1–P5)

Verbatim from the review's section G (P2 was ruled, then confirmed a second time; both lines are kept).

- **P2 → "within 60 s"**: the owner chose the cache TTL over eviction ("60s ttl is simpler and more maintainable"). 215b amends the record's C2 to "within the auth cache TTL (60 s)" and tests the bound with a clock seam (prompt §B5/A.9). §7.5 of the assurance spec must say the same.
- **P1 → direct push** (owner 2026-09-24 ~19:00): the local merge gate stays the enforcement point; the aggregate becomes a stage of `scripts/gate.sh`; CI re-runs it cold; the admin bypass of the branch rules is documented, not removed.
- **P2 → 60 s** confirmed a second time ("ok with all the eviction points, but 60 s is less code to maintain").
- **P3 → ASVS L2 as the target**, no compliance claim; the manual rows (SQL containment via DB privileges, in-process engines, egress to private ranges, file roots) listed as outstanding. (Owner asked what ASVS is — answered in the session: OWASP Application Security Verification Standard 5.0.0, the requirement checklist; L1 opportunistic, L2 for apps holding sensitive data, L3 for high-assurance.)
- **P4 → agreed**: running executions keep their authority to completion; open SSE streams are cut at the next event after revocation; auth.md says so.
- **P5 → agreed**: slice A (registration inventory both ways, public-glob contract, ten mutation scripts with one expected-red test each, the seam interface + jar-scan test, the ASVS map as an index) beside 215b; B after the scheduler ships; C–E as the calendar allows. Owner: "note it down".

Marked in: P1 — §10.2; P2 — §7.5; P3 — §2; P4 — §7.5; P5 — §11 and §12.
