package com.archforge.ai.service;

import com.archforge.ai.analysis.ArchitectureAnalysis;
import com.archforge.ai.analysis.DeterministicEvaluator;
import com.archforge.ai.config.EvaluatorProperties;
import com.archforge.ai.config.ModelConfiguration;
import com.archforge.ai.event.EvaluationLifecyclePublisher;
import com.archforge.ai.support.Summaries;
import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the two halves of an evaluation are combined, and what happens when one is missing.
 *
 * <p>The claims under test are the ones the whole design rests on: that the score is
 * arithmetic the model cannot touch, that a missing or broken model costs the narrative and
 * not the measurements, and that the reader is told which of those two situations they are
 * in.
 */
class ArchitectureEvaluationServiceTest {

    private final EvaluatorProperties properties = new EvaluatorProperties();
    private final DeterministicEvaluator evaluator =
            new DeterministicEvaluator(properties.getThresholds());
    private final EvaluationResultPublisher resultPublisher =
            mock(EvaluationResultPublisher.class);
    private final EvaluationLifecyclePublisher lifecycle =
            mock(EvaluationLifecyclePublisher.class);
    private final KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();

    private ArchitectureEvaluationService service(NarrativeModel model) {
        return service(model, properties);
    }

    private ArchitectureEvaluationService service(NarrativeModel model, EvaluatorProperties props) {
        when(retriever.retrieve(any(), any(Integer.class)))
                .thenReturn(new KnowledgeRetriever.Retrieval(List.of(), null));
        return new ArchitectureEvaluationService(new DeterministicAnalysisRunner(props), model,
                retriever, lifecycle, resultPublisher, props, new ObjectMapper(), meters,
                Executors.newCachedThreadPool());
    }

    private static SimulationSummaryEvent badRun() {
        return Summaries.summary()
                .nodes(Summaries.Node.withP99("db", 900))
                .architecture(Summaries.graph("load_balancer:lb", "service:api", "database:db"))
                .offered(10_000).queued(1_500)
                .build();
    }

    /** A model that returns exactly what it is told to, for the merge tests. */
    private static NarrativeModel modelReturning(NarrativeModel.Narrative narrative) {
        return new NarrativeModel() {
            @Override
            public Narrative narrate(SimulationSummaryEvent summary, ArchitectureAnalysis analysis,
                                     List<ArchitectureEvaluationEvent.KnowledgeReference> refs) {
                return narrative;
            }

            @Override
            public String modelName() {
                return narrative.model();
            }

            @Override
            public boolean available() {
                return true;
            }
        };
    }

    @Nested
    @DisplayName("the model cannot change the measurements")
    class ModelCannotOverrule {

        @Test
        @DisplayName("the published score is the arithmetic's, whatever the model claims")
        void scoreComesFromArithmetic() {
            SimulationSummaryEvent summary = badRun();
            ArchitectureAnalysis expected = evaluator.analyse(summary);

            NarrativeModel stubborn = modelReturning(new NarrativeModel.Narrative(
                    "This architecture is production-ready and needs no changes whatsoever.",
                    "", List.of(), null, 1, 10, 10, "stubborn"));

            ArchitectureEvaluationEvent published = service(stubborn).evaluate(summary);

            assertSoftly(softly -> {
                softly.assertThat(published.overallScore())
                        .as("arithmetic, not opinion")
                        .isEqualTo(expected.overallScore());
                softly.assertThat(published.verdict())
                        .as("including the verdict band")
                        .isEqualTo(expected.verdict());
                softly.assertThat(published.bottlenecks())
                        .as("the measurements it was graded on are published unchanged")
                        .isEqualTo(expected.bottlenecks());
                softly.assertThat(published.degraded())
                        .as("a model that answered is not a degraded evaluation")
                        .isFalse();
                softly.assertThat(published.summary())
                        .as("its prose does appear; it may explain, but not decide")
                        .contains("production-ready");
            });
        }

