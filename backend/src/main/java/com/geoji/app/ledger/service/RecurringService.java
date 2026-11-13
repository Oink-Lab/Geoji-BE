package com.geoji.app.ledger.service;
import com.geoji.app.ledger.scheduler.RecurringEngine;

import com.geoji.app.ledger.repository.RecurringExpenseRepository;
import com.geoji.app.ledger.repository.RecurringStatusEventRepository;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.entity.RecurringExpense;
import com.geoji.app.ledger.entity.RecurringStatusEvent;
import com.geoji.app.ledger.domain.LedgerTypes;

import com.geoji.app.global.ApiException;
import com.geoji.app.user.entity.User;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class RecurringService {
    private final RecurringExpenseRepository recurring;
    private final RecurringEngine engine;
    private final Clock clock;
    private final RecurringStatusEventRepository events;
    RecurringService(RecurringExpenseRepository recurring,RecurringEngine engine,Clock clock,RecurringStatusEventRepository events) {
        this.recurring=recurring; this.engine=engine; this.clock=clock; this.events=events;
    }
    public record Create(@NotBlank @Size(max=100) String name,@NotNull LedgerTypes.Category categoryCode,
        @NotNull LedgerTypes.Method transactionMethod,@NotNull @Positive Long amount,@NotNull @Min(1) @Max(31) Integer paymentDay) {}
    public record Update(@Size(min=1,max=100) String name,LedgerTypes.Category categoryCode,
        LedgerTypes.Method transactionMethod,@Positive Long amount,@Min(1) @Max(31) Integer paymentDay) {}
    public record View(UUID id,String name,LedgerTypes.Category categoryCode,LedgerTypes.Method transactionMethod,
        long amount,int paymentDay,LedgerTypes.Status status,LocalDate startDate,Instant createdAt,Instant updatedAt,Instant cancelledAt) {
        static View of(RecurringExpense r) { return new View(r.getId(),r.getName(),r.getCategoryCode(),r.getTransactionMethod(),
            r.getAmount(),r.getPaymentDay(),r.getStatus(),r.getStartDate(),r.getCreatedAt(),r.getUpdatedAt(),r.getCancelledAt()); }
    }
    public record ScheduledView(LocalDate date,UUID recurringExpenseId,String name,LedgerTypes.Category categoryCode,
        long amount,LedgerTypes.Status status) {}
    private LocalDate today() { return LocalDate.now(clock); }
    private RecurringExpense owned(User user,UUID id) { return recurring.findByIdAndUserId(id,user.getId())
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"RECURRING_NOT_FOUND","고정지출을 찾을 수 없습니다.")); }
    @Transactional public View create(User user,Create input) {
        LedgerService.category(LedgerTypes.Type.EXPENSE,input.categoryCode());
        LedgerService.amount(input.amount());
        var r=new RecurringExpense(); r.setUser(user); r.setName(input.name().trim()); r.setCategoryCode(input.categoryCode());
        r.setTransactionMethod(input.transactionMethod()); r.setAmount(input.amount()); r.setPaymentDay(input.paymentDay());
        r.setStartDate(RecurringEngine.nextDate(today(),input.paymentDay())); r.setActiveSince(r.getStartDate());
        recurring.saveAndFlush(r); event(r,LedgerTypes.Status.ACTIVE,r.getStartDate()); return View.of(r);
    }
    @Transactional(readOnly=true) public View get(User user,UUID id) { return View.of(owned(user,id)); }
    @Transactional(readOnly=true) public List<View> list(User user) { return recurring.findByUserIdOrderByCreatedAtDesc(user.getId()).stream().map(View::of).toList(); }
    @Transactional public View update(User user,UUID id,Update input) {
        var r=owned(user,id); requireNotCancelled(r);
        if(input.categoryCode()!=null) LedgerService.category(LedgerTypes.Type.EXPENSE,input.categoryCode());
        if(input.amount()!=null) LedgerService.amount(input.amount());
        if(input.name()!=null && input.name().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_NAME","이름을 입력해야 합니다.");
        engine.catchUp(r,today());
        if(input.name()!=null)r.setName(input.name().trim());
        if(input.categoryCode()!=null) r.setCategoryCode(input.categoryCode());
        if(input.transactionMethod()!=null)r.setTransactionMethod(input.transactionMethod());
        if(input.amount()!=null)r.setAmount(input.amount());
        if(input.paymentDay()!=null) { r.setPaymentDay(input.paymentDay()); r.setActiveSince(RecurringEngine.nextDate(today(),r.getPaymentDay())); }
        return View.of(recurring.saveAndFlush(r));
    }
    @Transactional public View pause(User user,UUID id) {
        var r=owned(user,id);
        if(r.getStatus()!=LedgerTypes.Status.ACTIVE) throw conflict("ACTIVE 상태만 일시정지할 수 있습니다.");
        engine.catchUp(r,today()); r.setStatus(LedgerTypes.Status.PAUSED); event(r,LedgerTypes.Status.PAUSED,today()); return View.of(recurring.saveAndFlush(r));
    }
    @Transactional public View resume(User user,UUID id) {
        var r=owned(user,id);
        if(r.getStatus()!=LedgerTypes.Status.PAUSED) throw conflict("PAUSED 상태만 재개할 수 있습니다.");
        r.setStatus(LedgerTypes.Status.ACTIVE); r.setActiveSince(RecurringEngine.nextDate(today(),r.getPaymentDay()));
        event(r,LedgerTypes.Status.ACTIVE,r.getActiveSince());
        return View.of(recurring.saveAndFlush(r));
    }
    @Transactional public void cancel(User user,UUID id) {
        var r=owned(user,id); requireNotCancelled(r);
        if(r.getStatus()==LedgerTypes.Status.ACTIVE) engine.catchUp(r,today());
        r.setStatus(LedgerTypes.Status.CANCELLED); r.setCancelledAt(Instant.now(clock));
        event(r,LedgerTypes.Status.CANCELLED,today()); recurring.saveAndFlush(r);
    }
    @Transactional(readOnly=true) public List<ScheduledView> schedule(User user,YearMonth month) {
        return recurring.findByUserIdOrderByCreatedAtDesc(user.getId()).stream().map(r -> {
            var date=RecurringEngine.paymentDate(month,r.getPaymentDay());
            if(date.isBefore(r.getStartDate())) return null;
            LedgerTypes.Status status=LedgerTypes.Status.PAUSED;
            for(var e:events.findByRecurringExpenseIdOrderByEffectiveDateAscCreatedAtAsc(r.getId())) {
                if(e.getEffectiveDate().isAfter(date)) break;
                status=e.getStatus();
            }
            if(status==LedgerTypes.Status.ACTIVE && date.isBefore(r.getActiveSince()) && date.isAfter(today())) status=LedgerTypes.Status.PAUSED;
            return new ScheduledView(date,r.getId(),r.getName(),r.getCategoryCode(),r.getAmount(),status);
        }).filter(Objects::nonNull).sorted(Comparator.comparing(ScheduledView::date)).toList();
    }
    private void requireNotCancelled(RecurringExpense r) { if(r.getStatus()==LedgerTypes.Status.CANCELLED) throw conflict("해지된 고정지출은 변경할 수 없습니다."); }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"INVALID_RECURRING_STATE",message); }
    private void event(RecurringExpense r,LedgerTypes.Status status,LocalDate effective) {
        var e=new RecurringStatusEvent(); e.setRecurringExpense(r); e.setStatus(status); e.setEffectiveDate(effective); events.save(e);
    }
}
