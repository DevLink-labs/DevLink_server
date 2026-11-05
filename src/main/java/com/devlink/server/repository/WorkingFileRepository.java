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

    long deleteByExpiresAtBefore(Instant threshold);
}
