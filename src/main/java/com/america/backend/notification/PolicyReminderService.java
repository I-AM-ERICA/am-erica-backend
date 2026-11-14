package com.america.backend.notification;

import java.time.LocalDate;
import java.util.List;
import com.america.backend.common.ApiException;
import com.america.backend.data.Policy;
import com.america.backend.data.PolicyRepository;
import com.america.backend.store.Store;
import com.america.backend.store.StoreService;
import com.america.backend.user.UserAccount;
import com.america.backend.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyReminderService {
    public record Settings(boolean enabled, String email, Long storeId, String storeName,
                           boolean deliveryConfigured, LocalDate lastSentDate) {}
    private final UserRepository users;
    private final PolicyReminderRepository reminders;
    private final StoreService stores;
    private final PolicyRepository policies;
    private final PolicyReminderMail mail;
    private final String frontendUrl;

    public PolicyReminderService(UserRepository users, PolicyReminderRepository reminders, StoreService stores,
            PolicyRepository policies, PolicyReminderMail mail,
            @Value("${app.policy-reminder.frontend-url:http://localhost:7001}") String frontendUrl) {
        this.users = users; this.reminders = reminders; this.stores = stores;
        this.policies = policies; this.mail = mail; this.frontendUrl = frontendUrl;
    }

    @Transactional(readOnly = true)
    public Settings get(Long userId) {
        UserAccount user = users.findById(userId).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        return settings(user, reminders.findById(userId).orElse(null));
    }

    @Transactional
    public Settings update(Long userId, boolean enabled, Long storeId) {
        // Lock the account even before a subscription exists; concurrent first-time saves cannot duplicate it.
        UserAccount user = lock(userId);
        Store store = null;
        if (enabled) {
            if (user.getEmail() == null || user.getEmail().isBlank())
                throw ApiException.badRequest("EMAIL_REQUIRED", "네이버 로그인에서 이메일 제공에 동의한 뒤 다시 로그인해 주세요.");
            if (storeId == null) throw ApiException.badRequest("STORE_REQUIRED", "알림을 받을 가게를 선택해 주세요.");
            store = stores.requireOwned(userId, storeId);
        }
        PolicyReminder reminder = reminders.findById(userId).orElseGet(() -> new PolicyReminder(user));
        reminder.update(enabled, store);
        reminders.save(reminder);
        return settings(user, reminder);
    }

    private Settings settings(UserAccount user, PolicyReminder reminder) {
        Store store = reminder == null ? null : reminder.getStore();
        return new Settings(reminder != null && reminder.isEnabled(), user.getEmail(),
                store == null ? null : store.getId(), store == null ? null : store.getName(),
                mail.configured(), reminder == null ? null : reminder.getLastSentDate());
    }

    private UserAccount lock(Long userId) {
        return users.lockById(userId).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
    }

    // One transaction per recipient: isolate failures and serialize concurrent workers and opt-out changes.
    @Transactional
    public void deliver(Long userId, LocalDate today) {
        UserAccount user = lock(userId);
        PolicyReminder reminder = reminders.findById(userId).orElse(null);
        if (reminder == null || !reminder.isEnabled() || reminder.getStore() == null || !mail.configured()
                || user.getEmail() == null || user.getEmail().isBlank()
                || (reminder.getLastSentDate() != null && !reminder.getLastSentDate().isBefore(today))) return;
        Store store = reminder.getStore();
        List<Policy> open = policies.findOpen(today).stream()
                .filter(policy -> matchesRegion(policy.getRegion(), store.getRegion())).toList();
        StringBuilder text = new StringBuilder(store.getName()).append(" 사장님, 오늘의 정책 리마인드입니다.\n\n")
                .append("확인일: ").append(today).append(" (한국 시간)\n")
                .append("가게 지역 및 전국 대상 정책이며, 신청 자격은 각 공식 공고에서 확인해 주세요.\n")
                .append("새 정책이 없어도 접수 기간이 남아 있으면 매일 다시 안내합니다.\n\n");
        if (open.isEmpty()) text.append("현재 등록된 정책 중 접수 기간이 확인된 진행 중 정책이 없습니다.\n");
        for (Policy policy : open) {
            text.append("• ").append(policy.getTitle()).append("\n")
                .append(policy.getAgency()).append(" · ").append(policy.getRegion()).append(" · ").append(policy.getCategory()).append("\n")
                .append("접수: ").append(policy.getApplyStart() == null ? "시작일 미등록" : policy.getApplyStart())
                .append(" ~ ").append(policy.getApplyEnd()).append(" (마감일 포함)\n")
                .append(policy.getSummary()).append("\n");
            if (policy.getUrl() != null && policy.getUrl().matches("(?i)^https?://[^\\s]+$"))
                text.append("공식 공고: ").append(policy.getUrl()).append("\n");
            text.append("\n");
        }
        text.append("알림 해제: 정책 비서 화면에서 오전 6시 이메일 알림을 OFF로 바꿔 주세요.\n")
            .append(frontendUrl.replaceAll("/+$", "")).append("/#policy\n");
        mail.send(user.getEmail(), "[가게비서] " + today + " 정책 리마인드 · " + open.size() + "건", text.toString());
        reminder.sent(today);
    }

    static boolean matchesRegion(String policyRegion, String storeRegion) {
        return Policy.NATIONWIDE.equals(policyRegion) || storeRegion.equals(policyRegion)
                || storeRegion.startsWith(policyRegion + " ");
    }
}
