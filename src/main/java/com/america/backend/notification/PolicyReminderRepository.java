package com.america.backend.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PolicyReminderRepository extends JpaRepository<PolicyReminder, Long> {
    @Query("select r.userId from PolicyReminder r where r.enabled = true order by r.userId")
    List<Long> enabledUserIds();
}
