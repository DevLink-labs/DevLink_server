package com.devlink.server.exception;

/** Outbox 메시지가 가리키는 작업·이벤트·Agent를 찾을 수 없거나 서로 맞지 않는다. 재시도해도 같으므로 영구 실패다. */
public class InvalidTeamContextEventException extends RuntimeException {
    public InvalidTeamContextEventException(String message) {
        super(message);
    }
}
