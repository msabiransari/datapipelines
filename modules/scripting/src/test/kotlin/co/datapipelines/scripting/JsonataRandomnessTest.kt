package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.DEFAULT_LIMITS
import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Ambient random reads refuse at call time, including aliases and library error wrappers. */
class JsonataRandomnessTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "\$random()",
            "\$eval('\$random()')",
            "\$eval('\$eval(\"\$random()\")')",
            "(\$r := \$random; \$r())",
            "\$map([1,2], function(\$v){\$random()})",
            "true and \$random() > 0",
            "\$sort([1,2], function(\$a,\$b){\$random() > 0})",
            "\$replace('a', /a/, function(\$m){\$random()})",
            "\$shuffle([1,2,3])",
            "\$shuffle([])",
            "\$shuffle([1])",
            "\$eval('\$shuffle([1,2,3])')",
            "(\$s := \$shuffle; \$s([1,2,3]))",
            "\$map([1,2], function(\$v){\$shuffle([1,2,3])})",
            "true and \$count(\$shuffle([1,2,3])) > 0",
            "\$sort([1,2], function(\$a,\$b){\$count(\$shuffle([1,2,3])) > 0})",
        ],
    )
    fun `ambient random reads are pure-function evaluation refusals`(body: String) {
        val builtin = if (body.contains("\$shuffle")) "\$shuffle()" else "\$random()"
        listOf(DEFAULT_LIMITS, DEFAULT_LIMITS.copy(now = null)).forEach { limits ->
            val caught = runCatching { engine.evaluate(engine.compile(body), null, limits) }.exceptionOrNull()
            val failure = caught.shouldBeInstanceOf<ScriptEvaluationException>()
            failure.code shouldBe "pipeline.script.evaluation_failed"
            failure.message shouldContain "$builtin is not available"
            failure.message shouldContain "pure function of its inputs"
            failure.message shouldContain "no seed exists; remove the call"
            if (builtin == "\$shuffle()") failure.message shouldContain "sort"
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "false ? \$random() : 42",
            "false ? \$shuffle([1,2,3]) : 42",
            "\$eval('false ? \$random() : 42')",
            "(\$random := function(){42}; \$random())",
            "(\$shuffle := function(\$a){42}; \$shuffle([1,2,3]))",
            "\$eval('(\$random := function(){42}; \$random())')",
        ],
    )
    fun `lazy branches and deterministic local rebindings remain legal`(body: String) {
        engine.evaluate(engine.compile(body), null, DEFAULT_LIMITS) shouldBe 42
    }
}
