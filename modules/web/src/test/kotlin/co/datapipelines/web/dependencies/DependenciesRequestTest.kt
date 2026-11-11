package co.datapipelines.web.dependencies

import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.Selector
import co.datapipelines.dependencies.wire.RefusalReason
import co.datapipelines.dependencies.wire.RequestField
import co.datapipelines.web.dependencies.DependenciesRequest.Parsed
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URLEncoder

/**
 * [DependenciesRequest.parse] over every row of record §11.1's request table a FAMILY ROUTE can produce (erratum
 * (8)(a): the family is the path, so `family_unknown` is not produced): each `details.reason` at least once, every
 * family's name through its NATIVE validator, the 200-character ceiling and the UTF-8 rule, in §A's order.
 */
class DependenciesRequestTest {
    private val transport = DependencyFamilyTransport.production(LENSES)

    private fun parse(
        family: FamilyKey,
        query: String?,
    ): Parsed = DependenciesRequest.parse(transport.row(family), query)

    private fun refused(
        family: FamilyKey,
        query: String?,
    ): Parsed.Refused = parse(family, query).shouldBeInstanceOf<Parsed.Refused>()

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    @Test
    fun `an artifact family accepts a native name at an exact version`() {
        val accepted = parse(FamilyKey.PIPELINE, "name=${enc("acme/daily/orders")}&version=3").shouldBeInstanceOf<Parsed.Accepted>()
        accepted.request shouldBe DependenciesRequest(FamilyKey.PIPELINE, "acme/daily/orders", Selector.ExactVersion(3))
    }

    @Test
    fun `schedule accepts a canonical id and datasource a name, both entity-selected`() {
        val id = "0b9c1f2e-3d4a-4b5c-8d6e-7f8091a2b3c4"
        parse(FamilyKey.SCHEDULE, "id=$id").shouldBeInstanceOf<Parsed.Accepted>().request.selector shouldBe Selector.Entity
        parse(FamilyKey.DATASOURCE, "name=warehouse_pg").shouldBeInstanceOf<Parsed.Accepted>().request.lookupKey shouldBe "warehouse_pg"
    }

    @Test
    fun `an API path is keyed by its stored spelling, the one api prefix stripped`() {
        parse(FamilyKey.API_ENDPOINT, "name=${enc("/api/nyc/v1/trips")}&version=1")
            .shouldBeInstanceOf<Parsed.Accepted>()
            .request.lookupKey shouldBe "/nyc/v1/trips"
    }

    @Test
    fun `a family field and unknown fields are ignored - the family is the route`() {
        parse(FamilyKey.TEMPLATE, "family=widget&name=acme/base&version=1&extra=${"x".repeat(500)}")
            .shouldBeInstanceOf<Parsed.Accepted>()
            .request.family shouldBe FamilyKey.TEMPLATE
    }

    @Test
    fun `selector_incompatible - name on schedule, id on a named family, name with id, version where forbidden`() {
        val id = "0b9c1f2e-3d4a-4b5c-8d6e-7f8091a2b3c4"
        refused(FamilyKey.SCHEDULE, "name=a/b") shouldBe Parsed.Refused(RequestField.NAME, RefusalReason.SELECTOR_INCOMPATIBLE)
        refused(FamilyKey.PIPELINE, "id=$id&version=1") shouldBe Parsed.Refused(RequestField.ID, RefusalReason.SELECTOR_INCOMPATIBLE)
        refused(FamilyKey.SCHEDULE, "name=a/b&id=$id") shouldBe Parsed.Refused(RequestField.NAME, RefusalReason.SELECTOR_INCOMPATIBLE)
        refused(FamilyKey.SCHEDULE, "id=$id&version=1") shouldBe Parsed.Refused(RequestField.VERSION, RefusalReason.SELECTOR_INCOMPATIBLE)
        refused(FamilyKey.DATASOURCE, "name=pg&version=1") shouldBe
            Parsed.Refused(RequestField.VERSION, RefusalReason.SELECTOR_INCOMPATIBLE)
    }

