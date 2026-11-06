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
public class CommitFileId implements Serializable {
    @Column(name = "commit_id", nullable = false)
    private Long commitId;

    @Column(name = "relative_path", length = 500, nullable = false)
    private String relativePath;

    public CommitFileId(Long commitId, String relativePath) {
        this.commitId = Objects.requireNonNull(commitId, "commitId must not be null");
        this.relativePath = Objects.requireNonNull(relativePath, "relativePath must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CommitFileId that)) return false;
        return commitId.equals(that.commitId) && relativePath.equals(that.relativePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commitId, relativePath);
    }
}
