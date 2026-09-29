package co.datapipelines.visualization

/**
 * The visualization artifact's catalogued error codes — pipeline-contract.md **§13.22**, the single catalog.
 * `PipelineErrorCodes.Visualization` in `pipeline-contract` carries the same set (`web`'s `ApiErrorCatalog`
 * reads it there, since `web` does not depend on this module yet); `VisualizationErrorCodesTest` pins the two
 * equal by reflection and `VisualizationErrorCodesSpecDriftTest` holds this object and §13.22 together in both
 * directions.
 *
 * Codes are additive — never reused, never renamed (§13). `visualization.validation.*` is the author's
 * document at save (400; `name_taken` 409); `version.*` / `import.*` / `authoring.*` are the `parameter.*`
 * twins of the lifecycle (versioning §3.5); `release.*` is the D56 gate; `test.*` is the tests lane's (L4),
 * catalogued now so the family is complete.
 */
object VisualizationErrorCodes {
    // ---- save-time validation (the spec's §3.1) -----------------------------------------------------

    /** The document's shape — unknown key, wrong JSON type, missing key, out-of-vocabulary literal, a bound. */
    const val BODY_INVALID = "visualization.validation.body_invalid"

    /** The name fails the folder grammar, or a write names another visualization (`immutable`). */
    const val NAME_INVALID = "visualization.validation.name_invalid"

    /** Agent surface only — a new top-level folder without `confirm_new_root: true` (L1b raises it). */
    const val NEW_ROOT_REQUIRES_CONFIRMATION = "visualization.validation.new_root_requires_confirmation"

    /** 409 — the name exists in the workspace (discarded included: names are unique forever). */
    const val NAME_TAKEN = "visualization.validation.name_taken"

    /** A reserved renderer kind (`html`, `svg`) or a renderer version the kind does not accept. */
    const val RENDERER_UNSUPPORTED = "visualization.validation.renderer_unsupported"

    /** An input contract that cannot be satisfied — no inputs or columns, a bad name, a duplicate column. */
    const val INPUT_CONTRACT_INVALID = "visualization.validation.input_contract_invalid"

    /** The transform pin does not bind — missing, not a transform, or its declared inputs differ. */
    const val TRANSFORM_BINDING_INVALID = "visualization.validation.transform_binding_invalid"

    /** `config` fails the renderer's schema; `details.path` names the path inside it. */
    const val CONFIG_SCHEMA_INVALID = "visualization.validation.config_schema_invalid"

    /** A binding whose path does not resolve in `config` or whose column the output contract lacks. */
    const val BINDING_UNBOUND = "visualization.validation.binding_unbound"

    /** A test case that cannot run — its name, fixtures or assertions. */
    const val TEST_CASE_INVALID = "visualization.validation.test_case_invalid"

    // ---- the entity and its lifecycle (versioning §3.5 — the parameter.* twins) --------------------

    /** 404 — no such visualization (or version), hidden by the lens, or discarded on a read/mutate path. */
    const val NOT_FOUND = "visualization.not_found"

    /** 409 — the precondition hash is stale; `details` carry the current state. */
    const val VERSION_CONFLICT = "visualization.version.conflict"

    /** 409 — release or a draft verb on a visualization with no DRAFT. */
    const val VERSION_NOT_DRAFT = "visualization.version.not_draft"

    /** 409 — discard of a version that is not RELEASED. */
    const val VERSION_NOT_RELEASED = "visualization.version.not_released"

    /** 409 — restore of a version that is not DISCARDED. */
    const val VERSION_NOT_DISCARDED = "visualization.version.not_discarded"

    /** 409 — purge of a released version, or of an entity holding one. */
    const val VERSION_LAST_RELEASE = "visualization.version.last_release"

    /** 409 — switch to a version that is not live and posture-eligible. */
    const val VERSION_NOT_ELIGIBLE = "visualization.version.not_eligible"

    /** 409 — discard or purge of a version a live dashboard version pins; `details.pinned_by`. */
    const val VERSION_PINNED = "visualization.version.pinned"

    // ---- the release gate (D56, the spec's §11.4) -----------------------------------------------------

    /** 409 — no test case or no completed run; the default gate's answer until L4 installs its own. */
    const val RELEASE_TESTS_MISSING = "visualization.release.tests_missing"

    /** 409 — the latest run's hash is not the candidate's. */
    const val RELEASE_TESTS_STALE = "visualization.release.tests_stale"

    /** 409 — the latest run for the candidate is not GREEN. */
    const val RELEASE_TESTS_RED = "visualization.release.tests_red"

    /** 409 — the server-run mechanical test fails now. */
    const val RELEASE_MECHANICAL_FAILED = "visualization.release.mechanical_failed"

    /** 409 — a pinned transform template is not RELEASED and the cascade was not consented to. */
    const val RELEASE_DEPENDENCY_NOT_RELEASED = "visualization.release.dependency_not_released"

    // ---- import, authoring ------------------------------------------------------------------------------

    /** 409 — the exported id belongs to another visualization on this server (C29: never re-issued). */
    const val IMPORT_ID_TAKEN = "visualization.import.id_taken"

    /** 400 — the pinned template is absent on the target (templates are promoted first). */
    const val IMPORT_MISSING_TEMPLATE = "visualization.import.missing_template"

    /** 403 — `datapipelines.deployment.authoring-enabled=false` refuses every authoring write. */
    const val AUTHORING_DISABLED = "visualization.authoring.disabled"

    // ---- test sessions (L4) -----------------------------------------------------------------------------

    /** 404 — no such test session, or its token does not match. */
    const val TEST_SESSION_NOT_FOUND = "visualization.test.session_not_found"

    /** 410 — the session's preview token expired or was revoked by its submit. */
    const val TEST_SESSION_EXPIRED = "visualization.test.session_expired"

    /** 413 — a screenshot over 4 MiB. */
    const val TEST_SCREENSHOT_TOO_LARGE = "visualization.test.screenshot_too_large"

    /** 400 — a screenshot that is not a readable PNG or WebP image. */
    const val TEST_SCREENSHOT_INVALID = "visualization.test.screenshot_invalid"

    /** Every code above — the catalog tests' handle. */
    val ALL: Set<String> =
        VisualizationErrorCodes::class.java.declaredFields
            .filter {
                java.lang.reflect.Modifier
                    .isStatic(it.modifiers) && it.type == String::class.java
            }.map { it.get(null) as String }
            .toSet()
}
