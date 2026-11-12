package com.america.backend.store;

import java.time.Instant;

import com.america.backend.user.UserAccount;
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
@Table(name = "stores")
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private UserAccount owner;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 50)
    private String region;

    @Column(length = 255)
    private String address;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Store() {
    }

    public Store(UserAccount owner, String name, String category, String region, String address) {
        this.owner = owner;
        this.createdAt = Instant.now();
        update(name, category, region, address);
    }

    public void update(String name, String category, String region, String address) {
        this.name = name;
        this.category = category;
        this.region = region;
        this.address = address;
    }

    public Long getId() {
        return id;
    }

    public UserAccount getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getRegion() {
        return region;
    }

    public String getAddress() {
        return address;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
