package co.datapipelines.application.charts

import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ChartFrame
import co.datapipelines.visualization.ChartInput
import co.datapipelines.visualization.ChartTheme
import co.datapipelines.visualization.ChartType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The picture service (brief 986 D2, Security; record §7, A8) on the product's entry: the standalone SVG comes only
 * from the engine's escaping string writer. A8's hostile fixture (`<script>`, `onload=`, `&`, quotes, a category
 * named `__proto__`) is TEXT in the picture: parsed as XML, it holds 0 script elements and 0 event-handler attributes,
 * and the hostile strings come back as text nodes. The theme is a NAME from a closed list; the frame is clamped.
 */
class JsRenderedPictureTest {
    private val hostile: ChartInput by lazy {
        val file = ChartGuardFiles.repoFile("${ChartGuardFiles.MODULE}/src/test/fixtures/bar/hostile.json")
        val fixture = ArtifactJson.mapper.readTree(file)
        val columns =
            fixture.path("columns").properties().associate { (field, values) ->
                field to values.map { v -> if (v.isNumber) v.numberValue() else v.textValue() }
            }
        // The fixture's DOUBLE is the engine's quantitative wire type; on the server an approximate number is DECIMAL.
        val types = mapOf("k" to LogicalType.STRING, "s" to LogicalType.STRING, "v" to LogicalType.DECIMAL)
        ChartInput(ChartType.BAR, fixture.path("document"), columns, types)
    }

    @Test
    fun `A8's hostile fixture is text in the picture - 0 script elements, 0 event attributes, the strings as text nodes`() {
        for (theme in ChartTheme.entries) {
            val picture = ChartEngines.engine.picture(hostile, ChartFrame.of(960, 540), theme)
            picture.state shouldBe "rendered"
            val svg = picture.svg.shouldNotBeNull()
            svg.lowercase().contains("<script") shouldBe false
            val root = parse(svg)
            root.tagName shouldBe "svg"
            val elements = elements(root)
            elements.size shouldBeGreaterThan 10 // non-vacuity: a drawn chart, not an empty frame
            elements.filter { it.localName.equals("script", ignoreCase = true) }.shouldBeEmpty()
            elements.flatMap { attributeNames(it) }.filter { it.lowercase().startsWith("on") }.shouldBeEmpty()
            val texts = textNodes(root).joinToString("\n")
            // Decoded by the parser: the writer escaped them, so they are characters, never markup.
            texts shouldContain "onload=alert(1)"
            texts shouldContain "a & b"
            texts shouldContain "<"
        }
    }

    @Test
    fun `the standalone picture is described as the page's is - role img, its title and its desc first, as text (990 D6)`() {
        val root = parse(ChartEngines.engine.picture(hostile, ChartFrame.of(960, 540), ChartTheme.LIGHT).svg.shouldNotBeNull())
        root.getAttribute("role") shouldBe "img"
        val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
        children[0].localName shouldBe "title"
        children[0].textContent shouldBe hostile.document.path("display_name").textValue()
        children[1].localName shouldBe "desc"
        children[1].textContent.startsWith("Bar chart; ") shouldBe true
        elements(children[0]).size shouldBe 1 // the title alone: the hostile display name is text, never an element
    }

    @Test
    fun `the theme is a name from a closed list - light and dark draw different literal colours`() {
        val light =
            ChartEngines.engine
                .picture(hostile, ChartFrame.CANONICAL, ChartTheme.LIGHT)
                .svg
                .shouldNotBeNull()
        val dark =
            ChartEngines.engine
                .picture(hostile, ChartFrame.CANONICAL, ChartTheme.DARK)
                .svg
                .shouldNotBeNull()
        ChartTheme.entries.map { it.wire } shouldBe listOf("light", "dark")
        light shouldNotBe dark
        // Standalone: literal colours, never a page's custom property.
        listOf(light, dark).forEach { it.contains("var(--") shouldBe false }
    }

    @Test
    fun `the frame is clamped to 16 to 4,096 px per side - the picture is drawn at the clamped size`() {
        ChartFrame.of(1, 100_000).let { (it.width to it.height) shouldBe (ChartFrame.MIN to ChartFrame.MAX) }
        ChartFrame.of(-5, 0).let { (it.width to it.height) shouldBe (ChartFrame.MIN to ChartFrame.MIN) }
        val root =
            parse(
                ChartEngines.engine
                    .picture(hostile, ChartFrame.of(100_000, 300), ChartTheme.LIGHT)
                    .svg
                    .shouldNotBeNull(),
            )
        root.getAttribute("width") shouldBe "${ChartFrame.MAX}"
        root.getAttribute("height") shouldBe "300"
    }

    private fun parse(svg: String): Element {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        return factory.newDocumentBuilder().parse(InputSource(StringReader(svg))).documentElement
    }

    private fun elements(root: Element): List<Element> = listOf(root) + children(root).filterIsInstance<Element>().flatMap { elements(it) }

    private fun children(node: Node): List<Node> = (0 until node.childNodes.length).map { node.childNodes.item(it) }

    private fun attributeNames(element: Element): List<String> =
        (0 until element.attributes.length).map { element.attributes.item(it).nodeName }

    private fun textNodes(node: Node): List<String> =
        children(node).flatMap { if (it.nodeType == Node.TEXT_NODE) listOf(it.nodeValue) else textNodes(it) }
}
