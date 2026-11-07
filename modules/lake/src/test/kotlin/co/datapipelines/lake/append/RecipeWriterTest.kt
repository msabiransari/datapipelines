package co.datapipelines.lake.append

import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeS3Clients
import co.datapipelines.lake.delivery.DeliveryKey
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RecipeWriterTest {
    @Test
    fun `expired deadline makes no PUT and a retryable 500 still makes exactly one HTTP attempt`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            requests.incrementAndGet()
            val body = "<Error><Code>InternalError</Code><Message>secret-server-detail-799</Message></Error>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/xml")
            exchange.sendResponseHeaders(SERVER_ERROR, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val storage =
                LakeProperties.Storage(
                    bucket = "recipes",
                    region = "us-east-1",
                    endpoint = "http://127.0.0.1:${server.address.port}",
                    pathStyle = true,
                    accessKey = UUID.randomUUID().toString(),
                    secretKey = UUID.randomUUID().toString(),
                )
            LakeS3Clients.build(storage).use { client ->
                val writer = RecipeWriter(client, storage, RecipeFixtures.document)
                val publication = RecipeFixtures.publication()
                shouldThrow<DatapipelinesException> { writer.write("s3://recipes/table", publication, Instant.EPOCH) }
                    .code shouldBe PipelineErrorCodes.Lake.APPEND_RECIPE_WRITE_FAILED
                requests.get() shouldBe 0
                val failure =
                    shouldThrow<DatapipelinesException> {
                        writer.write("s3://recipes/table", publication, Instant.now().plusSeconds(60))
                    }
                failure.code shouldBe PipelineErrorCodes.Lake.APPEND_RECIPE_WRITE_FAILED
                failure.message.orEmpty() shouldNotContain "secret-server-detail-799"
                failure.cause shouldBe null
                requests.get() shouldBe 1
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `recipe keys stay under the table location and one attempt cannot name another bucket`() {
        val operation = UUID.randomUUID()
        RecipeWriter.recipeKey("s3://recipes/lake/sales/table/", "recipes", DeliveryKey("feed", "batch"), operation) shouldBe
            "lake/sales/table/dp/recipes/feed/batch/$operation.json"
        shouldThrow<IllegalArgumentException> {
            RecipeWriter.recipeKey("s3://another/table", "recipes", DeliveryKey("feed", "batch"), operation)
        }
        shouldThrow<IllegalArgumentException> {
            RecipeWriter.recipeKey("s3://recipes/", "recipes", DeliveryKey("feed", "batch"), operation)
        }
    }

    private companion object {
        const val SERVER_ERROR = 500
    }
}
