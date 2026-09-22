package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect

/**
 * The FORM of a JDBC URL's sub-name for the dialects that can run **in this process**
 * (#186) — the fact the registration gate and the file-roots rule both read, computed once,
 * here, so the two cannot classify the same URL differently.
 *
 * H2's URL forms (the H2 2.3.232 "Database URL Overview" — the enumeration is exhaustive on
 * purpose, and what is not listed is [Form.Unknown]), matched **exactly as the driver reads
 * them**: `ConnectionInfo.parseName()` (h2-2.3.232 sources, `org/h2/engine/ConnectionInfo.java`
 * ll. 197–219) compares `mem:`/`file:`/`tcp:`/`ssl:` CASE-SENSITIVELY and treats every other
 * sub-name as a persistent FILE database named literally — `jdbc:h2:TCP://h/x` opens `./TCP:/h/x.mv.db`
 * under the process's working directory (pinned empirically against the real jar), it does not
 * fail and it is not a network client. This classifier therefore does NOT lowercase: a mixed-case
 * `<word>:` prefix is [Form.Unknown] and refused, never re-interpreted.
 *
 *  - `mem:<name>` — in-process, in-memory;
 *  - `file:<path>` — and the BARE path with no prefix (`/abs`, `./rel`, `~/home-relative`) —
 *    in-process, file-backed;
 *  - `tcp://…` / `ssl://…` — a network client of an H2 server: NOT in-process, and gated no
 *    differently than a Postgres URL to the same host ("your datasource, your data");
 *  - every other `<word>:` prefix — the file-system pseudo-protocols `zip:`, `split:`, `nio:`,
 *    `nioMapped:`, `nioMemFS:`, `nioMemLZF:`, `memFS:`, `memLZF:` and `async:`, AND the
 *    mixed-case shapes `TCP:`/`MEM:`/`FILE:` the driver would open as literal files —
 *    [Form.Unknown], **refused** (fail-closed: a form nobody has classified is not one a rule
 *    can reason about).
 *
 * DuckDB is embedded always: `:memory:` (or the empty sub-name) is in-process memory — the
 * driver itself matches it case-INSENSITIVELY (`jdbc:duckdb::MEMORY:` opened no file when
 * probed against duckdb_jdbc 1.5.5.1), so this classifier does too — anything else a file,
 * including MotherDuck's `md:` form, which this product does not support and classifies
 * [Form.Unknown] (both cases, fail-closed) rather than wave through: `md:` reaches the network
 * on connect. SQLite is embedded always and case-SENSITIVE (xerial 3.49.1.0, probed:
 * `jdbc:sqlite::MEMORY:` creates a literal file named `:MEMORY:`, `jdbc:sqlite:FILE:x` one named
 * `FILE:x`): exactly `:memory:` is memory, a `file:` URI or bare path is a file, the driver's
 * `:resource:` classpath form is [Form.Unknown]. Every other dialect (and `LAKE`, whose DuckDB
 * engine is the dp-lake posture — datasources.md §4.1) is [Form.Server]: the classification
 * never looks at it.
 *
 * The classification is deliberately free of the file-roots RULE: it answers "what shape is
 * this URL", and the two consumers decide what the shape means — the super-admin gate
 * ([Form.isInProcess]) and the validator's roots check ([Form.InProcessFile]).
 */
object JdbcUrlForm {
    /** What the sub-name of a `jdbc:<sub-protocol>:` URL connects to. */
    sealed interface Form {
        /** A network-reachable server (or a dialect this classification does not inspect). */
        data object Server : Form

        /** An in-process engine holding its data in memory — H2 `mem:`, DuckDB/SQLite `:memory:`. */
        data object InProcessMemory : Form

        /** An in-process engine reading/writing a FILE — [rawPath] is the path text before any normalization. */
        data class InProcessFile(
            val rawPath: String,
        ) : Form

