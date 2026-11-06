package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.recommendation.AgentRecommendationRequest;
import com.devlink.server.dto.recommendation.AgentRecommendationResponse;
import com.devlink.server.service.AgentRecommendationService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 담당 Agent 추천. Agent 연결 토큰으로 인증하며 읽기 전용 계산이므로 항상 200을 반환한다. */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/agents/recommendations")
@RequiredArgsConstructor
public class AgentRecommendationController {
    private final AgentRecommendationService recommendations;

    @PostMapping
    public ResponseEntity<ApiResponse<AgentRecommendationResponse>> recommend(
            @PathVariable @Positive Long teamId,
            @RequestBody AgentRecommendationRequest request,
            @AuthenticationPrincipal AgentPrincipal principal) {
        // 질문 길이·공백은 코드포인트 기준이라 Bean Validation 대신 서비스에서 검증한다.
        return ResponseEntity.ok(ApiResponse.success(
                recommendations.recommend(teamId, requireAgent(principal), request)));
    }

    private AgentPrincipal requireAgent(AgentPrincipal principal) {
        if (principal == null || principal.agentId() <= 0
                || principal.ownerUserId() <= 0 || principal.teamId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal;
    }
}
