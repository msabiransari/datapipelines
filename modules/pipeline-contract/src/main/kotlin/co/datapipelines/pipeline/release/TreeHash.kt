package co.datapipelines.pipeline.release

import java.security.MessageDigest

/**
 * The release plan's concurrency token (record §2.1): a versioned canonical UTF-8 serialisation of the root identity and
 * every resolved node's identity, exact version, status, body hash and current pointer, and every pin occurrence (from,
 * to, path), each list sorted by its members' UTF-8 bytes, digested with SHA-256 and prefixed with the format version.
 *
 * Covered: identity (family word, pin key, resolved UUID, version), status, body hash (null kept distinct from absent — a MISSING node
 * has `"body_hash":null`), current pointer, edges with their paths. NOT covered: display names, `node_id`s (positional
 * locators), selections, warnings. It is a token, not an authorisation: permission, lens, posture and evidence are
 * rechecked separately at submit. `TreeHashTest` pins a golden digest. S1 is unmerged and stateless; its UUID correction retains `v1`.
 * Subsequent changes to a shipped serialisation require a format bump.
 */
object TreeHash {
    const val FORMAT = "v1"

    /** `v1:` + 64 lower-case hex digits — the only shape a submitted `tree_hash` may take. */
    val SHAPE = Regex("^v1:[0-9a-f]{64}$")

    /** The digest of [closure]; refuses `dependency_limit` (`wire_bytes`) when the serialisation exceeds [maxWireBytes]. */
    fun of(
        closure: ReleaseClosure,
        maxWireBytes: Int,
    ): String {
        val bytes = canonical(closure).toByteArray(Charsets.UTF_8)
        if (bytes.size > maxWireBytes) throw ReleaseRefusals.limit(closure.root.family, ReleaseLimit.WIRE_BYTES, maxWireBytes)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "$FORMAT:" + digest.joinToString("") { "%02x".format(it) }
    }

    /** The canonical serialisation — exposed for the golden test and for diagnosing a mismatch, never sent. */
    fun canonical(closure: ReleaseClosure): String {
        val nodes =
            closure.nodes.map { node ->
                "{\"id\":${id(node.identity)},\"entity_uuid\":${node.entityUuid?.toString()?.let(::string) ?: "null"}," +
                    "\"status\":${string(node.status.name)}," +
                    "\"body_hash\":${node.bodyHash?.let(::string) ?: "null"},\"current\":${node.currentVersion ?: "null"}}"
            }
        val edges = closure.edges.map { "{\"from\":${id(it.from)},\"to\":${id(it.to)},\"path\":${string(it.path)}}" }
        return "{\"format\":${string(FORMAT)},\"workspace\":${string(closure.root.workspaceId.toString())}," +
            "\"root\":${id(closure.root)},\"nodes\":${sorted(nodes)},\"edges\":${sorted(edges)}}"
    }

    private fun id(identity: ReleaseNodeIdentity): String =
        "[${string(identity.family.wire)},${string(identity.entityId)},${identity.version}]"

    private fun sorted(members: List<String>): String =
        members
            .map { it.toByteArray(Charsets.UTF_8) }
            .sortedWith(BYTE_ORDER)
            .joinToString(",", "[", "]") { String(it, Charsets.UTF_8) }

    /** A JSON string literal: quotes, backslashes and every control character escaped; the rest verbatim UTF-8. */
    private fun string(value: String): String =
        buildString(value.length + 2) {
            append('"')
            value.forEach { char ->
                when {
                    char == '"' -> append("\\\"")
                    char == '\\' -> append("\\\\")
                    char < ' ' -> append("\\u%04x".format(char.code))
                    else -> append(char)
                }
            }
            append('"')
        }

    private val BYTE_ORDER =
        Comparator<ByteArray> { a, b ->
            val common = minOf(a.size, b.size)
            var i = 0
            var result = 0
            while (i < common && result == 0) {
                result = (a[i].toInt() and BYTE_MASK) - (b[i].toInt() and BYTE_MASK)
                i++
            }
            if (result != 0) result else a.size - b.size
        }

    private const val BYTE_MASK = 0xff
}
