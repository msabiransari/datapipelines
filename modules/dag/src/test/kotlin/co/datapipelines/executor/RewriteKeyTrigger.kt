package co.datapipelines.executor

import org.h2.api.Trigger
import java.sql.Connection

/**
 * An H2 `BEFORE INSERT` row trigger that rewrites the key column (position 0) of any row whose
 * second column is `rewrite` — the "trigger rewriting the key" case of #1034 (record §5): the
 * author supplied an id and the database stored a different one.
 */
class RewriteKeyTrigger : Trigger {
    override fun fire(
        conn: Connection,
        oldRow: Array<Any?>?,
        newRow: Array<Any?>?,
    ) {
        val row = newRow ?: return
        if (row[1] == "rewrite") row[0] = (row[0] as Number).toLong() + REWRITE_OFFSET
    }

    private companion object {
        const val REWRITE_OFFSET = 1000L
    }
}
