package com.geoji.app.analytics;

import com.geoji.app.challenge.entity.Challenge;
import com.geoji.app.challenge.repository.ChallengeRepository;
import com.geoji.app.challenge.service.ChallengeService;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against a dedicated PostgreSQL database when the three POSTGRES_ANALYTICS_TEST_* variables are set. */
@SpringBootTest
@ActiveProfiles("postgres-analytics")
@EnabledIfEnvironmentVariable(named = "POSTGRES_ANALYTICS_TEST_URL", matches = "jdbc:postgresql:.*")
@Transactional
class PostgresAnalyticsIntegrationTest {
    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock postgresAnalyticsClock() {
            ZoneId zone = ZoneId.of("Asia/Seoul");
            return Clock.fixed(LocalDate.of(2026, 11, 13).atTime(12, 0).atZone(zone).toInstant(), zone);
        }
    }

    @Autowired UserRepository users;
    @Autowired TransactionRepository transactions;
    @Autowired ChallengeRepository challenges;
    @Autowired ChallengeService challengeState;
    @Autowired MonthlyAnalyticsService monthly;
    @Autowired ChallengeAnalyticsService challengeAnalytics;
    @Autowired ChallengeAggregateService aggregate;
    @Autowired InsightService insights;

    @Test
    @SuppressWarnings("unchecked")
    void flywayAndPostgresAggregateDatesCategoriesAndFailureSnapshot() {
        User user = new User(); user.setNickname("postgres-check"); users.saveAndFlush(user);
        Challenge challenge = new Challenge(); challenge.setUser(user); challenge.setName("postgres-check");
        challenge.setStartDate(LocalDate.of(2026, 11, 11)); challenge.setEndDate(LocalDate.of(2026, 11, 13));
        challenge.setDurationDays(3); challenge.setTotalBudget(500);
        challenge.setStatus(Challenge.Status.IN_PROGRESS); challenges.saveAndFlush(challenge);
        expense(user, LocalDate.of(2026, 11, 11), 300, LedgerTypes.Category.FOOD);
        expense(user, LocalDate.of(2026, 11, 12), 250, LedgerTypes.Category.HOUSING);
        var basic = monthly.monthly(user, YearMonth.of(2026, 11));
        assertEquals(550L, basic.get("totalExpense"));
        assertEquals(2, ((List<?>) basic.get("categories")).size());
        assertEquals(11, monthly.patterns(user, YearMonth.of(2026, 11)).get("daysWithoutRecordedExpense"));
        challengeState.transactionChanged(user.getId(), LocalDate.of(2026, 11, 12));
        assertEquals(Challenge.Status.FAILED, challenges.findById(challenge.getId()).orElseThrow().getStatus());
        assertEquals(550L, challengeAnalytics.overview(user, challenge.getId()).get("cumulativeSpent"));
        assertEquals(1L, aggregate.monthly(user, YearMonth.of(2026, 11)).get("failureConfirmed"));
        var outcome = (Map<String, Object>) insights.challenge(user, challenge.getId()).get("outcome");
        var recorded = (Map<String, Object>) outcome.get("atConfirmation");
        assertEquals(true, recorded.get("categoryBreakdownAvailable"));
        assertEquals(2, ((List<?>) recorded.get("categories")).size());
    }

    private void expense(User user, LocalDate date, long amount, LedgerTypes.Category category) {
        Transaction transaction = new Transaction(); transaction.setUser(user);
        transaction.setType(LedgerTypes.Type.EXPENSE); transaction.setCategoryCode(category);
        transaction.setTransactionMethod(LedgerTypes.Method.CASH);
        transaction.setSource(LedgerTypes.Source.MANUAL);
        transaction.setTransactionDate(date); transaction.setAmount(amount);
        transactions.saveAndFlush(transaction);
    }
}
