package com.geoji.app.analytics;

import com.geoji.app.challenge.entity.Challenge;
import com.geoji.app.challenge.repository.ChallengeRepository;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static com.geoji.app.analytics.AnalyticsSupport.*;

@Service
@Transactional(readOnly = true)
public class ChallengeAggregateService {
    private final ChallengeRepository challenges;
    private final TransactionRepository transactions;
    private final ChallengeAnalyticsService analytics;
    private final Clock clock;

    public ChallengeAggregateService(ChallengeRepository challenges, TransactionRepository transactions,
                                     ChallengeAnalyticsService analytics, Clock clock) {
        this.challenges = challenges; this.transactions = transactions; this.analytics = analytics; this.clock = clock;
    }

    private List<Challenge> all(User user) {
        return challenges.findByUserIdAndStatusIn(user.getId(), List.of(Challenge.Status.values())).stream()
            .sorted(Comparator.comparing(Challenge::getStartDate).thenComparing(Challenge::getId)).toList();
    }

    private List<ChallengeAnalyticsService.Context> contexts(User user) {
        List<Challenge> all = all(user);
        if (all.isEmpty()) return List.of();
        LocalDate from = all.stream().map(Challenge::getStartDate).min(LocalDate::compareTo).orElseThrow();
        LocalDate to = all.stream().map(Challenge::getEndDate).max(LocalDate::compareTo).orElseThrow();
        LocalDate today = LocalDate.now(clock);
        if (to.isAfter(today)) to = today;
        List<Transaction> expenses = to.isBefore(from) ? List.of() : transactions.findChallengeExpenses(user.getId(), from, to);
        return all.stream().map(c -> analytics.load(c, expenses)).toList();
    }

    private static boolean terminal(Challenge c) {
        return c.getStatus() == Challenge.Status.SUCCESS || c.getStatus() == Challenge.Status.FAILED
            || c.getStatus() == Challenge.Status.ABANDONED;
    }

    public Map<String, Object> comparison(User user, UUID id) {
        var contexts = contexts(user);
        var current = contexts.stream().filter(c -> c.challenge().getId().equals(id)).findFirst()
            .orElseGet(() -> analytics.load(user, id));
        var previous = contexts.stream().filter(c -> c.challenge().getStartDate().isBefore(current.challenge().getStartDate()))
            .max(Comparator.comparing(c -> c.challenge().getStartDate())).orElse(null);
        if (previous == null) return map("challengeId", id, "comparable", false, "reason", "NO_PREVIOUS_CHALLENGE");
        return map("challengeId", id, "previousChallengeId", previous.challenge().getId(),
            "comparable", true, "currentProvisional", !terminal(current.challenge()),
            "currentThrough", current.through(), "previousThrough", previous.through(),
            "totalBudget", change(previous.challenge().getTotalBudget(), current.challenge().getTotalBudget()),
            "durationDays", change(previous.challenge().getDurationDays(), current.challenge().getDurationDays()),
            "totalExpense", change(previous.spent(), current.spent()),
            "dailyAverage", change(average(previous), average(current)),
            "budgetUsePercent", change(percent(previous.spent(), previous.challenge().getTotalBudget()),
                percent(current.spent(), current.challenge().getTotalBudget())),
            "unusedBudget", change(Math.max(0, previous.challenge().getTotalBudget() - previous.spent()),
                Math.max(0, current.challenge().getTotalBudget() - current.spent())),
            "allowanceCompliancePercent", change(compliance(previous), compliance(current)),
            "daysWithoutRecordedExpense", change(noExpense(previous), noExpense(current)),
            "daysWithoutRecordedExpensePercent", change(percent(noExpense(previous), previous.dayCount()),
                percent(noExpense(current), current.dayCount())),
            "longestWithinAllowance", change(longest(previous, true), longest(current, true)),
            "longestOverAllowance", change(longest(previous, false), longest(current, false)),
            "recurringSharePercent", change(fixedShare(previous), fixedShare(current)),
            "dailyStandardDeviation", change(deviation(previous), deviation(current)),
            "categories", categoryComparison(previous, current),
            "previousStatus", previous.challenge().getStatus(), "currentStatus", current.challenge().getStatus(),
            "comparisonBasis", "Different durations are compared with daily and percentage metrics.");
    }

