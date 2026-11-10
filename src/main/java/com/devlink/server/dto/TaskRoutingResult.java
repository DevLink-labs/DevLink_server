package com.devlink.server.dto;

/** 서버 내부 전송 결과다. SENT는 소켓 쓰기 성공이며 브로커 수신 확인(ACK_RECEIVED)이 아니다. */
public record TaskRoutingResult(long taskId, Status status, String eventId, String connectionId) {
    public enum Status {
        SENT,
        ALREADY_DELIVERED,
        ACK_IN_PROGRESS,
        TASK_NOT_PENDING,
        RECEIVER_OFFLINE,
        CONNECTION_UNAVAILABLE,
        CONNECTION_REPLACED,
        TRANSPORT_ERROR
    }
}
