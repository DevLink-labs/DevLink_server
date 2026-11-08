package com.devlink.server.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    INVALID_QUESTION_REFERENCE(HttpStatus.BAD_REQUEST, "같은 Task의 질문을 참조해야 합니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 요청 형식입니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "중복 요청입니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    GIT_EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "다른 사용자가 등록한 Git 이메일입니다."),
    AGENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "팀에 같은 이름과 도구 유형의 Agent가 이미 있습니다."),
    AGENT_HAS_TASKS(HttpStatus.CONFLICT, "작업 이력이 있는 Agent는 삭제할 수 없습니다."),
    REQUEST_KEY_CONFLICT(HttpStatus.CONFLICT, "동일 요청 키에 다른 작업 요청이 존재합니다."),
    TASK_VERSION_CONFLICT(HttpStatus.CONFLICT, "작업이 변경되었습니다. 최신 작업 정보를 조회한 후 다시 요청해주세요."),
    TASK_STATE_CONFLICT(HttpStatus.CONFLICT, "현재 작업 상태에서는 요청한 상태로 변경할 수 없습니다."),
    TASK_CONVERSATION_UNAVAILABLE(HttpStatus.CONFLICT, "요청자와 담당자가 같은 Agent인 Task에서는 질문과 답변을 등록할 수 없습니다."),
    RECEIVER_OFFLINE(HttpStatus.CONFLICT, "수신 Agent의 브로커에 연결할 수 없습니다."),
    DELIVERY_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "작업 전달 서비스를 사용할 수 없습니다."),
    GIT_ROUTING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현재 담당 Agent 추천을 처리할 수 없습니다. 잠시 후 다시 요청해주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }
}