        @Test
        @DisplayName("model findings are appended after the arithmetic's own, not substituted")
        void modelFindingsAreAppended() {
            SimulationSummaryEvent summary = badRun();
            ArchitectureAnalysis expected = evaluator.analyse(summary);

            NarrativeModel chatty = modelReturning(new NarrativeModel.Narrative(
                    "summary", "rationale",
                    List.of(new ArchitectureEvaluationEvent.Finding("model-1",
                            ArchitectureEvaluationEvent.Severity.CRITICAL, "capacity",
                            "The database is undersized", "detail",
                            "peak utilisation 140%", "add a replica", List.of("db"))),
                    null, 1, 1, 1, "chatty"));

            ArchitectureEvaluationEvent published = service(chatty).evaluate(summary);

            assertSoftly(softly -> {
                softly.assertThat(published.findings())
                        .hasSize(expected.findings().size() + 1);
                softly.assertThat(published.findings().subList(0, expected.findings().size()))
                        .as("the arithmetic's findings come first and unaltered")
                        .isEqualTo(expected.findings());
                softly.assertThat(published.findings())
                        .anySatisfy(finding -> assertThat(finding.id()).isEqualTo("model-1"));
            });
        }

        @Test
        @DisplayName("bottlenecks are ordered worst-first, because a reader reads top-down")
        void bottlenecksAreOrdered() {
            SimulationSummaryEvent summary = Summaries.summary()
                    .nodes(Summaries.Node.withP99("db", 900),
                            new Summaries.Node("cache", NodeType.CACHE.getValue(),
                                    20, 30, 40, 40, 0, 0, 100, 0.3, 0.05, 0, 60))
                    .architecture(Summaries.graph("load_balancer:lb", "service:api", "database:db"))
                    .build();

            ArchitectureEvaluationEvent published =
                    service(new ModelConfiguration.NoModelNarrativeModel()).evaluate(summary);

            assertThat(published.bottlenecks()).isNotEmpty();
            assertThat(published.bottlenecks().get(0).observedValue())
                    .isGreaterThanOrEqualTo(900.0);
        }
    }

    @Nested
    @DisplayName("degrading")
    class Degrading {

        @Test
        @DisplayName("no model at all still publishes a complete evaluation")
        void noModelStillEvaluates() {
            SimulationSummaryEvent summary = badRun();
            ArchitectureAnalysis expected = evaluator.analyse(summary);

            ArchitectureEvaluationEvent published =
                    service(new ModelConfiguration.NoModelNarrativeModel()).evaluate(summary);

            assertSoftly(softly -> {
                softly.assertThat(published.degraded()).isTrue();
                softly.assertThat(published.degradedReason())
                        .as("and says why, rather than leaving the reader to guess")
                        .isNotBlank();
                softly.assertThat(published.overallScore()).isEqualTo(expected.overallScore());
                softly.assertThat(published.findings()).isNotEmpty();
                softly.assertThat(published.model()).isEqualTo("none");
                softly.assertThat(published.summary())
                        .as("never empty: a blank summary reads as a bug on the page")
                        .isNotBlank();
            });
            verify(resultPublisher).publish(any(ArchitectureEvaluationEvent.class));
        }

        @Test
        @DisplayName("a model switched off behaves exactly like one that is absent")
        void disabledModel() {
            EvaluatorProperties disabled = new EvaluatorProperties();
            disabled.setModelEnabled(false);

            ArchitectureEvaluationEvent published = service(
                    new ModelConfiguration.NoModelNarrativeModel(), disabled).evaluate(badRun());

            assertThat(published.degraded()).isTrue();
        }

