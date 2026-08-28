package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect

/**
 * The **refusal sets** of datasources.md §5.6 — the one bounded exception to the §2 principle-8
 * passthrough model.
 *
 * A key is refused when the *pinned* driver treats its value as a class name to instantiate, a
 * file path to read or write, connect-time SQL, or a TLS-verification switch. Refusal applies to
 * **both carriers identically**: whatever is rejected under `properties.jdbc.*`
 * ([DatasourceErrorCodes.PROPERTIES_INVALID]) is also rejected when smuggled into the
 * `jdbc_url` query/property segment ([DatasourceErrorCodes.JDBC_URL_MALFORMED]).
 *
 * ## Where these strings come from
 *
 * Every name below was read out of the **pinned driver jar** in the Gradle cache (`javap
 * -constants` over the driver's own property catalog), never recalled — the versions are the ones
 * in `gradle/libs.versions.toml`:
 *
 * | Dialect | Jar inspected | Catalog class |
 * |---|---|---|
 * | POSTGRES | `postgresql-42.7.13.jar` | `org.postgresql.PGProperty` |
 * | MSSQL | `mssql-jdbc-12.10.2.jre11.jar` | `SQLServerDriver{String,Boolean,Object}Property` |
 * | MYSQL | `mysql-connector-j-9.7.0.jar` | `com.mysql.cj.conf.PropertyKey` |
 * | DUCKDB | `duckdb_jdbc-1.5.5.1.jar` | `org.duckdb.DuckDBDriver` |
 * | SQLITE | `sqlite-jdbc-3.49.1.0.jar` | `org.sqlite.SQLiteConfig.Pragma` |
 * | ORACLE | `ojdbc11-23.7.0.25.01.jar` | `oracle.jdbc.OracleConnection` |
 *
 * **A driver upgrade must re-review its dialect's set** (§5.6). [DialectRefusalSetsTest] pins
 * every set literally, so an upgrade that changes the expectation shows up as a red test rather
 * than as a silently widened attack surface.
 *
 * All entries are **lowercase**; matching lowercases the candidate, because driver property
 * lookup is case-insensitive and `Socketfactory` must not slip past.
 */
internal object DialectRefusalSets {
    /**
     * The refusal set for [dialect] — **total over the enum** by an exhaustive `when`, which is
     * the §5.6 fail-closed requirement: there is no code path that can yield an empty set for an
     * unrecognized dialect or a non-conforming adapter, because the lookup never consults the
     * adapter instance.
     */
    fun forDialect(dialect: Dialect): Set<String> =
        when (dialect) {
            Dialect.POSTGRES -> POSTGRES
            Dialect.ORACLE -> ORACLE
            Dialect.MSSQL -> MSSQL
            Dialect.MYSQL -> MYSQL
            Dialect.H2 -> H2
            Dialect.DUCKDB -> DUCKDB
            Dialect.SQLITE -> SQLITE
        }

    /**
     * PostgreSQL 42.7.13. `socketFactory`/`sslfactory`/`authenticationPluginClassName`/
     * `sslpasswordcallback`/`connectExecutor`/`xmlFactoryFactory` each name a class the driver
     * instantiates; `sslkey` is a private-key **path**; `sslpassword` its passphrase;
     * `loggerFile` writes an operator-chosen file; `loggerLevel` turns that writing on;
     * `sslhostnameverifier` is the TLS-verification switch.
     *
     * `sslrootcert` and `sslcert` are deliberately **not** here — §5 names `sslrootcert` as a
     * supported `properties.jdbc` key (the CA path an operator must be able to set), and `sslcert`
     * is its client-side sibling.
     */
    val POSTGRES =
        setOf(
            "socketfactory",
            "socketfactoryarg",
            "sslfactory",
            "sslfactoryarg",
            "sslhostnameverifier",
            "authenticationpluginclassname",
            "sslkey",
            "sslpassword",
            "sslpasswordcallback",
            "loggerfile",
            "loggerlevel",
            "connectexecutor",
            "connectexecutorarg",
            "xmlfactoryfactory",
        )

