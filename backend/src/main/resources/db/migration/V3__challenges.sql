CREATE TABLE challenges (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    name VARCHAR(100) NOT NULL,
    total_budget BIGINT NOT NULL CHECK (total_budget > 0),
    duration_days INTEGER NOT NULL CHECK (duration_days BETWEEN 1 AND 30),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    failure_at TIMESTAMPTZ,
    failure_spent_snapshot BIGINT,
    failure_reason VARCHAR(40),
    CHECK (end_date = start_date + (duration_days - 1)),
    CHECK (status IN ('SCHEDULED', 'IN_PROGRESS', 'SUCCESS', 'FAILED', 'ABANDONED'))
);

CREATE UNIQUE INDEX ux_challenges_one_active_per_user
    ON challenges(user_id)
    WHERE status IN ('SCHEDULED', 'IN_PROGRESS');

CREATE INDEX ix_challenges_user_start ON challenges(user_id, start_date DESC);
CREATE INDEX ix_challenges_due ON challenges(status, start_date, end_date);
