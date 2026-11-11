package com.devlink.server.dto.teamcontext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 추론 결과이자 저장 형태다. 세 배열은 의미 수준이며 {@code evidence}는 "taskId:eventId" 좌표다.
 * 구조 신호는 정의가 아니라 추론 힌트일 뿐이므로 여기에는 들어오지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TeamContextSummary(List<Common> common, List<Changed> changed, List<Issue> issues) {
    public TeamContextSummary {
        common = withoutNulls(common);
        changed = withoutNulls(changed);
        issues = withoutNulls(issues);
    }

    public static TeamContextSummary empty() {
        return new TeamContextSummary(List.of(), List.of(), List.of());
    }

    /** 공백 항목 제거, 양끝 공백 제거, 길이·개수 상한. 추론 출력이 길거나 비어 있어도 저장 가능한 형태로 만든다. */
    public TeamContextSummary normalized(int maxItems, int maxChars) {
        List<Common> commonItems = new ArrayList<>();
        for (Common item : common) {
            String statement = clip(item.statement(), maxChars);
            if (statement != null && commonItems.size() < maxItems) {
                commonItems.add(new Common(statement, evidence(item.evidence(), maxItems)));
            }
        }
        List<Changed> changedItems = new ArrayList<>();
        for (Changed item : changed) {
            String before = clip(item.before(), maxChars);
            String after = clip(item.after(), maxChars);
            if (after != null && changedItems.size() < maxItems) {
                changedItems.add(new Changed(before == null ? "" : before, after, evidence(item.evidence(), maxItems)));
            }
        }
        List<Issue> issueItems = new ArrayList<>();
        for (Issue item : issues) {
            String topic = clip(item.topic(), maxChars);
            if (topic == null || issueItems.size() >= maxItems) {
                continue;
            }
            List<Position> positions = new ArrayList<>();
            for (Position position : item.positions()) {
                String part = clip(position.part(), 16);
                String text = clip(position.position(), maxChars);
                if (part != null && text != null && positions.size() < maxItems) {
                    positions.add(new Position(part.toUpperCase(), text));
                }
            }
            issueItems.add(new Issue(topic, positions, evidence(item.evidence(), maxItems)));
        }
        return new TeamContextSummary(commonItems, changedItems, issueItems);
    }

    private static List<String> evidence(List<String> values, int maxItems) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String clipped = clip(value, 64);
            if (clipped != null && unique.size() < maxItems) {
                unique.add(clipped);
            }
        }
        return List.copyOf(unique);
    }

    private static String clip(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.codePointCount(0, trimmed.length()) <= maxChars
                ? trimmed
                : trimmed.substring(0, trimmed.offsetByCodePoints(0, maxChars));
    }

    /** 추론 출력의 null 원소는 버린다. List.copyOf는 null을 거부하므로 역직렬화 실패로 번지지 않게 한다. */
    private static <T> List<T> withoutNulls(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Common(String statement, List<String> evidence) {
        public Common {
            evidence = withoutNulls(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Changed(String before, String after, List<String> evidence) {
        public Changed {
            evidence = withoutNulls(evidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Issue(String topic, List<Position> positions, List<String> evidence) {
        public Issue {
            positions = withoutNulls(positions);
            evidence = withoutNulls(evidence);
        }

        public boolean cites(String reference) {
            return evidence.stream().anyMatch(item -> Objects.equals(item, reference));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Position(String part, String position) { }
}
