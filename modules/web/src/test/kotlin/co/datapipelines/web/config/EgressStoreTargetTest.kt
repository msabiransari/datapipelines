package co.datapipelines.web.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * #862 — the ONE store-authority derivation is pinned form by form against probe A's wire observations (lane 862
 * evidence `probe-a-output.txt`): each case here is what DuckDB httpfs v1.5.5 put on the wire, measured 2026-11-08, for that
 * configuration, so the boot check's HEAD, the datasource's secret and the proxy's allowlist all derive the SAME
 * authority and never an invented alias of it. The blank-endpoint cases pin the regional AWS form (case E: the
 * engine resolves `s3.<region>.amazonaws.com`, default region us-east-1 — not the legacy global endpoint today's
 * boot check probed); the port cases pin CONNECT's verbatim port (case D) and plain http's bare default-port host
 * (case C).
 */
class EgressStoreTargetTest {
    @Test
    fun `blank endpoint and blank region - the regional AWS vhost authority with the default region, as case E observed`() {
        val target = EgressStoreTarget.derive(endpoint = "", region = "", bucket = "dp-lake", pathStyle = false)
        withClue("domains") { target.domains shouldBe listOf("dp-lake.s3.us-east-1.amazonaws.com:443") }
        withClue("probe") { target.probeUri shouldBe URI("https://dp-lake.s3.us-east-1.amazonaws.com/") }
        withClue("endpoint") { target.duckdbEndpoint shouldBe "https://s3.us-east-1.amazonaws.com" }
        withClue("region") { target.region shouldBe "us-east-1" }
    }

    @Test
    fun `a declared region travels into the derived AWS form`() {
        val target = EgressStoreTarget.derive(endpoint = "", region = " eu-west-1 ", bucket = "dp-lake", pathStyle = false)
        target.domains shouldBe listOf("dp-lake.s3.eu-west-1.amazonaws.com:443")
        target.duckdbEndpoint shouldBe "https://s3.eu-west-1.amazonaws.com"
        target.region shouldBe "eu-west-1"
    }

    @Test
    fun `path style over the derived AWS endpoint - the bare regional host with the bucket in the path`() {
        val target = EgressStoreTarget.derive(endpoint = "", region = "", bucket = "dp-lake", pathStyle = true)
        target.domains shouldBe listOf("s3.us-east-1.amazonaws.com:443")
        target.probeUri shouldBe URI("https://s3.us-east-1.amazonaws.com/dp-lake")
        target.duckdbEndpoint shouldBe "https://s3.us-east-1.amazonaws.com"
    }

    @Test
    fun `an explicit http endpoint with a port - the port travels verbatim, as cases A and B observed`() {
        val path = EgressStoreTarget.derive(endpoint = "http://minio:9000", region = "", bucket = "dp-lake", pathStyle = true)
        path.domains shouldBe listOf("minio:9000")
        path.probeUri shouldBe URI("http://minio:9000/dp-lake")
        path.duckdbEndpoint shouldBe "http://minio:9000"
        val vhost = EgressStoreTarget.derive(endpoint = " http://minio:9000/ ", region = "", bucket = "dp-lake", pathStyle = false)
        vhost.domains shouldBe listOf("dp-lake.minio:9000")
        vhost.probeUri shouldBe URI("http://dp-lake.minio:9000/")
    }

    @Test
    fun `plain http on its default port is a BARE host on the wire, as case C observed`() {
        val target = EgressStoreTarget.derive(endpoint = "http://minio", region = "", bucket = "dp-lake", pathStyle = false)
        target.domains shouldBe listOf("dp-lake.minio")
        target.probeUri shouldBe URI("http://dp-lake.minio/")
        target.duckdbEndpoint shouldBe "http://minio"
    }

