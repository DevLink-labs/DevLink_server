package com.devlink.server.dto.recommendation;

import java.time.Instant;
import java.util.List;

public record AgentRecommendationResponse(
        RecommendationStatus status,
        GitCoverageResponse gitCoverage,
        Instant evaluatedAt,
        List<RecommendationCandidateResponse> candidates,
        InsufficientReason insufficientReason) {
}
