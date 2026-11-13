package co.datapipelines.dependencies

import java.time.Instant

/**
 * Whether a request resolved (§5.2). There is NO hidden value: a fact the caller may not see is removed by projection
 * (§12, G4), never published as a hidden node.
 */
enum class Resolution(
    val wire: String,
) {
    RESOLVED("resolved"),
    MISSING("missing"),
    UNSUPPORTED("unsupported"),
}

/** The artifact lifecycle (§5.2); a node's lifecycle is null for a family without one (schedule, datasource). */
enum class Lifecycle {
    DRAFT,
    RELEASED,
    DISCARDED,
    ;

    val wire: String get() = name
}

/** A schedule's operational condition — the `ScheduleCondition` wire values (§11.1). */
enum class ScheduleCondition(
    val wire: String,
) {
    ENABLED("enabled"),
    PAUSED("paused"),
    BLOCKED("blocked"),
}

/** The four families that carry an [Operational] condition, by family (§11.1); the other four carry null. */
enum class OperationalKind(
    val family: FamilyKey,
) {
    SCHEDULE(FamilyKey.SCHEDULE),
    API_ENDPOINT(FamilyKey.API_ENDPOINT),
    DATASOURCE(FamilyKey.DATASOURCE),
    VISUALIZATION(FamilyKey.VISUALIZATION),
}

/**
 * A family-specific operational condition (§5.2, §11.1) — exactly one shape per [OperationalKind]. Operational facts are
 * shown, never hashed: the internal fingerprint excludes them (§5.3: enabled state is not a dependency fact).
 */
sealed interface Operational {
    val kind: OperationalKind

    data class Schedule(
        val condition: ScheduleCondition,
    ) : Operational {
        override val kind: OperationalKind get() = OperationalKind.SCHEDULE
    }

    /** The API entity's `is_enabled`. */
    data class ApiEndpoint(
        val enabled: Boolean,
    ) : Operational {
        override val kind: OperationalKind get() = OperationalKind.API_ENDPOINT
    }

    /** The registry's readonly flag. */
    data class Datasource(
        val readonly: Boolean,
    ) : Operational {
        override val kind: OperationalKind get() = OperationalKind.DATASOURCE
    }

    /** The latest evidence gate outcome (§4, §15.1). */
    data class Visualization(
        val evidenceReady: Boolean,
    ) : Operational {
        override val kind: OperationalKind get() = OperationalKind.VISUALIZATION
    }
}

/**
 * An optional timestamped fact (§5.2), e.g. a datasource's last test outcome. A prior success is not a current health
 * guarantee; observations are never hashed (§5.3).
 */
data class Observation(
    val name: String,
    val observedAt: Instant,
    val value: String,
)
