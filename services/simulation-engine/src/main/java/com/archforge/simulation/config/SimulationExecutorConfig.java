package com.archforge.simulation.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The pool that runs simulations off the Kafka consumer thread.
 *
 * <h2>Why the work cannot run on the consumer thread</h2>
 * A 60-second, 1-second-tick run executes 60 ticks of arithmetic per node — fast. But
 * each tick emits a sample per metric, and a graph with 30 components emits ~16 000
 * messages. Done synchronously on the consumer thread with
 * {@code max.poll.records: 100}, a single poll's worth of work runs for minutes; the
 * consumer is then evicted for exceeding {@code max.poll.interval.ms}, it rejoins, it
 * re-reads from its last committed offset, and it does the same work again. The
 * previous version deadlocked itself exactly this way — ~90 seconds per record against
 * a poll interval that was never raised.
 *
 * <h2>Why the rejection policy is caller-runs</h2>
 * Two alternatives, both worse:
 * <ul>
 *   <li><b>Abort</b> — the record is rejected, and because the offset is already
 *       committed the simulation is simply never run. The submission sits at
 *       {@code SIMULATING} forever, with no error anywhere.</li>
 *   <li><b>Discard</b> — the same outcome, silently.</li>
 *   <li><b>Unbounded queue</b> — every accepted task is eventually run, but memory
 *       grows with the backlog, so a sustained arrival rate above the service rate
 *       ends in an out-of-memory kill that loses every in-flight run at once.</li>
 * </ul>
 *
 * <p>Caller-runs makes the overload visible where it can be acted on: the consumer
 * slows down, {@code max.poll.records} shrinks the batch it takes, and lag becomes a
 * Kafka metric an operator is already watching. Nothing is lost and nothing is
 * unbounded.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class SimulationExecutorConfig {

    /**
     * Thread name prefix.
     *
     * <p>Named so a thread dump during a stuck run identifies the culprit without
     * cross-referencing a config file.
     */
    static final String THREAD_PREFIX = "sim-run-";

    @Bean(name = "simulationExecutor", destroyMethod = "shutdown")
    public TaskExecutor simulationExecutor(SimulationProperties properties) {
        int threads = Math.max(1, properties.getWorkerThreads());
        int queueCapacity = Math.max(0, properties.getWorkerQueueCapacity());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(THREAD_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let in-flight runs finish on shutdown so a COMPLETED lifecycle event still
        // reaches the aggregator. A run abandoned at tick 40 of 60 publishes a
        // submission that stops progressing with nothing to explain it.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();

        log.info("Simulation executor: {} threads, queue capacity {}, overflow runs on the caller",
                threads, queueCapacity);
        return executor;
    }
}
