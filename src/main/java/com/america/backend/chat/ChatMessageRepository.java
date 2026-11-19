package com.america.backend.chat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByConversationIdOrderById(Long conversationId);
    Optional<ChatMessage> findByConversationIdAndClientMessageId(Long conversationId, UUID clientMessageId);
    void deleteByConversationId(Long conversationId);
}
