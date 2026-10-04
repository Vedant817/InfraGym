package com.archforge.ai.analysis;

import com.archforge.ai.analysis.DeterministicEvaluator.Thresholds;
import com.archforge.ai.support.Summaries;
import com.archforge.ai.support.Summaries.Builder;
import com.archforge.ai.support.Summaries.Node;
import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.ArchitectureEvaluationEvent;
import com.archforge.contracts.event.SimulationSummaryEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * The arithmetic a reader is asked to trust.
 *
 * <p>Every test here asserts an <em>identity or an ordering</em> rather than a magic number
 * wherever it can. A test pinned to "the score is exactly 63" breaks the moment a weight is
 * retuned and tells the reader nothing about whether the arithmetic is right; "the score fell
 * by more than the saturation penalty alone" survives retuning and pins the behaviour.
 */
class DeterministicEvaluatorTest {

    private final DeterministicEvaluator evaluator = new DeterministicEvaluator();

    @Nested
    @DisplayName("refusing to work without measurements")
    class Refuses {

        @Test
        @DisplayName("a null summary is refused rather than evaluated")
        void nullIsRefused() {
            // An evaluation with no measurements behind it is a fabrication. Failing loudly
            // here is the difference between a bug and a plausible-looking report.
            assertThatThrownBy(() -> evaluator.analyse(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fabrication");
        }

        @Test
        @DisplayName("a run with no components is scored, and says it has nothing to assess")
        void emptyRunIsHonest() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary().build());

            assertSoftly(softly -> {
                softly.assertThat(analysis.bottlenecks())
                        .as("an unmeasured architecture is a finding, not a pass")
                        .anySatisfy(b -> assertThat(b.metric()).isEqualTo("components_reported"));
                // Not a high score: a perfect score for an architecture that was not measured
                // is indistinguishable on the page from a design that genuinely passed.
                softly.assertThat(analysis.overallScore()).isLessThan(100);
            });
        }
    }

    @Nested
    @DisplayName("latency")
    class Latency {

        @Test
        @DisplayName("a component inside the target costs nothing")
        void insideTheTarget() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .nodes(Node.healthy("api", NodeType.SERVICE.getValue(), 20),
                            Node.healthy("db", NodeType.DATABASE.getValue(), 40))
                    .build());

