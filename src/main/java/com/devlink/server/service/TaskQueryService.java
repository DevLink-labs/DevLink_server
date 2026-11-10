package com.devlink.server.service;

import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Function;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.task.WebTaskListRequest;
import com.devlink.server.dto.task.TaskEventListResponse;
import com.devlink.server.dto.task.TaskFileResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.dto.task.TaskListRequest;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.repository.TeamMemberRepository;
import lombok.RequiredArgsConstructor;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.task.Task;
import com.devlink.server.repository.TaskRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.dto.task.TaskAgentResponse;
import com.devlink.server.dto.task.TaskListItemResponse;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import com.devlink.server.dto.task.TaskListResponse;
import java.util.ArrayList;
import java.util.Collections;
import com.devlink.server.dto.task.WebTaskDetailResponse;
import org.springframework.transaction.annotation.Transactional;
import com.devlink.server.repository.TaskFileRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.dto.task.TaskEventResponse;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.task.TaskDetailResponse;
import com.devlink.server.dto.task.TaskEventListRequest;
import com.devlink.server.dto.task.WebTaskHistoryResponse;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import com.devlink.server.domain.taskevent.TaskEventType;

@Service
@RequiredArgsConstructor
public class TaskQueryService {

	private final TeamRepository teamRepository;
	private final TeamMemberRepository teamMemberRepository;
	private final AgentRepository agentRepository;
	private final TaskRepository taskRepository;
	private final TaskFileRepository taskFileRepository;
	private final TaskEventRepository taskEventRepository;
	private final TeamAuthorizationService teamAuthorizationService;
	private final ObjectMapper objectMapper;
	
	@Transactional(readOnly = true)
	public WebTaskHistoryResponse getWebTaskHistory(
			Long teamId,
			Long taskId,
			UserPrincipal user,
			TaskEventListRequest request) {
		if(user == null) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
		teamAuthorizationService.requireTeamMember(user.userId(), teamId);
		
		Task task = findTask(taskId);
		
		if(!task.getTeamId().equals(teamId)) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		
		validateWebTaskAccess(task, user.userId());
		
		List<TaskEventType> eventTypes = List.of(
				TaskEventType.CREATED,
				TaskEventType.STATUS_CHANGED,
				TaskEventType.PROGRESS,
				TaskEventType.QUESTION,
				TaskEventType.ANSWER);
		
		int limit = request.limit();
		Pageable pageable = PageRequest.of(0, limit+1);
		
		List<TaskEvent> fetched;
		
		if(request.beforeSequence() == null) {
			fetched = taskEventRepository.findLatestEventsByTypes(taskId, eventTypes, pageable);
		}else {
			Long beforeSequence;
			try {
				beforeSequence = Long.valueOf(request.beforeSequence());
			}catch(NumberFormatException e) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
			fetched = taskEventRepository.findEventsBeforeByTypes(taskId, eventTypes, beforeSequence, pageable);
		}
		boolean hasNext = fetched.size()>limit;
		
		List<TaskEvent> selected = new ArrayList<>(
				fetched.subList(0, Math.min(limit, fetched.size())));
		
		Collections.reverse(selected);
		
		String nextBeforeSequence = hasNext ? selected.getFirst().getSequence().toString():null;
		
		TaskStatus status = task.getStatus();
		
		String resultText = status == TaskStatus.COMPLETED?task.getResultText():null;
		
		String failureReason = status == TaskStatus.FAILED?task.getResultText():null;
		
		String rejectionReason = status == TaskStatus.REJECTED?task.getResultText():null;
		
		boolean partiallyModified = status == TaskStatus.FAILED && task.isPartiallyModified();

		return new WebTaskHistoryResponse(
				task.getId(),
				task.getPrompt(),
				resultText,
				failureReason,
				rejectionReason,
				partiallyModified,
				findTaskFiles(taskId),
				toEventResponses(selected),
				nextBeforeSequence,
				hasNext);
	}
	
