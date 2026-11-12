package com.america.backend.ai;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiLogRepository extends JpaRepository<AiLog, Long> {

    List<AiLog> findByStoreIdOrderByIdDesc(Long storeId, Pageable pageable);
}
