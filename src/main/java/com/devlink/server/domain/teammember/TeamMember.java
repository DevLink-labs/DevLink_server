package com.devlink.server.domain.teammember;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: identity.
@Entity
@EntityListeners(com.devlink.server.realtime.WebEventEntityListener.class)
@Table(name = "team_members", uniqueConstraints = @UniqueConstraint(name = "uk_team_members", columnNames = {"team_id", "user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", length = 255, nullable = false)
    private Long id;

    @Column(name = "team_id", length = 255, nullable = false)
    private Long teamId;

    @Column(name = "user_id", length = 255, nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private MemberRole role;

    @Column(name = "joined_at", length = 255, nullable = false)
    private LocalDateTime joinedAt;

    public static TeamMember create(Long teamId, Long userId, MemberRole role) {
        requireValue(teamId, "teamId");
        requireValue(userId, "userId");
        requireValue(role, "role");
        TeamMember entity = new TeamMember();
        entity.teamId = teamId;
        entity.userId = userId;
        entity.role = role;
        return entity;
    }

    @PrePersist
    private void initializeTimestamp() {
        this.joinedAt = LocalDateTime.now();
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
