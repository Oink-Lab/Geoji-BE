package com.geoji.app.analytics;

import com.geoji.app.challenge.entity.Challenge;
import com.geoji.app.challenge.repository.ChallengeRepository;
import com.geoji.app.challenge.service.ChallengeCalculator;
import com.geoji.app.global.ApiException;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.user.entity.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.geoji.app.analytics.AnalyticsSupport.*;

@Service
@Transactional(readOnly = true)
public class ChallengeAnalyticsService {
    private final ChallengeRepository challenges;
    private final TransactionRepository transactions;
    private final ChallengeCalculator calculator;
    private final Clock clock;

    public ChallengeAnalyticsService(ChallengeRepository challenges, TransactionRepository transactions,
                                     ChallengeCalculator calculator, Clock clock) {
        this.challenges = challenges;
        this.transactions = transactions;
        this.calculator = calculator;
        this.clock = clock;
    }

    record Day(LocalDate date, int number, long allowance, long spent, long difference,
               long cumulativeSpent, long remaining, double budgetUsePercent, boolean hasRecordedExpense) {}
    record Context(Challenge challenge, LocalDate through, List<Transaction> expenses, List<Day> days) {
        long spent() { return sum(expenses); }
        int dayCount() { return days.size(); }
    }

    Context load(User user, UUID id) {
        Challenge c = challenges.findByIdAndUserId(id, user.getId()).orElseThrow(() ->
            new ApiException(HttpStatus.NOT_FOUND, "CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다."));
        return load(c);
    }

    Context load(Challenge c) {
        return load(c, null);
    }

    Context load(Challenge c, List<Transaction> preloaded) {
        LocalDate today = LocalDate.now(clock), through = c.getEndDate();
        if (through.isAfter(today)) through = today;
        if ((c.getStatus() == Challenge.Status.FAILED || c.getStatus() == Challenge.Status.ABANDONED)
            && c.getFinishedAt() != null) {
            LocalDate finished = LocalDate.ofInstant(c.getFinishedAt(), ZoneId.of("Asia/Seoul"));
            if (finished.isBefore(through)) through = finished;
        }
        if (c.getStatus() == Challenge.Status.SCHEDULED || through.isBefore(c.getStartDate()))
            return new Context(c, through, List.of(), List.of());
        // Current transaction state is intentional; failure snapshots remain separate.
        List<Transaction> expenses = preloaded == null
            ? transactions.findChallengeExpenses(c.getUser().getId(), c.getStartDate(), through)
            : within(preloaded, c.getStartDate(), through);
        Map<LocalDate, Long> byDay = daily(expenses);
        List<Day> days = new ArrayList<>();
        long cumulative = 0;
        for (LocalDate date = c.getStartDate(); !date.isAfter(through); date = date.plusDays(1)) {
            long allowance = calculator.allowance(c, date, cumulative);
            long spent = byDay.getOrDefault(date, 0L);
            cumulative = Math.addExact(cumulative, spent);
            days.add(new Day(date, days.size() + 1, allowance, spent, allowance - spent,
                cumulative, c.getTotalBudget() - cumulative, percent(cumulative, c.getTotalBudget()),
                byDay.containsKey(date)));
        }
        return new Context(c, through, expenses, days);
    }

    Map<String, Object> dayMap(Day d, int durationDays) {
        double periodProgress = percent(d.number(), durationDays);
        return map("date", d.date(), "day", d.number(), "allowance", d.allowance(),
            "spent", d.spent(), "difference", d.difference(), "cumulativeSpent", d.cumulativeSpent(),
            "remainingBudget", d.remaining(), "budgetUsePercent", d.budgetUsePercent(),
            "periodProgressPercent", periodProgress,
            "depletionGapPoints", round(d.budgetUsePercent() - periodProgress));
    }

    static int segmentIndex(int dayIndex, int dayCount) {
        if (dayCount <= 1) return 0;
        if (dayCount == 2) return dayIndex == 0 ? 0 : 2;
        for (int part = 0; part < 3; part++)
            if (dayIndex < (part + 1) * dayCount / 3) return part;
        return 2;
    }

