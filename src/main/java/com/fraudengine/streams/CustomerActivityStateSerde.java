package com.fraudengine.streams;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

// Plain JSON Serde for the "customer-activity-store" value type — not Protobuf/Schema
// Registry like the external-facing topics (transactions.raw/.flagged/.passed etc).
// This state is purely internal to this one Kafka Streams app, backing only its own
// auto-managed changelog topic; it's never a cross-service contract, so it doesn't need
// the ceremony the external topics use.
public class CustomerActivityStateSerde implements Serde<CustomerActivityState> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Serializer<CustomerActivityState> serializer = new Serializer<>() {
        @Override
        public byte[] serialize(String topic, CustomerActivityState data) {
            if (data == null) {
                return null;
            }
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize CustomerActivityState", e);
            }
        }
    };

    private final Deserializer<CustomerActivityState> deserializer = new Deserializer<>() {
        @Override
        public CustomerActivityState deserialize(String topic, byte[] data) {
            if (data == null || data.length == 0) {
                return null;
            }
            try {
                return MAPPER.readValue(data, CustomerActivityState.class);
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize CustomerActivityState", e);
            }
        }
    };

    @Override
    public Serializer<CustomerActivityState> serializer() {
        return serializer;
    }

    @Override
    public Deserializer<CustomerActivityState> deserializer() {
        return deserializer;
    }
}
