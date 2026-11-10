package co.datapipelines.dependencies

/**
 * A family's stable wire key (record §3.2, §4): `pipeline`, `template`, … — a value type over the string, NOT an enum,
 * because the closed set is a property of PRODUCTION ADMISSION rather than of the type: [FamilyRegistry.production]
 * refuses any descriptor outside [PRODUCTION] and G4's wire admission consults it, while a test-built registry admits a
 * synthetic family (`test_widget`, A28) with no walker change.
 */
@JvmInline
value class FamilyKey(
    val wire: String,
) {
    init {
        require(WIRE.matches(wire)) { "a family key is a lower-case snake_case token, got '$wire'" }
    }

    override fun toString(): String = wire

    companion object {
        private val WIRE = Regex("[a-z][a-z0-9_]{0,63}")

        val PIPELINE = FamilyKey("pipeline")
        val TEMPLATE = FamilyKey("template")
        val PARAMETER_SET = FamilyKey("parameter_set")
        val VISUALIZATION = FamilyKey("visualization")
        val DASHBOARD = FamilyKey("dashboard")
        val API_ENDPOINT = FamilyKey("api_endpoint")
        val SCHEDULE = FamilyKey("schedule")
        val DATASOURCE = FamilyKey("datasource")

        private val ARTIFACT = Capabilities(ReleaseCapability.ARTIFACT_RELEASE, PromotionCapability.VERSIONED_ARTIFACT)
        private val NOT_TRANSFERABLE = Capabilities(ReleaseCapability.NONE, PromotionCapability.NONE)

        /**
         * The frozen eight §4 families with their release/promotion capability pairs, in the record's table order. The
         * six artifact families declare `ARTIFACT_RELEASE`/`VERSIONED_ARTIFACT`; schedule and datasource declare `NONE`
         * for both. Capabilities describe supported operations, never role permissions or current eligibility (§4).
         */
        val PRODUCTION: Map<FamilyKey, Capabilities> =
            linkedMapOf(
                PIPELINE to ARTIFACT,
                TEMPLATE to ARTIFACT,
                PARAMETER_SET to ARTIFACT,
                VISUALIZATION to ARTIFACT,
                DASHBOARD to ARTIFACT,
                API_ENDPOINT to ARTIFACT,
                SCHEDULE to NOT_TRANSFERABLE,
                DATASOURCE to NOT_TRANSFERABLE,
            )
    }
}

/** The §4 release capability: what release operations a family supports (§11.1 `capabilities.release`). */
enum class ReleaseCapability {
    ARTIFACT_RELEASE,
    NONE,
    ;

    val wire: String get() = name
}

/** The §4 promotion capability (§11.1 `capabilities.promotion`); declaring it authorizes no promotion protocol (E1). */
enum class PromotionCapability {
    VERSIONED_ARTIFACT,
    NONE,
    ;

    val wire: String get() = name
}

/** A family's §4 capability pair — explicit capabilities instead of one overloaded `managed` Boolean. */
data class Capabilities(
    val release: ReleaseCapability,
    val promotion: PromotionCapability,
) {
    companion object {
        /** What an UNSUPPORTED node reports: no family adapter answered, so no operation is supported. */
        val NONE = Capabilities(ReleaseCapability.NONE, PromotionCapability.NONE)
    }
}
