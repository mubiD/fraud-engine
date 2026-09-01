package com.fraudengine.proto;

import com.fraudengine.model.FraudAssessment;
import com.fraudengine.model.Transaction;
import com.fraudengine.model.enums.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ProtoMapper {

    private ProtoMapper() {}

    public static Transaction toTransactionEntity(TransactionEventProto.TransactionEvent proto) {
        return Transaction.builder()
                .id(UUID.fromString(proto.getTransactionId()))
                .customerId(proto.getCustomerId())
                .merchantId(proto.getMerchantId())
                .amount(new BigDecimal(proto.getAmount()))
                .currency(proto.getCurrency())
                .category(nullIfEmpty(proto.getCategory()))
                .transactionType(toDomainType(proto.getTransactionType()))
                .location(nullIfEmpty(proto.getLocation()))
                // proto3 double defaults to 0.0; treat (0,0) as absent (Gulf of Guinea)
                .latitude(proto.getLatitude() != 0.0 ? proto.getLatitude() : null)
                .longitude(proto.getLongitude() != 0.0 ? proto.getLongitude() : null)
                .timestamp(toInstant(proto.getTimestamp()))
                .build();
    }

    public static ClearedTransactionEventProto.ClearedTransactionEvent toClearedProto(Transaction tx) {
        return ClearedTransactionEventProto.ClearedTransactionEvent.newBuilder()
                .setTransactionId(tx.getId().toString())
                .setCustomerId(tx.getCustomerId())
                .setMerchantId(tx.getMerchantId())
                .setAmount(tx.getAmount().toPlainString())
                .setCurrency(tx.getCurrency())
                .setTransactionType(toProtoType(tx.getTransactionType()))
                .setTimestamp(toTimestamp(tx.getTimestamp()))
                .build();
    }

    public static FraudulentTransactionEventProto.FraudulentTransactionEvent toFraudulentProto(
            Transaction tx, FraudAssessment assessment) {
        return FraudulentTransactionEventProto.FraudulentTransactionEvent.newBuilder()
                .setTransactionId(tx.getId().toString())
                .setCustomerId(tx.getCustomerId())
                .setRiskScore(assessment.getRiskScore())
                .setAssessedAt(toTimestamp(assessment.getAssessedAt()))
                .build();
    }

    public static PendingReviewTransactionEventProto.PendingReviewTransactionEvent toPendingReviewProto(
            Transaction tx, FraudAssessment assessment) {
        return PendingReviewTransactionEventProto.PendingReviewTransactionEvent.newBuilder()
                .setTransactionId(tx.getId().toString())
                .setCustomerId(tx.getCustomerId())
                .setMerchantId(tx.getMerchantId())
                .setAmount(tx.getAmount().toPlainString())
                .setCurrency(tx.getCurrency())
                .setTransactionType(toProtoType(tx.getTransactionType()))
                .setRiskScore(assessment.getRiskScore())
                .setAssessedAt(toTimestamp(assessment.getAssessedAt()))
                .build();
    }

    private static TransactionType toDomainType(TransactionEventProto.TransactionType proto) {
        return switch (proto) {
            case CARD_PRESENT  -> TransactionType.CARD_PRESENT;
            case CONTACTLESS   -> TransactionType.CONTACTLESS;
            case ATM           -> TransactionType.ATM;
            default            -> TransactionType.CARD_NOT_PRESENT;
        };
    }

    private static TransactionEventProto.TransactionType toProtoType(TransactionType domain) {
        if (domain == null) return TransactionEventProto.TransactionType.CARD_NOT_PRESENT;
        return switch (domain) {
            case CARD_PRESENT  -> TransactionEventProto.TransactionType.CARD_PRESENT;
            case CONTACTLESS   -> TransactionEventProto.TransactionType.CONTACTLESS;
            case ATM           -> TransactionEventProto.TransactionType.ATM;
            default            -> TransactionEventProto.TransactionType.CARD_NOT_PRESENT;
        };
    }

    private static Instant toInstant(com.google.protobuf.Timestamp ts) {
        if (ts == null) return Instant.now();
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    private static com.google.protobuf.Timestamp toTimestamp(Instant instant) {
        if (instant == null) return com.google.protobuf.Timestamp.getDefaultInstance();
        return com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static String nullIfEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
