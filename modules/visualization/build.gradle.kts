// module-structure.md §5.20 — visualizations and dashboards (#10; the dashboard implementation spec §1,
// the design record D29). Allowed internal deps (§4.2): typesystem, pipeline-contract, templates, parameters.
// `templates` is allowed and NOT declared: every template fact this module needs arrives through
// pipeline-contract's ports (TemplateDryRenderer.lookup/transformContract, TemplateVersionStatuses,
// TemplateReleaser), which `templates` implements — the house "allowed ahead, undeclared" rule.
import java.util.concurrent.TimeUnit

plugins { id("datapipelines.common-conventions") }

dependencies {
    // LogicalType (an input contract's column vocabulary), DatapipelinesException.
    implementation(project(":modules:typesystem"))
    // PipelineNameGrammar (the artifact names), ReadLens, AuthoringGuard, WriteSurface, CreateLifecycle,
    // PipelineVersionStatus, ValidationFailure/Result, TemplateRef, TransformContractView and the template
    // ports the validator and the release cascade go through.
    implementation(project(":modules:pipeline-contract"))
    // ParameterSetRepository — the production ParameterSetFacts reads a pinned set release's parameter
    // names and their dependents from it (spec §1: "the parameter-set repository for the set pin").
    implementation(project(":modules:parameters"))

    implementation(libs.jackson.module.kotlin)
    // The chart document v1's JSON Schema (draft 2020-12) at save — the schemas load from this module's
    // classpath only; the registry never fetches (ChartSchemaValidator).
    implementation(libs.json.schema.validator)
    // Jackson stays at the product's pin (#860): networknt 2.0.8's POM asks for Jackson 2.22.1 and its Jackson
    // modules import jackson-bom 2.22.1, which wins over the conventions' platform(jackson-bom 2.21.7) under
    // default resolution — the lake module's precedent (modules/lake/build.gradle.kts, Iceberg's 2.22.2), same
    // mechanism. Enforced at the only declaration that brings 2.0.8, so every consumer (mcp-server, web, app, the
    // test projects) resolves the same Jackson, the MCP SDK's own edge to the validator included.
    implementation(enforcedPlatform(libs.jackson.bom))
    testImplementation(enforcedPlatform(libs.jackson.bom))
    implementation(libs.spring.boot.starter.jdbc) // the two repositories (§8.1), @ConfigurationProperties

    // The container suite runs the repositories and the lifecycle against a real Postgres with the SHIPPED
    // migrations applied through plain JDBC (module-structure §7.4; Flyway stays in `app`, §3.1 rule 2).
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
}

// #860 A.2b — the chart model parity's JS half (design record 2026-11-06 §5.1). ChartModelParityTest compares
// ChartModel with the runtime core's `model` (modules/web's datapipelines-dashboard-chart.js) case by case over ONE
// corpus (modules/web/src/test/resources/chart-parity/corpus.json). The JS side is evaluated IN the build, every run:
// this task runs the node emitter against the CURRENT core and writes build/chart-parity/js-model.json, which the test
// reads. Never up to date (a change to the core alone forces a fresh evaluation), the previous result is deleted
// first (a failed run leaves no file to read), and a missing node, a non-zero exit or a missing result fails the
// build: unlike editorJsTest this guard does not skip on a node-less machine, because the parity it proves is the
// server's mechanical check judging what the browser draws (CI's ubuntu runners carry node).
val chartParityRoot = rootProject.file("modules/web/src/test/resources/chart-parity")
val chartCore = rootProject.file("modules/web/src/main/resources/static/js/datapipelines-dashboard-chart.js")
val chartExamples = file("src/test/resources/chart-examples/valid")
val chartParityResult = layout.buildDirectory.file("chart-parity/js-model.json")
val chartParityEmit =
    tasks.register("chartParityEmit") {
        group = "verification"
        description = "Evaluates the runtime chart core over the parity corpus on node for ChartModelParityTest."
        inputs.file(chartCore).withPropertyName("chartCore")
        inputs.dir(chartParityRoot).withPropertyName("chartParityCorpus")
        inputs.dir(chartExamples).withPropertyName("chartExamples")
        outputs.file(chartParityResult)
        outputs.upToDateWhen { false }
        val result = chartParityResult
        doLast {
            val out = result.get().asFile
            out.delete()
            val node =
                System.getenv("PATH")
                    ?.split(File.pathSeparator)
                    ?.asSequence()
                    ?.map { File(it, "node") }
                    ?.firstOrNull { it.canExecute() }
                    ?: throw GradleException(
                        "chartParityEmit needs node on PATH (Node >= 18): ChartModelParityTest compares ChartModel with " +
                            "the runtime chart core, which only node evaluates",
                    )
            val log = File(out.parentFile.apply { mkdirs() }, "emit.log")
            val process =
                ProcessBuilder(
                    node.absolutePath,
                    File(chartParityRoot, "emit-js-model.mjs").absolutePath,
                    chartCore.absolutePath,
                    chartParityRoot.absolutePath,
                    chartExamples.absolutePath,
                    out.absolutePath,
                ).redirectErrorStream(true)
                    .redirectOutput(log)
                    .start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw GradleException("chartParityEmit: the node emitter ran past 60 s (log: $log)")
            }
            val exit = process.exitValue()
            if (exit != 0 || !out.isFile) {
                throw GradleException("chartParityEmit: node exited $exit, result file present=${out.isFile}:\n${log.readText()}")
            }
            logger.lifecycle("chartParityEmit: {} {}", log.readText().trim(), out)
        }
    }

tasks.named<Test>("test") {
    // The emitter's result is an input: the test runs after a fresh evaluation and re-runs when the JS model changes.
    inputs.files(chartParityEmit).withPropertyName("chartParityJsModel")
}
