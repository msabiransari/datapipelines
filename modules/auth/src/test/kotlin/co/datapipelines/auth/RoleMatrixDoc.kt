package co.datapipelines.auth

/**
 * The auth.md §7.6 catalog, parsed (#215: the permission CATALOG — slice (a) the five role
 * columns, slice (b) the two KEY-ROLE columns and no scope tables). ONE walker, keyed by the bold
 * marker that opens the table, because a second hand-written parser is a second place for two
 * tables to disagree about where a table ends.
 *
 * A table ends at the first non-blank line after its rows that is not a table row — never at
 * a prose sentence about the CONTENT, which is the guard that broke whenever the content was
 * right (the 068 lesson, kept from the previous parser).
 *
 * The catalog row carries, besides its permission and its seven role cells, the PLACEMENT: every
 * route in code font as the handler maps it (`VERB /path`, a path variable's regex dropped) and,
 * after the cell's `MCP:` label, every MCP tool by wire name. The web module's reachability gate
 * reads the routes AND the tools with its sibling parser — it is the module that sees both the
 * handlers and `McpToolCatalog`, which this one cannot (#215: the tool permission lives on the
 * catalog entry now).
 *
 * `tests/integration-tests` carries a sibling of this object (`RoleMatrixDocE2e`): test
 * fixtures do not cross module boundaries (module-structure §4.2), and the walk over the REAL
 * application must read the same doc.
 */
object RoleMatrixDoc {
    const val CATALOG_MARKER = "**The catalog — permissions and roles:**"

    /** The five role cells of one row, decoded: which workspace roles hold it, whether super admin does, whether it is fenced. */
    data class RoleCells(
        val allowedRoles: Set<WorkspaceRole>,
        val superAdminAllowed: Boolean,
        /** Every cell reads `fenced`: no role, super admin included — the server-key route family only (§7.7). */
        val fenced: Boolean,
        /** The raw cell per column (`viewer` … `super_admin`), for failure messages. */
        val raw: Map<String, String>,
    ) {
        /** May a session holding [role] (or a super admin) perform this row's actions, per the doc? */
        fun allows(
            role: WorkspaceRole?,
            superAdmin: Boolean,
        ): Boolean = if (superAdmin) superAdminAllowed else role != null && role in allowedRoles
    }

    /** One catalog row: its first cell, its permission (null on a reserved row), its cells, and what it places. */
    data class CatalogRow(
        val label: String,
        val permission: Permission?,
        val cells: RoleCells,
        /** The KEY roles whose column admits this row (#215 record §3.2) — `api_caller`, `promotion_receiver`. */
        val keyRoles: Set<KeyRole>,
        /** The raw key-role cell per column, for failure messages. */
        val keyRoleRaw: Map<KeyRole, String>,
        /** `VERB /path` tokens, as the handlers map them. */
        val routes: List<String>,
        /** MCP tool wire names. */
        val tools: List<String>,
    )

    /** The catalog, in document order. */
    fun catalogRows(doc: String): List<CatalogRow> =
        tableRows(doc, CATALOG_MARKER)
            .filter { it.size == CATALOG_COLUMNS }
            .filterNot { it[0] == "Permission" || it[0].startsWith("---") }
            .map { cells ->
                val label = cells[0]
                val permission =
                    PERMISSION
                        .find(label)
                        ?.groupValues
                        ?.get(1)
                        ?.let(Permission::fromWire)
                val keyCells = cells.drop(2 + ROLE_COLUMNS).map { it.trim().trim('*').trim() }
                CatalogRow(
                    label = label,
                    permission = permission,
                    cells = roleCells(cells.drop(2).take(ROLE_COLUMNS)),
                    keyRoles =
                        KEY_ROLE_COLUMNS
                            .zip(keyCells)
                            .filter { (_, cell) -> allowsKeyCell(cell) }
                            .map { it.first }
                            .toSet(),
                    keyRoleRaw = KEY_ROLE_COLUMNS.zip(keyCells).toMap(),
                    routes =
                        CODE
                            .findAll(cells[1])
                            .map { it.groupValues[1] }
                            .filter { ROUTE.matches(it) }
                            .toList(),
                    // Tools are the code-font names after the cell's `MCP:` label — prose before it
                    // may put an ordinary word (`server`) in code font without becoming a tool.
                    tools =
                        CODE
                            .findAll(cells[1].substringAfter(MCP_LABEL, ""))
                            .map { it.groupValues[1] }
                            .filter { TOOL_NAME.matches(it) }
                            .toList(),
                )
            }

