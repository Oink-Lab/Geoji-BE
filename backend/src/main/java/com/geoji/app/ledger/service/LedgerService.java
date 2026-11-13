package com.geoji.app.ledger.service;

import com.geoji.app.ledger.repository.TransactionRepository;
import com.geoji.app.ledger.repository.RecurringOccurrenceRepository;
import com.geoji.app.ledger.entity.Transaction;
import com.geoji.app.ledger.domain.LedgerTypes;

import com.geoji.app.global.ApiException;
import com.geoji.app.challenge.service.ChallengeService;
import com.geoji.app.user.entity.User;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class LedgerService {
    private final TransactionRepository transactions;
    private final RecurringOccurrenceRepository occurrences;
    private final Clock clock;
    private final ChallengeService challenges;
    LedgerService(TransactionRepository transactions, RecurringOccurrenceRepository occurrences,
                  Clock clock, ChallengeService challenges) {
        this.transactions=transactions; this.occurrences=occurrences; this.clock=clock;
        this.challenges=challenges;
    }
    public record Create(@NotNull LedgerTypes.Type type, @NotNull LedgerTypes.Category categoryCode,
                         @NotNull LedgerTypes.Method transactionMethod, @NotNull @Positive Long amount,
                         @NotNull LocalDate transactionDate, @Size(max=255) String merchant, @Size(max=500) String memo) {}
    public record Update(LedgerTypes.Type type, LedgerTypes.Category categoryCode, LedgerTypes.Method transactionMethod,
                         @Positive Long amount, LocalDate transactionDate, @Size(max=255) String merchant,
                         @Size(max=500) String memo) {}
    public record View(UUID id, LedgerTypes.Type type, LedgerTypes.Category categoryCode,
                       LedgerTypes.Method transactionMethod, long amount, LocalDate transactionDate,
                       String merchant, String memo, LedgerTypes.Source source, UUID recurringExpenseId,
                       LocalDate recurringOccurrenceDate, Instant createdAt, Instant updatedAt) {
        static View of(Transaction t) { return new View(t.getId(),t.getType(),t.getCategoryCode(),t.getTransactionMethod(),
            t.getAmount(),t.getTransactionDate(),t.getMerchant(),t.getMemo(),t.getSource(),
            t.getRecurringExpense()==null?null:t.getRecurringExpense().getId(),t.getRecurringOccurrenceDate(),t.getCreatedAt(),t.getUpdatedAt()); }
    }
    public record Summary(long totalExpense, long totalIncome) {}
    public record Day(LocalDate date, long expense, long income) {}
    public record CalendarView(YearMonth month, List<Day> days) {}
    static void category(LedgerTypes.Type type, LedgerTypes.Category code) {
        if (type == null || code == null || code.type != type) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CATEGORY","거래 유형과 카테고리가 일치하지 않습니다.");
    }
    void date(LocalDate value) {
        if (value == null || value.isAfter(LocalDate.now(clock))) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DATE","미래 거래 날짜는 허용하지 않습니다.");
    }
    @Transactional public View create(User user, Create input) {
        category(input.type(),input.categoryCode()); date(input.transactionDate());
        amount(input.amount());
        var t = new Transaction(); t.setUser(user); t.setType(input.type()); t.setCategoryCode(input.categoryCode());
        t.setTransactionMethod(input.transactionMethod()); t.setAmount(input.amount()); t.setTransactionDate(input.transactionDate());
        t.setMerchant(input.merchant()); t.setMemo(input.memo()); t.setSource(LedgerTypes.Source.MANUAL);
        transactions.saveAndFlush(t);
        challenges.transactionChanged(user.getId(), t.getTransactionDate());
        return View.of(t);
    }
    @Transactional(readOnly=true) public View get(User user, UUID id) { return View.of(owned(user,id)); }
    Transaction owned(User user, UUID id) { return transactions.findByIdAndUserId(id,user.getId())
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"TRANSACTION_NOT_FOUND","거래를 찾을 수 없습니다.")); }
    @Transactional public View update(User user, UUID id, Update input) {
        var t=owned(user,id);
        LocalDate previousDate = t.getTransactionDate();
        var type=input.type()==null?t.getType():input.type();
        var code=input.categoryCode()==null?t.getCategoryCode():input.categoryCode();
        category(type,code);
        if(input.transactionDate()!=null) date(input.transactionDate());
        if(input.amount()!=null) amount(input.amount());
        t.setType(type); t.setCategoryCode(code);
        if(input.transactionMethod()!=null)t.setTransactionMethod(input.transactionMethod());
        if(input.amount()!=null)t.setAmount(input.amount());
        if(input.transactionDate()!=null)t.setTransactionDate(input.transactionDate());
        if(input.merchant()!=null)t.setMerchant(input.merchant());
        if(input.memo()!=null)t.setMemo(input.memo());
        transactions.saveAndFlush(t);
        challenges.transactionChanged(user.getId(), previousDate, t.getTransactionDate());
        return View.of(t);
    }
    @Transactional public void delete(User user, UUID id) {
        var t=owned(user,id);
        occurrences.findByTransactionId(id).ifPresent(o -> { o.setTransaction(null); o.setStatus("DELETED"); occurrences.saveAndFlush(o); });
        transactions.delete(t);
        transactions.flush();
        challenges.transactionChanged(user.getId(), t.getTransactionDate());
    }
    @Transactional(readOnly=true) public Page<View> list(User user, LocalDate date, YearMonth month, Pageable page) {
        if ((date==null)==(month==null)) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PERIOD","date 또는 month 중 하나만 지정해야 합니다.");
        LocalDate from=date!=null?date:month.atDay(1), to=date!=null?date:month.atEndOfMonth();
        return transactions.findByUserIdAndTransactionDateBetween(user.getId(),from,to,page).map(View::of);
    }
    @Transactional(readOnly=true) public Summary summary(User user, YearMonth month) {
        long expense=0,income=0;
        for(var t:monthTransactions(user,month)) { if(t.getType()==LedgerTypes.Type.EXPENSE) expense=Math.addExact(expense,t.getAmount());
            else income=Math.addExact(income,t.getAmount()); }
        return new Summary(expense,income);
    }
    @Transactional(readOnly=true) public CalendarView calendar(User user, YearMonth month) {
        var sums=new TreeMap<LocalDate,long[]>();
        for(var t:monthTransactions(user,month)) {
            var pair=sums.computeIfAbsent(t.getTransactionDate(),k -> new long[2]);
            int i=t.getType()==LedgerTypes.Type.EXPENSE?0:1; pair[i]=Math.addExact(pair[i],t.getAmount());
        }
        return new CalendarView(month,sums.entrySet().stream().map(e -> new Day(e.getKey(),e.getValue()[0],e.getValue()[1])).toList());
    }
    private List<Transaction> monthTransactions(User user,YearMonth month) { return transactions.findByUserIdAndTransactionDateBetween(user.getId(),month.atDay(1),month.atEndOfMonth()); }
    static void amount(Long value) { if(value==null || value<=0) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_AMOUNT","금액은 0보다 커야 합니다."); }
}