    @Test
    fun `a missing key field or a repeated field is a selector shape refusal`() {
        refused(FamilyKey.DATASOURCE, null) shouldBe Parsed.Refused(RequestField.NAME, RefusalReason.SELECTOR_INCOMPATIBLE)
        refused(FamilyKey.SCHEDULE, "") shouldBe Parsed.Refused(RequestField.ID, RefusalReason.SELECTOR_INCOMPATIBLE)
        refused(FamilyKey.PIPELINE, "name=a/b&name=a/c&version=1") shouldBe
            Parsed.Refused(RequestField.NAME, RefusalReason.SELECTOR_INCOMPATIBLE)
    }

    @Test
    fun `a version missing on an artifact family is version_invalid - absent where required`() {
        for (family in listOf(
            FamilyKey.PIPELINE,
            FamilyKey.TEMPLATE,
            FamilyKey.PARAMETER_SET,
            FamilyKey.VISUALIZATION,
            FamilyKey.DASHBOARD,
        )) {
            withClue(family.wire) {
                refused(family, "name=acme/x") shouldBe
                    Parsed.Refused(RequestField.VERSION, RefusalReason.VERSION_INVALID)
            }
        }
        refused(FamilyKey.API_ENDPOINT, "name=${enc("/nyc/v1/trips")}") shouldBe
            Parsed.Refused(RequestField.VERSION, RefusalReason.VERSION_INVALID)
    }

    @ParameterizedTest
    @ValueSource(strings = ["x", "current", "0", "007", "-1", "+1", "1.0", "2147483648", "99999999999", ""])
    fun `version_invalid - non-digit, current, zero, leading zero, sign, out of range, empty`(version: String) {
        refused(FamilyKey.DASHBOARD, "name=acme/board&version=${enc(version)}") shouldBe
            Parsed.Refused(RequestField.VERSION, RefusalReason.VERSION_INVALID)
    }

    @Test
    fun `the version bound is inclusive at 2147483647`() {
        parse(FamilyKey.DASHBOARD, "name=acme/board&version=2147483647")
            .shouldBeInstanceOf<Parsed.Accepted>()
            .request.version shouldBe Int.MAX_VALUE
    }

    @Test
    fun `name_invalid - each family through its native validator`() {
        val bad =
            mapOf(
                FamilyKey.PIPELINE to "single_segment",
                FamilyKey.TEMPLATE to "Upper/Case",
                FamilyKey.PARAMETER_SET to "a//b",
                FamilyKey.VISUALIZATION to "/leading/slash",
                FamilyKey.DASHBOARD to "",
                FamilyKey.API_ENDPOINT to "/two/segments",
                FamilyKey.DATASOURCE to "has/slash",
            )
        for ((family, name) in bad) {
            val version = if (transport.row(family).selector.versioned) "&version=1" else ""
            withClue(family.wire) {
                refused(family, "name=${enc(name)}$version") shouldBe Parsed.Refused(RequestField.NAME, RefusalReason.NAME_INVALID)
            }
        }
    }