    /** `tool -> the permission of the catalog row that names it`; a tool named twice throws. */
    fun toolPermissions(doc: String): Map<String, Permission> {
        val result = linkedMapOf<String, Permission>()
        catalogRows(doc).forEach { row ->
            row.tools.forEach { tool ->
                val permission = requireNotNull(row.permission) { "tool `$tool` sits on a reserved row '${row.label}'" }
                require(result.put(tool, permission) == null) { "tool `$tool` appears on two §7.6 catalog rows" }
            }
        }
        return result
    }

    /** Decodes five cells in doc column order: viewer | author | promoter | ws_admin | super_admin. */
    private fun roleCells(cells: List<String>): RoleCells {
        require(cells.size == ROLE_COLUMNS) { "expected $ROLE_COLUMNS role cells, got ${cells.size}: $cells" }
        val tokens = cells.map { it.trim().trim('*').trim() }
        val fencedCells = tokens.count { it == FENCED }
        require(fencedCells == 0 || fencedCells == ROLE_COLUMNS) { "a fenced row is fenced in all five cells, got $tokens" }
        val roles = listOf(WorkspaceRole.VIEWER, WorkspaceRole.AUTHOR, WorkspaceRole.PROMOTER, WorkspaceRole.WORKSPACE_ADMIN)
        val allowed =
            roles
                .zip(tokens.take(roles.size))
                .filter { (_, cell) -> allowsCell(cell) }
                .map { (role, _) -> role }
                .toSet()
        val raw = (roles.map { it.wire } + WorkspaceContext.SUPER_ADMIN_WIRE).zip(tokens).toMap()
        return RoleCells(allowed, allowsCell(tokens.last()), fenced = fencedCells == ROLE_COLUMNS, raw = raw)
    }

    /**
     * The cell alphabet (§7.6): ✗ and `fenced` refuse; ✓, `own`, `all` and `lens` admit. Anything
     * else is a doc defect and fails loudly — a cell that parses as "refused" by accident would be
     * a row silently refusing a role the record admits.
     */
    private fun allowsCell(token: String): Boolean =
        when (token) {
            "✗", FENCED -> false
            "✓", "own", "all", "lens" -> true
            else -> throw IllegalArgumentException("Unknown §7.6 role cell '$token' — the alphabet is ✓ ✗ own all lens fenced")
        }

    /**
     * The key-role cell alphabet (#215; keys v2 #233): ✗ refuses; ✓ admits; `own` admits the
     * key's OWN runs only (the executions it started, record §3.2); `bound` admits the published
     * paths bound to the key (§7.7); `lens` admits through the promoter lens (the `mcp:promoter`
     * column mirrors the promoter member cell, and an `mcp` promoter key IS lensed — A13);
     * `fenced` refuses (the promotion receiving rows, §7.7). Anything else fails loudly, for
     * the reason [allowsCell] does.
     */
    private fun allowsKeyCell(token: String): Boolean =
        when (token) {
            "✗", "fenced" -> false
            "✓", "own", "bound", "lens" -> true
            else -> throw IllegalArgumentException("Unknown §7.6 key-role cell '$token' — the alphabet is ✓ ✗ own bound lens fenced")
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

    private const val FENCED = "fenced"
    private const val MCP_LABEL = "MCP:"
    private const val ROLE_COLUMNS = 5

    /**
     * The key-role columns, in doc order, after the five member-role columns: the two transport
     * roles (#215 slice (b)) and — keys v2 (#233, A13/A14) — the three MEMBER roles an `mcp`
     * key may carry, each judged by the member column of the same name.
     */
    private val KEY_ROLE_COLUMNS =
        listOf(KeyRole.API_CALLER, KeyRole.PROMOTION_RECEIVER, KeyRole.AUTHOR, KeyRole.PROMOTER, KeyRole.WORKSPACE_ADMIN)

    /** Permission | Surfaces | five roles | two key roles. */
    private val CATALOG_COLUMNS = 2 + ROLE_COLUMNS + KEY_ROLE_COLUMNS.size

    /** A dotted catalog name in code font: `pipeline.read`, `workspace.members.manage`. */
    private val PERMISSION = Regex("`([a-z_]+\\.[a-z_.]+)`")
    private val CODE = Regex("`([^`]+)`")
    private val ROUTE = Regex("(GET|POST|PUT|PATCH|DELETE) /\\S*")
    private val TOOL_NAME = Regex("[a-z][a-z_]+")
}
