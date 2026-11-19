package com.america.backend.notification;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import java.util.List;
import com.america.backend.common.ApiException;
import com.america.backend.data.Policy;
import com.america.backend.data.PolicyRepository;
import com.america.backend.store.Store;
import com.america.backend.store.StoreService;
import com.america.backend.user.UserAccount;
import com.america.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PolicyReminderService {
    public record Settings(boolean enabled, String email, Long storeId, String storeName,
                           boolean deliveryConfigured, LocalDate lastSentDate, boolean manualDeliveryConfigured) {}
    public record DeliveryResult(String email, String content) {}
    private record Digest(String subject, String content) {}
    private final UserRepository users;
    private final PolicyReminderRepository reminders;
    private final StoreService stores;
    private final PolicyRepository policies;
    private final PolicyReminderMail mail;
    private final PolicyDigestSearch model;
    private final TransactionTemplate transactions;

    public PolicyReminderService(UserRepository users, PolicyReminderRepository reminders, StoreService stores,
            PolicyRepository policies, PolicyReminderMail mail, PolicyDigestSearch model, PlatformTransactionManager transactionManager) {
        this.users = users; this.reminders = reminders; this.stores = stores;
        this.policies = policies; this.mail = mail; this.model = model;
        this.transactions = new TransactionTemplate(transactionManager);
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
                mail.configured(), reminder == null ? null : reminder.getLastSentDate(), mail.smtpConfigured());
    }

    private UserAccount lock(Long userId) {
        return users.lockById(userId).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
    }

    // AI search runs outside DB transactions. Recheck opt-out/ownership under a lock just before SMTP.
    public void deliver(Long userId, LocalDate today) {
        Long storeId = transactions.execute(status -> {
            UserAccount user = users.findById(userId).orElse(null);
            PolicyReminder reminder = reminders.findById(userId).orElse(null);
            return due(user, reminder, today) ? reminder.getStore().getId() : null;
        });
        if (storeId == null) return;
        Digest digest = search(userId, stores.requireOwned(userId, storeId), today);
        transactions.executeWithoutResult(status -> {
            UserAccount user = lock(userId);
            PolicyReminder reminder = reminders.findById(userId).orElse(null);
            if (!due(user, reminder, today) || !storeId.equals(reminder.getStore().getId())) return;
            stores.requireOwned(userId, storeId);
            sendDigest(user, digest);
            reminder.sent(today);
        });
    }

    private boolean due(UserAccount user, PolicyReminder reminder, LocalDate today) {
        return user != null && reminder != null && reminder.isEnabled() && reminder.getStore() != null
                && mail.configured() && user.getEmail() != null && !user.getEmail().isBlank()
                && (reminder.getLastSentDate() == null || reminder.getLastSentDate().isBefore(today));
    }

    public DeliveryResult sendNow(Long userId, Long storeId) {
        Store store = stores.requireOwned(userId, storeId);
        checkManualRecipient(users.findById(userId).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "사용자를 찾을 수 없습니다.")));
        Digest digest = search(userId, store, LocalDate.now(ZoneId.of("Asia/Seoul")));
        return transactions.execute(status -> {
            UserAccount user = lock(userId);
            stores.requireOwned(userId, storeId);
            checkManualRecipient(user);
            // Never change subscription or lastSentDate: manual mail must not suppress the 6 AM reminder.
            return sendDigest(user, digest);
        });
    }

    private void checkManualRecipient(UserAccount user) {
        if (user.getEmail() == null || user.getEmail().isBlank())
            throw ApiException.badRequest("EMAIL_REQUIRED", "네이버 로그인에서 이메일 제공에 동의해 주세요.");
        if (!mail.smtpConfigured())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MAIL_NOT_CONFIGURED", "이메일 발송 설정이 필요합니다.");
    }

    private Digest search(Long userId, Store store, LocalDate today) {
        var result = model.search(userId, store, today);
        List<Policy> open = policies.findOpen(today).stream()
                .filter(policy -> matchesRegion(policy.getRegion(), store.getRegion())).toList();
        StringBuilder text = new StringBuilder(store.getName()).append(" 사장님, 오늘의 정책 리마인드입니다.\n\n")
                .append("확인일: ").append(today).append(" (한국 시간)\n")
                .append("업종: ").append(store.getCategory()).append(" · 지역: ").append(store.getRegion()).append("\n")
                .append("아래는 모델이 공식 공고를 검색하여 정리한 내용입니다. 신청 가능 여부와 자격은 공식 공고에서 확인해 주세요.\n")
                .append("등록된 정책은 새 공고가 없어도 접수 기간이 남아 있으면 매일 다시 안내합니다.\n")
                .append("모델이 기간을 확인하지 못한 정책은 신청 가능하다고 확정한 결과가 아닙니다.\n\n");
        text.append("[검색결과]\n").append(result.answer()).append("\n\n");
        for (var source : result.sources()) {
            text.append("출처: ").append(source.title()).append("\n").append(source.url()).append("\n");
        }
        text.append("\n[등록된 정책 중 접수 기간이 남은 정책 · ").append(open.size()).append("건]\n");
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
        text.append("알림 해제: 정책 비서 화면에서 오전 6시 이메일 알림을 OFF로 바꿔 주세요.\n");
        return new Digest("[가게비서] " + today + " 정책 검색 결과 · 등록 정책 " + open.size() + "건", text.toString());
    }

    private DeliveryResult sendDigest(UserAccount user, Digest digest) {
        try {
            mail.sendNow(user.getEmail(), digest.subject(), digest.content());
        } catch (MailException error) {
            // SMTP may have accepted a message before the connection failed; do not retry automatically.
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MAIL_SEND_FAILED", "메일 발송을 확인하지 못했습니다. 받은메일함을 확인해 주세요.");
        }
        return new DeliveryResult(user.getEmail(), digest.content());
    }

    static boolean matchesRegion(String policyRegion, String storeRegion) {
        return Policy.NATIONWIDE.equals(policyRegion) || storeRegion.equals(policyRegion)
                || storeRegion.startsWith(policyRegion + " ");
    }
}