    /**
     * MySQL Connector/J 9.7.0. Three families: the local-infile / gadget vectors named by §5.6,
     * every property whose value is a **class name** the driver loads (factories, plugins,
     * interceptors, JCE providers, the logger), and the file-path / keystore surface.
     * `sessionVariables` is connect-time SQL (`SET …` at handshake).
     *
     * `sslMode` / `useSSL` are deliberately absent — §5 names them as the dialect's supported TLS
     * knobs. `verifyServerCertificate` **is** refused: it is a TLS-verification switch, the same
     * category as MSSQL `trustServerCertificate` in the §5.6 table.
     */
    val MYSQL =
        setOf(
            // §5.6 minimum + the local-infile family.
            "allowloadlocalinfile",
            "allowloadlocalinfileinpath",
            "allowurlinlocalinfile",
            // Not a 9.7.0 PropertyKey; the pre-8.0 spelling, kept so a driver downgrade stays covered.
            "uselocalinfile",
            "autodeserialize",
            "allowmultiqueries",
            // DS-SEC-16: under caching_sha2/sha256 auth, `=true` fetches the server's RSA public
            // key over a channel that has not been authenticated yet, so a MITM can substitute its
            // own key and harvest the password. Verified present in Connector/J 9.7.0's PropertyKey.
            "allowpublickeyretrieval",
            // Class names the driver instantiates.
            "socketfactory",
            "parseinfocachefactory",
            "queryinfocachefactory",
            "serverconfigcachefactory",
            "clientinfoprovider",
            "connectionlifecycleinterceptors",
            "exceptioninterceptors",
            "queryinterceptors",
            "profilereventhandler",
            "propertiestransform",
            "loadbalanceexceptionchecker",
            "ha.loadbalancestrategy",
            "ha_loadbalancestrategy",
            "haloadbalancestrategy",
            "authenticationplugins",
            "defaultauthenticationplugin",
            "disabledauthenticationplugins",
            "authenticationopenidconnectcallbackhandler",
            "authenticationwebauthncallbackhandler",
            "keymanagerfactoryprovider",
            "trustmanagerfactoryprovider",
            "keystoreprovider",
            "sslcontextprovider",
            "logger",
            // File paths the driver reads.
            "clientcertificatekeystoreurl",
            "clientcertificatekeystorepassword",
            "clientcertificatekeystoretype",
            "trustcertificatekeystoreurl",
            "trustcertificatekeystorepassword",
            "trustcertificatekeystoretype",
            "fallbacktosystemkeystore",
            "fallbacktosystemtruststore",
            "serverrsapublickeyfile",
            "ociconfigfile",
            "ociconfigprofile",
            "idtokenfile",
            "namedpipepath",
            "localsocketaddress",
            // Connect-time SQL, and the TLS-verification switch.
            "sessionvariables",
            "verifyservercertificate",
        )

    /**
     * MSSQL 12.10.2.jre11 — the §5.6 table's full set (this dialect was previously **empty**),
     * plus the three further class/file properties the pinned driver exposes:
     * `accessTokenCallbackClass` and `trustManagerClass`/`trustManagerConstructorArg` instantiate
     * a caller-named class, and `serverCertificate` is a certificate **path** on the server.
     *
     * The `keyVault*` / `keyStorePrincipalId` trio (§5.6, v1.6 — DS-SEC-14/18) is here for the
     * *value* rather than the behavior: an Azure Key Vault client secret in `properties.jdbc` is
     * stored plaintext and returned to `read` scope (§3.2). `keyVaultProviderClientKey` is the one
     * the [SECRET_VALUED_SUFFIXES] predicate would miss on its own — it ends in `clientkey`, which
     * the predicate does cover, but the id and principal-id siblings do not, so both are named.
     */
    val MSSQL =
        setOf(
            "socketfactoryclass",
            "socketfactoryconstructorarg",
            "truststore",
            "truststorepassword",
            "truststoretype",
            "keystorelocation",
            "keystoresecret",
            "keystoreauthentication",
            "keystoreprincipalid",
            "clientcertificate",
            "clientkey",
            "clientkeypassword",
            "trustservercertificate",
            "keyvaultproviderclientkey",
            "keyvaultproviderclientid",
            "accesstokencallbackclass",
            "trustmanagerclass",
            "trustmanagerconstructorarg",
            "servercertificate",
        )

    /** H2 — `INIT` runs arbitrary SQL at connect (`RUNSCRIPT FROM '…'`); `RUNSCRIPT` is its verb. */
    val H2 = setOf("init", "runscript")

