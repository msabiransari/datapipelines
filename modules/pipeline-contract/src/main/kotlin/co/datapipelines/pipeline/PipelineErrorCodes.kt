package co.datapipelines.pipeline

/**
 * The system-wide error-code catalog, transcribed from
 * [pipeline-contract §12 and §13](../../../../../../../docs/pipeline-contract.md).
 *
 * pipeline-contract.md is the **single authority** for concrete error codes (README house
 * rules; enums.md §16 registers only the domain prefixes and points here). Every other
 * module reads its codes from this object rather than writing string literals, so there is
 * exactly one place a code can be misspelled — and `PipelineErrorCodesSpecDriftTest` reads
 * the document and fails if this object and §12/§13 ever disagree in either direction.
 *
 * Codes are `{domain}.{entity}.{failure}`, lowercase, dot-separated, ASCII, and
 * **additive** — never reused, never renamed (§13). Two-segment codes exist only where the
 * domain has no entity dimension (`datasource.in_use`, `rate_limit.exceeded`).
 *
 * The catalog carries codes this module never raises (auth, template, datasource, result,
 * staging, node execution). That is deliberate: the catalog is system-wide, and a module
 * that owns half a list is a list that drifts.
 */
object PipelineErrorCodes {
    /** §12 — pipeline validation, all write-time, all HTTP 400. */
    object Validation {
        /** §12.1 — `schema_version` is supported (currently only `1`). */
        const val SCHEMA_VERSION_UNSUPPORTED = "pipeline.validation.schema_version_unsupported"

        /** §12.1 — `name` matches `[a-z0-9_]+`, length 1–63. */
        const val NAME_INVALID = "pipeline.validation.name_invalid"

        /**
         * §13.1 (094) — an AGENT asked to create a pipeline under a top-level folder that has
         * nothing in it yet, without `confirm_new_root: true`. `details.existing_roots` lists
         * the roots that do exist. MCP-only: a person choosing a folder in the UI, and an
         * operator over REST, have already decided.
         */
        const val NEW_ROOT_REQUIRES_CONFIRMATION = "pipeline.validation.new_root_requires_confirmation"

        /** §12.1 — all node `id` values are unique. */
        const val DUPLICATE_NODE_ID = "pipeline.validation.duplicate_node_id"

        /**
         * §12.1 — the pipeline `name` is not already taken. HTTP 409.
         *
         * The only §12 code raised from the **database**, not from the document:
         * [PipelineRepository] maps the `pipelines.name` UNIQUE violation to it. A read-then-write
         * pre-check cannot replace that — two concurrent creates both pass the read and one still
         * violates the constraint — so the constraint stays the authority and this is its
         * translation.
         */
        const val DUPLICATE_NAME = "pipeline.validation.duplicate_name"

        /** §12.1 — `output.table` values are unique per namespace (§10.1). */
        const val DUPLICATE_OUTPUT_TABLE = "pipeline.validation.duplicate_output_table"

        /** §12.1 — `output.table` and node `id` match `[a-z0-9_]+`. */
        const val INVALID_IDENTIFIER = "pipeline.validation.invalid_identifier"

        /** §12.1 — no node id or table name is `tempdb` or matches the reserved `__…__` shape. */
        const val RESERVED_IDENTIFIER = "pipeline.validation.reserved_identifier"

        /** §12.1 — no scanned field carries an env-specific value (§11.4). */
        const val FORBIDDEN_ENV_SPECIFIC_VALUE = "pipeline.validation.forbidden_env_specific_value"

        /** §12.2 — every id in every `depends_on` exists in `nodes`. */
        const val DANGLING_DEPENDENCY = "pipeline.validation.dangling_dependency"

        /** §12.2 — the dependency graph is acyclic. */
        const val CYCLE_DETECTED = "pipeline.validation.cycle_detected"

        /** §12.2 — `nodes` is non-empty. */
        const val EMPTY_PIPELINE = "pipeline.validation.empty_pipeline"

        /**
         * §12.2 — `nodes` count ≤ 1000. HTTP 400.
         *
         * Defence in depth on top of §12.2's crash-safety rule, not a substitute for it: the
         * validator must survive hostile input at any size, and this caps the work one save can
         * commission.
         */
        const val PIPELINE_TOO_LARGE = "pipeline.validation.pipeline_too_large"

        /** §12.3 / §9.2 — at most one node resolves to `output.target: "caller"` (D1). */
        const val MULTIPLE_CALLER_NODES = "pipeline.validation.multiple_caller_nodes"

        /** §12.4 — each node `type` is one of `DQL`, `DML`, `DDL`. */
        const val TYPE_INVALID = "pipeline.validation.type_invalid"

        /** §12.4 — DML nodes must NOT have an `output` block. */
        const val DML_HAS_OUTPUT = "pipeline.validation.dml_has_output"

        /** §12.4 — DDL nodes must NOT have an `output` block. */
        const val DDL_HAS_OUTPUT = "pipeline.validation.ddl_has_output"

        /**
         * §9.2 — a DML/DDL node carries an `output` block, stated from the caller-node
         * angle. §9.2 defines it as the *same check* as [DML_HAS_OUTPUT] / [DDL_HAS_OUTPUT],
         * so the validator emits those two and never this alias; the constant exists so the
         * catalog is complete and the spelling has one home.
         */
        const val NON_DQL_CALLER_TARGET = "pipeline.validation.non_dql_caller_target"

        /** §12.4 — `output.target` (when the block is present) is `tempdb`, `caller` or `datasource`. */
        const val OUTPUT_TARGET_INVALID = "pipeline.validation.output_target_invalid"

        /** §12.4 — `output.target: "tempdb"` requires `output.table`. */
        const val OUTPUT_TABLE_MISSING = "pipeline.validation.output_table_missing"

        /** §12.4 — `output.target: "datasource"` requires `output.datasource` and `output.table`. */
        const val OUTPUT_DATASOURCE_MISSING = "pipeline.validation.output_datasource_missing"

        /** §12.4 — `output.mode` is `replace` or `append`. */
        const val OUTPUT_MODE_INVALID = "pipeline.validation.output_mode_invalid"

        /** §12.5 — every `source` (except `tempdb`) and every `output.datasource` is registered. */
        const val UNKNOWN_DATASOURCE = "pipeline.validation.unknown_datasource"

        /**
         * §12.5 — no write-shaped use names a readonly datasource (workspaces design §6, D6).
         *
         * The three and only three write shapes: a `DML` node's `source`, a `DDL` node's
         * `source`, and any node's `output.target: "datasource"`. `details` carries the node
         * id, the datasource name, and which shape fired (`dml_source` / `ddl_source` /
         * `output_target`) — the agent needs the pointer, not three enum values. DQL reads and
         * everything `tempdb` are untouched.
         */
        const val DATASOURCE_READONLY = "pipeline.validation.datasource_readonly"

