package com.devlink.server.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;
import com.devlink.server.repository.RememberLoginTokenRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.WebUtils;

@Service
public class RememberLoginService {
    private static final String COOKIE_NAME = "DEVLINK_REMEMBER_ME";
    private static final Duration VALIDITY = Duration.ofDays(14);
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final RememberLoginTokenRepository tokens;
    private final boolean secureCookie;
    private final String sameSite;
    private final SecureRandom random = new SecureRandom();

    public RememberLoginService(RememberLoginTokenRepository tokens, ServerProperties serverProperties) {
        this.tokens = tokens;
        var sessionCookie = serverProperties.getServlet().getSession().getCookie();
        this.secureCookie = Boolean.TRUE.equals(sessionCookie.getSecure());
        this.sameSite = sessionCookie.getSameSite() == null ? "Lax" : sessionCookie.getSameSite().attributeValue();
    }

    @Transactional
    public Optional<ResponseCookie> onPasswordLogin(long userId, boolean rememberMe, HttpServletRequest request) {
        Instant now = Instant.now();
        if (rememberMe) {
            tokens.deleteExpired(now);
        }
        var previousCookie = WebUtils.getCookie(request, COOKIE_NAME);
        if (previousCookie != null) {
            deleteToken(previousCookie.getValue());
        }
        if (!rememberMe) {
            return previousCookie == null ? Optional.empty() : Optional.of(cookie(request, "", Duration.ZERO));
        }

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(hash(token), userId, now.plus(VALIDITY));
        return Optional.of(cookie(request, token, VALIDITY));
    }

    public Optional<Long> restoreUserId(HttpServletRequest request, HttpServletResponse response) {
        var cookie = WebUtils.getCookie(request, COOKIE_NAME);
        if (cookie == null) {
            return Optional.empty();
        }

        String token = cookie.getValue();
        Optional<Long> userId = TOKEN_FORMAT.matcher(token).matches()
                ? tokens.findActiveUserId(hash(token), Instant.now()) : Optional.empty();
        if (userId.isEmpty()) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO).toString());
        }
        return userId;
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        var cookie = WebUtils.getCookie(request, COOKIE_NAME);
        if (cookie != null) {
            deleteToken(cookie.getValue());
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(request, "", Duration.ZERO).toString());
        }
    }

    private void deleteToken(String token) {
        if (TOKEN_FORMAT.matcher(token).matches()) {
            tokens.delete(hash(token));
        }
    }

    private ResponseCookie cookie(HttpServletRequest request, String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookie || request.isSecure())
                .sameSite(sameSite)
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
