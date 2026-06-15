package com.fraudengine.model.enums;

public enum Severity {
    LOW(10), MEDIUM(25), HIGH(50), CRITICAL(100);

    private final int scoreWeight;

    Severity(int scoreWeight) { this.scoreWeight = scoreWeight; }

    public int getScoreWeight() { return scoreWeight; }
}
