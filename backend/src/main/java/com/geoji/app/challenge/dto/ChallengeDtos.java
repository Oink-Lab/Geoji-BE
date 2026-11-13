package com.geoji.app.challenge.dto;

import com.geoji.app.challenge.entity.Challenge;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ChallengeDtos {
    private ChallengeDtos() {}

    public record Create(
        @Size(max = 100) String name,
        @Positive long totalBudget,
        @Min(1) @Max(30) int durationDays,
        LocalDate startDate
    ) {}

    public record View(
        UUID id, String name, Challenge.Status status, long totalBudget,
        int durationDays, LocalDate startDate, LocalDate endDate,
        Instant startedAt, Instant finishedAt, Instant failureAt,
        Long failureSpentSnapshot, String failureReason
    ) {
        public static View of(Challenge challenge) {
            return new View(
                challenge.getId(), challenge.getName(), challenge.getStatus(),
                challenge.getTotalBudget(), challenge.getDurationDays(),
                challenge.getStartDate(), challenge.getEndDate(),
                challenge.getStartedAt(), challenge.getFinishedAt(),
                challenge.getFailureAt(), challenge.getFailureSpentSnapshot(),
                challenge.getFailureReason()
            );
        }
    }

    public record Progress(
        View challenge, int currentDay, long cumulativeSpent,
        long remainingBudget, long todayAllowance, long todaySpent,
        long todayDifference
    ) {}

    public record CalendarDay(
        LocalDate date, boolean challengeDay, Challenge.Status status,
        Long allowance, long spent, Long difference,
        boolean hasOrdinaryTransactions
    ) {}

    public record Calendar(List<CalendarDay> days) {}

    public record Result(
        View challenge, long actualSpent, long remainingBudget,
        long averageDailySpent, String topCategory, LocalDate topSpendingDate,
        long daysUnderAllowance, long daysOverAllowance
    ) {}

    public record Retry(
        String name, long totalBudget, int durationDays
    ) {}
}
