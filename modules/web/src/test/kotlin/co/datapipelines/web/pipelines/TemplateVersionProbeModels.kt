package co.datapipelines.web.pipelines

import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineSettings
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.typesystem.Dialect

/** Bodies for the real import and restore witnesses; no lifecycle behavior is stubbed here. */
internal object TemplateVersionProbeModels {
    fun draft(
        id: String,
        body: String = "SELECT 1",
    ) = TemplateDraft(
        id = id,
        // The wire carries an explicit type: a null one is `type_invalid` on import (creation resolves it, import does not).
        type = TemplateType.SQL,
        dialect = Dialect.POSTGRES,
        displayName = "Probe",
        description = "Template version probe fixture.",
        body = body,
    )

    fun pipeline(
        name: String,
        template: TemplateRef,
    ) = Pipeline(
        schemaVersion = Pipeline.SUPPORTED_SCHEMA_VERSION,
        name = name,
        displayName = name,
        description = "",
        settings = PipelineSettings(),
        parameters = emptyMap(),
        nodes =
            listOf(
                Node(
                    id = "q",
                    description = "q",
                    type = NodeType.DQL,
                    source = "tempdb",
                    template = template,
                    output = NodeOutput.Caller,
                    dependsOn = emptyList(),
                ),
            ),
    )
}
