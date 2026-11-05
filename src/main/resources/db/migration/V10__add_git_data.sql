-- 3차 MVP: 담당 정보, Git 기여 이력 및 현재 작업 파일 저장.
-- 기존 Agent는 미지정 파트(OTHER)와 빈 태그 목록으로 유지한다.
ALTER TABLE agents
    ADD COLUMN part VARCHAR(16) NOT NULL DEFAULT 'OTHER',
    ADD COLUMN roles JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_agents_part
        CHECK (part IN ('FRONTEND', 'BACKEND', 'INFRA', 'OTHER')),
    ADD CONSTRAINT ck_agents_roles_array
        CHECK (jsonb_typeof(roles) = 'array');

-- 이번 ERD 기준: 해시가 아닌 정규화된 Git 이메일 원문을 저장한다.
-- 이메일 정규화 및 본인 대조값 생성은 등록/조회 API에서 처리한다.
CREATE TABLE user_git_settings (
    user_id       BIGINT       NOT NULL,
    git_email     VARCHAR(255) NOT NULL,
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_user_git_settings PRIMARY KEY (user_id),
    CONSTRAINT fk_user_git_settings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_user_git_settings_email UNIQUE (git_email)
);

CREATE TABLE team_repositories (
    id                BIGINT GENERATED ALWAYS AS IDENTITY,
    team_id           BIGINT      NOT NULL,
    origin_url_sha256 CHAR(64)    NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_team_repositories PRIMARY KEY (id),
    CONSTRAINT fk_team_repositories_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT uk_team_repositories_origin UNIQUE (team_id, origin_url_sha256),
    CONSTRAINT uk_team_repositories_team_and_id UNIQUE (team_id, id)
);

CREATE TABLE repository_commits (
    id             BIGINT GENERATED ALWAYS AS IDENTITY,
    team_id        BIGINT      NOT NULL,
    repo_id        BIGINT      NOT NULL,
    commit_sha     VARCHAR(64) NOT NULL,
    committed_at   TIMESTAMPTZ NOT NULL,
    author_user_id BIGINT,
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_repository_commits PRIMARY KEY (id),
    CONSTRAINT uk_repository_commits_sha UNIQUE (team_id, repo_id, commit_sha),
    -- 커밋의 team_id와 저장소의 실제 소속 팀이 일치하도록 제한한다.
    CONSTRAINT fk_repository_commits_repository
        FOREIGN KEY (team_id, repo_id) REFERENCES team_repositories (team_id, id),
    CONSTRAINT fk_repository_commits_author FOREIGN KEY (author_user_id) REFERENCES users (id)
);

CREATE INDEX ix_repository_commits_team_author_time
    ON repository_commits (team_id, author_user_id, committed_at DESC);

CREATE TABLE commit_files (
    commit_id     BIGINT       NOT NULL,
    relative_path VARCHAR(500) NOT NULL,
    change_type   VARCHAR(16)  NOT NULL,
    CONSTRAINT pk_commit_files PRIMARY KEY (commit_id, relative_path),
    CONSTRAINT fk_commit_files_commit FOREIGN KEY (commit_id) REFERENCES repository_commits (id),
    CONSTRAINT ck_commit_files_change_type
        CHECK (change_type IN ('ADDED', 'MODIFIED', 'DELETED', 'RENAMED'))
);

CREATE INDEX ix_commit_files_path ON commit_files (relative_path, commit_id);

-- 빈 파일 목록도 정상적인 관측으로 보존한다.
-- 사용자·저장소 단위 교체 및 90초 만료 시각 계산은 저장 API에서 처리한다.
CREATE TABLE working_files (
    repo_id     BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    branch_name VARCHAR(255) NOT NULL,
    files       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    observed_at TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_working_files PRIMARY KEY (repo_id, user_id),
    CONSTRAINT fk_working_files_repository FOREIGN KEY (repo_id) REFERENCES team_repositories (id),
    CONSTRAINT fk_working_files_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_working_files_array CHECK (jsonb_typeof(files) = 'array')
);

CREATE INDEX ix_working_files_repository_expiry ON working_files (repo_id, expires_at);

-- 4차 작업 영향 그래프용 예약 스키마. 3차에서는 수집/API/점수에 사용하지 않는다.
CREATE TABLE file_dependencies (
    repo_id              BIGINT       NOT NULL,
    source_relative_path VARCHAR(500) NOT NULL,
    target_relative_path VARCHAR(500) NOT NULL,
    relation_type        VARCHAR(16)  NOT NULL,
    analysis_commit_sha  VARCHAR(64)  NOT NULL,
    observed_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_file_dependencies PRIMARY KEY (
        repo_id, analysis_commit_sha, source_relative_path, target_relative_path, relation_type
    ),
    CONSTRAINT fk_file_dependencies_repository FOREIGN KEY (repo_id) REFERENCES team_repositories (id)
);

CREATE INDEX ix_file_dependencies_reverse
    ON file_dependencies (repo_id, analysis_commit_sha, target_relative_path, relation_type);
