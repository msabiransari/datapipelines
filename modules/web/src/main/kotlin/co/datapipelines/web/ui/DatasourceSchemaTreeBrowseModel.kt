package co.datapipelines.web.ui

import co.datapipelines.datasources.CurrentSchemaUnknownException
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceUnreachableException
import co.datapipelines.datasources.SchemaEntry
import co.datapipelines.datasources.SchemaIntrospector
import co.datapipelines.datasources.TableInfo
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.ui.Model
import java.security.MessageDigest

/**
 * The Tables view's tree model for every DISCOVERED-SCHEMA dialect (162, #156) — the 058/067
 * explorer pattern [LakeTableBrowseModel] already established for the LAKE registry, narrowed
 * to a FIXED depth instead of an arbitrary-deep namespace: schemas → tables → columns, exactly
 * the `datasources.md` §7A introspection flow, and no deeper.
 *
 * A schemaless dialect (SQLite) reports no schemas at all — [SchemaIntrospector.schemas]'s own
 * KDoc calls an empty list a valid answer, not an error — so [fillRoot] renders the TABLES level
 * directly at the root rather than an always-empty folder wrapper.
 *
 * Read-only by construction: there is no selection, no detail pane and no CRUD here, same as
 * the LAKE tree. Introspection opens a LIVE connection ([SchemaIntrospector]'s own KDoc), so a
 * connection failure or a driver unable to report its current schema is a state of the SCREEN,
 * not a bug — each level renders the catalogued §13 code and message INLINE rather than
 * bubbling to [UiExceptionHandler]'s toast (a toast is for actions; this is a read the reader
 * is already looking at) or a blank pane (#156 fix-wanted §3). The three-line translation from
 * the module-local exceptions to the catalogued codes is the same one the REST controller and
 * the MCP tools each keep for themselves ([DatasourceUnreachableException]'s KDoc, "two
 * three-line catches are the accepted cost of the layering") — this is the UI's own copy.
 *
 * Declared as an explicit `@Bean` in [UiConfig], not by a stereotype: the house rule is zero DI
 * stereotypes in production code (015 / module-structure §8.4), `ArchitectureGuardTest` enforces
 * it.
 */
