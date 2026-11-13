package com.devlink.server.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RememberLoginTokenRepository {
    private final JdbcTemplate jdbc;

    public RememberLoginTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String tokenHash, long userId, Instant expiresAt) {
        jdbc.update("""
                insert into remember_login_tokens (token_hash, user_id, expires_at)
                values (?, ?, ?)
                """, tokenHash, userId, Timestamp.from(expiresAt));
    }

    public Optional<Long> findActiveUserId(String tokenHash, Instant now) {
        return jdbc.query("""
                select user_id from remember_login_tokens
                where token_hash = ? and expires_at > ?
                """, (result, row) -> result.getLong("user_id"), tokenHash, Timestamp.from(now))
                .stream().findFirst();
    }

    public void delete(String tokenHash) {
        jdbc.update("delete from remember_login_tokens where token_hash = ?", tokenHash);
    }

    public void deleteExpired(Instant now) {
        jdbc.update("delete from remember_login_tokens where expires_at <= ?", Timestamp.from(now));
    }
}
