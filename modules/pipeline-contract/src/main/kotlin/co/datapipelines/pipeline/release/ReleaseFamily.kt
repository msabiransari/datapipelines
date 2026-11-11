package co.datapipelines.pipeline.release

/**
 * The six releasable artifact families (release-dependencies record §1.1; dependency-graph record §8: an API draft
 * version is the sixth releasable root, #786). [wire] is the `family` word the plan and the blockers carry (§2.1, §14)
 * and the G1 registry key (`FamilyKey(wire)`); [codePrefix] is the
 * family's error-code prefix. The two differ for the parameter set, whose codes have always been `parameter.*`
 * (pipeline-contract §13.20) while its wire word is `parameter_set`, and for the API, whose codes have always been
 * `endpoint.*` (§13.14) while its wire word is the G1 contract's frozen family key `api_endpoint` (dependency-graph
 * record §6, `FamilyKey.API_ENDPOINT`, the `PIPELINE_PIN` relationship's source).
 */
enum class ReleaseFamily(
    val wire: String,
    val codePrefix: String,
) {
    PIPELINE("pipeline", "pipeline"),
    TEMPLATE("template", "template"),
    PARAMETER_SET("parameter_set", "parameter"),
    VISUALIZATION("visualization", "visualization"),
    DASHBOARD("dashboard", "dashboard"),
    ENDPOINT("api_endpoint", "endpoint"),
    ;

    /** The family-prefixed code for [refusal] — the ROOT's family, never the blocker's (record §14). */
    fun code(refusal: ReleaseRefusal): String = "$codePrefix.release.${refusal.wire}"
}

/**
 * The nine release-plan refusals every family carries (record §14): one reason word, one family-prefixed code per family.
 * The eight frozen by the 2026-10-18 ruling are extended by ONE, [METADATA_UNAVAILABLE], for the release-planning
 * deadline only (owner ruling 2026-11-08, 868 review R1-4/R3-1, option 1; dependency-graph record Draft 10 erratum (6)).
 * `ReleaseFamilyTest` pins every `family × refusal` spelling to the family's catalogued constant, so a family whose
 * constant drifts from this enum is red.
 */
enum class ReleaseRefusal(
    val wire: String,
) {
    DEPENDENCY_NOT_RELEASED("dependency_not_released"),
    PLAN_CONFLICT("plan_conflict"),
    PLAN_REQUIRED("plan_required"),
    PLAN_INVALID("plan_invalid"),
    DEPENDENCY_CYCLE("dependency_cycle"),
    DEPENDENCY_LIMIT("dependency_limit"),
    BUSY("busy"),
    BUNDLE_UNSUPPORTED("bundle_unsupported"),

    /** The cumulative metadata deadline of one planning walk expired (record §25.4): 503 + `Retry-After: 5`, no partial plan. */
    METADATA_UNAVAILABLE("metadata_unavailable"),
}

/** A plan node's status (record §2.1): an absent or invisible pin is `MISSING` — the two are one answer (§13). */
enum class ReleaseNodeStatus {
    DRAFT,
    RELEASED,
    MISSING,
    DISCARDED,
}

/** Why a node blocks a release (record §14's `blockers[].reason`). */
enum class BlockerReason(
    val wire: String,
) {
    /** A visible DRAFT the request did not include. */
    NOT_INCLUDED("not_included"),

    /** The pin resolves to nothing the caller may see — absent and hidden answer alike. */
    MISSING("missing"),

    /** The pinned version is DISCARDED. */
    DISCARDED("discarded"),

    /** Reserved for the lensed read projection (record §5); a release refusal never emits it (no hidden rows). */
    HIDDEN("hidden"),

    /** The node closes a cycle on the active path. */
    CYCLE("cycle"),

    /** The node lies past an exhausted bound. */
    LIMIT("limit"),

    /**
     * A RESTORE's direct pin that is present but not RELEASED (record §12.1, RULED 2026-10-25): a restore lands the version
     * RELEASED without a release's consent, so it cannot include the draft. A release refusal never emits it.
     */
    NOT_RELEASED("not_released"),
}

/** Which bound a `dependency_limit` refusal names (record §14: `{limit, maximum}`). */
enum class ReleaseLimit(
    val wire: String,
) {
    DEPTH("depth"),
    VERSIONS("versions"),
    EDGES("edges"),
    WIRE_BYTES("wire_bytes"),
}
