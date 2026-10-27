/** A reviewed direct vcpkg dependency; expressions preserve upstream alternatives and combined terms. */
data class DuckDbComponent(
    val name: String,
    val spdx: String,
    val source: String,
    val attribution: String = "",
)

/** Extension licensing is bound to the exact source commit recorded in the binary footer. */
data class DuckDbExtensionLicense(
    val commit: String,
    val repository: String,
    val spdx: String,
    val copyright: String,
    val components: List<DuckDbComponent>,
)

/** A non-POM distribution row, resolved entirely from committed inputs. */
data class DuckDbExtensionNotice(
    val name: String,
    val core: String,
    val license: DuckDbExtensionLicense,
) {
    val coordinate: String get() = "duckdb-extension:$name:v$core+${license.commit}"
    val source: String get() = "${license.repository}/tree/${license.commit}"
}

/**
 * Reviewed against LICENSE and vcpkg.json at each footer commit (#692).
 * Port licence expressions: microsoft/vcpkg at 84bab45d415d22042bd0b9081aea57f362da3f35;
 * avro-c: duckdb/vcpkg-duckdb-ports at f13de79b66345fe31d3f9c119517613023d194a8.
 * httpfs has no manifest baseline; its binary identifies OpenSSL 3.6.0 (Apache-2.0).
 * vcpkg-cmake is a build helper, not statically linked code. Transitive ports are outside this inventory.
 */
object DuckDbExtensionLicenses {
    private val openssl = DuckDbComponent("openssl", "Apache-2.0", "https://github.com/openssl/openssl")
    private val curl =
        DuckDbComponent(
            "curl",
            "curl AND ISC AND BSD-3-Clause",
            "https://github.com/curl/curl",
            "Copyright (c) 1996 - 2025, Daniel Stenberg, <daniel@haxx.se>, and many contributors, see the THANKS file.",
        )
    private val zlib =
        DuckDbComponent(
            "zlib",
            "Zlib",
            "https://github.com/madler/zlib",
            "(C) 1995-2022 Jean-loup Gailly and Mark Adler",
        )
    private val aws = DuckDbComponent("aws-sdk-cpp", "Apache-2.0", "https://github.com/aws/aws-sdk-cpp")
    private val avro =
        DuckDbComponent(
            "avro-c",
            "Apache-2.0",
            "https://github.com/apache/avro",
            "Apache Avro. Copyright 2010-2019 The Apache Software Foundation. " +
                "This product includes software developed at The Apache Software Foundation (https://www.apache.org/).",
        )
    private val roaring =
        DuckDbComponent(
            "roaring",
            "Apache-2.0 OR MIT",
            "https://github.com/RoaringBitmap/CRoaring",
            "Copyright 2016-2022 The CRoaring authors",
        )
    private val knownIds = setOf("Apache-2.0", "MIT", "curl", "ISC", "BSD-3-Clause", "Zlib")
    private val platforms = setOf("linux_amd64", "linux_arm64")

    /** Every distributed extension needs its own reviewed entry, including its direct linked dependencies. */
    fun inventory(): Map<String, DuckDbExtensionLicense> =
        mapOf(
            "httpfs" to license("httpfs", "827222f", "2018-2025", listOf(openssl, curl)),
            "aws" to license("aws", "efa54a9", "2018-2025", listOf(zlib, aws, openssl, curl)),
            "iceberg" to license("iceberg", "45163a28", "2018-2025", listOf(avro, curl, openssl, roaring, aws)),
            "avro" to license("avro", "f9d5902", "2018-2024", listOf(avro)),
        )

    /** Rejects unknown identifiers even when a caller supplies a permissive text resolver. */
    fun licenseIds(expression: String): List<String> =
        expression.split(Regex(" (?:AND|OR|WITH) ")).also { ids ->
            check(ids.isNotEmpty() && ids.all { it in knownIds }) { "Unknown DuckDB component licence: $expression" }
        }

