package com.example.payment.event.processor.repository;

import com.example.payment.event.processor.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Wallet> findByUserId(UUID userId);

    @Query("SELECT w FROM Wallet w WHERE w.userId = :userId")
    Optional<Wallet> findWalletByUserId(@Param("userId") UUID userId);
}