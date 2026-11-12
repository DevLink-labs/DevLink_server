package com.devlink.server.config;

import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/**
 * 담당 Agent 추천의 역할 태그 판정을 보강하는 결정 모델 설정이다.
 * 기본은 꺼짐이다. 기본 주소는 OpenRouter Decisions API이고 키가 있어야 켜진다.
 * 같은 요청 형식을 받는 자체 호스팅 서버는 url을 바꾸고 require-api-key를 false로 두면 키 없이 쓴다.
 * 범위 검증은 다른 설정과 같이 기능이 꺼져 있어도 시작 시점에 한다.
 */
@Component
public record DecisionModelSettings(boolean enabled, String url, String apiKey, boolean requireApiKey, String model,
        long timeoutMs, double threshold) {
    /**
     * 브로커의 중앙 호출 제한(5초) 안에서 추천 계산 시간을 남기기 위한 상한이다.
     * 모델이 시간 초과하면 이 값만큼 응답이 늦어지므로, 나머지 계산에 3초를 남긴다.
     */
    static final long MAX_TIMEOUT_MS = 2000;

    public DecisionModelSettings(
            @Value("${devlink.recommendation.decision-model.enabled:false}") boolean enabled,
            @Value("${devlink.recommendation.decision-model.url:https://openrouter.ai/api/alpha/decisions}") String url,
            @Value("${devlink.recommendation.decision-model.api-key:}") String apiKey,
            @Value("${devlink.recommendation.decision-model.require-api-key:true}") boolean requireApiKey,
            @Value("${devlink.recommendation.decision-model.model:google/gemini-decision-1}") String model,
            @Value("${devlink.recommendation.decision-model.timeout-ms:1500}") long timeoutMs,
            @Value("${devlink.recommendation.decision-model.threshold:0.7}") double threshold) {
        Assert.isTrue(timeoutMs > 0 && timeoutMs <= MAX_TIMEOUT_MS, "decision model timeout must be 1..2000 ms");
        Assert.isTrue(threshold > 0 && threshold < 1, "decision model threshold must be between 0 and 1");
        String endpoint = url == null ? "" : url.trim();
        String key = apiKey == null ? "" : apiKey.trim();
        Assert.isTrue(endpoint.isEmpty() || acceptable(endpoint, !key.isEmpty()),
                "decision model url must be https; http is accepted on loopback, or on any host when no API key is set");
        this.enabled = enabled;
        this.url = endpoint;
        this.apiKey = key;
        this.requireApiKey = requireApiKey;
        this.model = model == null ? "" : model.trim();
        this.timeoutMs = timeoutMs;
        this.threshold = threshold;
    }

    /**
     * 키를 헤더로 보내므로 키가 있으면 https만 받는다. 평문은 호스트가 정확히 localhost·127.0.0.1·[::1]일 때와,
     * 보낼 키가 없는 자체 호스팅 구성일 때만 허용한다. 접두사 비교로는 localhost.example.net이나
     * localhost@example.net을 걸러내지 못해 호스트를 파싱해 비교한다.
     */
    private static boolean acceptable(String url, boolean sendsKey) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        return !sendsKey || host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }

    /** 켜져 있고 주소·모델이 있으며, 키가 필요한 구성이면 키도 있을 때만 모델을 호출한다. */
    public boolean configured() {
        return enabled && !url.isEmpty() && !model.isEmpty() && (!requireApiKey || !apiKey.isEmpty());
    }

    /** 로그나 오류 메시지에 키가 섞이지 않도록 키 값은 출력하지 않는다. */
    @Override
    public String toString() {
        return "DecisionModelSettings[enabled=" + enabled + ", url=" + url + ", apiKey="
                + (apiKey.isEmpty() ? "<empty>" : "<set>") + ", requireApiKey=" + requireApiKey + ", model=" + model
                + ", timeoutMs=" + timeoutMs + ", threshold=" + threshold + "]";
    }
}
