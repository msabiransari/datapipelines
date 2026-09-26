# Agent documentation by functional area, served by the application

**Status:** RATIFIED (2026-09-25, by lane 242a; owner's §9 rulings taken 2026-09-25).
GitHub #242; the first slice, the handshake cap, is #241.
**Direction (owner, 2026-09-25):** the documentation an agent reads is served by the application,
assembled from what the application already knows; no static skill is kept in the repository as a
second copy of the truth.

## 1. Problem, measured (2026-09-25, `a3e79706`)

| Fact | Measure |
|---|---|
| Connect-time instructions (`server-instructions.txt`) | 3,174 bytes; documentation discovery starts at byte 2,700 |
| Claude Code 2.1.282's cap on server instructions and tool descriptions | 2,048 characters by default (`CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH`; verified in the runtime bundle, which names "the 2,048-character cap on MCP tool descriptions and server instructions") |
| Our guard on the instructions | 4,096 bytes, phrases asserted anywhere in the whole string |
| `SKILL.md` / `authoring-playbook.md` / generated `tools.md` | 32,615 / 57,013 / 65,335 characters (≈ 8k / 14k / 16k tokens) |
| Mandatory reading beyond two nodes | core + playbook ≈ 22k tokens before any task-specific reference |
| Claude Code output limits | warning at 10,000 tokens per tool result; 25,000 default cap (`MAX_MCP_OUTPUT_TOKENS`); an over-limit text result is spilled to a file the model then reads; a tool may declare `_meta["anthropic/maxResultSizeChars"]` |
| Delivery paths today | the jar's `skill/` classpath dir (copied from `.agents/skills/datapipelines/` at build), served by MCP `docs_list`/`docs_get`, the `datapipelines://docs/skill` resource and HTTP `/skill.md` + `/skill/<reference>.md`; a Claude Code plugin copy in `plugins/datapipelines/` kept byte-identical by `SkillDistributionTest` |
| Generated today | `references/tools.md` from the tool catalog at BUILD time (`skillToolsDoc`), drift-tested |
| Hand-maintained today, drift-tested against code | `error-codes.md` (the catalog), node types, calculator kinds |

Two consequences drive the design: an agent's mandatory reading grows with every capability, and
every hand-written mirror of a catalog is a drift test waiting to go red.

## 2. Principles (owner rulings)

- **P1 — the application serves the manual.** The document set is assembled at boot from the
  application's catalogs, its configuration and hand-written narrative resources packaged in the
  jar; MCP and the unauthenticated HTTP twin deliver it. A deployment serves the manual it runs.
- **P2 — adding a service adds focused documentation; it never grows every agent's mandatory
  reading.** New capabilities add an area; the core stays a small routing guide.
- **P3 — generic, always.** The render context is catalogs and configuration only, never workspace
  or dataset content (the "skill is generic" rule of 2026-09-11 stands; its guard runs over the
  RENDERED set).
- **P4 — one home per shared rule.** Workspace boundaries, permissions, draft/release, parameter
  safety and error recovery are stated once (in the core) and referenced from the areas.
- **P5 — what is a catalog is generated; what is judgment is written.** Tool references, error
  codes, node types, calculator kinds and enumerations come from the code; rules, workflows and
  mistakes to avoid are prose.

## 3. The document set

### 3.1 Three layers

| Layer | Holds | Read when |
|---|---|---|
| Core (`core`) | product orientation, the universal rules (P4's homes), the area index with "open this when…" | at the start of relevant work |
| Area guide (`<area>`) | the area's concepts, workflow, prerequisites, common mistakes; links to its references | when the task involves the area |
| Reference (`<area>-<topic>`) | one operation, schema, example, error family or advanced procedure; the area's tools | when needed during the task |

### 3.2 Areas, and the tool families that belong to them

The areas are the product's functional areas; the tool catalog already groups itself by name
prefix, and each prefix has exactly one area:

| Area | Tool prefixes (count on `a3e79706`) | Narrative that moves here |
|---|---|---|
| `pipelines` | `pipelines_` 7, `calculators_` 2 | DAG authoring, composition, verification (the playbook's §1–§5), node types, pipeline schema, naming |
| `executions` | `executions_` 4 | runs: listing, reading, results and paging, cancellation, the event log; later the scheduler's run history if it is documented with runs (owner ruling O5) |
| `templates` | `templates_` (SQL, HTML) | SQL templates, parameters, shared libraries |
| `transforms` | `templates_evaluate`, the transform-typed template tools | JSONata, contracts, evaluation, implements |
| `datasources` | `datasources_` 8, `semantics_` 3, `sql_probe` | discovery, introspection, connectivity, learned semantics, connecting |
| `lake` | `lake_` 3 | object-storage tables, lake query behaviour (dp-lake) |
| `endpoints` | `endpoints_` 4 | publishing pipelines, consuming their HTTP interfaces |
| `core` | `docs_` 2 | the core itself; error codes (a cross-area reference) |
| reserved: `scheduling`, `reporting`, `dashboards` | none yet | added by the lanes that ship them; never listed before |

A tool belongs to one area; `docs_list` says which. Names are flat (`<area>` for the guide,
`<area>-<topic>` for a reference) so the public HTTP glob `/skill/*` and the MCP resource shape
stay as they are; the area is a catalog attribute, not a path segment.

### 3.3 The rendered set is immutable per boot

`DocSet` is assembled once at application start by `DocRenderer` from (a) the narrative Markdown
resources under `modules/mcp-server/src/main/resources/skill/` with typed placeholders, and
(b) generators over `McpToolCatalog` + the tool definitions, `ApiErrorCatalog`, `NodeType`,
`CalculatorRegistry` and the enumerations the documents cite, plus the configuration values the
prose names (the result TTL, the timeouts, the name grammar, the citation cap). Rendering is a
pure function of the build and the boot configuration; the set is served from memory. No
per-request template engine: a Kotlin builder for the generated parts and `${key}` substitution
from a typed context for the narrative — the HTML template engine is the wrong tool for Markdown
read by a model.

## 4. Retrieval

- **`docs_list {"area"?: "<area>"}`** → entries `{name, area, layer, title, purpose, chars,
  sections: [{id, title, chars}]}`; without `area`, the core and every area guide (references are
  listed inside their guide's entry, so the top-level list stays short).
- **`docs_get {"name", "section"?}`** → the whole document, or one section by its heading slug
  (`{name, title, section, markdown, next?}`); a section over the budget is split at the next
  heading and `next` names the continuation. The whole-document form stays for compatibility.
- **Budget:** one response ≤ **24,000 characters** (≈ 6k tokens — under the 10,000-token warning
  with room for the JSON envelope); a document larger than that is served only by section, and
  `docs_list` says so. Tested per document and per section on the rendered set.
- **Aliases for one release:** `skill` → the core; `authoring-playbook` → `pipelines-authoring`;
  every current reference name → its new name; `docs_get` answers the alias with the new name in
  the response. Removed the release after.
- The MCP resource `datapipelines://docs/<name>` mirrors `docs_get` for clients that read resources.
- The handshake (#241): ≤ 1,800 characters, discovery first, guarded on the client-visible prefix.

## 5. Guards (they move from file parity to rendered output)

| Guard | Replaces |
|---|---|
| Every tool appears exactly once, in its own area's reference, with its shipped description and schema summary; every catalogued error code appears once; every node type and calculator kind appears (structural assertions over the rendered set) | `SkillToolsDocDriftTest`, the hand-written `error-codes.md` and its drift test |
| Golden tests for the narrative documents (the rendered text equals a checked-in expectation for a fixed configuration; a deliberate placeholder change turns them red) | `SkillDistributionTest`'s byte-for-byte mirror check |
| No dataset content in the rendered set (`SkillHasNoDemoContentTest` over `DocSet`) | the same test over files |
| Sizes: every document and section within the budget; the core within its line budget; the handshake and every tool description ≤ 2,048 characters, the required phrases inside the first 2,048 (#241) | the 4 KB byte cap |
| `docs-audit.sh` over an exported rendering (`docsExport` writes the set to `build/skill-docs/`; the audit gains that directory as an input) | the audit over `.agents/` |
| The fresh-session proof: a default Claude Code (or the fenced OpenCode harness, fence verified from its store) with no checkout discovers the core and retrieves complete guidance for a two-node and a four-node task; the reading path recorded from the audit log | none today |

## 6. Distribution

- The HTTP twin stays public (`/skill.md` = the core; `/skill/<name>.md` = a guide or reference):
  PublicPaths and auth.md §8.6.2 unchanged.
- `.agents/skills/datapipelines/` and the build-time copy go away; the narrative resources live in
  the module that serves them.
- **The Claude Code plugin (`plugins/datapipelines/`)** — owner ruling needed (§9): either retired,
  or reduced to a pointer whose `SKILL.md` says "add the MCP server; read `docs_get skill`" in
  under twenty lines. Recommendation: the pointer, so a `/plugin marketplace add` still teaches
  the connection; nothing else is mirrored.
- mcp-server.md §15 describes the served set; rest-api.md's `/skill` section follows.

## 7. Sequencing

1. **#241** — the handshake under the cap (its own lane; no dependency on the rest).
2. **242a — the render-at-boot document set:** DELIVERED 2026-09-25 (lane 242a). `DocSet`/
   `DocRenderer`, the generators, the area catalog, `docs_list(area)` + `docs_get(section)` +
   budgets, the HTTP twin over the set, the guards of §5, today's narrative moved into the
   module unchanged in substance (the split is 242b), aliases, the plugin per §9's ruling, the
   fresh-session proof.
3. **242b — the narrative by area:** DELIVERED 2026-09-26 (lane 242b). The core shrunk to
   orientation + the universal rules + the area index (6,353 chars, inside the 8,000-char
   working bound, whole-document again); the playbook split into the `pipelines` guide's
   workflow plus per-topic references (`pipelines-learning`, `pipelines-dag`,
   `pipelines-numbers`, `pipelines-verification`, `pipelines-engine-quirks`,
   `pipelines-do-dont`); `connecting` became the `datasources` guide's workflow with
   `datasources-semantics` as a reference; seven area guides total (O5's `executions`
   included); the reading path re-proven on the dp242 stack (a four-node pipeline under 12k
   tokens of documentation).
4. Scheduling, reporting and dashboards areas: by the lanes that ship those capabilities.

## 8. Acceptance (the record's own)

- A default Claude Code session with no checkout reads the core (≤ its budget), then exactly the
  area guide the task needs, then references only as needed; the recorded reading path for a
  four-node pipeline is under 12k tokens of documentation.
- Adding a tool to the catalog changes the rendered set with no hand edit; removing one is a red
  structural guard until its prose is gone.
- No document or section exceeds the budget; the handshake fits the cap with headroom.
- `.agents/` is gone; the plugin is per §9; the public HTTP twin serves the same set.

## 9. Rulings

| # | Question | Ruling (owner, 2026-09-25) |
|---|---|---|
| O1 | The plugin: retire, or a pointer? | **A pointer, under 20 lines** — the front matter names the MCP server; the body says "add the server; read `docs_get skill`"; nothing else mirrored, no parity test |
| O2 | The response budget | **24,000 characters** |
| O3 | Flat names (`<area>-<topic>`) vs path-shaped | **Flat** — the area is a catalog attribute; `/skill/*` and the resource URIs unchanged |
| O4 | Aliases for one release, then removed | yes (not asked; the recommendation stands — flag if wrong) |
| O5 | `executions_*` tools | **Their own `executions` area** (seven areas + the reserved three); the pipelines guide's verification step points at it |