    public Map<String, Object> overview(User user, UUID id) {
        Context ctx = load(user, id);
        Challenge c = ctx.challenge();
        long spent = ctx.spent();
        long under = ctx.days().stream().filter(d -> d.difference() >= 0).count();
        long over = ctx.dayCount() - under;
        long difference = ctx.days().stream().mapToLong(Day::difference).sum();
        return map("challengeId", id, "userId", user.getId(), "status", c.getStatus(),
            "analysisThrough", ctx.through(), "provisional", c.getStatus() == Challenge.Status.IN_PROGRESS,
            "totalBudget", c.getTotalBudget(), "cumulativeSpent", spent,
            "remainingBudget", c.getTotalBudget() - spent, "unusedBudget", Math.max(0, c.getTotalBudget() - spent),
            "budgetUsePercent", percent(spent, c.getTotalBudget()), "averageDailySpent",
            ctx.dayCount() == 0 ? 0 : round((double) spent / ctx.dayCount()),
            "initialDailyAllowance", calculator.allowance(c, c.getStartDate(), 0),
            "averageDailyRecommended", ctx.dayCount() == 0 ? 0 : round(ctx.days().stream()
                .mapToLong(Day::allowance).average().orElse(0)),
            "budgetDifference", c.getTotalBudget() - spent, "elapsedDays", ctx.dayCount(),
            "durationDays", c.getDurationDays(), "periodProgressPercent", percent(ctx.dayCount(), c.getDurationDays()),
            "budgetUseMinusPeriodProgressPoints", round(percent(spent, c.getTotalBudget()) - percent(ctx.dayCount(), c.getDurationDays())),
            "daysWithinAllowance", under, "daysOverAllowance", over,
            "allowanceCompliancePercent", percent(under, ctx.dayCount()),
            "maxDailySaving", Math.max(0, ctx.days().stream().mapToLong(Day::difference).max().orElse(0)),
            "maxDailyOverageAmount", Math.max(0, -ctx.days().stream().mapToLong(Day::difference).min().orElse(0)),
            "averageDailyDifference", ctx.dayCount() == 0 ? 0 : round((double) difference / ctx.dayCount()),
            "dailyTrend", ctx.days().stream().map(d -> dayMap(d, c.getDurationDays())).toList(),
            "analysis", status(ctx.dayCount() == 0 ? "NOT_STARTED" : "AVAILABLE", ctx.dayCount() > 0));
    }

    public Map<String, Object> patterns(User user, UUID id) {
        Context ctx = load(user, id);
        List<Day> days = ctx.days();
        List<Map<String, Object>> thirds = new ArrayList<>();
        List<List<Day>> segmentDays = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int index = 0; index < days.size(); index++)
            segmentDays.get(segmentIndex(index, days.size())).add(days.get(index));
        String[] names = {"EARLY", "MIDDLE", "LATE"};
        for (int part = 0; part < names.length; part++) {
            List<Day> segment = segmentDays.get(part);
            if (segment.isEmpty()) continue;
            List<Transaction> segmentExpense = within(ctx.expenses(), segment.get(0).date(), segment.get(segment.size() - 1).date());
            long spent = sum(segmentExpense);
            var cats = categoryRows(segmentExpense, segment.size());
            thirds.add(map("segment", names[part], "from", segment.get(0).date(), "to", segment.get(segment.size() - 1).date(),
                "expense", spent, "dailyAverage", round((double) spent / segment.size()),
                "sharePercent", percent(spent, ctx.spent()), "topCategory", cats.isEmpty() ? null : cats.get(0),
                "daysOverAllowance", segment.stream().filter(d -> d.difference() < 0).count(),
                "budgetUsePoints", percent(spent, ctx.challenge().getTotalBudget()),
                "periodSharePercent", percent(segment.size(), ctx.challenge().getDurationDays()),
                "depletionGapPoints", round(percent(spent, ctx.challenge().getTotalBudget())
                    - percent(segment.size(), ctx.challenge().getDurationDays()))));
        }
        double mean = days.isEmpty() ? 0 : (double) ctx.spent() / days.size();
        double variance = days.stream().mapToDouble(d -> Math.pow(d.spent() - mean, 2)).average().orElse(0);
        double deviation = Math.sqrt(variance);
        int maxUnder = 0, maxOver = 0, under = 0, over = 0;
        for (Day day : days) {
            if (day.difference() >= 0) { under++; over = 0; }
            else { over++; under = 0; }
            maxUnder = Math.max(maxUnder, under); maxOver = Math.max(maxOver, over);
        }
        var topDays = topDays(daily(ctx.expenses()), 3);
        long topThree = topDays.stream().mapToLong(d -> (long) d.get("amount")).sum();
        List<Transaction> fixed = ctx.expenses().stream().filter(t -> t.getSource() == LedgerTypes.Source.RECURRING).toList();
        return map("challengeId", id, "analysisThrough", ctx.through(), "segments", thirds,
            "volatility", map("dailyMean", round(mean), "maximum", days.stream().mapToLong(Day::spent).max().orElse(0),
                "minimum", days.stream().mapToLong(Day::spent).min().orElse(0), "standardDeviation", round(deviation),
                "coefficientOfVariation", mean == 0 || days.size() < 2 ? null : round(deviation / mean),
                "comparable", mean > 0 && days.size() >= 2),
            "streaks", map("longestWithinAllowance", maxUnder, "longestOverAllowance", maxOver,
                "currentWithinAllowance", under, "currentOverAllowance", over,
                "daysWithoutRecordedExpense", days.stream().filter(d -> !d.hasRecordedExpense()).count()),
            "categories", categoryRows(ctx.expenses(), days.size()),
            "topThreeCategories", categoryRows(ctx.expenses(), days.size()).stream().limit(3).toList(),
            "recurringCategories", categoryRows(fixed, days.size()),
            "recurringSharePercent", percent(sum(fixed), ctx.spent()),
            "concentration", map("mostSpentDay", topDays.isEmpty() ? null : topDays.get(0),
                "leastSpentDayWithExpense", daily(ctx.expenses()).entrySet().stream()
                    .min(Map.Entry.<LocalDate, Long>comparingByValue().thenComparing(Map.Entry::getKey))
                    .map(e -> map("date", e.getKey(), "amount", e.getValue())).orElse(null),
                "largestTransaction", largest(ctx.expenses()), "topThreeDays", topDays,
                "topThreeDaysSharePercent", percent(topThree, ctx.spent())));
    }
}