        /** §12.6 — every `template.id` exists in the template registry. */
        const val TEMPLATE_NOT_FOUND = "pipeline.validation.template_not_found"

        /** §12.6 — every `template.version` exists for that template id. */
        const val TEMPLATE_VERSION_NOT_FOUND = "pipeline.validation.template_version_not_found"

        /** §12.6 — the template's dialect matches the node's source dialect. */
        const val TEMPLATE_DIALECT_MISMATCH = "pipeline.validation.template_dialect_mismatch"

        /**
         * §12.6 (046, template-hierarchy-design §7) — a DQL/DML/DDL node references a
         * `type='html'` template. Every template a node can legally reference is `sql`;
         * `details` carries `template_type`.
         */
        const val TEMPLATE_TYPE_MISMATCH = "pipeline.validation.template_type_mismatch"

        /** §12.6 — save-time dry-render found a Freemarker variable no parameter declares (D3). */
        const val TEMPLATE_PARAMETER_UNDECLARED = "pipeline.validation.template_parameter_undeclared"

        /**
         * §12.6 — the save-time dry render failed for a reason other than an undeclared
         * variable: a type-mismatched built-in, an unresolvable imported macro, an expression
         * error. HTTP 400.
         *
         * **Not** [Node.TEMPLATE_RENDER_FAILED.](PipelineErrorCodes.Node) That one is
         * `pipeline.node.template_render_failed` from §13.4 — HTTP 500, raised by the executor
         * when a render fails at *run* time. Same English, different domain segment, different
         * status, different section; collapsing them would make a 400 an author can fix
         * indistinguishable from a 500 an operator must page on.
         */
        const val TEMPLATE_RENDER_FAILED = "pipeline.validation.template_render_failed"

        /** §12.7 — each parameter `type` is one of the 10 allowed canonical types (NULL excluded). */
        const val PARAMETER_TYPE_INVALID = "pipeline.validation.parameter_type_invalid"

        /** §12.7 / §6.1 — every parameter key matches `[a-z0-9_]+`. */
        const val PARAMETER_NAME_INVALID = "pipeline.validation.parameter_name_invalid"

        /**
         * §12.7 — `precision` present when the type is `DECIMAL`.
         *
         * `BIGDECIMAL` precision is **optional**: omitted means unbounded, exactly as in
         * type-system §4 (adjudicated 2026-08-08 — a declared parameter follows the same
         * semantics as a derived column).
         */
        const val PARAMETER_PRECISION_MISSING = "pipeline.validation.parameter_precision_missing"

        /** §12.7 — `scale` present when the type is `BIGDECIMAL` (or `DECIMAL` with exact semantics). */
        const val PARAMETER_SCALE_MISSING = "pipeline.validation.parameter_scale_missing"

        /** §12.7 / §6.2 — `required: true` and `default` are not both set. */
        const val CONFLICTING_REQUIRED_DEFAULT = "pipeline.validation.conflicting_required_default"

        /** §12.7 — the `default` value's JSON type matches the declared type's wire encoding. */
        const val DEFAULT_TYPE_MISMATCH = "pipeline.validation.default_type_mismatch"

        /** §12.8 — `settings.tempdb.engine` is `H2` (v1). */
        const val TEMPDB_ENGINE_UNSUPPORTED = "pipeline.validation.tempdb_engine_unsupported"

        /** §12.8 — `settings.tempdb.config` keys are valid for the chosen engine. */
        const val TEMPDB_CONFIG_INVALID = "pipeline.validation.tempdb_config_invalid"

        /**
         * §12.8 (108) — a node's `settings.timeout_seconds` is not a positive integer, or it
         * exceeds `datapipelines.executor.node-timeout-max-seconds` (default 900).
         *
         * Refused at SAVE time rather than clamped at run time: an author who asks for 4 hours
         * and silently gets 15 minutes debugs the wrong thing. `details` carries the requested
         * value and the ceiling.
         */
        const val NODE_TIMEOUT_INVALID = "pipeline.validation.node_timeout_invalid"

        /** §12.9 — a PIPELINE node's `pipeline.name` exists in the pipeline registry. */
        const val PIPELINE_NOT_FOUND = "pipeline.validation.pipeline_not_found"

        /** §12.9 — the pinned `pipeline.version` exists for that name. */
        const val PIPELINE_VERSION_NOT_FOUND = "pipeline.validation.pipeline_version_not_found"

        /**
         * §12.9 (101, D58) — a PIPELINE node pinned a child pipeline version that is not
         * RELEASED. Composition references reviewed content only: a DRAFT child can be
         * purged out from under its parent, which an exact-version pin must never allow.
         * A DISCARDED child version is refused by the same rule.
         */
        const val PIPELINE_REFERENCE_NOT_RELEASED = "pipeline.validation.pipeline_reference_not_released"

        /** §12.9 — a PIPELINE node must not reference its containing pipeline. */
        const val PIPELINE_SELF_REFERENCE = "pipeline.validation.pipeline_self_reference"

        /**
         * §12.9 — the referenced pipeline's entity is DISCARDED (every version discarded;
         * derived since V19 — the old `is_deleted` read). Blocks NEW references at save
         * time only; existing pinned references keep resolving (D7).
         */
        const val PIPELINE_REFERENCE_DELETED = "pipeline.validation.pipeline_reference_deleted"

        /** §12.9 — a PIPELINE node carries no `source` (it runs no SQL of its own). */
        const val PIPELINE_NODE_HAS_SOURCE = "pipeline.validation.pipeline_node_has_source"

        /** §12.9 — a PIPELINE node carries no `template` (it runs no SQL of its own). */
        const val PIPELINE_NODE_HAS_TEMPLATE = "pipeline.validation.pipeline_node_has_template"

        /** §12.9 — every required-without-default child parameter is supplied. */
        const val PIPELINE_PARAMETER_UNMAPPED = "pipeline.validation.pipeline_parameter_unmapped"

        /**
         * §12.9 — every supplied key exists in the child's `parameters` or names one of its
         * CALCULATOR `context_key`s (078 A5-composition: a supplied key skips the child's node).
         */
        const val PIPELINE_PARAMETER_UNKNOWN = "pipeline.validation.pipeline_parameter_unknown"

        /**
         * §12.9 — a literal obeys the child target's §6.3 wire encoding; a `${ref}` resolves
         * against the parent's Context tiers (a declared parameter, a parent calculator
         * `context_key`, an org/platform key) to a value of the identical type. An ANY-output
         * key on either side skips the check — typed only by the run.
         */
        const val PIPELINE_PARAMETER_TYPE_MISMATCH = "pipeline.validation.pipeline_parameter_type_mismatch"

