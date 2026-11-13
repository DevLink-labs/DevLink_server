package com.devlink.server.exception;

/** 결정 모델 호출 실패나 응답 형식 오류다. 추천 응답으로 전파하지 않고 기존 토큰 일치로 대체한다. */
public class RoleDecisionException extends RuntimeException {
    public RoleDecisionException(String message) {
        super(message);
    }
}
