ALTER TABLE task_events
    ADD COLUMN sequence BIGINT,
    ADD COLUMN author_agent_id BIGINT,
    ADD COLUMN question_event_id BIGINT,
    ADD COLUMN request_key VARCHAR(64);

WITH ordered_events AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY created_at, id) AS sequence
    FROM task_events
)
UPDATE task_events AS event
SET sequence = ordered.sequence
FROM ordered_events AS ordered
WHERE event.id = ordered.id;

ALTER TABLE task_events
    ALTER COLUMN sequence SET NOT NULL,
    ADD CONSTRAINT ck_task_events_sequence CHECK (sequence > 0),
    ADD CONSTRAINT uk_task_events_sequence UNIQUE (task_id, sequence),
    ADD CONSTRAINT uk_task_events_task_and_id UNIQUE (task_id, id),
    ADD CONSTRAINT uk_task_events_request_key UNIQUE (task_id, author_agent_id, request_key),
    ADD CONSTRAINT fk_task_events_question
        FOREIGN KEY (task_id, question_event_id) REFERENCES task_events (task_id, id),
    ADD CONSTRAINT ck_task_events_conversation_fields CHECK (
        (event_type = 'ANSWER') = (question_event_id IS NOT NULL)
        AND (
            event_type NOT IN ('QUESTION', 'ANSWER')
            OR (author_agent_id IS NOT NULL AND request_key IS NOT NULL)
        )
    );

-- Serialize event appends on the parent row without updating Task or its version.
-- NO KEY UPDATE is compatible with foreign-key checks by other Task children.
CREATE FUNCTION assign_task_event_sequence() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM tasks WHERE id = NEW.task_id FOR NO KEY UPDATE;

    SELECT COALESCE(MAX(sequence), 0) + 1 INTO NEW.sequence
    FROM task_events WHERE task_id = NEW.task_id;

    RETURN NEW;
END;
$$;

CREATE TRIGGER task_events_assign_sequence
BEFORE INSERT ON task_events
FOR EACH ROW EXECUTE FUNCTION assign_task_event_sequence();
