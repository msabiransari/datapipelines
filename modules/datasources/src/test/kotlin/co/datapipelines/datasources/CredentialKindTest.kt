package co.datapipelines.datasources

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.collections.shouldNotContain as shouldNotContainElement

/**
 * The §3.4 credential-kind seam: the enum's field rules, the validator that enforces them, the
 * adapter that turns a kind into a driver slot, and the encryptor's indifference to what the
 * secret IS.
 *
 * The four are one contract and are tested together on purpose. Split across four files, the
 * question this suite exists to answer — "can a datasource be saved whose kind the pool build
 * will silently ignore?" — belongs to no file.
 */
class CredentialKindTest {
    private val validator = DatasourceValidator()

    @Test
    fun `every kind round-trips its wire value, and an unknown one is refused`() {
        assertAll(
            {
                CredentialKind.entries.map { it.wire } shouldContainExactly
                    listOf("password", "token", "private_key", "service_account_json", "none")
            },
            { CredentialKind.entries.forEach { CredentialKind.fromWire(it.wire) shouldBe it } },
            { CredentialKind.fromWireOrNull("kerberos").shouldBeNull() },
            // The DEFAULT is what a payload naming no kind means — the legacy pair.
            { CredentialKind.DEFAULT shouldBe CredentialKind.PASSWORD },
        )
    }

    @Test
    fun `the field rules are exactly the §3-4 table`() {
        assertAll(
            { CredentialKind.PASSWORD.usernameRule shouldBe FieldRule.REQUIRED },
            { CredentialKind.TOKEN.usernameRule shouldBe FieldRule.OPTIONAL },
            { CredentialKind.PRIVATE_KEY.usernameRule shouldBe FieldRule.FORBIDDEN },
            { CredentialKind.SERVICE_ACCOUNT_JSON.usernameRule shouldBe FieldRule.FORBIDDEN },
            { CredentialKind.NONE.usernameRule shouldBe FieldRule.FORBIDDEN },
            { CredentialKind.NONE.secretRule shouldBe FieldRule.FORBIDDEN },
            {
                CredentialKind.entries
                    .filter { it != CredentialKind.NONE }
                    .forEach { it.secretRule shouldBe FieldRule.REQUIRED }
            },
        )
    }

    @Test
    fun `password requires a username, and refuses a create with no secret`() {
        codes(Fixtures.h2(credentialKind = CredentialKind.PASSWORD, username = null), isCreate = true) shouldContainElement
            DatasourceErrorCodes.PROPERTIES_INVALID
        codes(Fixtures.h2(credentialKind = CredentialKind.PASSWORD, secret = null), isCreate = true) shouldContainElement
            DatasourceErrorCodes.PASSWORD_MISSING
    }

    @Test
    fun `an update may omit the secret - it keeps the stored one`() {
        validator
            .validate(Fixtures.h2(secret = null), isCreate = false)
            .errors
            .map { it.code } shouldNotContainElement DatasourceErrorCodes.PASSWORD_MISSING
    }

    @Test
    fun `none refuses BOTH a username and a secret - in either direction`() {
        val withUsername = validator.validate(Fixtures.h2(credentialKind = CredentialKind.NONE, secret = null), isCreate = true)
        withClue("Fixtures.h2 defaults username to 'sa', which kind none forbids") {
            withUsername.errors.single { it.field == "username" }.message shouldContain "must be absent"
        }

        val withSecret =
            validator.validate(
                Fixtures.h2(credentialKind = CredentialKind.NONE, username = null, secret = "anything"),
                isCreate = true,
            )
        withSecret.errors.single { it.field == "credential.secret" }.message shouldContain "must be absent"
    }

    @Test
    fun `none with neither field is valid - the shape the demo's file datasources now use`() {
        validator
            .validate(Fixtures.h2(credentialKind = CredentialKind.NONE, username = null, secret = null), isCreate = true)
            .valid shouldBe true
    }

