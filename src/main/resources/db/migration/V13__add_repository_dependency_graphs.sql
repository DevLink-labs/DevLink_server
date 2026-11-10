CREATE TABLE repository_dependency_graphs (
    repo_id BIGINT PRIMARY KEY REFERENCES team_repositories(id) ON DELETE CASCADE,
    display_name VARCHAR(120) NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    repo_kind VARCHAR(8) NOT NULL CHECK (repo_kind IN ('FRONTEND', 'BACKEND')),
    publisher_agent_id BIGINT REFERENCES agents(id) ON DELETE SET NULL,
    target_repo_id BIGINT REFERENCES team_repositories(id) ON DELETE SET NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    analysis_commit_sha VARCHAR(64),
    analysis_version INTEGER,
    analyzed_at TIMESTAMPTZ,
    graph JSONB,
    CONSTRAINT ck_dependency_graph_snapshot CHECK (
        (graph IS NULL AND analysis_commit_sha IS NULL AND analysis_version IS NULL AND analyzed_at IS NULL)
        OR
        (graph IS NOT NULL AND analysis_commit_sha IS NOT NULL AND analysis_version IS NOT NULL AND analyzed_at IS NOT NULL)
    )
);
