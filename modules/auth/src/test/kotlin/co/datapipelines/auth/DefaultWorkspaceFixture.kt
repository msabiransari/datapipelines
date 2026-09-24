package co.datapipelines.auth

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #146: the V4-seeded `default` workspace, put back after a `TRUNCATE users CASCADE`.
 *
 * TRUNCATE ... CASCADE truncates every table holding a foreign key toward users —
 * `workspaces` included, through `created_by` — whether or not any row actually
 * references one (Postgres truncates by constraint, not by data). So a suite that
 * truncates users leaves the shared container without the workspace V4 seeded, and a
 * suite that runs later in the same JVM and pins a key to it ([AuthHttpBoundaryTest])
 * or reads it back fails on the missing row — the fork assignment decided it. The
 * "both go back" discipline as a call instead of a comment: a truncating suite calls
 * [ensure] right after its truncate, and a suite that relies on the seeded world calls
 * [ensure] before it seeds. Idempotent, so class order never decides it again.
 */
object DefaultWorkspaceFixture {
    /** The well-known id V4__workspaces_rekey.sql pins (metadata-db §4.11). */
    val ID: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")

    /** The same row V4 seeds; ON CONFLICT because a well-behaved neighbour may have left it in place. */
    fun ensure(jdbc: NamedParameterJdbcTemplate) {
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name, is_personal, created_by)" +
                " VALUES ('$ID', 'default', 'Default', FALSE, NULL)" +
                " ON CONFLICT (id) DO NOTHING",
        )
    }
}
