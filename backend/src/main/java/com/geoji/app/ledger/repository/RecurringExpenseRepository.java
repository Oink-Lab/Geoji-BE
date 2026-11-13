package com.geoji.app.ledger.repository;

import com.geoji.app.ledger.entity.RecurringExpense;
import com.geoji.app.ledger.domain.LedgerTypes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface RecurringExpenseRepository extends JpaRepository<RecurringExpense, UUID> {
    Optional<RecurringExpense> findByIdAndUserId(UUID id, UUID userId);
    List<RecurringExpense> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<RecurringExpense> findByStatus(LedgerTypes.Status status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecurringExpense> findLockedById(UUID id);
}
