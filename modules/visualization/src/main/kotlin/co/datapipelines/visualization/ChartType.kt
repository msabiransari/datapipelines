package co.datapipelines.visualization

/**
 * The chart types (enums.md §63; design record 2026-11-14-js-rendered-charts §4.2): one entry per type the engine
 * draws, wire value = the document's `config.mark.type`. Each entry carries its channel descriptor as DATA (O-12): per
 * member of `config.encoding`, whether it is required and the node it takes. Derived by name, never configured: the
 * schema file, the engine's template file and the fixtures directory.
 *
 * The registry guard (R-REG, `modules/application` test `ChartTypeRegistryTest`) holds six sets equal — these wire
 * values, the schema files' `mark.type` constants, the skeleton's `familyConfig` branches, the template files, the
 * names `JsRendered.types()` returns in the sandbox and this section of enums.md — and compares [channels] node for
 * node with the descriptor the template registers. The save rules, the served columns, the derived bindings and
 * [ChartSchemas.FILES] read it through [ChartPositions] (lane F2, #986).
 */
enum class ChartType(
    val wire: String,
    val channels: List<ChartChannel>,
    /** The scale policy of the type's marks (record §9 G2): a shape outside any layer object takes this row. */
    val policy: ChartMarkPolicy,
) {
    BAR(
        "bar",
        listOf(
            ChartChannel("x", required = true, node = ChartChannelNodes.bandOrValue),
            ChartChannel("y", required = true, node = ChartChannelNodes.bandOrValue),
            ChartChannel(
                "color",
                required = false,
                node =
                    ChannelNode.OneOf(
                        listOf(
                            ShapeAlternative("series", listOf("nominal", "ordinal")),
                            ShapeAlternative("shade", listOf("quantitative")),
                        ),
                    ),
            ),
            ChartChannel("tooltip", required = false, node = ChartChannelNodes.tooltip),
        ),
        ChartMarkPolicy.BAR,
    ),

    /** A line per series over a band or a continuous axis (§4.5 row `line`; lane F5, #989). */
    LINE("line", ChartChannelNodes.lineFamily, ChartMarkPolicy.LINE),

    /** As [LINE], filled down to zero or to the series below it (§4.5 row `area`; lane F5, #989). */
    AREA("area", ChartChannelNodes.lineFamily, ChartMarkPolicy.AREA),

    /**
     * One point per row over two continuous axes, coloured by series or by value, sized by a third measure (§4.5 row
     * `scatter`; lane T1, #1062). A scatter with `size` is the bubble chart.
     */
    SCATTER("scatter", ChartChannelNodes.scatter, ChartMarkPolicy.POINT),

    /** Shares of a whole, drawn as a ring by default (§4.5; lane #1068). */
    PIE("pie", ChartChannelNodes.pie, ChartMarkPolicy.AREA),

    /** Two to four layers over one category band, with one or two value axes (§4.5; #1069). */
    COMBO("combo", ChartChannelNodes.combo, ChartMarkPolicy.LINE),

    /** Two category bands and a shade per cell; BAR names the rectangle policy, unused by band and shade shapes. */
    HEATMAP("heatmap", ChartChannelNodes.heatmap, ChartMarkPolicy.BAR),

    /** Numeric rows binned on nice edges, optionally stacked by series (§4.5; #1071). */
    HISTOGRAM("histogram", ChartChannelNodes.histogram, ChartMarkPolicy.AREA),

    /** Quartiles and whiskers per category, with outside points (§4.5 box; #1072). */
    BOX("box", ChartChannelNodes.box, ChartMarkPolicy.POINT),

    /** Running changes over a category band, with an optional closing total (§4.5; #1073). */
    WATERFALL("waterfall", ChartChannelNodes.waterfall, ChartMarkPolicy.AREA),

    /** Centred stage bars, one value per stage (§4.5; #1074). */
    FUNNEL("funnel", ChartChannelNodes.funnel, ChartMarkPolicy.AREA),

    /** One aggregate on an authored range, drawn as an open arc (§4.5; #1075). */
    GAUGE("gauge", listOf(ChartChannel("value", required = true, node = ChannelNode.Shape("value"))), ChartMarkPolicy.LINE),

    /** Nested leaf rectangles, with a key-only legend (§4.5 treemap; #1078). */
    TREEMAP("treemap", ChartChannelNodes.treemap, ChartMarkPolicy.AREA),
    ;

    /** The schema file under [ChartSchemas.RESOURCE_DIR]. */
    val schemaFile: String get() = "$wire.schema.json"

    /** The template file under the engine's directory (`co/datapipelines/jsrendered/engine/`). */
    val templateFile: String get() = "types/$wire.js"

    /** The fixtures directory under the engine module's `src/test/fixtures/`. */
    val fixturesDirectory: String get() = wire

    init {
        val keys = channels.map { it.key }
        require(keys.size == keys.toSet().size) { "$wire: a channel key appears twice" }
    }

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        fun fromWire(value: String): ChartType? = entries.firstOrNull { it.wire == value }
    }
}

