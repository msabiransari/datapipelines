package co.datapipelines.pipeline.release

import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * One datasource a release closure needs and never releases (#873 §F.3; record §8 "a datasource is a prerequisite node,
 * never released", erratum (7)): it has no version and no release status, so release consent over it is that the NAMED
 * datasource exists — [available] — in the caller's workspace scope. [readonly] is the datasource's own flag when it is
 * available and null when it is not: "unknown", never looked up and never defaulted (review R3-1), so an absent name and a
 * name granted only to another workspace are byte-identical rows (record §12, §F.3 f).
 *
 * The [name] is echoed only because the caller authored it into a body. Never a `tree_hash` input, never a checkbox.
 */
data class ReleasePrerequisite(
    val name: String,
    val available: Boolean,
    val readonly: Boolean?,
) {
    init {
        require(available || readonly == null) { "an unavailable prerequisite's readonly flag is unknown, never a value" }
    }

    /** The plan wire's prerequisite row: `{kind, name, available, readonly}`. */
    fun toWire(): Map<String, Any?> = linkedMapOf("kind" to KIND, "name" to name, "available" to available, "readonly" to readonly)

    /** The versionless blocker a refusal and a blocked plan list: `{kind, name, reason}` — no family, version or status. */
    fun blocker(): Map<String, Any?> = linkedMapOf("kind" to KIND, "name" to name, "reason" to REASON_UNAVAILABLE)

    companion object {
        /** The one prerequisite kind (record §8: datasources are the nonreleasable prerequisites). */
        const val KIND = "datasource"

        /** Absent and not visible are ONE reason (record §12; G3's provider answers MISSING for both). */
        const val REASON_UNAVAILABLE = "unavailable"
    }
}

/**
 * The apply-time prerequisite check (#873 §F.3 c): given the closure the guarded transaction re-walked and the acting
 * caller, one [ReleasePrerequisite] per distinct datasource name the closure's pipeline and parameter-set versions name,
 * resolved by NAME in the caller's workspace scope — never an unscoped lookup, never a connectivity test. The coordinator
 * calls it inside the guarded transaction on every entry path, before any family permission check and any flip, and
 * refuses with [refusal] when a prerequisite is unavailable. Implemented in `application` (it reads through the family
 * registry); [NONE] answers nothing, for a coordinator built directly in a test.
 *
 * Recreation under the same name, a configuration change or a generation advance does not change the answer (erratum (7)
 * (d)): the check compares nothing but the name's resolution.
 */
fun interface PrerequisiteCheck {
    fun check(
        closure: ReleaseClosure,
        actor: UUID,
    ): List<ReleasePrerequisite>

    companion object {
        val NONE = PrerequisiteCheck { _, _ -> emptyList() }

        /**
         * `<root family>.release.dependency_not_released` 409 for [unavailable] prerequisites (§F.3 c): never a
         * datasource-prefixed code — a nonreleasable prerequisite is not an unreleased artifact, and `ReleaseFamily` has no
         * datasource (record §8). The details are [ReleaseBlockers.prerequisiteDetails], bounded by [maxBlockers].
         */
        fun refusal(
            closure: ReleaseClosure,
            unavailable: List<ReleasePrerequisite>,
            maxBlockers: Int,
        ): DatapipelinesException =
            DatapipelinesException(
                code = closure.root.family.code(ReleaseRefusal.DEPENDENCY_NOT_RELEASED),
                message =
                    if (unavailable.size == 1) {
                        "Release blocked: 1 datasource this release needs is not available in this workspace. Nothing was released."
                    } else {
                        "Release blocked: ${unavailable.size} datasources this release needs are not available in this workspace. " +
                            "Nothing was released."
                    },
                details = ReleaseBlockers.prerequisiteDetails(closure, unavailable, maxBlockers),
            )
    }
}
