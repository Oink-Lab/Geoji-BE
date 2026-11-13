package com.geoji.app.challenge;

import com.geoji.app.ledger.entity.Transaction;

import com.geoji.app.challenge.dto.ChallengeDtos;
import com.geoji.app.challenge.entity.Challenge;
import com.geoji.app.challenge.service.ChallengeService;
import com.geoji.app.global.ApiException;
import com.geoji.app.ledger.service.LedgerService;
import com.geoji.app.ledger.domain.LedgerTypes;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ChallengeIntegrationTest {
    @Autowired ChallengeService challenges;
    @Autowired LedgerService ledger;
    @Autowired UserRepository users;
    @Autowired MutableClock clock;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock challengeTestClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private final ZoneId zone = ZoneId.of("Asia/Seoul");
        private Instant instant;

        MutableClock() {
            set(LocalDate.of(2026, 11, 12));
        }

        void set(LocalDate date) {
            instant = date.atTime(12, 0).atZone(zone).toInstant();
        }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }

    @BeforeEach
    void resetClock() {
        clock.set(LocalDate.of(2026, 11, 12));
    }

    private User user() {
        User user = new User();
        user.setNickname(UUID.randomUUID().toString().substring(0, 8));
        return users.saveAndFlush(user);
    }

    private ChallengeDtos.Create create(long budget, int days, LocalDate start) {
        return new ChallengeDtos.Create(null, budget, days, start);
    }

    private LedgerService.Create expense(long amount, LocalDate date) {
        return new LedgerService.Create(
            LedgerTypes.Type.EXPENSE, LedgerTypes.Category.FOOD,
            LedgerTypes.Method.CASH, amount, date, null, null
        );
    }

    @Test
    void homeCanDistinguishNoChallengesFromNoActiveChallenge() {
        User user = user();
        LocalDate today = LocalDate.of(2026, 11, 12);
        assertNull(challenges.current(user));
        assertTrue(challenges.history(user).isEmpty());

        var created = challenges.create(user, new ChallengeDtos.Create(
            "첫 챌린지", 10_000, 1, today
        ));
        assertEquals(created.id(), challenges.current(user).id());

        clock.set(today.plusDays(1));
        challenges.reconcile(today.plusDays(1));
        assertNull(challenges.current(user));
        assertEquals(created.id(), challenges.history(user).get(0).id());
        assertEquals(Challenge.Status.SUCCESS, challenges.history(user).get(0).status());
    }

    @Test
    void historyReturnsMostRecentlyFinishedChallengeFirst() {
        User user = user();
        LocalDate firstDay = LocalDate.of(2026, 11, 12);
        var succeeded = challenges.create(user, new ChallengeDtos.Create(
            "첫 성공", 10_000, 1, firstDay
        ));
        clock.set(firstDay.plusDays(1));
        challenges.reconcile(firstDay.plusDays(1));

        var failed = challenges.create(user, new ChallengeDtos.Create(
            "다시 도전", 10_000, 1, firstDay.plusDays(1)
        ));
        ledger.create(user, expense(10_001, firstDay.plusDays(1)));

        var history = challenges.history(user);
        assertEquals(2, history.size());
        assertEquals(failed.id(), history.get(0).id());
        assertEquals("다시 도전", history.get(0).name());
        assertEquals(Challenge.Status.FAILED, history.get(0).status());
        assertEquals(succeeded.id(), history.get(1).id());
        assertEquals(Challenge.Status.SUCCESS, history.get(1).status());
        assertNull(challenges.current(user));
        assertEquals(failed.id(), challenges.result(user, failed.id()).challenge().id());
        assertEquals("다시 도전", challenges.retry(user, failed.id()).name());
    }

    @Test
    void countsOnlyOwnedExpensesByTransactionDate() {
        User owner = user();
        User stranger = user();
        LocalDate today = LocalDate.of(2026, 11, 12);
        ledger.create(owner, expense(4_000, today.minusDays(1)));
        ledger.create(stranger, expense(80_000, today));
        var challenge = challenges.create(owner, create(10_000, 1, today));
        var income = ledger.create(owner, new LedgerService.Create(
            LedgerTypes.Type.INCOME, LedgerTypes.Category.SALARY,
            LedgerTypes.Method.CASH, 100_000L, today, null, null
        ));
        assertEquals(0, challenges.progress(owner, challenge.id()).cumulativeSpent());
        assertThrows(ApiException.class, () -> challenges.get(stranger, challenge.id()));

        var expense = ledger.create(owner, expense(10_000, today));
        assertEquals(Challenge.Status.IN_PROGRESS, challenges.get(owner, challenge.id()).status());
        ledger.update(owner, expense.id(), new LedgerService.Update(
            null, null, null, null, today.minusDays(1), null, null
        ));
        assertEquals(0, challenges.progress(owner, challenge.id()).cumulativeSpent());
        ledger.delete(owner, income.id());
        assertEquals(Challenge.Status.IN_PROGRESS, challenges.get(owner, challenge.id()).status());
    }

    @Test
    void usesOrdinaryTransactionsAndKeepsFailureSnapshot() {
        User user = user();
        LocalDate today = LocalDate.of(2026, 11, 12);
        var created = challenges.create(user, create(70_000, 7, today));
        assertEquals(Challenge.Status.IN_PROGRESS, created.status());
        assertEquals("1번째 챌린지", created.name());
        assertEquals(10_000, challenges.progress(user, created.id()).todayAllowance());

        var transaction = ledger.create(user, expense(5_000, today));
        assertEquals(5_000, challenges.progress(user, created.id()).todaySpent());
        clock.set(today.plusDays(1));
        assertEquals(10_834, challenges.progress(user, created.id()).todayAllowance());

        ledger.update(user, transaction.id(), new LedgerService.Update(
            null, null, null, 70_001L, null, null, null
        ));
        var failed = challenges.get(user, created.id());
        assertEquals(Challenge.Status.FAILED, failed.status());
        assertEquals(70_001, failed.failureSpentSnapshot());

        ledger.delete(user, transaction.id());
        assertEquals(Challenge.Status.FAILED, challenges.get(user, created.id()).status());
        assertEquals(0, challenges.result(user, created.id()).actualSpent());
        assertEquals(70_001, challenges.get(user, created.id()).failureSpentSnapshot());
        assertEquals(7, challenges.retry(user, created.id()).durationDays());
    }

    @Test
    void reservationStartsAndFinishesAfterEndDate() {
        User user = user();
        LocalDate start = LocalDate.of(2026, 11, 14);
        var created = challenges.create(user, create(10_000, 1, start));
        assertEquals(Challenge.Status.SCHEDULED, created.status());
        assertThrows(ApiException.class, () -> challenges.create(
            user, create(10_000, 1, start)
        ));

        clock.set(start);
        challenges.reconcile(start);
        assertEquals(Challenge.Status.IN_PROGRESS, challenges.get(user, created.id()).status());
        clock.set(start.plusDays(1));
        challenges.reconcile(start.plusDays(1));
        challenges.reconcile(start.plusDays(1));
        assertEquals(Challenge.Status.SUCCESS, challenges.get(user, created.id()).status());
    }

    @Test
    void cancelledReservationAndAbandonedChallengeAllowNewStarts() {
        User user = user();
        LocalDate today = LocalDate.of(2026, 11, 12);
        var reserved = challenges.create(user, create(10_000, 3, today.plusDays(2)));
        challenges.cancel(user, reserved.id());
        var active = challenges.create(user, create(10_000, 3, today));
        challenges.abandon(user, active.id());
        assertThrows(ApiException.class, () -> challenges.create(
            user, create(10_000, 1, today)
        ));
        assertEquals(Challenge.Status.SCHEDULED, challenges.create(
            user, create(10_000, 1, today.plusDays(1))
        ).status());
    }
}
