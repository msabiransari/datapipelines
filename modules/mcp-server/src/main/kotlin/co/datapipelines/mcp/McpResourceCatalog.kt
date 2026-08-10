package co.datapipelines.mcp

import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.executor.ExecutionRepository
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.templates.TemplateRepository
import io.modelcontextprotocol.spec.McpSchema
import java.time.Clock
import java.time.Duration

/** One page of `resources/list` (mcp-server.md §7.3). */
data class McpResourcePage(
    val resources: List<McpSchema.Resource>,
    val nextCursor: String?,
)

/**
 * `resources/list` (mcp-server.md §7.3) — the discovery half of the resource surface.
 *
 * §7.3 is normative on four points, all implemented here:
 *
 * - **Page size is fixed at 100** and is not client-controllable.
 * - The **cursor is opaque** ([McpResourceCursor]); an undecodable one is `-32602`.
 * - `nextCursor` is absent on the last page — its presence is the only "there is more" signal.
 * - **Enumeration order** is pipelines, then templates, then datasources, then executions.
 *   Entities created mid-run may be missed: the listing is a discovery aid, not a snapshot.
 *
 * **Scope filtering** (§7.3, §13 checklist): the listing shows only what the calling key may read.
 * Every scope in the §7.5 hierarchy implies `read`, so pipelines, templates and datasources are
 * visible to any authenticated key; executions additionally apply the ownership rule
 * ([visibleTo]), so two agents see different resource sets on the same server.
 *
 * **Executions are windowed to the last 24 hours.** Older ones stay readable by direct URI
 * ([McpResourceReader]) — they are simply not enumerated, because an unbounded execution history
 * makes `resources/list` useless on a busy instance.
 *
 * ## Reported deviation
 *
 * §7.3 says each kind is enumerated "by id". Executions are enumerated **newest first**, because
 * `ExecutionRepository` indexes and orders them by `started_at` and there is no id-ordered query;
 * the order is equally stable within a paging run and is the useful one for a 24-hour window.
 */
