package co.datapipelines.web.ui

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * UI configuration — enables [UiProperties] property binding.
 * Thymeleaf is auto-configured by Spring Boot; the `error/` templates
 * in `templates/error/` are picked up by [org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration].
 */
@Configuration
@EnableConfigurationProperties(UiProperties::class)
class UiConfig
