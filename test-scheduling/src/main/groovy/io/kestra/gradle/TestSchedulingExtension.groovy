package io.kestra.gradle

/**
 * Configuration for the test-scheduling plugin.
 *
 * <pre>
 * kestraTestScheduling {
 *     priority = [':core', ':tests']   // project paths whose Test tasks run unthrottled
 *     reservedSlots = 2                // optional: defaults to min(priority task count, maxWorkers - 1)
 *     enabled = true
 *     preferPriorityFirst = true       // build the priority modules' chain first
 * }
 * </pre>
 */
class TestSchedulingExtension {
    /** Project paths (e.g. ':core') whose Test tasks are considered priority and run unthrottled. */
    List<String> priority = []

    /**
     * How many worker slots to reserve for priority tasks while they are running.
     * Null means auto: min(number of priority Test tasks in the graph, maxWorkers - 1).
     */
    Integer reservedSlots = null

    /** When false the plugin registers itself but does nothing. */
    boolean enabled = true

    /**
     * When true, the priority projects' copies of every requested lifecycle task are moved to the
     * front of the requested task list, which puts them — and their whole dependency chain: their own
     * compile/jar tasks plus those of every upstream project — at the head of the execution plan.
     *
     * This is what makes the reservation useful. A priority Test task cannot start until its compile
     * chain is done; while that chain queues behind dozens of light-module compilations, the reserved
     * slots sit idle. Heading the plan lets the chain take workers first, so the priority tests
     * become dependency-ready as early as possible.
     *
     * Only plain lifecycle names are rewritten. Invocations carrying task options (--tests, --rerun)
     * are left untouched, as are already project-qualified names and excluded tasks.
     */
    boolean preferPriorityFirst = true
}
