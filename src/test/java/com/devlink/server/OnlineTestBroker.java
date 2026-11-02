package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import com.devlink.server.repository.AgentRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Real authenticated receiver prerequisite for HTTP creation/auth tests. */
final class OnlineTestBroker implements AutoCloseable {
    private final HttpClient client;
    private final WebSocket socket;
    private OnlineTestBroker(HttpClient client, WebSocket socket) { this.client = client; this.socket = socket; }

    static OnlineTestBroker connect(int port, String token, long agentId, AgentRepository agents) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        WebSocket socket = null;
        try {
            CompletableFuture<String> ready = new CompletableFuture<>();
            socket = client.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                    .header("Origin", "http://localhost:3000")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), new WebSocket.Listener() {
                        private final StringBuilder text = new StringBuilder();
                        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
                        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            text.append(data);
                            if (last) { ready.complete(text.toString()); text.setLength(0); }
                            webSocket.request(1);
                            return null;
                        }
                        @Override public void onError(WebSocket webSocket, Throwable error) { ready.completeExceptionally(error); }
                    }).get(5, TimeUnit.SECONDS);
            assertThat(ready.get(5, TimeUnit.SECONDS)).contains("BROKER_READY");
            socket.sendText("{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"ONLINE\"}}", true).get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(agents.findById(agentId).orElseThrow().getStatus().name()).isEqualTo("ONLINE"));
            return new OnlineTestBroker(client, socket);
        } catch (Exception | AssertionError error) {
            if (socket != null) socket.abort();
            client.close();
            throw error;
        }
    }
    @Override public void close() { socket.abort(); client.close(); }
}
