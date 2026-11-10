package com.devlink.server.dto.recommendation;

/** 관련 파일의 근거 유형. 4차 의존성 확장 시 값이 추가될 수 있으므로 소비자는 알 수 없는 값을 무시한다. */
public enum RelatedFileSource {
    WORKING, COMMIT
}
