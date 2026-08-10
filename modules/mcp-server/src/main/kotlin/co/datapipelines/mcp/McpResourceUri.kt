package co.datapipelines.mcp

import java.util.UUID

/**
 * The `datapipelines://` resource URI scheme (mcp-server.md §7.1), parsed into a closed set.
 *
 * ```
 * datapipelines://pipelines/{id}                      datapipelines://templates/{id}
 * datapipelines://pipelines/{id}/versions/{version}   datapipelines://templates/{id}/versions/{v}
 * datapipelines://pipelines/{id}/parameters           datapipelines://datasources
 * datapipelines://executions/{execution_id}           datapipelines://datasources/{name}
 * datapipelines://executions/{execution_id}/events
 * ```
 *
 * A sealed hierarchy rather than string matching at the read site: `when` over it is exhaustive,
 * so a URI form added to §7.1 later is a compile error in the reader, not a silent 404.
 */
sealed interface McpResourceUri {
    /** The canonical URI string this instance was parsed from. */
    val uri: String

    data class PipelineLatest(
        override val uri: String,
        val id: UUID,
    ) : McpResourceUri

    data class PipelineVersion(
        override val uri: String,
        val id: UUID,
        val version: Int,
    ) : McpResourceUri

    data class PipelineParameters(
        override val uri: String,
        val id: UUID,
    ) : McpResourceUri

    data class TemplateLatest(
        override val uri: String,
        val id: String,
    ) : McpResourceUri

    data class TemplateVersion(
        override val uri: String,
        val id: String,
        val version: Int,
    ) : McpResourceUri

    data class DatasourceList(
        override val uri: String,
    ) : McpResourceUri

    data class DatasourceByName(
        override val uri: String,
        val name: String,
    ) : McpResourceUri

    data class Execution(
        override val uri: String,
        val executionId: UUID,
    ) : McpResourceUri

    data class ExecutionEvents(
        override val uri: String,
        val executionId: UUID,
    ) : McpResourceUri

    companion object {
        const val SCHEME: String = "datapipelines://"

        const val PIPELINES: String = "pipelines"
        const val TEMPLATES: String = "templates"
        const val DATASOURCES: String = "datasources"
        const val EXECUTIONS: String = "executions"

        /** `datapipelines://{kind}/{id}` — the shortest addressable form. */
        private const val ENTITY_SEGMENTS = 2

        /** `datapipelines://{kind}/{id}/{sub}` — `…/parameters`, `…/events`. */
        private const val SUB_RESOURCE_SEGMENTS = 3

        /** `datapipelines://{kind}/{id}/versions/{version}`. */
        private const val VERSION_SEGMENTS = 4

        /** Index of the sub-resource keyword, and of the version literal after it. */
        private const val SUB_INDEX = 2
        private const val VERSION_INDEX = 3
        private const val ID_INDEX = 1

        private const val VERSIONS = "versions"
        private const val PARAMETERS = "parameters"
        private const val EVENTS = "events"

        fun pipeline(id: UUID): String = "$SCHEME$PIPELINES/$id"

        fun template(id: String): String = "$SCHEME$TEMPLATES/$id"

        fun datasource(name: String): String = "$SCHEME$DATASOURCES/$name"

        fun datasources(): String = "$SCHEME$DATASOURCES"

        fun execution(id: UUID): String = "$SCHEME$EXECUTIONS/$id"

        /** Parses [uri], or returns null when it is not a §7.1 form. */
        @Suppress("ReturnCount")
        fun parse(uri: String): McpResourceUri? {
            if (!uri.startsWith(SCHEME)) return null
            val segments = uri.removePrefix(SCHEME).split('/').filter { it.isNotEmpty() }
            return when (segments.firstOrNull()) {
                PIPELINES -> pipelineUri(uri, segments)
                TEMPLATES -> templateUri(uri, segments)
                DATASOURCES -> datasourceUri(uri, segments)
                EXECUTIONS -> executionUri(uri, segments)
                else -> null
            }
        }

        private fun pipelineUri(
            uri: String,
            segments: List<String>,
        ): McpResourceUri? {
            val id = segments.getOrNull(ID_INDEX)?.let { it.toUuidOrNull() } ?: return null
            return when {
                segments.size == ENTITY_SEGMENTS -> {
                    PipelineLatest(uri, id)
                }

                segments.size == SUB_RESOURCE_SEGMENTS && segments[SUB_INDEX] == PARAMETERS -> {
                    PipelineParameters(uri, id)
                }

                segments.size == VERSION_SEGMENTS && segments[SUB_INDEX] == VERSIONS -> {
                    segments[VERSION_INDEX].toIntOrNull()?.let { PipelineVersion(uri, id, it) }
                }

                else -> {
                    null
                }
            }
        }

        private fun templateUri(
            uri: String,
            segments: List<String>,
        ): McpResourceUri? {
            val id = segments.getOrNull(ID_INDEX)?.takeIf { it.isNotBlank() } ?: return null
            return when {
                segments.size == ENTITY_SEGMENTS -> {
                    TemplateLatest(uri, id)
                }

                segments.size == VERSION_SEGMENTS && segments[SUB_INDEX] == VERSIONS -> {
                    segments[VERSION_INDEX].toIntOrNull()?.let { TemplateVersion(uri, id, it) }
                }

                else -> {
                    null
                }
            }
        }

        private fun datasourceUri(
            uri: String,
            segments: List<String>,
        ): McpResourceUri? =
            when (segments.size) {
                1 -> DatasourceList(uri)
                ENTITY_SEGMENTS -> DatasourceByName(uri, segments[ID_INDEX])
                else -> null
            }

        private fun executionUri(
            uri: String,
            segments: List<String>,
        ): McpResourceUri? {
            val id = segments.getOrNull(ID_INDEX)?.let { it.toUuidOrNull() } ?: return null
            return when {
                segments.size == ENTITY_SEGMENTS -> Execution(uri, id)
                segments.size == SUB_RESOURCE_SEGMENTS && segments[SUB_INDEX] == EVENTS -> ExecutionEvents(uri, id)
                else -> null
            }
        }

        private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
    }
}
