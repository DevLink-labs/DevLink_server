package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.gitsetting.UserGitSetting;
import com.devlink.server.dto.gitidentity.AgentGitIdentityResponse;
import com.devlink.server.dto.gitidentity.GitIdentityStatusResponse;
import com.devlink.server.dto.gitidentity.GitIdentityVerificationResponse;
import com.devlink.server.repository.UserGitSettingRepository;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GitIdentityService {
    private final UserGitSettingRepository settings;

    @Transactional
    public GitIdentityStatusResponse register(Long userId, String gitEmail) {
        requireUser(userId);
        String hash = GitEmailHasher.hash(gitEmail);
        if (settings.existsByGitEmailHashAndUserIdNot(hash, userId)) {
            throw new BusinessException(ErrorCode.GIT_EMAIL_ALREADY_REGISTERED);
        }
        var existing = settings.findById(userId);
        if (existing.isPresent() && GitEmailHasher.matches(gitEmail, existing.get().getGitEmailHash())) {
            return statusOf(existing.get());
        }
        UserGitSetting setting = existing.orElseGet(() -> UserGitSetting.register(userId, hash));
        if (existing.isPresent()) {
            setting.replaceEmailHash(hash);
        }
        try {
            return statusOf(settings.saveAndFlush(setting));
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "uk_user_git_settings_email_hash".equals(violation.getConstraintName())) {
                    throw new BusinessException(ErrorCode.GIT_EMAIL_ALREADY_REGISTERED);
                }
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public GitIdentityStatusResponse status(Long userId) {
        requireUser(userId);
        return settings.findById(userId).map(GitIdentityService::statusOf)
                .orElseGet(() -> new GitIdentityStatusResponse(false, null));
    }

    @Transactional
    public GitIdentityStatusResponse delete(Long userId) {
        requireUser(userId);
        settings.deleteById(userId);
        return new GitIdentityStatusResponse(false, null);
    }

    @Transactional(readOnly = true)
    public AgentGitIdentityResponse agentIdentity(Long userId) {
        requireUser(userId);
        var setting = settings.findById(userId);
        return new AgentGitIdentityResponse(setting.isPresent(),
                setting.map(UserGitSetting::getGitEmailHash).orElse(null),
                "SHA-256", "TRIM_LOWERCASE");
    }

    @Transactional(readOnly = true)
    public GitIdentityVerificationResponse verify(Long userId, String gitEmail) {
        requireUser(userId);
        return settings.findById(userId)
                .map(setting -> new GitIdentityVerificationResponse(true,
                        GitEmailHasher.matches(gitEmail, setting.getGitEmailHash())))
                .orElseGet(() -> new GitIdentityVerificationResponse(false, false));
    }

    private static GitIdentityStatusResponse statusOf(UserGitSetting setting) {
        return new GitIdentityStatusResponse(true, setting.getRegisteredAt());
    }

    private static void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