    @Test
    fun `a kind no shipped adapter can use is refused, naming what the dialect accepts`() {
        // private_key and service_account_json are the REFERENCE targets' kinds: catalogued in
        // the contract, in no shipped adapter's set, and refused until a dialect declares it can
        // use them. Falsification: this test goes green only because the check exists — delete
        // the supportedCredentialKinds branch in the validator and the save succeeds.
        val error =
            validator
                .validate(
                    Fixtures.h2(credentialKind = CredentialKind.PRIVATE_KEY, username = null, secret = "-----BEGIN…"),
                    isCreate = true,
                ).errors
                .single { it.field == "credential.kind" }
        error.code shouldBe DatasourceErrorCodes.PROPERTIES_INVALID
        error.message shouldContain "cannot authenticate with credential kind 'private_key'"
        error.message shouldContain "none"
    }

    @Test
    fun `a token is accepted on a server dialect and refused on an embedded one`() {
        assertAll(
            {
                DialectAdapters.forDialect(co.datapipelines.typesystem.Dialect.POSTGRES).supportedCredentialKinds shouldContainElement
                    CredentialKind.TOKEN
            },
            {
                DialectAdapters.forDialect(co.datapipelines.typesystem.Dialect.SQLITE).supportedCredentialKinds shouldNotContainElement
                    CredentialKind.TOKEN
            },
            {
                DialectAdapters.forDialect(co.datapipelines.typesystem.Dialect.DUCKDB).supportedCredentialKinds shouldContainElement
                    CredentialKind.NONE
            },
        )
    }

    @Test
    fun `every adapter declares a non-empty, PASSWORD-inclusive credential set`() {
        // Total over the enum, like every other per-dialect map (§4.2): a new dialect that
        // forgot to think about credentials fails HERE rather than at a customer's pool build.
        // PASSWORD is the floor because every pre-V13 row carries it.
        DialectAdapters.all().forEach { adapter ->
            withClue("${adapter.dialect.wire} declares no credential kinds") {
                adapter.supportedCredentialKinds.isEmpty() shouldBe false
            }
            withClue("${adapter.dialect.wire} cannot serve its own pre-V13 rows") {
                adapter.supportedCredentialKinds shouldContainElement CredentialKind.PASSWORD
            }
        }
    }

    @Test
    fun `the pool build places a token in the password slot and NONE in neither slot`() {
        val h2 = DialectAdapters.forDialect(co.datapipelines.typesystem.Dialect.H2)
        val pg = DialectAdapters.forDialect(co.datapipelines.typesystem.Dialect.POSTGRES)

        val tokenConfig = pg.buildHikariConfig(Fixtures.postgres().copy(credentialKind = CredentialKind.TOKEN, secret = "pat-abc"))
        val noneConfig = h2.buildHikariConfig(Fixtures.h2(credentialKind = CredentialKind.NONE, username = null, secret = null))

        assertAll(
            { tokenConfig.password shouldBe "pat-abc" },
            { tokenConfig.username shouldBe "app" },
            // Neither field is set: an embedded file database has no login, and handing Hikari a
            // placeholder is the dummy password V13 exists to delete.
            { noneConfig.password.shouldBeNull() },
            { noneConfig.username.shouldBeNull() },
        )
    }

