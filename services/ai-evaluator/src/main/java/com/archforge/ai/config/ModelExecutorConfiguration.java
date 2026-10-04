package com.archforge.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pool the model calls run on.
 *
 * <p>A bean rather than a field, for two reasons that both cost a debugging cycle to learn.
 *
 * <p><b>Spring cannot choose between two constructors.</b> With an executor as a constructor
 * argument for tests, {@code ArchitectureEvaluationService} had two constructors and no
 * {@code @Autowired}, so the context failed to start with "No default constructor found" --
 * on a service whose entire claim is that it starts with no model configured. The failure was
 * invisible to 41 unit tests and appeared only when the jar was run.
 *
 * <p><b>The pool's lifetime belongs to the container.</b> Handing Spring an executor with
 * {@code destroyMethod} means shutdown is ordered correctly and an interrupt during context
 * close actually reaches an in-flight model call, instead of the JVM waiting on a
 * non-daemon thread that nothing knows about.
 */
@Configuration
public class ModelExecutorConfiguration {

    /**
     * Daemon threads, named, so a stuck model call cannot hold up shutdown.
     *
     * <p>Unbounded because the natural bound is the listener's concurrency and the terminal
     * grace period: a call already past its timeout is abandoned and its thread will finish
     * and exit. A bounded pool would instead queue calls behind abandoned ones, which turns a
     * slow provider into a growing backlog of evaluations.
     */
    @Bean(name = "evaluationModelExecutor", destroyMethod = "shutdownNow")
    public ExecutorService evaluationModelExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "eval-model-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }
}