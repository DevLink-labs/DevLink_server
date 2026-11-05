package com.devlink.server.domain.gitsetting;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: identity.
/** 사용자별 Git 작성자 이메일 등록. 정규화와 본인 대조값 생성은 서비스에서 처리한다. */
@Entity
@Table(name = "user_git_settings", uniqueConstraints = @UniqueConstraint(name = "uk_user_git_settings_email", columnNames = {"git_email"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserGitSetting {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "git_email", length = 255, nullable = false)
    private String gitEmail;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    public static UserGitSetting register(Long userId, String gitEmail) {
        requireValue(userId, "userId");
        requireValue(gitEmail, "gitEmail");
        UserGitSetting entity = new UserGitSetting();
        entity.userId = userId;
        entity.gitEmail = gitEmail;
        return entity;
    }

    /** 이메일을 교체하면 등록 시각도 갱신한다. */
    public void replaceEmail(String gitEmail) {
        requireValue(gitEmail, "gitEmail");
        this.gitEmail = gitEmail;
        this.registeredAt = Instant.now();
    }

    @PrePersist
    private void initializeTimestamp() {
        if (this.registeredAt == null) {
            this.registeredAt = Instant.now();
        }
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
