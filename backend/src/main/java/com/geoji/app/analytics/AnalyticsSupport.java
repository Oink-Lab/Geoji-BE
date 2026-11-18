package com.geoji.app.analytics;

import com.geoji.app.global.ApiException;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.ledger.entity.Transaction;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

final class AnalyticsSupport {
    private AnalyticsSupport() {}

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }

    static long sum(Collection<Transaction> items) {
        return items.stream().mapToLong(Transaction::getAmount).reduce(0L, Math::addExact);
    }

    static double percent(long part, long total) {
        return total == 0 ? 0 : round(100.0 * part / total);
    }

    static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static Map<String, Object> change(Number before, Number now) {
        double previous = before.doubleValue(), current = now.doubleValue();
        boolean integral = (before instanceof Long || before instanceof Integer)
            && (now instanceof Long || now instanceof Integer);
        Number difference;
        if (integral) difference = Math.subtractExact(now.longValue(), before.longValue());
        else difference = round(current - previous);
        return map("previous", before, "current", now, "difference", difference,
            "changePercent", previous == 0 ? null : round(100 * (current - previous) / previous),
            "comparable", previous != 0);
    }

    static LocalDate end(YearMonth month, LocalDate today) {
        if (month.atDay(1).isAfter(today)) throw new ApiException(HttpStatus.BAD_REQUEST,
            "FUTURE_MONTH", "미래 월은 분석할 수 없습니다.");
        return month.equals(YearMonth.from(today)) ? today : month.atEndOfMonth();
    }

    static List<Transaction> expenses(List<Transaction> items) {
        return items.stream().filter(t -> t.getType() == LedgerTypes.Type.EXPENSE).toList();
    }

    static Map<LocalDate, Long> daily(List<Transaction> items) {
        return items.stream().collect(Collectors.groupingBy(Transaction::getTransactionDate,
            TreeMap::new, Collectors.summingLong(Transaction::getAmount)));
    }

    static Map<LedgerTypes.Category, Long> categories(List<Transaction> items) {
        return items.stream().collect(Collectors.groupingBy(Transaction::getCategoryCode,
            () -> new EnumMap<>(LedgerTypes.Category.class), Collectors.summingLong(Transaction::getAmount)));
    }

    static List<Map<String, Object>> categoryRows(List<Transaction> expenses, int days) {
        long total = sum(expenses);
        var sums = categories(expenses);
        var counts = expenses.stream().collect(Collectors.groupingBy(Transaction::getCategoryCode, Collectors.counting()));
        List<LedgerTypes.Category> sorted = sums.keySet().stream()
            .sorted(Comparator.<LedgerTypes.Category>comparingLong(sums::get).reversed().thenComparing(Enum::name)).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            var category = sorted.get(i);
            long amount = sums.get(category), count = counts.get(category);
            rows.add(map("code", category.name(), "name", category.label, "amount", amount,
                "count", count, "averageTransaction", round((double) amount / count),
                "sharePercent", percent(amount, total), "rank", i + 1,
                "dailyAverage", days == 0 ? 0 : round((double) amount / days)));
        }
        return rows;
    }

    static List<Map<String, Object>> topDays(Map<LocalDate, Long> daily, int limit) {
        return daily.entrySet().stream().filter(e -> e.getValue() > 0)
            .sorted(Map.Entry.<LocalDate, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .limit(limit).map(e -> map("date", e.getKey(), "amount", e.getValue())).toList();
    }

    static Map<String, Object> largest(List<Transaction> items) {
        return items.stream().max(Comparator.comparingLong(Transaction::getAmount)
            .thenComparing(Transaction::getTransactionDate).thenComparing(Transaction::getId))
            .map(t -> map("id", t.getId(), "date", t.getTransactionDate(), "amount", t.getAmount(),
                "category", t.getCategoryCode().name(), "source", t.getSource().name()))
            .orElse(null);
    }

    static List<Transaction> within(List<Transaction> items, LocalDate from, LocalDate to) {
        return items.stream().filter(t -> !t.getTransactionDate().isBefore(from)
            && !t.getTransactionDate().isAfter(to)).toList();
    }

    static Map<String, Object> status(String state, boolean sufficient) {
        return map("state", state, "sufficientData", sufficient);
    }
}
