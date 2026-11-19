package com.america.backend.chat;

import com.america.backend.ai.AiDtos.*;
import com.america.backend.ai.grpc.AiAssistantClient;
import com.america.backend.common.ApiException;
import com.america.backend.store.StoreService;
import com.america.backend.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account-row locking serializes retention and writes, including requests from different devices. */
@Service
@Transactional
public class PolicyConversationService {
    public record Summary(Long id, Long storeId, String title, Instant createdAt, Instant updatedAt) {}
    public record Message(UUID id, String role, String content, String status, List<SourceDto> sources, String errorCode) {}
    public record Conversation(Summary conversation, List<Message> messages) {}
    public record Turn(Long conversationId, Long storeId, UUID assistantId, PolicyAskRequest request, Message replay) {}
    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final UserRepository users;
    private final StoreService stores;
    private final AiAssistantClient client;
    public PolicyConversationService(ChatConversationRepository conversations, ChatMessageRepository messages,
            UserRepository users, StoreService stores, AiAssistantClient client) {
        this.conversations=conversations; this.messages=messages; this.users=users; this.stores=stores; this.client=client;
    }
    private void lock(Long userId) {
        users.lockById(userId).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "계정을 찾을 수 없습니다."));
    }
    private ChatConversation owned(Long userId, Long id) {
        ChatConversation c = conversations.findById(id).filter(it -> it.userId.equals(userId) && it.assistant.equals("policy"))
            .orElseThrow(() -> ApiException.notFound("CHAT_NOT_FOUND", "보관되지 않은 대화입니다. 목록을 새로 불러와 주세요."));
        stores.requireOwned(userId, c.storeId);
        return c;
    }
    public List<Summary> list(Long userId) {
        lock(userId);
        return conversations.findByUserIdAndAssistantOrderByUpdatedAtDescIdDesc(userId, "policy").stream().limit(5).map(this::summary).toList();
    }
    public Summary create(Long userId, Long storeId) {
        lock(userId); stores.requireOwned(userId, storeId);
        ChatConversation c = conversations.saveAndFlush(new ChatConversation(userId, storeId));
        var all = conversations.findByUserIdAndAssistantOrderByUpdatedAtDescIdDesc(userId, "policy");
        for (ChatConversation old : all.stream().skip(5).toList()) {
            messages.deleteByConversationId(old.id);
            messages.flush();
            conversations.delete(old);
        }
        return summary(c);
    }
    public Conversation get(Long userId, Long id) {
        lock(userId); ChatConversation c = owned(userId,id);
        var all = messages.findByConversationIdOrderById(id);
        recoverInterrupted(all);
        return new Conversation(summary(c), all.stream().map(this::message).toList());
    }
    private void recoverInterrupted(List<ChatMessage> all) {
        Instant cutoff = Instant.now().minus(client.deadline().plusSeconds(60));
        for (ChatMessage m : all) if (active(m) && m.updatedAt.isBefore(cutoff)) {
            m.status="error"; m.errorCode="CHAT_INTERRUPTED"; m.updatedAt=Instant.now();
        }
    }
    public Turn begin(Long userId, Long id, UUID clientId, String question) {
        lock(userId); ChatConversation c = owned(userId,id);
        var all = messages.findByConversationIdOrderById(id);
        recoverInterrupted(all);
        UUID answerId = UUID.nameUUIDFromBytes(("assistant:" + clientId).getBytes(StandardCharsets.UTF_8));
        var existing = messages.findByConversationIdAndClientMessageId(id,clientId);
        if (existing.isPresent()) {
            if (!existing.get().role.equals("user") || !existing.get().content.equals(question.trim())) throw conflict("요청 식별자가 이미 사용되었습니다.");
            var answer = messages.findByConversationIdAndClientMessageId(id,answerId).orElseThrow(() -> conflict("이미 처리 중인 질문입니다."));
            if (active(answer)) throw conflict("이미 답변을 작성 중입니다. 잠시 후 대화를 다시 불러와 주세요.");
            return new Turn(id,c.storeId,answerId,null,message(answer));
        }
        if (all.stream().anyMatch(PolicyConversationService::active)) throw conflict("이 대화에서 다른 답변을 작성 중입니다.");
        List<PolicyChatTurn> history = history(all);
        messages.saveAndFlush(new ChatMessage(id,clientId,"user",question.trim(),"complete"));
        messages.save(new ChatMessage(id,answerId,"assistant","","streaming"));
        if (all.isEmpty()) c.title=question.trim().substring(0,Math.min(80,question.trim().length()));
        c.updatedAt=Instant.now();
        return new Turn(id,c.storeId,answerId,new PolicyAskRequest(question.trim(),3,history),null);
    }
    public boolean finish(Long userId, Long id, UUID answerId, String content, List<SourceDto> sources, String status, String errorCode) {
        lock(userId);
        // A sixth room may have pruned this room while the model was running. Never recreate it.
        var c = conversations.findById(id).filter(it -> it.userId.equals(userId));
        if (c.isEmpty()) return false;
        var found=messages.findByConversationIdAndClientMessageId(id,answerId);
        if (found.isEmpty() || !active(found.get())) return false;
        ChatMessage m=found.get();
        m.content=content; m.sources=List.copyOf(sources); m.status=status; m.errorCode=errorCode; m.updatedAt=Instant.now();
        c.get().updatedAt=m.updatedAt;
        return true;
    }
    public void verify(Long userId,Long id) { owned(userId,id); }
    public void stop(Long userId,Long id,UUID requestId) {
        lock(userId); owned(userId,id);
        UUID answerId=UUID.nameUUIDFromBytes(("assistant:" + requestId).getBytes(StandardCharsets.UTF_8));
        messages.findByConversationIdAndClientMessageId(id,answerId).filter(PolicyConversationService::active).ifPresent(m -> {
            m.status="stopped"; m.updatedAt=Instant.now();
        });
    }
    static boolean active(ChatMessage m) { return "streaming".equals(m.status) || "pending".equals(m.status); }
    private ApiException conflict(String text) { return new ApiException(HttpStatus.CONFLICT,"CHAT_BUSY",text); }
    private Summary summary(ChatConversation c) { return new Summary(c.id,c.storeId,c.title,c.createdAt,c.updatedAt); }
    private Message message(ChatMessage m) { return new Message(m.clientMessageId,m.role,m.content,m.status,m.sources,m.errorCode); }
    private List<PolicyChatTurn> history(List<ChatMessage> all) {
        List<PolicyChatTurn> turns=new ArrayList<>(); List<SourceDto> sources=List.of();
        for (int i=0; i+1<all.size(); i++) {
            ChatMessage a=all.get(i), b=all.get(i+1);
            if (a.role.equals("user") && b.role.equals("assistant") && b.status.equals("complete") && !b.content.isBlank()) {
                turns.add(new PolicyChatTurn("user",shorten(a.content,2000)));
                turns.add(new PolicyChatTurn("assistant",shorten(b.content,2000))); sources=b.sources; i++;
            }
        }
        String urls=sources.stream().filter(s -> s.url()!=null && s.url().startsWith("https:") && s.url().length()<=700)
            .limit(2).map(s -> "이전 답변 출처 URL: "+s.url()).collect(java.util.stream.Collectors.joining("\n"));
        if (!urls.isEmpty() && !turns.isEmpty()) {
            var last=turns.get(turns.size()-1);
            turns.set(turns.size()-1,new PolicyChatTurn("assistant",shorten(last.content(),2000-urls.length()-2)+"\n\n"+urls));
        }
        return List.copyOf(turns.subList(Math.max(0,turns.size()-8),turns.size()));
    }
    private String shorten(String text,int max) { return text.substring(0,Math.min(text.length(),max)); }
}
