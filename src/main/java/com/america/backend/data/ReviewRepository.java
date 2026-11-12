package com.america.backend.data;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByStoreIdOrderByCreatedAtDescIdDesc(Long storeId, Pageable pageable);

    Optional<Review> findByIdAndStoreId(Long id, Long storeId);

    long countByStoreIdAndReplyIsNull(Long storeId);
}