class McpResourceCatalog(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val datasources: DatasourceRegistry,
    private val executions: ExecutionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * The page addressed by [cursor], or the first page when it is null.
     *
     * @throws io.modelcontextprotocol.spec.McpError `-32602` for a cursor this server did not issue.
     */
    fun list(
        ctx: McpToolContext,
        cursor: String?,
    ): McpResourcePage {
        requireReadScope(ctx)
        val start = cursor?.let { McpResourceCursor.decode(it, KINDS) } ?: McpResourceCursor.first(KINDS)
        val scan = RequestScan()
        val page = mutableListOf<McpSchema.Resource>()
        var kindIndex = KINDS.indexOf(start.kind)
        var offset = start.offset

        while (kindIndex < KINDS.size) {
            val kind = KINDS[kindIndex]
            val slice = slice(kind, offset, PAGE_SIZE - page.size, ctx, scan)
            page += slice
            offset += slice.size
            if (page.size >= PAGE_SIZE) {
                return McpResourcePage(page, nextPosition(kind, offset, kindIndex, ctx, scan)?.encode())
            }
            kindIndex++
            offset = 0
        }
        return McpResourcePage(page, null)
    }

    /** Where the next page starts: the rest of this kind, else the first kind that still has rows. */
    private fun nextPosition(
        kind: String,
        consumed: Int,
        kindIndex: Int,
        ctx: McpToolContext,
        scan: RequestScan,
    ): McpResourceCursor? {
        if (hasMore(kind, consumed, ctx, scan)) return McpResourceCursor(kind, consumed)
        for (index in kindIndex + 1 until KINDS.size) {
            if (hasMore(KINDS[index], 0, ctx, scan)) return McpResourceCursor(KINDS[index], 0)
        }
        return null
    }

    private fun hasMore(
        kind: String,
        offset: Int,
        ctx: McpToolContext,
        scan: RequestScan,
    ): Boolean = slice(kind, offset, 1, ctx, scan).isNotEmpty()

    private fun slice(
        kind: String,
        offset: Int,
        limit: Int,
        ctx: McpToolContext,
        scan: RequestScan,
    ): List<McpSchema.Resource> =
        when (kind) {
            McpResourceUri.PIPELINES -> pipelineDescriptors(offset, limit, scan)
            McpResourceUri.TEMPLATES -> templateDescriptors(offset, limit)
            McpResourceUri.DATASOURCES -> datasourceDescriptors(offset, limit, scan)
            McpResourceUri.EXECUTIONS -> executionDescriptors(offset, limit, ctx)
            else -> emptyList()
        }

    private fun pipelineDescriptors(
        offset: Int,
        limit: Int,
        scan: RequestScan,
    ): List<McpSchema.Resource> =
        scan.pipelines
            .drop(offset)
            .take(limit)
            .map {
                descriptor(
                    uri = McpResourceUri.pipeline(it.id),
                    name = it.name,
                    description = "${it.displayName} — pipeline body, version ${it.currentVersion}.",
                    mimeType = MIME_JSON,
                )
            }

    private fun templateDescriptors(
        offset: Int,
        limit: Int,
    ): List<McpSchema.Resource> =
        templates
            .list(offset = offset, limit = limit)
            .map {
                descriptor(
                    uri = McpResourceUri.template(it.id),
                    name = it.id,
                    description = "${it.displayName} — ${it.dialect.wire} template, version ${it.version}.",
                    mimeType = MIME_FREEMARKER_SQL,
                )
            }

    /** The collection URI `datapipelines://datasources` is the first entry of its own kind (§7.1). */
    private fun datasourceDescriptors(
        offset: Int,
        limit: Int,
        scan: RequestScan,
    ): List<McpSchema.Resource> {
        val collection =
            descriptor(
                uri = McpResourceUri.datasources(),
                name = "datasources",
                description = "Every registered datasource, without credentials.",
                mimeType = MIME_JSON,
            )
        val items =
            scan.datasources.map {
                descriptor(
                    uri = McpResourceUri.datasource(it.name),
                    name = it.name,
                    description = "${it.displayName} — ${it.dialect.wire} connection metadata, no credentials.",
                    mimeType = MIME_JSON,
                )
            }
        return (listOf(collection) + items).drop(offset).take(limit)
    }

    private fun executionDescriptors(
        offset: Int,
        limit: Int,
        ctx: McpToolContext,
    ): List<McpSchema.Resource> {
        val since = clock.instant().minus(EXECUTION_WINDOW)
        return executions
            .findByUser(ctx.principal.userId, limit = limit, offset = offset)
            .filter { it.startedAt.isAfter(since) && it.visibleTo(ctx) }
            .map {
                descriptor(
                    uri = McpResourceUri.execution(it.executionId),
                    name = it.executionId.toString(),
                    description = "Execution of pipeline ${it.pipelineId} (${it.status.name}).",
                    mimeType = MIME_JSON,
                )
            }
    }

    /**
     * One `resources/list` call's memo of the unbounded reads (mcp-sec-5, partial mitigation).
     *
     * A page walks its kinds and then probes `hasMore`, so an un-memoized `findAll()` ran two or
     * three times per page. Memoizing bounds a call to **one** scan of each.
     *
     * Residual, tracked as a cross-module follow-up (`web`'s listing shares it): `findAll()` still
     * has no SQL `LIMIT`/`OFFSET`, so the page is cut in memory after the whole table is read. The
     * root fix is pushing limit/offset — and `pipelines_list`'s datasource predicate, which
     * deserializes each candidate body — down into `PipelineRepository`; that changes
     * `pipeline-contract` and is not this module's to make.
     */
    private inner class RequestScan {
        val pipelines by lazy { this@McpResourceCatalog.pipelines.findAll().sortedBy { it.id } }

        val datasources by lazy { this@McpResourceCatalog.datasources.list().sortedBy { it.name } }
    }

    private fun descriptor(
        uri: String,
        name: String,
        description: String,
        mimeType: String,
    ): McpSchema.Resource =
        McpSchema.Resource
            .builder(uri, name)
            .description(description)
            .mimeType(mimeType)
            .build()

    companion object {
        /** §7.3 — fixed, not client-controllable. */
        const val PAGE_SIZE: Int = 100

        /** §7.3 — only executions from the last 24 hours are enumerated. */
        val EXECUTION_WINDOW: Duration = Duration.ofHours(24)

        const val MIME_JSON: String = "application/json"

        /** §7.2.2 — a template resource is its Freemarker body, not a JSON wrapper. */
        const val MIME_FREEMARKER_SQL: String = "text/x-freemarker-sql"

        /** §7.3 — the enumeration order, and the cursor's `kind` alphabet. */
        val KINDS: List<String> =
            listOf(
                McpResourceUri.PIPELINES,
                McpResourceUri.TEMPLATES,
                McpResourceUri.DATASOURCES,
                McpResourceUri.EXECUTIONS,
            )
    }
}
