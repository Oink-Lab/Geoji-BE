CREATE TABLE recurring_expenses (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(100) NOT NULL,
    category_code VARCHAR(32) NOT NULL,
    transaction_method VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    payment_day INTEGER NOT NULL CHECK (payment_day BETWEEN 1 AND 31),
    status VARCHAR(12) NOT NULL,
    start_date DATE NOT NULL,
    active_since DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    cancelled_at TIMESTAMPTZ
);
CREATE INDEX ix_recurring_status_start ON recurring_expenses(status,start_date);
CREATE INDEX ix_recurring_user ON recurring_expenses(user_id);

CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(10) NOT NULL,
    category_code VARCHAR(32) NOT NULL,
    transaction_method VARCHAR(20) NOT NULL,
    amount BIGINT NOT NULL CHECK (amount > 0),
    transaction_date DATE NOT NULL,
    merchant VARCHAR(255),
    memo VARCHAR(500),
    source VARCHAR(12) NOT NULL,
    recurring_expense_id UUID REFERENCES recurring_expenses(id),
    recurring_occurrence_date DATE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_transactions_user_date ON transactions(user_id,transaction_date);
CREATE INDEX ix_transactions_recurring ON transactions(recurring_expense_id);

CREATE TABLE recurring_occurrences (
    id UUID PRIMARY KEY,
    recurring_expense_id UUID NOT NULL REFERENCES recurring_expenses(id),
    occurrence_month DATE NOT NULL,
    occurrence_date DATE NOT NULL,
    transaction_id UUID UNIQUE REFERENCES transactions(id) ON DELETE SET NULL,
    status VARCHAR(12) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_occurrence_month UNIQUE (recurring_expense_id,occurrence_month)
);

CREATE TABLE recurring_status_events (
    id UUID PRIMARY KEY,
    recurring_expense_id UUID NOT NULL REFERENCES recurring_expenses(id),
    status VARCHAR(12) NOT NULL,
    effective_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_recurring_status_events_recurring ON recurring_status_events(recurring_expense_id,effective_date);
