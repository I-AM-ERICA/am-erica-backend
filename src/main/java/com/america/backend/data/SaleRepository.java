package com.america.backend.data;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    List<Sale> findByStoreIdAndSaleDateBetweenOrderBySaleDateAsc(Long storeId, LocalDate from, LocalDate to);

    Optional<Sale> findByStoreIdAndSaleDate(Long storeId, LocalDate saleDate);
}
