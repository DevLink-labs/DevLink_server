package com.devlink.server.service;

import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TaskFileRepository;
import com.devlink.server.repository.TaskEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.task.TaskCompleteRequest;
import com.devlink.server.domain.taskfile.TaskFile;
import com.devlink.server.dto.task.TaskFileRequest;
import java.util.List;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.task.TaskCompleteResponse;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskCompleteService {

    private final TaskRepository taskRepository;
    private final TaskFileRepository taskFileRepository;
    private final TaskEventRepository taskEventRepository;
    private final TeamAuthorizationService teamAuthorizationService;
    private final TaskCompletionOutboxWriter taskCompletionOutboxWriter;
    private final TeamContextOutboxWriter teamContextOutbox;

    @Transactional
    public TaskCompleteResponse complete(
    		Long taskId,
    		AgentPrincipal agent,
    		TaskCompleteRequest request) {
    	validateAgent(agent);

    	Task task = findTask(taskId);

    	validatePermission(task, agent);
    	validateCompletion(task, request);

    	TaskStatus previousStatus = task.getStatus();

    	task.complete(agent.agentId(), request.resultText());

    	saveTaskFiles(task.getId(), request.taskFiles());
        TaskEvent completion = saveCompletionEvent(task, previousStatus);

    	taskCompletionOutboxWriter.enqueue(task);
        // 팀 Context 갱신 신호도 같은 트랜잭션에 남긴다.
        teamContextOutbox.enqueue(task, completion);

    	return new TaskCompleteResponse(
    			task.getId(),
    			task.getStatus(),
    			task.getVersion(),
    			task.getCompletedAt());

    }

    private TaskEvent saveCompletionEvent(
    	Task task,
    	TaskStatus previousStatus) {
        return taskEventRepository.save(TaskEvent.of(
    			task.getId(),
    			TaskEventType.STATUS_CHANGED,
    			previousStatus + " → " +task.getStatus()+": 작업 완료"));
    }

    private void saveTaskFiles(
    		Long taskId,
    		List<TaskFileRequest> taskFiles) {
    	List<TaskFile> files = taskFiles.stream()
    			.map(file -> TaskFile.of(
    					taskId,
    					file.relativePath(),
    					file.changeType(),
    					file.diffText()))
    			.toList();

    	taskFileRepository.saveAll(files);
    }

    private void validateCompletion(
    		Task task,
    		TaskCompleteRequest request) {
    	if(!task.getVersion().equals(request.version())) {
    		throw new BusinessException(ErrorCode.TASK_VERSION_CONFLICT);
    	}
    	if(task.getStatus() != TaskStatus.IN_PROGRESS) {
    		throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
    	}
    }

    private void validatePermission(
    		Task task,
    		AgentPrincipal agent) {
    	if(task.getTeamId().longValue() != agent.teamId()) {
    		throw new BusinessException(ErrorCode.ACCESS_DENIED);
    	}
    	teamAuthorizationService.requireTeamMember(agent.ownerUserId(), task.getTeamId());

    	if(task.getReceiverAgentId().longValue() != agent.agentId()) {
    		throw new BusinessException(ErrorCode.ACCESS_DENIED);
    	}
    }

    private void validateAgent(AgentPrincipal agent) {
    	if(agent == null||agent.agentId()<=0
    			||agent.ownerUserId()<=0||agent.teamId()<=0) {
    		throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
    	}
    }

    private Task findTask(Long taskId) {
    	if(taskId == null || taskId <= 0) {
    		throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    	}
    	return taskRepository.findByIdForUpdate(taskId)
    			.orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

}
