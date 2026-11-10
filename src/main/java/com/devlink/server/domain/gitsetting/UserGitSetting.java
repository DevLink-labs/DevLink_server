package com.devlink.server.domain.gitsetting;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: identity.
/** 사용자별 Git 작성자 이메일의 정규화된 SHA-256 해시 저장 */
@Entity
@Table(name = "user_git_settings", uniqueConstraints = @UniqueConstraint(name = "uk_user_git_settings_email_hash", columnNames = {"git_email_hash"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserGitSetting {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "git_email_hash", length = 64, nullable = false)
    private String gitEmailHash;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    public static UserGitSetting register(Long userId, String gitEmailHash) {
        requireValue(userId, "userId");
        requireHash(gitEmailHash);
        UserGitSetting entity = new UserGitSetting();
        entity.userId = userId;
        entity.gitEmailHash = gitEmailHash;
        return entity;
    }

    /** 이메일 해시 교체 시 등록 시각 갱신 */
    public void replaceEmailHash(String gitEmailHash) {
        requireHash(gitEmailHash);
        this.gitEmailHash = gitEmailHash;
        this.registeredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @PrePersist
    private void initializeTimestamp() {
        if (this.registeredAt == null) {
            this.registeredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }

    private static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("gitEmailHash must be a lowercase SHA-256 hash");
        }
    }
}
