package com.geoji.app.analytics;

import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.geoji.app.analytics.AnalyticsSupport.*;

@Service
@Transactional(readOnly = true)
public class MonthlyAnalyticsService {
    private final TransactionRepository transactions;
    private final Clock clock;

    public MonthlyAnalyticsService(TransactionRepository transactions, Clock clock) {
        this.transactions = transactions;
        this.clock = clock;
    }

    record Period(YearMonth month, LocalDate from, LocalDate to, List<Transaction> all,
                  List<Transaction> expense, List<Transaction> income, int days) {}

    Period period(User user, YearMonth month, LocalDate cutoff) {
        LocalDate from = month.atDay(1), to = cutoff == null ? end(month, LocalDate.now(clock)) : cutoff;
        List<Transaction> all = transactions.findByUserIdAndTransactionDateBetween(user.getId(), from, to);
        return new Period(month, from, to, all, expenses(all),
            all.stream().filter(t -> t.getType() == LedgerTypes.Type.INCOME).toList(),
            (int) ChronoUnit.DAYS.between(from, to) + 1);
    }

    public Map<String, Object> monthly(User user, YearMonth month) {
        Period p = period(user, month, null);
        long spent = sum(p.expense());
        List<Map<String, Object>> categories = categoryRows(p.expense(), p.days());
        return map("month", month, "through", p.to(), "userId", user.getId(),
            "analysis", status(p.expense().isEmpty() ? "NO_EXPENSES" : "AVAILABLE", !p.expense().isEmpty()),
            "totalExpense", spent, "totalIncome", sum(p.income()), "expenseCount", p.expense().size(),
            "incomeCount", p.income().size(), "averageExpenseTransaction",
            p.expense().isEmpty() ? 0 : round((double) spent / p.expense().size()),
            "dailyAverageExpense", round((double) spent / p.days()), "expenseDays", daily(p.expense()).size(),
            "categories", categories, "topThreeCategories", categories.stream().limit(3).toList(),
            "mostSpentCategory", categories.isEmpty() ? null : categories.get(0),
            "leastSpentCategory", categories.isEmpty() ? null : categories.get(categories.size() - 1),
            "topCategorySharePercent", categories.isEmpty() ? 0 : categories.get(0).get("sharePercent"));
    }

