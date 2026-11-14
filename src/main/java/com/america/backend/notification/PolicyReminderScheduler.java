package com.america.backend.notification;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "app.policy-reminder.enabled", havingValue = "true")
public class PolicyReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(PolicyReminderScheduler.class);
    private final PolicyReminderRepository reminders;
    private final PolicyReminderService service;
    public PolicyReminderScheduler(PolicyReminderRepository reminders, PolicyReminderService service) {
        this.reminders = reminders; this.service = service;
    }
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void sendDaily() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (Long userId : reminders.enabledUserIds()) {
            try { service.deliver(userId, today); }
            catch (RuntimeException error) {
                // Do not log recipient addresses, policy contents or SMTP credentials.
                log.warn("Policy reminder failed: userId={}, date={}, error={}", userId, today, error.getClass().getSimpleName());
            }
        }
    }
}
