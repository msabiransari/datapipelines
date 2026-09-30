package co.datapipelines.parameters

/**
 * The parameter engine's catalogued error codes — pipeline-contract.md **§13.20**, the single
 * catalog (record §10). `PipelineErrorCodes.Parameters` in `pipeline-contract` carries the same
 * set (the `ApiErrorCatalog` in `web` reads it there, since `web` does not depend on this module
 * yet); `ParameterErrorCodesTest` pins the two equal by reflection and
 * `ParameterErrorCodesSpecDriftTest` holds this object and §13.20 together in both directions.
 *
 * Codes are additive — never reused, never renamed (§13). Families: `parameter.validation.*` is the
 * author's document at save (400, `duplicate_name` 409); `parameter.evaluate.*` is the runtime
 * (lane C) — a whole-request refusal or an inline, per-parameter one in `state.errors[]`;
 * `parameter.version.*` / `parameter.release.*` / `parameter.import.*` / `parameter.authoring.*`
 * are the `template.*` twins of the lifecycle (versioning §3.5).
 *
 * `template.validation.parameter_interpolated` (§13.9) is deliberately NOT twinned: a selector
 * template interpolating a parent is refused with the existing template code (record §4 step 4).
 */
object ParameterErrorCodes {
    // ---- save-time validation (record §3, §4, §6, §7) -----------------------------------------------

    /**
     * The set name fails the folder grammar (`details.reason`: `folder_required` / `grammar` / `missing` /
     * `immutable`), or a parameter name fails §6.1's.
     */
    const val NAME_INVALID = "parameter.validation.name_invalid"

    /** A parameter name ends in `_count` or `__<digits>` — P29's generated bind namespace (record §3.2). */
    const val NAME_RESERVED = "parameter.validation.name_reserved"

    /** Agent surface only — a new top-level folder without `confirm_new_root: true` (lane D raises it). */
    const val NEW_ROOT_REQUIRES_CONFIRMATION = "parameter.validation.new_root_requires_confirmation"

    /** 409 — the set name exists in the workspace (discarded included: names are unique forever). */
    const val DUPLICATE_NAME = "parameter.validation.duplicate_name"

    /** Two parameters of one set share a name. */
    const val DUPLICATE_PARAMETER = "parameter.validation.duplicate_parameter"

    /** More parameters than `datapipelines.parameters.max-parameters-per-set`. */
    const val TOO_MANY_PARAMETERS = "parameter.validation.too_many_parameters"

    /**
     * The document's SHAPE (owner ruling 2026-09-26): an unknown key, a wrong JSON type or a missing
     * structural key, at any level — `details.path` names it, `details.reason` is `unknown_key` /
     * `wrong_type` / `missing` / `too_long`.
     */
    const val BODY_INVALID = "parameter.validation.body_invalid"

    /** A label (or the set's `display_name`) is missing, blank, or over 120 characters. */
    const val LABEL_INVALID = "parameter.validation.label_invalid"

    /** A description over 2000 characters. */
    const val DESCRIPTION_TOO_LONG = "parameter.validation.description_too_long"

    /** A `type` outside the ten declarable logical types (`NULL` excluded). */
    const val TYPE_INVALID = "parameter.validation.type_invalid"

    /** `DECIMAL` with no `precision`. */
    const val PRECISION_MISSING = "parameter.validation.precision_missing"

    /** `BIGDECIMAL` with no `scale`. */
    const val SCALE_MISSING = "parameter.validation.scale_missing"

    /** A `kind` other than `INPUT` / `SELECT`. */
    const val KIND_INVALID = "parameter.validation.kind_invalid"

    /** A `cardinality` other than `SINGLE` / `MULTI`, or `MULTI` on an `INPUT`. */
    const val CARDINALITY_INVALID = "parameter.validation.cardinality_invalid"

    /** `default_value` is not a wire value of the parameter's type (or not an array for a `MULTI`). */
    const val DEFAULT_TYPE_MISMATCH = "parameter.validation.default_type_mismatch"

