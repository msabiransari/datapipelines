/** A reviewed statically linked component; expressions preserve upstream alternatives and combined terms. */
data class DuckDbComponent(
    val name: String,
    val spdx: String,
    val source: String,
    val attribution: String = "",
    /** The direct dependencies that pull a transitive component in; empty for a direct one. */
    val via: String = "",
)

/** Extension licensing is bound to the exact source commit recorded in the binary footer. */
data class DuckDbExtensionLicense(
    val commit: String,
    val repository: String,
    val spdx: String,
    val copyright: String,
    val components: List<DuckDbComponent>,
)

/** One row of a committed closure file: a port or DuckDB part the pinned build compiles in, or a build-only tool. */
data class DuckDbClosureEntry(
    val port: String,
    val version: String,
    val spdx: String,
    val source: String,
    val kind: String,
)

/** The reconstructed closure of one extension (deploy/duckdb-extension-ports), bound to its footer commit. */
data class DuckDbClosure(
    val commit: String,
    val baseline: String,
    val overlays: String,
    val features: String,
    val entries: List<DuckDbClosureEntry>,
)

/** A non-POM distribution row, resolved entirely from committed inputs. */
data class DuckDbExtensionNotice(
    val name: String,
    val core: String,
    val license: DuckDbExtensionLicense,
    val closure: DuckDbClosure,
) {
    val coordinate: String get() = "duckdb-extension:$name:v$core+${license.commit}"
    val source: String get() = "${license.repository}/tree/${license.commit}"
}

/**
 * Reviewed for #747 against each component's licence and notice files at the pinned source in its
 * closure row: vcpkg ports at the merged-manifest resolution of DuckDB v1.5.5's extension build
 * (microsoft/vcpkg 84bab45d415d22042bd0b9081aea57f362da3f35 plus overlays), DuckDB's own third_party
 * code at v1.5.5 (every Linux loadable extension links duckdb_static), and code vendored inside ports.
 * Public-domain parts (lookup3, st.c) carry attribution only. The closure files are the reconstruction;
 * read() refuses any shipped closure row without an entry here and any entry the closure lacks.
 */
