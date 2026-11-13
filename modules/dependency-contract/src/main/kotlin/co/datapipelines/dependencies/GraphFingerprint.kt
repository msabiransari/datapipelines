package co.datapipelines.dependencies

import java.security.MessageDigest

/**
 * The internal graph fingerprint (record §5.3) — NOT the release `tree_hash` (native consent, `TreeHash`) and NOT an
 * artifact `bodyHash`; server-side only, never on the wire (G4 omits it).
 *
 * Canonical bytes: a domain-separation header and the [FORMAT] version, then one record per root, node and edge occurrence,
 * each a JSON array, sorted by their UTF-8 bytes (never by collation or display text) and joined by `\n`. Nodes and
 * edges are named by TYPED KEYS — a resolved node by family, owner scope, stable entity id and version; an unresolved
 * one by its request (address and selector) — never by the snapshot's node ids, so the fingerprint does not depend on
 * discovery order. Null is written as `null`, so absent and empty stay distinct.
 *
 * Covered: identity, exact version/revision, resolution, lifecycle, current pointer, body hash, definition generation,
 * the family's extractor schema version, and every edge's relationship, source path, requested selector, resolved
 * version and constraints. Excluded (§5.3): observed time, names, display names, subtypes (labels), operational state
 * (enabled/paused/readonly/evidence), observations, capabilities, diagnostics, node and edge ids.
 */
object GraphFingerprint {
    /** The canonical form's version; a change to the bytes is a new format, never an edit of this one. */
    const val FORMAT = "fp1"

    private const val DOMAIN = "datapipelines/dependency-graph/internal-fingerprint"

    /** The canonical text of a graph; [extractorVersion] answers a family's extractor schema version (null: unregistered). */
    fun canonical(
        roots: List<String>,
        nodes: List<Node>,
        edges: List<Edge>,
        extractorVersion: (FamilyKey) -> Int?,
    ): String {
        val keys = nodes.associate { it.nodeId to key(it) }
        val records =
            roots.map { array(str("root"), keys.getValue(it)) } +
                nodes.map { node(it, keys.getValue(it.nodeId), extractorVersion(it.family)) } +
                edges.map { edge(it, keys.getValue(it.fromNodeId), keys.getValue(it.toNodeId)) }
        val sorted = records.map { it.toByteArray(Charsets.UTF_8) }.sortedWith(UTF8_ORDER)
        return "$DOMAIN\n$FORMAT\n" + sorted.joinToString("\n") { String(it, Charsets.UTF_8) }
    }

    /** `fp1:` + the lower-case hex SHA-256 of [canonical]'s UTF-8 bytes. */
    fun digest(canonical: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "$FORMAT:" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun key(node: Node): String {
        val identity = node.identity
        return if (identity != null) {
            val owner =
                when (val scope = identity.ownerScope) {
                    is OwnerScope.Workspace -> "workspace:${scope.workspaceId}"
                    OwnerScope.Deployment -> "deployment"
                }
            array(str("resolved"), str(identity.family.wire), str(owner), str(identity.stableEntityId), num(identity.artifactVersion))
        } else {
            val address = node.requestedAddress
            array(
                str("unresolved"),
                str(address.family.wire),
                str("workspace:${address.lookupScope.workspaceId}"),
                str(address.lookupKey),
                str(node.requestedSelector.kind.wire),
                num(node.requestedSelector.number),
            )
        }
    }

    private fun node(
        node: Node,
        key: String,
        extractorVersion: Int?,
    ): String =
        array(
            str("node"),
            key,
            str(node.resolution.wire),
            str(node.lifecycle?.wire),
            str(node.versionInfo?.kind?.wire),
            num(node.versionInfo?.number),
            num(node.currentVersion),
            str(node.bodyHash),
            node.generation?.toString() ?: "null",
            num(extractorVersion),
        )

    private fun edge(
        edge: Edge,
        from: String,
        to: String,
    ): String =
        array(
            str("edge"),
            from,
            to,
            str(edge.relationship.wire),
            str(edge.sourcePath),
            str(edge.requestedSelector.kind.wire),
            num(edge.requestedSelector.number),
            num(edge.resolvedVersion),
            list(edge.constraints.map(::constraint).sortedWith(STRING_UTF8_ORDER)),
        )

    private fun constraint(constraint: Constraint): String =
        when (constraint) {
            is Constraint.ParameterNames -> {
                val names = constraint.names.map(::str).sortedWith(STRING_UTF8_ORDER)
                array(str(constraint.kind.wire), list(names))
            }

            is Constraint.DatasourceAccess -> {
                array(str(constraint.kind.wire), str(constraint.mode.wire))
            }

            is Constraint.DatasourceDialect -> {
                array(str(constraint.kind.wire), str(constraint.dialect))
            }
        }

    private fun array(vararg members: String): String = list(members.asList())

    private fun list(members: List<String>): String = members.joinToString(",", "[", "]")

    private fun num(value: Int?): String = value?.toString() ?: "null"

    /** A JSON string literal: quote, backslash and every control character escaped; null is `null`. */
    private fun str(value: String?): String {
        if (value == null) return "null"
        val out = StringBuilder(value.length + 2).append('"')
        value.forEach { c ->
            when {
                c == '"' || c == '\\' -> out.append('\\').append(c)
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    private val UTF8_ORDER: Comparator<ByteArray> = Comparator { a, b -> compareUnsigned(a, b) }

    private val STRING_UTF8_ORDER: Comparator<String> =
        Comparator { a, b -> compareUnsigned(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8)) }

    private fun compareUnsigned(
        a: ByteArray,
        b: ByteArray,
    ): Int = java.util.Arrays.compareUnsigned(a, b)
}
