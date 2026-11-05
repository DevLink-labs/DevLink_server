package com.devlink.server.repository;

import java.util.Collection;
import java.util.List;
import com.devlink.server.domain.gitrepo.CommitFile;
import com.devlink.server.domain.gitrepo.CommitFileId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommitFileRepository extends JpaRepository<CommitFile, CommitFileId> {
    List<CommitFile> findByIdCommitIdIn(Collection<Long> commitIds);

    List<CommitFile> findByIdRelativePathIn(Collection<String> relativePaths);
}
