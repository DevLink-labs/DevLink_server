CREATE SEQUENCE team_context_outbox_order_seq;

ALTER TABLE outbox_events ADD COLUMN context_order BIGINT;

-- V11 rows retain their original order and drain before newly ordered rows.
CREATE INDEX ix_outbox_team_context_open
    ON outbox_events (aggregate_id, (COALESCE(context_order, 0)), created_at, outbox_id)
    WHERE destination = 'team-context' AND status IN ('PENDING', 'PROCESSING');

ALTER TABLE team_part_contexts ADD CONSTRAINT uk_team_part_contexts_event
    UNIQUE (team_id, part_a, part_b, last_event_id);
