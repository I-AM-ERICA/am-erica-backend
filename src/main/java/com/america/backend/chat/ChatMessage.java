package com.america.backend.chat;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.america.backend.ai.AiDtos.SourceDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "chat_messages", uniqueConstraints = @UniqueConstraint(name = "uk_chat_messages_client_id", columnNames = {"conversation_id", "client_message_id"}))
class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false) Long conversationId;
    @Column(nullable = false) UUID clientMessageId;
    @Column(nullable = false, length = 20) String role;
    @Column(nullable = false, columnDefinition = "text") String content = "";
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") List<SourceDto> sources = List.of();
    @Column(nullable = false, length = 20) String status;
    @Column(length = 100) String errorCode;
    @Column(nullable = false) Instant createdAt = Instant.now();
    @Column(nullable = false) Instant updatedAt = createdAt;
    protected ChatMessage() {}
    ChatMessage(Long conversationId, UUID clientId, String role, String content, String status) {
        this.conversationId = conversationId; this.clientMessageId = clientId;
        this.role = role; this.content = content; this.status = status;
    }
}
