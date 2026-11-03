package com.devlink.server.service;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.task.TaskMessageItem;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/** Task별 위치를 압축·서명해 브로커에 보관하며 중앙 DB에는 진행 위치나 키를 저장하지 않는다. */
@Component
public class TaskMessageCursorCodec {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final ObjectMapper json;
    private static final int MAX_TOKEN_LENGTH = 65536;
    private static final int MAX_CONTENT_LENGTH = 1024 * 1024;
    private final byte[] signingKey;

    public TaskMessageCursorCodec(ObjectMapper json,
            @Value("${devlink.task-message-sync.cursor-secret:}") String secret) {
        this.json = json;
        this.signingKey = secret.getBytes(StandardCharsets.UTF_8);
        // 임시 키는 재시작 후 cursor를 깨뜨리므로 모든 인스턴스에 같은 설정값을 사용한다.
        if (secret.isBlank() || signingKey.length < 32) {
            throw new IllegalArgumentException("TASK_MESSAGE_CURSOR_SECRET에 32바이트 이상의 공통 서명키를 설정해주세요.");
        }
    }

    public Cursor decode(String value, AgentPrincipal agent) {
        if (value == null) {
            return new Cursor(2, agent.agentId(), agent.ownerUserId(), agent.teamId(), Map.of(), 0);
        }
        try {
            if (value.length() > MAX_TOKEN_LENGTH) throw new IllegalArgumentException();
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2 || parts[0].isEmpty()) throw new IllegalArgumentException();
            byte[] signature = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(signature, sign(parts[0]))) throw new IllegalArgumentException();
            byte[] content;
            // 서명 확인 뒤 압축을 풀고 크기도 제한해 외부 입력으로 메모리가 과도하게 늘지 않게 한다.
            try (var input = new GZIPInputStream(new ByteArrayInputStream(DECODER.decode(parts[0])))) {
                content = input.readNBytes(MAX_CONTENT_LENGTH + 1);
            }
            if (content.length > MAX_CONTENT_LENGTH) throw new IllegalArgumentException();
            Cursor cursor = json.readerFor(Cursor.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(content);
            validate(cursor);
            // 현재 Agent의 권한으로만 조회하며 다른 Agent의 정상 cursor도 재사용할 수 없다.
            if (cursor.agentId() != agent.agentId() || cursor.ownerUserId() != agent.ownerUserId()
                    || cursor.teamId() != agent.teamId()) {
                throw new BusinessException(ErrorCode.ACCESS_DENIED);
            }
            return cursor;
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    public String encode(Cursor cursor) {
        validate(cursor);
        try {
            byte[] content = json.writeValueAsBytes(cursor);
            if (content.length > MAX_CONTENT_LENGTH) throw new IllegalStateException("동기화 cursor 크기 한도를 초과했습니다.");
            var output = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(output)) {
                gzip.write(content);
            }
            String payload = ENCODER.encodeToString(output.toByteArray());
            String token = payload + "." + ENCODER.encodeToString(sign(payload));
            if (token.length() > MAX_TOKEN_LENGTH) throw new IllegalStateException("동기화 cursor 크기 한도를 초과했습니다.");
            return token;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("동기화 cursor를 만들 수 없습니다.", exception);
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("동기화 cursor 서명을 만들 수 없습니다.", exception);
        }
    }

    private void validate(Cursor cursor) {
        if (cursor == null || cursor.version() != 2 || cursor.agentId() <= 0
                || cursor.ownerUserId() <= 0 || cursor.teamId() <= 0
                || cursor.afterTaskId() < 0 || cursor.positions() == null
                || cursor.positions().entrySet().stream().anyMatch(entry -> entry.getKey() == null
                    || entry.getValue() == null || entry.getKey() <= 0 || entry.getValue() <= 0)
                || (cursor.afterTaskId() != 0 && !cursor.positions().containsKey(cursor.afterTaskId()))) {
            throw new IllegalArgumentException("올바르지 않은 동기화 cursor입니다.");
        }
    }

    /** sequence는 Task 안에서만 비교하며 다른 Task의 커밋으로 이 위치를 대신 진행하지 않는다. */
    public record Cursor(int version, long agentId, long ownerUserId, long teamId,
            Map<Long, Long> positions, long afterTaskId) {
        public Cursor {
            if (positions != null) positions = java.util.Collections.unmodifiableMap(new TreeMap<>(positions));
        }

        public Cursor advance(java.util.List<TaskMessageItem> items) {
            var next = new TreeMap<>(positions);
            long lastTask = afterTaskId;
            for (var item : items) {
                next.merge(item.taskId(), Long.parseLong(item.sequence()), Math::max);
                lastTask = item.taskId();
            }
            return new Cursor(version, agentId, ownerUserId, teamId, next, lastTask);
        }
    }
}
