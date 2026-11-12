package com.america.backend.data;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 소상공인 지원 정책입니다. 모든 매장이 공통으로 조회합니다. */
@Entity
@Table(name = "policies")
public class Policy {

    public static final String NATIONWIDE = "전국";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 100)
    private String agency;

    @Column(nullable = false, length = 50)
    private String region;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 2000)
    private String summary;

    @Column(length = 500)
    private String url;

    @Column(name = "apply_end")
    private LocalDate applyEnd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Policy() {
    }

    public Policy(String title, String agency, String region, String category, String summary,
                  String url, LocalDate applyEnd) {
        this.title = title;
        this.agency = agency;
        this.region = region;
        this.category = category;
        this.summary = summary;
        this.url = url;
        this.applyEnd = applyEnd;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getAgency() {
        return agency;
    }

    public String getRegion() {
        return region;
    }

    public String getCategory() {
        return category;
    }

    public String getSummary() {
        return summary;
    }

    public String getUrl() {
        return url;
    }

    public LocalDate getApplyEnd() {
        return applyEnd;
    }
}
