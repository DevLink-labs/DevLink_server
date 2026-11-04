package com.devlink.server.dto;

/** 알림 전송 시도 결과다. SENT는 소켓 쓰기 성공이며 수신 확인 ACK를 뜻하지 않는다. */
public record TaskNotificationResult(long taskId, String eventId, Status status) {
    public enum Status {
        SENT,
        INVALID_EVENT,
        REQUESTER_UNAVAILABLE,
        RECIPIENT_UNAVAILABLE,
        CONNECTION_UNAVAILABLE,
        CONNECTION_CHANGED,
        TRANSPORT_ERROR,
        INTERNAL_ERROR
    }
}
