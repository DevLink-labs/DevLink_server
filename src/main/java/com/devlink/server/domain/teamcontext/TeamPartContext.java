package com.devlink.server.domain.teamcontext;

import java.time.Instant;
import java.util.Objects;
import com.devlink.server.domain.agent.AgentPart;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 파트 쌍 사이의 요약 한 버전이다. 원본 작업·이벤트에서 파생한 값이며 이전 버전은 되돌리기·비교용으로 남긴다.
 * 쌍은 {@link #pair(AgentPart, AgentPart)}로 정규화한 순서로만 저장한다.
 */
@Entity
@Table(name = "team_part_contexts", uniqueConstraints = @UniqueConstraint(
        name = "uk_team_part_contexts_version", columnNames = {"team_id", "part_a", "part_b", "version"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamPartContext {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "team_id", nullable = false, updatable = false)
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "part_a", length = 16, nullable = false, updatable = false)
    private AgentPart partA;

    @Enumerated(EnumType.STRING)
    @Column(name = "part_b", length = 16, nullable = false, updatable = false)
    private AgentPart partB;

    @Column(name = "version", nullable = false, updatable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "common", nullable = false, updatable = false, columnDefinition = "JSONB")
    private JsonNode common;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed", nullable = false, updatable = false, columnDefinition = "JSONB")
    private JsonNode changed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issues", nullable = false, updatable = false, columnDefinition = "JSONB")
    private JsonNode issues;

    @Column(name = "last_event_id", nullable = false, updatable = false)
    private Long lastEventId;

    @Column(name = "model", length = 100, updatable = false)
    private String model;

    @Column(name = "prompt_version", length = 50, nullable = false, updatable = false)
    private String promptVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 두 파트를 이름 오름차순으로 정규화한다. 같은 파트끼리의 쌍도 허용한다. */
    public static AgentPart[] pair(AgentPart first, AgentPart second) {
        Objects.requireNonNull(first, "first part must not be null");
        Objects.requireNonNull(second, "second part must not be null");
        return first.name().compareTo(second.name()) <= 0
                ? new AgentPart[] {first, second}
                : new AgentPart[] {second, first};
    }

    public static TeamPartContext create(Long teamId, AgentPart first, AgentPart second, long version,
            JsonNode common, JsonNode changed, JsonNode issues, Long lastEventId, String model,
            String promptVersion) {
        Objects.requireNonNull(teamId, "teamId must not be null");
        Objects.requireNonNull(lastEventId, "lastEventId must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        requireArray(common, "common");
        requireArray(changed, "changed");
        requireArray(issues, "issues");
        if (promptVersion == null || promptVersion.isBlank() || promptVersion.length() > 50) {
            throw new IllegalArgumentException("promptVersion must not be blank or exceed 50 characters");
        }
        if (model != null && model.length() > 100) {
            throw new IllegalArgumentException("model must not exceed 100 characters");
        }
        AgentPart[] pair = pair(first, second);
        TeamPartContext entity = new TeamPartContext();
        entity.teamId = teamId;
        entity.partA = pair[0];
        entity.partB = pair[1];
        entity.version = version;
        entity.common = common.deepCopy();
        entity.changed = changed.deepCopy();
        entity.issues = issues.deepCopy();
        entity.lastEventId = lastEventId;
        entity.model = model;
        entity.promptVersion = promptVersion;
        return entity;
    }

    public JsonNode getCommon() {
        return common.deepCopy();
    }

    public JsonNode getChanged() {
        return changed.deepCopy();
    }

    public JsonNode getIssues() {
        return issues.deepCopy();
    }

    @PrePersist
    private void initializeTimestamp() {
        this.createdAt = Instant.now();
    }

    private static void requireArray(JsonNode node, String field) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(field + " must be a JSON array");
        }
    }
}
