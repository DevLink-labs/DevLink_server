package com.devlink.server.dto.recommendation;

/** 후보 사용자들의 작업 파일 관측 상태. 유효 관측이 있으면 FRESH, 만료 관측만 있으면 STALE. */
public enum WorkingObservationState {
    FRESH, STALE, NOT_COLLECTED
}
