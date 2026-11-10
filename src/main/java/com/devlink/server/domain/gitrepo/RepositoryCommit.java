package com.devlink.server.domain.gitrepo;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: git.
/**
 * 저장소 커밋 이력.
 * 작성자 미대조 시 authorUserId는 null이며, 확인 후 한 번만 보완한다.
 * 그 외 커밋 정보는 변경하지 않는다.
 */
@Entity
@Table(name = "repository_commits", uniqueConstraints = @UniqueConstraint(name = "uk_repository_commits_sha", columnNames = {"team_id", "repo_id", "commit_sha"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryCommit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "repo_id", nullable = false)
    private Long repoId;

    @Column(name = "commit_sha", length = 64, nullable = false)
    private String commitSha;

    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    @Column(name = "author_user_id", nullable = true)
    private Long authorUserId;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    public static RepositoryCommit of(Long teamId, Long repoId, String commitSha, Instant committedAt, Long authorUserId) {
        requireValue(teamId, "teamId");
        requireValue(repoId, "repoId");
        requireValue(commitSha, "commitSha");
        requireValue(committedAt, "committedAt");
        if (commitSha.length() > 64) {
            throw new IllegalArgumentException("commitSha must be at most 64 characters");
        }
        RepositoryCommit entity = new RepositoryCommit();
        entity.teamId = teamId;
        entity.repoId = repoId;
        entity.commitSha = commitSha;
        entity.committedAt = committedAt;
        entity.authorUserId = authorUserId;
        return entity;
    }
    
    public void fillAuthor(Long authorUserId) {
    	if(authorUserId == null || authorUserId <= 0) {
    		throw new IllegalArgumentException("authorUserId must be a positive integer");
    	}
    	if(this.authorUserId != null) {
    		throw new IllegalStateException("authorUserId is already assigned");
    	}
    	this.authorUserId = authorUserId;
    }

    @PrePersist
    private void initializeTimestamp() {
        this.firstSeenAt = Instant.now();
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
