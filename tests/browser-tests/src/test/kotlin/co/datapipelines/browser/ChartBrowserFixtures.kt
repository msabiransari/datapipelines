package co.datapipelines.browser

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The bar family's browser fixtures (#860 A.5; owner ruling 2026-11-08: one browser class per chart family, its fixtures
 * per family). The rows and the expectations are the 854 research corpus's web fixtures, copied byte-equal under
 * `src/test/resources/chart-fixtures/` (their sha list is in the lane's evidence). The specs of `bar-vertical`,
 * `bar-horizontal`, `bar-grouped`, `bar-stacked` and `bar-normalized` equal the product's five valid examples
 * (`modules/visualization/src/test/resources/chart-examples/valid`), and the `markup-title` example is `bar-vertical`
 * with its title replaced ([withTitle]).
 *
 * Every `chart` document a browser test builds comes from here, so a later family (S2's line) adds its own fixtures and
 * runs ONE class. The board seeds of the shared suites take their chart body from [barBody]; no chart document lives in
 * `DashboardBrowserSuite` or `VisualizationBrowserSuite`.
 */
object ChartBrowserFixtures {
    /** The fourteen copied fixtures, by file name. The corpus's tampered fixture is not copied: no browser proof reads it. */
    val NAMES: List<String> =
        listOf(
            "bar-vertical",
            "bar-horizontal",
            "bar-grouped",
            "bar-stacked",
            "bar-normalized",
            "bar-negatives",
            "bar-nulls",
            "bar-single",
            "bar-nodata",
            "bar-bigint",
            "bar-markup",
            "bar-long-labels",
            "bar-utc-date",
            "bar-utc-timestamp",
        )

    /** The fixtures whose spec is one of the product's five valid examples (the screenshots' set). */
    val EXAMPLES: List<String> = NAMES.take(EXAMPLE_COUNT)

    /** Nulls are written: a chart's `sort: null` and `stack: null` are values, and a fixture row's null cell is data. */
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    /**
     * One fixture: its chart document (the corpus's `spec`), its rows (the corpus's column-major `bindings` turned into
     * rows) and what the corpus measured in a browser (`expect`: `bars`, `categories`, `hover`, `label_texts`).
     */
    class Fixture(
        val name: String,
        val spec: JsonObject,
        val rows: List<JsonObject>,
        val expect: JsonObject,
    ) {
        /** The expected drawn bars (a null measure cell is a gap, not a bar). */
        val bars: Int get() = expect.get("bars").asInt

        /** The band axis's tick labels, in reading order (left to right, or top to bottom). */
        val categories: List<String> get() = expect.getAsJsonArray("categories").map { it.asString }

        val vertical: Boolean get() = expect.get("vertical").asBoolean

        /** The same fixture with the in-chart title [text] (the `markup-title` example is `bar-vertical` so). */
        fun withTitle(text: String): Fixture {
            val copy = spec.deepCopy()
            copy.getAsJsonObject("config").getAsJsonObject("title").addProperty("text", text)
            return Fixture(name, copy, rows, expect)
        }
    }

    /** Reads [name] off the classpath. */
    fun fixture(name: String): Fixture {
        val text =
            checkNotNull(ChartBrowserFixtures::class.java.getResourceAsStream("/chart-fixtures/$name.json")) {
                "no chart fixture $name on the test classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JsonParser.parseString(text).asJsonObject
        return Fixture(name, root.getAsJsonObject("spec"), rows(root.getAsJsonObject("bindings")), root.getAsJsonObject("expect"))
    }

    /**
     * The REST create body of [fixture] at [name]: the spec's renderer, config, title and any `bindings` unchanged, its
     * one input's columns PRUNED to the encoded fields (the corpus's rows carry only those, and an unencoded non-nullable
     * column would refuse every row), and one test case over the rows — `mark_count` equal to the corpus's bar count, or
     * `no_data` for the empty fixture. A row whose category or series is null is dropped: the save refuses a nullable
     * band or series column (`chart.category.nullable`), so the runtime never receives one from a saved document; the
     * drawn bars are the same, since a null category draws nothing.
     */
    fun document(
        name: String,
        fixture: Fixture,
    ): String {
        val spec = fixture.spec
        val (input, declared) = spec.getAsJsonObject("inputs").entrySet().single()
        val encoded = encodedFields(spec.getAsJsonObject("config"))
        val columns = JsonArray()
        declared.asJsonObject
            .getAsJsonArray("columns")
            .filter { it.asJsonObject.get("name").asString in encoded }
            .forEach(columns::add)
        val notNull = columns.map { it.asJsonObject }.filterNot { it.get("nullable")?.asBoolean ?: false }.map { it.get("name").asString }
        val rows = JsonArray()
        fixture.rows.filter { row -> notNull.all { row.get(it) != null && !row.get(it).isJsonNull } }.forEach(rows::add)
        val assertions = JsonArray()
        if (fixture.bars == 0) {
            assertions.add(assertion("no_data", null))
        } else {
            assertions.add(assertion("rendered", null))
            assertions.add(assertion("mark_count", fixture.bars))
        }
        val case =
            JsonObject().apply {
                addProperty("name", fixture.name)
                add("fixtures", JsonObject().apply { add(input, rows) })
                add("assertions", assertions)
            }
        val document =
            JsonObject().apply {
                addProperty("name", name)
                add("display_name", spec.get("display_name"))
                spec.get("description")?.let { add("description", it) }
                add("renderer", spec.get("renderer"))
                add("inputs", JsonObject().apply { add(input, JsonObject().apply { add("columns", columns) }) })
                add("config", spec.get("config"))
                spec.get("bindings")?.let { add("bindings", it) }
                add("tests", JsonObject().apply { add("cases", JsonArray().apply { add(case) }) })
            }
        return gson.toJson(document)
    }

    /**
     * A board seed's chart body (seeded RELEASED by SQL, so it is the document the runtime reads): a vertical bar over
     * the [band] column of [bandType], the sum of the INTEGER [value] column — or, with no [value], the count of rows
     * per category (the seeds whose pipeline returns one text column). [axisTitles] `false` authors both axis titles
     * `null` (no title line), the figure main's Plotly seeds drew; the default draws the record's defaults (field name,
     * "Sum of …").
     */
    fun barBody(
        band: String,
        bandType: String,
        value: String?,
        displayName: String = "Chart",
        axisTitles: Boolean = true,
    ): String {
        val columns =
            JsonArray().apply {
                add(column(band, bandType))
                if (value != null) add(column(value, "INTEGER"))
            }
        val y =
            JsonObject().apply {
                addProperty("type", "quantitative")
                if (value != null) addProperty("field", value)
                addProperty("aggregate", if (value != null) "sum" else "count")
                addProperty("format", "integer")
                if (!axisTitles) add("title", JsonNull.INSTANCE)
            }
        val body =
            JsonObject().apply {
                addProperty("display_name", displayName)
                add("renderer", renderer())
                add("inputs", JsonObject().apply { add("main", JsonObject().apply { add("columns", columns) }) })
                add("config", barConfig(band, y, bandTitle = axisTitles))
                add(
                    "bindings",
                    JsonObject().apply {
                        addProperty(band, band)
                        if (value != null) addProperty(value, value)
                    },
                )
            }
        return gson.toJson(body)
    }

    /**
     * A REST create body: a vertical bar over the [band] column (name to type, never nullable), the sum of the [value]
     * column, categories in data order, in-chart [title] when given, over the one input [input]. Each of [cases] is a
     * test case of its rows: `mark_count` equal to its distinct categories, or `no_data` when it has none. No cases
     * leaves `tests` out (a document a page only pins).
     */
    fun barDocument(
        name: String,
        displayName: String,
        band: Pair<String, String>,
        value: Pair<String, String>,
        input: String = "sales",
        description: String = "",
        title: String? = null,
        cases: List<Pair<String, List<Map<String, Any>>>> = emptyList(),
    ): String {
        val y =
            JsonObject().apply {
                addProperty("type", "quantitative")
                addProperty("field", value.first)
                addProperty("aggregate", "sum")
            }
        val columns =
            JsonArray().apply {
                add(column(band.first, band.second))
                add(column(value.first, value.second))
            }
        val document =
            JsonObject().apply {
                addProperty("name", name)
                addProperty("display_name", displayName)
                addProperty("description", description)
                add("renderer", renderer())
                add("inputs", JsonObject().apply { add(input, JsonObject().apply { add("columns", columns) }) })
                add("config", barConfig(band.first, y, sort = JsonNull.INSTANCE, title = title))
                if (cases.isNotEmpty()) {
                    val tests = JsonArray()
                    cases.forEach { (caseName, rows) -> tests.add(barCase(caseName, input, band.first, rows)) }
                    add("tests", JsonObject().apply { add("cases", tests) })
                }
            }
        return gson.toJson(document)
    }

    /**
     * The Visualizations pages' chart (REST-created, a DRAFT): units per month over one input, two test cases — three
     * months (three bars) and an empty one (`no_data`).
     */
    fun workspaceDocument(name: String): String =
        barDocument(
            name,
            "Units by month",
            "month" to "STRING",
            "units" to "INTEGER",
            description = "the #399 browser fixture",
            title = "Units by month",
            cases = listOf(WORKSPACE_DATA_CASE to THREE_MONTHS, WORKSPACE_EMPTY_CASE to emptyList()),
        )

    /** Units per month over three months (Jan 3, Feb 5, Mar 2): three bars. */
    val THREE_MONTHS: List<Map<String, Any>> =
        listOf(
            mapOf("month" to "Jan", "units" to 3),
            mapOf("month" to "Feb", "units" to 5),
            mapOf("month" to "Mar", "units" to 2),
        )

    /** The workspace chart's data case and the bars it draws. */
    const val WORKSPACE_DATA_CASE = "three months"
    const val WORKSPACE_EMPTY_CASE = "empty"
    const val WORKSPACE_BARS = 3

    private const val EXAMPLE_COUNT = 5

    private fun renderer(): JsonObject =
        JsonObject().apply {
            addProperty("kind", "chart")
            addProperty("version", "1")
        }

    private fun column(
        name: String,
        type: String,
    ): JsonObject =
        JsonObject().apply {
            addProperty("name", name)
            addProperty("type", type)
            addProperty("nullable", false)
        }

    private fun barConfig(
        band: String,
        y: JsonObject,
        sort: JsonElement = com.google.gson.JsonPrimitive("ascending"),
        title: String? = null,
        bandTitle: Boolean = true,
    ): JsonObject =
        JsonObject().apply {
            add("mark", JsonObject().apply { addProperty("type", "bar") })
            add(
                "encoding",
                JsonObject().apply {
                    add(
                        "x",
                        JsonObject().apply {
                            addProperty("field", band)
                            addProperty("type", "nominal")
                            add("sort", sort)
                            if (!bandTitle) add("title", JsonNull.INSTANCE)
                        },
                    )
                    add("y", y)
                },
            )
            if (title != null) add("title", JsonObject().apply { addProperty("text", title) })
        }

    private fun assertion(
        kind: String,
        equals: Int?,
    ): JsonObject =
        JsonObject().apply {
            addProperty("kind", kind)
            if (equals != null) addProperty("equals", equals)
        }

    /** A test case of [rows]: `mark_count` equal to the distinct values of [band], or `no_data` when there are none. */
    private fun barCase(
        name: String,
        input: String,
        band: String,
        rows: List<Map<String, Any>>,
    ): JsonObject {
        val marks = rows.map { it[band] }.distinct().size
        val assertions =
            if (marks == 0) {
                arrayOf(assertion("no_data", null))
            } else {
                arrayOf(assertion("rendered", null), assertion("mark_count", marks))
            }
        return case(name, input, gson.toJsonTree(rows).asJsonArray, *assertions)
    }

    private fun case(
        name: String,
        input: String,
        rows: JsonArray,
        vararg assertions: JsonObject,
    ): JsonObject =
        JsonObject().apply {
            addProperty("name", name)
            add("fixtures", JsonObject().apply { add(input, rows) })
            add("assertions", JsonArray().apply { assertions.forEach(::add) })
        }

    /** The fields of the encoding's x, y and color channels. */
    private fun encodedFields(config: JsonObject): Set<String> {
        val encoding = config.getAsJsonObject("encoding")
        return listOf("x", "y", "color")
            .mapNotNull { encoding.getAsJsonObject(it)?.get("field") }
            .filterNot { it.isJsonNull }
            .map { it.asString }
            .toSet()
    }

    /** The corpus's column-major bindings (`{field: [values]}`) as rows, in order. */
    private fun rows(bindings: JsonObject): List<JsonObject> {
        val fields = bindings.entrySet().map { it.key to it.value.asJsonArray }
        val count = fields.maxOfOrNull { it.second.size() } ?: 0
        return (0 until count).map { i ->
            JsonObject().apply { fields.forEach { (field, values) -> add(field, values[i]) } }
        }
    }
}
