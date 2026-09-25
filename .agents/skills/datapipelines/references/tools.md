# The MCP tools

Open when you need a tool's exact arguments, its permission, or whether calling it writes.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

**This file is GENERATED** from the server's own tool catalog
(`./gradlew :modules:mcp-server:skillToolsDoc`), so it cannot describe a surface the
server does not ship. Do not edit it by hand; a drift test fails if you do. Permission is
the auth §7.6 catalog permission the tool declares: your MCP key may call it when your
role in the key's workspace holds it (a workspace admin's key acts as an author).

There are **42 tools**, in `tools/list` order.

## pipelines

### `pipelines_list`

Permission `pipeline.read` · read-only

List the pipelines of the key's pinned workspace, filtered by owner, datasource, or text search. Returns metadata (id, name, display_name, description, version, status, updated_at) — version is the WORKING version and status says DRAFT or RELEASED, so an unreleased pipeline is visible as such. Not the full body. Use pipelines_get for the body; pipelines in other workspaces are absent from this listing and resolve as not-found by id. Pipeline names are FOLDER PATHS (finance/payments/daily_settlement): pass prefix to BROWSE one level of that tree — prefix:"" lists the roots, prefix:"finance" lists what is directly under finance — and q to SEARCH across full paths. Start with prefix:"" to see which roots this workspace already uses before creating a pipeline under a new one. A promoter's key sees only RELEASED pipelines newer than the promotion target's (the promoter lens); every other pipeline is absent for it and resolves as not-found by id.

| Argument | Type | | What it is |
|---|---|---|---|
| `owner` | string | optional | Filter by owner user ID. |
| `datasource` | string | optional | Filter by datasource name. |
| `q` | string | optional | Full-text search on name and description. Searches across full paths; use prefix to browse instead. |
| `prefix` | string | optional | Browse ONE level of the folder tree instead of listing flat: returns that prefix's direct sub-folders (with counts) and its direct children. An empty string is the root. Use this to discover which roots and folders exist; use q to search across full paths. |
| `limit` | integer, default `50` | optional |  |

### `pipelines_get`

Permission `pipeline.read` · read-only

Get the full definition of a pipeline (the working version by default — the draft when unreleased edits exist, else the latest released version — or a specific version). Use this to read the pipeline body before executing or modifying it. The result carries the version, its status and body_hash — echo body_hash back as expected_hash on pipelines_update; a draft pointer is present when unreleased edits exist. When a node pins a template version that a newer released version outdates, an upgrade_available array names the node, the template and both versions — an offer to re-pin via pipelines_update, never an automatic change. A promoter's key sees only RELEASED pipelines newer than the promotion target's (the promoter lens); every other pipeline is absent for it and resolves as not-found by id.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required | Pipeline ID. |
| `version` | integer | optional | Specific version. Defaults to the working version: the draft when one exists, else the latest released. |

### `pipelines_execute`

Permission `pipeline.execute` · **writes**

Execute a pipeline with the given input parameters. Returns execution events (node start/complete/fail) and the final result data. The result's schema describes column types; BIGINTEGER and BIGDECIMAL columns serialize as JSON strings — preserve them as strings when displaying or persisting to avoid precision loss. When the execution FAILS, the error result carries the full failure record (node context, rendered SQL, exception chain with the root cause last in caused_by) — the same object executions_get returns; quote its correlation_id when escalating. The server checks the loop: a DRAFT whose pinned draft template was updated after this key's last templates_render of it is refused pipeline.execution.template_unrendered — render, then run.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required |  |
| `version` | integer | optional | Specific version to run. Defaults to the WORKING version: the draft when one exists, else the latest released. Never clamped — an unknown version is refused, not rounded to the latest. |
| `parameters` | object | required | Object whose keys match the pipeline's declared parameters. Values must match the declared types (BIGINTEGER and BIGDECIMAL as strings, others as JSON native types). |

### `pipelines_execute_node`

Permission `pipeline.execute_node` · **writes**

Runs ONE pipeline node's rendered SQL against its own datasource and returns up to 50 decoded rows — a debug query for testing a node in isolation, NOT a pipeline execution. DML and DDL nodes execute FOR REAL against the datasource, leaving no execution history or trace. No ancestors run and no tempdb exists: a node whose source is tempdb is refused, and so is a TRANSFORM node (use templates_evaluate). Parameters bind through the pipeline's declarations; unsupplied required parameters fall back to sample values and the response names them in sampled_parameters. Absent version runs the DRAFT if one exists, else the current released version; the response states which version and status ran. A draft whose pinned draft template was updated after this key's last templates_render of it is refused pipeline.execution.template_unrendered — render, then run.

| Argument | Type | | What it is |
|---|---|---|---|
| `pipeline_id` | string | required |  |
| `node_id` | string | required | Node id within the pipeline body. |
| `version` | integer | optional | Pipeline version to read. Omitted: the DRAFT if one exists, else the current released version. |
| `parameters` | object | optional | Values for the pipeline's declared parameters, keyed by name. Types follow the declarations (BIGINTEGER and BIGDECIMAL as strings). |

### `pipelines_create`

Permission `pipeline.create` · **writes**

