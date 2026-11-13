package com.geoji.app.ledger.repository;

import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.domain.LedgerTypes;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.*;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);
    Page<Transaction> findByUserIdAndTransactionDateBetween(UUID userId, LocalDate from, LocalDate to, Pageable pageable);
    List<Transaction> findByUserIdAndTransactionDateBetween(UUID userId, LocalDate from, LocalDate to);

    @Query("select t from Transaction t where t.user.id = :userId " +
        "and t.type = com.geoji.app.ledger.domain.LedgerTypes.Type.EXPENSE " +
        "and t.transactionDate between :from and :to order by t.transactionDate, t.createdAt")
    List<Transaction> findChallengeExpenses(UUID userId, LocalDate from, LocalDate to);
}
