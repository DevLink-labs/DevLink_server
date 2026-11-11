package com.devlink.server.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlink.server.domain.gitrepo.WorkingFileId;
import com.devlink.server.domain.gitrepo.WorkingFileRevision;

public interface WorkingFileRevisionRepository extends JpaRepository<WorkingFileRevision, WorkingFileId>{

	@Modifying
	@Query(value = """
            INSERT INTO working_file_revisions (
            repo_id,
            user_id,
            revision
        )
        VALUES (
            :repoId,
            :userId,
            :revision
        )
        ON CONFLICT (repo_id, user_id) DO NOTHING
        """, nativeQuery = true)
	int insertIfAbsent(
			@Param("repoId") Long repoId,
			@Param("userId") Long userId,
			@Param("revision") UUID revision);
}
