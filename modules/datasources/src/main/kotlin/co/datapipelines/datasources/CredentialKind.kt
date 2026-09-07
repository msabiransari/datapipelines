package co.datapipelines.datasources

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * WHAT a datasource's stored credential IS (datasources.md §3.4, enums.md §5A).
 *
 * Before this existed the contract had exactly one shape — a `username` plus a `password` — and
 * that shape was written into the column (`password_encrypted NOT NULL`), the wire, the bootstrap
 * file and the pool build. It is the shape of "another two-level JDBC RDBMS with a login", and it
 * is wrong for every warehouse and lake connector the roadmap names: Snowflake authenticates a
 * service user with a **programmatic access token** or an RSA **private key**, BigQuery with a
 * **service-account JSON** blob, Databricks with a PAT (`UID=token`, the token in `PWD`) or OAuth
 * M2M, and a lake read over S3 with **no stored credential at all** — the instance role.
 *
 * So the kind is declared, and the ADAPTER decides where the secret goes. A caller says "this is
 * a token"; it never says "put it in the password slot" — that is a fact about a pinned driver,
 * and it belongs beside the driver's other facts ([DialectAdapter.applyCredential]).
 *
 * ## The one rule the kinds exist to make checkable
 *
 * **A credential travels ONLY in `credential`** — never in `jdbc_url`, never in `properties.*`.
 * §5.6 already refuses both carriers; the kinds are what let the dedicated carrier hold something
 * that is not a password, so nobody has a reason to smuggle one.
 *
 * ## Per-kind field rules (datasources.md §3.4, enforced by [DatasourceValidator])
 *
 * | Kind | `username` | `secret` |
 * |---|---|---|
 * | [PASSWORD] | REQUIRED | required on create |
 * | [TOKEN] | optional | required on create |
 * | [PRIVATE_KEY] | must be absent | required on create (PEM; passphrase via the dialect's own field) |
 * | [SERVICE_ACCOUNT_JSON] | must be absent | required on create (one JSON blob) |
 * | [NONE] | must be absent | must be absent |
 *
 * Storage is kind-agnostic: whatever the secret is, it is one blob under the 068 versioned
 * AES-GCM envelope with the datasource name as AAD (§7.1). Rotation (§7.3) does not care either.
 */
enum class CredentialKind(
    @JsonValue val wire: String,
) {
    /** A database login: `username` + password. Today's only shape, and still the default. */
    PASSWORD("password"),

    /**
     * A bearer token or personal access token the adapter places where the pinned driver wants
     * it — Snowflake and Azure/AWS-IAM Postgres take it in the password slot; Databricks wants
     * the literal `UID=token` with the PAT in `PWD`. The caller states the kind, not the slot.
     */
    TOKEN("token"),

    /** A PEM private key (Snowflake key-pair auth, BigQuery P12/PEM). No username. */
    PRIVATE_KEY("private_key"),

    /** One service-account JSON document (BigQuery, Google Cloud generally). No username. */
    SERVICE_ACCOUNT_JSON("service_account_json"),

    /**
     * There is nothing to store: an IAM role or instance profile supplies it (a lake read over
     * S3 through the credential chain), the OS does (integrated auth), or the datasource is an
     * embedded FILE database with no authentication at all — which is what the demo's SQLite and
     * DuckDB entries always were, previously wearing a dummy password because the contract had
     * no way to say "none" (datasources.md §8A.1).
     */
    NONE("none"),
    ;

    /** §3.4: a `username` is required for [PASSWORD], allowed for [TOKEN], forbidden otherwise. */
    val usernameRule: FieldRule
        get() =
            when (this) {
                PASSWORD -> FieldRule.REQUIRED
                TOKEN -> FieldRule.OPTIONAL
                PRIVATE_KEY, SERVICE_ACCOUNT_JSON, NONE -> FieldRule.FORBIDDEN
            }

    /** §3.4: every kind but [NONE] carries a secret; [NONE] must not. */
    val secretRule: FieldRule
        get() = if (this == NONE) FieldRule.FORBIDDEN else FieldRule.REQUIRED

    companion object {
        /** The default for a payload that names no kind — the legacy `username`/`password` pair. */
        val DEFAULT = PASSWORD

        @JsonCreator
        @JvmStatic
        fun fromWire(value: String): CredentialKind =
            entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown CredentialKind: $value")

        /** [fromWire] without the throw — for validators that report a bad value rather than crash. */
        fun fromWireOrNull(value: String): CredentialKind? = entries.firstOrNull { it.wire == value }
    }
}

/** Whether a credential field must be present, may be present, or must be absent for a kind. */
enum class FieldRule {
    REQUIRED,
    OPTIONAL,
    FORBIDDEN,
}
