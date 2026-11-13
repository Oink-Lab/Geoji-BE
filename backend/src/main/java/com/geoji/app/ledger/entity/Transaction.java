package com.geoji.app.ledger.entity;

import com.geoji.app.ledger.domain.LedgerTypes;

import com.geoji.app.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.*;
import java.util.UUID;

@Entity @Table(name = "transactions", indexes = @Index(name="ix_transactions_user_date", columnList="user_id,transaction_date"))
@Getter @Setter
public class Transaction {
    @Id private UUID id = UUID.randomUUID();
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="user_id", nullable=false) private User user;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=10) private LedgerTypes.Type type;
    @Enumerated(EnumType.STRING) @Column(name="category_code", nullable=false, length=32) private LedgerTypes.Category categoryCode;
    @Enumerated(EnumType.STRING) @Column(name="transaction_method", nullable=false, length=20) private LedgerTypes.Method transactionMethod;
    @Column(nullable=false) private long amount;
    @Column(name="transaction_date", nullable=false) private LocalDate transactionDate;
    @Column(length=255) private String merchant;
    @Column(length=500) private String memo;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=12) private LedgerTypes.Source source;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="recurring_expense_id") private RecurringExpense recurringExpense;
    @Column(name="recurring_occurrence_date") private LocalDate recurringOccurrenceDate;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @PrePersist void create() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void update() { updatedAt = Instant.now(); }
}
