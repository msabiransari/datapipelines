package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.Pipeline

/**
 * What a pinned pipeline version accepts as a request body, as authoring surfaces show it (#1040; published-mutation-apis
 * §3 items 2–3): the body MODE the pipeline declares and the SCRIPT transformers that read it.
 *
 * Derived from the exact pinned version's body — never stored on the API version — so the console, the MCP tools and the
 * promotion batch cannot disagree with what the serve path admits: the same `request_body` declaration the serve path's
 * body gate reads, and the same [Pipeline.requestBodyReaders] the binder delivers the body to. A pipeline with no
 * declaration takes no body ([Mode.NONE]); a mutation route over it is a bodyless operation.
 */
data class EndpointBodyContract(
    val mode: Mode,
    /** The SCRIPT nodes that bind `@request_body`, in pipeline order — the transformers no caller can skip. */
    val readers: List<Reader>,
) {
    /** The declared body mode; [wire] is the one spelling every surface uses. */
    enum class Mode(
        val wire: String,
    ) {
        NONE("none"),
        OPTIONAL("optional"),
        REQUIRED("required"),
        ;

        companion object {
            /** The mode for [wire], or null for anything else (an older or newer peer's value is not guessed at). */
            fun ofWire(wire: String?): Mode? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** One transformer that reads the body: its node id and the exact template version it runs. */
    data class Reader(
        val nodeId: String,
        val template: String,
    )

    /**
     * The one wire spelling every surface answers (`request_body` on REST and MCP, #1040): `mode` and the readers as
     * `node_id` + `template` (`id@version`).
     */
    fun wire(): Map<String, Any?> =
        mapOf(
            "mode" to mode.wire,
            "readers" to readers.map { mapOf("node_id" to it.nodeId, "template" to it.template) },
        )

    companion object {
        /** The contract of [pipeline] (one exact version's body). */
        fun of(pipeline: Pipeline): EndpointBodyContract {
            val declaration = pipeline.requestBody
            val mode =
                when {
                    declaration == null -> Mode.NONE
                    declaration.required -> Mode.REQUIRED
                    else -> Mode.OPTIONAL
                }
            val readers = pipeline.requestBodyReaders().map { Reader(it.id, "${it.template.id}@${it.template.version}") }
            return EndpointBodyContract(mode, readers)
        }
    }
}
