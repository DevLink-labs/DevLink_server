-- 3차 IDX-3-02 작업 파일 계약: detached HEAD 관측은 branch를 null로 보낸다.
ALTER TABLE working_files ALTER COLUMN branch_name DROP NOT NULL;
