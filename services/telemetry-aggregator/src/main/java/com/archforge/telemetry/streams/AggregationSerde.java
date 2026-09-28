package com.archforge.telemetry.streams;

import com.archforge.telemetry.streams.TelemetryStreamsTopology.Aggregation;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.nio.charset.StandardCharsets;

public class AggregationSerde implements Serde<Aggregation> {

    @Override
    public Serializer<Aggregation> serializer() {
        return (topic, data) -> {
            if (data == null) return null;
            String str = String.format("%.6f,%.6f,%.6f,%d,%s",
                    data.getMin(), data.getMax(), data.getSum(), data.getCount(), data.getUnit());
            return str.getBytes(StandardCharsets.UTF_8);
        };
    }

    @Override
    public Deserializer<Aggregation> deserializer() {
        return (topic, data) -> {
            if (data == null) return null;
            String str = new String(data, StandardCharsets.UTF_8);
            String[] parts = str.split(",", 5);
            Aggregation agg = new Aggregation();
            agg.min = Double.parseDouble(parts[0]);
            agg.max = Double.parseDouble(parts[1]);
            agg.sum = Double.parseDouble(parts[2]);
            agg.count = Long.parseLong(parts[3]);
            agg.unit = parts[4];
            return agg;
        };
    }
}