        /** §12.9 — `output` is absent when the pinned child has zero caller nodes. */
        const val PIPELINE_OUTPUT_ON_SIDEEFFECT_CHILD = "pipeline.validation.pipeline_output_on_sideeffect_child"

        /** §12.9 — the static reference-tree depth is within the configured maximum. */
        const val COMPOSITION_TOO_DEEP = "pipeline.validation.composition_too_deep"

        /** §12.10 — a CALCULATOR node declares all three of `kind`, `inputs` and `context_key`. */
        const val CALCULATOR_NODE_INCOMPLETE = "pipeline.validation.calculator_node_incomplete"

        /** §12.10 — no other node type carries `kind`, `inputs` or `context_key`. */
        const val CALCULATOR_FIELDS_ON_NON_CALCULATOR = "pipeline.validation.calculator_fields_on_non_calculator"

        /** §12.10 — a CALCULATOR node carries no `template`, `source` or `output`. */
        const val CALCULATOR_NODE_HAS_SQL_FIELDS = "pipeline.validation.calculator_node_has_sql_fields"

        /** §12.10 — `kind` names a kind in the registry (calculators.md §2). */
        const val CALCULATOR_UNKNOWN = "pipeline.validation.calculator_unknown"

        /** §12.10 — every input the kind declares `required` is present. */
        const val CALCULATOR_INPUT_MISSING = "pipeline.validation.calculator_input_missing"

        /**
         * §12.10 — a supplied input name the kind does not declare, or a `$reference` naming a
         * Context key nothing provides.
         *
         * One code for both because they are one authoring mistake seen from two sides: the node
         * names something that is not there. `details` carries which (`input` / `reference`) and
         * what WAS available, which is the part an author acts on.
         */
        const val CALCULATOR_INPUT_UNKNOWN = "pipeline.validation.calculator_input_unknown"

        /** §12.10 — a literal input's JSON type contradicts the kind's declared input type. */
        const val CALCULATOR_INPUT_TYPE_MISMATCH = "pipeline.validation.calculator_input_type_mismatch"

        /**
         * §12.10 — a reference to another node's `context_key` from a node that does not
         * `depends_on` its producer. Covers both shapes: a calculator's `$ref` and a SQL node's
         * `:bind`. Sequencing is topology, never array order (§0.3), and without the edge the
         * reader's value depends on which node the scheduler happened to reach first.
         */
        const val CALCULATOR_INPUT_UNORDERED = "pipeline.validation.calculator_input_unordered"

        /**
         * §12.10 — `context_key` collides with a declared parameter or another node's key.
         *
         * Shadowing an org or platform key is legal (§0.2 tier 5); shadowing a PARAMETER is not,
         * because a parameter is the caller's input and a silently overwritten one makes an
         * execute request a lie.
         */
        const val CALCULATOR_OUTPUT_COLLISION = "pipeline.validation.calculator_output_collision"

        /** §12.10 / §6.1 — `context_key` matches `[a-z_][a-z0-9_]*`. */
        const val CALCULATOR_OUTPUT_NAME_INVALID = "pipeline.validation.calculator_output_name_invalid"
    }

    /** §13.2 — pipeline import. */
    object Import {
        const val MISSING_DATASOURCE = "pipeline.import.missing_datasource"
        const val MISSING_TEMPLATE = "pipeline.import.missing_template"
        const val VERSION_CONFLICT = "pipeline.import.version_conflict"

        /** §13.2 / versioning §9.2 — declared body_hash ≠ hash recomputed from the payload body. */
        const val HASH_MISMATCH = "pipeline.import.hash_mismatch"

        /**
         * §13.2 (072, calculators design §0.5) — the imported body binds a Context key this
         * deployment does not provide, an `org_*` key the target's yml does not define. HTTP 409.
         *
         * Refused rather than defaulted, and refused at IMPORT rather than at the first run: a
         * promoted pipeline reading a made-up currency or fiscal start produces wrong numbers
         * with no error anywhere, which is the one failure shape promotion exists to prevent.
         */
        const val CONTEXT_KEY_MISSING = "pipeline.import.context_key_missing"
    }

    /** §13.3 — pipeline execution (run-time). */
    object Execution {
        const val NOT_FOUND = "pipeline.execution.not_found"
        const val PARAMETER_REQUIRED = "pipeline.execution.parameter_required"
        const val INVALID_PARAMETER_TYPE = "pipeline.execution.invalid_parameter_type"
        const val ABORTED = "pipeline.execution.aborted"
        const val TIMEOUT = "pipeline.execution.timeout"
        const val CONCURRENCY_LIMIT = "pipeline.execution.concurrency_limit"
        const val NOT_RUNNING = "pipeline.execution.not_running"
        const val INSTANCE_LOST = "pipeline.execution.instance_lost"

        /** §13.8 — pre-execution reachability check failed for a referenced datasource. */
        const val DATASOURCE_UNREACHABLE = "pipeline.execution.datasource_unreachable"
    }

    /** §13.4 — node execution. */
    object Node {
        const val TEMPLATE_NOT_FOUND = "pipeline.node.template_not_found"
        const val TEMPLATE_RENDER_FAILED = "pipeline.node.template_render_failed"
        const val DATASOURCE_NOT_FOUND = "pipeline.node.datasource_not_found"

        /**
         * §13.4 / mcp-server §6.2.20 — a node-run debug query named a node id the resolved
         * pipeline version does not hold (037 E2). HTTP 404. `details` carries the node id and
         * the version that was searched — after versioning, "no such node" almost always means
         * "you are looking at the released body while the node lives in the draft" (the tool's
         * E5 default already prefers the draft, so this fires mostly on genuine typos).
         */
        const val NOT_FOUND = "pipeline.node.not_found"

        /**
         * §13.4 / mcp-server §6.2.20 — a node-run debug query refused because the node cannot
         * run standalone (037 §A/E2): its `source` is `tempdb` (the staging database exists
         * only inside a full execution — use `pipelines_execute`), or it is a PIPELINE node
         * (it runs a child pipeline, not SQL). HTTP 400. `details.reason` names which
         * (`tempdb_source` / `pipeline_node`); one code because both are the same verdict —
         * this node has no standalone SQL to run.
         */
        const val STANDALONE_EXECUTION_REFUSED = "pipeline.node.standalone_execution_refused"

        /**
         * §13.4 — a write-shaped node reached execution against a datasource whose live
         * registry entry is readonly (workspaces design §6 layer 2a, D10). Same HTTP class and
         * shape as [DATASOURCE_NOT_FOUND]: the datasource resolved at write-time, but the flag
         * flipped (or the stored version predates it) — the backstop re-checks the LIVE entry
         * at node execution time so the flip window between save and run cannot ship a write.
         */
        const val DATASOURCE_READONLY = "pipeline.node.datasource_readonly"
        const val DATASOURCE_CONNECTION_FAILED = "pipeline.node.datasource_connection_failed"
        const val QUERY_EXECUTION_FAILED = "pipeline.node.query_execution_failed"

