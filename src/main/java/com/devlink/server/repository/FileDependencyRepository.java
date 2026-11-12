package com.devlink.server.repository;

import com.devlink.server.domain.gitrepo.FileDependency;
import com.devlink.server.domain.gitrepo.FileDependencyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

/** 담당 Agent 추천에 사용하는 import 역방향 관계를 조회한다. */
public interface FileDependencyRepository extends JpaRepository<FileDependency, FileDependencyId> {
    @Query(value = """
            select distinct fd.source_relative_path as "sourcePath", fd.target_relative_path as "targetPath"
            from file_dependencies fd
            join team_repositories r on r.id = fd.repo_id
            join repository_dependency_graphs g on g.repo_id = fd.repo_id
                and g.analysis_commit_sha = fd.analysis_commit_sha
            where r.team_id = :teamId and fd.repo_id = :repoId
                and fd.target_relative_path in (:paths) and fd.relation_type = 'IMPORT'
            order by fd.source_relative_path, fd.target_relative_path
            """, nativeQuery = true)
    List<ImportingFileView> findImportingFiles(@Param("teamId") Long teamId,
            @Param("repoId") Long repoId, @Param("paths") Collection<String> paths);

    interface ImportingFileView {
        String getSourcePath();
        String getTargetPath();
    }

}