/** One member of `config.encoding`: its key, whether a document must carry it, and the node it takes (§4.2). */
data class ChartChannel(
    val key: String,
    val required: Boolean,
    val node: ChannelNode,
) {
    /** The descriptor as the engine registers it: `{key, required, node}`. */
    fun wire(): Map<String, Any> = mapOf("key" to key, "required" to required, "node" to node.wire())
}

/** One alternative of a [ChannelNode.OneOf]: a shape of §4.4 and the `type` values it takes here. */
data class ShapeAlternative(
    val shape: String,
    val types: List<String>,
) {
    init {
        require(types.isNotEmpty()) { "the alternative $shape takes no type" }
    }
}

/**
 * A node of a channel descriptor (§4.2's four): a shape, a choice of shapes selected by the object's own `type`, a
 * JSON array of nodes, or a closed object of named nodes. [wire] is the engine's JSON form, node for node.
 */
sealed interface ChannelNode {
    fun wire(): Map<String, Any>

    /** One shape of §4.4 by name. */
    data class Shape(
        val name: String,
    ) : ChannelNode {
        override fun wire(): Map<String, Any> = mapOf("node" to "shape", "name" to name)
    }

    /** One of several shapes; the `type` sets are disjoint, so a document's `type` selects exactly one (R-REG). */
    data class OneOf(
        val alternatives: List<ShapeAlternative>,
    ) : ChannelNode {
        init {
            require(alternatives.size >= 2) { "a choice has at least two alternatives" }
            val all = alternatives.flatMap { it.types }
            require(all.size == all.toSet().size) { "two alternatives of a choice share a type value: $all" }
        }

        override fun wire(): Map<String, Any> =
            mapOf("node" to "oneOf", "of" to alternatives.map { mapOf("shape" to it.shape, "types" to it.types) })
    }

    /** A JSON array of [min] to [max] nodes. */
    data class ListOf(
        val min: Int,
        val max: Int,
        val of: ChannelNode,
    ) : ChannelNode {
        init {
            require(min in 0..max) { "a list's bounds are 0 <= min <= max" }
        }

        override fun wire(): Map<String, Any> = mapOf("node" to "list", "min" to min, "max" to max, "of" to of.wire())
    }

    /**
     * A closed object whose named members are nodes. [layer], when set, declares the object a layer (record §9 G2, "The
     * layer policy"): it names the member that holds the layer's mark, and every shape found inside the object takes
     * that mark's [ChartMarkPolicy]. [wire] carries `layer` only when it is set, so a descriptor without layers keeps its
     * registered form byte for byte.
     */
    data class ObjectOf(
        val members: Map<String, ChannelNode>,
        val layer: String? = null,
    ) : ChannelNode {
        init {
            require(members.isNotEmpty()) { "an object node names at least one member" }
            require(layer == null || layer !in members) { "the layer's mark member $layer is not a node member" }
        }

        override fun wire(): Map<String, Any> {
            val base = mapOf("node" to "object", "members" to members.mapValues { it.value.wire() })
            return if (layer == null) base else base + ("layer" to layer)
        }
    }
}

/** Nodes more than one type shares (§4.2's examples). */
private object ChartChannelNodes {
    val histogram: List<ChartChannel> =
        listOf(
            ChartChannel("x", required = true, node = ChannelNode.Shape("position")),
            ChartChannel("y", required = true, node = ChannelNode.Shape("count")),
            ChartChannel("color", required = false, node = ChannelNode.Shape("series")),
        )

