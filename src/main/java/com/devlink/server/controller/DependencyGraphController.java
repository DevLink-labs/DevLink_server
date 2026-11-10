package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.git.DependencyGraphData.*;
import com.devlink.server.service.DependencyGraphService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/git")
@RequiredArgsConstructor
public class DependencyGraphController {
    private static final int BODY_LIMIT = 2 * 1024 * 1024;
    private final DependencyGraphService service;
    private final ObjectMapper json;

    @PostMapping("/dependency-sources")
    public ApiResponse<RepositoryInfo> register(@PathVariable long teamId,
            @AuthenticationPrincipal UserPrincipal user, @RequestBody RegisterSource request) {
        return ApiResponse.success(service.register(teamId, user.userId(), request));
    }

    @GetMapping("/dependency-sources/{repoKey}")
    public ApiResponse<SourceConfig> source(@PathVariable long teamId, @PathVariable String repoKey,
            @AuthenticationPrincipal AgentPrincipal agent) {
        return ApiResponse.success(service.source(teamId, agent, repoKey));
    }

    @PostMapping(path = "/file-dependencies:batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RepositoryInfo> upload(@PathVariable long teamId,
            @AuthenticationPrincipal AgentPrincipal agent, HttpServletRequest request) throws IOException {
        return ApiResponse.success(service.upload(teamId, agent, bounded(request, Upload.class)));
    }    
    @GetMapping("/dependency-graphs")
    public ApiResponse<List<RepositoryInfo>> repositories(@PathVariable long teamId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(service.repositories(teamId, user.userId()));
    }

    @GetMapping("/dependency-graph")
    public ApiResponse<TeamGraph> graph(@PathVariable long teamId, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(service.graph(teamId, user.userId()));
    }

    @GetMapping("/dependency-graph/activity")
    public ApiResponse<Activity> activity(@PathVariable long teamId, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(service.activity(teamId, user.userId()));
    }

    private <T> T bounded(HttpServletRequest request, Class<T> type) throws IOException {
        if (request.getContentLengthLong() > BODY_LIMIT) throw new BusinessException(ErrorCode.DEPENDENCY_GRAPH_TOO_LARGE);
        byte[] bytes = request.getInputStream().readNBytes(BODY_LIMIT + 1);
        if (bytes.length > BODY_LIMIT) throw new BusinessException(ErrorCode.DEPENDENCY_GRAPH_TOO_LARGE);
        try {
            return json.readValue(bytes, type);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
