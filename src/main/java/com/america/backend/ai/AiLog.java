package com.america.backend.ai;

import java.time.Instant;

import com.america.backend.ai.grpc.AssistantType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** AI 비서 호출 이력입니다. 질문·리뷰 원문은 저장하지 않습니다. */
@Entity
@Table(name = "ai_logs")
public class AiLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AssistantType assistant;

    @Column(nullable = false, length = 50)
    private String operation;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiLog() {
    }

    public AiLog(Long userId, Long storeId, AssistantType assistant, String operation, boolean success,
                 String errorCode, long latencyMs) {
        this.userId = userId;
        this.storeId = storeId;
        this.assistant = assistant;
        this.operation = operation;
        this.success = success;
        this.errorCode = errorCode;
        this.latencyMs = latencyMs;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public AssistantType getAssistant() {
        return assistant;
    }

    public String getOperation() {
        return operation;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