        /**
         * A sub-name form the classification does not know (H2's `zip:`/`nio:`/`async:` family,
         * SQLite's `:resource:`, DuckDB's `md:`) — refused at registration, fail-closed.
         */
        data class Unknown(
            val prefix: String?,
        ) : Form

        /** In-process memory or file — the shape #186's super-admin gate reads. */
        val isInProcess: Boolean get() = this is InProcessMemory || this is InProcessFile
    }

    /** Classifies [jdbcUrl] under [dialect]'s sub-name grammar; properties (`;`/`?` tails) never take part. */
    fun classify(
        dialect: Dialect,
        jdbcUrl: String,
    ): Form {
        val subName = connectPartOf(JdbcUrlGuard.subNameOf(jdbcUrl))
        return when (dialect) {
            Dialect.H2 -> {
                h2(subName)
            }

            Dialect.DUCKDB -> {
                if (subName.isBlank() ||
                    subName.equals(":memory:", ignoreCase = true)
                ) {
                    Form.InProcessMemory
                } else {
                    duckdb(subName)
                }
            }

            Dialect.SQLITE -> {
                sqlite(subName)
            }

            // Server dialects — and LAKE, whose DuckDB engine is the dp-lake posture (§4.1) and
            // whose registration rules this classification is not the authority for.
            else -> {
                Form.Server
            }
        }
    }

    private fun h2(subName: String): Form =
        when {
            // Case-SENSITIVE, exactly as ConnectionInfo.parseName() reads them — see the class
            // KDoc: the driver opens a mixed-case prefix as a literal FILE, so lowercasing here
            // would classify a file (or a pseudo-network shape) as the gated in-process forms.
            subName.startsWith("mem:") -> Form.InProcessMemory

            subName.startsWith("file:") -> Form.InProcessFile(subName.substring("file:".length))

            subName.startsWith("tcp:") || subName.startsWith("ssl:") -> Form.Server

            // A `<word>:` prefix this classification does not know — the H2 file-system
            // pseudo-protocols, the mixed-case shapes of the four known prefixes, and anything
            // a driver upgrade adds — is refused, never guessed.
            subName.matches(H2_PREFIXED) -> Form.Unknown(subName.substringBefore(':'))

            // What remains is a bare path (absolute, relative, or `~`-prefixed) — H2 treats it
            // as a file database, so the file rules decide whether it is acceptable.
            else -> Form.InProcessFile(subName)
        }

    private fun duckdb(subName: String): Form {
        val lower = subName.lowercase()
        return when {
            lower.startsWith("md:") -> Form.Unknown("md")
            else -> Form.InProcessFile(subName)
        }
    }

    private fun sqlite(subName: String): Form =
        when {
            // Case-SENSITIVE, as the driver reads them (xerial 3.49.1.0, probed): `:MEMORY:` is
            // a literal FILE named `:MEMORY:`, and `FILE:x` one named `FILE:x` — lowercasing
            // here would strip a prefix the driver never stripped, and the roots check would
            // then vet a path that is not the file the driver opens.
            subName == ":memory:" -> {
                Form.InProcessMemory
            }

            subName.startsWith(":resource:") -> {
                Form.Unknown(":resource:")
            }

            // The xerial driver honours SQLite's `file:` URI form — the same path rules apply
            // to what follows it.
            subName.startsWith("file:") -> {
                val rest = subName.substring("file:".length)
                if (rest == ":memory:") Form.InProcessMemory else Form.InProcessFile(rest)
            }

            else -> {
                Form.InProcessFile(subName)
            }
        }

    /** Everything before the driver-property tail — the same connect part the credential-authority check reads. */
    private fun connectPartOf(subName: String): String = subName.takeWhile { it != '?' && it != ';' }

    /** A leading `<word>:` — the shape of H2's pseudo-protocol prefixes (and of the mixed-case shapes of the real ones). */
    private val H2_PREFIXED = Regex("^[A-Za-z][A-Za-z0-9]*:.*")
}
