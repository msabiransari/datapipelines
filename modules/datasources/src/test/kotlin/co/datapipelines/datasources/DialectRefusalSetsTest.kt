package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.IngressTypeMapper
import co.datapipelines.typesystem.TypeMappers
import com.zaxxer.hikari.HikariConfig
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The §5.6 refusal sets: their **contents**, their application to **both carriers**, and the
 * fail-closed rule.
 *
 * ## Why the sets are pinned literally
 *
 * §5.6 makes the module's sets the authoritative enumeration and states that *"a driver upgrade
 * must re-review its dialect's set"*. A test that only checked "socketFactory is refused" would
 * stay green through a driver upgrade that introduced a brand-new class-loading property. Pinning
 * the whole set means the upgrade shows up here as a red test whose fix is the review §5.6 asks
 * for. Every string below was read out of the pinned driver jar — see [DialectRefusalSets].
 */
class DialectRefusalSetsTest {
    @Test
    fun `the POSTGRES set is exactly the reviewed set for postgresql 42-7-13`() {
        DialectRefusalSets.POSTGRES shouldBe
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
    }

    @Test
    fun `the MSSQL set is exactly the reviewed set for mssql-jdbc 12-10-2`() {
        DialectRefusalSets.MSSQL shouldBe
            setOf(
                "socketfactoryclass",
                "socketfactoryconstructorarg",
                "truststore",
                "truststorepassword",
                "truststoretype",
                "keystorelocation",
                "keystoresecret",
                "keystoreauthentication",
                // §5.6 v1.6 (DS-SEC-14/18): secret-valued and identity properties that are stored
                // plaintext in properties_json and returned to read scope.
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
    }

    @Test
    fun `the DUCKDB set is exactly the driver session-init family plus the reachable engine settings`() {
        // org.duckdb.DuckDBDriver declares SESSION_INIT_SQL_FILE_OPTION and
        // SESSION_INIT_SQL_FILE_SHA256_OPTION; readSessionInitSQLFile/runSessionInitSQLFile read
        // that path and execute its SQL on every connection.
        //
        // The rest are DuckDB *engine* settings, which DS-SEC-19 proved reachable from
        // properties.jdbc against the running engine ([EmbeddedDialectBehaviorTest]) — see
        // DialectRefusalSets.DUCKDB for the per-key rationale and for the settings deliberately
        // left operator-settable.
        DialectRefusalSets.DUCKDB shouldBe
            setOf(
                "session_init_sql_file",
                "session_init_sql_file_sha256",
                "allow_unsigned_extensions",
                "allow_extensions_metadata_mismatch",
                "allow_parser_override_extension",
                "custom_extension_repository",
                "autoinstall_extension_repository",
                // §5.6 v1.8: the rest of the adapter-hardening five. Refused so properties.jdbc,
                // applied AFTER defaultProperties, cannot set them back to `true`.
                "allow_community_extensions",
                "autoload_known_extensions",
                "autoinstall_known_extensions",
                "enable_external_access",
                "allow_unredacted_secrets",
                "extension_directory",
                "extension_directories",
                "secret_directory",
                "home_directory",
                "file_search_path",
                "temp_directory",
                "allowed_paths",
            )
    }

    @Test
    fun `the SQLITE and H2 sets are exactly their reviewed sets`() {
        DialectRefusalSets.SQLITE shouldBe setOf("enable_load_extension", "temp_store_directory")
        DialectRefusalSets.H2 shouldBe setOf("init", "runscript")
    }

    @Test
    fun `the MYSQL set is exactly the reviewed set for mysql-connector-j 9-7-0`() {
        // NEW-1: pinned with shouldBe, not shouldContainAll. A subset assertion stays green when a
        // §5.6-mandated key is DELETED, which is the opposite of what "pins every set literally"
        // promises — the whole point is that a driver upgrade (or an accidental deletion) shows up
        // here as a red test whose fix is the review §5.6 asks for.
        DialectRefusalSets.MYSQL shouldBe
            setOf(
                // §5.6 minimum + the local-infile family.
                "allowloadlocalinfile",
                "allowloadlocalinfileinpath",
                "allowurlinlocalinfile",
                "uselocalinfile",
                "autodeserialize",
                "allowmultiqueries",
                // DS-SEC-16: fetches the server RSA key over an unauthenticated channel.
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

        // §5 names sslMode/useSSL as this dialect's supported TLS knobs — refusing them would
        // break the passthrough contract the refusal set is an exception to, not a replacement for.
        DialectRefusalSets.MYSQL shouldNotContain "sslmode"
        DialectRefusalSets.MYSQL shouldNotContain "usessl"
    }

    @Test
    fun `the ORACLE set is exactly the reviewed set for ojdbc11 23-7-0-25-01`() {
        // NEW-1: whole-set pin, as for the other six dialects. Carry-forward (DS-SEC-4): the driver
        // is not on the classpath without -Poracle, so these strings are pinned from the jar in the
        // Gradle cache and still owe a `-Poracle` classpath re-review.
        DialectRefusalSets.ORACLE shouldBe
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

    @Test
    fun `every dialect has a non-empty refusal set - an unreviewed adapter is a defect`() {
        Dialect.entries.forEach { dialect ->
            withClue("dialect $dialect has no §5.6 refusal set") {
                DialectRefusalSets.forDialect(dialect).isEmpty() shouldBe false
            }
            // Every entry is lowercase — matching lowercases the candidate, so an upper-case
            // entry would be dead weight that silently never matches.
            DialectRefusalSets.forDialect(dialect).forEach { key -> key shouldBe key.lowercase() }
        }
    }

    @Test
    fun `a refused key smuggled into the JDBC URL is rejected per dialect`() {
        smuggled(Dialect.POSTGRES, "jdbc:postgresql://h/db?authenticationPluginClassName=evil.Plugin")
        smuggled(Dialect.POSTGRES, "jdbc:postgresql://h/db?sslkey=/tmp/attacker.key")
        smuggled(Dialect.MYSQL, "jdbc:mysql://h/db?queryInterceptors=evil.Interceptor")
        smuggled(Dialect.MSSQL, "jdbc:sqlserver://h:1433;databaseName=app;trustServerCertificate=true")
        smuggled(Dialect.MSSQL, "jdbc:sqlserver://h:1433;socketFactoryClass=evil.Factory")
        smuggled(Dialect.H2, "jdbc:h2:mem:t;INIT=RUNSCRIPT FROM 'http://x/e.sql'")
        smuggled(Dialect.DUCKDB, "jdbc:duckdb:/tmp/a.db;session_init_sql_file=/tmp/evil.sql")
        smuggled(Dialect.DUCKDB, "jdbc:duckdb:/tmp/a.db;session_init_sql_file_sha256=deadbeef")
        smuggled(Dialect.SQLITE, "jdbc:sqlite:/tmp/a.db?enable_load_extension=true")
        smuggled(Dialect.ORACLE, "jdbc:oracle:thin:@//h:1521/svc?oracle.net.wallet_location=/tmp/w")
    }

    @Test
    fun `credentials are refused in the JDBC URL - both as properties and as authority userinfo`() {
        // §5.6: jdbc_url is stored plaintext and returned to read-scope principals, so a
        // credential there defeats §7.1 encryption at rest.
        smuggled(Dialect.POSTGRES, "jdbc:postgresql://h/db?user=admin")
        smuggled(Dialect.POSTGRES, "jdbc:postgresql://h/db?password=hunter2")
        smuggled(Dialect.MSSQL, "jdbc:sqlserver://h:1433;userName=sa;password=hunter2")
        smuggled(Dialect.MYSQL, "jdbc:mysql://h/db?password1=hunter2")
        smuggled(Dialect.MYSQL, "jdbc:mysql://admin:hunter2@h/db")
    }

    @Test
    fun `DS-SEC-13 - a userinfo authority is refused wherever it appears, not only after a leading slash-slash`() {
        // The pre-fix guard keyed off a `//`-prefixed sub-name, so the two driver forms that put
        // the SCHEME before the authority slipped through and stored a plaintext credential in a
        // column that is returned to read scope (§3.2). Both are the driver's own documented shape.
        smuggled(Dialect.ORACLE, "jdbc:oracle:thin:scott/tiger@//h:1521/svc")
        smuggled(Dialect.H2, "jdbc:h2:tcp://user:pw@host/db")
        // The Oracle EZConnect form with an EMPTY userinfo is the legitimate one and must survive.
        DialectAdapters.forDialect(Dialect.ORACLE).validateJdbcUrl("jdbc:oracle:thin:@//h:1521/svc").valid shouldBe true
        // The file-path false positive the guard must keep clearing: '@' in a filename is not an
        // authority, and DuckDB/SQLite URLs are bare paths.
        DialectAdapters.forDialect(Dialect.DUCKDB).validateJdbcUrl("jdbc:duckdb:/var/lib/a@b.db").valid shouldBe true
        DialectAdapters.forDialect(Dialect.SQLITE).validateJdbcUrl("jdbc:sqlite:/var/lib/a@b.db").valid shouldBe true
    }

    @Test
    fun `DS-SEC-16 - MySQL allowPublicKeyRetrieval is refused in both carriers`() {
        // Under caching_sha2/sha256 auth, `=true` makes the client fetch the server's RSA public
        // key over a channel that has not been authenticated yet, so a MITM substitutes its own key
        // and harvests the password. Verified present in the pinned Connector/J 9.7.0 PropertyKey.
        smuggled(Dialect.MYSQL, "jdbc:mysql://h/db?allowPublicKeyRetrieval=true")
        refusalErrorsForJdbcProperty(Dialect.MYSQL, "allowPublicKeyRetrieval").map { it.code } shouldContain
            DatasourceErrorCodes.PROPERTIES_INVALID
    }

    @Test
    fun `DS-SEC-14 - a secret-valued property is refused by name in both carriers`() {
        // properties.jdbc is stored plaintext in properties_json and returned to read scope exactly
        // like jdbc_url (§3.2), so an Azure Key Vault client secret there is a plaintext-secret
        // exposure even though it neither loads a class nor names a file. Pre-fix it saved cleanly.
        smuggled(Dialect.MSSQL, "jdbc:sqlserver://h:1433;keyVaultProviderClientKey=s3cret")
        refusalErrorsForJdbcProperty(Dialect.MSSQL, "keyVaultProviderClientKey").map { it.code } shouldContain
            DatasourceErrorCodes.PROPERTIES_INVALID
        refusalErrorsForJdbcProperty(Dialect.MSSQL, "keyStorePrincipalId").map { it.code } shouldContain
            DatasourceErrorCodes.PROPERTIES_INVALID
    }

    @Test
    fun `DS-SEC-14 - the suffix predicate covers a key no table names, in both carriers`() {
        // The property enumeration cannot have: a key nobody has reviewed, on a dialect whose set
        // does not mention it, is still refused because its NAME says its value is a secret. This
        // is what makes the next driver version's secret key covered by construction.
        listOf("fooClientKey", "someNewPassword", "vaultSecret", "myPwd", "legacyPasswd").forEach { key ->
            withClue("the suffix predicate must refuse properties.jdbc.$key") {
                refusalErrorsForJdbcProperty(Dialect.POSTGRES, key).map { it.code } shouldContain
                    DatasourceErrorCodes.PROPERTIES_INVALID
            }
            withClue("the suffix predicate must refuse $key smuggled into the URL") {
                smuggled(Dialect.POSTGRES, "jdbc:postgresql://h/db?$key=x")
            }
        }
    }

    @Test
    fun `DS-SEC-14 - the suffix predicate does not over-refuse a legitimate non-secret key`() {
        // The refusal set is a bounded exception to passthrough (§2 principle 8), so a predicate
        // that swallowed ordinary driver properties would be a worse defect than the one it fixes.
        // `sslrootcert` is a PATH that §5 names as supported; the rest are plain connection knobs;
        // `passwordCharacterEncoding` is the trap — it CONTAINS "password" but does not END in it.
        listOf("sslrootcert", "ApplicationName", "sslmode", "passwordCharacterEncoding", "connectTimeout").forEach { key ->
            withClue("properties.jdbc.$key must stay accepted") {
                RefusedPropertyKeys.isRefused(key, RefusedPropertyKeys.forDialect(Dialect.POSTGRES)) shouldBe false
            }
        }
        // And end-to-end through the validator, not just the predicate in isolation.
        DatasourceValidator(driverAvailable = { true })
            .validate(
                Fixtures.forDialect(Dialect.POSTGRES, properties = DatasourceProperties(jdbc = mapOf("sslrootcert" to "/etc/ca.crt"))),
                isCreate = true,
            ).errors shouldBe emptyList()
    }

    @Test
    fun `a server-managed hikari key smuggled into the URL is refused too`() {
        smuggled(Dialect.POSTGRES, "jdbc:postgresql://h/db?jdbcUrl=jdbc:postgresql://evil/db")
    }

    @Test
    fun `a Testcontainers-shaped Postgres URL is refused - loggerLevel is in the PG refusal set`() {
        // Recorded so a downstream module does not rediscover this the hard way:
        // PostgreSQLContainer.configure() adds `loggerLevel=OFF`, so getJdbcUrl() returns
        // "...?loggerLevel=OFF" — a key §5.6's POSTGRES row refuses (it enables driver logging to
        // the loggerFile path). Registering a container-backed datasource through save() must
        // therefore use the bare URL; the probe path (DialectProbe) is unaffected because it
        // builds a pool directly without validating.
        smuggled(Dialect.POSTGRES, "jdbc:postgresql://localhost:54321/test?loggerLevel=OFF")

        DialectAdapters
            .forDialect(Dialect.POSTGRES)
            .validateJdbcUrl("jdbc:postgresql://localhost:54321/test")
            .valid shouldBe true
    }

    @Test
    fun `the refusal message never echoes the credential value`() {
        val result = DialectAdapters.forDialect(Dialect.POSTGRES).validateJdbcUrl("jdbc:postgresql://h/db?password=hunter2")

        result.valid shouldBe false
        result.errors
            .single()
            .message
            .contains("hunter2") shouldBe false
    }

    @Test
    fun `the same key is refused in properties_jdbc - the twin carrier`() {
        // §5.6: "refusal applies to both carriers identically". Each URL case above has this twin.
        listOf(
            Dialect.POSTGRES to "authenticationPluginClassName",
            Dialect.POSTGRES to "sslkey",
            Dialect.MYSQL to "queryInterceptors",
            Dialect.MSSQL to "trustServerCertificate",
            Dialect.H2 to "INIT",
            Dialect.DUCKDB to "session_init_sql_file",
            Dialect.SQLITE to "enable_load_extension",
            // NEW-3: ORACLE had a URL-side case but no properties.jdbc twin, so "identical in both
            // carriers" was asserted over a smaller key set on this dialect than on the others.
            Dialect.ORACLE to "oracle.net.wallet_location",
            Dialect.ORACLE to "oracle.jdbc.debugJDWP",
        ).forEach { (dialect, key) ->
            val errors = refusalErrorsForJdbcProperty(dialect, key)
            withClue("properties.jdbc.$key must be refused for $dialect") {
                errors.map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
            }
        }
    }

    @Test
    fun `a credential in properties_jdbc is refused as a server-managed key`() {
        refusalErrorsForJdbcProperty(Dialect.POSTGRES, "user").map { it.code } shouldContain
            DatasourceErrorCodes.PROPERTIES_INVALID
        refusalErrorsForJdbcProperty(Dialect.POSTGRES, "password").map { it.code } shouldContain
            DatasourceErrorCodes.PROPERTIES_INVALID
        // NEW-3: the URL side already tested the MSSQL `userName` alias and Connector/J's
        // multi-factor `password1` slot; without these the twin-carrier claim rested on a key set
        // the two carriers did not actually share.
        refusalErrorsForJdbcProperty(Dialect.MSSQL, "userName").map { it.code } shouldContain
            DatasourceErrorCodes.PROPERTIES_INVALID
        refusalErrorsForJdbcProperty(Dialect.MYSQL, "password1").map { it.code } shouldContain
            DatasourceErrorCodes.PROPERTIES_INVALID
    }

    @Test
    fun `exceptionOverrideClassName is server-managed under hikari`() {
        // §5.6: HikariCP instantiates whatever class it names, so it is a class-loading surface,
        // not a tuning knob — and it is a real HikariCP property, so without the denylist entry
        // the reflective probe would accept it.
        val result =
            DatasourceValidator().validate(
                Fixtures.h2(properties = DatasourceProperties(hikari = mapOf("exceptionOverrideClassName" to "evil.Override"))),
                isCreate = true,
            )

        result.errors.single { it.code == DatasourceErrorCodes.PROPERTIES_INVALID }.field shouldContain
            "exceptionOverrideClassName"
    }

    @Test
    fun `an adapter that is not an AbstractDialectAdapter gets no exemption - the guard fails closed`() {
        // The pre-fix implementation resolved the refusal set with `as? AbstractDialectAdapter`,
        // so ANY other implementation yielded an empty set and every dangerous key sailed through.
        // The set now comes from the dialect enum, which no adapter can shrink: this double
        // declares no refusals and validates every URL as fine, and both carriers still refuse.
        val validator = DatasourceValidator(adapters = { PermissiveAdapter(it) })

        val urlErrors = validator.validate(Fixtures.h2(jdbcUrl = "jdbc:h2:mem:t;INIT=RUNSCRIPT FROM 'http://x/e.sql'"), true)
        urlErrors.errors.map { it.code } shouldContain DatasourceErrorCodes.JDBC_URL_MALFORMED

        val propertyErrors =
            validator.validate(Fixtures.h2(properties = DatasourceProperties(jdbc = mapOf("INIT" to "RUNSCRIPT FROM 'x'"))), true)
        propertyErrors.errors.map { it.code } shouldContain DatasourceErrorCodes.PROPERTIES_INVALID
    }

    private fun refusalErrorsForJdbcProperty(
        dialect: Dialect,
        key: String,
    ): List<ValidationResult.ValidationError> =
        DatasourceValidator(driverAvailable = { true })
            .validate(Fixtures.forDialect(dialect, properties = DatasourceProperties(jdbc = mapOf(key to "x"))), isCreate = true)
            .errors

    private fun smuggled(
        dialect: Dialect,
        url: String,
    ) {
        val result = DialectAdapters.forDialect(dialect).validateJdbcUrl(url)
        withClue("$url must be refused for $dialect") {
            result.valid shouldBe false
            result.errors.single().code shouldBe DatasourceErrorCodes.JDBC_URL_MALFORMED
        }
    }

    /** A [DialectAdapter] that is deliberately *not* an [AbstractDialectAdapter] and refuses nothing. */
    private class PermissiveAdapter(
        override val dialect: Dialect,
    ) : DialectAdapter {
        override val jdbcDriverClassName: String = JdbcDrivers.classNameFor(dialect)
        override val defaultProperties: Map<String, String> = emptyMap()
        override val typeMapper: IngressTypeMapper = TypeMappers.forDialect(dialect)
        override val refusedPropertyKeys: Set<String> = emptySet()

        override fun validateJdbcUrl(url: String): ValidationResult = ValidationResult.ok()

        override fun buildHikariConfig(datasource: Datasource): HikariConfig = HikariConfig().apply { jdbcUrl = datasource.jdbcUrl }
    }
}
