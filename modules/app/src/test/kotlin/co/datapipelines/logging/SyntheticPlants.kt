package co.datapipelines.logging

/**
 * One synthetic text a logger call might carry (#337-b): [raw] is what the code logs, [scrubbed]
 * is exactly what §9.2 says must come out — the delimiters and every non-secret byte preserved,
 * the whole secret value replaced by the mask. A plant whose [raw] and [scrubbed] are equal is a
 * KEEP plant: a never-redacted or non-matching key the redactor must leave alone.
 */
internal data class Plant(
    val label: String,
    val raw: String,
    val scrubbed: String,
)

/**
 * The redaction corpus shared by the pure scrubber test, the in-process encoder tests and the
 * child-JVM packaged-output proof, so every layer is held to the same expected text. No value is a
 * real credential: every secret is a `planted-secret-` marker (or the one all-digit scalar below).
 *
 * The raw strings are Kotlin raw literals on purpose — a backslash here is a backslash in the log
 * text, which is the whole point of the escaped-quote plants.
 */
internal object SyntheticPlants {
    /** The markers that must never survive in any output. */
    val SECRET_MARKERS: List<String> = listOf("planted-secret-", "998877665544")

    val REDACTED: List<Plant> =
        listOf(
            Plant("json upper-case key", """{"PASSWORD":"planted-secret-upper"}""", """{"PASSWORD":"***"}"""),
            Plant(
                "json mixed-case compound key",
                """{"Db_PassWord": "planted-secret-mixed"}""",
                """{"Db_PassWord": "***"}""",
            ),
            Plant(
                "json mixed-case key between never-redacted neighbours",
                """{"user_id":"keep-1","ApI_KeY":"planted-secret-apikey","node_id":"keep-2"}""",
                """{"user_id":"keep-1","ApI_KeY":"***","node_id":"keep-2"}""",
            ),
            Plant(
                "json dotted key",
                """{"spring.datasource.Password":"planted-secret-dotted"}""",
                """{"spring.datasource.Password":"***"}""",
            ),
            Plant(
                "json escaped quote first",
                """{"password":"\"planted-secret-first"}""",
                """{"password":"***"}""",
            ),
            Plant(
                "json escaped quote later",
                """{"password":"planted-secret-a\"planted-secret-b"} tail""",
                """{"password":"***"} tail""",
            ),
            Plant(
                "json escaped backslash at the end",
                """{"password":"planted-secret-c\\"} tail""",
                """{"password":"***"} tail""",
            ),
            Plant(
                "json escaped backslash then escaped quote",
                """{"password":"planted-secret-d\\\"planted-secret-e"} tail""",
                """{"password":"***"} tail""",
            ),
            Plant(
                "json unquoted scalar",
                """{"secret":998877665544,"user_id":"keep-3"}""",
                """{"secret":"***","user_id":"keep-3"}""",
            ),
            Plant(
                "assignment upper-case key",
                "PASSWORD=planted-secret-upper next",
                "PASSWORD=*** next",
            ),
            Plant(
                "assignment escaped quote first",
                """password="\"planted-secret-f" tail""",
                "password=*** tail",
            ),
            Plant(
                "assignment escaped quote later",
                """password="planted-secret-g\"planted-secret-h" tail""",
                "password=*** tail",
            ),
            Plant(
                "assignment escaped backslash at the end",
                """password="planted-secret-i\\" tail""",
                "password=*** tail",
            ),
            Plant(
                "assignment single-quoted value",
                "secret='planted-secret-j and k' tail",
                "secret=*** tail",
            ),
            Plant(
                "assignment unterminated quote runs to the end of the line",
                "password=\"planted-secret-l never closed",
                "password=***",
            ),
            Plant(
                "assignment inside delimiters",
                "(password=planted-secret-m),next=1",
                "(password=***),next=1",
            ),
        )

    /** Never-redacted and non-matching keys: the redactor must return these byte-for-byte. */
    val KEPT: List<Plant> =
        listOf(
            keep(
                "never-redacted json keys",
                """{"correlation_id":"c-1","execution_id":"e-1","pipeline_id":"p-1",""" +
                    """"node_id":"n-1","datasource_name":"d-1","user_id":"u-1"}""",
            ),
            keep("non-matching json keys", """{"passwordless":"keep-4","secrets":"keep-5"}"""),
            keep("non-matching assignment keys", "passwordless=keep-6 pipeline_id=p-2"),
        )

    private fun keep(
        label: String,
        text: String,
    ): Plant = Plant(label, text, text)
}