        /**
         * §13.4 — the node's statement outlived its JDBC query timeout (the datasource's
         * `query_timeout_seconds`, else `node-query-timeout-seconds`) and the driver cancelled
         * it. HTTP 504, the sibling of [Execution.TIMEOUT]. Distinct from
         * [QUERY_EXECUTION_FAILED] on purpose: "your query is too slow for the budget" and "your
         * SQL is wrong" call for different fixes, and the driver text that used to be the only
         * clue (`INTERRUPT Error: Interrupted!`, `canceling statement due to user request`,
         * `Statement was canceled`) names none of ours.
         */
        const val QUERY_TIMEOUT = "pipeline.node.query_timeout"

        /**
         * §13.4 (108) — the node outlived its WALL-CLOCK deadline
         * (`node.settings.timeout_seconds`, else `datapipelines.executor.node-timeout-seconds`)
         * and the executor stopped it. HTTP 504.
         *
         * Distinct from [QUERY_TIMEOUT], which is one *statement's* budget enforced by the
         * driver. This one is the executor's own, spans the node's whole lifecycle — render,
         * connect, execute, stage, materialize — and fires whatever the driver does: a driver
         * that ignores `Statement.cancel()` is not waited on past
         * `datapipelines.executor.cancel-grace-seconds`, so the node fails on schedule and the
         * abandoned statement is logged once. That is the difference measured in 108 §1: a
         * Postgres node stopped at its statement budget while H2 tempdb nodes in the same
         * pipeline ran minutes past it, because no bound above the statement existed.
         *
         * `details` carries `timeout_seconds`, `elapsed_ms` and `phase` — the phase is what
         * tells an author whether to make the QUERY cheaper or the STAGE smaller.
         */
        const val TIMEOUT = "pipeline.node.timeout"
        const val STAGING_FAILED = "pipeline.node.staging_failed"
        const val WRITEBACK_FAILED = "pipeline.node.writeback_failed"
        const val WRITEBACK_TARGET_MISSING = "pipeline.node.writeback_target_missing"

        /**
         * §13.4 — a PIPELINE node's child execution failed; the detail carries the child's
         * error code and execution id, so the debugging trail leads to a real execution record.
         */
        const val CHILD_EXECUTION_FAILED = "pipeline.node.child_execution_failed"

        /**
         * §13.4 — the rendered SQL references a `:name` bind parameter the execution context
         * does not declare (042 C2). Raised before anything executes: a missing value bound as
         * null would return wrong data instead of an error, so the refusal is loud and names
         * the parameter.
         */
        const val SQL_PARAMETER_MISSING = "pipeline.node.sql_parameter_missing"

        /**
         * §13.4 (072) — a CALCULATOR node's evaluation failed. HTTP 500.
         *
         * Save-time validation (§12.10) has already refused everything decidable from the body,
         * so reaching here means the RUN produced the offending value: a `$reference` that
         * resolved to null, text that did not match its pattern, a zero denominator. The detail
         * carries the node id, the `kind` and the input at fault.
         */
        const val CALCULATOR_FAILED = "pipeline.node.calculator_failed"

        /**
         * §13.4 — the run-time composition-depth backstop fired. Reaching it means save-time
         * validation (§12.9 `composition_too_deep`) was bypassed, since the static depth check
         * over immutable pins should have caught the chain first.
         */
        const val COMPOSITION_DEPTH_EXCEEDED = "pipeline.node.composition_depth_exceeded"
    }

    /** §13.5 — staging (tempdb). */
    object Staging {
        const val VALUE_OVERFLOW = "pipeline.staging.value_overflow"
        const val PRECISION_OVERFLOW = "pipeline.staging.precision_overflow"
        const val ENGINE_UNAVAILABLE = "pipeline.staging.engine_unavailable"
        const val CREATION_FAILED = "pipeline.staging.creation_failed"
        const val CLEANUP_FAILED = "pipeline.staging.cleanup_failed"
        const val MEMORY_LIMIT_EXCEEDED = "pipeline.staging.memory_limit_exceeded"
        const val INVALID_COLUMN_NAME = "pipeline.staging.invalid_column_name"
        const val TABLE_ALREADY_EXISTS = "pipeline.staging.table_already_exists"
    }

    /** §13.6 — type mapping. Warnings, not errors: they travel in the response `warnings` array. */
    object TypeMapping {
        const val UNKNOWN_SOURCE_TYPE = "type_mapping.unknown_source_type"
        const val SQL_VARIANT = "type_mapping.sql_variant"
    }

    /** §13.7 — authentication / authorization. Defined in auth.md §9; cataloged here (D5). */
    object Auth {
        const val API_KEY_MISSING = "auth.api_key.missing"
        const val API_KEY_INVALID = "auth.api_key.invalid"
        const val API_KEY_EXPIRED = "auth.api_key.expired"
        const val SESSION_INVALID = "auth.session.invalid"
        const val SESSION_EXPIRED = "auth.session.expired"
        const val SCOPE_INSUFFICIENT = "auth.scope.insufficient"
        const val CSRF_INVALID = "auth.csrf.invalid"
        const val LOGIN_DOMAIN_NOT_ALLOWED = "auth.login.domain_not_allowed"
        const val LOGIN_USER_INACTIVE = "auth.login.user_inactive"
        const val LOGIN_BAD_CREDENTIALS = "auth.login.bad_credentials"
        const val LOGIN_LOCKED = "auth.login.locked"
        const val PASSWORD_CHANGE_REQUIRED = "auth.password.change_required"
        const val SESSION_REQUIRED = "auth.session.required"

        /**
         * §13.7 (091) — key issuance named an expiry this surface cannot use: an unknown
         * preset, an unparseable custom date, a date already past, or `custom` with none.
         * The one `auth.api_key.` code that is a 400: the credential is fine, the body is not.
         */
        const val API_KEY_EXPIRY_INVALID = "auth.api_key.expiry_invalid"

        /**
         * §13.7 / versioning §10.6 — the promotion peer's pre-shared server key was absent,
         * malformed, or did not match. The SAME code answers a receiver with no key
         * configured: promotion is disabled there, fail-closed, and one code keeps the
         * response from distinguishing a wrong key from a disabled receiver.
         */
        const val PROMOTION_KEY_INVALID = "auth.promotion.key_invalid"

        /**
         * §13.7 — the principal's ROLE in the active workspace is below the operation's
         * (RBAC design §2, D-R1). Two segments, not three: the role axis has no ENTITY
         * dimension — it is a property of the caller's membership, not of a thing they named.
         */
        const val ROLE_REQUIRED = "auth.role_required"

