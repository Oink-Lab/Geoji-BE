package com.geoji.app.ledger.repository;

import com.geoji.app.ledger.entity.RecurringOccurrence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.*;

public interface RecurringOccurrenceRepository extends JpaRepository<RecurringOccurrence, UUID> {
    boolean existsByRecurringExpenseIdAndOccurrenceMonth(UUID recurringExpenseId, LocalDate month);
    Optional<RecurringOccurrence> findByTransactionId(UUID transactionId);
    List<RecurringOccurrence> findByRecurringExpenseIdAndOccurrenceMonth(UUID recurringExpenseId, LocalDate month);
}
