package com.geoji.app.challenge.service;

import com.geoji.app.challenge.dto.ChallengeDtos;
import com.geoji.app.challenge.entity.Challenge;
import com.geoji.app.challenge.repository.ChallengeRepository;
import com.geoji.app.global.ApiException;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ChallengeService {
    private static final List<Challenge.Status> ACTIVE = List.of(
        Challenge.Status.SCHEDULED, Challenge.Status.IN_PROGRESS
    );
    private static final List<Challenge.Status> TERMINAL = List.of(
        Challenge.Status.SUCCESS, Challenge.Status.FAILED,
        Challenge.Status.ABANDONED
    );

    private final ChallengeRepository challenges;
    private final TransactionRepository transactions;
    private final UserRepository users;
    private final ChallengeCalculator calculator;
    private final Clock clock;

    public ChallengeService(
        ChallengeRepository challenges,
        TransactionRepository transactions,
        UserRepository users,
        ChallengeCalculator calculator,
        Clock clock
    ) {
        this.challenges = challenges;
        this.transactions = transactions;
        this.users = users;
        this.calculator = calculator;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_CHALLENGE_STATE", message);
    }

    private Challenge owned(User user, UUID id) {
        return challenges.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다."
            ));
    }

    @Transactional
    public ChallengeDtos.View create(User user, ChallengeDtos.Create input) {
        if (input.totalBudget() <= 0 || input.durationDays() < 1 || input.durationDays() > 30) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHALLENGE", "예산과 기간을 확인해 주세요.");
        }
        LocalDate start = input.startDate() == null ? today() : input.startDate();
        if (start.isBefore(today())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_START_DATE", "과거 날짜에는 시작할 수 없습니다.");
        }

        // Serializes creations for the same user; the partial unique index is a second guard.
        users.findLockedById(user.getId()).orElseThrow();
        if (challenges.findFirstByUserIdAndStatusIn(user.getId(), ACTIVE).isPresent()) {
            throw conflict("예약 또는 진행 중인 챌린지가 있습니다.");
        }
        LocalDate latestEnd = challenges.findByUserIdAndStatusIn(user.getId(), TERMINAL)
            .stream().map(this::effectiveEndDate).max(LocalDate::compareTo).orElse(null);
        if (latestEnd != null && !start.isAfter(latestEnd)) {
            throw conflict("이전 챌린지의 종료일 다음 날짜부터 시작할 수 있습니다.");
        }

        Challenge challenge = new Challenge();
        challenge.setUser(user);
        challenge.setTotalBudget(input.totalBudget());
        challenge.setDurationDays(input.durationDays());
        challenge.setStartDate(start);
        challenge.setEndDate(start.plusDays(input.durationDays() - 1L));
        challenge.setStatus(start.equals(today())
            ? Challenge.Status.IN_PROGRESS : Challenge.Status.SCHEDULED);
        String suppliedName = input.name() == null ? "" : input.name().trim();
        long sequence = challenges.countByUserId(user.getId()) + 1;
        challenge.setName(suppliedName.isEmpty() ? sequence + "번째 챌린지" : suppliedName);
        if (challenge.getStatus() == Challenge.Status.IN_PROGRESS) {
            challenge.setStartedAt(Instant.now(clock));
        }
        challenges.saveAndFlush(challenge);
        if (challenge.getStatus() == Challenge.Status.IN_PROGRESS) {
            failIfOverBudget(challenge);
        }
        return ChallengeDtos.View.of(challenge);
    }

    @Transactional(readOnly = true)
    public ChallengeDtos.View current(User user) {
        return challenges.findFirstByUserIdAndStatusIn(user.getId(), ACTIVE)
            .map(ChallengeDtos.View::of).orElse(null);
    }

    @Transactional(readOnly = true)
    public ChallengeDtos.View get(User user, UUID id) {
        return ChallengeDtos.View.of(owned(user, id));
    }

    @Transactional(readOnly = true)
    public List<ChallengeDtos.View> history(User user) {
        return challenges.findByUserIdAndStatusInOrderByFinishedAtDescStartDateDesc(
            user.getId(), TERMINAL
        ).stream().map(ChallengeDtos.View::of).toList();
    }

    @Transactional(readOnly = true)
    public ChallengeDtos.Retry retry(User user, UUID id) {
        Challenge challenge = owned(user, id);
        if (!TERMINAL.contains(challenge.getStatus())) {
            throw conflict("종료된 챌린지만 재도전할 수 있습니다.");
        }
        return new ChallengeDtos.Retry(
            challenge.getName(), challenge.getTotalBudget(), challenge.getDurationDays()
        );
    }

    @Transactional
    public ChallengeDtos.View cancel(User user, UUID id) {
        Challenge challenge = challenges.findLocked(id, user.getId())
            .orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다."
            ));
        if (challenge.getStatus() != Challenge.Status.SCHEDULED || !today().isBefore(challenge.getStartDate())) {
            throw conflict("시작 전 예약만 취소할 수 있습니다.");
        }
        challenges.delete(challenge);
        return ChallengeDtos.View.of(challenge);
    }

    @Transactional
    public ChallengeDtos.View abandon(User user, UUID id) {
        Challenge challenge = challenges.findLocked(id, user.getId())
            .orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "CHALLENGE_NOT_FOUND", "챌린지를 찾을 수 없습니다."
            ));
        if (challenge.getStatus() != Challenge.Status.IN_PROGRESS) {
            throw conflict("진행 중인 챌린지만 포기할 수 있습니다.");
        }
        challenge.setStatus(Challenge.Status.ABANDONED);
        challenge.setFinishedAt(Instant.now(clock));
        return ChallengeDtos.View.of(challenges.saveAndFlush(challenge));
    }

    @Transactional
    public void transactionChanged(UUID userId, LocalDate... dates) {
        challenges.findFirstByUserIdAndStatusIn(userId, ACTIVE).ifPresent(found -> {
            Challenge challenge = challenges.findLocked(found.getId(), userId).orElseThrow();
            if (challenge.getStatus() != Challenge.Status.IN_PROGRESS) return;
            boolean relevant = Arrays.stream(dates).anyMatch(date ->
                date != null && !date.isBefore(challenge.getStartDate())
                    && !date.isAfter(challenge.getEndDate())
            );
            if (relevant) failIfOverBudget(challenge);
        });
    }

    private void failIfOverBudget(Challenge challenge) {
        long spent = calculator.spentThrough(challenge, today());
        if (spent <= challenge.getTotalBudget()) return;
        challenge.setStatus(Challenge.Status.FAILED);
        challenge.setFailureAt(Instant.now(clock));
        challenge.setFinishedAt(challenge.getFailureAt());
        challenge.setFailureSpentSnapshot(spent);
        challenge.setFailureReason("TOTAL_BUDGET_EXCEEDED");
        challenges.saveAndFlush(challenge);
    }

    private LocalDate effectiveEndDate(Challenge challenge) {
        if (challenge.getStatus() == Challenge.Status.SUCCESS) {
            return challenge.getEndDate();
        }
        Instant finished = challenge.getFinishedAt();
        if (finished == null) return challenge.getEndDate();
        LocalDate actualEnd = LocalDate.ofInstant(finished, clock.getZone());
        return actualEnd.isAfter(challenge.getEndDate())
            ? challenge.getEndDate() : actualEnd;
    }

    @Transactional
    public void reconcile(LocalDate date) {
        for (Challenge challenge : challenges.findByStatusAndStartDateLessThanEqual(
            Challenge.Status.SCHEDULED, date
        )) {
            challenge = challenges.findLocked(
                challenge.getId(), challenge.getUser().getId()
            ).orElseThrow();
            if (challenge.getStatus() != Challenge.Status.SCHEDULED) continue;
            challenge.setStatus(Challenge.Status.IN_PROGRESS);
            challenge.setStartedAt(Instant.now(clock));
            challenges.saveAndFlush(challenge);
            failIfOverBudget(challenge);
        }
        for (Challenge challenge : challenges.findByStatusAndEndDateBefore(
            Challenge.Status.IN_PROGRESS, date
        )) {
            challenge = challenges.findLocked(
                challenge.getId(), challenge.getUser().getId()
            ).orElseThrow();
            if (challenge.getStatus() != Challenge.Status.IN_PROGRESS) continue;
            long spent = calculator.spentThrough(challenge, challenge.getEndDate());
            challenge.setStatus(spent > challenge.getTotalBudget()
                ? Challenge.Status.FAILED : Challenge.Status.SUCCESS);
            challenge.setFinishedAt(Instant.now(clock));
            if (challenge.getStatus() == Challenge.Status.FAILED) {
                challenge.setFailureAt(challenge.getFinishedAt());
                challenge.setFailureSpentSnapshot(spent);
                challenge.setFailureReason("TOTAL_BUDGET_EXCEEDED");
            }
            challenges.saveAndFlush(challenge);
        }
    }

    @Transactional(readOnly = true)
    public ChallengeDtos.Progress progress(User user, UUID id) {
        Challenge challenge = owned(user, id);
        LocalDate day = today().isAfter(challenge.getEndDate())
            ? challenge.getEndDate() : today();
        if (day.isBefore(challenge.getStartDate())) day = challenge.getStartDate();
        int currentDay = (int) ChronoUnit.DAYS.between(challenge.getStartDate(), day) + 1;
        long spent = calculator.spentThrough(challenge, day);
        long allowance = calculator.allowance(challenge, day);
        long todaySpent = calculator.spentOn(challenge, day);
        return new ChallengeDtos.Progress(
            ChallengeDtos.View.of(challenge), currentDay, spent,
            Math.max(0, challenge.getTotalBudget() - spent), allowance,
            todaySpent, allowance - todaySpent
        );
    }

    @Transactional(readOnly = true)
    public ChallengeDtos.Calendar calendar(User user, UUID id) {
        Challenge challenge = owned(user, id);
        Map<LocalDate, Long> expenseByDay = calculator.expenses(challenge).stream()
            .collect(Collectors.groupingBy(
                Transaction::getTransactionDate,
                Collectors.summingLong(Transaction::getAmount)
            ));
        Set<LocalDate> ordinaryDays = transactions.findByUserIdAndTransactionDateBetween(
            user.getId(), challenge.getStartDate(), challenge.getEndDate()
        ).stream().map(Transaction::getTransactionDate).collect(Collectors.toSet());
        List<ChallengeDtos.CalendarDay> days = new ArrayList<>();
        for (LocalDate date = challenge.getStartDate();
             !date.isAfter(challenge.getEndDate());
             date = date.plusDays(1)) {
            long allowance = calculator.allowance(challenge, date);
            long spent = expenseByDay.getOrDefault(date, 0L);
            days.add(new ChallengeDtos.CalendarDay(
                date, true, challenge.getStatus(), allowance, spent,
                allowance - spent, ordinaryDays.contains(date)
            ));
        }
        return new ChallengeDtos.Calendar(days);
    }

    @Transactional(readOnly = true)
    public ChallengeDtos.Result result(User user, UUID id) {
        Challenge challenge = owned(user, id);
        if (!TERMINAL.contains(challenge.getStatus())) {
            throw conflict("종료된 챌린지의 결과만 조회할 수 있습니다.");
        }
        List<Transaction> expenses = calculator.expenses(challenge);
        long spent = expenses.stream().mapToLong(Transaction::getAmount)
            .reduce(0L, Math::addExact);
        String topCategory = expenses.stream().collect(Collectors.groupingBy(
            transaction -> transaction.getCategoryCode().name(),
            Collectors.summingLong(Transaction::getAmount)
        )).entrySet().stream().max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(null);
        LocalDate topDate = expenses.stream().collect(Collectors.groupingBy(
            Transaction::getTransactionDate, Collectors.summingLong(Transaction::getAmount)
        )).entrySet().stream().max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(null);
        long under = 0;
        long over = 0;
        LocalDate finalDate = effectiveEndDate(challenge);
        for (LocalDate date = challenge.getStartDate();
             !date.isAfter(finalDate);
             date = date.plusDays(1)) {
            if (calculator.spentOn(challenge, date) <= calculator.allowance(challenge, date)) under++;
            else over++;
        }
        return new ChallengeDtos.Result(
            ChallengeDtos.View.of(challenge), spent,
            challenge.getTotalBudget() - spent, spent / challenge.getDurationDays(),
            topCategory, topDate, under, over
        );
    }
}
