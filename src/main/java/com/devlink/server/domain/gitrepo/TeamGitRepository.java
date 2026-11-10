package com.devlink.server.domain.gitrepo;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Bounded context: git.
/** 팀에 등록된 Git 저장소. 최초 유효 업로드에서 생성한다. */
@Entity
@Table(name = "team_repositories", uniqueConstraints = {
        @UniqueConstraint(name = "uk_team_repositories_origin", columnNames = {"team_id", "origin_url_sha256"}),
        @UniqueConstraint(name = "uk_team_repositories_team_and_id", columnNames = {"team_id", "id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamGitRepository {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "origin_url_sha256", length = 64, nullable = false, columnDefinition = "CHAR(64)")
    private String originUrlSha256;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static TeamGitRepository create(Long teamId, String originUrlSha256) {
        requireValue(teamId, "teamId");
        requireValue(originUrlSha256, "originUrlSha256");
        if (originUrlSha256.length() != 64) {
            throw new IllegalArgumentException("originUrlSha256 must contain 64 characters");
        }
        TeamGitRepository entity = new TeamGitRepository();
        entity.teamId = teamId;
        entity.originUrlSha256 = originUrlSha256;
        return entity;
    }

    @PrePersist
    private void initializeTimestamp() {
        this.createdAt = Instant.now();
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
