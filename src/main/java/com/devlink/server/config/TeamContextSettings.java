package com.devlink.server.config;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 팀·파트 Context 갱신기와 추론 클라이언트 설정이다.
 * 기본 대상은 OpenRouter다. base-url과 model이 비어 있거나 필요한 키가 없으면 LLM 없이 구조 신호만으로 요약을 유지한다.
 */
@Component
public record TeamContextSettings(boolean enabled, String baseUrl, String apiKey, boolean requireApiKey,
        String model, long timeoutMs, int maxInputChars, int maxItems, int maxOutputTokens, String promptVersion,
        boolean openRouterProviderGuard) {
    /** application.yml의 기본 모델. OpenRouter 전용 ID라 다른 제공자에 그대로 가면 실패한다. */
    public static final String DEFAULT_OPENROUTER_MODEL = "upstage/solar-pro4";
    private static final long LEASE_MARGIN_MS = 30_000;

    /**
     * 설정에서 읽는 생성자다.
     * 키는 api-key(OPENROUTER_API_KEY)를 쓰고, 비어 있으면 이전 이름의 legacy-api-key(UPSTAGE_API_KEY)를 쓴다.
     * Compose가 빈 값으로 넘긴 변수도 "없음"으로 다루려고 여기서 고른다.
     * 제공자 필터는 auto(기본)면 주소가 OpenRouter일 때만 켜고, true·false면 주소와 무관하게 그 값을 따른다.
     */
    @Autowired
    public TeamContextSettings(
            @Value("${devlink.team-context.enabled:true}") boolean enabled,
            @Value("${devlink.team-context.inference.base-url:}") String baseUrl,
            @Value("${devlink.team-context.inference.api-key:}") String apiKey,
            @Value("${devlink.team-context.inference.legacy-api-key:}") String legacyApiKey,
            @Value("${devlink.team-context.inference.require-api-key:true}") boolean requireApiKey,
            @Value("${devlink.team-context.inference.model:}") String model,
            @Value("${devlink.team-context.inference.timeout-ms:60000}") long timeoutMs,
            @Value("${devlink.team-context.max-input-chars:6000}") int maxInputChars,
            @Value("${devlink.team-context.max-items:20}") int maxItems,
            @Value("${devlink.team-context.inference.max-output-tokens:6000}") int maxOutputTokens,
            @Value("${devlink.team-context.prompt-version:v1}") String promptVersion,
            @Value("${devlink.team-context.inference.openrouter-provider-guard:auto}") String openRouterProviderGuard) {
        this(enabled, baseUrl, apiKey == null || apiKey.isBlank() ? legacyApiKey : apiKey, requireApiKey, model, timeoutMs,
                maxInputChars, maxItems, maxOutputTokens, promptVersion, providerGuard(openRouterProviderGuard, baseUrl));
    }

    public TeamContextSettings {
        Assert.isTrue(timeoutMs > 0 && timeoutMs <= Long.MAX_VALUE - LEASE_MARGIN_MS,
                "inference timeout must leave room for the context lease margin");
        Assert.isTrue(maxInputChars >= 200, "max input chars must be at least 200");
        Assert.isTrue(maxItems > 0 && maxItems <= 100, "max items must be 1..100");
        Assert.isTrue(maxOutputTokens > 0, "max output tokens must be positive");
        Assert.hasText(promptVersion, "prompt version must not be blank");
        Assert.isTrue(promptVersion.length() <= 50, "prompt version must not exceed 50 characters");
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
    }

    private static boolean providerGuard(String value, String baseUrl) {
        String mode = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        Assert.isTrue(mode.equals("auto") || mode.equals("true") || mode.equals("false"),
                "openrouter-provider-guard must be auto, true or false");
        return mode.equals("auto") ? openRouterHost(baseUrl) : mode.equals("true");
    }

    /** 호스트가 openrouter.ai이거나 그 하위 도메인인지. 프록시나 게이트웨이 뒤의 OpenRouter는 알 수 없다. */
    private static boolean openRouterHost(String baseUrl) {
        if (baseUrl == null) {
            return false;
        }
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            return host.equals("openrouter.ai") || host.endsWith(".openrouter.ai");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 주소가 OpenRouter인지. */
    public boolean openRouter() {
        return openRouterHost(baseUrl);
    }

    /**
     * 키가 지금 주소의 것이 아닌지. 두 경우다.
     * 주소가 OpenRouter인데 키가 OpenRouter 키 형식(sk-or-)이 아닌 경우: 기본 주소가 Upstage에서 OpenRouter로 바뀌면서,
     * 주소를 지정하지 않고 다른 제공자의 키만 둔 배포가 이 상태가 된다.
     * 주소가 OpenRouter가 아닌데 키가 OpenRouter 키인 경우: 제공자를 바꾸면서 OPENROUTER_API_KEY를 남겨 둔 배포다.
     * 제공자 필터를 명시적으로 켠 구성은 OpenRouter 앞의 프록시로 보고 허용한다.
     * 어느 쪽이든 그 키를 다른 업체로 보내지 않는다.
     */
    public boolean keyBelongsElsewhere() {
        if (apiKey.isEmpty()) {
            return false;
        }
        boolean openRouterKey = apiKey.startsWith("sk-or-");
        return openRouter() ? !openRouterKey : openRouterKey && !openRouterProviderGuard;
    }

    /**
     * OpenRouter는 키가 있어야 켜진다. 다른 주소는 require-api-key를 false로 두면 키 없이 켜진다(자체 vLLM).
     * 키가 지금 주소의 것이 아닌 구성은 켜지 않는다.
     */
    public boolean llmConfigured() {
        boolean keyRequired = requireApiKey || openRouter();
        return !baseUrl.isEmpty() && !model.isEmpty() && (!keyRequired || !apiKey.isEmpty()) && !keyBelongsElsewhere();
    }

    /** 추론 제한에 입력 준비와 DB 저장 여유를 더한다. */
    public long leaseMillis() {
        return timeoutMs + LEASE_MARGIN_MS;
    }

    /** 로그나 오류 메시지에 키가 섞이지 않도록 키 값은 출력하지 않는다. */
    @Override
    public String toString() {
        return "TeamContextSettings[enabled=" + enabled + ", baseUrl=" + baseUrl + ", apiKey="
                + (apiKey.isEmpty() ? "<empty>" : "<set>") + ", requireApiKey=" + requireApiKey + ", model=" + model
                + ", timeoutMs=" + timeoutMs + ", maxInputChars=" + maxInputChars + ", maxItems=" + maxItems
                + ", maxOutputTokens=" + maxOutputTokens + ", promptVersion=" + promptVersion
                + ", openRouterProviderGuard=" + openRouterProviderGuard + "]";
    }
}