    public Map<String, Object> patterns(User user, YearMonth month) {
        Period p = period(user, month, null);
        Map<LocalDate, Long> expenseDays = daily(p.expense()), incomeDays = daily(p.income());
        Map<LocalDate, Long> countDays = p.expense().stream().collect(Collectors.groupingBy(
            Transaction::getTransactionDate, TreeMap::new, Collectors.counting()));
        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate date = p.from(); !date.isAfter(p.to()); date = date.plusDays(1))
            days.add(map("date", date, "expense", expenseDays.getOrDefault(date, 0L),
                "income", incomeDays.getOrDefault(date, 0L), "expenseCount", countDays.getOrDefault(date, 0L)));
        List<Map<String, Object>> topDays = topDays(expenseDays, 5);
        var least = expenseDays.entrySet().stream().filter(e -> e.getValue() > 0)
            .min(Map.Entry.<LocalDate, Long>comparingByValue().thenComparing(Map.Entry::getKey))
            .map(e -> map("date", e.getKey(), "amount", e.getValue())).orElse(null);
        long total = sum(p.expense());
        List<Map<String, Object>> weeks = new ArrayList<>();
        for (int week = 0; week < 5; week++) {
            LocalDate from = p.from().plusDays(week * 7L);
            if (from.isAfter(p.to())) break;
            LocalDate to = from.plusDays(6);
            if (to.isAfter(p.to())) to = p.to();
            List<Transaction> items = within(p.expense(), from, to);
            var categories = categoryRows(items, (int) ChronoUnit.DAYS.between(from, to) + 1);
            weeks.add(map("week", week + 1, "from", from, "to", to, "expense", sum(items),
                "dailyAverage", round((double) sum(items) / (ChronoUnit.DAYS.between(from, to) + 1)),
                "count", items.size(), "topCategory", categories.isEmpty() ? null : categories.get(0)));
        }
        return map("month", month, "through", p.to(), "userId", user.getId(),
            "days", days, "mostSpentDay", topDays.isEmpty() ? null : topDays.get(0),
            "leastSpentDayWithExpense", least, "daysWithoutRecordedExpense", p.days() - expenseDays.size(),
            "largestTransaction", largest(p.expense()), "topThreeDays", topDays.stream().limit(3).toList(),
            "concentration", map("topOnePercent", percent(topDays.stream().limit(1).mapToLong(d -> (long) d.get("amount")).sum(), total),
                "topThreePercent", percent(topDays.stream().limit(3).mapToLong(d -> (long) d.get("amount")).sum(), total),
                "topFivePercent", percent(topDays.stream().mapToLong(d -> (long) d.get("amount")).sum(), total),
                "topFiveDays", topDays), "weeks", weeks);
    }

    public Map<String, Object> recurring(User user, YearMonth month) {
        Period p = period(user, month, null);
        List<Transaction> fixed = p.expense().stream()
            .filter(t -> t.getSource() == LedgerTypes.Source.RECURRING).toList();
        long fixedSpent = sum(fixed), total = sum(p.expense());
        return map("month", month, "through", p.to(), "userId", user.getId(),
            "recurringExpense", fixedSpent, "ordinaryExpense", total - fixedSpent,
            "recurringCount", fixed.size(), "recurringSharePercent", percent(fixedSpent, total),
            "largestRecurringTransaction", largest(fixed), "recurringCategories", categoryRows(fixed, p.days()));
    }

    private Map<String, Object> metricChanges(Period before, Period now) {
        long beforeSpent = sum(before.expense()), nowSpent = sum(now.expense());
        long beforeFixed = sum(before.expense().stream().filter(t -> t.getSource() == LedgerTypes.Source.RECURRING).toList());
        long nowFixed = sum(now.expense().stream().filter(t -> t.getSource() == LedgerTypes.Source.RECURRING).toList());
        Map<String, Object> categoryChanges = new LinkedHashMap<>();
        var prior = categories(before.expense());
        var current = categories(now.expense());
        for (LedgerTypes.Category c : LedgerTypes.Category.values())
            if (c.type == LedgerTypes.Type.EXPENSE)
                categoryChanges.put(c.name(), change(prior.getOrDefault(c, 0L), current.getOrDefault(c, 0L)));
        return map("previousFrom", before.from(), "previousThrough", before.to(),
            "currentFrom", now.from(), "currentThrough", now.to(),
            "totalExpense", change(beforeSpent, nowSpent),
            "dailyAverage", change(round((double) beforeSpent / before.days()), round((double) nowSpent / now.days())),
            "expenseCount", change(before.expense().size(), now.expense().size()),
            "averageTransaction", change(before.expense().isEmpty() ? 0 : round((double) beforeSpent / before.expense().size()),
                now.expense().isEmpty() ? 0 : round((double) nowSpent / now.expense().size())),
            "recurringSharePercent", change(percent(beforeFixed, beforeSpent), percent(nowFixed, nowSpent)),
            "daysWithoutRecordedExpense", change(before.days() - daily(before.expense()).size(), now.days() - daily(now.expense()).size()),
            "categories", categoryChanges);
    }

    public Map<String, Object> comparison(User user, YearMonth month) {
        Period now = period(user, month, null);
        YearMonth previousMonth = month.minusMonths(1);
        Period previous = period(user, previousMonth, previousMonth.atEndOfMonth());
        Map<String, Object> aligned = null;
        if (month.equals(YearMonth.from(LocalDate.now(clock)))) {
            int alignedDays = Math.min(now.days(), previousMonth.lengthOfMonth());
            aligned = metricChanges(period(user, previousMonth, previousMonth.atDay(alignedDays)),
                period(user, month, month.atDay(alignedDays)));
        }
        return map("month", month, "userId", user.getId(), "fullPreviousMonthComparison",
            metricChanges(previous, now), "sameElapsedDaysComparison", aligned,
            "currentMonthPartial", month.equals(YearMonth.from(LocalDate.now(clock))));
    }
}
