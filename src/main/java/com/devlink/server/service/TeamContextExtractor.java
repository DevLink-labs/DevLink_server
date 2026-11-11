package com.devlink.server.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 작업·질문·답변 텍스트에서 API 경로, 파일 경로, 백틱 식별자, HTTP 상태, 대문자 코드를 뽑는다.
 * 결정적 순수 함수이며 결과는 추론 힌트로만 쓴다. 공통 맥락·쟁점의 정의가 아니다.
 */
@Component
public class TeamContextExtractor {
    static final int MAX_ENTITIES = 40;
    private static final Pattern API_PATH = Pattern.compile(
            "(?<![\\w/.])/(?:api|v\\d+|ws)(?:/[A-Za-z0-9_.{}:-]+)+");
    private static final Pattern FILE_PATH = Pattern.compile(
            "(?<![\\w/.])(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+\\."
            + "(?:java|kt|kts|ts|tsx|js|jsx|mjs|rs|py|sql|ya?ml|json|md|css|scss|html|vue|go|toml|gradle)\\b");
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\r\\n]{1,120})`");
    private static final Pattern HTTP_STATUS = Pattern.compile(
            "(?i)\\bhttp\\s*([1-5]\\d{2})\\b"
            + "|\\b([1-5]\\d{2})\\b(?=\\s*(?:error|response|status|code|응답|에러|오류|상태|코드|로|으로|를|을|이|가|인지|대신))");
    private static final Pattern CODE = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+\\b");

    public List<String> extract(String... texts) {
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            collect(entities, API_PATH.matcher(text), 0, null);
            collect(entities, FILE_PATH.matcher(text), 0, null);
            collect(entities, BACKTICK.matcher(text), 1, null);
            Matcher status = HTTP_STATUS.matcher(text);
            while (status.find() && entities.size() < MAX_ENTITIES) {
                String code = status.group(1) != null ? status.group(1) : status.group(2);
                entities.add("HTTP " + code);
            }
            collect(entities, CODE.matcher(text), 0, null);
        }
        return List.copyOf(entities);
    }

    private static void collect(LinkedHashSet<String> entities, Matcher matcher, int group, String prefix) {
        while (matcher.find() && entities.size() < MAX_ENTITIES) {
            String value = matcher.group(group).strip();
            if (!value.isEmpty()) {
                entities.add(prefix == null ? value : prefix + value);
            }
        }
    }
}