    /**
     * DuckDB 1.5.5.1 — two families.
     *
     * **1. The driver's session-init-SQL-file family.** `org.duckdb.DuckDBDriver` declares
     * `SESSION_INIT_SQL_FILE_OPTION = "session_init_sql_file"` and
     * `SESSION_INIT_SQL_FILE_SHA256_OPTION = "session_init_sql_file_sha256"`, and
     * `readSessionInitSQLFile` / `runSessionInitSQLFile` read that file and execute its SQL on
     * every connection — connect-time SQL from an operator-named path, both §5.6 categories at
     * once.
     *
     * **2. The engine config settings DuckDB accepts as connection properties (DS-SEC-19).** The
     * reviewer could not confirm from the jar whether an unrecognized `properties.jdbc` key reaches
     * the engine. It does. [EmbeddedDialectBehaviorTest] settles it against the real engine with a
     * control/set pair — with no property, `allow_unsigned_extensions` reads `false`; with
     * `properties.jdbc.allow_unsigned_extensions="true"` the same query reads `true`. So DuckDB's
     * whole settings catalog is a §5.6 surface reachable from a saved datasource, and
     * `allow_unsigned_extensions` alone is arbitrary native-code execution.
     *
     * The additions below were chosen from a **mechanical** inventory (`SELECT name, value FROM
     * duckdb_settings()` filtered to the extension / secret / path / repository families), not from
     * recall, and every one was proven to reach the engine by the same control-vs-set comparison.
     *
     * The line drawn is **"any settable value widens the surface"**. Refused, and why:
     *
     *  - `allow_unsigned_extensions` — `false`→`true` loads unsigned native code. RCE.
     *  - `allow_extensions_metadata_mismatch` — `false`→`true` skips metadata validation.
     *  - `allow_parser_override_extension` — non-`DEFAULT` lets an extension replace the parser.
     *  - `allow_unredacted_secrets` — `false`→`true` un-redacts `duckdb_secrets()` values.
     *  - `custom_extension_repository`, `autoinstall_extension_repository` — any value re-points
     *    the extension **download origin**, which is the rest of the unsigned-extension RCE chain.
     *  - `extension_directory`, `extension_directories`, `secret_directory`, `home_directory`,
     *    `file_search_path`, `temp_directory` — any value re-points engine file I/O, the §5.6
     *    file-path category.
     *  - `allowed_paths` — **adds** to the filesystem sandbox allowlist rather than narrowing it
     *    (proven: the probe's path is appended to the engine's list, not swapped for it).
     *
     * **The five extension-hardening keys (§5.6, v1.8).** `allow_unsigned_extensions`,
     * `allow_community_extensions`, `autoload_known_extensions`, `autoinstall_known_extensions` and
     * `enable_external_access` are refused for a *second*, different reason: they are the five
     * [DuckdbDialectAdapter.defaultProperties] sets to `false` to close the in-process RCE vector.
     * `properties.jdbc` is applied **after** `defaultProperties` (§4.2), so without refusing them an
     * operator could set `enable_external_access=true` on a saved datasource and re-open the exact
     * surface the adapter closes.
     *
     * This inverts the round-2 reasoning for four of them, and the inversion is the point worth
     * recording: while the *engine* default was `true`, `false` was the only settable-different
     * value, so refusing them would have blocked a hardening and gained nothing. Once the *server*
     * default became `false`, `true` became a widening. **A key's refusal category is a function of
     * the default it sits against, not of the key alone** — so changing a `defaultProperties` entry
     * obliges a re-check of that dialect's refusal set.
     *
     * Deliberately **not** refused, because every value they accept is at-or-safer-than the default,
     * so refusing them would block an operator hardening a datasource while gaining no surface:
     * `allow_persistent_secrets` (default `true`) and `default_secret_storage` (an enum over
     * engine-provided backends — the path half, `secret_directory`, *is* refused).
     * `log_query_path` is not refused because it is not reachable: DuckDB rejects it at connect
     * with *"can only be set when a context is present"*.
     */
    val DUCKDB =
        setOf(
            // The driver's own session-init-SQL-file family.
            "session_init_sql_file",
            "session_init_sql_file_sha256",
            // Native-code loading and its verification switches.
            "allow_unsigned_extensions",
            "allow_extensions_metadata_mismatch",
            "allow_parser_override_extension",
            "custom_extension_repository",
            "autoinstall_extension_repository",
            // The rest of the v1.8 adapter-hardening five: refused so properties.jdbc, which is
            // applied after defaultProperties, cannot set them back to `true`.
            "allow_community_extensions",
            "autoload_known_extensions",
            "autoinstall_known_extensions",
            "enable_external_access",
            // Secret exposure.
            "allow_unredacted_secrets",
            // File paths the engine reads or writes.
            "extension_directory",
            "extension_directories",
            "secret_directory",
            "home_directory",
            "file_search_path",
            "temp_directory",
            "allowed_paths",
        )

