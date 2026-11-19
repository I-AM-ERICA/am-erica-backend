package com.america.backend.chat;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "chat_conversations")
class ChatConversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(nullable = false) Long userId;
    @Column(nullable = false) Long storeId;
    @Column(nullable = false, length = 20) String assistant = "policy";
    @Column(nullable = false, length = 200) String title = "새 대화";
    @Column(nullable = false) Instant createdAt = Instant.now();
    @Column(nullable = false) Instant updatedAt = createdAt;
    protected ChatConversation() {}
    ChatConversation(Long userId, Long storeId) { this.userId = userId; this.storeId = storeId; }
}
