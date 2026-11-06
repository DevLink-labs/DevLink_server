package com.devlink.server.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 추천 검색용 텍스트 정규화. 질문·역할 태그·파일 경로에 같은 규칙을 적용한다.
 * NFKC 정규화 → ASCII camelCase·약어 경계와 영문·한글 경계 분리 → 소문자화 → 문자·숫자 이외를 구분자로 처리한다.
 * 불용어 사전과 형태소 분석은 사용하지 않는다.
 */
public final class RecommendationTokenizer {
    private static final Pattern WORD_BOUNDARIES = Pattern.compile(
            "(?<=[a-z0-9])(?=[A-Z])"
                    + "|(?<=[A-Z])(?=[A-Z][a-z])"
                    + "|(?<=\\p{IsHangul})(?=\\p{IsLatin})"
                    + "|(?<=\\p{IsLatin})(?=\\p{IsHangul})");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern DIGITS_ONLY = Pattern.compile("\\p{N}+");
    private static final int MIN_PATH_TOKEN_CODE_POINTS = 2;

    private RecommendationTokenizer() {
    }

    /** 질문·태그용 토큰 목록. 순서를 보존하며 빈 토큰은 제외한다. */
    public static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        String separated = WORD_BOUNDARIES.matcher(normalized).replaceAll(" ");
        String lowered = separated.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String token : SEPARATORS.split(lowered)) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    /**
     * 파일 경로용 토큰 집합. 디렉터리 세그먼트와 확장자를 뺀 파일명 본체를 토큰화한다.
     * 길이 2 코드포인트 미만과 숫자만인 토큰은 경로 매칭에서 제외한다.
     */
    public static Set<String> tokenizePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Set.of();
        }
        String[] segments = relativePath.split("/");
        Set<String> tokens = new LinkedHashSet<>();
        for (int i = 0; i < segments.length; i++) {
            String segment = i == segments.length - 1 ? stripExtension(segments[i]) : segments[i];
            for (String token : tokenize(segment)) {
                if (isPathMatchable(token)) {
                    tokens.add(token);
                }
            }
        }
        return Collections.unmodifiableSet(tokens);
    }

    /** 질문 토큰 중 경로 매칭에 쓸 수 있는 토큰만 남긴다. */
    public static Set<String> pathMatchableTokens(List<String> questionTokens) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : questionTokens) {
            if (isPathMatchable(token)) {
                tokens.add(token);
            }
        }
        return Collections.unmodifiableSet(tokens);
    }

    private static boolean isPathMatchable(String token) {
        return token.codePointCount(0, token.length()) >= MIN_PATH_TOKEN_CODE_POINTS
                && !DIGITS_ONLY.matcher(token).matches();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        // ".gitignore"처럼 점으로 시작하는 이름은 확장자가 없는 것으로 본다.
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
