package com.america.backend.ai.grpc;

public enum AssistantType {
    POLICY("정책"),
    PROMOTION("홍보"),
    CUSTOMER("고객 응대"),
    MANAGEMENT("경영");

    private final String label;

    AssistantType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
