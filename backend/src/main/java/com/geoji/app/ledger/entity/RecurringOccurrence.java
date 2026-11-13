package com.geoji.app.ledger.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.*;
import java.util.UUID;

@Entity @Table(name="recurring_occurrences", uniqueConstraints=@UniqueConstraint(name="uq_occurrence_month",columnNames={"recurring_expense_id","occurrence_month"}))
@Getter @Setter
public class RecurringOccurrence {
    @Id private UUID id = UUID.randomUUID();
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="recurring_expense_id", nullable=false) private RecurringExpense recurringExpense;
    @Column(name="occurrence_month", nullable=false) private LocalDate occurrenceMonth;
    @Column(name="occurrence_date", nullable=false) private LocalDate occurrenceDate;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="transaction_id") private Transaction transaction;
    @Column(nullable=false, length=12) private String status = "GENERATED";
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @PrePersist void create() { createdAt = Instant.now(); }
}
