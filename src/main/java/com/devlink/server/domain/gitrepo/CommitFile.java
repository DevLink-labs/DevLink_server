package com.devlink.server.domain.gitrepo;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: git.
/** 커밋에 포함된 파일. 생성 후 변경하지 않는다. */
@Entity
@Table(name = "commit_files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommitFile {
    @EmbeddedId
    private CommitFileId id;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", length = 16, nullable = false)
    private CommitChangeType changeType;

    public static CommitFile of(Long commitId, String relativePath, CommitChangeType changeType) {
        requireValue(commitId, "commitId");
        requireValue(relativePath, "relativePath");
        requireValue(changeType, "changeType");
        if (relativePath.length() > 500) {
            throw new IllegalArgumentException("relativePath must be at most 500 characters");
        }
        CommitFile entity = new CommitFile();
        entity.id = new CommitFileId(commitId, relativePath);
        entity.changeType = changeType;
        return entity;
    }

    public Long getCommitId() {
        return id.getCommitId();
    }

    public String getRelativePath() {
        return id.getRelativePath();
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
