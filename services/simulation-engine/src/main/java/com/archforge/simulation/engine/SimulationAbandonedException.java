package com.archforge.simulation.engine;

/**
 * A run stopped part-way because its wall-clock budget ran out.
 *
 * <p>Distinct from an ordinary failure because the work is not wrong — it is incomplete,
 * and the two need different handling. A run abandoned at tick 40 of 60 has a real trace
 * for those 40 ticks, and publishing it with a clear marker is more useful to a user than
 * discarding it. What must never happen is publishing it as though it were complete,
 * which is why this is thrown rather than logged: it propagates out of the tick loop to
 * the run service, which turns it into a terminal lifecycle event that says the run was
 * abandoned and at which tick.
 */
public class SimulationAbandonedException extends RuntimeException {

    public SimulationAbandonedException(String message) {
        super(message);
    }
}