object DuckDbExtensionLicenses {
    private const val CORE = "https://github.com/duckdb/duckdb/tree/v1.5.5"
    private const val AMAZON = "Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved."
    private const val BUILD_ONLY = "build-only"
    private const val ROW_FIELDS = 5
    private val kinds = setOf("linked", "header-only", BUILD_ONLY)
    private val reviewed: Map<String, DuckDbComponent> =
        listOf(
            port(
                "avro-c",
                "Apache-2.0",
                "duckdb/duckdb-avro-c/tree/52bafc6a90fb6176b9b85ab2489ee5e49a5f208c",
                "Apache Avro. Copyright 2010 The Apache Software Foundation. This product includes software developed at " +
                    "The Apache Software Foundation (https://www.apache.org/). st.c: public domain hash table by Peter Moore @ UCB.",
            ),
            port("aws-c-auth", "Apache-2.0", "awslabs/aws-c-auth/tree/v0.9.4", "AWS C Auth. $AMAZON"),
            port("aws-c-cal", "Apache-2.0", "awslabs/aws-c-cal/tree/v0.9.13", "AWS C Cal. $AMAZON"),
            port(
                "aws-c-common",
                "Apache-2.0 AND MIT",
                "awslabs/aws-c-common/tree/v0.12.6",
                "AWS C Common. Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved. Includes cJSON 1.7.19, " +
                    "Copyright (c) 2009-2017 Dave Gamble and cJSON contributors, and libcbor 0.13.0, Copyright (c) 2014-2017 " +
                    "Pavel Kalvoda (MIT). lookup3: public domain, by Bob Jenkins.",
            ),
            port("aws-c-compression", "Apache-2.0", "awslabs/aws-c-compression/tree/v0.3.1", "AWS C Compression. $AMAZON"),
            port("aws-c-event-stream", "Apache-2.0", "awslabs/aws-c-event-stream/tree/v0.5.9", "AWS C Event Stream. $AMAZON"),
            port("aws-c-http", "Apache-2.0", "awslabs/aws-c-http/tree/v0.10.7", "AWS C Http. $AMAZON"),
            port(
                "aws-c-io",
                "Apache-2.0",
                "awslabs/aws-c-io/tree/v0.24.0",
                "AWS C Io. $AMAZON The source/pkcs11/v2.40/pkcs11.h header file is based on Public Domain content from " +
                    "https://github.com/latchset/pkcs11-headers",
            ),
            port("aws-c-mqtt", "Apache-2.0", "awslabs/aws-c-mqtt/tree/v0.13.3", "AWS C Mqtt. $AMAZON"),
            port("aws-c-s3", "Apache-2.0", "awslabs/aws-c-s3/tree/v0.11.3", AMAZON),
            port("aws-c-sdkutils", "Apache-2.0", "awslabs/aws-c-sdkutils/tree/v0.2.4", AMAZON),
            port("aws-checksums", "Apache-2.0", "awslabs/aws-checksums/tree/v0.2.7", ""),
            port(
                "aws-crt-cpp",
                "Apache-2.0",
                "awslabs/aws-crt-cpp/tree/v0.36.0",
                "AWS Crt Cpp. Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.",
            ),
            port(
                "aws-sdk-cpp",
                "Apache-2.0 AND MIT AND Zlib",
                "aws/aws-sdk-cpp/tree/1.11.702",
                "AWS SDK for C++. $AMAZON This product includes software developed by Amazon Technologies, Inc " +
                    "(http://www.amazon.com/). Includes TinyXml2, original code by Lee Thomason (www.grinninglizard.com), " +
                    "Zlib, modified by Amazon; and cJSON, Copyright (c) 2009-2017 Dave Gamble and cJSON contributors (MIT).",
            ),
            port(
                "curl",
                "curl AND ISC AND BSD-3-Clause",
                "curl/curl/tree/curl-8_17_0",
                "Copyright (c) 1996 - 2025, Daniel Stenberg, <daniel@haxx.se>, and many contributors, see the THANKS file.",
            ),
            port(
                "jansson",
                "MIT AND dtoa",
                "akheron/jansson/tree/v2.14.1",
                "Copyright (c) 2009-2024 Petri Lehtinen <petri@digip.org>. dtoa.c: the author of this software is David M. Gay; " +
                    "Copyright (c) 1991, 2000, 2001 by Lucent Technologies. lookup3: public domain, by Bob Jenkins.",
            ),
            port("liblzma", "0BSD", "tukaani-project/xz/tree/v5.8.1", "Copyright (C) The XZ Utils authors and contributors"),
            port("openssl", "Apache-2.0", "openssl/openssl/tree/openssl-3.6.0", ""),
            port("roaring", "Apache-2.0 OR MIT", "RoaringBitmap/CRoaring/tree/v4.5.0", "Copyright 2016-2022 The CRoaring authors"),
            port(
                "s2n",
                "Apache-2.0",
                "aws/s2n-tls/tree/v1.6.2",
                "s2n. Copyright 2014-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.",
            ),
            port("snappy", "BSD-3-Clause", "google/snappy/tree/1.2.2", "Copyright 2011, Google Inc."),
            port("zlib", "Zlib", "madler/zlib/tree/v1.3.1", "(C) 1995-2022 Jean-loup Gailly and Mark Adler"),
            DuckDbComponent("duckdb", "MIT", CORE, "Copyright 2018-2025 Stichting DuckDB Foundation"),
            core(
                "concurrentqueue",
                "(BSD-2-Clause OR BSL-1.0) AND Zlib",
                "Copyright (c) 2013-2016, Cameron Desrochers. Lightweight semaphore: Copyright (c) 2015 Jeff Preshing (zlib).",
            ),
            core("fast_float", "MIT", ""),
            core("fmt", "MIT", "Copyright (c) 2012 - present, Victor Zverovich"),
            core("fsst", "MIT", "Copyright (c) 2018-2020, CWI, TU Munich, FSU Jena"),
            core("httplib", "MIT", "Copyright (c) 2017 yhirose"),
            core(
                "hyperloglog",
                "MIT AND BSD-3-Clause",
                "Copyright (c) 2012 Art.sy, Inc. Redis HyperLogLog and SDS: Copyright (c) 2006-2015, Salvatore Sanfilippo; " +
                    "Copyright (c) 2015, Oran Agra; Copyright (c) 2015, Redis Labs, Inc.",
            ),
            core("jaro_winkler", "MIT", "Copyright © 2022 Max Bachmann"),
            core(
                "jemalloc",
                "BSD-2-Clause",
                "Copyright (C) 2002-present Jason Evans <jasone@canonware.com>. Copyright (C) 2007-2012 Mozilla Foundation. " +
                    "Copyright (C) 2009-present Facebook, Inc.",
            ),
            core(
                "libpg_query",
                "BSD-3-Clause AND PostgreSQL",
                "Copyright (c) 2015, Lukas Fittl <lukas@fittl.com>. Portions Copyright (c) 1996-2017, PostgreSQL Global " +
                    "Development Group; Portions Copyright (c) 1994, Regents of the University of California.",
            ),
            core("mbedtls", "Apache-2.0 OR GPL-2.0-or-later", "Copyright The Mbed TLS Contributors"),
            core(
                "miniz",
                "MIT",
                "Copyright 2013-2014 RAD Game Tools and Valve Software. Copyright 2010-2014 Rich Geldreich and Tenacious Software LLC.",
            ),
            core("pcg", "MIT", "Copyright (c) 2014-2017 Melissa O'Neill and PCG Project contributors"),
            core("pdqsort", "Zlib", "Copyright (c) 2021 Orson Peters <orsonpeters@gmail.com>"),
            core("re2", "BSD-3-Clause", "Copyright (c) 2009 The RE2 Authors. All rights reserved."),
            core("ska_sort", "BSL-1.0", "Copyright Malte Skarupke 2016."),
            core(
                "utf8proc",
                "MIT AND LicenseRef-utf8proc-Unicode-data",
                "Copyright (c) 2014-2019 by Steven G. Johnson, Jiahao Chen, Tony Kelman, Jonas Fonseca, " +
                    "and other contributors. Copyright (c) 2009, 2013 Public Software Group e. V., Berlin, Germany. " +
                    "Unicode data: Copyright (c) 1991-2007 Unicode, Inc.",
            ),
            core("vergesort", "MIT", "Copyright (c) 2015 Morwenn <morwenn29@hotmail.fr>"),
            core("yyjson", "MIT", "Copyright (c) 2020 YaoYuan <ibireme@gmail.com>"),
        ).associateBy { it.name }
    private val awsSdk =
        listOf(
            "aws-c-auth",
            "aws-c-cal",
            "aws-c-common",
            "aws-c-compression",
            "aws-c-event-stream",
            "aws-c-http",
            "aws-c-io",
            "aws-c-mqtt",
            "aws-c-s3",
            "aws-c-sdkutils",
            "aws-checksums",
            "aws-crt-cpp",
            "s2n",
        )
    private val avroC = listOf("jansson", "liblzma", "snappy")
    private val duckdbCore =
        listOf(
            "concurrentqueue",
            "fast_float",
            "fmt",
            "fsst",
            "httplib",
            "jaro_winkler",
            "jemalloc",
            "libpg_query",
            "mbedtls",
            "miniz",
            "pcg",
            "yyjson",
        ).map { "duckdb/$it" }
    private val knownIds =
        setOf(
            "0BSD",
            "Apache-2.0",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "BSL-1.0",
            "GPL-2.0-or-later",
            "ISC",
            "LicenseRef-utf8proc-Unicode-data",
            "MIT",
            "PostgreSQL",
            "Zlib",
            "curl",
            "dtoa",
        )
    private val platforms = setOf("linux_amd64", "linux_arm64")

