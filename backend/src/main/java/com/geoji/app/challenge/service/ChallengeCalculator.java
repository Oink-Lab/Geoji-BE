package com.geoji.app.challenge.service;

import com.geoji.app.challenge.entity.Challenge;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.repository.TransactionRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class ChallengeCalculator {
    private final TransactionRepository transactions;

    public ChallengeCalculator(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    public List<Transaction> expenses(Challenge challenge) {
        List<Transaction> expenses = transactions.findChallengeExpenses(
            challenge.getUser().getId(),
            challenge.getStartDate(),
            challenge.getEndDate()
        );
        if (challenge.getStatus() != Challenge.Status.FAILED
            && challenge.getStatus() != Challenge.Status.ABANDONED) {
            return expenses;
        }
        if (challenge.getFinishedAt() == null) return expenses;
        LocalDate finalDate = LocalDate.ofInstant(
            challenge.getFinishedAt(), ZoneId.of("Asia/Seoul")
        );
        return expenses.stream().filter(transaction ->
            !transaction.getTransactionDate().isAfter(finalDate)
                && !transaction.getCreatedAt().isAfter(challenge.getFinishedAt())
        ).toList();
    }

    public long spentThrough(Challenge challenge, LocalDate date) {
        return expenses(challenge).stream()
            .filter(transaction -> !transaction.getTransactionDate().isAfter(date))
            .mapToLong(Transaction::getAmount)
            .reduce(0L, Math::addExact);
    }

    public long spentOn(Challenge challenge, LocalDate date) {
        return expenses(challenge).stream()
            .filter(transaction -> transaction.getTransactionDate().equals(date))
            .mapToLong(Transaction::getAmount)
            .reduce(0L, Math::addExact);
    }

    public long allowance(Challenge challenge, LocalDate date) {
        long elapsed = ChronoUnit.DAYS.between(challenge.getStartDate(), date);
        long spentBefore = elapsed == 0
            ? 0
            : spentThrough(challenge, date.minusDays(1));
        long remaining = Math.max(0, challenge.getTotalBudget() - spentBefore);
        long daysRemaining = challenge.getDurationDays() - elapsed;
        return remaining / daysRemaining
            + (remaining % daysRemaining == 0 ? 0 : 1);
    }
}