    private Map<String, Object> categoryComparison(ChallengeAnalyticsService.Context previous,
                                                     ChallengeAnalyticsService.Context current) {
        var a = categories(previous.expenses()); var b = categories(current.expenses());
        Map<String, Object> result = new LinkedHashMap<>();
        for (LedgerTypes.Category c : LedgerTypes.Category.values()) if (c.type == LedgerTypes.Type.EXPENSE)
            result.put(c.name(), map("amount", change(a.getOrDefault(c, 0L), b.getOrDefault(c, 0L)),
                "dailyAverage", change(previous.dayCount() == 0 ? 0 : round((double) a.getOrDefault(c, 0L) / previous.dayCount()),
                    current.dayCount() == 0 ? 0 : round((double) b.getOrDefault(c, 0L) / current.dayCount()))));
        return result;
    }

    private double average(ChallengeAnalyticsService.Context c) {
        return c.dayCount() == 0 ? 0 : round((double) c.spent() / c.dayCount());
    }
    private double compliance(ChallengeAnalyticsService.Context c) {
        return percent(c.days().stream().filter(d -> d.difference() >= 0).count(), c.dayCount());
    }
    private long noExpense(ChallengeAnalyticsService.Context c) {
        return c.days().stream().filter(d -> !d.hasRecordedExpense()).count();
    }
    private double fixedShare(ChallengeAnalyticsService.Context c) {
        return percent(sum(c.expenses().stream().filter(t -> t.getSource() == LedgerTypes.Source.RECURRING).toList()), c.spent());
    }
    private double deviation(ChallengeAnalyticsService.Context c) {
        if (c.dayCount() < 2) return 0;
        double mean = average(c);
        return round(Math.sqrt(c.days().stream().mapToDouble(d -> Math.pow(d.spent() - mean, 2)).average().orElse(0)));
    }
    private int longest(ChallengeAnalyticsService.Context c, boolean within) {
        int best = 0, run = 0;
        for (var d : c.days()) { run = (d.difference() >= 0) == within ? run + 1 : 0; best = Math.max(best, run); }
        return best;
    }

