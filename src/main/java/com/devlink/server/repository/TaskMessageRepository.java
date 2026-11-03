package com.devlink.server.repository;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.task.TaskMessageItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class TaskMessageRepository {
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;

    public TaskMessageRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
    }

    public List<TaskMessageItem> findReceived(AgentPrincipal agent, Map<Long, Long> positions,
            long afterTaskId, int limit) {
        // Task마다 실제 반환한 마지막 순서만 진행한다. 다른 Task가 먼저 커밋돼도
        // 아직 반환하지 않은 Task의 위치는 그대로라 늦게 커밋된 작은 ID를 놓치지 않는다.
        String sql = """
                SELECT event.id, event.task_id, event.sequence, event.event_type, event.created_at
                FROM task_events event JOIN tasks task ON task.id = event.task_id
                WHERE task.team_id = :teamId
                  AND task.requester_agent_id <> task.receiver_agent_id
                  AND ((task.requester_agent_id = :agentId AND event.author_agent_id = task.receiver_agent_id)
                    OR (task.receiver_agent_id = :agentId AND event.author_agent_id = task.requester_agent_id))
                  AND event.event_type IN ('QUESTION', 'ANSWER')
                  AND event.sequence > coalesce(
                    (CAST(:positions AS jsonb) ->> CAST(event.task_id AS text))::bigint, 0)
                ORDER BY CASE WHEN event.task_id > :afterTaskId THEN 0 ELSE 1 END,
                    event.task_id, event.sequence
                LIMIT :limit
                """;
        String encoded;
        try {
            encoded = json.writeValueAsString(positions);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Task별 동기화 위치를 변환할 수 없습니다.", exception);
        }
        var parameters = new MapSqlParameterSource()
                .addValue("teamId", agent.teamId()).addValue("agentId", agent.agentId())
                .addValue("positions", encoded).addValue("afterTaskId", afterTaskId).addValue("limit", limit);
        // Task를 순환해 한 Task의 연속 메시지가 다른 Task의 복구를 막지 않게 한다.
        return named.query(sql, parameters, (row, index) -> new TaskMessageItem(
                Long.toString(row.getLong("id")), row.getLong("task_id"),
                Long.toString(row.getLong("sequence")), row.getString("event_type"),
                row.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC)));
    }
}
