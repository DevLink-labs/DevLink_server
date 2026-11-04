-- 작업 등록 시 요청 Agent가 적는 작업 요약. 원래 요청(prompt)·완료 결과(result_text)와 구분해 보존한다.
-- 구버전 브로커는 보내지 않으므로 NULL을 허용하고, 기존 행은 NULL로 둔다.
-- length()는 문자(코드포인트) 단위이므로 서비스의 1~1,000 코드포인트 검증과 같은 기준이다.
ALTER TABLE tasks ADD COLUMN task_summary TEXT;
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_task_summary
    CHECK (task_summary IS NULL OR length(task_summary) BETWEEN 1 AND 1000);
