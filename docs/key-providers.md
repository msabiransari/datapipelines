# Key Providers — implementing a KMS-backed credential key source

**Status:** v1.0 (normative)
**Owner:** datapipelines.co core
**Depends on:** [Datasources](datasources.md) · [Configuration](configuration.md) · [Deployment](deployment.md) · [Module Structure](module-structure.md)
**Last updated:** 2026-09-04

---

This document is written for someone — a person or an agent — who has this repository, a customer's cloud account, and **no context from the conversation that produced this seam**. It is a procedure, not a design discussion. Follow it in order.

Datasource passwords are encrypted at rest with AES-256-GCM ([Datasources §7.1](datasources.md#71-encryption-at-rest)). *Where the AES keys come from* is the only part a customer's environment changes, and it is behind one interface. Implementing AWS KMS, GCP KMS, Azure Key Vault or Vault transit means writing that interface — not touching the crypto.

---

## 1. What a provider is, and is not

A key provider **is**:

- a source of 32-byte AES data keys, each stamped with a version in `1..255`;
- selected by one config value, `datapipelines.db.key-provider` ([Configuration §3.20](configuration.md#320-credential-key-provider));
- validated at **boot** — a provider that cannot reach its backing service stops the application from starting.

A key provider **is not**:

- a place passwords pass through. A provider never sees a plaintext or encrypted credential; it hands out keys and knows nothing about what they encrypt.
- a place key material may be logged, put in an exception message, put in an audit `details` map, or rendered by a `toString()`. The bearer-secret rule in [Configuration §3.20](configuration.md#320-credential-key-provider) applies without exception. Failure messages name the CONFIG PROPERTY and the defect, never the value.
- a fallback chain. Exactly one provider is active per deployment. There is no "try KMS, else the env key" — that is how a deployment silently starts writing rows nobody can decrypt later.

---

## 2. The contract

```kotlin
package co.datapipelines.datasources.crypto

/** A 32-byte AES key and the version the ciphertext will carry. */
class DataKey(val version: Int, val bytes: ByteArray)   // version 1..255

/**
 * Supplies data keys for CredentialEncryptor. Implementations own HOW a key reaches the
 * process (env, AWS KMS, GCP KMS, Azure Key Vault, Vault transit); none of them ever sees a
 * password. current() is used for every new encryption; byVersion() for decryption of a row
 * written under an earlier key. Unknown version -> null (the encryptor turns that into
 * CredentialDecryptionException — the "wrong deployment / lost key" tamper signal).
 */
interface KeyProvider {
    val name: String                     // the config value that selects it: "env", "aws-kms", …
    fun current(): DataKey
    fun byVersion(version: Int): DataKey?
}
```

Three invariants. `KeyProviderContractTest` pins the first two mechanically; the third is yours to honour in the constructor.

1. **`current()` is stable for the life of the process.** The encryptor reads it ONCE, in its constructor, and caches it. A provider that returns a different key on the second call has already produced rows nobody can decrypt.
2. **`byVersion(v)` must keep working for every `v` that `current()` ever returned on this deployment**, for as long as any stored row carries that version byte. [Datasources §7.3](datasources.md#73-key-rotation) has the one SQL query that tells an operator when a version is finally unused.
3. **Both must fail at BOOT, not at first decrypt.** Do the unwrap calls in the constructor or a `@PostConstruct`, and let the exception propagate: the provider is built at startup wiring, so a throw stops the context and a misconfigured pod never serves traffic. A provider that lazily contacts KMS on the first password write turns a configuration error into a production incident hours later.

Version `0` is reserved as "not a version" — an all-zero or truncated page must not be able to forge one. Never return it, and never answer `byVersion(0)` with a key.

---

## 3. Envelope design for KMS-backed providers

AWS, GCP, Azure and Vault all fit one shape, and it is the shape to use: the vendor holds a **key-encryption key** (the customer's CMK); this application holds **wrapped data keys** in its own metadata database and unwraps them once at boot.

The provider owns one table:

```sql
CREATE TABLE credential_data_keys (
    version     INT PRIMARY KEY,
    provider    TEXT        NOT NULL,
    wrapped_key BYTEA       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**The migration for that table ships WITH the first provider, not now.** This round ships the contract only; adding an unused table would be schema nobody runs against.

Lifecycle:

- **First boot, no rows** — call the vendor's *generate data key* operation and store the WRAPPED key as version 1:
  - AWS: `GenerateDataKey` against the customer's CMK ARN. It returns both the plaintext key and the ciphertext blob; keep the plaintext in memory, store the blob.
  - GCP: generate 32 bytes locally with a `SecureRandom`, then `Encrypt` them with the CryptoKey; store the ciphertext.
  - Azure: generate 32 bytes locally, then `WrapKey` with the Key Vault key; store the wrapped result.
  - Vault: `transit/datakey/plaintext/<key>`, which returns plaintext plus ciphertext; store the ciphertext.
- **Every boot** — read every row and unwrap each once (`Decrypt` / `UnwrapKey` / `transit/decrypt`). Keep the plaintext keys **in memory only**. This is the call that must fail loudly at boot (invariant 3).
- **Rotation** — insert version N+1 the same way as first boot and make it current. Everything else is [Datasources §7.3](datasources.md#73-key-rotation)'s lazy flow: old rows keep decrypting under the versions still present in the table.

The plaintext data key never goes back to the database, never goes to a log, and never leaves the process.

**IAM must grant the pod exactly unwrap, plus generate on first boot.** For AWS that is one statement on one key:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DatapipelinesCredentialDataKeys",
      "Effect": "Allow",
      "Action": ["kms:Decrypt", "kms:GenerateDataKey"],
      "Resource": "arn:aws:kms:<region>:<account-id>:key/<key-id>"
    }
  ]
}
```

No `kms:*`, no `"Resource": "*"`, and no `kms:Encrypt` — `GenerateDataKey` is the only creation path this design uses. Once the deployment has stopped creating versions, `kms:GenerateDataKey` can be dropped too, leaving a decrypt-only pod.

---

## 4. Step list for the implementing agent

**This list is a floor, not a ceiling.** End your handback with *"what I had to do beyond this list"*.

1. **The provider class** — `modules/datasources/src/main/kotlin/co/datapipelines/datasources/crypto/providers/<Vendor>KeyProvider.kt`, implementing `KeyProvider`. Its `name` is the config value that selects it (`aws-kms`, `gcp-kms`, `azure-key-vault`, `vault-transit`).
2. **Register it by name.** `KeyProviders` (same package) is a `name -> factory` map — the by-key registry shape this tree already uses for `DialectAdapters.forDialect` (`modules/datasources/…/DialectAdapters.kt`), which is the file to copy. Add one entry:
   ```kotlin
   mapOf(
       EnvKeyProvider.NAME to { config -> EnvKeyProvider.from(config) },
       AwsKmsKeyProvider.NAME to { config -> AwsKmsKeyProvider.from(config) },
   )
   ```
   Read your settings through the `KeyProviderConfig` port passed to the factory — `datasources` may depend on `typesystem` alone ([Module Structure §5.4](module-structure.md#54-datasources)), so it never sees Spring's `Environment`. The aggregation layer supplies the implementation (`SpringKeyProviderConfig` in `DomainConfiguration.kt`), exactly as it supplies `DatasourceReferences`.
3. **A config block** under `datapipelines.db`, named after the provider (an `aws-kms` block, a `vault-transit` block), documented as a table in [Configuration §3.20](configuration.md#320-credential-key-provider) with env var, default and description for every key. Do not add the block to `application.yml` unless it has a shipped default — a declared empty value binds OVER an environment-supplied one.
4. **A `ConfigValidator` branch.** `modules/app/…/config/ConfigValidator.kt` has one `checkKeyProvider` function: add your provider's name to `KNOWN_KEY_PROVIDERS` and a branch validating your own required settings. Bump `CHECK_COUNT` only if you add a new `check*` function (`ConfigValidatorCheckCountTest` counts them). Add the rule to [Configuration §7](configuration.md#7-config-validation).
5. **The vendor SDK, pinned.** One exact version in `gradle/libs.versions.toml` — verified current at the time you add it, never a version recalled or copied out of this document — plus the dependency-audit run. No version ranges, no `+`, no SNAPSHOT.
6. **Run `KeyProviderContractTest` against it.** Subclass it and implement `newProvider()`; that is the same suite `EnvKeyProvider` passes, and it is what makes "this provider satisfies the contract" a fact rather than a claim:
   ```kotlin
   class AwsKmsKeyProviderContractTest : KeyProviderContractTest() {
       override fun newProvider(): KeyProvider = AwsKmsKeyProvider(fakeKmsClient(), …)
   }
   ```
   Use a fake/local KMS (LocalStack, an in-memory client) — the contract suite must not need a cloud account to run in CI.
7. **A paragraph in [Deployment](deployment.md)** on the IAM/policy side: which principal the pod runs as, which single key it is granted on, and what an operator sees when the grant is wrong (startup refuses, naming the provider).
8. **A row in this document's provider table** (§7) and a mention in [Datasources §7.1.1](datasources.md#711-the-key-provider-seam).
9. **A migration for `credential_data_keys`** (§3), shipped in the same commit as the provider that reads it.

---

## 5. AWS KMS — the worked recipe

**Dependency.** `software.amazon.awssdk:kms`. **Verify the current version at implementation time; do not copy a number from this document** — a version pinned out of prose is a version nobody checked. Add it to `gradle/libs.versions.toml` with the rest, and run the dependency audit.

**Config keys** — an `aws-kms` block under `datapipelines.db`:

```yaml
datapipelines:
  db:
    key-provider: aws-kms
    aws-kms:
      # Required. The customer CMK the data keys are wrapped under. A full ARN, not an
      # alias, so the account and region are explicit in the config an operator reads.
      key-arn: ${DATAPIPELINES_DB_AWS_KMS_KEY_ARN}
      # Optional. Unset = the SDK's default region chain (which resolves the ARN's own
      # region in the ordinary case). Present for the split-region deployment.
      region: ${DATAPIPELINES_DB_AWS_KMS_REGION:}
```

Both keys go in the [Configuration §3.20](configuration.md#320-credential-key-provider) table when the provider ships.

Credentials themselves are **not** config keys: the SDK's default provider chain resolves the pod's role (IRSA, instance profile, or a local profile in development). Never add an access-key/secret-key pair to this configuration.

**The two SDK calls.**

- First boot, no rows in `credential_data_keys`:
  `GenerateDataKey(KeyId = key-arn, KeySpec = AES_256)` → `plaintext` (32 bytes, keep in memory) and `ciphertextBlob` (store as `wrapped_key`, version 1).
- Every boot, per row: `Decrypt(CiphertextBlob = wrapped_key, KeyId = key-arn)` → the plaintext key for that version.

**IAM** — §3's statement, verbatim: `kms:Decrypt` and `kms:GenerateDataKey` on that one key ARN, nothing wider.

**Boot-failure behaviour.** An unreachable KMS endpoint, a denied `Decrypt`, a CMK in the wrong account, a `key-arn` that is unset or malformed: all of them throw out of the provider's construction, which stops the Spring context, which stops the pod. The log line names the offending property (`key-arn`) and the vendor's error class — never the key material and never the wrapped blob.

**Contract test.** `class AwsKmsKeyProviderContractTest : KeyProviderContractTest()` with a fake KMS client, per §4 step 6.

**The other three, on the same skeleton.** Only the "generate" and "unwrap" calls differ:

- **GCP KMS.** *Generate*: there is no GenerateDataKey — create 32 bytes with `SecureRandom` and call `Encrypt` on the CryptoKey (`projects/…/cryptoKeys/…`) to wrap them. *Unwrap*: `Decrypt`. IAM: `roles/cloudkms.cryptoKeyEncrypterDecrypter` on that one key. Config: a `gcp-kms` block with `crypto-key`.
- **Azure Key Vault.** *Generate*: 32 local bytes, then `WrapKey` (`RSA-OAEP-256` or `A256KW`, depending on the key type). *Unwrap*: `UnwrapKey`. Access policy / RBAC: wrap+unwrap on one key. Config: an `azure-key-vault` block with `key-id`.
- **HashiCorp Vault transit.** *Generate*: `POST transit/datakey/plaintext/<key>` — it returns plaintext and ciphertext together, like AWS. *Unwrap*: `POST transit/decrypt/<key>`. Policy: `update` on those two paths only. Config: a `vault-transit` block with `address`, `key-name` and the token/auth-method settings the deployment uses.

---

## 6. Threat model, stated plainly

**Under every provider, a database dump plus the unwrap capability equals every stored password.** That is not a weakness of a particular provider; it is what "the application can connect to the customer's databases unattended" means. Do not read a KMS integration as making stored credentials safe against an attacker who has the process.

What a provider changes is exactly two things:

1. **Where the unwrap capability lives.** With `env`, the key is in the process environment: anyone who can read the pod's environment, its config store, or a memory dump has it directly. With a KMS provider, the process holds only *permission to ask*, and the key material is in the vendor's HSM.
2. **Whether that capability is auditable and revocable at the vendor.** CloudTrail / Cloud Audit Logs / Key Vault logs record every unwrap, and revoking the grant makes every wrapped key useless immediately — without touching this application, and without knowing which pods hold what.

What no provider changes:

- The process that legitimately decrypts a password holds it in plaintext, in the Hikari pool, for the pool's lifetime ([Datasources §7.4](datasources.md#74-decryption-points-and-audit-log)). No provider protects a password from the process that is supposed to use it.
- A DB dump alone, without unwrap access, yields nothing under any provider — including `env`, where the "unwrap access" is simply the environment variable.
- Compromise of the pod's identity is compromise of the credentials. KMS narrows the blast radius in time (revoke the grant) and makes it visible (the audit trail); it does not prevent it.

The honest summary for a customer: **`env` protects a stolen database backup; a KMS provider additionally gives you revocation and an audit trail for the key itself.** Neither protects a password from a compromised application host.

---

## 7. Providers

| `datapipelines.db.key-provider` | Status | Config | Notes |
|---|---|---|---|
| `env` | Shipped | [Configuration §3.20](configuration.md#320-credential-key-provider) | Keys from configuration, base64. Version 1 is `datapipelines.db.encryption-key`; `encryption-keys` adds rotation versions |
| `aws-kms` | Not shipped — §5 is the recipe | an `aws-kms` block | Envelope keys wrapped by a customer CMK |
| `gcp-kms` | Not shipped — §5 | a `gcp-kms` block | Same skeleton; `Encrypt` wraps a locally generated key |
| `azure-key-vault` | Not shipped — §5 | an `azure-key-vault` block | Same skeleton; `WrapKey` / `UnwrapKey` |
| `vault-transit` | Not shipped — §5 | a `vault-transit` block | Same skeleton; `datakey/plaintext` and `decrypt` |

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-04 | v1.0 | 068 key-provider seam | Born. The `KeyProvider` contract, the `env` provider, the envelope design for KMS-backed providers, the step list and the AWS worked recipe, and the threat model |
