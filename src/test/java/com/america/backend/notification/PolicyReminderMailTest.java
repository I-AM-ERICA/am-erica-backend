package com.america.backend.notification;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import static org.mockito.Mockito.*;

class PolicyReminderMailTest {
    @Test void missingCredentialsOrDisabledDeliveryNeverSends() {
        JavaMailSender sender = mock(JavaMailSender.class);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("sender", sender);
        var provider = factory.getBeanProvider(JavaMailSender.class);
        for (PolicyReminderMail mail : new PolicyReminderMail[]{
                new PolicyReminderMail(provider, false, "from@example.com", "user", "pass"),
                new PolicyReminderMail(provider, true, "from@example.com", "user", "")}) {
            assertThat(mail.configured()).isFalse();
            assertThatThrownBy(() -> mail.send("to@example.com", "subject", "body")).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(sender);
    }
}