        /** §13.7 — the key was valid; its issuer no longer holds the capability (D-R12). */
        const val KEY_ISSUER_ROLE_LOST = "auth.key_issuer_role_lost"

        /** §13.7 — issuance asked for a scope keys may no longer hold; today that is `admin` (O-2). */
        const val KEY_SCOPE_UNAVAILABLE = "auth.key_scope_unavailable"

        /** §13.7 — the workspace this key is pinned to has been deactivated (D-R10). */
        const val KEY_WORKSPACE_INACTIVE = "auth.key_workspace_inactive"
    }

    /** §13.8 — datasource. Defined in datasources.md §9–10; cataloged here (D5). */
    object Datasource {
        const val NAME_INVALID = "datasource.validation.name_invalid"
        const val DIALECT_INVALID = "datasource.validation.dialect_invalid"
        const val JDBC_URL_MALFORMED = "datasource.validation.jdbc_url_malformed"
        const val JDBC_URL_SCHEME_INVALID = "datasource.validation.jdbc_url_scheme_invalid"
        const val PASSWORD_MISSING = "datasource.validation.password_missing"
        const val PROPERTIES_INVALID = "datasource.validation.properties_invalid"

        /**
         * §13.8 (109 §B) — a DECLARED dialect property carries an empty, whitespace-only or null
         * value: refused at register/update (and bootstrap) rather than stored as `""`. 400 —
         * the fix is the caller's; the field names the key.
         */
        const val PROPERTY_EMPTY = "datasource.validation.property_empty"
        const val QUERY_TIMEOUT_INVALID = "datasource.validation.query_timeout_invalid"
        const val DUPLICATE_NAME = "datasource.validation.duplicate_name"

        /** §13.8 — a D8 refusal: non-admin attempted `global`/readonly-on-global, or a workspace binding they are not in. */
        const val WORKSPACE_FORBIDDEN = "datasource.validation.workspace_forbidden"

        const val IN_USE = "datasource.in_use"
        const val NOT_FOUND = "datasource.not_found"
        const val DRIVER_NOT_LOADED = "datasource.driver_not_loaded"

        /**
         * A customer-database connection was requested while a METADATA transaction was open on
         * the thread — refused by design (056 §E.2; the model is dag-executor §16). A server
         * fault, not a caller error: nothing a caller sends can produce it.
         */
        const val LEASE_IN_TRANSACTION = "datasource.lease_in_transaction"

        /** §13.8 (089 §A) — a lake-table operation was attempted on a datasource whose dialect is not `LAKE`. */
        const val LAKE_DIALECT_REQUIRED = "datasource.validation.lake_dialect_required"

        /** §13.8 (089 §A) — a lake table's `namespace` fails the segment grammar (the §4.1 segment, minus `.`). */
        const val LAKE_NAMESPACE_INVALID = "datasource.validation.lake_namespace_invalid"

        /** §13.8 (089 §A) — a lake table's `name` fails the segment grammar. */
        const val LAKE_NAME_INVALID = "datasource.validation.lake_name_invalid"

        /** §13.8 (089 §A) — a lake table's `format` is not `parquet` or `iceberg` (metadata-db §4.15's closed set). */
        const val LAKE_FORMAT_INVALID = "datasource.validation.lake_format_invalid"

        /** §13.8 (089 §A) — a lake table's `location` fails the scheme allowlist or the total injection refusal. */
        const val LAKE_LOCATION_INVALID = "datasource.validation.lake_location_invalid"

        /** §13.8 (089 §A) — an import manifest URL outside the datasource's own endpoint/bucket (the SSRF boundary). */
        const val LAKE_MANIFEST_URL_FORBIDDEN = "datasource.validation.lake_manifest_url_forbidden"

        /** §13.8 (089 §A) — the (datasource, namespace, name) triple is already registered (409). */
        const val LAKE_TABLE_DUPLICATE = "datasource.lake_table_duplicate"

        /** §13.8 (089 §A) — unregister named a lake table that is not registered (404). */
        const val LAKE_TABLE_NOT_FOUND = "datasource.lake_table_not_found"

        /**
         * §13.8 — the datasource exists on the instance but is not GRANTED to the caller's
         * workspace (D-R7). A 404, because an ungranted datasource is INVISIBLE: "this exists,
         * you may not see it" turns the flat, global datasource namespace into an enumeration
         * oracle one request at a time.
         */
        const val GRANT_REQUIRED = "datasource.grant_required"

        /**
         * §13.8 (109 §A) — a pipeline node referenced a registered lake table whose connect-time
         * view creation is recorded as failed (`lake_tables.last_error`, V20): the table's view
         * is skipped on every connection, so the engine could only answer "table not found".
         * 502 — a party behind us (the object store's content) failed; `details` carry `table`
         * and the recorded `last_error`.
         */
        const val LAKE_TABLE_UNAVAILABLE = "datasource.lake.table_unavailable"

        /**
         * §13.8 (109 §A) — a lake-table registration/import named a table the pre-flight could
         * not read (its view would not create, or a one-row scan through it failed). Refused
         * BEFORE storing, with the bounded engine error as the message — 400: the fix is the
         * caller's (the location, the format, the file).
         */
        const val LAKE_TABLE_UNREADABLE = "datasource.validation.lake_table_unreadable"
    }

    /** §13.9 — template. Defined in templates.md §7; cataloged here (D5). */
    object Template {
        const val SYNTAX_ERROR = "template.validation.syntax_error"
        const val DANGEROUS_CONSTRUCT = "template.validation.dangerous_construct"
        const val ID_INVALID = "template.validation.id_invalid"

        /**
         * §13.9 (094) — the template twin of
         * [Validation.NEW_ROOT_REQUIRES_CONFIRMATION]: an AGENT asked to create a template
         * under a top-level folder that has nothing in it yet, without `confirm_new_root: true`.
         */
        const val NEW_ROOT_REQUIRES_CONFIRMATION = "template.validation.new_root_requires_confirmation"
        const val DIALECT_INVALID = "template.validation.dialect_invalid"

        /**
         * §13.9 (046, template-hierarchy-design §5.2/§7) — `dialect` is present on a
         * `type='html'` template. Deliberately distinct from [DIALECT_INVALID] (unknown
         * dialect value): a different failure gets a different, greppable code.
         */
        const val DIALECT_NOT_ALLOWED = "template.validation.dialect_not_allowed"

        /**
         * §13.9 (046, template-hierarchy-design §5.4) — `type` is not `sql` or `html`. The
         * wire-value refusal for the new optional field, exactly the [DIALECT_INVALID] pattern;
         * without it an unknown value would surface as a raw Jackson enum-coercion failure.
         */
        const val TYPE_INVALID = "template.validation.type_invalid"