    /** A `constants` select's `default_value` is not among its option values. */
    const val DEFAULT_NOT_AN_OPTION = "parameter.validation.default_not_an_option"

    /** A default (hard-coded, a marked option, a sourced row at the dry run) breaks the parameter's own constraints. */
    const val DEFAULT_INVALID = "parameter.validation.default_invalid"

    /** A `SELECT` with no `source`. */
    const val SOURCE_MISSING = "parameter.validation.source_missing"

    /** A `source` stating both shapes, or neither, or a `template` without its `datasource`. */
    const val SOURCE_AMBIGUOUS = "parameter.validation.source_ambiguous"

    /** `constants` on an `INPUT` — its hard-coded value is `default_value`. */
    const val SOURCE_NOT_ALLOWED = "parameter.validation.source_not_allowed"

    /** `constraints` on a `SELECT` (constraints are `INPUT` only). */
    const val CONSTRAINTS_ON_SELECT = "parameter.validation.constraints_on_select"

    /** A constraint on a type it does not apply to. */
    const val CONSTRAINT_NOT_APPLICABLE = "parameter.validation.constraint_not_applicable"

    /** A malformed constraint (a bound not in the type, `min > max`, a negative length). */
    const val CONSTRAINT_INVALID = "parameter.validation.constraint_invalid"

    /** A `pattern` too long, not compiling, or using a construct the regex budget refuses. */
    const val PATTERN_INVALID = "parameter.validation.pattern_invalid"

    /** A `format` whose shape does not fit the type family (any format on `STRING`/`BOOLEAN`/`BINARY`). */
    const val FORMAT_INVALID = "parameter.validation.format_invalid"

    /** A `presentation` naming a control outside the catalogue. */
    const val PRESENTATION_INVALID = "parameter.validation.presentation_invalid"

    /** A catalogued `control` outside its kind × cardinality × type row (record §3.7). */
    const val CONTROL_NOT_APPLICABLE = "parameter.validation.control_not_applicable"

    /** A temporal `format.pattern` over 64 characters, not compiling, or naming fields its type lacks. */
    const val FORMAT_PATTERN_INVALID = "parameter.validation.format_pattern_invalid"

    /** A constants option with a null or mistyped value, an empty label, or over the value/label caps. */
    const val OPTION_INVALID = "parameter.validation.option_invalid"

    /** Two constants options share a value. */
    const val OPTION_DUPLICATE = "parameter.validation.option_duplicate"

    /** More than one constants option marked `is_default`. */
    const val MULTIPLE_DEFAULTS = "parameter.validation.multiple_defaults"

    /** An empty constants list, or one longer than `max-options-per-selector`. */
    const val TOO_MANY_OPTIONS = "parameter.validation.too_many_options"

    /** `depends_on` names no parameter of the set. */
    const val DEPENDENCY_UNKNOWN = "parameter.validation.dependency_unknown"

    /** `depends_on` names the parameter itself. */
    const val DEPENDENCY_SELF = "parameter.validation.dependency_self"

    /** The set's dependency graph has a cycle; `details.cycle` is the path. */
    const val DEPENDENCY_CYCLE = "parameter.validation.dependency_cycle"

    /** A selector template binds `:name` that is neither a `depends_on` parameter nor a tier key (P30). */
    const val BIND_UNDECLARED = "parameter.validation.bind_undeclared"

    /** An expression `ref` names a parameter outside the carrier's `depends_on`, or itself. */
    const val REF_UNDECLARED = "parameter.validation.ref_undeclared"

    /** An expression that is not a §7 AST (an unknown op, a missing operand, an unknown key). */
    const val EXPRESSION_INVALID = "parameter.validation.expression_invalid"

    /** An expression deeper than `max-expression-depth`. */
    const val EXPRESSION_DEPTH_EXCEEDED = "parameter.validation.expression_depth_exceeded"

    /** An expression with more nodes than `max-expression-nodes`, or an `in` list over 256 literals. */
    const val EXPRESSION_TOO_LARGE = "parameter.validation.expression_too_large"

    /** An operator applied to a ref of the wrong cardinality (§7.2). */
    const val EXPRESSION_CARDINALITY = "parameter.validation.expression_cardinality"

