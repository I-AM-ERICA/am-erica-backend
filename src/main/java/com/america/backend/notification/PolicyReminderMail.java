package com.america.backend.notification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class PolicyReminderMail {
    private final ObjectProvider<JavaMailSender> senders;
    private final boolean enabled;
    private final String from;
    private final String username;
    private final String password;

    public PolicyReminderMail(ObjectProvider<JavaMailSender> senders,
            @Value("${app.policy-reminder.enabled:false}") boolean enabled,
            @Value("${app.policy-reminder.from:}") String from,
            @Value("${spring.mail.username:}") String username,
            @Value("${spring.mail.password:}") String password) {
        this.senders = senders; this.enabled = enabled; this.from = from;
        this.username = username; this.password = password;
    }
    public boolean configured() {
        return enabled && !from.isBlank() && !username.isBlank() && !password.isBlank()
                && senders.getIfAvailable() != null;
    }
    public void send(String to, String subject, String text) {
        if (!configured()) throw new IllegalStateException("Policy reminder SMTP is not configured");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(to); message.setSubject(subject); message.setText(text);
        senders.getObject().send(message);
    }
}
