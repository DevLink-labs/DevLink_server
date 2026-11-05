package com.devlink.server.domain.gitrepo;

import java.io.Serializable;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FileDependencyId implements Serializable {
    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "analysis_commit_sha", length = 64, nullable = false)
    private String analysisCommitSha;

    @Column(name = "source_relative_path", length = 500, nullable = false)
    private String sourceRelativePath;

    @Column(name = "target_relative_path", length = 500, nullable = false)
    private String targetRelativePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", length = 16, nullable = false)
    private DependencyRelationType relationType;

    public FileDependencyId(Long repoId, String analysisCommitSha, String sourceRelativePath,
            String targetRelativePath, DependencyRelationType relationType) {
        this.repoId = Objects.requireNonNull(repoId, "repoId must not be null");
        this.analysisCommitSha = Objects.requireNonNull(analysisCommitSha, "analysisCommitSha must not be null");
        this.sourceRelativePath = Objects.requireNonNull(sourceRelativePath, "sourceRelativePath must not be null");
        this.targetRelativePath = Objects.requireNonNull(targetRelativePath, "targetRelativePath must not be null");
        this.relationType = Objects.requireNonNull(relationType, "relationType must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileDependencyId that)) return false;
        return repoId.equals(that.repoId)
                && analysisCommitSha.equals(that.analysisCommitSha)
                && sourceRelativePath.equals(that.sourceRelativePath)
                && targetRelativePath.equals(that.targetRelativePath)
                && relationType == that.relationType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(repoId, analysisCommitSha, sourceRelativePath, targetRelativePath, relationType);
    }
}
