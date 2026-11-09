package com.devlink.server.repository;

import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.gitrepo.TeamGitRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamGitRepositoryRepository extends JpaRepository<TeamGitRepository, Long> {
    Optional<TeamGitRepository> findByTeamIdAndOriginUrlSha256(Long teamId, String originUrlSha256);

    Optional<TeamGitRepository> findByTeamIdAndId(Long teamId, Long id);

    List<TeamGitRepository> findByTeamId(Long teamId);

    boolean existsByTeamId(Long teamId);
}
