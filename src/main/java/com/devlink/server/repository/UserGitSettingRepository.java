package com.devlink.server.repository;

import java.util.Optional;
import com.devlink.server.domain.gitsetting.UserGitSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserGitSettingRepository extends JpaRepository<UserGitSetting, Long> {
    Optional<UserGitSetting> findByGitEmail(String gitEmail);

    boolean existsByGitEmailAndUserIdNot(String gitEmail, Long userId);
}
