/**
 * POM spellings observed in the locked production graph, reviewed for #677 on 2026-10-24.
 * Keys are exact names, deliberately not fuzzy matches. Every active spelling must be used.
 * Texts are vendored from SPDX's canonical license-list-data; builds never fetch them.
 */
object ThirdPartyLicenseAllowlist {
    // SPDX text snapshot: https://github.com/spdx/license-list-data/tree/31ba1a50e5397e00a304dbadc76531740e89ee48/text

    /** Sources of the verbatim texts in gradle/licenses; exceptions have their own text. */
    fun source(id: String): String =
        if (id == "LicenseRef-Oracle-FUTC") {
            "https://www.oracle.com/downloads/licenses/oracle-free-license.html"
        } else {
            "https://spdx.org/licenses/$id.html"
        }

    /** Optional-driver spellings are active only for the corresponding build flag. */
    fun mappings(mysql: Boolean, oracle: Boolean): Map<String, String> = buildMap {
        // Spring, Jackson, Kotlin, Netty and the other Apache-licensed runtime components.
        listOf(
            "Apache License 2.0", "Apache License Version 2.0", "Apache License, Version 2.0",
            "Apache License, version 2.0", "Apache-2.0", "The Apache License, Version 2.0",
            "The Apache Software License, Version 2.0",
        ).forEach { put(it, "Apache-2.0") }
        // CommonMark and PostgreSQL declare this spelling in their POMs.
        put("BSD-2-Clause", "BSD-2-Clause")
        put("BSD-3-Clause", "BSD-3-Clause") // ASM.
        // SPDX explicitly identifies EDL 1.0 as BSD-3-Clause; Jakarta's two spellings.
        put("EDL 1.0", "BSD-3-Clause")
        put("Eclipse Distribution License - v 1.0", "BSD-3-Clause")
        put("EPL 1.0", "EPL-1.0") // H2's second declared licence.
        put("EPL 2.0", "EPL-2.0") // Jakarta.
        put("EPL-2.0", "EPL-2.0") // Logback.
        put("GNU Lesser General Public License, Version 3", "LGPL-3.0-only") // Argon2.
        put("GPL2 w/ CPE", "GPL-2.0-only WITH Classpath-exception-2.0") // Jakarta.
        put("LGPL-2.1-only", "LGPL-2.1-only") // Logback.
        put("LGPL-2.1-or-later", "LGPL-2.1-or-later") // JNA's alternative licence.
        listOf("MIT", "MIT License", "The MIT License").forEach { put(it, "MIT") }
        put("MIT-0", "MIT-0") // Reactive Streams.
        put("MPL 2.0", "MPL-2.0") // H2's first declared licence.
        put("Public Domain, per Creative Commons CC0", "CC0-1.0") // HdrHistogram, LatencyUtils.
        put("Universal Permissive License, Version 1.0", "UPL-1.0") // GraalVM and both isolates.
        if (mysql) {
            put(
                "The GNU General Public License, v2 with Universal FOSS Exception, v1.0",
                "GPL-2.0-only WITH Universal-FOSS-exception-1.0",
            )
        }
        if (oracle) {
            // FUTC has no SPDX-listed identifier. A local SPDX LicenseRef preserves its identity.
            put("Oracle Free Use Terms and Conditions (FUTC)", "LicenseRef-Oracle-FUTC")
        }
    }
}
