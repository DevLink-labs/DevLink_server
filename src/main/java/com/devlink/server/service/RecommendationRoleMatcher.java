package com.devlink.server.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.devlink.server.domain.agent.Agent;

/**
 * 질문 토큰에서 Agent 역할 태그를 찾는다.
 * 명시 태그가 비어 있으면 역할 설명을 구분자로 나눈 fallback 태그를 사용한다.
 * 태그 토큰 연속열이 질문에 그대로 나타나면 일치하며, 마지막 태그 토큰이 한글 음절뿐이면 조사 하나가 붙은 형태도 허용한다.
 */
public final class RecommendationRoleMatcher {
    static final int MAX_TAGS = 10;
    private static final Pattern DESCRIPTION_SEPARATORS = Pattern.compile("[,;/|\r\n]+");
    private static final Pattern HANGUL_SYLLABLES_ONLY = Pattern.compile("[\uAC00-\uD7A3]+");
    private static final Set<String> PARTICLES = Set.of(
            "을", "를", "이", "가", "은", "는", "의", "에", "에서", "으로", "로", "과", "와", "도");

    private RecommendationRoleMatcher() {
    }

    /** 질문과 일치한 표시 태그 목록. 같은 태그는 한 번만 센다. */
    public static List<String> matchedRoles(Agent agent, List<String> questionTokens) {
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, List<String>> tag : candidateTags(agent).entrySet()) {
            if (containsSequence(questionTokens, tag.getValue())) {
                matched.add(tag.getKey());
            }
        }
        return List.copyOf(matched);
    }

    /** 표시 태그 → 정규화 토큰. 정규화 결과가 같은 태그는 먼저 나온 것만 남기고 최대 10개를 사용한다. */
    static Map<String, List<String>> candidateTags(Agent agent) {
        List<String> rawTags = agent.getRoles().isEmpty()
                ? splitDescription(agent.getRoleDescription())
                : agent.getRoles();
        Map<String, List<String>> tags = new LinkedHashMap<>();
        Set<List<String>> seen = new HashSet<>();
        for (String raw : rawTags) {
            if (raw == null) {
                continue;
            }
            String display = raw.strip();
            List<String> tokens = RecommendationTokenizer.tokenize(display);
            if (display.isEmpty() || tokens.isEmpty() || !seen.add(tokens)) {
                continue;
            }
            tags.put(display, tokens);
            if (tags.size() >= MAX_TAGS) {
                break;
            }
        }
        return tags;
    }

    private static List<String> splitDescription(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        return List.of(DESCRIPTION_SEPARATORS.split(description));
    }

    static boolean containsSequence(List<String> question, List<String> tag) {
        if (tag.isEmpty() || question.size() < tag.size()) {
            return false;
        }
        String last = tag.get(tag.size() - 1);
        boolean allowParticle = HANGUL_SYLLABLES_ONLY.matcher(last).matches();
        for (int start = 0; start + tag.size() <= question.size(); start++) {
            if (matchesAt(question, start, tag, last, allowParticle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAt(List<String> question, int start, List<String> tag,
            String last, boolean allowParticle) {
        for (int i = 0; i < tag.size() - 1; i++) {
            if (!question.get(start + i).equals(tag.get(i))) {
                return false;
            }
        }
        String word = question.get(start + tag.size() - 1);
        if (word.equals(last)) {
            return true;
        }
        return allowParticle && word.startsWith(last)
                && PARTICLES.contains(word.substring(last.length()));
    }
}
