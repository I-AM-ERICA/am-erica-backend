package com.america.backend.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

    List<Store> findByOwnerIdOrderByIdAsc(Long ownerId);

    Optional<Store> findFirstByOwnerIdOrderByIdDesc(Long ownerId);

    Optional<Store> findByIdAndOwnerId(Long id, Long ownerId);
}
