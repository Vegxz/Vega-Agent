package github.vega.agent

/**
 * Reflection runner: runs every test* method of the two suites WITHOUT
 * aborting on the first failure, so failure SETS can be compared between the
 * modified tree and the pristine one.
 *
 * Usage: java -cp <test-classes>:<support>:<stdlib> github.vega.agent.RunAll
 *
 * Prints one line per test: PASS <name> or FAIL <name> :: <error>.
 * In this sandbox the loopback-HTTP tests fail identically on pristine
 * sources (the egress proxy intercepts loopback traffic); those failures are
 * environmental, not regressions — compare the two runs' failure sets.
 */
object RunAll {

    @JvmStatic
    fun main(args: Array<String>) {
        NetworkPolicy.allowLocalNetwork = true
        var pass = 0
        var fail = 0
        val failures = ArrayList<String>()
        for (suite in listOf(CoreRegressionTests, AgentLoopTests, FileToolTests, WebHardeningTests)) {
            val methods = suite.javaClass.declaredMethods
                .filter { it.name.startsWith("test") && it.parameterCount == 0 }
                .sortedBy { it.name }
            for (m in methods) {
                m.isAccessible = true
                try {
                    m.invoke(suite)
                    println("PASS " + suite.javaClass.simpleName + "." + m.name)
                    pass++
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val cause = e.cause ?: e
                    val msg = (cause.javaClass.simpleName + ": " + (cause.message ?: "")).take(220)
                    println("FAIL " + suite.javaClass.simpleName + "." + m.name + " :: " + msg)
                    failures.add(suite.javaClass.simpleName + "." + m.name)
                    fail++
                } catch (e: Throwable) {
                    val msg = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(220)
                    println("FAIL " + suite.javaClass.simpleName + "." + m.name + " :: " + msg)
                    failures.add(suite.javaClass.simpleName + "." + m.name)
                    fail++
                }
            }
        }
        println("----")
        println("pass=$pass fail=$fail")
        if (failures.isNotEmpty()) {
            println("failures:")
            for (f in failures) {
                println("  $f")
            }
        }
    }
}
