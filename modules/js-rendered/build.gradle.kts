// module-structure.md §5.24 — the js-rendered chart engine (design record 2026-11-14-js-rendered-charts §4.3, §5.1).
// Layer 0: NO internal dependencies and no external ones. The engine is the product's own JavaScript, classic script
// files on one namespace (`JsRendered`) in the fixed order of LOAD-ORDER.txt, no bundler and no npm (O-3, R17); the
// same files run in a browser page and in the server's GraalJS sandbox. The only Kotlin is JsRenderedEngineFiles,
// which lists those files and concatenates them for a JVM caller.
//
// Two Node tasks (record §5.5, brief 985 D10):
//   jsRenderedTest — the engine's Node suite (`node --test`, src/test/js/*.test.mjs). A plain, CACHEABLE task with a
//     CLOSED input list: the engine (src/main/resources), the tests (src/test/js), the fixtures (src/test/fixtures) and
//     the `node --version` string; its one output is the counts file. A recorded green run (up to date here, or from
//     the build cache under --build-cache) is not run again for the same inputs (R15); an edit outside those inputs
//     never re-runs it. A machine without node FAILS it (unlike modules/web's editorJsTest, which skips): the engine
//     has no other test of its geometry.
//   jsRenderedEmit — Node's render result for every chart fixture at the fixed frames, one file per fixture, under
//     build/. The JVM identity guard (modules/application JsRenderedIdentityTest) compares the sandbox's result with
//     these files byte for byte (I6/A3), reading them from this module's jsRenderedTestData configuration.
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
        val exit = runNode(node, listOf("--test", "--test-reporter=tap") + files.map { it.absolutePath }, tap, 15, "jsRenderedTest")
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
