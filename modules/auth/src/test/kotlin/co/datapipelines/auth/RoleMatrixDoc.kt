package co.datapipelines.auth

/**
 * The auth.md §7.6 tables, parsed (2026-09-20, role-first). ONE walker for all four tables,
 * keyed by the bold marker that opens each, because a second hand-written parser is a second
 * place for two tables to disagree about where a table ends.
 *
 * A table ends at the first non-blank line after its rows that is not a table row — never at
 * a prose sentence about the CONTENT, which is the guard that broke whenever the content was
 * right (the 068 lesson, kept from the previous parser).
 *
 * `tests/integration-tests` carries a sibling of this object (`RoleMatrixDocE2e`): test
 * fixtures do not cross module boundaries (module-structure §4.2), and the walk over the REAL
 * application must read the same doc.
 */
object RoleMatrixDoc {
    const val REST_ROLES_MARKER = "**REST endpoints — roles:**"
    const val REST_SCOPES_MARKER = "**REST endpoints — key scopes**"
    const val MCP_ROLES_MARKER = "**MCP tools — roles**"
    const val MCP_SCOPES_MARKER = "**MCP tools — key scopes**"

    /** The five role cells of one row, decoded: which workspace roles are admitted, and whether super admin is. */
    data class RoleCells(
        val allowedRoles: Set<WorkspaceRole>,
        val superAdminAllowed: Boolean,
        /** The raw cell per column, for the walk's reporting (`own`, `all`, `lens`, ✓, ✗). */
        val raw: Map<String, String>,
    ) {
        /** May a session holding [role] (or a super admin) perform this row's actions, per the doc? */
        fun allows(
            role: WorkspaceRole?,
            superAdmin: Boolean,
        ): Boolean = if (superAdmin) superAdminAllowed else role != null && role in allowedRoles
    }

    /** One REST row: its label, its constant (null on a reserved row) and its cells. */
    data class RestRow(
        val label: String,
        val operation: ScopeMatrix.RestOperation?,
        val cells: RoleCells,
    )

    /** The REST role table, in document order. */
    fun restRoleRows(doc: String): List<RestRow> =
        tableRows(doc, REST_ROLES_MARKER)
            .filter { it.size == REST_ROLE_COLUMNS }
            .filterNot { it[0] == "Operation" || it[0].startsWith("---") }
            .map { cells ->
                val label = cells[0]
                val operation =
                    CONSTANT
                        .findAll(label)
                        .map { it.groupValues[1] }
                        .firstNotNullOfOrNull { token -> ScopeMatrix.RestOperation.entries.firstOrNull { it.name == token } }
                RestRow(label, operation, roleCells(cells.drop(2)))
            }

    /** `RestOperation name -> min key scope`, from the REST key-scope table. */
    fun restKeyScopes(doc: String): Map<String, Scope> =
        scopeTable(doc, REST_SCOPES_MARKER) { token -> ScopeMatrix.RestOperation.entries.any { it.name == token } }

    /** `tool -> cells`, from the MCP role table; rows group several tools, each gets the row's cells. */
    fun mcpRoleRows(doc: String): Map<String, RoleCells> {
        val result = linkedMapOf<String, RoleCells>()
        tableRows(doc, MCP_ROLES_MARKER)
            .filter { it.size == MCP_ROLE_COLUMNS }
            .filterNot { it[0] == "Tools" || it[0].startsWith("---") }
            .forEach { cells ->
                val roleCells = roleCells(cells.drop(1))
                TOOL.findAll(cells[0]).forEach { m -> result[m.groupValues[1]] = roleCells }
            }
        return result
    }

    /** `tool -> min key scope`, from the MCP key-scope table. */
    fun mcpKeyScopes(doc: String): Map<String, Scope> = scopeTable(doc, MCP_SCOPES_MARKER) { token -> TOOL_NAME.matches(token) }

    private fun scopeTable(
        doc: String,
        marker: String,
        isKey: (String) -> Boolean,
    ): Map<String, Scope> {
        val result = linkedMapOf<String, Scope>()
        tableRows(doc, marker)
            .filter { it.size == SCOPE_COLUMNS }
            .filterNot { it[0] == "Min scope" || it[0].startsWith("---") }
            .forEach { cells ->
                val scope = Scope.fromWire(requireNotNull(TOKEN.find(cells[0])?.groupValues?.get(1)) { "no scope token in '${cells[0]}'" })
                TOKEN
                    .findAll(cells[1])
                    .map { it.groupValues[1] }
                    .filter(isKey)
                    .forEach { key -> result[key] = scope }
            }
        return result
    }

    /** Decodes five cells in doc column order: viewer | author | promoter | ws_admin | super_admin. */
    private fun roleCells(cells: List<String>): RoleCells {
        require(cells.size == ROLE_COLUMNS) { "expected $ROLE_COLUMNS role cells, got ${cells.size}: $cells" }
        val roles = listOf(WorkspaceRole.VIEWER, WorkspaceRole.AUTHOR, WorkspaceRole.PROMOTER, WorkspaceRole.WORKSPACE_ADMIN)
        val allowed =
            roles
                .zip(cells.take(roles.size))
                .filter { (_, cell) -> allowsCell(cell) }
                .map { (role, _) -> role }
                .toSet()
        val raw = (roles.map { it.wire } + "super_admin").zip(cells).toMap()
        return RoleCells(allowed, allowsCell(cells.last()), raw)
    }

    /**
     * The cell alphabet (§7.6): ✗ refuses; ✓, `own`, `all` and `lens` admit. Anything else is a
     * doc defect and fails loudly — a cell that parses as "refused" by accident would be a row
     * silently refusing a role the record admits.
     */
    private fun allowsCell(cell: String): Boolean =
        when (val token = cell.trim().trim('*').trim()) {
            "✗" -> false
            "✓", "own", "all", "lens" -> true
            else -> throw IllegalArgumentException("Unknown §7.6 role cell '$token' — the alphabet is ✓ ✗ own all lens")
        }

    /** The rows of the table that follows [marker], split into trimmed cells. */
    private fun tableRows(
        doc: String,
        marker: String,
    ): List<List<String>> {
        val start = doc.indexOf(marker)
        require(start >= 0) { "Could not find '$marker' in auth.md §7.6" }
        val section =
            buildString {
                var seenRow = false
                for (line in doc.substring(start + marker.length).lineSequence()) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("|")) {
                        seenRow = true
                    } else if (seenRow && trimmed.isNotEmpty()) {
                        break
                    }
                    appendLine(line)
                }
            }
        return section
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { it.trim('|').split("|").map(String::trim) }
            .toList()
    }

    private const val ROLE_COLUMNS = 5

    /** Operation | Endpoints | five roles. */
    private const val REST_ROLE_COLUMNS = 2 + ROLE_COLUMNS

    /** Tools | five roles. */
    private const val MCP_ROLE_COLUMNS = 1 + ROLE_COLUMNS

    /** Min scope | operations-or-tools. */
    private const val SCOPE_COLUMNS = 2

    private val CONSTANT = Regex("`([A-Z][A-Z_]+)`")
    private val TOOL = Regex("`([a-z][a-z_]+)`")
    private val TOKEN = Regex("`([A-Za-z_]+)`")
    private val TOOL_NAME = Regex("[a-z][a-z_]+")
}
