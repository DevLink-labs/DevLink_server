package com.devlink.server.domain.gitrepo;

import java.io.Serializable;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkingFileId implements Serializable {
    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    public WorkingFileId(Long repoId, Long userId) {
        this.repoId = Objects.requireNonNull(repoId, "repoId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkingFileId that)) return false;
        return repoId.equals(that.repoId) && userId.equals(that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repoId, userId);
    }
}
