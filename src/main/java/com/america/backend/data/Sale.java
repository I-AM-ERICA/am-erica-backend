package com.america.backend.data;

import java.time.Instant;
import java.time.LocalDate;

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
import jakarta.persistence.UniqueConstraint;

/** 매장의 일 단위 매출 집계입니다. 매장·날짜당 1건입니다. */
@Entity
@Table(name = "sales", uniqueConstraints = @UniqueConstraint(columnNames = {"store_id", "sale_date"}))
public class Sale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "sale_date", nullable = false)
    private LocalDate saleDate;

    @Column(nullable = false)
    private long amount;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Sale() {
    }

    public Sale(Store store, LocalDate saleDate, long amount, int orderCount) {
        this.store = store;
        this.saleDate = saleDate;
        update(amount, orderCount);
    }

    public void update(long amount, int orderCount) {
        this.amount = amount;
        this.orderCount = orderCount;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public LocalDate getSaleDate() {
        return saleDate;
    }

    public long getAmount() {
        return amount;
    }

    public int getOrderCount() {
        return orderCount;
    }
}
