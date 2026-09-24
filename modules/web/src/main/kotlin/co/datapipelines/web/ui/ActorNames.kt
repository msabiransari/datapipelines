package co.datapipelines.web.ui

import co.datapipelines.auth.UserKind
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * The display identity of one actor — the person (or the key's owner) a version, a release or
 * an execution is stamped with.
 *
 * 106: the explorer detail states *who* made each version, and every stamp in
 * `pipeline_versions` / `template_versions` / `pipeline_executions` is a bare `users.id`.
 * Rendering the UUID was the pre-106 behaviour and it is what the template-editor's source
 * pane still does ("this app resolves no display name for it anywhere"); a folder-path screen
 * that reads like prose cannot also print a 36-character id in a row of four facts.
 *
 * ONE batched query per render (never one per row), exactly [PipelineNames]' shape and for the
 * same reason: `dag` and `pipeline-contract` own the stamped rows and neither may depend on
 * `auth`'s user table, so the join happens web-side.
 *
 * A missing id — a user removed after stamping a version — simply does not appear in the
 * result, and the caller renders the truncated id. No crash, and no invented name.
 *
 * #215 (record §3.3): an API or server key acts as its own `service` identity, whose display name
 * is the key's name, so its runs and received versions read "<key name> (API key)" — history keeps
 * that name after the key is revoked, because the identity row is deactivated, never deleted.
 */
class ActorNames(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun lookup(actorIds: Collection<UUID>): Map<UUID, String> {
        val ids = actorIds.toSet()
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .query(
                "SELECT id, display_name, kind FROM users WHERE id IN (:ids)",
                mapOf("ids" to ids),
            ) { rs, _ -> rs.getObject("id", UUID::class.java) to displayed(rs.getString("display_name"), rs.getString("kind")) }
            .toMap()
    }

    companion object {
        /** A key's identity is named for its key (record §3.3); a person and the System account by their own name. */
        fun displayed(
            displayName: String,
            kind: String,
        ): String = if (UserKind.fromWire(kind) == UserKind.SERVICE) "$displayName$KEY_SUFFIX" else displayName

        private const val KEY_SUFFIX = " (API key)"

        /** What a row shows for an actor no longer in `users`: the id, short enough to grep. */
        fun fallback(actor: UUID): String = actor.toString().take(SHORT_ID) + "…"

        private const val SHORT_ID = 8
    }
}
