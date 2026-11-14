package com.america.backend.notification;

import java.time.LocalDate;
import com.america.backend.store.Store;
import com.america.backend.user.UserAccount;
import jakarta.persistence.*;

@Entity
@Table(name = "policy_reminders")
public class PolicyReminder {
    @Id
    @Column(name = "user_id")
    private Long userId;
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "last_sent_date")
    private LocalDate lastSentDate;

    protected PolicyReminder() {}
    public PolicyReminder(UserAccount user) { this.user = user; }
    public boolean isEnabled() { return enabled; }
    public Store getStore() { return store; }
    public LocalDate getLastSentDate() { return lastSentDate; }
    public void update(boolean enabled, Store store) { this.enabled = enabled; if (store != null) this.store = store; }
    public void sent(LocalDate date) { lastSentDate = date; }
}
