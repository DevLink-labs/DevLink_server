ALTER TABLE tasks
    ADD CONSTRAINT fk_tasks_requester_agent
        FOREIGN KEY (requester_agent_id) REFERENCES agents (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_tasks_receiver_agent
        FOREIGN KEY (receiver_agent_id) REFERENCES agents (id) ON DELETE RESTRICT;