Create a new pipeline. The body must satisfy the Pipeline Contract: nodes must form a DAG; at most one DQL node may resolve to output.target='caller' (a node that omits its output block resolves to 'caller' by default); zero caller nodes is legal for pure write-back pipelines; all datasource references must exist in this environment; all template references must exist and dry-render against the declared parameters. A node may also be type='CALCULATOR': it evaluates one catalog function and writes a typed value into the execution Context under context_key, which downstream nodes bind as :context_key — call calculators_list first for the kinds and their input names, and remember that a node referencing another node's context_key must depend_on it. A NEW top-level folder is refused until you confirm it: reuse an existing root, or ask the person first and then pass confirm_new_root: true. The server checks what you learned: a body whose template names a table this key never datasources_get_columns'd is refused pipeline.validation.table_not_learned with the clearing calls listed, and a raw-date door (two DATE parameters, no period parameter, no window calculator) is refused pipeline.validation.door_unacknowledged until you pass door_acknowledged: true. Returns the created pipeline with server-assigned id and version 1, which lands as a DRAFT: run it straight away, then STOP — a human releases it from the UI, and no tool releases anything.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Machine name, and a FOLDER PATH: 2-10 lower-case '/'-separated segments (finance/payments/daily_settlement). A FOLDER IS REQUIRED — a bare 'daily_settlement' is refused with pipeline.validation.name_invalid and details.reason='folder_required'; put experiments under test/. The root segment says who owns it — list the existing roots with pipelines_list {prefix: ''} and reuse one; ASK before minting a new root. Keep a pipeline under the same prefix as the templates it uses. There is no rename: the name is the pipeline's identity, so choose the folder now. |
| `display_name` | string | required |  |
| `description` | string | optional |  |
| `parameters` | object | optional | Declared pipeline parameters (name -> {type, required, default, description}). This is the ONLY parameter declaration point: the full parameter map, defaults applied, is the render context for every template the pipeline references. |
| `settings` | object | optional | Pipeline-level execution settings (e.g., tempdb engine). |
| `nodes` | array | required | Pipeline nodes. Each node has type (DQL/DML/DDL/PIPELINE), source, template ref, depends_on array, and — for DQL only — an optional output block. Omitting output on a DQL node means output.target='caller'; at most one node per pipeline may resolve to 'caller'. A node whose data downstream nodes query must declare output.target='tempdb' with a table name explicitly. A PIPELINE node instead carries a pipeline ref {name, version} pinning an existing pipeline version to execute as a child execution, an optional parameters map (typed literals, or '${parent_param}' to pass a parent parameter through), and an optional output block allowed only when the pinned child has a caller node; it declares neither source nor template. |
| `checks` | array | optional | Release checks (pipeline-contract §3.3): at most 20 objects, each {id, name, datasource, sql, expected} — id [a-z0-9_]{1,63} unique in the body, name 1-200 chars, sql ONE read-only statement. expected.kind is value (single numeric cell compared with absolute tolerance, default 0), range (the cell within min..max inclusive), or rows (the statement's row count equals rows). Every :name bind must name a DECLARED pipeline parameter (the calculator context is not available to a check); ${} interpolation is refused (a check has no rendering); tempdb is not a check datasource. You supply the query and the expectation, never an observed value — run them with pipelines_run_checks, and only the server's run produces observed. |
| `confirm_new_root` | boolean | optional | Set true ONLY after a person has agreed to a new top-level folder. A name whose root segment has no pipelines or templates under it yet is refused with details.existing_roots listing the roots that do exist — reuse one of those, or ask the person first and then pass this. 'test/' never needs it. |
| `door_acknowledged` | boolean | optional | Set true ONLY when the question truly fixes two dates. A pipeline whose parameters are two raw DATE inputs with no period parameter (year, quarter, month, *_year) and no window CALCULATOR node is refused pipeline.validation.door_unacknowledged — the door is a decision: prefer the period vocabulary of rule 13, and never pass this to silence the refusal. |

### `pipelines_update`

Permission `pipeline.update` · **writes**

Update an existing pipeline by writing its DRAFT — the first update after a release creates the draft (copy-on-write); later updates overwrite that same draft in place. Requires expected_hash: the body_hash you read (pipelines_get, or a previous update's result) for the version you based your edit on. The result carries status='DRAFT' — your work is NOT released; a human releases it from the UI. On pipeline.version.conflict someone modified it after you loaded it: re-read, rebase, retry; never retry blindly. The body takes the same node types as pipelines_create, CALCULATOR included (calculators_list has the kinds); no extra arguments are needed for one. The same entry-point checks apply: a body naming a table this key never datasources_get_columns'd is refused pipeline.validation.table_not_learned, and a raw-date door is refused pipeline.validation.door_unacknowledged until door_acknowledged: true.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required | Pipeline to update. |
| `expected_hash` | string | required | The body_hash of the version this edit is based on — pipelines_get or the previous update's result. A mismatch is a 409 conflict; re-read and rebase. |
| `name` | string | required | Machine name, and a FOLDER PATH: 2-10 lower-case '/'-separated segments (finance/payments/daily_settlement). A FOLDER IS REQUIRED — a bare 'daily_settlement' is refused with pipeline.validation.name_invalid and details.reason='folder_required'; put experiments under test/. The root segment says who owns it — list the existing roots with pipelines_list {prefix: ''} and reuse one; ASK before minting a new root. Keep a pipeline under the same prefix as the templates it uses. There is no rename: the name is the pipeline's identity, so choose the folder now. |
| `display_name` | string | required |  |
| `description` | string | optional |  |
| `parameters` | object | optional | Declared pipeline parameters (name -> {type, required, default, description}). This is the ONLY parameter declaration point: the full parameter map, defaults applied, is the render context for every template the pipeline references. |
| `settings` | object | optional | Pipeline-level execution settings (e.g., tempdb engine). |
| `nodes` | array | required | Pipeline nodes. Each node has type (DQL/DML/DDL/PIPELINE), source, template ref, depends_on array, and — for DQL only — an optional output block. Omitting output on a DQL node means output.target='caller'; at most one node per pipeline may resolve to 'caller'. A node whose data downstream nodes query must declare output.target='tempdb' with a table name explicitly. A PIPELINE node instead carries a pipeline ref {name, version} pinning an existing pipeline version to execute as a child execution, an optional parameters map (typed literals, or '${parent_param}' to pass a parent parameter through), and an optional output block allowed only when the pinned child has a caller node; it declares neither source nor template. |
| `checks` | array | optional | Release checks (pipeline-contract §3.3): at most 20 objects, each {id, name, datasource, sql, expected} — id [a-z0-9_]{1,63} unique in the body, name 1-200 chars, sql ONE read-only statement. expected.kind is value (single numeric cell compared with absolute tolerance, default 0), range (the cell within min..max inclusive), or rows (the statement's row count equals rows). Every :name bind must name a DECLARED pipeline parameter (the calculator context is not available to a check); ${} interpolation is refused (a check has no rendering); tempdb is not a check datasource. You supply the query and the expectation, never an observed value — run them with pipelines_run_checks, and only the server's run produces observed. |
| `door_acknowledged` | boolean | optional | Set true ONLY when the question truly fixes two dates. A pipeline whose parameters are two raw DATE inputs with no period parameter (year, quarter, month, *_year) and no window CALCULATOR node is refused pipeline.validation.door_unacknowledged — the door is a decision: prefer the period vocabulary of rule 13, and never pass this to silence the refusal. |

## templates

### `templates_list`

Permission `template.read` · read-only

List the templates of the key's pinned workspace. Templates are reusable generators referenced by id+version — sql/html bodies are authored in Freemarker, transform bodies are script expressions; each has a fixed type — 'sql' renders SQL for pipeline nodes (and carries a dialect), 'html' renders escaped output and declares none, and the transform types ('jsonata', 'javascript') evaluate the body as a pure function of its input and declare neither. Template ids are unique per workspace — another workspace's template resolves as not-found. A promoter's key sees only RELEASED templates newer than the promotion target's (the promoter lens); every other template resolves as not-found.

| Argument | Type | | What it is |
|---|---|---|---|
| `dialect` | string (`POSTGRES` \| `ORACLE` \| `MSSQL` \| `MYSQL` \| `H2` \| `DUCKDB` \| `SQLITE` \| `LAKE`) | optional |  |
| `type` | string (`sql` \| `html` \| `jsonata` \| `javascript`) | optional | Filter by template kind: 'sql' (pipeline-referenced SQL), 'html' (rendered output), or a transform type ('jsonata', 'javascript' — a pure function over its input, never rendered). |
| `q` | string | optional |  |
| `prefix` | string | optional | Browse ONE level of the folder tree instead of listing flat: returns that prefix's direct sub-folders (with counts) and its direct children. An empty string is the root. Use this to discover which roots and folders exist; use q to search across full paths. |
| `is_library` | boolean | optional | Filter to library templates (macro collections) or executable templates. |
| `limit` | integer, default `50` | optional |  |

### `templates_get`

Permission `template.read` · read-only

Get the body and metadata of a template version, including its imports array (the library macros it can call). Defaults to the working version — the draft when unreleased edits exist, else the latest released. A promoter's key sees only RELEASED templates newer than the promotion target's (the promoter lens); every other template resolves as not-found.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required |  |
| `version` | integer | optional | Specific version. Defaults to the working version: the draft when one exists, else the latest released. |

### `templates_used_by`

Permission `template.read` · read-only

Which pipelines pin a given template version in their working version (the draft when unreleased edits exist, else the latest released). Returns one reference per node — pipeline name and id, node id, and the pipeline version carrying the pin — plus the distinct pipeline count. Use it before editing or retiring a template version to see who you would affect. It does not answer 'is it safe to delete' (that scan includes historical pipeline versions and lives in the delete refusal), and it never changes anything. A promoter's key sees only RELEASED templates newer than the promotion target's (the promoter lens); every other template resolves as not-found, and pinning pipelines it cannot see are left out of the answer.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required | Template id. |
| `version` | integer | required | The pinned version to look for. |

### `templates_create`

Permission `template.create` · **writes**

Create a new template. Templates use Freemarker syntax. A template declares NO parameters of its own: the variables its body may reference are exactly the parameters declared by the pipeline that calls it, with defaults applied. Describe the variables you expect in 'description' — that free text is how humans and agents discover them. Macros from library templates are made available by listing them in 'imports'; the body must NOT contain import or include directives, they are synthesized from the imports array. The 'type' is chosen here and never changes afterwards: 'sql' (default) requires a dialect and is what pipeline nodes reference; 'html' takes no dialect and renders through an auto-escaping engine. A NEW top-level folder is refused until you confirm it: reuse an existing root, or ask the person first and then pass confirm_new_root: true. Version 1 lands as a DRAFT: a pipeline draft may pin it and render against it while you iterate, and a human releases it from the UI — a RELEASED pipeline may only pin RELEASED template versions, so the template is released first.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | optional | Template id, and a FOLDER PATH: 2-10 lower-case '/'-separated segments (acme/finance/daily_orders.sql). A FOLDER IS REQUIRED — a bare 'daily_orders.sql' is refused with template.validation.id_invalid and details.reason='folder_required'; put experiments under test/, and shared macros under <owner>/lib/. Keep a template under the same prefix as the pipelines that read it. Optional; auto-generated if omitted. There is no rename, so choose the folder now. |
| `engine` | string (`freemarker` \| `none`), default `"freemarker"` | optional | Template engine, matched to the type: 'freemarker' for sql/html, 'none' for the transform types ('jsonata'/'javascript' — the body is evaluated, never rendered). Any other pairing is refused with template.validation.engine_unsupported. |
| `type` | string (`sql` \| `html` \| `jsonata` \| `javascript`), default `"sql"` | optional | Template kind, fixed at creation and identical on every version: 'sql' renders SQL for pipeline nodes (requires 'dialect'); 'html' renders HTML through an auto-escaping engine (must have NO 'dialect'); 'jsonata' and 'javascript' are transform types — the body is one expression evaluated as a pure function of its input, engine is 'none', dialect/imports/is_library are refused, and contract/invariants/tests blocks are required. 'javascript' is refused at save until round two. |
| `dialect` | string (`POSTGRES` \| `ORACLE` \| `MSSQL` \| `MYSQL` \| `H2` \| `DUCKDB` \| `SQLITE` \| `LAKE`) | optional | SQL execution target. Required when type is 'sql' (the default); forbidden otherwise — html and the transform types declare no dialect. |
| `display_name` | string | required |  |
| `description` | string | required | Free text. State the variables the body expects and their types — the template declares none. |
| `imports` | array of object | optional | Library templates whose macros this body calls. Aliases must be unique within the template; each referenced template must exist at that exact version and be is_library=true. |
| `is_library` | boolean, default `false` | optional | true if this template exists to be imported by others. A library body contains only <#macro>/<#function> definitions — no output outside macro definitions. body is still required. |
| `body` | string | required | Template source. Must not contain <#import> or <#include>. |
| `contract` | object | optional | Transform contract (transform types only — refused on sql/html with template.blocks_not_allowed): { mode: 'row'\|'table'\|'value', inputs: { name: { kind: 'table', columns: [{name, type, precision?, scale?, nullable?}] } or { kind: 'value', type, precision?, scale? } }, output: { kind: 'table'\|'value'\|'object', ... }, rejects?: boolean }. Types are LogicalType wire names; a row-mode contract requires exactly one table input. |
| `invariants` | array | optional | Transform invariants: [{ name, expr, message }] — JSONata over { rows, rejects, inputs }, must be true on every test case and every real execution. May be empty but is required on a transform type. |
| `tests` | array | optional | Transform test cases: [{ name, input: { rows?, inputs?, meta?, now? }, expect: { output } or { refusal } }] — non-empty, at least one case whose every table input and rows are empty, expect is exactly one of output/refusal. Save runs the suite; release re-runs it. |
| `confirm_new_root` | boolean | optional | Set true ONLY after a person has agreed to a new top-level folder. A name whose root segment has no pipelines or templates under it yet is refused with details.existing_roots listing the roots that do exist — reuse one of those, or ask the person first and then pass this. 'test/' never needs it. |

### `templates_update`

Permission `template.update` · **writes**

Update an existing template by writing its DRAFT — the first update after a release creates the draft (copy-on-write); later updates overwrite that same draft in place. Requires expected_hash: the body_hash you read (templates_get, or a previous templates_create/templates_update result) for the version you based your edit on. The result carries status='DRAFT' — your work is NOT released; a human releases it from the UI. On template.version.conflict someone modified it after you loaded it: re-read with templates_get, rebase, retry; never retry blindly. The body takes the same fields as templates_create, and the template's type is fixed at creation — an update naming a different type is refused with template.validation.type_immutable. type and dialect are optional: omitted, the working version's are inherited; a different dialect is refused. templates_purge_draft is for a template that was a mistake, not for editing one.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required | Template to update — the FOLDER PATH id it was created under (acme/finance/daily_orders.sql). Required here: §9.6, the name never travels in a path or anywhere else. There is no rename, so the id cannot change — an unknown id is the catalogued template.not_found. |
| `expected_hash` | string | required | The body_hash of the version this edit is based on — templates_get, or a previous templates_create/templates_update result. A mismatch is a 409 template.version.conflict; re-read and rebase, never retry blindly. |
| `engine` | string (`freemarker` \| `none`), default `"freemarker"` | optional | Template engine, matched to the type: 'freemarker' for sql/html, 'none' for the transform types ('jsonata'/'javascript' — the body is evaluated, never rendered). Any other pairing is refused with template.validation.engine_unsupported. |
| `type` | string (`sql` \| `html` \| `jsonata` \| `javascript`) | optional | Template kind — fixed at creation, so on an update it is OPTIONAL: omitted, the working version's is inherited; stated, it must equal it (template.validation.type_immutable otherwise). |
| `dialect` | string (`POSTGRES` \| `ORACLE` \| `MSSQL` \| `MYSQL` \| `H2` \| `DUCKDB` \| `SQLITE` \| `LAKE`) | optional | Optional on update: omit it and the working version's dialect is inherited. When present it must be the dialect the template already has — a different one is refused with template.validation.dialect_invalid (a template pinned by pipeline nodes cannot change engine; create a new template instead). Never present for an html template. |
| `display_name` | string | required |  |
| `description` | string | required | Free text. State the variables the body expects and their types — the template declares none. |
| `imports` | array of object | optional | Library templates whose macros this body calls. Aliases must be unique within the template; each referenced template must exist at that exact version and be is_library=true. |
| `is_library` | boolean, default `false` | optional | true if this template exists to be imported by others. A library body contains only <#macro>/<#function> definitions — no output outside macro definitions. body is still required. |
| `body` | string | required | Template source. Must not contain <#import> or <#include>. |
| `contract` | object | optional | Transform contract (transform types only — refused on sql/html with template.blocks_not_allowed): { mode: 'row'\|'table'\|'value', inputs: { name: { kind: 'table', columns: [{name, type, precision?, scale?, nullable?}] } or { kind: 'value', type, precision?, scale? } }, output: { kind: 'table'\|'value'\|'object', ... }, rejects?: boolean }. Types are LogicalType wire names; a row-mode contract requires exactly one table input. |
| `invariants` | array | optional | Transform invariants: [{ name, expr, message }] — JSONata over { rows, rejects, inputs }, must be true on every test case and every real execution. May be empty but is required on a transform type. |
| `tests` | array | optional | Transform test cases: [{ name, input: { rows?, inputs?, meta?, now? }, expect: { output } or { refusal } }] — non-empty, at least one case whose every table input and rows are empty, expect is exactly one of output/refusal. Save runs the suite; release re-runs it. |

### `templates_render`

Permission `template.render` · read-only

Render a template against the provided context values and return the SQL it produces. Use this to preview generated SQL before creating a pipeline that references the template. The context is a free-form map: supply the same keys the calling pipeline would declare as parameters. Referencing a key absent from the context fails the render — that is the same failure a pipeline save would report.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required |  |
| `version` | integer | optional | Defaults to latest. |
| `context` | object | required | Render context: the parameter map a calling pipeline would provide, defaults already applied. Values follow the wire conventions of the Type System (BIGINTEGER/BIGDECIMAL as strings, TIMESTAMP with Z or offset). |

### `templates_evaluate`

Permission `template.evaluate` · read-only

Evaluate a transform template ('jsonata'/'javascript') over a caller-supplied input object and return { output, rejects, invariants } — no staging, no Context. Use this to run a transform's body against one input the way its test suite does (sql_probe's twin; templates_render is for sql/html and refuses a transform type with template.render_not_applicable). The version resolves as templates_render does: omitted, the working version (the draft when one exists, else the latest released). A refusal is the code with its detail — a type-gate refusal, an input-contract violation, or an engine refusal (timeout, resource limit, pool exhausted).

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required |  |
| `version` | integer | optional | Specific version. Defaults to the working version: the draft when one exists, else the latest released. |
| `input` | object | required | The input object of the template's contract: { rows: [...], inputs: {...} } — in row mode `rows` is the batch and `inputs` holds the value inputs only (the table input is NOT listed); in table/value mode `inputs` holds every input, tables as arrays. Optional `now` (ISO-8601) pins the clock: without it the $now()/$millis() builtins refuse. |
| `now` | string | optional | Optional ISO-8601 instant the $now()/$millis() builtins return for this evaluation. Absent, a body that reads the clock refuses (a transform is a pure function of its inputs — the clock is an input). |

### `templates_purge_draft`

Permission `template.version.manage` · **writes**

Hard-delete a template that has NEVER been released: the only version is a DRAFT, created by this key's user, and pinned by nothing — no pipeline version anywhere, draft or released, may reference any version of it (the refusal names the pinning pipelines; templates_used_by answers the working-version scan if you need to inspect them). The sole-draft purge takes the entity row with it. A template holding any RELEASED or discarded version, another user's draft, or a pinned draft is refused — humans release and humans discard releases; an agent's own draft that should not exist is what this verb removes. Mutating.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required | Template id. |

## datasources

### `datasources_list`

Permission `datasource.read` · read-only

List the datasources GRANTED to the key's pinned workspace. Visibility is the grant: a datasource registered elsewhere and not granted to this workspace is ABSENT, not hidden, and there is no such thing as a global datasource. This is the learn-first read: every entry carries name, dialect, description, the readonly flag, the workspace that REGISTERED it (omitted for an instance-level one), granted:true, connection metadata — never passwords — AND `facts`, the datasource-wide learned facts earlier sessions recorded (its time window, whether it is a sample), each with `trust` and evidence — read them before you assume coverage — AND `definitions`, this workspace's rules (definition, exclusion, preference) earlier pipelines chose on this datasource: a definition is a rule an earlier pipeline chose; read it before you choose yours, and reuse or supersede it, never re-choose. `datasources_get` is the same payload for ONE datasource — the refresh to call after recording facts with semantics_record.

| Argument | Type | | What it is |
|---|---|---|---|
| `dialect` | string | optional |  |

### `datasources_get`

Permission `datasource.read` · read-only

Get metadata for a single datasource GRANTED to the key's pinned workspace: name, dialect, JDBC URL, the workspace that REGISTERED it (omitted for an instance-level one), granted:true, readonly flag, pool settings, `facts` — the datasource-wide learned facts agents recorded (its time window, whether it is a sample) — read them before you assume coverage — and `definitions`, this workspace's rules (definition, exclusion, preference) earlier pipelines chose on this datasource: a definition is a rule an earlier pipeline chose; read it before you choose yours. Credentials are never returned. A datasource that is not granted to this workspace resolves as not-found — the same answer a name that exists nowhere gets, so nothing about it can be probed.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required |  |

### `datasources_test`

Permission `datasource.test` · read-only

Test connectivity to a datasource. Returns success/failure and server version on success. Useful for diagnosing pipeline connection errors.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required |  |

### `datasources_get_schemas`

Permission `datasource.introspect` · read-only

List the namespaces of a registered datasource by reading its live JDBC metadata, excluding the engine's own system schemas. The entry point of schema discovery: call this first, then get_tables(namespace), then get_columns for only the tables the SQL needs. Each entry carries an ordered `namespace` path and a `label`; on a two-level engine (catalog.schema, project.dataset) two entries can share a label and differ only by their outer segment, so pass the whole `namespace` back rather than the label. `schemas` repeats the labels for older clients. An empty list on a datasource with no namespaces is a valid answer. Read-only, for pipeline authoring.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Datasource name. |

### `datasources_get_tables`

Permission `datasource.introspect` · read-only

List the tables and views of a registered datasource by reading its live JDBC metadata. The listing spans namespaces — pass each table's reported `namespace` array to datasources_get_columns. A table carries `facts` when agents have recorded table-level learned facts on it (grain, sampling, window, a caveat) — read them before probing; a fact marked stale or needs_review is a warning, not a truth. For a LAKE datasource each table carries `partition_column`: the registered partition key, or `null` when none is registered. A compatible filter on a partition key can skip files; missing registration does not establish file count or rule out Parquet row-group skipping and column projection. Read-only, for pipeline authoring.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Datasource name. |
| `namespace` | array of string | optional | Optional namespace filter, outermost first, as returned by datasources_get_schemas. An unknown namespace matches nothing. |
| `schema` | string | optional | Optional single-level filter; accepts the dotted 'catalog.schema' form. Superseded by namespace. |

### `datasources_get_columns`

Permission `datasource.introspect` · read-only

List one table's columns with canonical types, read from the datasource's live JDBC metadata. Pass the table name exactly as datasources_get_tables returned it, and its `namespace` array with it. Without a namespace only the connection's current one is read; if the datasource reports none, an explicit namespace is required (list them with datasources_get_schemas). On a two-level engine an unqualified read can merge same-named tables from different catalogs, which is why the namespace is worth passing. Each column carries `facts` when agents have recorded learned facts on it — a unit, a time zone, what a coded value means, a join, a caveat — with trust and evidence: read them before probing, and treat stale or needs_review as a warning to re-verify, then record the superseding fact. An unknown table is refused as datasource.table_not_found, naming the nearest listed table when one is close; a LAKE datasource answers datasource.lake_table_not_found for a table its registry does not carry. Read-only, for pipeline authoring.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Datasource name. |
| `table` | string | required | Table name as returned by datasources_get_tables. |
| `namespace` | array of string | optional | The table's namespace, outermost first, as datasources_get_tables reported it. An unknown namespace matches nothing. |
| `schema` | string | optional | Optional single-level filter; accepts the dotted 'catalog.schema' form. Superseded by namespace. |

### `datasources_get_table_stats`

Permission `datasource.read` · read-only

One table's catalog statistics: a row estimate, the index list (a lake table's partition column reports as the pseudo-index it is), and per-column distinct / null-fraction / min-max bounds — the min/max are the catalog's estimates, not a scan — probe for the exact bound. For a LAKE table the payload also carries partition_column: the registered partition column's name, or null when none is registered. This metadata does not establish file count or physical layout; Parquet row-group skipping and column projection may still reduce reads. A pushed READ_PARQUET filter alone does not prove skipped files or bytes. Every number comes from the engine's own catalog (pg_class, information_schema, parquet footers) — never a scan of the table, so this is safe at any table size. When a dialect holds no catalog stats the stat fields are null and stats_source is "none" — probe an explicit count with sql_probe if you need one. An unknown table is refused as datasource.table_not_found, naming the nearest listed table when one is close; a LAKE datasource answers datasource.lake_table_not_found for a table its registry does not carry. Read this before writing a predicate, then probe the query's access path and timing; an absent index alone does not establish scan cost.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Datasource name. |
| `table` | string | required | Table name exactly as datasources_get_tables returned it. |
| `namespace` | array of string | optional | The table's namespace, outermost first, as datasources_get_tables reported it. An unknown namespace matches nothing. |

### `datasources_preview_rows`

Permission `datasource.preview_rows` · read-only

Preview up to `limit` rows of one table's data, read live from the datasource. The counterpart to datasources_get_columns: this shows the DATA, that shows the shape. Without order_by the top-N is engine-arbitrary; pass order_by to see a chosen end of the data, e.g. direction DESC for the newest or largest rows. Read-only (SELECT); readonly datasources are valid targets. Values arrive wire-encoded: BIGINTEGER and BIGDECIMAL as strings, temporal as fixed-width ISO forms. An unknown table is refused as datasource.table_not_found naming the nearest listed table; a table the datasource's credentials cannot read is datasource.table_forbidden.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Datasource name. |
| `table` | string | required | Table name exactly as datasources_get_tables returned it. |
| `schema` | string | optional | Optional schema qualifier. Omitted means the connection's current schema. |
| `order_by` | array of object | optional | Sort terms applied in order. Each is an object with a column and a direction, never a free SQL string. |
| `limit` | integer, default `50` | optional |  |

## sql

### `sql_probe`

Permission `datasource.sql_probe` · read-only

Run ONE read-only SELECT or WITH statement against a datasource and return up to `limit` wire-encoded rows, the canonical column schema, wall_ms of query time, and the EXPLAIN plan captured BEFORE the query ran — a bounded debug probe, not an export. The statement is classified before any connection opens: anything but a single SELECT/WITH, or a denylisted verb (INSERT, DROP, ATTACH, EXPLAIN, INTO, ...) anywhere in it, is refused without touching the datasource. Parameters bind as named :name placeholders through the same binder pipeline SQL uses; every referenced name must be supplied in `parameters` with its canonical type. `tempdb` is a scratch check, not an execution: the statement is prepared against an EMPTY scratch H2 in the staging mode (no staged tables, no data). Read `validation_status`: `executed` (`parsed: true`, rows) means a self-contained statement ran; `incomplete` (`parsed: null`, `missing_table`) means H2 stopped at the first staged table it could not find and NOTHING after that point — syntax or names — was checked; an error is a real H2 error (a syntax slip, a `VALUES` column named column1 where H2 says C1) found in milliseconds instead of a full run. To finish an incomplete check, restate the suspect construct over typed, aliased `VALUES` inputs so it executes here, or run the node with its real staged inputs. On a timeout the error details carry wall_ms and the plan, so the plan that explains the timeout survives it. A statement the database refuses for lack of privilege on an existing table is datasource.table_forbidden. The sql text never reaches the audit log — only its SHA-256 and length are recorded.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Datasource name, or the reserved name tempdb to prepare a staging (H2) statement against an empty scratch engine. validation_status executed means it ran; incomplete with missing_table means H2 stopped at a staged table that exists only inside a full execution, and the rest of the statement is unverified. |
| `sql` | string | required | ONE SELECT or WITH statement. A second statement or a denylisted verb is refused before any connection opens. |
| `parameters` | object | optional | Bind values for the statement's :name placeholders, keyed by name. type is the canonical logical type; value is its wire string (BIGINTEGER/BIGDECIMAL as decimal text, temporal in ISO forms, BINARY as padded base64). A null value binds SQL NULL. |
| `limit` | integer, default `50` | optional |  |
| `timeout_seconds` | integer, default `10` | optional |  |

## executions

### `executions_list`

Permission `execution.read` · read-only

List recent pipeline executions of the key's pinned workspace, optionally filtered by pipeline or status. The key acts as its own role: your own runs are always listed, plus every run of the workspace when the key's role holds execution.read_all (a workspace-admin-role key). Other members' runs are not listed.

| Argument | Type | | What it is |
|---|---|---|---|
| `pipeline_id` | string | optional |  |
| `status` | string (`RUNNING` \| `SUCCESS` \| `FAILED` \| `ABORTED`) | optional |  |
| `limit` | integer, default `50` | optional |  |

### `executions_get`

Permission `execution.read` · read-only

Get metadata for a specific execution: status, timing, node_stats, parameters used. On a FAILED execution, error carries the full failure record: code, message, correlation_id, node context (datasource, dialect, pinned template), the rendered SQL (:name form, no bound values) and the exception chain with stack frames — read error.code first, then error.exception.caused_by (root cause LAST), then error.sql; quote error.correlation_id when escalating. To get the result rows, use executions_get_result. Visible for YOUR OWN runs (this key's own), or any run of the workspace when the key's role holds execution.read_all; another member's execution is not found.

| Argument | Type | | What it is |
|---|---|---|---|
| `execution_id` | string | required |  |

### `executions_get_result`

Permission `execution.result.read` · read-only

Fetch result rows for a completed execution, paginated via offset+limit. Returns schema + rows + pagination metadata. Works for ANY completed execution that produced a caller result, of any size, until its TTL expires (default 300s, set at execution time). Order is stable across pages. Reading pages does NOT extend the TTL — after expiry the result is gone and the pipeline must be re-run. Readable for YOUR OWN runs (this key's own), or any run of the workspace when the key's role holds execution.read_all; another member's execution is not found.

| Argument | Type | | What it is |
|---|---|---|---|
| `execution_id` | string | required |  |
| `offset` | integer, default `0` | optional |  |
| `limit` | integer, default `1000` | optional | Rows per page. Defaults to the server's result page size. |
| `format` | string (`json` \| `arrow` \| `csv`), default `"json"` | optional |  |

### `executions_cancel`

Permission `execution.cancel` · **writes**

Request cancellation of a RUNNING execution. This key can cancel ONLY an execution its own MCP calls started: the execution must have been triggered via MCP by this key's user, and an audit row must pair this key with the execution's correlation id — an execution started over REST, the UI, a pipeline node, a published endpoint, or another key of the same user is refused, and the refusal names which rule fired. A non-RUNNING execution is refused with its current status. Cancellation is requested, not awaited: the flag reaches the executing instance within about one poll interval (immediately when same-instance); poll executions_get for the terminal ABORTED status. Mutating.

| Argument | Type | | What it is |
|---|---|---|---|
| `execution_id` | string | required |  |

## calculators

### `calculators_list`

Permission `calculator.read` · read-only

The catalog of calculator kinds a CALCULATOR node can evaluate: every kind with its typed inputs (name, type, required, whether it takes a JSON array, and its default when optional), its output type (or, for a multi-output kind, the named `outputs` set a node maps through `context_keys`), one worked example, and `phrases` — the everyday phrases the kind answers. Call this before authoring a CALCULATOR node — the kind names and input names are not guessable — and match the question's words against `phrases` before you pick a kind: a relative time phrase ('last quarter', 'month to date') is resolved by that lookup, never by interpreting it yourself. Also returns the Context keys every pipeline can reference without declaring anything: the deployment's org_* values and the platform keys current_date, current_timestamp and execution_id. Read-only.

No arguments.

### `calculators_get`

Permission `calculator.read` · read-only

One calculator kind's full definition: display name, description, typed inputs, output type (or the named `outputs` set of a multi-output kind), a worked example and `phrases` — the everyday phrases the kind answers, which you match the question's words against before picking a kind. Use it when you know the kind and need its exact input names and types. An unknown kind is refused with the catalogued names in the error detail. Read-only.

| Argument | Type | | What it is |
|---|---|---|---|
| `kind` | string | required | The kind name, e.g. fiscal_quarter. |

## endpoints

### `endpoints_create`

Permission `endpoint.publish` · **writes**

Publish a released pipeline as a GET endpoint at /api/<category>/<version>/<path>. The category is your namespace (a business domain, a team); categories matching v<number> and the literal 'api' are reserved to the product and refused with endpoint.path_reserved. The version is one free-form segment (v1 by convention); path variables come after it. The pipeline must have a RELEASED version and must be side-effect-free: every node DQL into tempdb or the caller, a DML/DDL node whose source is tempdb, transitively through PIPELINE nodes. A DML/DDL node against a registered datasource, or a DQL node writing back to a datasource, is refused with endpoint.pipeline_not_readonly naming the node and the datasource — that rule is what makes serving over GET safe, since GET is retried, preloaded and crawled. path is 3-10 segments, each a literal [a-z0-9][a-z0-9_.-]{0,63} or a {variable} naming a declared parameter; remaining parameters come from the query string. A leading /api prefix is stripped, not refused. A path that could match the same URL as an existing one is refused (endpoint.path_conflict) rather than resolved by precedence. Calling the endpoint needs an API key bound to it — create one with the api_caller role and bind it over REST or in the UI (auth.md §7.7); an endpoint bound to no key is served to no one, and an MCP key never reaches REST at all (it acts over /mcp only).

| Argument | Type | | What it is |
|---|---|---|---|
| `path` | string | required | e.g. /finance/v1/revenue/{region} — category, version, then the path; no /api prefix (one is stripped, not refused), no trailing slash. |
| `pipeline` | string | required | The pipeline NAME. It must have a released version. |
| `timeout_seconds` | integer | optional | Clamped by datapipelines.endpoints.timeout-min-seconds/max-seconds. On timeout the endpoint answers 202 and the execution keeps running. |
| `description` | string | optional |  |

### `endpoints_list`

Permission `endpoint.read` · read-only

List the published endpoints of the key's workspace: path, pipeline name, timeout, whether it is enabled, and the path variables it binds. A disabled endpoint answers 404 exactly like an unpublished one, so this listing is the only way to see that it exists. A promoter's key lists only the endpoints of pipelines it can see (the promoter lens: released, newer than the promotion target's).

No arguments.

### `endpoints_get`

Permission `endpoint.read` · read-only

One published endpoint by its path (the pattern, not a request URL — '/finance/v1/revenue/{region}'). For a promoter's key an endpoint over a pipeline it cannot see resolves as not-found.

| Argument | Type | | What it is |
|---|---|---|---|
| `path` | string | required | The published path PATTERN, e.g. /finance/v1/revenue/{region}. |

### `endpoints_delete`

Permission `endpoint.unpublish` · **writes**

Unpublish an endpoint by its path. The pipeline is untouched — only the URL stops answering. Key bindings on that path are NOT removed: they describe a node of the tree, which may still carry other endpoints beneath it.

| Argument | Type | | What it is |
|---|---|---|---|
| `path` | string | required | The published path PATTERN, e.g. /finance/v1/revenue/{region}. |

## lake

### `lake_tables_register`

Permission `lake_table.manage` · **writes**

Register one table in a LAKE datasource's catalog (the dp-lake registry). Mirrors POST /api/v1/datasources/{name}/tables: namespace (array of segments or the dotted 'acme.analytics' shorthand), table, format (parquet | iceberg) and location are required; partition_column is optional. The location is s3://bucket/prefix/ (parquet: a directory or glob; iceberg: the table's CURRENT metadata file, e.g. s3://bucket/table/metadata/00042-<uuid>.metadata.json — DuckDB 1.5.5 cannot scan a pyiceberg table by its root, so register the file, and re-register it when the table commits) or a file:// path — no other scheme, and no quotes, backslashes, whitespace or control characters (it is interpolated into the engine's CREATE VIEW, so the refusal is total). Segments follow the pipeline/template segment grammar without dots. Registering an already-registered (namespace, table) is the 409 datasource.lake_table_duplicate; a non-LAKE datasource is refused. Mutating.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Datasource name. A LAKE datasource visible in the key's pinned workspace. |
| `namespace` | any | required | Namespace — a segments array or the dotted shorthand. 1-9 segments, the segment grammar without dots. |
| `table` | string | required | The table's name — one segment of the same grammar, e.g. events_by_day. |
| `format` | string (`parquet` \| `iceberg`) | required |  |
| `location` | string | required | s3://bucket/prefix/ (parquet dir/glob; iceberg: the current metadata file, not the table root) or file:// path. Nothing else; no injection chars. |
| `partition_column` | string | optional | Optional. The hive-style partition column, e.g. event_date. |

### `lake_tables_import`

Permission `lake_table.manage` · **writes**

Bulk-register lake tables from a manifest's `tables[]` shape. Mirrors POST /api/v1/datasources/{name}/tables/import: pass EITHER tables (an array of {name, format, location|path, partition_column?, namespace?}, with publish_prefix for relative paths and an optional shared namespace) OR manifest_url. A manifest URL is fetched server-side ONLY from the datasource's own endpoint/bucket — derived from its declared dialect.endpoint / catalog.ref, or AWS S3 when neither is set; anything else is refused with datasource.validation.lake_manifest_url_forbidden (no arbitrary URL fetch — SSRF). Import is idempotent: already-registered tables are reported in already_registered, not errors. Mutating.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Datasource name. A LAKE datasource visible in the key's pinned workspace. |
| `tables` | array of object | optional | Inline form: manifest entries {name, format, location\|path, partition_column?, namespace?}. |
| `namespace` | any | optional | Shared namespace applied to entries that carry none — an array of segments or the dotted shorthand. |
| `publish_prefix` | string | optional | Base URI resolving relative entry paths, e.g. s3://bucket/lake/v1. |
| `manifest_url` | string | optional | URL of a manifest.json. Fetched ONLY from the datasource's own endpoint/bucket or AWS S3; else refused. |

### `lake_tables_unregister`

Permission `lake_table.manage` · **writes**

Unregister one table from a LAKE datasource's catalog. Mirrors DELETE /api/v1/datasources/{name}/tables/{namespace}/{table}: the objects in the bucket are untouched — the table stops being served by the datasource. Unregistering a table that is not registered is the 404 datasource.lake_table_not_found, never a silent no-op. Mutating.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | Datasource name. A LAKE datasource visible in the key's pinned workspace. |
| `namespace` | any | required | The table's namespace, outermost first — an array of segments or the dotted shorthand ('acme.analytics'). |
| `table` | string | required | The table to unregister. |

## semantics

### `semantics_record`

Permission `semantic.record` · **writes**

Record ONE fact you learned about a datasource that introspection could not tell you — a unit, a time zone, a sample rate, a grain, what a coded value means, a join that holds, a trap — so the next session reads it beside the columns instead of probing again. Never record what introspection already returns (types, keys, comments). refs name the table(s) and column(s) the fact is about, structurally; every ref is checked against the live schema and an unknown one is refused. The fact's text must agree with its refs: a catalog table the text names but refs omit is ADDED to the stored refs for you (refs_added in the result names what was added); a near-miss of a listed table is refused as semantics.ref_mismatch (the refusal names the nearest listed table). A lake table's ref takes schema as ONE dotted namespace string ({"schema": "lake.mart", "table": "events"}), never a namespace array. Pass evidence_sql (the SELECT that showed the fact): it runs once, its first rows become evidence_summary, and the fact is stored as observed — without it the fact is only asserted. scope DATASOURCE is about the data over the table's WHOLE window and is shared with every workspace the datasource is granted to — an observation tied to one window belongs in the pipeline description, not in a fact; scope WORKSPACE (definition, exclusion, preference) is this organisation's meaning and stays here — and a WORKSPACE rule may carry NO refs: a rule that spans datasources ('busiest day = A + B combined') is recorded once, against the datasource the question is mostly about, and names no table; a DATASOURCE fact always names at least one (a unit without a column is meaningless — semantics.fact_invalid). To correct a stale or wrong fact, record the replacement with supersedes: the old one is retired as superseded. An identical live fact is refused as semantics.duplicate. Mutating.

| Argument | Type | | What it is |
|---|---|---|---|
| `scope` | string (`DATASOURCE` \| `WORKSPACE`) | required | DATASOURCE: a fact about the data, visible wherever the datasource is granted. WORKSPACE: this organisation's meaning, visible here only. |
| `datasource` | string | required | Datasource name (must be granted to this workspace). |
| `kind` | string (`unit` \| `time_zone` \| `sampling` \| `grain` \| `window` \| `enum_meaning` \| `join` \| `caveat` \| `format` \| `definition` \| `exclusion` \| `preference`) | required | What the fact is about. unit, time_zone, sampling, grain, window, enum_meaning, join, caveat, format are DATASOURCE kinds; definition, exclusion, preference are WORKSPACE kinds. |
| `fact` | string | required | The fact, in one or two sentences, specific enough to act on: 'value is already in the unit named by unit_col', 'pickup_ts is naive local time (America/New_York)'. |
| `refs` | array of object | optional | The object(s) the fact is about. One ref for a column fact, two for a join, a column-less ref for a table-grain fact. Required (at least one) for a DATASOURCE kind; a WORKSPACE rule that spans datasources omits it. |
| `evidence_sql` | string | optional | ONE read-only SELECT/WITH that shows the fact (no :parameters). Runs once at record time under the sql_probe rules; a statement that fails refuses the record. |
| `evidence_summary` | string | optional | What the evidence showed, in your words. Defaults to the probe's first rows. |
| `source_pipeline_id` | string | optional | The pipeline you learned this while building, if any. Shown only to readers who can read that pipeline. |
| `source_version` | integer | optional |  |
| `supersedes` | string | optional | The id of the fact this one replaces (a stale or wrong one); it is retired with reason superseded. |

### `semantics_list`

Permission `semantic.read` · read-only

List the learned facts recorded on a datasource this workspace can see — every DATASOURCE fact (whoever recorded it) and this workspace's own WORKSPACE facts — with trust, drift, refs, the evidence SQL and who recorded it through what. The same facts also arrive inline on datasources_get / _get_tables / _get_columns, which is where to read them while authoring; use this to review, to find a fact's id to supersede or retire, or to answer 'what was recorded since <time>'. Retired facts are hidden unless include_retired.

| Argument | Type | | What it is |
|---|---|---|---|
| `datasource` | string | required | Datasource name. |
| `table` | string | optional | Only facts with a ref on this table. |
| `scope` | string (`DATASOURCE` \| `WORKSPACE`) | optional |  |
| `include_retired` | boolean, default `false` | optional |  |
| `since` | string | optional | Only facts recorded at or after this ISO-8601 instant. |

### `semantics_retire`

Permission `semantic.retire` · **writes**

Retire one learned fact with a reason — it stops being served beside the columns but keeps its row (facts are never deleted; history is the audit). Prefer semantics_record with supersedes when you know the correct fact: that retires the old one and records the new in one step. A fact this workspace cannot see is not-found; a DATASOURCE fact another workspace established needs datasource.manage — the permission decides, not a role. Mutating.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required | The fact's id, from semantics_list or an introspection response. |
| `reason` | string | required | Why — one sentence, kept on the row and in the audit log. |

## docs

### `docs_list`

Permission `docs.read` · read-only

The datapipelines skill's document catalog: the operating core (`skill`) first, then every reference in the order the skill's own map lists them, each with its title and a one-line purpose (when to open it). The same documents the datapipelines://docs/skill resources serve, for clients that fetch resources reluctantly or never. Read-only.

No arguments.

### `docs_get`

Permission `docs.read` · read-only

One datapipelines skill document's full markdown: `name` is `skill` for the operating core or a reference name from docs_list. Returns {name, title, markdown} — the same bytes the datapipelines://docs/skill/<name> resource serves. An unknown name is refused with the catalogued names in the error detail. Read-only.

| Argument | Type | | What it is |
|---|---|---|---|
| `name` | string | required | The document name: `skill` for the operating core, or a reference name from docs_list, e.g. authoring-playbook. |

## pipelines

### `pipelines_run_checks`

Permission `pipeline.run_checks` · **writes**

Run a pipeline version's release checks (checks[]) NOW, against their own datasources. The SERVER runs every check and persists one pipeline_check_runs row per check before returning — only the server's own run produces `observed`, and there is deliberately no pipelines_record_check tool: no tool records an observed value from a caller. Each run's verdict is pass (observed satisfied expected), fail (a value was produced and did not satisfy it), or error (no verdict could be formed: the datasource was unresolvable or unreachable, the statement was refused or returned a shape the expectation cannot compare, or the parameters did not bind — the truth recorded, never silently a fail). With no version the WORKING version's checks run (the draft when one exists, else the latest released). Returns {version, runs: [{check_id, name, expected, observed, verdict, message, ran_at}]}; an empty checks[] returns an empty runs array.

| Argument | Type | | What it is |
|---|---|---|---|
| `id` | string | required |  |
| `version` | integer | optional | Specific version whose checks to run. Defaults to the WORKING version: the draft when one exists, else the latest released. Never clamped — an unknown version is refused, not rounded to the latest. |
| `parameters` | object | optional | Object whose keys match the pipeline's declared parameters — the same binding pipelines_execute uses: undeclared keys are ignored, defaults fill the execute way, and the calculator context is NOT available to a check. Values must match the declared types (BIGINTEGER and BIGDECIMAL as strings, others as JSON native types). |
