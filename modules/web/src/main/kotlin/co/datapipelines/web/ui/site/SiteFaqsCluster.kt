package co.datapipelines.web.ui.site

/**
 * The eight 073 intent-cluster pages' FAQ content (111 §B): each page gets its FAQ block
 * from its OWN claims — no new facts. Split from [SiteFaqs] so no single FAQ object carries
 * the whole site (detekt LargeClass); [SiteFaqs] stays the /faq page's and the v2 hub pages'
 * lists.
 */
object SiteFaqsCluster {
    /** The pillar's five, from its own cards: reach, the credential, the write, the revoke, the audit. */
    val PILLAR: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Which databases can one MCP server reach?",
                "PostgreSQL, MySQL, SQL Server, Oracle, SQLite and DuckDB — one MCP endpoint over all six, each with " +
                    "its own dialect adapter, and any of them marked read-only where writes must never happen. The " +
                    "dialect catalog with drivers and licenses is docs/datasources.md §4.",
                "docs/datasources.md §4",
            ),
            FaqEntry(
                "Does the agent ever see a database password?",
                "No. Datasource credentials are encrypted at rest in the metadata database and never returned — not " +
                    "through a tool, not through the UI, not in a pipeline's JSON. The agent holds a key to the " +
                    "server; the server holds the credentials. Credential storage is docs/datasources.md §7.",
                "docs/datasources.md §7",
            ),
            FaqEntry(
                "What stops a write against a read-only source?",
                "The datasource's read-only flag refuses the write-shaped uses — a DML node's source, a DDL node's " +
                    "source, writing a node's output back — at save time and again at execution against the live " +
                    "row, and the documented pattern pairs it with a SELECT-only database user. The flag's " +
                    "semantics are docs/datasources.md §5.7.",
                "docs/datasources.md §5.7",
            ),
            FaqEntry(
                "How do I revoke one agent?",
                "Revoke its key — one checkbox, and the agent stops working within about a minute, the validation " +
                    "cache's TTL. No shared password to rotate, and nobody else's key is touched. Revocation and " +
                    "the validation cache are docs/auth.md §11.4.",
                "docs/auth.md §11.4",
            ),
            FaqEntry(
                "Is every tool call audited?",
                "Yes: the dispatcher writes mcp.tool.called for every call and mcp.tool.write for every mutating " +
                    "one, into the same audit log that carries logins and key issuance — with the key and the " +
                    "tool, never the SQL text or the row data. The audit log is docs/auth.md §10.",
                "docs/auth.md §10",
            ),
        )

    /** The client-setup page's four: the client, the scope, the drivers, the troubleshooting. */
    val ADD_TO_CLIENT: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Which MCP client should I use?",
                "Any client that speaks MCP over Streamable HTTP: Claude Code, Cursor, GitHub Copilot and " +
                    "JSON-configured clients like Claude Desktop all take the same two facts — the POST /mcp " +
                    "endpoint and an API key header. The transport and authentication are docs/mcp-server.md §3.2.",
                "docs/mcp-server.md §3.2",
            ),
            FaqEntry(
                "What scope should the agent's key carry?",
                "The smallest one that does the job: read to browse pipelines and schemas, execute to also run " +
                    "them, author to also create and edit drafts. The key can never exceed its creator's scopes " +
                    "at issue time. The scope table is docs/auth.md §7.5.",
                "docs/auth.md §7.5",
            ),
            FaqEntry(
                "Which database drivers ship in the image?",
                "Postgres, SQL Server, SQLite and DuckDB drivers are bundled; the MySQL and Oracle drivers are " +
                    "user-supplied for license reasons, and registering a datasource whose driver is absent fails " +
                    "at save time. The driver matrix is docs/deployment.md §3.5.",
                "docs/deployment.md §3.5",
            ),
            FaqEntry(
                "The tools did not come back — what now?",
                "Read the refusal: auth.api_key.missing means the header did not arrive; invalid or expired means " +
                    "wrong, revoked or the owner is deactivated; auth.scope.insufficient means the key is real " +
                    "but too small. A silently ignored browser session is expected — /mcp takes API keys only. " +
                    "The troubleshooting list is docs/mcp-server.md §4.2.",
                "docs/mcp-server.md §4.2",
            ),
        )

    /** The AI-data-pipeline page's four: grounding, binding, the release, the audit. */
    val AI_DATA_PIPELINE: List<FaqEntry> =
        listOf(
            FaqEntry(
                "What does the agent read before it writes SQL?",
                "Your real schema: datasources_get_schemas, datasources_get_tables, datasources_get_columns and " +
                    "datasources_preview_rows return the columns that exist and the values they actually hold, so " +
                    "the query is grounded rather than inferred from table names. The tool list is " +
                    "docs/mcp-server.md §6.1.",
                "docs/mcp-server.md §6.1",
            ),
            FaqEntry(
                "How do parameter values reach the SQL?",
                "As bound parameters: a declared name is translated to a positional parameter on a prepared " +
                    "statement before the driver sees it, so a STRING value is never parsed as SQL — an injected " +
                    "payload matches no row. The binding contract is docs/templates.md §8.4.",
                "docs/templates.md §8.4",
            ),
            FaqEntry(
                "Can an agent change a released pipeline?",
                "No. The first write to a released pipeline copies it into a draft, and the released version keeps " +
                    "running until a person releases the new one — one draft at a time, releases immutable. The " +
                    "lifecycle is docs/versioning.md §3.1.",
                "docs/versioning.md §3.1",
            ),
            FaqEntry(
                "What does the audit log record for agent runs?",
                "Every MCP call is written with the key and the tool — mcp.tool.called, and mcp.tool.write for the " +
                    "mutating ones — beside the login and key-issuance events, and never the SQL text, the rows " +
                    "or the parameter values. The audit log is docs/auth.md §10.1.",
                "docs/auth.md §10.1",
            ),
        )

    /** The text-to-SQL page's four: the honest no, the artefact, the failure, the cross-engine join. */
    val TEXT_TO_SQL: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Does datapipelines generate the SQL?",
                "No — your agent does that, and the page says so in its first sentence. What the server adds is " +
                    "everything on either side: the real schema going in, and a versioned, reviewable artefact " +
                    "coming out. The grounding tools are docs/mcp-server.md §6.1.",
                "docs/mcp-server.md §6.1",
            ),
            FaqEntry(
                "What happens to the query after it answers once?",
                "It becomes an artefact: the SQL is saved as a template referenced by a pipeline in declarative " +
                    "JSON — named, parameterised, diffable — so anyone can rerun it without asking the agent " +
                    "again. The pipeline shape is docs/pipeline-contract.md §3.1.",
                "docs/pipeline-contract.md §3.1",
            ),
            FaqEntry(
                "What does the agent get when a query fails?",
                "A catalogued {domain}.{entity}.{failure} code with details — a missing parameter, an unreachable " +
                    "datasource, a refused write — instead of a driver message to parse, so the agent can branch " +
                    "on the code. The error-code catalog is docs/pipeline-contract.md §13.",
                "docs/pipeline-contract.md §13",
            ),
            FaqEntry(
                "Can it join two databases in one question?",
                "Yes — each source is read in place and staged into an in-memory database created for that one " +
                    "execution, and the join runs there as ordinary SQL. That is the staging join, and the " +
                    "worked example runs it on real NYC data. Staging is docs/staging.md §1.",
                "docs/staging.md §1",
            ),
        )

    /** The Airflow comparison's four: the scheduler honestly, composing, the agent, reproducibility. */
    val COMPARE_AIRFLOW: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Does this replace Airflow's scheduler?",
                "No — pipelines here execute on demand, by an agent, a REST call or a person; there are no " +
                    "cron triggers, backfills or SLAs. If scheduling is the problem, Airflow is the answer, and " +
                    "the page says so first. The execution model is docs/dag-executor.md §5.",
                "docs/dag-executor.md §5",
            ),
            FaqEntry(
                "Can Airflow trigger these pipelines?",
                "Yes, and that is the composition the page recommends: an operator POSTs to the execution " +
                    "endpoint and follows the per-milestone SSE stream, so Airflow keeps the platform view while " +
                    "the cross-database SQL stays here. The execution API is docs/rest-api.md §6.",
                "docs/rest-api.md §6",
            ),
            FaqEntry(
                "Can an agent author the work?",
                "The full lifecycle is on the MCP surface — introspect schemas, create a template, create a " +
                    "pipeline, execute it, read the rows back — which is the shape an Airflow DAG repository " +
                    "does not offer a tool-calling agent. The tool list is docs/mcp-server.md §6.1.",
                "docs/mcp-server.md §6.1",
            ),
            FaqEntry(
                "Are reruns reproducible?",
                "A released pipeline is immutable — editing one copies it into a draft — so what ran last quarter " +
                    "is still exactly what ran, without a git archaeology exercise. The lifecycle is " +
                    "docs/versioning.md §3.1.",
                "docs/versioning.md §3.1",
            ),
        )

    /** The dbt comparison's four: the warehouse, the sources, the agent, the credential story. */
    val COMPARE_DBT: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Do I need a warehouse for this?",
                "No — and that is the fork with dbt. The join across Postgres, MySQL and SQLite happens in a " +
                    "staging database created for that one execution and destroyed with it; nothing is loaded " +
                    "anywhere first. Staging is docs/staging.md §1.",
                "docs/staging.md §1",
            ),
            FaqEntry(
                "Which sources can it read?",
                "The operational databases applications write to — the dialect catalog covers seven JDBC engines " +
                    "plus the lake's Parquet, CSV and Iceberg — each with its own adapter and type mapping, read " +
                    "where they live. The catalog is docs/datasources.md §4.1.",
                "docs/datasources.md §4.1",
            ),
            FaqEntry(
                "Is an agent the author here?",
                "That is the intended shape: introspect, create a template, create a pipeline, execute, read rows " +
                    "— the whole loop over MCP tools, with a person releasing what the agent drafts. dbt's " +
                    "authoring surface is a repository and a CLI, a different shape of collaboration. The tools " +
                    "are docs/mcp-server.md §6.1.",
                "docs/mcp-server.md §6.1",
            ),
            FaqEntry(
                "What is the credential story?",
                "Read-only datasources whose write-shaped uses are refused at save time and again at execution, " +
                    "one scoped revocable key per agent, and an audit log entry for every tool call — the checks " +
                    "an agent-facing warehouse pipeline needs. The read-only flag is docs/datasources.md §5.7.",
                "docs/datasources.md §5.7",
            ),
        )

    /** The federated-query page's four: where the join runs, the copies, the demo, the engine's future. */
    val FEDERATED_QUERY: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Where does the cross-engine join actually run?",
                "In a per-execution staging database: an isolated in-memory H2 instance created when the run " +
                    "starts, at jdbc:h2:mem:exec_{execution_id}, where each source's result is a table the join " +
                    "SQL reads. The lifecycle is docs/staging.md §3.1.",
                "docs/staging.md §3.1",
            ),
            FaqEntry(
                "Is anything copied between the engines?",
                "Nothing permanent: results are staged into the scratch database for the duration of the one " +
                    "execution, and the staging database is destroyed when the execution ends — by design, not " +
                    "as a limitation. The design is docs/staging.md §1.",
                "docs/staging.md §1",
            ),
            FaqEntry(
                "Can I try it without my own data?",
                "Yes — the NYC demo seeds the Postgres trips, the SQLite zone lookup and the pipeline that joins " +
                    "them (nyc/mobility/revenue_by_borough), and its SQL is printed verbatim on the page. The " +
                    "demo quickstart is docs/deployment.md Appendix B.",
                "docs/deployment.md Appendix B",
            ),
            FaqEntry(
                "Will staging always be in-memory H2?",
                "No — DuckDB as a staging engine is a planned roadmap item, and the staging abstraction exists so " +
                    "a larger analytical engine can take H2's place. The honest limit today: join a rollup to a " +
                    "lookup, not two billion-row fact tables. The roadmap is docs/ROADMAP.md §2.",
                "docs/ROADMAP.md §2",
            ),
        )

    /** The engine pages' four — one list over the six routes: the driver, the auth, the credential, the registration. */
    val ENGINES: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Does the JDBC driver ship in the published image?",
                "That is the page's driver-matrix table above: some engines' drivers are bundled, and the ones " +
                    "whose licenses we do not redistribute are user-supplied — registering a datasource whose " +
                    "driver is absent fails at save time with datasource.driver_not_loaded. The matrix is " +
                    "docs/deployment.md §3.5.",
                "docs/deployment.md §3.5",
            ),
            FaqEntry(
                "How does the agent authenticate to this engine?",
                "It does not reach the engine at all: the agent talks to the MCP endpoint with an API key in " +
                    "DP-API-Key or Authorization: Bearer dpk_, and the server connects to the database with the " +
                    "credentials it holds. Session cookies are rejected on /mcp. The authentication is " +
                    "docs/mcp-server.md §4.1.",
                "docs/mcp-server.md §4.1",
            ),
            FaqEntry(
                "Is the database credential exposed to the agent?",
                "No — the credential is AES-256-GCM encrypted at rest with the datasource name bound as " +
                    "additional authenticated data, and it is never returned through a tool or the API. The " +
                    "agent references the datasource by name. The encryption is docs/datasources.md §7.1.",
                "docs/datasources.md §7.1",
            ),
            FaqEntry(
                "Can the agent register the datasource itself?",
                "No — no credential travels through an agent, so a person registers the connection and the agent " +
                    "uses it by name. What the agent gets instead is the read side: schemas, tables, columns and " +
                    "preview rows over the introspection tools. The tool list is docs/mcp-server.md §6.1.",
                "docs/mcp-server.md §6.1",
            ),
        )
}
