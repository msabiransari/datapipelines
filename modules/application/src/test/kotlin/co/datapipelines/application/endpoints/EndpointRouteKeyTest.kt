package co.datapipelines.application.endpoints

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * #1037 — the API family's graph key names method AND path, with GET as the bare legacy path. The key is the contract
 * between release planning, the dependency tree, restore and the lens, so one spelling per route is pinned here.
 */
class EndpointRouteKeyTest {
    private val path = "/shop/v1/orders"

    @Test
    fun `GET is the bare path - byte-identical to every pre-method key`() {
        EndpointRouteKey.of(EndpointMethod.GET, path) shouldBe path
    }

    @Test
    fun `a mutation method precedes the path with one space`() {
        EndpointRouteKey.of(EndpointMethod.POST, path) shouldBe "POST $path"
        EndpointRouteKey.of(EndpointMethod.PUT, path) shouldBe "PUT $path"
        EndpointRouteKey.of(EndpointMethod.DELETE, path) shouldBe "DELETE $path"
    }

    @Test
    fun `the four methods at one path are four distinct keys`() {
        EndpointMethod.ALL
            .map { EndpointRouteKey.of(it, path) }
            .toSet()
            .size shouldBe EndpointMethod.ALL.size
    }

    @Test
    fun `parse inverts of for every method`() {
        for (method in EndpointMethod.ALL) {
            EndpointRouteKey.parse(EndpointRouteKey.of(method, path)) shouldBe EndpointRouteKey.Route(method, path)
        }
    }

    @Test
    fun `a bare value is GET and a GET-prefixed or unknown method word is no key`() {
        EndpointRouteKey.parse(path) shouldBe EndpointRouteKey.Route(EndpointMethod.GET, path)
        EndpointRouteKey.parse("GET $path") shouldBe null
        EndpointRouteKey.parse("PATCH $path") shouldBe null
        EndpointRouteKey.parse("post $path") shouldBe null
    }
}
