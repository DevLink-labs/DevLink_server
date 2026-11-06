package com.devlink.server.dto.recommendation;

/** 팀 전체 저장소의 Git 데이터 수집 상태. 근거 부족과는 별도 축이다. */
public record GitCoverageResponse(
        GitCoverageState state,
        GitCoverageReason reason,
        WorkingObservationState workingState) {
}
