package com.archforge.simulation.event;

import com.archforge.contracts.canvas.NodeType;
import com.archforge.contracts.event.TelemetryEvent;
import com.archforge.contracts.lifecycle.MetricNames;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How many samples the sender claims the broker accepted.
 *
 * <p>This number is the whole basis of the drop accounting one layer up. Everything
 * downstream — {@code telemetry.samples.dropped}, the per-run incomplete-trace warning,
 * the {@code telemetryComplete} flag on a run's terminal event — is derived from it, so
 * an error here does not lose samples, it loses <em>knowledge</em> that samples were lost.
 * A trace missing samples while reporting itself complete is the failure mode this whole
 * service exists to eliminate, and it is reachable by getting one subtraction wrong.
 *
 * <p>The defect this file was written for: the accepted count subtracted the
 * serialisation failures a second time, having already excluded them by counting from the
 * list of futures. Nothing tested the class, so it survived review twice.
 */
class KafkaTelemetrySenderTest {

    private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

    /** Ticks are unique per sample, which is the handle both fakes key on. */
    private static final Pattern TICK = Pattern.compile("\"tick\":(\\d+)");

    /** Fails to serialise exactly the samples listed, so S > 0 needs no extra machinery. */
    private static final class PickyObjectMapper extends ObjectMapper {
        private final Set<Integer> poison;

        PickyObjectMapper(Set<Integer> poison) {
            this.poison = poison;
            // Without this a bare ObjectMapper cannot write the sample's Instant at all, so
            // every sample would "fail to serialise", futures would be empty and every
            // count in this file would be zero -- a suite that asserts the arithmetic
            // while measuring nothing. Production gets this from ContractJacksonConfig.
            registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        }

        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            if (value instanceof TelemetryEvent event && poison.contains((int) event.tick())) {
                throw new JsonProcessingException("simulated serialisation failure") { };
            }
            return super.writeValueAsString(value);
        }
    }

    /** Returns a completed-exceptionally future for the listed ticks, completed for the rest. */
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> templateFailing(Set<Integer> poison) {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            String payload = invocation.getArgument(2);
            Matcher matcher = TICK.matcher(payload);
            int tick = matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;

            CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
            if (poison.contains(tick)) {
                future.completeExceptionally(new IllegalStateException("broker refused"));
            } else {
                future.complete(mock(SendResult.class));
            }
            return future;
        });
        return template;
    }

    private static KafkaTelemetrySender sender(Set<Integer> serialiseFailures,
                                               Set<Integer> brokerRefusals) {
        return new KafkaTelemetrySender(templateFailing(brokerRefusals),
                new PickyObjectMapper(serialiseFailures), new SimpleMeterRegistry());
    }

    private static List<TelemetryEvent> samples(int count) {
        List<TelemetryEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            events.add(new TelemetryEvent("sim-1", "sub-1", "api", NodeType.SERVICE.getValue(),
                    MetricNames.LATENCY_MS, 20 + i, MetricNames.LATENCY_MS, i, "steady", false, WHEN));
        }
        return events;
    }

    private static Set<Integer> ticks(int... values) {
        Set<Integer> set = new java.util.LinkedHashSet<>();
        for (int value : values) {
            set.add(value);
        }
        return set;
    }

    @Test
    @DisplayName("every sample acknowledged means every sample accepted")
    void allAcknowledged() {
        assertThat(sender(Set.of(), Set.of()).send("simulation.telemetry", samples(10)))
                .isEqualTo(10);
    }

    @Test
    @DisplayName("a refused send is not counted as accepted")
    void refusedSendIsNotAccepted() {
        assertThat(sender(Set.of(), ticks(3, 7)).send("simulation.telemetry", samples(10)))
                .as("8 of 10 acknowledged")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("serialisation failures are excluded exactly once, not twice")
    void serialisationFailuresExcludedOnce() {
        // 10 samples, 2 of which never reach the producer, and all 8 that were sent are
        // acknowledged. Accepted must be 8.
        //
        // Two wrong answers are live here and both look plausible on review:
        //   futures.size() - failures - acked -> 8 - 2 - 0 = 6   (failures counted twice)
        //   events.size()  - acked            -> 10 - 0    = 10  (failures called published)
        assertThat(sender(ticks(2, 5), Set.of()).send("simulation.telemetry", samples(10)))
                .as("the two samples that never reached the producer are not 'accepted'")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("serialisation and send failures add up rather than overlap")
    void bothKindsOfFailure() {
        // 4 could not be serialised, 2 more were refused by the broker. Accepted is the 4
        // that both survived.
        assertThat(sender(ticks(0, 1, 2, 3), ticks(5, 9)).send("simulation.telemetry", samples(10)))
                .as("10 offered, 6 lost, 4 accepted")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("a batch in which nothing could be serialised accepts nothing, without going negative")
    void allSerialisationFailures() {
        assertThat(sender(ticks(0, 1, 2, 3), Set.of()).send("simulation.telemetry", samples(4)))
                .as("never below zero: the publisher subtracts this from the batch size, and a "
                        + "negative would skip the drop loop entirely")
                .isZero();
    }

    @Test
    @DisplayName("an empty batch accepts nothing")
    void emptyBatch() {
        assertThat(sender(Set.of(), Set.of()).send("simulation.telemetry", List.of())).isZero();
    }

    @Test
    @DisplayName("every accepted count is a whole number of samples the broker could have taken")
    void accountingAlwaysAddsUp() {
        // The publisher computes lost = offered - accepted and then walks the tail of the
        // batch. If accepted ever exceeds the batch or the sum of the two does not equal it,
        // samples are lost without being counted. Swept across every combination rather than
        // asserted for one, because the arithmetic has been wrong in three different ways.
        for (int serialiseFailures = 0; serialiseFailures <= 5; serialiseFailures++) {
            for (int refusals = 0; refusals <= 10 - serialiseFailures; refusals++) {
                Set<Integer> poison = serialiseFailures == 0
                        ? Set.of()
                        : ticks(java.util.stream.IntStream.range(0, serialiseFailures).toArray());
                Set<Integer> refused = refusals == 0
                        ? Set.of()
                        : ticks(java.util.stream.IntStream
                                .range(serialiseFailures, serialiseFailures + refusals).toArray());

                int accepted = sender(poison, refused).send("simulation.telemetry", samples(10));
                int offered = 10;

                assertThat(accepted)
                        .as("accepted with %d unserialisable and %d refused", serialiseFailures, refusals)
                        .isBetween(0, offered);
                assertThat(offered - accepted)
                        .as("every lost sample is accounted for: %d serialise + %d refused",
                                serialiseFailures, refusals)
                        .isEqualTo(serialiseFailures + refusals);
            }
        }
    }
}