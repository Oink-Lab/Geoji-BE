package com.geoji.app.ledger;

import com.geoji.app.ledger.service.LedgerService;
import com.geoji.app.ledger.service.RecurringService;
import com.geoji.app.ledger.scheduler.RecurringEngine;
import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.ledger.repository.RecurringExpenseRepository;
import com.geoji.app.ledger.repository.RecurringOccurrenceRepository;
import com.geoji.app.ledger.domain.LedgerTypes;

import com.geoji.app.global.ApiException;
import com.geoji.app.user.entity.User;
import com.geoji.app.user.repository.UserRepository;
import com.geoji.app.user.controller.UserController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class LedgerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired LedgerService ledger;
    @Autowired RecurringService recurring;
    @Autowired RecurringExpenseRepository recurringRepository;
    @Autowired RecurringEngine engine;
    @Autowired RecurringOccurrenceRepository occurrences;
    @Autowired TransactionRepository transactions;
    @Autowired MutableClock clock;
    @BeforeEach void resetClock() { clock.set(LocalDate.of(2026,11,11)); }
    @TestConfiguration static class FixedTime {
        @Bean @Primary MutableClock clock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        private final ZoneId zone=ZoneId.of("Asia/Seoul");
        private volatile Instant now;
        MutableClock() { set(LocalDate.of(2026,11,11)); }
        void set(LocalDate date) { now=date.atTime(12,0).atZone(zone).toInstant(); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
        @Override public Instant instant() { return now; }
    }
    private User user() { var u=new User(); u.setNickname(UUID.randomUUID().toString().substring(0,8)); return users.saveAndFlush(u); }
    private LedgerService.Create item(LedgerTypes.Type type,LedgerTypes.Category category,long amount,LocalDate date) {
        return new LedgerService.Create(type,category,LedgerTypes.Method.CASH,amount,date,"store","memo");
    }
    @Test void crudSummaryCalendarAndOwnership() {
        var a=user(); var b=user(); var date=LocalDate.of(2026,11,10);
        var expense=ledger.create(a,item(LedgerTypes.Type.EXPENSE,LedgerTypes.Category.FOOD,12000,date));
        ledger.create(a,item(LedgerTypes.Type.INCOME,LedgerTypes.Category.SALARY,50000,date));
        assertEquals(2,ledger.list(a,date,null,PageRequest.of(0,10)).getTotalElements());
        assertEquals(2,ledger.list(a,null,YearMonth.of(2026,11),PageRequest.of(0,10)).getTotalElements());
        assertEquals(12000,ledger.summary(a,YearMonth.of(2026,11)).totalExpense());
        assertEquals(50000,ledger.summary(a,YearMonth.of(2026,11)).totalIncome());
        assertEquals(12000,ledger.calendar(a,YearMonth.of(2026,11)).days().get(0).expense());
        assertThrows(ApiException.class,() -> ledger.get(b,expense.id()));
        assertThrows(ApiException.class,() -> ledger.update(b,expense.id(),new LedgerService.Update(null,null,null,1L,null,null,null)));
        assertThrows(ApiException.class,() -> ledger.delete(b,expense.id()));
        ledger.update(a,expense.id(),new LedgerService.Update(null,null,null,19000L,null,null,null));
        assertEquals(19000,ledger.get(a,expense.id()).amount());
        ledger.delete(a,expense.id()); assertThrows(ApiException.class,() -> ledger.get(a,expense.id()));
    }
    @Test void rejectsInvalidTransactions() {
        var u=user(); var date=LocalDate.of(2026,11,10);
        assertThrows(ApiException.class,() -> ledger.create(u,item(LedgerTypes.Type.EXPENSE,LedgerTypes.Category.SALARY,1,date)));
        assertThrows(ApiException.class,() -> ledger.create(u,item(LedgerTypes.Type.EXPENSE,LedgerTypes.Category.FOOD,1,date.plusDays(3))));
        assertThrows(ApiException.class,() -> ledger.create(u,item(LedgerTypes.Type.EXPENSE,LedgerTypes.Category.FOOD,0,date)));
    }
    @Test void recurringMonthEndDeletionAndLifecycle() {
        var u=user(); var r=recurring.create(u,new RecurringService.Create("rent",LedgerTypes.Category.HOUSING,LedgerTypes.Method.BANK_TRANSFER,10000L,31));
        var entity=recurringRepository.findById(r.id()).orElseThrow();
        assertEquals(LocalDate.of(2026,11,30),r.startDate());
        engine.catchUp(LocalDate.of(2026,11,30));
        engine.catchUp(LocalDate.of(2026,11,30));
        assertEquals(1,occurrences.findByRecurringExpenseIdAndOccurrenceMonth(r.id(),LocalDate.of(2026,11,1)).size());
        var generated=ledger.list(u,LocalDate.of(2026,11,30),null,PageRequest.of(0,10)).getContent().get(0);
        ledger.update(u,generated.id(),new LedgerService.Update(null,null,null,12000L,null,null,null));
        assertEquals(10000,recurring.get(u,r.id()).amount());
        ledger.delete(u,generated.id());
        engine.catchUp(LocalDate.of(2026,11,30));
        assertEquals(0,ledger.list(u,LocalDate.of(2026,11,30),null,PageRequest.of(0,10)).getTotalElements());
        assertEquals("DELETED",occurrences.findByRecurringExpenseIdAndOccurrenceMonth(r.id(),LocalDate.of(2026,11,1)).get(0).getStatus());
        engine.catchUp(entity,LocalDate.of(2027,2,28));
        assertEquals(1,ledger.list(u,LocalDate.of(2027,2,28),null,PageRequest.of(0,10)).getTotalElements());
        recurring.pause(u,r.id());
        assertEquals(LedgerTypes.Status.PAUSED,recurring.get(u,r.id()).status());
        recurring.resume(u,r.id());
        recurring.cancel(u,r.id());
        assertEquals(LedgerTypes.Status.CANCELLED,recurring.get(u,r.id()).status());
        assertEquals(1,ledger.list(u,LocalDate.of(2027,2,28),null,PageRequest.of(0,10)).getTotalElements());
    }
    @Test void authenticatedApiAndValidation() throws Exception {
        var u=user(); var auth=authentication(new UsernamePasswordAuthenticationToken(u,null,List.of()));
        mvc.perform(get("/api/v1/transactions?month=2026-11")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/transactions").with(auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"EXPENSE\",\"categoryCode\":\"FOOD\",\"transactionMethod\":\"CASH\",\"amount\":0,\"transactionDate\":\"2026-11-10\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/transactions").with(auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"EXPENSE\",\"categoryCode\":\"FOOD\",\"transactionMethod\":\"CASH\",\"amount\":1200,\"transactionDate\":\"2026-11-10\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.data.source").value("MANUAL"));
        mvc.perform(get("/api/v1/transactions").param("month","2026-11").with(auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/v1/transactions/summary").param("month","2026-11").with(auth))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalExpense").value(1200));
    }
    @Test void calendarClampsShortMonthsAndPausedRulesStayIsolated() {
        assertEquals(LocalDate.of(2026,4,30),RecurringEngine.paymentDate(YearMonth.of(2026,4),31));
        assertEquals(LocalDate.of(2027,2,28),RecurringEngine.paymentDate(YearMonth.of(2027,2),31));
        assertEquals(LocalDate.of(2028,2,29),RecurringEngine.paymentDate(YearMonth.of(2028,2),31));
        var a=user(); var b=user();
        var r=recurring.create(a,new RecurringService.Create("fee",LedgerTypes.Category.SUBSCRIPTION,LedgerTypes.Method.CREDIT_CARD,17000L,20));
        assertThrows(ApiException.class,() -> recurring.get(b,r.id()));
        assertThrows(ApiException.class,() -> recurring.pause(b,r.id()));
        recurring.pause(a,r.id());
        engine.catchUp(LocalDate.of(2026,11,20));
        assertEquals(0,ledger.list(a,LocalDate.of(2026,11,20),null,PageRequest.of(0,10)).getTotalElements());
        recurring.resume(a,r.id());
        engine.catchUp(LocalDate.of(2026,11,20));
        assertEquals(1,ledger.list(a,LocalDate.of(2026,11,20),null,PageRequest.of(0,10)).getTotalElements());
        assertEquals(0,ledger.list(b,LocalDate.of(2026,11,20),null,PageRequest.of(0,10)).getTotalElements());
        recurring.cancel(a,r.id());
        engine.catchUp(LocalDate.of(2026,12,20));
        assertEquals(0,ledger.list(a,LocalDate.of(2026,12,20),null,PageRequest.of(0,10)).getTotalElements());
        assertEquals(1,ledger.list(a,LocalDate.of(2026,11,20),null,PageRequest.of(0,10)).getTotalElements());
    }
    @Test void deletedNovemberOccurrenceStaysDeletedAndDecemberGenerates() {
        clock.set(LocalDate.of(2026,11,1));
        var u=user(); var r=recurring.create(u,new RecurringService.Create("Netflix",LedgerTypes.Category.SUBSCRIPTION,LedgerTypes.Method.CREDIT_CARD,17000L,10));
        clock.set(LocalDate.of(2026,11,10)); engine.catchUp(LocalDate.now(clock));
        var november=ledger.list(u,LocalDate.of(2026,11,10),null,PageRequest.of(0,10)).getContent().get(0);
        ledger.delete(u,november.id());
        engine.catchUp(LocalDate.now(clock));
        assertEquals(0,ledger.list(u,LocalDate.of(2026,11,10),null,PageRequest.of(0,10)).getTotalElements());
        assertEquals("DELETED",occurrences.findByRecurringExpenseIdAndOccurrenceMonth(r.id(),LocalDate.of(2026,11,1)).get(0).getStatus());
        clock.set(LocalDate.of(2026,12,10)); engine.catchUp(LocalDate.now(clock));
        assertEquals(17000,ledger.list(u,LocalDate.of(2026,12,10),null,PageRequest.of(0,10)).getContent().get(0).amount());
    }
    @Test void pauseBeforeNovemberPaymentAndResumeAfterSkipsNovember() {
        clock.set(LocalDate.of(2026,11,1));
        var u=user(); var r=recurring.create(u,new RecurringService.Create("Netflix",LedgerTypes.Category.SUBSCRIPTION,LedgerTypes.Method.CREDIT_CARD,17000L,10));
        clock.set(LocalDate.of(2026,11,5)); recurring.pause(u,r.id());
        clock.set(LocalDate.of(2026,11,10)); engine.catchUp(LocalDate.now(clock));
        assertEquals(0,ledger.list(u,LocalDate.of(2026,11,10),null,PageRequest.of(0,10)).getTotalElements());
        clock.set(LocalDate.of(2026,11,15)); recurring.resume(u,r.id());
        engine.catchUp(LocalDate.now(clock));
        assertEquals(0,ledger.list(u,LocalDate.of(2026,11,10),null,PageRequest.of(0,10)).getTotalElements());
        clock.set(LocalDate.of(2026,12,10)); engine.catchUp(LocalDate.now(clock));
        assertEquals(17000,ledger.list(u,LocalDate.of(2026,12,10),null,PageRequest.of(0,10)).getContent().get(0).amount());
    }
    @Test void editingOneOccurrenceLeavesBaseAndNextMonthUntouched() {
        clock.set(LocalDate.of(2026,11,1));
        var u=user(); var r=recurring.create(u,new RecurringService.Create("Netflix",LedgerTypes.Category.SUBSCRIPTION,LedgerTypes.Method.CREDIT_CARD,17000L,10));
        clock.set(LocalDate.of(2026,11,10)); engine.catchUp(LocalDate.now(clock));
        var november=ledger.list(u,LocalDate.of(2026,11,10),null,PageRequest.of(0,10)).getContent().get(0);
        ledger.update(u,november.id(),new LedgerService.Update(null,null,null,19000L,null,null,null));
        assertEquals(19000,ledger.get(u,november.id()).amount());
        assertEquals(17000,recurring.get(u,r.id()).amount());
        clock.set(LocalDate.of(2026,12,10)); engine.catchUp(LocalDate.now(clock));
        assertEquals(17000,ledger.list(u,LocalDate.of(2026,12,10),null,PageRequest.of(0,10)).getContent().get(0).amount());
    }
}
