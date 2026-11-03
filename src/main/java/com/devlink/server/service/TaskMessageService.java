package com.devlink.server.service;

import java.util.Objects;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.task.TaskAnswerRequest;
import com.devlink.server.dto.task.TaskMessageResponse;
import com.devlink.server.dto.task.TaskQuestionRequest;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskMessageService {
    private static final int MAX_MESSAGE_CODE_POINTS = 8_000;

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final AgentRepository agents;
    private final TeamAuthorizationService authorization;
    private final TaskMessageOutboxWriter outbox;

    @Transactional
    public TaskMessageResponse createQuestion(Long taskId, long authorId, TaskQuestionRequest request) {
        return create(taskId, authorId, request.requestKey(), request.message(), TaskEventType.QUESTION, null);
    }

    @Transactional
    public TaskMessageResponse createAnswer(Long taskId, long authorId, TaskAnswerRequest request) {
        long questionEventId;
        try {
            questionEventId = Long.parseLong(request.questionEventId());
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return create(taskId, authorId, request.requestKey(), request.message(), TaskEventType.ANSWER, questionEventId);
    }

    private TaskMessageResponse create(Long taskId, long authorId, String requestKey, String message,
            TaskEventType type, Long questionEventId) {
        // 1) 입력 검증 agentId와 본문 길이 확인
        if (authorId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }

        if (message.codePointCount(0, message.length()) > MAX_MESSAGE_CODE_POINTS || message.indexOf('\0') >= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        // 2) Task를 잠근 뒤 Agent를 조회
        // 2-1) 같은 팀의 멤버이자 Task 참여자인지 확인
        var task = tasks.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        var author = agents.findById(authorId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED));
        boolean requester = task.getRequesterAgentId().equals(authorId);
        if (!task.getTeamId().equals(author.getTeamId())
                || (!requester && !task.getReceiverAgentId().equals(authorId))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        // 2-2) 자기 배정 Task는 409로 거부
        authorization.requireTeamMember(author.getOwnerUserId(), task.getTeamId());
        if (task.getRequesterAgentId().equals(task.getReceiverAgentId())) {
            throw new BusinessException(ErrorCode.TASK_CONVERSATION_UNAVAILABLE);
        }

        // 3) 멱등 키 확인
        // 같은 요청을 재시도한 경우 Task가 종료됐어도 기존 이벤트 반환
        var existing = events.findByTaskIdAndAuthorAgentIdAndRequestKey(taskId, authorId, requestKey);
        if (existing.isPresent()) {
            TaskEvent event = existing.get();
            if (event.getEventType() != type || !message.equals(event.getContent())
                    || !Objects.equals(questionEventId, event.getQuestionEventId())) {
                throw new BusinessException(ErrorCode.REQUEST_KEY_CONFLICT);
            }
            return TaskMessageResponse.from(event, author.getName(), true);
        }

        // 4) 신규 이벤트만 상태와 답변 대상을 검증한 뒤 저장
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.TASK_STATE_CONFLICT);
        }
        if (questionEventId != null) {
            validateQuestion(taskId, questionEventId, authorId);
        }
        TaskEvent event = events.saveAndFlush(
                TaskEvent.of(taskId, type, message, authorId, questionEventId, requestKey));
        Long receiverId = requester ? task.getReceiverAgentId() : task.getRequesterAgentId();
        outbox.enqueue(event, receiverId);
        return TaskMessageResponse.from(event, author.getName(), false);
    }

    private void validateQuestion(Long taskId, Long questionEventId, long authorId) {
        TaskEvent question = events.findById(questionEventId)
                .filter(event -> taskId.equals(event.getTaskId()) && event.getEventType() == TaskEventType.QUESTION)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_QUESTION_REFERENCE));
        if (Objects.equals(question.getAuthorAgentId(), authorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }
}