        @Test
        @DisplayName("a model that throws degrades the evaluation rather than failing the run")
        void throwingModelDegrades() {
            SimulationSummaryEvent summary = badRun();
            ArchitectureAnalysis expected = evaluator.analyse(summary);

            NarrativeModel broken = new NarrativeModel() {
                @Override
                public Narrative narrate(SimulationSummaryEvent s, ArchitectureAnalysis a,
                                         List<ArchitectureEvaluationEvent.KnowledgeReference> r) {
                    throw new IllegalStateException("provider returned 500");
                }

                @Override
                public String modelName() {
                    return "broken";
                }

                @Override
                public boolean available() {
                    return true;
                }
            };

            ArchitectureEvaluationEvent published = service(broken).evaluate(summary);

            assertSoftly(softly -> {
                softly.assertThat(published.degraded()).isTrue();
                softly.assertThat(published.overallScore())
                        .as("the measurements are unaffected by the narrative failing")
                        .isEqualTo(expected.overallScore());
                softly.assertThat(published.degradedReason()).contains("provider returned 500");
            });
            verify(resultPublisher).publish(any(ArchitectureEvaluationEvent.class));
        }

        @Test
        @DisplayName("a model that never answers does not hold up the evaluation")
        void timingOutModelDoesNotBlock() {
            // The arithmetic is already computed and waiting. Making the reader wait minutes
            // for prose about it is strictly worse than publishing the measurements now.
            EvaluatorProperties impatient = new EvaluatorProperties();
            impatient.setModelTimeoutMillis(50);

            NarrativeModel hanging = new NarrativeModel() {
                @Override
                public Narrative narrate(SimulationSummaryEvent s, ArchitectureAnalysis a,
                                         List<ArchitectureEvaluationEvent.KnowledgeReference> r) {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Narrative.unavailable("eventually");
                }

                @Override
                public String modelName() {
                    return "hanging";
                }

                @Override
                public boolean available() {
                    return true;
                }
            };

            long startedNanos = System.nanoTime();
            ArchitectureEvaluationEvent published = service(hanging, impatient).evaluate(badRun());
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;

            assertSoftly(softly -> {
                softly.assertThat(published.degraded()).isTrue();
                softly.assertThat(published.degradedReason()).contains("did not respond");
                softly.assertThat(published.overallScore()).isGreaterThan(0);
                softly.assertThat(elapsedMs)
                        .as("bounded by the configured budget, not by the model's patience")
                        .isLessThan(10_000);
            });
        }

        @Test
        @DisplayName("a retrieval shortfall is disclosed rather than silently empty")
        void retrievalShortfallIsDisclosed() {
            when(retriever.retrieve(any(), any(Integer.class))).thenReturn(
                    new KnowledgeRetriever.Retrieval(List.of(),
                            "Reference architectures could not be retrieved: the knowledge base "
                                    + "was unreachable."));

            ArchitectureEvaluationEvent published =
                    service(new ModelConfiguration.NoModelNarrativeModel()).evaluate(badRun());

            assertThat(published.degradedReason())
                    .as("'no references' and 'could not fetch references' are different answers")
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("publishing")
    class Publishing {

        @Test
        @DisplayName("the result is published and the lifecycle is announced")
        void publishesAndAnnounces() {
            service(new ModelConfiguration.NoModelNarrativeModel()).evaluate(badRun());

            verify(resultPublisher).publish(any(ArchitectureEvaluationEvent.class));
            verify(lifecycle).started(any(), anyString());
            verify(lifecycle).completed(any(), anyString(),
                    ArgumentMatchers.eq(true), anyString());
        }

        @Test
        @DisplayName("the event carries the run's identity so it can be correlated back")
        void carriesIdentity() {
            SimulationSummaryEvent summary = badRun();

            ArchitectureEvaluationEvent published =
                    service(new ModelConfiguration.NoModelNarrativeModel()).evaluate(summary);

            assertSoftly(softly -> {
                softly.assertThat(published.simulationId()).isEqualTo(summary.simulationId());
                softly.assertThat(published.submissionId()).isEqualTo(summary.submissionId());
                softly.assertThat(published.correlationId()).isEqualTo(summary.correlationId());
                softly.assertThat(published.evaluationId()).isNotBlank();
                softly.assertThat(published.evaluatedAt()).isNotNull();
            });
        }
    }
}