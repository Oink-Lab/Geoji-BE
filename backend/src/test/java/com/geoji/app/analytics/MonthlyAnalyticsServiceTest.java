package com.geoji.app.analytics;

import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.user.entity.User;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MonthlyAnalyticsServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void alignedComparisonCapsBothMonthsWhenPreviousMonthIsShorter() {
        TransactionRepository transactions = mock(TransactionRepository.class);
        when(transactions.findByUserIdAndTransactionDateBetween(any(), any(), any()))
            .thenReturn(List.of());
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        Clock clock = Clock.fixed(LocalDate.of(2026, 3, 31).atStartOfDay(seoul).toInstant(), seoul);
        MonthlyAnalyticsService service = new MonthlyAnalyticsService(transactions, clock);
        Map<String, Object> result = service.comparison(new User(), YearMonth.of(2026, 3));
        Map<String, Object> aligned = (Map<String, Object>) result.get("sameElapsedDaysComparison");
        assertEquals(LocalDate.of(2026, 2, 28), aligned.get("previousThrough"));
        assertEquals(LocalDate.of(2026, 3, 28), aligned.get("currentThrough"));
    }
}
