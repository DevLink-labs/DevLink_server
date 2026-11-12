package com.devlink.server.config;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.devlink.server.client.OpenAiCompatibleTeamContextInferenceClient;
import com.devlink.server.client.TeamContextInferenceClient;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.Hints;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.NewEvent;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정이 만든 실제 HTTP 클라이언트가 평문 http 주소로 보내는 요청을 확인한다.
 * JDK 클라이언트의 기본값(HTTP/2)은 평문 주소에 h2c 업그레이드 헤더를 붙인다. vLLM의 서버(uvicorn)는 그런 요청의 본문을
 * 앱에 넘기지 않고, vLLM은 빈 본문을 400("Field required")으로 거절한다. 실제 vLLM 0.32.0에서 관찰했다(S15P31D108-246).
 */
class TeamContextInferenceTransportTest {
    @Test
    void plainHttpEndpointGetsAnHttp11RequestWithoutAnUpgradeHeader() throws Exception {
        AtomicReference<Headers> headers = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            headers.set(exchange.getRequestHeaders());
            protocol.set(exchange.getProtocol());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                     "content":"{\\"common\\":[],\\"changed\\":[],\\"issues\\":[]}"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            TeamContextSettings settings = new TeamContextSettings(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "", false, "local-model", 5000, 6000, 20,
                    6000, "v1", false);
            ObjectMapper mapper = new ObjectMapper();
            TeamContextInferenceClient client = new TeamContextInferenceConfig()
                    .teamContextInferenceClient(settings, RestClient.builder(), mapper);

            assertThat(client).isInstanceOf(OpenAiCompatibleTeamContextInferenceClient.class);
            assertThat(client.infer(request())).isEqualTo(TeamContextSummary.empty());

            // 업그레이드 시도도 HTTP/1.1 요청으로 오므로 수정 여부는 아래 두 헤더가 가른다. 프로토콜은 서버가 본 값의 기록이다.
            assertThat(protocol.get()).isEqualTo("HTTP/1.1");
            assertThat(headers.get().getFirst("Upgrade")).as("h2c upgrade header").isNull();
            assertThat(headers.get().getFirst("HTTP2-Settings")).isNull();
            assertThat(headers.get().getFirst("Authorization")).isNull();
            assertThat(mapper.readTree(body.get()).path("model").asText()).isEqualTo("local-model");
        } finally {
            server.stop(0);
        }
    }

    private static TeamContextInferenceRequest request() {
        return new TeamContextInferenceRequest(1L, AgentPart.BACKEND, AgentPart.FRONTEND, 0,
                TeamContextSummary.empty(),
                new NewEvent(7L, 4L, "7:4", "ANSWER", AgentPart.BACKEND, "IN_PROGRESS", "로그인 응답", "항상 채움"),
                new Hints(List.of("delivery"), List.of(), 1, 1, false));
    }
}
