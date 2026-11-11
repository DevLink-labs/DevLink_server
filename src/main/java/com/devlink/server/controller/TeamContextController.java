package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.teamcontext.TeamContextResponse;
import com.devlink.server.service.TeamContextQueryService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 팀·파트 간 Context 요약 조회. Agent 연결 토큰으로 인증하는 읽기 전용 경로다. */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/context")
@RequiredArgsConstructor
public class TeamContextController {
    private final TeamContextQueryService contexts;

    @GetMapping
    public ResponseEntity<ApiResponse<TeamContextResponse>> get(
            @PathVariable @Positive Long teamId,
            @RequestParam(required = false) String part,
            @RequestParam(required = false) String counterpart,
            @AuthenticationPrincipal AgentPrincipal principal) {
        // part 값 검증은 서비스에서 VALIDATION_FAILED로 통일한다.
        return ResponseEntity.ok(ApiResponse.success(contexts.get(teamId, principal, part, counterpart)));
    }
}
