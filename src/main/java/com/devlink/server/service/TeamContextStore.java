package com.devlink.server.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.devlink.server.config.TeamContextSettings;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.teamcontext.TeamPartContext;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.exception.InvalidTeamContextEventException;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.Hints;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.NewEvent;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.QuestionRef;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamPartContextRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 메시지를 추론 입력으로 바꾸고(읽기 트랜잭션), 추론 결과를 다음 버전으로 저장한다(쓰기 트랜잭션).
 * 두 트랜잭션 사이에서 추론이 일어나므로 저장 시점에 최신 버전을 다시 읽어 중복 반영을 막는다.
 */
@Service
@RequiredArgsConstructor
public class TeamContextStore {
    private static final int SUMMARY_CHARS = 300;

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final AgentRepository agents;
    private final TeamPartContextRepository contexts;
    private final TeamContextExtractor extractor;
    private final TeamContextSettings settings;
    private final ObjectMapper mapper;

    /** 추론에 필요한 모든 값. 저장 시 쌍과 이벤트 ID만 다시 쓴다. */
    public record Prepared(TeamContextInferenceRequest request, AgentPart partA, AgentPart partB, Long eventId) { }

    /** 이미 반영된 이벤트면 비어 있다. 가리키는 작업·이벤트·Agent가 없거나 서로 맞지 않으면 영구 실패다. */
    @Transactional(readOnly = true)
    public Optional<Prepared> prepare(OutboxDelivery delivery) {
        JsonNode message = delivery.message();
        long teamId = message.path("team_id").asLong(0);
        long taskId = message.path("task_id").asLong(0);
        long eventId = message.path("payload").path("task_event_id").asLong(0);
        if (teamId <= 0 || taskId <= 0 || eventId <= 0) {
            throw new InvalidTeamContextEventException("team-context message lacks team_id, task_id or task_event_id");
        }
        Task task = tasks.findById(taskId)
                .filter(found -> found.getTeamId().equals(teamId))
                .orElseThrow(() -> new InvalidTeamContextEventException("task " + taskId + " not found in team " + teamId));
        TaskEvent event = events.findById(eventId)
                .filter(found -> found.getTaskId().equals(taskId))
                .orElseThrow(() -> new InvalidTeamContextEventException("event " + eventId + " not found in task " + taskId));
        JsonNode payload = message.path("payload");
        AgentPart requesterPart = part(payload, "requester_part", task.getRequesterAgentId());
        AgentPart receiverPart = part(payload, "receiver_part", task.getReceiverAgentId());
        AgentPart[] pair = TeamPartContext.pair(requesterPart, receiverPart);
        if (contexts.findByTeamIdAndPartAAndPartBAndLastEventId(teamId, pair[0], pair[1], eventId).isPresent()) {
            return Optional.empty();
        }
        Optional<TeamPartContext> latest = contexts.findFirstByTeamIdAndPartAAndPartBOrderByVersionDesc(
                teamId, pair[0], pair[1]);
        TeamContextSummary previous = latest.map(this::toSummary).orElse(TeamContextSummary.empty());

        List<TaskEvent> history = events.findByTaskIdOrderBySequenceAsc(taskId);
        Set<Long> answered = new HashSet<>();
        int questionCount = 0;
        int answerCount = 0;
        for (TaskEvent item : history) {
            if (item.getEventType() == TaskEventType.ANSWER) {
                answerCount++;
                answered.add(item.getQuestionEventId());
            } else if (item.getEventType() == TaskEventType.QUESTION) {
                questionCount++;
            }
        }
        List<QuestionRef> unanswered = new ArrayList<>();
        for (TaskEvent item : history) {
            if (item.getEventType() == TaskEventType.QUESTION && !answered.contains(item.getId())) {
                unanswered.add(new QuestionRef(reference(taskId, item.getId()),
                        authorPart(item, task, requesterPart, receiverPart), clip(item.getContent(), SUMMARY_CHARS)));
            }
        }
        String text = eventText(task, event);
        List<String> entities = extractor.extract(task.getTaskSummary(), text);
        boolean failedOrRejected = task.getStatus() == TaskStatus.FAILED || task.getStatus() == TaskStatus.REJECTED;
        NewEvent newEvent = new NewEvent(taskId, eventId, reference(taskId, eventId), event.getEventType().name(),
                authorPart(event, task, requesterPart, receiverPart), task.getStatus().name(),
                clip(task.getTaskSummary(), SUMMARY_CHARS), text);
        TeamContextInferenceRequest request = new TeamContextInferenceRequest(teamId, pair[0], pair[1],
                latest.map(TeamPartContext::getVersion).orElse(0L), previous, newEvent,
                new Hints(entities, unanswered, questionCount, answerCount, failedOrRejected));
        return Optional.of(new Prepared(request, pair[0], pair[1], eventId));
    }

