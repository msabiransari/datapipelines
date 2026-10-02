package co.datapipelines.visualization

/**
 * The dashboard artifact's catalogued error codes — pipeline-contract.md **§13.23**, the single catalog.
 * `PipelineErrorCodes.Dashboard` in `pipeline-contract` carries the same set; `DashboardErrorCodesTest` pins the
 * two equal by reflection and `DashboardErrorCodesSpecDriftTest` holds this object and §13.23 together in both
 * directions.
 *
 * `dashboard.validation.*` is the author's document at save, at release and at every runtime configuration
 * read (400; `name_taken` 409). [DUPLICATE_NAME] is the dashboard's OBJECT namespace (the spec's §3.2) — the
 * artifact-name clash is [NAME_TAKEN]. `version.*` / `release.*` / `import.*` / `authoring.*` are the
 * lifecycle; `runtime.*` / `refresh.*` are the server runtime's (L2) and `key.*` the key kind's (L5),
 * catalogued now so the family is complete.
 */
object DashboardErrorCodes {
    // ---- save-time validation (the spec's §3.2) -----------------------------------------------------

    /** The document's shape — unknown key, wrong JSON type, missing key, wrong object type, a bound. */
    const val BODY_INVALID = "dashboard.validation.body_invalid"

    /** The name fails the folder grammar, or a write names another dashboard (`immutable`). */
    const val NAME_INVALID = "dashboard.validation.name_invalid"

    /** Agent surface only — a new top-level folder without `confirm_new_root: true` (L1b raises it). */
    const val NEW_ROOT_REQUIRES_CONFIRMATION = "dashboard.validation.new_root_requires_confirmation"

    /** 409 — the name exists in the workspace (discarded included: names are unique forever). */
    const val NAME_TAKEN = "dashboard.validation.name_taken"

    /** Two objects of one dashboard share a name, or a name breaks the object grammar (one namespace). */
    const val DUPLICATE_NAME = "dashboard.validation.duplicate_name"

    /** A reference to no object, source or pipeline parameter of this dashboard. */
    const val UNKNOWN_OBJECT = "dashboard.validation.unknown_object"

    /** An action target that is not a visualization occurrence. */
    const val TARGET_NOT_VISUALIZATION = "dashboard.validation.target_not_visualization"

    /** `scope: targets` with no targets, or `scope: all` with some (D55). */
    const val EMPTY_TARGETS = "dashboard.validation.empty_targets"

    /** An action control bound to a parent parameter (R2, D42). */
    const val PARENT_ACTION_BINDING = "dashboard.validation.parent_action_binding"

    /** A declared parameter scope that omits a consuming group (D41). */
    const val SCOPE_OMITS_CONSUMER = "dashboard.validation.scope_omits_consumer"

    /** A source pins a pipeline version that is not RELEASED (D1). */
    const val SOURCE_NOT_RELEASED = "dashboard.validation.source_not_released"

    /** A source pins a pipeline release that fails the read-only rule (D38). */
    const val SOURCE_NOT_READ_ONLY = "dashboard.validation.source_not_read_only"

    /** A required pipeline parameter left unbound, or a binding to a parameter the set does not have. */
    const val PARAMETER_UNBOUND = "dashboard.validation.parameter_unbound"

    /** A visualization input mapped to no source, or a mapping to an input it does not declare. */
    const val INPUT_UNBOUND = "dashboard.validation.input_unbound"

    /**
     * The dashboard needs more DISTINCT executions than one refresh may run (`max-executions-per-refresh`, spec §18
     * premise 11): a document valid at save that no refresh could ever be admitted for. `details.invocations` and `max`.
     */
    const val TOO_MANY_INVOCATIONS = "dashboard.validation.too_many_invocations"

    /** A source's output columns do not satisfy the input's by name and type; `details.column`. */
    const val INPUT_CONTRACT_MISMATCH = "dashboard.validation.input_contract_mismatch"

    /** The layout does not place every occurrence, group and control exactly once on the 12-column grid. */
    const val LAYOUT_INVALID = "dashboard.validation.layout_invalid"

    /** A pinned visualization, pipeline or set version that does not exist here. */
    const val DEPENDENCY_NOT_FOUND = "dashboard.validation.dependency_not_found"

    // ---- the entity and its lifecycle ---------------------------------------------------------------------

    /** 404 — no such dashboard (or version), hidden by the lens, or discarded on a read/mutate path. */
    const val NOT_FOUND = "dashboard.not_found"

    /** 409 — the precondition hash is stale; `details` carry the current state. */
    const val VERSION_CONFLICT = "dashboard.version.conflict"

    /** 409 — release or a draft verb on a dashboard with no DRAFT. */
    const val VERSION_NOT_DRAFT = "dashboard.version.not_draft"

    /** 409 — discard of a version that is not RELEASED. */
    const val VERSION_NOT_RELEASED = "dashboard.version.not_released"

    /** 409 — restore of a version that is not DISCARDED. */
    const val VERSION_NOT_DISCARDED = "dashboard.version.not_discarded"

    /** 409 — purge of a released version, or of an entity holding one. */
    const val VERSION_LAST_RELEASE = "dashboard.version.last_release"

    /** 409 — switch to a version that is not live and posture-eligible. */
    const val VERSION_NOT_ELIGIBLE = "dashboard.version.not_eligible"

    /** 409 — a pinned dependency is not released (D61); `details.dependencies_not_released`. */
    const val RELEASE_DEPENDENCY_NOT_RELEASED = "dashboard.release.dependency_not_released"

    /** 409 — the exported id belongs to another dashboard on this server (C29: never re-issued). */
    const val IMPORT_ID_TAKEN = "dashboard.import.id_taken"

    /** 400 — a pinned dependency absent on the target and not in the bundle (D61's promotion order). */
    const val IMPORT_MISSING_DEPENDENCY = "dashboard.import.missing_dependency"

    /** 403 — `datapipelines.deployment.authoring-enabled=false` refuses every authoring write. */
    const val AUTHORING_DISABLED = "dashboard.authoring.disabled"

    // ---- the runtime (L2) and the key kind (L5) ---------------------------------------------------------

    /** 409 — a runtime call's `configuration_id` is not the current one; the client reloads. */
    const val RUNTIME_CONFIGURATION_STALE = "dashboard.runtime.configuration_stale"

    /** 409 — a pinned dependency no longer resolves at config time. */
    const val RUNTIME_DEPENDENCY_MISSING = "dashboard.runtime.dependency_missing"

    /** 429 — the refresh could not reserve its execution slots in time (D53). */
    const val REFRESH_SATURATED = "dashboard.refresh.saturated"

    /** 422 — a source's result exceeded its byte cap (D54). */
    const val REFRESH_RESULT_TOO_LARGE = "dashboard.refresh.result_too_large"

    /** 404 — no such refresh for the caller, or it already finished. */
    const val REFRESH_NOT_FOUND = "dashboard.refresh.not_found"

    /** 403 — a `dashboard` key outside the runtime and refresh routes, or on `/mcp`. */
    const val KEY_KIND_REFUSED = "dashboard.key.kind_refused"

    /** 400 — a dashboard binding's folder prefix fails the folder grammar, or names no folder of the caller's own tree (#191's rule). */
    const val BINDING_PATH_INVALID = "dashboard.binding.path_invalid"

    /** Every code above — the catalog tests' handle. */
    val ALL: Set<String> =
        DashboardErrorCodes::class.java.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.type == String::class.java
            }.map { it.get(null) as String }
            .toSet()
}