    /**
     * SQLite 3.49.1.0. `enable_load_extension` (the `LOAD_EXTENSION` pragma) lets a connection
     * load a native extension — arbitrary code; `temp_store_directory` names a directory the
     * engine writes into.
     *
     * `limit_attached` is refused as a §5.6 v1.9 hardening: it controls `SQLITE_LIMIT_ATTACHED`,
     * which [SqliteDialectAdapter.defaultProperties] sets to `0` at connect to prevent
     * `ATTACH DATABASE` — an in-process engine must not hand author SQL a filesystem-access
     * primitive. Refused so `properties.jdbc`, applied AFTER `defaultProperties`, cannot re-open
     * the surface.
     */
    val SQLITE = setOf("enable_load_extension", "temp_store_directory", "limit_attached")

    /**
     * Oracle ojdbc11 23.7.0.25.01, read from `oracle.jdbc.OracleConnection`'s
     * `CONNECTION_PROPERTY_*` constants. Wallets, keystores, truststores and the LDAP variants are
     * file paths (with their passwords); `oracle.jdbc.debugJDWP` opens a JDWP debug channel
     * (arbitrary code); `oracle.net.KerberosJaasLoginModule` and
     * `oracle.net.radius_challenge_response_handler` name classes; `oracle.jdbc.config.file`,
     * `oracle.net.tns_admin`, `oracle.net.profile`, `oracle.jdbc.sqlErrorTranslationFile` and the
     * OCI/token files are read from disk; `oracle.net.ssl_server_dn_match`,
     * `oracle.net.ssl_allow_weak_dn_match` and `oracle.net.allow_weak_crypto` are
     * TLS-verification switches.
     *
     * The driver is **not** on this build's classpath without `-Poracle`, so this set is pinned
     * from the jar in the Gradle cache rather than exercised against a live Oracle. Flagged in the
     * fix-cycle report as needing a `-Poracle` re-review.
     */
    val ORACLE =
        setOf(
            "oracle.net.wallet_location",
            "oracle.net.wallet_password",
            "javax.net.ssl.keystore",
            "javax.net.ssl.keystorepassword",
            "javax.net.ssl.keystoretype",
            "javax.net.ssl.truststore",
            "javax.net.ssl.truststorepassword",
            "javax.net.ssl.truststoretype",
            "oracle.net.ldap.ssl.keystore",
            "oracle.net.ldap.ssl.keystorepassword",
            "oracle.net.ldap.ssl.keystoretype",
            "oracle.net.ldap.ssl.truststore",
            "oracle.net.ldap.ssl.truststorepassword",
            "oracle.net.ldap.ssl.truststoretype",
            "oracle.net.ldap.ssl.walletlocation",
            "oracle.net.ldap.ssl.walletpassword",
            "oracle.net.ldap.ssl.keymanagerfactory.algorithm",
            "oracle.net.ldap.ssl.trustmanagerfactory.algorithm",
            "ssl.keymanagerfactory.algorithm",
            "ssl.trustmanagerfactory.algorithm",
            "oracle.jdbc.debugjdwp",
            "oracle.net.kerberosjaasloginmodule",
            "oracle.net.radius_challenge_response_handler",
            "oracle.net.kerberos5_cc_name",
            "oracle.jdbc.config.file",
            "oracle.jdbc.configurationproviders",
            "oracle.jdbc.remoteconfigurationfiltering",
            "oracle.jdbc.provider.tlsconfiguration",
            "oracle.jdbc.ociconfigfile",
            "oracle.jdbc.ociprofile",
            "oracle.jdbc.ons.walletfile",
            "oracle.jdbc.ons.walletpassword",
            "oracle.jdbc.sqlerrortranslationfile",
            "oracle.jdbc.tokenlocation",
            "oracle.net.tns_admin",
            "oracle.net.profile",
            "oracle.net.ssl_server_dn_match",
            "oracle.net.ssl_allow_weak_dn_match",
            "oracle.net.allow_weak_crypto",
        )
}

/**
 * The **union** §5.6 validates both carriers against: the dialect refusal set, the server-managed
 * set, and the credential keys.
 *
 * Kept in one object so the `jdbc_url` guard ([JdbcUrlGuard]) and the `properties.jdbc` denylist
 * ([DatasourceValidator]) cannot drift — "refusal applies to both carriers identically" is a
 * property of *this* function, not of two lists that happen to agree today.
 */
