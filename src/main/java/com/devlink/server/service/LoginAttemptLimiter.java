package com.devlink.server.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import com.devlink.server.exception.LoginRateLimitException;
import org.springframework.stereotype.Component;

@Component
public class LoginAttemptLimiter {
    private static final long IP_WINDOW = Duration.ofMinutes(1).toNanos();
    private static final long EMAIL_WINDOW = Duration.ofMinutes(5).toNanos();
    private static final int IP_LIMIT = 60;
    private static final int EMAIL_LIMIT = 10;
    private static final int SERVER_LIMIT = 120;
    private static final int CONCURRENT_LIMIT = 2;
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private final Map<String, Window> byIp = new HashMap<>();
    private final Map<String, Window> byEmail = new HashMap<>();
    private final LongSupplier nanoTime;
    private Window serverWindow;
    private int inProgress;

    public LoginAttemptLimiter() {
        this(System::nanoTime);
    }

    LoginAttemptLimiter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public <T> T verify(String remoteAddress, String normalizedEmail, Supplier<T> verifyCredentials) {
        reserve(ipKey(remoteAddress), normalizedEmail);
        try {
            return verifyCredentials.get();
        } finally {
            release();
        }
    }

    private synchronized void reserve(String ip, String email) {
        long now = nanoTime.getAsLong();
        if (serverWindow == null || serverWindow.expired(now)) {
            // 전체 처리 한도로 새 키의 유입량 제한, 한도 갱신 시 만료 키 정리
            byIp.values().removeIf(window -> window.expired(now));
            byEmail.values().removeIf(window -> window.expired(now));
            serverWindow = new Window(now + IP_WINDOW);
        }

        Window ipWindow = byIp.get(ip);
        Window emailWindow = byEmail.get(email);
        long wait = Math.max(serverWindow.waitNanos(now, SERVER_LIMIT),
                Math.max(waitNanos(ipWindow, now, IP_LIMIT), waitNanos(emailWindow, now, EMAIL_LIMIT)));
        if (wait > 0) {
            throw new LoginRateLimitException(TimeUnit.NANOSECONDS.toSeconds(wait - 1) + 1);
        }
        if (inProgress >= CONCURRENT_LIMIT) {
            throw new LoginRateLimitException(1);
        }

        count(byIp, ip, ipWindow, now, IP_WINDOW);
        count(byEmail, email, emailWindow, now, EMAIL_WINDOW);
        serverWindow.attempts++;
        inProgress++;
    }

    private synchronized void release() {
        inProgress--;
    }

    private static long waitNanos(Window window, long now, int limit) {
        return window == null ? 0 : window.waitNanos(now, limit);
    }

    private static void count(Map<String, Window> windows, String key, Window window, long now, long duration) {
        if (window == null || window.expired(now)) {
            window = new Window(now + duration);
            windows.put(key, window);
        }
        window.attempts++;
    }

    private static String ipKey(String address) {
        if (address.indexOf(':') < 0) {
            return address;
        }
        String literal = address;
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (literal.indexOf(':') < 0 || !IPV6_LITERAL.matcher(literal).matches()) {
            return address;
        }
        try {
            InetAddress ip = InetAddress.getByName(literal);
            byte[] bytes = ip.getAddress();
            // 같은 IPv6 /64에서 주소를 바꿔도 동일한 시도 한도 적용
            return bytes.length == 16 ? "ipv6:" + HexFormat.of().formatHex(bytes, 0, 8) : ip.getHostAddress();
        } catch (UnknownHostException exception) {
            return address;
        }
    }

    private static final class Window {
        private final long expiresAt;
        private int attempts;

        private Window(long expiresAt) {
            this.expiresAt = expiresAt;
        }

        private boolean expired(long now) {
            return now - expiresAt >= 0;
        }

        private long waitNanos(long now, int limit) {
            return attempts >= limit && !expired(now) ? expiresAt - now : 0;
        }
    }
}
