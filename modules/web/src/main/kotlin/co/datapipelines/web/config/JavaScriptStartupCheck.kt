package co.datapipelines.web.config

import co.datapipelines.scripting.Availability
import co.datapipelines.scripting.JavaScriptRuntime
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory

/** Reports the boot probe once; absent isolates disable JavaScript without degrading JSONata (D-T16). */
class JavaScriptStartupCheck(
    private val runtime: JavaScriptRuntime,
    private val properties: ScriptProperties,
) {
    @PostConstruct
    fun check() {
        val log = LoggerFactory.getLogger(JavaScriptStartupCheck::class.java)
        when (val availability = runtime.availability()) {
            is Availability.Unavailable -> {
                log.atInfo().addKeyValue("reason", availability.reason).log(
                    "script.js: isolate not installed (datapipelines.script.js.isolate-dir={}): javascript templates are refused at save",
                    properties.js.isolateDir ?: "",
                )
            }

            Availability.Available -> {
                log.info(
                    "script.js: isolate loaded from {} (GraalJS 25.4.4.1.1; engine per jvm; " +
                        "heap {} MB, isolate {} MB, statements {}, result {} MiB, input {} MiB)",
                    properties.js.isolateDir ?: "classpath",
                    properties.js.maxHeapMb,
                    properties.isolateMemoryMb,
                    properties.js.maxStatements,
                    properties.js.maxResultMb,
                    properties.js.maxInputMb,
                )
            }
        }
    }
}
