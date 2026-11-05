package com.devlink.server.domain.gitrepo;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: git.
/** 4차 작업 영향 그래프용 예약 엔티티. 3차에서는 수집·API·점수에 사용하지 않는다. */
@Entity
@Table(name = "file_dependencies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FileDependency {
    @EmbeddedId
    private FileDependencyId id;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    public static FileDependency of(Long repoId, String analysisCommitSha, String sourceRelativePath,
            String targetRelativePath, DependencyRelationType relationType, Instant observedAt) {
        requireValue(repoId, "repoId");
        requireValue(analysisCommitSha, "analysisCommitSha");
        requireValue(sourceRelativePath, "sourceRelativePath");
        requireValue(targetRelativePath, "targetRelativePath");
        requireValue(relationType, "relationType");
        requireValue(observedAt, "observedAt");
        FileDependency entity = new FileDependency();
        entity.id = new FileDependencyId(repoId, analysisCommitSha, sourceRelativePath, targetRelativePath, relationType);
        entity.observedAt = observedAt;
        return entity;
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
