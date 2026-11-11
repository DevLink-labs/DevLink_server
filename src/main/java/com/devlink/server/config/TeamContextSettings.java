package com.devlink.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 팀·파트 Context 갱신기와 추론 클라이언트 설정이다.
 * base-url과 model이 비어 있으면 LLM 없이 구조 신호만으로 요약을 유지한다.
 */
@Component
public record TeamContextSettings(boolean enabled, String baseUrl, String apiKey, boolean requireApiKey,
        String model, long timeoutMs, int maxInputChars, int maxItems, int maxOutputTokens, String promptVersion,
        boolean openRouterProviderGuard) {
    private static final long LEASE_MARGIN_MS = 30_000;

    public TeamContextSettings(
            @Value("${devlink.team-context.enabled:true}") boolean enabled,
            @Value("${devlink.team-context.inference.base-url:}") String baseUrl,
            @Value("${devlink.team-context.inference.api-key:}") String apiKey,
            @Value("${devlink.team-context.inference.require-api-key:true}") boolean requireApiKey,
            @Value("${devlink.team-context.inference.model:}") String model,
            @Value("${devlink.team-context.inference.timeout-ms:60000}") long timeoutMs,
            @Value("${devlink.team-context.max-input-chars:6000}") int maxInputChars,
            @Value("${devlink.team-context.max-items:20}") int maxItems,
            @Value("${devlink.team-context.inference.max-output-tokens:2000}") int maxOutputTokens,
            @Value("${devlink.team-context.prompt-version:v1}") String promptVersion,
            @Value("${devlink.team-context.inference.openrouter-provider-guard:false}") boolean openRouterProviderGuard) {
        Assert.isTrue(timeoutMs > 0 && timeoutMs <= Long.MAX_VALUE - LEASE_MARGIN_MS,
                "inference timeout must leave room for the context lease margin");
        Assert.isTrue(maxInputChars >= 200, "max input chars must be at least 200");
        Assert.isTrue(maxItems > 0 && maxItems <= 100, "max items must be 1..100");
        Assert.isTrue(maxOutputTokens > 0, "max output tokens must be positive");
        Assert.hasText(promptVersion, "prompt version must not be blank");
        Assert.isTrue(promptVersion.length() <= 50, "prompt version must not exceed 50 characters");
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.requireApiKey = requireApiKey;
        this.model = model == null ? "" : model.trim();
        this.timeoutMs = timeoutMs;
        this.maxInputChars = maxInputChars;
        this.maxItems = maxItems;
        this.maxOutputTokens = maxOutputTokens;
        this.promptVersion = promptVersion;
        this.openRouterProviderGuard = openRouterProviderGuard;
    }

    /** 호스팅 API(Upstage 등)는 키가 있어야 켜진다. 자체 vLLM은 require-api-key를 false로 두면 키 없이 켜진다. */
    public boolean llmConfigured() {
        return !baseUrl.isEmpty() && !model.isEmpty() && (!requireApiKey || !apiKey.isEmpty());
    }

    /** 추론 제한에 입력 준비와 DB 저장 여유를 더한다. */
    public long leaseMillis() {
        return timeoutMs + LEASE_MARGIN_MS;
    }
}
