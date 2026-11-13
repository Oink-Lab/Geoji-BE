package com.geoji.app.ledger.scheduler;

import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.ledger.repository.RecurringExpenseRepository;
import com.geoji.app.ledger.repository.RecurringOccurrenceRepository;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.entity.RecurringExpense;
import com.geoji.app.ledger.entity.RecurringOccurrence;
import com.geoji.app.ledger.domain.LedgerTypes;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;

@Service
public class RecurringEngine {
    private final RecurringExpenseRepository recurring;
    private final OccurrenceWriter writer;
    private final Clock clock;
    RecurringEngine(RecurringExpenseRepository recurring, OccurrenceWriter writer, Clock clock) {
        this.recurring=recurring; this.writer=writer; this.clock=clock;
    }
    public static LocalDate paymentDate(YearMonth month,int day) { return month.atDay(Math.min(day,month.lengthOfMonth())); }
    public static LocalDate nextDate(LocalDate after,int day) {
        var month=YearMonth.from(after);
        var candidate=paymentDate(month,day);
        return candidate.isAfter(after)?candidate:paymentDate(month.plusMonths(1),day);
    }
    @Scheduled(cron="0 5 0 * * *",zone="Asia/Seoul") public void scheduled() { catchUp(LocalDate.now(clock)); }
    @EventListener(ApplicationReadyEvent.class) public void startupRecovery() { catchUp(LocalDate.now(clock)); }
    public void catchUp(LocalDate through) {
        for(var item:recurring.findByStatus(LedgerTypes.Status.ACTIVE)) catchUp(item,through);
    }
    public void catchUp(RecurringExpense item,LocalDate through) {
        LocalDate start=item.getActiveSince();
        if(start==null || start.isAfter(through)) return;
        for(var month=YearMonth.from(start); !month.isAfter(YearMonth.from(through)); month=month.plusMonths(1)) {
            LocalDate date=paymentDate(month,item.getPaymentDay());
            if(date.isBefore(start) || date.isAfter(through)) continue;
            try { writer.generate(item.getId(),date); }
            catch(DataIntegrityViolationException ignored) { /* Concurrent run won the unique month constraint. */ }
        }
    }
}

@Service
class OccurrenceWriter {
    private final RecurringExpenseRepository recurring;
    private final RecurringOccurrenceRepository occurrences;
    private final TransactionRepository transactions;
    private final com.geoji.app.challenge.service.ChallengeService challenges;
    OccurrenceWriter(RecurringExpenseRepository recurring,RecurringOccurrenceRepository occurrences,
                     TransactionRepository transactions,
                     com.geoji.app.challenge.service.ChallengeService challenges) {
        this.recurring=recurring; this.occurrences=occurrences; this.transactions=transactions;
        this.challenges=challenges;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW) public void generate(java.util.UUID id,LocalDate date) {
        var item=recurring.findLockedById(id).orElseThrow();
        if(item.getStatus()!=LedgerTypes.Status.ACTIVE || date.isBefore(item.getStartDate()) || date.isBefore(item.getActiveSince())) return;
        LocalDate month=date.withDayOfMonth(1);
        if(occurrences.existsByRecurringExpenseIdAndOccurrenceMonth(id,month)) return;
        var t=new Transaction(); t.setUser(item.getUser()); t.setType(LedgerTypes.Type.EXPENSE);
        t.setCategoryCode(item.getCategoryCode()); t.setTransactionMethod(item.getTransactionMethod()); t.setAmount(item.getAmount());
        t.setTransactionDate(date); t.setMerchant(item.getName()); t.setSource(LedgerTypes.Source.RECURRING);
        t.setRecurringExpense(item); t.setRecurringOccurrenceDate(date);
        transactions.save(t);
        var occurrence=new RecurringOccurrence(); occurrence.setRecurringExpense(item); occurrence.setOccurrenceMonth(month);
        occurrence.setOccurrenceDate(date); occurrence.setTransaction(t);
        occurrences.saveAndFlush(occurrence);
        challenges.transactionChanged(item.getUser().getId(), date);
    }
}
