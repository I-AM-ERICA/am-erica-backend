package com.america.backend.chat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {
    List<ChatConversation> findByUserIdAndAssistantOrderByUpdatedAtDescIdDesc(Long userId, String assistant);
}
