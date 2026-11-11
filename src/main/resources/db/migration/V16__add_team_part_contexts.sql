-- 팀·파트 간 Context 중계: 파트 쌍별 요약(공통 맥락·달라진 맥락·주요 쟁점)의 버전 이력.
-- 원본은 tasks·task_events에 그대로 있고, 이 테이블은 그로부터 파생한 요약만 저장한다.
CREATE TABLE team_part_contexts (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY,
    team_id        BIGINT       NOT NULL,
    part_a         VARCHAR(16)  NOT NULL,
    part_b         VARCHAR(16)  NOT NULL,
    version        BIGINT       NOT NULL,
    common         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    changed        JSONB        NOT NULL DEFAULT '[]'::jsonb,
    issues         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    last_event_id  BIGINT       NOT NULL,
    model          VARCHAR(100),
    prompt_version VARCHAR(50)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_team_part_contexts PRIMARY KEY (id),
    CONSTRAINT fk_team_part_contexts_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT fk_team_part_contexts_last_event FOREIGN KEY (last_event_id) REFERENCES task_events (id),
    CONSTRAINT uk_team_part_contexts_version UNIQUE (team_id, part_a, part_b, version),
    CONSTRAINT ck_team_part_contexts_part_a CHECK (part_a IN ('FRONTEND', 'BACKEND', 'INFRA', 'OTHER')),
    CONSTRAINT ck_team_part_contexts_part_b CHECK (part_b IN ('FRONTEND', 'BACKEND', 'INFRA', 'OTHER')),
    -- 쌍은 이름 오름차순으로 정규화해 (A,B)와 (B,A)가 한 행 계열이 되게 한다. 같은 파트 쌍도 허용한다.
    CONSTRAINT ck_team_part_contexts_pair_order CHECK (part_a <= part_b),
    CONSTRAINT ck_team_part_contexts_version_positive CHECK (version >= 1),
    CONSTRAINT ck_team_part_contexts_common_array CHECK (jsonb_typeof(common) = 'array'),
    CONSTRAINT ck_team_part_contexts_changed_array CHECK (jsonb_typeof(changed) = 'array'),
    CONSTRAINT ck_team_part_contexts_issues_array CHECK (jsonb_typeof(issues) = 'array')
);

CREATE INDEX ix_team_part_contexts_latest
    ON team_part_contexts (team_id, part_a, part_b, version DESC);
