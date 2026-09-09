package co.datapipelines.datasources

/**
 * The per-dialect [TableStatsPlan]s (datasources.md §7C) — every catalog query every adapter
 * declares, in one place so the recipes can be read side by side. Adapters reference their own
 * plan by one line; [DialectAdapter.tableStatsPlan]'s KDoc states the contract.
 *
 * Verification status per dialect:
 *  - POSTGRES, MYSQL: live against testcontainers (TableStatsPostgresIntegrationTest,
 *    TableStatsMysqlIntegrationTest).
 *  - H2, DUCKDB, SQLITE: live against the pinned embedded drivers (TableStatsH2Test,
 *    TableStatsEmbeddedTest).
 *  - ORACLE, MSSQL: by construction only — no container exists (Oracle is `-Poracle`-gated,
 *    MSSQL amd64-gated); their SQL follows the same descriptor contract the verified
 *    dialects exercise.
 */
internal object TableStatsPlans {
    /**
     * Postgres: `pg_class.reltuples` for the row estimate (`-1` = never analyzed → NULL via
     * `nullif`), `greatest(last_analyze, last_autoanalyze)` for the as-of, indexes from
     * `pg_index` + `pg_attribute` ordered by `indkey` position through
     * `unnest(...) WITH ORDINALITY` — never by parsing `pg_indexes.indexdef` — and column
     * statistics from `pg_stats`, whose `histogram_bounds` first/last elements are the
     * min/max. Expression-index key columns (indkey attnum 0) join to no attribute and drop
     * out of the listing.
     */
    val POSTGRES =
        TableStatsPlan(
            source = "pg_class",
            distinctStoredAsSignedRatio = true,
            rowEstimate =
                TableStatsPlan.Query(
                    """
                    SELECT nullif(c.reltuples, -1)::bigint AS row_estimate,
                           greatest(s.last_analyze, s.last_autoanalyze) AS stats_as_of
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    LEFT JOIN pg_stat_all_tables s ON s.schemaname = n.nspname AND s.relname = c.relname
                    WHERE n.nspname = ? AND c.relname = ?
                    """.trimIndent(),
                    listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                ),
            indexes =
                listOf(
                    TableStatsPlan.Query(
                        """
                        SELECT i.relname AS index_name, ix.indisunique AS is_unique, ix.indisprimary AS is_primary,
                               k.n AS ordinal, a.attname AS column_name
                        FROM pg_class t
                        JOIN pg_namespace n ON n.oid = t.relnamespace
                        JOIN pg_index ix ON ix.indrelid = t.oid
                        JOIN pg_class i ON i.oid = ix.indexrelid
                        JOIN unnest(ix.indkey) WITH ORDINALITY AS k(attnum, n) ON true
                        JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
                        WHERE n.nspname = ? AND t.relname = ?
                        ORDER BY i.relname, k.n
                        """.trimIndent(),
                        listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                    ),
                ),
            columnStats =
                TableStatsPlan.Query(
                    // histogram_bounds travels as its array TEXT: the pg_stats column is
                    // `anyarray`, and unnest(anyarray) is refused outright (verified live on
                    // postgres:16-alpine — "cannot determine element type of \"anyarray\"
                    // argument"). The reader splits the text form conservatively and takes
                    // the first/last element as min/max.
                    """
                    SELECT s.attname AS column_name, s.n_distinct AS n_distinct, s.null_frac AS null_fraction,
                           s.histogram_bounds::text AS histogram_bounds
                    FROM pg_stats s
                    WHERE s.schemaname = ? AND s.tablename = ?
                    ORDER BY s.attname
                    """.trimIndent(),
                    listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                ),
        )

    /**
     * MySQL: `information_schema.tables.table_rows` (InnoDB's estimate — refreshed by
     * `ANALYZE TABLE`; no as-of timestamp the catalog will swear to) and indexes from
     * `information_schema.statistics` in `seq_in_index` order. Functional key parts report a
     * NULL column name and drop out. `information_schema.column_statistics` (8.0+ histogram
     * JSON) is deliberately NOT read: the histogram payload has no trivially reliable min/max
     * for every column type, so MySQL reports no per-column stats.
     */
    val MYSQL =
        TableStatsPlan(
            source = "information_schema.tables",
            rowEstimate =
                TableStatsPlan.Query(
                    """
                    SELECT t.table_rows AS row_estimate
                    FROM information_schema.tables t
                    WHERE t.table_schema = ? AND t.table_name = ?
                    """.trimIndent(),
                    listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                ),
            indexes =
                listOf(
                    TableStatsPlan.Query(
                        """
                        SELECT s.index_name AS index_name,
                               CASE WHEN s.non_unique = 0 THEN 1 ELSE 0 END AS is_unique,
                               CASE WHEN s.index_name = 'PRIMARY' THEN 1 ELSE 0 END AS is_primary,
                               s.seq_in_index AS ordinal, s.column_name AS column_name
                        FROM information_schema.statistics s
                        WHERE s.table_schema = ? AND s.table_name = ?
                        ORDER BY s.index_name, s.seq_in_index
                        """.trimIndent(),
                        listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                    ),
                ),
        )

