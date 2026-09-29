package co.datapipelines.web.config

import co.datapipelines.executor.ResultConfig
import co.datapipelines.pipeline.OrgContext
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `EngineConfiguration.executorConfig` copies every `ExecutorProperties` field into `ExecutorConfig` by name —
 * nothing else does, and no drift test looks at the bean (the spec-drift test pins the doc against the
 * PROPERTIES). A field the bean forgets is bound from the environment and then never read: #311's
 * `lifecycle-write-timeout-seconds` reached the statement layer through `executionRepository` but not the
 * emitter's caller-side wait, which reads `ExecutorConfig` (the 306 merge's security pass). This test builds
 * the bean with non-default values and reads them back.
 */
class ExecutorConfigMappingTest {
    @Test
    fun `the bean carries the lifecycle write bound the operator set - the emitter's layer reads the config, not the properties`() {
        val config =
            EngineConfiguration().executorConfig(
                executor = ExecutorProperties(lifecycleWriteTimeoutSeconds = 33, heartbeatSeconds = 7),
                staging = StagingH2Properties(),
                sse = SseProperties(),
                pipelines = PipelineProperties(),
                executions = ExecutionsProperties(),
                result = ResultConfig(),
                org = OrgContext.DEFAULTS,
            )
        withClue("a field the bean does not copy stays at the data-class default whatever the operator set") {
            config.lifecycleWriteTimeoutSeconds shouldBe 33
        }
        config.heartbeatSeconds shouldBe 7
    }
}