class DatasourceSchemaTreeBrowseModel(
    private val introspector: SchemaIntrospector,
) {
    /**
     * The page's root level: schema folders, or — on a schemaless dialect — the datasource's
     * tables directly (`flat`, read by [fillTables] with no namespace).
     */
    fun fillRoot(
        model: Model,
        datasource: Datasource,
    ): String {
        model.addAttribute("datasource", datasource)
        var flat = false
        val view =
            render(model, SCHEMAS_VIEW) {
                val schemas = introspector.schemas(datasource)
                if (schemas.entries.isEmpty()) {
                    flat = true
                } else {
                    model.addAttribute("schemas", schemas.entries.map(::SchemaFolderView))
                    model.addAttribute("schemasTruncated", schemas.truncated)
                }
            }
        // The template's own `flat` branch reads this — a LOCAL variable is not a model
        // attribute, and the root fragment would otherwise always take the "has schemas"
        // (empty) branch on a schemaless dialect, because `${flat}` was never bound.
        model.addAttribute("flat", flat)
        return if (flat) fillTables(model, datasource, namespace = null, offset = 0) else view
    }

    /**
     * One schema's tables, paged — or, when [namespace] is null/blank, the datasource's own
     * tables on a schemaless dialect ([fillRoot]'s `flat` branch). Sorted by name: the
     * introspector makes no ordering promise ([SchemaIntrospector.tables]'s KDoc), and an
     * unordered page would reorder itself between Prev/Next clicks.
     */
    fun fillTables(
        model: Model,
        datasource: Datasource,
        namespace: String?,
        offset: Int,
    ): String {
        model.addAttribute("datasource", datasource)
        val ns = namespaceOf(namespace)
        model.addAttribute("namespace", ns.joinToString(NAMESPACE_SEPARATOR))
        model.addAttribute("levelId", tablesLevelId(ns))
        return render(model, TABLES_VIEW) {
            val page = introspector.tables(datasource, namespaceFilter = ns.ifEmpty { null })
            val sorted = page.tables.sortedBy { it.name }
            val at = maxOf(0, offset)
            model.addAttribute("tables", sorted.drop(at).take(PAGE_SIZE).map { TableRowView(ns, it) })
            model.addAttribute("tablesTruncated", page.truncated)
            model.addAttribute("offset", at)
            model.addAttribute("hasMore", sorted.size > at + PAGE_SIZE)
            model.addAttribute("total", sorted.size)
        }
    }

    /** One table's columns — name, canonical type, nullability — through the same read the REST/MCP surfaces use. */
    fun fillColumns(
        model: Model,
        datasource: Datasource,
        namespace: String?,
        table: String,
    ): String {
        model.addAttribute("datasource", datasource)
        model.addAttribute("table", table)
        val ns = namespaceOf(namespace)
        model.addAttribute("levelId", columnsLevelId(ns, table))
        return render(model, COLUMNS_VIEW) {
            val columns = introspector.columns(datasource, table, namespaceFilter = ns.ifEmpty { null })
            model.addAttribute("columns", columns)
        }
    }

    private fun namespaceOf(namespace: String?): List<String> =
        namespace
            ?.split(NAMESPACE_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** Runs [fill], translating the two module-local exceptions; always returns [view]. */
    private fun render(
        model: Model,
        view: String,
        fill: () -> Unit,
    ): String {
        try {
            fill()
        } catch (e: DatasourceUnreachableException) {
            setError(model, PipelineErrorCodes.Execution.DATASOURCE_UNREACHABLE, e.message)
        } catch (e: CurrentSchemaUnknownException) {
            setError(model, PipelineErrorCodes.Execution.PARAMETER_REQUIRED, e.message)
        } catch (e: DatapipelinesException) {
            setError(model, e.code, e.message)
        }
        return view
    }

    private fun setError(
        model: Model,
        code: String,
        message: String?,
    ) {
        model.addAttribute("introspectionErrorCode", code)
        model.addAttribute("introspectionErrorMessage", message ?: "The introspection failed.")
    }

    companion object {
        /** A schema's own tables page size — the datasources screen's page size (§4.5). */
        const val PAGE_SIZE = 25

        const val SCHEMAS_VIEW = "partials/datasource-tables-schemas"
        const val TABLES_VIEW = "partials/datasource-tables-tree"
        const val COLUMNS_VIEW = "partials/datasource-tables-columns"

        private const val NAMESPACE_SEPARATOR = "."
        private const val LEVEL_ID_HEX_LENGTH = 16

        /** The flat root's tables level — no namespace to derive an id from. */
        private const val ROOT_TABLES_LEVEL_ID = "ds-tables-flat"

        fun tablesLevelId(namespace: List<String>): String =
            if (namespace.isEmpty()) ROOT_TABLES_LEVEL_ID else "ds-tables-" + digest(namespace.joinToString(NAMESPACE_SEPARATOR))

        fun columnsLevelId(
            namespace: List<String>,
            table: String,
        ): String = "ds-columns-" + digest((namespace + table).joinToString(NAMESPACE_SEPARATOR))

        /** Hex characters of a level id digest — the [LakeTableBrowseModel] rule, same reason. */
        private fun digest(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }.take(LEVEL_ID_HEX_LENGTH)
        }
    }
}

/** One schema folder, as the root level needs it — the [LakeTableFolderView] precedent. */
data class SchemaFolderView(
    val entry: SchemaEntry,
) {
    val path: String get() = entry.namespace.joinToString(".")
    val label: String get() = entry.label
    val levelId: String get() = DatasourceSchemaTreeBrowseModel.tablesLevelId(entry.namespace)
}

/** One table row, as a schema's (or the flat root's) level needs it. */
data class TableRowView(
    val namespace: List<String>,
    val table: TableInfo,
) {
    val name: String get() = table.name
    val type: String get() = table.type
    val levelId: String get() = DatasourceSchemaTreeBrowseModel.columnsLevelId(namespace, table.name)
}
