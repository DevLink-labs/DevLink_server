package com.devlink.server.dto.task;

import java.time.Instant;

/** 본문은 공통 상세 조회로 확인하며 이 응답에는 도착 정보만 포함한다. */
public record TaskMessageItem(String eventId, long taskId, String sequence,
        String eventType, Instant createdAt) { }
