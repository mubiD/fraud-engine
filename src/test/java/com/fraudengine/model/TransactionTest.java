package com.fraudengine.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionTest {

    @Test
    void assignIdIfMissing_preservesCallerAssignedId() {
        UUID assigned = UUID.randomUUID();
        Transaction transaction = Transaction.builder().id(assigned).build();

        transaction.assignIdIfMissing();

        assertThat(transaction.getId()).isEqualTo(assigned);
    }

    @Test
    void assignIdIfMissing_generatesOneWhenOmitted() {
        Transaction transaction = Transaction.builder().build();
        assertThat(transaction.getId()).isNull();

        transaction.assignIdIfMissing();

        assertThat(transaction.getId()).isNotNull();
    }
}