    /**
     * #862 review R5, probe A2: a declared default port is the engine's no-port wire form — `host:80` reaches the wire
     * as `GET http://host/` (case L; Envoy routes on that URL authority), `host:443` as `CONNECT host:443` (case M).
     * Every consumer (allowlist, boot probe, engine endpoint) takes the one form.
     */
    @Test
    fun `a declared default port is the no-port form - the engine drops 80 from the URL and 443 is the CONNECT default`() {
        val http = EgressStoreTarget.derive(endpoint = "http://minio:80", region = "", bucket = "dp-lake", pathStyle = false)
        http.domains shouldBe listOf("dp-lake.minio")
        http.probeUri shouldBe URI("http://dp-lake.minio/")
        http.duckdbEndpoint shouldBe "http://minio"
        val https = EgressStoreTarget.derive(endpoint = "https://minio.example:443", region = "", bucket = "dp-lake", pathStyle = true)
        https.domains shouldBe listOf("minio.example:443")
        https.probeUri shouldBe URI("https://minio.example/dp-lake")
        https.duckdbEndpoint shouldBe "https://minio.example"
        withClue("the other scheme's default port is an ordinary declared port") {
            EgressStoreTarget.derive(endpoint = "http://minio:443", region = "", bucket = "b", pathStyle = true).domains shouldBe
                listOf("minio:443")
            EgressStoreTarget.derive(endpoint = "https://minio:80", region = "", bucket = "b", pathStyle = true).domains shouldBe
                listOf("minio:80")
        }
    }

    @Test
    fun `https always carries its port on the CONNECT line, as case D observed`() {
        val target = EgressStoreTarget.derive(endpoint = "https://minio.example", region = "", bucket = "dp-lake", pathStyle = false)
        target.domains shouldBe listOf("dp-lake.minio.example:443")
        target.probeUri shouldBe URI("https://dp-lake.minio.example/")
        target.duckdbEndpoint shouldBe "https://minio.example"
    }

    @Test
    fun `an explicit https port replaces the default rather than aliasing it`() {
        val target = EgressStoreTarget.derive(endpoint = "https://minio.example:9443", region = "", bucket = "dp-lake", pathStyle = true)
        target.domains shouldBe listOf("minio.example:9443")
        target.probeUri shouldBe URI("https://minio.example:9443/dp-lake")
    }

    @Test
    fun `an uppercase scheme binds like its lowercase spelling`() {
        val target = EgressStoreTarget.derive(endpoint = "HTTP://MinIO:9000", region = "", bucket = "dp-lake", pathStyle = true)
        target.duckdbEndpoint shouldBe "http://MinIO:9000"
        target.domains shouldBe listOf("MinIO:9000")
    }

    @Test
    fun `a dotted bucket stays one exact vhost form`() {
        val target = EgressStoreTarget.derive(endpoint = "http://minio:9000", region = "", bucket = "my.buckets", pathStyle = false)
        target.domains shouldBe listOf("my.buckets.minio:9000")
        // Probe A2 (cases H, I, K): the engine keeps a dotted bucket in the vhost authority, also over TLS and on the
        // derived AWS host — no switch to path style.
        EgressStoreTarget
            .derive(endpoint = "https://s3fake.test", region = "", bucket = "dp.lake.b862", pathStyle = false)
            .domains shouldBe listOf("dp.lake.b862.s3fake.test:443")
        EgressStoreTarget
            .derive(endpoint = "", region = "eu-west-1", bucket = "dp.lake.b862", pathStyle = false)
            .domains shouldBe listOf("dp.lake.b862.s3.eu-west-1.amazonaws.com:443")
    }

