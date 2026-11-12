package com.devlink.server.client;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.devlink.server.config.DecisionModelSettings;
import com.devlink.server.config.RoleDecisionConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영과 같은 JDK HTTP 클라이언트 구성({@link RoleDecisionConfig})으로 루프백 서버에 실제 요청을 보낸다.
 * 시간 제한이 응답 헤더 지연과 본문 지연 모두에서 호출을 끊는지 확인한다.
 */
class DecisionsApiRoleDecisionClientHttpTest {
    private static final String KEY = "synthetic-decision-key-never-print";
    /** 지연 시험용 짧은 제한. 정상 응답 시험은 느린 CI에서도 넘기지 않을 넉넉한 제한을 쓴다. */
    private static final long TIMEOUT_MS = 300;
    private static final long GENEROUS_TIMEOUT_MS = 2_000;
    private static final long STALL_MS = 5_000;
    /** 느린 CI를 감안한 여유. 서버가 멈춰 있는 시간보다는 충분히 짧다. */
    private static final long BOUND_MS = 2_500;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void realRequestCarriesTheKeyHeaderAndParsesAnswers() {
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        handle(exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("Authorization"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"answers\":{\"t0\":{\"type\":\"noul\",\"noul\":0.91,\"confidence\":0.91}}}");
        });

        assertThat(client(GENEROUS_TIMEOUT_MS).matchedTags("login 오류", List.of("로그인")))
                .containsExactly("로그인");
        assertThat(receivedKey.get()).isEqualTo("Bearer " + KEY);
        assertThat(receivedBody.get()).contains("\"state\":\"login 오류\"").doesNotContain(KEY);
    }

    @Test
    void keylessSelfHostedUrlIsCalledAsWrittenWithoutAnAuthorizationHeader() {
        AtomicReference<String> receivedTarget = new AtomicReference<>();
        AtomicReference<Boolean> hadAuthorization = new AtomicReference<>();
        server.createContext("/v1/decisions", exchange -> {
            try (exchange) {
                receivedTarget.set(exchange.getRequestURI().toString());
                hadAuthorization.set(exchange.getRequestHeaders().containsKey("Authorization"));
                exchange.getRequestBody().readAllBytes();
                respond(exchange, "{\"answers\":{\"t0\":{\"type\":\"noul\",\"noul\":0.91}}}");
            }
        });
        DecisionModelSettings settings = new DecisionModelSettings(true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/decisions?tenant=a%20b", "", false,
                "cloudflare/clef-flash", GENEROUS_TIMEOUT_MS, 0.7);
        RoleDecisionClient keyless = new RoleDecisionConfig().roleDecisionClient(settings, RestClient.builder(),
                new ObjectMapper());

        assertThat(keyless.matchedTags("login 오류", List.of("로그인"))).containsExactly("로그인");
        // 경로와 쿼리는 설정에 적은 그대로 간다.
        assertThat(receivedTarget.get()).isEqualTo("/v1/decisions?tenant=a%20b");
        assertThat(hadAuthorization.get()).isFalse();
    }

    @Test
    void stalledResponseHeadersAreCutOffAtTheTimeout() {
        handle(exchange -> {
            sleep(STALL_MS);
            respond(exchange, "{\"answers\":{\"t0\":{\"noul\":0.99}}}");
        });

        assertEmptyWithinBound();
    }

    @Test
    void stalledResponseBodyIsCutOffAtTheTimeout() throws InterruptedException {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        handle(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            body.write("{\"answers\":{\"t0\":".getBytes(StandardCharsets.UTF_8));
            body.flush();
            bodyStarted.countDown();
            sleep(STALL_MS);
            body.write("{\"noul\":0.99}}}".getBytes(StandardCharsets.UTF_8));
            body.close();
        });

        assertEmptyWithinBound();
        // 헤더 단계가 아니라 본문을 받는 도중에 끊겼는지 확인한다.
        assertThat(bodyStarted.await(0, TimeUnit.MILLISECONDS)).isTrue();
    }

    private void assertEmptyWithinBound() {
        RoleDecisionClient client = client(TIMEOUT_MS);
        long started = System.nanoTime();

        assertThat(client.matchedTags("login 오류", List.of("로그인"))).isEmpty();
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(BOUND_MS);
    }

    private RoleDecisionClient client(long timeoutMs) {
        DecisionModelSettings settings = new DecisionModelSettings(true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/api/alpha/decisions", KEY, true,
                "google/gemini-decision-1", timeoutMs, 0.7);
        return new RoleDecisionConfig().roleDecisionClient(settings, RestClient.builder(), new ObjectMapper());
    }

    private void handle(HttpHandler handler) {
        server.createContext("/api/alpha/decisions", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            } catch (IOException ignored) {
                // 클라이언트가 시간 제한으로 연결을 끊은 뒤의 쓰기 실패는 기대한 결과다.
            }
        });
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
