package com.devlink.server.repository;

import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import com.devlink.server.domain.gitrepo.TeamGitRepository;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamGitRepositoryRepository extends JpaRepository<TeamGitRepository, Long> {
    Optional<TeamGitRepository> findByTeamIdAndOriginUrlSha256(Long teamId, String originUrlSha256);

    Optional<TeamGitRepository> findByTeamIdAndId(Long teamId, Long id);

    List<TeamGitRepository> findByTeamId(Long teamId);

    boolean existsByTeamId(Long teamId);
    
    @Modifying
    @Query(value = """
            INSERT INTO team_repositories (
                team_id,
                origin_url_sha256,
                created_at
            )
            VALUES (
                :teamId,
                :repoKey,
                CURRENT_TIMESTAMP
            )
            ON CONFLICT (team_id, origin_url_sha256) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("teamId") Long teamId,
            @Param("repoKey") String repoKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select repo
            from TeamGitRepository repo
            where repo.teamId = :teamId
              and repo.originUrlSha256 = :repoKey
            """)
    Optional<TeamGitRepository> findForUpdate(
            @Param("teamId") Long teamId,
            @Param("repoKey") String repoKey);
}
