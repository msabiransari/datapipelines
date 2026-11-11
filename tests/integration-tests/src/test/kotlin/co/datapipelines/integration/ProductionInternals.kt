package co.datapipelines.integration

import org.springframework.context.ApplicationContext
import java.lang.reflect.InvocationTargetException

/**
 * The production classes this module may not compile against (module-structure §4.2), reached by NAME at runtime —
 * beans out of the application context, Kotlin objects and companions by their JVM fields, constructors and methods by
 * arity. A Kotlin value-class parameter is erased to its underlying type and moves the constructor to a synthetic one
 * ending in `DefaultConstructorMarker`, which [construct] admits (passing null for the marker). The cause of a reflective
 * call's failure is rethrown unwrapped, so an assertion inside production code reads as itself.
 */
internal object ProductionInternals {
    private const val ROOT = "co.datapipelines."
    private const val MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"

    fun type(name: String): Class<*> = Class.forName(ROOT + name)

    fun bean(
        context: ApplicationContext,
        name: String,
    ): Any = context.getBean(type(name))

    fun singleton(name: String): Any = type(name).getField("INSTANCE").get(null)

    fun companion(name: String): Any? = type(name).getField("Companion").get(null)

    fun enumOf(
        name: String,
        constant: String,
    ): Any = type(name).enumConstants.single { (it as Enum<*>).name == constant }

    fun construct(
        name: String,
        vararg args: Any?,
    ): Any {
        val constructors = type(name).constructors
        val constructor =
            constructors.firstOrNull { it.parameterCount == args.size }
                ?: constructors.single { it.parameterCount == args.size + 1 && it.parameterTypes.last().name == MARKER }
        val actual = if (constructor.parameterCount == args.size) args else arrayOf(*args, null)
        return unwrapped { constructor.newInstance(*actual) }
    }

    fun call(
        target: Any,
        method: String,
        vararg args: Any?,
    ): Any? {
        val found =
            target.javaClass.methods.firstOrNull { it.name == method && it.parameterCount == args.size && !it.isBridge && !it.isSynthetic }
                ?: error("${target.javaClass.name} has no public $method with ${args.size} parameters")
        return unwrapped { found.invoke(target, *args) }
    }

    private fun <T> unwrapped(call: () -> T): T =
        try {
            call()
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
}
