package com.devlink.server.controller;

import java.util.List;
import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.AgentResponse;
import com.devlink.server.dto.CreateAgentRequest;
import com.devlink.server.dto.UpdateAgentRequest;
import com.devlink.server.service.AgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agents;

    @PostMapping("/teams/{teamId}")
    public ResponseEntity<ApiResponse<AgentResponse>> create(
            @PathVariable @Positive Long teamId,
            @Valid @RequestBody CreateAgentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(agents.create(userId(principal), teamId, request)));
    }

    @GetMapping("/teams/{teamId}")
    public ApiResponse<List<AgentResponse>> list(@PathVariable @Positive Long teamId,
            @RequestParam(name = "ownedByMe", defaultValue = "false") boolean ownedByMe,
            @AuthenticationPrincipal Object principal) {
        if (principal instanceof AgentPrincipal agent) {
            // Agent 토큰은 해당 Agent가 속한 팀의 목록만 조회 허용
            if (agent.teamId() != teamId.longValue()) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED);
            }
            return ApiResponse.success(agents.list(agent.ownerUserId(), teamId, ownedByMe));
        }
        if (principal instanceof UserPrincipal user) {
            return ApiResponse.success(agents.list(userId(user), teamId, ownedByMe));
        }
        throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @GetMapping("/{agentId}")
    public ApiResponse<AgentResponse> get(@PathVariable @Positive Long agentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(agents.get(userId(principal), agentId));
    }

    @PatchMapping("/{agentId}")
    public ApiResponse<AgentResponse> update(@PathVariable @Positive Long agentId,
            @Valid @RequestBody UpdateAgentRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(agents.update(userId(principal), agentId, request));
    }

    @DeleteMapping("/{agentId}")
    public ApiResponse<Void> delete(@PathVariable @Positive Long agentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        agents.delete(userId(principal), agentId);
        return ApiResponse.success(null);
    }

    private Long userId(UserPrincipal principal) {
        if (principal == null || principal.userId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.userId();
    }
}