    @Test
    fun `the encryptor round-trips every kind's secret - the blob is kind-agnostic`() {
        val encryptor = testEncryptor()
        // One secret per kind, in the shape that kind actually carries. The encryptor is never
        // told which is which — that is the property: rotation and the key provider (068) do not
        // care what the plaintext MEANS, so a new kind needs no crypto work at all.
        // The fixtures carry each kind's SHAPE — multi-line, long, JSON-structured — without
        // carrying anything a secret scanner should match. `scripts/secret-scan.sh` (gitleaks)
        // flagged the first draft of this test twice: a real PEM private-key banner hits the
        // `private-key` rule, and a `dapi`-prefixed hex string hits `databricks-api-token`.
        // (This comment does not spell either of them, for the same reason.)
        // Allowlisting a test fixture would have been the weaker fix —
        // the scanner was right about the shape, and what this test needs is the LENGTH and the
        // line breaks, not a convincing forgery.
        val secrets =
            mapOf(
                CredentialKind.PASSWORD to "hunter2",
                CredentialKind.TOKEN to "fixture-token-0123456789abcdef0123456789",
                CredentialKind.PRIVATE_KEY to
                    "-----BEGIN FIXTURE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END FIXTURE KEY-----\n",
                CredentialKind.SERVICE_ACCOUNT_JSON to
                    """{"type":"service_account","project_id":"p",""" +
                    """"private_key":"-----BEGIN FIXTURE KEY-----\nabc\n-----END FIXTURE KEY-----\n"}""",
            )

        secrets.forEach { (kind, plaintext) ->
            val sealed = encryptor.encrypt(plaintext, "ds_${kind.wire}")
            withClue("${kind.wire}: the sealed blob still contains the plaintext") {
                String(sealed, Charsets.ISO_8859_1) shouldNotContain plaintext.take(16)
            }
            withClue("${kind.wire}: round trip") {
                encryptor.decrypt(sealed, "ds_${kind.wire}") shouldBe plaintext
            }
            withClue("${kind.wire}: the datasource NAME is the AAD, whatever the kind") {
                runCatching { encryptor.decrypt(sealed, "another_datasource") }.isSuccess shouldBe false
            }
        }
        // Multi-line and JSON secrets are not truncated or normalised anywhere on the path.
        secrets.values
            .map { it.length }
            .distinct()
            .size shouldNotBe 1
    }

    private fun codes(
        datasource: Datasource,
        isCreate: Boolean,
    ): List<String> = validator.validate(datasource, isCreate).errors.map { it.code }

    @Test
    fun `changing the kind on an update requires a secret - the stored one is not relabelled`() {
        // Found by reading the update path, not by a failing test. The repository writes
        // credential_kind unconditionally but KEEPS the stored ciphertext when the caller sent no
        // secret, so a PUT that changes only the kind would relabel the existing secret as
        // something it is not — and `none` -> `password` with no secret would leave the column
        // NULL against V13's chk_datasource_credential_present, turning a 400 the caller can act
        // on into a constraint violation they cannot.
        // POSTGRES, not H2: only a dialect whose driver ACCEPTS both kinds can exercise a kind
        // CHANGE at all — on H2 the token is refused by supportedCredentialKinds first, and the
        // field rules are never reached (which is the fail-closed order working, not a gap).
        val toToken = Fixtures.postgres().copy(credentialKind = CredentialKind.TOKEN, secret = null)

        assertAll(
            // Same kind, no secret: keeps the stored one, exactly as before.
            {
                validator
                    .validate(Fixtures.postgres().copy(secret = null), isCreate = false, storedKind = CredentialKind.PASSWORD)
                    .valid shouldBe true
            },
            // Kind CHANGED, no secret: refused, naming the field.
            {
                validator
                    .validate(toToken, isCreate = false, storedKind = CredentialKind.PASSWORD)
                    .errors
                    .single { it.field == "credential.secret" }
                    .code shouldBe DatasourceErrorCodes.PASSWORD_MISSING
            },
            // Kind changed WITH a secret: fine.
            {
                validator
                    .validate(toToken.copy(secret = "pat-abc"), isCreate = false, storedKind = CredentialKind.PASSWORD)
                    .valid shouldBe true
            },
            // …and moving TO `none` needs nothing, because `none` has nothing: the column is
            // cleared rather than kept.
            {
                validator
                    .validate(
                        Fixtures.h2(credentialKind = CredentialKind.NONE, username = null, secret = null),
                        isCreate = false,
                        storedKind = CredentialKind.PASSWORD,
                    ).valid shouldBe true
            },
        )
    }
}
