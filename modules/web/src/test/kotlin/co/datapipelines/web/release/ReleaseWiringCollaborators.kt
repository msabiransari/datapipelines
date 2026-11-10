package co.datapipelines.web.release

import co.datapipelines.pipeline.PipelineReleaseService
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.release.ReleasedVersionObserver
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.templates.TemplateReleaseService
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

/**
 * The collaborators `ReleaseConfiguration`'s graph needs, as beans a test context resolves: the wiring, not the
 * persistence, is under test (the integration suite runs the same beans against PostgreSQL).
 */
@Configuration
@Suppress("unused") // read by the context runner, never called
class ReleaseWiringCollaborators {
    @Bean
    fun jdbcTemplate(): NamedParameterJdbcTemplate = NamedParameterJdbcTemplate(mockk<javax.sql.DataSource>(relaxed = true))

    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()

    @Bean
    fun pipelineReleaseService(): PipelineReleaseService = mockk(relaxed = true)

    @Bean
    fun pipelineRepository(): PipelineRepository = mockk(relaxed = true)

    @Bean
    fun templateRepository(): TemplateRepository = mockk(relaxed = true)

    @Bean
    fun templateReleaseService(): TemplateReleaseService = mockk(relaxed = true)

    @Bean
    fun metadataTransactionManager(): PlatformTransactionManager = mockk(relaxed = true)

    @Bean
    fun releasedVersionObserver(): ReleasedVersionObserver = ReleasedVersionObserver.NONE
}