	@Transactional(readOnly = true)
	public WebTaskDetailResponse getWebTask(
			Long teamId,
			Long taskId,
			UserPrincipal user) {
		if(user == null) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
		//로그인 사용자가 요청한 팀의 현재 회원인지 확인
		teamAuthorizationService.requireTeamMember(user.userId(), teamId);
		
		Task task = findTask(taskId);
		
		//경로의 팀과 작업이 속한 팀이 같은지 확인
		if(!task.getTeamId().equals(teamId)) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		
		validateWebTaskAccess(task, user.userId());
		
		Map<Long,Agent> agentsById = findAgents(List.of(task));
		
		Agent requesterAgent = agentsById.get(task.getRequesterAgentId());
		Agent receiverAgent = agentsById.get(task.getReceiverAgentId());
		
		if(requesterAgent == null || receiverAgent == null) {
			throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		
		String teamName = teamRepository.findById(task.getTeamId())
				.orElseThrow(() -> 
				new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
				.getName();
		
		LocalDateTime lastActivityAt = findLastActivityTimes(List.of(task))
				.get(task.getId());
		
		return new WebTaskDetailResponse(
				task.getId(),
				task.getTeamId(),
				teamName,
				resolveWebTaskSummary(task),
				new TaskAgentResponse(
						requesterAgent.getId(),
						requesterAgent.getName())
				,new TaskAgentResponse(
						receiverAgent.getId(),
						receiverAgent.getName()),
				task.getStatus(),
				task.getCreatedAt(),
				task.getStartedAt(),
				task.getCompletedAt(),
				lastActivityAt
				); 
	}
	
	private String resolveWebTaskSummary(Task task) {
		String summary = task.getTaskSummary();
		
		//저장된 요약 우선 사용
		if(summary != null && !summary.isBlank()) {
			return summary;
		}
		String prompt = task.getPrompt();
		
		if(prompt == null || prompt.isBlank()) {
			return null;
		}
		
		try {
			JsonNode root = objectMapper.reader()
					.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.readTree(prompt);
			if(root == null || !root.isObject()) {
				return null;
			}
			JsonNode title = root.get("title");
			if(title == null || !title.isTextual()) {
				return null;
			}
			String extractedTitle = title.textValue().strip();
			return extractedTitle.isEmpty()?null : extractedTitle;
		}catch(JsonProcessingException e) {
			//일반 문자열 또는 잘못된 JSON이면 제목 추출 없이 반환.
			return null;
		}
	}
	
	private void validateWebTaskAccess(Task task, Long userId) {
		boolean participant = agentRepository
				.findUserAgentInTeam(task.getTeamId(), userId)
				.stream()
				.anyMatch(agent ->
				agent.getId().equals(task.getRequesterAgentId())
				|| agent.getId().equals(task.getReceiverAgentId()));
		
		if(!participant) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
	}
	
	@Transactional(readOnly = true)
	public TaskListResponse getWebTasks(
		Long teamId,
		UserPrincipal user,
		WebTaskListRequest request) {
		
		if(user == null) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
		
		teamAuthorizationService.requireTeamMember(user.userId(), teamId);
		Pageable pageable = PageRequest.of(request.page(), request.size());
		
		List<Long> agentIds = agentRepository
				.findUserAgentInTeam(teamId,user.userId())
				.stream()
				.map(Agent::getId)
				.toList();
		
		Page<Task> taskPage;
		
		if(agentIds.isEmpty()) {
			taskPage = Page.empty(pageable);
		}else {
			taskPage = taskRepository.findAgentTasks(teamId, agentIds, "all", request.status(), pageable);
		}
		
		return toListResponse(taskPage, this::resolveWebTaskSummary);
	}
	
	@Transactional(readOnly = true)
	public TaskListResponse getTasks(
	        Long teamId,
	        AgentPrincipal agent,
	        TaskListRequest request
	) {
	    validateAgent(agent);
	    validateConditions(request);
	    validateTeam(teamId, agent);

	    Page<Task> taskPage = findTasks(
	            teamId,
	            agent,
	            request
	    );

	    return toListResponse(taskPage,Task::getTaskSummary);
	}
	
	@Transactional(readOnly = true)
	public TaskDetailResponse getTask(
	        Long taskId,
	        AgentPrincipal agent
	) {
	    validateAgent(agent);

	    Task task = findTask(taskId);

	    validateTeam(task.getTeamId(), agent);

	    Map<Long, Agent> agentsById = findAgents(List.of(task));

	    Agent requesterAgent = agentsById.get(
	            task.getRequesterAgentId()
	    );

	    Agent receiverAgent = agentsById.get(
	            task.getReceiverAgentId()
	    );

	    if (requesterAgent == null || receiverAgent == null) {
	        throw new BusinessException(
	                ErrorCode.INTERNAL_SERVER_ERROR
	        );
	    }

	    List<TaskFileResponse> taskFiles = findTaskFiles(taskId);
	    List<TaskEventResponse> events = findTaskEvents(taskId);

	    return toDetailResponse(
	            task,
	            requesterAgent,
	            receiverAgent,
	            taskFiles,
	            events
	    );
	}
	
	private Map<Long, LocalDateTime> findLastActivityTimes(List<Task> tasks) {
		Map<Long, LocalDateTime> lastActivityTimes = new HashMap<>();

		if (tasks.isEmpty()) {
			return lastActivityTimes;
		}

		for (Task task : tasks) {
			lastActivityTimes.put(task.getId(), task.getCreatedAt());
		}

		List<Long> taskIds = tasks.stream()
				.map(Task::getId)
				.toList();

		var latestEventTimes = taskEventRepository.findLatestEventTimes(
				taskIds,
				List.of(
						TaskEventType.CREATED,
						TaskEventType.STATUS_CHANGED,
						TaskEventType.QUESTION,
						TaskEventType.ANSWER));

		ZoneId localZone = ZoneId.systemDefault();

		for (var event : latestEventTimes) {
			LocalDateTime latestAt = event.getLatestAt();
			// 질문·답변은 UTC로 저장되므로 로컬 시간대로 변환
	        if (event.getEventType() == TaskEventType.QUESTION
	                || event.getEventType() == TaskEventType.ANSWER) {
	            latestAt = latestAt.atOffset(ZoneOffset.UTC)
	                    .atZoneSameInstant(localZone)
	                    .toLocalDateTime();
	        }

	        lastActivityTimes.merge(
	                event.getTaskId(),
	                latestAt,
	                (current, candidate) ->
	                        current.isAfter(candidate) ? current : candidate
	        );
		}
		return lastActivityTimes;
	}

	private TaskDetailResponse toDetailResponse(
	        Task task,
	        Agent requesterAgent,
	        Agent receiverAgent,
	        List<TaskFileResponse> taskFiles,
	        List<TaskEventResponse> events
	) {
	    TaskStatus status = task.getStatus();

	    String resultText = status == TaskStatus.COMPLETED
	            ? task.getResultText() : null;

	    String failureReason = status == TaskStatus.FAILED
	            ? task.getResultText() : null;

	    String rejectionReason = status == TaskStatus.REJECTED
	            ? task.getResultText() : null;

	    String teamName = teamRepository.findById(task.getTeamId())
	            .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
	            .getName();

	    boolean partiallyModified = status == TaskStatus.FAILED
	            && task.isPartiallyModified();

	    LocalDateTime lastActivityAt = findLastActivityTimes(List.of(task))
	            .get(task.getId());

	    return new TaskDetailResponse(
	            task.getId(),
	            task.getTeamId(),
	            teamName,
	            task.getTaskSummary(),
	            new TaskAgentResponse(
	                    requesterAgent.getId(),
	                    requesterAgent.getName()
	            ),
	            new TaskAgentResponse(
	                    receiverAgent.getId(),
	                    receiverAgent.getName()
	            ),
	            task.getPrompt(),
	            task.getStatus(),
	            task.getVersion(),
	            resultText,
	            failureReason,
	            rejectionReason,
	            partiallyModified,
	            taskFiles,
	            events,
	            task.getCreatedAt(),
	            task.getStartedAt(),
	            task.getCompletedAt(),
	            lastActivityAt
	    );
	}
	
	private List<TaskFileResponse> findTaskFiles(Long taskId) {
	    return taskFileRepository.findByTaskId(taskId)
	            .stream()
	            .map(file -> new TaskFileResponse(
	                    file.getId(),
	                    file.getRelativePath(),
	                    file.getChangeType(),
	                    file.getDiffText()
	            ))
	            .toList();
	}
	
	@Transactional(readOnly = true)
	public TaskEventListResponse getTaskEvents(
			Long taskId,
			AgentPrincipal agent,
			TaskEventListRequest request) {
		
		validateAgent(agent);
		
		Task task = findTask(taskId);
		validateTeam(task.getTeamId(),agent);
		
		int limit = request.limit();
		Pageable pageable = PageRequest.of(0, limit+1);
		
		List<TaskEvent> fetched;
		
		if(request.beforeSequence() == null) {
			fetched = taskEventRepository.findLatestEvents(taskId, pageable);
		}else {
			Long beforeSequence;
			try {
				beforeSequence = Long.valueOf(request.beforeSequence());
			}catch(NumberFormatException e) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
			fetched = taskEventRepository.findEventsBefore(taskId, beforeSequence, pageable);
		}
		boolean hasNext = fetched.size()>limit;
		
		List<TaskEvent> selected = new ArrayList<>(
				fetched.subList(0, Math.min(limit, fetched.size())));
		
		Collections.reverse(selected);
		
		String nextBeforeSequence = hasNext?selected.getFirst().getSequence().toString():null;

		return new TaskEventListResponse(
				taskId,
				toEventResponses(selected),
				nextBeforeSequence,
				hasNext);
	}
	
	private List<TaskEventResponse> findTaskEvents(Long taskId) {
		
		var events = taskEventRepository.findByTaskIdOrderBySequenceAsc(taskId);
		
		return toEventResponses(events);
		
	}
	
	private List<TaskEventResponse> toEventResponses(List<TaskEvent> events){
		if(events.isEmpty()) {
			return List.of();
		}
		//작성자가 있는 이벤트에서 에이전트 ID를 중복 없이 수집
		Set<Long> authorAgentIds = new HashSet<>();
		
		for(var event: events) {
			if(event.getAuthorAgentId() != null) {
				authorAgentIds.add(event.getAuthorAgentId());
			}
		}
		
		Map<Long,Agent> authorsById = new HashMap<>();
		
		if(!authorAgentIds.isEmpty()) {
			for(Agent author:agentRepository.findAllById(authorAgentIds)) {
				authorsById.put(author.getId(), author);
			}
		}
		
		return events.stream()
				.map(event ->{
					Long authorAgentId = event.getAuthorAgentId();
					
					Agent author = authorAgentId == null?null:authorsById.get(authorAgentId);
					
					TaskAgentResponse authorAgent = author == null?null
							:new TaskAgentResponse(author.getId(),author.getName());
					
					Long questionEventId = event.getQuestionEventId();
					
					return new TaskEventResponse(
							event.getId().toString(),
							event.getSequence().toString(),
							event.getEventType(),
							authorAgent,
							event.getContent(),
							questionEventId == null?null
									: questionEventId.toString(),
							event.getCreatedAt());
					
				}).toList();
	}
	
	private Task findTask(Long taskId) {
	    if (taskId == null || taskId <= 0) {
	        throw new BusinessException(
	                ErrorCode.VALIDATION_FAILED
	        );
	    }

	    return taskRepository.findById(taskId)
	            .orElseThrow(() -> new BusinessException(
	                    ErrorCode.RESOURCE_NOT_FOUND
	            ));
	}
	
	private TaskListResponse toListResponse(Page<Task> taskPage,
			Function<Task,String> summaryResolver) {
	    List<Task> tasks = taskPage.getContent();

	    Map<Long, Agent> agentsById = findAgents(tasks);
	    Map<Long,LocalDateTime> lastActivityTimes = findLastActivityTimes(tasks);

	    List<TaskListItemResponse> items = new ArrayList<>();

	    for (Task task : tasks) {
	        Agent requesterAgent = agentsById.get(
	                task.getRequesterAgentId()
	        );

	        Agent receiverAgent = agentsById.get(
	                task.getReceiverAgentId()
	        );

	        if (requesterAgent == null || receiverAgent == null) {
	            throw new BusinessException(
	                    ErrorCode.INTERNAL_SERVER_ERROR
	            );
	        }

	        items.add(toListItem(
	                task,
	                requesterAgent,
	                receiverAgent,
	                lastActivityTimes.get(task.getId()),
	                summaryResolver.apply(task)
	        ));
	    }

	    return new TaskListResponse(
	            items,
	            taskPage.getNumber(),
	            taskPage.getSize(),
	            taskPage.getTotalElements(),
	            taskPage.getTotalPages(),
	            taskPage.hasNext()
	    );
	}

	private Map<Long,Agent> findAgents(List<Task> tasks){
		if(tasks.isEmpty()) {
			return Map.of();
		}
		Set<Long> agentIds = new HashSet<>();
		
		for(Task task : tasks) {
			agentIds.add(task.getRequesterAgentId());
			agentIds.add(task.getReceiverAgentId());
		}
		Map<Long,Agent> agentsById = new HashMap<>();
		
		for(Agent agent:agentRepository.findAllById(agentIds)) {
			agentsById.put(agent.getId(), agent);
		}
		return agentsById;
	}
	
	private TaskListItemResponse toListItem(Task task,
			Agent requesterAgent,
			Agent receiverAgent,
			LocalDateTime lastActivityAt,
			String taskSummary) {
		return new TaskListItemResponse(task.getId(),
				task.getTeamId(),
				taskSummary,
				new TaskAgentResponse(
						requesterAgent.getId(),requesterAgent.getName()),
				new TaskAgentResponse(
						receiverAgent.getId(),receiverAgent.getName()),
				task.getStatus(),
				task.getCreatedAt(),
				task.getStartedAt(),
				task.getCompletedAt(),
				lastActivityAt);
	}
	
	private Page<Task> findTasks(Long teamId,AgentPrincipal agent,TaskListRequest request){
		Pageable pageable = PageRequest.of(request.page(), request.size());
		if("team".equals(request.scope())) {
			return taskRepository.findTeamTasks(teamId, request.status(), pageable);
		}
		return taskRepository.findAgentTasks(
				teamId,
				List.of(agent.agentId()),
				request.direction(),
				request.status(),
				pageable
				);
	}

	// 팀 전체 조회에서는 direction=all만 허용
	private void validateConditions(TaskListRequest request) {
		if("team".equals(request.scope())
				&& !"all".equals(request.direction())) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}

	private void validateTeam(Long teamId, AgentPrincipal agent) {
		//teamId 형식 확인.
		if(teamId == null || teamId <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		//요청한 팀과 호출 에이전트의 팀이 같은지 확인
		if(teamId.longValue() != agent.teamId()) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		//팀 존재 여부 확인
		if(!teamRepository.existsById(teamId)) {
			throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		
		//사용자가 해당 팀의 팀원인지 확인
		if(!teamMemberRepository.existsByTeamIdAndUserId(teamId, agent.ownerUserId())) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
	}

	private void validateAgent(AgentPrincipal agent) {
		if(agent == null || agent.agentId()<=0 || agent.ownerUserId()<=0 || agent.teamId() <= 0) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
	}

}
