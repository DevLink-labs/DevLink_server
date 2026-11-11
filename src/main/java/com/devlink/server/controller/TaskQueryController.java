package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.task.WebTaskListRequest;
import com.devlink.server.dto.task.TaskEventListRequest;
import com.devlink.server.dto.task.TaskEventListResponse;
import com.devlink.server.dto.task.TaskDetailResponse;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.task.TaskListRequest;
import com.devlink.server.dto.task.TaskListResponse;
import com.devlink.server.service.TaskQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.devlink.server.dto.task.WebTaskDetailResponse;
import com.devlink.server.dto.task.WebTaskHistoryResponse;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TaskQueryController {

    private final TaskQueryService taskQueryService;

    
    @GetMapping("/web/teams/{teamId}/tasks/{taskId}/events")
    public ResponseEntity<ApiResponse<WebTaskHistoryResponse>> getWebTaskHistory(
    		@PathVariable @Positive Long teamId,
    		@PathVariable @Positive Long taskId,
    		@AuthenticationPrincipal UserPrincipal user,
            @Valid @ModelAttribute TaskEventListRequest request,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            @jakarta.validation.constraints.Pattern(regexp = "0|[1-9][0-9]{0,18}")
            @jakarta.validation.constraints.DecimalMax("9223372036854775807") String afterSequence){
        WebTaskHistoryResponse response = taskQueryService.getWebTaskHistory(teamId, taskId, user, request, afterSequence);
    	
    	return ResponseEntity.ok(ApiResponse.success(response));
    	
    }
    
    @GetMapping("/web/teams/{teamId}/tasks/{taskId}")
    public ResponseEntity<ApiResponse<WebTaskDetailResponse>> getWebTask(
    		@PathVariable @Positive Long teamId,
    		@PathVariable @Positive Long taskId,
    		@AuthenticationPrincipal UserPrincipal user){
    	WebTaskDetailResponse response = taskQueryService.getWebTask(teamId, taskId, user);
    	
    	return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    @GetMapping("/web/teams/{teamId}/tasks")
    public ResponseEntity<ApiResponse<TaskListResponse>> getWebTasks(
    		@PathVariable @Positive Long teamId,
    		@AuthenticationPrincipal UserPrincipal user,
    		@Valid @ModelAttribute WebTaskListRequest request){
    	TaskListResponse response = taskQueryService.getWebTasks(teamId, user, request);
    	
    	return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    @GetMapping("/teams/{teamId}/tasks")
    public ResponseEntity<ApiResponse<TaskListResponse>> getTasks(
            @PathVariable @Positive Long teamId,
            @AuthenticationPrincipal AgentPrincipal agent,
            @Valid @ModelAttribute TaskListRequest request
    ) {
        TaskListResponse response = taskQueryService.getTasks(
                teamId,
                agent,
                request
        );

        return ResponseEntity.ok(
                ApiResponse.success(response)
        );
    }
    
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<ApiResponse<TaskDetailResponse>> getTask(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal AgentPrincipal agent
    ) {
        TaskDetailResponse response = taskQueryService.getTask(
                taskId,
                agent
        );

        return ResponseEntity.ok(
                ApiResponse.success(response)
        );
    }
    
    @GetMapping("/tasks/{taskId}/events")
    public ResponseEntity<ApiResponse<TaskEventListResponse>> getTaskEvents(
    		@PathVariable @Positive Long taskId,
    		@AuthenticationPrincipal AgentPrincipal agent,
    		@Valid @ModelAttribute TaskEventListRequest request){
    	TaskEventListResponse response = taskQueryService.getTaskEvents(taskId, agent, request);
    	
    	return ResponseEntity.ok(ApiResponse.success(response));
    }
    
    
}
