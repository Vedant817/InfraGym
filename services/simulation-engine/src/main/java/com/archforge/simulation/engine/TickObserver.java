package com.archforge.simulation.engine;

import com.archforge.contracts.graph.CompiledNode;

/**
 * Receives one measurement.
 *
 * <p>A functional interface rather than a stream collector so the caller decides
 * whether a sample goes to Kafka, into an in-memory list, or nowhere — which is what
 * lets the simulator's behaviour be asserted in a unit test with no broker running.
 *
 * <p>Called once per component per metric per tick, on the thread executing the run.
 * An implementation that blocks therefore stalls the simulation, so publishing must
 * enqueue rather than wait.
 */
@FunctionalInterface
public interface TickObserver {

    void onSample(CompiledNode node,
                  ComponentState state,
                  String metricName,
                  double value,
                  long tick,
                  String phase,
                  boolean failing);
}
