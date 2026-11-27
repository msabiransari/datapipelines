// module-structure.md §5.24 — the js-rendered chart engine (design record 2026-11-14-js-rendered-charts §4.3, §5.1).
// Layer 0: NO internal dependencies and no external ones. The engine is the product's own JavaScript, classic script
// files on one namespace (`JsRendered`) in the fixed order of LOAD-ORDER.txt, no bundler and no npm (O-3, R17); the
// same files run in a browser page and in the server's GraalJS sandbox. The only Kotlin is JsRenderedEngineFiles,
// which lists those files and concatenates them for a JVM caller.
//
// Two Node tasks (record §5.5, brief 985 D10):
//   jsRenderedTest — the engine's Node suite (`node --test`, src/test/js/*.test.mjs). A plain, CACHEABLE task with a
//     CLOSED input list: the engine (src/main/resources), the tests (src/test/js), the fixtures (src/test/fixtures), the
//     interaction layer's one file that scatter.test.mjs loads (modules/web) and the `node --version` string; its one
//     output is the counts file. A recorded green run (up to date here, or from
//     the build cache under --build-cache) is not run again for the same inputs (R15); an edit outside those inputs
//     never re-runs it. A machine without node FAILS it (unlike modules/web's editorJsTest, which skips): the engine
//     has no other test of its geometry.
//   jsRenderedEmit — Node's render result for every chart fixture at the fixed frames, one file per fixture, under
//     build/. The JVM identity guard (modules/application JsRenderedIdentityTest) compares the sandbox's result with
//     these files byte for byte (I6/A3), reading them from this module's jsRenderedTestData configuration.
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

plugins {
    id("datapipelines.common-conventions")
}

/** The node on PATH, or null. */
fun findNode(): File? =
    System.getenv("PATH")
        ?.split(File.pathSeparator)
        ?.asSequence()
        ?.map { File(it, "node") }
        ?.firstOrNull { it.canExecute() }

fun versionOf(node: File): String =
    ProcessBuilder(node.absolutePath, "--version")
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .trim()

// The node version is an INPUT of both tasks (a new node re-runs them); "absent" when there is none, which a task then
// refuses at execution (so a node-less machine fails, and never reuses a result recorded elsewhere under that word).
val nodeVersion: Provider<String> = providers.provider { findNode()?.let { versionOf(it) } ?: "absent" }

val engineResources = file("src/main/resources")
val jsTests = file("src/test/js")
val jsFixtures = file("src/test/fixtures")

/** The node to run, or a failure naming the task: no task of this module skips without node. */
fun requireNode(task: String): File =
    findNode() ?: throw GradleException("$task needs node on PATH (Node >= 18): the js-rendered engine's tests run on node")

