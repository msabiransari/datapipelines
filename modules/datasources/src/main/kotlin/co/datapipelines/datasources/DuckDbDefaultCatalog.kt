package co.datapipelines.datasources

/**
 * The default catalog a DuckDB JDBC URL opens into, derived from the URL TEXT alone (#790): what `current_database()`
 * answers on a connection to that URL, without opening one. The lake reference scanner resolves a written `c.s.t`
 * against it (`c` equal to it names the one-segment namespace `[s]`), and it runs inside a metadata transaction, where
 * no customer connection may be opened.
 *
 * The rules restate what the pinned `duckdb_jdbc` answers; `DuckDbDefaultCatalogTest` opens the driver for every form
 * below and holds this function EQUAL to the engine (the engine is the oracle):
 *
 * - the empty sub-name, `:memory:` and the named in-memory `:memory:<label>` (case-SENSITIVE: `:MEMORY:` is a literal
 *   FILE of that name) → `memory`;
 * - `memory:` and `memory:<label>` (lower case, the engine's own in-memory prefix) → `memory`;
 * - a file path → its last segment up to the first `.` (`/data/shop.v2.db` → `shop`; empty pieces are skipped, so
 *   `.hidden` → `hidden`), ignoring a `?…` tail; the engine's reserved names `main`, `temp` and `system` (exact case)
 *   become `main_db`, `temp_db` and `system_db`.
 *
 * The driver's `;key=value` property tail is never part of the answer. Every other shape answers NULL — a sub-name
 * with any other `<word>:` prefix (`md:`, `file:`, `MEMORY:`, an extension's storage prefix), surrounding whitespace,
 * a URL that is not `jdbc:duckdb:`, or a path whose name is empty. Null means "not known", never a guess: the caller
 * treats it as an unknown catalog.
 *
 * Pure: it reads a string. It opens, stats and creates nothing, and the URL (which may carry properties) is never
 * logged or returned.
 */
object DuckDbDefaultCatalog {
    /** The catalog DuckDB names an anonymous or in-memory database. */
    const val IN_MEMORY = "memory"

    /** The default catalog [jdbcUrl] opens into, or null when the URL is not a form this function knows. */
    fun of(jdbcUrl: String): String? {
        if (!jdbcUrl.startsWith(PREFIX)) return null
        val connect = jdbcUrl.substring(PREFIX.length).substringBefore(';')
        return when {
            connect.isBlank() -> IN_MEMORY
            connect != connect.trim() -> null
            connect.startsWith(NAMED_MEMORY) || connect.startsWith(MEMORY_PREFIX) -> IN_MEMORY
            STORAGE_PREFIX.containsMatchIn(connect) -> null
            else -> fileCatalog(connect.substringBefore('?'))
        }
    }

    private fun fileCatalog(path: String): String? {
        val name =
            path
                .split('/')
                .lastOrNull { it.isNotEmpty() }
                ?.split('.')
                ?.firstOrNull { it.isNotEmpty() }
                ?: return null
        return if (name in RESERVED) "${name}_db" else name
    }

    private const val PREFIX = "jdbc:duckdb:"
    private const val NAMED_MEMORY = ":memory:"
    private const val MEMORY_PREFIX = "memory:"

    /** A leading `<word>:` before any path separator — an extension's or a service's prefix, not a file path. */
    private val STORAGE_PREFIX = Regex("^[A-Za-z0-9_]+:")

    /** The engine's own catalog names; a file named like one is attached under `<name>_db` instead. */
    private val RESERVED = setOf("main", "temp", "system")
}
