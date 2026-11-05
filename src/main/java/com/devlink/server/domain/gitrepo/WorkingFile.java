package com.devlink.server.domain.gitrepo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Bounded context: git.
/** 사용자·저장소별 현재 작업 파일 관측. 빈 파일 목록도 정상 관측으로 보존한다. 만료 시각 계산은 서비스에서 처리한다. */
@Entity
@Table(name = "working_files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkingFile {
    @EmbeddedId
    private WorkingFileId id;

    @Column(name = "branch_name", length = 255, nullable = false)
    private String branchName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "files", nullable = false, columnDefinition = "JSONB")
    private List<WorkingFileEntry> files;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public static WorkingFile observe(Long repoId, Long userId, String branchName, List<WorkingFileEntry> files,
            Instant observedAt, Instant expiresAt) {
        requireValue(repoId, "repoId");
        requireValue(userId, "userId");
        WorkingFile entity = new WorkingFile();
        entity.id = new WorkingFileId(repoId, userId);
        entity.replace(branchName, files, observedAt, expiresAt);
        return entity;
    }

    /** 새 관측으로 전체를 교체한다. files가 null이면 빈 배열로 저장한다. */
    public void replace(String branchName, List<WorkingFileEntry> files, Instant observedAt, Instant expiresAt) {
        requireValue(branchName, "branchName");
        requireValue(observedAt, "observedAt");
        requireValue(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(observedAt)) {
            throw new IllegalArgumentException("expiresAt must be after observedAt");
        }
        this.branchName = branchName;
        this.files = files == null ? new ArrayList<>() : new ArrayList<>(files);
        this.observedAt = observedAt;
        this.expiresAt = expiresAt;
    }

    public Long getRepoId() {
        return id.getRepoId();
    }

    public Long getUserId() {
        return id.getUserId();
    }

    public List<WorkingFileEntry> getFiles() {
        return List.copyOf(files);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
