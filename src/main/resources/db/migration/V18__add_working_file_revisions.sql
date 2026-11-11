-- 작업 파일 관측의 만료·삭제와 독립적으로 revision을 보존한다.
CREATE TABLE working_file_revisions (
    repo_id  BIGINT NOT NULL,
    user_id  BIGINT NOT NULL,
    revision UUID   NOT NULL,

    CONSTRAINT pk_working_file_revisions
        PRIMARY KEY (repo_id, user_id),

    CONSTRAINT fk_working_file_revisions_repository
        FOREIGN KEY (repo_id)
        REFERENCES team_repositories (id),

    CONSTRAINT fk_working_file_revisions_user
        FOREIGN KEY (user_id)
        REFERENCES users (id)
);