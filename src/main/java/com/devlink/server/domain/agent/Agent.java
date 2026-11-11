package com.devlink.server.domain.agent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Bounded context: identity.
@Entity
@EntityListeners(com.devlink.server.realtime.WebEventEntityListener.class)
@Table(name = "agents", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agents_connection_token_hash", columnNames = {"connection_token_hash"}),
        @UniqueConstraint(name = "uk_agents_team_name_tool_type", columnNames = {"team_id", "name", "tool_type"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Agent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", length = 255, nullable = false)
    private Long id;

    @Column(name = "team_id", length = 255, nullable = false)
    private Long teamId;

    @Column(name = "owner_user_id", length = 255, nullable = false)
    private Long ownerUserId;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "role_description", length = 255, nullable = true)
    private String roleDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "tool_type", length = 40, nullable = false)
    private ToolType toolType;

    @Column(name = "project_name", length = 120, nullable = false)
    private String projectName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "connection_token_hash", length = 64, nullable = true, columnDefinition = "CHAR(64)")
    private String connectionTokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AgentStatus status;

    @Column(name = "last_seen_at", length = 255, nullable = true)
    private LocalDateTime lastSeenAt;

    // 3차 MVP 담당 정보. 미지정은 OTHER, 태그 없음은 빈 배열이다.
    @Enumerated(EnumType.STRING)
    @Column(name = "part", length = 16, nullable = false)
    private AgentPart part;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "roles", nullable = false, columnDefinition = "JSONB")
    private List<String> roles;

    @Column(name = "created_at", length = 255, nullable = false)
    private LocalDateTime createdAt;

    public static Agent create(Long teamId, Long ownerUserId, String name, String roleDescription, ToolType toolType, String projectName) {
        return create(teamId, ownerUserId, name, roleDescription, toolType, projectName, null, null);
    }

    /** part가 null이면 OTHER, roles가 null이면 빈 배열로 저장한다. 태그 문자열·개수 제한은 서비스에서 검증한다. */
    public static Agent create(Long teamId, Long ownerUserId, String name, String roleDescription, ToolType toolType,
            String projectName, AgentPart part, List<String> roles) {
        requireValue(teamId, "teamId");
        requireValue(ownerUserId, "ownerUserId");
        requireValue(name, "name");
        requireValue(toolType, "toolType");
        requireValue(projectName, "projectName");
        Agent entity = new Agent();
        entity.teamId = teamId;
        entity.ownerUserId = ownerUserId;
        entity.name = name;
        entity.roleDescription = roleDescription;
        entity.toolType = toolType;
        entity.projectName = projectName;
        entity.status = AgentStatus.OFFLINE;
        entity.part = part == null ? AgentPart.OTHER : part;
        entity.roles = roles == null ? new ArrayList<>() : new ArrayList<>(roles);
        return entity;
    }

    public List<String> getRoles() {
        return List.copyOf(roles);
    }

    /** null은 기존 값을 유지한다. roles의 빈 배열은 태그 삭제다. */
    public void updateRoutingMetadata(AgentPart part, List<String> roles) {
        if (part != null) {
            this.part = part;
        }
        if (roles != null) {
            this.roles = new ArrayList<>(roles);
        }
    }

    public void updateProfile(String name, String roleDescription, String projectName) {
        if (name != null) {
            requireValue(name, "name");
            this.name = name;
        }
        if (roleDescription != null) {
            this.roleDescription = roleDescription.isBlank() ? null : roleDescription;
        }
        if (projectName != null) {
            requireValue(projectName, "projectName");
            this.projectName = projectName;
        }
    }

    public void markOnline(LocalDateTime seenAt) {
        requireValue(seenAt, "seenAt");
        this.status = AgentStatus.ONLINE;
        this.lastSeenAt = seenAt;
    }

    public void markOffline() {
        this.status = AgentStatus.OFFLINE;
    }

    public void issueConnectionToken(String tokenHash) {
        requireValue(tokenHash, "tokenHash");
        if (tokenHash.length() != 64) {
            throw new IllegalArgumentException("tokenHash must contain 64 characters");
        }
        this.connectionTokenHash = tokenHash;
    }

    @PrePersist
    private void initializeTimestamp() {
        this.createdAt = LocalDateTime.now();
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