    @Test
    fun `an endpoint without a scheme is refused, not guessed`() {
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "minio:9000", region = "", bucket = "dp-lake", pathStyle = true)
        }.message shouldBe "the endpoint 'minio:9000' carries no http:// or https:// scheme (§3.36)"
    }

    @Test
    fun `an IPv6 literal endpoint is refused - a hostname must be declared`() {
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "http://[::1]:9000", region = "", bucket = "dp-lake", pathStyle = true)
        }.message shouldContain "IPv6 literal endpoint is refused"
    }

    @Test
    fun `a malformed or out-of-range port is refused`() {
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "http://minio:notaport", region = "", bucket = "dp-lake", pathStyle = true)
        }.message shouldContain "not a port"
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "http://minio:70000", region = "", bucket = "dp-lake", pathStyle = true)
        }.message shouldContain "outside 1..65535"
    }

    @Test
    fun `an empty bucket is refused - no authority can be derived from it`() {
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "http://minio:9000", region = "", bucket = "  ", pathStyle = true)
        }.message shouldContain "the bucket is empty"
    }

    /**
     * Review R3: a blank endpoint takes the DNS suffix of the region's AWS partition, as the SDK's partition metadata
     * (`software.amazon.awssdk:regions:2.46.7`, `endpoints.json`) names it — `amazonaws.com.cn` for aws-cn — and the
     * same derived host reaches the engine's secret, the boot probe and the proxy allowlist.
     */
    @Test
    fun `a blank endpoint takes the region's partition DNS suffix - aws, aws-cn and aws-us-gov`() {
        val china = EgressStoreTarget.derive(endpoint = "", region = "cn-north-1", bucket = "dp-lake", pathStyle = false)
        china.domains shouldBe listOf("dp-lake.s3.cn-north-1.amazonaws.com.cn:443")
        china.duckdbEndpoint shouldBe "https://s3.cn-north-1.amazonaws.com.cn"
        china.probeUri shouldBe URI("https://dp-lake.s3.cn-north-1.amazonaws.com.cn/")
        EgressStoreTarget.derive(endpoint = "", region = "cn-northwest-1", bucket = "b", pathStyle = true).domains shouldBe
            listOf("s3.cn-northwest-1.amazonaws.com.cn:443")
        EgressStoreTarget.derive(endpoint = "", region = "us-gov-west-1", bucket = "b", pathStyle = true).domains shouldBe
            listOf("s3.us-gov-west-1.amazonaws.com:443")
        EgressStoreTarget.derive(endpoint = "", region = "ap-southeast-7", bucket = "b", pathStyle = true).domains shouldBe
            listOf("s3.ap-southeast-7.amazonaws.com:443")
        EgressStoreTarget.derive(endpoint = "", region = "mx-central-1", bucket = "b", pathStyle = true).domains shouldBe
            listOf("s3.mx-central-1.amazonaws.com:443")
    }

    @Test
    fun `the partition table is the SDK's - one partition per region, the regexes disjoint`() {
        EgressStoreTarget.PARTITIONS.map { it.name to it.dnsSuffix } shouldBe
            listOf("aws" to "amazonaws.com", "aws-cn" to "amazonaws.com.cn", "aws-us-gov" to "amazonaws.com")
        for (region in listOf("us-east-1", "eu-central-2", "cn-north-1", "us-gov-east-1", "il-central-1")) {
            withClue(region) { EgressStoreTarget.PARTITIONS.count { it.regionRegex.matches(region) } shouldBe 1 }
        }
    }

    @Test
    fun `a region outside the supported partitions is refused with a blank endpoint - never a guessed host`() {
        for (region in listOf("us-iso-east-1", "us-isob-east-1", "eu-isoe-west-1", "eusc-de-east-1", "aws-global", "US-EAST-1", "moon-1")) {
            withClue(region) {
                shouldThrow<IllegalStateException> {
                    EgressStoreTarget.derive(endpoint = "", region = region, bucket = "dp-lake", pathStyle = false)
                }.message shouldContain "declare datapipelines.lake.storage.endpoint explicitly"
            }
        }
    }

    @Test
    fun `a declared endpoint keeps any region for signing - the partition table governs the blank endpoint only`() {
        val target = EgressStoreTarget.derive(endpoint = "http://minio:9000", region = "us-iso-east-1", bucket = "b", pathStyle = true)
        target.domains shouldBe listOf("minio:9000")
        target.region shouldBe "us-iso-east-1"
    }

    @Test
    fun `a leading-zero port, userinfo or a path in the endpoint is refused - no second spelling of the authority`() {
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "http://minio:09000", region = "", bucket = "b", pathStyle = true)
        }.message shouldContain "leading zero"
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "http://user:pw@minio:9000", region = "", bucket = "b", pathStyle = true)
        }
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "http://user@minio", region = "", bucket = "b", pathStyle = true)
        }
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "http://minio:9000/prefix", region = "", bucket = "b", pathStyle = true)
        }
        shouldThrow<IllegalStateException> {
            EgressStoreTarget.derive(endpoint = "http://minio/prefix", region = "", bucket = "b", pathStyle = true)
        }
    }
}
