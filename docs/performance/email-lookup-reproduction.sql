\set ON_ERROR_STOP on
\timing on

-- 실행 시점의 DB·세션 설정 기록
SELECT version();
SHOW shared_buffers;
SHOW max_parallel_workers_per_gather;
SHOW lc_ctype;

-- 현재 세션의 임시 테이블에 기존 사용자 구조·색인 복제
-- 임시 테이블의 버퍼는 local로 기록되며 과거 일반 테이블의 병렬·shared 수치와 직접 비교 불가
CREATE TEMP TABLE email_lookup_fixture (LIKE public.users INCLUDING ALL);

-- 실제 인증에 사용할 수 없는 비밀번호 문자열로 가상 회원 200,000명 생성
INSERT INTO email_lookup_fixture (email, name, nickname, password)
SELECT
    'member-' || sequence_number || '@example.test',
    'Fixture User',
    NULL,
    repeat('x', 60)
FROM generate_series(1, 200000) AS fixture(sequence_number);

-- 성공 조회용 가상 회원 1명 추가
INSERT INTO email_lookup_fixture (email, name, nickname, password)
VALUES ('index-probe@example.test', 'Lookup Probe', NULL, repeat('x', 60));

-- 같은 데이터와 최신 통계를 사용하는 비교 조건 준비
VACUUM (ANALYZE) email_lookup_fixture;
SELECT count(*) AS fixture_user_count FROM email_lookup_fixture;

-- 변경 전 로그인 조회 조건
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, created_at, email, name, nickname, password
FROM email_lookup_fixture
WHERE upper(email) = upper('INDEX-PROBE@EXAMPLE.TEST');

-- 변경 후 서버에서 소문자로 변환한 값의 일반 동등 조회 조건
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, created_at, email, name, nickname, password
FROM email_lookup_fixture
WHERE email = 'index-probe@example.test';

-- 변경 전 존재하지 않는 이메일의 중복 검사 조건
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id
FROM email_lookup_fixture
WHERE upper(email) = upper('NEW-MEMBER@EXAMPLE.TEST')
LIMIT 1;

-- 변경 후 존재하지 않는 이메일의 중복 검사 조건
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id
FROM email_lookup_fixture
WHERE email = 'new-member@example.test'
LIMIT 1;

-- 동일 쿼리 반복 시 첫 결과를 워밍업으로 분리하고 실행 순서를 바꾸어 비교
-- 세션 종료 시 임시 테이블·임시 색인 자동 정리
