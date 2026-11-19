package com.america.backend.chat;

import com.america.backend.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/me/policy/conversations")
public class PolicyConversationController {
    public record Create(@NotNull @Positive Long storeId) {}
    public record Ask(@NotNull UUID clientMessageId,@NotBlank @Size(max=1000) String question) {}
    private final PolicyConversationService service;
    private final PolicyConversationStream streams;
    private final com.america.backend.notification.PolicyReminderService reminders;
    public PolicyConversationController(PolicyConversationService service,PolicyConversationStream streams,com.america.backend.notification.PolicyReminderService reminders) { this.service=service; this.streams=streams; this.reminders=reminders; }
    @GetMapping public List<PolicyConversationService.Summary> list(@AuthenticationPrincipal Jwt jwt) { return service.list(CurrentUser.id(jwt)); }
    @PostMapping public PolicyConversationService.Summary create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody Create request) {
        return service.create(CurrentUser.id(jwt),request.storeId());
    }
    @GetMapping("/{id}") public PolicyConversationService.Conversation get(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id) { return service.get(CurrentUser.id(jwt),id); }
    @PostMapping(value="/{id}/messages",produces="text/event-stream")
    public SseEmitter ask(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@Valid @RequestBody Ask request) {
        return streams.ask(CurrentUser.id(jwt),id,request.clientMessageId(),request.question());
    }
    public record Email(@NotNull UUID clientMessageId) {}
    @PostMapping("/{id}/email")
    public com.america.backend.notification.PolicyReminderService.DeliveryResult email(
            @AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@Valid @RequestBody Email request) {
        Long userId=CurrentUser.id(jwt);
        var turn=service.begin(userId,id,request.clientMessageId(),"현재 신청할 수 있는 정책을 검색하고 이메일로 보내주세요.");
        if (turn.replay()!=null) {
            if (!turn.replay().status().equals("complete")) throw new com.america.backend.common.ApiException(
                org.springframework.http.HttpStatus.CONFLICT,"CHAT_INTERRUPTED","이전에 완료되지 않은 발송 요청입니다. 받은메일함을 확인해 주세요.");
            return new com.america.backend.notification.PolicyReminderService.DeliveryResult(reminders.get(userId).email(),turn.replay().content());
        }
        try {
            var result=reminders.sendNow(userId,turn.storeId());
            service.finish(userId,id,turn.assistantId(),result.content(),List.of(),"complete",null);
            return result;
        } catch (RuntimeException error) {
            String code=error instanceof com.america.backend.common.ApiException api ? api.getCode() : "MAIL_RESULT_UNKNOWN";
            service.finish(userId,id,turn.assistantId(),"",List.of(),"error",code);
            throw error;
        }
    }
    @PostMapping("/{id}/messages/{requestId}/stop")
    public void stop(@AuthenticationPrincipal Jwt jwt,@PathVariable Long id,@PathVariable UUID requestId) { streams.stop(CurrentUser.id(jwt),id,requestId); }
}