        /**
         * §13.9 (046, template-hierarchy-design §5.3) — a payload attempted to change a
         * template's `type`, which is fixed at creation and identical across every version.
         */
        const val TYPE_IMMUTABLE = "template.validation.type_immutable"

        /**
         * §13.9 — the body contains an HTML entity for a SQL operator (`&lt;`, `&gt;`, `&amp;`,
         * `&quot;`, `&#39;`). No SQL dialect reads those; a body carrying one was HTML-escaped
         * somewhere between the author and the server (an agent client, a copy from a rendered
         * page) and would fail at execution with an H2/driver syntax error far from its cause
         * (pipeline-3 audit, 2026-09-11: `WHERE rn &lt;= :top_n`). Refused at save with the
         * entity named, so the fix is one edit and not a full run.
         */
        const val HTML_ENTITY = "template.validation.html_entity"
        const val ENGINE_UNSUPPORTED = "template.validation.engine_unsupported"
        const val SCHEMA_VERSION_UNSUPPORTED = "template.validation.schema_version_unsupported"
        const val IS_LIBRARY_WITHOUT_MACROS = "template.validation.is_library_without_macros"
        const val IMPORT_NOT_FOUND = "template.validation.import_not_found"
        const val IMPORT_NOT_LIBRARY = "template.validation.import_not_library"
        const val IMPORT_CYCLE = "template.validation.import_cycle"
        const val IMPORT_DEPTH_EXCEEDED = "template.validation.import_depth_exceeded"
        const val DUPLICATE_ALIAS = "template.validation.duplicate_alias"

        /**
         * §13.9 — a declared pipeline parameter name appears inside a `${}` interpolation
         * (042 B2): declared parameters are values and must be referenced as `:name`, bound
         * as SQL parameters. Interpolation is for structure only, and this refusal is what
         * makes the bind form the *only* way a value can reach SQL from a new template.
         */
        const val PARAMETER_INTERPOLATED = "template.validation.parameter_interpolated"

        /**
         * §13.9 — name already exists in this workspace (UNIQUE(workspace_id, name),
         * soft-deleted included); mirrors `pipeline.validation.duplicate_name`.
         */
        const val DUPLICATE_NAME = "template.validation.duplicate_name"

        const val NOT_FOUND = "template.not_found"

        /**
         * §13.9 (040 D4) — delete refused while any pipeline version pins any version of the
         * template (the any-version scan; `details` names the referencing pipelines, nodes and
         * carrying pipeline versions). The `datasource.in_use` / `workspace.in_use` shape: an
         * in-use refusal, not a validation failure. Soft delete stays the only deletion — this
         * code guards it, it does not harden it into hard deletion.
         */
        const val IN_USE = "template.in_use"

        /** §13.9 / versioning §4.2 — hash precondition failed on a template draft mutation. */
        const val VERSION_CONFLICT = "template.version.conflict"

        /** §13.9 / versioning §3 — release/discard requested but no DRAFT version exists. */
        const val VERSION_NOT_DRAFT = "template.version.not_draft"

        /**
         * §13.9 / versioning §3.1 (101) — discard targeted a version that is not RELEASED
         * (a DRAFT is purged, never discarded; a DISCARDED version is already retired).
         */
        const val VERSION_NOT_RELEASED = "template.version.not_released"

        /** §13.9 / versioning §3.1 (101) — restore targeted a version that is not DISCARDED. */
        const val VERSION_NOT_DISCARDED = "template.version.not_discarded"

        /**
         * §13.9 / versioning §3.5 (101, D57) — the purge path refused: a RELEASED version
         * is never purged, and an entity holding any non-draft version is never purged.
         * Discard is per version, the entity stays; restore or release something first.
         */
        const val VERSION_LAST_RELEASE = "template.version.last_release"

        /**
         * §13.9 / versioning §3.4 (101, D60) — manual switch targeted a version that is not
         * a live, posture-eligible version (DISCARDED, or a DRAFT under a hardened posture).
         */
        const val VERSION_NOT_ELIGIBLE = "template.version.not_eligible"

        /**
         * §13.9 / ui-screens §5.1 typed-confirm (102) — the typed-confirm guard on the
         * irreversible template purge dialogs: the `confirm` form field did not name what
         * the dialog asked the user to type (the version, or the template name on the
         * entity purge). Checked BEFORE the lifecycle service runs.
         */
        const val VERSION_CONFIRM_MISMATCH = "template.version.confirm_mismatch"

        /**
         * §13.13 / versioning §5.5 — the template-surface mirror of
         * `pipeline.authoring.disabled`.
         */
        const val AUTHORING_DISABLED = "template.authoring.disabled"
    }

    /** §13.10 — result retrieval. Defined in rest-api.md §7; cataloged here (D5/D9). */
    object Result {
        const val EXECUTION_NOT_FOUND = "result.execution_not_found"
        const val EXECUTION_INCOMPLETE = "result.execution_incomplete"
        const val EXECUTION_FAILED = "result.execution_failed"
        const val EXPIRED = "result.expired"
        const val FORMAT_UNSUPPORTED = "result.format_unsupported"
        const val TOO_LARGE = "result.too_large"
        const val STORAGE_UNAVAILABLE = "result.storage_unavailable"
    }

    /** §13.11 — rate limiting / idempotency. */
    object Limits {
        /** Single code for every layer — REST, MCP, login (D5). */
        const val RATE_LIMIT_EXCEEDED = "rate_limit.exceeded"

        /**
         * §13.11 (083, owner ruling 2026-09-06) — the limiter itself could not decide, so the
         * request is refused rather than admitted.
         *
         * Deliberately a DIFFERENT code from [RATE_LIMIT_EXCEEDED] at the same 429: a client that
         * cannot tell "you are over your budget" from "the limiter is down" backs off identically
         * to both, and only one of them is its own doing. The distinct code is what lets an agent
         * retry an outage while honouring a real throttle, and what lets an operator alert on the
         * second without drowning in the first.
         */
        const val RATE_LIMIT_UNAVAILABLE = "rate_limit.unavailable"
        const val IDEMPOTENCY_KEY_REUSED = "idempotency.key_reused_for_different_request"
    }

    /**
     * §13.12 — workspace resolution and CRUD (design 2026-08-16-workspaces §5/§7/§8/§9).
     * The resolution codes are raised by the `auth` module's resolution layer; the CRUD
     * codes by the workspace REST surface. The `auth`-side constants mirror these exactly
     * and `AuthErrorSpecDriftTest` asserts both against the doc — the same duplication
     * pattern as [Auth] vs §13.7.
     */
    object Workspace {
        /**
         * §13.12 — the principal has NO active workspace at all (zero memberships). Not the
         * answer for an ADDRESSED workspace: since D-R5 that is [NOT_FOUND], and this code
         * survives only where no name was supplied and so none can leak.
         */
        const val MEMBERSHIP_REQUIRED = "workspace.membership_required"

