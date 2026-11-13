package co.datapipelines.dependencies

/**
 * The §11.1 relationship tokens — one per §6 edge-inventory row, with the row's from/to family. Closed: an unknown token
 * is never emitted; adding one is a schema change.
 */
enum class Relationship(
    val from: FamilyKey,
    val to: FamilyKey,
) {
    TEMPLATE(FamilyKey.PIPELINE, FamilyKey.TEMPLATE),
    CHILD_PIPELINE(FamilyKey.PIPELINE, FamilyKey.PIPELINE),
    DATASOURCE(FamilyKey.PIPELINE, FamilyKey.DATASOURCE),
    IMPORT(FamilyKey.TEMPLATE, FamilyKey.TEMPLATE),
    PARAMETER_SOURCE_TEMPLATE(FamilyKey.PARAMETER_SET, FamilyKey.TEMPLATE),
    PARAMETER_SOURCE_DATASOURCE(FamilyKey.PARAMETER_SET, FamilyKey.DATASOURCE),
    TRANSFORM_TEMPLATE(FamilyKey.VISUALIZATION, FamilyKey.TEMPLATE),
    PARAMETER_SET(FamilyKey.DASHBOARD, FamilyKey.PARAMETER_SET),
    SOURCE_PIPELINE(FamilyKey.DASHBOARD, FamilyKey.PIPELINE),
    VISUALIZATION(FamilyKey.DASHBOARD, FamilyKey.VISUALIZATION),
    PIPELINE_PIN(FamilyKey.API_ENDPOINT, FamilyKey.PIPELINE),
    EXECUTOR_TARGET(FamilyKey.SCHEDULE, FamilyKey.PIPELINE),
    ;

    val wire: String get() = name

    companion object {
        /** The one token of the §6 row between two families, or null when §6 has no such row. */
        fun between(
            from: FamilyKey,
            to: FamilyKey,
        ): Relationship? = entries.firstOrNull { it.from == from && it.to == to }
    }
}

/** The §11.1 constraint kinds (closed; adding one bumps `schema_version`). */
enum class ConstraintKind(
    val wire: String,
) {
    PARAMETER_NAMES("parameter_names"),
    DATASOURCE_ACCESS("datasource_access"),
    DATASOURCE_DIALECT("datasource_dialect"),
}

/** A pipeline's datasource access mode (§11.1 `datasource_access.mode`). */
enum class AccessMode(
    val wire: String,
) {
    READ("read"),
    WRITE("write"),
}

/** One edge constraint (§6, §11.1), exactly one shape per [ConstraintKind]. */
sealed interface Constraint {
    val kind: ConstraintKind

    /** Dashboard → parameter set: every set-parameter name a `{parameter}` source binding uses [R19; F8]. */
    data class ParameterNames(
        val names: List<String>,
    ) : Constraint {
        override val kind: ConstraintKind get() = ConstraintKind.PARAMETER_NAMES
    }

    /** Pipeline → datasource: read from node semantics, write from a write-back output. */
    data class DatasourceAccess(
        val mode: AccessMode,
    ) : Constraint {
        override val kind: ConstraintKind get() = ConstraintKind.DATASOURCE_ACCESS
    }

    /** Parameter set → datasource: the selector's dialect requirement. */
    data class DatasourceDialect(
        val dialect: String,
    ) : Constraint {
        override val kind: ConstraintKind get() = ConstraintKind.DATASOURCE_DIALECT
    }
}

/**
 * The §11.1 diagnostic codes. The walk emits MISSING, DISCARDED, UNSUPPORTED_FAMILY, CYCLE and LIMIT; INCOMPLETE_VIEW is
 * projection's (§12, G4) — an unreadable node removed with its edges leaves one generic INCOMPLETE_VIEW.
 */
enum class DiagnosticKind {
    MISSING,
    DISCARDED,
    UNSUPPORTED_FAMILY,
    CYCLE,
    LIMIT,
    INCOMPLETE_VIEW,
    ;

    val wire: String get() = name
}

/** The traversal limits a LIMIT diagnostic names (§11.1: depth, nodes, edges, diagnostics, payload budget). */
enum class LimitKind(
    val wire: String,
) {
    DEPTH("depth"),
    NODES("nodes"),
    EDGES("edges"),
    DIAGNOSTICS("diagnostics"),
    WIRE_BYTES("wire_bytes"),
}
