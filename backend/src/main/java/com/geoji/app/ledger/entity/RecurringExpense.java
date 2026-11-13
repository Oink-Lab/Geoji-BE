package com.geoji.app.ledger.entity;

import com.geoji.app.ledger.domain.LedgerTypes;

import com.geoji.app.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.*;
import java.util.UUID;

@Entity @Table(name="recurring_expenses", indexes=@Index(name="ix_recurring_status_start", columnList="status,start_date"))
@Getter @Setter
public class RecurringExpense {
    @Id private UUID id = UUID.randomUUID();
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="user_id", nullable=false) private User user;
    @Column(nullable=false, length=100) private String name;
    @Enumerated(EnumType.STRING) @Column(name="category_code", nullable=false, length=32) private LedgerTypes.Category categoryCode;
    @Enumerated(EnumType.STRING) @Column(name="transaction_method", nullable=false, length=20) private LedgerTypes.Method transactionMethod;
    @Column(nullable=false) private long amount;
    @Column(name="payment_day", nullable=false) private int paymentDay;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=12) private LedgerTypes.Status status = LedgerTypes.Status.ACTIVE;
    @Column(name="start_date", nullable=false) private LocalDate startDate;
    @Column(name="active_since", nullable=false) private LocalDate activeSince;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @Column(name="cancelled_at") private Instant cancelledAt;
    @PrePersist void create() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate void update() { updatedAt = Instant.now(); }
}
