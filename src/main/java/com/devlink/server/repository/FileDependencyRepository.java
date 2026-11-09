package com.devlink.server.repository;

import com.devlink.server.domain.gitrepo.FileDependency;
import com.devlink.server.domain.gitrepo.FileDependencyId;
import org.springframework.data.jpa.repository.JpaRepository;

/** 4차 예약. 3차에서는 조회 메서드를 두지 않는다. */
public interface FileDependencyRepository extends JpaRepository<FileDependency, FileDependencyId> {
}