            assertThat(analysis.findings())
                    .as("both components are within the 250 ms target")
                    .noneSatisfy(finding -> assertThat(finding.category()).isEqualTo("latency"));
        }

        @Test
        @DisplayName("a component past the target is found, and the cost grows with the breach")
        void penaltyGrowsWithTheBreach() {
            int mild = evaluator.analyse(Summaries.summary()
                    .node(Node.withP99("db", 300)).build()).overallScore();
            int severe = evaluator.analyse(Summaries.summary()
                    .node(Node.withP99("db", 900)).build()).overallScore();

            assertThat(mild).isLessThan(100);
            assertThat(severe)
                    .as("a P99 nearly four times the target must cost more than one just over it")
                    .isLessThan(mild);
        }

        @Test
        @DisplayName("the full latency weight is spent at twice the target, not beyond it")
        void penaltySaturates() {
            // A pathological outlier must not drive the score to zero and make the rest of the
            // report meaningless. Past twice the target the latency cost is capped.
            int atFourTimes = evaluator.analyse(Summaries.summary()
                    .node(Node.withP99("db", 1000)).build()).overallScore();
            int atTenTimes = evaluator.analyse(Summaries.summary()
                    .node(Node.withP99("db", 2500)).build()).overallScore();

            assertThat(atTenTimes)
                    .as("beyond twice the target there is nothing more to deduct for latency")
                    .isEqualTo(atFourTimes);
        }

        @Test
        @DisplayName("the finding names the component and the numbers that caused it")
        void findingIsCheckable() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .node(Node.withP99("db", 900)).build());

            ArchitectureEvaluationEvent.Bottleneck bottleneck = analysis.bottlenecks().stream()
                    .filter(b -> "db".equals(b.nodeId()))
                    .findFirst()
                    .orElseThrow();

            assertSoftly(softly -> {
                softly.assertThat(bottleneck.nodeId()).isEqualTo("db");
                softly.assertThat(bottleneck.metric()).isEqualTo("p99_latency_ms");
                softly.assertThat(bottleneck.observedValue())
                        .as("a reader can check this against the summary")
                        .isEqualTo(900.0);
                softly.assertThat(bottleneck.thresholdValue()).isEqualTo(250.0);
                softly.assertThat(bottleneck.unit()).isEqualTo("ms");
                softly.assertThat(bottleneck.explanation()).contains("900");
            });
        }

        @Test
        @DisplayName("a component that reported no samples is not reported as fast")
        void noSamplesIsNotFast() {
            // The worst failure mode available here: a component nobody measured would have a
            // P99 of zero, and zero is the best-looking number on the page.
            Node silent = new Node("worker", NodeType.SERVICE.getValue(),
                    0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0);

            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .nodes(Node.healthy("api", NodeType.SERVICE.getValue(), 20), silent)
                    .build());

            assertSoftly(softly -> {
                softly.assertThat(analysis.strengths())
                        .as("'worker' must never appear as a strength")
                        .noneSatisfy(strength -> assertThat(strength).contains("worker"));
                softly.assertThat(analysis.findings())
                        .anySatisfy(finding -> {
                            assertThat(finding.relatedNodeIds()).contains("worker");
                            assertThat(finding.title()).contains("latency sample");
                        });
            });
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {

        @Test
        @DisplayName("an error spike is found even when the run-wide average is clean")
        void spikeIsFound() {
            // The peak/avg gap is the whole signal: a component that averages 0.2% errors and
            // peaks at 25% failed once under load, which an average hides completely.
            Node spiky = new Node("db", NodeType.DATABASE.getValue(),
                    100, 200, 250, 250, 0.002, 0.25, 100, 0.9, -1, 0, 60);

            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .node(spiky).build());

            ArchitectureEvaluationEvent.Bottleneck bottleneck = analysis.bottlenecks().stream()
                    .filter(b -> "peak_error_rate".equals(b.metric()))
                    .findFirst()
                    .orElseThrow();
            assertThat(bottleneck.explanation())
                    .as("the finding must point at the gap, not just the peak")
                    .contains("spike");
        }

        @Test
        @DisplayName("an error rate inside the target costs nothing")
        void insideTarget() {
            Node clean = new Node("db", NodeType.DATABASE.getValue(),
                    50, 100, 120, 120, 0.001, 0.005, 100, 0.5, -1, 0, 60);

            assertThat(evaluator.analyse(Summaries.summary().node(clean).build()).bottlenecks())
                    .noneSatisfy(b -> assertThat(b.metric()).isEqualTo("peak_error_rate"));
        }
    }

    @Nested
    @DisplayName("capacity")
    class Capacity {

        @Test
        @DisplayName("a saturated component is found and named")
        void saturationIsFound() {
            Node saturated = new Node("db", NodeType.DATABASE.getValue(),
                    300, 400, 450, 450, 0, 0, 200, 1.4, -1, 0, 60);

            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .node(saturated).build());

            assertSoftly(softly -> {
                softly.assertThat(analysis.bottlenecks())
                        .anySatisfy(b -> {
                            assertThat(b.metric()).isEqualTo("peak_utilization");
                            assertThat(b.explanation()).contains("db");
                        });
                softly.assertThat(analysis.overallScore()).isLessThan(100);
            });
        }

        @Test
        @DisplayName("more saturated components cost more")
        void moreSaturationCostsMore() {
            Node hot = Node.withP99("db", 20);
            Node one = new Node("db", NodeType.DATABASE.getValue(),
                    20, 30, 40, 40, 0, 0, 100, 1.2, -1, 0, 60);
            Node two = new Node("cache", NodeType.CACHE.getValue(),
                    10, 20, 25, 25, 0, 0, 100, 1.5, 0.9, 0, 60);

            int oneSaturated = evaluator.analyse(Summaries.summary().nodes(hot, one).build())
                    .overallScore();
            int twoSaturated = evaluator.analyse(Summaries.summary().nodes(hot, one, two).build())
                    .overallScore();

            assertThat(twoSaturated).isLessThan(oneSaturated);
        }
    }

    @Nested
    @DisplayName("backlog and the accounting identity")
    class Backlog {

        @Test
        @DisplayName("requests left queued are not viable, whatever the latency looks like")
        void backlogIsNotViable() {
            // A design can have a fine P99 and still fail the scenario outright: the requests
            // were never rejected, they were never served, and they were still waiting at the
            // end. Calling that "acceptable latency" would be the worst reading of the data.
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .nodes(Node.healthy("api", NodeType.SERVICE.getValue(), 20),
                            Node.healthy("db", NodeType.DATABASE.getValue(), 40))
                    .offered(17_260).queued(1_653)
                    .build());

            assertSoftly(softly -> {
                softly.assertThat(analysis.verdict()).isEqualTo("not viable under this scenario");
                softly.assertThat(analysis.bottlenecks())
                        .anySatisfy(b -> assertThat(b.metric()).isEqualTo("backlogged_requests"));
                softly.assertThat(analysis.caveats())
                        .as("the reader must be told these were queued, not lost")
                        .anySatisfy(caveat -> {
                            assertThat(caveat).contains("1653");
                            assertThat(caveat).contains("never served");
                        });
            });
        }

        @Test
        @DisplayName("the unserved requests lead the report, ahead of the structural notes")
        void unservedRequestsAreReportedFirst() {
            // Found by running the pipeline. A design with a cache, a queue and a database all
            // single-instance produced three "instance count is 1" findings ahead of the one
            // saying 1652 requests were never served -- so the reader met the structural notes
            // first and the reason the run failed last, if at all.
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .architecture(Summaries.graph("load_balancer:lb", "service:api",
                            "queue:queue", "worker:worker", "cache:cache", "database:db"))
                    .nodes(Node.healthy("lb", NodeType.LOAD_BALANCER.getValue(), 1),
                            Node.healthy("api", NodeType.SERVICE.getValue(), 20),
                            Node.healthy("worker", NodeType.SERVICE.getValue(), 30),
                            Node.healthy("queue", NodeType.QUEUE.getValue(), 10),
                            Node.healthy("cache", NodeType.CACHE.getValue(), 5),
                            Node.healthy("db", NodeType.DATABASE.getValue(), 40))
                    .offered(17_260).queued(1_652)
                    .build());

            assertSoftly(softly -> {
                softly.assertThat(analysis.bottlenecks())
                        .as("the backlog is the reason the verdict is what it is")
                        .first()
                        .satisfies(bottleneck ->
                                assertThat(bottleneck.metric()).isEqualTo("backlogged_requests"));
                softly.assertThat(analysis.bottlenecks())
                        .as("three single-instance components are one finding, not three")
                        .filteredOn(bottleneck -> bottleneck.metric().equals("redundancy"))
                        .hasSize(1);
                softly.assertThat(analysis.findings())
                        .filteredOn(finding -> finding.evidence().startsWith("redundancy ="))
                        .first()
                        .satisfies(finding -> assertThat(finding.detail())
                                .as("one finding that names all of them")
                                .contains("cache").contains("queue").contains("db"));
            });
        }

        @Test
        @DisplayName("a run with everything served and nothing failed is a measured strength")
        void fullyServedIsAStrength() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .node(Node.healthy("api", NodeType.SERVICE.getValue(), 20))
                    .offered(17_260).errors(0).queued(0)
                    .build());

            assertThat(analysis.strengths())
                    .anySatisfy(strength -> assertThat(strength).contains("17260"));
        }
    }

    @Nested
    @DisplayName("candour about what is not known")
    class Candour {

        @Test
        @DisplayName("an incomplete trace caps the score so it cannot read as clean")
        void incompleteTraceCapsTheScore() {
            Thresholds thresholds = Thresholds.defaults();
            int capped = evaluator.analyse(Summaries.summary()
                    .node(Node.healthy("api", NodeType.SERVICE.getValue(), 10))
                    .warnings(List.of("This summary was computed from an incomplete trace: "
                            + "the engine reported 400 undelivered telemetry sample(s)."))
                    .build()).overallScore();

            assertThat(capped)
                    .as("a score derived from part of a run is an estimate, and an estimate "
                            + "presented as 95/100 is a lie")
                    .isLessThanOrEqualTo((int) thresholds.incompleteTraceCap());
            assertThat(capped)
                    .as("and it must be lower than the same run measured completely")
                    .isLessThan(100);
        }

        @Test
        @DisplayName("a truncated run says how many ticks actually ran")
        void truncatedRunIsDisclosed() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .node(Node.healthy("api", NodeType.SERVICE.getValue(), 20))
                    .ticks(40, 60)
                    .build());

            assertThat(analysis.caveats())
                    .anySatisfy(caveat -> {
                        assertThat(caveat).contains("40");
                        assertThat(caveat).contains("60");
                    });
        }

        @Test
        @DisplayName("a complete, untruncated run carries no data caveats")
        void cleanRunHasNoCaveats() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .nodes(Node.healthy("api", NodeType.SERVICE.getValue(), 20),
                            Node.healthy("db", NodeType.DATABASE.getValue(), 40))
                    .architecture(Summaries.graph("load_balancer:lb", "service:api", "database:db"))
                    .offered(1000)
                    .build());

            assertThat(analysis.caveats()).isEmpty();
        }
    }

    @Nested
    @DisplayName("structure the measurements cannot see")
    class Structure {

        /** One load balancer in front of two databases: redundancy was provided. */
        private com.archforge.contracts.event.SimulationLifecycleEvent.ArchitectureDigest
                replicatedDatabases() {
            return new com.archforge.contracts.event.SimulationLifecycleEvent.ArchitectureDigest(
                    "replicated",
                    List.of(
                            new com.archforge.contracts.event.SimulationLifecycleEvent
                                    .ArchitectureDigest.NodeDigest("lb",
                                    NodeType.LOAD_BALANCER.getValue(), "lb"),
                            new com.archforge.contracts.event.SimulationLifecycleEvent
                                    .ArchitectureDigest.NodeDigest("db1",
                                    NodeType.DATABASE.getValue(), "db1"),
                            new com.archforge.contracts.event.SimulationLifecycleEvent
                                    .ArchitectureDigest.NodeDigest("db2",
                                    NodeType.DATABASE.getValue(), "db2")),
                    List.of(
                            new com.archforge.contracts.event.SimulationLifecycleEvent
                                    .ArchitectureDigest.EdgeDigest("e1", "lb", "db1", 1, 1000),
                            new com.archforge.contracts.event.SimulationLifecycleEvent
                                    .ArchitectureDigest.EdgeDigest("e2", "lb", "db2", 1, 1000)),
                    java.util.Map.of(), List.of());
        }

        @Test
        @DisplayName("a lone stateful component is a single point of failure")
        void loneDatabaseIsASpof() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .architecture(Summaries.graph("load_balancer:lb", "service:api", "database:db"))
                    .nodes(Node.healthy("lb", NodeType.LOAD_BALANCER.getValue(), 1),
                            Node.healthy("api", NodeType.SERVICE.getValue(), 20),
                            Node.healthy("db", NodeType.DATABASE.getValue(), 40))
                    .build());

            assertSoftly(softly -> {
                softly.assertThat(analysis.findings())
                        .anySatisfy(finding -> {
                            assertThat(finding.category()).isEqualTo("capacity");
                            assertThat(finding.relatedNodeIds()).contains("db");
                            assertThat(finding.detail()).contains("single point of failure");
                        });
                softly.assertThat(analysis.overallScore()).isLessThan(100);
            });
        }

        @Test
        @DisplayName("two databases means no single point of failure to report")
        void twoDatabasesIsNotASpof() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .architecture(replicatedDatabases())
                    .node(Node.healthy("db1", NodeType.DATABASE.getValue(), 40))
                    .build());

            assertThat(analysis.findings())
                    .as("replication was provided, so there is no single database to fail")
                    .noneSatisfy(finding -> assertThat(finding.evidence()).startsWith("redundancy ="));
        }

        @Test
        @DisplayName("a lone stateless component is not called a single point of failure")
        void loneServiceIsNotASpof() {
            // Only state whose loss is unrecoverable makes a single instance an availability
            // risk. Calling every lone component one dilutes the findings that matter.
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .architecture(Summaries.statelessGraph("lb", "api"))
                    .nodes(Node.healthy("lb", NodeType.LOAD_BALANCER.getValue(), 1),
                            Node.healthy("api", NodeType.SERVICE.getValue(), 20))
                    .build());

            assertThat(analysis.findings())
                    .noneSatisfy(finding -> assertThat(finding.evidence()).startsWith("redundancy ="));
        }

        @Test
        @DisplayName("one entry point means the service cannot be replaced without downtime")
        void singleEntryPoint() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .architecture(Summaries.graph("load_balancer:lb", "service:api", "database:db"))
                    .build());

            assertThat(analysis.findings())
                    .anySatisfy(finding -> {
                        assertThat(finding.evidence()).startsWith("entry_points =");
                        assertThat(finding.category()).isEqualTo("availability");
                    });
        }

        @Test
        @DisplayName("a design with no digest at all is assessed on measurements alone, and says so")
        void missingDigestIsDisclosed() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .node(Node.withP99("db", 900))
                    .build());

            assertSoftly(softly -> {
                softly.assertThat(analysis.overallScore())
                        .as("the measurements still produce a real score")
                        .isGreaterThan(0);
                softly.assertThat(analysis.bottlenecks())
                        .as("and the latency finding is unaffected by the missing graph")
                        .anySatisfy(b -> assertThat(b.metric()).isEqualTo("p99_latency_ms"));
            });
        }
    }

    @Nested
    @DisplayName("injected failures")
    class Failures {

        private SimulationSummaryEvent.FailureRecord unrecovered(String nodeId) {
            return new SimulationSummaryEvent.FailureRecord(nodeId, NodeType.DATABASE.getValue(),
                    "node_down", 10, 30, false, -1, List.of("api"), 900, 0.4);
        }

        @Test
        @DisplayName("a failure that never cleared is critical, and is not ignored")
        void unrecoveredFailureIsCritical() {
            // The previous evaluator never read FailureRecord at all, so a design whose
            // database failed and never came back was graded on its happy path.
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .nodes(Node.healthy("api", NodeType.SERVICE.getValue(), 20))
                    .failure(unrecovered("db"))
                    .build());

            assertThat(analysis.findings())
                    .anySatisfy(finding -> {
                        assertThat(finding.severity())
                                .isEqualTo(ArchitectureEvaluationEvent.Severity.CRITICAL);
                        assertThat(finding.category()).isEqualTo("resilience");
                        assertThat(finding.detail()).contains("never cleared");
                    });
        }

        @Test
        @DisplayName("a recovered failure reports how long the system was degraded")
        void recoveredFailureIsReported() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .nodes(Node.healthy("api", NodeType.SERVICE.getValue(), 20))
                    .failure(new SimulationSummaryEvent.FailureRecord("db",
                            NodeType.DATABASE.getValue(), "cpu_saturation", 10, 20,
                            true, 4500, List.of("api"), 900, 0.4))
                    .build());

            assertThat(analysis.findings())
                    .anySatisfy(finding -> {
                        assertThat(finding.category()).isEqualTo("resilience");
                        assertThat(finding.detail()).contains("4,500");
                    });
        }
    }

    @Nested
    @DisplayName("properties of the output itself")
    class OutputProperties {

        @Test
        @DisplayName("the score is always in range, however bad the run")
        void scoreIsAlwaysInRange() {
            Node catastrophic = new Node("db", NodeType.DATABASE.getValue(),
                    30_000, 60_000, 90_000, 90_000, 0.4, 0.9, 500, 3.0, 0.0, 5_000, 60);

            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .nodes(catastrophic)
                    .failure(new SimulationSummaryEvent.FailureRecord("db",
                            NodeType.DATABASE.getValue(), "node_down", 0, 60,
                            false, -1, List.of(), 90_000, 0.9))
                    .offered(100_000).errors(40_000).queued(50_000)
                    .build());

            assertThat(analysis.overallScore()).isBetween(0, 100);
            assertThat(analysis.verdict()).isEqualTo("not viable under this scenario");
        }

        @Test
        @DisplayName("every finding carries evidence and a recommendation")
        void findingsAreActionable() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .architecture(Summaries.graph("load_balancer:lb", "service:api", "database:db"))
                    .nodes(Node.withP99("db", 900),
                            new Node("api", NodeType.SERVICE.getValue(),
                                    20, 30, 40, 40, 0, 0, 100, 1.2, -1, 500, 60))
                    .offered(10_000).queued(2_000)
                    .build());

            assertThat(analysis.findings()).isNotEmpty();
            assertThat(analysis.findings()).allSatisfy(finding -> {
                assertThat(finding.evidence())
                        .as("a finding with no evidence is a guess wearing a measurement's clothes")
                        .isNotBlank();
                assertThat(finding.evidence()).contains("=");
                assertThat(finding.title()).isNotBlank();
                assertThat(finding.severity()).isNotNull();
            });
            assertThat(analysis.recommendations())
                    .as("a report that finds problems and proposes nothing is half a report")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("findings are ordered most-severe first")
        void findingsAreOrdered() {
            ArchitectureAnalysis analysis = evaluator.analyse(Summaries.summary()
                    .nodes(Node.withP99("db", 900),
                            new Node("cache", NodeType.CACHE.getValue(),
                                    20, 30, 40, 40, 0, 0, 100, 0.3, 0.05, 0, 60))
                    .architecture(Summaries.graph("load_balancer:lb", "service:api", "database:db"))
                    .build());

            List<ArchitectureEvaluationEvent.Severity> severities = analysis.findings().stream()
                    .map(ArchitectureEvaluationEvent.Finding::severity)
                    .toList();
            assertThat(severities)
                    .as("the reader reads top-down; burying a critical finding under a low one "
                            + "hides it")
                    .isSortedAccordingTo(java.util.Comparator.comparingInt(
                            ArchitectureEvaluationEvent.Severity::ordinal));
        }

        @Test
        @DisplayName("findings have stable ids so a re-evaluation replaces rather than duplicates")
        void findingIdsAreStable() {
            Builder builder = Summaries.summary().node(Node.withP99("db", 900));
            SimulationSummaryEvent first = builder.build();
            SimulationSummaryEvent second = builder.build();

            List<String> firstIds = evaluator.analyse(first).findings().stream()
                    .map(ArchitectureEvaluationEvent.Finding::id).toList();
            List<String> secondIds = evaluator.analyse(second).findings().stream()
                    .map(ArchitectureEvaluationEvent.Finding::id).toList();

            assertThat(firstIds).isEqualTo(secondIds).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("the analysis is immutable, and a caveat can be added by copying")
        void caveatsAreAdditive() {
            ArchitectureAnalysis original = evaluator.analyse(Summaries.summary()
                    .node(Node.withP99("db", 900)).build());

            ArchitectureAnalysis extended = original.withCaveat("references unavailable");

            assertSoftly(softly -> {
                softly.assertThat(original.caveats())
                        .as("the original is untouched")
                        .noneSatisfy(caveat -> assertThat(caveat).contains("references"));
                softly.assertThat(extended.caveats()).hasSize(original.caveats().size() + 1);
                softly.assertThat(extended.overallScore())
                        .as("adding a caveat must not change the arithmetic")
                        .isEqualTo(original.overallScore());
            });
        }

        @Test
        @DisplayName("a blank caveat is ignored rather than added as an empty string")
        void blankCaveatIsIgnored() {
            ArchitectureAnalysis analysis = evaluator.analyse(
                    Summaries.summary().node(Node.healthy("api", NodeType.SERVICE.getValue(), 20))
                            .build());

            assertThat(analysis.withCaveat("  ")).isSameAs(analysis);
        }
    }

    @Nested
    @DisplayName("thresholds")
    class ThresholdConfig {

        @Test
        @DisplayName("a stricter target makes the same run score worse")
        void stricterTargetScoresWorse() {
            Summaries.Node node = Node.withP99("db", 400);

            int lenient = new DeterministicEvaluator(Thresholds.defaults())
                    .analyse(Summaries.summary().node(node).build()).overallScore();
            int strict = new DeterministicEvaluator(new Thresholds(
                    200, 0.01, 35, 30, 20, 15, 0.20, 100, 60, 15))
                    .analyse(Summaries.summary().node(node).build()).overallScore();

            assertThat(strict).isLessThan(lenient);
        }

        @Test
        @DisplayName("a non-positive latency target is refused at construction")
        void invalidThresholdRefused() {
            // Reachable from configuration. A target of zero would make every non-negative
            // latency a breach, and a negative one would make the penalty arithmetic produce
            // a negative deduction -- a score above 100.
            assertThatThrownBy(() -> new Thresholds(0, 0.01, 35, 30, 20, 15, 0.2, 100, 60, 15))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
            assertThatThrownBy(() -> new Thresholds(250, 0, 35, 30, 20, 15, 0.2, 100, 60, 15))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }
    }
}