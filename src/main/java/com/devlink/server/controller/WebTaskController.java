package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.ChangeTaskStatusResponse;
import com.devlink.server.dto.task.WebTaskAcceptRequest;
import com.devlink.server.service.TaskStatusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/web/teams/{teamId}/tasks")
@RequiredArgsConstructor
public class WebTaskController {
    private final TaskStatusService taskStatusService;

    @PostMapping("/{taskId}/accept")
    public ApiResponse<ChangeTaskStatusResponse> accept(
            @PathVariable @Positive Long teamId,
            @PathVariable @Positive Long taskId,
            @Valid @RequestBody WebTaskAcceptRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return ApiResponse.success(taskStatusService.acceptWebTask(teamId, taskId, user.userId(), request.version()));
    }
}