        /** §13.12 — `DP-Workspace` on an API-key request; a key's workspace is pinned at issuance (D3). */
        const val HEADER_FORBIDDEN = "workspace.header_forbidden"

        /**
         * §13.12 — an API-key principal reached a session-only workspace action (the UI's
         * create/members/delete/switch). A key cannot hold — let alone mint — a
         * `dp_session`, and `switch` mints one from the USER's scopes, so the class of
         * action is refused for the credential outright (025 A2; the 96240ed hotfix
         * carried `workspace.header_forbidden` as the interim code).
         */
        const val SESSION_REQUIRED = "workspace.session_required"

        /**
         * §13.12 — the addressed workspace does not exist FOR THIS CALLER (D-R5): unknown name,
         * non-member, or deactivated, all one answer so nothing about a workspace is probeable.
         * Also refuses a promotion batch naming a workspace the receiver does not have (O-4).
         */
        const val NOT_FOUND = "workspace.not_found"

        /** §13.12 — the membership change would leave the workspace with no admin. */
        const val LAST_ADMIN = "workspace.last_admin"

        /** §13.12 — a super admin addressed a DEACTIVATED workspace on a path that must refuse it. */
        const val INACTIVE = "workspace.inactive"

        /**
         * §13.12 — the invitation addressed does not exist (113): revoked already, never
         * created, or created for another workspace. Its own code rather than [NOT_FOUND]:
         * the WORKSPACE resolved fine, so the not-found thing is the invitation — and a
         * caller probing emails must not be able to use the workspace's 404 to tell an
         * existing invitation from a missing workspace.
         */
        const val INVITATION_NOT_FOUND = "workspace.invitation.not_found"

        /** §13.12 — workspace name fails `[a-z0-9_-]+`, 1–63. */
        const val NAME_INVALID = "workspace.validation.name_invalid"

        /** §13.12 — workspace name exists (global namespace, soft-deleted included). */
        const val DUPLICATE_NAME = "workspace.validation.duplicate_name"

        /**
         * §13.12 — delete blocked: workspace still owns non-deleted
         * pipelines/templates/datasources. Deactivation (D-R10) needs no such check: it
         * purges nothing.
         */
        const val IN_USE = "workspace.in_use"
    }

    /**
     * §13.13 — the draft/release version lifecycle and environment promotion
     * (versioning.md). Semantics live there; this object is the constant spelling.
     */
    object Versioning {
        /** §13.13 / versioning §4.2 — hash precondition failed; another writer got there first. */
        const val VERSION_CONFLICT = "pipeline.version.conflict"

        /** §13.13 / versioning §3 — release or discard requested but no DRAFT version exists. */
        const val NOT_DRAFT = "pipeline.version.not_draft"

        /**
         * §13.13 / versioning §3.1 (101) — discard targeted a version that is not RELEASED
         * (a DRAFT is purged, never discarded; a DISCARDED version is already retired).
         */
        const val NOT_RELEASED = "pipeline.version.not_released"

        /** §13.13 / versioning §3.1 (101) — restore targeted a version that is not DISCARDED. */
        const val NOT_DISCARDED = "pipeline.version.not_discarded"

        /**
         * §13.13 / versioning §3.5 (101, graph rule 1) — discard or purge refused: a LIVE
         * (non-discarded) version of another pipeline exact-pins this version. `details`
         * names the pinning entities.
         */
        const val PINNED = "pipeline.version.pinned"

        /**
         * §13.13 / versioning §3.5 (101, D57) — the purge path refused: a RELEASED version
         * is never purged, and an entity holding any non-draft version is never purged.
         * Discard is per version, the entity stays; restore or release something first.
         */
        const val LAST_RELEASE = "pipeline.version.last_release"

        /**
         * §13.13 / versioning §3.4 (101, D60) — manual switch targeted a version that is
         * not a live, posture-eligible version (DISCARDED, missing, or a DRAFT under a
         * hardened posture).
         */
        const val NOT_ELIGIBLE = "pipeline.version.not_eligible"

        /**
         * §13.13 / ui-screens §5.1 typed-confirm (102) — the typed-confirm guard on the
         * irreversible purge dialogs: the `confirm` form field did not name the version the
         * dialog asked the user to type. Checked BEFORE the lifecycle service runs.
         */
        const val CONFIRM_MISMATCH = "pipeline.version.confirm_mismatch"

        /** §13.13 / versioning §5.3 — pipeline release blocked on a DRAFT template pin. */
        const val RELEASE_TEMPLATE_NOT_RELEASED = "pipeline.release.template_not_released"

        /** §13.13 / versioning §10.3 — promotion selected a non-RELEASED version. */
        const val PROMOTION_NOT_RELEASED = "pipeline.promotion.not_released"

        /** §13.13 / versioning §10.3 — push of a version not newer than the target's current. */
        const val PROMOTION_NOT_NEWER = "pipeline.promotion.not_newer"

        /**
         * §13.13 / versioning §10.5 — the batch references datasource names the target does
         * not have. Collected across the WHOLE batch and reported once, before anything is
         * pushed, so a missing name never leaves the target half-promoted.
         */
        const val PROMOTION_MISSING_DATASOURCES = "pipeline.promotion.missing_datasources"

        /**
         * §13.13 / versioning §10.1 (D7) — promotion into a deployment whose authoring
         * capability is ON. Dev is where drafts live; a receiver never authors. Raised by the
         * RECEIVER, so a misconfigured sender cannot push into an authoring deployment.
         */
        const val PROMOTION_TARGET_IS_AUTHORING = "pipeline.promotion.target_is_authoring"

        /**
         * §13.13 / versioning §10 — the SENDER could not reach its target, or the target
         * answered something that is not this API. A transport failure, never a refusal: a
         * receiver that refuses does so with its own §13 code, which the sender re-raises
         * verbatim rather than flattening into this one.
         */
        const val PROMOTION_TARGET_UNREACHABLE = "pipeline.promotion.target_unreachable"

        /**
         * §13.13 / versioning §5.5 — an authoring write (create, update/draft, release,
         * discard, delete) on a deployment with `datapipelines.deployment.authoring-enabled=false`. Drafts
         * are a capability of authoring environments; a promotion receiver never authors
         * (D7), so its write path refuses, naming the reason. Reads, execution and import
         * are unaffected.
         */
        const val AUTHORING_DISABLED = "pipeline.authoring.disabled"
    }