    val combo: List<ChartChannel> =
        listOf(
            ChartChannel("x", required = true, node = ChannelNode.Shape("band")),
            ChartChannel(
                "layers",
                required = true,
                node =
                    ChannelNode.ListOf(
                        min = 2,
                        max = 4,
                        of = ChannelNode.ObjectOf(members = mapOf("y" to ChannelNode.Shape("value")), layer = "mark"),
                    ),
            ),
            ChartChannel("tooltip", required = false, node = ChannelNode.ListOf(0, 6, ChannelNode.Shape("detail"))),
        )

    val treemap: List<ChartChannel> =
        listOf(
            ChartChannel("path", required = true, node = ChannelNode.ListOf(1, 3, ChannelNode.Shape("level"))),
            ChartChannel("size", required = true, node = ChannelNode.Shape("value")),
            ChartChannel(
                "color",
                required = false,
                node =
                    ChannelNode.OneOf(
                        listOf(
                            ShapeAlternative("series", listOf("nominal", "ordinal")),
                            ShapeAlternative("shade", listOf("quantitative")),
                        ),
                    ),
            ),
        )

    val funnel: List<ChartChannel> =
        listOf(
            ChartChannel("stage", required = true, node = ChannelNode.Shape("band")),
            ChartChannel("value", required = true, node = ChannelNode.Shape("value")),
        )

    val waterfall: List<ChartChannel> =
        listOf(
            ChartChannel("x", required = true, node = ChannelNode.Shape("band")),
            ChartChannel("y", required = true, node = ChannelNode.Shape("value")),
        )

    val box: List<ChartChannel> =
        listOf(
            ChartChannel("x", required = false, node = ChannelNode.Shape("band")),
            ChartChannel("y", required = true, node = ChannelNode.Shape("position")),
        )

    val heatmap: List<ChartChannel> =
        listOf(
            ChartChannel("x", required = true, node = ChannelNode.Shape("band")),
            ChartChannel("y", required = true, node = ChannelNode.Shape("band")),
            ChartChannel("color", required = true, node = ChannelNode.Shape("shade")),
        )

    val bandOrValue: ChannelNode =
        ChannelNode.OneOf(
            listOf(
                ShapeAlternative("band", listOf("nominal", "ordinal", "temporal")),
                ShapeAlternative("value", listOf("quantitative")),
            ),
        )

    /** `tooltip`: `list(0, 6, shape(detail))`. */
    val tooltip: ChannelNode = ChannelNode.ListOf(min = 0, max = 6, of = ChannelNode.Shape("detail"))

    /**
     * The line family's channels (§4.2's `line` example): `x` is a band (nominal, ordinal) or a position (temporal,
     * quantitative) — a temporal `x` has ONE form, so `temporal` is not a band's here — `y` a value, `color` a series.
     */
    val lineFamily: List<ChartChannel> =
        listOf(
            ChartChannel(
                "x",
                required = true,
                node =
                    ChannelNode.OneOf(
                        listOf(
                            ShapeAlternative("band", listOf("nominal", "ordinal")),
                            ShapeAlternative("position", listOf("temporal", "quantitative")),
                        ),
                    ),
            ),
            ChartChannel("y", required = true, node = ChannelNode.Shape("value")),
            ChartChannel("color", required = false, node = ChannelNode.Shape("series")),
            ChartChannel("tooltip", required = false, node = tooltip),
        )

    /**
     * Scatter's channels (§4.5 row `scatter`): `x` and `y` positions (the schema narrows `y` to quantitative, brief 1062
     * D2), `color` a series or a shade, `size` the shape `size`, `tooltip` the shared list.
     */
    val scatter: List<ChartChannel> =
        listOf(
            ChartChannel("x", required = true, node = ChannelNode.Shape("position")),
            ChartChannel("y", required = true, node = ChannelNode.Shape("position")),
            ChartChannel(
                "color",
                required = false,
                node =
                    ChannelNode.OneOf(
                        listOf(
                            ShapeAlternative("series", listOf("nominal", "ordinal")),
                            ShapeAlternative("shade", listOf("quantitative")),
                        ),
                    ),
            ),
            ChartChannel("size", required = false, node = ChannelNode.Shape("size")),
            ChartChannel("tooltip", required = false, node = tooltip),
        )

    /** A single measure split by series: no axes or authored tooltip. */
    val pie: List<ChartChannel> =
        listOf(
            ChartChannel("theta", required = true, node = ChannelNode.Shape("value")),
            ChartChannel("color", required = true, node = ChannelNode.Shape("series")),
        )
}
