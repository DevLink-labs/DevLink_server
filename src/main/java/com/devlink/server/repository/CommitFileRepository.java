package com.devlink.server.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import com.devlink.server.domain.gitrepo.CommitFile;
import com.devlink.server.domain.gitrepo.CommitFileId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommitFileRepository extends JpaRepository<CommitFile, CommitFileId> {
    List<CommitFile> findByIdCommitIdIn(Collection<Long> commitIds);

    List<CommitFile> findByIdRelativePathIn(Collection<String> relativePaths);

    /** 추천 기간 안에서 확인된 작성자가 변경한 팀 저장소별 고유 경로. 질문과의 경로 매칭 후보다. */
    @Query("""
            select distinct c.repoId as repoId, cf.id.relativePath as relativePath
            from CommitFile cf
            join RepositoryCommit c on cf.id.commitId = c.id
            where c.teamId = :teamId
              and c.authorUserId is not null
              and c.committedAt >= :from
              and c.committedAt <= :to
            """)
    List<CommitPathView> findTeamPathsInWindow(
            @Param("teamId") Long teamId, @Param("from") Instant from, @Param("to") Instant to);

    /** 관련 경로를 변경한 후보 사용자들의 커밋 근거. 같은 상대 경로라도 저장소가 다르면 호출자가 걸러낸다. */
    @Query("""
            select c.repoId as repoId, c.commitSha as commitSha, c.committedAt as committedAt,
                   c.authorUserId as authorUserId, cf.id.relativePath as relativePath
            from CommitFile cf
            join RepositoryCommit c on cf.id.commitId = c.id
            where c.teamId = :teamId
              and c.authorUserId in :userIds
              and c.committedAt >= :from
              and c.committedAt <= :to
              and cf.id.relativePath in :paths
            """)
    List<CommitEvidenceView> findEvidenceInWindow(
            @Param("teamId") Long teamId, @Param("userIds") Collection<Long> userIds,
            @Param("from") Instant from, @Param("to") Instant to, @Param("paths") Collection<String> paths);

    interface CommitPathView {
        Long getRepoId();

        String getRelativePath();
    }

    interface CommitEvidenceView {
        Long getRepoId();

        String getCommitSha();

        Instant getCommittedAt();

        Long getAuthorUserId();

        String getRelativePath();
    }
}
