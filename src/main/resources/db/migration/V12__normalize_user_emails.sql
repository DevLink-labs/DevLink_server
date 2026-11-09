-- 이메일 정리 중 다른 요청의 회원 생성·수정·삭제 방지
LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE;

-- 소문자 변환 시 중복되는 계정의 자동 병합 방지
DO $$
BEGIN
    IF EXISTS (
        SELECT lower(email)
        FROM users
        GROUP BY lower(email)
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Email normalization requires resolving case-insensitive duplicates first';
    END IF;
END;
$$;

-- 기존 계정 이메일을 일반 동등 조회와 같은 소문자 기준으로 정리
UPDATE users
SET email = lower(email)
WHERE email <> lower(email);
