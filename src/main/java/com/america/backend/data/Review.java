package com.america.backend.data;

import java.time.Instant;

import com.america.backend.store.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(length = 30)
    private String platform;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(length = 2000)
    private String reply;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Review() {
    }

    public Review(Store store, String platform, int rating, String content) {
        this.store = store;
        this.platform = platform;
        this.rating = rating;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public void reply(String reply) {
        this.reply = reply;
    }

    public Long getId() {
        return id;
    }

    public String getPlatform() {
        return platform;
    }

    public int getRating() {
        return rating;
    }

    public String getContent() {
        return content;
    }

    public String getReply() {
        return reply;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
