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

        /** §12.6 — every `template.id` exists in the template registry. */
        const val TEMPLATE_NOT_FOUND = "pipeline.validation.template_not_found"

        /** §12.6 — every `template.version` exists for that template id. */
        const val TEMPLATE_VERSION_NOT_FOUND = "pipeline.validation.template_version_not_found"

        /** §12.6 — the template's dialect matches the node's source dialect. */
        const val TEMPLATE_DIALECT_MISMATCH = "pipeline.validation.template_dialect_mismatch"

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

        /** §12.9 — a PIPELINE node's `pipeline.name` exists in the pipeline registry. */
        const val PIPELINE_NOT_FOUND = "pipeline.validation.pipeline_not_found"

        /** §12.9 — the pinned `pipeline.version` exists for that name. */
        const val PIPELINE_VERSION_NOT_FOUND = "pipeline.validation.pipeline_version_not_found"

        /** §12.9 — a PIPELINE node must not reference its containing pipeline. */
        const val PIPELINE_SELF_REFERENCE = "pipeline.validation.pipeline_self_reference"

        /**
         * §12.9 — the referenced pipeline is soft-deleted (D7: blocks NEW references at save
         * time only; existing pinned references keep resolving).
         */
        const val PIPELINE_REFERENCE_DELETED = "pipeline.validation.pipeline_reference_deleted"

        /** §12.9 — a PIPELINE node carries no `source` (it runs no SQL of its own). */
        const val PIPELINE_NODE_HAS_SOURCE = "pipeline.validation.pipeline_node_has_source"

        /** §12.9 — a PIPELINE node carries no `template` (it runs no SQL of its own). */
        const val PIPELINE_NODE_HAS_TEMPLATE = "pipeline.validation.pipeline_node_has_template"

        /** §12.9 — every required-without-default child parameter is supplied. */
        const val PIPELINE_PARAMETER_UNMAPPED = "pipeline.validation.pipeline_parameter_unmapped"

        /** §12.9 — every supplied parameter key exists in the child's `parameters`. */
        const val PIPELINE_PARAMETER_UNKNOWN = "pipeline.validation.pipeline_parameter_unknown"

        /**
         * §12.9 — a literal obeys the child parameter's §6.3 wire encoding; a `${ref}` names a
         * parent parameter of the identical declared type.
         */
        const val PIPELINE_PARAMETER_TYPE_MISMATCH = "pipeline.validation.pipeline_parameter_type_mismatch"

        /** §12.9 — `output` is absent when the pinned child has zero caller nodes. */
        const val PIPELINE_OUTPUT_ON_SIDEEFFECT_CHILD = "pipeline.validation.pipeline_output_on_sideeffect_child"

        /** §12.9 — the static reference-tree depth is within the configured maximum. */
        const val COMPOSITION_TOO_DEEP = "pipeline.validation.composition_too_deep"
    }

    /** §13.2 — pipeline import. */
    object Import {
        const val MISSING_DATASOURCE = "pipeline.import.missing_datasource"
        const val MISSING_TEMPLATE = "pipeline.import.missing_template"
        const val VERSION_CONFLICT = "pipeline.import.version_conflict"
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
        const val DATASOURCE_CONNECTION_FAILED = "pipeline.node.datasource_connection_failed"
        const val QUERY_EXECUTION_FAILED = "pipeline.node.query_execution_failed"
        const val STAGING_FAILED = "pipeline.node.staging_failed"
        const val WRITEBACK_FAILED = "pipeline.node.writeback_failed"
        const val WRITEBACK_TARGET_MISSING = "pipeline.node.writeback_target_missing"

        /**
         * §13.4 — a PIPELINE node's child execution failed; the detail carries the child's
         * error code and execution id, so the debugging trail leads to a real execution record.
         */
        const val CHILD_EXECUTION_FAILED = "pipeline.node.child_execution_failed"

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
    }

    /** §13.8 — datasource. Defined in datasources.md §9–10; cataloged here (D5). */
    object Datasource {
        const val NAME_INVALID = "datasource.validation.name_invalid"
        const val DIALECT_INVALID = "datasource.validation.dialect_invalid"
        const val JDBC_URL_MALFORMED = "datasource.validation.jdbc_url_malformed"
        const val JDBC_URL_SCHEME_INVALID = "datasource.validation.jdbc_url_scheme_invalid"
        const val PASSWORD_MISSING = "datasource.validation.password_missing"
        const val PROPERTIES_INVALID = "datasource.validation.properties_invalid"
        const val QUERY_TIMEOUT_INVALID = "datasource.validation.query_timeout_invalid"
        const val DUPLICATE_NAME = "datasource.validation.duplicate_name"
        const val IN_USE = "datasource.in_use"
        const val NOT_FOUND = "datasource.not_found"
        const val DRIVER_NOT_LOADED = "datasource.driver_not_loaded"
    }

    /** §13.9 — template. Defined in templates.md §7; cataloged here (D5). */
    object Template {
        const val SYNTAX_ERROR = "template.validation.syntax_error"
        const val DANGEROUS_CONSTRUCT = "template.validation.dangerous_construct"
        const val ID_INVALID = "template.validation.id_invalid"
        const val DIALECT_INVALID = "template.validation.dialect_invalid"
        const val ENGINE_UNSUPPORTED = "template.validation.engine_unsupported"
        const val SCHEMA_VERSION_UNSUPPORTED = "template.validation.schema_version_unsupported"
        const val IS_LIBRARY_WITHOUT_MACROS = "template.validation.is_library_without_macros"
        const val IMPORT_NOT_FOUND = "template.validation.import_not_found"
        const val IMPORT_NOT_LIBRARY = "template.validation.import_not_library"
        const val IMPORT_CYCLE = "template.validation.import_cycle"
        const val IMPORT_DEPTH_EXCEEDED = "template.validation.import_depth_exceeded"
        const val DUPLICATE_ALIAS = "template.validation.duplicate_alias"
        const val NOT_FOUND = "template.not_found"
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
        const val IDEMPOTENCY_KEY_REUSED = "idempotency.key_reused_for_different_request"
    }

    /**
     * §13.12 — workspace resolution (design 2026-08-16-workspaces §5/§7/§8). Raised by the
     * `auth` module's resolution layer; the `auth`-side constants mirror these exactly and
     * `AuthErrorSpecDriftTest` asserts both against the doc — the same duplication pattern
     * as [Auth] vs §13.7.
     *
     * The CRUD codes (`workspace.not_found`, `workspace.validation.*`, `workspace.in_use`)
     * land with the REST surface slice that raises them (021).
     */
    object Workspace {
        /** §13.12 — the principal is not a member of the addressed workspace (or has zero memberships). */
        const val MEMBERSHIP_REQUIRED = "workspace.membership_required"

        /** §13.12 — the provisioning mode forbids this caller creating a workspace. */
        const val CREATION_FORBIDDEN = "workspace.creation_forbidden"

        /** §13.12 — `DP-Workspace` on an API-key request; a key's workspace is pinned at issuance (D3). */
        const val HEADER_FORBIDDEN = "workspace.header_forbidden"
    }
}
