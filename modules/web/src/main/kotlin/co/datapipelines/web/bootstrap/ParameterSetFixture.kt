package co.datapipelines.web.bootstrap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/**
 * One `parameter_sets` entry of an examples file (#374, workspace spec §9) — the authored set
 * document `{name, display_name, description?, parameters: [...]}` — and the one place that turns it
 * into the body `POST /api/v1/parameter-sets/import` accepts.
 *
 * ## What the file may not carry
 * `id`, `version` and `body_hash` belong to the SEEDER, not to the content: a fixed id in a shared
 * file would be `parameter.version.conflict`/`id_taken` for the SECOND personal workspace seeded
 * (`parameter_sets.id` is the primary key of the whole server, record C29), and a hash in a file
 * would be a number nobody can recompute by hand. A file that names one is refused at startup.
 *
 * ## The envelope per workspace ([envelope])
 * - `id` — derived from the workspace and the set's name, so it is unique per workspace (no
 *   `id_taken` across workspaces) and stable for one workspace (the same call twice names the same
 *   set);
 * - no `version` — the import then lands the body RELEASED at version 1 and sets the pointer (the
 *   version-less arm of `ParameterSetService.importValidated`), and does NOT compare `body_hash`;
 * - `body_hash` — the envelope's shape demands a string even though this arm never compares it, so
 *   the seeder states the SHA-256 of the body's text. It is a shape field here, deliberately not
 *   the database's own hash (which only the database can compute).
 *
 * The selector templates a set pins come from the SAME file's `templates` array, and the seeder
 * imports every template before any set — the import validates each pin at save time.
 */
internal class ParameterSetFixture private constructor(
    val name: String,
    private val body: ObjectNode,
) {
    /** The import body for [workspaceId]: `{"parameter_set": {id, name, body_hash, ...body}}` (the §21.4 envelope, no `templates`). */
    fun envelope(
        workspaceId: UUID,
        mapper: ObjectMapper,
    ): String {
        val payload = mapper.createObjectNode()
        payload.put("id", idFor(workspaceId))
        payload.put("name", name)
        payload.put("body_hash", sha256Hex(mapper.writeValueAsString(body)))
        payload.setAll<ObjectNode>(body)
        return mapper.writeValueAsString(mapper.createObjectNode().set<ObjectNode>("parameter_set", payload))
    }

    /** The set's id in [workspaceId]: a name-based UUID over the pair, so two workspaces never share one. */
    fun idFor(workspaceId: UUID): String = UUID.nameUUIDFromBytes("$workspaceId|$name".toByteArray(Charsets.UTF_8)).toString()

    companion object {
        /** The keys the SEEDER owns (see the class KDoc) — a file that carries one is refused. */
        private val SEEDER_OWNED = listOf("id", "version", "body_hash")

        /**
         * One entry of the array as a fixture. Structural checks only — the body's own validity is
         * the import's job (the full §4 check, the selector probe included), at seeding time.
         */
        fun parse(
            node: JsonNode,
            index: Int,
            path: Path,
        ): ParameterSetFixture {
            val where = "Bootstrap examples file '$path' parameter_sets[$index]"
            if (node !is ObjectNode) throw ExampleContentFileException("$where must be an object.")
            val name = nameOf(node, where)
            val body = node.deepCopy().also { it.remove("name") }
            return ParameterSetFixture(name, body)
        }

        /** The entry's name, after the two refusals of its identity keys: a blank name, and a key only the seeder may write. */
        private fun nameOf(
            node: ObjectNode,
            where: String,
        ): String {
            val name = node.get("name")?.takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
            val owned = SEEDER_OWNED.firstOrNull { node.has(it) }
            return when {
                name == null -> {
                    throw ExampleContentFileException("$where must carry a non-blank string 'name'.")
                }

                owned != null -> {
                    throw ExampleContentFileException(
                        "$where ('$name') must not carry '$owned': the seeder derives the id per workspace and owns the version and hash.",
                    )
                }

                else -> {
                    name
                }
            }
        }

        private fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
