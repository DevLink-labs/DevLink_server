package com.devlink.server.repository;

import java.time.Instant;
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
}