    public Map<String, Object> overall(User user) {
        var contexts = contexts(user);
        List<ChallengeAnalyticsService.Context> finished = contexts.stream().filter(c -> terminal(c.challenge())).toList();
        long success = finished.stream().filter(c -> c.challenge().getStatus() == Challenge.Status.SUCCESS).count();
        long failed = finished.stream().filter(c -> c.challenge().getStatus() == Challenge.Status.FAILED).count();
        long abandoned = finished.size() - success - failed;
        var successful = finished.stream().filter(c -> c.challenge().getStatus() == Challenge.Status.SUCCESS).toList();
        long successBudget = successful.stream().mapToLong(c -> c.challenge().getTotalBudget()).sum();
        long successSpent = successful.stream().mapToLong(ChallengeAnalyticsService.Context::spent).sum();
        long unused = successful.stream().mapToLong(c -> Math.max(0, c.challenge().getTotalBudget() - c.spent())).sum();
        int run = 0, best = 0;
        for (var c : finished) { run = c.challenge().getStatus() == Challenge.Status.SUCCESS ? run + 1 : 0; best = Math.max(best, run); }
        var recent = finished.stream().sorted(Comparator.comparing((ChallengeAnalyticsService.Context c) ->
            c.challenge().getFinishedAt(), Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .limit(5).map(c -> map("id", c.challenge().getId(), "status", c.challenge().getStatus(),
                "finishedAt", c.challenge().getFinishedAt())).toList();
        Map<LedgerTypes.Category, Integer> topCounts = new EnumMap<>(LedgerTypes.Category.class);
        Map<LedgerTypes.Category, Long> overdaySpending = new EnumMap<>(LedgerTypes.Category.class);
        long[] segmentWins = new long[3];
        for (var c : finished) {
            var cats = categories(c.expenses());
            cats.keySet().stream().sorted(Comparator.<LedgerTypes.Category>comparingLong(cats::get)
                .reversed().thenComparing(Enum::name)).findFirst()
                .ifPresent(category -> topCounts.merge(category, 1, Integer::sum));
            for (var day : c.days()) if (day.difference() < 0)
                categories(within(c.expenses(), day.date(), day.date())).forEach((category, amount) ->
                    overdaySpending.merge(category, amount, Long::sum));
            long[] segments = new long[3];
            for (int i = 0; i < c.dayCount(); i++) {
                int index = c.dayCount() == 2 ? i * 2 : Math.min(2, i * 3 / c.dayCount());
                segments[index] += c.days().get(i).spent();
            }
            if (c.spent() > 0) { int index = 0; for (int i = 1; i < 3; i++) if (segments[i] > segments[index]) index = i; segmentWins[index]++; }
        }
        return map("userId", user.getId(), "totalCreated", contexts.size(),
            "scheduled", contexts.stream().filter(c -> c.challenge().getStatus() == Challenge.Status.SCHEDULED).count(),
            "inProgress", contexts.stream().filter(c -> c.challenge().getStatus() == Challenge.Status.IN_PROGRESS).count(),
            "completed", finished.size(), "success", success, "failed", failed, "abandoned", abandoned,
            "successRatePercent", success + failed == 0 ? null : percent(success, success + failed),
            "successfulTotalBudget", successBudget, "successfulTotalSpent", successSpent,
            "cumulativeUnusedBudget", unused,
            "averageBudgetUsePercent", finished.isEmpty() ? null : round(finished.stream()
                .mapToDouble(c -> percent(c.spent(), c.challenge().getTotalBudget())).average().orElse(0)),
            "averageDailySpent", finished.isEmpty() ? null : round(finished.stream().mapToDouble(this::average).average().orElse(0)),
            "averageParticipationDays", finished.isEmpty() ? null : round(finished.stream().mapToInt(ChallengeAnalyticsService.Context::dayCount).average().orElse(0)),
            "averageUnusedBudget", successful.isEmpty() ? null : round((double) unused / successful.size()),
            "currentSuccessStreak", run, "longestSuccessStreak", best, "recentResults", recent,
            "mostFrequentlyTopCategory", topCounts.entrySet().stream()
                .sorted(Map.Entry.<LedgerTypes.Category, Integer>comparingByValue().reversed()
                    .thenComparing(e -> e.getKey().name())).findFirst().map(e -> e.getKey().name()).orElse(null),
            "overAllowanceDayCategories", overdaySpending.entrySet().stream().sorted(Map.Entry.<LedgerTypes.Category, Long>comparingByValue().reversed())
                .map(e -> map("category", e.getKey().name(), "amount", e.getValue())).toList(),
            "mostOftenConcentratedSegment", segmentWins[0] + segmentWins[1] + segmentWins[2] == 0 ? null
                : new String[]{"EARLY", "MIDDLE", "LATE"}[segmentWins[1] > segmentWins[0] && segmentWins[1] >= segmentWins[2] ? 1
                    : segmentWins[2] > segmentWins[0] && segmentWins[2] > segmentWins[1] ? 2 : 0],
            "averageAllowanceCompliancePercent", finished.isEmpty() ? null : round(finished.stream().mapToDouble(this::compliance).average().orElse(0)),
            "averageRecurringSharePercent", finished.isEmpty() ? null : round(finished.stream().mapToDouble(this::fixedShare).average().orElse(0)));
    }

    public Map<String, Object> monthly(User user, YearMonth month) {
        LocalDate today = LocalDate.now(clock), from = month.atDay(1), through = end(month, today);
        var contexts = contexts(user);
        long days = 0, under = 0, over = 0, spent = 0, fixed = 0, success = 0, failed = 0, abandoned = 0;
        for (var c : contexts) {
            for (var d : c.days()) if (!d.date().isBefore(from) && !d.date().isAfter(through)) {
                days++; spent += d.spent();
                if (d.difference() >= 0) under++; else over++;
            }
            fixed += sum(c.expenses().stream().filter(t -> t.getSource() == LedgerTypes.Source.RECURRING
                && !t.getTransactionDate().isBefore(from) && !t.getTransactionDate().isAfter(through)).toList());
            if (c.challenge().getFinishedAt() != null) {
                LocalDate finished = LocalDate.ofInstant(c.challenge().getFinishedAt(), ZoneId.of("Asia/Seoul"));
                if (!finished.isBefore(from) && !finished.isAfter(through)) {
                    switch (c.challenge().getStatus()) {
                        case SUCCESS -> success++;
                        case FAILED -> failed++;
                        case ABANDONED -> abandoned++;
                        default -> { }
                    }
                }
            }
        }
        return map("month", month, "through", through, "userId", user.getId(),
            "participationDays", days, "daysWithinAllowance", under, "daysOverAllowance", over,
            "challengeExpense", spent, "recurringExpenseWithinChallenges", fixed,
            "successConfirmed", success, "failureConfirmed", failed, "abandoned", abandoned);
    }
}
