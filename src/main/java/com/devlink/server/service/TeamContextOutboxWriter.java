package com.devlink.server.service;

import java.util.Objects;
import java.util.UUID;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.teamcontext.TeamPartContext;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 생성·상태 변경·완료·질문·답변과 같은 트랜잭션에서 팀 Context 갱신 신호를 저장한다.
 * 본문은 넣지 않는다. 갱신기가 작업·이벤트를 다시 읽는다. 저장 실패는 업무 변경과 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class TeamContextOutboxWriter {
    public static final String DESTINATION = "team-context";
    public static final String TYPE = "TEAM_CONTEXT_EVENT";
    public static final String AGGREGATE_TYPE = "TEAM_CONTEXT";

    private final OutboxEventRepository outbox;
    private final AgentRepository agents;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(Task task, TaskEvent event) {
        Objects.requireNonNull(task.getId(), "task must be persisted");
        Objects.requireNonNull(event.getId(), "task event must be persisted");
        AgentPart requesterPart = partOf(task.getRequesterAgentId());
        AgentPart receiverPart = partOf(task.getReceiverAgentId());
        AgentPart[] pair = TeamPartContext.pair(requesterPart, receiverPart);
        String pairKey = "TEAM_CONTEXT:" + task.getTeamId() + ":" + pair[0] + ":" + pair[1];
        long contextOrder = outbox.nextTeamContextOrder(pairKey);
        UUID outboxId = UUID.randomUUID();
        var envelope = objectMapper.createObjectNode()
                .put("event_id", outboxId.toString())
                .put("type", TYPE)
                .put("team_id", task.getTeamId())
                .put("task_id", task.getId());
        envelope.putObject("payload")
                .put("task_event_id", event.getId())
                .put("event_type", event.getEventType().name())
                .put("requester_part", requesterPart.name())
                .put("receiver_part", receiverPart.name());
        // aggregate_type을 TASK와 분리해 작업 알림 발행기와 기존 집계 쿼리가 이 신호를 세지 않게 한다.
        outbox.save(OutboxEvent.create(outboxId, TYPE, AGGREGATE_TYPE, task.getTeamId().toString(), DESTINATION,
                pairKey, envelope, contextOrder));
        return outboxId;
    }

    private AgentPart partOf(Long agentId) {
        return agents.findById(agentId).map(Agent::getPart).orElse(AgentPart.OTHER);
    }
}