    /** A literal not coercible to its ref's type. */
    const val EXPRESSION_LITERAL_TYPE = "parameter.validation.expression_literal_type"

    /** A `BINARY` ref tested by anything but `is_null`. */
    const val EXPRESSION_TYPE_UNSUPPORTED = "parameter.validation.expression_type_unsupported"

    /** The pinned template id does not exist in the workspace. */
    const val TEMPLATE_NOT_FOUND = "parameter.validation.template_not_found"

    /** The pinned template exists, but not at that version (or only DISCARDED there). */
    const val TEMPLATE_VERSION_NOT_FOUND = "parameter.validation.template_version_not_found"

    /** The pinned template is not `type = 'sql'`. */
    const val TEMPLATE_TYPE_MISMATCH = "parameter.validation.template_type_mismatch"

    /** The pinned template's dialect differs from the datasource's. */
    const val TEMPLATE_DIALECT_MISMATCH = "parameter.validation.template_dialect_mismatch"

    /** The dry render of a selector template failed (record §4 step 5). */
    const val TEMPLATE_RENDER_FAILED = "parameter.validation.template_render_failed"

    /** The source's datasource is not visible from the workspace. */
    const val DATASOURCE_NOT_FOUND = "parameter.validation.datasource_not_found"

    /** The source's datasource could not be reached for the metadata execution (record §4 step 6). */
    const val DATASOURCE_UNREACHABLE = "parameter.validation.datasource_unreachable"

    /**
     * The metadata execution reached the datasource and its STATEMENT failed (owner ruling
     * 2026-09-26) — `details.datasource_code` carries the datasource's own code.
     */
    const val SELECTOR_QUERY_FAILED = "parameter.validation.selector_query_failed"

    /** The selector's columns are not exactly `value`, `display_value`, `is_default` (or `value` alone for an `INPUT`). */
    const val SELECTOR_COLUMNS_INVALID = "parameter.validation.selector_columns_invalid"

    /** The selector's `value` column type does not pass record §6.4 against the declared type. */
    const val SELECTOR_VALUE_TYPE_MISMATCH = "parameter.validation.selector_value_type_mismatch"

    /**
     * A database-fed `INPUT`'s template returned two or more rows at save's dry run (owner ruling
     * 2026-09-27) — the save-time twin of [EVALUATE_INPUT_SOURCE_MULTIPLE_ROWS] (record §4 step 6).
     */
    const val INPUT_SOURCE_MULTIPLE_ROWS = "parameter.validation.input_source_multiple_rows"

    /** A `SELECT`'s rendered SQL carries no `ORDER BY` (P7). */
    const val SELECTOR_ORDER_BY_MISSING = "parameter.validation.selector_order_by_missing"

    // ---- evaluate (record §5; the runtime is lane C's) ----------------------------------------------

    /** 400 — a `selections` key names no parameter of the set; the whole request is refused. */
    const val EVALUATE_UNKNOWN_PARAMETER = "parameter.evaluate.unknown_parameter"

    /** Inline — a `MULTI` longer than `max-multi-bind-values`. */
    const val EVALUATE_TOO_MANY_VALUES = "parameter.evaluate.too_many_values"

    /** Inline — a rendered statement over `max-binds-per-statement` placeholders. */
    const val EVALUATE_TOO_MANY_BINDS = "parameter.evaluate.too_many_binds"

    /** Inline — a selector row violating the option invariants (`details.reason`). */
    const val EVALUATE_SELECTOR_ROWS_INVALID = "parameter.evaluate.selector_rows_invalid"

    /** 413 — the response would exceed `max-evaluate-response-bytes`; the whole request is refused. */
    const val EVALUATE_RESPONSE_TOO_LARGE = "parameter.evaluate.response_too_large"

    /** Inline — a submitted value in the wrong wire form, not coercible, or a duplicate `MULTI` member. */
    const val EVALUATE_INVALID_VALUE_TYPE = "parameter.evaluate.invalid_value_type"

