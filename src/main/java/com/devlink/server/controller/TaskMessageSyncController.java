package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.task.TaskMessageSyncResponse;
import com.devlink.server.service.TaskMessageSyncService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Agent Bearer 인증을 사용하며 요청 본문이나 사용자 세션으로 수신자를 지정하지 않는다. */
@RestController
@RequestMapping("/api/v1/agents/me/task-messages")
public class TaskMessageSyncController {
    private final TaskMessageSyncService service;

    public TaskMessageSyncController(TaskMessageSyncService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<TaskMessageSyncResponse> synchronize(@AuthenticationPrincipal AgentPrincipal agent,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(service.synchronize(agent, cursor, limit));
    }
}
