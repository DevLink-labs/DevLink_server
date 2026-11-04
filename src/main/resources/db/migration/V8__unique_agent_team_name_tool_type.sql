ALTER TABLE agents
    ADD CONSTRAINT uk_agents_team_name_tool_type UNIQUE (team_id, name, tool_type);