    /** Inline — a submitted value breaks a constraint, the precision or the scale (`details.reason`). */
    const val EVALUATE_CONSTRAINT_VIOLATION = "parameter.evaluate.constraint_violation"

    /** Inline — required and nothing resolves (`details.reason`: `no_options` / `no_default` / `no_row`). */
    const val EVALUATE_REQUIRED_MISSING = "parameter.evaluate.required_missing"

    /** Inline — a selector returned more rows than `max-options-per-selector`. */
    const val EVALUATE_TOO_MANY_OPTIONS = "parameter.evaluate.too_many_options"

    /** Inline — an `INPUT`'s source template returned two or more rows; `default_value` is used. */
    const val EVALUATE_INPUT_SOURCE_MULTIPLE_ROWS = "parameter.evaluate.input_source_multiple_rows"

    /** Inline — the selector's `value` column no longer passes record §6.4 (schemas drift). */
    const val EVALUATE_SELECTOR_VALUE_TYPE_MISMATCH = "parameter.evaluate.selector_value_type_mismatch"

    /** 400 — MCP only: a draft set's pinned DRAFT template changed after the key's last render of it. */
    const val EVALUATE_TEMPLATE_UNRENDERED = "parameter.evaluate.template_unrendered"

    /** 504 — the evaluate's deadline; the whole request, never a half-form. */
    const val EVALUATE_TIMEOUT = "parameter.evaluate.timeout"

    /** Inline — the selector bulkhead's queue was already full at admission (decided at once). */
    const val EVALUATE_SELECTORS_SATURATED = "parameter.evaluate.selectors_saturated"

    // ---- the entity and its lifecycle (versioning §3.5 — the template.* twins) ---------------------

    /** 404 — no such set (or version) in the workspace, hidden by the lens, or discarded on a read/mutate path. */
    const val NOT_FOUND = "parameter.not_found"

    /**
     * 409 — purge or discard refused: a LIVE dashboard version pins the set (#320, versioning §3.5's graph rule 1 — the
     * consumer binding this code was reserved for). `details`: `id`, `version` (absent for an entity purge) and
     * `pinned_by` — one `{dashboard, version, status}` per pinning dashboard version. An entity purge, and a draft purge
     * that takes the entity, also count DISCARDED dashboard versions (graph rule 3, R12).
     */
    const val IN_USE = "parameter.in_use"

    /** 409 — the draft-write / release / discard precondition hash is stale; `details` carry the current state. */
    const val VERSION_CONFLICT = "parameter.version.conflict"

    /** 409 — release or a draft verb on a set with no DRAFT. */
    const val VERSION_NOT_DRAFT = "parameter.version.not_draft"

    /** 409 — discard of a version that is not RELEASED. */
    const val VERSION_NOT_RELEASED = "parameter.version.not_released"

    /** 409 — restore of a version that is not DISCARDED. */
    const val VERSION_NOT_DISCARDED = "parameter.version.not_discarded"

    /** 409 — purge of a released version, or of an entity holding one. */
    const val VERSION_LAST_RELEASE = "parameter.version.last_release"

    /** 409 — switch to a version that is not live and posture-eligible. */
    const val VERSION_NOT_ELIGIBLE = "parameter.version.not_eligible"

    /** 400 — a destructive verb's typed confirmation did not match. */
    const val VERSION_CONFIRM_MISMATCH = "parameter.version.confirm_mismatch"

    /** 409 — release with a pinned template version that is not RELEASED; `details.pins_not_released`. */
    const val RELEASE_TEMPLATE_NOT_RELEASED = "parameter.release.template_not_released"

    /** 400 — an import whose pinned template is absent on the target (promotion order: templates first). */
    const val IMPORT_MISSING_TEMPLATE = "parameter.import.missing_template"

    /** 403 — `datapipelines.deployment.authoring-enabled=false` refuses every authoring write. */
    const val AUTHORING_DISABLED = "parameter.authoring.disabled"

    /** Every code above — the catalog tests' handle. */
    val ALL: Set<String> =
        ParameterErrorCodes::class.java.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.type == String::class.java
            }.map { it.get(null) as String }
            .toSet()
}
