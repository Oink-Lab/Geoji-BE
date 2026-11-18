package com.geoji.app.challenge.dto;

import com.geoji.app.challenge.entity.Challenge;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.ledger.entity.Transaction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/** Immutable facts recorded in the transaction that confirms a challenge failure. */
public record FailureAnalysisSnapshot(
    int version, Instant capturedAt, LocalDate through, long totalBudget, long totalSpent,
    List<CategoryAmount> categories, List<ExpenseTransaction> topTransactions,
    SpendingDay maxSpendingDay, List<SpendingDay> topThreeDays,
    double topOneDaySharePercent, double topThreeDaysSharePercent,
    long recurringExpense, double recurringSharePercent
) {
    public record CategoryAmount(String code, String name, long amount, double sharePercent) {}
    public record ExpenseTransaction(UUID id, LocalDate date, long amount, String category, String source) {}
    public record SpendingDay(LocalDate date, long amount) {}

    public static FailureAnalysisSnapshot capture(Challenge challenge, List<Transaction> expenses,
                                                  LocalDate through, Instant capturedAt) {
        List<Transaction> included = expenses.stream()
            .filter(t -> !t.getTransactionDate().isAfter(through)).toList();
        long total = sum(included);
        var byCategory = included.stream().collect(Collectors.groupingBy(Transaction::getCategoryCode,
            () -> new EnumMap<>(LedgerTypes.Category.class), Collectors.summingLong(Transaction::getAmount)));
        List<CategoryAmount> categories = byCategory.entrySet().stream()
            .sorted(Map.Entry.<LedgerTypes.Category, Long>comparingByValue().reversed()
                .thenComparing(e -> e.getKey().name()))
            .map(e -> new CategoryAmount(e.getKey().name(), e.getKey().label,
                e.getValue(), percent(e.getValue(), total))).toList();
        List<ExpenseTransaction> topTransactions = included.stream()
            .sorted(Comparator.comparingLong(Transaction::getAmount).reversed()
                .thenComparing(Transaction::getTransactionDate).thenComparing(Transaction::getId))
            .limit(3).map(t -> new ExpenseTransaction(t.getId(), t.getTransactionDate(),
                t.getAmount(), t.getCategoryCode().name(), t.getSource().name())).toList();
        var byDay = included.stream().collect(Collectors.groupingBy(Transaction::getTransactionDate,
            TreeMap::new, Collectors.summingLong(Transaction::getAmount)));
        List<SpendingDay> topDays = byDay.entrySet().stream()
            .sorted(Map.Entry.<LocalDate, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .limit(3).map(e -> new SpendingDay(e.getKey(), e.getValue())).toList();
        long recurring = sum(included.stream()
            .filter(t -> t.getSource() == LedgerTypes.Source.RECURRING).toList());
        return new FailureAnalysisSnapshot(1, capturedAt, through, challenge.getTotalBudget(), total,
            categories, topTransactions, topDays.isEmpty() ? null : topDays.get(0), topDays,
            percent(topDays.stream().limit(1).mapToLong(SpendingDay::amount).sum(), total),
            percent(topDays.stream().mapToLong(SpendingDay::amount).sum(), total),
            recurring, percent(recurring, total));
    }

    private static long sum(List<Transaction> items) {
        return items.stream().mapToLong(Transaction::getAmount).reduce(0L, Math::addExact);
    }

    private static double percent(long part, long total) {
        return total == 0 ? 0 : Math.round(10_000.0 * part / total) / 100.0;
    }
}
