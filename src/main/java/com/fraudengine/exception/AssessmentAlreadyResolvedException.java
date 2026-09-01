package com.fraudengine.exception;

public class AssessmentAlreadyResolvedException extends RuntimeException {

    public AssessmentAlreadyResolvedException(Object transactionId, Object currentOutcome) {
        super("Assessment for transaction " + transactionId + " is already resolved as " + currentOutcome);
    }
}