    /** Every distributed extension needs its own reviewed entry, including every component its closure compiles in. */
    fun inventory(): Map<String, DuckDbExtensionLicense> =
        mapOf(
            "httpfs" to
                license(
                    "httpfs",
                    "827222f",
                    "2018-2025",
                    components("" to listOf("curl", "duckdb", "openssl"), "curl" to listOf("zlib"), "duckdb" to duckdbCore + "duckdb/re2"),
                ),
            "aws" to
                license(
                    "aws",
                    "efa54a9",
                    "2018-2025",
                    components(
                        "" to listOf("aws-sdk-cpp", "curl", "duckdb", "openssl", "zlib"),
                        "aws-sdk-cpp" to awsSdk,
                        "duckdb" to duckdbCore,
                    ),
                ),
            "iceberg" to
                license(
                    "iceberg",
                    "45163a28",
                    "2018-2025",
                    components(
                        "" to listOf("avro-c", "aws-sdk-cpp", "curl", "duckdb", "openssl", "roaring"),
                        "aws-sdk-cpp" to awsSdk,
                        "avro-c" to avroC,
                        "avro-c, aws-sdk-cpp, curl" to listOf("zlib"),
                        "duckdb" to
                            duckdbCore + listOf("hyperloglog", "pdqsort", "re2", "ska_sort", "utf8proc", "vergesort").map { "duckdb/$it" },
                    ),
                ),
            "avro" to
                license(
                    "avro",
                    "f9d5902",
                    "2018-2024",
                    components(
                        "" to listOf("avro-c", "duckdb"),
                        "avro-c" to avroC + "zlib",
                        "duckdb" to duckdbCore,
                    ),
                ),
        )

    /** Rejects unknown identifiers and unbalanced groups even when a caller supplies a permissive text resolver. */
    fun licenseIds(expression: String): List<String> {
        val ids = expression.split(Regex("[()]| (?:AND|OR|WITH) ")).map(String::trim).filter(String::isNotEmpty)
        val depths =
            expression.runningFold(0) { depth, char ->
                depth +
                    if (char == '(') {
                        1
                    } else if (char == ')') {
                        -1
                    } else {
                        0
                    }
            }
        check(ids.isNotEmpty() && ids.all { it in knownIds } && depths.all { it >= 0 } && depths.last() == 0) {
            "Unknown DuckDB component licence: $expression"
        }
        return ids
    }

