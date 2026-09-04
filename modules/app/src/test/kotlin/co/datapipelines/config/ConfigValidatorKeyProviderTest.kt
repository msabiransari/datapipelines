package co.datapipelines.config

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * §7's credential key-provider rules (configuration.md §3.20, datasources.md §7.1) as data —
 * the 068 half of [ConfigValidatorTest], in its own class because that one is already at
 * detekt's `LargeClass` ceiling.
 *
 * Same discipline as its sibling: [ConfigValidator.validate] is pure, so every branch is
 * exercised by feeding a [ConfigSnapshot] built from the shared [ConfigSnapshots] baseline and
 * breaking exactly one rule.
 */
class ConfigValidatorKeyProviderTest {
    private fun secret(bytes: Int): String = ConfigSnapshots.secret(bytes)

    private fun validSnapshot() = ConfigSnapshots.valid()

    @Test
    fun `the shipped default — key-provider unset — is env and passes with one key`() {
        // The compatibility promise of the round: a deployment that predates the seam sets
        // nothing new and is still valid. validSnapshot() carries no provider at all.
        ConfigValidator.validate(validSnapshot()).violations.shouldBeEmpty()
        ConfigValidator.validate(validSnapshot().copy(dbKeyProvider = "env")).violations.shouldBeEmpty()
        ConfigValidator.validate(validSnapshot().copy(dbKeyProvider = "  ")).violations.shouldBeEmpty()
    }

    @Test
    fun `an unknown key provider is refused at boot, naming what this build ships`() {
        val report = ConfigValidator.validate(validSnapshot().copy(dbKeyProvider = "aws-kms"))

        report.violations shouldHaveSize 1
        report.violations.first() shouldContain "datapipelines.db.key-provider is 'aws-kms'"
        report.violations.first() shouldContain "docs/key-providers.md"
    }

    @Test
    fun `an unknown provider does not also report the env provider's rules`() {
        // Reporting "your env keys are wrong" to an operator who asked for aws-kms is noise
        // they cannot act on: the unknown name short-circuits.
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(dbKeyProvider = "vault", dbEncryptionKeys = mapOf("2" to secret(31))),
            )

        report.violations shouldHaveSize 1
    }

    @Test
    fun `an env rotation key of the wrong length is refused, naming the version`() {
        val report = ConfigValidator.validate(validSnapshot().copy(dbEncryptionKeys = mapOf("2" to secret(31))))

        report.violations shouldHaveSize 1
        report.violations.first() shouldContain "datapipelines.db.encryption-keys.2"
        report.violations.first() shouldContain "31 bytes"
    }

    @Test
    fun `a well-formed rotation pair passes`() {
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(dbEncryptionKeys = mapOf("2" to secret(32)), dbEncryptionKeyCurrent = "2"),
            )

        report.violations.shouldBeEmpty()
    }

    @Test
    fun `a malformed rotation key version is a named violation, not a binder crash`() {
        val report =
            ConfigValidator.validate(validSnapshot().copy(dbEncryptionKeys = mapOf("two" to secret(32))))

        report.violations shouldHaveSize 1
        report.violations.first() shouldContain "datapipelines.db.encryption-keys.two"
    }

    @Test
    fun `redeclaring version 1 in the rotation map is refused — one version has one spelling`() {
        val report = ConfigValidator.validate(validSnapshot().copy(dbEncryptionKeys = mapOf("1" to secret(32))))

        report.violations shouldHaveSize 1
        report.violations.first() shouldContain "datapipelines.db.encryption-key"
    }

    @Test
    fun `a version outside the single-byte range is refused`() {
        val report = ConfigValidator.validate(validSnapshot().copy(dbEncryptionKeys = mapOf("256" to secret(32))))

        report.violations shouldHaveSize 1
        report.violations.first() shouldContain "outside 1..255"
    }

    @Test
    fun `current naming an unconfigured version is refused, listing what IS configured`() {
        val report = ConfigValidator.validate(validSnapshot().copy(dbEncryptionKeyCurrent = "3"))

        report.violations shouldHaveSize 1
        report.violations.first() shouldContain "configured: [1]"
    }

    @Test
    fun `a non-numeric current version is a named violation`() {
        val report = ConfigValidator.validate(validSnapshot().copy(dbEncryptionKeyCurrent = "latest"))

        report.violations shouldHaveSize 1
        report.violations.first() shouldContain "datapipelines.db.encryption-key-current is 'latest'"
    }

    @Test
    fun `no key-provider violation message can carry key material`() {
        // configuration.md's bearer-secret rule: the §7 report is LOGGED. Messages name the
        // property and the defect, never the value.
        val badKey = secret(31)
        val report =
            ConfigValidator.validate(
                validSnapshot().copy(dbEncryptionKeys = mapOf("2" to badKey), dbEncryptionKeyCurrent = "9"),
            )

        report.violations.forEach { it.contains(badKey) shouldBe false }
    }

    @Test
    fun `the snapshot's toString never renders a configured key`() {
        val badKey = secret(32)

        val rendered = validSnapshot().copy(dbEncryptionKeys = mapOf("2" to badKey)).toString()

        rendered.contains(badKey) shouldBe false
        rendered shouldContain "dbEncryptionKeys=<redacted, versions=[2]>"
    }
}
