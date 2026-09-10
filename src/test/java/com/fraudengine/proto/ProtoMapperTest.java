package com.fraudengine.proto;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionType;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoMapperTest {

    // ---- toTransactionEntity ----

    @Test
    void toTransactionEntity_mapsAllFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp ts = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();

        TransactionEventProto.TransactionEvent proto = TransactionEventProto.TransactionEvent.newBuilder()
                .setTransactionId(id.toString())
                .setCustomerId("CUST_01")
                .setMerchantId("MERCH_01")
                .setAmount("1500.00")
                .setCurrency("ZAR")
                .setCategory("RETAIL")
                .setTransactionType(TransactionEventProto.TransactionType.CARD_PRESENT)
                .setLocation("Cape Town")
                .setLatitude(-33.9249)
                .setLongitude(18.4241)
                .setTimestamp(ts)
                .build();

        Transaction tx = ProtoMapper.toTransactionEntity(proto);

        assertThat(tx.getId()).isEqualTo(id);
        assertThat(tx.getCustomerId()).isEqualTo("CUST_01");
        assertThat(tx.getMerchantId()).isEqualTo("MERCH_01");
        assertThat(tx.getAmount()).isEqualByComparingTo("1500.00");
        assertThat(tx.getCurrency()).isEqualTo("ZAR");
        assertThat(tx.getCategory()).isEqualTo("RETAIL");
        assertThat(tx.getTransactionType()).isEqualTo(TransactionType.CARD_PRESENT);
        assertThat(tx.getLocation()).isEqualTo("Cape Town");
        assertThat(tx.getLatitude()).isEqualTo(-33.9249);
        assertThat(tx.getLongitude()).isEqualTo(18.4241);
        assertThat(tx.getTimestamp()).isEqualTo(Instant.ofEpochSecond(now.getEpochSecond(), now.getNano()));
    }

    @Test
    void toTransactionEntity_zeroLatLon_treatedAsAbsent() {
        // proto3 default for double is 0.0, so the Gulf of Guinea is treated as "no coordinates"
        TransactionEventProto.TransactionEvent proto = baseProto()
                .setLatitude(0.0).setLongitude(0.0).build();

        Transaction tx = ProtoMapper.toTransactionEntity(proto);

        assertThat(tx.getLatitude()).isNull();
        assertThat(tx.getLongitude()).isNull();
    }

    @Test
    void toTransactionEntity_nonZeroCoordinates_preserved() {
        TransactionEventProto.TransactionEvent proto = baseProto()
                .setLatitude(51.5074).setLongitude(-0.1278).build();

        Transaction tx = ProtoMapper.toTransactionEntity(proto);

        assertThat(tx.getLatitude()).isEqualTo(51.5074);
        assertThat(tx.getLongitude()).isEqualTo(-0.1278);
    }

    @Test
    void toTransactionEntity_timestampNeverSet_fallsBackToNow() {
        // hasTimestamp() is false only when the producer genuinely never set the field:
        // proto3 message fields track real presence, unlike scalars. baseProto() always
        // sets it, so build one from scratch without calling setTimestamp(...) at all.
        TransactionEventProto.TransactionEvent proto = TransactionEventProto.TransactionEvent.newBuilder()
                .setTransactionId(UUID.randomUUID().toString())
                .setCustomerId("C")
                .setMerchantId("M")
                .setAmount("100.00")
                .setCurrency("ZAR")
                .setTransactionType(TransactionEventProto.TransactionType.CARD_NOT_PRESENT)
                .build();
        assertThat(proto.hasTimestamp()).isFalse();

        Instant before = Instant.now();
        Transaction tx = ProtoMapper.toTransactionEntity(proto);
        Instant after = Instant.now();

        assertThat(tx.getTimestamp()).isBetween(before, after);
    }

    @Test
    void toTransactionEntity_timestampExplicitlySetToEpoch_preservedNotTreatedAsMissing() {
        // Contrast with the fallback above: an explicitly-set epoch (seconds=0, nanos=0) is
        // real presence (hasTimestamp() true) and must be preserved as-is, not silently
        // reinterpreted as "now".
        TransactionEventProto.TransactionEvent proto = baseProto()
                .setTimestamp(Timestamp.newBuilder().setSeconds(0).setNanos(0).build())
                .build();
        assertThat(proto.hasTimestamp()).isTrue();

        Transaction tx = ProtoMapper.toTransactionEntity(proto);

        assertThat(tx.getTimestamp()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void toTransactionEntity_emptyCategory_mappedToNull() {
        TransactionEventProto.TransactionEvent proto = baseProto().setCategory("").build();

        Transaction tx = ProtoMapper.toTransactionEntity(proto);

        assertThat(tx.getCategory()).isNull();
    }

    @Test
    void toTransactionEntity_emptyLocation_mappedToNull() {
        TransactionEventProto.TransactionEvent proto = baseProto().setLocation("").build();

        Transaction tx = ProtoMapper.toTransactionEntity(proto);

        assertThat(tx.getLocation()).isNull();
    }

    // ---- toDomainType (via toTransactionEntity) ----

    @Test
    void transactionType_contactless_mapped() {
        Transaction tx = ProtoMapper.toTransactionEntity(
                baseProto().setTransactionType(TransactionEventProto.TransactionType.CONTACTLESS).build());
        assertThat(tx.getTransactionType()).isEqualTo(TransactionType.CONTACTLESS);
    }

    @Test
    void transactionType_atm_mapped() {
        Transaction tx = ProtoMapper.toTransactionEntity(
                baseProto().setTransactionType(TransactionEventProto.TransactionType.ATM).build());
        assertThat(tx.getTransactionType()).isEqualTo(TransactionType.ATM);
    }

    @Test
    void transactionType_unspecified_defaultsToCardNotPresent() {
        Transaction tx = ProtoMapper.toTransactionEntity(
                baseProto().setTransactionType(TransactionEventProto.TransactionType.TRANSACTION_TYPE_UNSPECIFIED).build());
        assertThat(tx.getTransactionType()).isEqualTo(TransactionType.CARD_NOT_PRESENT);
    }

    // ---- toClearedProto ----

    @Test
    void toClearedProto_roundTripsAllFields() {
        Instant now = Instant.now();
        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID())
                .customerId("CUST_02")
                .merchantId("MERCH_02")
                .amount(new BigDecimal("250.50"))
                .currency("USD")
                .transactionType(TransactionType.CONTACTLESS)
                .timestamp(now)
                .build();

        ClearedTransactionEventProto.ClearedTransactionEvent proto = ProtoMapper.toClearedProto(tx);

        assertThat(proto.getTransactionId()).isEqualTo(tx.getId().toString());
        assertThat(proto.getCustomerId()).isEqualTo("CUST_02");
        assertThat(proto.getMerchantId()).isEqualTo("MERCH_02");
        assertThat(proto.getAmount()).isEqualTo("250.50");
        assertThat(proto.getCurrency()).isEqualTo("USD");
        assertThat(proto.getTransactionType()).isEqualTo(TransactionEventProto.TransactionType.CONTACTLESS);
        assertThat(proto.getTimestamp().getSeconds()).isEqualTo(now.getEpochSecond());
    }

    @Test
    void toClearedProto_nullTransactionType_defaultsToCardNotPresent() {
        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID()).customerId("C").merchantId("M")
                .amount(BigDecimal.TEN).currency("ZAR").transactionType(null).timestamp(Instant.now())
                .build();

        ClearedTransactionEventProto.ClearedTransactionEvent proto = ProtoMapper.toClearedProto(tx);

        assertThat(proto.getTransactionType()).isEqualTo(TransactionEventProto.TransactionType.CARD_NOT_PRESENT);
    }

    // ---- toFraudulentProto ----

    @Test
    void toFraudulentProto_mapsTransactionAndAssessmentFields() {
        Instant now = Instant.now();
        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_03").merchantId("M")
                .amount(BigDecimal.TEN).currency("ZAR").timestamp(now).build();

        FraudAssessment assessment = new FraudAssessment();
        assessment.setRiskScore(85);
        assessment.setAssessedAt(now);

        FraudulentTransactionEventProto.FraudulentTransactionEvent proto =
                ProtoMapper.toFraudulentProto(tx, assessment);

        assertThat(proto.getTransactionId()).isEqualTo(tx.getId().toString());
        assertThat(proto.getCustomerId()).isEqualTo("CUST_03");
        assertThat(proto.getRiskScore()).isEqualTo(85);
        assertThat(proto.getAssessedAt().getSeconds()).isEqualTo(now.getEpochSecond());
    }

    // ---- toPendingReviewProto ----

    @Test
    void toPendingReviewProto_mapsTransactionAndAssessmentFields() {
        Instant now = Instant.now();
        Transaction tx = Transaction.builder()
                .id(UUID.randomUUID()).customerId("CUST_04").merchantId("MERCH_04")
                .amount(new BigDecimal("620.00")).currency("ZAR")
                .transactionType(TransactionType.CARD_PRESENT).timestamp(now).build();

        FraudAssessment assessment = new FraudAssessment();
        assessment.setRiskScore(22);
        assessment.setAssessedAt(now);

        PendingReviewTransactionEventProto.PendingReviewTransactionEvent proto =
                ProtoMapper.toPendingReviewProto(tx, assessment);

        assertThat(proto.getTransactionId()).isEqualTo(tx.getId().toString());
        assertThat(proto.getCustomerId()).isEqualTo("CUST_04");
        assertThat(proto.getMerchantId()).isEqualTo("MERCH_04");
        assertThat(proto.getAmount()).isEqualTo("620.00");
        assertThat(proto.getCurrency()).isEqualTo("ZAR");
        assertThat(proto.getTransactionType()).isEqualTo(TransactionEventProto.TransactionType.CARD_PRESENT);
        assertThat(proto.getRiskScore()).isEqualTo(22);
        assertThat(proto.getAssessedAt().getSeconds()).isEqualTo(now.getEpochSecond());
    }

    // ---- helpers ----

    private TransactionEventProto.TransactionEvent.Builder baseProto() {
        return TransactionEventProto.TransactionEvent.newBuilder()
                .setTransactionId(UUID.randomUUID().toString())
                .setCustomerId("C")
                .setMerchantId("M")
                .setAmount("100.00")
                .setCurrency("ZAR")
                .setTransactionType(TransactionEventProto.TransactionType.CARD_NOT_PRESENT)
                .setTimestamp(Timestamp.newBuilder()
                        .setSeconds(Instant.now().getEpochSecond()).build());
    }
}
