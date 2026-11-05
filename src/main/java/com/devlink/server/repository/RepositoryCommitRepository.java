package com.devlink.server.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import com.devlink.server.domain.gitrepo.RepositoryCommit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositoryCommitRepository extends JpaRepository<RepositoryCommit, Long> {
    List<RepositoryCommit> findByTeamIdAndRepoIdAndCommitShaIn(Long teamId, Long repoId, Collection<String> commitShas);

    List<RepositoryCommit> findByTeamIdAndCommittedAtAfter(Long teamId, Instant since);

    List<RepositoryCommit> findByTeamIdAndAuthorUserIdAndCommittedAtAfter(Long teamId, Long authorUserId, Instant since);

    boolean existsByTeamId(Long teamId);
}