    /** 이벤트 ID로 중복을 판정하고, 오래된 요약을 기반으로 한 추론은 재시도한다. */
    @Transactional
    public TeamPartContext save(Prepared prepared, TeamContextSummary summary, String model) {
        Long teamId = prepared.request().teamId();
        var applied = contexts.findByTeamIdAndPartAAndPartBAndLastEventId(
                teamId, prepared.partA(), prepared.partB(), prepared.eventId());
        if (applied.isPresent()) {
            return applied.get();
        }
        long previousVersion = contexts.findFirstByTeamIdAndPartAAndPartBOrderByVersionDesc(
                teamId, prepared.partA(), prepared.partB()).map(TeamPartContext::getVersion).orElse(0L);
        if (previousVersion != prepared.request().previousVersion()) {
            throw new OptimisticLockingFailureException("Team context changed during inference");
        }
        TeamContextSummary normalized = summary.normalized(settings.maxItems(), settings.maxInputChars());
        return contexts.save(TeamPartContext.create(teamId, prepared.partA(), prepared.partB(), previousVersion + 1,
                mapper.valueToTree(normalized.common()), mapper.valueToTree(normalized.changed()),
                mapper.valueToTree(normalized.issues()), prepared.eventId(), model, settings.promptVersion()));
    }

    public TeamContextSummary toSummary(TeamPartContext context) {
        return new TeamContextSummary(
                mapper.convertValue(context.getCommon(), new TypeReference<List<TeamContextSummary.Common>>() { }),
                mapper.convertValue(context.getChanged(), new TypeReference<List<TeamContextSummary.Changed>>() { }),
                mapper.convertValue(context.getIssues(), new TypeReference<List<TeamContextSummary.Issue>>() { }));
    }

    private String eventText(Task task, TaskEvent event) {
        int limit = settings.maxInputChars();
        return switch (event.getEventType()) {
            case CREATED -> clip(task.getPrompt(), limit);
            case STATUS_CHANGED -> {
                String content = clip(event.getContent(), limit);
                if (task.getStatus() == TaskStatus.COMPLETED && task.getResultText() != null) {
                    String result = clip(task.getResultText(), limit);
                    yield content == null ? result : content + "\n결과: " + result;
                }
                yield content;
            }
            default -> clip(event.getContent(), limit);
        };
    }

    private static AgentPart authorPart(TaskEvent event, Task task, AgentPart requester, AgentPart receiver) {
        return switch (event.getEventType()) {
            case CREATED -> requester;
            case QUESTION, ANSWER -> task.getRequesterAgentId().equals(event.getAuthorAgentId())
                    ? requester : receiver;
            default -> receiver;
        };
    }

    private AgentPart part(JsonNode payload, String field, Long agentId) {
        if (!payload.has(field)) {
            // 이전 형식의 Outbox 행은 파트 스냅샷이 없다.
            return agents.findById(agentId).map(Agent::getPart)
                    .orElseThrow(() -> new InvalidTeamContextEventException("agent " + agentId + " missing"));
        }
        try {
            return AgentPart.valueOf(payload.path(field).asText());
        } catch (IllegalArgumentException exception) {
            throw new InvalidTeamContextEventException("invalid " + field + " in team-context message");
        }
    }

    private static String reference(long taskId, long eventId) {
        return taskId + ":" + eventId;
    }

    private static String clip(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.codePointCount(0, trimmed.length()) <= maxChars
                ? trimmed
                : trimmed.substring(0, trimmed.offsetByCodePoints(0, maxChars));
    }
}
