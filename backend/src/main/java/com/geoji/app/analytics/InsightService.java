package com.geoji.app.analytics;

import com.geoji.app.challenge.entity.Challenge;
import com.geoji.app.challenge.dto.FailureAnalysisSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;

import static com.geoji.app.analytics.AnalyticsSupport.*;

@Service
@Transactional(readOnly = true)
public class InsightService {
    private final MonthlyAnalyticsService monthly;
    private final ChallengeAnalyticsService challenges;
    private final ChallengeAggregateService aggregate;
    private final ObjectMapper json;

    public InsightService(MonthlyAnalyticsService monthly, ChallengeAnalyticsService challenges,
                          ChallengeAggregateService aggregate, ObjectMapper json) {
        this.monthly = monthly; this.challenges = challenges; this.aggregate = aggregate;
        this.json = json;
    }

    private Map<String, Object> message(String code, String text, Object evidence) {
        return map("code", code, "message", text, "evidence", evidence);
    }

    public Map<String, Object> monthly(User user, YearMonth month) {
        var basic = monthly.monthly(user, month);
        var fixed = monthly.recurring(user, month);
        var comparison = monthly.comparison(user, month);
        List<Map<String, Object>> messages = new ArrayList<>();
        long total = ((Number) basic.get("totalExpense")).longValue();
        @SuppressWarnings("unchecked") var top = (Map<String, Object>) basic.get("mostSpentCategory");
        if (top != null) messages.add(message("TOP_CATEGORY",
            month + "에는 " + top.get("name") + "가 소비의 " + top.get("sharePercent") + "%를 차지했어요.",
            map("category", top.get("code"), "sharePercent", top.get("sharePercent"), "totalExpense", total)));
        long recurring = ((Number) fixed.get("recurringExpense")).longValue();
        if (recurring > 0) messages.add(message("RECURRING_SHARE",
            "고정지출이 전체 소비의 " + fixed.get("recurringSharePercent") + "%였어요.",
            map("recurringExpense", recurring, "sharePercent", fixed.get("recurringSharePercent"))));
        @SuppressWarnings("unchecked") var aligned = (Map<String, Object>) comparison.get("sameElapsedDaysComparison");
        if (aligned != null) {
            @SuppressWarnings("unchecked") var average = (Map<String, Object>) aligned.get("dailyAverage");
            if (Boolean.TRUE.equals(average.get("comparable"))) messages.add(message("DAILY_AVERAGE_CHANGE",
                "지난달 같은 기간 대비 일평균 소비가 " + average.get("changePercent") + "% 변했어요.", average));
        }
        return map("month", month, "userId", user.getId(),
            "analysis", status(messages.isEmpty() ? "INSUFFICIENT_DATA" : "AVAILABLE", !messages.isEmpty()),
            "insights", messages);
    }

