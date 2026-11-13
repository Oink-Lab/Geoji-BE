package com.geoji.app.ledger.entity;

import com.geoji.app.ledger.domain.LedgerTypes;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.*;
import java.util.UUID;

@Entity @Table(name="recurring_status_events", indexes=@Index(name="ix_recurring_status_events_recurring",columnList="recurring_expense_id,effective_date"))
@Getter @Setter
public class RecurringStatusEvent {
    @Id private UUID id=UUID.randomUUID();
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="recurring_expense_id",nullable=false) private RecurringExpense recurringExpense;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=12) private LedgerTypes.Status status;
    @Column(name="effective_date",nullable=false) private LocalDate effectiveDate;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @PrePersist void create() { createdAt=Instant.now(); }
}
