package com.america.backend.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserAccount, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findByNaverId(String naverId);

    boolean existsByEmail(String email);
}