    public Map<String, Object> challenge(User user, UUID id) {
        var ctx = challenges.load(user, id);
        Challenge c = ctx.challenge();
        long spent = ctx.spent();
        var byCategory = categoryRows(ctx.expenses(), ctx.dayCount());
        var byDate = topDays(daily(ctx.expenses()), 3);
        List<Map<String, Object>> messages = new ArrayList<>();
        long under = ctx.days().stream().filter(d -> d.difference() >= 0).count();
        int earlyLength = ctx.dayCount() == 0 ? 0 : Math.max(1, ctx.dayCount() / 3);
        int lateStart = Math.max(0, ctx.dayCount() - earlyLength);
        long earlySpent = ctx.days().stream().limit(earlyLength).mapToLong(ChallengeAnalyticsService.Day::spent).sum();
        long lateSpent = ctx.days().stream().skip(lateStart).mapToLong(ChallengeAnalyticsService.Day::spent).sum();
        long earlySavings = ctx.days().stream().limit(earlyLength)
            .mapToLong(ChallengeAnalyticsService.Day::difference).sum();
        if (ctx.dayCount() > 0) messages.add(message("ALLOWANCE_DAYS",
            ctx.dayCount() + "일 중 " + under + "일 동안 하루 권장금액을 지켰어요.",
            map("elapsedDays", ctx.dayCount(), "daysWithinAllowance", under)));
        if (!byCategory.isEmpty()) messages.add(message("TOP_CATEGORY",
            byCategory.get(0).get("name") + "가 현재 소비에서 가장 큰 비중을 차지했어요.", byCategory.get(0)));
        if (!byDate.isEmpty() && spent > 0) messages.add(message("TOP_DAY",
            byDate.get(0).get("date") + "에 소비가 가장 많았어요.", byDate.get(0)));

        Map<String, Object> outcome = null;
        if (c.getStatus() == Challenge.Status.FAILED) {
            Long snapshot = c.getFailureSpentSnapshot();
            FailureAnalysisSnapshot detail = failureSnapshot(c);
            outcome = map("type", "FAILURE", "confirmedAt", c.getFailureAt(),
                "atConfirmation", map("totalBudget", c.getTotalBudget(), "spent", snapshot,
                    "overBudget", snapshot == null ? null : Math.max(0, snapshot - c.getTotalBudget()),
                    "budgetUsePercent", snapshot == null ? null : percent(snapshot, c.getTotalBudget()),
                    "categoryBreakdownAvailable", detail != null,
                    "snapshotVersion", detail == null ? null : detail.version(),
                    "through", detail == null ? null : detail.through(),
                    "categories", detail == null ? null : detail.categories(),
                    "topTransactions", detail == null ? null : detail.topTransactions(),
                    "maxSpendingDay", detail == null ? null : detail.maxSpendingDay(),
                    "topThreeDays", detail == null ? null : detail.topThreeDays(),
                    "topOneDaySharePercent", detail == null ? null : detail.topOneDaySharePercent(),
                    "topThreeDaysSharePercent", detail == null ? null : detail.topThreeDaysSharePercent(),
                    "recurringExpense", detail == null ? null : detail.recurringExpense(),
                    "recurringSharePercent", detail == null ? null : detail.recurringSharePercent()),
                "currentRecalculation", map("spent", spent, "budgetUsePercent", percent(spent, c.getTotalBudget()),
                    "topCategory", byCategory.isEmpty() ? null : byCategory.get(0),
                    "topDay", byDate.isEmpty() ? null : byDate.get(0),
                    "earlyExpenseSharePercent", percent(earlySpent, spent),
                    "earlyBudgetUseMinusPeriodProgressPoints", round(percent(earlySpent, c.getTotalBudget())
                        - percent(earlyLength, c.getDurationDays())),
                    "topThreeDaysSharePercent", percent(byDate.stream().mapToLong(d -> (long) d.get("amount")).sum(), spent),
                    "recurringSharePercent", fixedShare(ctx), "topTransactions", ctx.expenses().stream()
                        .sorted(Comparator.comparingLong(Transaction::getAmount).reversed()).limit(3)
                        .map(t -> map("id", t.getId(), "amount", t.getAmount(), "date", t.getTransactionDate(),
                            "category", t.getCategoryCode().name())).toList(),
                    "overAllowanceDays", ctx.days().stream().filter(d -> d.difference() < 0)
                        .map(d -> map("date", d.date(), "overage", -d.difference())).toList()));
            if (snapshot != null) messages.add(message("BUDGET_EXCEEDED",
                "실패 확정 당시 총예산을 " + Math.max(0, snapshot - c.getTotalBudget()) + "원 초과했어요.",
                map("snapshotSpent", snapshot, "totalBudget", c.getTotalBudget())));
        } else if (c.getStatus() == Challenge.Status.SUCCESS) {
            long unused = Math.max(0, c.getTotalBudget() - spent);
            outcome = map("type", "SUCCESS", "unusedBudget", unused,
                "budgetUsePercent", percent(spent, c.getTotalBudget()), "averageDailySpent",
                ctx.dayCount() == 0 ? 0 : round((double) spent / ctx.dayCount()),
                "allowanceCompliancePercent", percent(under, ctx.dayCount()),
                "daysWithoutRecordedExpense", ctx.days().stream().filter(d -> !d.hasRecordedExpense()).count(),
                "largestDailySaving", Math.max(0, ctx.days().stream().mapToLong(ChallengeAnalyticsService.Day::difference).max().orElse(0)),
                "longestWithinAllowanceStreak", longestWithin(ctx),
                "largestSavingDay", ctx.days().stream().max(Comparator.comparingLong(ChallengeAnalyticsService.Day::difference))
                    .map(d -> map("date", d.date(), "amount", Math.max(0, d.difference()))).orElse(null),
                "earlySavings", earlySavings, "lateExpense", lateSpent,
                "earlyExpenseSharePercent", percent(earlySpent, spent),
                "lateExpenseSharePercent", percent(lateSpent, spent));
            messages.add(message("UNUSED_BUDGET", "예산 중 " + unused + "원을 사용하지 않았어요.",
                map("unusedBudget", unused, "totalBudget", c.getTotalBudget())));
            if (earlySavings > 0) messages.add(message("EARLY_SAVINGS",
                "초반에는 일일 권장금액보다 합계 " + earlySavings + "원 적게 사용했어요.",
                map("earlyDays", earlyLength, "earlySavings", earlySavings)));
        } else if (c.getStatus() == Challenge.Status.ABANDONED) {
            outcome = map("type", "ABANDONED", "abandonedAt", c.getFinishedAt(),
                "participationDays", ctx.dayCount(), "spent", spent,
                "budgetUsePercent", percent(spent, c.getTotalBudget()),
                "allowanceCompliancePercent", percent(under, ctx.dayCount()),
                "topCategory", byCategory.isEmpty() ? null : byCategory.get(0),
                "earlyExpenseSharePercent", percent(earlySpent, spent));
        }
        if (ctx.dayCount() > 0) {
            var previous = aggregate.comparison(user, id);
            if (Boolean.TRUE.equals(previous.get("comparable"))) {
                @SuppressWarnings("unchecked") var daily = (Map<String, Object>) previous.get("dailyAverage");
                if (Boolean.TRUE.equals(daily.get("comparable")) && ((Number) daily.get("difference")).doubleValue() < 0)
                    messages.add(message("DAILY_SPENDING_IMPROVED", "직전 챌린지보다 일평균 소비가 줄었어요.", daily));
            }
        }
        return map("challengeId", id, "userId", user.getId(), "status", c.getStatus(),
            "analysisThrough", ctx.through(), "analysis", status(messages.isEmpty() ? "INSUFFICIENT_DATA" : "AVAILABLE", !messages.isEmpty()),
            "outcome", outcome, "insights", messages);
    }

    private double fixedShare(ChallengeAnalyticsService.Context ctx) {
        return percent(sum(ctx.expenses().stream().filter(t -> t.getSource() == LedgerTypes.Source.RECURRING).toList()), ctx.spent());
    }

    private FailureAnalysisSnapshot failureSnapshot(Challenge challenge) {
        if (challenge.getFailureAnalysisSnapshot() == null) return null;
        try {
            return json.readValue(challenge.getFailureAnalysisSnapshot(), FailureAnalysisSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored failure snapshot", e);
        }
    }

    private int longestWithin(ChallengeAnalyticsService.Context ctx) {
        int run = 0, best = 0;
        for (var day : ctx.days()) {
            run = day.difference() >= 0 ? run + 1 : 0;
            best = Math.max(best, run);
        }
        return best;
    }
}
