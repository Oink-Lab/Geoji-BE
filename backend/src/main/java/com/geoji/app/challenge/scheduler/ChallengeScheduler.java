package com.geoji.app.challenge.scheduler;

import com.geoji.app.challenge.service.ChallengeService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
public class ChallengeScheduler {
    private final ChallengeService service;
    private final Clock clock;

    public ChallengeScheduler(ChallengeService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void scheduled() {
        service.reconcile(LocalDate.now(clock));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startupRecovery() {
        service.reconcile(LocalDate.now(clock));
    }
}
