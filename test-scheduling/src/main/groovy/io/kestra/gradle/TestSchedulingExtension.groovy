package io.kestra.gradle

/**
 * Configuration for the test-scheduling plugin.
 *
 * <pre>
 * kestraTestScheduling {
 *     priority = [':core', ':tests']   // project paths whose Test tasks run unthrottled
 *     reservedSlots = 2                // optional: defaults to min(priority task count, maxWorkers - 1)
 *     enabled = true
 *     preferPriorityFirst = true       // adds soft shouldRunAfter ordering
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
     * When true, non-priority Test tasks declare a soft shouldRunAfter dependency on priority Test
     * tasks so the scheduler prefers to start priority tasks first.
     * Note: incompatible with --configure-on-demand; set to false if you use it.
     */
    boolean preferPriorityFirst = true
}
