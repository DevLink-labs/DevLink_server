package com.devlink.server.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public final class GitEmailHasher {
    private GitEmailHasher() {
    }

    public static String hash(String email) {
        String normalized = Objects.requireNonNull(email, "gitEmail").trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    public static boolean matches(String email, String expectedHash) {
        if (email == null || expectedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(hash(email).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
