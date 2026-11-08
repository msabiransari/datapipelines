package co.datapipelines.web.visualizations

import co.datapipelines.visualization.ChartSchemas
import co.datapipelines.web.api.ApiExceptionHandler
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletResponse
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

/**
 * `GET /schemas/chart/1/{name}.schema.json` (#860 A.6): the served bytes ARE the validator's resource — one copy —
 * under the schema media type, and only the v1 files resolve.
 */
class ChartSchemaControllerTest {
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(ChartSchemaController())
            .setControllerAdvice(ApiExceptionHandler())
            .build()

    @Test
    fun `the route carries ONE public cache header even when the security chain already wrote its no-store`() {
        // The stand-in writes what the running instance's security chain put on this route before the handler ran
        // (measured 2026-11-08): a handler that ADDS its header leaves two, and a cache that combines them obeys
        // no-store.
        val securityWritten =
            Filter { request, response, chain ->
                (response as HttpServletResponse)
                    .setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate")
                chain.doFilter(request, response)
            }
        val chained =
            MockMvcBuilders
                .standaloneSetup(ChartSchemaController())
                .setControllerAdvice(ApiExceptionHandler())
                .addFilter<StandaloneMockMvcBuilder>(securityWritten)
                .build()
        val response =
            chained
                .perform(get("/schemas/chart/1/${ChartSchemas.BAR}"))
                .andExpect(status().isOk)
                .andReturn()
                .response
        response.getHeaders(HttpHeaders.CACHE_CONTROL) shouldBe listOf("max-age=900, public")
    }

    @Test
    fun `every v1 file is served byte for byte as the classpath resource the validator loads, under its own id`() {
        ChartSchemas.FILES.size shouldBe 2
        for (file in ChartSchemas.FILES) {
            val path = "/schemas/chart/1/$file"
            val served =
                mvc
                    .perform(get(path))
                    .andExpect(status().isOk)
                    .andExpect(content().contentType("application/schema+json;charset=UTF-8"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                    .andReturn()
                    .response
                    .contentAsByteArray
            // Read off the classpath directly, not through ChartSchemas.bytes: the oracle is not the code under test.
            val resource =
                checkNotNull(javaClass.classLoader.getResourceAsStream(ChartSchemas.RESOURCE_DIR + file)) { file }
                    .use { it.readBytes() }
            (served.contentEquals(resource)) shouldBe true
            // The URL answers the `$id` the validator's registry keys the same bytes by.
            ObjectMapper().readTree(served).get("\$id").asText() shouldBe "https://datapipelines.co$path"
        }
    }

    @Test
    fun `a name outside the v1 files is the 404 envelope, so nothing else on the classpath is reachable`() {
        for (name in listOf("plotly", "chart-support", "skeleton.schema", "BAR")) {
            mvc
                .perform(get("/schemas/chart/1/$name.schema.json"))
                .andExpect(status().isNotFound)
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.error.code").value("pipeline.execution.not_found"))
                .andExpect(jsonPath("$.error.details.reason").value("chart_schema_not_found"))
                .andExpect(jsonPath("$.error.message").value(containsString(ChartSchemas.BAR)))
        }
    }
}