    @Test
    fun `the datasource grammar is the registry's own - 63 characters pass, 64 refuse`() {
        parse(FamilyKey.DATASOURCE, "name=${"a".repeat(63)}").shouldBeInstanceOf<Parsed.Accepted>()
        refused(FamilyKey.DATASOURCE, "name=${"a".repeat(64)}") shouldBe Parsed.Refused(RequestField.NAME, RefusalReason.NAME_INVALID)
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-uuid", "0B9C1F2E-3D4A-4B5C-8D6E-7F8091A2B3C4", "0b9c1f2e3d4a4b5c8d6e7f8091a2b3c4", "1-1-1-1-1"])
    fun `id_invalid - anything but the canonical lower-case UUID form`(id: String) {
        refused(FamilyKey.SCHEDULE, "id=${enc(id)}") shouldBe Parsed.Refused(RequestField.ID, RefusalReason.ID_INVALID)
    }

    @Test
    fun `value_too_long - 201 characters, before any grammar`() {
        refused(FamilyKey.TEMPLATE, "name=${"a".repeat(200)}&version=1") shouldBe
            Parsed.Refused(RequestField.NAME, RefusalReason.NAME_INVALID)
        refused(FamilyKey.TEMPLATE, "name=${"a".repeat(201)}&version=1") shouldBe
            Parsed.Refused(RequestField.NAME, RefusalReason.VALUE_TOO_LONG)
        refused(FamilyKey.SCHEDULE, "id=${"a".repeat(201)}") shouldBe Parsed.Refused(RequestField.ID, RefusalReason.VALUE_TOO_LONG)
        refused(FamilyKey.PIPELINE, "name=acme/x&version=${"1".repeat(201)}") shouldBe
            Parsed.Refused(RequestField.VERSION, RefusalReason.VALUE_TOO_LONG)
    }

    @Test
    fun `the ceiling counts characters, not bytes - 200 two-byte characters are within it`() {
        refused(FamilyKey.DATASOURCE, "name=${enc("é".repeat(200))}") shouldBe Parsed.Refused(RequestField.NAME, RefusalReason.NAME_INVALID)
        refused(FamilyKey.DATASOURCE, "name=${enc("é".repeat(201))}") shouldBe
            Parsed.Refused(RequestField.NAME, RefusalReason.VALUE_TOO_LONG)
    }

    @ParameterizedTest
    @ValueSource(strings = ["%FF", "%C3", "%ED%A0%80", "%zz", "%4", "%-1", "%+1", "%\u0663\u0663"])
    fun `value_too_long - a non-UTF-8 byte sequence or a malformed escape`(raw: String) {
        refused(FamilyKey.DATASOURCE, "name=$raw") shouldBe Parsed.Refused(RequestField.NAME, RefusalReason.VALUE_TOO_LONG)
    }

    @Test
    fun `the order is length and encoding, then shape, then grammar, then version`() {
        // a too-long version beats an incompatible id
        refused(FamilyKey.PIPELINE, "id=x&version=${"9".repeat(201)}") shouldBe
            Parsed.Refused(RequestField.VERSION, RefusalReason.VALUE_TOO_LONG)
        // shape beats grammar: an id on a named family is refused before the bad name is examined
        refused(FamilyKey.PIPELINE, "name=BAD&id=x&version=1") shouldBe Parsed.Refused(RequestField.ID, RefusalReason.SELECTOR_INCOMPATIBLE)
        // grammar beats version
        refused(FamilyKey.PIPELINE, "name=BAD&version=current") shouldBe Parsed.Refused(RequestField.NAME, RefusalReason.NAME_INVALID)
    }

    @Test
    fun `a refusal carries the field and the reason only - never the value`() {
        val refusal = refused(FamilyKey.DATASOURCE, "name=${"secret".repeat(40)}")
        refusal.toString().contains("secret") shouldBe false
    }

    @Test
    fun `plus decodes to a space, so a name carrying one is refused by its grammar`() {
        refused(FamilyKey.DATASOURCE, "name=a+b") shouldBe Parsed.Refused(RequestField.NAME, RefusalReason.NAME_INVALID)
        DependenciesRequest.decode("a+b%2Fc") shouldBe "a b/c"
    }

    companion object {
        private object Admit : RootLens.Lensed {
            override fun admitsRequest(
                principal: AuthenticatedPrincipal,
                view: LensedView,
                request: DependenciesRequest,
            ) = true
        }

        val LENSES = DependencyFamilyTransport.ProductionRootLenses(Admit, Admit, Admit, Admit, Admit, Admit, Admit)
    }
}