    /** Validates version, names, platform coverage, commit annotations, closures and reviewed licences offline. */
    fun read(
        dockerfile: String,
        checksumFile: String,
        closures: Map<String, String>,
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
        mismatch("commit annotations", names.toSet(), commits.keys)
        mismatch("licence inventory", names.toSet(), inventory.keys)
        mismatch("closure files", names.toSet(), closures.keys)
        return names.sorted().map { name ->
            val license = inventory.getValue(name)
            check(license.commit == commits.getValue(name)) { "DuckDB licence commit mismatch for $name: ${commits.getValue(name)}" }
            licenseIds(license.spdx)
            license.components.forEach { licenseIds(it.spdx) }
            val closure = closure(name, closures.getValue(name))
            check(closure.commit == license.commit) { "DuckDB closure commit mismatch for $name: ${closure.commit} vs ${license.commit}" }
            reconcile(name, closure, license.components)
            DuckDbExtensionNotice(name, version, license, closure)
        }
    }

    private fun mismatch(
        what: String,
        names: Set<String>,
        keys: Set<String>,
    ) = check(keys == names) { "DuckDB $what mismatch: missing=${names - keys}, unexpected=${keys - names}" }

    private fun reconcile(
        name: String,
        closure: DuckDbClosure,
        components: List<DuckDbComponent>,
    ) {
        val byName = components.associateBy { it.name }
        check(byName.size == components.size) { "Duplicate DuckDB inventory component for $name" }
        val shipped = closure.entries.filter { it.kind != BUILD_ONLY }
        shipped.forEach { entry ->
            val component = byName[entry.port] ?: error("DuckDB closure entry without a reviewed licence for $name: ${entry.port}")
            check(component.spdx == entry.spdx && component.source == entry.source) {
                "DuckDB closure entry differs from its reviewed licence for $name: ${entry.port} (${entry.spdx}, ${entry.source})"
            }
        }
        val absent = (byName.keys - shipped.map { it.port }.toSet()).sorted()
        check(absent.isEmpty()) { "DuckDB inventory component not in closure for $name: ${absent.first()}" }
    }

    /** One header, then sorted unique rows; a build-only row follows the `# why:` line that justifies it. */
    private fun closure(
        name: String,
        text: String,
    ): DuckDbClosure {
        val lines = text.removeSuffix("\n").split('\n')
        val header =
            checkNotNull(
                Regex("# ([a-z0-9_]+) commit=([a-f0-9]{7,40}) baseline=([a-f0-9]{40}) overlays=(\\S+) features=(\\S+)")
                    .matchEntire(lines.first())
                    ?.takeIf { it.groupValues[1] == name && text.endsWith("\n") },
            ) { "Invalid DuckDB closure header for $name: ${lines.first()}" }
        val entries = mutableListOf<DuckDbClosureEntry>()
        lines.drop(1).forEachIndexed { index, line ->
            if (line.startsWith("# why: ")) return@forEachIndexed
            val fields = line.split('\t')
            check(fields.size == ROW_FIELDS && fields.none(String::isBlank)) { "Invalid DuckDB closure row for $name: $line" }
            val (port, version, spdx) = fields
            val (source, kind) = fields.takeLast(2)
            check(kind in kinds) { "Invalid DuckDB closure row for $name: $line" }
            check(kind != BUILD_ONLY || lines[index].startsWith("# why: $port ")) {
                "DuckDB build-only row without a why comment for $name: $port"
            }
            entries.add(DuckDbClosureEntry(port, version, spdx, source, kind))
        }
        val ports = entries.map { it.port }
        check(ports == ports.distinct().sorted()) { "DuckDB closure rows must be sorted and unique for $name" }
        val (_, commit, baseline) = header.groupValues.drop(1)
        val (overlays, features) = header.groupValues.takeLast(2)
        return DuckDbClosure(commit, baseline, overlays, features, entries)
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

    private fun port(
        name: String,
        spdx: String,
        tree: String,
        attribution: String,
    ) = DuckDbComponent(name, spdx, "https://github.com/$tree", attribution)

    private fun core(
        directory: String,
        spdx: String,
        attribution: String,
    ) = DuckDbComponent("duckdb/$directory", spdx, "$CORE/third_party/$directory", attribution)

    private fun components(vararg groups: Pair<String, List<String>>): List<DuckDbComponent> =
        groups.flatMap { (via, names) ->
            names.map { name -> checkNotNull(reviewed[name]) { "No reviewed DuckDB component: $name" }.copy(via = via) }
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