    /**
     * §13.14 — published endpoints (published-endpoints design, round 074): a released pipeline
     * served as `GET /api/x/{path}`.
     *
     * Two families in one object because they are one surface:
     *  - the bare `endpoint.*` codes are **publish-time and resolution-time** refusals — the path
     *    grammar, the read-only rule, the key model;
     *  - `endpoint.request.*` are the **request validator's** (§5.3), and they are the only ones a
     *    caller of a published endpoint can provoke by changing their query string.
     *
     * The bare codes are two-segment (`endpoint.path_conflict`) because the `endpoint` domain has
     * no entity dimension — the same shape as `datasource.in_use` and `template.not_found`, and
     * `PipelineErrorCodesSpecDriftTest.KNOWN_TWO_SEGMENT_CODES` lists each one deliberately.
     */
    object Endpoint {
        /**
         * §4.1 — a `path_pattern` that is not a legal path: wrong segment alphabet, more than 10
         * segments, over 200 characters, a trailing slash, a `**`, or a malformed `{variable}`.
         */
        const val PATH_INVALID = "endpoint.path_invalid"

        /**
         * §4.1 — the pattern could match the same URL as one already published (`/a/{x}` against
         * `/a/b`). `details.conflicting_path` names the other. Ambiguity is REFUSED, never
         * resolved by precedence: at request time at most one pattern may match.
         */
        const val PATH_CONFLICT = "endpoint.path_conflict"

        /** §4.2 — a `{variable}` in the path names no declared parameter of the released version. */
        const val PATH_VARIABLE_UNKNOWN = "endpoint.path_variable_unknown"

        /**
         * §4.2/§5.1 — the pipeline is not side-effect-free: some node is `DML`/`DDL`, or a DQL
         * node writes back to a datasource, transitively through PIPELINE children.
         * `details.node_id` names the offender. **409 at publish, 503 at serve** — the same fact
         * has two meanings depending on when it is discovered, which is why the catalog lists it
         * once and the two surfaces choose the status (§5.6).
         */
        const val PIPELINE_NOT_READONLY = "endpoint.pipeline_not_readonly"

        /**
         * §5.1 — the published pipeline has no servable version: nothing the pointer names is
         * eligible (RELEASED always; a DRAFT only under development posture — D63).
         */
        const val PIPELINE_NOT_RELEASED = "endpoint.pipeline_not_released"

        /**
         * §5.6 — no endpoint matches. Deliberately identical for an unknown path and a DISABLED
         * one: distinguishing them would let an unauthenticated caller enumerate the registry.
         */
        const val NOT_FOUND = "endpoint.not_found"

        /** §5.6 — any method but `GET` on `/api/x/{path}`; the response carries `Allow: GET`. */
        const val METHOD_NOT_ALLOWED = "endpoint.method_not_allowed"

        /** §5.3 — an `Accept` this surface cannot satisfy (v1 serves `application/json` only). */
        const val NOT_ACCEPTABLE = "endpoint.not_acceptable"

        /**
         * §5.2 — the presented key is not among those bound at the first ancestor of the request
         * path that carries any binding. A deeper binding REPLACES an inherited one, so a key
         * bound higher up is refused here rather than inherited (ruling R-EP2).
         */
        const val KEY_NOT_BOUND = "endpoint.key_not_bound"

        /**
         * §5.2 — the key's KIND is wrong for what it is doing: an `endpoint` key anywhere but its
         * bound endpoints and the cursor of executions it started, or an `endpoint` key presented
         * to an endpoint that has no binding on any ancestor (where only `user` keys with
         * `execute` are accepted — an unbound endpoint key authorises nothing).
         */
        const val KEY_KIND_REFUSED = "endpoint.key_kind_refused"

        /**
         * §6 — a promotion batch carries an endpoint binding naming an API key by NAME that the
         * target deployment does not have. Refused before anything is pushed, like every other
         * promotion pre-validation, so the target is left byte-unchanged.
         */
        const val PROMOTION_KEY_MISSING = "endpoint.promotion.key_missing"
    }

    /**
     * §13.14 — the published-endpoint REQUEST validator (§5.3).
     *
     * Every code here is reported inside ONE `400` whose `details.errors[]` carries every defect
     * the request has, because a client fixes a request once. The envelope code is
     * [EndpointRequest.INVALID]; the per-defect codes below (and the two existing
     * `pipeline.execution.*` parameter codes, reused rather than re-spelled) appear in the
     * entries.
     */
    object EndpointRequest {
        /** §5.3 — the envelope: one or more request defects, each named in `details.errors[]`. */
        const val INVALID = "endpoint.request.invalid"

        /**
         * §5.3 — a query parameter the version does not declare. Strict on purpose: a typo that
         * silently ran the default would return plausible, wrong rows, which is worse than a 400.
         */
        const val PARAMETER_UNKNOWN = "endpoint.request.parameter_unknown"

        /** §5.3 — the same query key appeared more than once; the endpoint takes one value each. */
        const val PARAMETER_REPEATED = "endpoint.request.parameter_repeated"

        /** §5.3 — a single parameter value over 4 KB. */
        const val VALUE_TOO_LARGE = "endpoint.request.value_too_large"
    }

    /**
     * §13.15 — the learned semantic layer (118; the 2026-09-11 learned-semantic-layer design
     * record). Two-segment like `datasource.not_found`: the domain has no entity dimension —
     * every code is about ONE thing, a fact. Raised by `modules/datasources`'s recorder and
     * `modules/application`'s service; mirrored in `SemanticsErrorCodes` beside the recorder
     * (the same layering as [Datasource]), drift-tested from both sides.
     */
    object Semantics {
        /** `kind` is not in the closed list, or not a kind of the requested `scope` (§4). */
        const val KIND_INVALID = "semantics.kind_invalid"

        /** `fact` is outside its 8–1000 character window, `refs` is empty, or `evidence_summary` is over 300 characters. */
        const val FACT_INVALID = "semantics.fact_invalid"

        /** A ref does not resolve against live introspection: the table or the column is not there (§3.1). */
        const val REF_UNRESOLVED = "semantics.ref_unresolved"

        /** `evidence_sql` is not a single read-only SELECT/WITH, or names a parameter — refused before any connection opens. */
        const val EVIDENCE_REFUSED = "semantics.evidence_refused"

        /** `evidence_sql` ran and the database refused it or it timed out; the fact is not recorded (§7.1). */
        const val EVIDENCE_FAILED = "semantics.evidence_failed"

        /**
         * O-4 — an identical live fact `(scope, workspace, datasource, kind, refs, fact)` already
         * exists; `details.existing_id` names it.
         */
        const val DUPLICATE = "semantics.duplicate"

        /** The fact addressed by id does not exist or is not visible to the caller's workspace (D-R5). */
        const val NOT_FOUND = "semantics.not_found"
    }
}
