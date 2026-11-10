package com.devlink.server.dto.recommendation;

import java.time.Instant;
import java.util.List;

/** 점수 근거가 된 관련 파일. 같은 저장소·경로는 하나로 합친다. */
public record RelatedFileResponse(
        Long repoId,
        String relativePath,
        List<RelatedFileSource> sources,
        Instant observedAt) {
}
