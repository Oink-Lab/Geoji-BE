package com.geoji.app.analytics;

import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.user.entity.User;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonthlyAnalyticsServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void alignedComparisonCapsBothMonthsWhenPreviousMonthIsShorter() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        List<Transaction> stored = List.of(
            expense(LocalDate.of(2026, 2, 10), 100),
            expense(LocalDate.of(2026, 3, 10), 200),
            expense(LocalDate.of(2026, 3, 29), 300));
        when(transactions.findByUserIdAndTransactionDateBetween(any(), any(), any()))
            .thenAnswer(call -> {
                LocalDate from = call.getArgument(1), to = call.getArgument(2);
                return stored.stream().filter(t -> !t.getTransactionDate().isBefore(from)
                    && !t.getTransactionDate().isAfter(to)).toList();
            });
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Clock clock = Clock.fixed(LocalDate.of(2026, 3, 31).atStartOfDay(seoul).toInstant(), seoul);
        MonthlyAnalyticsService service = new MonthlyAnalyticsService(transactions, clock);
        Map<String, Object> result = service.comparison(new User(), YearMonth.of(2026, 3));
        Map<String, Object> aligned = (Map<String, Object>) result.get("sameElapsedDaysComparison");
        Map<String, Object> full = (Map<String, Object>) result.get("fullPreviousMonthComparison");
        assertEquals(LocalDate.of(2026, 2, 28), aligned.get("previousThrough"));
        assertEquals(LocalDate.of(2026, 3, 28), aligned.get("currentThrough"));
        assertEquals(100L, ((Map<String, Object>) aligned.get("totalExpense")).get("previous"));
        assertEquals(200L, ((Map<String, Object>) aligned.get("totalExpense")).get("current"));
        assertEquals(500L, ((Map<String, Object>) full.get("totalExpense")).get("current"));
    }

    private Transaction expense(LocalDate date, long amount) {
        Transaction transaction = new Transaction();
        transaction.setTransactionDate(date);
        transaction.setAmount(amount);
        transaction.setType(LedgerTypes.Type.EXPENSE);
        transaction.setCategoryCode(LedgerTypes.Category.FOOD);
        transaction.setSource(LedgerTypes.Source.MANUAL);
        return transaction;
    }
}
