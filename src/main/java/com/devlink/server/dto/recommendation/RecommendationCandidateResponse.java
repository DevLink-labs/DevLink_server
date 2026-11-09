package com.devlink.server.dto.recommendation;

import java.time.Instant;
import java.util.List;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.AgentStatus;

public record RecommendationCandidateResponse(
        Long agentId,
        String name,
        AgentPart part,
        AgentStatus connectionStatus,
        double score,
        RecommendationScoresResponse scores,
        List<String> matchedRoles,
        List<RelatedFileResponse> relatedFiles,
        Instant latestCommitAt,
        Instant workingObservedAt,
        InsufficientReason insufficientReason) {
}
