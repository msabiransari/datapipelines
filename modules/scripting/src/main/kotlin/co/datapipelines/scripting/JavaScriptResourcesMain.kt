package co.datapipelines.scripting

import kotlin.system.exitProcess

/**
 * Pre-installs the JavaScript isolate's native resources at IMAGE BUILD time (#8, D5; the
 * Dockerfile runs it as root before `USER`).
 *
 * On its first Engine, polyglot unpacks the isolate library and `libtruffleattach` into a resource
 * cache — by default under the user's home, which a read-only root filesystem refuses. This main
 * points `polyglot.engine.userResourceCache` at the one argument and starts the isolate through
 * [JavaScriptRuntime] (the module's one factory, under the full policy), so polyglot's own installer
 * writes the cache in its own layout and the build fails here, not at the first evaluation, if the
 * isolate cannot start. The image's ENTRYPOINT points the same property at the same directory.
 *
 * `Engine.copyResources` is not used: it copies the isolate but not `libtruffleattach`, which a
 * HotSpot JVM needs (measured in lane 8a: "Polyglot isolates require libtruffleattach when running
 * on HotSpot with the fallback Truffle runtime").
 */
object JavaScriptResourcesMain {
    private const val USAGE = 2
    private const val UNAVAILABLE = 1

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 1) {
            System.err.println("usage: JavaScriptResourcesMain <resource cache directory>")
            exitProcess(USAGE)
        }
        System.setProperty("polyglot.engine.userResourceCache", args[0])
        when (val availability = JavaScriptRuntime().availability()) {
            Availability.Available -> {
                println("JavaScript isolate resources installed in ${args[0]}")
            }

            is Availability.Unavailable -> {
                System.err.println("the JavaScript isolate did not start: ${availability.reason}")
                exitProcess(UNAVAILABLE)
            }
        }
    }
}
