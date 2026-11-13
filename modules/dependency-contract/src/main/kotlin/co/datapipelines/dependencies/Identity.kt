package co.datapipelines.dependencies

import java.util.UUID

/**
 * Where a lookup happens (record §5.1 `lookupScope`): the requesting workspace. One server is one deployment, so the
 * deployment context is implicit; visibility is still checked in each request's workspace.
 */
data class LookupScope(
    val workspaceId: UUID,
)

/** What a reference names before resolution (§5.1): the family, the lookup scope and the family's lookup key. */
data class ObjectAddress(
    val family: FamilyKey,
    val lookupScope: LookupScope,
    /** The family's lookup key as stored in the dependent's body — a name, a path or an id; never display text. */
    val lookupKey: String,
)

/** The three §5.1 selector shapes, as the wire's `requested_selector.kind` tokens (§11.1). */
enum class SelectorKind(
    val wire: String,
) {
    EXACT_VERSION("exact_version"),
    CURRENT("current"),
    ENTITY("entity"),
}

/**
 * How a reference selects its target (§5.1): an exact artifact version, the legacy/transitional current pointer (resolved
 * to one exact version per snapshot; the selector is kept on every edge), or an unversioned entity such as a datasource.
 * There is no `LATEST`.
 */
sealed interface Selector {
    val kind: SelectorKind

    /** The selector's number on the wire (§11.1 `requested_selector.number`); null for CURRENT and ENTITY. */
    val number: Int?

    data class ExactVersion(
        override val number: Int,
    ) : Selector {
        init {
            require(number >= 1) { "an exact version is at least 1, got $number" }
        }

        override val kind: SelectorKind get() = SelectorKind.EXACT_VERSION
    }

    data object Current : Selector {
        override val kind: SelectorKind get() = SelectorKind.CURRENT
        override val number: Int? get() = null
    }

    data object Entity : Selector {
        override val kind: SelectorKind get() = SelectorKind.ENTITY
        override val number: Int? get() = null
    }
}

/**
 * One request (§5.1): an address and a selector. Equal requests are memoised to one resolution per snapshot, and an
 * unresolved request's node key is the request itself, so two identical missing references share one node.
 */
data class ObjectRequest(
    val address: ObjectAddress,
    val selector: Selector,
) {
    val family: FamilyKey get() = address.family
}

/** Who owns a resolved object (§5.1 `ownerScope`): a workspace, or the deployment (a flat global datasource name). */
sealed interface OwnerScope {
    data class Workspace(
        val workspaceId: UUID,
    ) : OwnerScope

    data object Deployment : OwnerScope
}

/**
 * A resolved object's identity inside one deployment (§5.1): family, owner scope, the stable local entity id and, for an
 * artifact, the exact version. Aliases deduplicate on it; distinct versions stay distinct nodes.
 *
 * Not served as a whole: only [stableEntityId] reaches the wire, as the §11.1 node's `entity_id` (record erratum (9),
 * #890), and only on a node whose resolution is `resolved` — every other node carries `null`, so a hidden object's id
 * never crosses the view. The UI addresses an object's page by it, which name and version cannot do.
 */
data class ResolvedIdentity(
    val family: FamilyKey,
    val ownerScope: OwnerScope,
    val stableEntityId: String,
    val artifactVersion: Int?,
)

/** The wire's `version.kind` tokens (§11.1). */
enum class VersionKind(
    val wire: String,
) {
    ARTIFACT("artifact"),
    REVISION("revision"),
    UNVERSIONED("unversioned"),
}

/**
 * What a node's version means (§5.1, §5.2): an artifact version "vN", a mutable revision "Revision N" (a schedule — never
 * shown as a release), or an unversioned configuration (a datasource).
 */
sealed interface VersionInfo {
    val kind: VersionKind
    val number: Int?

    data class Artifact(
        override val number: Int,
    ) : VersionInfo {
        override val kind: VersionKind get() = VersionKind.ARTIFACT
    }

    data class Revision(
        override val number: Int,
    ) : VersionInfo {
        override val kind: VersionKind get() = VersionKind.REVISION
    }

    data object Unversioned : VersionInfo {
        override val kind: VersionKind get() = VersionKind.UNVERSIONED
        override val number: Int? get() = null
    }
}
