package co.datapipelines.datasources

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * The declared roots a file-backed datasource's path must live under (#186) —
 * `datapipelines.datasources.file-roots`, default **empty**, and empty means NO file-backed
 * datasource is registrable by anyone, super admin included. This is the framework-free domain
 * half; [DatasourceFileRootsProperties] is the Spring binding half.
 *
 * The rule runs on the RAW path text before any normalization is trusted, because every one of
 * these is a known escape shape:
 *
 *  - `..` segments — refused outright rather than normalized away: a path that needed them was
 *    written to leave the root, and "normalize first, check after" is exactly the pattern that
 *    turns `root/../../etc` into a pass;
 *  - a leading `~` — H2 expands it against the process user's home; the product does not
 *    evaluate shell shortcuts in a stored URL;
 *  - URL-encoded separators (`%2f`, `%5c`, either case) — the URL is stored and re-read by
 *    layers that may decode them; what the check sees must be what the driver gets;
 *  - a relative path — it would resolve against the server process's working directory, an
 *    invisible root nobody declared.
 *
 * Only then is the path resolved: symlinks in the PARENT are followed (`toRealPath`, so the
 * parent must exist — the database file itself may not exist yet; H2/SQLite/DuckDB create it on
 * first connect), and the resolved path must sit under one of the roots, themselves resolved at
 * bind time.
 */
class DatasourceFileRoots(
    roots: List<Path>,
) {
    /**
     * The canonical roots, each resolved with `toRealPath` (symlinks followed) so the
     * containment comparison below compares two REAL paths — a root handed in through a symlink
     * and a parent resolved to its target must still compare equal. Construction therefore
     * requires each root to exist (fail-fast, like the binding half).
     */
    val roots: List<Path> = roots.map { it.toRealPath() }.distinct()

    /**
     * The refusal reason for [rawPath], or null when the path is under a declared root.
     * The reason text names the CONFIG KEY an operator would change, never a path the caller
     * was not told about — the caller wrote the path; the roots are the deployment's.
     */
    fun refusalFor(rawPath: String): String? {
        if (rawPath.isBlank()) {
            return "a file-backed URL must carry a path"
        }
        if (rawPath.startsWith("~")) {
            return "a file-backed URL path may not begin with '~' (the driver's home-directory expansion is not evaluated)"
        }
        if (CONTAINS_ENCODED_SEPARATOR.containsMatchIn(rawPath)) {
            return "a file-backed URL path may not carry URL-encoded separators (%2F, %5C)"
        }
        if (rawPath.split('/', '\\').any { it == ".." }) {
            return "a file-backed URL path may not contain '..' segments"
        }
        val path =
            try {
                Path.of(rawPath)
            } catch (e: java.nio.file.InvalidPathException) {
                return "the file-backed URL path is not a valid path: ${e.reason}"
            }
        if (!path.isAbsolute) {
            return "a file-backed URL path must be absolute, not '$rawPath'"
        }
        if (roots.isEmpty()) {
            return "no datasource file roots are declared — set ${DatasourceFileRootsProperties.CONFIG_KEY} " +
                "to register file-backed datasources (H2 file:, SQLite, DuckDB); without it only server URLs and in-memory engines are registrable"
        }
        val parent = path.parent ?: return "a file-backed URL path must name a file below a directory"
        val realParent =
            try {
                parent.toRealPath()
            } catch (_: IOException) {
                return "the directory of '$rawPath' does not exist"
            } catch (_: SecurityException) {
                return "the directory of '$rawPath' is not readable"
            }
        val resolved = realParent.resolve(path.fileName.toString())
        if (roots.none { resolved.startsWith(it) }) {
            return "the path '$rawPath' is not under any declared datasource file root (${DatasourceFileRootsProperties.CONFIG_KEY})"
        }
        return null
    }

    companion object {
        /** The posture when the key is unset: every file-backed registration is refused. */
        val EMPTY = DatasourceFileRoots(emptyList())

        private val CONTAINS_ENCODED_SEPARATOR = Regex("%2[fF]|%5[cC]")
    }
}

/**
 * The `datapipelines.datasources.file-roots` binding (#186, configuration.md §3.26). Lives in
 * the datasources module — beside the validator that enforces it — rather than in web's
 * `DatasourcesProperties`: the rule is a property of the datasource aggregate, and web is only
 * one of its surfaces.
 *
 * Each declared root must be absolute, must exist, and must be a directory — checked at bind
 * time, so a mistyped root refuses the boot instead of silently refusing every registration
 * later (or, worse, silently ADMITTING a subtree nobody declared because the comparison was
 * against a path string rather than a real directory). Blank entries are dropped: a
 * comma-separated env var with a trailing comma is an operator slip, not a refusal.
 */
@ConfigurationProperties(prefix = "datapipelines.datasources")
data class DatasourceFileRootsProperties(
    val fileRoots: List<String> = emptyList(),
) {
    init {
        fileRoots.forEachIndexed { index, raw ->
            val path =
                try {
                    Path.of(raw)
                } catch (e: java.nio.file.InvalidPathException) {
                    throw IllegalArgumentException("$CONFIG_KEY[$index] is not a valid path: $raw", e)
                }
            require(path.isAbsolute) { "$CONFIG_KEY[$index] must be absolute: $raw" }
            require(path.exists()) { "$CONFIG_KEY[$index] does not exist: $raw" }
            require(path.isDirectory()) { "$CONFIG_KEY[$index] is not a directory: $raw" }
        }
    }

    /** The domain view the validator enforces: blank entries dropped, duplicates collapsed. */
    fun toFileRoots(): DatasourceFileRoots =
        DatasourceFileRoots(
            fileRoots
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { Path.of(it) },
        )

    companion object {
        const val CONFIG_KEY = "datapipelines.datasources.file-roots"
    }
}
