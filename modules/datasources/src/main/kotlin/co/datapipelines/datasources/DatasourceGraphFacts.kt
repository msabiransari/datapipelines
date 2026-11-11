package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import java.util.UUID

/**
 * What the dependency graph may know about one live datasource (#874 G3, dependency-graph record §5.1, §5.3): the four
 * facts a tree node shows — [name], [displayName], [dialect] (the node's subtype) and [isReadonly] — and the two INTERNAL
 * identity facts the graph's fingerprint carries, [incarnationId] and [generation] (V59). Nothing of the connection: no
 * JDBC URL, username, credential, properties, owner workspace or grants.
 *
 * The identity facts come from the stored row and nowhere else. The constructor is `internal` and the repository's
 * graph-facts row mapper is its only caller, so neither a create/update input ([Datasource] carries no identity) nor
 * a caller outside this module can supply or forge them (the G3 brief §A.2; a test greps `src/main` for a
 * second construction site). [toString] prints neither identity fact, so a log line or an assertion message never
 * carries one.
 */
class DatasourceGraphFacts internal constructor(
    val name: String,
    val displayName: String,
    val dialect: Dialect,
    val isReadonly: Boolean,
    /** V59 `incarnation_id`: which incarnation of [name] this row is. Internal; never on a wire. */
    val incarnationId: UUID,
    /** V59 `generation`: the row's protected local change counter (≥ 1). Internal; never on a wire. */
    val generation: Long,
) {
    override fun toString(): String =
        "DatasourceGraphFacts(name=$name, displayName=$displayName, dialect=${dialect.wire}, isReadonly=$isReadonly)"
}

/**
 * A datasource row's identity facts for tests and diagnostics (V59), soft-deleted rows included: [isDeleted] says
 * which. Like [DatasourceGraphFacts] it is read only from the stored row and its [toString] prints neither value.
 */
class Incarnation internal constructor(
    val incarnationId: UUID,
    val generation: Long,
    val isDeleted: Boolean,
) {
    override fun toString(): String = "Incarnation(isDeleted=$isDeleted)"
}
