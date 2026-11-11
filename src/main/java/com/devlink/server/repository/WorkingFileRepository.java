package com.devlink.server.repository;

import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import com.devlink.server.domain.gitrepo.WorkingFile;
import com.devlink.server.domain.gitrepo.WorkingFileId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkingFileRepository extends JpaRepository<WorkingFile, WorkingFileId> {
    List<WorkingFile> findByIdRepoIdInAndExpiresAtAfter(Collection<Long> repoIds, Instant now);

    List<WorkingFile> findByIdRepoIdAndExpiresAtAfter(Long repoId, Instant now);

    /** 만료 여부와 관계없이 보존 중인 관측 전체. 작업 관측 상태(FRESH/STALE) 판정에 사용한다. */
    List<WorkingFile> findByIdRepoIdInAndIdUserIdIn(Collection<Long> repoIds, Collection<Long> userIds);

    boolean existsByIdRepoIdIn(Collection<Long> repoIds);

    long deleteByExpiresAtBefore(Instant threshold);
    @Modifying
    @Query(value = """
            INSERT INTO working_files (
                repo_id,
                user_id,
                branch_name,
                files,
                observed_at,
                expires_at
            )
            VALUES (
                :repoId,
                :userId,
                :branchName,
                CAST(:filesJson AS jsonb),
                :observedAt,
                :expiresAt
            )
            ON CONFLICT (repo_id, user_id)
            DO UPDATE SET
                branch_name = EXCLUDED.branch_name,
                files = EXCLUDED.files,
                observed_at = EXCLUDED.observed_at,
                expires_at = EXCLUDED.expires_at
            """, nativeQuery = true)
    int upsert(
            @Param("repoId") Long repoId,
            @Param("userId") Long userId,
            @Param("branchName") String branchName,
            @Param("filesJson") String filesJson,
            @Param("observedAt") Instant observedAt,
            @Param("expiresAt") Instant expiresAt);
}