    /** Validates version, names, full platform coverage, commit annotations and reviewed licences offline. */
    fun read(
        dockerfile: String,
        checksumFile: String,
        inventory: Map<String, DuckDbExtensionLicense>,
    ): List<DuckDbExtensionNotice> {
        val versions = Regex("(?m)^ARG DUCKDB_CORE_VERSION=([^\\s]+)$").findAll(dockerfile).toList()
        check(versions.size == 1) { "Dockerfile must declare exactly one DUCKDB_CORE_VERSION" }
        val version = versions.single().groupValues[1]
        check(version.matches(Regex("[0-9]+(?:\\.[0-9]+)+"))) { "Invalid DUCKDB_CORE_VERSION: $version" }
        val loops = Regex("for ext in ([a-z0-9_ ]+); do").findAll(dockerfile).toList()
        check(loops.size == 1) { "Dockerfile must declare exactly one DuckDB extension loop" }
        val names =
            loops
                .single()
                .groupValues[1]
                .split(' ')
                .filter(String::isNotBlank)
        check(names.isNotEmpty() && names.distinct().size == names.size) { "Duplicate or empty DuckDB extension names: $names" }
        val (commits, paths) = checksums(checksumFile, version)
        val expected = names.flatMap { name -> platforms.map { "v$version/$it/$name.duckdb_extension" } }.toSet()
        check(paths.size == paths.distinct().size) { "Duplicate DuckDB checksum entries: $paths" }
        check(paths.toSet() == expected) {
            "DuckDB checksum coverage mismatch: missing=${expected - paths.toSet()}, unexpected=${paths.toSet() - expected}"
        }
        check(commits.keys == names.toSet()) {
            "DuckDB commit annotations mismatch: missing=${names.toSet() - commits.keys}, unexpected=${commits.keys - names.toSet()}"
        }
        check(inventory.keys == names.toSet()) {
            "DuckDB licence inventory mismatch: missing=${names.toSet() - inventory.keys}, unexpected=${inventory.keys - names.toSet()}"
        }
        return names.sorted().map { name ->
            val license = inventory.getValue(name)
            check(license.commit == commits.getValue(name)) { "DuckDB licence commit mismatch for $name: ${commits.getValue(name)}" }
            licenseIds(license.spdx)
            license.components.forEach { licenseIds(it.spdx) }
            DuckDbExtensionNotice(name, version, license)
        }
    }

    private fun checksums(
        checksumFile: String,
        version: String,
    ): Pair<Map<String, String>, List<String>> {
        val commits = mutableMapOf<String, String>()
        val paths = mutableListOf<String>()
        checksumFile.lineSequence().filter(String::isNotBlank).forEach { line ->
            if (line.startsWith('#')) {
                if (line.contains("commit=")) {
                    val match = Regex("# ([a-z0-9_]+) commit=([a-f0-9]{7,40})").matchEntire(line)
                    check(match != null) { "Invalid DuckDB commit annotation: $line" }
                    val name = match.groupValues[1]
                    check(commits.put(name, match.groupValues[2]) == null) { "Duplicate DuckDB commit annotation: $name" }
                }
            } else {
                val match =
                    Regex("[a-f0-9]{64}  (v$version/(?:linux_amd64|linux_arm64)/[a-z0-9_]+\\.duckdb_extension)")
                        .matchEntire(line)
                check(match != null) { "Invalid DuckDB checksum entry for core v$version: $line" }
                paths.add(match.groupValues[1])
            }
        }
        return commits to paths
    }

    private fun license(
        name: String,
        commit: String,
        years: String,
        components: List<DuckDbComponent>,
    ): DuckDbExtensionLicense =
        DuckDbExtensionLicense(
            commit,
            "https://github.com/duckdb/duckdb-$name",
            "MIT",
            "Copyright $years Stichting DuckDB Foundation",
            components,
        )
}
