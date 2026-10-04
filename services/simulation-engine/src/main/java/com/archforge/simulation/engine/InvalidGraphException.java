package com.archforge.simulation.engine;

/**
 * A graph the simulator cannot evaluate without producing a wrong number.
 *
 * <p>Separate from a general exception because every case here is a <em>defect</em> that
 * a correct producer prevents, and the simulator's response is to refuse rather than
 * guess. Each case otherwise produces a run that reports {@code COMPLETED} with numbers
 * that are wrong in a way no consumer can detect:
 *
 * <ul>
 *   <li><b>Duplicate node ids</b> — the state map collapses them, the node list does not,
 *       so every node is evaluated twice and every {@code (node, metric, tick)} key is
 *       published twice. Sample counts double and the aggregator's percentiles are
 *       computed over a stream with every value in it twice.</li>
 *   <li><b>An edge to a node that does not exist</b> — the source stops counting as a
 *       terminal, so nothing is ever recorded as served. The run completes reporting
 *       100% loss with no warning and an empty findings list.</li>
 *   <li><b>A graph that is not in topological order</b> — the tick loop does one pass in
 *       rank order and reads each upstream's <em>current</em> tick value, which is
 *       {@code 0} for a component not yet evaluated. Every non-entry node is therefore
 *       starved <b>permanently</b>, not just on the first tick, and the run reports all
 *       load offered and none served.</li>
 * </ul>
 *
 * <p>The graph compiler's {@code TopologicalSorter} guarantees all three, so these are
 * unreachable through the normal pipeline. They are reachable from a replayed dead-letter
 * record, a hand-published task, or a future producer that skips the compiler — and the
 * reason to check is that every failure mode is silent.
 */
public class InvalidGraphException extends IllegalArgumentException {

    public InvalidGraphException(String message) {
        super(message);
    }
}