    /**
     * H2: `information_schema.tables.row_count_estimate` and indexes from
     * `information_schema.indexes`/`index_columns` (verified against h2 2.3.232:
     * `index_type_name` is `PRIMARY KEY` / `UNIQUE INDEX` / `INDEX`, `ordinal_position` is
     * 1-based). H2 keeps no per-column catalog statistics.
     */
    val H2 =
        TableStatsPlan(
            source = "h2_information_schema",
            rowEstimate =
                TableStatsPlan.Query(
                    """
                    SELECT t.row_count_estimate AS row_estimate
                    FROM information_schema.tables t
                    WHERE t.table_schema = ? AND t.table_name = ?
                    """.trimIndent(),
                    listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                ),
            indexes =
                listOf(
                    TableStatsPlan.Query(
                        """
                        SELECT i.index_name AS index_name,
                               CASE WHEN i.index_type_name <> 'INDEX' THEN 1 ELSE 0 END AS is_unique,
                               CASE WHEN i.index_type_name = 'PRIMARY KEY' THEN 1 ELSE 0 END AS is_primary,
                               c.ordinal_position AS ordinal, c.column_name AS column_name
                        FROM information_schema.indexes i
                        JOIN information_schema.index_columns c
                          ON c.index_schema = i.index_schema AND c.index_name = i.index_name
                         AND c.table_schema = i.table_schema AND c.table_name = i.table_name
                        WHERE i.table_schema = ? AND i.table_name = ?
                        ORDER BY i.index_name, c.ordinal_position
                        """.trimIndent(),
                        listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                    ),
                ),
        )

    /**
     * DuckDB (embedded, non-LAKE): `duckdb_tables().estimated_size` for the row estimate
     * (verified 1.5.5.1). Indexes come from TWO catalogs, merged by name with the constraint
     * side winning: `duckdb_constraints()` carries PRIMARY KEY / UNIQUE constraints with the
     * key list as an ORDERED `constraint_column_names` array (unnested WITH ORDINALITY), while
     * explicit `CREATE INDEX` entries appear only in `duckdb_indexes()` — and, verified on
     * 1.5.5.1, NOWHERE else: `duckdb_constraints()` does not list them. Their columns arrive
     * as the `expressions` text (`['"label"', fare]`), which the reader parses conservatively;
     * an expression that is not a plain column (a computed index's `(a + b)`) is dropped.
     * DuckDB keeps no per-column catalog statistics.
     */
    val DUCKDB =
        TableStatsPlan(
            source = "duckdb_tables",
            rowEstimate =
                TableStatsPlan.Query(
                    """
                    SELECT t.estimated_size AS row_estimate
                    FROM duckdb_tables() t
                    WHERE t.schema_name = ? AND t.table_name = ?
                    """.trimIndent(),
                    listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                ),
            indexes =
                listOf(
                    TableStatsPlan.Query(
                        """
                        SELECT c.constraint_name AS index_name,
                               true AS is_unique,
                               (c.constraint_type = 'PRIMARY KEY') AS is_primary,
                               u.n AS ordinal, u.col AS column_name
                        FROM duckdb_constraints() c, unnest(c.constraint_column_names) WITH ORDINALITY AS u(col, n)
                        WHERE c.schema_name = ? AND c.table_name = ?
                          AND c.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
                        ORDER BY c.constraint_name, u.n
                        """.trimIndent(),
                        listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                    ),
                    TableStatsPlan.Query(
                        """
                        SELECT i.index_name AS index_name, i.is_unique AS is_unique, i.is_primary AS is_primary,
                               i.expressions AS expressions
                        FROM duckdb_indexes() i
                        WHERE i.schema_name = ? AND i.table_name = ?
                        ORDER BY i.index_name
                        """.trimIndent(),
                        listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                    ),
                ),
        )