internal object RefusedPropertyKeys {
    /**
     * HikariCP properties the server derives from the entity and the adapter (§5). Supplying one
     * is a validation failure rather than a silent override.
     *
     * `exceptionOverrideClassName` is here per §5.6: HikariCP instantiates whatever class it
     * names, so it is a class-loading surface, not a tuning knob.
     *
     * `readOnly` is here per workspaces design §6 layer 2b (D6): the flag is the server's to
     * derive from the entity — `config.isReadOnly = datasource.isReadonly` — so an operator
     * passthrough must not flip it EITHER way on ANY datasource (silently hardening a writable
     * one would be as much a lie as silently un-hardening a readonly one; both directions are
     * refused with `datasource.validation.properties_invalid`).
     */
    val SERVER_MANAGED =
        setOf(
            "jdbcurl",
            "username",
            "password",
            "driverclassname",
            "datasourceclassname",
            "poolname",
            "metricregistry",
            "healthcheckregistry",
            "datasource",
            "datasourcejndi",
            "exceptionoverrideclassname",
            "readonly",
        )

    /**
     * Credential keys, refused in **both** carriers (§5.6): `jdbc_url` is stored plaintext and
     * returned to `read`-scope principals (§3.2), so a credential embedded there defeats the §7.1
     * encryption at rest. Credentials arrive through the dedicated `username`/`password` fields.
     *
     * `username` is the MSSQL alias (`SQLServerDriver` accepts `userName` beside `user`);
     * `password1`/`password2`/`password3` are Connector/J 9.7.0's multi-factor password slots.
     */
    val CREDENTIALS = setOf("user", "username", "password", "password1", "password2", "password3")

    /**
     * The §5.6 (v1.6) **secret-valued-property** suffix predicate — DS-SEC-14.
     *
     * Enumeration alone cannot hold this line. `properties.jdbc` is stored plaintext in
     * `properties_json` and returned to `read` scope exactly like `jdbc_url` (§3.2), so *any*
     * property whose **value** is credential material is a plaintext-secret exposure — regardless
     * of whether it also loads a class or names a file. The reviewer's proof was MSSQL
     * `keyVaultProviderClientKey`: an Azure Key Vault client secret that saved cleanly and was
     * GET-readable, because no tabled key matched it.
     *
     * A suffix test over the key name covers the next driver version's secret key **by
     * construction**, which is the property enumeration cannot have. It is layered on top of the
     * tabled sets, never instead of them: the tables still carry the class-loading and file-path
     * keys the predicate says nothing about, and named secrets that the suffixes would miss
     * (`keyVaultProviderClientId`, `keyStorePrincipalId`) stay enumerated.
     *
     * Deliberately narrow. Every suffix names a value that *is* the secret, so a legitimate
     * property cannot end in one: `sslrootcert` (a path), `ApplicationName`, `sslmode`,
     * `passwordCharacterEncoding` (an encoding, not a password) all pass untouched — asserted in
     * [DialectRefusalSetsTest].
     */
    val SECRET_VALUED_SUFFIXES = listOf("password", "passwd", "pwd", "secret", "clientkey")

    /**
     * Whether [key] is refused under [refusedKeys] — the enumerated union **or** the §5.6
     * secret-valued suffix predicate.
     *
     * The single entry point both carriers call ([JdbcUrlGuard] for `jdbc_url`, the
     * `properties.jdbc` scan in [DatasourceValidator]), so the predicate cannot be applied to one
     * and forgotten on the other.
     */
    fun isRefused(
        key: String,
        refusedKeys: Set<String>,
    ): Boolean {
        val lower = key.lowercase()
        return lower in refusedKeys || SECRET_VALUED_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * The refusal union for [dialect]. [adapter], when supplied, may only **add** to it — an
     * adapter that returns an empty [DialectAdapter.refusedPropertyKeys] gains no exemption,
     * which is the §5.6 fail-closed rule made structural.
     *
     * The [SECRET_VALUED_SUFFIXES] predicate is *not* folded in here — it is unbounded over key
     * names and so cannot be expressed as a set. Callers test membership through [isRefused].
     */
    fun forDialect(
        dialect: Dialect,
        adapter: DialectAdapter? = null,
    ): Set<String> = DialectRefusalSets.forDialect(dialect) + adapter?.refusedPropertyKeys.orEmpty() + SERVER_MANAGED + CREDENTIALS
}
