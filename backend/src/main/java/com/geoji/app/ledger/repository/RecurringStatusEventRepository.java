package com.geoji.app.ledger.repository;

import com.geoji.app.ledger.entity.RecurringStatusEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RecurringStatusEventRepository extends JpaRepository<RecurringStatusEvent,UUID> {
    List<RecurringStatusEvent> findByRecurringExpenseIdOrderByEffectiveDateAscCreatedAtAsc(UUID recurringExpenseId);
}