/** Runs node with [args] in this module's directory, output to [log], bounded by [minutes]; returns the exit code. */
fun runNode(
    node: File,
    args: List<String>,
    log: File,
    minutes: Long,
    task: String,
): Int {
    log.parentFile.mkdirs()
    val process =
        ProcessBuilder(listOf(node.absolutePath) + args)
            .directory(projectDir)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
    if (!process.waitFor(minutes, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        throw GradleException("$task: node ran past $minutes min (log: $log)")
    }
    return process.exitValue()
}

val jsRenderedTestResult = layout.buildDirectory.file("js-rendered-test/result.txt")
tasks.register("jsRenderedTest") {
    group = "verification"
    description = "Runs the js-rendered engine's Node suite (node --test src/test/js/*.test.mjs)."
    inputs.dir(engineResources).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("engine")
    inputs.dir(jsTests).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("tests")
    inputs.dir(jsFixtures).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("fixtures")
    // scatter.test.mjs pins the keys' plan through the interaction layer's pure half (brief 1062 r2 R1), a file of
    // modules/web: an input, so a change of the layer runs this cached suite again instead of serving its old result.
    inputs
        .file(rootProject.file("modules/web/src/main/resources/static/js/datapipelines-chart-interactions.js"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("interactionLayer")
    inputs.property("nodeVersion", nodeVersion)
    outputs.file(jsRenderedTestResult).withPropertyName("result")
    outputs.cacheIf { true }
    val result = jsRenderedTestResult
    doLast {
        val node = requireNode("jsRenderedTest")
        val out = result.get().asFile
        out.delete()
        val files = jsTests.listFiles { f -> f.name.endsWith(".test.mjs") }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) throw GradleException("jsRenderedTest found no *.test.mjs under src/test/js: the suite ran vacuously")
        // The static floor: every `test(` call site must be reported at least once (a loop reports more; fewer means
        // a test was never counted).
        val floor = files.sumOf { f -> Regex("""(^|[^.\w])test\(""").findAll(f.readText()).count() }
        val tap = File(temporaryDir, "tap.log")
        // Full suite with treemap: 1026 tests measured at about 16m19s; allow bounded headroom (#1145).
        val exit = runNode(node, listOf("--test", "--test-reporter=tap") + files.map { it.absolutePath }, tap, 25, "jsRenderedTest")
        val text = tap.readText()

        fun count(key: String): Int =
            Regex("""^# $key (\d+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.toInt()
                ?: throw GradleException("jsRenderedTest: the TAP summary has no '# $key' line (exit $exit):\n${text.takeLast(4000)}")
        val pass = count("pass")
        val fail = count("fail")
        val cancelled = count("cancelled")
        val skipped = count("skipped")
        val todo = count("todo")
        val summary = "files=${files.size} pass=$pass fail=$fail cancelled=$cancelled skipped=$skipped todo=$todo floor=$floor node=${versionOf(node)}"
        if (exit != 0 || fail != 0 || cancelled != 0 || skipped != 0 || todo != 0 || pass < floor) {
            throw GradleException("jsRenderedTest FAILED: $summary exit=$exit\n${text.takeLast(8000)}")
        }
        out.parentFile.mkdirs()
        out.writeText("$summary\n$text")
        logger.lifecycle("jsRenderedTest: {}", summary)
    }
}

val jsRenderedEmitDir = layout.buildDirectory.dir("js-rendered-emit")
val jsRenderedEmit =
    tasks.register("jsRenderedEmit") {
        group = "verification"
        description = "Writes Node's render result for every chart fixture (the identity guard's reference)."
        inputs.dir(engineResources).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("engine")
        inputs.dir(jsTests).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("tests")
        inputs.dir(jsFixtures).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("fixtures")
        inputs.property("nodeVersion", nodeVersion)
        outputs.dir(jsRenderedEmitDir).withPropertyName("emitted")
        outputs.cacheIf { true }
        val dir = jsRenderedEmitDir
        doLast {
            val node = requireNode("jsRenderedEmit")
            val out = dir.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            val log = File(temporaryDir, "emit.log")
            val exit = runNode(node, listOf(File(jsTests, "emit.mjs").absolutePath, out.absolutePath), log, 10, "jsRenderedEmit")
            if (exit != 0) throw GradleException("jsRenderedEmit: node exited $exit:\n${log.readText().takeLast(4000)}")
            logger.lifecycle("jsRenderedEmit: {}", log.readText().trim().lines().lastOrNull() ?: "")
        }
    }

// The JVM guards (modules/application) read Node's emitted inputs and results and the invariant checker as classpath
// resources under co/datapipelines/jsrendered/{emitted,test}/ (each emitted input embeds its chart fixture whole). They reach them through ONE consumable
// configuration, jsRenderedTestData, whose artifact is the synced directory built from jsRenderedEmit:
// application declares testImplementation(project(":modules:js-rendered", configuration = "jsRenderedTestData")), an
// edge its root-map row already holds. Not the java-test-fixtures plugin: its self-edge (testFixtures on main) is a
// project dependency of this module on itself, which verifyModuleDependencies refuses against this row's empty set.
val fixtureResources = layout.buildDirectory.dir("js-rendered-fixture-resources")
val jsRenderedFixtureResources =
    tasks.register<Sync>("jsRenderedFixtureResources") {
        into(fixtureResources)
        from(jsRenderedEmit) { into("co/datapipelines/jsrendered/emitted") }
        // The invariant checker the Node suites run, loaded by the sandbox guard too (one checker, two runtimes).
        from(jsTests) {
            include("geometry.js")
            into("co/datapipelines/jsrendered/test")
        }
        // The types for tests only (brief 1049 D5), evaluated by JsRenderedTestTypesTest after the product's program.
        // Their pictures, types-index.txt, trig.json and sweep.json come with jsRenderedEmit above (emitted/_types/…).
        from(File(jsTests, "types")) {
            include("*.js")
            into("co/datapipelines/jsrendered/test/types")
        }
    }
val jsRenderedTestData =
    configurations.create("jsRenderedTestData") {
        isCanBeConsumed = true
        isCanBeResolved = false
        description = "Node's emitted inputs and results and the invariant checker, for the JVM chart guards."
    }
artifacts.add(jsRenderedTestData.name, fixtureResources.get().asFile) {
    type = "directory"
    builtBy(jsRenderedFixtureResources)
}

tasks.named("check") {
    dependsOn("jsRenderedTest")
}

// jsRenderedReference — the look reference page (lane 991; design record §9 F7, look contract §10 LA9; brief 991 D1,
// D1a). A static page for people looking at the look, assembled into build/reference/: the engine's files in the
// order of LOAD-ORDER.txt with the script tags GENERATED from that list (the pages carry one marker line where they
// go), the interaction layer's one file, the page's own script, stylesheet, tiles and chosen pictures, and the product's
// stylesheets copied byte for byte at their own relative addresses with every file they name by url() (the fonts).
// In no jar and behind no route (record O-11): nothing here is a resource of any source set. The browser test reads
// the directory through the jsRenderedReference configuration (tests/browser-tests, an edge its root-map row holds).
val referenceSource = file("src/reference")
val webStatic = file("../web/src/main/resources/static")
val referenceDir = layout.buildDirectory.dir("reference")

/** The product's leanest chart page links these six, in this order (templates/visualizations/preview.html). */
val referenceStylesheets =
    listOf(
        "vendor/design-system/tokens.css",
        "vendor/design-system/themes/light.css",
        "vendor/design-system/base.css",
        "vendor/design-system/primitives.css",
        "css/app.css",
        "css/dashboards.css",
    )

/** The theme files the two pages swap or link: light and dark (the switch), forest (its own page, brief D2a). */
val referenceThemes = listOf("vendor/design-system/themes/dark.css", "vendor/design-system/themes/forest.css")
val referenceLayer = "js/datapipelines-chart-interactions.js"
val referenceMarker = "<!-- js-rendered: engine scripts -->"

/** The CSS colour keywords (CSS Color 4 named colours plus the system-free extras) a layout file may not write. */
val cssColourNames =
    (
        "aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown burlywood " +
            "cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod darkgray " +
            "darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon darkseagreen " +
            "darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue dimgray dimgrey dodgerblue " +
            "firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite gold goldenrod gray green greenyellow grey honeydew " +
            "hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan " +
            "lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray " +
            "lightslategrey lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine mediumblue " +
            "mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise mediumvioletred " +
            "midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab orange orangered orchid " +
            "palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru pink plum powderblue purple " +
            "rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue " +
            "slateblue slategray slategrey snow springgreen steelblue tan teal thistle tomato turquoise violet wheat white " +
            "whitesmoke yellow yellowgreen transparent currentcolor"
    ).split(" ").toSet()

fun sha256(f: File): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(f.readBytes())
        .joinToString("") { "%02x".format(it) }

val jsRenderedReference =
    tasks.register("jsRenderedReference") {
        group = "documentation"
        description = "Assembles the look reference page into build/reference/ (lane 991): engine, layer, stylesheets, tiles."
        inputs.dir(engineResources).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("engine")
        inputs.dir(referenceSource).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("page")
        inputs.dir(File(webStatic, "vendor/design-system")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("designSystem")
        inputs.dir(File(webStatic, "vendor/fonts")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("fonts")
        inputs
            .files(File(webStatic, "css/app.css"), File(webStatic, "css/dashboards.css"), File(webStatic, referenceLayer))
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("productFiles")
        outputs.dir(referenceDir).withPropertyName("reference")
        val outDir = referenceDir
        val engineDir = File(engineResources, "co/datapipelines/jsrendered/engine")
        val source = referenceSource
        val static = webStatic
        val sheets = referenceStylesheets
        val themes = referenceThemes
        val layer = referenceLayer
        val marker = referenceMarker
        val colourNames = cssColourNames
        doLast {
            val out = outDir.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            val report = mutableListOf<String>()

            fun copyChecked(from: File, rel: String) {
                if (!from.isFile) throw GradleException("jsRenderedReference: $rel is missing at $from")
                val to = File(out, rel)
                to.parentFile.mkdirs()
                from.copyTo(to, overwrite = true)
                val a = sha256(from)
                val b = sha256(to)
                if (a != b) throw GradleException("jsRenderedReference: the copy of $rel differs from its source")
                report += "  $rel sha256=$a"
            }

            // 1. The engine, in load order: the generated script list is this list, line for line (G1).
            val names = File(engineDir, "LOAD-ORDER.txt").readLines().map(String::trim).filter(String::isNotEmpty)
            if (names.isEmpty()) throw GradleException("jsRenderedReference: LOAD-ORDER.txt lists no file")
            report += "engine (${names.size} files, LOAD-ORDER.txt order):"
            names.forEach { copyChecked(File(engineDir, it), "engine/$it") }
            report += "interaction layer:"
            copyChecked(File(static, layer), layer)

            // 2. The product's stylesheets, unedited, at their own addresses, and every file they name by url().
            report += "stylesheets (the six of preview.html in its order, then the theme files):"
            (sheets + themes).forEach { copyChecked(File(static, it), it) }
            report += "files the stylesheets name:"
            val urlPattern = Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""")
            val named = sortedSetOf<String>()
            (sheets + themes).forEach { rel ->
                urlPattern.findAll(File(static, rel).readText()).forEach { m ->
                    val ref = m.groupValues[2].trim()
                    if (ref.startsWith("data:") || ref.startsWith("#")) return@forEach
                    if (ref.contains("://") || ref.startsWith("/")) {
                        throw GradleException("jsRenderedReference: $rel names $ref, outside the page's own directory")
                    }
                    val clean = ref.substringBefore('?').substringBefore('#')
                    val target = File(File(static, rel).parentFile, clean).normalize()
                    val relTarget = target.relativeTo(static.normalize()).invariantSeparatorsPath
                    if (relTarget.startsWith("..")) throw GradleException("jsRenderedReference: $rel names $ref, outside the static tree")
                    named += relTarget
                }
            }
            if (named.isEmpty()) throw GradleException("jsRenderedReference: the stylesheets name no file (the fonts): the scan ran vacuously")
            named.forEach { copyChecked(File(static, it), it) }

            // 3. The page: the two documents with the script tags generated at their one marker line.
            val tags =
                (names.map { "engine/$it" } + layer + "reference.js").joinToString("\n") { "  <script src=\"$it\"></script>" }
            listOf("index.html", "forest.html").forEach { page ->
                val text = File(source, page).readText()
                val count = text.split(marker).size - 1
                if (count != 1) throw GradleException("jsRenderedReference: $page carries the marker $count times (expected 1)")
                if (Regex("""<script\b""").containsMatchIn(text)) throw GradleException("jsRenderedReference: $page writes a script tag by hand")
                if (Regex("""\sstyle\s*=""").containsMatchIn(text)) throw GradleException("jsRenderedReference: $page carries a style attribute")
                File(out, page).writeText(text.replace(marker, tags))
            }
            listOf("reference.js", "reference.css").forEach { File(source, it).copyTo(File(out, it), overwrite = true) }
            File(source, "tiles").copyRecursively(File(out, "tiles"), overwrite = true)
            File(source, "study").copyRecursively(File(out, "study"), overwrite = true)

            // 4. The counts the brief asks for (A.1, Security): each one fails the task when it is not 0.
            val css = File(source, "reference.css").readText().replace(Regex("""/\*[\s\S]*?\*/"""), "")
            val chartDeclarations = Regex("""--chart-[a-z0-9-]*\s*:""").findAll(css).count()
            val values = Regex(""":([^;{}]+)[;}]""").findAll(css).map { it.groupValues[1] }.toList()
            val colourLiterals =
                values.sumOf { v ->
                    Regex("""#[0-9a-fA-F]{3,8}\b""").findAll(v).count() +
                        Regex("""\b(rgba?|hsla?|hwb|lab|lch|oklab|oklch|color|light-dark)\(""").findAll(v).count() +
                        Regex("""[A-Za-z]+""").findAll(v.replace(Regex("""var\([^)]*\)"""), "")).count { it.value.lowercase() in colourNames }
                }
            val script = File(source, "reference.js").readText()
            val sinks =
                listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "Function(")
                    .associateWith { script.split(it).size - 1 }
            report += "reference.css: --chart- declarations=$chartDeclarations colour literals=$colourLiterals (values read: ${values.size})"
            report += "reference.js sinks: " + sinks.entries.joinToString(" ") { "${it.key}=${it.value}" }
            if (values.isEmpty()) throw GradleException("jsRenderedReference: reference.css has no declaration: the count ran vacuously")
            if (chartDeclarations != 0 || colourLiterals != 0) {
                throw GradleException("jsRenderedReference: reference.css declares $chartDeclarations chart tokens and writes $colourLiterals colour literals (expected 0 and 0)")
            }
            if (sinks.values.any { it != 0 }) throw GradleException("jsRenderedReference: reference.js names a markup or code sink: $sinks")

            // 5. The tiles: one file per type, every tile with its hand-written expect.
            listOf("bar", "line", "area", "scatter", "pie", "combo", "heatmap", "box", "waterfall", "funnel", "gauge", "treemap").forEach { wire ->
                @Suppress("UNCHECKED_CAST")
                val tiles = groovy.json.JsonSlurper().parse(File(source, "tiles/$wire.json")) as List<Map<String, Any?>>
                val missing = tiles.filter { it["expect"] !is Map<*, *> || (it["expect"] as Map<*, *>).isEmpty() }.map { it["id"] }
                if (tiles.isEmpty() || missing.isNotEmpty()) throw GradleException("jsRenderedReference: tiles/$wire.json: ${tiles.size} tiles, without an expect: $missing")
                report += "tiles/$wire.json: ${tiles.size} tiles, each with its expect"
            }
            val pictures = File(source, "study").listFiles { f -> f.name.endsWith(".png") }.orEmpty().size
            report += "study: $pictures pictures"
            File(out, "ASSEMBLY.txt").writeText(report.joinToString("\n") + "\n")
            logger.lifecycle("jsRenderedReference: {}\n{}", out, report.joinToString("\n"))
        }
    }

val jsRenderedReferenceOut =
    configurations.create("jsRenderedReference") {
        isCanBeConsumed = true
        isCanBeResolved = false
        description = "The assembled look reference page (build/reference/), for the browser test that checks it (lane 991)."
    }
artifacts.add(jsRenderedReferenceOut.name, referenceDir.get().asFile) {
    type = "directory"
    builtBy(jsRenderedReference)
}
