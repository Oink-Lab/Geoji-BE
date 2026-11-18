package com.geoji.app.analytics;

import com.geoji.app.challenge.entity.Challenge;
import com.geoji.app.challenge.dto.FailureAnalysisSnapshot;
import com.geoji.app.challenge.repository.ChallengeRepository;
import com.geoji.app.challenge.service.ChallengeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geoji.app.global.ApiException;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AnalyticsIntegrationTest {
    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock analyticsTestClock() {
            return Clock.fixed(LocalDate.of(2026, 11, 13).atTime(12, 0)
                .atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired UserRepository users;
    @Autowired TransactionRepository transactions;
    @Autowired ChallengeRepository challenges;
    @Autowired ChallengeService challengeState;
    @Autowired ObjectMapper json;
    @Autowired MonthlyAnalyticsService monthly;
    @Autowired ChallengeAnalyticsService challengeAnalytics;
    @Autowired ChallengeAggregateService aggregate;
    @Autowired InsightService insights;
    @Autowired MockMvc mvc;

    private User user() {
        User user = new User(); user.setNickname(UUID.randomUUID().toString().substring(0, 8));
        return users.saveAndFlush(user);
    }

    private Transaction transaction(User user, LocalDate date, long amount, LedgerTypes.Type type,
                                    LedgerTypes.Category category, LedgerTypes.Source source) {
        Transaction t = new Transaction();
        t.setUser(user); t.setTransactionDate(date); t.setAmount(amount); t.setType(type);
        t.setCategoryCode(category); t.setSource(source); t.setTransactionMethod(LedgerTypes.Method.CASH);
        return transactions.saveAndFlush(t);
    }

    private Challenge challenge(User user, LocalDate from, int duration, long budget, Challenge.Status status) {
        Challenge c = new Challenge(); c.setUser(user); c.setName("test");
        c.setStartDate(from); c.setEndDate(from.plusDays(duration - 1)); c.setDurationDays(duration);
        c.setTotalBudget(budget); c.setStatus(status);
        return challenges.saveAndFlush(c);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> at(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    @Test
    void monthlySeparatesIncomeRecurringFutureAndOtherUsersAndRecalculates() {
        User owner = user(), other = user();
        LocalDate day1 = LocalDate.of(2026, 11, 1), day8 = LocalDate.of(2026, 11, 8);
        transaction(owner, day1, 1_000, LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        Transaction fixed = transaction(owner, day8, 600, LedgerTypes.Type.EXPENSE,
            LedgerTypes.Category.HOUSING, LedgerTypes.Source.RECURRING);
        transaction(owner, day1, 300, LedgerTypes.Type.INCOME, LedgerTypes.Category.SALARY, LedgerTypes.Source.MANUAL);
        transaction(owner, day1.plusDays(13), 50_000, LedgerTypes.Type.EXPENSE,
            LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        transaction(other, day1, 99_000, LedgerTypes.Type.EXPENSE,
            LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        YearMonth month = YearMonth.of(2026, 11);
        var basic = monthly.monthly(owner, month);
        assertEquals(1_600L, basic.get("totalExpense"));
        assertEquals(300L, basic.get("totalIncome"));
        assertEquals(2, basic.get("expenseCount"));
        assertEquals(2, basic.get("expenseDays"));
        assertEquals(2, ((List<?>) basic.get("categories")).size());
        var patterns = monthly.patterns(owner, month);
        assertEquals(11, patterns.get("daysWithoutRecordedExpense"));
        assertEquals(2, ((List<?>) patterns.get("weeks")).size());
        assertEquals(1_000L, at(patterns, "mostSpentDay").get("amount"));
        assertEquals(600L, monthly.recurring(owner, month).get("recurringExpense"));
        fixed.setAmount(800); transactions.saveAndFlush(fixed);
        assertEquals(1_800L, monthly.monthly(owner, month).get("totalExpense"));
        assertEquals(800L, monthly.recurring(owner, month).get("recurringExpense"));
        transactions.delete(fixed); transactions.flush();
        assertEquals(1_000L, monthly.monthly(owner, month).get("totalExpense"));
    }

    @Test
    void previousMonthZeroAndLeapMonthBoundariesAreExplicit() {
        User owner = user();
        transaction(owner, LocalDate.of(2024, 2, 29), 290,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        assertEquals(290L, monthly.monthly(owner, YearMonth.of(2024, 2)).get("totalExpense"));
        assertEquals(28, monthly.patterns(owner, YearMonth.of(2024, 2)).get("daysWithoutRecordedExpense"));
        var comparison = monthly.comparison(owner, YearMonth.of(2024, 2));
        assertNull(at(at(comparison, "fullPreviousMonthComparison"), "totalExpense").get("changePercent"));
        assertThrows(ApiException.class, () -> monthly.monthly(owner, YearMonth.of(2026, 12)));
    }

    @Test
    void emptyMonthHasNoInventedRankingOrInsight() {
        User owner = user();
        YearMonth month = YearMonth.of(2026, 11);
        var basic = monthly.monthly(owner, month);
        assertEquals(0L, basic.get("totalExpense"));
        assertNull(basic.get("mostSpentCategory"));
        assertEquals(13, monthly.patterns(owner, month).get("daysWithoutRecordedExpense"));
        assertEquals(0.0, monthly.recurring(owner, month).get("recurringSharePercent"));
        assertEquals("INSUFFICIENT_DATA", at(insights.monthly(owner, month), "analysis").get("state"));
        assertNull(aggregate.overall(owner).get("successRatePercent"));
    }

    @Test
    void challengeBudgetPatternsOwnershipAndSnapshotStaySeparate() {
        User owner = user(), other = user();
        Challenge c = challenge(owner, LocalDate.of(2026, 11, 11), 5, 1_000, Challenge.Status.IN_PROGRESS);
        Transaction first = transaction(owner, LocalDate.of(2026, 11, 11), 100,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        transaction(owner, LocalDate.of(2026, 11, 12), 300,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        transaction(owner, LocalDate.of(2026, 11, 13), 50,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.HOUSING, LedgerTypes.Source.RECURRING);
        transaction(other, LocalDate.of(2026, 11, 12), 900,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        var overview = challengeAnalytics.overview(owner, c.getId());
        assertEquals(450L, overview.get("cumulativeSpent"));
        assertEquals(2L, overview.get("daysWithinAllowance"));
        assertEquals(1L, overview.get("daysOverAllowance"));
        assertEquals(3, ((List<?>) overview.get("dailyTrend")).size());
        assertThrows(ApiException.class, () -> challengeAnalytics.overview(other, c.getId()));
        var patterns = challengeAnalytics.patterns(owner, c.getId());
        assertEquals(3, ((List<?>) patterns.get("segments")).size());
        assertEquals(0L, at(patterns, "streaks").get("daysWithoutRecordedExpense"));
        c.setStatus(Challenge.Status.FAILED);
        c.setFailureAt(LocalDate.of(2026, 11, 13).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        c.setFinishedAt(c.getFailureAt()); c.setFailureSpentSnapshot(1_200L);
        challenges.saveAndFlush(c);
        transactions.delete(first); transactions.flush();
        var failure = insights.challenge(owner, c.getId());
        assertEquals(1_200L, at(at(failure, "outcome"), "atConfirmation").get("spent"));
        assertNull(at(at(failure, "outcome"), "atConfirmation").get("categories"));
        assertEquals(350L, at(at(failure, "outcome"), "currentRecalculation").get("spent"));
        assertEquals(Challenge.Status.FAILED, challenges.findById(c.getId()).orElseThrow().getStatus());
    }

    @Test
    void newFailureCapturesDetailedFactsAndKeepsThemAfterTransactionChange() throws Exception {
        User owner = user();
        Challenge c = challenge(owner, LocalDate.of(2026, 11, 11), 5, 10_000, Challenge.Status.IN_PROGRESS);
        Transaction food = transaction(owner, LocalDate.of(2026, 11, 11), 4_000,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        transaction(owner, LocalDate.of(2026, 11, 11), 3_000,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.HOUSING, LedgerTypes.Source.RECURRING);
        transaction(owner, LocalDate.of(2026, 11, 12), 4_000,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.SHOPPING, LedgerTypes.Source.MANUAL);
        challengeState.transactionChanged(owner.getId(), LocalDate.of(2026, 11, 12));
        Challenge failed = challenges.findById(c.getId()).orElseThrow();
        assertEquals(Challenge.Status.FAILED, failed.getStatus());
        String stored = failed.getFailureAnalysisSnapshot();
        FailureAnalysisSnapshot snapshot = json.readValue(stored, FailureAnalysisSnapshot.class);
        assertEquals(11_000L, snapshot.totalSpent());
        assertEquals(3, snapshot.categories().size());
        assertEquals(3, snapshot.topTransactions().size());
        assertEquals(LocalDate.of(2026, 11, 11), snapshot.maxSpendingDay().date());
        assertEquals(7_000L, snapshot.maxSpendingDay().amount());
        assertEquals(3_000L, snapshot.recurringExpense());
        assertEquals(27.27, snapshot.recurringSharePercent());
        assertEquals(63.64, snapshot.topOneDaySharePercent());
        transactions.delete(food); transactions.flush();
        assertEquals(stored, challenges.findById(c.getId()).orElseThrow().getFailureAnalysisSnapshot());
        var outcome = at(insights.challenge(owner, c.getId()), "outcome");
        assertEquals(11_000L, at(outcome, "atConfirmation").get("spent"));
        assertEquals(7_000L, at(outcome, "currentRecalculation").get("spent"));
        assertEquals(true, at(outcome, "atConfirmation").get("categoryBreakdownAvailable"));
        assertEquals(Challenge.Status.FAILED, challenges.findById(c.getId()).orElseThrow().getStatus());
    }

    @Test
    void scheduledReconciliationAlsoCapturesFailureFacts() throws Exception {
        User owner = user();
        Challenge c = challenge(owner, LocalDate.of(2026, 11, 11), 2, 500, Challenge.Status.IN_PROGRESS);
        transaction(owner, LocalDate.of(2026, 11, 12), 600,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        challengeState.reconcile(LocalDate.of(2026, 11, 13));
        Challenge failed = challenges.findById(c.getId()).orElseThrow();
        assertEquals(Challenge.Status.FAILED, failed.getStatus());
        assertEquals(600L, json.readValue(failed.getFailureAnalysisSnapshot(), FailureAnalysisSnapshot.class).totalSpent());
    }

    @Test
    void crossMonthChallengeCountsOnlyRelevantDaysAndFinalizationMonth() {
        User owner = user();
        Challenge c = challenge(owner, LocalDate.of(2026, 10, 30), 5, 1_000, Challenge.Status.SUCCESS);
        c.setFinishedAt(LocalDate.of(2026, 11, 4).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        challenges.saveAndFlush(c);
        transaction(owner, LocalDate.of(2026, 10, 30), 100,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        transaction(owner, LocalDate.of(2026, 11, 1), 200,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        var october = aggregate.monthly(owner, YearMonth.of(2026, 10));
        var november = aggregate.monthly(owner, YearMonth.of(2026, 11));
        assertEquals(2L, october.get("participationDays"));
        assertEquals(3L, november.get("participationDays"));
        assertEquals(100L, october.get("challengeExpense"));
        assertEquals(200L, november.get("challengeExpense"));
        assertEquals(0L, october.get("successConfirmed"));
        assertEquals(1L, november.get("successConfirmed"));
        assertEquals(1L, aggregate.overall(owner).get("success"));
    }

    @Test
    void challengeComparisonAndOverallUseNormalizedMetrics() {
        User owner = user();
        Challenge previous = challenge(owner, LocalDate.of(2026, 11, 1), 2, 200, Challenge.Status.SUCCESS);
        previous.setFinishedAt(LocalDate.of(2026, 11, 3).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        challenges.saveAndFlush(previous);
        transaction(owner, LocalDate.of(2026, 11, 1), 100,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        Challenge current = challenge(owner, LocalDate.of(2026, 11, 11), 5, 600, Challenge.Status.IN_PROGRESS);
        transaction(owner, LocalDate.of(2026, 11, 11), 300,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.SHOPPING, LedgerTypes.Source.MANUAL);
        var comparison = aggregate.comparison(owner, current.getId());
        assertEquals(true, comparison.get("comparable"));
        assertEquals(true, comparison.get("currentProvisional"));
        assertEquals(50.0, at(comparison, "dailyAverage").get("previous"));
        assertEquals(100.0, at(comparison, "dailyAverage").get("current"));
        assertEquals(false, aggregate.comparison(owner, previous.getId()).get("comparable"));
        var overall = aggregate.overall(owner);
        assertEquals(2, overall.get("totalCreated"));
        assertEquals(1L, overall.get("success"));
        assertEquals(100.0, overall.get("successRatePercent"));
        assertEquals(100L, overall.get("cumulativeUnusedBudget"));
        assertEquals("SUCCESS", at(insights.challenge(owner, previous.getId()), "outcome").get("type"));
    }

    @Test
    void shortAbandonedChallengeExcludesLaterDays() {
        User owner = user();
        Challenge c = challenge(owner, LocalDate.of(2026, 11, 10), 5, 500, Challenge.Status.ABANDONED);
        c.setFinishedAt(LocalDate.of(2026, 11, 11).atTime(18, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant());
        challenges.saveAndFlush(c);
        transaction(owner, LocalDate.of(2026, 11, 10), 50,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        transaction(owner, LocalDate.of(2026, 11, 12), 400,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        assertEquals(2, challengeAnalytics.overview(owner, c.getId()).get("elapsedDays"));
        assertEquals(50L, challengeAnalytics.overview(owner, c.getId()).get("cumulativeSpent"));
        assertEquals(2, ((List<?>) challengeAnalytics.patterns(owner, c.getId()).get("segments")).size());
        assertEquals("ABANDONED", at(insights.challenge(owner, c.getId()), "outcome").get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void individualAndOverallConcentrationUseTheSameSegments() {
        assertSegmentAligned(1, 0, "EARLY");
        assertSegmentAligned(2, 1, "LATE");
        assertSegmentAligned(5, 1, "MIDDLE");
        assertSegmentAligned(7, 4, "LATE");
    }

    @SuppressWarnings("unchecked")
    private void assertSegmentAligned(int duration, int expenseDay, String expected) {
        User owner = user();
        LocalDate start = LocalDate.of(2026, 11, 1);
        Challenge c = challenge(owner, start, duration, 10_000, Challenge.Status.SUCCESS);
        c.setFinishedAt(LocalDate.of(2026, 11, 10).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        challenges.saveAndFlush(c);
        transaction(owner, start.plusDays(expenseDay), 100,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        List<Map<String, Object>> segments = (List<Map<String, Object>>) challengeAnalytics.patterns(owner, c.getId()).get("segments");
        Map<String, Object> strongest = segments.stream()
            .max(Comparator.comparingLong(s -> (long) s.get("expense"))).orElseThrow();
        assertEquals(expected, strongest.get("segment"));
        assertEquals(strongest.get("segment"), aggregate.overall(owner).get("mostOftenConcentratedSegment"));
    }

    @Test
    void volatilityUsesUnroundedDeviationForCoefficient() {
        User owner = user();
        Challenge c = challenge(owner, LocalDate.of(2026, 11, 1), 4, 100, Challenge.Status.SUCCESS);
        transaction(owner, LocalDate.of(2026, 11, 4), 1,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        var volatility = at(challengeAnalytics.patterns(owner, c.getId()), "volatility");
        assertEquals(0.43, volatility.get("standardDeviation"));
        assertEquals(1.73, volatility.get("coefficientOfVariation"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void earlySavingsSubtractsOverAllowanceDaysBeforeWritingInsight() {
        User owner = user();
        Challenge c = challenge(owner, LocalDate.of(2026, 11, 1), 9, 9_000, Challenge.Status.SUCCESS);
        transaction(owner, LocalDate.of(2026, 11, 2), 3_000,
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD, LedgerTypes.Source.MANUAL);
        var analysis = insights.challenge(owner, c.getId());
        assertEquals(-17L, at(analysis, "outcome").get("earlySavings"));
        List<Map<String, Object>> messages = (List<Map<String, Object>>) analysis.get("insights");
        assertTrue(messages.stream().noneMatch(m -> "EARLY_SAVINGS".equals(m.get("code"))));
    }

    @Test
    void analyticsEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/analytics/monthly?month=2026-11")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/analytics/challenges/overall")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedApiUsesStandardEnvelopeAndHidesOtherChallenge() throws Exception {
        User owner = user(), other = user();
        Challenge hidden = challenge(other, LocalDate.of(2026, 11, 1), 2, 300, Challenge.Status.SUCCESS);
        var authentication = new UsernamePasswordAuthenticationToken(owner, null, List.of());
        mvc.perform(get("/api/v1/analytics/monthly?month=2026-11").with(authentication(authentication)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.totalExpense").value(0));
        mvc.perform(get("/api/v1/analytics/monthly?month=invalid").with(authentication(authentication)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_MONTH"));
        mvc.perform(get("/api/v1/analytics/challenges/" + hidden.getId()).with(authentication(authentication)))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CHALLENGE_NOT_FOUND"));
    }
}