    /**
     * SQLite: the row estimate is `sqlite_stat1`'s first stat token for the table (every stat
     * row of a table leads with its row count, so any single row answers; verified on
     * sqlite-jdbc 3.49.1.0). `sqlite_stat1` exists only after the first ANALYZE —
     * [TableStatsPlan.rowEstimateCatalogOptional] absorbs the "no such table" refusal into a
     * null estimate with `stats_source: none`. Indexes come from the table-valued pragma
     * functions, which DO accept a bind parameter (verified): `pragma_index_list` flags
     * unique/primary (`origin = 'pk'`) and `pragma_index_info` orders the key columns by
     * `seqno`. An `INTEGER PRIMARY KEY` rowid alias has no index at all — none is reported,
     * which is the truth of the storage engine. SQLite keeps no per-column catalog statistics.
     */
    val SQLITE =
        TableStatsPlan(
            source = "sqlite_stat1",
            rowEstimateCatalogOptional = true,
            rowEstimate =
                TableStatsPlan.Query(
                    """
                    SELECT CASE WHEN instr(stat, ' ') > 0 THEN substr(stat, 1, instr(stat, ' ') - 1)
                                ELSE stat END AS row_estimate
                    FROM sqlite_stat1
                    WHERE tbl = ?
                    LIMIT 1
                    """.trimIndent(),
                    listOf(TableStatsPlan.Bind.TABLE),
                ),
            indexes =
                listOf(
                    TableStatsPlan.Query(
                        """
                        SELECT l.name AS index_name,
                               l."unique" AS is_unique,
                               CASE WHEN l.origin = 'pk' THEN 1 ELSE 0 END AS is_primary,
                               i.seqno + 1 AS ordinal, i.name AS column_name
                        FROM pragma_index_list(?) l
                        JOIN pragma_index_info(l.name) i
                        ORDER BY l.name, i.seqno
                        """.trimIndent(),
                        listOf(TableStatsPlan.Bind.TABLE),
                    ),
                ),
        )

    /**
     * Oracle: `all_tables.num_rows` + `last_analyzed`, indexes from
     * `all_indexes`/`all_ind_columns` with the primary flag looked up in `all_constraints`
     * (constraint_type 'P'). By construction — no container exists (§5.4.1's `-Poracle` flag
     * gates the driver itself).
     */
    val ORACLE =
        TableStatsPlan(
            source = "all_tables",
            rowEstimate =
                TableStatsPlan.Query(
                    """
                    SELECT t.num_rows AS row_estimate, t.last_analyzed AS stats_as_of
                    FROM all_tables t
                    WHERE t.owner = ? AND t.table_name = ?
                    """.trimIndent(),
                    listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                ),
            indexes =
                listOf(
                    TableStatsPlan.Query(
                        """
                        SELECT i.index_name AS index_name,
                               CASE WHEN i.uniqueness = 'UNIQUE' THEN 1 ELSE 0 END AS is_unique,
                               CASE WHEN pk.index_name IS NULL THEN 0 ELSE 1 END AS is_primary,
                               c.column_position AS ordinal, c.column_name AS column_name
                        FROM all_indexes i
                        JOIN all_ind_columns c
                          ON c.index_owner = i.owner AND c.index_name = i.index_name
                        LEFT JOIN all_constraints pk
                               ON pk.owner = i.table_owner AND pk.table_name = i.table_name
                              AND pk.index_name = i.index_name AND pk.constraint_type = 'P'
                        WHERE i.table_owner = ? AND i.table_name = ?
                        ORDER BY i.index_name, c.column_position
                        """.trimIndent(),
                        listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                    ),
                ),
        )

    /**
     * MSSQL: summed `sys.partitions.rows` over the heap/clustered allocation (index_id 0/1 —
     * summing every partition would count nonclustered copies), indexes from
     * `sys.indexes`/`sys.index_columns` in `key_ordinal` order (0 = included column, dropped).
     * By construction — the module's MSSQL container is amd64-gated
     * (MssqlConnectivityIntegrationTest).
     */
    val MSSQL =
        TableStatsPlan(
            source = "sys.partitions",
            rowEstimate =
                TableStatsPlan.Query(
                    """
                    SELECT SUM(p.rows) AS row_estimate
                    FROM sys.partitions p
                    JOIN sys.tables t ON t.object_id = p.object_id
                    JOIN sys.schemas s ON s.schema_id = t.schema_id
                    WHERE s.name = ? AND t.name = ? AND p.index_id IN (0, 1)
                    """.trimIndent(),
                    listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                ),
            indexes =
                listOf(
                    TableStatsPlan.Query(
                        """
                        SELECT i.name AS index_name, i.is_unique AS is_unique, i.is_primary_key AS is_primary,
                               ic.key_ordinal AS ordinal, c.name AS column_name
                        FROM sys.indexes i
                        JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                        JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                        JOIN sys.tables t ON t.object_id = i.object_id
                        JOIN sys.schemas s ON s.schema_id = t.schema_id
                        WHERE s.name = ? AND t.name = ?
                          AND i.type > 0 AND i.is_hypothetical = 0 AND ic.key_ordinal > 0
                        ORDER BY i.name, ic.key_ordinal
                        """.trimIndent(),
                        listOf(TableStatsPlan.Bind.SCHEMA, TableStatsPlan.Bind.TABLE),
                    ),
                ),
        )
}
