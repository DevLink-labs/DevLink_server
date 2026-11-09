package com.devlink.server.service;

import java.util.Locale;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.LoginRequest;
import com.devlink.server.dto.SignupRequest;
import com.devlink.server.dto.UserResponse;
import com.devlink.server.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.dummyPasswordHash = passwordEncoder.encode("DevLink-invalid-credentials-placeholder");
    }

    @Transactional
    public UserResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmailIgnoreCase(email)) {
            log.debug("Signup rejected: account already exists");
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        User user = User.create(email, request.name(), request.nickname(),
                passwordEncoder.encode(request.password()));
        try {
            return UserResponse.from(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            // 동시 가입으로 발생한 이메일 중복만 가입 오류로 변환
            if (isEmailConflict(exception)) {
                log.debug("Signup rejected: account uniqueness constraint conflict");
                throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public UserResponse login(LoginRequest request) {
        User user = users.findByEmail(normalizeEmail(request.email())).orElse(null);
        String storedPassword = user == null ? null : user.getPassword();
        String passwordHash = storedPassword == null ? dummyPasswordHash : storedPassword;
        String suppliedPassword = request.password();
        boolean matches;
        try {
            matches = passwordEncoder.matches(suppliedPassword == null ? "" : suppliedPassword, passwordHash);
        } catch (IllegalArgumentException exception) {
            // 저장된 비밀번호 형식 오류도 동일한 인증 실패로 처리
            log.debug("Login password verification could not complete");
            matches = false;
        }
        if (user == null || storedPassword == null || suppliedPassword == null || !matches) {
            log.debug("Login rejected: invalid credentials");
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(long userId) {
        return users.findById(userId).map(UserResponse::from)
                .orElseThrow(() -> {
                    log.debug("Current user lookup failed: account not found");
                    return new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
                });
    }

    private String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private boolean isEmailConflict(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uk_users_email".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
