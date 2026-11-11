package com.devlink.server.exception;

/** 추론 요청 실패나 출력 형식 오류다. 쓰기 경로에는 닿지 않으며 Outbox 재시도 대상이다. */
public class TeamContextInferenceException extends RuntimeException {
    public TeamContextInferenceException(String message) {
        super(message);
    }

    public TeamContextInferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
